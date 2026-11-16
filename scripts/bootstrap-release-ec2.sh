#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# 최초 이관용이다. 중간 실패 후 동일한 운영 파일로 재실행할 수 있다.
# 컨테이너·볼륨·.env는 변경하지 않는다.
BASE_DIR=/opt/myshelf
CURRENT="$BASE_DIR/current"
exec 9>"$BASE_DIR/.deployment.lock"
flock -n 9 || { echo 'ERROR: Another deployment or bootstrap is running' >&2; exit 1; }
[[ -f "$BASE_DIR/.env" ]] || { echo 'ERROR: .env missing' >&2; exit 1; }
for f in compose.yaml compose.prod.yaml deploy-ec2.sh; do
  [[ -f "$BASE_DIR/$f" && ! -L "$BASE_DIR/$f" ]] || { echo "ERROR: Invalid existing file: $f" >&2; exit 1; }
done
[[ $(grep -c '^MYSHELF_IMAGE=' "$BASE_DIR/.env" || true) == 1 ]] || { echo 'ERROR: Invalid MYSHELF_IMAGE' >&2; exit 1; }
ENV_IMAGE="$(sed -n 's/^MYSHELF_IMAGE=//p' "$BASE_DIR/.env")"
[[ "$ENV_IMAGE" =~ ^ghcr\.io/pickylemon/myshelf:sha-([0-9a-f]{40})$ ]] || { echo 'ERROR: Existing image has no SHA tag' >&2; exit 1; }
OLD_SHA="${BASH_REMATCH[1]}"

# 실제 실행 이미지와 .env 값이 같은지 확인해 기존 운영 버전을 정확히 식별한다.
COMPOSE=(sudo -n docker compose -p myshelf --project-directory "$BASE_DIR" --env-file "$BASE_DIR/.env" -f "$BASE_DIR/compose.yaml" -f "$BASE_DIR/compose.prod.yaml")
CONTAINER_ID="$("${COMPOSE[@]}" ps -q app)"
[[ -n "$CONTAINER_ID" ]] || { echo 'ERROR: Existing app container missing' >&2; exit 1; }
RUNNING_IMAGE="$(sudo -n docker inspect --format '{{.Config.Image}}' "$CONTAINER_ID")"
[[ "$RUNNING_IMAGE" == "$ENV_IMAGE" ]] || { echo 'ERROR: Running image differs from .env' >&2; exit 1; }

mkdir -p -- "$BASE_DIR/releases"
RELEASE="$BASE_DIR/releases/$OLD_SHA"
if [[ -e "$CURRENT" || -L "$CURRENT" ]]; then
  [[ -L "$CURRENT" && "$(readlink -f -- "$CURRENT")" == "$RELEASE" ]] || {
    echo 'ERROR: current refers to another release; refusing bootstrap' >&2; exit 1;
  }
fi

# 디렉터리 이동 후 링크 생성만 실패했다면 같은 파일인지 검증하여 재사용한다.
if [[ -e "$RELEASE" || -L "$RELEASE" ]]; then
  [[ -d "$RELEASE" && ! -L "$RELEASE" ]] || { echo 'ERROR: Invalid initial release path' >&2; exit 1; }
  for f in compose.yaml compose.prod.yaml deploy-ec2.sh; do
    [[ -f "$RELEASE/$f" && ! -L "$RELEASE/$f" ]] || { echo "ERROR: Initial release incomplete: $f" >&2; exit 1; }
    cmp -s -- "$BASE_DIR/$f" "$RELEASE/$f" || { echo "ERROR: Initial release differs: $f" >&2; exit 1; }
  done
  if [[ -L "$CURRENT" ]]; then
    echo "Initial release already registered: $OLD_SHA"
    exit 0
  fi
fi

STAGING=""
cleanup() {
  [[ -z "$STAGING" ]] || rm -rf -- "$STAGING"
  rm -f -- "$BASE_DIR/.current.bootstrap.$$"
}
trap cleanup EXIT
if [[ ! -d "$RELEASE" ]]; then
  STAGING="$(mktemp -d "$BASE_DIR/releases/.bootstrap.XXXXXXXX")"
  cp -- "$BASE_DIR/compose.yaml" "$BASE_DIR/compose.prod.yaml" "$BASE_DIR/deploy-ec2.sh" "$STAGING/"
  mv -T -- "$STAGING" "$RELEASE"
  STAGING=""
fi
ln -s -- "$RELEASE" "$BASE_DIR/.current.bootstrap.$$"
mv -T -- "$BASE_DIR/.current.bootstrap.$$" "$CURRENT"
echo "Initial release registered: $OLD_SHA"
echo 'Running containers, .env and Docker volumes were not changed.'
