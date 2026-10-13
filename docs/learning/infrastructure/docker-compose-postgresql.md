# Docker Compose를 이용한 PostgreSQL 로컬 개발 환경 구성

## 1. 학습 배경

MyShelf는 초기 로컬 개발 환경에서 H2 인메모리 데이터베이스를 사용했다.

이후 실제 PostgreSQL에서 Flyway 마이그레이션과 JPA 매핑을 검증하기 위해 Docker Compose 기반 로컬 DB 환경을 구성했다.

이번 작업의 범위는 다음과 같다.

- PostgreSQL을 Docker 컨테이너로 실행
- Docker Compose를 이용한 실행 환경 관리
- Named Volume을 통한 데이터 영속성 확보
- Spring Boot의 로컬 DB 연결을 PostgreSQL로 변경
- Flyway 마이그레이션 및 Hibernate 스키마 검증

Spring Boot 자체는 아직 컨테이너화하지 않고 IntelliJ에서 실행한다.

Windows와 macOS에서 동일한 Compose 설정을 사용할 수 있도록 구성하되, 실제 최초 검증은 Windows 환경에서 수행했다.

---

## 2. Docker의 기본 구조

### 2.1. Docker CLI와 Engine

Docker는 주요 구성 요소를 다음과 같이 구분할 수 있다.

| 구성 요소 | 역할 |
|---|---|
| Docker CLI | 사용자가 명령어를 입력하는 도구 |
| Docker Engine | 이미지·컨테이너·네트워크·볼륨 관리 |
| Docker Desktop | Windows/macOS에서 Docker 실행 환경 제공 |

명령 실행 흐름은 다음과 같다.

```text
Docker CLI
    │
    │ Docker API
    ▼
Docker Engine
    ├── Image
    ├── Container
    ├── Network
    └── Volume
```

CLI가 설치되어 있다고 Engine까지 실행 중인 것은 아니다.

```bash
docker --version    # CLI 버전 확인
docker info         # Engine 연결 및 서버 정보 확인
```

### 2.2. 이미지와 컨테이너

- **Image:** 컨테이너를 생성하기 위한 읽기 전용 템플릿
- **Container:** 이미지를 기반으로 생성한 실행 인스턴스

```text
postgres:17 (Image)
       │
       │ 생성
       ▼
PostgreSQL Container
       └── PostgreSQL 서버 실행
```

이미지는 실행 환경을 정의하고, 컨테이너는 해당 환경에서 실제 프로세스를 실행한다.

### 2.3. Windows와 macOS의 차이

Docker의 Linux 컨테이너는 Linux 커널 기능을 사용한다.

Windows와 macOS에서는 Docker Desktop이 Linux 실행 환경을 제공한다.

| 구분 | Windows | macOS |
|---|---|---|
| Linux 실행 환경 | WSL 2 백엔드 등 | Linux 가상 머신 |
| Docker Compose 명령어 | 동일 | 동일 |
| Named Volume | Docker가 관리 | Docker가 관리 |

WSL 2(Windows Subsystem for Linux)는 Windows에서 실제 Linux 커널 기반 환경을 실행할 수 있도록 하는 기술이다.

macOS는 WSL 2를 사용하지 않는다.

또한 Apple Silicon Mac은 ARM64 아키텍처를 사용하므로 이미지의 아키텍처 지원 여부를 확인해야 한다. PostgreSQL 공식 이미지는 AMD64와 ARM64를 지원하므로 일반적으로 별도 플랫폼 설정이 필요하지 않다.

---

## 3. Docker Compose 구성

Docker Compose는 컨테이너 서비스의 실행 구성을 YAML 파일로 선언하고 관리하는 도구다.

MyShelf 프로젝트 루트에 `compose.yaml`을 작성했다.

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: myshelf
      POSTGRES_USER: myshelf
      POSTGRES_PASSWORD: myshelf
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U myshelf -d myshelf"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  postgres-data:
```

### 주요 설정

| 설정 | 의미 |
|---|---|
| `services.postgres` | Compose 서비스 이름 |
| `image` | PostgreSQL 17 계열 이미지 사용 |
| `environment` | DB 초기 생성에 필요한 환경변수 |
| `ports` | 호스트와 컨테이너 포트 연결 |
| `volumes` | 데이터 저장 공간 연결 |
| `healthcheck` | PostgreSQL 연결 준비 상태 확인 |

`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`는 주로 최초 DB 초기화 시 적용된다. 이미 초기화된 데이터가 존재한다면 환경변수를 변경해도 기존 사용자나 비밀번호가 자동으로 변경되지는 않는다.

현재 `127.0.0.1:5432:5432` 설정은 개발자 PC의 루프백 인터페이스를 통해 PostgreSQL에 접근하도록 한다.

```text
개발자 PC
localhost:5432
      │
      ▼
PostgreSQL Container
      :5432
```

`healthcheck`에서 사용하는 `pg_isready`는 PostgreSQL이 연결을 받을 준비가 되었는지 확인한다. Flyway 마이그레이션이나 애플리케이션 쿼리의 성공까지 보장하지는 않는다.

**주의:** 현재 비밀번호는 로컬 개발용 예시다. 운영 환경에서는 별도의 비밀정보 관리가 필요하다.

---

## 4. Named Volume과 데이터 영속성

컨테이너 내부에만 데이터를 저장하면 컨테이너 삭제 시 데이터가 사라질 수 있다.

PostgreSQL처럼 데이터 보존이 필요한 서비스에서는 볼륨을 사용한다.

### 4.1. 서비스 내부 volumes

```yaml
services:
  postgres:
    volumes:
      - postgres-data:/var/lib/postgresql/data
```

`postgres-data`라는 Named Volume을 컨테이너 내부의 `/var/lib/postgresql/data` 경로에 연결한다.

이 경로는 사용 중인 PostgreSQL 17 공식 이미지의 데이터 저장 경로다.

### 4.2. 최상위 volumes

```yaml
volumes:
  postgres-data:
```

Compose 프로젝트에서 사용할 Named Volume을 선언한다.

두 설정의 역할은 다르다.

| 위치 | 역할 |
|---|---|
| 서비스 내부 `volumes` | 볼륨을 어느 경로에 연결할지 정의 |
| 최상위 `volumes` | 사용할 Named Volume 선언 |

### 4.3. Named Volume과 Bind Mount

```yaml
# Named Volume
- postgres-data:/var/lib/postgresql/data

# Bind Mount
- ./postgres-data:/var/lib/postgresql/data
```

Named Volume은 Docker가 저장 공간을 관리한다.

Bind Mount는 호스트 파일 시스템의 특정 경로를 직접 연결한다.

MyShelf에서는 운영체제별 경로와 파일 권한 문제를 줄이기 위해 Named Volume을 선택했다.

### 4.4. 볼륨 생명주기

```bash
docker compose down
```

컨테이너를 제거하지만 기본적으로 Named Volume은 유지한다.

```bash
docker compose down -v
```

Compose 프로젝트의 Named Volume까지 제거한다.

따라서 DB 데이터를 유지해야 한다면 `down -v` 사용에 주의해야 한다.

Named Volume은 컨테이너 재생성 시 데이터를 보존하는 수단이지 백업 자체는 아니다.

또한 Windows와 macOS에서 같은 Compose 파일을 실행해도 각 PC의 데이터 볼륨은 별도로 관리된다.

---

## 5. Docker Compose와 PostgreSQL 명령어

### 5.1. 주요 Compose 명령

```bash
# 컨테이너 생성 및 백그라운드 실행
docker compose up -d

# 실행 상태 확인
docker compose ps

# PostgreSQL 로그 확인
docker compose logs postgres

# 실시간 로그 확인
docker compose logs -f postgres

# 서비스 중지
docker compose stop

# 컨테이너 제거 (볼륨은 유지)
docker compose down
```

`up -d`의 `-d`는 Detached Mode를 의미하며, 컨테이너를 백그라운드에서 실행한다.

### 5.2. exec 명령어

```bash
docker compose exec postgres psql -U myshelf -d myshelf
```

이 명령어는 두 부분으로 구분한다.

```text
docker compose exec postgres
└── 실행 중인 postgres 서비스 컨테이너에서

psql -U myshelf -d myshelf
└── psql 프로그램을 실행하고 DB에 접속
```

| 요소 | 의미 |
|---|---|
| `exec` | 실행 중인 컨테이너에서 명령 실행 |
| `postgres` | Compose 서비스 이름 |
| `psql` | PostgreSQL 클라이언트 |
| `-U myshelf` | DB 사용자 지정 |
| `-d myshelf` | 접속할 데이터베이스 지정 |

`exec`는 컨테이너 내부에서 새로운 프로세스를 실행하는 명령이다.

예를 들어 `docker compose exec postgres ls`는 컨테이너 내부에서 `ls`를 실행한다.

참고로 `docker compose up -d`의 `-d`와 `psql -d`의 `-d`는 서로 다른 프로그램의 옵션이므로 의미가 다르다.

### 5.3. psql 주요 명령어

PostgreSQL에 접속한 후 다음 명령어를 사용할 수 있다.

| 명령어 | 의미 |
|---|---|
| `\dt` | 테이블 목록 |
| `\d` | 관계형 객체 목록 |
| `\d users` | users 테이블 구조 |
| `\d+ users` | 추가 상세 정보 |
| `\q` | psql 종료 |

이 명령들은 SQL이 아니라 `psql`에서 제공하는 메타 명령어다.

컨테이너 내부에서 `psql` 접속 시 비밀번호를 묻지 않을 수 있는데, 이는 PostgreSQL의 접속 방식과 인증 설정에 따른 것이다.

---

## 6. Spring Boot와 PostgreSQL 연결

현재 MyShelf는 다음 구조로 실행한다.

```text
개발자 PC (Windows / macOS)
│
├── IntelliJ
│   └── MyShelf Spring Boot
│       └── JDBC: localhost:5432
│
└── Docker Desktop
    └── PostgreSQL Container
        └── Named Volume
```

`application-local.yaml`의 DataSource를 변경했다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myshelf
    driver-class-name: org.postgresql.Driver
    username: myshelf
    password: myshelf
```

Gradle에는 PostgreSQL JDBC 드라이버와 Flyway PostgreSQL 모듈을 사용한다.

```groovy
runtimeOnly 'org.postgresql:postgresql'
runtimeOnly 'org.flywaydb:flyway-database-postgresql'
```

### localhost와 서비스 이름의 차이

현재는 Spring Boot가 호스트에서 실행되므로 `localhost:5432`로 접속한다.

향후 Spring Boot까지 Compose 컨테이너로 실행한다면 상황이 달라진다.

컨테이너 내부의 `localhost`는 해당 컨테이너 자신을 의미한다.

따라서 동일한 Compose 네트워크에서는 PostgreSQL 서비스 이름을 사용한다.

```text
jdbc:postgresql://postgres:5432/myshelf
```

이 차이는 운영체제와 관계없이 동일하다.

---

## 7. MyShelf 실제 검증 결과

다음 검증은 Windows 개발 환경에서 수행했다.

### 7.1. PostgreSQL 연결

```text
HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection
Database: jdbc:postgresql://localhost:5432/myshelf (PostgreSQL 17.11)
```

실제 PostgreSQL JDBC 연결이 정상적으로 생성되었다.

### 7.2. Flyway 마이그레이션

```text
Successfully validated 1 migration
Migrating schema "public" to version "1 - init"
Successfully applied 1 migration
```

기존 `V1__init.sql`이 PostgreSQL에 정상 적용되었다.

Hibernate도 `ddl-auto: validate` 상태에서 오류 없이 초기화되었다.

Flyway와 Hibernate의 역할 및 검증 차이는 별도 `flyway-and-hibernate.md` 문서에서 다룬다.

### 7.3. 스키마 확인

`psql`에서 `\dt`를 실행하여 다음 테이블을 확인했다.

```text
books
flyway_schema_history
user_books
users
```

마이그레이션 적용 이력은 다음과 같았다.

```text
version | description | success
--------+-------------+--------
1       | init        | t
```

`\d users` 명령으로 UNIQUE 제약조건도 확인했다.

```text
"uk_users_email" UNIQUE CONSTRAINT, btree (email)
```

Flyway에서 의도한 이름의 제약조건이 실제 PostgreSQL에 생성된 것을 확인했다.

단, 제약조건의 존재 확인과 애플리케이션의 중복 이메일 예외 처리 검증은 별개다.

---

## 8. 문제 해결 기록 및 다음 과제

### 8.1. Windows 실습에서 발생한 문제

| 문제 | 원인 및 해결 |
|---|---|
| Docker CLI는 작동하지만 컨테이너 실행 실패 | Docker Desktop의 Engine이 실행되지 않아 Desktop 실행 후 해결 |
| IntelliJ 터미널에서 Docker 명령 인식 실패 | 설치 후 변경된 PATH가 반영되지 않아 IntelliJ 재시작 |
| `no configuration file provided` | Compose 설정 파일을 찾지 못하는 위치에서 실행 |
| `No active profile set` | IntelliJ 실행 설정에서 `local` 프로파일 미적용 |

이 문제들은 Windows 실습에서 실제 경험한 내용이며 모든 환경에서 발생하는 일반적인 문제는 아니다.

### 8.2. macOS 재현 시 확인할 사항

macOS에서는 아직 동일한 구성을 직접 검증하지 않았다.

추후 확인할 항목은 다음과 같다.

- Docker Desktop 및 Engine 정상 실행
- Apple Silicon 환경의 이미지 아키텍처 지원
- `docker compose up -d` 정상 실행
- 로컬 포트 5432 충돌 여부
- Spring Boot의 PostgreSQL 연결 및 Flyway 실행

### 8.3. 다음 학습 과제

현재 PostgreSQL 컨테이너 구성과 로컬 애플리케이션 연결까지 완료했다.

다음 단계에서는 다음 내용을 학습한다.

1. Dockerfile을 이용한 Spring Boot 이미지 빌드
2. Spring Boot와 PostgreSQL 컨테이너 간 네트워크
3. Compose를 통한 두 서비스의 실행
4. 환경변수 및 실행 환경별 설정 분리
5. GitHub Actions 기반 CI/CD 구성

Testcontainers PostgreSQL을 이용한 통합 테스트 전환은 별도 작업으로 진행할 예정이다.

---

## 9. 정리

이번 실습을 통해 Docker의 이미지, 컨테이너, 볼륨과 Compose의 역할을 구분했다.

특히 다음 세 가지가 중요하다.

**첫째, 애플리케이션과 DB는 독립된 프로세스다.**

Spring Boot와 PostgreSQL은 각각 실행되며 JDBC로 통신한다. Compose 파일이 MyShelf 저장소에 존재한다고 해서 DB가 Spring Boot 프로세스에 종속되는 것은 아니다.

**둘째, 컨테이너와 데이터 저장 공간의 생명주기는 다르다.**

Named Volume을 이용하면 컨테이너를 제거하고