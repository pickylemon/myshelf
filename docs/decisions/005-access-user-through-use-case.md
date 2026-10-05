# 005. Use Case를 통한 사용자 조회

> **Update (2026-10-01)**: 이 문서는 비밀번호 검증을 `auth`가 직접 구현하던 시점(`PasswordMatcherPort`/`BCryptPasswordMatcherAdapter`)의 기록이다. 이후 비밀번호 검증을 Spring Security `AuthenticationManager`/`DaoAuthenticationProvider`로 전환하면서, 이 문서가 `AuthService`의 것이라고 설명하는 `FindUserByEmailUseCase` 의존은 지금 `UserDetailsServiceAdapter`가 갖고 있다. "Use Case를 통해 조회하고 구현 클래스에 직접 의존하지 않는다"는 핵심 결정 자체는 그대로 유효하지만, 호출 주체와 흐름 다이어그램은 아래 내용과 다르다. 최신 흐름은 `docs/architecture/authentication.md`(5절, 6절) 참고.

## Context

로그인 기능을 구현하려면 `auth` feature에서 이메일을 기준으로 사용자를 조회해야 한다.

가장 단순한 방법은 `AuthService`가 `UserJpaRepository` 또는 `UserPersistenceAdapter`를 직접 사용하는 것이다.

예를 들면 다음과 같은 구조가 가능하다.

```text
AuthService
    ↓
UserJpaRepository
    ↓
Database
```

하지만 이 방식은 `auth`가 `user` feature 내부의 Persistence 구현에 직접 의존하게 만든다.

현재 MyShelf는 feature-first + hexagonal architecture를 사용하고 있으며,
각 feature 내부의 Persistence 구현은 외부 feature에서 직접 접근하지 않는 것을 기본 원칙으로 한다.

로그인 과정에서 `auth`가 실제로 필요한 것은 다음 한 가지다.

```text
"이 이메일을 가진 사용자를 조회한다."
```

사용자가 JPA를 통해 저장되는지,
어떤 Repository 구현을 사용하는지는 `auth`가 알 필요가 없다.

---

## Decision

사용자 조회는 `user` feature가 제공하는 Inbound Port를 통해 수행한다.

`user/application/port/in`에 다음 Use Case를 둔다.

```java
public interface FindUserByEmailUseCase {

    Optional<User> findByEmail(String email);
}
```

기존 `UserService`가 해당 Use Case를 구현한다.

```java
public class UserService
        implements UserSignUpUseCase, FindUserByEmailUseCase {
}
```

`AuthService`는 `UserService` 구현체에 직접 의존하지 않고,
`FindUserByEmailUseCase`를 주입받아 사용한다.

```java
private final FindUserByEmailUseCase findUserByEmailUseCase;
```

런타임에는 Spring이 `FindUserByEmailUseCase`의 구현체인 `UserService`를 주입한다.

전체 호출 흐름은 다음과 같다.

```text
AuthService
    ↓
FindUserByEmailUseCase
    ↓
UserService
    ↓
UserRepositoryPort
    ↓
UserPersistenceAdapter
    ↓
UserJpaRepository
    ↓
Database
```

---

## Reason

### 1. Feature 경계를 유지한다

`auth`가 다음 클래스에 직접 의존하지 않는다.

```text
UserJpaRepository
UserPersistenceAdapter
UserEntity
```

따라서 사용자 저장 방식은 `user` feature 내부 구현으로 유지된다.

`auth`는 사용자 조회 기능의 계약만 알면 된다.

```text
auth
    ↓
FindUserByEmailUseCase
```

---

### 2. Persistence 구현을 숨긴다

현재는 JPA를 사용하지만,
사용자 조회 방식이 변경되어도 `AuthService`는 영향을 최소화할 수 있다.

예를 들어 내부 구현이 변경되더라도:

```text
JPA
→ 다른 Persistence 기술
```

`FindUserByEmailUseCase` 계약이 유지된다면 로그인 흐름은 그대로 유지할 수 있다.

---

### 3. 구현체보다 역할에 의존한다

다음과 같이 구현 클래스에 직접 의존하지 않는다.

```java
private final UserService userService;
```

대신:

```java
private final FindUserByEmailUseCase findUserByEmailUseCase;
```

를 사용한다.

`AuthService`가 필요한 것은 `UserService`라는 클래스 자체가 아니라,

```text
"이메일로 사용자를 조회할 수 있다."
```

라는 기능이기 때문이다.

---

### 4. User 조회 책임을 user feature에 둔다

로그인 과정에서 사용자를 조회하더라도,
사용자 조회 자체의 책임은 `auth`로 이동시키지 않는다.

책임을 다음과 같이 구분한다.

```text
auth
→ 로그인 흐름 조율

user
→ 사용자 조회
```

`auth`는 조회 결과를 로그인 관점에서 해석한다.

예를 들어 사용자를 찾지 못한 경우:

```text
FindUserByEmailUseCase
→ Optional.empty()
```

를 반환하고,

`AuthService`가 이를:

```text
INVALID_CREDENTIALS
```

로 변환한다.

즉 사용자 조회와 로그인 실패의 의미를 각각의 feature가 담당한다.

---

## Trade-offs

### Auth가 user domain 모델을 알게 된다

현재 `FindUserByEmailUseCase`는 다음 타입을 반환한다.

```java
Optional<User>
```

따라서 `auth`는 `user.domain.User`에 의존한다.

```text
auth
→ user.application.port.in
→ user.domain.User
```

현재 프로젝트 규모에서는 이 정도 feature 간 의존을 허용한다.

User 모델이 커지거나 feature 간 결합을 더 엄격하게 관리할 필요가 생기면,
로그인에 필요한 정보만 제공하는 별도의 조회 모델을 고려할 수 있다.

예:

```java
public record UserAuthInfo(
        UUID userId,
        String encodedPassword
) {
}
```

하지만 현재는 추가 추상화가 실제 문제를 해결하지 않으므로 도입하지 않는다.

---

### 조회 기능 추가를 위해 user 내부 여러 계층이 수정된다

이메일 조회 기능을 추가하기 위해 다음 계층에 변경이 필요하다.

```text
FindUserByEmailUseCase
UserService
UserRepositoryPort
UserPersistenceAdapter
UserJpaRepository
```

단순 Repository 직접 호출보다 코드가 늘어난다.

하지만 각 변경은 다음 경계를 연결하기 위한 역할을 가진다.

```text
Inbound Port
    ↓
Application Service
    ↓
Outbound Port
    ↓
Persistence Adapter
```

현재 프로젝트에서는 이 비용보다 feature 경계와 의존 방향을 명확히 유지하는 이점이 더 크다고 판단한다.

---

## Consequences

로그인 기능에서 사용자 조회는 다음 규칙을 따른다.

```text
AuthService
→ FindUserByEmailUseCase 사용
```

다음 형태의 직접 접근은 사용하지 않는다.

```text
AuthService
→ UserJpaRepository

AuthService
→ UserPersistenceAdapter
```

현재 feature 간 의존 방향은 다음과 같다.

```text
auth → user
```

반대로:

```text
user → auth
```

의존은 만들지 않는다.

이를 통해 순환 의존 가능성을 줄이고,
`user`의 Persistence 구현을 `user` feature 내부에 유지한다.

---

## Summary

로그인에서 사용자 조회가 필요하더라도
`auth`가 `user`의 Persistence 구현에 직접 접근하지 않는다.

대신 `user`가 제공하는 `FindUserByEmailUseCase`를 통해 조회한다.

```text
AuthService
    ↓
FindUserByEmailUseCase
    ↑
UserService
```

핵심 기준은 다음과 같다.

> 사용하는 feature가 아니라, 해당 기능의 책임을 가진 feature에 Use Case를 둔다.

따라서 로그인에서 사용하더라도
`FindUserByEmailUseCase`의 소유자는 `user` feature이다.