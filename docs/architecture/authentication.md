# Authentication Architecture

## 1. 개요

MyShelf의 인증 기능은 `auth` feature를 중심으로 구성한다.

현재 인증의 주요 흐름은 다음과 같다.

1. 사용자가 이메일과 비밀번호로 로그인을 요청한다.
2. `auth`가 `user` feature를 통해 사용자를 조회한다.
3. 입력한 비밀번호와 저장된 비밀번호를 비교한다.
4. 인증에 성공하면 JWT Access Token을 발급한다.
5. 발급된 Access Token을 HTTP 응답으로 반환한다.
6. 이후 API 요청의 Bearer JWT를 Resource Server가 검증한다.
7. 검증된 인증 정보를 현재 요청의 SecurityContext에 설정하고 접근 정책을 적용한다.

로그인 과정에서 `auth`는 사용자 저장소나 JPA 구현에 직접 접근하지 않는다.

각 feature와 계층의 책임을 분리하고, Application 계층이 JPA, BCrypt, JWT 등의 구체 기술에 직접 의존하지 않도록 구성한다.

---

## 2. 패키지 구조

현재 인증 관련 주요 구조는 다음과 같다.

```text
auth/
├── adapter/
│   ├── in/
│   │   ├── security/
│   │   │   └── ProblemDetailAuthenticationEntryPoint
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

아래는 보안 필터를 통과해 Controller에 도달한 로그인 요청의 흐름이다. 로그인은 `permitAll()` 경로이지만, 잘못된 Bearer 토큰이 첨부되면 Controller에 도달하기 전에 인증 실패로 응답한다.

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

로그인 인증을 위해 `SecurityConfig`는 `PasswordEncoder` Bean과, `AuthenticationConfiguration#getAuthenticationManager()`를 감싼 `AuthenticationManager` Bean을 노출한다. `DaoAuthenticationProvider`는 별도로 Bean을 만들지 않는다 — 컨텍스트에 `UserDetailsService` Bean(`UserDetailsServiceAdapter`)과 `PasswordEncoder` Bean이 각각 하나씩만 있으면, Spring Security가 `AuthenticationManager`를 구성하는 과정에서 자동으로 `DaoAuthenticationProvider`를 만들어 연결한다. JWT 요청 인증은 같은 클래스의 `SecurityFilterChain`에서 Resource Server로 별도 구성한다.

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

JWT 요청 인증 실패는 MVC의 `LoginControllerAdvice`가 아닌 `ProblemDetailAuthenticationEntryPoint`가 처리한다. 기본 `BearerTokenAuthenticationEntryPoint`에 상태 코드와 `WWW-Authenticate` 헤더 설정을 위임한 뒤, `application/problem+json` 본문을 작성한다.

- 로그인 자격 증명 실패: `INVALID_CREDENTIALS`
- 토큰 없음 또는 유효하지 않은 토큰: `AUTHENTICATION_FAILED`

두 경로 모두 `title`은 `Auth Error`이고, `status`, `detail`, `code`를 포함하는 ProblemDetail 형식을 사용한다. 본문의 `status`는 기본 EntryPoint가 설정한 HTTP 상태와 일치시킨다. 현재 통합 테스트의 미인증·잘못된 JWT 사례는 모두 401이며, 잘못된 Bearer 요청 형식 등에서 기본 처리기가 선택하는 다른 상태 코드도 유지한다.

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

`JwtConfig`는 설정값으로 공용 `SecretKey` Bean을 만들고, 이를 Spring Security의 `JwtEncoder`와 `JwtDecoder`에 주입한다. Base64 디코딩 결과는 최소 32바이트여야 하며 발급·검증 모두 HS256을 사용한다.

```text
JWT_SECRET
    ↓
JwtProperties
    ↓
SecretKey
    ├── NimbusJwtEncoder → JwtEncoder Bean
    └── NimbusJwtDecoder → JwtDecoder Bean
```

실제 Access Token 생성은 `JwtConfig`가 아닌 `JwtAccessTokenAdapter`가 담당한다.

각 클래스의 책임은 다음과 같다.

```text
JwtProperties
→ JWT 설정값 표현

JwtConfig
→ JWT 발급·검증에 필요한 키, Encoder, Decoder와 발급 시각용 Clock 구성

JwtAccessTokenAdapter
→ 실제 Access Token 발급
```

Decoder에는 `JwtValidators.createDefaultWithValidators(subjectValidator)`를 적용한다. 기본 검증을 유지하면서 `sub`를 필수로 요구하고, 정규식으로 16진수 `8-4-4-4-12` UUID 문자열 형식을 검사한다. 대소문자는 허용하고 축약 표현은 거부한다. UUID 버전과 사용자 DB 존재 여부는 검사하지 않는다.

기본 시간 검증은 존재하는 `exp`·`nbf`에 60초의 허용 오차를 적용한다. `exp` 존재 자체를 강제하거나 미래 `iat`를 별도로 검증하는 설정은 추가하지 않았다. 발급은 주입된 `Clock`을 사용하고, Decoder의 기본 시간 검증은 자체 시스템 UTC Clock을 사용한다.

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

`POST /users`도 회원가입을 위해 공개한다. `STATELESS`로 인증을 HTTP 세션에 저장하지 않으며, Authorization 헤더 기반 API이므로 CSRF를 비활성화한다.

`oauth2ResourceServer(...jwt(...).authenticationEntryPoint(...))`로 Decoder와 사용자 정의 EntryPoint를 연결한다. `permitAll()`은 인가 규칙이므로 인증 필터를 생략하지 않는다. 공개 API라도 Bearer JWT를 첨부하면 검증 대상이다.

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
- Bearer JWT 요청 인증 및 SecurityContext 설정
- JWT 서명·만료 검증과 sub UUID 형식 검증
- 필터 인증 실패의 ProblemDetail 응답
```

다음 기능은 현재 인증 기능 범위에 포함하지 않는다.

```text
- Refresh Token
- Redis 기반 Token 관리
- 로그아웃 Token blacklist
- Access Token 재발급
- 별도 Authorization Server
```

JWT를 이용한 요청 인증은 Access Token 발급과 별도의 관심사로 본다.

현재 인증이 필요한 API 요청에는 다음 흐름을 적용한다.

```text
Authorization: Bearer <access-token>
        ↓
BearerTokenAuthenticationFilter
        ↓
AuthenticationManager → JwtAuthenticationProvider
        ↓
JwtDecoder: 서명·시간·sub UUID 형식 검증
        ↓
JwtAuthenticationToken 생성
        ↓
SecurityContext
        ↓
AuthorizationFilter: 접근 정책 확인
        ↓
Controller
```

JWT 인증 자체는 `JwtRequestAuthenticationIntegrationTest`의 테스트 전용 Controller로 확인한다. 현재는 Shelf CRUD와 Book 검색·등록·관리 업무 API에도 JWT 인증을 적용하며, 웹 Adapter의 `JwtUserIdResolver`로 sub를 UUID로 변환해 UseCase에 전달한다. 요청 인증을 위한 application 포트나 사용자 정의 JWT 필터는 추가하지 않았다.

2026-10-23에 해당 테스트 클래스를 실행해 7개 테스트 메서드의 총 8개 실행 사례가 모두 통과했다. 토큰 없음, 다른 키 서명, 실제 로그인 JWT, 공개 로그인 경로의 잘못된 JWT, 만료, 잘못된 UUID 두 사례, sub 누락을 확인한다. 모든 401 사례에서 공통 ProblemDetail 계약과 인증 헤더를 검증한다. 전체 회귀 테스트·Spotless·전체 빌드·CI는 최종 검증에서 별도로 확인한다.

---

## 13. 설계 원칙 요약

현재 인증 구조는 다음 원칙을 따른다.

```text
1. auth는 로그인과 JWT 요청 인증 구성을 담당한다.

2. 사용자 데이터의 조회 책임은 user가 가진다.

3. auth는 user의 Persistence 구현에 직접 접근하지 않는다.

4. Application 계층은 BCrypt, JWT, JPA와 같은
   구체적인 외부 기술에 직접 의존하지 않는다.

5. 로그인에서 사용하는 외부 기능은 Outbound Port와 Adapter로 연결한다.
   HTTP 요청 인증과 오류 응답은 Inbound 측 보안 구성에서 처리한다.

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
