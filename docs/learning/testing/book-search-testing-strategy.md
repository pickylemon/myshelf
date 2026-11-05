# 도서 검색의 외부 의존성과 테스트 범위

## 1. 무엇을 실제로 연결할 것인가

도서 검색에는 외부 HTTP API, PostgreSQL, Redis가 함께 사용된다. 모든 테스트에서 전부 실행하면 실패 원인을 구분하기 어렵다. 반대로 전부 Mock으로 바꾸면 직렬화나 JOIN 조건 같은 연결 문제를 놓친다.

이번 테스트는 **검증하려는 경계는 실제로 실행하고, 그 밖의 의존성은 대체**하는 방식으로 구성했다. 기본적인 Mockito·MockMvc 문법은 [테스트 코드 작성 요약](test-code-cheatsheet.md)을 참고한다.

| 테스트 클래스 | 실제 검증 대상 | 대체하는 의존성 |
|---|---|---|
| `ExternalBookSearchServiceTest` | 검색 진행·페이지 구성·소유권·중복 요청 차단 | Port. 동시성 사례는 InMemory 상태 저장소 사용 |
| `BookSearchControllerTest` | 입력 검증·사용자 전달·응답 변환·보안 설정 | UseCase·JwtDecoder 등 |
| `NationalLibraryBookAdapterTest` | HTTP 요청 구성·응답 변환·오류 분류 | MockRestServiceServer와 직접 실행 Executor |
| `RedisBookSearchStateStoreTest` | 연결 장애와 예외 변환 범위 | RedisTemplate |
| `RedisBookSearchStateStoreIntegrationTest` | 실제 Redis 직렬화·저장·삭제·만료 | PostgreSQL·외부 API는 로딩하지 않음 |
| `BookPersistenceAdapterIntegrationTest` | 실제 PostgreSQL JOIN·사용자 격리 | 없음 |
| `BookSearchIntegrationTest` | JWT → 검색 진행 → Redis·PostgreSQL → HTTP 응답 | 외부 검색 Port만 Mock |

## 2. 외부 HTTP는 서버를 호출하지 않고 검증

`MockRestServiceServer`를 RestClient.Builder에 연결해 요청과 응답을 제어한다. 실제 국립중앙도서관에 요청하지 않으면서 검색 필드·페이지·API 키 파라미터와 JSON 변환을 검증한다.

외부 Adapter는 테스트에서 `Runnable::run`을 Executor로 받는다. 작업을 현재 스레드에서 실행해 결과를 일정하게 만들되, CompletableFuture의 예외 래핑 경로는 유지한다. **병렬 실행 속도나 완료 순서 경쟁을 검증하는 테스트는 아니다.**

| 입력·실패 | 확인하는 결과 |
|---|---|
| 정상 JSON·빈 목록 | 도서 변환·정상 빈 결과 |
| HTTP 401·429·500, 연결 실패 | 외부 검색 실패로 변환 |
| 시간 초과 예외 | 타임아웃 오류로 분류 |
| 빈 본문·잘못된 JSON·목록 누락 | 잘못된 외부 응답으로 분류 |
| 일부 검색 필드 실패 | 부분 결과 반환 안 함 |
| null 도서·빈 ISBN·중복 ISBN | 해당 항목 제외 |
| 누락·잘못된 날짜 | 해당 날짜만 null로 변환 |

타임아웃은 요청 시작 때뿐 아니라 응답 본문을 읽다가 발생할 수 있다. 테스트는 InputStream이 일부 JSON을 반환한 뒤 예외를 던지게 하여, 바깥 RestClientException이 달라도 원인 체인으로 분류하는지 확인한다. 실제로 5초를 기다려 네트워크 타임아웃을 일으키는 시험은 아니다.

## 3. 동시성 테스트는 겹치는 시점을 만든다

Service 테스트는 첫 요청의 등록 서재 조회를 CountDownLatch로 잠시 멈춘다.

```text
첫 요청: 상태 조회 → 도서 선택 → 등록 서재 조회에서 대기
둘째 요청: 같은 사용자·검색 ID로 호출 → 처리 중 예외
첫 요청: 대기 해제 → 상태 저장 완료
다음 요청: 갱신된 상태에서 다음 도서 반환
```

요청 두 개를 단순히 연달아 실행하는 것만으로는 동시 처리 구간을 검증할 수 없다. Latch는 첫 요청이 처리 중인 시점을 확인하고 그 구간에 둘째 요청을 넣기 위해 사용한다.

다른 검색 ID는 진행할 수 있는지, 타인 세션은 404 정책을 유지하는지, 예외가 나도 다음 요청을 계속 차단하지 않는지도 확인한다. 대기에는 시간 제한을 두고 `finally`에서 Latch와 Executor를 정리한다. 이 테스트는 여러 서버의 분산 잠금을 검증하지 않는다.

## 4. Redis 만료를 30분 기다리지 않고 검증

Redis 통합 테스트는 필요한 Redis·Jackson 설정만 로딩한다. 테스트 프로필의 DB 1을 확인하고, 생성한 검색 ID의 키만 정리한다. 전체 DB를 비우지 않는다.

TTL 검증은 다음 순서다.

1. 운영 `save()`를 호출하고 TTL이 양수이며 30분 이하인지 확인한다.
2. **테스트 키 하나만** 만료 시간을 1초로 줄인다. 운영 TTL 상수는 바꾸지 않는다.
3. Awaitility로 최대 5초 동안 만료 후 빈 결과가 되는지 확인한다.
4. 별도 테스트에서 짧아진 TTL이 재저장 후 다시 늘어나는지 확인한다.

```java
await()
    .atMost(Duration.ofSeconds(5))
    .pollInterval(Duration.ofMillis(100))
    .untilAsserted(() -> assertThat(store.findById(searchId)).isEmpty());
```

고정 시간만큼 sleep하는 대신 조건을 만족할 때까지 확인하되, 끝없이 기다리지는 않는다. 객체 복원은 모든 필드를 비교하며 LocalDate·Set·Enum 키 Map도 포함한다.

## 5. DB 격리와 전체 요청 흐름

등록 서재 조회 테스트는 같은 ISBN의 본인·타인 도서와 서재를 함께 준비한다. FK만으로 두 소유자의 일치가 보장되지는 않으므로, 일부러 소유자가 다른 연결도 넣어 **UserBook과 Shelf 양쪽 사용자 조건**이 적용되는지 확인한다.

API 통합 테스트는 실제 JWT를 발급하고 검색 → 버퍼 반환 → 추가 외부 조회 → 세션 종료까지 이어서 요청한다. 외부 검색 Port만 Mock이므로 응답 JSON뿐 아니라 Redis 상태 변화와 DB의 등록 서재 정보 연결까지 확인한다. 실제 국립중앙도서관과의 종단 간 통신 시험은 아니다.

실행 전 `myshelf_test`와 Redis DB 1인지 확인한다. 정리할 때는 생성한 Redis 키와 사용자 데이터만 제거하고, DB는 ShelfBook → UserBook·Shelf → User 순으로 삭제한다. Redis 정리가 실패해도 DB 정리는 실행되도록 `finally`를 사용한다.

## 6. 실행 조건과 남은 검증

PostgreSQL·Redis를 실행하고 Gradle에 `DB_PASSWORD`를 전달해야 한다. 외부 API 키는 test 프로필의 가짜 값을 사용하며 실제 외부 API에 보내지 않는다. Redis 통합 테스트가 `test,redis` 프로필을 직접 활성화한다.

현재 CI Workflow에는 PostgreSQL 서비스만 있으므로 Redis 실행 환경을 추가해야 한다. 테스트 코드가 존재한다는 사실과 로컬 전체 빌드·CI 통과는 구분한다.

아직 다루지 않은 범위는 실제 외부 API의 지연·호출 제한, Executor 포화, 여러 서버의 동일 세션 처리다. 현재 테스트로 병렬 성능 향상이나 분산 환경의 원자성까지 보장했다고 해석하지 않는다.

관련 문서: [도서 검색 설계](../../architecture/book-search.md), [Redis 검색 세션](../infrastructure/redis-search-session.md), [병렬 호출과 중복 요청](../concurrency/parallel-external-api-search.md).
