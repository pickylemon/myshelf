# 아키텍처 개요

## 개요

MyShelf 백엔드는 도메인 단위로 기능을 분리하고,
각 도메인 내부에 헥사고날 아키텍처를 적용한다.

프로젝트의 주요 목표는 다음과 같다.

- 도메인 로직과 외부 기술 의존성 분리
- 유스케이스 중심의 애플리케이션 설계
- Spring, JPA, Redis, Kafka 등의 기술 요소를 Adapter 영역으로 격리
- 기능 확장 시 변경 범위를 최소화할 수 있는 구조 구성

---

## 주요 도메인

초기 도메인은 다음과 같이 구성한다.

```text
user
book
shelf
auth
```

### user

사용자 정보와 회원 관련 기능을 담당한다.

주요 책임:

- 회원가입
- 사용자 정보 관리
- 사용자 조회

### book

도서 자체의 공통 정보를 담당한다.

주요 책임:

- 도서 정보 관리
- ISBN 기반 도서 식별
- 도서 조회

### shelf

사용자의 개인 서재와 도서 상태를 담당한다.

주요 책임:

- 사용자의 서재에 도서 등록
- 독서 상태 관리
- 사용자와 도서 간 관계 관리

`User`와 `Book`의 N:M 관계는 `UserBook`과 같은 관계 엔티티를 통해 관리한다.

### auth

인증과 인가를 담당한다.

주요 책임:

- 로그인
- JWT 발급 및 검증
- 인증 사용자 조회
- Spring Security 연동

---

## 패키지 구조

각 도메인은 필요한 범위에서 다음 구조를 가진다.

```text
<domain>
├── domain
│   └── model
│
├── application
│   ├── port
│   │   ├── in
│   │   └── out
│   └── service
│
└── adapter
    ├── in
    │   └── web
    └── out
        └── persistence
```

모든 하위 패키지를 미리 생성하지 않고,
실제 유스케이스와 외부 의존성이 발생할 때 필요한 구조만 추가한다.

---

## 요청 흐름

일반적인 HTTP 요청은 다음 흐름을 따른다.

```text
Client
  ↓
Inbound Adapter
Controller
  ↓
Input Port
UseCase
  ↓
Application Service
  ↓
Output Port
  ↓
Outbound Adapter
Persistence / Redis / Kafka
```

예) 회원가입 흐름

```text
POST /api/users
      ↓
UserController
      ↓
SignUpUseCase
      ↓
SignUpService
      ↓
SaveUserPort
      ↓
UserPersistenceAdapter
      ↓
JPA Repository
      ↓
Database
```

---

## 의존 방향

핵심 원칙은 외부 영역이 내부 영역을 의존하도록 구성하는 것이다.

```text
Adapter
   ↓
Application
   ↓
Domain
```

Domain은 Application이나 Adapter를 알지 않도록 구성한다.

Application은 구체적인 JPA Repository, Redis Client, Kafka Producer 등의
구현체를 직접 알지 않는다.

Adapter는 Application이 정의한 Port를 구현하거나 호출한다.

---

## 외부 기술 배치

외부 기술은 가능한 한 Adapter 또는 Config 영역에 위치시킨다.

```text
JPA / PostgreSQL
→ adapter/out/persistence

Redis
→ adapter/out/cache

Kafka Producer
→ adapter/out/messaging

Kafka Consumer
→ adapter/in/messaging

HTTP API
→ adapter/in/web

Spring Security
→ auth 또는 config/security
```

---

## 설계 원칙

- 도메인 모델은 Spring 및 JPA에 의존하지 않는다.
- JPA Entity와 Domain Model을 분리한다.
- Application Service는 유스케이스를 구현한다.
- 외부 시스템 접근은 Output Port를 통해 수행한다.
- 의미 없는 인터페이스를 미리 만들지 않는다.
- 실제 요구사항이 생길 때 Port와 Adapter를 추가한다.
- Controller에서 직접 Repository를 호출하지 않는다.
- Entity를 API 응답으로 직접 노출하지 않는다.
- 트랜잭션 경계는 Application Service를 중심으로 관리한다.

---

## 향후 확장

프로젝트 진행에 따라 다음 요소를 추가할 예정.

```text
Redis
→ 조회 캐시

Kafka
→ 비동기 도메인 이벤트 처리

Docker
→ 애플리케이션 및 인프라 실행 환경 구성

PostgreSQL
→ 운영 환경 데이터베이스
```

현재 구조는 이러한 외부 기술이 추가되더라도
Domain과 Application 계층의 변경을 최소화할 수 있도록 구성한다.