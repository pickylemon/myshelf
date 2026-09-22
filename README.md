# MyShelf

개인 독서 기록 및 서재 관리 서비스입니다.

단순 CRUD 구현을 넘어 Spring Boot 기반 백엔드 설계와
헥사고날 아키텍처 적용을 통해 도메인과 외부 기술의 의존성을 분리하고,
인증/인가, 캐싱, 비동기 이벤트 처리, 컨테이너 기반 실행 환경을
직접 구성하며 백엔드 전반의 설계와 운영 관점을 학습하는 것을 목표로 합니다.

---

## 1. 주요 기능

- 회원가입 / 로그인
- JWT 기반 인증
- 도서 등록 / 조회 / 수정 / 삭제
- 내 서재 관리
- 독서 상태 관리
    - 읽고 싶은 책
    - 읽는 중
    - 완독
- 완독 기록 및 독서 통계
- 도서 조회 캐싱
- 비동기 이벤트 처리

---

## 2. 기술 스택

### Backend

- Java 21
- Spring Boot
- Spring Web
- Spring Security
- Spring Data JPA
- Validation
- Gradle

### Database / Infrastructure

- PostgreSQL
- Redis
- Apache Kafka
- Docker
- Docker Compose

### Test

- JUnit 5
- Spring Boot Test

---

## 3. Architecture

헥사고날 아키텍처를 기반으로 구성합니다.

```text
domain
├── model
└── ...

application
├── port
│   ├── in
│   └── out
└── service

adapter
├── in
│   └── web
└── out
    ├── persistence
    ├── redis
    └── kafka