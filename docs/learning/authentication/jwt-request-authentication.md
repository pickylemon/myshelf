# JWT 기반 API 요청 인증 — Feature 3

> MyShelf 학습 문서 · 기준: 2026-10-23  
> 범위: `Authorization: Bearer <JWT>`로 보호된 API에 접근할 때 Spring Security가 수행하는 인증  
> 관련 문서: [로그인 인증과 JWT 발급](./login-authentication-and-jwt-issuance.md), `docs/architecture/authentication.md`

## 1. 왜 로그인과 별도의 인증 과정이 필요한가?

Feature 2에서는 이메일·비밀번호가 맞는지 확인하고 JWT를 **발급**했다. Feature 3에서는 API 요청에 포함된 JWT를 **검증**하고, 그 요청을 인증된 사용자의 요청으로 처리한다.

```text
로그인 때: 이메일 + 비밀번호 → 인증 성공 → JWT 발급
API 요청 때: Bearer JWT → 토큰 검증 → 현재 요청에 인증 정보 설정
```

**매 요청마다 이메일과 비밀번호를 다시 검사하는 것은 아니다.** 이전 로그인 결과를 바탕으로 발급된 토큰이 유효한지 확인한다.

## 2. MyShelf의 JWT 요청 인증 흐름

```text
GET /test-only/principal
Authorization: Bearer <JWT>
   ↓
Spring Security Filter Chain
   ↓
BearerTokenAuthenticationFilter      ← 헤더에서 Bearer 토큰 추출
   ↓
BearerTokenAuthenticationToken       ← 아직 검증되지 않은 인증 요청
   ↓
AuthenticationManager               ← JWT를 처리할 Provider로 위임
   ↓
JwtAuthenticationProvider           ← JWT 인증 담당
   ↓
JwtDecoder (NimbusJwtDecoder)       ← HS256 서명 및 Claims 검증
   ↓
JwtAuthenticationToken              ← 검증 성공 후의 인증 결과
   ↓
SecurityContextHolder               ← 현재 요청에서 사용할 인증 정보
   ↓
Controller                           ← Authentication.getName()으로 sub 확인
```

이는 일반적인 Spring Security Servlet Resource Server의 처리 흐름이다. **각 단계가 모두 사용자가 직접 작성한 코드인 것은 아니다.** MyShelf는 `JwtDecoder`와 Security Filter Chain 설정을 제공하고, 나머지 주요 연결은 Spring Security가 수행한다.

### 단계별 역할

| 구성 요소 | 역할 |
|---|---|
| `SecurityFilterChain` | 어떤 요청이 인증되어야 하는지, 어떤 인증 기능을 사용할지 정한다. |
| `BearerTokenAuthenticationFilter` | HTTP 요청에서 Bearer Token을 추출한다. |
| `AuthenticationManager` | 해당 요청의 인증을 적합한 Provider로 위임한다. |
| `JwtAuthenticationProvider` | `JwtDecoder`를 이용하여 JWT 인증을 수행한다. |
| `JwtDecoder` | JWT 서명을 검증하고 필요한 Claims 유효성을 검사한다. |
| `JwtAuthenticationToken` | 검증이 끝난 토큰에 대한 인증 결과를 표현한다. |
| `SecurityContextHolder` | 현재 요청 처리 동안 인증 정보를 사용할 수 있게 한다. |

## 3. JWT 검증은 정확히 무엇을 하는가?

MyShelf는 발급과 검증 모두 `HS256`을 사용하며, **같은 Secret Key**를 사용한다.

### 서명 검증

토큰 서명이 해당 키로 만들어진 서명과 일치하는지 확인한다. 다른 키로 서명했거나 내용이 변조되었다면 인증에 실패한다.

### Claims 검증

| 항목 | 의미 | MyShelf에서 주의할 점 |
|---|---|---|
| `exp` | 만료 시각 | 만료된 토큰은 거부해야 한다. |
| `nbf` | 사용 시작 가능 시각 | 토큰에 있는 경우 관련 시간 검증 대상이다. |
| `iat` | 발급 시각 | 기록된다고 해서 미래 시각 등의 정책이 자동으로 모두 검증되는 것은 아니다. |
| `sub` | 사용자 식별자 | MyShelf는 UUID 문자열을 사용하나, 올바른 UUID인지 여부는 별도 검증 정책이 필요하다. |
| `iss`, `aud` | 발급자, 수신 대상 | 현재 MyShelf 토큰에 없으며, 해당 값을 검사하려면 발급·검증 정책을 함께 정해야 한다. |

> 일반적인 `JwtDecoder`의 기본 검증은 서명과 표준적인 시간 조건에 집중한다. 정확한 검증 범위는 등록한 `JwtDecoder`와 Validator 설정에 따라 결정된다. 만료 시각 검증에는 허용 시간 오차가 적용될 수도 있다.

**토큰이 유효하다 ≠ 현재 토큰을 보낸 사람이 원래 계정 소유자라는 것이 별도로 증명된다.** 일반적인 Bearer Token은 탈취되면 만료되기 전까지 악용될 수 있다. HTTPS와 안전한 토큰 보관이 중요한 이유다.

## 4. 무엇을 직접 구현했고, 무엇을 재사용했는가?

### MyShelf에서 구성한 부분

- `JwtConfig`: 발급과 검증에서 동일한 키를 쓰는 `SecretKey` 구성 및 HS256 `JwtDecoder` Bean 등록
- `SecurityConfig`: 보호 API에 인증 요구, `STATELESS`, `oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))` 설정
- 로그인 및 JWT 발급: Feature 2의 기존 구현 재사용

### Spring Security에서 제공하는 부분

- Bearer Token 추출
- 인증용 토큰 생성 및 `AuthenticationManager` 호출
- JWT 검증 Provider 연결
- 검증 결과를 `Authentication`으로 변환
- `SecurityContext`에 인증 결과 설정
- 인증 실패에 대한 기본 HTTP 응답 처리

**OAuth2 Resource Server를 사용한다고 외부 Google 로그인, OIDC, 별도 OAuth 인증 서버를 도입하는 것은 아니다.** MyShelf에서는 기존에 직접 발급한 JWT를 검증하는 표준 컴포넌트를 재사용한다.

## 5. 로그인 때와 무엇이 다른가?

| 비교 | Feature 2 로그인 | Feature 3 API 요청 |
|---|---|---|
| 시작 지점 | `LoginController`가 유스케이스 호출 | 보안 필터가 요청을 먼저 검사 |
| 제출하는 증명 | 이메일·비밀번호 | Bearer JWT |
| 인증 Provider | `DaoAuthenticationProvider` | `JwtAuthenticationProvider` |
| 실제 확인 대상 | 사용자 조회 + 비밀번호 일치 | JWT 서명 + 설정된 Claims 조건 |
| 인증 성공 뒤 | `AuthService`가 JWT 발급 | 요청의 `SecurityContext`에 인증 결과 설정 |
| 결과 | 토큰을 클라이언트에 반환 | 보호 API 실행 가능 |

**공통점:** 두 과정 모두 `AuthenticationManager`와 `AuthenticationProvider`라는 Spring Security 인증 구조를 활용한다. **차이점:** 인증에 사용되는 자격 증명과 Provider, 인증 성공 뒤 수행하는 일이 다르다.

## 6. 인증된 사용자 UUID는 어디서 얻는가?

현재 JWT의 `sub`에는 사용자 UUID 문자열이 담겨 있다.

Resource Server의 기본 JWT 인증에서는 `Authentication.getName()`이 보통 `sub` 문자열을 반환한다. 실제 통합 테스트에서 `GET /test-only/principal`의 반환값과 사용자 UUID가 일치함을 확인했다.

향후 업무 API에서는 웹 어댑터에서 인증된 UUID를 얻어 유스케이스에 전달한다. **Application/Domain 계층이 `Jwt`, `Authentication`, `SecurityContextHolder`에 의존하도록 만들 필요는 없다.**

또한 현재 구성은 서명과 시간 조건을 검사할 뿐, 토큰 소유자의 계정이 DB에 여전히 존재하는지 매번 조회하지 않는다. 로그인 상태와 별개로 **책의 소유권을 확인하는 인가**는 서재 기능에서 구현해야 한다.

## 7. 인증 실패하면 어떻게 되는가?

| 요청 | 기본 결과 | 이유 |
|---|---|---|
| 보호 API에 JWT 없이 요청 | HTTP 401 | 인증되지 않은 요청 |
| 다른 키로 서명한 JWT 전달 | HTTP 401 | 서명 검증 실패 |
| 만료된 JWT 전달 | HTTP 401 예상 | 만료 조건 위반 — 별도 테스트로 확인 예정 |
| 정상 JWT 전달 | HTTP 200 (테스트 API 기준) | 토큰 인증 성공 |
| 인증은 됐으나 별도의 접근 권한 부족 | HTTP 403 | 인가 실패; 관련 권한 규칙 도입 시 검증 |

기본 `BearerTokenAuthenticationEntryPoint`는 401과 `WWW-Authenticate` 응답 헤더를 제공한다. **기본 설정만으로 기존 `LoginControllerAdvice`의 `ProblemDetail` JSON이 자동 생성되지는 않는다.** 필터에서 일어난 실패는 Controller 예외 처리와 경로가 다르기 때문이다.

`WWW-Authenticate` 값은 Spring Security 버전 및 요청에 따라 단순 `Bearer`보다 긴 문자열일 수 있다. 테스트에서는 우선 헤더의 존재를 확인하고 실제 값을 관찰한다.

`permitAll()`은 인증 없는 접근을 허용하는 규칙이지, 명시적으로 첨부한 잘못된 Bearer 토큰의 검증까지 건너뛰라는 뜻은 아니다.

## 8. 작성한 통합 테스트가 검증한 범위

사용자가 작성·실행한 `JwtRequestAuthenticationIntegrationTest`의 세 테스트는 모두 통과했다고 보고되었다.

| 테스트 | 검증되는 사실 | 검증하지 않는 사실 |
|---|---|---|
| 토큰 없이 요청 → 401 | 보호 경로가 미인증 요청을 거부한다. | `JwtDecoder`가 서명을 검증하는지까지는 알 수 없다. |
| 다른 Secret Key로 서명한 토큰 → 401 | 실제 Bearer 요청이 서명 검증에 실패한다. | 만료 및 UUID 형식 검증까지 증명하지는 않는다. |
| 실제 로그인 JWT → 200 + UUID | 로그인 → 발급 → 검증 → Controller까지 연결된다. | 사용자별 도서 접근 권한 검증은 하지 않는다. |

이 테스트는 `MockMvc`로 실제 Spring Security 필터를 거치므로 **통합 테스트**다. 내부 메서드 호출 순서를 단정하기보다, 입력된 HTTP 요청에 대해 시스템이 어떤 결과를 내는지 검증한다.

- `@AutoConfigureMockMvc`에서 필터를 비활성화하지 않아야 한다.
- `jwt()` 테스트 후처리기만 사용하면 실제 JWT 문자열의 Decoder 검증까지 확인하는 테스트가 되지 않는다.
- 테스트용 보호 Controller는 `@TestConfiguration`으로 운영 API와 분리한다.

## 9. 다음 검증과 학습 계획

**기능 검증 보완**

1. 서명은 유효하지만 만료된 JWT가 거부되는지 검증한다.
2. 공개 API의 비인증 요청이 계속 허용되는지 확인한다.
3. `sub`가 없거나 UUID 형식이 잘못되었을 때의 정책을 정하고 검증한다.
4. 인증 실패 응답을 기존 `ProblemDetail`과 통일할지 결정한다.
5. 전체 테스트와 CI를 확인한다.

**디버거로 이해할 흐름**

1. `BearerTokenAuthenticationFilter`: 헤더에서 무엇을 꺼내는가?
2. `JwtAuthenticationProvider` → `JwtDecoder`: 인증 요청이 어디서 검증되는가?
3. `JwtAuthenticationToken`: 어떤 Principal이 만들어지는가?
4. `SecurityContextHolder`: Controller가 인증 정보를 어떻게 받는가?

## 참고 자료

- [Spring Security — OAuth 2.0 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [Spring Security — Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security — Servlet Authentication Architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html)

> 이 문서는 공유된 MyShelf 코드 분석 및 사용자가 보고한 통합 테스트 결과를 바탕으로 작성했다. 최신 저장소 전체나 테스트 로그를 직접 조회한 것은 아니므로 향후 설정 변경 시 함께 갱신한다.
