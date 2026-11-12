# 도서 외부 검색 설계

## 목적과 범위

국립중앙도서관 서지정보를 제목·저자·출판사별로 조회하고, ISBN 중복 제거 후 **최대 20권씩** 제공한다. 응답 직전에 사용자별 등록 서재 정보를 결합한다.

- **최초 검색:** `keyword`로 새 검색 세션 생성
- **더 보기:** `searchId`로 기존 검색 세션 이어서 진행
- 사용자 식별은 인증 정보로 처리하며, 외부 도서 API에는 전달하지 않는다.

### 현재 구현 상태

최초 검색과 더 보기의 HTTP 응답 변환을 구현했다. 두 응답 모두 `searchId`, `books`, `hasNext`를 반환하며, 각 도서에는 사용자별 등록 서재 목록을 포함한다. 연결된 서재가 없으면 빈 배열을 반환한다.

`redis` 프로필에서는 `RedisBookSearchStateStore`, 그 외에는 `InMemoryBookSearchStateStore`를 사용한다. Compose의 local·prod 설정은 `redis` 프로필을 함께 활성화한다. Redis 세션은 마지막 저장부터 30분 후 만료된다. InMemory 저장소에는 TTL이 없다.

## 데이터 흐름

```text
클라이언트 ── keyword / searchId ──> Controller
                                     │
                          ExternalBookSearchService
                                     │
                       ┌─────────────┴────────────┐
                       │                          │
              BookSearchStatePort        ExternalBookSearchPort
              (세션 상태 저장·조회)                │
                       │                 NationalLibraryBookAdapter
                 Redis / InMemory           ├─ TITLE 조회
                                           ├─ AUTHOR 조회
                                           └─ PUBLISHER 조회
                                                   │
                                 ISBN 중복 제거 + 미반환 도서 보관
                                                   │
                                   최대 20권 추출 / DB 서재 조회
                                                   │
                        searchId + books + hasNext 반환
```

## 책임 경계

| 구성 요소 | 책임 |
|---|---|
| `ExternalBookSearchService` | 최초 검색·더 보기 구분, 검색 상태 진행, 페이지 구성 |
| `ExternalBookSearchPort` | `keyword`, 필드별 페이지를 받아 외부 결과 조회 |
| `NationalLibraryBookAdapter` | 병렬 HTTP 호출, 응답 변환, **현재 조회 묶음 내** ISBN 중복 제거 |
| `BookSearchStatePort` | 검색 세션 저장·조회·삭제 |
| `BookRegistrationLookupPort` | **반환 대상 ISBN**의 사용자별 등록 서재 목록 조회 |
| `BookMetadataCachePort` | 반환한 도서의 ISBN별 메타데이터 저장. 등록 시 재사용 |

검색 필드는 Application의 `BookSearchField` Enum으로 전달한다. Adapter가 실제 HTTP 요청을 생성할 때 `TITLE → "title"` 등 국립중앙도서관 전용 파라미터로 변환한다.

## 주요 타입과 생성 위치

| 타입 | 생성하는 곳 | 주요 값 |
|---|---|---|
| `NationalLibrarySearchResponse` | HTTP 응답 역직렬화 | 외부 API 응답 원형 |
| `BookSearchMeta` | `NationalLibraryBookAdapter` | ISBN, 제목, 저자, 출판사, 출판예정일, 표지 URL |
| `ExternalBookFetchResult` | `NationalLibraryBookAdapter` | 도서 목록, `nextPageByField` |
| `BookSearchState` | `ExternalBookSearchService` | `userId`, `keyword`, `pendingBooks`, `seenIsbns`, `nextPageByField` |
| `BookShelfInfo` | Persistence Adapter | 등록된 서재 ID·이름 |
| `BookSearchItem` | `ExternalBookSearchService` | 도서 메타데이터 + 사용자별 등록 서재 목록 |
| `ExternalBookSearchResult` | `ExternalBookSearchService` | 최대 20권의 `books`, `hasNext`, `searchId` |

현재 `BookRegistrationLookupPort`는 ISBN별 `BookShelfInfo` 목록만 반환하며, 독서 상태는 조회하지 않는다. 등록 서재는 현재 연결만 뜻한다. UserBook이 없거나 연결 없이 남아 있는 경우 모두 빈 배열로 표시하고, 행 존재 여부를 별도 응답으로 구분하지 않는다.

## 최초 검색과 더 보기

### 최초 검색 `search(userId, keyword)`

1. `searchId`를 생성하고 필드별 조회 위치를 모두 1페이지로 초기화한다.
2. 각 필드의 외부 결과를 병렬 조회하고 ISBN 중복을 제거한다.
3. `pendingBooks`에서 최대 20권을 골라 등록 정보를 조회한다.
4. 미반환 도서나 다음 조회 위치가 남으면 상태를 저장하고, 모두 소진되면 삭제한다.
5. 반환 도서의 메타데이터를 별도 캐시에 저장한 뒤 `searchId`, `books`, `hasNext`를 응답한다. 캐시 저장 장애 정책은 아래와 같다.

### 더 보기 `loadMore(userId, searchId)`

1. 같은 사용자·검색 ID의 처리 중 요청이 있으면 409로 거부한다. 이후 상태를 조회하고 소유자를 확인한다. 없거나 타인 소유면 동일하게 404로 처리한다.
2. `pendingBooks`가 20권 이상이면 **외부 API를 호출하지 않는다**.
3. 부족하면 `nextPageByField`에 남은 필드만 추가 조회하고, 세션 전체 `seenIsbns`로 중복을 제거한다. 20권 확보·필드 소진·최대 2라운드 중 하나에 도달하면 반복을 멈춘다.
4. 최대 20권을 선택하고 등록 서재 목록을 조회한 뒤 상태를 저장·삭제한다. 반환 도서의 메타데이터도 캐시에 저장한다.

중복 요청 차단은 상태 조회부터 저장·삭제까지 유지하고 `finally`에서 해제한다. 단일 JVM 안에서만 동작하며, Redis를 통한 서버 간 분산 잠금은 아니다.

예를 들어 `TITLE=3`, `PUBLISHER=2`만 남았다면 저자 조회는 하지 않는다. **외부 API 페이지 번호는 클라이언트가 보내지 않고 서버가 관리**한다.

## 페이징·종료 정책

| 항목 | 정책 |
|---|---|
| 외부 API 요청 | 필드별 최대 100건 요청 (실제 허용·응답 결과 검증 필요) |
| 사용자 응답 | 최대 20권 |
| 추가 조회 상한 | 최초 검색·더 보기 각각 최대 2라운드. 한 라운드는 남은 필드들의 병렬 조회이며 HTTP 1회가 아님 |
| 중복 제거 | Adapter 현재 조회 묶음 + Service 검색 세션 전체 |
| 다음 결과 판단 | 미반환 도서 또는 미조회 외부 페이지 존재 시 `hasNext=true` |
| 최종 종료 | 미반환 도서와 다음 외부 페이지가 모두 없으면 세션 삭제 |

중복 결과나 조회 상한 때문에 **빈 목록과 `hasNext=true`가 함께 반환될 수 있다.** 더 보기 요청에서 남은 조회 위치를 이어서 사용한다. 이 정책을 선택한 근거는 [ADR의 `hasNext` 정책](../decisions/012-stateful-book-search-pagination.md#hasnext-정책)에 기록한다.

제목·저자·출판사의 병합 우선순위는 아직 고정하지 않았다. `BookSearchField.priority`와 외부 조회 크기 비교는 [후속 실험](../planning/feature-5-book-management.md#후속-구현실험--병합-우선순위와-외부-조회-크기)이며 현재 동작으로 설명하지 않는다.

## 메타데이터 캐시와 검색 세션

| 구분 | 검색 세션 | 도서 메타데이터 캐시 |
|---|---|---|
| 기준 | searchId·소유 사용자 | ISBN, 사용자 간 공유 |
| 저장 값 | 미반환 도서·중복 제거 집합·필드별 다음 페이지 | BookSearchMeta, 사용자 상태·메모 없음 |
| 실제 키 | `myshelf:book-search:{searchId}` | `myshelf:book-metadata{isbn}` (현재 코드에는 구분 콜론 없음) |
| 수명 | 마지막 저장부터 30분, 결과 소진 시 삭제 | 마지막 저장부터 30분, 검색 소진과 독립 |
| redis 프로필 외 구현 | InMemory 검색 상태, TTL 없음 | NoOp, 조회는 빈 결과·저장은 수행하지 않음 |

검색에서는 실제 반환 대상만 캐시에 저장한다. 캐시가 없거나 만료되면 등록 Service가 외부 ISBN 조회를 사용하므로 검색 세션 종료가 등록 불가를 뜻하지 않는다. 사용자별 반환 결과 스냅샷을 누적하는 구조가 아니다.

현재 순서는 검색 상태 저장·삭제 → 메타데이터 캐시 저장 → 응답이다. 캐시 저장의 연결 장애·시간 초과(`BOOK_METADATA_CACHE_UNAVAILABLE`)는 로그를 남기고 응답을 계속한다. 한 도서 저장에서 실패하면 해당 루프의 남은 도서 저장도 중단된다. 다른 오류는 전파하며, 두 Redis 키 종류의 변경을 하나의 원자적 작업으로 묶지는 않는다.

등록 중 캐시 조회의 연결 장애·시간 초과는 503이며, 정상적인 캐시 부재와 구분한다. 등록 흐름은 [도서 관리 구조](book-management.md#2-등록-외부-정보-준비와-db-쓰기를-분리)를 참고한다.

## 오류 처리

| 상황 | 오류 코드 | HTTP |
|---|---|---|
| 미인증 | `AUTHENTICATION_FAILED` | 401 |
| 없는·만료된·타인 소유 검색 세션 | `SEARCH_SESSION_NOT_FOUND` | 404 |
| 같은 사용자의 동일 검색 요청 처리 중 | `SEARCH_REQUEST_IN_PROGRESS` | 409 |
| 외부 HTTP·연결 오류 | `EXTERNAL_BOOK_SEARCH_FAILED` | 502 |
| 외부 응답 누락·변환 실패 | `INVALID_EXTERNAL_BOOK_RESPONSE` | 502 |
| 외부 호출 시간 초과 | `EXTERNAL_BOOK_SEARCH_TIMEOUT` | 504 |
| Redis 연결 장애·시간 초과 | `SEARCH_SESSION_STORE_UNAVAILABLE` | 503 |

외부 필드 조회가 하나라도 실패하면 부분 결과를 반환하지 않는다. HTTP 클라이언트의 연결 제한은 기본 2초, 응답 제한은 5초다. 전체 검색 요청의 종료 시간을 보장하는 deadline이나 이미 제출된 작업의 취소 처리는 없다.

## 검증 및 보완

계층별 테스트 구성·검증 방법·미검증 범위는 [도서 검색 테스트 전략](../learning/testing/book-search-testing-strategy.md#1-무엇을-실제로-연결할-것인가)에 정리한다. 실제 외부 API의 성능·허용량 검증 및 전체 빌드·CI 결과와는 구분한다.

관련 문서: [ADR-012](../decisions/012-stateful-book-search-pagination.md), [API 선정 근거](../planning/book-metadata-api-selection.md), [Redis 검색 세션](../learning/infrastructure/redis-search-session.md), [검색 테스트 전략](../learning/testing/book-search-testing-strategy.md).
