# Hibernate ddl-auto로 인해 Flyway UNIQUE 제약조건 이름이 달라진 문제

## 문제

전체 테스트 실행 중 다음 Persistence Adapter 통합 테스트가 실패했다.

```text
UserPersistenceAdapterIntegrationTest
.savingSameEmailTwiceDirectlyTranslatesToDuplicateEmailException()
```

이 테스트는 동일한 이메일을 `UserPersistenceAdapter.save()`로 두 번 저장하여 DB UNIQUE 제약 위반이 발생했을 때 이를 `UserException(DUPLICATE_EMAIL)`으로 변환하는지 검증한다.

기대 흐름은 다음과 같았다.

```text
DB UNIQUE 위반
→ DataIntegrityViolationException
→ UserException(DUPLICATE_EMAIL)
```

하지만 실제로는 `DataIntegrityViolationException`이 그대로 노출되었다.

H2에서 발생한 실제 오류에는 다음과 같은 constraint 이름이 포함되어 있었다.

```text
Unique index or primary key violation:
"PUBLIC.CONSTRAINT_4 INDEX PUBLIC.CONSTRAINT_INDEX_4
ON PUBLIC.USERS(EMAIL NULLS FIRST) ..."

insert into users (email,password,id) values (?,?,?)
```

## 기존 예외 변환 방식

Persistence Adapter는 DB 예외 메시지에 이메일 UNIQUE 제약조건 이름이 포함되어 있는지를 확인한다.

```java
private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";
```

예외 메시지에 `uk_users_email`이 포함되어 있으면 이메일 중복으로 판단하여 `UserException(DUPLICATE_EMAIL)`으로 변환한다.

DB 예외 메시지는 constraint 이름 외에도 여러 문자열을 포함하기 때문에 이전에 완전 일치 비교가 아닌 `contains()` 방식으로 처리하도록 수정한 상태였다.

그러나 이번 오류 메시지에는 `uk_users_email` 자체가 존재하지 않고 H2가 자동 생성한 `CONSTRAINT_4`가 사용되고 있었다.

따라서 `contains("uk_users_email")`도 실패했다.

## 원인

새 개발 환경에서 `application-local.yaml`을 다시 구성하면서 다음 설정이 들어가 있었다.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
```

프로젝트에서는 DB 스키마와 제약조건을 Flyway migration으로 관리하고 있으며, migration에는 이메일 UNIQUE constraint 이름이 명시되어 있다.

```text
uk_users_email
```

하지만 `ddl-auto: create-drop`이 적용되면서 Hibernate도 엔티티 정보를 기준으로 스키마 생성에 관여하게 되었다.

그 결과 테스트 DB의 이메일 UNIQUE 제약조건이 Flyway에서 지정한 `uk_users_email`이 아니라 H2가 자동 생성한 이름으로 만들어졌다.

```text
CONSTRAINT_4
```

따라서 Persistence Adapter가 기대하는 constraint 이름과 실제 테스트 DB의 constraint 이름이 달라졌고, 중복 이메일 예외 변환이 동작하지 않았다.

## 해결

DB 스키마 생성 및 변경은 Flyway가 담당하고 Hibernate는 스키마 검증만 수행하도록 설정을 변경했다.

변경 전:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
```

변경 후:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

이후 테스트를 다시 실행하자 정상적으로 통과했다.

역할은 다음과 같이 분리된다.

```text
Flyway
→ 테이블, 인덱스, constraint 생성 및 변경

Hibernate
→ Entity와 실제 DB schema가 호환되는지 검증
```

로컬 환경은 H2 인메모리 DB를 사용한다.

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:myshelf
```

인메모리 DB는 새로운 애플리케이션 실행에서 다시 생성되므로 새 DB에 Flyway migration이 다시 적용된다.

따라서 매번 깨끗한 DB를 사용하기 위해 Hibernate의 `create-drop`에 의존할 필요도 없다.

## Persistence Adapter의 중복 예외 변환이 필요한 이유

회원가입 서비스에는 사전 중복 검사가 존재한다.

```text
existsByEmail()
→ 중복이면 가입 거부
```

하지만 사전 검사만으로 동시 요청까지 완전히 방지할 수는 없다.

예를 들어 다음 상황이 가능하다.

```text
요청 A: existsByEmail → false
요청 B: existsByEmail → false

요청 A: INSERT 성공
요청 B: INSERT → UNIQUE constraint 위반
```

따라서 DB의 UNIQUE constraint가 최종적인 무결성 방어선이다.

Persistence Adapter는 DB에서 발생한 이메일 중복 위반을 애플리케이션에서 이해할 수 있는 `DUPLICATE_EMAIL` 예외로 변환할 책임이 있다.

해당 통합 테스트는 사전 중복 검사와 별개로 이 최종 방어선과 예외 변환이 정상적으로 동작하는지를 검증한다.

## 정리

문제는 `contains()`를 이용한 constraint 판별 방식이 아니라 테스트 DB 스키마를 생성하는 주체가 달라진 것이었다.

Flyway를 DB schema의 source of truth로 사용하는 프로젝트에서 Hibernate의 `ddl-auto=create` 또는 `create-drop`을 함께 사용하면 운영과 테스트에서 서로 다른 스키마가 만들어질 수 있다.

특히 constraint 이름을 이용해 DB 예외를 해석하는 코드가 있다면 이러한 차이가 실제 애플리케이션 동작 차이로 이어질 수 있다.

따라서 이 프로젝트에서는 다음 원칙을 유지한다.

- DB 스키마 생성 및 변경은 Flyway가 담당한다.
- Hibernate는 `ddl-auto=validate`로 Entity와 DB schema의 호환 여부만 검증한다.
- Persistence 통합 테스트 역시 Flyway migration으로 생성된 schema를 기준으로 수행한다.