# JWT 기반 API 요청 인증 — Feature 3

> MyShelf 학습 문서 · 기준: 2026-10-23  
> 범위: JWT 검증 **설정·정책·통합 테스트**와 Feature 3 구현 결과  
> 함께 읽기: [Spring Security 인증·인가 구조](./spring-security-authentication-authorization.md) · [로그인 인증과 JWT 발급](./login-authentication-and-jwt-issuance.md) · [OAuth2 Resource Server](./oauth2-resource-server.md) · [인증 아키텍처](../../architecture/authentication.md)

## 1. 목적과 구현 범위

Feature 2는 이메일·비밀번호 인증 후 JWT를 **발급**한다. Feature 3은 클라이언트가 제출한 Bearer JWT를 **검증**하여 보호 API에서 인증된 사용자 정보를 사용할 수 있게 한다.

```text
로그인 성공 → JWT 발급 → 클라이언트 보관
보호 API 요청(Authorization: Bearer <JWT>)
  → Spring Security 인증 → 인가 판단 → Controller
```

- **Spring Security 재사용:** Bearer 토큰 추출, JWT 인증 Provider 연결, `Authentication` 생성과 `SecurityContext` 설정.
- **MyShelf에서 구성:** 공유 Secret Key·HS256 Decoder, `sub` 형식 정책, 보호 경로 설정, 인증 실패 `ProblemDetail` 응답.
- 매 요청마다 비밀번호나 DB 사용자를 재조회하지 않는다. JWT가 유효해도 **해당 UUID의 계정이 현재 DB에 존재하는지**까지 보장하지 않는다.

필터 실행 순서와 인증(Authentication)·인가(Authorization)의 차이는 [별도 학습 문서](./spring-security-authentication-authorization.md)에서 다룬다.

## 2. JWT 검증 설정과 정책

### 검증 구성

- `JwtConfig`: 발급·검증에 같은 `SecretKey`를 사용하며 `NimbusJwtDecoder`를 `HS256`으로 설정.
- `JwtValidators.createDefaultWithValidators(subjectValidator)`: 기본 시간 검증을 유지하면서 MyShelf의 `sub` 형식 검증을 추가.
- `SecurityConfig`: `STATELESS`, `POST /users`·`POST /auth/login`의 `permitAll()`, 나머지 요청의 `authenticated()`, OAuth2 Resource Server JWT 처리 및 사용자 정의 EntryPoint 연결.

`setJwtValidator(subjectValidator)`만 사용하면 기본 검증을 **교체**할 수 있으므로, 현재처럼 `createDefaultWithValidators(...)`를 사용한다.

### 검증 대상

| 항목 | 현재 정책 |
|---|---|
| 서명 | HS256 및 MyShelf Secret Key로 검증. 다른 키로 서명한 토큰은 거부 |
| `exp`, `nbf` | 기본 시간 검증 사용(기본 허용 오차 60초). 존재하는 값은 검사하지만 `exp` 존재 자체를 요구하는 별도 정책은 없음 |
| `iat` | 발급 시각으로 사용. 미래 `iat` 등에 대한 모든 정책이 자동 검증되지는 않음 |
| `sub` | **필수**. 정규식으로 표준 UUID 문자열 형식 검사 |
| `iss`, `aud` | 현재 발급 토큰에는 없고 검증 정책도 별도로 지정하지 않음 |

`sub` 검사에 사용하는 정규식:

```regex
[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}
```

이 규칙은 16진수 `8-4-4-4-12` 자릿수와 하이픈 위치만 확인한다. UUID **버전**과 DB의 **사용자 존재 여부**는 확인하지 않는다. `sub`가 누락되면 `JwtClaimValidator`가 검증 실패를 반환한다. `invalid-user-id`와 축약 표현 `1-1-1-1-1`도 거부한다.

> 학습 메모: 기존 `UUID.fromString(subject).toString().equalsIgnoreCase(subject)`도 표준 표현을 확인할 수 있지만, `fromString()`은 일부 축약 표현을 변환할 수 있다. 현재 코드는 정규식으로 요구 형식을 직접 표현한다.

### 시간 검증 시 주의

만료 테스트는 **정상 키로 서명**하면서 `iat`는 30분 전, `exp`는 5분 전으로 설정했다. 기본 60초 허용 오차를 넘겨 만료로 거부되는지 확인하기 위해서다.

발급 측 `JwtAccessTokenAdapter`는 `JwtConfig`에 등록된 `Clock`을 주입받아 사용하지만, 운영 Decoder의 기본 시간 검증은 그 `Clock`을 자동으로 공유하지 않는다. 고정 시간을 이용하는 별도 발급 테스트에서는 시간 기준을 일치시켜야 한다.

## 3. 인증 성공 결과와 책임 경계

Spring Security의 기본 JWT 인증에서 `Authentication.getName()`은 일반적으로 `sub` 문자열이다. 테스트 전용 `GET /test-only/principal`은 실제 로그인 JWT로 요청했을 때 사용자 UUID와 일치하는 값을 반환한다.

추후 서재 API에서는 **웹 어댑터가 인증된 사용자 UUID를 추출해 유스케이스에 전달**하면 된다. Application/Domain이 `Jwt`, `Authentication`, `SecurityContextHolder`를 직접 알 필요는 없다.

JWT 인증 성공과 **책의 소유권 확인**은 다르다. 다른 사용자의 서재 데이터 접근 제어는 서재 기능에서 별도로 구현해야 한다.

## 4. 인증 실패 응답 — MyShelf 구현

필터 단계의 인증 실패는 MVC `LoginControllerAdvice`로 자동 전달되지 않는다. `ProblemDetailAuthenticationEntryPoint`는 기본 `BearerTokenAuthenticationEntryPoint`에 상태 코드 및 `WWW-Authenticate` 헤더 설정을 위임한 뒤 `application/problem+json` 본문을 기록한다.

```json
{
  "type": "about:blank",
  "title": "Auth Error",
  "status": 401,
  "detail": "인증 정보가 없거나 유효하지 않습니다.",
  "code": "AUTHENTICATION_FAILED"
}
```

- 보호 API에 토큰이 없는 경우와 JWT가 만료·변조된 경우는 **401이라는 결과는 같아도 실패 경로가 다르다**.
- 로그인 자격 증명 실패는 기존 Advice의 `INVALID_CREDENTIALS`로 별도 처리한다.
- `WWW-Authenticate`의 실제 값은 요청과 Spring Security 버전에 따라 달라질 수 있으므로 테스트에서는 헤더 존재를 검사한다.
- 기본 EntryPoint가 잘못된 Bearer 요청 형식 등을 이유로 400을 설정하면 현재 구현은 이를 무조건 401로 덮어쓰지 않는다.
- 403(인증된 사용자에게 권한 부족)은 별도의 인가 실패 상황이며, 현재 사용자별 권한 정책의 테스트 대상은 아니다.

실패 경로, `ExceptionTranslationFilter`, `AuthenticationEntryPoint.commence()`의 의미는 [Spring Security 인증·인가 구조](./spring-security-authentication-authorization.md)를 참고한다.

## 5. 통합 테스트에서 검증한 것

2026-10-23 기준 `JwtRequestAuthenticationIntegrationTest`: **7개 테스트 메서드, 총 8개 실행 사례 통과**. 잘못된 UUID `sub` 테스트를 두 입력으로 실행한다. PostgreSQL 테스트 환경과 Spring Security 필터를 포함한 `MockMvc` 통합 테스트다. **프로젝트 전체 테스트 40건 및 로컬 전체 빌드도 통과**했다(개발 환경에서 확인한 결과).

| 테스트 | 검증되는 사실 | 검증하지 않는 사실 |
|---|---|---|
| 보호 API, 토큰 없음 → 401 | 인증되지 않은 접근이 거부됨 | JWT 서명 검증 동작 자체 |
| 다른 Secret Key 서명 → 401 | 서명이 다른 토큰이 거부됨 | 만료·UUID 검증 |
| 실제 로그인 JWT → 200 + UUID | 로그인·발급·검증·Controller 연결 | 사용자별 도서 접근 권한 |
| 로그인 API + 잘못된 Bearer 토큰 → 401 | `permitAll()` 경로에서도 잘못된 토큰 거부 | Controller 미실행 여부 자체를 직접 단언한 것은 아님 |
| 정상 키로 서명한 만료 JWT → 401 | Decoder 만료 오류 및 HTTP 거부 | 60초 허용 오차의 정확한 경계 |
| `sub=invalid-user-id` → 401 | 잘못된 UUID 문자열 거부 | 사용자 존재 여부 |
| `sub=1-1-1-1-1` → 401 | 축약 표현을 거부하고 표준 자릿수 요구 | UUID 버전 제한 |
| `sub` 누락 → 401 | 필수 사용자 식별자 누락 거부 | 다른 Claim의 필수 여부 |

테스트 설계 참고:

- 401 응답은 `unauthorizedProblemDetail()`로 상태 코드, 인증 헤더, Content-Type, JSON의 `status`·`title`·`code`를 공통 검증한다.
- 만료 테스트는 실제 `JwtEncoder`와 `JwtDecoder`를 사용하고 `JwtValidationException`의 만료 메시지도 확인한다. 메시지 단언은 프레임워크 버전에 민감할 수 있다.
- `print()`는 인증 토큰이 로그에 노출될 수 있어 제거했다.
- 공개 API의 무토큰 접근은 기존 `LoginIntegrationTest`, `UserSignUpIntegrationTest`에서 확인하므로 중복하지 않았다.
- `MockMvc`가 실제 필터를 통과하도록 유지한다. `jwt()` 테스트 후처리기만 사용하면 실제 토큰 서명·Decoder 검증을 검증하지 못한다.
- Feature 3 검증 당시에는 업무용 보호 API가 없어 `@TestConfiguration`의 테스트 전용 Controller를 사용했다. 현재도 이 테스트는 인증 자체를 검증하며, Shelf·Book 업무 API의 소유권 검증은 각 기능의 통합 테스트에서 다룬다.

**통합 테스트는 내부 메서드 호출 순서보다 HTTP 요청부터 최종 응답까지의 동작을 검증한다.**

## 6. Feature 3 당시 마무리 확인

- [x] 기존 회원가입·로그인을 포함한 전체 회귀 테스트: 전체 40건 통과
- [x] 로컬 전체 빌드 성공(Spotless 검사 포함)
- [ ] 변경 사항 최종 리뷰, Draft PR 생성 및 GitHub Actions CI 확인

위 체크리스트는 Feature 3 당시 기록이다. 이후 JWT 인증을 Shelf·Book API에 적용하고 CI를 구축했다. 최신 기능 상태는 [로드맵](../../planning/roadmap.md), CI 구성은 [CI/CD 아키텍처](../../architecture/ci-cd.md)에서 관리한다. **로컬 검증 완료와 GitHub Actions CI 통과는 별개**다.

## 참고 자료

- [Spring Security — OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [Spring Security — JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)

> 5~6절의 테스트 수·실행 결과와 미완료 체크 항목은 2026-10-23 당시 기록이다. 이후 전체 테스트 수나 최신 CI 결과를 나타내지 않는다.
