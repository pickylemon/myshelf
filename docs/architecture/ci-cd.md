# MyShelf CI/CD 아키텍처

> 상태: CI 구현 및 main 병합 완료 / Branch Ruleset 적용 / CD 미구현
> 작성일: 2026-10-21
> 범위: 현재 CI 구성 및 향후 CD 확장 방향

## 1. 개요

MyShelf는 GitHub Actions를 이용하여 코드 변경사항에 대한 빌드와 테스트를 자동화한다.

기존에는 개발자가 로컬에서 직접 검증했지만, CI 도입 이후에는 GitHub의 독립적인 실행 환경에서 동일한 테스트를 수행한다.

주요 목적은 다음과 같다.

- Pull Request 단위의 자동화된 검증
- 로컬 환경에 의존하지 않는 빌드 및 테스트
- PostgreSQL 통합 테스트의 독립성과 재현성 확보
- 향후 Docker 이미지 게시 및 CD 자동화 기반 마련

현재는 **빌드 및 테스트 자동화까지만 구현**했다. Docker 이미지 게시, Jenkins 실습, 운영 배포 자동화는 후속 작업으로 진행한다.

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
| 실행 명령어 | `bash ./gradlew clean build --no-daemon` |
| Workflow 권한 | `contents: read` |

GitHub Actions를 주 CI 도구로 사용하며, 별도의 Jenkins 서버는 운영하지 않는다.

### 2.2 실행 조건

| 이벤트 | 목적 |
|---|---|
| `pull_request` → `main` | PR 병합 전 검증 |
| `push` → `main` | 메인 브랜치 반영 후 검증 |
| `workflow_dispatch` | 수동 실행 |

PR 생성 및 업데이트 시 CI가 실행되는 것을 확인했다.

PR #13 병합 이후 `main` 브랜치에 대한 `push` 이벤트로 CI가 실행되었으며, 정상 완료를 확인했다.

### 브랜치 보호 정책

`main` 브랜치에 GitHub Branch Ruleset을 적용하여 다음 정책을 구성했다.

- Pull Request를 통한 변경사항 병합 필수 (`Require a pull request before merging`)
- 필수 CI 상태 검사 `build-and-test` 통과 요구 (`Require status checks to pass`)
- 브랜치 삭제 제한 (`Restrict deletions`)
- 강제 Push 차단 (`Block force pushes`)
- 필수 리뷰 승인 수는 0으로 설정하여 개인 프로젝트 환경에 맞게 운영

이를 통해 CI 검사에 실패한 변경사항이 `main`에 병합되지 않도록 제한한다.

### 2.3 실행 흐름

CI는 다음 순서로 동작한다.

1. GitHub Actions Workflow 실행
2. Ubuntu Runner 및 PostgreSQL 서비스 컨테이너 준비
3. 소스 코드 Checkout
4. JDK 21 및 Gradle 환경 구성
5. Gradle 빌드 실행
6. Spotless 코드 포맷 검사 및 전체 테스트 수행
7. Spring Boot `test` 프로필과 Flyway를 이용한 PostgreSQL 통합 테스트
8. 빌드·테스트 결과 반영 및 테스트 리포트 업로드

Gradle 작업이 실패하면 CI Job도 실패 처리된다.

현재 CI에서는 Docker 이미지 게시나 애플리케이션 배포를 수행하지 않는다.

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

반면 CI는 매 Job에 독립적인 PostgreSQL 서비스 컨테이너를 준비하므로 개발자 로컬 DB에 접근하지 않는다.

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

의도적 JUnit 실패 테스트를 이용하여 테스트 실패가 Gradle 빌드 및 GitHub Actions Job 실패로 전파되는 것을 확인했다.

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

### 5.1 Docker 이미지 빌드 및 게시

CI 구축 이후 다음 항목을 별도 작업으로 진행한다.

- CI 검증 성공 후 Docker 이미지 빌드
- GitHub Container Registry(GHCR)에 이미지 게시
- 커밋 식별자 기반 이미지 태그 관리
- 게시된 이미지 실행 검증

이미지 게시 권한과 실행 조건은 해당 작업에서 결정한다.

### 5.2 Jenkins 학습

GitHub Actions를 주 CI 도구로 유지하면서 Jenkins Pipeline을 별도로 실습한다.

Jenkinsfile, 빌드 자동화, 실행 환경 및 관리 방식의 차이를 학습하되, 두 CI 도구를 동시에 운영하는 구조는 도입하지 않는다.

### 5.3 운영 배포 및 CD

실제 운영 배포 자동화는 핵심 기능 개발 이후 진행한다.

주요 검토 대상은 다음과 같다.

- 배포 대상 서버 및 실행 환경
- 운영 PostgreSQL 및 환경변수 관리
- Docker 이미지 기반 배포
- 배포 후 Health Check
- 배포 실패 시 복구 및 롤백

초기에는 단일 서버 기반의 단순한 구성을 우선 검토하며, Kubernetes 도입을 전제로 하지 않는다.

## 6. 관련 문서

- CI 구현: `.github/workflows/ci.yml`
- CI 개념 및 검증 과정: [GitHub Actions CI 학습](../learning/infrastructure/github-actions-ci.md)
- 개발 일정 및 후속 작업: [로드맵](../planning/roadmap.md)

구체적인 구현 작업은 GitHub Issues에서 관리한다.
