
#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# 사용법: bash deploy-ec2.sh <40자리 커밋 SHA>
# CI에서 게시한 커밋 SHA를 첫 번째 인자로 전달받는다.
DEPLOY_SHA="${1:-}"

# 잘못된 이미지 버전이나 명령어가 전달되지 않도록 검증한다.
if [[ ! "$DEPLOY_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: Invalid commit SHA" >&2
  exit 1
fi

NEW_IMAGE="ghcr.io/pickylemon/myshelf:sha-${DEPLOY_SHA}"

cd /opt/myshelf

ENV_FILE=".env"
HISTORY_FILE="deployment-history.log"
LAST_SUCCESS_FILE=".last-successful-image"

# 운영 환경변수 파일이 있는지 확인한다.
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: .env not found" >&2
  exit 1
fi

# 이미지 변수가 정확히 한 번 정의되어 있어야 한다.
if [[ $(grep -c '^MYSHELF_IMAGE=' "$ENV_FILE" || true) != 1 ]]; then
  echo "ERROR: Invalid MYSHELF_IMAGE configuration" >&2
  exit 1
fi

# 변경하기 전 이미지 버전을 보관한다.
OLD_IMAGE=$(grep '^MYSHELF_IMAGE=' "$ENV_FILE" | cut -d= -f2-)

# 배포 시각, 결과, 이전/신규 이미지를 기록한다.
record_history() {
  printf '%s | %s | from=%s | to=%s\n' \
    "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
    "$1" "$OLD_IMAGE" "$NEW_IMAGE" >> "$HISTORY_FILE"
}

# 배포 중 오류가 나면 실패 이력을 남긴다.
# 임시 파일이 남아 있다면 제거한다.
TEMP_FILE=""

on_exit() {
  local exit_code=$?

  if [[ -n "$TEMP_FILE" && -f "$TEMP_FILE" ]]; then
    rm -f "$TEMP_FILE"
  fi

  if (( exit_code != 0 )); then
    record_history "FAILED"
  fi
}

trap on_exit EXIT

record_history "STARTED"

echo "Deploying: $NEW_IMAGE"

# 1. 새 이미지가 GHCR에 존재하고 다운로드 가능한지 먼저 확인한다.
# 다운로드가 실패하면 .env를 수정하지 않는다.
echo "1. Pull new image"
sudo -n docker pull "$NEW_IMAGE"

# 2. .env에서 MYSHELF_IMAGE 항목만 새 이미지로 변경한다.
# 비밀번호나 API 키 등 나머지 항목은 그대로 유지한다.
# 임시 파일을 같은 디렉터리에 생성한 뒤 교체한다.
echo "2. Update image version"

TEMP_FILE=$(mktemp /opt/myshelf/.env.XXXXXX)

awk -v image="$NEW_IMAGE" '
  /^MYSHELF_IMAGE=/ {
    print "MYSHELF_IMAGE=" image
    next
  }
  { print }
' "$ENV_FILE" > "$TEMP_FILE"

mv "$TEMP_FILE" "$ENV_FILE"
TEMP_FILE=""

# 반복해서 사용할 운영 Compose 명령
COMPOSE=(
  sudo -n docker compose
  -f compose.yaml
  -f compose.prod.yaml
)

# 3. 변경한 환경변수와 Compose 설정이 유효한지 확인한다.
echo "3. Validate Compose configuration"
"${COMPOSE[@]}" config --quiet

# 4. 새 이미지를 사용하는 컨테이너를 실행한다.
echo "4. Start containers"
"${COMPOSE[@]}" up -d

"${COMPOSE[@]}" ps

# 5. 실제 app 컨테이너에 새 이미지가 적용되었는지 확인한다.
CONTAINER_ID=$("${COMPOSE[@]}" ps -q app)

if [[ -z "$CONTAINER_ID" ]]; then
  echo "ERROR: App container is not running" >&2
  exit 1
fi

RUNNING_IMAGE=$(
  sudo -n docker inspect --format '{{.Config.Image}}' "$CONTAINER_ID"
)

if [[ "$RUNNING_IMAGE" != "$NEW_IMAGE" ]]; then
  echo "ERROR: Running image does not match target" >&2
  exit 1
fi

# 6. Spring Boot의 HTTP 응답을 최대 30회 확인한다.
echo "5. Verify HTTP response"

for attempt in {1..30}; do
  if curl -fsS --max-time 3 \
      -o /dev/null \
      http://127.0.0.1:8080/v3/api-docs 2>/dev/null; then

    # 검증에 성공한 이미지 버전과 배포 결과를 기록한다.
    printf '%s\n' "$NEW_IMAGE" > "$LAST_SUCCESS_FILE"
    record_history "SUCCESS"

    echo "Deployment verification succeeded"
    exit 0
  fi

  sleep 2
done

echo "ERROR: Deployment verification failed" >&2
exit 1
