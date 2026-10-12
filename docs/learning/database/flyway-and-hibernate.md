# Flyway와 Hibernate의 스키마 관리 책임

## 1. 학습 배경

MyShelf는 초기 개발 환경에서 H2를 사용했고, Flyway를 통해 데이터베이스 스키마를 관리했다.

이후 로컬 개발 환경을 Docker PostgreSQL로 전환하면서 기존 Flyway 마이그레이션이 실제 PostgreSQL에서도 정상적으로 적용되는지 확인했다.

이 과정에서 다음 개념을 명확하게 구분할 필요가 있었다.

- Database Migration은 정확히 무엇인가?
- Flyway의 `validate`와 `migrate`는 어떻게 다른가?
- Hibernate의 `ddl-auto: validate`는 Flyway의 검증과 무엇이 다른가?
- Flyway가 스키마의 Source of Truth가 된다는 것은 어떤 의미인가?
- 실제 PostgreSQL에 생성된 스키마와 제약조건은 어떻게 확인하는가?

이 문서에서는 MyShelf에서 실제 확인한 로그와 PostgreSQL 실행 결과를 바탕으로 각 기술의 책임을 정리한다.

---

## 2. Database Migration이란?

Database Migration은 **데이터베이스의 구조 또는 데이터를 기존 상태에서 새로운 상태로 변경하는 작업**이다.

일반적으로 테이블 생성, 컬럼 추가, 제약조건 변경, 인덱스 생성 등이 포함된다. 필요한 경우 기존 데이터를 새로운 구조에 맞춰 변경하는 작업도 포함할 수 있다.

MyShelf의 최초 마이그레이션 파일은 다음과 같다.

`src/main/resources/db/migration/V1__init.sql`

이 파일에 정의된 DDL을 실행하여 초기 데이터베이스 스키마를 생성한다.

```text
[초기 PostgreSQL]
    테이블 없음
         |
         | V1__init.sql 실행
         v
[변경 후 PostgreSQL]
    users
    books
    user_books
```

마이그레이션은 최초 테이블 생성에만 국한되지 않는다.

예를 들어 이후 `books` 테이블에 `isbn` 컬럼을 추가한다면 다음과 같은 마이그레이션을 작성할 수 있다.

```sql
-- V2__add_isbn_to_books.sql

ALTER TABLE books
ADD COLUMN isbn VARCHAR(13);
```

이를 버전별로 정리하면 다음과 같다.

| 버전 | 변경 내용 |
|---|---|
| V1 | 초기 테이블 및 제약조건 생성 |
| V2 | books 테이블에 isbn 컬럼 추가 |
| V3 | isbn 컬럼에 UNIQUE 제약조건 추가 |

위 V2, V3는 개념 설명을 위한 예시이며 현재 MyShelf에 실제 적용된 마이그레이션은 아니다.

### 핵심

마이그레이션은 DB를 새로 생성하는 행위만 의미하지 않는다.

**데이터베이스를 특정 버전의 상태에서 다음 버전의 상태로 변경하는 과정**을 의미한다.

---

## 3. Flyway는 무엇을 하는가?

Flyway는 **데이터베이스 변경사항을 버전별로 실행하고, 적용 이력을 관리하는 데이터베이스 마이그레이션 도구**이다.

핵심 책임은 크게 세 가지다.

### 3.1. 마이그레이션 실행

Flyway는 설정된 경로에서 마이그레이션 파일을 검색한다.

MyShelf의 설정은 다음과 같다.

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

예를 들어 다음 파일들이 존재한다고 가정한다.

```text
db/migration/
├── V1__init.sql
├── V2__add_isbn_to_books.sql
└── V3__add_isbn_unique_constraint.sql
```

Flyway는 데이터베이스의 적용 이력을 확인한 후 아직 적용되지 않은 버전의 마이그레이션을 순서대로 실행한다.

이미 V1까지 적용된 데이터베이스라면 다음 실행 시 V1을 다시 실행하지 않고 V2, V3를 적용한다.

### 3.2. 마이그레이션 이력 관리

Flyway는 다음 테이블을 이용해 적용 이력을 관리한다.

```text
flyway_schema_history
```

이 테이블에는 다음과 같은 정보가 저장된다.

- 마이그레이션 버전
- 설명
- 스크립트 이름
- 체크섬
- 적용 시점
- 적용 성공 여부

MyShelf에서 확인한 결과는 다음과 같다.

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history;
```

```text
installed_rank | version | description | success
---------------+---------+-------------+--------
1              | 1       | init        | t
```

이는 V1 마이그레이션이 정상적으로 적용되었음을 의미한다.

### 3.3. 마이그레이션 유효성 검증

Flyway는 이미 적용된 마이그레이션의 이력과 현재 존재하는 마이그레이션 파일을 비교한다.

대표적으로 다음을 확인한다.

- 적용된 마이그레이션과 파일의 체크섬이 일치하는가?
- 적용된 버전과 현재 파일 목록 사이에 불일치가 있는가?
- 마이그레이션 이력에 문제가 있는가?

여기서 중요한 점은 **Flyway의 검증이 실제 테이블 구조와 JPA 엔티티를 비교하는 작업은 아니라는 것**이다.

Flyway는 기본적으로 자신이 관리하는 마이그레이션의 적용 이력과 무결성을 검증한다.

---

## 4. Flyway validate와 migrate의 차이

MyShelf를 PostgreSQL로 전환한 후 다음 로그를 확인했다.

```text
DbValidate : Successfully validated 1 migration

DbMigrate : Migrating schema "public" to version "1 - init"

DbMigrate : Successfully applied 1 migration to schema "public",
            now at version v1
```

두 단계는 서로 다른 작업이다.

### 4.1. Flyway validate

```text
org.flywaydb.core.internal.command.DbValidate
```

```text
Successfully validated 1 migration
```

Flyway의 `validate`는 마이그레이션 파일과 적용 이력의 유효성을 검사한다.

SQL 파일을 실제 DB에 새로 적용했다는 의미는 아니다.

### 4.2. Flyway migrate

```text
org.flywaydb.core.internal.command.DbMigrate
```

```text
Successfully applied 1 migration
```

Flyway의 `migrate`는 데이터베이스에 아직 적용되지 않은 마이그레이션을 실행한다.

즉, 실제 DB 구조나 데이터가 변경될 수 있는 단계다.

### 4.3. 최초 실행과 재실행의 차이

| 구분 | 최초 실행 | 동일한 DB에 재실행 |
|---|---|---|
| Flyway validate | 수행 | 수행 |
| 마이그레이션 상태 확인 | 수행 | 수행 |
| V1 마이그레이션 적용 | 실행 | 생략 |
| 실제 스키마 변경 | 발생 | 신규 마이그레이션이 없다면 없음 |

이미 V1이 적용된 데이터베이스에서는 다음과 유사한 로그가 출력될 수 있다.

```text
Schema "public" is up to date. No migration necessary.
```

따라서 `Successfully applied` 로그가 보이지 않는다고 해서 문제가 발생한 것은 아니다.

새롭게 적용할 마이그레이션이 없다면 정상적인 상황이다.

---

## 5. Hibernate의 ddl-auto: validate

MyShelf에서는 Hibernate의 자동 스키마 변경 기능을 사용하지 않는다.

공통 설정은 다음과 같다.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

`ddl-auto: validate`는 **JPA 엔티티 매핑과 실제 DB 스키마의 호환성을 검사하는 설정**이다.

예를 들어 JPA 엔티티가 다음 컬럼을 요구한다고 가정한다.

```java
@Entity
@Table(name = "users")
public class UserJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;
}
```

Hibernate는 실제 데이터베이스에 대응하는 테이블과 컬럼이 존재하는지, 매핑된 타입이 호환되는지 등을 검사한다.

요구하는 스키마가 없거나 호환되지 않는다면 애플리케이션 시작 과정에서 오류가 발생할 수 있다.

반대로 검증을 통과하면 JPA 영속성 계층 초기화가 계속 진행된다.

MyShelf에서 다음 로그를 확인했다.

```text
Initialized JPA EntityManagerFactory for persistence unit 'default'
```

`ddl-auto: validate`가 활성화된 상태에서 오류 없이 EntityManagerFactory 초기화까지 완료되었으므로 Hibernate 스키마 검증을 통과한 것으로 판단할 수 있다.

### validate의 한계

Hibernate의 `validate`가 모든 DB 구조를 완벽하게 검증하는 것은 아니다.

예를 들어 다음 사항은 별도로 확인할 필요가 있다.

- UNIQUE 제약조건의 정확한 이름
- 인덱스 구성
- 모든 제약조건의 세부 정의
- 데이터베이스 고유 기능의 동작

따라서 Hibernate 검증에 성공했다고 해서 Flyway가 의도한 모든 제약조건이 정확히 구성되었다고 단정해서는 안 된다.

---

## 6. Flyway validate와 Hibernate validate 비교

이름은 같지만 목적이 다르다.

| 구분 | Flyway validate | Hibernate validate |
|---|---|---|
| 담당 기술 | Flyway | Hibernate ORM |
| 검증 대상 | 마이그레이션 파일과 적용 이력 | 엔티티 매핑과 실제 DB 스키마 |
| 주요 목적 | 마이그레이션 이력의 무결성 확인 | JPA 매핑과 DB 스키마의 호환성 확인 |
| 스키마 생성 | 하지 않음 | 하지 않음 |
| 대표 설정 | Flyway 검증 기능 | `ddl-auto: validate` |

MyShelf의 애플리케이션 시작 흐름은 다음과 같다.

```text
Spring Boot 시작
        |
        v
PostgreSQL 연결
        |
        v
Flyway validate
  - 마이그레이션 이력 검증
        |
        v
Flyway migrate
  - 미적용 SQL 실행
        |
        v
Hibernate validate
  - JPA 매핑과 실제 스키마 검증
        |
        v
EntityManagerFactory 초기화
        |
        v
애플리케이션 시작
```

현재 구조에서는 Flyway가 스키마 변경을 담당하고, Hibernate는 변경된 스키마를 검증한 후 영속성 처리를 수행한다.

---

## 7. Schema Source of Truth란?

Source of Truth는 어떤 정보나 상태를 판단할 때 기준으로 삼는 권위 있는 원천을 의미한다.

MyShelf에서는 **Flyway 마이그레이션 SQL을 데이터베이스 스키마 변경의 Source of Truth로 사용한다.**

이는 Flyway가 ORM과 DB 사이에서 중재한다는 의미가 아니다.

Flyway와 Hibernate는 서로를 통해 통신하는 관계가 아니라, 각자 데이터베이스에 접근하며 서로 다른 책임을 수행한다.

```text
      Flyway                  Hibernate
        |                         |
        | 마이그레이션 실행       | 엔티티 매핑
        |                         | 스키마 검증
        v                         v
  +--------------------------------------+
  |             PostgreSQL               |
  |                                      |
  |  users                               |
  |  books                               |
  |  user_books                          |
  |  flyway_schema_history               |
  +--------------------------------------+
```

각 구성 요소의 책임은 다음과 같다.

| 구성 요소 | 책임 |
|---|---|
| Flyway SQL 파일 | 의도한 스키마 변경사항 정의 |
| Flyway | SQL 변경사항 실행 및 적용 이력 관리 |
| PostgreSQL | 실제 스키마와 데이터 저장 |
| JPA 엔티티 | Java 객체와 DB 테이블의 매핑 정의 |
| Hibernate | ORM 기반 영속성 처리 및 스키마 호환성 검증 |

따라서 MyShelf에서 새로운 컬럼이 필요하다면 JPA 엔티티만 수정하는 것으로 작업이 끝나지 않는다.

일반적인 변경 과정은 다음과 같다.

1. 필요한 스키마 변경사항을 설계한다.
2. 새로운 Flyway 마이그레이션 SQL을 작성한다.
3. 해당 변경에 맞춰 JPA 엔티티 매핑을 수정한다.
4. 애플리케이션을 실행하여 Flyway 마이그레이션을 적용한다.
5. Hibernate `validate`로 매핑 호환성을 확인한다.
6. 관련 테스트로 실제 동작을 검증한다.

Flyway가 스키마 변경을 관리하도록 결정했다면 Hibernate의 `ddl-auto: update` 등으로 스키마를 별도로 변경하지 않는 것이 중요하다.

그렇게 하면 스키마 변경 경로가 둘로 나뉘어 관리 일관성이 깨질 수 있기 때문이다.

---

## 8. MyShelf에서 실제 검증한 결과

로컬 PostgreSQL로 전환한 뒤 다음 명령어로 DB에 접속했다.

```bash
docker compose exec postgres psql -U myshelf -d myshelf
```

각 명령어의 의미는 다음과 같다.

| 명령 | 의미 |
|---|---|
| `docker compose` | Compose 프로젝트의 서비스 관리 |
| `exec` | 실행 중인 컨테이너에서 명령 실행 |
| `postgres` | Compose 서비스 이름 |
| `psql` | PostgreSQL 클라이언트 실행 |
| `-U myshelf` | DB 사용자 지정 |
| `-d myshelf` | 접속할 데이터베이스 지정 |

`exec`는 특정 컨테이너에 접속한다는 의미로 사용되기도 하지만, 정확히는 **이미 실행 중인 컨테이너 내부에서 지정한 프로세스를 실행하는 명령**이다.

이번에는 `postgres` 서비스 컨테이너 내부에서 `psql`을 실행한 것이다.

### 8.1. 테이블 생성 확인

```sql
\dt
```

```text
books
flyway_schema_history
user_books
users
```

`\dt`는 `psql`의 테이블 목록 조회 명령이다.

Flyway 이력 테이블과 MyShelf의 데이터 테이블들이 정상적으로 생성된 것을 확인했다.

### 8.2. Flyway 마이그레이션 이력 확인

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history;
```

```text
installed_rank | version | description | success
---------------+---------+-------------+--------
1              | 1       | init        | t
```

V1 마이그레이션이 성공적으로 적용된 상태다.

### 8.3. users 테이블 구조 확인

```sql
\d users
```

`\d users`는 `users` 테이블의 컬럼, 인덱스, 제약조건, 참조 관계 등을 확인하는 `psql` 명령이다.

확인된 주요 결과는 다음과 같다.

```text
Column   | Type                   | Nullable
---------+------------------------+---------
id       | uuid                   | not null
email    | character varying(255) | not null
password | character varying(255) | not null

Indexes:
    "users_pkey" PRIMARY KEY, btree (id)
    "uk_users_email" UNIQUE CONSTRAINT, btree (email)
```

이를 통해 다음 사항을 확인했다.

- `users` 테이블이 생성되었다.
- `id` 컬럼에 기본 키가 설정되었다.
- `email` 컬럼에 UNIQUE 제약조건이 설정되었다.
- 제약조건 이름이 `uk_users_email`로 생성되었다.

특히 이전에 Hibernate 스키마 변경 설정 때문에 UNIQUE 제약조건 이름이 달라졌던 문제와 관련하여, 이번에는 Flyway에서 의도한 이름으로 생성되었음을 직접 확인했다.

단, 제약조건의 존재를 확인한 것만으로 애플리케이션의 중복 이메일 예외 변환까지 검증한 것은 아니다.

`UserException(DUPLICATE_EMAIL)` 변환 동작은 별도의 통합 테스트를 통해 검증해야 한다.

---

## 9. 기존 마이그레이션 파일을 수정하면 안 되는 이유

Flyway는 적용된 마이그레이션의 체크섬을 관리한다.

따라서 이미 적용한 `V1__init.sql`의 내용을 변경하면 기존 적용 이력과 현재 파일 사이에 불일치가 발생할 수 있다.

예를 들어 V1이 적용된 이후 새로운 컬럼이 필요하다면 기존 V1을 수정하기보다 새로운 버전의 SQL을 추가하는 것이 원칙이다.

```text
V1__init.sql
V2__add_isbn_to_books.sql
```

이렇게 해야 환경별로 어떤 변경이 적용되었는지 추적할 수 있다.

단, 아직 어느 환경에도 적용되지 않은 마이그레이션은 개발 과정에서 수정할 수 있다.

핵심은 **이미 공유되거나 적용된 마이그레이션 이력을 임의로 변경하지 않는 것**이다.

---

## 10. 정리

이번 PostgreSQL 전환을 통해 Flyway와 Hibernate가 각기 다른 책임을 수행한다는 점을 확인했다.

**Flyway**

- 데이터베이스 변경사항을 버전별로 관리한다.
- 적용되지 않은 마이그레이션을 실행한다.
- `flyway_schema_history`에 적용 이력을 기록한다.
- 마이그레이션 파일과 적용 이력의 무결성을 검증한다.

**Hibernate**

- JPA 엔티티와 데이터베이스 테이블 사이의 매핑을 처리한다.
- 객체의 저장, 조회, 변경, 삭제를 담당한다.
- `ddl-auto: validate` 설정에서 실제 DB 스키마와 매핑의 호환성을 검증한다.

**MyShelf의 스키마 관리 원칙**

- 스키마 변경은 Flyway 마이그레이션 SQL로 관리한다.
- Hibernate는 스키마를 자동 변경하지 않는다.
- 새로운 스키마 변경은 새로운 마이그레이션으로 추가한다.
- Hibernate 검증뿐 아니라 필요한 제약조건 및 실제 DB 동작을 별도로 검증한다.

최종적으로 다음과 같이 구분할 수 있다.

> Flyway는 데이터베이스 스키마가 어떻게 변경되어 왔는지 관리하고, Hibernate는 현재 스키마를 이용해 Java 객체를 영속화한다.
>
> MyShelf에서 스키마 변경의 기준은 Flyway 마이그레이션 SQL이며, 실제 스키마는 PostgreSQL에 존재한다.