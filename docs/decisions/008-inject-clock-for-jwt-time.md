# 008. JWT 시간 생성에 Clock을 주입한다

## Context

`JwtAccessTokenAdapter`는 access token을 발급하면서 다음 시간 claim을 생성한다.

```text
iat
→ 토큰 발급 시각

exp
→ 토큰 만료 시각
```

기존 구현에서 `Instant.now()`를 직접 호출하면 현재 시간이 코드 내부에서 결정된다.

```java
Instant issuedAt = Instant.now();
```

이 경우 테스트에서는 실제 발급 시각을 직접 알 수 없기 때문에 토큰 발급 전후 시간을 측정하여 범위로 검증해야 했다.

```java
Instant before = Instant.now();

String token = jwtAccessTokenAdapter.issue(userId);

Instant after = Instant.now();

assertThat(decoded.getIssuedAt())
    .isBetween(before, after);
```

이 방식은 실행 시점과 환경에 의존하며 정확한 기대값을 지정할 수 없다.

또한 `iat`와 `exp`의 차이만 검증하는 경우 두 값이 잘못된 기준 시각에서 생성되어도 테스트를 통과할 수 있다.

```text
iat = 잘못된 과거 시각
exp = iat + 30분

→ 두 값의 차이는 여전히 30분
```

현재 시간 역시 애플리케이션 외부 환경에서 제공되는 값으로 보고 제어 가능한 의존성으로 다룰 필요가 있다.

## Decision

`JwtAccessTokenAdapter`가 `Instant.now()`를 직접 호출하지 않고 `Clock`을 주입받도록 변경한다.

```java
public class JwtAccessTokenAdapter {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public JwtAccessTokenAdapter(
        JwtEncoder jwtEncoder,
        JwtProperties jwtProperties,
        Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }
}
```

토큰 발급 시 현재 시간은 주입된 `Clock`으로부터 얻는다.

```java
Instant issuedAt = Instant.now(clock);
```

운영 환경에서는 실제 UTC 시간을 제공한다.

```java
@Bean
Clock clock() {
    return Clock.systemUTC();
}
```

테스트에서는 시간을 고정한다.

```java
private static final Instant FIXED_NOW =
    Instant.parse("2026-01-01T00:00:00Z");

Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
```

이를 통해 JWT claim을 정확한 값으로 검증한다.

```java
assertThat(decoded.getIssuedAt())
    .isEqualTo(FIXED_NOW);

assertThat(decoded.getExpiresAt())
    .isEqualTo(FIXED_NOW.plus(ACCESS_TOKEN_EXPIRATION));
```

JWT Decoder의 timestamp validation 역시 같은 고정 시간을 사용하도록 구성하여 테스트의 시간 기준을 하나로 통일한다.

```java
JwtTimestampValidator timestampValidator =
    new JwtTimestampValidator();

timestampValidator.setClock(clock);

jwtDecoder.setJwtValidator(timestampValidator);
```

## Testing Strategy

기존에는 다음 두 테스트가 필요했다.

```text
JWT의 sub / iat / exp 구성 검증

iat가 실제 현재 시간 근처인지 범위 검증
```

`Clock.fixed(...)`를 사용하면 하나의 테스트에서 다음 값을 모두 정확하게 검증할 수 있다.

```text
sub = userId

iat = FIXED_NOW

exp = FIXED_NOW + accessTokenExpiration
```

따라서 실제 시스템 시간을 사용하는 다음 형태의 테스트는 제거한다.

```java
Instant before = Instant.now();

token 발급

Instant after = Instant.now();

assertThat(issuedAt)
    .isBetween(before, after);
```

테스트 실행 시각이나 머신 상태와 무관하게 항상 동일한 결과를 얻을 수 있도록 한다.

## Consequences

### 장점

- JWT 시간 기반 테스트가 deterministic해진다.
- `iat`와 `exp`를 범위가 아니라 정확한 값으로 검증할 수 있다.
- 실제 시스템 시간에 의존하는 flaky test 가능성을 줄인다.
- 시간이라는 외부 의존성이 코드에 명시적으로 드러난다.
- 향후 시간 기반 정책이 추가되더라도 테스트에서 원하는 시각을 쉽게 구성할 수 있다.

### 단점

- `JwtAccessTokenAdapter` 생성자에 `Clock` 의존성이 추가된다.
- 운영 환경에서 사용할 `Clock` Bean 구성이 필요하다.

## 결론

현재 시간은 코드 내부에서 직접 읽는 값이 아니라 외부 환경 의존성으로 취급한다.

운영에서는 `Clock.systemUTC()`를 사용하고 테스트에서는 `Clock.fixed(...)`를 사용하여 동일한 production code를 서로 다른 시간 환경에서 실행한다.

이를 통해 JWT의 `iat`, `exp` 생성 로직을 더 정확하고 안정적으로 검증한다.