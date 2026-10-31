# 도서 외부 검색 설계

## 목적과 범위

국립중앙도서관 서지정보를 제목·저자·출판사별로 조회하고, ISBN 중복 제거 후 **최대 20권씩** 제공한다. 응답 직전에 사용자별 등록 서재 정보를 결합한다.

- **최초 검색:** `keyword`로 새 검색 세션 생성
- **더 보기:** `searchId`로 기존 검색 세션 이어서 진행
- 사용자 식별은 인증 정보로 처리하며, 외부 도서 API에는 전달하지 않는다.

### 현재 구현 상태

Service의 검색 상태 진행과 외부 조회·DB 서재 조회는 연결되어 있다. 다만 `ExternalBookSearchResponse.from()`은 현재 `null`을 반환하며, `ExternalBookSearchResult`와 응답 DTO에 `searchId`가 없다. 따라서 **아래 흐름의 HTTP 응답과 클라이언트의 더 보기 연결은 미완성**이다. `searchId`를 포함한 응답은 구현해야 할 계약이다.

현재 활성 저장소는 `InMemoryBookSearchStateStore`다. `RedisBookSearchStateStore`는 메서드 골격만 있으며, 동작하는 Redis 저장소는 아직 구현하지 않았다.

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
                    InMemory                ├─ TITLE 조회
                                           ├─ AUTHOR 조회
                                           └─ PUBLISHER 조회
                                                   │
                                 ISBN 중복 제거 + 미반환 도서 보관
                                                   │
                                   최대 20권 추출 / DB 서재 조회
                                                   │
                        searchId + books + hasNext 반환 (응답 연결 예정)
```

## 책임 경계

| 구성 요소 | 책임 |
|---|---|
| `ExternalBookSearchService` | 최초 검색·더 보기 구분, 검색 상태 진행, 페이지 구성 |
| `ExternalBookSearchPort` | `keyword`, 필드별 페이지를 받아 외부 결과 조회 |
| `NationalLibraryBookAdapter` | 병렬 HTTP 호출, 응답 변환, **현재 조회 묶음 내** ISBN 중복 제거 |
| `BookSearchStatePort` | 검색 세션 저장·조회·삭제 |
| `BookRegistrationLookupPort` | **반환 대상 ISBN**의 사용자별 등록 서재 목록 조회 |

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
| `ExternalBookSearchResult` | `ExternalBookSearchService` | 최대 20권의 `books`, `hasNext` (`searchId` 추가 필요) |

현재 `BookRegistrationLookupPort`는 ISBN별 `BookShelfInfo` 목록만 반환하며, 독서 상태는 조회하지 않는다. 조회 SQL은 `ShelfBook` 연결이 있는 도서를 대상으로 하므로, 서재 목록이 없다는 사실만으로 `UserBook` 미등록을 판단할 수 없다.

## 최초 검색과 더 보기

### 최초 검색 `search(userId, keyword)`

1. `searchId`를 생성하고 필드별 조회 위치를 모두 1페이지로 초기화한다.
2. 각 필드의 외부 결과를 병렬 조회하고 ISBN 중복을 제거한다.
3. `pendingBooks`에서 최대 20권을 골라 등록 정보를 조회한다.
4. 미반환 도서나 다음 조회 위치가 남으면 상태를 저장하고, 모두 소진되면 삭제한다. Service는 `books`와 `hasNext`를 담은 결과 DTO를 반환한다. HTTP 응답 변환과 `searchId` 전달은 아직 미구현이다.

### 더 보기 `loadMore(userId, searchId)`

1. 상태를 조회하고 요청 사용자와 세션 소유자가 같은지 확인한다.
2. `pendingBooks`가 20권 이상이면 **외부 API를 호출하지 않는다**.
3. 부족하면 `nextPageByField`에 남은 필드만 해당 페이지에서 추가 조회하고, 세션 전체 `seenIsbns`로 중복을 제거한다. 20권을 확보하거나 모든 필드가 소진될 때까지 반복한다.
4. 최대 20권을 선택하고 등록 서재 목록을 조회한 뒤, 결과·상태를 갱신한다.

예를 들어 `TITLE=3`, `PUBLISHER=2`만 남았다면 저자 조회는 하지 않는다. **외부 API 페이지 번호는 클라이언트가 보내지 않고 서버가 관리**한다.

## 페이징·종료 정책

| 항목 | 정책 |
|---|---|
| 외부 API 요청 | 필드별 최대 100건 요청 (실제 허용·응답 결과 검증 필요) |
| 사용자 응답 | 최대 20권 |
| 중복 제거 | Adapter 현재 조회 묶음 + Service 검색 세션 전체 |
| 다음 결과 판단 | 미반환 도서 또는 미조회 외부 페이지 존재 시 `hasNext=true` |
| 최종 종료 | 미반환 도서와 다음 외부 페이지가 모두 없으면 세션 삭제 |

`hasNext=true` 뒤에 중복 결과만 나와 **빈 다음 응답**이 생길 수 있다. 이것은 불필요한 선조회를 줄이기 위해 수용한 정책이다.

## 검증 및 보완

- [ ] 최초 검색 → 보관 도서로 더 보기 → 버퍼 소진 후 추가 조회
- [ ] 검색 결과·응답 DTO에 `searchId` 전달 및 HTTP 응답 변환 구현
- [ ] 중복 ISBN, 결과 0권, 모든 검색 필드 소진
- [ ] 사용자별 검색 세션 접근 제한
- [ ] 외부 API 응답 오류·타임아웃 및 반복 조회 횟수 제한
- [ ] 동일 세션 동시 요청과 TTL 만료 처리
- [ ] Redis 전환 후 상태 저장·복원 검증

관련 결정: [ADR-012](../decisions/012-stateful-book-search-pagination.md). API 선정 근거: [book-metadata-api-selection.md](../planning/book-metadata-api-selection.md).
