# PostgreSQL 테스트 DB 자동 생성 누락 문제

## 1. 문제 상황

MyShelf는 로컬 개발 환경과 통합 테스트 환경에서 PostgreSQL을 사용한다.

개발 데이터와 테스트 데이터의 간섭을 방지하기 위해 데이터베이스를 다음과 같이 분리했다.

- 개발 DB: `myshelf`
- 통합 테스트 DB: `myshelf_test`
- 통합 테스트 프로필: `test`

그러나 Docker Compose 설정에는 개발 DB인 `myshelf`만 자동 생성하도록 구성되어 있었다.

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: myshelf
      POSTGRES_USER: myshelf
      POSTGRES_PASSWORD: ${DB_PASSWORD:?Set DB_PASSWORD in .env or shell}
```

`application-test.yaml`에서는 `myshelf_test`에 연결하도록 설정되어 있지만, 새로운 PostgreSQL 데이터 볼륨에서 해당 DB를 생성하는 과정이 누락되어 있었다.

기존 개발 환경에서는 테스트 DB를 수동으로 생성하여 사용할 수 있었지만, 신규 개발 환경에서는 동일한 구성을 자동으로 재현할 수 없었다.

결과적으로 PostgreSQL을 처음 실행한 환경에서 통합 테스트를 수행하려면 테스트 DB를 별도로 생성해야 하는 문제가 있었다.

## 2. 원인 분석

### 2.1. POSTGRES_DB의 역할

PostgreSQL 공식 Docker 이미지에서 `POSTGRES_DB`는 최초 초기화 시 생성할 기본 데이터베이스를 지정한다.

```yaml
environment:
  POSTGRES_DB: myshelf
```

이 설정으로 생성되는 데이터베이스는 `myshelf`이다.

`myshelf_test`는 별도의 데이터베이스이므로 자동으로 생성되지 않는다.

기존 Compose 설정에는 추가 데이터베이스를 생성하는 SQL이나 초기화 스크립트가 없었다.

### 2.2. 데이터베이스 분리와 생성 자동화는 별개의 작업

MyShelf는 이미 개발 DB와 테스트 DB의 연결 대상을 분리했다.

그러나 애플리케이션에서 접속할 데이터베이스를 지정하는 것과 PostgreSQL 서버에 실제 데이터베이스를 생성하는 것은 별개의 작업이다.

각 구성 요소의 책임은 다음과 같다.

| 구성 요소 | 책임 |
|---|---|
| Spring Profile | 연결할 데이터베이스 선택 |
| PostgreSQL 초기화 SQL | 데이터베이스 생성 |
| Flyway | 데이터베이스 내부 스키마 및 마이그레이션 이력 관리 |
| Hibernate | 엔티티 매핑과 실제 스키마의 호환성 검증 |

따라서 테스트 DB 생성은 Spring Profile이나 Flyway 설정만으로 해결할 문제가 아니었다.

## 3. 해결 방법

PostgreSQL 공식 이미지에서 제공하는 `/docker-entrypoint-initdb.d` 초기화 메커니즘을 사용했다.

추가 데이터베이스 이름이 고정되어 있으므로 별도의 셸 스크립트나 비공식 `POSTGRES_MULTIPLE_DATABASES` 패턴은 도입하지 않았다.

### 3.1. 초기화 SQL 추가

파일 경로:

`docker/postgres/init/01-create-test-db.sql`

```sql
CREATE DATABASE myshelf_test OWNER myshelf;
```

기존 `POSTGRES_USER=myshelf` 설정에 따라 초기화 시 생성된 사용자를 테스트 DB의 소유자로 지정했다.

### 3.2. Docker Compose에 초기화 디렉터리 연결

`compose.yaml`의 PostgreSQL 서비스에 다음 마운트를 추가했다.

```yaml
volumes:
  - postgres-data:/var/lib/postgresql/data
  - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
```

두 마운트의 목적은 다르다.

- `postgres-data`: PostgreSQL 데이터 보존을 위한 Named Volume
- `./docker/postgres/init`: 호스트의 초기화 SQL 파일에 접근하기 위한 Bind Mount
- `:ro`: 컨테이너에서 초기화 파일을 읽기 전용으로 접근

PostgreSQL 공식 이미지가 최초 데이터 디렉터리 초기화 과정에서 해당 SQL을 실행하도록 구성했다.

### 3.3. 기존 볼륨에 대한 제약

`docker-entrypoint-initdb.d`에 배치한 스크립트는 PostgreSQL 데이터 디렉터리가 비어 있는 최초 초기화 시에만 실행된다.

따라서 다음 명령어를 실행하더라도 기존 볼륨이 유지된다면 SQL은 재실행되지 않는다.

```bash
docker compose down
docker compose up -d postgres
```

기존 개발 볼륨에 `myshelf_test`가 없다면 다음 명령으로 한 번 생성해야 한다.

```bash
docker compose exec postgres \
  psql -U myshelf -d myshelf \
  -c "CREATE DATABASE myshelf_test OWNER myshelf;"
```

기존 개발 데이터를 보존해야 하므로 테스트 DB 생성만을 위해 `docker compose down -v`를 실행하지 않는다.

## 4. 검증

### 4.1. 검증 환경 분리

초기화 SQL의 동작을 확인하려면 데이터가 없는 신규 PostgreSQL 볼륨이 필요하다.

하지만 기존 `postgres-data` 볼륨을 제거하면 개발 DB의 데이터도 삭제된다.

따라서 기존 개발 환경과 독립된 Docker Compose 프로젝트를 이용했다.

검증용 프로젝트 이름은 `myshelf-verify`로 지정했다.

기존 `compose.yaml`에는 컨테이너 이름과 호스트 포트가 고정되어 있으므로, 검증용 프로젝트에서 충돌하지 않도록 임시 설정 파일을 추가했다.

`compose.verify.yaml`

```yaml
services:
  postgres:
    container_name: !reset null
    ports: !reset []
```

이 파일은 기존 Compose 설정과 병합하여 사용했다.

- `container_name: !reset null`: 기존 고정 컨테이너 이름 제거
- `ports: !reset []`: 기존 호스트 포트 매핑 제거
- `-p myshelf-verify`: 별도의 프로젝트 이름으로 컨테이너, 네트워크, 볼륨 구성

기존 PostgreSQL 서비스 설정은 재사용하면서 검증 환경에서 충돌하는 항목만 제거했다.

### 4.2. 신규 PostgreSQL 실행

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  up -d --wait postgres
```

실제 실행 결과:

```text
Network myshelf-verify_default         Created
Volume myshelf-verify_postgres-data   Created
Container myshelf-verify-postgres-1    Healthy
```

별도의 네트워크, 데이터 볼륨, PostgreSQL 컨테이너가 생성되었으며 컨테이너가 Healthy 상태에 도달했다.

### 4.3. 테스트 DB 생성 확인

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  exec postgres \
  psql -U myshelf -d myshelf_test \
  -c "SELECT current_database();"
```

실제 실행 결과:

```text
current_database
----------------
myshelf_test
(1 row)
```

`myshelf_test` 데이터베이스에 정상적으로 접속할 수 있음을 확인했다.

추가로 다음 명령어로 전체 데이터베이스 목록을 조회했다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  exec postgres \
  psql -U myshelf -d myshelf -c "\l"
```

확인 결과:

| 데이터베이스 | 소유자 |
|---|---|
| myshelf | myshelf |
| myshelf_test | myshelf |

신규 볼륨에서 개발 DB와 테스트 DB가 모두 생성된 것을 확인했다.

### 4.4. 검증 환경 정리

검증 완료 후 임시 프로젝트의 리소스만 삭제했다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  down -v
```

실제 실행 결과:

```text
Container myshelf-verify-postgres-1  Removed
Volume myshelf-verify_postgres-data  Removed
Network myshelf-verify_default       Removed
```

검증용 컨테이너, 네트워크, 볼륨만 제거했으며 기존 개발용 PostgreSQL 데이터 볼륨은 유지했다.

임시 `compose.verify.yaml` 파일은 검증 후 삭제 대상으로 두고 Git에 포함하지 않았다.

## 5. 해결 결과

신규 PostgreSQL 데이터 볼륨에서 개발 DB `myshelf`와 테스트 DB `myshelf_test`가 자동 생성되는 것을 확인했다.

이번 변경으로 신규 개발 환경에서 테스트 DB를 수동으로 생성해야 했던 문제를 해결했다.

다만 검증 범위는 다음과 같이 구분한다.

**검증 완료**

- 신규 PostgreSQL 데이터 볼륨 생성
- 초기화 SQL을 통한 테스트 DB 생성
- 두 데이터베이스의 존재 및 소유자 확인
- 검증용 리소스와 기존 개발 리소스 분리
- 검증용 리소스 정리

**이번 검증에 포함하지 않은 항목**

- Spring Boot 통합 테스트 전체 실행
- 테스트 간 데이터 격리 및 정리
- Testcontainers 기반 데이터베이스 생명주기 관리

테스트 DB 자동 생성은 최초 개발 환경 구성을 재현 가능하게 만드는 작업이며, 통합 테스트 실행 환경 전체를 자동화한 것은 아니다.

## 6. 재발 방지 및 유의 사항

이번 문제에서 확인한 핵심 사항은 다음과 같다.

1. **접속 설정과 리소스 생성은 별개다.** Spring Profile에서 데이터베이스를 지정해도 해당 데이터베이스가 자동으로 생성되는 것은 아니다.
2. **컨테이너 재생성과 DB 초기화는 다르다.** PostgreSQL 초기화 스크립트는 컨테이너 생성 여부가 아니라 데이터 디렉터리의 초기화 상태에 따라 실행된다.
3. **데이터 검증을 위해 기존 볼륨을 삭제하지 않는다.** 최초 초기화 검증에는 별도의 Compose 프로젝트와 Named Volume을 사용할 수 있다.
4. **초기화 SQL과 Flyway의 책임을 분리한다.** 데이터베이스 생성은 초기화 SQL에서 처리하고, 테이블과 제약조건 등의 스키마 변경은 Flyway에서 관리한다.

## 7. 관련 문서

- `docs/decisions/009-migrate-local-db-to-postgresql.md` — PostgreSQL 전환 및 테스트 DB 분리 결정
- `docs/learning/infrastructure/docker-compose-postgresql.md` — PostgreSQL 실행, Named Volume, 최초 초기화 메커니즘
- `docs/learning/infrastructure/docker-compose-configuration-and-isolation.md` — Compose 프로젝트 격리와 설정 병합 원리

이번 문제는 데이터베이스 엔진의 오류가 아니라, **개발 환경의 설정을 코드로 관리하는 과정에서 테스트 DB 생성 절차가 누락된 구성 문제**였다.