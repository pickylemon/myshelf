# Spring Boot Troubleshooting

## Spring Boot 4에서 H2 Console이 동작하지 않음
- 발생일: 2026-09-22
- 환경: Spring Boot 4.1.1 / Java 21 / H2 2.4.240

### 증상

`/h2-console` 접근 시 404가 발생하거나 다음 오류가 발생.

```text
NoClassDefFoundError:
org/springframework/boot/h2console/autoconfigure/H2ConsoleProperties
```

### 원인

Spring Boot 4에서는 H2 Console 자동설정이 별도 모듈로 분리되어 있다.

`com.h2database:h2` 의존성은 H2 데이터베이스 엔진만 제공하며,
Spring Boot의 H2 Console 자동설정은 포함하지 않아서 별도 추가 필요함.

### 해결

`build.gradle`에 H2 Console 모듈을 추가.

```gradle
implementation 'org.springframework.boot:spring-boot-h2console'
runtimeOnly 'com.h2database:h2'
```

---

## Spring Boot 4에서 Flyway Migration이 실행되지 않음
- 발생일: 2026-09-22
- 환경: Spring Boot 4.1.1 / Java 21 / Flyway / H2 2.4.240

### 증상

H2 데이터베이스는 정상적으로 실행되지만 `V1__init.sql`이 적용되지 않았고,
`flyway_schema_history` 테이블도 생성되지 않았음.

```sql
select * from flyway_schema_history;
```

실행 시 다음 오류가 발생했습니다.

```text
Table "FLYWAY_SCHEMA_HISTORY" not found
```

### 원인

`org.flywaydb:flyway-core`만 추가되어 있었고,
Spring Boot의 Flyway 자동설정 모듈이 포함되어 있지 않았다.

### 해결

`build.gradle`에 Spring Boot Flyway 모듈을 추가했다.

```gradle
implementation 'org.springframework.boot:spring-boot-flyway'
implementation 'org.flywaydb:flyway-core'
```

애플리케이션 재실행 후 Flyway가 H2에 연결되고
migration이 정상적으로 실행되는 것을 확인했다.

---

## 정리

Spring Boot 4에서는 외부 라이브러리 본체와 Spring Boot 자동설정 모듈이
분리되어 있는 경우가 있다.

라이브러리 의존성만 추가했는데 자동설정이 동작하지 않는다면,
해당 기능의 Spring Boot integration module 또는 starter가 별도로 필요한지 확인이 필요하다.

---

## `@RestController`의 value를 URL 경로로 착각

- 발생일: 2026-09-24
- 환경: Spring Boot 4.1.1 / Spring MVC

### 증상

다음과 같이 작성했지만 `/users`가 Controller의 기본 URL로 매핑되지 않았다.

```java
@RestController("/users")
public class UserController {
}
```

### 원인

`@RestController`의 `value`는 URL이 아니라 Spring Bean 이름이다.

### 해결

URL prefix는 `@RequestMapping`으로 지정한다.

```java
@RestController
@RequestMapping("/users")
public class UserController {
}
```

### 정리

`@RestController("...")`는 Bean 이름이고,
요청 URL은 `@RequestMapping`, `@GetMapping`, `@PostMapping` 등이 담당한다.

