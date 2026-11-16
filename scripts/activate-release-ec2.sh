#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# 사용법: bash activate-release-ec2.sh <40자리 SHA>
# 전제: 호출자는 /opt/myshelf/.deployment.lock에 배포 잠금을 획득했다.
DEPLOY_SHA="${1:-}"
[[ "$DEPLOY_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'ERROR: Invalid commit SHA' >&2; exit 1; }
BASE_DIR=/opt/myshelf
CURRENT="$BASE_DIR/current"
ENV_FILE="$BASE_DIR/.env"
TARGET="$BASE_DIR/releases/$DEPLOY_SHA"
[[ -f "$ENV_FILE" && -L "$CURRENT" ]] || { echo 'ERROR: Run bootstrap first' >&2; exit 1; }
PREVIOUS="$(readlink -f -- "$CURRENT")"
[[ -d "$PREVIOUS" && "$PREVIOUS" == "$BASE_DIR"/releases/* ]] || { echo 'ERROR: Invalid previous release' >&2; exit 1; }
[[ -d "$TARGET" && ! -L "$TARGET" ]] || { echo 'ERROR: Target release not prepared' >&2; exit 1; }
for f in compose.yaml compose.prod.yaml deploy-ec2.sh; do
  [[ -f "$TARGET/$f" && ! -L "$TARGET/$f" ]] || { echo "ERROR: Target file missing: $f" >&2; exit 1; }
done

# 파일 참조를 바꾸기 전에 새 Compose 설정이 유효한지 검증한다.
sudo -n docker compose -p myshelf --project-directory "$BASE_DIR" --env-file "$ENV_FILE" \
  -f "$TARGET/compose.yaml" -f "$TARGET/compose.prod.yaml" config --quiet

BACKUP="$(mktemp "$BASE_DIR/.env.rollback.XXXXXXXX")"
cp -p -- "$ENV_FILE" "$BACKUP"
OLD_IMAGE="$(sed -n 's/^MYSHELF_IMAGE=//p' "$BACKUP")"
[[ -n "$OLD_IMAGE" ]] || { echo 'ERROR: Previous image not found' >&2; exit 1; }

# 임시 링크를 rename하여 current를 원자적으로 전환한다.
atomic_link() {
  local destination="$1"
  local temp_link="$BASE_DIR/.current.next.$$"
  rm -f -- "$temp_link"
  ln -s -- "$destination" "$temp_link"
  mv -Tf -- "$temp_link" "$CURRENT"
}

SWITCHED=0
rollback() {
  echo 'Deployment failed; attempting rollback' >&2
  # 링크와 환경변수를 복구한 뒤 기존 이미지로 Compose를 재적용한다.
  atomic_link "$PREVIOUS" || return 1
  local restore_env
  restore_env="$(mktemp "$BASE_DIR/.env.restore.XXXXXXXX")" || return 1
  cp -p -- "$BACKUP" "$restore_env" || return 1
  mv -Tf -- "$restore_env" "$ENV_FILE" || return 1
  local -a compose=(sudo -n docker compose -p myshelf --project-directory "$BASE_DIR" --env-file "$ENV_FILE" -f "$CURRENT/compose.yaml" -f "$CURRENT/compose.prod.yaml")
  "${compose[@]}" config --quiet || return 1
  "${compose[@]}" up -d --pull never || return 1
  local container_id running_image
  container_id="$("${compose[@]}" ps -q app)" || return 1
  [[ -n "$container_id" ]] || return 1
  running_image="$(sudo -n docker inspect --format '{{.Config.Image}}' "$container_id")" || return 1
  [[ "$running_image" == "$OLD_IMAGE" ]] || return 1
  for attempt in {1..30}; do
    if curl -fsS --max-time 3 -o /dev/null http://127.0.0.1:8080/v3/api-docs 2>/dev/null; then
      printf '%s | ROLLBACK_SUCCESS | from=%s | to=%s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "ghcr.io/pickylemon/myshelf:sha-$DEPLOY_SHA" "$OLD_IMAGE" >> "$BASE_DIR/deployment-history.log"
      echo 'Rollback succeeded'
      return 0
    fi
    sleep 2
  done
  return 1
}

on_exit() {
  local status=$?
  trap - EXIT
  if (( status != 0 && SWITCHED == 1 )); then
    if ! rollback; then
      echo "CRITICAL: Rollback failed. Backup retained: $BACKUP" >&2
      exit "$status"
    fi
  fi
  rm -f -- "$BASE_DIR/.current.next.$$" "$BACKUP"
  exit "$status"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

SWITCHED=1
atomic_link "$TARGET"
bash "$TARGET/deploy-ec2.sh" "$DEPLOY_SHA"
echo "Release activated: $DEPLOY_SHA"
