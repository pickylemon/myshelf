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

## 도메인 모델링 원칙

MyShelf는 기술적 구조보다 서비스의 목적과 도메인 요구사항을 먼저 고려하여 핵심 모델을 정의한다.

제품 요구사항에서 출발한 도메인 모델링 과정과 헥사고날 아키텍처의 관계는 [도메인 모델링 원칙](domain-modeling-principles.md)에 정리한다.

---

## 주요 도메인

현재 구현된 기능 패키지는 다음과 같다. Feature 5의 도서 검색·등록·관리·Shelf별 목록은 구현했으며, 남은 테스트와 최종 검증은 진행 전이다.

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
- 로그인에 필요한 이메일 기반 사용자 조회

사용자 정보 수정·삭제 API는 아직 구현하지 않았다.

### book

외부 도서 검색과 사용자별 도서·서재 연결 기능을 담당한다.

주요 책임:

- 국립중앙도서관의 제목·저자·출판사 검색과 ISBN 중복 제거
- 검색 세션 기반 더 보기, Redis 상태 저장·만료
- 검색 응답에 본인의 등록 서재 정보 결합
- ISBN 캐시·외부 API에서 신규 도서정보 확보, UserBook 생성·재사용과 ShelfBook 연결
- UserBook의 공유 독서 상태와 ShelfBook별 메모 변경·다건 삭제
- Shelf별 도서 목록의 소유권 확인·JOIN 조회·기본 정렬

검색은 [도서 검색 설계](book-search.md), 등록 Writer의 트랜잭션과 조회 DTO 변환은 [도서 관리 구조](book-management.md)에 정리한다. 전체 도서·상세 조회와 사용자 지정 순서 변경은 Feature 6 범위다.

### shelf

Feature 4에서 사용자의 개인 Shelf CRUD와 소유권 검증을 구현했다.

주요 책임:

- 이름과 선택적 설명으로 Shelf 생성
- 인증된 사용자의 Shelf 목록 및 상세 조회
- 소유자의 Shelf 이름·설명 수정 및 Shelf 삭제
- 사용자별 이름 중복 방지, 타인 소유·미존재 Shelf의 404 처리

`ShelfController`의 `GET /shelves/{shelfId}/books`는 Book의 `ShelfBookListUseCase`를 직접 호출한다. ShelfService가 BookService를 다시 주입받지 않도록 하며, BookService는 ShelfGetUseCase로 서재 존재·소유권을 확인한다. Shelf 자체의 상세 응답과 도서 목록 응답은 분리한다.

### auth

인증과 인가를 담당한다.

주요 책임:

- 로그인
- JWT 발급 및 검증
- 인증된 요청의 사용자 식별 정보 제공 (JWT `sub`)
- Spring Security 연동

---

## 패키지 구조

각 도메인은 필요한 범위에서 다음 구조를 가진다.

```text
<domain>
├── domain
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
POST /users
      ↓
UserController
      ↓
UserSignUpUseCase
      ↓
UserService
      ↓
UserRepositoryPort
      ↓
UserPersistenceAdapter
      ↓
JPA Repository
      ↓
Database
```

### Shelf CRUD 흐름

```text
JWT 요청 인증 → ShelfController → Shelf*UseCase → ShelfService
    → ShelfRepositoryPort → ShelfPersistenceAdapter → ShelfJpaRepository → PostgreSQL
```

- `Shelf`: JPA에 의존하지 않는 record로 ID·이름·설명·소유자·생성/수정 시각을 담는다. 접근자는 `id()`, `userId()` 등이다. JPA Entity의 getter와 구분한다.
- `ShelfService`: 생성·조회·수정·삭제의 Input Port를 구현하고 부재·중복 정책을 처리한다.
- 웹 Adapter: JWT에서 사용자 UUID를 추출하고 DTO Validation, Command 및 `ShelfResponse` 변환을 담당한다. 응답에는 `userId`를 포함하지 않는다.
- 영속성 Adapter: Domain과 `ShelfEntity` 변환, 소유자 조건 조회, DB 제약조건 예외 변환과 수정·삭제 트랜잭션을 담당한다.

생성·수정은 이름 필수 및 최대 255자, 설명 선택 및 최대 255자를 검증한다. 수정 시 설명을 null로 전달하면 기존 설명을 제거한다. 생성은 201과 UUID, 목록·상세·수정은 200과 응답 데이터, 삭제는 본문 없는 204를 반환한다.

소유자 조건 조회 결과가 없으면 미존재·타인 소유를 구분하지 않고 404로 처리한다. 이름 중복은 409, 미인증은 401이다. 생성 시 사용자 FK 위반은 별도의 `INVALID_SHELF_OWNER(401)`로 처리하며 JWT 서명 오류와 구분한다. [접근 정책](../decisions/010-private-resource-access-policy.md)을 따른다.

`V2`는 사용자별 이름 UNIQUE, `V3`는 설명 길이, `V4`는 감사 시각 컬럼을 관리한다. ShelfBook 연결 데이터가 있는 삭제 정책·검증은 Feature 5에서 다룬다.

### Swagger / OpenAPI 현황

- `springdoc-openapi-starter-webmvc-ui:3.1.1`을 사용한다. Spring Boot `4.1.1`은 공식 호환 표의 지원 범위다.
- 기본 UI는 `/swagger-ui.html` (UI 리소스 `/swagger-ui/**`), JSON은 `/v3/api-docs`다. Security에서 이 경로들과 `/v3/api-docs/**`를 공개한다.
- `OpenApiConfig`의 `@SecurityScheme(name = "bearerAuth")`로 HTTP Bearer/JWT를 정의한다. ShelfController와 도서 검색·등록 Controller에 `@SecurityRequirement`를 적용했다.
- Shelf CRUD에는 주요 성공·오류 응답과 상태 코드를 보완했다. 신규 ShelfBook·UserBook 관리 Controller에는 인증 요구사항·응답 설명 어노테이션이 아직 없으므로 생성 명세를 점검해야 한다. Swagger 어노테이션 유무는 실제 Security 접근 통제와 별개다.
- 이번 문서 점검에서는 서버를 기동하거나 실제 UI·JSON을 조회하지 않았다. 과거 접속 실패를 현재 설정 미구현으로 해석하지 않는다.

후속 작업은 신규 관리 API의 실제 생성 명세 확인이다. 런타임에 결정되는 204 및 오류 응답은 필요시 `@ApiResponse`로 보완한다. JWT 주입 인자가 일반 요청값으로 노출되는지도 확인하며 자동 숨김이 적용되면 추가 어노테이션은 필요 없다.

API 계약은 코드 기반 OpenAPI를 기준으로 관리하고 별도 Markdown API 명세를 중복 작성하지 않는다. [공식 호환 표](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot), [인증 문서 설정](https://springdoc.org/#adding-api-information-and-security-documentation)을 참고한다.

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
→ book/adapter/out/redis (비활성 프로필 대체 구현은 adapter/out/memory)

Kafka Producer
→ adapter/out/messaging

Kafka Consumer
→ adapter/in/messaging

HTTP API
→ adapter/in/web

Spring Security
→ auth/adapter/in/security 및 global/config
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
- 트랜잭션 경계는 작업의 원자성과 예외 처리 시점을 기준으로 정한다. Shelf 수정·삭제는 Adapter, 도서 등록은 별도 Writer, 메모·상태·연결 삭제는 BookService 메서드에 둔다. 목록은 readOnly 트랜잭션이다. [실제 경계와 flush](../learning/database/jpa-update-and-transactions.md)를 참고한다.

---

## 향후 확장

프로젝트 진행에 따라 다음 요소를 추가할 예정.

```text
Redis 조회 캐시
→ 현재 검색 세션 저장과 별도로 후속 검토

Kafka
→ 비동기 도메인 이벤트 처리

운영 배포 / CD
→ 검증된 이미지 배포 및 운영 환경 구성
```

Docker Compose 기반 로컬 실행과 PostgreSQL·Flyway, GitHub Actions CI 및 GHCR 이미지 게시 기반은 이미 구성했다. 현재 브랜치의 CI 결과와 운영 배포 완료 여부는 별도로 확인한다.

Redis 검색 세션 저장소와 ISBN별 메타데이터 캐시는 구현했다. 후속 작업과 검증 현황은 [로드맵](../planning/roadmap.md#기능별-진행-상태)에서 관리한다.

현재 구조는 이러한 외부 기술이 추가되더라도
Domain과 Application 계층의 변경을 최소화할 수 있도록 구성한다.

