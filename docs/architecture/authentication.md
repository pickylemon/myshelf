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

| 영역 | 주요 구성과 책임 |
|---|---|
| `auth/adapter/in/web` | 로그인 입력·응답과 업무 예외의 HTTP 변환 |
| `auth/adapter/in/security` | JWT 사용자 ID 해석과 필터 인증 실패 응답 |
| `auth/application` | `LoginUseCase`, `AuthService`, 인증·발급 Outbound Port |
| `auth/adapter/out/security` | AuthenticationManager 위임과 UserDetailsService 연결 |
| `auth/adapter/out/token` | JWT 발급·키·Encoder·Decoder·시간 구성 |
| `user/application/port/in` | 이메일 사용자 조회 계약 `FindUserByEmailUseCase` |

파일 목록은 [auth 소스](../../src/main/java/com/myshelf/auth), 보안 체인은 [SecurityConfig](../../src/main/java/com/myshelf/global/config/SecurityConfig.java)를 기준으로 한다. 사용자 조회의 구현은 UserService이며 auth가 사용자 Persistence를 직접 호출하지 않는다.

---

## 3. 로그인 요청 흐름

로그인은 `permitAll()`이지만 잘못된 Bearer 토큰이 첨부되면 Controller 전에 인증 실패가 날 수 있다.

```text
POST /auth/login → LoginController → LoginUseCase → AuthService
  ├─ AuthenticationPort → SpringSecurityAuthenticationAdapter
  │    → AuthenticationManager → DaoAuthenticationProvider
  │       ├─ UserDetailsServiceAdapter → FindUserByEmailUseCase → UserService
  │       │    → UserRepositoryPort → UserPersistenceAdapter → PostgreSQL
  │       └─ PasswordEncoder로 비밀번호 비교
  └─ AccessTokenIssuerPort → JwtAccessTokenAdapter → JwtEncoder
       → Access Token → LoginResponse → HTTP 200
```

로그인 인증과 JWT 요청 인증은 다른 경로다. Resource Server는 이후 요청의 토큰을 검증하며 로그인 비밀번호를 대신 확인하지 않는다. 원리·예제는 [로그인 학습](../learning/authentication/login-authentication-and-jwt-issuance.md)에 정리한다.

---

## 4. AuthService의 책임

[AuthService](../../src/main/java/com/myshelf/auth/application/service/AuthService.java)는 이메일·비밀번호를 `AuthenticationPort`에 전달하고, 반환된 사용자 UUID로 `AccessTokenIssuerPort`를 호출한다. Spring Security·BCrypt·JPA·JWT 생성 API를 직접 사용하지 않는다.

인증 결과 모델 `AuthenticatedUser`에는 현재 필요한 UUID만 담는다. 웹 요청·응답 DTO는 Controller가 조립한다.

---

## 5. User feature와의 연결

사용자 조회 UseCase를 호출하는 주체는 AuthService가 아닌 **UserDetailsServiceAdapter**다. DaoAuthenticationProvider의 콜백에서 `FindUserByEmailUseCase`를 사용해 UserService에 조회를 요청한다.

의존 방향은 `auth → user`이며 `UserJpaRepository`에 직접 접근하지 않는다. 선택 근거와 이전 직접 비밀번호 비교 방식은 [ADR-005](../decisions/005-access-user-through-use-case.md), [ADR-007](../decisions/007-use-spring-security-authentication-manager.md)에 보존한다.

---

## 6. 인증 처리 (사용자 조회 + 비밀번호 검증)

회원가입의 암호화는 user의 `PasswordEncoderPort`·`BCryptPasswordEncoderAdapter`가 맡는다. 로그인의 사용자 조회·비밀번호 비교는 auth의 AuthenticationManager 위임 경로를 사용한다.

SecurityConfig는 PasswordEncoder와 `AuthenticationConfiguration#getAuthenticationManager()`를 통한 AuthenticationManager Bean을 노출한다. DaoAuthenticationProvider를 별도로 Bean으로 선언하지 않고 UserDetailsService·PasswordEncoder를 이용한 구성을 사용한다. JWT 요청 인증은 Resource Server로 별도 연결한다.

Provider의 조회·비교·타이밍 공격 완화 원리는 [Spring Security 인증·인가 학습](../learning/authentication/spring-security-authentication-authorization.md)과 [로그인 학습](../learning/authentication/login-authentication-and-jwt-issuance.md)을 참고한다.

---

## 7. 인증 실패 처리

이메일 미존재와 비밀번호 불일치를 모두 `INVALID_CREDENTIALS`·401로 응답해 계정 존재 여부를 구분하지 않는다. DaoAuthenticationProvider의 BadCredentialsException을 SpringSecurityAuthenticationAdapter가 AuthException으로 변환하고, LoginControllerAdvice가 HTTP로 변환한다. 로그인에서는 `UserErrorCode.USER_NOT_FOUND`를 사용하지 않는다.

JWT 요청 인증 실패는 MVC의 `LoginControllerAdvice`가 아닌 `ProblemDetailAuthenticationEntryPoint`가 처리한다. 기본 `BearerTokenAuthenticationEntryPoint`에 상태 코드와 `WWW-Authenticate` 헤더 설정을 위임한 뒤, `application/problem+json` 본문을 작성한다.

- 로그인 자격 증명 실패: `INVALID_CREDENTIALS`
- 토큰 없음 또는 유효하지 않은 토큰: `AUTHENTICATION_FAILED`

두 경로 모두 `title`은 `Auth Error`이고, `status`, `detail`, `code`를 포함하는 ProblemDetail 형식을 사용한다. 본문의 `status`는 기본 EntryPoint가 설정한 HTTP 상태와 일치시킨다. 현재 통합 테스트의 미인증·잘못된 JWT 사례는 모두 401이며, 잘못된 Bearer 요청 형식 등에서 기본 처리기가 선택하는 다른 상태 코드도 유지한다.

---

## 8. Access Token 발급

[JwtAccessTokenAdapter](../../src/main/java/com/myshelf/auth/adapter/out/token/JwtAccessTokenAdapter.java)는 AccessTokenIssuerPort의 구현체다. JwtEncoder에 `sub`(사용자 UUID), `iat`(발급 시각), `exp`(만료 시각)를 담아 발급한다. 이메일·역할 같은 추가 Claim은 현재 발급하지 않는다.

발급 시각은 주입된 Clock을 사용한다. HS256 선택과 키 외부화는 [ADR-006](../decisions/006-use-spring-security-jwt.md), Clock 주입의 테스트 근거는 [ADR-008](../decisions/008-inject-clock-for-jwt-time.md)을 따른다.

---

## 9. JWT 설정

설정의 실제 값은 [application.yaml](../../src/main/resources/application.yaml), Bean 구성과 검증은 [JwtConfig](../../src/main/java/com/myshelf/auth/adapter/out/token/JwtConfig.java)가 기준이다.

| 구성 | 책임 |
|---|---|
| JwtProperties | Secret·토큰 수명 설정 바인딩 |
| JwtConfig | Base64 Secret을 키로 복원, Encoder·Decoder와 발급용 Clock 구성 |
| JwtAccessTokenAdapter | 설정과 Clock으로 Claim을 구성해 발급 |

운영 Secret은 `JWT_SECRET`으로 주입하며 Base64 디코딩 결과는 최소 32바이트다. Encoder·Decoder는 같은 SecretKey와 HS256을 사용한다.

Decoder에는 `JwtValidators.createDefaultWithValidators(subjectValidator)`를 적용한다. 기본 검증을 유지하면서 `sub`를 필수로 요구하고, 정규식으로 16진수 `8-4-4-4-12` UUID 문자열 형식을 검사한다. 대소문자는 허용하고 축약 표현은 거부한다. UUID 버전과 사용자 DB 존재 여부는 검사하지 않는다.

기본 시간 검증은 존재하는 `exp`·`nbf`에 60초의 허용 오차를 적용한다. `exp` 존재 자체를 강제하거나 미래 `iat`를 별도로 검증하는 설정은 추가하지 않았다. 발급은 주입된 `Clock`을 사용하고, Decoder의 기본 시간 검증은 자체 시스템 UTC Clock을 사용한다.

---

## 10. Web 계층의 역할

LoginController는 LoginRequest를 LoginCommand로 바꾸고, UseCase가 반환한 토큰 문자열을 LoginResponse로 감싸 반환한다. Application은 웹 DTO를 참조하지 않는다. Shelf·Book Controller는 `JwtUserIdResolver`로 JWT sub를 UUID로 변환해 UseCase에 전달한다.

입력 필드와 응답의 실제 정의는 [로그인 웹 Adapter](../../src/main/java/com/myshelf/auth/adapter/in/web)를 기준으로 한다.

---

## 11. Security 설정

SecurityConfig의 단일 SecurityFilterChain을 사용한다.

- `POST /users`, `POST /auth/login`과 Swagger UI·OpenAPI 경로는 인증 없이 접근할 수 있다.
- 그 외 요청은 인증을 요구한다. 인증과 리소스 소유권 검증은 별개이며 소유권은 각 업무 UseCase에서 확인한다.
- STATELESS로 인증을 HTTP 세션에 저장하지 않고 Authorization 헤더 기반 API로 CSRF를 비활성화한다.
- Resource Server에 JwtDecoder와 사용자 정의 EntryPoint를 연결한다. `permitAll()`은 Bearer 인증 필터를 생략하지 않는다.

정확한 경로·체인 설정은 [SecurityConfig](../../src/main/java/com/myshelf/global/config/SecurityConfig.java)를 확인한다. 공개 경로에 잘못된 토큰을 보내는 사례는 [Resource Server 학습](../learning/authentication/oauth2-resource-server.md)에 설명한다.

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

테스트 방법·사례와 2026-10-23 당시 실행 결과는 [JWT 요청 인증의 검증 기록](../learning/authentication/jwt-request-authentication.md#5-통합-테스트에서-검증한-것)에 보존한다. 업무별 소유권은 Shelf·Book 통합 테스트에서 확인한다. 이 문서 정리에서 테스트·빌드를 다시 실행한 것은 아니다.

---

## 13. 설계 원칙 요약

Application은 인증·발급 계약에 의존하고 기술 구현은 Adapter에 둔다. 사용자 조회 책임은 user에, 로그인·JWT 연결 책임은 auth에 둔다. 학습 원리·선택 근거·실행 결과는 연결된 문서에서 각각 관리한다.
