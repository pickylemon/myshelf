# H2ConsoleProperties Bean 미등록으로 로그인 통합 테스트 실패

> 이 문서는 H2 Console 전용 보안 설정이 존재하던 당시의 장애와 해결 과정이다. 현재는 해당 보안 설정이 제거되었으며, 현재 테스트의 DB와 남은 설정은 아래 '현재 구성'에 구분한다.

## 문제

다른 PC에서 프로젝트를 pull한 뒤 `LoginIntegrationTest` 실행 시 다음 오류가 발생했다.

```text
No qualifying bean of type
'org.springframework.boot.h2console.autoconfigure.H2ConsoleProperties' available
```

테스트에서는 `local`, `test` profile을 함께 활성화하고 있었다.

```java
@ActiveProfiles({"local", "test"})
```

당시 `local` profile에서는 일반 API용 `SecurityFilterChain`과 H2 Console 전용 `SecurityFilterChain`이 함께 등록되었다.

H2 Console 전용 Security 설정이 `H2ConsoleProperties`를 필요로 했지만, 테스트 Context에는 해당 Bean이 존재하지 않았다.

## 원인

`application-local.yaml`은 Git 관리 대상에서 제외되어 있었고, 새 PC에는 해당 파일이 존재하지 않았다.

기존 개발 환경에서는 다음 설정을 통해 H2 Console이 활성화되어 있었다.

```yaml
spring:
  h2:
    console:
      enabled: true
```

그러나 새 환경에서는 `local` profile 이름만 활성화되고 실제 H2 Console 활성화 설정은 존재하지 않았다.

그 결과 H2 Console 자동 설정 조건이 충족되지 않았고, `H2ConsoleProperties`도 Bean으로 등록되지 않았다.

즉 테스트가 코드만으로 완결되지 않고 Git에 포함되지 않은 개발자 개인의 로컬 설정 파일에 암묵적으로 의존하고 있었다.

## 해결

로그인 통합 테스트에 필요한 H2 Console 활성화 조건을 테스트 자체에서 명시했다.

```java
@SpringBootTest(properties = "spring.h2.console.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class LoginIntegrationTest {
    // ...
}
```

`local` profile은 H2 Console용 `SecurityFilterChain`을 활성화하기 위해 유지하고, H2 Console 활성화 여부는 테스트 설정에서 직접 제공하도록 변경했다.

JWT secret은 `application-test.yaml`의 테스트 전용 값을 사용한다.

```yaml
jwt:
  # 테스트 전용 값
  secret: <test-secret>
```

이 조치로 H2 Console 활성화 설정과 JWT secret을 테스트에서 제공할 수 있게 되었다. 이것이 Datasource 등 `application-local.yaml`의 다른 설정에 대한 의존까지 제거한다는 의미는 아니다.

## 현재 구성

- PostgreSQL 전환 이후 커밋 `a3fddb0`에서 H2 Console 전용 `LocalSecurityConfig`를 제거했다. 현재는 일반 API용 `SecurityFilterChain`만 존재한다.
- `LoginIntegrationTest`는 여전히 `local`, `test` 프로필을 사용한다. 현재 `local` 프로필의 역할은 PostgreSQL 접속 정보 등 로컬 설정을 제공하는 것이며, H2 Console 전용 보안 설정을 활성화하는 것이 아니다.
- `application-test.yaml`은 JWT 테스트 키만 제공한다. DB 접속에는 `application-local.yaml`의 PostgreSQL 설정과 올바른 `DB_PASSWORD`가 필요하다. 관련 오류는 [Gradle 테스트 비밀번호 전달 문제](gradle-test-db-password-not-propagated.md)에 정리했다.
- 테스트의 `spring.h2.console.enabled=true`와 H2 관련 의존성은 아직 남아 있지만, 테스트 DB를 H2로 바꾸지는 않는다. 미사용 설정 및 의존성 정리는 별도 작업이다.

## 정리

문제의 핵심은 `H2ConsoleProperties` 자체가 아니라 테스트가 Git에 포함되지 않은 로컬 설정에 의존하고 있었다는 점이다.

Profile을 활성화하는 것과 해당 Profile에서 기대하는 설정값이 실제로 존재하는 것은 별개의 문제다.

통합 테스트 실행에 반드시 필요한 설정은 개발자 개인의 로컬 환경에 기대지 않고 테스트 코드 또는 테스트 전용 설정에서 명시적으로 제공한다.

이를 통해 다른 개발 환경과 CI에서도 동일한 조건으로 테스트를 재현할 수 있다.
