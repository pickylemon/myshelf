# 개발 환경 데이터베이스를 H2에서 PostgreSQL로 전환

- 상태: Accepted
- 범위: MyShelf 로컬 개발 환경
- 관련 기술: H2, PostgreSQL, Docker Compose, Flyway, Hibernate
- 향후 검토: Testcontainers 기반 통합 테스트

> 구현 기준 정정: PostgreSQL 전환 커밋 `8816602`에서 통합 테스트도 `local` 프로필의 PostgreSQL 설정을 사용했다. 아래는 당시 구현에 맞게 정정한 기록이다. 이후 테스트 DB를 `myshelf_test`로 분리하고 `test` 프로필만 사용하도록 변경했으며, 미사용 H2 설정과 의존성도 제거했다. 추가로 PostgreSQL 최초 초기화 시 테스트 DB가 자동 생성되도록 개선했다. Testcontainers 도입은 아직 별도 과제다.

## 1. Context

MyShelf는 초기 개발 환경과 테스트 환경에서 H2 인메모리 데이터베이스를 사용했다.

초기에는 헥사고날 아키텍처 구조 설계, 회원가입 및 로그인 기능 구현, JPA 영속성 계층 검증에 집중하고 있었기 때문에 별도의 데이터베이스 서버 없이 실행할 수 있는 H2가 적절했다.

H2는 PostgreSQL 호환 모드도 제공하므로 기본적인 SQL과 JPA 매핑을 검증하는 데 활용할 수 있다.

하지만 PostgreSQL 호환 모드가 PostgreSQL과 완전히 동일한 동작을 보장하는 것은 아니다.

두 데이터베이스는 서로 다른 엔진이며 다음과 같은 차이가 발생할 수 있다.

- SQL 문법 및 데이터 타입 지원
- 제약조건 위반 시 반환되는 오류 정보
- 트랜잭션과 동시성 처리
- 인덱스 및 쿼리 실행 계획
- 데이터베이스 고유 기능
- Flyway 마이그레이션 적용 결과

MyShelf는 Flyway를 이용해 데이터베이스 스키마를 관리하고 Hibernate의 `ddl-auto: validate`로 엔티티 매핑과 실제 스키마의 호환성을 검증한다.

이전에 Hibernate의 DDL 자동 변경 설정으로 인해 Flyway에서 정의한 UNIQUE 제약조건 이름과 실제 데이터베이스의 제약조건 이름이 달라진 문제를 경험했다.

해당 문제의 직접적인 원인은 H2가 아니라 Flyway와 Hibernate의 스키마 관리 책임이 충돌한 것이었다.

따라서 이 사례를 H2 자체의 결함으로 해석해서는 안 된다.

다만 이 경험을 통해 스키마 관리 주체를 명확히 분리하고, 향후 운영에 사용할 실제 데이터베이스 엔진에서 마이그레이션과 영속성 동작을 검증할 필요성을 확인했다.

Docker 기반 개발 환경을 구축하는 시점에 맞춰 로컬 데이터베이스 전환을 결정했다.

---

## 2. Decision

MyShelf의 로컬 개발 데이터베이스를 H2에서 PostgreSQL로 전환한다.

### 2.1. 로컬 개발 환경

PostgreSQL을 Docker Compose로 실행한다.

PostgreSQL 전환 당시 Spring Boot 애플리케이션은 개발자 PC의 IntelliJ에서 실행하고, PostgreSQL만 Docker Compose로 관리했다.

이후 Docker Compose에 Spring Boot 서비스도 통합했으므로, 현재는 애플리케이션을 호스트에서 직접 실행하거나 컨테이너로 실행할 수 있다.

```text
Developer PC
├── IntelliJ
│   └── MyShelf Spring Boot
│       └── application-local.yaml
│
└── Docker Desktop
    └── PostgreSQL 17
        └── Named Volume
```

Spring Boot는 JDBC를 통해 `localhost:5432`의 PostgreSQL에 접속한다.

### 2.2. 데이터베이스 스키마 관리

스키마 변경은 Flyway 마이그레이션 SQL로 관리한다.

Hibernate는 다음 설정을 유지한다.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

이를 통해 역할을 구분한다.

- Flyway: 데이터베이스 스키마 변경 및 이력 관리
- Hibernate: 엔티티 매핑과 실제 스키마의 호환성 검증
- PostgreSQL: 실제 스키마와 데이터 저장

### 2.3. 테스트 환경

PostgreSQL 전환 당시 DB를 사용하는 통합 테스트는 `@ActiveProfiles({"local", "test"})`를 통해 `application-local.yaml`의 Datasource 설정을 사용했다. 따라서 로컬 DB를 PostgreSQL로 변경하면서 통합 테스트의 접속 대상도 PostgreSQL로 바뀌었다.

당시 `application-test.yaml`에는 테스트 전용 JWT secret만 정의되어 있었으며, H2 Datasource로 대체하는 설정은 없었다. 통합 테스트는 개발용 PostgreSQL DB를 공유했고, 테스트 DB 분리와 Testcontainers를 통한 DB 자동 준비는 후속 작업으로 남겼다.

이후 테스트 환경을 다음과 같이 변경했다.

- 로컬 개발 DB: `myshelf`
- 통합 테스트 DB: `myshelf_test`
- 통합 테스트는 `test` 프로필만 사용한다.
- `application-test.yaml`에서 `myshelf_test`에 연결한다.
- 개발 DB와 테스트 DB는 동일한 PostgreSQL 인스턴스에 존재하지만 데이터베이스 단위로 분리한다.

초기에는 `myshelf_test`를 수동으로 생성해야 했으나, 이후 Docker Compose에 PostgreSQL 초기화 SQL을 연결하여 신규 환경에서 자동 생성되도록 개선했다.

초기화 SQL은 `docker/postgres/init/01-create-test-db.sql`에 위치하며 다음 데이터베이스를 생성한다.

```sql
CREATE DATABASE myshelf_test OWNER myshelf;
```

`compose.yaml`은 해당 디렉터리를 PostgreSQL 공식 이미지의 `/docker-entrypoint-initdb.d`에 읽기 전용으로 마운트한다.

PostgreSQL 데이터 디렉터리가 비어 있는 최초 초기화 시 다음 순서로 실행된다.

1. `POSTGRES_DB=myshelf` 설정에 따라 개발 DB 생성
2. `/docker-entrypoint-initdb.d`의 초기화 SQL 실행
3. `myshelf_test` 데이터베이스 생성

단, 이 초기화 스크립트는 컨테이너가 시작할 때마다 실행되는 것이 아니다. PostgreSQL 데이터 디렉터리가 이미 초기화되어 있다면 실행되지 않는다.

따라서 기존 볼륨에 `myshelf_test`가 없다면 수동으로 한 번 생성해야 한다. 기존 개발 데이터를 삭제하면서 초기화를 다시 수행할 필요는 없다.

이 구성은 테스트 데이터베이스의 최초 생성만 자동화한다.

Testcontainers와 달리 통합 테스트 실행 시 PostgreSQL 컨테이너를 자동으로 준비하거나 생명주기를 관리하지 않으며, 테스트 간 데이터 격리와 정리도 별도로 처리해야 한다.

단위 테스트는 가능하면 실제 데이터베이스에 의존하지 않도록 구성한다.

### 2.4. 운영 환경

운영 데이터베이스는 PostgreSQL을 사용할 계획이다.

다만 운영 PostgreSQL을 동일 서버의 컨테이너로 운영할지, 별도 서버 또는 관리형 DB로 구성할지는 배포 단계에서 결정한다.

이번 결정은 운영 데이터베이스의 구체적인 구축 방식까지 확정하는 것은 아니다.

---

## 3. Alternatives Considered

### 3.1. H2 PostgreSQL 호환 모드 유지

**장점**

- 별도 DB 서버가 필요하지 않음
- 실행 및 초기화가 간단함
- 기존 개발 환경을 변경하지 않아도 됨
- 기본적인 JPA 및 CRUD 검증에 충분함

**단점**

- 실제 PostgreSQL과 동일한 동작을 보장하지 않음
- PostgreSQL 고유 기능과 오류 처리를 검증하기 어려움
- 운영 DB와의 호환성을 별도로 확인해야 함

초기 개발 단계에서는 합리적인 방식이었으나, 운영 DB의 동작을 검증하려는 현재 목적에는 한계가 있다고 판단했다.

### 3.2. 로컬은 PostgreSQL, 통합 테스트는 H2 유지

**장점**

- 로컬에서 실제 PostgreSQL 동작을 확인할 수 있음
- 기존 테스트 환경을 유지할 수 있음
- 변경 범위를 단계적으로 관리할 수 있음

**단점**

- 테스트 통과가 PostgreSQL에서의 동일한 동작을 보장하지 않음
- 개발 환경과 테스트 환경에서 사용하는 DB 엔진이 다름

검토한 대안으로 남긴다. 실제 구현에서는 통합 테스트도 `local` 프로필의 PostgreSQL 설정을 사용하므로 이 구성은 적용되지 않았다.

### 3.3. 로컬 및 통합 테스트 모두 PostgreSQL 사용

**장점**

- 개발과 테스트에서 실제 PostgreSQL 동작 검증 가능
- DB 엔진 차이로 인한 위험 감소
- JDBC 예외, 제약조건, 트랜잭션 동작 등을 실제 엔진에서 검증 가능

**단점**

- 테스트 실행 시 PostgreSQL 서버와 접속 정보 준비 필요
- H2보다 테스트 실행 비용이 증가할 수 있음
- Testcontainers로 DB를 자동 준비한다면 Docker 실행 환경과 추가 설정 및 학습이 필요함

실제 구현에서는 로컬 개발과 DB 통합 테스트 모두 PostgreSQL을 사용한다.

다만 당시에는 개발용 DB와 테스트 DB를 분리하거나 Testcontainers로 DB의 생명주기를 관리하는 구성까지 적용한 것은 아니었다. PostgreSQL 엔진 사용과 테스트 실행 환경의 격리는 구분한다.

---

## 4. Consequences

### 4.1. 긍정적 영향

**운영 환경과의 일관성 향상**

로컬에서 실제 PostgreSQL을 사용하므로 DB 엔진 차이에서 발생하는 문제를 개발 과정에서 확인할 수 있다.

**Flyway 마이그레이션 검증 강화**

H2가 아닌 PostgreSQL에서 마이그레이션 SQL이 정상적으로 적용되는지 직접 확인할 수 있다.

**실제 제약조건 확인 가능**

PostgreSQL의 `psql`을 이용해 UNIQUE, PRIMARY KEY, FOREIGN KEY 등의 제약조건을 확인할 수 있다.

**실행 환경 재현성 향상**

Docker Compose를 통해 필요한 PostgreSQL 이미지와 실행 설정을 코드로 관리할 수 있다.

### 4.2. 부정적 영향

**개발 환경 의존성 증가**

로컬에서 MyShelf를 실행하려면 PostgreSQL 컨테이너가 준비되어 있어야 한다.

**환경 관리 비용 증가**

컨테이너 실행 상태, 포트 충돌, 볼륨 및 데이터 초기화 등을 관리해야 한다.

**테스트 데이터베이스 실행 환경 의존성**

PostgreSQL 전환 당시 통합 테스트는 개발 DB를 공유했으므로 테스트 데이터가 개발 환경에 영향을 줄 수 있었다.

이후 `myshelf_test`로 데이터베이스를 분리하여 데이터 공유 문제를 개선했다.

현재는 PostgreSQL 최초 초기화 과정에서 테스트 DB가 자동 생성되지만, 통합 테스트 실행 전에 PostgreSQL 컨테이너가 준비되어 있어야 한다.

또한 개발 DB와 테스트 DB는 동일한 PostgreSQL 인스턴스의 자원을 공유하며, 테스트 간 데이터 격리와 정리는 별도로 관리해야 한다.

### 4.3. 유의 사항

PostgreSQL로 전환하더라도 Flyway나 Hibernate 관련 문제가 자동으로 해결되는 것은 아니다.

스키마 관리 책임, 트랜잭션 경계, 예외 처리 등은 별도로 올바르게 설계하고 검증해야 한다.

또한 Docker Named Volume을 사용한다고 해서 운영 수준의 백업이나 복구가 보장되는 것은 아니다.

---

## 5. Verification

로컬 PostgreSQL 전환 후 다음 사항을 확인했다.

### PostgreSQL 연결

Spring Boot 실행 로그에서 PostgreSQL JDBC 연결을 확인했다.

```text
HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection
Database: jdbc:postgresql://localhost:5432/myshelf (PostgreSQL 17.11)
```

### Flyway 마이그레이션

```text
Successfully validated 1 migration
Migrating schema "public" to version "1 - init"
Successfully applied 1 migration to schema "public"
```

기존 V1 마이그레이션이 실제 PostgreSQL에 적용되었다.

### Hibernate 스키마 검증

`ddl-auto: validate` 상태에서 EntityManagerFactory 초기화 및 애플리케이션 시작이 정상적으로 완료되었다.

### PostgreSQL 테이블 확인

```text
books
flyway_schema_history
user_books
users
```

### UNIQUE 제약조건 확인

`psql`의 `\d users` 명령으로 다음 제약조건을 확인했다.

```text
"uk_users_email" UNIQUE CONSTRAINT, btree (email)
```

Flyway에서 의도한 이름의 UNIQUE 제약조건이 실제 PostgreSQL에 존재함을 확인했다.

단, 실제 중복 저장 시 도메인 예외로 변환되는 동작까지 이번 검증에서 확인한 것은 아니다.

### 테스트 DB 자동 생성 검증 (후속 변경)

PostgreSQL 전환 이후 `myshelf_test` 자동 생성 기능을 추가하고, 신규 데이터 볼륨에서 초기화 SQL이 정상 실행되는지 검증했다.

기존 개발 DB의 데이터를 보존하기 위해 별도의 Docker Compose 프로젝트 `myshelf-verify`를 사용했다.

검증 시 `compose.yaml`과 임시 `compose.verify.yaml`을 병합했다. 검증용 설정에서는 기존 컨테이너 이름과 호스트 포트 매핑을 `!reset`으로 제거하여 개발 환경과의 충돌을 방지했다.

**1. 신규 볼륨으로 PostgreSQL 실행**

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  up -d --wait postgres
```

검증 결과:

- `myshelf-verify_postgres-data` 신규 볼륨 생성
- `myshelf-verify_default` 네트워크 생성
- `myshelf-verify-postgres-1` 컨테이너 Healthy 상태 확인

**2. 테스트 DB 자동 생성 확인**

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  exec postgres \
  psql -U myshelf -d myshelf_test \
  -c "SELECT current_database();"
```

실행 결과:

```text
current_database
----------------
myshelf_test
(1 row)
```

추가로 `psql`의 `\l` 명령을 사용하여 `myshelf`와 `myshelf_test`가 모두 존재하고, 두 데이터베이스의 소유자가 `myshelf`임을 확인했다.

**3. 검증용 리소스 정리**

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  down -v
```

검증용 컨테이너, 네트워크, 볼륨이 제거된 것을 확인했다.

기존 개발 환경의 PostgreSQL 데이터 볼륨은 삭제하지 않았다.

이를 통해 신규 PostgreSQL 데이터 볼륨에서 개발 DB와 테스트 DB가 자동으로 생성되는 기능을 검증했다.

단, 이 검증은 데이터베이스의 생성 및 접속 가능 여부에 대한 것으로, Spring Boot 애플리케이션의 통합 테스트 전체 실행 결과와는 구분한다.

---

## 6. Follow-up

PostgreSQL 전환 당시 정의한 후속 작업과 현재 진행 상태는 다음과 같다.

- Testcontainers PostgreSQL 기반 DB 통합 테스트 도입 — 미진행
- 개발용 PostgreSQL DB와 통합 테스트용 DB 분리 — 완료 (`myshelf` / `myshelf_test`)
- PostgreSQL 최초 초기화 시 테스트 DB 자동 생성 — 완료 (`docker-entrypoint-initdb.d` 활용)
- 불필요한 H2 관련 의존성 및 설정 정리 — 완료
- Spring Boot 애플리케이션 Docker 이미지 구성 — 완료
- 운영 DB의 실행 방식 및 백업 전략 결정 — 미진행

테스트 DB 자동 생성은 Docker Compose 환경의 최초 초기화를 대상으로 한다.

이미 초기화된 PostgreSQL 볼륨에 테스트 DB를 추가하거나, 테스트 실행마다 데이터베이스를 생성·삭제하는 기능은 포함하지 않는다.

Testcontainers 도입과 테스트 간 데이터 정리는 별도의 후속 과제로 유지한다.

---

## 7. 결론

MyShelf 초기에는 개발 편의성과 빠른 영속성 계층 검증을 위해 H2를 사용하는 것이 합리적이었다.

그러나 운영 데이터베이스를 PostgreSQL로 사용할 계획이고, Flyway 및 DB 제약조건에 대한 실제 엔진 검증이 중요해짐에 따라 로컬 개발 환경을 PostgreSQL로 전환했다.

**이번 결정은 H2가 잘못된 선택이었다는 의미가 아니라, 프로젝트의 검증 요구가 달라짐에 따라 데이터베이스 실행 환경을 변경한 것이다.**

개발 및 DB 통합 테스트는 PostgreSQL을 사용하며, 현재는 개발용 `myshelf`와 테스트용 `myshelf_test` DB로 분리했다.

또한 PostgreSQL 공식 이미지의 초기화 메커니즘을 활용하여 신규 Docker Compose 환경에서 두 데이터베이스가 자동으로 생성되도록 구성했다.

이를 통해 신규 개발 환경에서 테스트 DB를 수동으로 생성해야 했던 문제를 개선했다.

향후에는 Testcontainers와 테스트 데이터 정리 전략을 검토하여 통합 테스트의 독립성과 재현성을 더욱 높일 계획이다.
