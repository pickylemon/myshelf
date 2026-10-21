# GitHub Actions 기반 CI 구축 및 검증

> 학습일: 2026-10-21
> 대상: MyShelf GitHub Actions CI 구축
> 관련 문서: [CI/CD 아키텍처](../../architecture/ci-cd.md)

## 1. 학습 목적

MyShelf는 기존에 개발자가 로컬 환경에서 직접 Gradle 빌드와 테스트를 실행했다.

하지만 로컬 환경에만 의존하면 다음과 같은 한계가 있다.

- 개발자가 테스트 실행을 누락할 수 있다.
- 로컬 DB 상태가 테스트 결과에 영향을 줄 수 있다.
- 개발자별 Java, Gradle, PostgreSQL 환경이 다를 수 있다.
- Pull Request의 변경사항을 일관된 조건에서 검증하기 어렵다.

이러한 문제를 줄이기 위해 GitHub Actions 기반 CI를 구축했다.

이번 학습 목표는 **자동 빌드·테스트 환경을 직접 구성하고, 성공과 실패가 의도한 대로 처리되는지 검증하는 것**이다.

Docker 이미지 자동 게시와 운영 배포는 이번 작업 범위에 포함하지 않는다.

## 2. CI와 GitHub Actions 기본 개념

### 2.1 CI란?

CI(Continuous Integration)는 코드 변경사항을 자주 통합하면서 빌드와 테스트 같은 검증을 자동화하는 개발 방식이다.

MyShelf에서는 GitHub에 Pull Request를 생성하거나 업데이트하면 자동으로 Gradle 빌드와 테스트가 실행되도록 구성했다.

CI를 도입했다고 코드의 품질이 자동으로 보장되는 것은 아니다.

CI는 **미리 정의한 검증을 빠짐없이 반복해서 실행**하며, 검증되지 않은 문제를 발견할 수 있도록 돕는다.

### 2.2 GitHub Actions의 구성 요소

| 개념 | 의미 | MyShelf 적용 |
|---|---|---|
| Workflow | 자동화 작업의 전체 정의 | `ci.yml` |
| Event | Workflow 실행 계기 | PR, Push, 수동 실행 |
| Job | Runner에서 실행되는 작업 단위 | `build-and-test` |
| Runner | Job을 실행하는 컴퓨팅 환경 | `ubuntu-latest` |
| Step | Job 내부의 개별 작업 | Checkout, JDK 설정, 빌드 등 |
| Action | 재사용 가능한 자동화 작업 | `actions/checkout` 등 |
| Service | Job에서 사용하는 서비스 컨테이너 | PostgreSQL |
| Artifact | 실행 중 생성된 결과 파일 | HTML·XML 테스트 리포트 |

Workflow는 `.github/workflows/ci.yml`에 YAML로 정의하고 Git으로 버전 관리한다.

이를 통해 CI 구성을 코드와 함께 검토하고 변경 이력을 추적할 수 있다.

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

실제로 `main` 브랜치에 Push가 발생했을 때 실행된다.

따라서 기능 브랜치에 일반적인 Push만 수행하는 것으로는 `push → main` 조건이 충족되지 않는다.

다만 열린 PR의 작업 브랜치에 Push하면 PR 업데이트 이벤트로 Workflow가 다시 실행될 수 있다.

### 3.2 Draft PR에서도 CI가 실행될까?

MyShelf에서는 Draft PR을 생성한 상태에서도 CI가 실행되는 것을 확인했다.

Draft 여부와 CI 실행 여부는 별개다.

Draft PR은 아직 리뷰 준비가 완료되지 않았음을 나타내지만, Workflow의 이벤트 조건이 충족되면 CI가 동작할 수 있다.

### 3.3 CI 실패가 PR 병합을 자동으로 차단할까?

그렇지는 않다.

GitHub Actions가 실패 상태를 표시하는 것과, 실패한 PR의 병합을 제한하는 것은 별개의 설정이다.

CI 성공을 필수 병합 조건으로 강제하려면 GitHub의 Branch Protection 또는 관련 Ruleset에서 필수 상태 검사를 구성해야 한다.

MyShelf의 초기 CI 구현에서는 해당 정책을 별도로 적용하지 않았다.

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
| `--no-daemon` | Gradle Daemon을 재사용하지 않는 일회성 실행 방식 |

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

5. **CI 결과와 병합 제한은 별개다.** 실패한 PR의 병합을 강제로 제한하려면 필수 상태 검사 정책이 필요하다.

6. **테스트 리포트도 CI의 중요한 결과물이다.** 실패 상황에서 로그뿐 아니라 생성된 HTML·XML 결과를 보존하면 원인 분석에 도움이 된다.

## 9. 후속 학습 및 작업

CI 구현 및 PR 검증을 마쳤으며, 다음 항목은 후속 작업으로 관리한다.

- `main` 병합 이후 Push 기반 CI 실행 검증
- Branch Protection 및 필수 상태 검사 설정
- Docker 이미지 빌드 및 GHCR 게시 자동화
- Jenkins Pipeline 기초 실습
- 핵심 기능 개발 이후 운영 배포 및 CD 구축

현재 CI의 실제 구성은 [CI/CD 아키텍처](../../architecture/ci-cd.md)를 기준으로 관리한다.
