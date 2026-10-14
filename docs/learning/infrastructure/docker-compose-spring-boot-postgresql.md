# Docker Compose로 Spring Boot와 PostgreSQL 통합 실행 — MyShelf

## 1. 작업 배경과 범위

기존 MyShelf 로컬 환경은 **IntelliJ에서 Spring Boot를 실행하고 PostgreSQL만 Docker Compose에서 실행**하는 구조였다. 이후 멀티스테이지 Dockerfile로 `myshelf-app:local` 이미지를 만드는 데 성공했다.

이번 단계에서는 기존 `compose.yaml`에 `app` 서비스를 추가하여 **Spring Boot와 PostgreSQL을 함께 실행**했다.

- 실행 환경: Windows + Docker Desktop
- Spring Boot 4.1.1 / Java 21
- PostgreSQL 17 (`17.11` 연결 로그 확인)
- Spring Profile: `local`
- DB 스키마: Flyway V1 적용 상태 유지

> **검증 범위:** 두 컨테이너 실행, Spring Boot 기동, PostgreSQL 연결, Flyway 검증, JPA 초기화, 회원가입·로그인 API 동작 및 컨테이너 재생성 후 데이터 유지까지 확인했다. 발급된 JWT를 이용한 보호 API 접근과 토큰 검증, macOS 실행은 검증하지 않았다.

---

## 2. 단일 컨테이너에서 여러 서비스 실행으로

### 이전 구조

```text
개발자 PC
├── IntelliJ → Spring Boot (:8080)
│                └── jdbc:postgresql://localhost:5432/myshelf
└── Docker Compose
     └── postgres (:5432) → Named Volume
```

### 변경 후 구조

```text
개발자 PC
│
├── localhost:8080 ────────┐
│                          ▼
│                   Docker Compose
│                   ├── app (:8080)
│                   │     │
│                   │     └── jdbc:postgresql://postgres:5432/myshelf
│                   │                         │
│                   └── postgres (:5432) ◀────┘
│                          │
│                          └── postgres-data (Named Volume)
│
└── localhost:5432 → postgres (개발 도구의 DB 접근용)
```

두 서비스는 Compose가 생성한 **기본 네트워크 `myshelf_default`**를 사용한다. PostgreSQL 데이터는 기존 Named Volume에 남는다.

---

## 3. compose.yaml에서 추가한 app 서비스

아래는 **실제 구성의 핵심을 설명하기 위한 발췌본**이다. 데이터베이스 비밀번호는 문서에 기록하지 않았으며, 현재 프로젝트의 PostgreSQL 서비스 및 나머지 설정을 그대로 유지한다.

```yaml
services:
  app:
    build:
      context: .
      dockerfile: Dockerfile
    image: myshelf-app:local
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/myshelf
      SPRING_DATASOURCE_USERNAME: myshelf
      # SPRING_DATASOURCE_PASSWORD: 로컬 PostgreSQL 비밀번호 (실제 설정에서 지정)
      JWT_SECRET: ${JWT_SECRET:?Set JWT_SECRET in .env or shell}
    depends_on:
      postgres:
        condition: service_healthy

  postgres:
    # 기존 postgres:17 이미지, 환경변수, 포트, Named Volume 및
    # pg_isready healthcheck 구성을 그대로 사용
```

| 설정 | 역할 |
|---|---|
| `build.context: .` | 프로젝트 루트를 Docker 이미지 빌드 컨텍스트로 사용 |
| `build.dockerfile` | 프로젝트 루트의 멀티스테이지 Dockerfile 지정 |
| `image: myshelf-app:local` | 빌드한 애플리케이션 이미지 이름과 태그 |
| `ports: 127.0.0.1:8080:8080` | 호스트의 루프백 주소 8080을 앱 컨테이너 8080으로 전달 |
| `SPRING_PROFILES_ACTIVE: local` | `application-local.yaml` 활성화 |
| `SPRING_DATASOURCE_*` | 컨테이너에서 사용할 JDBC 접속 정보를 Spring에 전달 |
| `depends_on.condition: service_healthy` | `postgres`의 healthcheck 통과 후 `app` 시작 |

`depends_on`의 `service_healthy`는 **최초 시작 시점의 준비 상태를 조정**한다. 실행 중 DB가 장애를 일으켜도 앱의 정상 동작이나 자동 복구까지 보장하지 않는다.

---

## 4. 컨테이너에서 localhost 대신 postgres를 쓰는 이유

개발자 PC에서 실행하는 Spring Boot의 DB 주소는 다음과 같았다.

```text
jdbc:postgresql://localhost:5432/myshelf
```

하지만 **앱 컨테이너 안의 `localhost`는 앱 컨테이너 자신**을 의미한다. 따라서 다른 컨테이너에서 실행 중인 DB에 접속하려면 Compose 서비스 이름을 이용한다.

```text
jdbc:postgresql://postgres:5432/myshelf
```

- `postgres`: 같은 Compose 네트워크에서 찾을 수 있는 서비스 이름
- `5432`: PostgreSQL 컨테이너가 수신하는 포트
- `myshelf`: 데이터베이스 이름

Compose 기본 네트워크에서는 서비스 이름을 기반으로 서로를 찾을 수 있으므로 별도의 IP 주소 고정이나 명시적 `networks` 설정이 필요하지 않았다.

**호스트 포트 매핑과 컨테이너 간 통신은 별개다.** `127.0.0.1:5432:5432`는 개발자 PC에서 DB에 접근할 때 사용하고, 앱과 DB 사이의 연결은 내부 네트워크에서 `postgres:5432`를 사용한다.

### application-local.yaml을 수정하지 않은 이유

IntelliJ의 로컬 실행에는 기존 `localhost:5432` 설정을 유지하고, 컨테이너 실행에서는 Compose의 `SPRING_DATASOURCE_URL` 환경변수가 Spring 설정을 덮어쓴다.

따라서 **로컬 IDE 실행과 Docker 실행을 위해 동일한 YAML 파일을 번갈아 수정하지 않아도 된다.**

### Spring Boot 설정 우선순위

Spring Boot는 `application.yaml`, `application-local.yaml`, 운영체제 환경변수 등 여러 설정 소스에서 속성을 읽는다.

동일한 속성이 여러 곳에 정의되어 있다면 우선순위가 높은 값이 적용된다.

이번 구성에서 관련된 우선순위는 다음과 같다.

```text
운영체제 환경변수 (우선순위 높음)
        ↑
application-local.yaml
        ↑
application.yaml
```

IntelliJ에서 직접 실행할 때는 `application-local.yaml`에 정의한 다음 설정을 사용한다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myshelf
```

Docker에서는 Compose가 애플리케이션 컨테이너에 환경변수를 전달한다.

```yaml
environment:
  SPRING_PROFILES_ACTIVE: local
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/myshelf
```

Spring Boot는 `SPRING_DATASOURCE_URL`을 `spring.datasource.url` 속성으로 인식한다.

따라서 Docker에서도 `local` 프로필을 사용하지만, 실제 JDBC 주소는 환경변수에서 전달한 `postgres:5432`가 적용된다.

**이는 YAML 파일을 수정하는 것이 아니라 애플리케이션 실행 시 최종 설정값을 결정하는 과정이다.**

관련 공식 문서: [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)

---

## 5. 환경변수와 .env의 역할

이번 구성에서는 JWT 서명키를 Git에 직접 기록하지 않고 `.env` 또는 셸 환경변수에서 읽도록 했다.

```yaml
JWT_SECRET: ${JWT_SECRET:?Set JWT_SECRET in .env or shell}
```

`${변수:?오류 메시지}`는 Compose가 변수를 치환할 때 **값이 없거나 비어 있으면 실패**하도록 한다.

```text
.env 또는 PowerShell 환경변수
             │
             ▼
Compose의 ${JWT_SECRET} 치환
             │
             ▼
app 서비스의 environment
             │
             ▼
Spring Boot 컨테이너의 JWT_SECRET
```

여기서 `.env`는 **Compose 변수 치환에 사용되는 파일**이고, `environment`는 **컨테이너 내부에 전달할 변수**를 선언하는 부분이다. `.env`에 적었다는 사실만으로 모든 변수가 자동으로 앱 컨테이너에 전달되는 것은 아니다.

보안 관련 주의점:

- `.env`는 `.gitignore`로 Git 추적에서 제외한다.
- 실제 JWT 서명키와 운영 비밀번호를 학습 문서에 기록하지 않는다.
- `docker compose config`는 치환된 비밀값까지 화면에 출력할 수 있으므로, 공유 가능한 설정 검증에는 `docker compose config --quiet`을 사용한다.
- 이번 학습 중 터미널 출력에 JWT 키 값이 노출되었으므로 **사용하던 키는 교체해야 한다.**
- 현재 로컬 DB 비밀번호 설정은 개발 편의를 위한 것이며 운영 환경에 그대로 사용하지 않는다.

---

## 6. 실행 명령과 의미

### 설정 검증

```powershell
docker compose config --quiet
```

Compose 문법과 변수 치환 등을 확인한다. **실제 DB 접속 또는 Spring Boot 기동 성공까지 검증하는 명령은 아니다.**

### 이미지 빌드와 실행

```powershell
docker compose up -d --build
```

| 옵션 | 의미 |
|---|---|
| `up` | 정의된 서비스의 컨테이너를 생성·시작하거나 설정 변경 시 재생성 |
| `-d` | 백그라운드(detached) 실행 |
| `--build` | `build:`가 있는 서비스의 이미지를 시작 전에 빌드 |

이번 구성에서 `app`은 Dockerfile로 빌드하고, `postgres`는 기존 `postgres:17` 이미지를 사용한다.

**Compose 환경변수만 수정한 경우** 기존 이미지를 재사용하면 되므로 다음 명령으로 충분하다.

```powershell
docker compose up -d
```

반면 **애플리케이션 소스나 Dockerfile을 변경해 이미지에 반영해야 한다면** 다시 `--build`를 사용한다.

### 상태 및 로그 확인

```powershell
docker compose ps
docker compose ps -a
docker compose logs --since=2m app
```

- `ps`: 기본적으로 실행 중인 컨테이너를 확인한다.
- `ps -a`: 종료된 컨테이너까지 확인한다.
- `logs --since=2m app`: 최근 앱 로그를 확인하여 이전 실행의 오류와 구분한다.

### 종료

```powershell
docker compose stop     # 컨테이너 중지, 컨테이너 유지
docker compose down     # 서비스 컨테이너와 프로젝트 네트워크 제거
```

`down`은 기본적으로 Named Volume을 제거하지 않는다. **`docker compose down -v`는 DB 데이터가 담긴 Named Volume까지 제거할 수 있으므로 주의해야 한다.**

---

## 7. 실제 검증 결과 — 2026-10-13

### 컨테이너 상태

```text
NAME               IMAGE               SERVICE    STATUS                  PORTS
myshelf-app-1      myshelf-app:local   app        Up                      127.0.0.1:8080->8080/tcp
myshelf-postgres   postgres:17         postgres   Up (healthy)            127.0.0.1:5432->5432/tcp
```

### Spring Boot 로그에서 확인한 항목

```text
Starting MyshelfApplication ... using Java 21.0.12.1
The following 1 profile is active: "local"
HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection
Database: jdbc:postgresql://postgres:5432/myshelf (PostgreSQL 17.11)
Successfully validated 1 migration
Current version of schema "public": 1
Schema "public" is up to date. No migration necessary.
Initialized JPA EntityManagerFactory for persistence unit 'default'
Tomcat started on port 8080 (http)
Started MyshelfApplication in 6.159 seconds
```

이로써 다음을 확인했다.

1. 최종 컨테이너에서 Java 21로 Spring Boot 실행
2. `app`에서 `postgres` 서비스로 JDBC 접속 성공
3. PostgreSQL에 존재하던 Flyway V1 이력 확인 및 마이그레이션 검증
4. Hibernate/JPA 초기화와 Tomcat 시작 완료
5. 두 서비스가 동시에 실행 중인 상태 확인

**주의:** `No migration necessary`는 Flyway가 기존 DB에 새로 적용할 마이그레이션이 없다는 의미다. 새 컨테이너를 실행할 때마다 테이블을 다시 만드는 것이 아니다. PostgreSQL 데이터를 보관하는 Named Volume 덕분에 기존 스키마를 유지할 수 있었다.

### 후속 API 및 데이터 영속성 검증 — 2026-10-14

앞서 컨테이너 시작과 데이터베이스 연결까지 확인한 이후, 실제 HTTP 요청을 통해 애플리케이션 동작을 검증했다.

| 검증 항목 | 결과 |
|---|---|
| 회원가입 `POST /users` | HTTP 201 |
| PostgreSQL 회원 데이터 저장 | Hibernate INSERT 로그 확인 |
| 동일 이메일 회원가입 | HTTP 409 |
| 중복 이메일 예외 | `DUPLICATE_EMAIL` |
| 로그인 `POST /auth/login` | JWT Access Token 발급 성공 |
| Compose 종료 및 재실행 | 성공 |
| 기존 회원 데이터 유지 | 확인 |
| 재실행 후 기존 회원 로그인 | 성공 |

컨테이너 재생성 및 데이터 영속성은 다음 순서로 검증했다.

```powershell
docker compose down
docker compose up -d
```

이후 동일 이메일로 회원가입을 요청했을 때 다음 응답을 받았다.

```json
{
  "detail": "가입된 이메일입니다",
  "instance": "/users",
  "status": 409,
  "title": "User Error",
  "code": "DUPLICATE_EMAIL"
}
```

기존 회원으로 로그인해 JWT 발급에도 성공했다.

따라서 **Docker Compose 환경에서 Spring Boot와 PostgreSQL의 연동뿐 아니라 실제 회원가입·로그인 및 컨테이너 재생성 후 데이터 유지까지 확인했다.**

단, 발급된 JWT를 이용한 보호 API 접근과 토큰 검증까지 확인한 것은 아니다.

---

## 8. 진행 중 확인한 문제와 학습 포인트

이번 통합 과정에서 다음 세 문제가 있었다.

| 증상 | 원인 또는 판단 | 대응 |
|---|---|---|
| `JWT_SECRET` 미설정으로 Compose 설정 해석 실패 | 필수 변수 없음 | `.env` 또는 셸 변수 설정 |
| `127.0.0.1:8080` 포트 바인딩 실패 | 당시 IDE에서 실행하던 앱의 포트 사용이 유력한 원인 | IDE 앱 종료 후 재시도해 해결 |
| `app`이 `Started` 후 `Exited (1)` | 잘못된 JDBC URL `jdbc://postgresql://...` | `jdbc:postgresql://...`로 수정 |

특히 **Compose가 `Started`를 출력한 것만으로 Spring Boot가 정상 기동했다고 판단하면 안 된다.** 종료 상태는 `docker compose ps -a`, 원인은 `docker compose logs`로 확인해야 한다.

자세한 원인 분석과 수정 과정은 별도 Troubleshooting 문서 `docker-compose-app-startup-errors.md`에 정리한다.

---

## 9. 다음 작업 및 남은 검증

Docker Compose를 이용한 Spring Boot와 PostgreSQL 통합 실행, 회원가입·로그인 및 데이터 영속성 검증까지 완료했다.

DB 비밀번호와 JWT 서명키의 환경변수 관리 및 `.env`의 Git 추적 제외도 확인했다. 환경변수 변경 후 Docker Compose 실행과 API 동작, IntelliJ 직접 실행, 기존 회원 로그인 및 JWT 발급을 검증했다.

이후 진행할 작업은 다음과 같다.

- 현재 `compose.yaml` 수정사항 커밋
- GitHub Actions 기반 CI 구축
- 별도 작업으로 Testcontainers PostgreSQL 통합 테스트 도입
- 운영 배포 단계에서 `prod` 프로필과 배포 환경 구성 검토

관련 문서:

- `docker-compose-postgresql.md`: PostgreSQL 단독 Compose 구성, 기본 Docker 개념, Named Volume
- `dockerfile-multistage.md`: 멀티스테이지 Dockerfile 및 이미지 빌드
- `docker-compose-app-startup-errors.md`: Compose 통합 시 발생한 실제 오류
