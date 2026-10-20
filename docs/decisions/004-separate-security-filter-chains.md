# 004. 일반 API와 Local H2 Console의 SecurityFilterChain을 분리한다

> 이 문서는 H2 Console을 사용하던 당시의 설계 결정과 구현을 보존한 기록이다. PostgreSQL 전환 이후 커밋 `a3fddb0`에서 `LocalSecurityConfig`를 제거했으며, 현재는 `SecurityConfig`의 단일 `SecurityFilterChain`을 사용한다. DB 전환 배경은 [ADR 009](009-migrate-local-db-to-postgresql.md)를 참고한다.

## Context

MyShelf에는 서로 다른 보안 요구사항이 있다.

일반 API는 회원가입과 로그인만 인증 없이 접근할 수 있어야 하고,
나머지 API는 인증된 사용자만 접근할 수 있어야 한다.

반면 Local 환경의 H2 Console은 개발 편의를 위해 인증 없이 접근해야 하며,
CSRF와 Frame 관련 별도 설정도 필요하다.

처음에는 `SecurityConfig`와 `LocalSecurityConfig`가 각각
모든 요청에 매칭되는 `SecurityFilterChain`을 가지고 있었다.

```text
SecurityConfig
→ 모든 요청

LocalSecurityConfig
→ 모든 요청
```

이렇게 두 개의 Filter Chain이 같은 요청 범위를 담당하면
각 설정의 역할이 불명확해진다.

## Decision

SecurityFilterChain의 역할을 다음과 같이 분리한다.

```text
LocalSecurityConfig
→ Local 환경에서만 생성
→ H2 Console 요청만 담당

SecurityConfig
→ 일반 MyShelf API 담당
```

### 일반 API SecurityConfig

일반 API는 다음 정책을 사용한다.

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}
```

접근 정책은 다음과 같다.

```text
POST /users
→ 인증 없이 접근 가능

POST /auth/login
→ 인증 없이 접근 가능

그 외 요청
→ 인증 필요
```

`/users/**` 전체를 공개하지 않고
`HTTP Method + Path`를 함께 지정하여 회원가입 요청만 공개한다.

MyShelf는 JWT를 Authorization Header로 전달하는
Stateless API를 인증 방식으로 사용할 예정이므로
Spring Security의 Session에 인증 상태를 저장하지 않는다.

현재 인증 전략에서는 CSRF 보호를 비활성화한다.

향후 인증 정보를 브라우저 쿠키를 통해 자동 전송하는 방식으로 변경할 경우
CSRF 정책을 다시 검토한다.

### Local H2 Console SecurityConfig

H2 Console은 Local 환경에서만 별도의 Filter Chain으로 처리한다.

```java
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(toH2Console())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );

        return http.build();
    }
}
```

`@Profile("local")`을 사용하여 Local 환경에서만 해당 설정을 활성화한다.

`securityMatcher(toH2Console())`을 사용하여
이 Filter Chain이 H2 Console 요청에만 적용되도록 범위를 제한한다.

`@Order(1)`을 지정하여 H2 Console 요청에 대해
H2 전용 Filter Chain을 일반 API Filter Chain보다 먼저 검사한다.

H2 Console은 Frame을 사용하므로
동일 Origin에서 Frame 사용을 허용한다.

## Request Flow

회원가입 요청:

```text
POST /users
    ↓
H2 Filter Chain 확인
    ↓
H2 요청이 아니므로 불일치
    ↓
일반 SecurityFilterChain
    ↓
POST /users permitAll
```

H2 Console 요청:

```text
/h2-console
    ↓
H2 Filter Chain 확인
    ↓
securityMatcher(toH2Console()) 일치
    ↓
H2 Filter Chain 적용
    ↓
permitAll
```

인증이 필요한 일반 API:

```text
GET /users/me
    ↓
H2 요청 아님
    ↓
일반 SecurityFilterChain
    ↓
공개 API에 해당하지 않음
    ↓
authenticated
```

## Reason

일반 API와 H2 Console은 보안 요구사항과 실행 환경이 다르다.

하나의 Filter Chain에 Local 전용 H2 설정까지 포함시키는 대신,
H2 Console이라는 명확한 요청 범위를 별도의 Filter Chain으로 분리한다.

각 Filter Chain이 담당하는 요청 범위를 명확하게 하여
서로의 설정이 충돌하지 않도록 한다.

## Trade-off

여러 개의 `SecurityFilterChain`을 사용하면
하나의 Filter Chain만 사용하는 것보다 설정을 이해하기 어렵다.

특히 다음 개념을 함께 이해해야 한다.

- Filter Chain 우선순위
- `securityMatcher`
- `requestMatchers`
- `@Order`

따라서 명확하게 다른 보안 정책이 필요한 경우에만
Filter Chain을 추가로 분리한다.

## Consequences

- 일반 API 보안 정책은 `SecurityConfig`에서 관리한다.
- H2 Console 관련 보안 설정은 `LocalSecurityConfig`에서 관리한다.
- `LocalSecurityConfig`는 `local` Profile에서만 활성화한다.
- H2 Filter Chain은 H2 Console 요청에만 매칭한다.
- 회원가입은 `POST /users`만 공개한다.
- 로그인은 `POST /auth/login`만 공개한다.
- 그 외 일반 API는 인증을 요구한다.
- JWT 기반 Stateless 인증 전략을 사용한다.
- 인증 방식이 변경되면 Session과 CSRF 정책을 함께 재검토한다.
