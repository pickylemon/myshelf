# MyShelf

개인 독서 기록 및 서재 관리 서비스입니다.

단순 CRUD 구현을 넘어 Spring Boot 기반 백엔드 설계와
헥사고날 아키텍처 적용을 통해 도메인과 외부 기술의 의존성을 분리하고,
인증/인가, 캐싱, 비동기 이벤트 처리, 컨테이너 기반 실행 환경을
직접 구성하며 백엔드 전반의 설계와 운영 관점을 학습하는 것을 목표로 합니다.

---

## 1. 주요 기능

- 회원가입 / 로그인
- JWT 발급
- JWT 기반 보호 API 인증 (예정)
- 도서 등록 / 조회 / 수정 / 삭제
- 내 서재 관리
- 독서 상태 관리
    - 읽고 싶은 책
    - 읽는 중
    - 완독
- 완독 기록 및 독서 통계
- 도서 조회 캐싱
- 비동기 이벤트 처리

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
- Redis
- Apache Kafka
- Docker
- Docker Compose

### Test

- JUnit 5
- Spring Boot Test

---

## 3. Architecture

헥사고날 아키텍처를 기반으로 구성합니다.

```text
domain
├── model
└── ...

application
├── port
│   ├── in
│   └── out
└── service

adapter
├── in
│   └── web
└── out
    ├── persistence
    ├── redis
    └── kafka
```

---

## 4. 로컬 실행 환경

MyShelf는 Docker Compose를 이용해 Spring Boot와 PostgreSQL을 함께 실행할 수 있습니다.

### 사전 요구사항

- Docker Desktop 또는 Docker Engine + Compose
- Docker를 사용하지 않고 애플리케이션을 직접 실행하는 경우 Java 21

### 환경변수 설정

프로젝트 루트에 `.env` 파일을 생성하고 다음 변수를 설정합니다.

```dotenv
JWT_SECRET=<Base64로 인코딩된 JWT 서명키>
DB_PASSWORD=<PostgreSQL 비밀번호>
```

JWT 서명키는 **Base64 디코딩 결과가 최소 32바이트**여야 합니다.

출력된 값을 `JWT_SECRET`에 설정합니다. `.env`는 Git에 포함하지 않습니다.

IntelliJ에서 Spring Boot를 직접 실행하는 경우 Run Configuration에서 다음을 설정합니다.

- Active profiles: `local`
- Environment variables: `JWT_SECRET`, `DB_PASSWORD`

IntelliJ에서는 `.env`가 기본적으로 자동 적용되지 않으므로 환경변수를 별도로 등록해야 합니다.

### Docker Compose 실행

프로젝트 루트에서 다음 명령을 실행합니다.

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

실행 후 접근 주소:

| 서비스 | 주소 |
|---|---|
| Spring Boot | `http://localhost:8080` |
| PostgreSQL | `localhost:5432` |

PostgreSQL 데이터는 Docker Named Volume에 보관되며, 일반적인 `docker compose down`으로는 삭제되지 않습니다.

**주의:** `docker compose down -v`는 데이터가 저장된 볼륨까지 삭제할 수 있습니다.

### 관련 문서

- [Dockerfile 멀티스테이지 빌드](docs/learning/infrastructure/dockerfile-multistage.md)
- [PostgreSQL Docker Compose 구성](docs/learning/infrastructure/docker-compose-postgresql.md)
- [Spring Boot와 PostgreSQL 통합 실행](docs/learning/infrastructure/docker-compose-spring-boot-postgresql.md)
- [Docker 환경변수와 Spring Boot 설정](docs/learning/infrastructure/docker-environment-variables.md)
