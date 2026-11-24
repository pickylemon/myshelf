# ERD

MyShelf의 데이터 모델 및 엔티티 간 관계를 정의하는 문서.

Feature 4의 초기 모델에 Feature 5의 V5~V7 컬럼·제약 변경을 반영한다. 문서와 마이그레이션 정의를 설명하며 각 DB의 실제 적용 완료를 의미하지 않는다.

초기 모델 재정리 당시에는 폐기 가능한 개발 DB를 초기화하고 V1을 재작성했다. 이후 변경은 V2부터 추가 마이그레이션으로 관리하며, 현재 변경을 위해 적용된 V1을 다시 수정하지 않는다.

## 1. Diagram

MyShelf의 데이터 모델을 시각화한 ERD.

![MyShelf ERD](myshelf-erd.png)

실제 데이터베이스 스키마는 Flyway 마이그레이션 SQL을 기준으로 관리한다.
ERD 이미지는 초기 관계를 이해하기 위한 보조 자료로, V5~V7의 컬럼 변경을 모두 반영한 그림은 아니다. 최신 컬럼·제약은 아래 표와 마이그레이션 SQL을 확인한다.

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
| description | varchar(255) | NULL | Shelf 설명, V3 |
| created_at | timestamptz | NOT NULL, DEFAULT CURRENT_TIMESTAMP | 생성 시각, V4 |
| updated_at | timestamptz | NOT NULL, DEFAULT CURRENT_TIMESTAMP | 수정 시각, V4 |

**제약조건 및 인덱스**

- FK: `user_id` → `users.id`
- UNIQUE: `uk_shelves_name_per_user` (`user_id`, `name`), V2

V2에서 기존 `idx_shelves_user_id`를 제거했다. UNIQUE의 복합 인덱스가 `user_id`를 선행 컬럼으로 가지므로 사용자별 조회에도 활용할 수 있다.

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
| publisher | varchar(255) | NULL | 출판사, V5 |
| cover_image_url | text | NULL | 표지 URL, V5 |
| reading_status_code | varchar(30) | FK, NOT NULL | 독서 상태 코드 |
| created_at | timestamptz | NOT NULL, DEFAULT NOW() | 생성 시각, V6 |
| updated_at | timestamptz | NOT NULL, DEFAULT NOW() | 수정 시각, V6 |

V6에서 추가했던 UserBook 메모는 V7에서 제거하고 ShelfBook으로 옮긴다.

**제약조건 및 인덱스**

- FK: `user_id` → `users.id`
- FK: `reading_status_code` → `reading_statuses.code`
- UNIQUE: `uk_user_books_user_isbn` (`user_id`, `isbn`)

**도서 식별·생명주기 정책 참조**

`UNIQUE(user_id, isbn)`은 사용자별 ISBN 중복 생성을 막는다. 입력 검증·재사용·재등록과 데이터 보존의 계약은 [현행 도서 관리 정책](../planning/book-management-policy.md)을 따른다. 이 문서는 컬럼·제약·관계를 설명하고 등록 정책 전체를 중복 정의하지 않는다.

### 2.5 shelf_books

Shelf와 UserBook의 M:N 관계를 표현하는 연결 엔티티.

어떤 UserBook이 어떤 Shelf에 배치되어 있는지 관리하며, Shelf 내부의 책 순서도 저장한다.

| Column | Type | Constraint | Description |
|---|---|---|---|
| id | uuid | PK | ShelfBook 식별자 |
| shelf_id | uuid | FK, NOT NULL | Shelf 식별자 |
| user_book_id | uuid | FK, NOT NULL | UserBook 식별자 |
| position | integer | NULL, 기본값 없음 | 배치 순서. V6에서 NOT NULL 제거, 신규 연결은 null |
| memo | text | NULL, CHECK 길이 2,000자 이하 | 서재별 메모, V7 |
| created_at | timestamptz | NOT NULL, DEFAULT NOW() | 서재 등록 시각, V6 |
| updated_at | timestamptz | NOT NULL, DEFAULT NOW() | 메모·위치 수정 시각, V7 |

**제약조건 및 인덱스**

- FK: `shelf_id` → `shelves.id`, V6부터 `ON DELETE CASCADE`
- FK: `user_book_id` → `user_books.id`
- UNIQUE: `uk_shelf_books_shelf_user_book` (`shelf_id`, `user_book_id`)
- INDEX: `idx_shelf_books_user_book_id` (`user_book_id`)

동일한 Shelf에 동일한 UserBook을 중복 연결할 수 없다.

하나의 UserBook은 여러 Shelf에 연결할 수 있으며, 각 Shelf의 배치 순서는 독립적으로 관리한다.

`position`은 nullable이며 사용자 지정 순서 변경 API는 Feature 6 범위다. 수정 시각 갱신은 JPA의 `@UpdateTimestamp`가 담당한다. DB DEFAULT는 UPDATE마다 실행되는 설정이 아니다. 목록 정렬은 [목록 조회 구조](../architecture/book-management.md#5-목록-조회와-모델-변환), 메모 수명은 [현행 정책](../planning/book-management-policy.md#4-메모와-삭제-후-데이터-보존)을 참고한다.

메모의 요청 검증은 [ShelfBookMemoUpdateRequest](../../src/main/java/com/myshelf/book/adapter/in/web/shelfBook/ShelfBookMemoUpdateRequest.java)의 UTF-16 길이, DB CHECK는 문자 수를 기준으로 한다. 일부 이모지는 계산이 달라 요청 검증이 DB보다 엄격할 수 있다. 실제 CHECK 정의는 V7을 확인한다.

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

빈 Shelf·이름 중복·사용자 소유권은 [Feature 4](../planning/feature-4-shelf-management.md)에서 관리한다.

### 도서 관리

등록·메모·독서 상태와 연결 보존의 기준은 [현행 도서 관리 정책](../planning/book-management-policy.md)이다. DB가 보장하는 범위는 위 FK·UNIQUE·CHECK 정의를 따른다.

### 도서 제거 및 조회

현재 연결을 통한 Shelf별 목록과, 보존된 UserBook 행의 존재 여부를 구분한다. 전체 도서 조회의 포함 조건은 [Feature 6](../planning/feature-6-book-browsing-and-ordering.md)의 후속 계약이며 구현 완료를 뜻하지 않는다.

### 다른 도메인과의 연관

향후 수집한 문장이나 필사 기록은 ShelfBook이 아닌 UserBook을 기준으로 연관관계를 설계한다.

이를 통해 책의 Shelf 소속이 변경되더라도 관련 기록을 유지할 수 있도록 한다.

## 5. 인덱스 설계

PK 및 UNIQUE 제약조건에 의해 PostgreSQL에서 생성되는 인덱스 외에 다음 일반 인덱스를 추가한다.

| Index | Columns | Purpose |
|---|---|---|
| idx_shelf_books_user_book_id | shelf_books(user_book_id) | UserBook이 속한 Shelf 관계 조회 |

다음 UNIQUE 인덱스는 데이터 정합성뿐 아니라 조회에도 활용할 수 있다.

| Unique Constraint | Columns | Purpose |
|---|---|---|
| uk_users_email | users(email) | 이메일 중복 방지 |
| uk_shelves_name_per_user | shelves(user_id, name) | 사용자별 Shelf 이름 중복 방지 및 사용자별 조회 |
| uk_user_books_user_isbn | user_books(user_id, isbn) | 사용자별 ISBN 중복 방지 및 도서 탐색 |
| uk_shelf_books_shelf_user_book | shelf_books(shelf_id, user_book_id) | Shelf 내 중복 연결 방지 및 Shelf별 도서 탐색 |

복합 인덱스의 컬럼 순서를 고려하여 별도 인덱스의 필요성을 판단한다.

추가 성능 최적화 인덱스는 실제 조회 패턴과 실행 계획을 확인한 뒤 도입한다.

## 6. Flyway 관리

초기 V1 재작성은 당시의 개발 DB 재정리 기록이다. 현재는 V1~V7로 구조를 관리한다.

- V1: 기본 테이블·FK·UNIQUE·독서 상태 초기값
- V2~V4: Shelf 이름 UNIQUE·설명 길이·생성/수정 시각
- V5: UserBook 출판사·표지 URL
- V6: UserBook 메모·감사 시각, ShelfBook 생성 시각·nullable 위치·Shelf 삭제 CASCADE
- V7: 메모를 ShelfBook으로 이동, 최대 길이 CHECK·수정 시각 추가

이미 적용한 마이그레이션은 변경하지 않고 새 버전으로 관리한다. 미적용 파일 수정 여부와 실제 DB 적용 상태는 별도로 확인한다.

## 7. 추후 검토 사항

현재 ERD의 기본 엔티티 구성과 관계는 확정했으며, 다음 사항은 관련 기능을 설계할 때 구체화한다.

- Shelf 내부 `position`의 시작값, 중복 허용 여부 및 순서 변경 방식
- ISBN 정규화 도입 필요성 (현재는 적용하지 않음)
- 실제 조회 패턴에 따른 추가 인덱스 필요성
- 필사 및 수집 문장 도메인 확장 시 UserBook과의 관계

---

이 문서는 MyShelf 초기 데이터 모델의 설계 기준으로 사용하며, 실제 데이터베이스 스키마는 Flyway 마이그레이션을 통해 관리한다.
