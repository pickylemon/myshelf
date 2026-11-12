# EC2 수동 배포 Runbook — GHCR + Docker Compose

- **유형:** Learning
- **최종 수동 검증일:** 2026-11-12
- **대상:** MyShelf / Ubuntu 24.04 / AWS EC2 `t4g.small` (ARM64)
- **범위:** 비공개 GHCR 이미지 인증, 이미지 다운로드, Compose 실행, 기본 HTTP 응답 검증
- **후속 작업:** GitHub Actions CD 자동화 (`cd/ec2-deployment`)

> 이 문서는 **수동으로 성공한 배포 절차**를 재현하기 위한 기록이다. 실제 운영용 자격 증명, API 키, DB 비밀번호를 문서에 기록하지 않는다.

## 1. 배포 구조

```text
GitHub Actions CI → GHCR 멀티 플랫폼 이미지
                       ↓ ARM64 이미지 Pull
                   AWS EC2
                /opt/myshelf/
                ├── compose.yaml
                ├── compose.prod.yaml
                └── .env  (Git에 커밋하지 않음)
                       ↓
             Spring Boot + PostgreSQL + Redis
```

- `compose.yaml`: 공통 서비스 구성
- `compose.prod.yaml`: GHCR 이미지, `pull_policy: always`, `prod,redis` 프로필
- `compose.override.yaml`: 로컬 개발용이므로 **EC2 배포에 포함하지 않음**

## 2. 사전 조건

- EC2에 Docker Engine 및 Docker Compose 설치
- EC2가 외부 GHCR에 HTTPS로 접근 가능
- `/opt/myshelf`에 **현재 배포할 버전의** `compose.yaml`, `compose.prod.yaml` 존재
- `/opt/myshelf/.env`에 운영용 `DB_PASSWORD`, `JWT_SECRET`, `NATIONAL_LIBRARY_API_KEY` 및 `MYSHELF_IMAGE` 설정
- GHCR 패키지가 비공개라면 읽기 권한이 있는 인증 정보 설정
- `MYSHELF_IMAGE` 태그가 `linux/arm64`를 포함한 이미지여야 함

EC2에서 다음으로 파일 존재 여부를 확인한다.

```bash
cd /opt/myshelf
ls -l compose.yaml compose.prod.yaml
ls -l .env  # 권한만 확인, 내용을 출력하지 않음
```

`.env`의 권한은 최소한 현재 사용자만 읽을 수 있도록 제한한다.

```bash
chmod 600 /opt/myshelf/.env
```

**주의:** 이미 초기화된 PostgreSQL 데이터 볼륨이 있다면 `.env`의 `DB_PASSWORD`를 수정하는 것만으로 기존 DB 계정 비밀번호가 변경되지는 않는다.

## 3. 최신 Compose 파일 반영

MyShelf 프로젝트 루트의 **로컬 PowerShell**에서 실행하는 예시:

```powershell
scp -i <SSH_KEY_PATH> `
  compose.yaml compose.prod.yaml `
  ubuntu@<EC2_PUBLIC_IP>:/opt/myshelf/
```

운영 `.env`는 EC2에서 따로 관리하므로 복사·덮어쓰지 않는다. GitHub 저장소에서 버전 관리되는 것은 Compose 설정이고, EC2가 변경 사항을 자동으로 받는 것은 아니다. CD 구현 후에는 이 파일 전달 단계도 자동화한다.

## 4. 이미지 버전 지정

EC2의 `/opt/myshelf/.env`에서 다음 항목을 **새로 게시된 실제 SHA 태그**로 수정한다.

```dotenv
# 예시: 2026-11-12 수동 배포 검증에 사용한 이미지
MYSHELF_IMAGE=ghcr.io/pickylemon/myshelf:sha-4e651623b2f855f81d4a023d365632358b0d484b
```

`MYSHELF_IMAGE`는 Compose가 이미지를 선택할 때 사용하는 변수이며, 그 자체가 Spring Boot 컨테이너 내부로 자동 전달되지는 않는다. 비밀번호 등은 Compose 파일의 `environment`에 선언된 항목만 컨테이너에 전달된다.

값 변경 후 별도의 `.env` 재적용 명령은 필요 없다. 다음 Compose 실행 시 새 값을 읽는다. 단, 이미 실행 중인 컨테이너에는 파일 변경만으로 값이 반영되지 않는다.

## 5. 비공개 GHCR 인증 (최초 또는 토큰 갱신 시)

GitHub에서 **Personal Access Token (classic)**을 발급하고, 패키지 읽기 용도에 필요한 `read:packages` 권한을 부여한다. GitHub 계정 자체에도 해당 패키지의 읽기 권한이 있어야 한다.

EC2 Ubuntu 터미널에서 다음을 실행한다.

```bash
read -rsp 'GHCR Token: ' GHCR_TOKEN
echo
printf '%s' "$GHCR_TOKEN" | sudo -n docker login ghcr.io \
  -u pickylemon --password-stdin
unset GHCR_TOKEN
```

- `read -s`는 입력 내용을 화면에 표시하지 않는다. Enter로 입력 완료한다.
- `echo`는 줄바꿈만 출력한다. 토큰을 출력하지 않는다.
- `--password-stdin`을 사용하여 명령 이력에 토큰을 직접 남기지 않는다.
- `sudo docker compose`로 배포하므로 **로그인에도 `sudo docker`를 사용**한다.
- 성공 메시지는 `Login Succeeded`이다.
- `sudo -n`은 비밀번호 입력 프롬프트를 허용하지 않으며, sudo 권한이 없는 환경에서는 오류가 발생한다. 실제 서버 권한 정책에 따라 조정한다.

이번 EC2에서는 로그인 성공 후 다음 경고를 확인했다.

```text
WARNING! Your credentials are stored unencrypted in '/root/.docker/config.json'.
```

Docker 자격 증명 도우미가 없으면 파일에 인증 정보가 Base64 형식으로 저장될 수 있다. **이 파일을 화면에 출력하거나 Git에 보관하지 말 것.** 장기 운영에서는 자격 증명 도우미 적용, 최소 권한·만료 관리 및 토큰 갱신 절차를 검토한다.

## 6. 배포 명령

EC2에서 실행:

```bash
cd /opt/myshelf

# 설정 검증: 치환된 비밀번호를 화면에 출력하지 않음
sudo docker compose -f compose.yaml -f compose.prod.yaml config --quiet

# 선택적 선행 검증: 앱 이미지만 Pull
sudo docker compose -f compose.yaml -f compose.prod.yaml pull app

# 운영 서비스 실행
sudo docker compose -f compose.yaml -f compose.prod.yaml up -d
```

`compose.prod.yaml`에 `pull_policy: always`가 있어 `up -d`만으로도 이미지 Pull을 요청한다. 별도 `pull app`은 오류를 먼저 분리하여 확인하고 싶을 때 사용한다.

`-f`로 운영 파일을 명시했으므로 기본 로컬 `compose.override.yaml`은 자동 병합되지 않는다. EC2에서는 Dockerfile 빌드 없이 GHCR 이미지를 사용한다.

## 7. 배포 검증

```bash
sudo docker compose -f compose.yaml -f compose.prod.yaml ps -a
sudo docker compose -f compose.yaml -f compose.prod.yaml logs --tail=100 app
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:8080/v3/api-docs
```

2026-11-12 실제 검증 결과:

| 항목 | 결과 |
|---|---|
| ARM64 GHCR 이미지 Pull | 성공 |
| `myshelf-app-1` | `Up` |
| `myshelf-postgres` | `Healthy` |
| `myshelf-redis` | `Healthy` |
| 애플리케이션 포트 | `127.0.0.1:8080->8080/tcp` |
| DB·Redis 호스트 포트 | 게시하지 않음 (`5432/tcp`, `6379/tcp` 표시는 컨테이너 내부 포트) |
| `GET /v3/api-docs` | `HTTP 200` |

**검증 범위 해석:** `HTTP 200`은 EC2 내부에서 Spring Boot의 API 문서 엔드포인트에 접근할 수 있음을 보여 준다. 인증, 도서 검색, DB 쓰기, Redis 캐시 등 모든 기능이 정상이라는 증거는 아니다. 추후 별도 통합 스모크 테스트를 마련한다.

`127.0.0.1:8080` 바인딩이므로 EC2 외부에서 공인 IP의 8080 포트로 직접 접속할 수 없으며, 이번 검증을 위해 AWS 보안 그룹에서 8080을 공개할 필요가 없다.

## 8. 발생한 오류와 조치

| 증상 | 원인 또는 해석 | 조치 |
|---|---|---|
| `error from registry: unauthorized` | GHCR 패키지 인증 또는 접근 권한 부족 | `read:packages` 토큰과 `sudo docker login` 확인 |
| `no matching manifest for linux/arm64/v8` | 지정 태그에 ARM64 실행 이미지가 없음 | `linux/arm64`를 포함한 새 GHCR 태그 사용 |
| `postgres:17`, `redis:7-alpine`이 `Interrupted` | 앱 이미지 Pull 실패로 다른 Pull도 중단된 후속 증상 | 앱 Pull 문제 해결 후 재시도 |
| `No such image: postgres:17` | 다운로드가 완료되지 않아 로컬 이미지가 없음 | 정상 Pull 재시도 또는 `sudo docker pull postgres:17` |
| `curl -I https://ghcr.io/v2/` → `HTTP 405` | HEAD 메서드가 허용되지 않은 응답 | 서버까지 네트워크가 연결된 증거로 해석 가능, 인증·Pull 권한은 별도 검증 |
| `.env` 수정했는데 이전 태그 사용 | 기존 컨테이너와 새 설정의 적용 시점 차이 또는 셸 변수 우선순위 | `.env`·셸 변수 확인 후 Compose 다시 실행 |
| 컨테이너는 `Up`이나 API 응답 실패 | 실행 상태와 서비스 정상성은 별개 | 앱 로그, 포트 바인딩, HTTP 상태 확인 |

**주의:** `docker compose down -v`는 영속 데이터 볼륨을 제거할 수 있으므로 운영에서 문제 해결용으로 실행하지 않는다. `docker compose config`는 비밀값을 출력할 수 있으므로 가급적 `config --quiet`을 사용한다.

## 9. CD 전환 시 자동화할 작업

1. CI가 정상 게시한 **커밋 SHA 태그**를 배포 입력으로 확정한다.
2. 현재 커밋 버전의 Compose 파일 두 개를 EC2에 전달한다.
3. EC2에 필요한 인증을 사용할 수 있는 상태인지 확인한다.
4. 기존 비밀정보를 유지하면서 `MYSHELF_IMAGE`만 새 태그로 지정한다.
5. `docker compose config --quiet`, `up -d`를 실행한다.
6. 컨테이너 상태뿐 아니라 HTTP 응답도 확인하고 실패 시 작업을 실패 처리한다.
7. 배포 실패 시 이전 태그로 복원할 절차와 운영 비밀정보 보호 방안을 별도로 설계한다.

## 10. 관련 문서

- [ADR 014 — 멀티 플랫폼 이미지 빌드](../../decisions/014-multi-platform-image-build.md)
- [멀티 플랫폼 이미지와 CI 실행 원리](docker-multi-platform-ci.md)
- [Docker Compose 파일 병합과 PostgreSQL 초기화 동작](docker-compose-merge-and-postgres-init.md)
- [Docker — docker login](https://docs.docker.com/reference/cli/docker/login/)
- [GitHub — Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
