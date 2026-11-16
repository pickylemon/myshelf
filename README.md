# MyShelf

개인 독서 기록 및 서재 관리 서비스입니다.

단순 CRUD 구현을 넘어 Spring Boot 기반 백엔드 설계와
헥사고날 아키텍처 적용을 통해 도메인과 외부 기술의 의존성을 분리하고,
인증/인가, 캐싱, 비동기 이벤트 처리, 컨테이너 기반 실행 환경을
직접 구성하며 백엔드 전반의 설계와 운영 관점을 학습하는 것을 목표로 합니다.

---

## 1. 주요 기능

**현재 구현**

- 회원가입, 이메일·비밀번호 로그인 및 JWT 발급
- OAuth2 Resource Server 기반 JWT 요청 인증
- Shelf 생성·목록 조회·상세 조회·수정·삭제 (Feature 4)
- 인증된 사용자 UUID 기반 소유권 검증, 사용자별 Shelf 이름 중복 방지
- 요청 Validation 및 ProblemDetail 오류 응답
- 외부 도서 통합 검색·더 보기 및 사용자별 등록 서재 정보 조회 (Feature 5)
- Redis 검색 세션 저장·만료와 단일 서버의 동일 세션 중복 요청 차단
- UserBook 등록·재사용, ShelfBook 연결 및 ISBN별 메타데이터 캐시
- 서재별 도서 목록 조회·메모 수정, 독서 상태 변경 및 ShelfBook 다건 삭제

Feature 5는 구현·테스트 후 main에 병합했습니다(PR #25). 이후 멀티 플랫폼 이미지 게시와 EC2 자동 배포를 구현하고, PR #36에서 S3·SSM 방식으로 전환했습니다. 기능 검증은 [Feature 5 기록](docs/planning/feature-5-book-management.md#6-구현-단계-및-완료-기준), 자동 배포 결과는 [CD 검증 기록](docs/learning/infrastructure/github-actions-cd.md#8-ssm-전환-및-실제-배포-검증)에 정리합니다.

**이후 범위**

- 포트폴리오 1차 정리 및 운영 보완: 전용 상태 점검, 실패 복구 검증·외부 공개 구성
- Feature 6: 전체 도서·상세 조회, Shelf 내 순서 변경 및 검색 응답 보완
- Feature 7: 문장 수집·필사
- 후속 검토: 독서 통계, 일반 조회 캐싱, Kafka 기반 비동기 처리 및 AI Agent

진행 상태와 계획은 [로드맵](docs/planning/roadmap.md)에서 관리합니다.

---

## 2. 기술 스택

### Backend

- Java 21
- Spring Boot
- Spring Web
- Spring Security
- Spring Data JPA
- Validation
- Gradle

### Database / Infrastructure

- PostgreSQL
- Redis (검색 세션 저장·ISBN별 도서 메타데이터 캐시)
- Docker
- Docker Compose
- GitHub Actions CI·CD, GHCR 멀티 플랫폼 이미지, S3·SSM 기반 AWS EC2 ARM64 배포

Redis는 검색 진행 상태와 ISBN별 도서 메타데이터를 별도 키로 저장합니다. 일반 조회 캐시와 Kafka는 후속 검토 대상입니다.

CI가 같은 SHA의 이미지와 S3 배포 파일을 게시하면 CD가 SSM으로 EC2의 릴리스를 전환·배포하고 기본 HTTP 응답을 확인합니다. 이전 릴리스 자동 복구 시도는 구현했지만 실제 실패 복구는 별도 검증이 필요합니다. 외부 공개·무중단 배포는 아직 포함하지 않습니다. [CI/CD 아키텍처](docs/architecture/ci-cd.md)를 참고합니다.

### Test

- JUnit 5
- Spring Boot Test

---

## 3. Architecture

헥사고날 아키텍처를 기반으로 구성합니다.

```text
com.myshelf
├── user
├── auth
├── book
├── shelf
│   ├── domain
│   ├── application
│   │   ├── port/in
│   │   ├── port/out
│   │   └── service
│   └── adapter
│       ├── in/web
│       └── out/persistence
└── global
```

계층별 책임은 [아키텍처 개요](docs/architecture/overview.md)를 참고합니다.

---

## 4. 로컬 실행 환경

MyShelf는 Docker Compose로 Spring Boot, PostgreSQL, Redis 서비스를 구성합니다. 로컬에서는 `compose.yaml`과 자동 병합되는 `compose.override.yaml`을 사용하며, 앱 프로필은 `local,redis`입니다.

### 사전 요구사항

- Docker Desktop 또는 Docker Engine + Compose
- Docker를 사용하지 않고 애플리케이션을 직접 실행하는 경우 Java 21

### 환경변수 설정

프로젝트 루트에 `.env` 파일을 생성하고 다음 변수를 설정합니다.

```dotenv
JWT_SECRET=<Base64로 인코딩된 JWT 서명키>
DB_PASSWORD=<PostgreSQL 비밀번호>
NATIONAL_LIBRARY_API_KEY=<국립중앙도서관 API 키>
```

JWT 서명키는 **Base64 디코딩 결과가 최소 32바이트**여야 합니다.

`.env`는 Git에 포함하지 않습니다.

IntelliJ에서 Spring Boot를 직접 실행하는 경우 Run Configuration에서 다음을 설정합니다.

- Active profiles: `local,redis` (검색 세션을 InMemory에 저장하려면 `redis` 제외)
- Environment variables: `JWT_SECRET`, `DB_PASSWORD`, `NATIONAL_LIBRARY_API_KEY`

IntelliJ에서는 `.env`가 기본적으로 자동 적용되지 않으므로 환경변수를 별도로 등록해야 합니다.

### Docker Compose 실행

프로젝트 루트에서 다음 명령을 실행합니다.

`compose.yaml`의 `environment` 선언을 통해 JWT 서명키·DB 비밀번호·국립중앙도서관 API 키를 컨테이너에 전달합니다. 로컬 빌드·프로필·DB 초기화 SQL·DB/Redis 호스트 포트는 `compose.override.yaml`에 정의합니다. 아래 명령은 별도 `COMPOSE_FILE` 설정이 없는 경우를 기준으로 합니다.

```bash
# 이미지 빌드 및 컨테이너 실행
docker compose up -d --build

# 컨테이너 상태 확인
docker compose ps

# 애플리케이션 로그 확인
docker compose logs app

# 컨테이너 종료 및 제거
docker compose down
```

운영에서는 로컬 Override 파일을 제외하도록 `-f compose.yaml -f compose.prod.yaml`을 명시합니다. 운영 이미지와 환경변수 구성은 [Compose 환경 분리 ADR](docs/decisions/013-compose-environment-separation.md#5-실행-및-운영-규칙)을 참고합니다.

실행 후 접근 주소:

| 서비스 | 주소 |
|---|---|
| Spring Boot | `http://localhost:8080` |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |

PostgreSQL 데이터는 Docker Named Volume에 보관되며, 일반적인 `docker compose down`으로는 삭제되지 않습니다.

**주의:** `docker compose down -v`는 데이터가 저장된 볼륨까지 삭제할 수 있습니다.

### 테스트 및 API 문서

통합 테스트는 `myshelf_test`와 Redis 논리 DB 1을 사용합니다. PostgreSQL·Redis를 실행하고 Gradle 실행 환경에 테스트 DB의 `DB_PASSWORD`를 설정합니다. 검색 통합 테스트는 `test,redis` 프로필을 사용하며 외부 검색만 Mock으로 대체합니다. 테스트용 JWT Secret과 가짜 외부 API 키는 테스트 설정에 정의되어 있습니다.

```bash
./gradlew clean build
```

Windows PowerShell에서는 `.\gradlew.bat clean build`를 사용합니다. 이 명령은 테스트, Spotless 검사 및 패키징을 포함하며 CI 통과 여부는 PR에서 별도로 확인합니다.

Swagger UI 기본 경로는 `/swagger-ui.html`, OpenAPI JSON은 `/v3/api-docs`입니다. 의존성과 공개 경로는 구성되어 있으나, JWT Bearer 문서 설정 및 실제 UI 실행 검증은 아직 남아 있습니다. [현재 상태](docs/architecture/overview.md#swagger--openapi-현황)를 참고합니다.

### 관련 문서

- [Dockerfile 멀티스테이지 빌드](docs/learning/infrastructure/dockerfile-multistage.md)
- [PostgreSQL Docker Compose 구성](docs/learning/infrastructure/docker-compose-postgresql.md)
- [Spring Boot와 PostgreSQL 통합 실행](docs/learning/infrastructure/docker-compose-spring-boot-postgresql.md)
- [Docker 환경변수와 Spring Boot 설정](docs/learning/infrastructure/docker-environment-variables.md)
- [도서 검색 설계](docs/architecture/book-search.md)
- [Redis 검색 세션](docs/learning/infrastructure/redis-search-session.md)
- [도서 검색 테스트 전략](docs/learning/testing/book-search-testing-strategy.md)
