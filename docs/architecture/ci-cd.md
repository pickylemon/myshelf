# MyShelf CI/CD 아키텍처

> 상태: CI·멀티 플랫폼 이미지 게시 및 SSH 기반 EC2 CD 구현 완료 / 배포 후 기본 HTTP 응답 검증 완료
> 작성일: 2026-10-21
> 최종 수정일: 2026-11-13
> 범위: GitHub Actions 기반 CI, AMD64·ARM64 이미지 빌드·게시, OIDC·SSH 기반 EC2 자동 배포

## 1. 개요

MyShelf는 GitHub Actions를 이용하여 코드 변경사항에 대한 빌드, 테스트 및 Docker 이미지 빌드 검증을 자동화한다.

기존에는 개발자가 로컬에서 직접 검증했지만, CI 도입 이후에는 GitHub의 독립적인 실행 환경에서도 동일한 테스트를 수행하고 Docker 이미지 생성 가능 여부를 확인한다.

주요 목적은 다음과 같다.

- Pull Request 단위의 자동화된 검증
- 로컬 환경에 의존하지 않는 빌드 및 테스트
- PostgreSQL 통합 테스트의 독립성과 재현성 확보
- Redis 세션·메타데이터 캐시 테스트를 위한 독립 실행 환경 준비
- Docker 이미지 빌드 검증 및 Registry 게시 자동화
- CI가 게시한 커밋 SHA 이미지의 EC2 자동 배포와 기본 HTTP 응답 확인

현재 Gradle 빌드·테스트 자동화와 PR 단계의 Docker 이미지 빌드 검증을 완료했다.

또한 `main` 브랜치에 병합된 변경사항을 기반으로 GHCR 이미지 게시가 정상적으로 수행되는 것을 확인했다.

게시된 이미지를 로컬 환경으로 다운로드하고 PostgreSQL과 연결하여 Spring Boot 애플리케이션을 실행했다. 회원가입 및 로그인 API 요청도 정상적으로 처리되는 것을 확인했다.

현재는 `main` Push의 CI가 이미지 게시까지 성공하면 별도 CD 워크플로가 EC2에 배포한다. 구현은 HEAD의 `.github/workflows/cd.yaml`과 `scripts/deploy-ec2.sh`를 기준으로 한다. 실행 번호·배포 SHA·서버 확인 결과는 [최초 통합 검증 기록](../learning/infrastructure/github-actions-cd.md#7-실제-최초-통합-검증)에 모아 둔다. Jenkins 실습, SSM 전환, 자동 롤백은 후속 과제다.

## 2. GitHub Actions CI 구성

### 2.1 Workflow

CI Workflow는 `.github/workflows/ci.yaml`에 정의한다.

| 항목 | 구성 |
|---|---|
| CI 도구 | GitHub Actions |
| Runner | `ubuntu-latest` |
| Java | Temurin JDK 21 |
| Gradle | Gradle Wrapper 9.7.1 |
| PostgreSQL | `postgres:17` |
| Redis | `redis:7-alpine`, Runner의 `localhost:6379` |
| Spring 프로필 | `test` |
| Gradle 빌드 명령어 | `bash ./gradlew clean build --no-daemon` |
| Docker 이미지 빌드 | QEMU + Docker Buildx, `linux/amd64,linux/arm64` |
| Workflow 기본 권한 | `contents: read` |

Java, Gradle, PostgreSQL·Redis 및 Spring 프로필 설정은 `build-and-test` Job에서 사용한다. Redis 통합 테스트는 테스트 클래스의 `test,redis` 프로필로 DB 1을 사용한다. Docker 이미지 관련 Job은 별도의 Runner에서 기존 멀티스테이지 Dockerfile을 이용해 빌드한다.

GitHub Actions를 주 CI 도구로 사용하며, 별도의 Jenkins 서버는 운영하지 않는다.

현재 Workflow는 세 개의 Job으로 구성된다.

| Job | 역할 | 실행 조건 |
|---|---|---|
| `build-and-test` | Gradle 빌드, Spotless 검사, PostgreSQL·Redis 통합 테스트 | PR, main Push, 수동 실행 |
| `verify-image` | Docker 이미지 빌드 가능 여부 검증 | PR, `build-and-test` 성공 후 |
| `publish-image` | Docker 이미지 빌드 및 GHCR 게시 | main Push, `build-and-test` 성공 후 |

`verify-image`와 `publish-image`는 모두 `needs: build-and-test`를 통해 선행 Job에 의존한다.

두 Job은 실행 이벤트에 따라 구분되며, 각각 선행 Job이 성공한 경우에만 실행된다.

Workflow의 기본 권한은 `contents: read`이고, `publish-image` Job에는 `packages: write` 권한을 추가한다.

GHCR 인증에는 GitHub Actions가 자동 제공하는 `GITHUB_TOKEN`을 사용한다.

### 2.2 실행 조건

| 이벤트 | 목적 |
|---|---|
| `pull_request` → `main` | PR 병합 전 빌드·테스트 및 이미지 빌드 검증 |
| `push` → `main` | 메인 브랜치 빌드·테스트 및 GHCR 이미지 게시 |
| `workflow_dispatch` | 수동 빌드·테스트 실행 |

PR 생성 및 업데이트 시 CI가 자동 실행되는 것을 확인했다.

PR #13 병합 이후 `main` 브랜치의 `push` 이벤트로 기존 CI가 실행되었으며, 정상 완료를 확인했다.

이후 PR #16 병합으로 발생한 `main` Push 이벤트에서는 `build-and-test`와 `publish-image`가 성공적으로 실행되어 GHCR 이미지 게시까지 완료되었다.

Docker 이미지 관련 Job은 이벤트별로 구분한다.

- PR: `verify-image` 실행, `publish-image` 건너뜀
- main Push: `publish-image` 실행, `verify-image` 건너뜀
- 수동 실행: 현재는 `build-and-test`만 실행

#### 브랜치 보호 정책

`main` 브랜치에 GitHub Branch Ruleset을 적용하여 다음 정책을 구성했다.

- Pull Request를 통한 변경사항 병합 필수 (`Require a pull request before merging`)
- 필수 CI 상태 검사 통과 요구 (`Require status checks to pass`)
    - `build-and-test`
    - `verify-image`
- 브랜치 삭제 제한 (`Restrict deletions`)
- 강제 Push 차단 (`Block force pushes`)
- 필수 리뷰 승인 수 0으로 설정

이를 통해 Gradle 빌드·테스트 또는 Docker 이미지 빌드 검증에 실패한 PR의 병합을 제한한다.

현재 `publish-image`는 PR 단계에서 실행되지 않으므로 필수 상태 검사에 포함하지 않는다.

### 2.3 실행 흐름

**PR에서 실행되는 CI**

```text
pull_request → main
        |
        v
  build-and-test
  - PostgreSQL·Redis 서비스 준비
  - JDK 21 및 Gradle 환경 구성
  - Gradle 빌드 및 Spotless 검사
  - Flyway 마이그레이션
  - PostgreSQL·Redis 통합 테스트
  - 테스트 리포트 업로드
        |
        | 성공 시
        v
   verify-image
  - QEMU 및 Docker Buildx 준비
  - AMD64·ARM64 멀티스테이지 Docker 이미지 빌드
  - Registry 게시 없음
        |
        v
   PR 검증 완료
```

**main 브랜치 반영 이후 실행되는 CI**

```text
push → main
      |
      v
build-and-test
      |
      | 성공 시
      v
publish-image
  - QEMU 및 Docker Buildx 준비
  - GHCR 인증
  - AMD64·ARM64 멀티스테이지 Docker 이미지 빌드
  - 커밋 SHA 기반 태그 지정
  - GHCR 이미지 게시
```

이미지 빌드에는 기존 멀티스테이지 Dockerfile을 사용한다.

- Builder Stage: `eclipse-temurin:21-jdk` 환경에서 Gradle `bootJar` 실행
- Runtime Stage: `eclipse-temurin:21-jre` 환경에 생성된 JAR 복사
- 최종 이미지: Java 21 런타임과 MyShelf 애플리케이션 포함

`verify-image`는 `docker/build-push-action@v6`의 `push: false` 설정으로 이미지 빌드만 수행한다.

반면 `publish-image`는 `push: true`로 이미지를 GHCR에 게시하도록 구성했다.

이미지 빌드 성공은 컨테이너 실행이나 애플리케이션의 정상 기동까지 보장하지 않는다. 게시된 이미지의 실행 검증은 별도로 진행한다.

## 3. 테스트 데이터베이스 구성

### 3.1 환경별 DB 구성

| 항목 | 로컬 개발 환경 | CI 환경 |
|---|---|---|
| 실행 방식 | Docker Compose | GitHub Actions `services` |
| PostgreSQL | `postgres:17` | `postgres:17` |
| 테스트 DB | `myshelf_test` | `myshelf_test` |
| DB 사용자 | `myshelf` | `myshelf` |
| DB 생성 | Docker 초기화 SQL | `POSTGRES_DB` 환경변수 |
| 스키마 관리 | Flyway | Flyway |
| Spring 프로필 | `test` | `test` |

로컬 환경은 개발 DB `myshelf`와 테스트 DB `myshelf_test`를 동일한 PostgreSQL 컨테이너 안에서 분리한다.

CI의 `build-and-test` Job은 독립적인 PostgreSQL 서비스 컨테이너를 사용하며 개발자 로컬 DB에 접근하지 않는다.

`verify-image`와 `publish-image` Job은 PostgreSQL 서비스를 실행하지 않고 Docker 이미지 빌드 작업만 수행한다.

### 3.2 CI 데이터베이스 초기화

CI에서는 PostgreSQL 이미지의 `POSTGRES_DB` 설정으로 `myshelf_test` 데이터베이스를 생성한다.

PostgreSQL 서비스에는 `pg_isready` 기반 Health Check를 구성한다.

Spring Boot 테스트는 다음 연결 정보를 사용한다.

- JDBC URL: `jdbc:postgresql://localhost:5432/myshelf_test`
- 사용자: `myshelf`
- 비밀번호: Job 환경변수 `DB_PASSWORD`
- 활성 프로필: `test`

CI 비밀번호와 JWT Secret은 운영 비밀값과 분리된 테스트 전용 값을 사용한다.

테스트 DB의 테이블 및 제약조건은 Flyway 마이그레이션으로 구성한다.

실제 CI 실행에서 빈 데이터베이스에 Flyway V1 초기 마이그레이션이 성공적으로 적용되는 것을 확인했다.

## 4. 검증 및 실패 처리

위 구성은 현재 Workflow 파일을 기준으로 한다. 아래 32개 테스트·V1 검증은 초기 CI 구축 당시의 기록이며, 최신 Feature 5 전체 검증 결과로 해석하지 않는다.

### 4.1 검증 결과

GitHub Actions 및 로컬 Docker 환경에서 다음 항목을 확인했다.

| 검증 항목 | 환경 | 결과 |
|---|---|---|
| PostgreSQL 연결 | CI | 성공 |
| Spring `test` 프로필 활성화 | CI | 성공 |
| Flyway V1 마이그레이션 | CI | 성공 |
| Spotless 검사 | CI | 성공 |
| 전체 32개 테스트 | CI | 통과 |
| Gradle 빌드 | CI | 성공 |
| 의도적 테스트 실패 감지 | CI | 확인 |
| 실패 상황에서 테스트 리포트 업로드 | CI | 확인 |
| main 병합 후 CI 실행 | CI | 성공 |
| Docker 멀티스테이지 이미지 빌드 | CI | 성공 |
| PR에서 `verify-image` 실행 | CI | 성공 |
| PR에서 `publish-image` 건너뛰기 | CI | 확인 |
| main Push에서 `publish-image` 실행 | CI | 성공 |
| GHCR 이미지 게시 | GHCR | 성공 |
| GHCR 이미지 다운로드 | 로컬 Docker | 성공 |
| 게시된 이미지로 컨테이너 실행 | 로컬 Docker | 성공 |
| PostgreSQL 연결 및 Flyway V1 적용 | 로컬 Docker | 성공 |
| Spring Boot 정상 기동 | 로컬 Docker | 성공 |
| 회원가입 API 요청 | 로컬 Docker | 성공 |
| 로그인 API 요청 | 로컬 Docker | HTTP 200, Access Token 발급 확인 |

기존 CI 구축 과정에서는 의도적 JUnit 실패 테스트를 통해 테스트 실패가 Gradle 및 GitHub Actions Job 실패로 전파되는 것을 확인했다.

이후 Docker 이미지 빌드 검증 Job을 추가하여 PR에서 `build-and-test`와 `verify-image`가 모두 성공하는 것을 확인했다.

PR #16에서는 `publish-image`가 Skipped 처리되었으며, 병합 이후 `main` Push 이벤트에서는 GHCR 이미지 게시가 정상적으로 완료되었다.

게시된 이미지를 로컬 환경으로 다운로드한 뒤 기존 Docker Compose의 PostgreSQL 컨테이너와 연결하여 실행했다.

실행 로그에서 PostgreSQL 연결, Flyway V1 마이그레이션, JPA 초기화 및 Spring Boot 정상 기동을 확인했다.

추가로 회원가입 및 로그인 API를 호출하여 애플리케이션이 실제 HTTP 요청을 처리하고 PostgreSQL과 연동하는 것까지 검증했다.

초기 검증 당시에는 Docker 이미지 빌드 실패 유도와 운영 서버 배포·CD 검증이 범위 밖이었다. 이후 SSH 기반 EC2 CD와 기본 HTTP 응답은 검증했다. 의도적인 이미지 빌드 실패 검증과 자동 롤백은 아직 수행하지 않았다.

CI 구축 과정은 [GitHub Actions CI 학습 문서](../learning/infrastructure/github-actions-ci.md)에 기록하며, GHCR 게시 및 실행 과정은 별도의 학습 문서에서 정리한다.

### 4.2 테스트 리포트

Gradle 테스트 결과는 다음 경로에 생성된다.

- HTML: `build/reports/tests/test/`
- XML: `build/test-results/test/`

GitHub Actions는 해당 파일을 `test-reports` Artifact로 업로드한다.

- 보관 기간: 7일
- 업로드 조건: `if: ${{ !cancelled() }}`
- 테스트가 실패하더라도 Workflow가 취소되지 않았다면 업로드 시도

이를 통해 테스트 실패 이후에도 생성된 리포트를 확인할 수 있도록 구성했다.

## 5. 이미지 게시와 EC2 자동 배포

### 5.1 GHCR 이미지 게시 자동화

GitHub Actions에 `publish-image` Job을 추가하여 GHCR 이미지 게시 자동화를 구성하고, 실제 게시 및 실행 검증을 완료했다.

주요 설정은 다음과 같다.

- `main` Push 이벤트에서만 실행
- `build-and-test` 성공 이후 실행
- `GITHUB_TOKEN`으로 GHCR 인증
- `packages: write` 권한을 게시 Job에만 부여
- QEMU·Docker Buildx로 기존 멀티스테이지 Dockerfile의 AMD64·ARM64 이미지 빌드
- 커밋 SHA 기반 이미지 태그 사용
- `push: true`로 GHCR에 이미지 게시

게시 대상 이미지 이름은 다음과 같다.

```text
ghcr.io/pickylemon/myshelf:sha-<커밋 SHA>
```

PR에서는 `verify-image`를 통해 이미지 빌드 가능 여부만 검증한다.

`main`에서는 `publish-image`가 이미지를 다시 빌드하여 GHCR에 게시한다. 따라서 PR에서 검증한 이미지와 `main`에서 게시한 이미지는 별도로 생성된 빌드 결과물이다.

#### GHCR 게시 및 실행 검증

PR #16 병합 이후 `main` Push 이벤트로 `publish-image`가 실행되었으며, GHCR 이미지 게시가 성공했다.

게시된 이미지는 Private 패키지로 관리한다.

로컬 환경에서는 `read:packages` 권한을 가진 Personal Access Token (classic)으로 GHCR에 인증한 뒤 이미지를 다운로드했다.

이후 다운로드한 이미지를 `docker run`으로 실행하고 기존 PostgreSQL 컨테이너와 연결했다.

검증 과정에서 다음 결과를 확인했다.

- GHCR 이미지 Pull 성공
- 게시된 이미지로 컨테이너 실행 성공
- `local` 프로필 활성화
- PostgreSQL 연결 및 Flyway V1 마이그레이션 성공
- Spring Boot 정상 기동
- 회원가입 API를 통한 테스트 사용자 등록 성공
- 로그인 API HTTP 200 및 Access Token 발급 확인

이를 통해 **CI에서 빌드하고 GHCR에 게시한 이미지를 로컬에서 다시 빌드하지 않고도 실제 애플리케이션 실행에 사용할 수 있음**을 확인했다.

위 내용은 초기 GHCR 게시·로컬 실행 검증 기록이다. 이후 EC2 자동 배포를 구현했다. 현재는 CI의 Git 커밋 SHA 태그를 전달해 배포 버전을 연결하며, 이미지 Digest 고정은 적용하지 않았다.

### 5.2 Jenkins 학습

GitHub Actions를 주 CI 도구로 유지하면서 Jenkins Pipeline을 별도로 실습한다.

Jenkinsfile, 빌드 자동화, 실행 환경 및 관리 방식의 차이를 학습하되, 두 CI 도구를 동시에 운영하는 구조는 도입하지 않는다.

### 5.3 운영 배포 및 CD

CD Workflow는 `.github/workflows/cd.yaml`, EC2 실행 절차는 `scripts/deploy-ec2.sh`에 정의한다. AWS EC2 ARM64 `t4g.small`의 `/opt/myshelf`에서 공통·운영 Compose 파일을 병합해 실행한다.

| 항목 | 현재 구현 |
|---|---|
| 시작 이벤트 | 이름이 `CI`인 워크플로의 `workflow_run: completed` |
| 배포 조건 | 선행 CI 성공, `push` 이벤트, `main` 브랜치, 원본 저장소 `pickylemon/myshelf` |
| 배포 버전 | `workflow_run.head_sha`로 파일 Checkout 및 `sha-<SHA>` 이미지 선택 |
| AWS 인증 | GitHub OIDC로 IAM Role의 임시 자격 증명 획득 |
| EC2 접근 | Runner IPv4 `/32`의 SSH 규칙 임시 추가, 호스트 키 검증 후 SSH·SCP |
| 전달 파일 | `compose.yaml`, `compose.prod.yaml`, `deploy-ec2.sh`. 운영 `.env`는 전송하지 않음 |
| 동시 배포 | 고정 concurrency 그룹, `cancel-in-progress: false`. 엄격한 순차 큐·최신 커밋 우선 보장은 아님 |
| 정리 | `always()` 조건으로 생성한 보안 그룹 Rule ID 삭제 시도 |

```text
main Push → CI(build-and-test → publish-image) → GHCR
                        ↓ CI 성공 완료 이벤트
CD: CI head_sha Checkout → OIDC → 임시 SSH 허용 → 배포 파일 전송
                        ↓
EC2: 이미지 사전 Pull → .env 이미지 항목 갱신 → Compose up
                        ↓
실행 이미지 태그·HTTP 확인 → 성공 이미지·배포 이력 기록
                        ↓
Runner: 임시 SSH 규칙 제거
```

운영 `.env`의 비밀정보는 EC2에서 관리하며, 배포 스크립트는 `MYSHELF_IMAGE`만 갱신한다. `.env` 갱신 이후 실패해도 자동 복원하지 않는다. 마지막 검증 성공 이미지는 `.last-successful-image`, 시도·성공·실패는 `deployment-history.log`에 남긴다.

현재 HTTP 검사는 EC2 내부의 `http://127.0.0.1:8080/v3/api-docs`를 사용한다. 앱 포트는 루프백에만 바인딩하며 PostgreSQL·Redis 호스트 포트는 게시하지 않는다. 이 확인은 외부 공개, 전체 비즈니스 API, 데이터 복구 또는 무중단 배포를 보장하지 않는다.

SSM 전환, 전용 Health Check, Digest 고정, 롤백, HTTPS·리버스 프록시 및 운영 모니터링은 후속 범위다. 상세 원리와 단계별 코드는 [CD 학습](../learning/infrastructure/github-actions-cd.md), [OIDC·IAM](../learning/infrastructure/github-oidc-aws-iam.md), [EC2 배포 스크립트](../learning/infrastructure/ec2-automated-deployment.md)를 참고한다.

## 6. 관련 문서

- CI 구현: `.github/workflows/ci.yaml`
- CD 구현: `.github/workflows/cd.yaml`, `scripts/deploy-ec2.sh`
- Docker 이미지 빌드 구성: `Dockerfile`, `.dockerignore`
- CI 개념 및 검증 과정: [GitHub Actions CI 학습](../learning/infrastructure/github-actions-ci.md)
- 개발 일정 및 후속 작업: [로드맵](../planning/roadmap.md)
- GHCR 이미지 게시 자동화: GitHub Issue #15
- GHCR 이미지 게시 및 실행 검증: [GitHub Container Registry 학습](../learning/infrastructure/github-container-registry.md)
- CD 검증 기록: [GitHub Actions CD](../learning/infrastructure/github-actions-cd.md#7-실제-최초-통합-검증)
- 배포 장애 진단: [CD 트러블슈팅](../troubleshooting/github-actions-cd-troubleshooting.md)

구체적인 구현 작업과 검증 결과는 GitHub Issues에서 관리한다.
