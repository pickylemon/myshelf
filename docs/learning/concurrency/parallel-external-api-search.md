# CompletableFuture를 이용한 외부 검색 병렬 처리

## 출발점

국립중앙도서관 API에서 제목·저자·출판사를 각각 조회한 뒤 결과를 **OR 방식으로 병합**해야 한다. 독립적인 HTTP 요청 3건을 순차적으로 실행하면 각 응답을 차례로 기다리게 된다.

선택한 방식은 **블로킹 `RestClient` 호출을 `CompletableFuture` 작업으로 제출해 병렬 실행을 시도**하는 것이다. HTTP 응답 자체를 비동기로 바꾼 것은 아니다.

## 구현 흐름

```java
Map<BookSearchField, CompletableFuture<NationalLibrarySearchResponse>> futures =
        new LinkedHashMap<>();

// 작업을 모두 먼저 제출한다.
pageByFields.forEach((field, page) ->
        futures.put(field, CompletableFuture.supplyAsync(
                () -> fetchByField(toApiSearchField(field), keyword, page),
                searchExecutor
        ))
);

// 해당 Future의 결과를 원래 검색 필드에 대응시킨다.
Map<BookSearchField, NationalLibrarySearchResponse> results = new LinkedHashMap<>();
futures.forEach((field, future) -> results.put(field, future.join()));
```

`searchExecutor`는 Spring에서 공유 관리하는 Executor다. 요청마다 새 스레드 풀을 생성하지 않는다.

## 이해한 핵심 개념

| 질문 | 정리 |
|---|---|
| 제출도 비동기인가? | 제출하는 `forEach`는 호출 스레드에서 순차 실행된다. 실행은 제출 직후 다른 스레드에서 시작될 수 있다. |
| 완료 순서가 섞이면? | `Map`의 Key는 **Future 객체**와 미리 연결된다. 각 Future는 자신의 결과를 반환하므로 필드가 뒤바뀌지 않는다. |
| `join()`의 역할은? | 해당 Future가 끝날 때까지 기다리고 결과를 가져온다. 실패하면 예외가 전파될 수 있다. |
| `allOf().join()`도 필요한가? | 모든 작업을 먼저 제출한 뒤 각 Future를 `join()`한다면 결과 수집만을 위해 중복으로 기다릴 필요는 없다. |
| 전체 HTTP 요청도 비동기인가? | 아니다. Service는 결과를 모을 때까지 대기하고 동기적으로 클라이언트에 응답한다. |

## Map의 순서와 메타데이터 대표성

`LinkedHashMap`은 삽입 순서대로 결과를 병합하도록 도와주지만, **제목 검색 결과가 저자 검색 결과보다 정확하다는 근거는 없다.** 같은 ISBN에서 어느 메타데이터를 남길지는 현재 병합 순서의 결과일 뿐, 품질 평가 정책이 아니다.

또한 `Map.copyOf()`는 순회 순서를 보장하지 않으므로 **검색 세션 전체에서 특정 필드 우선순위를 보장해야 한다면** 별도의 순서 정책이 필요하다. 현재는 이를 메타데이터 정확도 규칙으로 취급하지 않는다.

## 병렬화의 비용과 한계

- `RestClient`는 블로킹이므로 각 외부 호출은 실행하는 스레드를 점유한다.
- 외부 API가 느리거나 요청이 몰리면 Executor의 스레드·대기열이 병목이 될 수 있다.
- 스레드 수는 단순히 '필드가 3개니까 3개'로 정하기 어렵다. **요청 유입량 × 요청당 외부 호출 수 × 평균 호출 지연**과 외부 API 호출 제한을 함께 고려해야 한다.
- 한 필드라도 실패하면 부분 결과를 반환하지 않는다. 이미 제출한 다른 작업을 취소하는 처리는 없다.
- 병렬 처리로 빨라졌다고 단정하지 않는다. 응답 시간과 실패율은 별도 측정 대상이다.

## 실패를 HTTP 응답까지 전달하기

외부 Adapter는 HTTP·연결 오류, 시간 초과, 응답 변환 실패를 `BookException`으로 구분한다. `join()`에서는 이 예외가 `CompletionException`으로 감싸질 수 있어, 원인이 `BookException`이면 다시 꺼내 전달한다. 분류되지 않은 예외까지 같은 업무 오류로 바꾸지는 않는다.

응답 본문을 읽는 도중에도 시간 초과가 발생할 수 있다. 바깥 예외 타입만 보지 않고 원인 체인에서 `HttpTimeoutException`·`SocketTimeoutException`을 찾는 이유다. 이 경로를 재현하는 방법은 [외부 HTTP 테스트](../testing/book-search-testing-strategy.md#2-외부-http는-서버를-호출하지-않고-검증)를 참고한다.

개별 HTTP 호출의 타임아웃은 Executor 대기와 여러 조회 라운드를 포함한 전체 검색 요청의 시간 상한과 다르다. 현재 설정값과 HTTP 오류 응답은 [검색 아키텍처의 오류 처리](../../architecture/book-search.md#오류-처리)에서 관리한다.

## 병렬 호출과 중복 요청은 다른 문제

외부 API를 병렬로 조회하는 것과, 같은 검색 세션을 두 요청이 동시에 소비하는 것은 별개의 문제다. 후자는 같은 미반환 도서를 중복 반환하거나 갱신된 상태를 덮어쓸 수 있다.

`loadMore()`는 `(userId, searchId)`를 처리 중 집합에 먼저 추가한다. 이미 있으면 중복 요청을 거부하고, 없으면 상태 조회부터 결과 저장까지 진행한다. 성공 여부와 관계없이 `finally`에서 키를 제거해야 실패 후에도 다음 요청을 받을 수 있다.

이 차단은 **단일 JVM 범위**다. Redis가 검색 상태를 공유해도 처리 중 집합은 서버마다 따로 있으므로, 서버 간 동시 요청까지 막지는 못한다. 실제 요청 순서와 소유권 검증은 [더 보기 흐름](../../architecture/book-search.md#더-보기-loadmoreuserid-searchid)을 참고한다.

## 테스트가 확인하는 것과 남은 실험

현재 테스트는 결과·예외 처리와 동일 세션의 요청 차단을 검증한다. 실제 외부 HTTP 호출의 병렬 성능을 측정한 것은 아니다.

- Adapter 테스트의 실행 방식과 검증 범위: [외부 HTTP 테스트](../testing/book-search-testing-strategy.md#2-외부-http는-서버를-호출하지-않고-검증)
- CountDownLatch로 요청이 겹치는 시점을 만드는 방법: [동시성 테스트](../testing/book-search-testing-strategy.md#3-동시성-테스트는-겹치는-시점을-만든다)
- 실제 성능·Executor 포화 등 남은 실험: [미검증 범위](../testing/book-search-testing-strategy.md#6-실행-조건과-남은-검증)
- Redis의 상태 공유와 원자성 차이: [Redis 검색 세션](../infrastructure/redis-search-session.md#6-상태-공유가-해결하지-않는-것)

> **학습 결론:** 작업 제출 순서, 실행·완료 순서, 결과를 수집하는 순서는 서로 다른 개념이다. Future를 필드와 연결해 두면 비동기 완료 순서에 영향을 받지 않고 각 결과를 처리할 수 있다.

참고: [CompletableFuture의 예외 전달](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html).
