# MyShelf 테스트 코드 작성 요약

현재 Auth·User·Shelf 테스트에서 사용하는 핵심 문법을 정리한다. 예제는 기존 테스트의 일부를 줄인 것이며, 변수·주입 필드·import는 해당 테스트 문맥을 전제로 한다.

## 1. 문법보다 먼저 결정할 것

테스트 하나마다 **준비할 상황 → 실행할 동작 → 증명할 결과**를 한 문장으로 정한다.

> 같은 사용자의 Shelf 이름이 중복이면 수정 예외가 발생하고, 기존 DB 값은 유지된다.

| 검증 목적 | 테스트 위치 | 실제로 사용하는 것 |
|---|---|---|
| 비즈니스 분기·호출 순서 | Service 단위 테스트 | Service + Mock Port |
| 요청 Validation·HTTP·예외 응답 | Controller 웹 테스트 | Spring MVC·보안 설정 + Mock UseCase |
| UNIQUE/FK·롤백·Entity 변환 | Adapter 통합 테스트 | Adapter + JPA + PostgreSQL |
| 인증부터 DB 변경까지 연결 | API 통합 테스트 | 실제 JWT·Service·Adapter·PostgreSQL |

Mock이 중복이라고 반환하게 설정한 테스트는 **실제 DB 중복 판정**을 증명하지 않는다.

## 2. 어노테이션과 반복 실행

| 문법 | 의미 |
|---|---|
| `@Test` | 일반 테스트 메서드 |
| `@BeforeEach` / `@AfterEach` | 각 테스트 실행 전 준비 / 실행 후 정리 |
| `@ExtendWith(MockitoExtension.class)` | JUnit에서 Mockito의 `@Mock` 등을 처리 |
| `@Mock` | Spring 없이 사용할 Mock 생성 | ** @Mock과 @MockitoBean의 차이
| `@MockitoBean` | Spring 테스트 컨텍스트에 Mock Bean 등록·교체 |
| `@WebMvcTest(ShelfController.class)` | 웹 계층 중심으로 컨텍스트 구성 |
| `@SpringBootTest` | 애플리케이션 컨텍스트를 구성하는 통합 테스트 |
| `@AutoConfigureMockMvc` | MockMvc 자동 구성. 프로젝트에서는 API 통합 테스트에 사용 |
| `@ActiveProfiles("test")` | test 프로필 적용. 테스트 DB 비밀번호 등 환경변수는 별도 필요 |
| `@Import(...)` / `@Autowired` | 필요한 설정 추가 / Spring Bean 주입 |

웹 테스트에서는 Controller 생성자에 필요한 UseCase들을 Mock Bean으로 등록해야 한다.

```java
@ParameterizedTest
@NullAndEmptySource
@ValueSource(strings = "주말에 읽을 책")
void 설명을_저장한다(String description) { /* ... */ }
```

`null`, `""`, `"주말에 읽을 책"`으로 **총 3회** 실행한다. 각 실행마다 준비·정리 메서드도 실행된다.

- `@ValueSource`: 문자열·boolean 등 한 종류의 입력값을 제공한다.
- `@MethodSource("validRequests")`: 메서드가 반환하는 테스트 데이터로 실행한다.
- `Stream.of(Arguments.of(name, description), ...)`: 여러 인자를 한 묶음씩 제공한다.

## 3. 기본 작성 형태: given → when → then

`ShelfServiceTest`의 삭제 실패 테스트처럼, 먼저 가장 작은 분기를 작성한다.

```java
@Test
void 삭제_대상이_없으면_예외가_발생한다() {
  // given: 의존성이 어떤 결과를 반환할지 준비
  UUID shelfId = UUID.randomUUID();
  UUID userId = UUID.randomUUID();
  when(shelfRepositoryPort.delete(shelfId, userId)).thenReturn(false);

  // when, then: 실행 중 발생한 예외와 업무 오류 코드 검증
  assertThatThrownBy(() -> shelfService.delete(shelfId, userId))
      .isInstanceOfSatisfying(
          ShelfException.class,
          ex -> assertThat(ex.getErrorCode()).isEqualTo(ShelfErrorCode.SHELF_NOT_FOUND));

  verify(shelfRepositoryPort).delete(shelfId, userId);
}
```

`when(...).thenReturn(...)`은 **준비**, 실제 Service 호출은 **실행**, `assertThat`·`verify`는 **검증**이다.

## 4. Mockito: 결과 설정과 호출 확인

| 문법 | 용도 |
|---|---|
| `when(port.method(...)).thenReturn(value)` | 호출 시 반환할 값 설정 |
| `.thenThrow(exception)` | 호출 시 예외 발생 설정 |
| `.thenAnswer(invocation -> { ... })` | 실제 전달된 인자를 사용해 반환값 계산 |
| `any(Shelf.class)` | Shelf 타입 인자를 매칭. 필드 값까지 확인하지는 않음 |
| `verify(port).method(...)` | 해당 인자로 정확히 한 번 호출됐는지 확인 |
| `verify(port, never()).update(any())` | 해당 수정 호출이 없었는지 확인 |
| `verifyNoInteractions(useCase)` | 그 Mock의 메서드가 하나도 호출되지 않았는지 확인 |
| `inOrder(port)` | 여러 호출의 순서를 검증할 도구 생성 |

전달한 객체의 내부 값이 중요하면 `ArgumentCaptor`로 꺼낸다.

```java
ArgumentCaptor<Shelf> captor = ArgumentCaptor.forClass(Shelf.class);
verify(shelfRepositoryPort).update(captor.capture());
assertThat(captor.getValue().getName()).isEqualTo("새 이름");
```

`InOrder order = inOrder(port)` 후 `order.verify(port)...`를 실행 순서대로 나열한다.
`same(object)`는 같은 인스턴스를 인자로 전달했는지 확인하는 Mockito 매처다.

## 5. AssertJ: 무엇을 비교하는가

| 문법 | 검증 내용 |
|---|---|
| `isEqualTo(expected)` | 값의 동등성. 일반 객체는 equals 구현에 유의 |
| `isSameAs(expected)` | 정확히 같은 객체 인스턴스 |
| `isNull()` / `isNotNull()` | null 여부 |
| `isEmpty()` / `isZero()` | 빈 결과 / 숫자 0 |
| `usingRecursiveComparison().isEqualTo(expected)` | 객체 필드를 재귀적으로 비교 |
| `hasValueSatisfying(value -> ...)` | Optional에 값이 있고, 그 값이 내부 검증을 만족 |
| `filteredOn(...).singleElement()` | 조건에 맞는 원소가 정확히 하나인지 확인하고 검증 |
| `containsExactlyInAnyOrderElementsOf(before)` | 순서만 무시하고 원소와 개수가 정확히 같은지 비교 |

`assertThatThrownBy(() -> 실행)`은 실행을 람다로 넘겨 발생한 예외를 검증한다.
`isInstanceOfSatisfying(예외타입.class, ex -> ...)`은 예외 타입과 내부 오류 코드를 함께 확인한다.

## 6. MockMvc: HTTP 요청과 응답 검증

```java
mockMvc.perform(
        put("/shelves/{shelfId}", shelfId)
            .with(jwt().jwt(token -> token.subject(userId.toString())))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new UpdateRequestBody("새 이름", null))))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.name").value("새 이름"));
```

- `get/post/put/delete`: 요청 메서드와 경로를 만든다. `contentType`은 요청 본문의 형식이다.
- `writeValueAsString`: Java 객체 → JSON. `readValue`·`readTree`는 응답 JSON을 읽을 때 사용한다.
- `status()` / `header()` / `content()`: 응답 상태 / 헤더 / 본문·응답 Content-Type 검증.
- `jsonPath("$.name")`: JSON 최상위 객체의 name. `$[0].id`는 배열 첫 객체의 id.
- `hasJsonPath()`는 null이어도 경로 존재를 확인한다. `nullValue()`·`is(...)` 등은 Hamcrest 매처다.
- `andExpectAll(...)`: 여러 응답 검증을 묶는다. `andReturn().getResponse().getContentAsString()`은 본문을 꺼낸다.
- `.with(jwt())`는 인증 정보를 직접 넣는다. **실제 JWT 서명 검증은 하지 않는다.** 실제 인증 연결은 발급한 토큰을 Authorization 헤더로 보내는 통합 테스트에서 확인한다.

## 7. PostgreSQL: 응답 외에 DB 결과도 확인

| JdbcTemplate 메서드 | 반환 형태 |
|---|---|
| `queryForObject(sql, Long.class, id)` | count 등 단일 값 |
| `queryForMap(sql, id)` | 한 행의 `Map<컬럼명, 값>` |
| `queryForList(sql, userId)` | 여러 행의 `List<Map<String, Object>>` |
| `update(sql, id)` | INSERT·UPDATE·DELETE 실행, 영향받은 행 수 반환 |

SQL의 `?`는 뒤에 전달한 인자로 바인딩한다. 문자열을 이어 붙여 SQL을 만들지 않는다.

```java
List<Map<String, Object>> before =
    jdbcTemplate.queryForList("select * from shelves where user_id = ?", userId);
// 중복 수정 실행 + 예외 검증
assertThat(jdbcTemplate.queryForList("select * from shelves where user_id = ?", userId))
    .containsExactlyInAnyOrderElementsOf(before);
```

행 수뿐 아니라 **모든 컬럼 값과 중복 개수까지 같아야** 한다. ORDER BY가 없으므로 순서는 무시한다.
예외만 확인하지 않고, 실패한 이름·설명 변경이 저장되지 않았는지도 증명하는 패턴이다.

이 프로젝트의 Shelf DB 테스트는 운영의 commit/flush 경계를 검증하기 위해 테스트 전체에 `@Transactional`을 걸지 않고, `@AfterEach`에서 생성한 데이터만 자식 → 부모 순서로 정리한다. 다른 테스트에서 `@Transactional`을 쓰면 안 된다는 뜻은 아니다.

## 8. 직접 작성할 때의 연습 순서

1. **삭제 Service 테스트**: 반환값 설정 → 실제 호출 → 예외·호출 인자 검증.
2. **수정 Service 테스트**: 위 패턴에 Captor와 필요한 호출 순서 검증 추가.
3. **웹 테스트**: 정상 요청 하나 → 잘못된 입력 → 업무 예외의 HTTP 응답 변환.
4. **DB 테스트**: 알려진 데이터 준비 → 실제 Adapter 실행 → DB 값·실패 시 원본 유지 확인.

`UUID.randomUUID()`는 식별자 분리, `"이".repeat(255)`는 경계값, `Clock.fixed(...)`는 시간 고정에 사용한다.
`record`는 요청 데이터 묶음, `() -> ...`는 나중에 실행할 동작, `ex -> ...`는 받은 값을 처리하는 람다다.
`import static` 덕분에 `Assertions.assertThat(...)` 대신 `assertThat(...)`처럼 호출할 수 있다.

테스트를 쓴 뒤 **“운영 코드가 잘못되어도 이 테스트가 통과할 수 있을까?”**를 확인한다. Mock 반환값만 되돌려 확인하고 있지는 않은지, DB 제약조건을 실제 DB에서 검증했는지 점검한다.

실제 예제: [Service](../../../src/test/java/com/myshelf/shelf/application/service/ShelfServiceTest.java) · [웹](../../../src/test/java/com/myshelf/shelf/adapter/in/web/ShelfControllerTest.java) · [DB](../../../src/test/java/com/myshelf/shelf/adapter/out/persistence/ShelfPersistenceAdapterIntegrationTest.java) · [API](../../../src/test/java/com/myshelf/shelf/ShelfUpdateDeleteIntegrationTest.java)

설계 원리는 [테스트 계층별 책임](shelf-testing-strategy.md), [픽스처와 통합 테스트 설계](integration-test-design-and-fixtures.md)를 참고한다.
