# Docker Compose를 이용한 PostgreSQL 로컬 개발 환경 구성

## 1. 학습 배경

MyShelf는 초기 로컬 개발 환경에서 H2 인메모리 데이터베이스를 사용했다.

이후 실제 PostgreSQL에서 Flyway 마이그레이션과 JPA 매핑을 검증하기 위해 Docker Compose 기반 로컬 DB 환경을 구성했다.

이번 작업에서는 Spring Boot 애플리케이션 자체를 컨테이너화하지 않고, PostgreSQL만 Docker 컨테이너로 실행했다.

학습 목표는 다음과 같다.

- Docker Desktop과 WSL 2의 관계 이해
- Docker 이미지와 컨테이너의 차이
- Docker Compose를 통한 서비스 실행
- Named Volume과 데이터 영속성
- 컨테이너 내부 명령 실행
- PostgreSQL 접속 및 스키마 확인
- Spring Boot와 컨테이너 DB 연결

---

## 2. Docker와 WSL 2

### 2.1. WSL 2란?

WSL은 Windows Subsystem for Linux의 약자다.

Windows에서 Linux 실행 환경을 사용할 수 있도록 하는 기술이다.

WSL 2는 실제 Linux 커널을 경량 가상 머신에서 실행하는 방식이다.

### 2.2. Docker Desktop과 WSL 2의 관계

Docker의 Linux 컨테이너는 Linux 커널 기능을 활용한다.

Windows에서 Docker Desktop의 WSL 2 백엔드를 사용하면 Linux 커널 기반 환경에서 컨테이너를 실행할 수 있다.

```text
Windows
│
├── IntelliJ
│   └── MyShelf Spring Boot
│
├── Docker Desktop
│   └── WSL 2 기반 Linux 환경
│       └── Docker Engine
│           └── PostgreSQL 컨테이너
│
└── PowerShell
    └── docker 명령 실행
```

Docker Desktop과 WSL 2는 동일한 기술이 아니다.

- WSL 2: Windows에 Linux 실행 환경 제공
- Docker Engine: 컨테이너 생성 및 실행 관리
- Docker Desktop: Windows에서 Docker를 사용할 수 있도록 실행 환경과 관리 기능 제공

---

## 3. Docker 이미지와 컨테이너

### 3.1. 이미지

Docker 이미지는 컨테이너를 생성하는 데 사용하는 읽기 전용 템플릿이다.

애플리케이션 실행에 필요한 파일, 라이브러리, 설정 등의 내용을 포함한다.

MyShelf에서는 PostgreSQL 공식 이미지를 사용했다.

```yaml
image: postgres:17
```

이는 PostgreSQL 17 계열 이미지를 사용한다는 의미다.

다만 `postgres:17`은 특정 패치 버전까지 고정하는 태그는 아니다.

### 3.2. 컨테이너

컨테이너는 이미지를 기반으로 생성한 실행 인스턴스다.

```text
PostgreSQL 이미지
       │
       │ 컨테이너 생성
       ▼
PostgreSQL 컨테이너
       │
       └── PostgreSQL 서버 실행
```

이미지와 컨테이너의 관계는 클래스와 인스턴스에 비유할 수 있지만 완전히 동일한 개념은 아니다.

이미지는 실행에 필요한 파일 시스템과 설정의 기반이고, 컨테이너는 이를 이용해 생성된 독립적인 실행 환경이다.

---

## 4. Docker Compose란?

Docker Compose는 여러 컨테이너 서비스의 실행 구성을 YAML 파일에 정의하고 관리할 수 있도록 하는 도구다.

MyShelf에서는 프로젝트 루트에 다음 파일을 작성했다.

```text
compose.yaml
```

현재 PostgreSQL 서비스 하나만 사용하지만, 향후 MyShelf 애플리케이션 컨테이너를 추가할 수 있다.

### 4.1. MyShelf compose.yaml

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

위 비밀번호는 로컬 개발용 예시다. 운영 환경에서는 별도의 비밀정보 관리가 필요하다.

### 4.2. services

```yaml
services:
  postgres:
```

`postgres`는 Compose 서비스 이름이다.

이 이름은 이후 다음 명령에서 사용된다.

```bash
docker compose exec postgres ...
```

서비스 이름과 컨테이너 이름은 구분해야 한다.

Compose는 일반적으로 프로젝트명과 서비스명을 조합해 컨테이너 이름을 생성한다.

### 4.3. environment

```yaml
environment:
  POSTGRES_DB: myshelf
  POSTGRES_USER: myshelf
  POSTGRES_PASSWORD: myshelf
```

PostgreSQL 이미지의 초기 데이터베이스 구성에 사용되는 환경변수다.

- `POSTGRES_DB`: 초기 생성 데이터베이스
- `POSTGRES_USER`: 초기 사용자
- `POSTGRES_PASSWORD`: 해당 사용자의 비밀번호

중요한 점은 이미 초기화된 데이터 볼륨이 존재할 때 이 값을 변경한다고 기존 데이터베이스가 자동으로 재설정되지는 않는다는 것이다.

이 값들은 주로 초기 데이터베이스 생성 과정에서 사용된다.

### 4.4. ports

```yaml
ports:
  - "127.0.0.1:5432:5432"
```

호스트와 컨테이너 간 포트 매핑이다.

```text
Windows
127.0.0.1:5432
      │
      ▼
PostgreSQL 컨테이너
5432
```

형식은 다음과 같다.

```text
호스트 IP:호스트 포트:컨테이너 포트
```

`127.0.0.1`로 바인딩했으므로 개발자 PC의 루프백 인터페이스를 통해 접근하도록 제한한다.

### 4.5. healthcheck

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U myshelf -d myshelf"]
  interval: 5s
  timeout: 5s
  retries: 5
```

컨테이너의 PostgreSQL 서버가 연결 요청을 받을 준비가 되었는지 확인한다.

`pg_isready`는 PostgreSQL의 연결 가능 상태를 확인하는 도구다.

다만 이 검사가 성공했다고 해서 Flyway 마이그레이션이나 애플리케이션 쿼리까지 정상 동작한다는 의미는 아니다.

---

## 5. Named Volume이란?

컨테이너의 파일 시스템에만 데이터를 보관하면 컨테이너를 제거할 때 데이터가 함께 사라질 수 있다.

PostgreSQL처럼 데이터를 지속적으로 보존해야 하는 서비스에서는 별도 저장 공간을 연결하는 것이 필요하다.

Docker Named Volume은 Docker가 생성하고 관리하는 데이터 저장 공간이다.

### 5.1. 서비스 내부 volumes

```yaml
services:
  postgres:
    volumes:
      - postgres-data:/var/lib/postgresql/data
```

이는 `postgres-data` 볼륨을 컨테이너 내부의 `/var/lib/postgresql/data` 경로에 마운트한다는 의미다.

PostgreSQL 17 공식 이미지에서 이 경로는 데이터 디렉터리로 사용된다.

### 5.2. 최상위 volumes

```yaml
volumes:
  postgres-data:
```

이는 Compose 프로젝트에서 사용할 Named Volume을 선언하는 설정이다.

두 설정은 역할이 다르다.

| 위치 | 역할 |
|---|---|
| 서비스 내부 `volumes` | 볼륨을 컨테이너의 어느 경로에 연결할지 정의 |
| 최상위 `volumes` | Compose가 사용할 Named Volume 선언 |

### 5.3. Named Volume과 Bind Mount

Named Volume:

```yaml
volumes:
  - postgres-data:/var/lib/postgresql/data
```

Bind Mount:

```yaml
volumes:
  - ./postgres-data:/var/lib/postgresql/data
```

Named Volume은 Docker가 관리하는 저장 공간을 사용한다.

Bind Mount는 호스트 파일 시스템의 특정 경로를 컨테이너에 연결한다.

MyShelf의 Windows 로컬 환경에서는 PostgreSQL 데이터 저장을 Docker가 관리하도록 Named Volume을 선택했다.

### 5.4. 볼륨 삭제 주의

```bash
docker compose down
```

컨테이너와 관련 네트워크를 제거하지만 기본적으로 Named Volume은 유지한다.

```bash
docker compose down -v
```

Compose 프로젝트에 선언된 Named Volume까지 제거한다.

따라서 데이터 보존이 필요하다면 `down -v` 사용에 주의해야 한다.

Named Volume은 데이터 보존 수단이지 백업 자체를 의미하지는 않는다.

---

## 6. Docker Compose 주요 명령어

### 6.1. 컨테이너 실행

```bash
docker compose up -d
```

- `up`: 서비스 컨테이너 생성 및 실행
- `-d`: Detached Mode, 백그라운드 실행

### 6.2. 실행 상태 확인

```bash
docker compose ps
```

Compose 프로젝트의 컨테이너 상태를 확인한다.

### 6.3. 로그 확인

```bash
docker compose logs postgres
```

PostgreSQL 서비스의 로그를 확인한다.

실시간으로 확인하려면 다음과 같이 실행한다.

```bash
docker compose logs -f postgres
```

### 6.4. 컨테이너 내부 명령 실행

```bash
docker compose exec postgres psql -U myshelf -d myshelf
```

명령을 두 부분으로 분리하면 이해하기 쉽다.

```text
docker compose exec postgres
└── postgres 서비스 컨테이너에서 명령 실행

psql -U myshelf -d myshelf
└── myshelf 사용자로 myshelf DB 접속
```

| 요소 | 의미 |
|---|---|
| `docker compose` | Compose 프로젝트 대상으로 작업 |
| `exec` | 실행 중인 컨테이너에서 명령 실행 |
| `postgres` | 서비스 이름 |
| `psql` | PostgreSQL 클라이언트 |
| `-U myshelf` | 사용자 지정 |
| `-d myshelf` | 데이터베이스 지정 |

여기서 `-d`는 `docker compose up -d`의 `-d`와 의미가 다르다.

명령어 옵션은 각각의 프로그램이 정의하므로 동일한 옵션 문자라도 의미가 다를 수 있다.

### 6.5. exec와 run의 차이

`exec`는 이미 실행 중인 컨테이너에서 명령을 실행한다.

`docker compose run`은 서비스 설정을 기반으로 새로운 일회성 컨테이너를 생성해 명령을 실행한다.

현재 PostgreSQL 컨테이너가 실행 중이므로 DB 접속에는 `exec`를 사용한다.

---

## 7. psql을 이용한 PostgreSQL 확인

`psql`은 PostgreSQL에 접속해 SQL 또는 관리 명령을 실행하는 클라이언트 프로그램이다.

### 7.1. 접속

```bash
docker compose exec postgres psql -U myshelf -d myshelf
```

접속 후 다음과 같은 프롬프트가 나타난다.

```text
myshelf=#
```

### 7.2. 테이블 목록 확인

```text
\dt
```

MyShelf에서는 다음 결과를 확인했다.

```text
books
flyway_schema_history
user_books
users
```

### 7.3. 테이블 구조 확인

```text
\d users
```

`users` 테이블의 컬럼, 타입, 인덱스, 제약조건, 참조 관계 등을 확인한다.

### 7.4. 주요 psql 명령어

| 명령 | 역할 |
|---|---|
| `\d` | 관계형 객체 목록 |
| `\dt` | 테이블 목록 |
| `\d users` | users 테이블 구조 |
| `\d+ users` | users 테이블 추가 상세 정보 |
| `\q` | psql 종료 |

이 명령들은 일반 SQL이 아니라 `psql`에서 제공하는 메타 명령어다.

---

## 8. Spring Boot와 PostgreSQL 연결

이번 단계에서는 Spring Boot를 Windows에서 실행하고 PostgreSQL만 Docker 컨테이너로 실행한다.

```text
IntelliJ
└── Spring Boot
    └── JDBC
        └── localhost:5432
            └── Docker PostgreSQL
```

`application-local.yaml`의 DataSource를 다음과 같이 변경했다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myshelf
    driver-class-name: org.postgresql.Driver
    username: myshelf
    password: myshelf
```

Gradle에서는 PostgreSQL JDBC 드라이버와 Flyway PostgreSQL 모듈을 사용한다.

```groovy
runtimeOnly 'org.postgresql:postgresql'
runtimeOnly 'org.flywaydb:flyway-database-postgresql'
```

현재는 Spring Boot가 호스트에서 실행되므로 JDBC 주소에 `localhost`를 사용한다.

향후 Spring Boot까지 Compose의 컨테이너로 실행한다면 컨테이너 안의 `localhost`는 해당 컨테이너 자신을 의미한다.

따라서 동일한 Compose 네트워크에서는 일반적으로 서비스 이름을 사용한다.

```text
jdbc:postgresql://postgres:5432/myshelf
```

즉, `localhost`와 `postgres`의 차이는 **어느 실행 환경에서 접속하는가**에 따라 결정된다.

---

## 9. 실제 실행 검증

### 9.1. Docker PostgreSQL 실행

```bash
docker compose up -d
docker compose ps
```

PostgreSQL 컨테이너 실행 및 연결 가능 상태를 확인했다.

### 9.2. Spring Boot 실행

IntelliJ에서 `local` 프로파일로 실행했다.

```text
The following 1 profile is active: "local"
```

### 9.3. JDBC 연결 확인

```text
HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection
```

실제 PostgreSQL JDBC 연결이 생성되었다.

### 9.4. Flyway 마이그레이션

```text
Database: jdbc:postgresql://localhost:5432/myshelf
Successfully validated 1 migration
Successfully applied 1 migration
```

Flyway가 기존 V1 마이그레이션을 PostgreSQL에 적용했다.

### 9.5. Hibernate 스키마 검증

`ddl-auto: validate` 설정에서 오류 없이 EntityManagerFactory가 초기화되었다.

### 9.6. DB 스키마 확인

`psql`로 테이블 생성 상태와 UNIQUE 제약조건 이름을 확인했다.

```text
"uk_users_email" UNIQUE CONSTRAINT, btree (email)
```

따라서 Flyway가 의도한 스키마가 실제 PostgreSQL에 생성되었음을 확인했다.

---

## 10. 학습 과정에서 발생한 문제

### 10.1. Docker 명령은 인식되지만 컨테이너 실행 실패

Docker 설치 직후 다음과 같은 문제가 발생했다.

```text
failed to connect to the docker API
```

Docker CLI는 설치되었으나 Docker Engine에 연결되지 않은 상태였다.

Docker Desktop을 실행하고 Engine이 시작된 이후 정상 동작했다.

**학습한 점**

Docker CLI 설치 여부와 Docker Engine 실행 상태는 별개의 문제다.

### 10.2. IntelliJ 터미널에서 Docker 명령어 인식 실패

Windows PowerShell에서는 `docker --version`이 정상 동작했지만 IntelliJ 터미널에서는 인식되지 않았다.

IntelliJ를 재시작하자 정상 동작했다.

**학습한 점**

실행 중인 프로그램은 이전에 상속한 환경 변수를 계속 사용할 수 있으므로, 설치 후 PATH 변경사항이 반영되지 않을 수 있다.

### 10.3. Compose 파일을 찾지 못하는 문제

```text
no configuration file provided: not found
```

Compose 파일이 없는 디렉터리에서 명령을 실행했을 때 발생했다.

프로젝트 루트에서 실행하여 해결했다.

**학습한 점**

Compose는 기본적으로 현재 디렉터리와 상위 디렉터리 등을 기준으로 설정 파일을 탐색한다. 필요하다면 `-f` 옵션으로 명시할 수 있다.

### 10.4. Spring Boot local 프로파일 미적용

최초 실행 시 다음 로그가 나타났다.

```text
No active profile set, falling back to 1 default profile: "default"
```

IntelliJ 실행 설정에 `local` 프로파일이 정상 전달되도록 수정한 후 해결했다.

**학습한 점**

환경별 YAML 파일이 존재하더라도 해당 프로파일이 활성화되지 않으면 기대한 설정이 적용되지 않는다.

---

## 11. 남은 학습 과제

현재는 PostgreSQL만 Docker 컨테이너로 실행하는 단계까지 완료했다.

이후 학습할 내용은 다음과 같다.

- Dockerfile 작성 및 Spring Boot 이미지 생성
- Docker 이미지 빌드와 컨테이너 실행
- Spring Boot와 PostgreSQL 컨테이너 간 네트워크
- Compose로 두 서비스를 함께 실행
- 환경변수 및 설정 분리
- 운영 환경을 고려한 컨테이너 구성
- CI/CD 파이프라인과 Docker 이미지 배포

Testcontainers를 통한 DB 통합 테스트 구성도 별도로 진행할 예정이다.

---

## 12. 정리

이번 학습에서 가장 중요한 것은 Docker 명령어를 익히는 것보다 애플리케이션과 데이터베이스의 실행 환경을 구분하는 것이었다.

Spring Boot와 PostgreSQL은 별개의 프로세스로 실행되며 JDBC로 통신한다.

Compose 파일이 MyShelf 저장소에 존재한다고 해서 PostgreSQL이 Spring Boot 프로세스에 종속되는 것은 아니다.

Docker Compose는 실행 환경을 정의하고 관리하는 역할을 한다.

또한 Named Volume은 컨테이너의 생명주기와 데이터 저장 공간의 생명주기를 분리한다.

최종적으로 현재 MyShelf의 로컬 개발 환경은 다음과 같다.

```text
Windows Developer PC
│
├── IntelliJ
│   └── MyShelf Spring Boot
│       └── local profile
│
└── Docker Desktop
    └── PostgreSQL 17
        └── Named Volume
```

이 구성을 기반으로 이후 Spring Boot 컨테이너화와 CI/CD 학습으로 확장할 예정이다.