# Docker Compose 환경변수와 Spring Boot 설정 우선순위 — MyShelf

## 1. 학습 배경

MyShelf는 Spring Boot 애플리케이션을 두 가지 방식으로 실행할 수 있도록 구성했다.

- IntelliJ에서 Spring Boot를 직접 실행
- Docker Compose에서 Spring Boot와 PostgreSQL을 함께 실행

두 방식 모두 `local` 프로필을 사용하지만 PostgreSQL에 접근하는 주소와 환경변수 전달 방식이 다르다.

```text
IntelliJ 실행 → localhost:5432
Docker 실행   → postgres:5432
```

또한 JWT 서명키와 데이터베이스 비밀번호를 Git에 추적되는 설정 파일에 직접 기록하지 않고, 실행 환경에서 전달하도록 변경했다.

이번 학습의 핵심은 다음 세 가지를 구분하는 것이다.

1. Docker Compose의 `.env` 변수 치환
2. 컨테이너에 전달하는 환경변수
3. Spring Boot의 설정 파일 및 환경변수 우선순위

---

## 2. Docker Compose의 .env와 environment

### 2.1. .env — Compose 변수 치환

프로젝트 루트의 `.env` 파일에 로컬 개발용 환경변수를 정의했다.

```dotenv
JWT_SECRET=<로컬 JWT 서명키>
DB_PASSWORD=<로컬 DB 비밀번호>
```

`.env`는 Docker Compose가 설정 파일의 변수를 치환할 때 사용하는 파일이다.

예를 들어 다음 표현이 있다.

```yaml
SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
```

Compose는 `${DB_PASSWORD}`를 자신의 변수 치환 환경에서 찾은 값으로 대체한다.

일반적인 실행에서는 프로젝트의 `.env` 파일을 사용할 수 있으며, 셸 환경변수도 변수 치환에 사용된다.

동일한 변수가 PowerShell 환경변수와 .env 파일에 모두 정의되어 있다면, Docker Compose는 PowerShell 환경변수의 값을 우선 사용한다. (변수 치환 우선순위)

즉, **`.env`에 변수를 작성했다는 사실만으로 그 변수가 모든 컨테이너에 자동 전달되는 것은 아니다.**

### 2.2. environment — 컨테이너 환경변수 전달

MyShelf의 `compose.yaml`에서는 다음과 같이 설정했다.

```yaml
services:
  app:
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/myshelf
      SPRING_DATASOURCE_USERNAME: myshelf
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET:?Set JWT_SECRET in .env or shell}

  postgres:
    environment:
      POSTGRES_DB: myshelf
      POSTGRES_USER: myshelf
      POSTGRES_PASSWORD: ${DB_PASSWORD}
```

이는 환경변수와 관련된 설정을 발췌한 것으로, 실제 Compose 파일에는 빌드, 포트, 볼륨, healthcheck 등의 설정도 포함되어 있다.

Compose는 `.env` 또는 셸에서 읽은 `DB_PASSWORD`를 두 곳에 전달한다.

- `app` 컨테이너: `SPRING_DATASOURCE_PASSWORD`
- `postgres` 컨테이너: `POSTGRES_PASSWORD`

즉, 하나의 변수를 이용해 애플리케이션과 PostgreSQL의 접속 비밀번호 설정을 일치시킨다.

```text
.env
 └── DB_PASSWORD
       │
       ▼
Docker Compose 변수 치환
       │
       ├── app 컨테이너
       │    └── SPRING_DATASOURCE_PASSWORD
       │
       └── postgres 컨테이너
            └── POSTGRES_PASSWORD
```

여기서 `DB_PASSWORD`는 Compose가 치환에 사용하는 변수 이름이고, `SPRING_DATASOURCE_PASSWORD`와 `POSTGRES_PASSWORD`는 각 컨테이너에 전달되는 환경변수 이름이다.

**Compose의 변수 치환과 컨테이너 환경변수 전달은 서로 다른 단계다.**

---

## 3. Spring Boot의 설정 우선순위

Spring Boot는 여러 설정 소스에서 속성을 읽는다.

MyShelf에서는 다음 설정 소스를 사용한다.

- `application.yaml`: 공통 설정
- `application-local.yaml`: 로컬 프로필 설정
- `application-prod.yaml`: 운영 프로필 설정
- 운영체제 환경변수: 실행 환경에서 전달하는 설정

### 3.1. 동일한 속성이 여러 곳에 정의된 경우

이번 프로젝트에서 관련 있는 우선순위는 다음과 같다.

```text
우선순위 높음
    │
    ▼
운영체제 환경변수
application-local.yaml
application.yaml
    │
    ▼
우선순위 낮음
```

Spring Boot는 동일한 속성이 여러 곳에 정의되어 있으면 우선순위가 높은 값을 적용한다.

`application-local.yaml`에는 다음 설정이 있다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myshelf
```

반면 Docker Compose에서는 다음 환경변수를 전달한다.

```yaml
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/myshelf
```

Spring Boot는 `SPRING_DATASOURCE_URL`을 `spring.datasource.url`에 대응하는 속성으로 인식한다.

따라서 Docker에서 실행할 때는 Compose가 전달한 JDBC URL이 적용된다.

**덮어쓴다는 것은 YAML 파일 자체가 수정된다는 의미가 아니다. 애플리케이션 실행 시 최종 설정값을 결정한다는 뜻이다.**

### 3.2. local 프로필과 환경변수의 관계

Compose에는 다음 설정이 있다.

```yaml
SPRING_PROFILES_ACTIVE: local
```

이 환경변수는 Spring Boot의 `local` 프로필을 활성화한다.

그렇다고 `application-local.yaml`에 있는 모든 설정이 반드시 최종 적용되는 것은 아니다.

```text
SPRING_PROFILES_ACTIVE=local
          │
          ▼
application-local.yaml 활성화
          │
          ▼
환경변수 등 다른 설정 소스와 우선순위 비교
          │
          ▼
최종 Spring Boot 설정 결정
```

실제 Docker 실행 로그에서도 다음 내용을 확인했다.

```text
The following 1 profile is active: "local"
Database JDBC URL [jdbc:postgresql://postgres:5432/myshelf]
```

즉, `local` 프로필이 활성화되어 있지만 JDBC URL에는 Docker 환경변수에서 전달한 주소가 적용되었다.

공식 문서: [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)

---

## 4. IntelliJ와 Docker의 환경변수 전달 방식

### 4.1. IntelliJ에서 직접 실행

IntelliJ의 Run Configuration에서 다음 환경변수를 설정했다.

```text
DB_PASSWORD=<로컬 DB 비밀번호>
JWT_SECRET=<로컬 JWT 서명키>
```

`application-local.yaml`은 다음처럼 구성했다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myshelf
    driver-class-name: org.postgresql.Driver
    username: myshelf
    password: ${DB_PASSWORD}
```

Spring Boot는 `${DB_PASSWORD}`를 실행 중인 Java 프로세스의 환경변수에서 읽는다.

```text
IntelliJ Run Configuration
        │
        └── DB_PASSWORD
                 │
                 ▼
         Spring Boot 프로세스
                 │
                 ▼
    application-local.yaml
      password: ${DB_PASSWORD}
```

IntelliJ는 프로젝트 루트의 `.env`를 기본적으로 자동으로 읽지 않는다.

따라서 직접 실행할 때는 Run Configuration을 통해 필요한 환경변수를 전달하도록 구성했다.

### 4.2. Docker Compose에서 실행

Docker에서는 `.env`의 값을 Compose가 읽고, `app` 컨테이너에 다음 환경변수로 전달한다.

```yaml
SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
```

Spring Boot는 `SPRING_DATASOURCE_PASSWORD`를 `spring.datasource.password`에 대응하는 속성으로 인식한다.

이 값은 `application-local.yaml`의 `password: ${DB_PASSWORD}`보다 우선 적용된다.

여기서 주의할 점은 **Docker 컨테이너에 `DB_PASSWORD`라는 이름의 환경변수를 직접 전달한 것은 아니라는 사실**이다.

Compose가 전달한 것은 `SPRING_DATASOURCE_PASSWORD`이고, Spring Boot는 이를 데이터베이스 비밀번호 속성에 직접 적용한다.

### 4.3. 두 실행 방식 비교

| 구분 | IntelliJ 직접 실행 | Docker Compose 실행 |
|---|---|---|
| Spring Profile | `local` | `local` |
| 환경변수 전달 주체 | IntelliJ | Docker Compose |
| 비밀번호 변수 | `DB_PASSWORD` | `SPRING_DATASOURCE_PASSWORD` |
| 비밀번호 적용 | YAML 플레이스홀더 참조 | Spring 속성에 직접 대응 |
| DB 접속 주소 | `localhost:5432` | `postgres:5432` |
| 설정 파일 | `application-local.yaml` | 동일한 파일 사용 |

두 방식 모두 비밀번호를 실행 환경에서 전달하지만, 전달 주체와 변수 이름이 다르다.

**공통된 목적은 애플리케이션 코드 및 Git에 추적되는 설정 파일과 실제 비밀정보를 분리하는 것이다.**

---

## 5. 비밀정보 관리

### 5.1. 비밀번호 하드코딩 제거

기존에는 `application-local.yaml`과 `compose.yaml`에 로컬 PostgreSQL 비밀번호를 직접 작성했다.

이 방식은 다음 문제가 있었다.

- 비밀번호가 Git 기록에 포함될 수 있다.
- 비밀번호 변경 시 여러 설정 파일을 수정해야 한다.
- 환경마다 서로 다른 자격 증명을 적용하기 어렵다.

이를 개선하기 위해 다음과 같이 변경했다.

| 위치 | 변경 내용 |
|---|---|
| `.env` | `DB_PASSWORD` 정의 |
| `compose.yaml` | `${DB_PASSWORD}` 참조 |
| `application-local.yaml` | `password: ${DB_PASSWORD}` |
| IntelliJ Run Configuration | `DB_PASSWORD` 환경변수 등록 |

JWT 서명키 역시 기존과 같이 환경변수로 관리한다.

이로써 로컬 환경의 비밀번호와 JWT 서명키를 Git에 추적되는 설정 파일에 직접 작성하지 않도록 구성했다.

### 5.2. .gitignore와 .dockerignore

`.env`는 Git에 추적되지 않도록 관리해야 한다.

```gitignore
.env
```

또한 `.dockerignore`에서도 제외한다.

```dockerignore
.env
.env.*
```

두 파일은 목적이 다르다.

| 파일 | 역할 |
|---|---|
| `.gitignore` | Git이 추적하지 않을 파일 지정 |
| `.dockerignore` | Docker 빌드 컨텍스트에서 제외할 파일 지정 |

`.dockerignore`에 `.env`를 추가했다고 해서 Git 추적까지 자동으로 제외되는 것은 아니다.

이미 Git에 추적 중인 파일이라면 `.gitignore`를 추가하는 것만으로 추적이 해제되지 않는다는 점도 주의해야 한다.

### 5.3. 필수 환경변수 검사

MyShelf에서는 JWT 서명키에 다음 문법을 사용한다.

```yaml
JWT_SECRET: ${JWT_SECRET:?Set JWT_SECRET in .env or shell}
```

`${변수:?오류 메시지}`는 Compose가 변수를 치환할 때 값이 없거나 비어 있으면 오류를 발생시킨다.

이를 통해 필수 설정의 누락을 조기에 확인할 수 있다.

```powershell
docker compose config --quiet
```

이 명령은 Compose 설정 해석을 확인할 뿐, Spring Boot나 PostgreSQL이 정상 실행된다는 사실까지 보장하지 않는다.

### 5.4. 환경변수 관리의 한계

환경변수로 비밀정보를 분리하는 것은 Git에 실제 값을 기록하지 않도록 하는 개선이다.

하지만 환경변수 자체가 완전한 비밀 저장소는 아니다.

- 컨테이너 환경변수는 적절한 권한이 있는 사용자나 관리 도구에서 확인될 수 있다.
- `docker compose config` 출력에 치환된 비밀정보가 표시될 수 있다.
- 터미널 출력이나 로그에 비밀정보가 노출될 수 있다.

따라서 운영 환경에서는 로컬 `.env`를 그대로 사용하기보다 별도의 자격 증명 관리 방식을 결정해야 한다.

### 5.5. PostgreSQL 초기화 비밀번호와 기존 데이터

PostgreSQL 공식 이미지의 `POSTGRES_PASSWORD`는 주로 데이터 디렉터리의 최초 초기화 과정에서 사용된다.

따라서 이미 초기화된 Named Volume을 유지하면서 Compose의 비밀번호 환경변수만 변경하더라도 PostgreSQL 내부 계정의 비밀번호가 자동으로 변경되는 것은 아니다.

기존 데이터베이스의 비밀번호를 변경하려면 PostgreSQL 계정 비밀번호를 별도로 변경하고 애플리케이션 접속 정보도 일치시켜야 한다.

**환경변수로 설정을 분리하는 작업과 실제 데이터베이스 계정의 비밀번호를 변경하는 작업은 구분해야 한다.**

---

## 6. MyShelf 적용 및 검증 결과

이번 작업에서 다음 구성을 적용했다.

- `.env`에 `JWT_SECRET`, `DB_PASSWORD` 정의
- `compose.yaml`에서 두 변수를 참조하도록 설정
- `application-local.yaml`의 비밀번호를 `${DB_PASSWORD}`로 변경
- IntelliJ Run Configuration에 `DB_PASSWORD`, `JWT_SECRET` 환경변수 설정

Docker Compose 통합 과정에서 다음 동작을 확인했다.

| 항목 | 결과 |
|---|---|
| JWT_SECRET 누락 시 설정 오류 발생 | 확인 |
| JWT_SECRET 설정 후 Compose 실행 | 성공 |
| Docker에서 `local` 프로필 활성화 | 확인 |
| Docker 환경변수의 JDBC URL 적용 | 확인 |
| PostgreSQL 연결 및 Flyway 검증 | 성공 |
| 회원가입 API | HTTP 201 |
| 중복 이메일 요청 | HTTP 409 |
| 로그인 및 JWT 발급 | 성공 |
| Compose 재생성 후 데이터 유지 | 확인 |

위 API 검증은 Docker Compose 통합 실행 과정에서 수행한 결과다.

DB 비밀번호 환경변수 분리 설정을 적용한 이후 다음 동작도 검증했다.

- Docker Compose에서 애플리케이션 실행 및 API 동작
- IntelliJ에서 Spring Boot 직접 실행
- 기존 회원 로그인 및 JWT 발급

위 Compose 통합 검증 당시에는 보호 API 인증을 구현하기 전이었다. 현재는 Feature 3의 요청 인증과 Shelf·Book 업무 API를 구현했으며, 최신 흐름은 [인증 아키텍처](../../architecture/authentication.md)를 참고한다.

---

## 7. 정리

이번 학습에서 중요한 것은 **환경변수를 누가 읽고, 누구에게 전달하는지 구분하는 것**이다.

```text
[Docker Compose 실행]

.env
  │
  ▼
Docker Compose
  │ 변수 치환
  ▼
compose.yaml의 environment
  │
  ▼
Spring Boot 컨테이너 환경변수
  │
  ▼
Spring Boot 설정에 적용


[IntelliJ 직접 실행]

IntelliJ Run Configuration
  │ 환경변수 전달
  ▼
Spring Boot 프로세스
  │
  ▼
application-local.yaml의 플레이스홀더 해석
  │
  ▼
Spring Boot 설정에 적용
```

최종적으로 다음 사항을 이해했다.

1. `.env`는 Compose가 변수 치환에 사용할 수 있는 파일이며, 컨테이너 환경변수와 동일한 개념은 아니다.
2. Compose의 `environment`는 컨테이너에 전달할 환경변수를 지정한다.
3. Spring Boot는 여러 설정 소스의 우선순위에 따라 최종 설정을 결정한다.
4. IntelliJ와 Docker는 환경변수를 전달하는 방식이 다르다.
5. 동일한 `local` 프로필을 사용하더라도 실행 환경에 따라 다른 JDBC URL을 적용할 수 있다.
6. 비밀번호를 환경변수로 분리하는 것과 데이터베이스 계정의 실제 비밀번호를 변경하는 것은 별개의 작업이다.

**결론적으로 MyShelf는 Spring Boot 설정 파일을 실행 환경과 분리하고, IntelliJ와 Docker Compose에서 필요한 환경변수를 각각 주입하는 방식으로 구성했다.**

관련 문서:

- `docker-compose-spring-boot-postgresql.md`: Spring Boot와 PostgreSQL 통합 실행
- `docker-compose-postgresql.md`: PostgreSQL 및 Named Volume 구성
- `docker-compose-app-startup-errors.md`: Compose 통합 중 발생한 오류와 해결 과정
