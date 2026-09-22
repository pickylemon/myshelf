# 헥사고날 아키텍처

## 목적

MyShelf는 핵심 비즈니스 로직이 Spring, JPA, 데이터베이스,
메시지 브로커와 같은 외부 기술에 직접 의존하지 않도록
헥사고날 아키텍처를 적용한다.

핵심 목표는 다음과 같다.

- 비즈니스 로직과 프레임워크 분리
- 외부 기술 변경에 대한 영향 최소화
- 유스케이스 중심의 코드 구조
- 테스트 가능한 Application / Domain 구성

---

## 기본 구조

```text
             Inbound Adapter
             Controller
                  │
                  ▼
              Input Port
                  │
                  ▼
          Application Service
                  │
                  ▼
              Output Port
                  │
                  ▼
            Outbound Adapter
        JPA / Redis / Kafka / API


                  │
                  ▼
               Domain
```

핵심 영역은 Domain과 Application이며,
Adapter는 외부 세계와 핵심 영역을 연결하는 역할을 한다.

---

## Domain

Domain은 비즈니스 규칙과 상태를 표현한다.

예:

```text
User
Book
UserBook
```

Domain은 다음 요소를 알지 않는다.

```text
Spring
JPA
HTTP
Redis
Kafka
PostgreSQL
```

예를 들어 Domain Model에 다음과 같은 JPA 의존성을 두지 않는 것을 원칙으로 한다.

```java
@Entity
@Table(name = "users")
```

대신 JPA 전용 Entity는 Persistence Adapter 영역에 둔다.

---

## Application

Application은 시스템이 제공하는 유스케이스를 정의하고 실행한다.

예:

```text
회원가입
로그인
내 서재에 도서 추가
내 서재 조회
```

Application은 크게 세 부분으로 나눈다.

```text
application
├── port
│   ├── in
│   └── out
└── service
```

---

## Input Port

Input Port는 외부에서 사용할 수 있는 유스케이스를 정의.

예:

```java
public interface SignUpUseCase {

    UserResult signUp(SignUpCommand command);
}
```

Controller는 구체적인 Service 구현체보다
Input Port를 통해 Application을 호출한다.

```text
Controller
    ↓
SignUpUseCase
    ↓
SignUpService
```

---

## Application Service

Application Service는 Input Port를 구현하고
유스케이스의 흐름을 제어한다.

예:

```text
회원가입 요청
    ↓
이메일 중복 확인
    ↓
비밀번호 암호화
    ↓
User 생성
    ↓
저장
```

Application Service는 필요한 외부 기능을
직접 구현하지 않고 Output Port를 통해 요청한다.

---

## Output Port

Output Port는 Application이 외부 시스템에 요구하는 기능을 정의한다.

예:

```text
SaveUserPort
LoadUserPort
PasswordEncoderPort
BookCachePort
BookEventPublisher
```

예:

```java
public interface SaveUserPort {

    User save(User user);
}
```

Application은 이 인터페이스만 알고,
실제 JPA 구현체를 알지 않는다.

---

## Inbound Adapter

Inbound Adapter는 외부 요청을 Application에서 이해할 수 있는 형태로 변환한다.

대표적으로 다음이 해당된다.

```text
REST Controller
Kafka Consumer
Scheduler
```

웹 요청의 경우:

```text
HTTP Request
    ↓
Request DTO
    ↓
Command
    ↓
Input Port
```

와 같은 흐름을 가진다.

---

## Outbound Adapter

Outbound Adapter는 Output Port를 구현하여
외부 시스템과 실제로 통신한다.

예:

```text
JPA
Redis
Kafka Producer
External API
```

Persistence Adapter 예:

```text
SaveUserPort
      ▲
      │ implements
      │
UserPersistenceAdapter
      │
      ▼
UserJpaRepository
```

---

## Persistence 구조

MyShelf에서는 Domain Model과 JPA Entity를 분리한다.

```text
Domain

User
```

```text
Persistence Adapter

UserJpaEntity
UserJpaRepository
UserPersistenceAdapter
```

변환 흐름은 다음과 같다.

```text
User
 ↓
UserJpaEntity
 ↓
Database
```

조회 시에는 반대로 변환한다.

```text
Database
 ↓
UserJpaEntity
 ↓
User
```

이렇게 하면 Domain이 JPA에 의존하지 않는다.

---

## 의존성 규칙

MyShelf에서 가장 중요한 규칙은 다음과 같다.

```text
외부 → 내부 의존 허용
내부 → 외부 의존 금지
```

허용되는 방향:

```text
adapter
   ↓
application
   ↓
domain
```

피해야 하는 방향:

```text
domain
   ↓
JpaRepository

application
   ↓
RedisTemplate

application
   ↓
KafkaTemplate
```

외부 기술이 필요한 경우 Port를 통해 의존성을 역전한다.

예:

```text
Application
     │
     ▼
BookEventPublisher
     ▲
     │ implements
     │
KafkaBookEventPublisher
```

---

## 패키지 구성 원칙

MyShelf는 계층 기준으로 전체 프로젝트를 나누기보다
도메인을 먼저 나누고 각 도메인 내부에 헥사고날 구조를 적용한다.

```text
com.myshelf
├── user
│   ├── domain
│   ├── application
│   └── adapter
│
├── book
│   ├── domain
│   ├── application
│   └── adapter
│
├── shelf
│   ├── domain
│   ├── application
│   └── adapter
│
└── auth
    ├── application
    ├── adapter
    └── config
```

도메인 단위로 코드를 모아
기능 변경 시 관련 코드를 한 영역에서 확인할 수 있도록 한다.

---

## 적용 원칙

헥사고날 아키텍처 자체를 목적으로 두지 않는다.

다음 기준을 따른다.

- 실제 유스케이스가 존재할 때 Input Port를 생성.
- 실제 외부 의존성이 존재할 때 Output Port를 생성.
- 구현체가 하나라는 이유만으로 모든 클래스에 인터페이스를 만들지 않는다.
- 단순 CRUD에도 불필요한 계층을 억지로 추가하지 않는다.
- 구조보다 책임과 의존 방향을 우선.

즉, 헥사고날 구조를 형식적으로 적용하기보다
외부 기술과 핵심 로직의 경계를 명확하게 만드는 데 집중하는 것이 목표다.