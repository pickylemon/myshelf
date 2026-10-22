# GitHub Container Registry 기반 이미지 게시 및 실행 검증

> 학습일: 2026-10-22
> 대상: MyShelf Docker 이미지의 GHCR 게시, 다운로드 및 실행 검증
> 관련 문서: [CI/CD 아키텍처](../../architecture/ci-cd.md), [GitHub Actions CI 학습](github-actions-ci.md)
> 관련 Issue: #15

## 1. 학습 목적

MyShelf는 GitHub Actions를 통해 Gradle 빌드·테스트와 Docker 이미지 빌드 검증을 자동화했다.

하지만 `verify-image`에서 Docker 이미지 생성에 성공하더라도 해당 이미지는 외부 Registry에 저장되지 않는다. GitHub-hosted Runner는 임시 실행 환경이므로 별도의 게시 작업 없이 빌드된 이미지를 다른 환경에서 재사용할 수 없다.

이에 따라 다음 목표로 GHCR(GitHub Container Registry) 게시 자동화를 추가했다.

- `main` 브랜치의 CI 성공 이후 Docker 이미지 자동 게시
- GitHub Actions에서 GHCR 인증 및 권한 관리
- 커밋 SHA 기반 이미지 태그 사용
- 게시된 이미지를 로컬 Docker 환경으로 다운로드
- 다운로드한 이미지로 Spring Boot 애플리케이션 실행
- PostgreSQL 연결 및 실제 API 동작 검증

이번 작업의 핵심은 **CI에서 빌드한 이미지를 Registry에 게시하고, 다른 환경에서 소스 코드를 다시 빌드하지 않고도 해당 이미지를 실행할 수 있는지 확인하는 것**이다.

운영 서버 자동 배포는 이번 작업에 포함하지 않았다.

## 2. Container Registry와 GHCR

### 2.1 Container Registry란?

Container Registry는 Docker 이미지를 저장하고 배포할 수 있도록 제공하는 저장소다.

Git 저장소가 소스 코드를 관리한다면, Container Registry는 빌드된 컨테이너 이미지를 관리한다.

기본적인 흐름은 다음과 같다.

```text
소스 코드 + Dockerfile
          |
          | docker build
          v
     Docker Image
          |
          | docker push
          v
   Container Registry
          |
          | docker pull
          v
    다른 실행 환경
          |
          | docker run
          v
     Docker Container
```

Registry를 사용하면 개발자 PC나 배포 서버에서 Dockerfile을 이용해 매번 이미지를 다시 빌드할 필요가 없다.

이미지 생성과 실행을 분리할 수 있다는 것이 중요한 장점이다.

### 2.2 GHCR을 선택한 이유

GHCR은 GitHub가 제공하는 Container Registry다.

MyShelf는 이미 GitHub에서 소스 코드와 Pull Request를 관리하고 있으며, GitHub Actions로 CI를 구축했다.

따라서 GHCR을 사용하면 기존 GitHub Actions Workflow에서 이미지 게시까지 연결하기 쉽다.

특히 GitHub Actions에서 제공하는 `GITHUB_TOKEN`을 이용해 별도의 개인 토큰 없이 이미지를 게시할 수 있다.

이번 작업에서 사용한 이미지 주소는 다음과 같다.

```text
ghcr.io/pickylemon/myshelf
```

이미지 주소는 Registry, 소유자, 이미지 이름으로 구성된다.

| 구성 | 값 | 의미 |
|---|---|---|
| Registry | `ghcr.io` | GitHub Container Registry |
| 소유자 | `pickylemon` | GitHub 사용자 |
| 이미지 이름 | `myshelf` | MyShelf 애플리케이션 |

## 3. GitHub Actions에서 GHCR 게시 자동화

### 3.1 `publish-image` Job 구성

기존 GitHub Actions Workflow에 `publish-image` Job을 추가했다.

```yaml
publish-image:
  name: publish-image
  needs: build-and-test
  if: github.event_name == 'push' && github.ref == 'refs/heads/main'
  runs-on: ubuntu-latest

  permissions:
    contents: read
    packages: write

  steps:
    - name: Checkout repository
      uses: actions/checkout@v7

    - name: Set up Docker Buildx
      uses: docker/setup-buildx-action@v3

    - name: Log in to GHCR
      uses: docker/login-action@v3
      with:
        registry: ghcr.io
        username: ${{ github.actor }}
        password: ${{ secrets.GITHUB_TOKEN }}

    - name: Build and push Docker image
      uses: docker/build-push-action@v6
      with:
        context: .
        push: true
        tags: ghcr.io/${{ github.repository }}:sha-${{ github.sha }}
```

주요 설정은 다음과 같다.

| 설정 | 의미 |
|---|---|
| `needs: build-and-test` | 기존 빌드·테스트 성공 이후 실행 |
| `if` | `main` Push 이벤트에서만 실행 |
| `packages: write` | GHCR 패키지 게시 권한 |
| `docker/setup-buildx-action` | Docker 이미지 빌드 환경 구성 |
| `docker/login-action` | GHCR 인증 |
| `docker/build-push-action` | Docker 이미지 빌드 및 게시 |
| `push: true` | 이미지를 Registry에 게시 |
| `tags` | 게시할 이미지 이름과 태그 지정 |

### 3.2 PR 검증과 main 게시를 분리한 이유

MyShelf의 이미지 관련 Job은 다음과 같이 구분했다.

```text
[Pull Request]

build-and-test
      |
      v
 verify-image
      |
      v
 Docker 이미지 빌드 검증
 Registry 게시 없음


[main Push]

build-and-test
      |
      v
 publish-image
      |
      v
 Docker 이미지 빌드
      |
      v
     GHCR 게시
```

`verify-image`와 `publish-image`는 모두 `build-and-test`에 의존하지만 서로 다른 이벤트에서 실행된다.

PR에서는 이미지 생성 가능 여부를 검증하고, `main`에서는 해당 커밋을 기반으로 이미지를 새로 빌드해 게시한다.

이렇게 하면 PR 단계에서는 Registry에 불필요한 이미지를 게시하지 않으면서 Docker 빌드 오류를 확인할 수 있다.

다만 PR에서 빌드한 이미지와 `main`에서 게시한 이미지는 별도 빌드 결과물이므로 동일한 이미지라고 보장할 수는 없다.

### 3.3 `GITHUB_TOKEN`과 게시 권한

GitHub Actions에서 GHCR에 로그인할 때 다음 표현식을 사용했다.

```yaml
username: ${{ github.actor }}
password: ${{ secrets.GITHUB_TOKEN }}
```

- `github.actor`: Workflow 실행을 유발한 GitHub 사용자
- `secrets.GITHUB_TOKEN`: GitHub Actions가 자동으로 제공하는 인증 토큰

`GITHUB_TOKEN`은 Workflow 실행을 위해 제공되는 토큰이므로 별도로 GitHub Secrets에 생성할 필요가 없다.

하지만 자동 발급된 토큰이라고 해서 모든 권한을 갖는 것은 아니다.

GHCR 게시를 위해 `publish-image` Job에 다음 권한을 설정했다.

```yaml
permissions:
  contents: read
  packages: write
```

`contents: read`는 저장소 내용을 읽기 위한 권한이고, `packages: write`는 GHCR에 패키지를 게시하기 위한 권한이다.

이번에는 **게시 권한을 필요한 Job에만 부여하는 방식**을 사용했다.

### 3.4 커밋 SHA 기반 태그

게시 이미지 태그는 다음 표현식으로 생성한다.

```yaml
tags: ghcr.io/${{ github.repository }}:sha-${{ github.sha }}
```

`github.repository`는 저장소의 소유자와 이름이며, `github.sha`는 Workflow가 실행되는 커밋의 SHA다.

이번에 실제 게시한 이미지 태그는 다음과 같다.

```text
ghcr.io/pickylemon/myshelf:sha-5aa7329e6209b25ddaa43da2e9fdd9b8e0057899
```

커밋 SHA를 태그에 포함하면 이미지가 어느 소스 코드 커밋을 대상으로 빌드되었는지 추적하기 쉽다.

다만 **태그 자체가 이미지의 불변성을 보장하지는 않는다.**

동일한 태그를 다시 게시할 수도 있기 때문에, 정확히 동일한 이미지의 재사용을 보장하려면 이미지 Digest를 기준으로 식별하는 방식이 더 적합하다.

## 4. GHCR 게시 결과 확인

PR #16을 `main`에 병합한 이후 GitHub Actions에서 다음 결과를 확인했다.

| Job | 결과 |
|---|---|
| `build-and-test` | 성공 |
| `verify-image` | Skipped |
| `publish-image` | 성공 |

`verify-image`는 PR 이벤트에서만 실행하므로 `main` Push에서 Skipped 처리되는 것이 정상이다.

`publish-image`는 선행 Job의 성공 이후 실행되어 GHCR에 Docker 이미지를 게시했다.

GitHub Packages 화면에서 `myshelf` 패키지와 커밋 SHA 기반 태그가 생성된 것을 확인했다.

게시된 패키지의 접근 상태는 **Private**이었다.

이를 통해 GitHub Actions에서 이미지 빌드, GHCR 인증 및 게시까지 정상적으로 동작함을 확인했다.

## 5. Private 이미지 다운로드

### 5.1 GHCR 이미지 Pull

게시된 이미지를 로컬 환경에서 다운로드하기 위해 다음 명령어를 사용했다.

```powershell
docker pull ghcr.io/pickylemon/myshelf:sha-5aa7329e6209b25ddaa43da2e9fdd9b8e0057899
```

하지만 처음에는 다음 오류가 발생했다.

```text
Error response from daemon: error from registry: unauthorized
```

처음에는 GHCR 게시가 잘못되었는지 의심할 수 있지만, 원인은 Registry에 접근할 인증 정보가 없었기 때문이다.

GHCR의 Private 패키지는 다운로드할 때도 접근 권한이 필요하다.

### 5.2 GitHub Actions 인증과 로컬 인증의 차이

GitHub Actions에서는 `GITHUB_TOKEN`으로 GHCR에 로그인했다.

하지만 개발자 PC의 Docker는 GitHub Actions 실행 환경이 아니므로 동일한 토큰을 자동으로 제공받지 않는다.

| 환경 | 인증 방식 | 목적 |
|---|---|---|
| GitHub Actions | `GITHUB_TOKEN` | GHCR 이미지 게시 |
| 로컬 Docker | Personal Access Token (classic) | Private 이미지 다운로드 |

로컬 다운로드를 위해 GitHub의 Personal Access Token (classic)을 생성했다.

토큰 생성 시 설정한 내용은 다음과 같다.

- Note: `myshelf-ghcr-pull`
- Expiration: 임시 검증에 필요한 기간
- Scope: `read:packages`

이번 검증에서는 이미지를 읽는 작업만 필요하므로 `write:packages` 권한을 부여하지 않았다.

토큰은 인증 수단이므로 Git 저장소나 학습 문서에 실제 값을 기록하지 않는다.

### 5.3 Docker 로그인

PowerShell에서 다음 명령어를 실행했다.

```powershell
docker login ghcr.io -u pickylemon
```

비밀번호 입력 단계에서 GitHub 계정 비밀번호가 아닌 생성한 Personal Access Token을 입력했다.

로그인에 성공한 뒤 `docker pull`을 다시 실행하자 이미지가 정상적으로 다운로드되었다.

로컬 이미지 목록은 다음 명령어로 확인했다.

```powershell
docker images
```

여기서 기존 로컬 빌드 이미지인 `myshelf-app:local`과 GHCR에서 다운로드한 이미지는 서로 다른 이미지 참조다.

이번 검증에서는 **반드시 GHCR에서 다운로드한 이미지 태그를 지정하여 실행**했다.

## 6. GHCR 이미지 실행

### 6.1 Dockerfile과 Docker Compose의 역할

이번 작업에서 Dockerfile과 Docker Compose의 역할 차이를 다시 확인했다.

| 구분 | Dockerfile | Docker Compose |
|---|---|---|
| 목적 | 이미지 빌드 정의 | 컨테이너 실행 구성 |
| 주요 내용 | 베이스 이미지, 파일 복사, 실행 명령어 | 서비스, 네트워크, 볼륨, 환경변수 |
| 현재 CI | 이미지 빌드에 사용 | 사용하지 않음 |
| 현재 로컬 | 애플리케이션 이미지 빌드에 사용 가능 | 컨테이너 실행 및 DB 구성에 사용 |

GitHub Actions의 `verify-image`와 `publish-image`는 프로젝트 루트의 Dockerfile을 사용한다.

반면 PostgreSQL 통합 테스트는 Docker Compose가 아닌 GitHub Actions의 `services` 설정을 사용한다.

현재 GHCR에 게시된 이미지는 다음 구성요소를 포함한다.

```text
MyShelf Docker Image
  ├─ Java 21 Runtime
  ├─ Spring Boot 실행 JAR
  └─ 애플리케이션 실행 명령어
```

PostgreSQL 컨테이너, Docker Compose 네트워크, 볼륨, `.env` 파일 등은 최종 애플리케이션 이미지에 포함되지 않는다.

멀티스테이지 Dockerfile의 Builder Stage에서는 빌드 컨텍스트의 파일을 복사하지만, 최종 Runtime Stage에는 생성된 JAR만 복사하도록 구성되어 있기 때문이다.

따라서 애플리케이션을 실행할 때 필요한 DB 연결과 네트워크 설정은 외부에서 제공해야 한다.

Docker Compose는 로컬 전용 도구가 아니며, 향후 운영 서버에서도 GHCR 이미지를 실행하는 데 활용할 수 있다.

### 6.2 PostgreSQL 컨테이너 준비

먼저 프로젝트 루트에서 다음 명령어를 사용해 PostgreSQL을 실행했다.

```powershell
docker compose up -d postgres
```

이는 로컬 Compose 설정을 이용해 PostgreSQL 컨테이너를 실행하는 명령어다.

PostgreSQL 컨테이너가 실행 중인지 확인할 때는 다음 명령어를 사용한다.

```powershell
docker compose ps
```

전체 Docker 컨테이너는 다음 명령어로 확인할 수 있다.

```powershell
docker ps -a
```

`docker compose ps`는 현재 Compose 프로젝트의 컨테이너를 조회하고, `docker ps -a`는 Compose 외부에서 생성한 컨테이너까지 포함하여 조회한다.

이후 GHCR 애플리케이션은 Compose로 실행하지 않고 별도의 `docker run` 명령어로 실행했다.

### 6.3 Docker Compose의 기본 네트워크

PostgreSQL 실행 과정에서 `myshelf_default` 네트워크가 생성되었다.

처음에는 직접 정의하지 않은 네트워크 이름이라 혼동했지만, Docker Compose가 프로젝트 기본 네트워크를 자동 생성한 것이었다.

일반적인 기본 네트워크 이름 규칙은 다음과 같다.

```text
<Compose 프로젝트 이름>_default
```

MyShelf에서는 다음 네트워크를 사용했다.

```text
myshelf_default
```

네트워크 목록과 설정은 다음 명령어로 확인할 수 있다.

```powershell
docker network ls
docker network inspect myshelf_default
```

이번 검증에서는 `docker run`으로 생성하는 애플리케이션 컨테이너를 이 네트워크에 연결했다.

```text
myshelf_default
  |
  ├── PostgreSQL 컨테이너
  |     └── postgres:5432
  |
  └── MyShelf 애플리케이션 컨테이너
        └── Spring Boot:8080
```

같은 사용자 정의 Docker 네트워크에 연결되면 애플리케이션에서 Compose 서비스 이름인 `postgres`를 호스트 이름으로 사용할 수 있다.

### 6.4 `docker run` 실행 명령어

실제 실행에는 다음 명령어를 사용했다.

```powershell
docker run --rm --name myshelf-ghcr-check `
  --network myshelf_default `
  -p 8081:8080 `
  --env-file .env `
  -e SPRING_PROFILES_ACTIVE=local `
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/myshelf `
  ghcr.io/pickylemon/myshelf:sha-5aa7329e6209b25ddaa43da2e9fdd9b8e0057899
```

PowerShell의 각 줄 끝에 있는 백틱(`)은 명령어를 다음 줄까지 이어서 작성한다는 의미다.

주요 옵션은 다음과 같다.

| 옵션 | 의미 |
|---|---|
| `docker run` | 이미지로 컨테이너 생성 및 실행 |
| `--rm` | 컨테이너 종료 시 자동 삭제 |
| `--name` | 컨테이너 이름 지정 |
| `--network` | 사용할 Docker 네트워크 지정 |
| `-p 8081:8080` | 호스트 8081 포트를 컨테이너 8080 포트에 연결 |
| `--env-file .env` | 파일의 환경변수를 컨테이너에 전달 |
| `-e` | 개별 환경변수 지정 |
| 마지막 이미지 주소 | 실행할 GHCR 이미지 선택 |

이번에는 `-d` 옵션을 사용하지 않았다.

따라서 컨테이너를 포그라운드에서 실행하고 Spring Boot 로그를 터미널에서 직접 확인할 수 있었다.

백그라운드 실행이 필요하다면 `-d` 옵션을 사용할 수 있으며, 별도 터미널에서 `docker logs`로 로그를 확인할 수 있다.

### 6.5 포트 매핑과 DB 연결 주소

다음 옵션은 PC와 애플리케이션 컨테이너 사이의 HTTP 연결을 설정한다.

```powershell
-p 8081:8080
```

호스트의 `localhost:8081`로 요청하면 컨테이너 내부의 8080 포트로 전달된다.

```text
로컬 PC
localhost:8081
      |
      v
MyShelf 컨테이너
8080
```

반면 PostgreSQL 연결에는 다음 URL을 사용했다.

```text
jdbc:postgresql://postgres:5432/myshelf
```

각 구성요소의 의미는 다음과 같다.

- `postgres`: Docker 네트워크 내부의 PostgreSQL 서비스 이름
- `5432`: PostgreSQL 컨테이너 포트
- `myshelf`: 연결할 데이터베이스 이름

컨테이너 내부에서 `localhost`는 해당 컨테이너 자신을 가리킨다.

따라서 별도의 PostgreSQL 컨테이너에 접근하려면 `localhost`가 아니라 Docker 네트워크에서 해석할 수 있는 호스트 이름을 사용해야 한다.

이번에는 `SPRING_DATASOURCE_URL` 환경변수로 DB 연결 주소를 지정했다.

### 6.6 Spring 프로필과 환경변수

애플리케이션 실행 시 다음 환경변수를 전달했다.

```powershell
-e SPRING_PROFILES_ACTIVE=local
-e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/myshelf
```

`SPRING_PROFILES_ACTIVE=local`은 Spring Boot의 `local` 프로필을 활성화한다.

`SPRING_DATASOURCE_URL`은 컨테이너 환경에 맞는 PostgreSQL 연결 주소를 지정한다.

또한 `--env-file .env`를 통해 DB 비밀번호 등 필요한 환경변수를 전달했다.

여기서 중요한 점은 **Spring 프로필을 선택하는 것과 Docker 네트워크를 구성하는 것은 별개의 작업**이라는 것이다.

`local` 프로필을 사용하더라도 PostgreSQL 연결 주소는 실제 컨테이너 네트워크에 맞게 지정해야 한다.

## 7. 실제 실행 검증 결과

### 7.1 PostgreSQL 및 Flyway 연결 검증

GHCR 이미지로 실행한 Spring Boot 로그에서 다음 내용을 확인했다.

```text
HikariPool-1 - Added connection
HikariPool-1 - Start completed.

Database: jdbc:postgresql://postgres:5432/myshelf (PostgreSQL 17.11)

Successfully validated 1 migration
Migrating schema "public" to version "1 - init"
Successfully applied 1 migration to schema "public", now at version v1
```

이를 통해 다음 사실을 확인했다.

- PostgreSQL 연결 성공
- Flyway 마이그레이션 파일 검증 성공
- 빈 스키마에 V1 초기 마이그레이션 적용 성공
- Hibernate 및 JPA 초기화 성공

실제로 PostgreSQL에 접속하여 테이블 목록도 확인했다.

```powershell
docker exec -it myshelf-postgres psql -U myshelf -d myshelf
```

PostgreSQL 터미널에서 다음 명령어를 사용했다.

```sql
\dt
```

그 결과 `users`, `books`, `user_books`, `flyway_schema_history` 테이블을 확인했다.

### 7.2 Spring Boot 정상 기동

로그에서 다음 메시지를 확인했다.

```text
Tomcat started on port 8080 (http) with context path '/'
Started MyshelfApplication in 5.147 seconds
```

또한 `local` 프로필이 활성화되고 Java 21 환경에서 실행된 것도 확인했다.

따라서 GHCR에서 내려받은 이미지가 Spring Boot 애플리케이션을 정상적으로 기동할 수 있음을 확인했다.

### 7.3 회원가입 및 로그인 API 검증

애플리케이션 기동 이후 PostgreSQL의 `users` 테이블을 조회했다.

```sql
SELECT id, email FROM users;
```

최초 조회 결과는 `0 rows`였다.

이는 새로운 PostgreSQL 데이터베이스에 아직 회원가입한 사용자가 없었기 때문이다.

이후 `POST /users` 회원가입 API로 테스트 사용자를 등록했다.

등록한 계정을 이용해 다음 로그인 요청을 전송했다.

```powershell
'{"email":"docker-test@example.com","password":"<TEST_PASSWORD>"}' | curl.exe -i -X POST "http://localhost:8081/auth/login" -H "Content-Type: application/json" --data-binary "@-"
```

실제 호출 시에는 `<TEST_PASSWORD>`를 테스트 계정의 비밀번호로 변경했다.

응답 결과는 다음과 같았다.

```text
HTTP/1.1 200
Content-Type: application/json

{"accessToken": "..."}
```

전체 토큰 값은 보안상 문서에 기록하지 않았다.

이를 통해 다음 내용을 확인했다.

- 게시된 이미지로 실제 HTTP API 호출 가능
- 회원가입 API를 통한 사용자 등록 성공
- PostgreSQL에 저장된 사용자 정보로 로그인 성공
- HTTP 200 응답 및 Access Token 필드 반환 확인

다만 이번 검증은 회원가입과 로그인 기능에 대한 기본적인 스모크 테스트이며, 모든 API 기능이나 보안 요구사항을 검증한 것은 아니다.

## 8. 검증 중 확인한 사항

### 8.1 브라우저에서 `/users` 접근 시 403이 발생한 이유

브라우저 주소창에서 다음 URL에 접근했을 때 HTTP 403이 발생했다.

```text
http://localhost:8081/users
```

처음에는 컨테이너 실행이나 포트 연결 문제를 의심했지만, MyShelf의 Spring Security 설정을 확인하여 원인을 이해했다.

현재 공개된 요청은 다음과 같다.

```java
.requestMatchers(HttpMethod.POST, "/users").permitAll()
.requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
.anyRequest().authenticated()
```

브라우저 주소창을 통한 접근은 기본적으로 GET 요청이다.

따라서 `GET /users`는 `POST /users`에만 적용한 `permitAll()` 조건과 일치하지 않는다.

| 요청 | 인증 요구 여부 |
|---|---|
| `POST /users` | 인증 없이 허용 |
| `POST /auth/login` | 인증 없이 허용 |
| `GET /users` | 인증 필요 |
| `GET /` | 인증 필요 |

이번에 관찰한 403은 인증되지 않은 GET 요청이 Spring Security 설정에 의해 차단된 결과로 설명할 수 있다.

이는 Docker 이미지 게시 또는 네트워크 연결 실패와는 다른 문제다.

또한 `curl`에서 로그인하여 발급받은 Access Token이 브라우저 주소창 요청에 자동으로 포함되는 것은 아니다.

이번 경험을 통해 **URL 경로뿐 아니라 HTTP 메서드와 인증 조건까지 함께 확인해야 한다**는 점을 이해했다.

### 8.2 이미지 게시 성공과 실행 성공은 다르다

GHCR 게시가 성공했다는 것은 Docker 이미지를 Registry에 저장할 수 있었다는 의미다.

하지만 이것만으로 애플리케이션이 실제 환경에서 정상 실행된다고 보장할 수는 없다.

예를 들어 다음 문제가 발생할 수 있다.

- 필수 환경변수 누락
- PostgreSQL 연결 실패
- Docker 네트워크 설정 오류
- CPU 아키텍처 호환성 문제
- 애플리케이션 기동 중 예외 발생

따라서 이번에는 게시된 이미지를 실제로 다운로드하여 컨테이너로 실행하고, DB 연결과 API 요청까지 검증했다.

**Docker 이미지 빌드 성공, Registry 게시 성공, 컨테이너 실행 성공은 각각 별도의 검증 단계**라는 점을 확인했다.

### 8.3 Git 브랜치와 Docker 이미지의 관계

이번 실행 검증은 `docs/ghcr-verification` 브랜치에서 진행했다.

하지만 `docker pull`과 `docker run`은 현재 Git 브랜치의 소스 코드를 빌드하는 명령어가 아니다.

GHCR에 이미 게시된 이미지를 로컬 Docker 저장소로 가져와 실행하는 작업이다.

따라서 로컬 Git 브랜치를 변경하더라도 이미 다운로드한 Docker 이미지 자체가 변경되지는 않는다.

이 점에서 Git의 소스 코드 관리와 Docker의 이미지 관리는 서로 독립적인 작업이라는 것을 확인했다.

### 8.4 Build Once, Deploy Many

Build Once, Deploy Many는 한 번 생성한 배포용 이미지를 여러 환경에서 다시 빌드하지 않고 재사용하는 방식이다.

이번에는 GitHub Actions가 `main`에서 빌드해 GHCR에 게시한 이미지를 로컬 환경으로 다운로드하여 그대로 실행했다.

이 과정에서 애플리케이션을 로컬에서 다시 빌드하지 않았다.

다만 PR에서 수행한 `verify-image`와 `main`에서 수행한 `publish-image`는 별도의 이미지 빌드이므로 동일한 이미지가 아니다.

향후 실제 CD 환경에서는 GHCR에 게시한 이미지의 Digest를 기준으로 동일한 이미지를 테스트 및 운영 환경에 배포하는 방향을 검토한다.

## 9. 최종 검증 결과와 향후 계획

### 9.1 최종 검증 결과

| 검증 항목 | 결과 |
|---|---|
| PR Docker 이미지 빌드 검증 | 성공 |
| PR에서 이미지 게시 Job 건너뛰기 | 확인 |
| main Push 이후 GHCR 게시 | 성공 |
| 커밋 SHA 기반 태그 생성 | 확인 |
| Private 이미지 GHCR 인증 | 성공 |
| 로컬 이미지 다운로드 | 성공 |
| GHCR 이미지 컨테이너 실행 | 성공 |
| PostgreSQL 연결 | 성공 |
| Flyway V1 마이그레이션 | 성공 |
| Spring Boot 정상 기동 | 성공 |
| 회원가입 API | 성공 |
| 로그인 API | HTTP 200 및 Access Token 반환 확인 |

이를 통해 **GitHub Actions에서 Docker 이미지를 빌드·게시하고, 다른 실행 환경에서 해당 이미지를 다운로드하여 실제 애플리케이션 기능을 실행하는 과정**을 검증했다.

### 9.2 향후 확장

이번 작업에서는 실제 운영 서버 배포와 CD 자동화까지 구현하지 않았다.

향후 진행할 작업은 다음과 같다.

- Jenkins Pipeline 기초 실습
- 운영 서버 및 DB 환경 구성
- 운영 환경용 Docker Compose 설정 검토
- GHCR 게시 이미지의 Digest 기반 배포
- 배포 후 Health Check
- 배포 실패 시 복구 및 롤백 전략 수립

현재 MyShelf는 GitHub Actions를 통해 CI 및 GHCR 이미지 게시 자동화를 구성한 상태다.

추후 실제 배포 대상과 운영 환경이 결정되면 GHCR에 게시된 이미지를 재사용하는 방식으로 CD를 확장할 예정이다.
