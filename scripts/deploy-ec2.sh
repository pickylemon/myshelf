#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# 사용법: bash deploy-ec2.sh <40자리 커밋 SHA>
# 준비된 current 릴리스의 Compose 설정을 사용해 이미지를 배포한다.
DEPLOY_SHA="${1:-}"
[[ "$DEPLOY_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'ERROR: Invalid commit SHA' >&2; exit 1; }
BASE_DIR=/opt/myshelf
CURRENT="$BASE_DIR/current"
RELEASE_DIR="$BASE_DIR/releases/$DEPLOY_SHA"
ENV_FILE="$BASE_DIR/.env"
HISTORY_FILE="$BASE_DIR/deployment-history.log"
LAST_SUCCESS_FILE="$BASE_DIR/.last-successful-image"
NEW_IMAGE="ghcr.io/pickylemon/myshelf:sha-$DEPLOY_SHA"

[[ -L "$CURRENT" ]] || { echo 'ERROR: current symlink missing' >&2; exit 1; }
[[ "$(readlink -f -- "$CURRENT")" == "$RELEASE_DIR" ]] || { echo 'ERROR: current does not match deployment SHA' >&2; exit 1; }
for file in compose.yaml compose.prod.yaml deploy-ec2.sh; do
  [[ -f "$RELEASE_DIR/$file" && ! -L "$RELEASE_DIR/$file" ]] || { echo "ERROR: Missing release file: $file" >&2; exit 1; }
done
[[ -f "$ENV_FILE" ]] || { echo 'ERROR: .env missing' >&2; exit 1; }
[[ $(grep -c '^MYSHELF_IMAGE=' "$ENV_FILE" || true) == 1 ]] || { echo 'ERROR: Invalid MYSHELF_IMAGE configuration' >&2; exit 1; }
OLD_IMAGE="$(sed -n 's/^MYSHELF_IMAGE=//p' "$ENV_FILE")"

record_history() {
  printf '%s | %s | from=%s | to=%s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$1" "$OLD_IMAGE" "$NEW_IMAGE" >> "$HISTORY_FILE"
}
finished=0
CANDIDATE_ENV=""
on_exit() {
  # 정리 명령이 $?를 바꾸기 전에 전달받은 배포 종료 코드를 보존한다.
  local result="$1"
  trap - EXIT
  if [[ -n "$CANDIDATE_ENV" ]]; then rm -f -- "$CANDIDATE_ENV" || true; fi
  if (( result != 0 && finished == 0 )); then record_history FAILED || true; fi
  exit "$result"
}
trap 'on_exit "$?"' EXIT
record_history STARTED

COMPOSE=(sudo -n docker compose -p myshelf --project-directory "$BASE_DIR" --env-file "$ENV_FILE" -f "$CURRENT/compose.yaml" -f "$CURRENT/compose.prod.yaml")

# 이미지와 후보 Compose 설정을 검증한 뒤에야 운영 .env를 교체한다.
sudo -n docker pull "$NEW_IMAGE"
CANDIDATE_ENV="$(mktemp "$BASE_DIR/.env.next.XXXXXXXX")"
awk -v image="$NEW_IMAGE" '/^MYSHELF_IMAGE=/{ print "MYSHELF_IMAGE=" image; next } {print}' "$ENV_FILE" > "$CANDIDATE_ENV"
sudo -n docker compose -p myshelf --project-directory "$BASE_DIR" --env-file "$CANDIDATE_ENV" -f "$CURRENT/compose.yaml" -f "$CURRENT/compose.prod.yaml" config --quiet
mv -fT -- "$CANDIDATE_ENV" "$ENV_FILE"
CANDIDATE_ENV=""

"${COMPOSE[@]}" up -d
"${COMPOSE[@]}" ps
CONTAINER_ID="$("${COMPOSE[@]}" ps -q app)"
[[ -n "$CONTAINER_ID" ]] || { echo 'ERROR: App container missing' >&2; exit 1; }
RUNNING_IMAGE="$(sudo -n docker inspect --format '{{.Config.Image}}' "$CONTAINER_ID")"
[[ "$RUNNING_IMAGE" == "$NEW_IMAGE" ]] || { echo 'ERROR: Running image mismatch' >&2; exit 1; }

# 기존 애플리케이션의 HTTP 검증 정책 유지.
for attempt in {1..30}; do
  if curl -fsS --max-time 3 -o /dev/null http://127.0.0.1:8080/v3/api-docs 2>/dev/null; then
    TEMP_SUCCESS="$(mktemp "$BASE_DIR/.last-successful-image.next.XXXXXXXX")"
    printf '%s\n' "$NEW_IMAGE" > "$TEMP_SUCCESS"
    mv -fT -- "$TEMP_SUCCESS" "$LAST_SUCCESS_FILE"
    record_history SUCCESS
    finished=1
    echo 'Deployment verification succeeded'
    exit 0
  fi
  sleep 2
done

echo 'ERROR: Deployment HTTP verification failed' >&2
exit 1
