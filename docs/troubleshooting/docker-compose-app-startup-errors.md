# Docker Compose 통합 중 Spring Boot 실행 실패 — 환경변수, 포트, JDBC URL

## 1. 상황

MyShelf에서 PostgreSQL만 Docker로 실행하던 구성을 Spring Boot `app` + PostgreSQL `postgres`의 Compose 통합 실행으로 확장했다.

작업 중 **서로 다른 단계에서 발생한 세 가지 문제**를 확인했다.

1. Compose 설정 해석 단계에서 `JWT_SECRET` 누락
2. 컨테이너 시작 단계에서 Windows 호스트 8080 포트 바인딩 실패
3. Spring Boot 초기화 단계에서 JDBC URL 오타로 앱 컨테이너 종료

모든 문제가 같은 원인에서 발생한 것은 아니며, **실패 시점에 따라 확인할 명령이 달랐다.**

---

## 2. 문제 A — JWT_SECRET 미설정

### 증상

`compose.yaml`에 다음 설정을 추가하고 Compose 설정을 검증했을 때, 필수 변수 `JWT_SECRET`이 없어서 처리가 중단되었다.

```yaml
JWT_SECRET: ${JWT_SECRET:?Set JWT_SECRET in .env or shell}
```

### 원인

IntelliJ의 Spring Boot 실행 설정에 정의된 환경변수는 **외부 PowerShell에서 실행하는 Docker Compose에 자동으로 전달되지 않는다.** Compose는 자신의 셸 환경 또는 `.env` 등에서 치환할 값을 찾아야 한다.

`${변수:?메시지}`는 누락되거나 빈 변수에 대해 오류를 발생시키는 문법이다.

### 해결

프로젝트 루트(`compose.yaml`과 같은 위치)의 Git에 추적되지 않는 `.env` 또는 셸 환경변수에 실제 JWT 서명키를 설정했다.

```dotenv
# 실제 값은 문서·Git에 기록하지 않는다.
JWT_SECRET=<충분히 긴 무작위 JWT 서명키>
```

```powershell
docker compose config --quiet
```

`docker compose config --quiet`로 설정 해석 여부를 확인했다. 단, 이는 **앱이 정상 기동한다는 검증은 아니다.**

> 이번 학습 과정에서 일반 `docker compose config` 출력에 실제 JWT 서명키가 노출되었다. 공유된 값을 재사용하지 말고 **교체**해야 하며, 이후에는 민감 값이 표시되지 않도록 `--quiet` 사용을 우선한다.

---

## 3. 문제 B — 호스트 8080 포트 바인딩 실패

### 증상

`docker compose up -d --build`에서 이미지 빌드와 PostgreSQL healthcheck는 성공했지만, `app` 시작 과정에서 다음 오류가 발생했다.

```text
ports are not available:
listen tcp4 127.0.0.1:8080:
bind: An attempt was made to access a socket
in a way forbidden by its access permissions.
```

### 원인 분석

`ports: "127.0.0.1:8080:8080"`은 **Windows PC의 8080 포트**를 컨테이너 내부 8080에 연결한다는 뜻이다. 당시 IntelliJ에서 Spring Boot를 실행 중이었고, IDE 앱을 종료한 후 동일한 호스트 포트로 Compose 실행에 성공했다.

따라서 **기존 IDE 프로세스의 포트 점유가 가장 유력한 원인**이다. 다만 당시 해당 PID를 별도로 확인한 것은 아니므로 단정하지 않는다. Windows 포트 예약 등의 다른 원인도 가능하다.

### 해결 및 진단 명령

실제로는 IntelliJ에서 실행 중인 Spring Boot를 종료하고 재시도하여 해결했다.

다시 발생한다면 PowerShell에서 다음 명령으로 포트 사용 상태를 확인할 수 있다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue |
    Select-Object LocalAddress, LocalPort, State, OwningProcess
```

포트 예약 여부가 의심된다면:

```powershell
netsh interface ipv4 show excludedportrange protocol=tcp
```

의도적으로 IntelliJ와 Docker 앱을 동시에 실행해야 하는 경우 **호스트 포트만** 다른 값으로 바꿀 수도 있다.

```yaml
ports:
  - "127.0.0.1:18080:8080"
```

단, **이번에 최종 검증된 실제 구성은 호스트 8080 포트**를 사용했다. 위 18080 설정은 대안이며 실제 적용한 결과가 아니다.

---

## 4. 문제 C — JDBC URL 오타로 앱 컨테이너 종료

### 증상

포트 문제를 해결한 뒤 Compose는 다음과 같이 출력했다.

```text
Container myshelf-postgres Healthy
Container myshelf-app-1 Started
```

그러나 곧 실행한 `docker compose ps`에는 PostgreSQL만 표시되었다. 종료된 컨테이너를 확인하면 앱이 `Exited (1)` 상태였고, 로그에는 다음 오류가 있었다.

```text
Driver org.postgresql.Driver claims to not accept jdbcUrl,
jdbc://postgresql://postgres:5432/myshelf
```

### 원인

`compose.yaml`의 JDBC URL에 다음과 같은 오타가 있었다.

```yaml
# 잘못된 값
SPRING_DATASOURCE_URL: jdbc://postgresql://postgres:5432/myshelf

# 올바른 값
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/myshelf
```

PostgreSQL JDBC URL은 `jdbc:postgresql://` 형식이어야 한다. 기존 잘못된 값은 `jdbc://postgresql://`로 되어 있었다.

이 오류 때문에 HikariCP가 JDBC 연결을 초기화하지 못했고, Flyway 초기화 의존 관계를 거쳐 Spring ApplicationContext 시작이 실패했다. **DB 자체의 장애나 Flyway SQL 오류로 판단하면 안 된다.**

### 해결

JDBC URL 한 줄만 수정한 후 이미지를 재빌드하지 않고 Compose 서비스를 다시 시작했다.

```powershell
docker compose config --quiet
docker compose up -d
docker compose ps
docker compose logs --since=2m app
```

Compose 설정의 환경변수만 변경했으므로 **`--build` 없이 기존 `myshelf-app:local` 이미지를 재사용**할 수 있었다.

### 검증 결과

수정 후 다음 내용을 확인했다.

```text
myshelf-app-1      Up
myshelf-postgres   Up (healthy)

HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection
Database: jdbc:postgresql://postgres:5432/myshelf (PostgreSQL 17.11)
Successfully validated 1 migration
Schema "public" is up to date. No migration necessary.
Initialized JPA EntityManagerFactory for persistence unit 'default'
Tomcat started on port 8080 (http)
Started MyshelfApplication in 6.159 seconds
```

따라서 **Spring Boot 시작 및 PostgreSQL 연결 실패는 해결**되었다. 당시에는 회원가입·로그인 API 호출까지 검증하지 않았으며, 이후 수행한 API 검증 결과는 6절에 정리했다.

---

## 5. 문제를 구분하는 진단 순서

```text
docker compose config --quiet
        │
        ├── 실패 → YAML, 변수 치환, 필수 환경변수 확인
        │
        ▼
docker compose up -d [--build]
        │
        ├── 포트 바인딩 실패 → 호스트 포트 점유·예약 확인
        │
        ▼
docker compose ps -a
        │
        ├── app Exited → docker compose logs app
        │
        ▼
로그의 가장 아래쪽 Caused by 등 구체적인 원인 확인
        │
        ▼
설정 수정 → docker compose up -d → 로그 및 상태 재확인
```

**핵심 교훈은 `Started`가 애플리케이션의 정상 기동까지 보장하지 않는다는 점이다.** Compose 컨테이너의 상태와 애플리케이션 시작 완료 로그를 함께 확인해야 한다.

관련 학습 문서: `docker-compose-spring-boot-postgresql.md`

---

## 6. 후속 검증 — 2026-10-14

앞서 Compose 실행 오류를 해결한 뒤, Spring Boot와 PostgreSQL이 정상적으로 시작되는 것까지 확인했다.

이후 HTTP 요청을 통해 실제 API 동작과 컨테이너 재생성 후 데이터 영속성을 추가로 검증했다.

### 6.1 회원가입 및 PostgreSQL 저장 검증

Docker Compose로 실행 중인 Spring Boot에 회원가입 요청을 전송했다.

```http
POST http://localhost:8080/users
Content-Type: application/json

{
  "email": "docker-test@example.com",
  "password": "Test1234!"
}
```

응답:

```text
HTTP/1.1 201 Created
```

응답 본문으로 생성된 사용자 식별자(UUID)가 반환되었다.

애플리케이션 로그에서도 Hibernate가 다음 SQL을 실행한 것을 확인했다.

```sql
select id
from users
where email = ?;

insert into users (email, password, id)
values (?, ?, ?);
```

따라서 Docker 환경에서 HTTP 요청 처리, PostgreSQL 조회 및 회원 데이터 저장이 정상적으로 이루어졌음을 확인했다.

### 6.2 컨테이너 재생성 후 데이터 영속성 검증

다음 명령으로 Compose 서비스를 종료하고 다시 실행했다.

```powershell
docker compose down
docker compose up -d
```

재실행 후 동일한 이메일로 회원가입을 요청했다.

응답:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
```

```json
{
  "detail": "가입된 이메일입니다",
  "instance": "/users",
  "status": 409,
  "title": "User Error",
  "code": "DUPLICATE_EMAIL"
}
```

이는 컨테이너를 제거하고 다시 생성한 이후에도 기존 회원 데이터가 유지되었음을 보여준다.

현재 PostgreSQL 서비스는 다음 Named Volume을 사용한다.

```yaml
volumes:
  - postgres-data:/var/lib/postgresql/data
```

`docker compose down`은 기본적으로 Named Volume을 삭제하지 않으므로 데이터가 유지된다.

반면 `docker compose down -v`는 Compose에서 관리하는 볼륨까지 제거할 수 있으므로 데이터 보존이 필요한 경우 주의해야 한다.

### 6.3 로그인 및 JWT 발급 검증

컨테이너 재생성 후 기존에 가입한 계정으로 로그인을 요청했다.

```http
POST http://localhost:8080/auth/login
Content-Type: application/json

{
  "email": "docker-test@example.com",
  "password": "Test1234!"
}
```

로그인이 성공했고 JWT Access Token이 정상 발급되었다.

이를 통해 다음 사항을 확인했다.

- 재생성된 Spring Boot 컨테이너에서 기존 회원 조회 가능
- PostgreSQL에 저장된 회원 정보 유지
- 로그인 인증 처리 정상 동작
- Docker 환경변수로 전달한 JWT Secret을 이용한 토큰 발급 정상 동작

단, 이번 검증은 **JWT 발급까지 확인한 것**이며, 발급된 토큰을 이용한 보호 API 접근 및 토큰 검증까지 확인한 것은 아니다.

### 6.4 검증 결과 요약

| 검증 항목 | 결과 |
|---|---|
| Spring Boot 컨테이너 시작 | 성공 |
| PostgreSQL 연결 | 성공 |
| Flyway 마이그레이션 검증 | 성공 |
| 회원가입 API | HTTP 201 |
| PostgreSQL INSERT | 실행 확인 |
| 컨테이너 재생성 후 데이터 유지 | 확인 |
| 중복 이메일 처리 | HTTP 409 |
| 기존 회원 로그인 | 성공 |
| JWT Access Token 발급 | 성공 |

이번 검증으로 **Docker Compose 기반 로컬 환경에서 Spring Boot와 PostgreSQL의 연동, 회원가입·로그인, 컨테이너 재생성 후 데이터 유지까지 정상 동작함을 확인했다.**

다만 이는 로컬 통합 실행 검증이며, 운영 환경의 배포 안정성이나 장애 복구까지 검증한 것은 아니다.
