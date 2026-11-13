# GitHub Actions 기반 CI 구축 및 검증

> 학습일: 2026-10-21
> 최종 수정일: 2026-11-13
> 대상: MyShelf GitHub Actions CI 구축, Docker 이미지 빌드 검증 및 GHCR 게시
> 관련 문서: [CI/CD 아키텍처](../../architecture/ci-cd.md)

## 1. 학습 목적

MyShelf는 기존에 개발자가 로컬 환경에서 직접 Gradle 빌드와 테스트를 실행했다.

하지만 로컬 환경에만 의존하면 다음과 같은 한계가 있다.

- 개발자가 테스트 실행을 누락할 수 있다.
- 로컬 DB 상태가 테스트 결과에 영향을 줄 수 있다.
- 개발자별 Java, Gradle, PostgreSQL 환경이 다를 수 있다.
- Pull Request의 변경사항을 일관된 조건에서 검증하기 어렵다.
- 로컬에서 성공한 빌드가 다른 환경에서도 성공한다고 보장할 수 없다.

이러한 문제를 줄이기 위해 GitHub Actions 기반 CI를 구축했다.

이번 학습 목표는 **자동 빌드·테스트 환경을 구성하고, 성공과 실패가 의도한 대로 처리되는지 검증하는 것**이다.

이후 Docker 이미지 빌드 검증을 추가하여, 독립된 CI 환경에서도 애플리케이션을 컨테이너 이미지로 패키징할 수 있는지 확인했다.

추가로 GHCR 이미지 게시 자동화를 구현하고, 실제 게시된 이미지를 로컬 환경으로 다운로드하여 Spring Boot 애플리케이션을 실행하는 데 성공했다.

이후 별도의 CD 워크플로를 구축했고, 2026-11-13 `main` 병합을 계기로 CI 성공 → GHCR 게시 → EC2 자동 배포까지 검증했다. 상세 내용은 [GitHub Actions CD](github-actions-cd.md)에 정리한다.

## 2. CI와 GitHub Actions 기본 개념

### 2.1 CI란?

CI(Continuous Integration)는 코드 변경사항을 자주 통합하면서 빌드와 테스트 같은 검증을 자동화하는 개발 방식이다.

MyShelf에서는 GitHub에 Pull Request를 생성하거나 업데이트하면 자동으로 Gradle 빌드와 테스트가 실행되도록 구성했다.

이후 Docker 이미지 빌드 검증도 추가했다.

CI를 도입했다고 코드의 품질이 자동으로 보장되는 것은 아니다.

CI는 **미리 정의한 검증을 일관되게 반복 실행**하며, 변경사항을 통합하기 전에 문제를 발견하도록 돕는다.

### 2.2 GitHub Actions의 구성 요소

| 개념 | 의미 | MyShelf 적용 |
|---|---|---|
| Workflow | 자동화 작업의 전체 정의 | `ci.yaml` |
| Event | Workflow 실행 계기 | PR, Push, 수동 실행 |
| Job | Runner에서 실행되는 작업 단위 | `build-and-test`, `verify-image`, `publish-image` |
| Runner | Job을 실행하는 컴퓨팅 환경 | `ubuntu-latest` |
| Step | Job 내부의 개별 작업 | Checkout, Gradle 빌드 등 |
| Action | 재사용 가능한 자동화 작업 | `actions/checkout` 등 |
| Service | Job에서 사용하는 서비스 컨테이너 | PostgreSQL·Redis |
| Artifact | 실행 중 생성된 결과 파일 | HTML·XML 테스트 리포트 |

Workflow는 `.github/workflows/ci.yaml`에 YAML로 정의하고 Git으로 버전 관리한다.

이를 통해 CI 구성을 코드와 함께 검토하고 변경 이력을 추적할 수 있다.

현재 MyShelf Workflow는 세 Job으로 구성된다.

- `build-and-test`: Gradle 빌드, Spotless 검사 및 PostgreSQL·Redis 통합 테스트
- `verify-image`: PR에서 Docker 이미지 빌드 가능 여부 검증
- `publish-image`: `main` Push 이후 Docker 이미지 빌드 및 GHCR 게시

각 Job은 별도의 Runner에서 실행되며, 이미지 관련 Job들은 `needs: build-and-test`로 실행 의존성을 정의한다.

다만 `verify-image`와 `publish-image`는 서로 의존하지 않는다. 실행 이벤트에 따라 둘 중 하나가 실행되도록 구성했다.

### 2.3 GitHub Actions를 선택한 이유

MyShelf는 GitHub를 소스 코드 및 PR 관리 도구로 사용하고 있다.

GitHub Actions는 별도 CI 서버 없이 GitHub 저장소의 이벤트를 직접 활용할 수 있고, PostgreSQL 서비스 컨테이너도 함께 실행할 수 있다.

따라서 현재 규모에서는 자체 Jenkins 서버를 운영하는 것보다 설정과 관리 부담이 적다.

Jenkins는 이후 별도 실습에서 Pipeline 구성과 운영 방식의 차이를 비교할 예정이다.

## 3. Workflow 실행 조건 이해

### 3.1 `pull_request`와 `push`

MyShelf CI는 다음 이벤트를 사용한다.

- `pull_request` → `main`
- `push` → `main`
- `workflow_dispatch`

여기서 `branches: [main]`의 의미는 이벤트에 따라 달라진다.

**`pull_request`의 경우**

PR의 **대상 브랜치(Base Branch)** 가 `main`인 경우 실행된다.

예를 들어 `ci/github-actions` 브랜치에서 `main`으로 PR을 생성하면 Workflow 실행 대상이 된다.

작업 브랜치 이름이 `main`이어야 한다는 의미가 아니다.

**`push`의 경우**

GitHub 저장소의 `main` 브랜치가 업데이트되었을 때 실행된다.

여기서 `push`는 Git의 `git push` 명령어가 아니라 **GitHub Actions가 감지하는 이벤트 이름**이다.

- `git push origin main`: 로컬 변경사항을 원격 `main`에 전달
- GitHub에서 PR Merge: GitHub의 `main` 브랜치에 변경사항 통합
- GitHub Actions `push` 이벤트: 원격 브랜치 업데이트를 감지하는 이벤트

따라서 사용자가 직접 `git push` 명령어를 입력하지 않았더라도, PR 병합으로 `main`이 업데이트되면 `push` 이벤트가 발생할 수 있다.

실제로 MyShelf의 PR #13을 병합한 후 `main` 브랜치에서 CI가 다시 실행되는 것을 확인했다.

기능 브랜치에 일반적인 Push만 수행하는 것으로는 `push → main` 조건이 충족되지 않는다.

다만 열린 PR의 작업 브랜치에 Push하면 PR 업데이트 이벤트로 Workflow가 다시 실행될 수 있다.

### 3.2 Draft PR에서도 CI가 실행될까?

MyShelf에서는 Draft PR을 생성한 상태에서도 CI가 실행되는 것을 확인했다.

Draft 여부와 CI 실행 여부는 별개다.

Draft PR은 아직 리뷰 준비가 완료되지 않았음을 나타내지만, Workflow의 이벤트 조건이 충족되면 CI가 동작할 수 있다.

### 3.3 CI 실패가 PR 병합을 자동으로 차단할까?

그렇지는 않다.

GitHub Actions가 실패 상태를 표시하는 것과 실패한 PR의 병합을 제한하는 것은 별개의 설정이다.

CI 성공을 필수 병합 조건으로 강제하려면 GitHub Branch Protection 또는 Ruleset에서 필수 상태 검사를 구성해야 한다.

MyShelf에서는 CI 구축 후 `main` 브랜치에 Branch Ruleset을 적용했다.

현재 설정은 다음과 같다.

- PR을 통한 변경사항 병합 필수
- 필수 상태 검사 `build-and-test`, `verify-image` 통과 요구
- 브랜치 삭제 제한
- 강제 Push 차단
- 필수 리뷰 승인 수 0

이를 통해 Gradle 빌드·테스트 또는 Docker 이미지 빌드 검증에 실패한 PR의 병합을 제한한다.

반면 `publish-image`는 `main` Push에서만 실행하므로 PR의 필수 상태 검사에 등록하지 않았다.

즉, **CI Job의 성공·실패 판정과 PR 병합 허용 정책은 별도로 관리된다.**

## 4. CI 환경과 로컬 환경의 차이

### 4.1 Runner는 개발자 PC가 아니다

GitHub Actions에서 `runs-on: ubuntu-latest`를 설정하면 GitHub가 제공하는 Ubuntu Runner에서 Job이 실행된다.

따라서 CI는 개발자 PC에서 실행 중인 Docker 컨테이너나 로컬 데이터베이스를 그대로 사용하지 않는다.

MyShelf는 CI에 필요한 PostgreSQL을 GitHub Actions의 Service Container로 실행한다.

이렇게 하면 개발자 PC의 상태에 의존하지 않는 테스트 환경을 구성할 수 있다.

다만 CI 환경이 독립적이라고 해서 테스트 간 데이터 격리까지 자동으로 보장되는 것은 아니다. 테스트 내부의 데이터 정리와 트랜잭션 관리도 별도로 고려해야 한다.

### 4.2 Docker Compose와 GitHub Actions Services

두 환경 모두 PostgreSQL 컨테이너를 사용하지만 생성 방법이 다르다.

| 항목 | 로컬 | CI |
|---|---|---|
| 실행 도구 | Docker Compose | GitHub Actions Services |
| DB 생성 | 초기화 SQL | `POSTGRES_DB` |
| DB 수명 | 볼륨 및 컨테이너 설정에 따름 | Job 실행 기간 |
| 접근 대상 | 로컬 PostgreSQL | CI 전용 PostgreSQL |

로컬 Docker Compose는 개발용 `myshelf` DB와 테스트용 `myshelf_test` DB를 함께 관리한다.

반면 CI에서는 통합 테스트에 필요한 `myshelf_test`만 생성한다.

현재 CI의 PostgreSQL·Redis 서비스는 `ci.yaml`의 `build-and-test` Job에 정의되어 있으며, 로컬 Docker Compose 설정을 참조하지 않는다.

또한 `verify-image`와 `publish-image`는 기존 `Dockerfile`을 이용해 이미지를 빌드하며, Docker Compose 파일을 실행하지 않는다.

따라서 현재 MyShelf의 CI는 **테스트 DB 실행에는 GitHub Actions Services를 사용하고, 이미지 생성에는 Dockerfile을 사용하는 구조**다.

### 4.3 CI에서 초기화 SQL을 재사용하지 않은 이유

로컬 환경에서는 초기화 SQL이 `myshelf_test` 데이터베이스를 생성한다.

CI에서는 PostgreSQL 이미지가 제공하는 `POSTGRES_DB` 환경변수로 동일한 데이터베이스를 생성할 수 있다.

현재 초기화 SQL의 역할이 테스트 DB 생성이므로 CI에서 이를 다시 실행할 필요가 없었다.

중요한 점은 **데이터베이스 자체의 생성과 테이블 스키마 생성은 서로 다른 작업**이라는 것이다.

- `POSTGRES_DB`: PostgreSQL 데이터베이스 생성
- Flyway: 데이터베이스 내부의 테이블, 제약조건 등 스키마 적용

향후 초기화 SQL에 사용자 권한이나 확장 기능 등의 설정이 추가된다면 CI 환경에서도 해당 설정이 필요한지 다시 검토해야 한다.

### 4.4 Health Check의 역할

PostgreSQL 컨테이너가 생성되었다고 즉시 DB 연결을 받을 수 있는 것은 아니다.

CI에서는 `pg_isready`를 사용하는 Health Check로 PostgreSQL의 연결 준비 상태를 확인한다.

단, Health Check는 PostgreSQL이 연결 요청을 받을 수 있는지 확인하는 단계다.

**테이블이 정상적으로 생성되었는지, Flyway 마이그레이션이 성공했는지는 별도로 검증해야 한다.**

## 5. Spring Boot 테스트 환경 구성

### Feature 5에서 Redis를 추가한 이유

로컬 검색·등록 테스트가 성공해도 CI에 Redis가 없으면 실제 저장소 테스트는 실패한다. 당시 로그는 `RedisConnectionFailureException → RedisConnectionException → ConnectException`으로 이어졌다. 가짜 외부 API 키나 Mock 설정 문제가 아니라 Redis 연결 대상이 준비되지 않은 문제였다.

현재 `build-and-test.services.redis`는 `redis:7-alpine`을 실행하고 6379 포트를 게시하며 `redis-cli ping`으로 준비 상태를 확인한다. 테스트는 Redis DB 1을 사용한다. PostgreSQL은 `myshelf_test`와 Flyway로 별도 준비한다. 로컬 Docker Compose를 CI에서 자동 재사용하는 구조는 아니다.

외부 도서 API는 테스트에서 Mock으로 대체하므로 `test-api-key`는 Bean 구성용 값이다. 실제 외부 API 인증·검색 품질을 검증했다는 뜻은 아니다. 이후 절의 초기 테스트 개수와 V1 실행 결과는 당시 기록으로 유지하며, 최신 Feature 5 CI 결과는 별도로 확인한다.

### 5.1 `test` 프로필 활성화

CI에서는 `SPRING_PROFILES_ACTIVE=test` 환경변수를 설정한다.

Spring Boot는 이에 따라 `application-test.yaml` 설정을 적용한다.

MyShelf의 테스트 DB 연결 정보는 다음과 같다.

- URL: `jdbc:postgresql://localhost:5432/myshelf_test`
- 사용자: `myshelf`
- 비밀번호: `${DB_PASSWORD}`

GitHub Actions Job에서 지정한 `DB_PASSWORD`가 Spring Boot 설정에 전달된다.

CI Job이 Ubuntu Runner에서 직접 실행되고 PostgreSQL 서비스 포트가 Runner에 게시되어 있으므로 `localhost:5432`로 연결할 수 있다.

Job 자체를 별도 컨테이너 안에서 실행하도록 변경한다면 서비스 접근 방식도 달라질 수 있다.

### 5.2 환경변수를 사용하는 이유

DB 연결 정보와 같은 환경별 설정을 애플리케이션 코드에 고정하면 실행 환경이 달라질 때마다 설정을 변경해야 한다.

MyShelf는 프로필과 환경변수를 이용하여 실행 환경에 필요한 값을 외부에서 제공한다.

CI에서는 운영 비밀번호 대신 임시 PostgreSQL 컨테이너용 테스트 비밀번호를 사용한다.

JWT도 테스트 전용 Secret을 사용하므로 운영 환경의 Secret을 CI에 제공할 필요가 없다.

단, 테스트용 고정 비밀번호를 사용할 수 있다는 사실이 운영 비밀값을 저장소에 커밋해도 된다는 의미는 아니다.

### 5.3 Flyway 검증

CI 실행 로그에서 다음 사실을 확인했다.

- 활성 Spring 프로필: `test`
- 연결 DB: `myshelf_test`
- PostgreSQL 버전: 17.11
- 기존 Flyway Schema History 테이블 없음
- Flyway 마이그레이션 1개 검증
- V1 초기 마이그레이션 적용 성공

즉, CI가 준비한 빈 데이터베이스에 Flyway가 스키마를 생성하는 흐름이 실제로 동작했다.

로컬에 이미 생성된 테이블이 있기 때문에 테스트가 우연히 성공한 것이 아니라는 점을 확인한 것이다.

## 6. Gradle 빌드와 자동 검증

### 6.1 실행 명령어

CI에서는 다음 명령어를 사용한다.

`bash ./gradlew clean build --no-daemon`

각 요소의 의미는 다음과 같다.

| 요소 | 의미 |
|---|---|
| `bash` | Shell에서 Gradle Wrapper 실행 |
| `./gradlew` | 프로젝트에 지정된 Gradle 버전 사용 |
| `clean` | 기존 빌드 결과물 제거 |
| `build` | 프로젝트 빌드 및 관련 검증 작업 수행 |
| `--no-daemon` | 지속적으로 실행되는 Gradle Daemon 재사용 방지 |

MyShelf의 빌드 과정에는 테스트와 Spotless 코드 포맷 검사가 포함된다.

따라서 정상적인 테스트 코드라도 Spotless 규칙을 위반하면 빌드가 실패할 수 있다.

### 6.2 Java 버전과 Gradle 버전의 차이

Gradle Wrapper는 Gradle 버전을 맞추는 도구이지, 실행에 사용하는 Java 버전까지 자동으로 일치시키는 도구는 아니다.

실제로 로컬에서 Gradle 명령어를 실행할 때 오래된 Java 환경이 선택되어 문제가 발생했고, IntelliJ에서 설정한 Java 21 환경으로 실행하여 해결했다.

확인할 항목은 다음과 같다.

- 터미널의 `java -version`
- Gradle의 `./gradlew -version`
- IntelliJ의 Gradle JVM 설정

CI에서는 `actions/setup-java`로 JDK 21을 명시하여 실행 환경을 구성한다.

### 6.3 Spotless 검사

MyShelf는 Spotless를 사용하여 Java 코드의 포맷을 검사한다.

의도적 실패 테스트를 추가한 첫 실행에서는 테스트가 실행되기 전에 Spotless 포맷 위반으로 빌드가 실패했다.

이후 포맷을 수정한 뒤 재실행하여 의도한 JUnit 실패를 검증할 수 있었다.

이 과정에서 **빌드 실패와 테스트 실패는 구분해야 한다**는 점을 확인했다.

GitHub Actions가 실패했다고 해서 반드시 테스트 로직에 문제가 있는 것은 아니다.

실패한 Gradle Task와 로그를 확인하여 원인을 판단해야 한다.

## 7. 정상 및 실패 동작 검증

### 7.1 정상 실행 검증

기존 MyShelf 테스트를 GitHub Actions에서 실행한 결과는 다음과 같다.

| 항목 | 결과 |
|---|---|
| 테스트 개수 | 32개 |
| 성공 | 32개 |
| 실패 | 0개 |
| 건너뜀 | 0개 |
| Flyway 마이그레이션 | 성공 |
| Spotless 검사 | 성공 |
| Gradle 빌드 | 성공 |

이로써 로컬 PostgreSQL 환경뿐 아니라 GitHub Actions의 독립된 환경에서도 테스트가 정상 실행되는 것을 확인했다.

PR #13 병합 이후에는 `main` 브랜치의 `push` 이벤트에서도 CI가 정상 완료되는 것을 확인했다.

### 7.2 의도적 실패 테스트를 추가한 이유

정상 테스트만으로는 CI가 실패를 제대로 감지하는지 확신할 수 없다.

따라서 임시 브랜치 `ci/failure-probe`를 생성하고 JUnit의 `fail()`을 호출하는 테스트를 추가했다.

해당 브랜치에서 별도의 Draft PR을 생성해 기존 CI 구축 PR에 영향을 주지 않고 검증했다.

기존 테스트 32개에 의도적 실패 테스트 1개를 추가한 결과는 다음과 같았다.

- 전체 33개 테스트 실행
- 기존 32개 테스트 통과
- 의도적 테스트 1개 실패
- Gradle `:test` Task 실패
- Gradle 빌드 실패
- GitHub Actions Job 실패
- 프로세스 종료 코드 1 반환

이 과정에서 **테스트 실패 → Gradle 실패 → GitHub Actions 실패**가 연결되는 것을 확인했다.

검증 이후 임시 PR을 병합하지 않고 닫았으며, 임시 브랜치도 정리했다.

따라서 의도적 실패 테스트는 실제 CI 구축 브랜치에 포함되지 않는다.

### 7.3 테스트 실패 이후 리포트는 어떻게 될까?

테스트 실패 시 Gradle 실행이 종료되더라도 원인 분석을 위해 테스트 결과 파일이 필요하다.

MyShelf는 GitHub Actions의 `actions/upload-artifact`로 HTML·XML 테스트 결과물을 업로드한다.

업로드 Step에는 `if: ${{ !cancelled() }}` 조건을 적용했다.

GitHub Actions의 기본적인 Step 실행 조건은 앞선 Step의 성공 여부에 영향을 받지만, 해당 조건을 명시하면 앞선 빌드가 실패했더라도 Workflow가 취소되지 않은 경우 업로드를 시도할 수 있다.

실제 실패 검증에서 다음을 확인했다.

- Gradle 테스트 실패
- `Upload test reports` Step 실행
- `test-reports` Artifact 업로드 성공

단, 빌드 초기에 실패하여 리포트 파일 자체가 생성되지 않았다면 업로드할 결과물이 없을 수 있다.

Artifact의 보관 기간은 7일로 설정했다.

## 8. 학습을 통해 정리한 사항

이번 CI 구축과 검증을 통해 다음 내용을 확인했다.

1. **CI 실행 환경은 로컬 환경과 독립적이다.** 필요한 Java와 PostgreSQL 환경을 Runner에 명시적으로 준비해야 한다.

2. **DB 생성과 스키마 마이그레이션은 별개다.** PostgreSQL의 `POSTGRES_DB`로 데이터베이스를 생성하고, Flyway로 내부 스키마를 적용한다.

3. **CI 성공과 실패를 모두 검증해야 한다.** 정상 실행만으로는 실패가 올바르게 전파되는지 확인할 수 없다.

4. **Gradle 빌드 실패의 원인은 여러 가지다.** Spotless, 컴파일, 테스트 등 어느 단계에서 실패했는지 구분해야 한다.

5. **CI 결과와 병합 제한은 별개다.** 실패한 PR의 병합을 제한하려면 필수 상태 검사 정책이 필요하다.

6. **테스트 리포트도 CI의 중요한 결과물이다.** 실패 상황에서 로그뿐 아니라 생성된 HTML·XML 결과를 보존하면 원인 분석에 도움이 된다.

7. **Git 명령어와 GitHub 이벤트는 구분해야 한다.** `git push`를 직접 실행하지 않아도 PR 병합으로 원격 브랜치가 업데이트되면 `push` 이벤트가 발생할 수 있다.

8. **코드 검증과 Docker 이미지 빌드 검증은 목적이 다르다.** 테스트 통과뿐 아니라 애플리케이션이 Docker 이미지로 정상적으로 패키징되는지도 확인해야 한다.

9. **이미지 빌드와 이미지 게시는 별개다.** 이미지를 성공적으로 빌드했더라도 Registry에 게시하지 않으면 다른 환경에서 바로 가져다 사용할 수 없다.

10. **이미지 게시와 실제 실행 검증도 별개다.** GHCR 게시 성공만으로 Spring Boot 기동이나 DB 연동이 보장되지 않으므로, 게시된 이미지를 직접 실행하여 검증해야 한다.

## 9. 후속 학습 및 작업

현재까지 완료한 항목은 다음과 같다.

- GitHub Actions 기반 Gradle 빌드 및 테스트 자동화
- 정상 실행 및 의도적 실패 감지 검증
- `main` 병합 이후 Push 기반 CI 검증
- Branch Ruleset 및 `build-and-test`, `verify-image` 필수 상태 검사 적용
- PR 단계의 Docker 이미지 빌드 검증
- GHCR 게시를 위한 `publish-image` Job 구현
- PR에서 `publish-image`가 Skipped 처리되는 동작 확인
- PR #16 병합 이후 `main` Push 기반 GHCR 이미지 게시 성공
- GHCR Private 이미지 인증 및 로컬 다운로드 성공
- 게시된 이미지로 Spring Boot 실행 및 PostgreSQL 연동 성공
- 회원가입 및 로그인 API 동작 확인
- 2026-11-13 CI 성공 후 EC2 자동 배포 검증 완료 ([최초 통합 검증 기록](github-actions-cd.md#7-실제-최초-통합-검증))

다음 항목은 후속 작업으로 진행한다.

- CI 빌드 시간 분석 및 최적화
- SSH 기반 CD에서 AWS SSM 기반 원격 실행으로 전환
- 이미지 Digest 고정, 보다 엄격한 준비 상태 검사 및 롤백 절차 검토

현재 CI의 실제 구성은 [CI/CD 아키텍처](../../architecture/ci-cd.md)를 기준으로 관리한다.

GHCR 게시 자동화 구현 및 최종 검증은 GitHub Issue #15에서 관리한다.

## 10. Docker 이미지 빌드 검증

### 10.1 이미지 빌드 검증을 추가한 이유

기존 `build-and-test`는 GitHub Actions 환경에서 Gradle 빌드와 테스트가 성공하는지 확인한다.

하지만 Gradle 빌드가 성공했다고 해서 Docker 이미지 빌드까지 성공하는 것은 아니다.

Docker 이미지를 생성할 때는 Dockerfile의 빌드 단계, 이미지에 복사할 파일, 컨테이너 내부 실행 환경 등이 추가로 관여한다.

따라서 현재 애플리케이션 소스 코드와 Dockerfile을 이용해 이미지를 정상적으로 생성할 수 있는지 확인하는 별도 Job을 추가했다.

MyShelf에서는 다음 두 가지를 구분하여 검증한다.

| Job | 검증 목적 |
|---|---|
| `build-and-test` | 테스트 통과 및 Gradle 빌드 성공 |
| `verify-image` | Docker 이미지 빌드 성공 |

두 검증 모두 개발자 PC가 아닌 GitHub Actions의 독립된 환경에서 실행된다.

### 10.2 로컬과 CI에서 이미지 빌드를 각각 검증하는 이유

MyShelf의 Dockerfile은 기존에 Windows 및 macOS 로컬 환경에서 사용하고 있었다.

하지만 로컬에서 이미지가 정상적으로 생성된다는 사실이 독립적인 CI 환경에서도 동일한 결과를 보장하지는 않는다.

대표적인 차이는 다음과 같다.

- 호스트 운영체제와 파일시스템
- CPU 아키텍처(ARM64, AMD64 등)
- 로컬에 존재하는 빌드 캐시
- 개발자 PC에만 존재하는 파일 및 환경변수
- 의존성 다운로드와 빌드 환경

Docker는 애플리케이션 실행에 필요한 런타임과 파일을 이미지로 패키징하여 환경 차이를 상당 부분 줄인다.

다만 호스트 커널, CPU 아키텍처, 네트워크, 메모리, 외부 DB 설정 등 모든 차이를 제거하지는 못한다.

따라서 목표는 **CI와 배포 서버의 물리적 사양을 완전히 동일하게 만드는 것이 아니라, 동일한 Docker 이미지가 실행될 수 있도록 필요한 호환성을 확보하는 것**이다.

`verify-image`는 이 과정에서 CI의 Docker 빌드 환경에서 이미지 생성이 가능한지 확인하는 역할을 한다.

### 10.3 `verify-image` Job의 동작

현재 `verify-image`는 다음과 같이 구성했다.

```yaml
verify-image:
  name: verify-image
  needs: build-and-test
  if: github.event_name == 'pull_request'
  runs-on: ubuntu-latest

  steps:
    - name: Checkout repository
      uses: actions/checkout@v7

    - name: Set up Docker Buildx
      uses: docker/setup-buildx-action@v3

    - name: Verify Docker image build
      uses: docker/build-push-action@v6
      with:
        context: .
        push: false
        tags: myshelf:ci
```

주요 설정은 다음과 같다.

| 설정 | 의미 |
|---|---|
| `needs: build-and-test` | 선행 Job 성공 후 실행 |
| `if: github.event_name == 'pull_request'` | PR 이벤트에서만 실행 |
| `docker/setup-buildx-action` | Docker Buildx 빌드 환경 준비 |
| `docker/build-push-action` | Docker 이미지 빌드 수행 |
| `context: .` | 저장소의 프로젝트 디렉터리를 빌드 컨텍스트로 사용 |
| `push: false` | Registry에 이미지를 게시하지 않음 |
| `tags: myshelf:ci` | 이미지 빌드에 사용할 이름과 태그 지정 |

`needs`는 두 Job 사이의 실행 의존성을 정의한다.

따라서 기존 Gradle 빌드와 테스트가 실패하면 기본적으로 `verify-image`는 실행되지 않는다.

### 10.4 멀티스테이지 Dockerfile과 Gradle 빌드

MyShelf는 멀티스테이지 Dockerfile을 사용한다.

**Builder Stage**

- `eclipse-temurin:21-jdk` 이미지 사용
- 애플리케이션 소스 코드 복사
- Gradle `clean bootJar` 실행
- 실행 가능한 Spring Boot JAR 생성

**Runtime Stage**

- `eclipse-temurin:21-jre` 이미지 사용
- Builder Stage에서 생성한 JAR 복사
- Java 애플리케이션 실행 명령어 정의

기존 CI Job에서도 Gradle `clean build`를 실행하므로 Java 컴파일과 JAR 생성 작업은 일부 중복된다.

하지만 두 작업의 목적은 다르다.

- `build-and-test`: Gradle 빌드와 전체 테스트 검증
- `verify-image`: Dockerfile에 정의된 빌드 과정을 포함하여 최종 이미지 생성 검증

현재 두 Job은 서로 다른 Runner에서 실행되며, 기존 CI Job의 JAR 파일을 Docker Job에 전달하지 않는다.

따라서 Dockerfile 내부에서 다시 Gradle을 실행한다.

이번에는 Dockerfile의 독립적인 빌드 가능성을 유지하고 GHCR 자동화 흐름을 학습하는 데 집중하기 위해 기존 구조를 유지했다.

추후 빌드 시간이 문제가 된다면 JAR 재사용이나 빌드 캐시 최적화를 검토할 수 있다.

### 10.5 PR과 main에서 이미지 빌드 작업을 구분한 이유

현재 `verify-image`에는 다음 조건이 있다.

`if: github.event_name == 'pull_request'`

이것은 Docker 이미지 빌드가 PR에서만 가능하다는 의미가 아니라, 이번 파이프라인에서 정한 실행 정책이다.

MyShelf는 다음과 같이 역할을 구분한다.

- PR: `verify-image`에서 이미지를 빌드하고 게시하지 않음
- `main` Push: `publish-image`에서 이미지를 빌드하고 GHCR에 게시

`main`에서 검증용 이미지 빌드와 게시용 이미지 빌드를 별도로 실행하면 동일한 빌드 작업이 중복될 수 있다.

따라서 PR에서는 이미지 생성 가능 여부를 확인하고, `main`에서는 게시할 이미지를 생성하도록 설계했다.

`publish-image` Job은 다음과 같이 구현했다.

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

여기서 `github.actor`, `github.repository`, `github.sha`는 GitHub Actions의 Context를 참조하는 표현식이다.

`GITHUB_TOKEN`은 GitHub Actions가 Workflow 실행 시 제공하는 임시 인증 토큰이며, `packages: write`는 GHCR 게시에 필요한 권한이다.

따라서 현재 구성에서는 별도의 개인 액세스 토큰을 생성하지 않고 GHCR에 이미지를 게시한다.

PR #16에서는 `publish-image`가 Skipped 처리되는 것을 확인했다.

이후 PR #16을 `main`에 병합하자 `push` 이벤트로 `build-and-test`와 `publish-image`가 실행되었으며, 실제 GHCR 이미지 게시에 성공했다.

이를 통해 **PR에서는 게시 없이 이미지 빌드를 검증하고, `main`에서는 검증 성공 후 이미지를 게시하는 실행 정책**이 의도대로 동작함을 확인했다.

### 10.6 Runner에서 빌드한 이미지는 어디에 보관될까?

`verify-image`의 `docker/build-push-action`에는 `push: false`가 적용되어 있다.

또한 `load: true`를 설정하지 않았다.

따라서 빌드 결과는 Registry에 게시되지 않으며, 현재 Buildx 구성에서는 일반 Docker 이미지 저장소로 자동 로드되지 않고 BuildKit의 임시 빌드 캐시에 남는다.

각 설정의 차이는 다음과 같다.

| 설정 | 결과 |
|---|---|
| `push: false` | Registry 게시 없음 |
| `load: true` | Runner의 Docker 이미지 저장소에 로드 |
| `push: true` | 설정한 Registry에 이미지 게시 |

`push`는 특정 Registry를 의미하는 것이 아니다. 이미지 태그와 인증 설정에 따라 GHCR, Docker Hub 또는 다른 Registry로 게시할 수 있다.

현재 MyShelf의 `publish-image`는 `ghcr.io`에 로그인하고 `ghcr.io/pickylemon/myshelf` 이미지 주소를 사용한다.

GitHub-hosted Runner는 Job 실행을 위한 임시 환경이다.

별도의 외부 캐시 저장이나 Artifact 업로드를 설정하지 않은 `verify-image` 구성에서는 Job 종료 후 Runner와 함께 빌드 결과도 폐기된다.

따라서 PR의 이미지 빌드 검증에서 생성된 이미지를 로컬 PC에서 그대로 가져다 사용할 수는 없다.

**이미지가 정상적으로 만들어지는지 검증하는 것과 이미지를 보관·배포하는 것은 별개의 작업**이다.

반면 `publish-image`는 GHCR에 이미지를 게시하므로 Runner가 종료된 뒤에도 Registry에 이미지가 남는다.

실제로 게시된 이미지를 로컬에서 `docker pull`로 다운로드하여 컨테이너를 실행하는 데 성공했다.

### 10.7 검증 결과와 한계

PR #16과 `main` 병합 이후 다음 결과를 확인했다.

**PR 검증**

- `build-and-test` 성공
- `verify-image` 성공
- 멀티스테이지 Docker 이미지 빌드 성공
- `publish-image` Skipped 확인

**main Push 및 GHCR 게시 검증**

- `build-and-test` 성공
- `publish-image` 성공
- GHCR 이미지 게시 성공
- 커밋 SHA 기반 이미지 태그 생성 확인

**로컬 실행 검증**

- GHCR Private 이미지 인증 성공
- 게시된 이미지 다운로드 성공
- Docker 컨테이너 생성 및 실행 성공
- Spring Boot `local` 프로필 활성화
- PostgreSQL 연결 성공
- Flyway V1 마이그레이션 적용 성공
- JPA 초기화 및 Spring Boot 정상 기동
- 회원가입 API 동작 확인
- 로그인 API HTTP 200 응답 및 Access Token 발급 확인

이번 검증에서는 GitHub Actions가 GHCR에 게시한 이미지를 로컬에서 다시 빌드하지 않고 실행했다.

특히 로컬 Docker Compose로 PostgreSQL 컨테이너를 준비하고, GHCR에서 다운로드한 이미지로 별도의 애플리케이션 컨테이너를 실행했다.

실행 시에는 Docker 네트워크, 포트 매핑, 환경변수 및 Spring 프로필을 명시적으로 지정했다.

Spring Boot 로그에서 DB 연결과 Flyway 마이그레이션이 성공했음을 확인했으며, 실제 회원가입 및 로그인 요청도 정상 처리되었다.

따라서 이번 검증은 이미지 생성 가능 여부뿐 아니라 **게시된 이미지의 실제 실행 가능성과 기본 API 동작**까지 확인한 것이다.

당시(2026-10)의 로컬 검증에서는 다음 항목이 범위 밖이었다.

- 의도적인 Docker 이미지 빌드 실패 감지
- EC2 운영 서버 실행 및 자동 배포
- 배포 후 HTTP 응답 검증과 실패 시 롤백

이후 **EC2 운영 배포와 자동 배포 후 기본 HTTP 검증은 2026-11-13 완료**했다. 의도적인 이미지 빌드 실패 검증과 자동 롤백은 아직 수행하지 않았다.

GHCR 인증, Private 이미지 Pull 및 `docker run` 실행 과정의 상세 학습은 [GitHub Container Registry 학습 문서](github-container-registry.md)에 정리한다.

### 10.8 CI 검증과 실제 배포의 관계

이번 학습을 통해 CI 환경과 실제 배포 환경이 완전히 동일할 필요는 없다는 점을 이해했다.

Docker는 Java 런타임과 애플리케이션 실행 파일 등 필요한 구성요소를 하나의 이미지로 묶어 환경 차이를 줄인다.

하지만 CPU 아키텍처, 컨테이너 실행 환경, DB 연결, 네트워크 등의 호환성은 별도로 확보해야 한다.

현재 MyShelf에서 구현하고 검증한 흐름은 다음과 같다.

```text
PR 생성 및 업데이트
        |
        v
  build-and-test
        |
        v
   verify-image
        |
        v
     PR 병합
        |
        v
    push → main
        |
        v
  build-and-test
        |
        v
   publish-image
        |
        v
       GHCR
        |
        | docker pull
        v
  로컬 컨테이너 실행
        |
        v
 PostgreSQL 및 API 검증
```

위 다이어그램은 **초기 CI/GHCR 구축 당시의 로컬 실행 검증 흐름**이다. 이후 SSH 기반 CD를 추가해 다음과 같이 운영 배포까지 연결했다.

```text
main PR 병합 → CI(build-and-test → publish-image) → GHCR
                                  ↓ CI 성공 완료 이벤트(workflow_run)
                       CD(main Push의 CI 성공만 허용)
                                  ↓ CI 커밋 SHA 전달
                       AWS OIDC 인증 → 임시 SSH 접근 허용
                                  ↓
                       EC2 이미지 Pull → Compose 실행 → HTTP 검증
```

현재는 `sha-<커밋 SHA>` 태그로 CI와 배포 버전을 연결하며, Digest 고정 및 자동 롤백은 추후 개선 대상이다. 실행 결과는 [최초 통합 검증 기록](github-actions-cd.md#7-실제-최초-통합-검증), 상세 절차는 [EC2 자동 배포](ec2-automated-deployment.md)에 정리한다.

여기서 중요한 원칙은 **Build Once, Deploy Many**이다.

배포할 때마다 소스 코드를 다시 빌드하는 것이 아니라, 게시된 동일한 이미지를 재사용하여 배포 환경 사이의 차이를 줄이는 방식이다.

다만 현재 PR에서 검증한 이미지와 `main`에서 게시한 이미지는 별도 빌드 결과물이므로 동일한 이미지라고 볼 수 없다.

이번 로컬 실행 검증에서는 `main`에서 GHCR에 게시한 이미지를 그대로 다운로드하여 사용했다.

현재 CD는 CI에서 게시한 이미지의 커밋 SHA 태그를 전달받아 배포한다. 이미지 Digest로 고정하는 방식은 아직 적용하지 않았으며, 추후 개선 과제로 남겼다.
