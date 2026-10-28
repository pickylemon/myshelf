# ERD

MyShelf의 데이터 모델 및 엔티티 간 관계를 정의하는 문서.

Feature 4 설계를 기준으로 초기 데이터 모델을 재정리했으며, 향후 기능이 추가됨에 따라 점진적으로 확장한다.

현재는 초기 개발 단계로 보존해야 할 데이터가 없으므로, 기존 Flyway V1을 새로운 스키마로 재작성하고 개발 DB를 초기화하여 적용한다.

## 1. Diagram

MyShelf의 데이터 모델을 시각화한 ERD.

![MyShelf ERD](myshelf-erd.png)

실제 데이터베이스 스키마는 Flyway 마이그레이션 SQL을 기준으로 관리한다.
ERD는 스키마 구조를 이해하기 위한 보조 문서다.

주요 엔티티 관계는 다음과 같다.

```text
users
 ├── 1:N ── shelves
 └── 1:N ── user_books
                  │
reading_statuses  │
      │           │
      └── 1:N ────┘

shelves 1:N ── shelf_books ── N:1 user_books
```

## 2. Entities

### 2.1 users

사용자 정보를 관리하는 기존 엔티티.

| Column | Type | Constraint | Description |
|---|---|---|---|
| id | uuid | PK | 사용자 식별자 |
| email | varchar(255) | NOT NULL, UNIQUE | 로그인 이메일 |
| password | varchar(255) | NOT NULL | 암호화된 비밀번호 |

### 2.2 shelves

사용자가 생성한 개별 Shelf의 메타데이터를 관리한다.

사용자 한 명은 여러 개의 Shelf를 생성할 수 있으며, 책이 없는 빈 Shelf도 허용한다.

| Column | Type | Constraint | Description |
|---|---|---|---|
| id | uuid | PK | Shelf 식별자 |
| user_id | uuid | FK, NOT NULL | Shelf 소유자 |
| name | varchar(255) | NOT NULL | Shelf 이름 |
| description | text | NULL | Shelf 설명 |

**제약조건 및 인덱스**

- FK: `user_id` → `users.id`
- INDEX: `idx_shelves_user_id` (`user_id`)

사용자별 Shelf 목록 조회를 고려하여 `user_id` 인덱스를 생성한다.

### 2.3 reading_statuses

사용자의 독서 상태를 관리하는 Lookup Table.

DB를 독서 상태 기준 데이터의 SSOT(Single Source of Truth)로 사용하며, 허용되는 상태를 미리 정의한다.

| Column | Type | Constraint | Description |
|---|---|---|---|
| code | varchar(30) | PK | 독서 상태 코드 |
| name | varchar(50) | NOT NULL | 독서 상태 표시명 |

**초기 데이터**

| code | name |
|---|---|
| WANT_TO_READ | 읽고 싶음 |
| READING | 읽는 중 |
| READ | 완독 |

- Flyway V1에서 초기 데이터를 등록한다.
- 독서 상태는 일반 사용자가 추가·수정·삭제하지 않는다.
- Java에서는 `ReadingStatus` Enum으로 상태를 표현한다.
- DB의 상태 코드와 Java Enum은 동일한 값을 유지하도록 관리한다.
- 상태 추가·변경 시 DB 기준 데이터와 애플리케이션 코드를 함께 변경한다.

### 2.4 user_books

사용자가 등록한 도서의 정보와 독서 상태를 관리한다.

외부 도서 API를 도서정보의 원본으로 사용하며, MyShelf에서는 Shelf 표시 및 사용자별 도서 관리를 위해 필요한 정보를 저장한다.

별도의 `books` 테이블은 두지 않는다.

| Column | Type | Constraint | Description |
|---|---|---|---|
| id | uuid | PK | UserBook 식별자 |
| user_id | uuid | FK, NOT NULL | 사용자 식별자 |
| isbn | varchar(20) | NOT NULL | 도서 ISBN |
| title | varchar(255) | NOT NULL | 도서명 |
| author | varchar(255) | NULL | 저자 |
| reading_status_code | varchar(30) | FK, NOT NULL | 독서 상태 코드 |

**제약조건 및 인덱스**

- FK: `user_id` → `users.id`
- FK: `reading_status_code` → `reading_statuses.code`
- UNIQUE: `uk_user_books_user_isbn` (`user_id`, `isbn`)

**도서 식별 정책**

- 외부 도서 API에서 조회된 책을 등록 대상으로 한다.
- 초기에는 ISBN이 존재하는 책만 등록한다.
- 동일 사용자는 동일 ISBN을 가진 도서를 중복 등록할 수 없다.
- 서로 다른 사용자는 동일 ISBN의 도서를 각각 등록할 수 있다.
- 동일 작품이라도 ISBN이 다르면 서로 다른 도서로 취급한다.
- ISBN은 저장 전 일관된 형식으로 정규화한다.

**UserBook 생명주기**

UserBook은 현재 Shelf에 배치된 책만을 의미하지 않는다.

사용자가 한 번 등록한 책의 정보를 보존하는 엔티티이며, 모든 Shelf에서 제거되더라도 삭제하지 않는다.

- Shelf에서 책 제거 시 해당 ShelfBook 관계만 삭제한다.
- 마지막 ShelfBook이 제거되어도 UserBook은 유지한다.
- 별도의 Soft Delete는 도입하지 않는다.
- 다시 Shelf에 추가할 때 기존 UserBook을 재사용할 수 있다.
- 독서 상태는 UserBook에 저장하며 Shelf에 종속되지 않는다.

### 2.5 shelf_books

Shelf와 UserBook의 M:N 관계를 표현하는 연결 엔티티.

어떤 UserBook이 어떤 Shelf에 배치되어 있는지 관리하며, Shelf 내부의 책 순서도 저장한다.

| Column | Type | Constraint | Description |
|---|---|---|---|
| id | uuid | PK | ShelfBook 식별자 |
| shelf_id | uuid | FK, NOT NULL | Shelf 식별자 |
| user_book_id | uuid | FK, NOT NULL | UserBook 식별자 |
| position | integer | NOT NULL | Shelf 내부 배치 순서 |

**제약조건 및 인덱스**

- FK: `shelf_id` → `shelves.id`
- FK: `user_book_id` → `user_books.id`
- UNIQUE: `uk_shelf_books_shelf_user_book` (`shelf_id`, `user_book_id`)
- INDEX: `idx_shelf_books_user_book_id` (`user_book_id`)

동일한 Shelf에 동일한 UserBook을 중복 연결할 수 없다.

하나의 UserBook은 여러 Shelf에 연결할 수 있으며, 각 Shelf의 배치 순서는 독립적으로 관리한다.

`position`은 현재 NOT NULL만 적용하며, 순서 변경 정책을 설계할 때 추가 제약조건을 검토한다.

## 3. Relationships

| Parent | Child | Relationship |
|---|---|---|
| users | shelves | 1:N |
| users | user_books | 1:N |
| reading_statuses | user_books | 1:N |
| shelves | shelf_books | 1:N |
| user_books | shelf_books | 1:N |

Shelf와 UserBook은 ShelfBook을 통해 M:N 관계를 형성한다.

### 사용자 소유권 일치

Shelf와 UserBook은 각각 `user_id`를 가지고 있다.

ShelfBook 생성 시 연결 대상인 Shelf와 UserBook이 모두 인증된 사용자의 데이터인지 검증해야 한다.

현재 FK만으로는 두 엔티티의 사용자 소유권 일치가 보장되지 않으므로, 해당 비즈니스 규칙은 애플리케이션 계층에서 검증한다.

다른 사용자의 Shelf 또는 UserBook에 대한 접근 및 연결은 허용하지 않는다.

## 4. 데이터 관리 정책

### Shelf 관리

- 사용자는 여러 Shelf를 생성할 수 있다.
- Shelf는 사용자에게 종속된다.
- 비어 있는 Shelf도 존재할 수 있다.

### 도서 관리

- 한 사용자의 동일 ISBN은 하나의 UserBook으로 관리한다.
- 하나의 UserBook은 여러 Shelf에 배치할 수 있다.
- Shelf별 도서 배치 순서는 독립적이다.
- 독서 상태는 UserBook에서 관리하므로 어느 Shelf에서 조회해도 동일하다.

### 도서 제거 및 조회

- Shelf에서 책을 제거하면 해당 ShelfBook 관계만 삭제한다.
- ShelfBook 관계가 모두 제거되어도 UserBook은 보존한다.
- 전체 보관 도서 조회에서는 현재 하나 이상의 ShelfBook에 연결된 UserBook만 반환한다.
- Shelf별 도서 조회는 ShelfBook 관계를 기준으로 수행한다.
- Shelf에 속하지 않는 UserBook은 현재 보관 중인 도서 목록에 표시하지 않는다.

### 다른 도메인과의 연관

향후 수집한 문장이나 필사 기록은 ShelfBook이 아닌 UserBook을 기준으로 연관관계를 설계한다.

이를 통해 책의 Shelf 소속이 변경되더라도 관련 기록을 유지할 수 있도록 한다.

## 5. 인덱스 설계

PK 및 UNIQUE 제약조건에 의해 PostgreSQL에서 생성되는 인덱스 외에 다음 일반 인덱스를 추가한다.

| Index | Columns | Purpose |
|---|---|---|
| idx_shelves_user_id | shelves(user_id) | 사용자별 Shelf 조회 |
| idx_shelf_books_user_book_id | shelf_books(user_book_id) | UserBook이 속한 Shelf 관계 조회 |

다음 UNIQUE 인덱스는 데이터 정합성뿐 아니라 조회에도 활용할 수 있다.

| Unique Constraint | Columns | Purpose |
|---|---|---|
| uk_users_email | users(email) | 이메일 중복 방지 |
| uk_user_books_user_isbn | user_books(user_id, isbn) | 사용자별 ISBN 중복 방지 및 도서 탐색 |
| uk_shelf_books_shelf_user_book | shelf_books(shelf_id, user_book_id) | Shelf 내 중복 연결 방지 및 Shelf별 도서 탐색 |

복합 인덱스의 컬럼 순서를 고려하여 별도 인덱스의 필요성을 판단한다.

추가 성능 최적화 인덱스는 실제 조회 패턴과 실행 계획을 확인한 뒤 도입한다.

## 6. Flyway 관리

현재는 초기 개발 단계로 기존 개발 데이터를 보존할 필요가 없으므로, 새로운 스키마를 반영한 V1 마이그레이션을 사용한다.

- 기존 V1을 최종 ERD 기준으로 재작성한다.
- 기존 `books` 테이블은 새 스키마에서 제외한다.
- `reading_statuses` 초기 데이터를 V1에서 등록한다.
- 기존 개발 DB를 초기화한 뒤 Flyway를 재실행한다.
- 스키마가 안정화된 이후 변경사항은 V2부터 순차적으로 관리한다.

이미 적용한 V1을 수정하는 것은 폐기 가능한 개발 DB를 초기화하는 현재 단계에 한정한다.

## 7. 추후 검토 사항

현재 ERD의 기본 엔티티 구성과 관계는 확정했으며, 다음 사항은 관련 기능을 설계할 때 구체화한다.

- Shelf 내부 `position`의 시작값, 중복 허용 여부 및 순서 변경 방식
- Shelf 삭제 시 연결된 ShelfBook의 처리 방식
- ISBN 정규화의 구체적인 구현 방식
- 실제 조회 패턴에 따른 추가 인덱스 필요성
- 필사 및 수집 문장 도메인 확장 시 UserBook과의 관계

---

이 문서는 MyShelf 초기 데이터 모델의 설계 기준으로 사용하며, 실제 데이터베이스 스키마는 Flyway 마이그레이션을 통해 관리한다.