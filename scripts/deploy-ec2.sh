
#!/usr/bin/env bash

# 스크립트 실행 중 오류가 발생하면 배포를 중단하도록 설정한다.
# -e: 명령이 실패하면 스크립트 종료
# -u: 정의되지 않은 변수를 사용하면 오류 처리
# -o pipefail: 파이프라인 중간의 명령이 실패해도 오류 처리
set -euo pipefail

# Docker Compose 파일과 .env 파일이 있는 배포 디렉터리로 이동한다.
cd /opt/myshelf

# 운영 환경변수 파일이 없으면 배포를 중단한다.
# DB 비밀번호, JWT 비밀 키, 배포 이미지 태그 등이 이 파일에 정의되어 있다.
if [[ ! -f .env ]]; then
  echo "ERROR: .env file not found" >&2
  exit 1
fi

# 반복해서 사용할 Docker Compose 명령을 Bash 배열로 정의한다.
# sudo -n: 비밀번호 입력 없이 관리자 권한으로 실행하며,
#          비밀번호가 필요한 경우 즉시 실패한다.
# -f: 공통 Compose 설정과 운영 환경 설정을 순서대로 병합한다.
COMPOSE=(
  sudo -n docker compose
  -f compose.yaml
  -f compose.prod.yaml
)

# 1. Compose 설정 및 환경변수 치환 결과가 유효한지 검사한다.
# --quiet: 설정 내용을 출력하지 않고 검증 결과만 반환한다.
echo "1. Validate Compose configuration"
"${COMPOSE[@]}" config --quiet

# 2. .env의 MYSHELF_IMAGE에 지정된 애플리케이션 이미지를 GHCR에서 가져온다.
# app은 Compose 파일에 정의된 서비스 이름이다.
# PostgreSQL과 Redis 이미지는 여기서 별도로 Pull하지 않는다.
echo "2. Pull application image"
"${COMPOSE[@]}" pull app

# 3. Compose 설정에 따라 컨테이너를 실행한다.
# 변경된 이미지나 설정이 있다면 필요한 컨테이너를 재생성한다.
# -d: 백그라운드에서 컨테이너를 실행한다.
echo "3. Start containers"
"${COMPOSE[@]}" up -d

# 4. 애플리케이션, PostgreSQL, Redis 컨테이너의 실행 상태를 출력한다.
echo "4. Check container status"
"${COMPOSE[@]}" ps

# 5. 애플리케이션이 실제 HTTP 요청에 응답하는지 확인한다.
# 컨테이너가 실행되더라도 Spring Boot 초기화가 끝나지 않았을 수 있으므로
# 최대 30번 재시도한다.
echo "5. Verify HTTP response"

for attempt in {1..30}; do

  # -f: HTTP 400~599 응답을 실패로 처리
  # -sS: 진행 상황은 숨기고 오류 메시지는 표시
  # --max-time 3: 요청당 최대 3초 대기
  # -o /dev/null: HTTP 응답 본문은 출력하지 않음
  # 2>/dev/null: 오류 메시지를 화면에 표시하지 않음
  if curl -fsS --max-time 3 \
      -o /dev/null \
      http://127.0.0.1:8080/v3/api-docs 2>/dev/null; then

    # HTTP 요청에 성공하면 배포 검증을 완료한다.
    echo "Deployment verification succeeded"
    exit 0
  fi

  # 아직 응답하지 않으면 2초 기다린 뒤 다시 요청한다.
  sleep 2
done

# 모든 재시도에 실패하면 오류 메시지를 출력하고 비정상 종료한다.
# >&2: 오류 메시지를 표준 에러(stderr)로 출력
# exit 1: GitHub Actions가 배포 실패로 판단할 수 있도록 종료 코드 반환
echo "ERROR: Deployment verification failed" >&2
exit 1
