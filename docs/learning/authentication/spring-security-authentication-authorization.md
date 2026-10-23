# Spring Security 인증·인가 처리 구조 — MyShelf 학습

> MyShelf 학습 문서 · 기준: 2026-10-23  
> 범위: Servlet `SecurityFilterChain`의 핵심 인증·인가 흐름과 오류 처리  
> 관련 문서: [JWT 요청 인증 구현·테스트](./jwt-request-authentication.md) · [로그인 인증과 JWT 발급](./login-authentication-and-jwt-issuance.md) · [OAuth2 Resource Server](./oauth2-resource-server.md)

## 1. 먼저 구분할 세 가지

| 개념 | 묻는 질문 | 담당 구성 요소(현재 MyShelf) |
|---|---|---|
| **JWT 검증** | 제출한 JWT의 서명·Claims가 유효한가? | `JwtDecoder` |
| **인증(Authentication)** | 이 요청을 누구의 요청으로 인정할까? | 인증 필터, `AuthenticationManager`, `JwtAuthenticationProvider` |
| **인가(Authorization)** | 이 요청이 해당 API에 접근해도 되는가? | `AuthorizationFilter` |

**JWT 검증은 인증 과정의 일부**다. 검증에 성공하면 Spring Security는 인증 결과인 `Authentication`을 만들고 현재 요청에서 참조할 수 있게 한다. 그 후 `AuthorizationFilter`가 접근 정책을 판단한다.

인증·인가 실패 시 HTTP 응답을 만드는 동작은 **별도의 인증 단계가 아니라 예외 처리**다.

## 2. 보호 API의 전체 흐름

```text
GET /test-only/principal
         ↓
SecurityFilterChain
         ↓
BearerTokenAuthenticationFilter ───── 토큰 없음 ─────┐
         │                                           │
     Bearer JWT 있음                                  │
         ↓                                           │
BearerTokenAuthenticationToken                       │
         ↓                                           │
AuthenticationManager → JwtAuthenticationProvider    │
                              ↓                      │
                           JwtDecoder                │
                              │                      │
             실패 ────────────┤                      │
              ↓               │ 성공                 │
        EntryPoint            ↓                      │
         401 응답     JwtAuthenticationToken         │
                              ↓                      │
                    SecurityContextHolder            │
                              │                      │
                              └─────────────┬────────┘
                                            ↓
                               AuthorizationFilter
                        (보호 API: authenticated() 판단)
                                  │         │
                            미인증(익명)    인증됨
                                  ↓         ↓
                          접근 거부 예외  Controller
                                  ↓
                         ExceptionTranslationFilter
                                  ↓
                        AuthenticationEntryPoint
                                  ↓
                            401 Unauthorized
```

*현재 MyShelf의 `authenticated()` 보호 API를 기준으로 그린 핵심 흐름이다. 실제 체인에는 `AnonymousAuthenticationFilter` 등 다른 필터도 있다. `ExceptionTranslationFilter`는 `AuthorizationFilter`보다 앞에 배치되어 뒤쪽에서 발생한 접근 거부 예외를 처리한다. **403은 현재 그림의 미인증 경로가 아니라, 추후 권한 규칙을 도입했을 때 인증된 사용자에게 권한이 부족한 일반적인 사례**다.*

### 주요 구성 요소

| 구성 요소 | 역할 |
|---|---|
| `BearerTokenAuthenticationFilter` | Bearer 토큰 추출; 없으면 JWT 인증을 시도하지 않고 진행 |
| `AuthenticationManager` | 인증 요청을 처리할 Provider에 위임 |
| `JwtAuthenticationProvider` | `JwtDecoder`를 호출해 JWT 인증 수행 |
| `JwtAuthenticationToken` | JWT 인증에 성공한 `Authentication` 결과 |
| `SecurityContextHolder` | 현재 요청의 인증 정보를 조회하는 통로 |
| `AnonymousAuthenticationFilter` | 인증 결과가 없을 때 익명 사용자 표현을 설정할 수 있음 |
| `AuthorizationFilter` | `permitAll()`, `authenticated()` 등의 경로 접근 정책 평가 |
| `ExceptionTranslationFilter` | 뒤쪽 필터에서 발생한 인증·인가 관련 예외를 응답 처리로 연결 |

로그인(Feature 2)도 `AuthenticationManager`·`AuthenticationProvider` 구조를 사용하지만, `LoginController` 이후의 `DaoAuthenticationProvider`가 이메일·비밀번호를 검사한다. 반면 JWT API 요청(Feature 3)은 보안 필터에서 `JwtAuthenticationProvider`를 사용한다. **구조를 공유한다고 같은 Manager 객체 하나가 두 요청을 모두 처리한다는 뜻은 아니다.**

## 3. 토큰 상태에 따라 왜 실패 위치가 다를까?

### A. 토큰 없이 보호 API 요청

```text
BearerTokenAuthenticationFilter
  → 토큰 없음: JWT 검증을 하지 않음
  → (AnonymousAuthenticationFilter: 익명 인증 표현 가능)
  → AuthorizationFilter: authenticated() 불충족
  → ExceptionTranslationFilter: EntryPoint 호출
  → 401 Unauthorized
```

여기서는 **JWT 검증에 실패한 것이 아니다**. 유효한 인증 정보가 없는 상태에서 보호 경로에 접근했기 때문에 인가 판단에서 거부된다.

### B. 만료되거나 서명이 잘못된 JWT로 요청

```text
BearerTokenAuthenticationFilter
  → 제출한 Bearer 토큰으로 인증 시도
  → AuthenticationManager → JwtAuthenticationProvider → JwtDecoder
  → 검증 실패
  → BearerTokenAuthenticationFilter의 실패 처리에서 EntryPoint 호출
  → 401 Unauthorized
```

이 경우는 **인가 단계까지 가기 전에** JWT 인증이 실패한다. 모든 401이 `ExceptionTranslationFilter`를 거치는 것은 아니다.

### C. 정상 JWT로 보호 API 요청

```text
BearerTokenAuthenticationFilter
  → JwtDecoder 검증 성공
  → JwtAuthenticationToken 생성 및 SecurityContext 설정
  → AuthorizationFilter: authenticated() 충족
  → Controller 실행 (테스트 API: 200 + 사용자 UUID)
```

두 401 경로의 **실패 이유와 연결 과정**은 다르지만, MyShelf에서는 최종적으로 같은 `ProblemDetail` 응답 규격을 적용한다.

## 4. `permitAll()`은 무엇을 허용할까?

MyShelf의 인가 설정을 개념적으로 표현하면 다음과 같다.

```java
.requestMatchers(HttpMethod.POST, "/users", "/auth/login").permitAll()
.anyRequest().authenticated()
```

- `permitAll()`: **인증 없이도** 접근 가능하다는 인가 규칙.
- `authenticated()`: **인증된 요청만** 접근 가능하다는 인가 규칙.

`permitAll()`은 **토큰이 없어야 한다**거나 **SecurityFilterChain을 건너뛴다**는 의미가 아니다. 기본 구성에서는 Bearer 토큰 검사가 인가 판단보다 먼저 진행된다.

| 공개 로그인·회원가입 요청 | 결과 |
|---|---|
| Bearer 토큰 없음 | Controller 실행(로그인 비밀번호 검사는 별도 수행) |
| 유효한 JWT 첨부 | JWT 인증 후 Controller 실행(로그인이 자동 성공하는 것은 아님) |
| 잘못된 JWT 첨부 | 앞선 JWT 인증 단계에서 실패 → 401 |

따라서 `AuthorizationFilter`는 JWT가 있는지 직접 보는 것이 아니라, **요청의 인증 상태가 설정된 접근 규칙에 맞는지** 판단한다.

## 5. `AuthenticationEntryPoint`와 `commence()`

이름 때문에 'JWT 검증을 시작하는 곳'으로 오해하기 쉽지만 **그 역할이 아니다**.

> `AuthenticationEntryPoint`는 인증되지 않은 요청에 대해 **어떤 HTTP 응답을 보낼지 처리하는 인터페이스**다.

`commence()`는 **이미 인증되지 못한 상황**에서 호출되어 응답 처리를 수행한다.

| 인증 방식 | EntryPoint가 하는 일의 예 |
|---|---|
| Form Login | 로그인 페이지로 리다이렉트 |
| HTTP Basic | 401과 `WWW-Authenticate: Basic` |
| Bearer JWT | 401과 `WWW-Authenticate: Bearer ...` |

MyShelf의 `ProblemDetailAuthenticationEntryPoint.commence()`는 아래 두 작업을 수행한다.

1. 기본 `BearerTokenAuthenticationEntryPoint`에 HTTP 상태·`WWW-Authenticate` 헤더 설정을 **위임**한다.
2. `application/problem+json` 형식의 **`ProblemDetail` 본문**을 추가한다.

따라서 이것은 **토큰 검증기가 아니라 인증 실패 응답 처리기**다. 이미 설정된 응답 상태가 요청 형식에 따라 400일 수도 있으므로, 커스텀 구현은 이를 무조건 401로 덮어쓰지 않는다.

## 6. `ExceptionTranslationFilter`, 401과 403

`ExceptionTranslationFilter`는 뒤쪽 필터에서 발생한 보안 관련 예외를 적절한 HTTP 응답 처리기로 연결한다. **토큰 없음 → 보호 API 접근 거부** 상황에서는 보통 이 필터가 `AuthenticationEntryPoint`를 호출한다.

| 상황 | 보통의 응답 | 처리 주체 |
|---|---|---|
| 인증되지 않은 요청이 보호 API 접근 | 401 | `AuthenticationEntryPoint` |
| 제출한 JWT 자체가 검증 실패 | 401 | Bearer 인증 필터의 실패 처리 → `AuthenticationEntryPoint` |
| 인증은 됐지만 필요한 권한이 부족 | 403 | `AccessDeniedHandler` |

MyShelf는 현재 역할 기반 권한 정책을 구현하지 않았으므로 **403은 일반적인 구분을 설명한 것이며, Feature 3에서 403 경로를 검증했다는 뜻은 아니다.**

### 로그인 실패 응답과 다른 이유

`POST /auth/login`도 SecurityFilterChain을 통과한다. 단, **이메일·비밀번호 인증**은 Controller 이후 `AuthService`가 `AuthenticationManager`를 호출하면서 시작한다. 이 과정의 실패는 `LoginControllerAdvice`가 `ProblemDetail`로 처리한다.

반면 JWT 인증 실패는 **Controller에 도달하기 전 필터에서 발생**하므로 `LoginControllerAdvice`가 자동 처리하지 않는다. 두 경로의 JSON 형식을 통일하기 위해 MyShelf는 별도의 EntryPoint를 구성했다.

## 7. MyShelf의 테스트와 연결해 보기

- **토큰 없음 → 401:** `AuthorizationFilter`의 `authenticated()` 요구와 미인증 요청 처리 검증.
- **만료 토큰 → 401:** 실제 `JwtDecoder` 검증 실패 후 EntryPoint 응답 검증.
- **로그인 API + 잘못된 Bearer → 401:** `permitAll()`이 인증 필터를 생략하지 않는다는 사실 확인.
- **정상 JWT → 200 + UUID:** 인증 결과가 Controller까지 전달되는지 확인.

정확한 테스트 입력·단언·한계는 [Feature 3 통합 테스트 기록](./jwt-request-authentication.md#5-통합-테스트에서-검증한-것)을 참조한다.

### 디버거로 따라가 볼 지점

1. `BearerTokenAuthenticationFilter`가 토큰을 추출하거나 생략하는 부분
2. `JwtAuthenticationProvider` → `JwtDecoder`의 검증
3. `SecurityContextHolder`에 설정된 인증 정보
4. `AuthorizationFilter`의 접근 판단
5. `ExceptionTranslationFilter`와 `AuthenticationEntryPoint.commence()`의 실패 처리

## 참고 자료

- [Spring Security — Servlet Authentication Architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html)
- [Spring Security — OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [Spring Security — Authorization Architecture](https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html)

> 이 문서는 MyShelf에서 사용한 Spring Security 구성과 이번 Feature 3에서 학습한 개념을 정리한 것이다. 전체 Spring Security 필터 목록을 설명하는 문서는 아니다.
