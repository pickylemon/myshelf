# HTTP 인증 헤더 — Authorization과 WWW-Authenticate

## 1. 두 헤더의 역할

HTTP에서는 클라이언트가 인증 정보를 전달하는 것과 서버가 인증 방식을 안내하는 것을 구분한다.

| 헤더 | 방향 | 역할 |
|---|---|---|
| `Authorization` | 클라이언트 → 서버 | 인증 정보 전달 |
| `WWW-Authenticate` | 서버 → 클라이언트 | 필요한 인증 방식 안내 |

두 헤더는 서로 대응하는 역할을 한다.

## 2. Authorization

클라이언트가 서버에 인증 정보를 전달할 때 사용하는 요청 헤더다.

MyShelf는 JWT 기반 Bearer 인증 방식을 사용한다.

```http
POST /shelves HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzI1Ni...
Content-Type: application/json

{
  "name": "개발 서적"
}
```

- `Authorization`: 인증 정보를 전달하는 HTTP 헤더
- `Bearer`: 토큰을 제시하는 인증 방식
- JWT: 실제 전달되는 토큰의 형식

Bearer는 JWT 자체를 의미하지 않는다.

## 3. WWW-Authenticate

서버가 클라이언트에게 필요한 인증 방식을 알려주는 응답 헤더다.

예를 들어 인증 없이 Shelf 생성 API를 호출하면 다음과 같이 응답할 수 있다.

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
```

의미는 다음과 같다.

- `401 Unauthorized`: 요청에 필요한 유효한 인증 정보가 없음
- `WWW-Authenticate: Bearer`: Bearer 방식의 인증이 필요함

HTTP 표준 RFC 9110에 따르면 서버가 401 응답을 반환할 때는 해당 리소스에 적용되는 인증 방식을 `WWW-Authenticate` 헤더로 제공해야 한다.

단, 이 헤더가 있다고 클라이언트가 자동으로 JWT를 발급받거나 요청을 재시도하는 것은 아니다.

## 4. Bearer 인증 실패 정보

`WWW-Authenticate`에는 인증 방식 외에 오류 정보도 포함할 수 있다.

예를 들어 만료된 토큰의 경우 다음과 같다.

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="invalid_token"
```

RFC 6750에 정의된 주요 오류는 다음과 같다.

| 오류 | 의미 | 일반적인 HTTP 상태 |
|---|---|---|
| `invalid_request` | 토큰 전달 방식 등 요청 형식에 문제가 있음 | 400 |
| `invalid_token` | 토큰이 만료되었거나 유효하지 않음 | 401 |
| `insufficient_scope` | 토큰에 필요한 권한 범위가 없음 | 403 |

인증 정보가 아예 없는 경우에는 일반적으로 `error` 없이 Bearer 인증 방식을 안내한다.

## 5. Spring Security에서의 처리

Spring Security는 인증 실패를 처리하기 위해 `AuthenticationEntryPoint`를 제공한다.

OAuth2 Resource Server에서는 `BearerTokenAuthenticationEntryPoint`가 인증 실패 응답의 상태 코드와 `WWW-Authenticate` 헤더를 설정할 수 있다.

일반적인 처리 흐름은 다음과 같다.

```text
클라이언트 요청
    ↓
Spring Security Filter Chain
    ↓
JWT 인증 실패
    ↓
AuthenticationEntryPoint
    ↓
401 + WWW-Authenticate 응답
```

### ControllerAdvice와의 차이

Spring Security 필터에서 발생한 인증 예외는 일반적인 ControllerAdvice에서 자동으로 처리되지 않는다.

따라서 인증 실패 응답을 `ProblemDetail` 형식으로 통일하려면 Security 계층의 인증 실패 처리도 별도로 구성해야 할 수 있다.

`ProblemDetail` 응답 본문과 `WWW-Authenticate` 응답 헤더는 서로 다른 역할이며 함께 사용할 수 있다.

## 6. MyShelf의 INVALID_SHELF_OWNER 사례

Shelf 생성 시 다음 상황이 발생할 수 있다.

```text
정상 로그인 후 JWT 발급
    ↓
사용자 레코드 삭제
    ↓
아직 만료되지 않은 JWT로 요청
    ↓
JWT 검증 성공
    ↓
Shelf INSERT
    ↓
fk_shelves_user 위반
```

이것은 JWT 자체의 서명·형식 오류가 아니라 **인증된 사용자 식별자와 현재 DB 상태가 일치하지 않는 상황**이다.

MyShelf에서는 이 상황을 `INVALID_SHELF_OWNER`로 구분하고 401을 반환하는 정책을 선택했다.

다만 다음 사항을 구분해야 한다.

- DB 외래 키 위반 자체가 곧 JWT 오류는 아니다.
- 모든 외래 키 위반을 401로 변환해서는 안 된다.
- 401 응답에는 `WWW-Authenticate: Bearer` 헤더를 포함한다.
- `invalid_token` 오류 코드는 JWT 자체의 검증 오류와 혼동하여 무조건 추가하지 않는다.

이 사례는 일반적인 JWT 인증 실패와 발생 위치가 다르므로, 별도의 예외 처리와 통합 테스트가 필요하다.

## 7. 핵심 정리

- `Authorization`은 클라이언트가 인증 정보를 전달하는 요청 헤더다.
- `WWW-Authenticate`는 서버가 인증 방식을 안내하는 응답 헤더다.
- 401 응답에는 적용 가능한 인증 방식의 `WWW-Authenticate` 헤더가 필요하다.
- 401은 인증 문제, 403은 주로 인증된 사용자의 권한 부족을 의미한다.
- Spring Security의 인증 필터에서 발생한 오류와 Controller 이후 발생한 오류는 처리 경로가 다르다.

### 참고 자료

- [RFC 9110 — HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html)
- [RFC 6750 — Bearer Token Usage](https://www.rfc-editor.org/rfc/rfc6750.html)
- [Spring Security — BearerTokenAuthenticationEntryPoint](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/server/resource/web/BearerTokenAuthenticationEntryPoint.html)
