# 007. Spring Security 인증 파이프라인 사용

- Status: Accepted
- Date: 2026-10-01

## Context

기존 로그인 구현에서는 `AuthService`가 다음 작업을 직접 수행했다.

1. `FindUserByEmailUseCase`를 통해 사용자 조회
2. `PasswordMatcherPort`를 통해 비밀번호 검증
3. 인증 성공 시 Access Token 발급

```text
AuthService
 ├─ FindUserByEmailUseCase
 ├─ PasswordMatcherPort
 │    └─ BCryptPasswordMatcherAdapter
 └─ AccessTokenIssuerPort
```

이 구조는 application 계층이 Spring Security의 구현 세부사항에 직접 의존하지 않는다는 장점이 있었다.

하지만 비밀번호 인증 자체를 직접 구현하면서 Spring Security가 기본적으로 제공하는 인증 처리와 보안 기능을 우회하게 되었다.

특히 사용자가 존재하지 않는 경우에는 BCrypt 연산 없이 빠르게 실패하고, 사용자가 존재하지만 비밀번호가 틀린 경우에는 BCrypt 검증을 수행하므로 두 실패 경로 사이에 실행 시간 차이가 발생할 수 있었다.

이 차이는 계정 존재 여부를 추측하는 user enumeration의 단서가 될 수 있다.

Spring Security의 `DaoAuthenticationProvider`는 `UserDetailsService`와 `PasswordEncoder`를 이용해 username/password 인증을 수행하며, 존재하지 않는 사용자에 대해서도 dummy password 검증을 수행하여 이러한 timing 차이를 완화한다.

또한 직접 비밀번호 인증을 구현할 경우 Spring Security가 이미 제공하는 인증 실패 처리, credential 검증 흐름 등의 책임을 애플리케이션에서 다시 구현해야 한다.

따라서 비밀번호 검증을 직접 수행하는 대신 Spring Security의 표준 인증 파이프라인을 사용하기로 했다.

## Decision

username/password 인증을 Spring Security의 `AuthenticationManager`에 위임한다.

전체 인증 흐름은 다음과 같다.

```text
AuthService
   ↓
AuthenticationPort
   ↓
SpringSecurityAuthenticationAdapter
   ↓
AuthenticationManager
   ↓
ProviderManager
   ↓
DaoAuthenticationProvider
   ├─ UserDetailsService
   │    ↓
   │  UserDetailsServiceAdapter
   │    ↓
   │  FindUserByEmailUseCase
   │
   └─ PasswordEncoder
```

### Application 계층

`AuthService`는 더 이상 사용자를 직접 조회하거나 비밀번호를 직접 검증하지 않는다.

```text
AuthService
   ↓
AuthenticationPort
   ↓
AuthenticatedUser
   ↓
AccessTokenIssuerPort
```

`AuthService`는 `AuthenticationPort`를 통해 인증을 요청하고, 인증 성공 후 반환된 `AuthenticatedUser`의 `userId`를 이용해 Access Token을 발급한다.

Spring Security의 `Authentication`, `UserDetails`, `AuthenticationManager` 등의 타입은 application 계층에 노출하지 않는다.

### AuthenticationPort

`AuthenticationPort`는 application 계층이 요구하는 인증 기능을 추상화한다.

```java
public interface AuthenticationPort {

  AuthenticatedUser authenticate(String email, String password);
}
```

현재 application 계층에서 인증 완료 후 필요한 정보는 `userId`뿐이므로 `AuthenticatedUser`는 최소 정보만 가진다.

```java
public record AuthenticatedUser(UUID userId) {
}
```

### SpringSecurityAuthenticationAdapter

`SpringSecurityAuthenticationAdapter`는 `AuthenticationPort`를 구현한다.

주요 책임은 다음과 같다.

- email/password를 Spring Security의 인증 요청으로 변환
- `AuthenticationManager.authenticate()`를 통해 인증 위임
- 인증 성공 결과의 principal을 `AuthenticatedUser`로 변환
- `BadCredentialsException`을 애플리케이션의 `INVALID_CREDENTIALS` 예외로 변환

비밀번호 검증 자체는 이 Adapter에서 수행하지 않는다.

```text
email/password
   ↓
UsernamePasswordAuthenticationToken
   ↓
AuthenticationManager
   ↓
인증 성공 Authentication
   ↓
AuthUserPrincipal
   ↓
AuthenticatedUser
```

`AuthenticationException` 전체를 `INVALID_CREDENTIALS`로 변환하지 않고 `BadCredentialsException`만 처리한다.

DB 조회 실패나 인증 설정 오류 등 서버 내부 문제까지 잘못된 자격 증명으로 처리하지 않기 위함이다.

### UserDetailsServiceAdapter

Spring Security는 MyShelf의 사용자 조회 방식과 도메인 모델을 알지 못한다.

따라서 Spring Security의 `UserDetailsService`를 구현하는 `UserDetailsServiceAdapter`를 둔다.

```text
DaoAuthenticationProvider
   ↓
UserDetailsService
   ↑
UserDetailsServiceAdapter
   ↓
FindUserByEmailUseCase
   ↓
User
```

`UserDetailsServiceAdapter`는 다음 책임을 가진다.

- `FindUserByEmailUseCase`를 통해 user feature에서 사용자 조회
- `Optional<User>`가 비어 있으면 `UsernameNotFoundException`으로 변환
- MyShelf의 `User`를 Spring Security가 이해할 수 있는 `AuthUserPrincipal`로 변환

이를 통해 auth feature가 user repository를 직접 참조하지 않고 기존 feature boundary를 유지한다.

### AuthUserPrincipal

MyShelf의 도메인 `User`가 Spring Security의 `UserDetails`를 직접 구현하지 않도록 별도의 `AuthUserPrincipal`을 둔다.

```text
MyShelf User
   ↓
AuthUserPrincipal
   implements UserDetails
   ↓
Spring Security
```

`AuthUserPrincipal`은 Spring Security 인증 과정에 필요한 다음 정보를 제공한다.

- `userId`
- email
- BCrypt로 인코딩된 password
- 현재는 별도 권한 모델이 없으므로 빈 authorities

email은 MyShelf의 로그인 식별자이므로 `UserDetails#getUsername()`을 통해 반환한다.

또한 `CredentialsContainer`를 구현하여 인증 완료 후 principal이 보유한 password hash를 제거할 수 있도록 한다.

도메인 `User`는 Spring Security 인터페이스에 의존하지 않는다.

### PasswordEncoder

비밀번호 검증은 기존 `PasswordMatcherPort`와 `BCryptPasswordMatcherAdapter`가 아니라 Spring Security의 `PasswordEncoder`와 `DaoAuthenticationProvider`가 담당한다.

```text
기존

AuthService
   ↓
PasswordMatcherPort
   ↓
BCryptPasswordMatcherAdapter
```

```text
변경 후

DaoAuthenticationProvider
   ↓
PasswordEncoder
   ↓
BCryptPasswordEncoder
```

따라서 다음 구성요소는 제거한다.

- `PasswordMatcherPort`
- `BCryptPasswordMatcherAdapter`

### AuthenticationManager 구성

`UserDetailsService`와 `PasswordEncoder`를 기반으로 Spring Security가 username/password 인증을 위한 내부 인증 구성을 만든다.

`DaoAuthenticationProvider`를 애플리케이션 코드에서 직접 생성하여 사용할 필요는 없다.

다만 `SpringSecurityAuthenticationAdapter`에서 `AuthenticationManager`를 생성자 주입받아 사용하므로 `AuthenticationConfiguration`에서 얻은 `AuthenticationManager`를 Bean으로 노출한다.

```java
@Bean
public AuthenticationManager authenticationManager(
    AuthenticationConfiguration configuration
) throws Exception {
  return configuration.getAuthenticationManager();
}
```

즉 Spring Security 내부 인증 구성과 애플리케이션 코드에서 주입 가능한 Bean은 구분한다.

### Testing Strategy

인증 실패 응답의 비노출과 timing side-channel 완화는 서로 다른 수준에서 검증한다.

#### 외부 응답 비노출

`LoginIntegrationTest`에서는 다음 두 실패 요청이 외부에서 구분되지 않는지 검증한다.

```text
존재하지 않는 이메일
→ 401
→ INVALID_CREDENTIALS

존재하는 이메일 + 잘못된 비밀번호
→ 401
→ INVALID_CREDENTIALS
```

두 경우의 HTTP 응답을 동일하게 유지하여 응답 내용만으로 이메일 존재 여부를 추측할 수 없도록 한다.

#### timing side-channel 완화

`SpringSecurityAuthenticationAdapterTest`에서는 실제 수행 시간을 직접 비교하지 않는다.

실행 시간은 JIT, GC, CPU 스케줄링, CI 환경 부하 등의 영향을 받기 때문에 일반적인 JUnit 테스트에서 안정적인 검증 기준으로 사용하기 어렵다.

대신 존재하지 않는 사용자에 대해서도 `PasswordEncoder.matches()`가 수행되는지를 검증한다.

```java
verify(passwordEncoder)
    .matches(PASSWORD, DUMMY_ENCODED_PASSWORD);
```

이를 통해 다음과 같이 사용자 미존재 시 비밀번호 비교 자체를 생략하는 구현으로 회귀하지 않는지 확인한다.

```text
잘못된 비밀번호
→ 실제 password hash와 password comparison
→ 인증 실패

존재하지 않는 사용자
→ dummy password hash와 password comparison
→ 인증 실패
```

이 테스트는 두 경로의 실행 시간이 완전히 동일하다는 것을 보장하지 않는다.

목표는 constant-time 인증을 증명하는 것이 아니라, 사용자 존재 여부에 따라 비용이 큰 비밀번호 비교 연산이 완전히 생략되는 명백한 timing 차이를 완화하는 것이다.

`AuthServiceTest`에서는 이러한 인증 세부 동작을 다시 검증하지 않는다.

`AuthService`의 책임은 인증 결과를 받아 Access Token 발급 여부를 조율하는 것이므로 다음만 검증한다.

```text
인증 성공
→ AuthenticatedUser의 userId로 Access Token 발급

인증 실패
→ AuthException 전파
→ AccessTokenIssuerPort 호출하지 않음
```

## Alternatives Considered

### 1. 기존 PasswordMatcher 구조 유지

기존 구조를 유지하면서 사용자가 존재하지 않는 경우 dummy BCrypt 검증을 직접 추가하는 방법을 검토할 수 있다.

장점:

- 기존 구조 변경이 작음
- 인증 흐름을 직접 제어할 수 있음

단점:

- Spring Security가 이미 제공하는 인증 로직을 다시 구현하게 됨
- user enumeration timing 완화를 직접 책임져야 함
- 향후 계정 잠금, 비활성화, credential 상태 등 인증 관련 기능이 추가될수록 직접 관리해야 할 책임이 증가함

따라서 선택하지 않았다.

### 2. AuthService에서 AuthenticationManager 직접 사용

`AuthenticationPort`와 Adapter를 두지 않고 `AuthService`에서 바로 `AuthenticationManager`를 주입받는 방법도 가능하다.

장점:

- 클래스와 인터페이스 수가 줄어듦
- 구현이 단순함

단점:

- application 계층이 Spring Security 타입에 직접 의존함
- 현재 프로젝트에서 유지하는 hexagonal boundary와 맞지 않음

따라서 `AuthenticationPort`와 `SpringSecurityAuthenticationAdapter`를 통해 Spring Security 의존성을 adapter 계층에 한정하기로 했다.

## Consequences

### Positive

- Spring Security의 표준 username/password 인증 흐름을 재사용한다.
- `DaoAuthenticationProvider`가 제공하는 user enumeration timing 완화 기능을 활용할 수 있다.
- 외부 응답 비노출과 dummy password comparison 동작을 서로 다른 테스트 수준에서 검증할 수 있다.
- `AuthService`의 책임이 로그인 흐름 조정과 Access Token 발급으로 축소된다.
- application 계층은 Spring Security 구현 타입에 의존하지 않는다.
- `FindUserByEmailUseCase`를 통해 기존 user/auth feature boundary를 유지한다.
- 직접 구현했던 BCrypt 비교 코드와 관련 추상화를 제거할 수 있다.

### Negative

- `AuthenticationPort`, `SpringSecurityAuthenticationAdapter`, `UserDetailsServiceAdapter`, `AuthUserPrincipal` 등 인증 흐름을 연결하기 위한 클래스가 추가된다.
- Spring Security의 `AuthenticationManager`, `ProviderManager`, `DaoAuthenticationProvider`, `UserDetailsService` 간 관계를 이해해야 하므로 초기 학습 비용이 증가한다.
- Spring Security 인증 예외와 애플리케이션 예외 사이의 변환 정책을 명시적으로 관리해야 한다.
- timing side-channel을 완전히 제거하거나 constant-time 동작을 보장하는 것은 아니다.

## Resulting Structure

```text
auth
├── adapter
│   ├── in
│   │   └── web
│   │       ├── LoginController
│   │       ├── LoginControllerAdvice
│   │       ├── LoginRequest
│   │       └── LoginResponse
│   │
│   └── out
│       ├── security
│       │   ├── AuthUserPrincipal
│       │   ├── SpringSecurityAuthenticationAdapter
│       │   └── UserDetailsServiceAdapter
│       │
│       └── token
│           └── ...
│
├── application
│   ├── port
│   │   ├── in
│   │   │   ├── LoginCommand
│   │   │   └── LoginUseCase
│   │   │
│   │   └── out
│   │       ├── AuthenticationPort
│   │       ├── AuthenticatedUser
│   │       └── AccessTokenIssuerPort
│   │
│   └── service
│       └── AuthService
│
└── error
    └── AuthErrorCode
```

`SecurityConfig`는 위 트리에 포함하지 않는다 — `auth` 전용 설정이 아니라 `com.myshelf.global.config.SecurityConfig`에 있는 전역 설정이며, `auth`의 `/auth/login`뿐 아니라 `user`의 `/users` permitAll까지 같은 곳에서 구성한다.

## Summary

비밀번호 인증을 애플리케이션에서 직접 구현하지 않고 Spring Security의 `AuthenticationManager`와 `DaoAuthenticationProvider`에 위임한다.

동시에 `AuthenticationPort`와 Adapter를 사용하여 Spring Security 의존성은 adapter 계층에 한정하고, user feature 접근은 기존 `FindUserByEmailUseCase` 경계를 유지한다.

인증 실패 시에는 존재하지 않는 사용자와 잘못된 비밀번호가 동일한 외부 응답을 반환하도록 하고, 사용자 미존재 시에도 dummy password comparison이 수행되는 구조를 테스트하여 timing side-channel을 완화한다.

이를 통해 Spring Security가 제공하는 검증된 인증 동작을 재사용하면서도 MyShelf의 hexagonal architecture 경계를 유지한다.