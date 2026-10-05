# Authentication Architecture

## 1. 개요

MyShelf의 인증 기능은 `auth` feature를 중심으로 구성한다.

현재 인증의 주요 흐름은 다음과 같다.

1. 사용자가 이메일과 비밀번호로 로그인을 요청한다.
2. `auth`가 `user` feature를 통해 사용자를 조회한다.
3. 입력한 비밀번호와 저장된 비밀번호를 비교한다.
4. 인증에 성공하면 JWT Access Token을 발급한다.
5. 발급된 Access Token을 HTTP 응답으로 반환한다.

로그인 과정에서 `auth`는 사용자 저장소나 JPA 구현에 직접 접근하지 않는다.

각 feature와 계층의 책임을 분리하고, Application 계층이 JPA, BCrypt, JWT 등의 구체 기술에 직접 의존하지 않도록 구성한다.

---

## 2. 패키지 구조

현재 인증 관련 주요 구조는 다음과 같다.

```text
auth/
├── adapter/
│   ├── in/
│   │   └── web/
│   │       ├── LoginController
│   │       ├── LoginRequest
│   │       ├── LoginResponse
│   │       └── LoginControllerAdvice
│   │
│   └── out/
│       ├── security/
│       │   ├── AuthUserPrincipal
│       │   ├── SpringSecurityAuthenticationAdapter
│       │   └── UserDetailsServiceAdapter
│       │
│       └── token/
│           ├── JwtAccessTokenAdapter
│           ├── JwtConfig
│           └── JwtProperties
│
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── LoginCommand
│   │   │   └── LoginUseCase
│   │   │
│   │   └── out/
│   │       ├── AuthenticationPort
│   │       ├── AuthenticatedUser
│   │       └── AccessTokenIssuerPort
│   │
│   └── service/
│       └── AuthService
│
├── error/
│   └── AuthErrorCode
│
└── exception/
    └── AuthException
```

사용자 조회는 `auth` 내부에서 직접 구현하지 않고 `user` feature가 제공하는 Use Case를 사용한다. 다만 이 Use Case를 직접 호출하는 주체는 `AuthService`가 아니라 `UserDetailsServiceAdapter`다 — 자세한 흐름은 5절 참고.

```text
user/
└── application/
    └── port/
        └── in/
            └── FindUserByEmailUseCase
```

`FindUserByEmailUseCase`의 실제 구현체는 `UserService`이다.

---

## 3. 로그인 요청 흐름

로그인 API는 다음 흐름으로 처리한다.

```text
POST /auth/login
        ↓
LoginController
        ↓
LoginRequest
        ↓
LoginCommand
        ↓
LoginUseCase
        ↓
AuthService
        │
        ├── AuthenticationPort
        │       ↓
        │    SpringSecurityAuthenticationAdapter
        │       ↓
        │    AuthenticationManager
        │       ↓
        │    DaoAuthenticationProvider
        │       │
        │       ├── UserDetailsServiceAdapter (사용자 조회)
        │       │       ↓
        │       │    FindUserByEmailUseCase
        │       │       ↓
        │       │    UserService
        │       │       ↓
        │       │    UserRepositoryPort
        │       │       ↓
        │       │    UserPersistenceAdapter
        │       │       ↓
        │       │    Database
        │       │
        │       └── PasswordEncoder (비밀번호 비교)
        │
        └── AccessTokenIssuerPort
                ↓
             JwtAccessTokenAdapter
                ↓
             JwtEncoder
                ↓
             JWT Access Token
        ↓
LoginResponse
        ↓
200 OK
```

`AuthService`는 로그인에 필요한 기능을 직접 구현하지 않고 각 Port를 이용해 로그인 절차를 조율한다. 사용자 조회와 비밀번호 비교는 `AuthenticationPort` 하나로 위임되고, 그 내부(조회 → 비교 → 성공/실패 판단)는 Spring Security의 `DaoAuthenticationProvider`가 담당한다.

---

## 4. AuthService의 책임

`AuthService`는 `LoginUseCase`의 구현체이다.

주요 책임은 다음 두 단계로 제한한다.

```text
1. 이메일/비밀번호로 인증 (조회 + 비교는 AuthenticationPort에 위임)
2. Access Token 발급
```

실제 코드는 다음과 같다.

```java
@Override
public String login(LoginCommand loginCommand) {
  AuthenticatedUser user =
      authenticationPort.authenticate(loginCommand.email(), loginCommand.password());

  return accessTokenIssuerPort.issue(user.userId());
}
```

`AuthService`는 다음 구현 세부사항을 알지 않는다.

```text
- User가 JPA를 통해 저장되는지
- 실제 DB가 H2인지 PostgreSQL인지
- 비밀번호가 어떤 PasswordEncoder로 비교되는지
- 인증이 AuthenticationManager/DaoAuthenticationProvider로 처리되는지
- JWT가 NimbusJwtEncoder로 생성되는지
```

Application Service는 로그인이라는 비즈니스 흐름만 알고,
구체적인 기술 선택은 Adapter에 위임한다.

이전에는 `AuthService`가 사용자 조회(`FindUserByEmailUseCase`)와 비밀번호 비교(`PasswordMatcherPort`)를 각각 별도 Port로 직접 호출했다. 지금은 이 둘을 `AuthenticationPort` 하나로 묶어서 위임한다 — 조회와 비교를 하나의 인증 절차로 보고 Spring Security의 `DaoAuthenticationProvider`에 맡기는 게 이번 변경의 핵심이다. 자세한 이유는 6절 참고.

---

## 5. User feature와의 연결

로그인에는 사용자 조회가 필요하지만 `auth`가 `UserJpaRepository`를 직접 사용하지 않는다.

다음과 같은 직접 의존은 사용하지 않는다.

```text
AuthService
    ↓
UserJpaRepository
```

대신 `user` feature가 제공하는 Inbound Port를 사용한다. 다만 이 Port를 직접 호출하는 건 `AuthService`가 아니라 `UserDetailsServiceAdapter`다 — Spring Security의 `DaoAuthenticationProvider`가 인증 과정에서 `UserDetailsService.loadUserByUsername()`을 콜백으로 호출하고, 그 구현체인 `UserDetailsServiceAdapter`가 `FindUserByEmailUseCase`를 사용한다.

```text
UserDetailsServiceAdapter
    ↓
FindUserByEmailUseCase
    ↑
UserService
```

`UserDetailsServiceAdapter`의 의존성은 다음과 같다.

```java
private final FindUserByEmailUseCase findUserByEmailUseCase;
```

실행 시 실제 주입되는 구현체는 `UserService`이지만,
`UserDetailsServiceAdapter`는 구현 클래스가 아닌 `FindUserByEmailUseCase` 계약에 의존한다.

이를 통해 `auth`가 `user`의 Persistence 구현 세부사항을 알지 않도록 한다.

전체 사용자 조회 흐름은 다음과 같다.

```text
AuthService
    ↓
AuthenticationPort
    ↓
SpringSecurityAuthenticationAdapter
    ↓
AuthenticationManager → DaoAuthenticationProvider
    ↓
UserDetailsServiceAdapter
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

현재 feature 간 의존 방향은 다음과 같이 유지한다.

```text
auth → user
```

`user → auth` 형태의 역방향 의존은 만들지 않는다.

---

## 6. 인증 처리 (사용자 조회 + 비밀번호 검증)

회원가입과 로그인은 비밀번호와 관련된 요구사항이 서로 다르다.

회원가입에서는 비밀번호를 암호화해야 한다.

```text
user
    ↓
PasswordEncoderPort
    ↓
BCryptPasswordEncoderAdapter
    ↓
PasswordEncoder.encode()
```

로그인에서는 저장된 비밀번호와 입력된 비밀번호가 일치하는지 확인해야 하는데, 이 확인을 직접 구현하지 않고 Spring Security의 `AuthenticationManager`에 위임한다.

```text
auth
    ↓
AuthenticationPort
    ↓
SpringSecurityAuthenticationAdapter
    ↓
AuthenticationManager
    ↓
DaoAuthenticationProvider
    ├── UserDetailsServiceAdapter (사용자 조회)
    └── PasswordEncoder (비밀번호 비교)
```

`SpringSecurityAuthenticationAdapter`는 `UsernamePasswordAuthenticationToken`을 만들어 `AuthenticationManager.authenticate()`를 호출할 뿐, 조회나 비교 로직을 직접 구현하지 않는다. 실제 조회는 `UserDetailsServiceAdapter`(→ `FindUserByEmailUseCase`)가, 실제 비교는 `DaoAuthenticationProvider`가 `SecurityConfig`에 등록된 `PasswordEncoder` Bean으로 수행한다.

`SecurityConfig`는 `PasswordEncoder` Bean과, `AuthenticationConfiguration#getAuthenticationManager()`를 감싼 `AuthenticationManager` Bean만 노출한다. `DaoAuthenticationProvider`는 별도로 Bean을 만들지 않는다 — 컨텍스트에 `UserDetailsService` Bean(`UserDetailsServiceAdapter`)과 `PasswordEncoder` Bean이 각각 하나씩만 있으면, Spring Security가 `AuthenticationManager`를 구성하는 과정에서 자동으로 `DaoAuthenticationProvider`를 만들어 연결한다.

이전에는 `user`의 `PasswordEncoderPort`와 별개로 `auth`가 자체 `PasswordMatcherPort`/`BCryptPasswordMatcherAdapter`를 두고 비밀번호 비교를 직접 구현했다. 지금은 이 직접 구현을 제거하고 `DaoAuthenticationProvider`가 제공하는 비교 로직을 그대로 사용한다 — 존재하지 않는 사용자에 대해서도 동일한 시간이 걸리도록 dummy password와 비교하는 타이밍 공격 방어까지 포함해서, 직접 구현하는 것보다 검증된 방식을 그대로 재사용하는 쪽을 선택했다. 자세한 내용은 7절 참고.

---

## 7. 인증 실패 처리

로그인에서는 다음 두 경우를 외부에 구분해서 노출하지 않는다.

```text
1. 입력한 이메일에 해당하는 사용자가 없음
2. 사용자는 존재하지만 비밀번호가 일치하지 않음
```

두 경우 모두 다음 오류로 처리한다.

```text
AuthException
└── INVALID_CREDENTIALS
```

HTTP 응답은 `401 Unauthorized`로 변환한다.

이는 로그인 과정에서 이메일 존재 여부를 외부에 노출하지 않기 위한 결정이다.

이 구분 없음은 이제 두 계층에 걸쳐 보장된다.

1. **Spring Security 내부**: `UserDetailsServiceAdapter`가 사용자를 못 찾으면 `UsernameNotFoundException`을 던지는데, `DaoAuthenticationProvider`는 이때 미리 계산해둔 dummy password와 비교 연산을 한 번 수행한 뒤(타이밍 공격 방어) `BadCredentialsException`으로 변환해서 던진다. 비밀번호가 실제로 틀린 경우도 동일하게 `BadCredentialsException`이 발생한다 — Spring Security 계층에서 이미 두 경우가 같은 예외로 통일된다.
2. **`auth` 어댑터**: `SpringSecurityAuthenticationAdapter`가 `BadCredentialsException`을 잡아 `AuthException(INVALID_CREDENTIALS)`로 번역한다.

```text
사용자 없음 또는 비밀번호 불일치
        ↓
DaoAuthenticationProvider가 BadCredentialsException으로 통일
        ↓
SpringSecurityAuthenticationAdapter가 AuthException(INVALID_CREDENTIALS)로 번역
        ↓
LoginControllerAdvice가 401로 변환
```

따라서 로그인 흐름에서 사용자 조회 결과가 없더라도
`UserErrorCode.USER_NOT_FOUND`를 사용하지 않는다.

예외는 문제가 발견된 기술적 위치가 아니라,
현재 수행 중인 Use Case에서의 의미를 기준으로 처리한다.

---

## 8. Access Token 발급

Application 계층은 JWT 구현 자체에 직접 의존하지 않는다.

`AuthService`는 다음 Port만 사용한다.

```java
public interface AccessTokenIssuerPort {

    String issue(UUID userId);
}
```

구현체는 `JwtAccessTokenAdapter`이다.

```text
AuthService
    ↓
AccessTokenIssuerPort
    ↑
JwtAccessTokenAdapter
    ↓
Spring Security JwtEncoder
    ↓
NimbusJwtEncoder
```

현재 JWT Access Token에는 최소한의 Claim만 포함한다.

```text
sub
→ 사용자 UUID

iat
→ 토큰 발급 시각

exp
→ 토큰 만료 시각
```

현재 인증에 필요하지 않은 이메일 등의 사용자 정보는 Token에 추가하지 않는다.

---

## 9. JWT 설정

JWT 관련 설정값은 외부 설정으로 관리한다.

```yaml
jwt:
  secret: ${JWT_SECRET}
  access-token-expiration: 30m
```

`JwtProperties`가 해당 설정을 Java 객체로 바인딩한다.

```text
application.yml
        ↓
JwtProperties
        ├── secret
        └── accessTokenExpiration
```

JWT Secret은 소스 코드나 `application.yml`에 직접 저장하지 않고
환경변수 `JWT_SECRET`을 통해 주입한다.

`JwtConfig`는 설정값을 사용해 Spring Security의 `JwtEncoder` Bean을 생성한다.

```text
JWT_SECRET
    ↓
JwtProperties
    ↓
SecretKey
    ↓
NimbusJwtEncoder
    ↓
JwtEncoder Bean
```

실제 Access Token 생성은 `JwtConfig`가 아닌 `JwtAccessTokenAdapter`가 담당한다.

각 클래스의 책임은 다음과 같다.

```text
JwtProperties
→ JWT 설정값 표현

JwtConfig
→ JWT 발급에 필요한 기술 객체 구성

JwtAccessTokenAdapter
→ 실제 Access Token 발급
```

---

## 10. Web 계층의 역할

`LoginController`는 Web 요청을 Application 입력으로 변환하고,
Application의 결과를 HTTP 응답으로 변환한다.

```text
LoginRequest
    ↓
LoginCommand
    ↓
LoginUseCase
    ↓
String accessToken
    ↓
LoginResponse
```

`LoginResponse`는 HTTP 응답 표현이므로 Web Adapter에 위치한다.

```java
public record LoginResponse(
        String accessToken
) {
}
```

따라서 `LoginUseCase`가 `LoginResponse`를 직접 반환하지 않는다.

현재 Use Case는 다음과 같이 Web 기술과 독립된 값을 반환한다.

```java
public interface LoginUseCase {

    String login(LoginCommand command);
}
```

이 구조를 통해 Application 계층이 Web DTO에 의존하지 않도록 한다.

---

## 11. Security 설정

로그인 API는 인증 전 사용자가 접근해야 하므로 명시적으로 허용한다.

```text
POST /auth/login
→ permitAll
```

모든 `/auth/**` 요청을 일괄적으로 허용하지 않고,
현재 인증 없이 접근해야 하는 Endpoint만 HTTP Method까지 포함해 명시한다.

예:

```java
.requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
```

그 외 요청은 기본적으로 인증이 필요하도록 유지한다.

---

## 12. 현재 범위

현재 인증 기능의 범위는 다음과 같다.

```text
- 이메일/비밀번호 로그인
- 사용자 조회
- BCrypt 비밀번호 비교
- 인증 실패 처리
- JWT Access Token 발급
- Access Token HTTP 응답
```

다음 기능은 현재 로그인 및 Access Token 발급 범위에 포함하지 않는다.

```text
- Refresh Token
- Redis 기반 Token 관리
- 로그아웃 Token blacklist
- Access Token 재발급
- 별도 Authorization Server
```

JWT를 이용한 요청 인증은 Access Token 발급과 별도의 관심사로 본다.

향후 인증이 필요한 API에서는 다음 흐름이 추가될 수 있다.

```text
Authorization: Bearer <access-token>
        ↓
Spring Security Resource Server
        ↓
JwtDecoder
        ↓
JWT 서명 및 만료 검증
        ↓
Authentication 생성
        ↓
SecurityContext
        ↓
보호된 API 접근
```

---

## 13. 설계 원칙 요약

현재 인증 구조는 다음 원칙을 따른다.

```text
1. auth는 로그인 흐름을 담당한다.

2. 사용자 데이터의 조회 책임은 user가 가진다.

3. auth는 user의 Persistence 구현에 직접 접근하지 않는다.

4. Application 계층은 BCrypt, JWT, JPA와 같은
   구체적인 외부 기술에 직접 의존하지 않는다.

5. 외부 기술은 Outbound Port와 Adapter를 통해 연결한다.

6. 로그인 실패 시 사용자 존재 여부와 비밀번호 오류를
   외부에 구분해서 노출하지 않는다. 이 구분 없음은 직접 구현하지 않고
   Spring Security의 DaoAuthenticationProvider가 제공하는
   타이밍 공격 방어를 그대로 재사용한다.

7. Web Response 모델은 Web Adapter에서 조립한다.

8. JWT에는 현재 인증에 필요한 최소한의 Claim만 포함한다.

9. Secret과 만료시간은 코드가 아닌 외부 설정으로 관리한다.

10. 현재 요구사항에 필요하지 않은 Refresh Token,
    Redis 등의 기능은 선행 구현하지 않는다.
```