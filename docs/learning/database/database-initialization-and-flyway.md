# Database 초기화와 Flyway 마이그레이션

## 1. DB 생성과 마이그레이션의 차이

DB 생성과 Flyway 마이그레이션은 서로 다른 작업이다.

| 구분 | DB 초기화 | Flyway 마이그레이션 |
|---|---|---|
| 목적 | 데이터베이스 준비 | 테이블 및 스키마 구조 관리 |
| 실행 주체 | PostgreSQL 초기화 과정 | Flyway |
| 실행 시점 | 최초 DB 초기화 시 | 애플리케이션 시작 시 |
| 결과 | `myshelf`, `myshelf_test` 생성 | `users`, `shelves` 등 테이블 생성·변경 |

DB가 존재한다고 해서 해당 DB에 테이블까지 생성되어 있는 것은 아니다.

## 2. Docker Compose와 PostgreSQL 초기화

MyShelf는 개발용 `myshelf`와 테스트용 `myshelf_test`를 분리하여 사용한다.

PostgreSQL 공식 Docker 이미지에서는 데이터 디렉터리가 비어 있을 때 초기화 작업을 수행한다.

이때 환경변수 및 초기화 SQL을 통해 데이터베이스를 생성할 수 있다.

```text
docker compose up -d
    ↓
PostgreSQL 컨테이너 시작
    ↓
데이터 디렉터리가 비어 있으면 초기화 수행
    ↓
myshelf / myshelf_test DB 준비
```

**주의 사항**

- 초기화 스크립트는 기존 데이터가 있는 볼륨에서는 다시 실행되지 않는다.
- `docker compose down -v`는 Compose에서 관리하는 볼륨을 삭제하므로 DB 데이터가 유실될 수 있다.
- 같은 PostgreSQL 컨테이너 안에 개발용·테스트용 DB가 있어도 두 DB의 테이블과 데이터는 독립적이다.

## 3. Spring Profile과 Flyway

Flyway가 직접 Spring Profile을 판단하는 것은 아니다.

Spring Boot가 활성화된 Profile에 따라 DataSource를 구성하고, Flyway는 기본적으로 해당 DataSource를 통해 마이그레이션을 실행한다.

단, Flyway 전용 연결 설정이 있다면 다른 DB를 사용할 수도 있다.

### local 프로필

```text
Spring Boot 실행 (local)
    ↓
DataSource → myshelf
    ↓
Flyway 실행
    ↓
myshelf에 V1~V3 적용
```

### test 프로필

```text
Spring Boot Test 실행 (test)
    ↓
DataSource → myshelf_test
    ↓
Flyway 실행
    ↓
myshelf_test에 V1~V3 적용
```

따라서 `local` 프로필로 애플리케이션을 실행해도 `myshelf_test`에 마이그레이션이 자동 적용되지는 않는다.

각 DB의 마이그레이션 이력 역시 별도로 관리된다.

## 4. 실제 MyShelf에서 확인한 사례

Docker 볼륨 초기화 후 애플리케이션을 실행했을 때 다음 로그를 확인했다.

```text
Successfully validated 3 migrations
```

하지만 `myshelf_test`에 접속하면 테이블이 존재하지 않았다.

```sql
\dt
-- Did not find any relations.
```

원인은 일반 애플리케이션 실행과 테스트 실행의 DB 연결이 다르기 때문이었다.

이후 Gradle 테스트를 실행하자 `test` 프로필에 따라 `myshelf_test`에도 Flyway 마이그레이션이 적용되었다.

참고로 `Successfully validated`는 마이그레이션 검증 성공을 의미하며, 실제 신규 마이그레이션 적용을 의미하는 로그와는 구분해야 한다.

## 5. 핵심 정리

- **Docker/PostgreSQL 초기화**는 DB 자체를 준비하는 과정이다.
- **Flyway**는 연결된 DB의 스키마 변경을 관리한다.
- Spring Profile에 따른 DataSource 설정이 Flyway의 적용 대상을 결정한다.
- 개발 DB와 테스트 DB는 동일한 PostgreSQL 서버에 있어도 별도의 마이그레이션 이력을 가진다.
- Flyway 체크섬 오류가 발생하면 실제 연결된 DB와 기존 마이그레이션 이력부터 확인해야 한다.

관련 문서: `docs/troubleshooting/flyway-checksum-mismatch.md`

### 참고 자료

- [Spring Boot — Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Docker — PostgreSQL Guide](https://docs.docker.com/guides/postgresql/)