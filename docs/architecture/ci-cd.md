# MyShelf CI/CD 아키텍처

> 상태: CI 구축 및 main 병합 완료 / Branch Ruleset 적용 / Docker 이미지 빌드 검증 완료 / GHCR·CD 미구현
> 작성일: 2026-10-21
> 범위: 현재 CI 구성 및 향후 이미지 게시·CD 확장 방향

## 1. 개요

MyShelf는 GitHub Actions를 이용하여 코드 변경사항에 대한 빌드, 테스트 및 Docker 이미지 빌드 검증을 자동화한다.

기존에는 개발자가 로컬에서 직접 검증했지만, CI 도입 이후에는 GitHub의 독립적인 실행 환경에서도 동일한 테스트를 수행하고 Docker 이미지 생성 가능 여부를 확인한다.

주요 목적은 다음과 같다.

- Pull Request 단위의 자동화된 검증
- 로컬 환경에 의존하지 않는 빌드 및 테스트
- PostgreSQL 통합 테스트의 독립성과 재현성 확보
- 배포 가능한 Docker 이미지의 빌드 검증
- 향후 이미지 게시 및 CD 자동화 기반 마련

현재는 **Gradle 빌드·테스트 자동화와 PR 단계의 Docker 이미지 빌드 검증까지 구현**했다.

GHCR 이미지 게시, Jenkins 실습, 운영 배포 자동화는 후속 작업으로 진행한다.

## 2. GitHub Actions CI 구성

### 2.1 Workflow

CI Workflow는 `.github/workflows/ci.yml`에 정의한다.

| 항목 | 구성 |
|---|---|
| CI 도구 | GitHub Actions |
| Runner | `ubuntu-latest` |
| Java | Temurin JDK 21 |
| Gradle | Gradle Wrapper 9.7.1 |
| PostgreSQL | `postgres:17` |
| Spring 프로필 | `test` |
| Gradle 빌드 명령어 | `bash ./gradlew clean build --no-daemon` |
| Docker 이미지 빌드 | Docker Buildx |
| Workflow 권한 | `contents: read` |

GitHub Actions를 주 CI 도구로 사용하며, 별도의 Jenkins 서버는 운영하지 않는다.

현재 Workflow는 두 개의 Job으로 구성된다.

| Job | 역할 | 실행 조건 |
|---|---|---|
| `build-and-test` | Gradle 빌드, Spotless 검사, PostgreSQL 통합 테스트 | PR, main Push, 수동 실행 |
| `verify-image` | Docker 이미지 빌드 가능 여부 검증 | PR, `build-and-test` 성공 후 |

`verify-image`는 `needs: build-and-test`를 통해 선행 Job에 의존한다.

### 2.2 실행 조건

| 이벤트 | 목적 |
|---|---|
| `pull_request` → `main` | PR 병합 전 빌드·테스트 및 이미지 빌드 검증 |
| `push` → `main` | 메인 브랜치 반영 후 빌드·테스트 검증 |
| `workflow_dispatch` | 수동 빌드·테스트 실행 |

PR 생성 및 업데이트 시 CI가 실행되는 것을 확인했다.

PR #13 병합 이후 `main` 브랜치의 `push` 이벤트로 CI가 실행되었으며 정상 완료를 확인했다.

이미지 빌드 검증 Job인 `verify-image`는 현재 PR 이벤트에서만 실행된다.

#### 브랜치 보호 정책

`main` 브랜치에 GitHub Branch Ruleset을 적용하여 다음 정책을 구성했다.

- Pull Request를 통한 변경사항 병합 필수 (`Require a pull request before merging`)
- 필수 CI 상태 검사 `build-and-test` 통과 요구 (`Require status checks to pass`)
- 브랜치 삭제 제한 (`Restrict deletions`)
- 강제 Push 차단 (`Block force pushes`)
- 필수 리뷰 승인 수 0으로 설정

이를 통해 필수 CI 검사에 실패한 변경사항이 `main`에 병합되지 않도록 제한한다.

현재 `verify-image`는 필수 상태 검사에 등록하지 않았으며, GHCR 게시 작업의 PR 병합 전에 추가할 예정이다.

### 2.3 실행 흐름

PR에서 실행되는 CI는 다음 구조로 동작한다.

```text
pull_request → main
        |
        v
  build-and-test
  - PostgreSQL 서비스 준비
  - JDK 21 및 Gradle 환경 구성
  - Gradle 빌드
  - Spotless 검사
  - Flyway 마이그레이션
  - PostgreSQL 통합 테스트
  - 테스트 리포트 업로드
        |
        | 성공 시
        v
   verify-image
  - Docker Buildx 준비
  - 멀티스테이지 Docker 이미지 빌드
  - Registry 게시 없음
        |
        v
   PR 검증 완료
```

`build-and-test`가 실패하면 `verify-image`는 실행되지 않는다.

`verify-image`는 기존 멀티스테이지 Dockerfile을 사용한다.

- Builder Stage: `eclipse-temurin:21-jdk` 환경에서 Gradle `bootJar` 실행
- Runtime Stage: `eclipse-temurin:21-jre` 환경에 생성된 JAR 복사
- 최종 이미지: Java 21 런타임과 MyShelf 애플리케이션 포함

이미지 빌드에는 `docker/build-push-action@v6`을 사용하며, `push: false`로 설정하여 Registry에 게시하지 않는다.

따라서 현재 단계에서는 **이미지 생성 가능 여부만 검증**하며, 컨테이너 실행과 애플리케이션 정상 기동까지 확인하지는 않는다.

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

`verify-image` Job은 현재 PostgreSQL 서비스 없이 Docker 이미지만 빌드한다.

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

### 4.1 검증 결과

GitHub Actions 환경에서 다음 항목을 확인했다.

| 항목 | 결과 |
|---|---|
| PostgreSQL 연결 | 성공 |
| Spring `test` 프로필 활성화 | 성공 |
| Flyway V1 마이그레이션 | 성공 |
| Spotless 검사 | 성공 |
| 전체 32개 테스트 | 통과 |
| Gradle 빌드 | 성공 |
| 의도적 테스트 실패 감지 | 확인 |
| 실패 상황에서 테스트 리포트 업로드 | 확인 |
| Docker 멀티스테이지 이미지 빌드 | 성공 |
| `verify-image` Job 실행 | 성공 |

기존 CI 구축 과정에서는 의도적 JUnit 실패 테스트를 통해 테스트 실패가 Gradle 및 GitHub Actions Job 실패로 전파되는 것을 확인했다.

이후 Docker 이미지 빌드 검증 Job을 추가하고, PR에서 `build-and-test`와 `verify-image`가 모두 성공하는 것을 확인했다.

다만 Docker 이미지 빌드 실패 상황을 별도로 검증하거나, 생성된 이미지로 컨테이너를 실행하는 검증은 아직 수행하지 않았다.

검증 과정과 학습 내용은 [GitHub Actions CI 학습 문서](../learning/infrastructure/github-actions-ci.md)에 기록한다.

### 4.2 테스트 리포트

테스트 결과는 다음 경로에 생성된다.

- HTML: `build/reports/tests/test/`
- XML: `build/test-results/test/`

GitHub Actions는 해당 파일을 `test-reports` Artifact로 업로드한다.

- 보관 기간: 7일
- 업로드 조건: `if: ${{ !cancelled() }}`
- 테스트가 실패하더라도 Workflow가 취소되지 않았다면 업로드 시도

이를 통해 테스트 실패 이후에도 생성된 리포트를 확인할 수 있도록 구성했다.

## 5. 향후 확장 계획

### 5.1 Docker 이미지 게시 자동화

PR 단계에서 Docker 이미지 빌드가 성공하는 것을 확인했으며, 다음으로 GHCR 게시 자동화를 구현한다.

목표는 다음과 같다.

- `main` 브랜치 반영 시 CI 성공 후 Docker 이미지 빌드
- GitHub Container Registry(GHCR)에 이미지 게시
- `GITHUB_TOKEN` 기반 인증 및 최소 권한 적용
- 커밋 식별자 기반 이미지 태그 관리
- GHCR에 게시된 이미지 다운로드 및 실행 검증
- 이미지 빌드 검증을 필수 PR 상태 검사로 등록

이미지 게시 Job은 기존 `build-and-test` 결과에 의존하도록 구성할 예정이다.

PR에서는 이미지 빌드만 수행하고, Registry 게시 작업은 `main` 브랜치 반영 이후 실행하는 방향으로 설계한다.

구체적인 인증, 태그, 게시 조건은 구현 과정에서 확정한다.

### 5.2 Jenkins 학습

GitHub Actions를 주 CI 도구로 유지하면서 Jenkins Pipeline을 별도로 실습한다.

Jenkinsfile, 빌드 자동화, 실행 환경 및 관리 방식의 차이를 학습하되, 두 CI 도구를 동시에 운영하는 구조는 도입하지 않는다.

### 5.3 운영 배포 및 CD

실제 운영 배포 자동화는 핵심 기능 개발 이후 진행한다.

주요 검토 대상은 다음과 같다.

- 배포 대상 서버 및 실행 환경
- 운영 PostgreSQL 및 환경변수 관리
- GHCR 이미지 기반 배포
- 배포 후 Health Check
- 배포 실패 시 복구 및 롤백

초기에는 단일 서버 기반의 단순한 구성을 우선 검토하며, Kubernetes 도입을 전제로 하지 않는다.

## 6. 관련 문서

- CI 구현: `.github/workflows/ci.yml`
- Docker 이미지 빌드 구성: `Dockerfile`, `.dockerignore`
- CI 개념 및 검증 과정: [GitHub Actions CI 학습](../learning/infrastructure/github-actions-ci.md)
- 개발 일정 및 후속 작업: [로드맵](../planning/roadmap.md)

구체적인 구현 작업은 GitHub Issues에서 관리한다.
