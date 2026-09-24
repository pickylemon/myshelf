# 003. 이메일 중복을 Application 검사와 DB 제약으로 함께 처리한다

## Status

Accepted

## Context

MyShelf의 사용자 이메일은 중복될 수 없다.

Application에서 회원가입 전에 `existsByEmail()`을 호출하면 일반적인 중복 요청은 미리 확인할 수 있다.

하지만 다음과 같이 두 요청이 동시에 들어오면 race condition이 발생할 수 있다.

```text
Request A → existsByEmail() = false
Request B → existsByEmail() = false

Request A → INSERT 성공
Request B → INSERT 시도
```

따라서 Application의 사전 검사만으로는 이메일 중복을 완전히 막을 수 없다.

## Decision

이메일 중복은 두 단계로 처리한다.

### 1. Application Service의 사전 검사

회원가입 유스케이스에서 먼저 이메일 존재 여부를 확인한다.

```text
existsByEmail(email)
→ 존재하면 DUPLICATE_EMAIL
```

이는 일반적인 중복 요청에 빠르고 명확한 응답을 제공하기 위한 검사다.

### 2. DB Unique Constraint

데이터 무결성의 최종 책임은 데이터베이스에 둔다.

Migration에서 이메일에 명시적인 Unique Constraint를 정의한다.

```sql
constraint uk_users_email unique (email)
```

Constraint 이름을 DB 자동 생성에 맡기지 않고 직접 지정한다.

## Persistence Exception Translation

동시 요청으로 인해 DB의 `uk_users_email` 제약이 위반되면
Spring Data 계층에서 `DataIntegrityViolationException`이 발생할 수 있다.

Persistence Adapter는 cause chain에서 Hibernate의
`ConstraintViolationException`을 확인하고,
constraint 이름이 `uk_users_email`인 경우에만 다음 예외로 변환한다.

```text
UserException(DUPLICATE_EMAIL)
```

다른 constraint 위반까지 `DUPLICATE_EMAIL`로 오분류하지 않는다.

구조는 다음과 같다.

```text
DB unique violation
    ↓
Spring Data / Hibernate exception
    ↓
UserPersistenceAdapter
    ↓
constraint name 확인
    ↓
UserException(DUPLICATE_EMAIL)
    ↓
ControllerAdvice
    ↓
409 Conflict
```

## Reason

`existsByEmail()`과 DB Unique Constraint는 중복된 책임이 아니다.

- Application 검사
    - 정상적인 중복 요청을 미리 처리한다.
    - 사용자에게 명확한 오류를 반환한다.

- DB Unique Constraint
    - 동시 요청을 포함한 모든 경우에 데이터 무결성을 최종 보장한다.

Persistence Adapter는 DB/JPA/Hibernate와 같은 기술 세부사항을 알고 있으므로,
기술 예외를 Application이 이해할 수 있는 예외로 번역하는 책임을 가진다.

Application Service는 `DataIntegrityViolationException`이나 Hibernate 예외를 직접 알지 않는다.

## Transaction / Flush Consideration

JPA의 실제 SQL 실행 시점은 Transaction과 Flush 경계에 영향을 받는다.

현재처럼 더 바깥쪽에 별도의 Transaction이 없다면
Spring Data Repository의 Transaction 종료 과정에서 flush와 commit이 이루어지므로
Persistence Adapter의 Repository 호출 주변에서 constraint 예외를 처리할 수 있다.

향후 Application Service 등에 더 넓은 `@Transactional` 범위를 추가하면
Repository는 해당 Transaction에 참여하게 되고,
실제 flush와 constraint 예외 발생 시점이 더 바깥쪽 Transaction 종료 시점으로 이동할 수 있다.

Transaction 경계를 변경할 경우 Persistence Adapter의 예외 변환이
원하는 위치에서 정상적으로 동작하는지 다시 확인한다.

단순히 예외를 잡기 위해 모든 저장을 `saveAndFlush()`로 변경하지 않는다.

## Consequences

- `existsByEmail()` 사전 검사는 유지한다.
- DB에는 반드시 email unique constraint를 둔다.
- Constraint 이름은 `uk_users_email`로 명시한다.
- `DataIntegrityViolationException` 전체를 무조건 이메일 중복으로 변환하지 않는다.
- Persistence 기술 예외는 Application Service까지 노출하지 않는다.
- Transaction 경계를 변경할 때 constraint 예외 처리 위치를 함께 재검토한다.