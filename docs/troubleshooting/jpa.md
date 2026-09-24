# JPA Troubleshooting

## UUID를 미리 할당한 Entity의 `save()`

- 발생일: 2026-09-24
- 환경: Spring Data JPA / Hibernate / UUID v7

### 증상

UUID를 애플리케이션에서 생성해 Entity에 미리 넣은 뒤 `save()`하면
단순 INSERT만 수행할 것으로 생각했지만 추가 SELECT가 발생할 수 있다.

### 원인

`JpaRepository.save()`는 항상 INSERT를 의미하지 않는다.

Spring Data JPA의 신규 Entity 판별 결과에 따라
`persist()` 또는 `merge()`가 사용되며,
미리 ID가 할당된 Entity는 `merge()` 경로를 탈 수 있다.

### 대응

현재는 기능상 문제가 없으므로 그대로 사용한다.

추가 SELECT가 실제 성능 문제가 될 때
`Persistable` 등의 신규 Entity 판별 전략을 검토한다.

---

## `existsByEmail()`만으로 이메일 중복을 막을 수 없음

- 발생일: 2026-09-24

### 원인

동시에 두 요청이 들어오면 둘 다 `existsByEmail() = false`를 확인한 뒤
INSERT를 시도할 수 있다.

### 해결

Application의 사전 검사와 DB Unique Constraint를 함께 사용한다.

```text
existsByEmail()
→ 일반적인 중복 요청을 사전 처리

UNIQUE(email)
→ 동시 요청까지 포함한 최종 데이터 무결성 보장
```

---

## Unique Constraint 예외 식별

- 발생일: 2026-09-24

### 문제

모든 `DataIntegrityViolationException`을
`DUPLICATE_EMAIL`로 변환하면 다른 무결성 오류도 이메일 중복으로 오인할 수 있다.

### 해결

DB constraint에 `uk_users_email`이라는 이름을 명시하고,
Persistence Adapter에서 cause chain의 `ConstraintViolationException`을 찾아
constraint 이름이 일치할 때만 `DUPLICATE_EMAIL`로 변환한다.

cause chain 탐색 시에는 다음 cause로 이동해야 한다.

```java
cause = cause.getCause();
```

### 주의

향후 더 바깥쪽에 `@Transactional`을 추가하면
flush 시점이 달라져 Persistence Adapter의 `save()` 이후에
constraint 예외가 발생할 수 있다.

Transaction 경계를 변경할 때 예외 처리 위치도 함께 재검토한다.

---

## H2/Hibernate에서 Unique Constraint 이름이 길게 반환됨

- 발생일: 2026-09-24
- 환경: Spring Data JPA / Hibernate / H2

### 증상

DB에는 다음과 같이 email unique constraint 이름을 명시했다.

```sql
constraint uk_users_email unique (email)
```

하지만 실제 unique 위반 시 Hibernate의 `ConstraintViolationException#getConstraintName()`에서
다음과 같이 긴 문자열이 반환되었다.

```text
PUBLIC.UK_USERS_EMAIL INDEX PUBLIC.UK_USERS_EMAIL_INDEX_4
```

### 원인

H2에서는 다음 정보가 함께 포함된다.

```text
PUBLIC
→ H2 기본 schema

UK_USERS_EMAIL
→ 직접 지정한 unique constraint 이름

UK_USERS_EMAIL_INDEX_4
→ H2가 unique constraint를 구현하기 위해 내부적으로 생성한 index 이름
```

즉 `UK_USERS_EMAIL_INDEX_4`는 애플리케이션에서 지정한 constraint 이름이 아니다.

또한 Hibernate의 H2Dialect는 H2의 에러 메시지를 파싱해 constraint 이름을 추출하는데,
H2에서는 schema와 내부 index 정보까지 포함된 문자열이 반환될 수 있다.

따라서 다음과 같은 완전 일치 비교는 H2에서 실패한다.

```java
EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(
        constraintException.getConstraintName()
);
```

### 해결

먼저 UNIQUE 위반인지 확인한 뒤, 그 constraint 정보 안에 명시적으로 지정한 이름이
포함되어 있는지 확인한다.

```java
if (constraintException.getKind() != ConstraintViolationException.ConstraintKind.UNIQUE) {
    return false;
}

String constraintName = constraintException.getConstraintName();

return constraintName != null
        && constraintName.toUpperCase(Locale.ROOT)
        .contains(EMAIL_UNIQUE_CONSTRAINT.toUpperCase(Locale.ROOT));
```

`getKind()` 체크 없이 `contains()`만 쓰면, NOT_NULL/CHECK 등 다른 종류의 제약 위반
메시지 안에 우연히 이 이름이 섞여 있는 경우까지 DUPLICATE_EMAIL로 오판할 여지가
이론상 남는다. UNIQUE 위반인지 먼저 좁혀서 그 여지를 없앤다.

### 정리

DB constraint 이름을 직접 지정하더라도
Hibernate가 해당 이름만 깨끗한 문자열로 반환한다는 보장은 없다.

```text
DB
→ JDBC Driver
→ Hibernate Dialect
→ ConstraintViolationException
```

과정에서 DB별 예외 메시지 형식의 영향을 받을 수 있다.

현재 방식은 실제 H2를 사용하는 Integration Test로 함께 검증한다.
Hibernate/H2 버전 변경 시 해당 테스트가 실패하면 constraint 판별 로직을 다시 확인한다.