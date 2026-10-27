# Shelf 테스트를 통해 이해한 단위 테스트와 통합 테스트

## 1. 학습 배경

MyShelf의 `createShelf` 기능을 구현하면서 네 가지 테스트 클래스를 작성했다.

- `ShelfServiceTest`
- `ShelfControllerTest`
- `ShelfPersistenceAdapterIntegrationTest`
- `ShelfCreateIntegrationTest`

처음에는 각 테스트에서 무엇을 검증하는지, 단위 테스트와 통합 테스트를 왜 구분하는지 명확하게 이해하지 못했다.

특히 Service 단위 테스트에서 실제 DB를 사용하지 않는 이유와, 전체 API 통합 테스트에서 HTTP 응답뿐 아니라 DB 상태까지 검증하는 이유를 학습했다.

## 2. 테스트별 검증 범위

| 테스트 | 분류 | 검증 대상 |
|---|---|---|
| ShelfServiceTest | 단위 테스트 | Service의 비즈니스 로직 |
| ShelfControllerTest | 웹 계층 테스트 | 요청·응답, Validation, ControllerAdvice |
| ShelfPersistenceAdapterIntegrationTest | 영속성 통합 테스트 | Adapter, JPA, PostgreSQL |
| ShelfCreateIntegrationTest | 전체 API 통합 테스트 | 인증부터 DB 저장까지의 흐름 |

각 테스트는 검증하려는 대상과 사용하는 의존성이 다르다.

### 2.1 Service 단위 테스트

```text
ShelfService (실제 객체)
    |
    v
ShelfRepositoryPort (Mock 객체)
```

Service가 의존하는 Port를 Mock으로 대체한다.

따라서 실제 Persistence Adapter나 PostgreSQL을 사용하지 않고 Service의 로직에 집중한다.

주요 검증 내용은 다음과 같다.

- Shelf 이름의 중복 여부를 확인하는가?
- 중복된 이름이면 저장하지 않는가?
- 요청받은 정보로 Shelf를 생성하는가?
- 올바른 Shelf 객체를 Port에 전달하는가?
- Port가 반환한 저장 결과를 올바르게 처리하는가?

단건 조회에서는 `ShelfService.get(userId, shelfId)`가 Port의
`findByIdAndUserId(shelfId, userId)`를 호출하는지, 빈 결과를 `SHELF_NOT_FOUND`로 변환하는지 검증한다.
Mock 설정과 호출 검증에서도 Port의 인자 순서인 **Shelf ID → 사용자 ID**를 사용한다.

**단위 테스트는 전체 호출 체인을 검증하는 것이 아니라 특정 단위의 동작을 격리해서 검증한다.**

### 2.2 Controller 웹 계층 테스트

```text
HTTP 요청 (MockMvc)
    |
    v
ShelfController
    |
    v
CreateShelfUseCase (Mock 객체)
```

`@WebMvcTest`와 MockMvc를 이용하는 경우 Spring MVC의 요청 처리 기능을 테스트하면서 Application UseCase는 Mock으로 대체한다.

주요 검증 내용은 다음과 같다.

- HTTP 요청이 올바른 Controller 메서드에 연결되는가?
- JSON 요청이 DTO로 정상 변환되는가?
- Validation이 동작하는가?
- Controller가 UseCase에 올바른 값을 전달하는가?
- 정상 요청에 적절한 HTTP 상태 코드와 응답을 반환하는가?
- 예외를 ControllerAdvice가 올바른 HTTP 응답으로 변환하는가?

Spring MVC의 여러 구성요소가 함께 실행되므로 순수 단위 테스트보다는 웹 계층 슬라이스 테스트에 해당한다.

### 2.3 Persistence Adapter 통합 테스트

```text
ShelfPersistenceAdapter
    |
    v
JpaRepository
    |
    v
PostgreSQL
```

실제 PostgreSQL을 사용하여 영속성 처리를 검증한다.

주요 검증 내용은 다음과 같다.

- 도메인 모델이 JPA Entity로 올바르게 변환되는가?
- 실제 DB에 데이터가 저장되는가?
- UNIQUE 및 FK 제약조건이 동작하는가?
- DB 제약조건 위반이 적절한 애플리케이션 예외로 변환되는가?

단건 조회는 `ShelfPersistenceAdapter.findByIdAndUserId(shelfId, userId)`를 직접 호출한다.
자신의 Shelf는 저장된 필드와 함께 반환되고, 타인 소유이거나 존재하지 않는 Shelf는
`Optional.empty()`로 반환되는지 실제 PostgreSQL에서 검증한다.

Service 단위 테스트에서는 Mock Port를 사용하므로 검증할 수 없는 부분이다.

### 2.4 전체 API 통합 테스트

```text
MockMvc HTTP 요청
    |
    v
Spring Security (JWT 인증)
    |
    v
ShelfController
    |
    v
ShelfService
    |
    v
ShelfPersistenceAdapter
    |
    v
JpaRepository
    |
    v
PostgreSQL
```

`@SpringBootTest`와 `@AutoConfigureMockMvc`를 사용한다.

MockMvc는 실제 네트워크 서버를 띄우지 않고 Spring MVC 요청 처리 흐름을 실행하지만, Service와 Adapter, DB는 실제 구현을 사용할 수 있다.

MyShelf에서는 실제 JWT 발급 및 검증, 비즈니스 로직, DB 저장을 하나의 흐름으로 테스트한다.

**개별 계층이 올바르게 동작하는 것뿐 아니라, 계층들이 실제로 연결됐을 때도 정상 동작하는지 확인하는 것이 목적이다.**

## 3. Service 단위 테스트에서 Mock을 사용하는 이유

`ShelfServiceTest`에서는 `ShelfRepositoryPort`를 Mock으로 대체한다.

```java
when(shelfRepositoryPort.existsByUserIdAndName(userId, name))
    .thenReturn(false);
```

이 코드는 실제 DB를 조회하는 것이 아니라, Mock이 해당 메서드 호출에 `false`를 반환하도록 설정한다.

저장 동작도 Mock으로 정의할 수 있다.

```java
when(shelfRepositoryPort.save(any(Shelf.class)))
    .thenAnswer(invocation -> {
        Shelf shelf = invocation.getArgument(0);

        return new Shelf(
            savedId,
            shelf.getName(),
            shelf.getDescription(),
            shelf.getUserId()
        );
    });
```

`thenAnswer()`는 Mock 메서드에 전달된 인자를 이용하여 반환값을 만든다.

이 테스트에서는 저장 요청으로 전달된 Shelf의 정보를 유지하면서 ID를 `savedId`로 설정한 객체를 반환한다.

이를 통해 Service가 저장소에서 반환받은 ID를 사용하는지 검증한다.

### ArgumentCaptor와 InOrder

```java
ArgumentCaptor<Shelf> captor =
    ArgumentCaptor.forClass(Shelf.class);

InOrder order = inOrder(shelfRepositoryPort);

order.verify(shelfRepositoryPort)
    .existsByUserIdAndName(userId, name);

order.verify(shelfRepositoryPort)
    .save(captor.capture());
```

- `ArgumentCaptor`: Mock 메서드에 전달된 인자를 가져와 검증한다.
- `InOrder`: Mock 메서드들의 호출 순서를 검증한다.

이후 캡처한 Shelf의 이름, 설명, 소유자 UUID가 요청 정보와 일치하는지 확인한다.

여기서 중요한 점은 **실제 DB에 저장된 Shelf를 비교하는 것이 아니라, Service가 저장소에 전달한 객체를 검증한다는 것**이다.

또한 `save()`가 반환한 객체의 ID와 Service의 반환값을 비교한다.

## 4. 전체 API 통합 테스트의 검증 방식

`ShelfCreateIntegrationTest`는 HTTP 응답과 실제 DB 저장 결과를 함께 검증한다.

### 4.1 정상 요청 검증

```java
UUID shelfId = createShelf(token, name, description);
```

이 코드는 테스트 헬퍼 메서드인 `createShelf()`를 호출한다.

헬퍼 내부에서는 MockMvc로 실제 요청 처리 흐름을 실행하고 다음을 검증한다.

```java
.andExpect(status().isCreated())
.andExpect(
    content().contentTypeCompatibleWith(
        MediaType.APPLICATION_JSON
    )
)
```

즉, 이미 HTTP 201 응답과 JSON 응답 형식을 검증하고 있다.

이후 응답 본문을 UUID로 변환하고 null이 아닌지도 확인한다.

### 4.2 실제 DB 저장 결과 검증

헬퍼가 반환한 Shelf ID로 PostgreSQL을 직접 조회한다.

```java
Map<String, Object> row =
    jdbcTemplate.queryForMap(
        """
        select id, user_id, name, description
        from shelves
        where id = ?
        """,
        shelfId
    );
```

그리고 저장된 정보를 검증한다.

```java
assertThat(row.get("id")).isEqualTo(shelfId);
assertThat(row.get("user_id")).isEqualTo(userId);
assertThat(row.get("name")).isEqualTo(name);
assertThat(row.get("description")).isEqualTo(description);
```

이 테스트는 다음 사실을 함께 확인한다.

1. 정상 요청이 HTTP 201을 반환한다.
2. 응답 본문에서 Shelf ID를 받을 수 있다.
3. 해당 ID의 Shelf가 실제 DB에 존재한다.
4. JWT로 인증된 사용자 UUID가 소유자로 저장된다.
5. 요청한 이름과 설명이 정확하게 저장된다.

**HTTP 응답이 성공했다고 해서 실제 데이터까지 올바르게 저장됐다고 단정하지 않고, DB 상태를 추가로 검증한다는 점이 중요하다.**

## 5. 테스트 헬퍼 메서드를 활용한 중복 제거

`ShelfCreateIntegrationTest`에는 다음 헬퍼 메서드가 존재한다.

- `createUser()`: 테스트용 사용자 생성
- `createShelf()`: Shelf 생성 요청 및 응답 검증

여러 테스트에서 동일한 준비 작업과 검증이 반복되므로 공통 메서드로 분리했다.

예를 들어 다음 테스트들이 `createShelf()`를 재사용한다.

- 정상적인 Shelf 생성
- 동일 사용자 내 이름 중복
- 서로 다른 사용자의 동일한 Shelf 이름 허용

이렇게 하면 각 테스트가 자신의 검증 목적에 집중할 수 있다.

다만 주의할 점도 있다.

**헬퍼 메서드 안에 검증 코드가 들어가면 테스트 본문만 읽었을 때 어떤 검증이 수행되는지 놓칠 수 있다.**

따라서 헬퍼의 이름과 책임을 명확하게 하고, 필요한 경우 내부 구현까지 확인해야 한다.

모든 검증을 무조건 헬퍼로 분리하는 것이 좋은 것은 아니다.

## 6. ParameterizedTest를 활용한 반복 검증

정상 생성 테스트에서는 `@ParameterizedTest`와 `@MethodSource`를 사용한다.

```java
private static Stream<Arguments> validRequests() {
    return Stream.of(
        Arguments.of("읽고 싶은 책", "주말에 읽을 책"),
        Arguments.of("이".repeat(255), "설".repeat(255)),
        Arguments.of("설명 없는 서재", null)
    );
}
```

동일한 테스트 로직에 서로 다른 입력값을 전달한다.

이를 통해 다음 상황을 하나의 테스트 구조로 검증한다.

- 일반적인 이름과 설명
- 최대 허용 길이인 255자
- 설명이 없는 경우

공통 검증 로직을 반복해서 작성하지 않아도 된다는 장점이 있다.

## 7. 실패 요청에서는 DB 상태도 검증한다

통합 테스트에서는 HTTP 상태 코드뿐 아니라 **요청 실패 이후 DB 데이터가 올바르게 유지되는지**도 확인하는 것이 유용하다.

예를 들어 동일한 Shelf 이름으로 다시 생성하면 HTTP 409를 기대한다.

그뿐 아니라 다음 사항도 검증한다.

- 동일한 Shelf가 추가로 생성되지 않았는가?
- 기존 Shelf의 설명이 변경되지 않았는가?

실제 테스트에서는 Shelf 개수가 1개인지 확인하고 기존 description이 유지되는지 조회한다.

또한 JWT가 유효하지만 사용자가 DB에서 삭제된 경우를 검증한다.

이때 JWT 자체의 서명·유효기간 검증은 통과할 수 있지만, Shelf 소유자가 DB에 존재하지 않아 생성이 거부된다.

MyShelf에서는 이를 `INVALID_SHELF_OWNER`로 변환해 HTTP 401과 `WWW-Authenticate: Bearer`를 반환하도록 정책을 정했다.

이 경우에도 Shelf가 저장되지 않았는지 확인한다.

이처럼 **실패 응답과 데이터 정합성을 함께 검증하면 예외 처리의 실제 결과까지 확인할 수 있다.**

## 8. 통합 테스트와 @Transactional

### 8.1 테스트에 @Transactional을 붙이면?

Spring 테스트에서 `@Transactional`을 사용하면 테스트 메서드를 실행하기 전에 테스트 트랜잭션이 시작된다.

일반적으로 해당 트랜잭션은 테스트 종료 시 롤백된다.

```text
테스트 트랜잭션 시작
    |
    v
테스트 메서드 실행
    |
    v
Repository.save()
    |
    v
테스트 메서드 종료
    |
    v
테스트 트랜잭션 Rollback
```

이 방식은 테스트 데이터를 정리하는 데 편리하다.

그러나 운영 코드와 트랜잭션 경계가 달라질 수 있다는 점을 주의해야 한다.

### 8.2 현재 MyShelf의 운영 흐름

현재 Shelf 생성 Service에는 별도의 `@Transactional`이 없다.

따라서 상위 트랜잭션이 없는 상태에서 Spring Data JPA Repository의 `save()`가 호출되면 Repository 자체의 트랜잭션이 적용된다.

```text
ShelfPersistenceAdapter.save()
    |
    v
JpaRepository.save()
    |
    v
Repository 트랜잭션 시작 및 종료
    |
    v
DB 제약조건 위반 시 예외 발생 가능
    |
    v
Adapter에서 예외 변환
```

실제 DB의 UNIQUE 또는 FK 제약조건 위반이 Repository 호출 과정에서 발생하면 Adapter가 예외를 잡아 애플리케이션 예외로 변환할 수 있다.

### 8.3 테스트에 @Transactional을 붙였을 때

```text
테스트 트랜잭션 시작
    |
    v
ShelfPersistenceAdapter.save()
    |
    v
JpaRepository.save()
    |
    v
기존 테스트 트랜잭션에 참여
    |
    v
Adapter 메서드 종료
    |
    v
이후 flush 과정에서 DB 오류 발생 가능
```

Repository가 이미 존재하는 테스트 트랜잭션에 참여하면 `save()` 호출이 끝나도 트랜잭션이 종료되지 않는다.

따라서 DB 제약조건 위반이 Adapter 메서드 종료 이후에 발생할 수 있다.

이 경우 Adapter 내부의 `catch`가 해당 예외를 잡지 못해 예외 변환 검증이 의도와 다르게 동작할 수 있다.

이것은 `saveAndFlush()`가 작동하지 않는다는 의미가 아니다. 핵심은 **테스트의 트랜잭션이 실제 운영 코드의 트랜잭션 경계를 변경할 수 있다는 점**이다.

### 8.4 현재 테스트의 선택

`ShelfPersistenceAdapterIntegrationTest`에서는 운영 환경과 유사한 트랜잭션 경계를 유지하기 위해 테스트 전체에 `@Transactional`을 적용하지 않았다.

그 대신 필요한 테스트 데이터를 별도로 정리한다.

이 선택은 모든 통합 테스트에서 `@Transactional`을 사용하면 안 된다는 뜻이 아니다.

**무엇을 검증할 것인지에 따라 테스트 트랜잭션의 사용 여부를 결정해야 한다.**

향후 Service에 `@Transactional`을 추가하는 등 운영 코드의 트랜잭션 경계가 변경되면 해당 예외 변환 테스트도 다시 검토해야 한다.

## 9. 테스트 코드 작성 시 기억할 원칙

1. **테스트 대상을 명확하게 정한다.** Service 로직을 검증할지, DB 저장을 검증할지, 전체 API를 검증할지 구분한다.
2. **검증 대상 밖의 의존성은 필요에 따라 격리한다.** Service 단위 테스트에서는 Port를 Mock으로 대체할 수 있다.
3. **HTTP 응답과 DB 상태는 서로 다른 검증 대상이다.** 전체 통합 테스트에서는 두 결과를 함께 확인할 수 있다.
4. **성공뿐 아니라 실패 이후의 상태도 검증한다.** 중복 요청이나 소유권 위반 시 데이터가 변경되지 않는지 확인한다.
5. **반복되는 테스트 준비 작업은 헬퍼로 분리한다.** 단, 헬퍼에 포함된 검증이 무엇인지 명확히 이해해야 한다.
6. **@Transactional을 습관적으로 사용하지 않는다.** 테스트가 운영 코드의 트랜잭션 경계를 바꾸지 않는지 고려한다.
7. **모든 계층에 무조건 테스트 클래스를 만들 필요는 없다.** 실제로 검증할 동작과 위험을 기준으로 테스트 범위를 결정한다.

## 10. 정리

MyShelf의 Shelf 생성 테스트를 통해 다음 내용을 이해했다.

- 단위 테스트는 특정 단위의 로직을 격리하여 검증한다.
- Service 단위 테스트에서는 실제 DB 대신 Mock Port를 사용한다.
- 웹 계층 테스트는 HTTP 요청·응답 및 Validation 등을 검증한다.
- 영속성 통합 테스트는 실제 DB와 JPA의 동작을 검증한다.
- 전체 API 통합 테스트는 인증부터 실제 DB 저장까지의 연결을 검증한다.
- MockMvc로 받은 HTTP 응답과 JdbcTemplate로 조회한 DB 상태를 함께 확인할 수 있다.
- 테스트 헬퍼와 파라미터화 테스트를 활용하면 중복을 줄일 수 있다.
- 테스트의 `@Transactional` 설정이 예외 발생 시점과 검증 결과에 영향을 줄 수 있다.

**핵심은 테스트 코드를 얼마나 많이 작성했는지가 아니라, 각각의 테스트가 무엇을 검증하고 무엇을 검증하지 않는지 정확하게 이해하는 것이다.**

앞으로 Shelf 수정·조회·삭제와 Book 관리 기능을 구현할 때도 이 기준을 활용해 테스트를 설계한다.
