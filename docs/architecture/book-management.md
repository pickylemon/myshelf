# 도서 등록·관리 구조

## 1. 범위와 책임

Feature 5의 UserBook 등록·독서 상태 변경과 ShelfBook 연결·메모·삭제·목록 조회를 설명한다. 비즈니스 정책과 API 계약은 [Feature 5](../planning/feature-5-book-management.md#3-확정된-비즈니스-정책)에서 관리한다.

| 모델 | 책임 |
|---|---|
| UserBook | 사용자별 ISBN·도서 메타데이터·공유 독서 상태 |
| ShelfBook | 특정 Shelf의 연결·독립 메모·위치·생성/수정 시각 |
| ShelfBookItem | 목록 조회에 필요한 두 모델의 값을 모은 Application DTO |

마지막 연결을 삭제해도 UserBook은 보존한다. 메모는 연결과 함께 삭제되므로 재등록 시 UserBook 메모를 초기화하는 분기는 없다. 관리 대상 여부의 기준은 [소프트 삭제 판단](../learning/database/soft-delete-and-source-of-truth.md)을 참고한다.

## 2. 등록: 외부 정보 준비와 DB 쓰기를 분리

```text
ShelfBookAddController → ShelfBookAddUseCase → BookService
    ├─ Shelf 존재·소유권 확인
    ├─ 요청 ISBN 중 기존 UserBook이 없는 도서 식별
    ├─ 신규 도서 메타데이터 준비: ISBN 캐시 → 없으면 외부 API
    └─ BookRegistrationWriter.register() [트랜잭션]
         ├─ Shelf 소유권 재확인
         ├─ 현재 Shelf에 이미 연결된 도서는 제외
         ├─ UserBook 생성 또는 요청 독서 상태 적용
         └─ ShelfBook 생성 → 새 연결의 ISBN·ID 반환
```

클라이언트는 ISBN·독서 상태만 전달한다. 제목·저자 등을 다시 받아 저장하지 않고 서버가 확보한 메타데이터를 사용한다. 기존 UserBook은 메타데이터 조회·갱신 없이 재사용한다.

외부 API와 Redis 대기 동안 DB 쓰기 트랜잭션을 유지하지 않도록 Writer를 별도 Spring Bean으로 분리했다. `BookService`가 Writer 프록시를 호출하면 트랜잭션이 시작된다. 등록 중 런타임 예외가 밖으로 전달되면 앞선 생성·상태 변경·연결도 롤백 대상이다. 실제 롤백 검증은 아직 테스트 TODO로 남아 있다.

Redis 저장과 PostgreSQL 쓰기는 하나의 분산 트랜잭션이 아니다. 등록이 롤백되어도 공용 메타데이터 캐시가 남는 것은 허용한다. 캐시의 수명과 장애 정책은 [검색 설계](book-search.md#메타데이터-캐시와-검색-세션)를 참고한다.

## 3. Persistence Adapter의 역할

`BookPersistenceAdapter`는 등록 정보 조회 Port와 도서 저장·조회 Port를 구현한다. JPA Entity 변환, 소유자 조건 조회와 DB 예외 변환을 담당한다.

- 생성은 `saveAndFlush()`로 SQL을 실행해 Adapter의 catch 안에서 UNIQUE 위반을 확인한다.
- `uk_user_books_user_isbn`, `uk_shelf_books_shelf_user_book` 위반만 `DUPLICATE_REGISTRATION`으로 변환한다. FK·다른 무결성 오류까지 중복 등록으로 취급하지 않는다.
- 메모·독서 상태 변경은 관리 Entity의 필요한 필드만 변경한다. 별도 `save()` 없이 트랜잭션의 Dirty Checking을 사용한다.
- flush는 commit이 아니다. 현재 영속성 컨텍스트의 대기 변경을 반영하며, 이후 실패 시 전체 트랜잭션을 롤백할 수 있다.

쓰기 지연과 예외 발생 시점은 [JPA 학습 문서](../learning/database/jpa-update-and-transactions.md#7-feature-5의-등록과-관리-트랜잭션)에 정리한다.

## 4. 메모·상태 변경과 다건 삭제

`BookService.updateMemo()`, `update()`, `delete()`는 각각 Service 메서드에 트랜잭션을 둔다. 외부 API·Redis 호출 없이 소유권 조회와 DB 변경만 묶는다. 등록용 Writer를 이 기능들에 재사용하지 않는다.

ShelfBook은 연결된 Shelf의 소유권을, 독서 상태 변경은 UserBook의 소유권을 확인한다. 다건 삭제는 요청 내 중복을 웹에서 거부하고, 소유권 조건으로 찾은 건수가 요청 건수와 다르면 삭제 전에 404로 거부한다. 검증된 요청 ID로 Entity를 조회해 `deleteAll()`을 호출하며, SQL 한 번으로 삭제하는 벌크 쿼리는 아니다.

## 5. 목록 조회와 모델 변환

```text
ShelfController → ShelfBookListUseCase → BookService.list() [readOnly]
    ├─ ShelfGetUseCase: 빈 서재와 미존재·타인 소유 구분
    └─ BookRepositoryPort → BookPersistenceAdapter
         → ShelfBookJpaRepository: JOIN FETCH sb.userBook
         → Adapter.toItem(): Entity → ShelfBookItem
```

본인의 빈 서재는 빈 배열, 미존재·타인 소유 서재는 404다. ShelfBook 목록이 비었다는 사실만으로는 이 둘을 구분할 수 없어 Shelf 자체를 먼저 확인한다.

JPA Entity는 `UserBookEntity`를 참조하지만 도메인 ShelfBook은 `userBookId`만 가진다. 목록에서는 Domain 두 개를 중간에 만들지 않고 Adapter가 Entity 필드로 Application DTO를 구성한다. Application은 JPA Entity를 참조하지 않으며, `ShelfBookItem`도 Entity를 받는 변환 메서드를 두지 않는다.

Controller는 UseCase의 `List<ShelfBookItem>`을 그대로 반환한다. 웹 응답과 조회 DTO의 필드가 같아 별도 Response를 만들지 않았다. 응답 계약을 독립적으로 변경할 필요가 생기면 분리한다. 현재 목록은 페이징 없이 전체 반환하고, 정렬은 [Feature 5 정책](../planning/feature-5-book-management.md#33-shelfbook과-데이터-보존)을 따른다.

## 6. 검증 상태

등록 Service·Writer 단위 테스트, PostgreSQL Adapter·JWT 등록 통합 테스트, Redis 캐시 테스트가 있다. 관리·목록 API와 등록 전체 롤백 등은 보완이 필요하다. 코드 존재와 최신 전체 테스트·CI 통과를 구분하며, 종료 기준은 Feature 5에서 관리한다.
