# Gradle 테스트 실행 시 PostgreSQL 비밀번호 인증 실패

## 1. 증상

IntelliJ에서 Gradle `test` 태스크를 실행했을 때 전체 32개 테스트 중 7개가 실패했다.

```text
FATAL: password authentication failed for user "myshelf"
```

Spring Boot의 `entityManagerFactory` 초기화 과정에서 Flyway가 PostgreSQL에 연결하지 못했다.

## 2. 원인

테스트에 다음 프로필이 적용되어 있었다.

```java
@ActiveProfiles({"local", "test"})
```

`application-local.yaml`은 PostgreSQL 비밀번호를 환경변수로 참조한다.

```yaml
spring:
  datasource:
    password: ${DB_PASSWORD}
```

하지만 IntelliJ의 **Spring Boot 실행 구성과 Gradle 테스트 실행 구성은 별개**다.

`MyshelfApplication` 실행 구성에는 `DB_PASSWORD`가 있었지만, Gradle `test` 실행 구성에는 등록되지 않았다.

따라서 테스트 실행 시 올바른 DB 비밀번호가 전달되지 않아 인증에 실패했다.

## 3. 해결

IntelliJ의 다음 메뉴에서 Gradle 테스트 실행 구성을 수정했다.

`Run → Edit Configurations → Gradle → myshelf [test]`

Environment variables에 다음 값을 추가했다.

```text
DB_PASSWORD=<로컬 PostgreSQL 비밀번호>
```

재실행 결과:

```text
32 tests passed
BUILD SUCCESSFUL
```

## 4. 학습 내용

- IntelliJ의 실행 구성마다 환경변수 설정이 독립적이다.
- PostgreSQL 컨테이너가 실행 중이어도 인증 정보가 올바르지 않으면 연결에 실패한다.
- CI에서도 테스트 실행 환경에 필요한 DB와 환경변수를 별도로 준비해야 한다.

**핵심:** 로컬 애플리케이션 실행 성공이 별도의 Gradle 테스트 실행 환경까지 보장하지는 않는다.