# 로그인 인증과 JWT Access Token 발급 — Feature 2

> MyShelf 학습 문서 · 기준: 2026-10-23  
> 범위: `POST /auth/login`에서 이메일·비밀번호를 검증하고 JWT를 발급하는 과정  
> 관련 문서: [JWT 기반 API 요청 인증](./jwt-request-authentication.md), `docs/architecture/authentication.md`

## 1. 이 문서에서 이해할 것

로그인 API의 역할은 **사용자가 입력한 자격 증명(이메일·비밀번호)을 확인하고, 성공하면 이후 요청에 사용할 JWT를 발급하는 것**이다.

여기서 서로 다른 두 작업을 구분한다.

- **로그인 인증:** 입력한 비밀번호가 해당 계정의 비밀번호와 일치하는지 확인한다.
- **JWT 발급:** 로그인 성공 결과를 바탕으로 사용자 식별 정보와 유효기간을 담은 토큰에 서명한다.

> 로그인 때 비밀번호를 확인하는 것과, 이후 요청에서 JWT를 검증하는 것은 서로 다른 인증 단계다.

## 2. MyShelf의 실제 로그인 흐름

로그인 요청도 `SecurityFilterChain`을 통과한다. `permitAll()`은 **토큰 없이도 접근할 수 있다**는 인가 규칙이지, 토큰이 없어야 한다는 검사는 아니다. 현재 설정에서는 다음과 같이 동작한다.

```text
POST /auth/login
   ↓
SecurityFilterChain
   ↓
BearerTokenAuthenticationFilter
   ├─ Bearer Token 없음 → JWT 인증 시도 없이 계속 진행
   ├─ 유효한 Bearer Token → JWT 인증 성공 후 계속 진행
   └─ 유효하지 않은 Bearer Token → 401 (Controller에 도달하지 않음)
   ↓
AuthorizationFilter: permitAll()
   ↓
LoginController
```

`LoginController`에 도달한 이후의 **이메일·비밀번호 인증 및 JWT 발급 흐름**은 다음과 같다.

```text
POST /auth/login (이메일 + 비밀번호)
   ↓
LoginController                  ← HTTP 요청을 LoginUseCase로 전달
   ↓
LoginUseCase / AuthService       ← 로그인 과정 조율
   ↓
AuthenticationPort               ← 애플리케이션의 인증 요구 계약
   ↓
SpringSecurityAuthenticationAdapter
   ↓
AuthenticationManager           ← 적합한 AuthenticationProvider에 위임
   ↓
DaoAuthenticationProvider        ← 사용자 조회 + 비밀번호 비교
   ├─ UserDetailsServiceAdapter
   │     ↓
   │  FindUserByEmailUseCase     ← user 기능의 조회 계약
   └─ PasswordEncoder            ← BCrypt 해시와 입력 비밀번호 비교
   ↓
인증 성공: AuthenticatedUser(userId)
   ↓
AccessTokenIssuerPort            ← 애플리케이션의 토큰 발급 요구 계약
   ↓
JwtAccessTokenAdapter
   ↓
JwtEncoder / NimbusJwtEncoder   ← JWT 생성 및 HS256 서명
   ↓
로그인 응답: Access Token
```

이 흐름은 MyShelf의 기존 코드 분석을 바탕으로 정리했다. 실제 클래스나 경로가 리팩터링되면 문서도 함께 갱신한다.

### 구성 요소별 역할

| 구성 요소 | 담당하는 일 |
|---|---|
| `LoginController` | HTTP 입력을 받아 로그인 유스케이스를 호출한다. |
| `AuthService` | 인증이 성공한 경우에만 토큰을 발급하도록 흐름을 조율한다. |
| `AuthenticationPort` | 애플리케이션이 외부 인증 기술에 직접 의존하지 않도록 한다. |
| `SpringSecurityAuthenticationAdapter` | 로그인 입력을 Spring Security의 인증 처리에 연결한다. |
| `AuthenticationManager` | 인증 요청을 적절한 Provider에 위임한다. |
| `DaoAuthenticationProvider` | 사용자를 조회하고 비밀번호를 검증한다. |
| `UserDetailsServiceAdapter` | `FindUserByEmailUseCase`를 호출해 Spring Security가 사용할 사용자 정보를 제공한다. |
| `PasswordEncoder` | 입력 비밀번호와 저장된 BCrypt 해시를 비교한다. |
| `AccessTokenIssuerPort` | 인증 성공 후 토큰 발급을 요청하는 애플리케이션 계약이다. |
| `JwtAccessTokenAdapter` | JWT Claims를 만들고 `JwtEncoder`로 서명한다. |

## 3. Spring Security가 여기서 하는 일

### `AuthenticationManager`는 무엇인가?

인증의 진입점이다. **직접 이메일을 조회하거나 비밀번호를 비교하는 클래스가 아니라**, 해당 인증을 처리할 `AuthenticationProvider`에 요청을 전달한다.

로그인에서는 `DaoAuthenticationProvider`가 이메일·비밀번호 인증을 수행한다.

### `DaoAuthenticationProvider`는 무엇인가?

두 가지 협력 객체를 사용한다.

1. `UserDetailsService`로 사용자 정보를 조회한다.
2. `PasswordEncoder`로 제출한 비밀번호가 저장된 해시와 일치하는지 확인한다.

MyShelf의 `UserDetailsServiceAdapter`는 `user` 저장소에 직접 결합하지 않고 `FindUserByEmailUseCase`를 사용한다. 이 점이 현재 기능 간 경계를 유지하는 데 중요하다.

### `Authentication`과 `Principal`은 무엇인가?

- `Authentication`: Spring Security가 사용하는 **인증 요청 또는 인증 결과** 객체다. 인증 여부와 사용자 정보 등을 표현한다.
- `Principal`: 그 인증에서 **누구를 나타내는지** 표현하는 주체다.
- MyShelf 로그인에서는 `AuthUserPrincipal`이 `UserDetails` 형태의 사용자 정보를 제공한다.

같은 `Authentication` 인터페이스라도 **로그인 인증과 JWT 요청 인증에서는 구현 타입과 Principal이 다를 수 있다.** 두 객체를 강제로 같은 타입으로 캐스팅하면 안 된다.

## 4. 로그인 성공 후 JWT는 어떻게 발급되는가?

MyShelf에서 현재 사용하는 토큰의 주요 항목은 다음과 같다.

| 항목 | 값 또는 의미 |
|---|---|
| 서명 알고리즘 | `HS256` (HMAC SHA-256) |
| 서명 키 | `JWT_SECRET` 환경변수를 Base64 디코딩한 키(최소 32바이트 검증) |
| `sub` | 사용자 UUID 문자열 |
| `iat` | 토큰 발급 시각 |
| `exp` | 토큰 만료 시각(기본 유효기간 30분) |
| `iss`, `aud`, 역할·scope | 현재 발급 토큰에는 없음 |
| 시간 계산 | 주입된 `Clock` 사용 |

JWT는 보통 `Header.Payload.Signature`의 세 부분으로 표현된다. **Payload는 암호화된 비밀 저장 공간이 아니다.** Base64URL 디코딩으로 확인할 수 있으므로 비밀번호나 민감한 정보를 넣지 않는다.

`JwtEncoder`는 발급 내용을 서명한다. 서명은 **토큰 내용이 변조되었는지 검증할 근거**가 된다. 토큰을 누가 보유하는지까지 보증하지는 않는다.

## 5. 로그인이 성공해도 이후 요청이 자동 로그인 상태가 아닌 이유

MyShelf는 `SessionCreationPolicy.STATELESS`를 사용한다.

- 로그인 API는 JWT를 **발급하여 클라이언트에 돌려준다.**
- 서버가 이후 요청을 위한 HTTP 세션에 로그인 상태를 보관하는 구조가 아니다.
- 따라서 다음 보호 API 요청에서는 클라이언트가 다시 JWT를 전달해야 한다.

```http
Authorization: Bearer <access-token>
```

그 JWT를 검사하고 요청의 인증 정보로 만드는 과정은 [JWT 기반 API 요청 인증](./jwt-request-authentication.md)에서 설명한다.

## 6. 실패하면 어떻게 되는가?

존재하지 않는 이메일 또는 틀린 비밀번호는 모두 `INVALID_CREDENTIALS`로 처리하고, 로그인 실패 응답은 HTTP 401 및 기존 `ProblemDetail` 형식을 따른다. 두 실패 상황에서 사용자 존재 여부가 불필요하게 드러나지 않도록 한다.

이 **로그인 실패 처리 경로**는 Resource Server의 **JWT 요청 인증 실패 처리 경로**와 다르다. Feature 3에서는 `ProblemDetailAuthenticationEntryPoint`를 추가해 필터의 인증 실패도 기존과 같은 ProblemDetail 구조로 응답하도록 연결했다. 두 경로의 처리가 자동으로 통합되는 것은 아니다.

### 로그인 요청에서 발생할 수 있는 두 종류의 인증 실패

| 실패 상황 | 발생 위치 | 응답 처리 |
|---|---|---|
| 이메일·비밀번호가 일치하지 않음 | `LoginController` 진입 후 로그인 처리 과정 | `LoginControllerAdvice`가 HTTP 401과 `ProblemDetail` 반환 |
| 잘못된 Bearer Token을 요청에 첨부 | Controller 진입 전 `BearerTokenAuthenticationFilter` | `ProblemDetailAuthenticationEntryPoint`가 기본 Bearer 상태·헤더 처리를 위임한 뒤 `AUTHENTICATION_FAILED` ProblemDetail 반환 |

로그인 API는 **Bearer Token이 없어도 접근할 수 있다.** 반대로 Bearer Token이 명시적으로 첨부되면 인증 필터가 이를 검증하며, 잘못된 토큰은 `permitAll()`이 적용된 로그인 경로에서도 거부될 수 있다.

## 7. 코드와 테스트를 통해 확인할 지점

**관련 코드**

- `auth/adapter/in/web/LoginController`
- `auth/adapter/in/security/ProblemDetailAuthenticationEntryPoint`
- `auth/application/service/AuthService`
- `auth/adapter/out/security/SpringSecurityAuthenticationAdapter`
- `auth/adapter/out/security/UserDetailsServiceAdapter`, `AuthUserPrincipal`
- `auth/adapter/out/token/JwtAccessTokenAdapter`, `JwtConfig`
- `global/config/SecurityConfig`

**기존 테스트가 확인하는 내용**

- `AuthServiceTest`: 인증 성공 시 발급 요청, 실패 시 미발급
- `SpringSecurityAuthenticationAdapterTest`: 실제 사용자 인증 어댑터 동작
- `JwtAccessTokenAdapterTest`: 발급 토큰의 서명, `sub`·`iat`·`exp`
- `LoginIntegrationTest`: 실제 로그인 API의 성공/실패 흐름
- `JwtRequestAuthenticationIntegrationTest`: 실제 로그인 JWT의 요청 인증 성공과 실패 사례를 총 8건 검증한다. `loginWithInvalidBearerTokenReturnsUnauthorized()`는 공개 로그인 API의 잘못된 Bearer 토큰에 대해 401, 인증 헤더, ProblemDetail 본문을 확인한다. Controller 미실행 여부 자체를 단언하는 테스트는 아니다.

**디버거로 따라가 보기**

1. `AuthService`가 `AuthenticationPort`를 호출하는 시점
2. `AuthenticationManager`가 `DaoAuthenticationProvider`로 위임하는 시점
3. `UserDetailsServiceAdapter`가 사용자 정보를 반환하는 시점
4. 비밀번호 비교가 성공하여 사용자 UUID를 얻는 시점
5. `JwtAccessTokenAdapter`가 토큰을 발급하는 시점

## 8. 스스로 확인할 질문

1. 왜 `AuthService`가 BCrypt를 직접 검증하지 않고 `AuthenticationPort`를 호출하는가?
2. `AuthenticationManager`와 `DaoAuthenticationProvider`는 왜 분리되어 있는가?
3. 로그인 성공 뒤 서버가 세션을 저장하지 않아도 되는 이유는 무엇인가?
4. JWT의 `sub`는 누구를 가리키고, 서명은 무엇을 보증하는가?

## 참고 자료

- [Spring Security — DaoAuthenticationProvider](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html)
- [Spring Security — Servlet Authentication Architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html)

> 2026-10-23에 로그인 관련 소스와 Feature 3의 EntryPoint·통합 테스트를 대조해 갱신했다. JWT 요청 인증 8건의 통과는 확인했으며, 기존 로그인·회원가입을 포함한 전체 회귀 결과는 최종 검증에서 별도로 확인한다.
