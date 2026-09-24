# 001. UUID v7을 식별자로 사용한다

## Status

Accepted

## Context

MyShelf의 주요 도메인 객체는 데이터베이스의 auto increment 값에 의존하지 않고,
애플리케이션에서 식별자를 생성할 수 있도록 한다.

랜덤 기반 UUID v4도 사용할 수 있지만,
Primary Key 인덱스의 삽입 특성을 고려해 시간 순서에 가까운 UUID v7을 사용하기로 했다.

## Decision

도메인 객체의 ID는 `UUID` 타입을 사용한다.

UUID는 데이터베이스가 생성하지 않고 애플리케이션에서 먼저 생성한다.

현재 구현에서는 공통 `UuidGenerator`를 통해 UUID v7을 생성한다.

```text
Application Service
    ↓
UuidGenerator.generate()
    ↓
Domain 객체 생성
    ↓
Persistence
```

Domain 객체와 JPA Entity 모두 동일하게 `UUID` 타입의 ID를 사용한다.

## Reason

애플리케이션에서 ID를 먼저 생성하면 DB 저장 전에 도메인 객체의 식별자를 확정할 수 있다.

또한 여러 애플리케이션 인스턴스가 존재하더라도 중앙의 sequence나 auto increment 값에 의존하지 않고 식별자를 생성할 수 있다.

UUID v7은 랜덤 기반 UUID v4보다 시간 순서에 가까운 값을 생성하므로,
Primary Key 인덱스에서 완전히 무작위인 UUID보다 유리한 삽입 특성을 기대할 수 있다.

## Trade-off

JPA Entity에 UUID를 미리 할당하면 `JpaRepository.save()`의 신규 엔티티 판별 과정에서
`persist()`가 아닌 `merge()` 경로를 선택할 수 있다.

이 경우 저장 전에 ID 기준 `SELECT`가 추가로 발생할 수 있다.

현재는 기능 오류나 데이터 무결성 문제가 아니며,
이를 피하기 위해 `Persistable`과 JPA lifecycle 상태까지 추가하는 것은 현재 규모에서는 과도하다고 판단한다.

실제 성능 문제가 확인되면 신규 Entity 판별 전략을 다시 검토한다.

## Consequences

- Domain과 Persistence의 ID 타입을 `UUID`로 통일한다.
- ID 생성은 현재 Application Service에서 수행한다.
- JPA의 자동 ID 생성(`@GeneratedValue`)은 사용하지 않는다.
- `Long`과 `UUID`를 혼용하지 않는다.
- UUID 선할당으로 인한 추가 조회 가능성은 현재 허용한다.