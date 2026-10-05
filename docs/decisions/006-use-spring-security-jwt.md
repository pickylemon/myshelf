# 006. Spring Security를 이용한 JWT Access Token 발급

## Context

로그인 성공 후 클라이언트가 이후 요청에서 인증 정보를 전달할 수 있도록
JWT 기반 Access Token을 발급해야 한다.

MyShelf는 이미 Spring Security를 사용하고 있으며,
향후 인증이 필요한 API에서도 다음과 같은 Bearer Token 기반 인증을 사용할 예정이다.

```text
Authorization: Bearer <access-token>
```

JWT 구현 방법으로는 다음과 같은 선택지가 있다.

```text
- Spring Security의 JwtEncoder / NimbusJwtEncoder 사용
- JJWT와 같은 별도 JWT 라이브러리 사용
- JWT 생성 로직을 직접 구현
```

JWT 생성과 검증은 보안과 관련된 저수준 구현이므로 직접 구현하지 않고,
검증된 라이브러리를 사용한다.

---

## Decision

JWT Access Token 발급에는 Spring Security에서 제공하는
`JwtEncoder`와 `NimbusJwtEncoder`를 사용한다.

Application 계층은 Spring Security의 JWT API에 직접 의존하지 않는다.

다음 Outbound Port를 정의한다.

```java
public interface AccessTokenIssuerPort {

    String issue(UUID userId);
}
```

`auth`의 Outbound Adapter가 해당 Port를 구현한다.

```text
AuthService
    ↓
AccessTokenIssuerPort
    ↑
JwtAccessTokenAdapter
    ↓
JwtEncoder
    ↓
NimbusJwtEncoder
```

이를 통해 Application 계층은

```text
"사용자 ID를 이용해 Access Token을 발급한다."
```

는 요구사항만 알고,
JWT 라이브러리와 서명 방식은 Adapter의 구현 세부사항으로 둔다.

---

## JWT 서명 방식

현재 Access Token의 서명 알고리즘으로 `HS256`을 사용한다.

HS256은 Secret Key를 사용하는 HMAC-SHA256 기반 서명 방식이다.

Java 암호화 API와 JWT에서 같은 방식을 다음과 같이 표현한다.

```text
Java Crypto
HmacSHA256

JWT / JOSE
HS256
```

따라서 다음 두 설정은 같은 서명 방식에 대응한다.

```java
new SecretKeySpec(keyBytes, "HmacSHA256");
```

```java
.algorithm(MacAlgorithm.HS256)
```

현재 MyShelf는 하나의 백엔드 애플리케이션이
Access Token 발급과 향후 Token 검증을 모두 담당할 예정이므로,
대칭키 방식인 HS256을 현재 요구사항에 적합한 선택으로 판단한다.

공개키/개인키 기반 서명이 필요한 요구사항이 생기기 전까지
RS256 등의 비대칭키 방식은 도입하지 않는다.

---

## Secret Key 관리

JWT 서명에 사용하는 Secret Key는 소스 코드에 직접 저장하지 않는다.

설정 파일에서는 환경변수를 참조한다.

```yaml
jwt:
  secret: ${JWT_SECRET}
  access-token-expiration: 30m
```

실제 Secret은 실행 환경의 `JWT_SECRET` 환경변수로 제공한다.

```text
application.yml
        ↓
JWT_SECRET 환경변수
```

Secret의 원본은 충분히 긴 랜덤 byte 데이터로 생성하고,
환경변수에 저장하기 쉽도록 Base64 문자열로 표현한다.

예:

```bash
openssl rand -base64 32
```

이 명령은 32byte, 즉 256bit의 랜덤 데이터를 생성하고
이를 Base64 문자열로 출력한다.

애플리케이션에서는 해당 문자열을 다시 byte 배열로 복원한다.

```java
byte[] keyBytes = Base64.getDecoder()
        .decode(properties.secret());
```

그리고 JWT 서명에 사용할 `SecretKey`로 변환한다.

```java
SecretKey secretKey =
        new SecretKeySpec(keyBytes, "HmacSHA256");
```

Base64는 Secret을 보호하기 위한 암호화 방식이 아니다.

```text
Random bytes
    ↓
Base64 encode
    ↓
환경변수에 문자열로 저장
    ↓
Base64 decode
    ↓
원래의 bytes
```

Secret 자체의 보안은 환경변수 및 실행 환경의 Secret 관리 방식으로 보장한다.

---

## JWT 설정 관리

JWT 관련 설정을 코드에 직접 작성하지 않고 `JwtProperties`로 관리한다.

```java
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenExpiration
) {
}
```

이를 통해 다음 설정을 하나의 타입으로 관리한다.

```text
jwt.secret
jwt.access-token-expiration
```

역할은 다음과 같이 구분한다.

```text
application.yml
→ 실제 설정값 정의

JwtProperties
→ 설정값을 Java 타입으로 표현

JwtConfig
→ 설정값을 이용해 JwtEncoder 구성

JwtAccessTokenAdapter
→ JwtEncoder를 이용해 실제 Access Token 발급
```

---

## JwtEncoder 구성

`JwtConfig`에서 Secret Key를 이용해 `JwtEncoder` Bean을 생성한다.

```java
@Bean
public JwtEncoder jwtEncoder(JwtProperties properties) {
    byte[] keyBytes = Base64.getDecoder()
            .decode(properties.secret());

    SecretKey secretKey =
            new SecretKeySpec(keyBytes, "HmacSHA256");

    return NimbusJwtEncoder.withSecretKey(secretKey)
            .algorithm(MacAlgorithm.HS256)
            .build();
}
```

`JwtAccessTokenAdapter`는 구체 구현체인 `NimbusJwtEncoder`에 직접 의존하지 않고
Spring Security의 `JwtEncoder` 인터페이스를 주입받는다.

```java
private final JwtEncoder jwtEncoder;
```

실제 Bean 구현체는 `JwtConfig`에서 결정한다.

```text
JwtAccessTokenAdapter
        ↓
JwtEncoder
        ↑
NimbusJwtEncoder
```

---

## Access Token Claim

현재 Access Token에는 인증에 필요한 최소한의 Claim만 포함한다.

```text
sub
→ 사용자 ID(UUID)

iat
→ Token 발급 시각

exp
→ Token 만료 시각
```

예를 들면 다음과 같은 의미를 가진다.

```text
{
    sub: "019...",
    iat: 발급 시각,
    exp: 만료 시각
}
```

현재 인증 과정에 필요하지 않은 다음 정보는 Token에 넣지 않는다.

```text
- 이메일
- 사용자 프로필
- 기타 사용자 데이터
```

JWT Payload는 암호화된 비밀 저장소가 아니기 때문에
민감하거나 불필요한 정보를 Token에 포함하지 않는다.

필요한 Claim은 실제 인증/인가 요구사항이 생길 때 추가한다.

---

## Access Token 만료시간

Access Token 만료시간은 코드에 고정하지 않고 설정값으로 관리한다.

```yaml
jwt:
  access-token-expiration: 30m
```

Token을 발급할 때 다음과 같이 사용한다.

```text
iat = 현재 시각
exp = 현재 시각 + access-token-expiration
```

이를 통해 환경 또는 정책에 따라 만료시간을 변경하더라도
JWT 발급 코드를 수정하지 않아도 된다.

---

## JJWT 대신 Spring Security JWT를 선택한 이유

JJWT 역시 Java에서 JWT를 생성하고 검증할 수 있는 라이브러리이므로
기술적으로 사용할 수 있다.

예를 들어 JJWT를 사용할 경우 JWT 생성 API가 비교적 직접적이고 간결할 수 있다.

하지만 MyShelf는 이미 Spring Security를 사용하고 있으며,
향후 Bearer Access Token을 이용한 인증도 Spring Security를 통해 처리할 예정이다.

현재 계획은 다음과 같다.

```text
현재
로그인 성공
    ↓
JwtEncoder
    ↓
JWT Access Token 발급


향후
Authorization: Bearer <token>
    ↓
Spring Security Resource Server
    ↓
JwtDecoder
    ↓
Token 검증 및 Authentication 생성
```

따라서 JWT 발급과 향후 검증을 같은 Spring Security 생태계 안에서 구성하기 위해
별도의 JJWT 의존성을 추가하지 않는다.

현재 요구사항에서는 두 JWT 구현 기술을 동시에 사용할 이유가 없다.

---

## Alternative: JJWT

고려 가능한 다른 방법은 JJWT를 사용하는 것이다.

```text
AuthService
    ↓
AccessTokenIssuerPort
    ↓
JjwtAccessTokenAdapter
    ↓
JJWT
```

이 방식도 Hexagonal Architecture 관점에서는 문제가 없다.

Application 계층은 동일한 `AccessTokenIssuerPort`만 사용하기 때문이다.

JJWT의 장점은 JWT 생성 API를 비교적 직접적으로 사용할 수 있다는 점이다.

하지만 현재 프로젝트에서는 다음 이유로 선택하지 않았다.

```text
- 이미 Spring Security를 사용 중
- 향후 JWT 검증도 Spring Security로 구현 예정
- JWT 라이브러리를 추가로 하나 더 사용할 필요가 없음
- 발급과 검증 기술을 동일한 생태계로 유지할 수 있음
```

Spring Security의 JWT 지원으로 해결하기 어려운 구체적인 요구사항이 생긴다면
그때 다른 라이브러리를 다시 검토한다.

---

## Trade-offs

### Spring Security에 대한 기술 의존성이 생긴다

`JwtAccessTokenAdapter`와 `JwtConfig`는 다음 Spring Security API에 의존한다.

```text
JwtEncoder
NimbusJwtEncoder
JwtClaimsSet
MacAlgorithm
```

하지만 해당 의존성은 `adapter/out/token` 내부에 한정한다.

Application 계층에서는 다음 Port만 알고 있다.

```java
AccessTokenIssuerPort
```

따라서 JWT 구현 기술이 변경되더라도
로그인 비즈니스 로직의 변경을 최소화할 수 있다.

---

### HS256은 Secret Key를 안전하게 공유해야 한다

HS256은 동일한 Secret Key를 이용해 서명과 검증을 수행한다.

따라서 Secret이 노출되면 유효한 Token을 임의로 생성할 수 있다.

Secret은 다음 원칙을 따른다.

```text
- Git에 저장하지 않는다.
- application.yml에 실제 값을 직접 작성하지 않는다.
- 환경변수 또는 배포 환경의 Secret 관리 기능을 사용한다.
- 충분히 긴 랜덤 값을 사용한다.
```

현재 단일 백엔드 구조에서는 이 방식의 단순성을 선택한다.

향후 여러 독립 서비스가 Token을 검증해야 하거나
서명 키와 검증 키의 권한을 분리해야 하는 요구사항이 생기면
비대칭키 방식 도입을 다시 검토한다.

---

## Consequences

로그인 성공 시 다음 흐름으로 Access Token을 발급한다.

```text
AuthService
    ↓
AccessTokenIssuerPort.issue(userId)
    ↓
JwtAccessTokenAdapter
    ↓
JwtClaimsSet 생성
    ↓
JwtEncoder
    ↓
HS256 서명
    ↓
JWT 문자열 반환
```

`AuthService`는 다음 내용을 알지 않는다.

```text
- JWT가 Nimbus 기반인지
- Secret Key가 어떻게 생성되는지
- Base64를 어떻게 처리하는지
- 어떤 JWT 라이브러리를 사용하는지
```

이러한 구현 세부사항은 `adapter/out/token` 내부에 둔다.

또한 JWT Secret과 만료시간은 외부 설정으로 관리한다.

---

## Current Scope

현재 결정의 범위는 JWT Access Token **발급**이다.

포함되는 기능은 다음과 같다.

```text
- JwtEncoder 구성
- HS256 서명
- Secret Key 외부 설정
- Access Token 발급
- sub / iat / exp Claim 생성
```

다음 기능은 현재 범위에 포함하지 않는다.

```text
- JWT 요청 인증
- JwtDecoder
- SecurityContext 생성
- Refresh Token
- Token 재발급
- Redis Token 저장
- 로그아웃 blacklist
```

Access Token 검증 및 보호된 API 인증은 별도 기능으로 구현한다.

---

## Summary

MyShelf의 JWT Access Token 발급은
Spring Security의 `JwtEncoder`와 `NimbusJwtEncoder`를 사용한다.

```text
AuthService
    ↓
AccessTokenIssuerPort
    ↑
JwtAccessTokenAdapter
    ↓
JwtEncoder
    ↓
NimbusJwtEncoder
```

현재 주요 결정은 다음과 같다.

```text
1. JWT 구현은 Application 계층에서 분리한다.

2. Spring Security의 JWT API를 사용하고
   별도의 JJWT 라이브러리는 추가하지 않는다.

3. 현재 서명 알고리즘은 HS256을 사용한다.

4. Secret은 랜덤 값으로 생성하고
   Base64 문자열 형태로 환경변수에서 관리한다.

5. Access Token에는 sub, iat, exp만 포함한다.

6. 만료시간은 외부 설정으로 관리한다.

7. 현재 범위에서는 Access Token 발급만 구현하고,
   검증과 Refresh Token은 별도 요구사항으로 분리한다.
```