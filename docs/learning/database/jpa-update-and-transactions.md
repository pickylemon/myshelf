# Shelf UPDATE로 이해한 JPA와 트랜잭션

Shelf 수정과 Feature 5 등록을 예제로 트랜잭션·변경 감지를 학습한 기록이다. 아래 호출 순서와 경계는 기록 당시 구현을 설명하며, 이후 변경마다 예제를 갱신하지 않는다. 현재 도서 처리 구조는 [도서 관리 아키텍처](../../architecture/book-management.md), 실제 Shelf 경계는 [ShelfService](../../../src/main/java/com/myshelf/shelf/application/service/ShelfService.java)에서 확인한다.

## 1. 실제 수정 흐름

`ShelfService.update()`는 소유권 조회 → 자기 자신을 제외한 이름 중복 검사 → `ShelfRepositoryPort.update()` 순으로 진행한다. 입력 Validation은 웹 DTO에서 수행한다.

`ShelfPersistenceAdapter.update()`는 다음 작업을 하나의 `@Transactional` 안에서 수행한다.

```java
// found는 같은 트랜잭션의 findByIdAndUserId() 조회 결과다.
ShelfEntity entity = found.get();
entity.update(shelf.name(), shelf.description());
shelfJpaRepository.flush();
return Optional.of(ShelfEntity.toDomain(entity));
```

조회한 Entity는 관리 상태다. Hibernate가 필드 변경을 감지하고 SQL에 반영하는 **Dirty Checking**을 사용하므로 별도 `save()` 호출은 필요 없다. 수정은 name·description만 변경하고 ID·소유자·생성 시각은 유지한다.

## 2. 왜 명시적으로 flush하는가

`flush()`는 영속성 컨텍스트의 변경을 SQL로 DB에 반영한다. **커밋은 아니며**, 이후 예외로 트랜잭션이 롤백되면 변경도 취소된다.

현재 구현에는 두 가지 목적이 있다.

1. **예외 발생 시점:** `uk_shelves_name_per_user` 위반을 Adapter의 try-catch 안에서 감지해 `DUPLICATE_NAME`으로 변환한다. V2의 UNIQUE는 지연 검사로 선언되지 않았다.
2. **반환 시각:** Entity에 `@UpdateTimestamp`가 반영된 뒤 Domain으로 복사해 최신 `updatedAt`을 반환한다.

명시적 flush를 제거하면 SQL 실행이 커밋까지 미뤄질 수 있다. 이 경우 메서드 본문 밖에서 발생한 예외는 내부 catch로 잡을 수 없고, flush 전에 만들어 둔 Domain에는 이전 시각이 남을 수 있다.

## 3. 감사 시각과 응답

- `createdAt`: `@CreationTimestamp`와 `updatable = false`로 생성 시각을 유지한다.
- `updatedAt`: `@UpdateTimestamp`로 실제 Entity UPDATE 시 갱신한다. 현재 기본값은 DB 트리거가 아닌 JVM 시각 생성이다.
- V4의 `DEFAULT CURRENT_TIMESTAMP`는 INSERT 기본값이며 UPDATE마다 자동 실행되는 설정이 아니다.
- 이름·설명이 모두 동일하면 변경 감지 결과 UPDATE가 생략될 수 있다. 모든 PUT에서 시각이 반드시 증가한다는 정책은 아니다.

`Shelf`는 Entity와 분리된 객체다. Entity가 나중에 변경되어도 이미 만든 Domain의 필드가 자동으로 갱신되지는 않으므로 변환 순서가 중요하다.

## 4. 사전 검사와 최종 정합성

Service 사전 검사는 소유권·부재를 먼저 판정하고, 이름 중복을 빠르게 알리기 위한 것이다. 검사 후 다른 요청이 같은 이름을 사용할 수 있으므로 동시 요청의 중복 저장은 DB UNIQUE가 최종적으로 막는다.

Adapter는 `DataIntegrityViolationException`의 원인을 따라 Hibernate 제약조건 종류와 이름을 확인한다. **이름 UNIQUE 위반만** `DUPLICATE_NAME`으로 변환하며 다른 DB 오류까지 중복으로 분류하지 않는다. 이 런타임 예외가 트랜잭션 밖으로 전달되면 롤백된다.

UPDATE는 userId를 변경하지 않으므로 사용자 FK 위반의 별도 변환을 두지 않는다. 생성의 사용자 FK 예외 처리와 혼동하지 않는다.

## 5. 현재 트랜잭션 경계를 선택한 이유

Service의 사전 조회는 검증 순서를 위한 것이고, Adapter 재조회는 **쓰기 트랜잭션 안에서 관리 상태 Entity를 확보**하기 위한 것이다. 두 조회를 포함한 Service 전체가 하나의 트랜잭션인 것은 아니다.

현재 Entity 조회·변경·flush를 Adapter의 하나의 트랜잭션으로 묶고, 동일 사용자 내 Shelf 이름 중복은 DB UNIQUE 제약조건으로 방지한다. 다만 동시 수정으로 인한 변경사항 덮어쓰기까지 방지하는 것은 아니다.

단순히 Service 계층이라는 이유로 트랜잭션을 이동할 필요는 없다. 향후 여러 저장 작업이 하나의 업무로 묶이면 경계를 다시 검토한다.

DELETE도 Adapter 안에서 소유자 조건 조회와 삭제를 묶는다. 반환 시각이나 내부 예외 변환이 없어 현재는 명시적으로 flush하지 않는다. 메서드 본문이 true를 반환해도 커밋 실패 시 호출자에게 예외가 전달되므로 204 응답이 성공한 것으로 처리되지는 않는다.

조회 전에 대상이 사라지면 부재로 처리하지만, Entity 조회 이후 다른 트랜잭션에서 삭제되면 동시성 예외가 생길 수 있다. 이를 자동으로 404로 바꾸는 처리는 없다. 동시 수정의 변경 충돌을 검출하는 `@Version`도 현재는 없다.

## 6. 검증한 범위와 관련 문서

`ShelfPersistenceAdapterIntegrationTest`는 실제 PostgreSQL에서 정상 수정, 자기 이름 유지·설명 null, 생성 시각 유지·수정 시각 증가, 반환값과 DB 일치, UNIQUE 실패 시 원본 보존을 확인한다. 이는 동시 요청 경쟁을 재현한 테스트는 아니다.

기존 문서와 역할을 나눈다.

- [테스트 전략](../testing/shelf-testing-strategy.md): 테스트 전체에 트랜잭션을 걸지 않은 이유와 계층별 검증 범위
- [Flyway와 Hibernate](flyway-and-hibernate.md): 스키마 변경·검증 책임
- [JPA 문제 해결](../../troubleshooting/jpa.md): 제약조건 예외 식별과 과거 H2 환경 문제

## 7. Feature 5의 등록과 관리 트랜잭션

Shelf CRUD의 Adapter 트랜잭션을 모든 기능에 그대로 적용하지는 않았다. 도서 등록은 여러 UserBook·ShelfBook 쓰기가 하나의 업무이므로 `BookRegistrationWriter.register()`를 하나의 트랜잭션으로 묶는다. 외부 API·Redis에서 신규 도서정보를 준비하는 BookService 단계는 그 밖에 둔다. [등록 구조](../../architecture/book-management.md#2-등록-외부-정보-준비와-db-쓰기를-분리)를 참고한다.

이 경계에서는 Adapter의 `save()`가 반환되어도 INSERT가 아직 실행되지 않았을 수 있다. JPA가 변경을 모아 반영하는 **쓰기 지연** 때문에 Writer의 commit 시점에 UNIQUE 위반이 발생하면 Adapter의 catch는 이미 끝난 뒤다.

따라서 UserBook·ShelfBook 생성은 `saveAndFlush()`를 사용해 Adapter 안에서 제약 위반을 확인하고 지정한 UNIQUE 오류만 `DUPLICATE_REGISTRATION`으로 변환한다. flush는 현재 영속성 컨텍스트의 다른 대기 변경도 실행할 수 있으며, commit이 아니므로 뒤 항목의 실패 시 앞 항목도 롤백 대상이다. 실제 전체 롤백의 검증 방법은 [등록 롤백 테스트 전략](../testing/book-search-testing-strategy.md#51-등록-트랜잭션의-롤백-검증)을 참고한다.

메모·독서 상태 변경·다건 삭제는 외부 호출이 없어 BookService의 각 메서드에 트랜잭션을 둔다. 관리 Entity의 필드 변경과 명시적 `deleteAll()`을 사용한다. `ShelfBook.updatedAt`은 `@UpdateTimestamp`로 갱신되며, DB의 `DEFAULT NOW()`는 INSERT 기본값일 뿐 UPDATE 자동 갱신 설정이 아니다.

참고: [Spring Data JPA 트랜잭션](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html), [Hibernate Session과 Dirty Checking](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/Session.html), [UpdateTimestamp](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/UpdateTimestamp.html).
