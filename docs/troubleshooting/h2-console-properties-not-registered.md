# H2ConsoleProperties Bean 미등록으로 로그인 통합 테스트 실패

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

`local` profile에서는 일반 API용 `SecurityFilterChain`과 H2 Console 전용 `SecurityFilterChain`이 함께 등록된다.

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

따라서 테스트 실행에 `application-local.yaml`이나 외부 `JWT_SECRET` 환경변수가 필요하지 않게 되었다.

## 정리

문제의 핵심은 `H2ConsoleProperties` 자체가 아니라 테스트가 Git에 포함되지 않은 로컬 설정에 의존하고 있었다는 점이다.

Profile을 활성화하는 것과 해당 Profile에서 기대하는 설정값이 실제로 존재하는 것은 별개의 문제다.

통합 테스트 실행에 반드시 필요한 설정은 개발자 개인의 로컬 환경에 기대지 않고 테스트 코드 또는 테스트 전용 설정에서 명시적으로 제공한다.

이를 통해 다른 개발 환경과 CI에서도 동일한 조건으로 테스트를 재현할 수 있다.