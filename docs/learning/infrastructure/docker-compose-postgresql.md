# Docker Compose를 이용한 PostgreSQL 로컬 개발 환경 구성

## 1. 학습 배경

> 본문의 구성과 설정 예시는 PostgreSQL 단독 실행 학습 단계 기준이다. 4.6절의 데이터 영속성 검증은 후속 통합 단계의 결과이며, 현재 Spring Boot와 PostgreSQL의 통합 실행 구성은 [통합 실행 문서](docker-compose-spring-boot-postgresql.md)를 참고한다.

MyShelf는 초기 로컬 개발 환경에서 H2 인메모리 데이터베이스를 사용했다. 이후 운영에서 사용할 PostgreSQL과 동일한 엔진에서 Flyway 마이그레이션 및 JPA 매핑을 검증하기 위해 로컬 DB를 PostgreSQL로 전환했다.

이번 작업의 범위는 다음과 같다.

- PostgreSQL을 Docker 컨테이너로 실행한다.
- Docker Compose로 실행 설정을 파일에 선언하고 관리한다.
- Named Volume으로 컨테이너와 데이터의 생명주기를 분리한다.
- Spring Boot의 `local` 프로파일을 PostgreSQL에 연결한다.
- Flyway 마이그레이션과 Hibernate 스키마 검증 결과를 확인한다.

**Spring Boot 자체는 아직 컨테이너화하지 않는다.** IntelliJ에서 Spring Boot를 실행하고, PostgreSQL만 Docker로 실행한다.

Windows와 macOS에서 같은 Compose 설정을 재사용할 수 있도록 구성했다. 실제 최초 실행과 검증은 Windows 환경에서 수행했으며, macOS에서의 실행은 추후 확인할 예정이다.

---

## 2. Docker의 기본 구조

### 2.1. Docker CLI, Engine, Desktop

| 구성 요소 | 역할 |
|---|---|
| Docker CLI | `docker` 명령을 입력하고 Engine에 요청하는 클라이언트 |
| Docker Engine | 이미지, 컨테이너, 네트워크, 볼륨을 관리하는 실행 엔진 |
| Docker Desktop | Windows/macOS에서 Docker Engine을 사용하기 위한 실행 환경 및 관리 도구 |

```text
사용자 → Docker CLI → Docker API → Docker Engine
                                     ├── Image
                                     ├── Container
                                     ├── Network
                                     └── Volume
```

CLI 설치와 Engine 실행은 별개다.

```bash
docker --version      # CLI 버전 확인
docker compose version # Compose 버전 확인
docker info           # Engine 연결 및 서버 정보 확인
docker run --rm hello-world  # 실제 컨테이너 실행 확인
```

`docker --version`이 정상이어도 `docker info`의 `Server` 항목에서 연결 오류가 발생할 수 있다. 이 경우에는 Engine 실행 상태를 확인해야 한다.

### 2.2. 이미지와 컨테이너

- **Image:** 컨테이너를 생성하기 위한 읽기 전용 기반. 실행에 필요한 파일 및 설정을 포함한다.
- **Container:** 이미지를 기반으로 생성된 실행 인스턴스. 격리된 환경에서 프로세스를 실행한다.

```text
postgres:17 (Image)
       │ 컨테이너 생성
       ▼
PostgreSQL Container
       └── PostgreSQL 서버 프로세스 실행
```

이미지는 실행의 기반이고, 컨테이너는 실제 실행 환경이다. 하나의 이미지로 여러 컨테이너를 만들 수도 있다.

### 2.3. Windows와 macOS의 실행 환경

Linux 컨테이너는 Linux 커널 기능을 사용한다. Windows와 macOS의 Docker Desktop은 Linux 컨테이너 실행을 위한 Linux 환경을 제공한다.

| 구분 | Windows | macOS |
|---|---|---|
| Linux 컨테이너 실행 | WSL 2 백엔드 등 활용 | Linux 가상 머신 활용 |
| Docker CLI/Compose 명령 | 동일 | 동일 |
| Named Volume 관리 | Docker Engine | Docker Engine |

WSL 2(Windows Subsystem for Linux)는 Windows에서 실제 Linux 커널 기반 환경을 사용할 수 있게 해주는 기술이다. **macOS는 WSL 2를 사용하지 않는다.**

Apple Silicon Mac은 ARM64, 일반적인 Intel/AMD PC는 AMD64 아키텍처를 사용한다. PostgreSQL 공식 이미지는 이들 아키텍처를 지원하므로 일반적으로 Compose에 `platform`을 강제로 지정할 필요가 없다. 다른 이미지를 사용할 때는 해당 아키텍처의 지원 여부를 확인해야 한다.

---

## 3. Docker CLI와 Docker Compose

### 3.1. 둘의 관계

Docker Compose는 Docker CLI 플러그인으로 제공되며, Docker Engine을 이용해 컨테이너를 관리한다.

`docker run`과 `docker compose up`은 같은 Engine을 사용하지만 **명령어의 옵션이나 동작이 완전히 일대일로 호환되는 것은 아니다.** Compose는 서비스, 프로젝트, 기본 네트워크, 설정 변경 시 컨테이너 재생성 등을 추가로 관리한다.

| 구분 | `docker run` | `docker compose` |
|---|---|---|
| 실행 설정 | 실행할 때 옵션 전달 | YAML 파일에 선언 |
| 관리 단위 | 개별 컨테이너 중심 | 서비스와 프로젝트 중심 |
| 설정 재사용 | 명령어 또는 스크립트 관리 | Compose 파일 재사용 |
| 버전 관리 | 별도 스크립트 등을 추적 | `compose.yaml`을 Git으로 추적 |

Compose는 **여러 컨테이너를 함께 실행할 때뿐 아니라, 단일 컨테이너의 실행 설정을 반복적으로 재현할 때도 유용하다.**

### 3.2. 같은 PostgreSQL을 실행하는 두 방식

Docker CLI로 직접 실행한다면 다음처럼 옵션을 나열한다. 아래는 개념 비교를 위한 한 줄 예시다.

```bash
docker run -d --name myshelf-postgres -e POSTGRES_DB=myshelf -e POSTGRES_USER=myshelf -e POSTGRES_PASSWORD=myshelf -p 127.0.0.1:5432:5432 -v myshelf-pg-data:/var/lib/postgresql/data postgres:17
```

Compose에서는 설정을 파일로 분리한다.

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

PostgreSQL 단독 실행 단계에서 적용한  `compose.yaml` 구성이다. Compose에는 `healthcheck`도 포함되어 있으므로 위 `docker run` 예시와 설정이 완전히 동일한 것은 아니다.

파일을 작성한 이후에는 다음 명령으로 실행한다.

```bash
docker compose up -d
```

**Compose의 가치는 명령어를 짧게 줄이는 데만 있지 않다.** 포트, 환경변수, 볼륨, 상태 확인 설정을 선언적으로 관리하고 Git에 기록할 수 있다는 점이 중요하다.

### 3.3. compose.yaml의 주요 설정

| 설정 | 의미 |
|---|---|
| `services.postgres` | Compose 서비스 이름. 명령어 및 컨테이너 간 통신에서 사용 |
| `image: postgres:17` | PostgreSQL 17 계열 이미지 사용 |
| `environment` | DB 최초 초기화에 사용할 환경변수 |
| `ports` | 호스트 주소/포트를 컨테이너 포트와 연결 |
| `volumes` | 데이터를 저장할 볼륨을 컨테이너에 마운트 |
| `healthcheck` | PostgreSQL의 연결 준비 상태 확인 |

`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`는 주로 **데이터 디렉터리의 최초 초기화 시점**에 사용된다. 기존 볼륨을 유지한 채 이 값을 바꿔도 DB 사용자나 비밀번호가 자동 변경되는 것은 아니다.

`127.0.0.1:5432:5432`는 개발자 PC의 루프백 주소 5432번 포트를 컨테이너의 5432번 포트로 전달한다.

```text
개발자 PC localhost:5432
           │ 포트 매핑
           ▼
PostgreSQL 컨테이너 :5432
```

`pg_isready`는 PostgreSQL이 연결을 받을 준비가 되었는지 확인한다. **마이그레이션이나 애플리케이션 쿼리 성공까지 검증하는 것은 아니다.**

현재 비밀번호는 로컬 전용 예시다. 운영 환경에서는 자격 증명을 별도 관리해야 한다. 또한 `postgres:17`은 메이저 버전 계열을 고정하지만 패치 버전까지 고정하지는 않는다.

---

## 4. Named Volume과 데이터 영속성

### 4.1. 왜 볼륨이 필요한가?

컨테이너의 쓰기 가능 계층에만 데이터를 저장하면 컨테이너를 삭제할 때 해당 데이터도 사라질 수 있다. PostgreSQL 데이터는 컨테이너와 독립적으로 보존할 필요가 있으므로 볼륨에 저장한다.

```text
PostgreSQL 컨테이너
       │ 마운트
       ▼
Docker Named Volume (postgres-data)
```

### 4.2. 서비스 내부 volumes와 최상위 volumes

```yaml
services:
  postgres:
    volumes:
      - postgres-data:/var/lib/postgresql/data

volumes:
  postgres-data:
```

| 위치 | 역할 |
|---|---|
| 서비스 내부 `volumes` | 어떤 볼륨을 컨테이너의 어디에 연결할지 지정 |
| 최상위 `volumes` | Compose 프로젝트에서 사용할 Named Volume 선언 |

`/var/lib/postgresql/data`는 **현재 사용 중인 PostgreSQL 17 공식 이미지**의 데이터 저장 경로다. 이미지 버전을 바꿀 때는 해당 이미지의 데이터 디렉터리 규칙을 확인해야 한다.

### 4.3. Named Volume과 Bind Mount

```yaml
# Named Volume: Docker가 저장 경로 관리
- postgres-data:/var/lib/postgresql/data

# Bind Mount: 개발자가 호스트 경로 지정
- ./postgres-data:/var/lib/postgresql/data
```

| 구분 | Named Volume | Bind Mount |
|---|---|---|
| 저장 경로 | Docker가 관리 | 개발자가 호스트 경로 지정 |
| 호스트 파일에 직접 접근 | 상대적으로 불편 | 쉬움 |
| 운영체제별 경로 고려 | 비교적 적음 | 필요할 수 있음 |
| 컨테이너 삭제 후 데이터 재사용 | 가능 | 가능 |

둘 다 결국 디스크에 데이터를 저장한다. **Named Volume은 경로가 없는 저장소가 아니라 Docker에 경로 관리를 맡긴 저장소**다. MyShelf에서는 Windows/macOS 경로와 권한 차이를 줄이기 위해 Named Volume을 채택했다.

### 4.4. 실제 데이터는 어디에 저장되는가?

일반적인 Linux Docker Engine의 기본 로컬 볼륨은 보통 다음 위치에 저장된다.

```text
/var/lib/docker/volumes/<볼륨명>/_data
```

Windows/macOS의 Docker Desktop에서는 일반적으로 **Docker가 사용하는 Linux 환경의 가상 디스크 내부**에 데이터가 저장된다. 위 Linux 경로가 Windows의 `C:\...` 또는 macOS의 `/Users/...` 경로와 직접 일치한다는 뜻은 아니다.

실제 생성된 볼륨을 확인할 때는 다음 명령을 사용한다.

```bash
docker volume ls
docker volume inspect myshelf_postgres-data
```

Compose가 관리하는 볼륨 이름은 보통 `<프로젝트명>_postgres-data` 형태이므로 실제 이름을 `docker volume ls`로 먼저 확인한다. `inspect`의 `Mountpoint`는 **Docker Engine 내부에서 인식하는 마운트 경로**다.

PostgreSQL 데이터 파일을 직접 수정하지 말고 SQL 또는 데이터베이스 관리 도구로 조회·변경한다.

### 4.5. 볼륨의 생명주기

```bash
docker compose down      # 컨테이너/네트워크 제거, Named Volume은 기본 유지
docker compose down -v   # Compose의 Named Volume까지 제거
```

볼륨이 유지되면 PostgreSQL 컨테이너를 재생성하더라도 저장된 데이터를 다시 사용할 수 있다. 다만 **Named Volume은 백업 기능 자체가 아니다.**

또한 같은 `compose.yaml`을 다른 PC에서 실행해도 볼륨의 데이터가 자동으로 동기화되지는 않는다. Windows PC와 MacBook은 각자 별도의 Docker 데이터 저장 공간을 갖는다.

### 4.6. 컨테이너 재생성 후 데이터 영속성 검증

앞서 Named Volume의 데이터 보존 원리를 학습했으며, 이후 Spring Boot와 PostgreSQL을 Compose로 통합 실행하면서 실제 데이터 영속성을 검증했다.

검증 순서는 다음과 같다.

1. `POST /users`로 회원가입을 요청하여 HTTP 201 응답을 확인했다.
2. Hibernate 로그에서 `users` 테이블에 대한 INSERT 실행을 확인했다.
3. `docker compose down`으로 컨테이너와 Compose 네트워크를 제거했다.
4. `docker compose up -d`로 서비스를 다시 생성했다.
5. 동일 이메일로 회원가입을 요청하여 HTTP 409와 `DUPLICATE_EMAIL` 응답을 확인했다.
6. 기존 회원으로 로그인하여 JWT Access Token 발급에 성공했다.

```powershell
docker compose down
docker compose up -d
```

이번 검증으로 PostgreSQL 컨테이너를 제거하고 재생성해도 Named Volume에 저장된 회원 데이터가 유지된다는 것을 확인했다.

단, `docker compose down -v`는 실행하지 않았으며, 볼륨 삭제 후 데이터 복구까지 검증한 것은 아니다.

**Named Volume은 컨테이너의 생명주기와 데이터의 생명주기를 분리하지만, 데이터 백업이나 장애 복구를 자동으로 보장하지는 않는다.**

관련 학습 문서: `docker-compose-spring-boot-postgresql.md`

---

## 5. Docker Compose와 PostgreSQL 주요 명령어

### 5.1. Compose 명령어

```bash
docker compose up -d             # 서비스 생성 및 백그라운드 실행
docker compose ps                # 컨테이너 상태 확인
docker compose logs postgres     # PostgreSQL 로그 확인
docker compose logs -f postgres  # 로그를 실시간으로 확인
docker compose stop              # 서비스 중지
docker compose start             # 중지된 서비스 재시작
docker compose down              # 컨테이너 제거, 볼륨 기본 유지
```

`up -d`의 `-d`는 **Detached Mode(백그라운드 실행)**이다.

Compose는 기본적으로 작업 디렉터리와 그 상위 디렉터리에서 지원되는 설정 파일을 찾는다. `no configuration file provided: not found`가 출력되면 위치 또는 파일명을 확인한다. 파일 경로를 직접 지정할 수도 있다.

```bash
docker compose -f compose.yaml up -d
```

### 5.2. exec: 실행 중인 컨테이너에서 명령 실행

```bash
docker compose exec postgres psql -U myshelf -d myshelf
```

```text
docker compose exec postgres
└── 실행 중인 postgres 서비스 컨테이너에서

psql -U myshelf -d myshelf
└── psql 프로세스를 실행하여 DB에 접속
```

| 요소 | 뜻 |
|---|---|
| `exec` | 이미 실행 중인 컨테이너에서 새로운 프로세스 실행 |
| `postgres` | `compose.yaml`의 서비스 이름 |
| `psql` | PostgreSQL 명령줄 클라이언트 |
| `-U myshelf` | PostgreSQL 사용자 지정 |
| `-d myshelf` | 접속할 데이터베이스 지정 |

`exec`는 단순히 '컨테이너에 들어간다'는 명령이 아니라 **컨테이너 안에서 지정한 프로그램을 실행하는 명령**이다. 예를 들어 다음 명령은 `psql`이 아니라 `ls`를 실행한다.

```bash
docker compose exec postgres ls
```

### 5.3. exec와 run의 차이

- `docker compose exec`: 이미 실행 중인 서비스 컨테이너 안에서 명령을 실행한다.
- `docker compose run`: 서비스 설정을 바탕으로 **새로운 일회성 컨테이너**를 만들어 명령을 실행한다. 기본적으로 서비스의 포트는 게시하지 않는다.

현재는 PostgreSQL 서버 컨테이너가 이미 실행 중이므로 DB 접속에 `exec`를 사용한다.

`docker run` 역시 새 컨테이너를 생성하는 명령이지만, `docker compose run`은 Compose 서비스 설정을 이용한다는 차이가 있다.

### 5.4. 옵션 `-d`는 프로그램마다 뜻이 다르다

```bash
docker compose up -d  # Detached Mode
psql -d myshelf       # Database 선택
```

옵션의 의미는 각 명령어가 정의하므로 같은 문자라도 역할이 다를 수 있다.

### 5.5. psql 메타 명령어

`psql` 접속 후 다음 명령을 사용할 수 있다.

| 명령 | 의미 |
|---|---|
| `\dt` | 테이블 목록 |
| `\d` | 검색 경로에서 보이는 테이블 등 관계형 객체 목록 |
| `\d users` | `users` 테이블의 컬럼·인덱스·제약조건·참조 관계 |
| `\d+ users` | 추가 상세 정보 |
| `\q` | psql 종료 |

이들은 SQL이 아니라 **psql이 제공하는 메타 명령어**다.

컨테이너 내부에서 `psql -U myshelf -d myshelf`로 접속할 때 비밀번호 입력을 요구하지 않을 수 있다. `-h`를 지정하지 않으면 일반적으로 Unix Domain Socket을 사용하며, 실제 인증 여부는 PostgreSQL의 접속 인증 설정(`pg_hba.conf`)에 따른다. 따라서 비밀번호가 설정되지 않았다는 뜻은 아니다.

---

## 6. Spring Boot와 PostgreSQL 연결

PostgreSQL 단독 실행 단계의 MyShelf 구성은 다음과 같다.

```text
개발자 PC (Windows / macOS)
├── IntelliJ
│   └── MyShelf Spring Boot (local)
│       └── JDBC: localhost:5432
└── Docker Desktop
    └── PostgreSQL 컨테이너
        └── Named Volume
```

Spring Boot가 PC에서 실행되므로 `application-local.yaml`의 JDBC 주소는 `localhost`를 사용한다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myshelf
    driver-class-name: org.postgresql.Driver
    username: myshelf
    password: myshelf
```

`build.gradle`에서는 PostgreSQL JDBC 드라이버 및 Flyway PostgreSQL 모듈을 사용한다.

```groovy
runtimeOnly 'org.postgresql:postgresql'
runtimeOnly 'org.flywaydb:flyway-database-postgresql'
```

Flyway 활성화 및 `ddl-auto: validate`는 공통 설정에 유지했다. 기존 H2 기반 테스트는 이번 커밋 범위에서 전환하지 않았다.

### localhost와 Compose 서비스 이름의 차이

Spring Boot까지 Compose 컨테이너로 실행하면 컨테이너 내부의 `localhost`는 **그 컨테이너 자신**을 뜻한다. 같은 Compose 네트워크에 있는 PostgreSQL로 접속할 때는 일반적으로 서비스 이름을 사용한다.

```text
호스트에서 실행하는 Spring Boot → jdbc:postgresql://localhost:5432/myshelf
컨테이너에서 실행하는 Spring Boot → jdbc:postgresql://postgres:5432/myshelf
```

이 원리는 Windows/macOS 여부와 무관하다. 실제 운영 배포의 DB 주소 및 연결 방식은 추후 별도 결정한다.

---

## 7. MyShelf 실제 검증 결과

다음 결과는 **Windows 개발 환경에서 실제로 확인한 내용**이다.

### 7.1. PostgreSQL 연결

```text
The following 1 profile is active: "local"
HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection
Database: jdbc:postgresql://localhost:5432/myshelf (PostgreSQL 17.11)
```

Spring Boot의 `local` 프로파일로 실제 PostgreSQL에 연결했다.

### 7.2. Flyway 적용 및 Hibernate 검증

```text
Successfully validated 1 migration
Migrating schema "public" to version "1 - init"
Successfully applied 1 migration to schema "public", now at version v1
Initialized JPA EntityManagerFactory for persistence unit 'default'
```

기존 `V1__init.sql`이 PostgreSQL에 적용되었고, Hibernate도 `ddl-auto: validate` 설정에서 스키마 검증 오류 없이 초기화되었다.

Flyway의 `validated`와 `applied`, Hibernate의 `validate`는 서로 다른 작업이다. 상세한 원리는 별도 `flyway-and-hibernate.md`에서 다룬다.

### 7.3. 데이터베이스 스키마 확인

```bash
docker compose exec postgres psql -U myshelf -d myshelf
```

`\dt` 실행 결과:

```text
books
flyway_schema_history
user_books
users
```

Flyway 적용 이력:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history;
```

```text
installed_rank | version | description | success
1              | 1       | init        | t
```

`\d users`에서 확인한 제약조건:

```text
"users_pkey" PRIMARY KEY, btree (id)
"uk_users_email" UNIQUE CONSTRAINT, btree (email)
```

의도한 `uk_users_email` 제약조건이 실제 PostgreSQL에 존재한다는 점을 확인했다. 다만 제약조건 존재 확인과 중복 이메일 저장 시 애플리케이션 예외 변환 검증은 별개다.

---

## 8. 문제 해결 기록과 남은 과제

### 8.1. Windows 실습에서 발생한 문제

| 문제 | 원인과 해결 |
|---|---|
| Docker 버전은 출력되지만 `docker run` 실패 | CLI 설치 후 Docker Engine이 실행되지 않아 Docker Desktop 실행 |
| IntelliJ 터미널에서 `docker`를 찾지 못함 | 설치 이후 PATH 변경이 기존 IDE 프로세스에 반영되지 않아 IntelliJ 재시작 |
| `no configuration file provided` | Compose 파일을 찾지 못하는 위치에서 실행. 프로젝트 루트에서 재실행 |
| `No active profile set` | IntelliJ 실행 구성에서 `local` 프로파일이 전달되지 않아 설정 수정 후 재실행 |

이 내용은 실제 Windows 실습 사례다. 모든 환경에서 같은 문제가 발생한다는 의미는 아니다.

### 8.2. macOS에서 추후 검증할 사항

- Docker Desktop과 Docker Engine 정상 실행
- Apple Silicon 환경의 PostgreSQL 이미지 아키텍처 지원
- `docker compose up -d` 후 `docker compose ps` 상태 확인
- 호스트 포트 5432 충돌 여부 확인
- IntelliJ에서 `local` 프로파일로 Spring Boot 실행
- Flyway 적용 및 PostgreSQL 스키마 확인

아직 macOS에서 동일 구성을 직접 검증하지 않았으므로 완료한 작업으로 기록하지 않는다.

### 8.3. 다음 학습 과제

- Dockerfile을 이용한 Spring Boot 이미지 빌드
- Spring Boot와 PostgreSQL 컨테이너 네트워크 연결
- Compose로 두 서비스 함께 실행
- 실행 환경별 설정과 환경변수 관리
- GitHub Actions 기반 CI/CD 구성
- 별도 작업으로 Testcontainers PostgreSQL 통합 테스트 도입

---

## 9. 정리

이번 학습의 핵심은 다음과 같다.

1. **애플리케이션과 DB는 별개의 프로세스다.** Spring Boot와 PostgreSQL은 JDBC로 통신하며, Compose 파일을 같은 저장소에서 관리한다고 해서 DB가 Spring Boot 프로세스에 종속되지는 않는다.
2. **이미지, 컨테이너, 볼륨은 다른 개념이다.** 이미지는 실행 기반, 컨테이너는 실행 인스턴스, Named Volume은 Docker가 저장 위치를 관리하는 영속 저장 공간이다.
3. **Compose는 단일 컨테이너에도 유용하다.** 긴 `docker run` 옵션을 파일로 선언하고, 실행 환경을 Git으로 관리하며 반복해서 재현할 수 있다.
4. **운영체제가 달라도 핵심 원리는 같다.** Docker Desktop의 Linux 실행 환경은 Windows와 macOS에서 다르지만, Compose 설정과 주요 명령은 공통으로 활용할 수 있다.

MyShelf에서는 Windows 환경에서 PostgreSQL 컨테이너 실행, Flyway 적용, Hibernate 검증까지 완료했다. 다음 단계는 Spring Boot 애플리케이션을 Docker 이미지로 만들고 PostgreSQL 컨테이너와 연결하는 것이다.
