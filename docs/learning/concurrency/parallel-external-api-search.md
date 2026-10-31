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
- 단일 작업 실패 시 `join()`에서 `CompletionException` 등이 전달될 수 있다. 부분 실패를 허용할지, 전체 검색을 실패시킬지 정책이 필요하다.
- 병렬 처리로 빨라졌다고 단정하지 않는다. 응답 시간과 실패율은 별도 측정 대상이다.

## 확인할 테스트·실험

- [ ] 순차 호출과 병렬 호출의 실제 응답 시간 비교
- [ ] 필드별 응답 완료 순서가 달라도 결과 대응 관계 유지
- [ ] 외부 API 타임아웃, 실패·부분 실패 및 Executor 포화 상황
- [ ] 같은 ISBN의 중복 제거와 필드별 다음 페이지 계산

> **학습 결론:** 작업 제출 순서, 실행·완료 순서, 결과를 수집하는 순서는 서로 다른 개념이다. Future를 필드와 연결해 두면 비동기 완료 순서에 영향을 받지 않고 각 결과를 처리할 수 있다.
