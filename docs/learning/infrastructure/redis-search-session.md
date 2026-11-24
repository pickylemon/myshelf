# Redis에 도서 검색 세션 저장하기

Feature 5 구현 과정의 저장소 분리·직렬화·만료 학습 기록이다. 아래 코드와 30분 TTL·프로필·오류 처리는 당시 예제이며 매 변경에 맞춰 덮어쓰지 않는다. 현재 구성은 [검색 아키텍처](../../architecture/book-search.md)에서 확인한다.

## 1. 검색 결과 캐시와 다른 점

MyShelf는 외부 도서 검색 결과를 20권씩 반환한다. 외부 API에서는 더 많은 도서를 가져오므로, 다음 요청에 쓸 도서와 필드별 조회 위치를 보관해야 한다.

검색 세션에 저장하는 것은 같은 검색어의 응답을 여러 사용자에게 재사용하는 캐시가 아니다. **특정 사용자가 특정 검색을 어디까지 진행했는지** 나타내는 상태다. 다른 검색이나 다른 사용자의 세션과 섞이면 안 된다.

Feature 5에는 이와 별개로 **ISBN별 도서 메타데이터 캐시**도 추가했다. 세션은 사용자별 진행 상태라 잃으면 더 보기를 이어갈 수 없지만, 메타데이터 캐시는 외부 API에서 다시 얻을 수 있는 값이다. 그래서 세션 종료와 캐시 삭제를 연결하지 않고, 메타데이터 저장의 연결 장애는 검색 응답 실패로 만들지 않는다. 키·TTL·장애 처리는 [검색 아키텍처](../../architecture/book-search.md#메타데이터-캐시와-검색-세션)에서 관리한다.

상태 구성과 페이징 정책은 [ADR-012](../../decisions/012-stateful-book-search-pagination.md)에 정리했다. 이 문서에서는 그 상태를 Redis에 저장할 때 달라지는 부분을 다룬다.

## 2. 저장소를 바꿔도 Service가 유지되는 이유

Service는 `BookSearchStatePort`의 저장·조회·삭제만 사용한다.

```text
ExternalBookSearchService
    → BookSearchStatePort
        ├─ InMemoryBookSearchStateStore  (@Profile("!redis"))
        └─ RedisBookSearchStateStore     (@Profile("redis"))
```

`redis` 프로필을 켜면 Redis 구현체가 선택된다. Compose의 local·prod 설정은 이 프로필을 포함한다. Service에서 RedisTemplate을 직접 사용하거나 저장소 종류를 분기하지 않는다.

`BookSearchState`도 Adapter에서 `application/model`로 옮겼다. Application이 사용하는 상태 모델이 특정 저장소 패키지에 의존하지 않도록 하기 위해서다.

## 3. 키와 값의 직렬화

| 항목 | 학습 당시 설정 |
|---|---|
| 키 Serializer | `StringRedisSerializer` |
| 값 | `BookSearchState` |
| 값 Serializer | `JacksonJsonRedisSerializer<BookSearchState>` |

Redis는 Java 객체 참조를 보관하지 않는다. 저장할 때 JSON으로 변환하고, 조회할 때 다시 객체를 만든다. 따라서 같은 인스턴스인지가 아니라 **복원된 필드 값이 같은지** 확인해야 한다.

상태에는 record, `LocalDate`, List, Set, Enum 키 Map이 함께 들어 있다. `RedisConfig`는 애플리케이션의 JsonMapper를 사용하고, 통합 테스트는 이 타입들이 실제 Redis를 거쳐 복원되는지 확인한다. Mock RedisTemplate으로는 직렬화 설정을 검증할 수 없다.

## 4. TTL은 마지막 저장부터 30분

현재 저장소 구성은 [검색 아키텍처의 저장소 비교](../../architecture/book-search.md#메타데이터-캐시와-검색-세션)에서 확인한다. 아래 코드는 학습 당시 TTL이 저장과 함께 설정되는 방식을 설명하는 발췌다.

```java
redisTemplate.opsForValue().set(KEY_PREFIX + searchId, state, TTL);
```

`save()`는 상태와 30분의 만료 시간을 함께 설정한다. 더 보기 처리 후 같은 키에 저장하면 그 시점부터 다시 30분이다. `findById()`는 단순 조회이므로 TTL을 연장하지 않는다.

운영 TTL의 숫자보다 중요한 점은 **재저장은 만료 시간을 다시 설정하지만 단순 조회는 연장하지 않는다**는 것이다. 저장소의 만료·종료 정책은 위 비교와 [검색 흐름](../../architecture/book-search.md#최초-검색과-더-보기)을 따른다. Port가 같더라도 InMemory 구현까지 같은 수명 관리를 보장하는 것은 아니다.

## 5. 세션 부재와 저장소 장애 구분

키가 없는 상황과 Redis에 접근할 수 없는 상황은 다르다.

- 키 없음: `Optional.empty()` → `SEARCH_SESSION_NOT_FOUND` → 404
- 연결 장애·시간 초과: `SEARCH_SESSION_STORE_UNAVAILABLE` → 503
- 역직렬화 오류: 현재 위 장애 코드로 변환하지 않고 원래 예외를 전파

연결 장애를 빈 결과로 바꾸면 클라이언트는 정상 세션이 사라졌다고 오해하게 된다. Adapter는 연결 실패와 QueryTimeout만 변환하고 원인 예외를 유지한다.

## 6. 상태 공유가 해결하지 않는 것

Redis를 사용하면 서버들이 같은 검색 상태에 접근할 수 있다. 하지만 **조회 → 도서 소비 → 상태 저장** 전체가 하나의 원자적 작업이 되는 것은 아니다.

현재 Service의 처리 중 집합은 단일 JVM에서만 같은 요청의 동시 실행을 막는다. 서버 간 동시 요청 제어는 미구현이다. [병렬 처리와 중복 요청의 차이](../concurrency/parallel-external-api-search.md)를 참고한다.

Redis DB 0은 애플리케이션, DB 1은 테스트에 사용한다. 논리 DB 분리는 별도 Redis 서버나 자원 격리를 뜻하지 않는다. 현재 Compose에는 Redis용 Named Volume도 없어 컨테이너 재생성 후 세션 복구를 보장하지 않는다.

저장·복원·TTL의 테스트 방법은 [도서 검색 테스트 전략](../testing/book-search-testing-strategy.md)에 정리했다.

참고: [Spring Data RedisTemplate](https://docs.spring.io/spring-data/redis/reference/redis/template.html), [Redis 만료 시간](https://redis.io/docs/latest/commands/expire/).
