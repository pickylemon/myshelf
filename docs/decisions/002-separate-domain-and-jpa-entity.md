# 002. Domain Model과 JPA Entity를 분리한다

## Status

Accepted

## Context

MyShelf는 feature-first 구조와 Hexagonal Architecture를 사용한다.

Domain 객체에 `@Entity`, `@Id`, `@Column` 등 JPA 관련 요소를 직접 사용하면
도메인 계층이 Persistence 기술에 의존하게 된다.

이를 피하기 위해 비즈니스 모델과 데이터베이스 영속성 모델을 분리한다.

## Decision

Domain Model과 JPA Entity를 별도 클래스로 관리한다.

User 기준 구조는 다음과 같다.

```text
user
├── domain
│   └── User
│
└── adapter
    └── out
        └── persistence
            ├── UserEntity
            ├── UserJpaRepository
            └── UserPersistenceAdapter
```

각 객체의 역할은 다음과 같다.

- `User`
    - 도메인 모델
    - JPA에 의존하지 않는다.
    - 비즈니스 상태와 규칙을 표현한다.

- `UserEntity`
    - JPA 영속성 모델
    - DB 테이블과 매핑한다.
    - JPA의 제약과 생명주기를 따른다.

- `UserJpaRepository`
    - Spring Data JPA를 이용해 DB에 접근한다.

- `UserPersistenceAdapter`
    - `UserRepositoryPort`를 구현한다.
    - Domain과 JPA Entity 사이를 변환한다.
    - Persistence 기술에서 발생한 문제를 Application이 이해할 수 있는 형태로 변환한다.

## Dependency Direction

Application은 `UserJpaRepository`나 `UserEntity`를 직접 알지 않는다.

```text
UserService
    ↓
UserRepositoryPort
    ↑
UserPersistenceAdapter
    ↓
UserJpaRepository
    ↓
UserEntity
    ↓
DB
```

Domain은 Spring, JPA, Hibernate를 알지 않는다.

## Reason

Persistence 기술의 변경이 Domain과 Application까지 직접 전파되는 것을 줄이기 위함이다.

또한 JPA Entity의 기본 생성자, mutable field 등 ORM을 위한 제약을 Domain Model에 강제하지 않아도 된다.

Persistence Adapter가 두 모델 사이의 경계를 명확하게 만든다.

## Trade-off

Domain과 Entity 사이에 변환 코드가 필요하다.

필드가 추가될 때 두 모델과 변환 로직을 함께 수정해야 하므로 코드량이 증가한다.

현재 규모에서는 별도의 Mapper 클래스를 추가하지 않고
`UserEntity.from(User)` / `UserEntity.toDomain()` 수준으로 유지한다.

변환 로직이 복잡해질 때만 Mapper 분리를 검토한다.

## Consequences

- Domain 클래스에는 JPA annotation을 사용하지 않는다.
- `JpaRepository`의 generic 타입에는 Domain이 아니라 JPA Entity를 사용한다.
- Application Service는 Repository Port에만 의존한다.
- Persistence Adapter가 Domain ↔ Entity 변환 책임을 가진다.