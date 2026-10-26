# Flyway Migration Checksum Mismatch

## 1. 문제 상황

`V3__limit_shelf_description_length.sql` 작성 후 Spring Boot 실행 시 Flyway 체크섬 검증 오류가 발생했다.

```text
Migration checksum mismatch for migration version 3
-> Applied to database : 0
-> Resolved locally    : -571708480
```

확인 결과:
- `flyway_schema_history`에 V3가 `checksum = 0`, `success = true`로 기록됨
- 실제 DB의 `shelves.description`은 여전히 `text` 타입
- V3의 `ALTER TABLE` SQL은 실행되지 않음

## 2. 원인 및 재현

**미완성 SQL이 Flyway에 먼저 적용된 후 파일 내용이 변경된 것이 원인으로 판단된다.**

Spring Boot DevTools는 클래스패스 변경을 감지하면 애플리케이션을 자동 재시작하며, 이 과정에서 Flyway 마이그레이션이 다시 실행된다.

이를 검증하기 위해 실험용 `V4__checksum_experiment.sql`을 생성했다.

| 단계 | 결과 |
|---|---|
| 빈 V4 파일 생성 | DevTools가 클래스패스 변경 감지 |
| DevTools 자동 재시작 | Flyway가 빈 V4 적용 |
| DB 이력 확인 | `checksum = 0`, `success = true` |
| V4에 `SELECT 1;` 작성 | DevTools 자동 재시작 |
| Flyway 재검증 | 체크섬 불일치 발생 |

```text
Migration checksum mismatch for migration version 4
-> Applied to database : 0
-> Resolved locally    : 78787420
```

**실험 결론:** 수동 재시작 없이도 DevTools에 의해 미완성 마이그레이션이 실행될 수 있음을 확인했다.

V3와 동일한 현상을 재현했지만, 과거 V3가 실제로 적용된 경로까지 직접 확인한 것은 아니다.

## 3. 해결 방법

데이터 보존이 필요 없는 로컬 개발 DB였으므로 볼륨을 초기화하여 해결했다.

1. Spring Boot 애플리케이션 종료
2. V3 SQL 내용 확인 및 실험용 V4 파일 제거
3. Docker Compose 볼륨 초기화 및 PostgreSQL 재실행

```bash
docker compose down -v
docker compose up -d
```

4. 애플리케이션 재실행 후 Flyway 이력 검증

```sql
SELECT version, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

V1~V3의 정상 적용 및 `shelves.description`의 `varchar(255)` 변경을 확인한다.

> `down -v`는 Compose 프로젝트의 볼륨 데이터를 삭제하므로 데이터 보존이 필요 없는 개발 환경에서만 사용한다.

**운영 환경에서는** 기존 마이그레이션을 임의로 변경하거나 DB를 초기화하지 않는다. 기존 SQL을 원래 상태로 복원하고 필요한 스키마 변경은 새로운 마이그레이션으로 작성한다.

`flyway repair`는 적용 이력을 수정하는 기능이며, 누락된 스키마 변경 SQL을 실행해 주지 않는다.

## 4. 재발 방지

- **SQL 작성 완료 후 배치:** 미완성 마이그레이션 파일을 `db/migration` 경로에 두지 않는다.
- **DevTools 재시작 제어:** 필요하면 로컬 환경에서 자동 재시작을 비활성화한다.

```yaml
spring:
  devtools:
    restart:
      enabled: false
```

- **적용 이력 불변:** 이미 적용된 버전의 SQL을 수정하지 않고 새 버전으로 변경한다.
- **적용 결과 검증:** Flyway 이력뿐 아니라 실제 DB 스키마도 확인한다.

## 핵심 교훈

Flyway는 빈 SQL 파일도 성공한 마이그레이션으로 기록할 수 있다.

**이번 문제의 직접적인 트리거는 DevTools 자동 재시작이었다.** 따라서 애플리케이션 종료 자체가 필수 규칙은 아니며, 미완성 SQL이 의도치 않게 실행되지 않도록 마이그레이션 적용 시점을 관리하는 것이 중요하다.