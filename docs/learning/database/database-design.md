# MyShelf 데이터베이스 설계 학습

## 1. ERD 설계에서 배운 핵심

테이블 설계에서는 단순히 엔티티와 FK 관계를 정의하는 것뿐 아니라, 다음 사항을 함께 고려해야 한다.

1. 각 엔티티가 어떤 데이터를 책임지는가?
2. 데이터의 식별 기준과 중복 허용 범위는 무엇인가?
3. 엔티티 사이의 생명주기가 동일한가?
4. 어떤 정합성을 DB가 보장하고, 어떤 규칙을 애플리케이션이 보장하는가?
5. 실제 데이터를 어떤 조건으로 조회하는가?

MyShelf에서는 `UserBook`과 `ShelfBook`의 책임을 분리하면서 이런 판단을 경험했다.

## 2. M:N 관계와 연결 엔티티

하나의 Shelf에는 여러 UserBook이 들어갈 수 있고, 하나의 UserBook도 여러 Shelf에 속할 수 있다.

따라서 `ShelfBook`이라는 연결 엔티티를 사용한다.

```text
Shelf 1:N ── ShelfBook ── N:1 UserBook
```

`ShelfBook`은 단순 연결뿐 아니라 Shelf 내부의 도서 배치 순서(`position`)도 관리한다.

반면 독서 상태는 Shelf에 따라 달라지지 않으므로 UserBook에 둔다.

**학습 포인트:** 같은 데이터라도 소속 관계에 종속되는 정보인지, 대상 엔티티 자체에 종속되는 정보인지에 따라 저장 위치가 달라진다.

## 3. 데이터 식별과 UNIQUE 제약

MyShelf는 ISBN을 도서 식별 기준으로 사용한다.

```sql
UNIQUE (user_id, isbn)
```

이 제약의 의미는 다음과 같다.

- 같은 사용자는 동일 ISBN을 중복 등록할 수 없다.
- 서로 다른 사용자는 같은 ISBN을 등록할 수 있다.
- ISBN이 다르면 같은 작품이라도 별개 판본으로 취급한다.

UNIQUE는 단순 조회 최적화가 아니라 **데이터의 중복을 방지하는 정합성 제약**이다.

PostgreSQL은 PK와 UNIQUE 제약조건을 구현하기 위해 인덱스도 자동 생성한다.

## 4. PK, FK, UNIQUE, INDEX의 차이

| 구분 | 목적 | 인덱스 자동 생성 |
|---|---|---|
| PK | 행 식별, 유일성 | O |
| FK | 참조 무결성 | X (참조하는 컬럼) |
| UNIQUE | 중복 방지 | O |
| INDEX | 조회 및 조인 성능 최적화 | 직접 정의 |

PostgreSQL에서는 FK 컬럼에 인덱스가 자동으로 생성되지 않는다.

따라서 FK가 있다는 이유만으로 인덱스가 존재한다고 가정하면 안 된다.

반대로 PK나 UNIQUE에 의해 이미 생성된 인덱스가 있다면 중복된 인덱스를 추가할 필요가 있는지도 검토해야 한다.

## 5. 복합 인덱스의 컬럼 순서

이번 설계에서 가장 중요한 학습 내용이다.

```sql
UNIQUE (shelf_id, user_book_id)
```

PostgreSQL의 기본 B-tree 인덱스는 `shelf_id`를 우선으로 정렬하고, 같은 `shelf_id` 안에서는 `user_book_id` 순서로 정렬한다.

따라서 다음 조회에 적합하다.

```sql
SELECT *
FROM shelf_books
WHERE shelf_id = :shelfId;
```

```sql
SELECT *
FROM shelf_books
WHERE shelf_id = :shelfId
  AND user_book_id = :userBookId;
```

반면 다음 조회는 선행 컬럼인 `shelf_id`에 조건이 없다.

```sql
SELECT *
FROM shelf_books
WHERE user_book_id = :userBookId;
```

복합 인덱스를 활용할 수 있는 경우도 있지만, 일반적으로 선행 컬럼에 조건이 있는 조회보다 불리할 수 있다.

**핵심 원칙: 복합 인덱스에서는 컬럼 순서가 탐색에 유리한 방향을 결정한다.**

참고로 이 인덱스의 정렬 기준은 `position`이 아니므로, Shelf 내부의 도서 배치 순서를 보장하지는 않는다.

## 6. 관계 탐색 방향에 따른 인덱스 설계

MyShelf에서 두 가지 접근 방향을 비교했다.

| 인덱스 | 유리한 조회 |
|---|---|
| `(shelf_id, user_book_id)` | 특정 Shelf에 어떤 책들이 있는가? |
| `(user_book_id)` | 특정 UserBook은 어떤 Shelf들에 속하는가? |

`idx_shelf_books_user_book_id`는 두 번째 조회를 고려해 추가한 일반 인덱스다.

중요한 것은 **관계가 양방향이라고 무조건 양쪽 인덱스를 생성하는 것이 아니라, 실제 조회 패턴을 기준으로 필요성을 판단해야 한다**는 점이다.

또한 `UNIQUE(user_id, isbn)`으로 생성된 인덱스는 `user_id`를 선행 컬럼으로 가지므로, 사용자별 UserBook 조회에도 활용될 수 있다.

## 7. 인덱스의 비용과 검증

인덱스가 많을수록 항상 좋은 것은 아니다.

인덱스는 다음과 같은 비용을 가진다.

- INSERT, DELETE 및 일부 UPDATE 시 인덱스 유지 비용
- 인덱스 저장 공간
- 불필요하거나 중복된 인덱스의 관리 비용

따라서 다음 순서로 검토한다.

1. 실제 조회 쿼리의 WHERE, JOIN, ORDER BY 조건 확인
2. PK 및 UNIQUE로 이미 생성된 인덱스 확인
3. 부족한 접근 패턴에 필요한 인덱스 추가
4. 실제 데이터와 `EXPLAIN (ANALYZE, BUFFERS)`로 실행 계획 검증

인덱스가 존재하더라도 PostgreSQL이 항상 사용하는 것은 아니다. 테이블 크기와 데이터 분포에 따라 Sequential Scan이 더 효율적일 수 있다.

## 8. DB 정합성과 애플리케이션 비즈니스 규칙

`ShelfBook`은 Shelf와 UserBook을 참조하지만, 두 엔티티의 소유자가 같은지는 FK만으로 보장되지 않는다.

예를 들어 다음 상황은 FK 위반이 아니다.

```text
Shelf.user_id    = User A
UserBook.user_id = User B

ShelfBook → 두 엔티티 연결
```

두 대상이 모두 존재하기 때문이다.

MyShelf에서는 인증된 사용자를 기준으로 Shelf와 UserBook의 소유권을 애플리케이션 계층에서 검증하기로 했다.

이 방식은 합리적이지만, DB 자체에서 소유자 일치를 보장하지는 않으므로 모든 연결 생성 경로에서 검증하고 테스트해야 한다.

**학습 포인트:** DB 제약조건과 애플리케이션 검증은 서로 다른 역할을 가진다. 어떤 규칙을 어디서 보장할지 명확히 결정해야 한다.

## 9. Lookup Table과 기준 데이터

독서 상태는 자유로운 문자열이 아니라 사전에 정의된 기준 데이터다.

MyShelf에서는 `reading_statuses` Lookup Table을 두고, `user_books.reading_status_code`가 FK로 참조하도록 설계했다.

- DB의 Lookup Table을 상태 기준 데이터의 SSOT로 사용한다.
- Java에서는 동일한 상태 코드를 Enum으로 표현한다.
- Flyway에서 초기 상태 데이터를 등록한다.
- 상태 변경 시 DB 기준 데이터와 Java Enum의 일치 여부를 함께 관리한다.

Lookup Table은 VARCHAR + CHECK보다 항상 우수한 방식은 아니다. 하지만 기준 데이터를 DB에서 명시적으로 관리한다는 설계 목적에 부합한다.

## 10. 데이터 생명주기 분리

UserBook을 Shelf에서 제거한다고 해서 UserBook 자체를 삭제할 필요는 없다.

```text
Shelf에서 책 제거
    ↓
ShelfBook 삭제
    ↓
UserBook 유지
    ↓
독서 상태 및 향후 필사 기록 보존
```

**핵심 원칙: 관계의 종료와 엔티티 자체의 삭제는 서로 다른 사건이다.**

이처럼 비즈니스 생명주기를 기준으로 삭제 정책을 설계해야 한다.

---

## 최종 정리

이번 ERD 설계에서 배운 가장 중요한 원칙은 다음과 같다.

> 제약조건은 데이터가 지켜야 할 규칙을 정의하고, 인덱스는 데이터를 어떻게 탐색하는지에 따라 설계한다.

또한 테이블 간 FK 관계만으로 모든 비즈니스 규칙이 보장되는 것은 아니다.

MyShelf에서는 엔티티 책임, 데이터 생명주기, 제약조건, 인덱스, 애플리케이션 검증의 역할을 구분하는 방향으로 설계했다.