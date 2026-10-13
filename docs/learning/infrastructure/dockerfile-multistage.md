# Dockerfile 멀티스테이지 빌드 — MyShelf

## 1. 학습 목표와 적용 범위

MyShelf는 Java 21 기반 Spring Boot 애플리케이션이다. 로컬 PostgreSQL은 이미 Docker Compose로 실행하고 있으며, 이번 단계에서는 **애플리케이션 실행용 Docker 이미지를 만드는 과정**을 학습했다.

이번 단계의 목표는 다음과 같다.

- 호스트 PC의 Java 설정에 의존하지 않고 Spring Boot JAR를 빌드한다.
- JDK가 필요한 **빌드 단계**와 JRE만 필요한 **실행 단계**를 분리한다.
- Dockerfile, `.dockerignore`, 빌드 컨텍스트의 관계를 이해한다.
- 생성된 이미지와 그 안의 Java 실행 환경을 검증한다.

> **검증 범위:** Docker 이미지 빌드 성공과 최종 이미지의 Java 21 실행까지 확인했다. Spring Boot 애플리케이션 기동 및 PostgreSQL 연결은 다음 Compose 통합 단계에서 검증한다.

## 2. 로컬 Gradle 빌드와 Docker 빌드를 구분해야 하는 이유

Windows PowerShell에서 다음 명령을 실행했을 때 빌드가 실패했다.

```powershell
.\gradlew.bat clean bootJar
```

```text
Gradle requires JVM 17 or later to run.
Your build is currently configured to use JVM 8.
```

이는 **PowerShell에서 Gradle을 실행하는 JVM이 Java 8**이었기 때문이다. IntelliJ의 Project SDK 또는 Gradle JVM 설정은 PowerShell 환경과 별개이며, IntelliJ의 Gradle 도구 창에서는 `bootJar`를 실행할 수 있었다.

`bootJar`는 Spring Boot Gradle Plugin이 제공하는 태스크로, 의존성을 포함한 실행 가능한 Spring Boot JAR를 생성한다. 일반 `jar` 태스크와 목적이 다르다. `clean`은 이전 빌드 산출물을 정리하고, `bootJar`는 필요한 컴파일·리소스 처리 등을 수행해 실행 JAR를 만든다. **`bootJar` 자체가 테스트 실행까지 보장하지는 않는다.**

이번에는 로컬 JDK를 변경하는 대신 Docker 빌드 환경에 Java 21 JDK를 포함하는 **멀티스테이지 빌드**를 사용했다.

## 3. 최종 Dockerfile

프로젝트 루트의 `Dockerfile`:

```dockerfile
# Stage 1: Spring Boot JAR 빌드
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

COPY . .

RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew --no-daemon clean bootJar

# Stage 2: Spring Boot 애플리케이션 실행
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

구조는 아래와 같다.

```text
호스트 PC (PowerShell의 Java 8과 무관)
    │
    └── docker build
           │
           ▼
Stage 1: builder (Java 21 JDK)
    ├── 빌드 컨텍스트의 프로젝트 파일 복사
    ├── Gradle Wrapper 실행
    └── bootJar로 실행 JAR 생성
           │
           │ JAR만 복사
           ▼
Stage 2: runtime (Java 21 JRE)
    ├── /app/app.jar
    └── java -jar app.jar
           │
           ▼
최종 이미지: myshelf-app:local
```

### Stage 1 — JAR 빌드

- `FROM eclipse-temurin:21-jdk AS builder`: Java 21 JDK 기반 빌드 스테이지를 만들고 `builder`라는 이름을 붙인다. JDK에는 컴파일에 필요한 도구가 포함된다.
- `WORKDIR /workspace`: **컨테이너 내부** 작업 디렉터리를 정한다.
- `COPY . .`: Docker 빌드 컨텍스트에서 제외되지 않은 파일을 `/workspace`에 복사한다. 첫 번째 `.`은 컨텍스트, 두 번째 `.`은 작업 디렉터리를 뜻한다.
- `sed -i 's/\r$//' gradlew`: Windows 체크아웃 환경에서 `gradlew`에 CRLF 줄바꿈이 들어간 경우를 대비해 LF로 정규화한다.
- `chmod +x gradlew`: Linux 컨테이너에서 Wrapper 스크립트를 실행할 수 있도록 권한을 설정한다.
- `./gradlew --no-daemon clean bootJar`: 프로젝트가 지정한 Gradle Wrapper를 사용해 실행 JAR를 생성한다. `--no-daemon`은 이 빌드에서 상주 Gradle Daemon 재사용을 요청하지 않는 옵션이며, 필요하면 Gradle이 일회성 프로세스를 사용할 수 있다.

### Stage 2 — JAR 실행

- `FROM eclipse-temurin:21-jre`: 최종 실행 이미지는 Java 21 JRE를 사용한다. 첫 번째 스테이지의 JDK와 Gradle 빌드 도구를 최종 이미지에 복사하지 않는다.
- `WORKDIR /app`: 최종 이미지의 작업 디렉터리를 지정한다.
- `COPY --from=builder /workspace/build/libs/*.jar app.jar`: 빌드 스테이지에서 생성된 JAR를 `/app/app.jar`로 복사한다.
- `EXPOSE 8080`: 애플리케이션이 사용하는 포트를 문서화한다. **호스트 포트를 자동으로 개방하지는 않는다.** 포트 공개에는 `docker run -p` 또는 Compose의 `ports` 설정이 필요하다.
- `ENTRYPOINT ["java", "-jar", "app.jar"]`: 컨테이너 시작 시 Spring Boot JAR를 실행한다. 배열 형식은 실행 파일과 인자를 명시적으로 전달하는 exec 형식이다.

> **JAR 복사 시 주의:** `COPY .../*.jar app.jar`는 일치하는 파일이 하나라는 전제가 있다. `build/libs`에 일반 JAR나 추가 산출물이 함께 생성된다면 파일명을 명시하거나 Gradle 출력 설정을 조정해야 한다. `clean bootJar`를 사용했더라도 별도 빌드 설정은 확인해야 한다.

## 4. .dockerignore와 빌드 컨텍스트

이번 멀티스테이지 빌드에 적합한 `.dockerignore` 예시:

```dockerignore
.git
.github
.idea
.vscode
.gradle
.claude

build
docs

*.md
*.iml
*.log

.env
.env.*
.DS_Store
```

`docker build -t myshelf-app:local .`의 마지막 `.`은 **현재 디렉터리를 빌드 컨텍스트로 사용한다**는 뜻이다. Docker는 이 범위의 파일을 빌드에 사용할 수 있으며, `.dockerignore` 패턴에 해당하는 파일은 빌드 컨텍스트에서 제외한다.

이번 Dockerfile은 컨테이너 안에서 `bootJar`를 실행하므로 다음은 **제외하면 안 된다**.

- `src/`
- `gradle/`과 Gradle Wrapper 관련 파일
- `gradlew`
- `build.gradle`, `settings.gradle` 등 빌드 설정

반대로 호스트에서 만들어진 `build/` 산출물은 제외해도 된다. 실행 JAR는 Docker 빌드의 첫 번째 스테이지에서 새로 생성하기 때문이다.

`.dockerignore`는 빌드 컨텍스트를 줄이고 불필요하거나 민감한 로컬 파일이 빌드에 들어가는 일을 줄이는 수단이다. **소스 안에 직접 작성한 비밀정보까지 자동으로 보호하는 것은 아니다.**

## 5. 이미지 빌드 명령어

프로젝트 루트에서 실행:

```powershell
docker build -t myshelf-app:local .
```

| 요소 | 의미 |
|---|---|
| `docker build` | Dockerfile을 바탕으로 이미지 빌드 |
| `-t` / `--tag` | 이미지 이름과 태그 지정 |
| `myshelf-app` | 이미지 이름 |
| `local` | 이미지 태그 |
| `.` | 현재 디렉터리를 빌드 컨텍스트로 전달 |

이 명령을 실행할 때 호스트의 `gradlew`나 `java`를 직접 실행하는 것이 아니다. Docker는 첫 번째 스테이지의 **Java 21 JDK 컨테이너 환경**에서 Gradle Wrapper와 `bootJar`를 실행한다.

처음 빌드할 때는 JDK/JRE 베이스 이미지 다운로드, Gradle 배포판 및 프로젝트 의존성 다운로드, 컴파일과 패키징 과정으로 시간이 오래 걸릴 수 있다.

## 6. 이미지와 실행 환경 검증

### 이미지 생성 확인

```powershell
docker image ls
```

실제 확인한 이미지 목록에는 다음이 포함됐다.

```text
myshelf-app:local   584MB  (Docker CLI 표시 기준)
postgres:17         646MB  (Docker CLI 표시 기준)
```

이 수치는 당시 `docker image ls`의 디스크 사용량 표시다. 이미지의 최적화 여부나 실행 중 메모리 사용량을 의미하지는 않는다.

### 최종 이미지의 Java 버전 확인

```powershell
docker run --rm --entrypoint java myshelf-app:local -version
```

실제 출력의 핵심:

```text
openjdk version "21.0.12.1" 2026-08-18 LTS
OpenJDK Runtime Environment Temurin-21.0.12.1+1
```

- `docker run`: 이미지로 새로운 컨테이너를 생성하고 실행한다.
- `--rm`: 명령 실행 종료 후 생성된 **컨테이너만 자동 삭제**한다. 이미지는 유지된다.
- `--entrypoint java`: Dockerfile의 기본 `ENTRYPOINT`를 이번 실행에서만 `java`로 덮어쓴다.
- `-version`: `java` 명령에 전달할 인자다.

따라서 이 명령은 Spring Boot를 실행하지 않고 임시 컨테이너에서 `java -version`만 확인한다.

필요하면 JAR 파일의 존재 여부도 별도로 확인할 수 있다.

```powershell
docker run --rm --entrypoint ls myshelf-app:local -lh /app/app.jar
```

이는 **추가 확인 명령**이며, 이번에 실제로 실행했다고 기록하지 않는다.

## 7. 확인된 사실과 아직 검증하지 않은 것

| 항목 | 상태 |
|---|---|
| Docker 멀티스테이지 이미지 빌드 | 확인 완료 |
| `myshelf-app:local` 이미지 생성 | 확인 완료 |
| 최종 이미지의 Java 21 실행 | 확인 완료 |
| 호스트 PowerShell의 Java 8을 사용하지 않는 빌드 | 확인 완료 |
| Spring Boot 애플리케이션 실제 기동 | 미검증 |
| 애플리케이션 컨테이너 ↔ PostgreSQL 연결 | 미검증 |
| 이미지 빌드 과정의 테스트 실행 | 수행하지 않음 (`bootJar` 사용) |

**이미지 빌드 성공 = 애플리케이션 정상 기동 또는 테스트 통과**는 아니다. 실행을 위한 설정과 DB 연결은 별도 검증이 필요하다.

## 8. 빌드 캐시와 앞으로의 개선점

현재 Dockerfile은 아래 순서로 파일 복사와 Gradle 빌드를 수행한다.

```dockerfile
COPY . .
RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew --no-daemon clean bootJar
```

소스 파일이 변경되면 `COPY` 단계의 캐시가 무효화되면서 뒤의 Gradle 빌드도 다시 수행될 수 있다. 특히 별도의 Gradle 캐시 마운트를 구성하지 않았기 때문에 빌드 의존성 다운로드가 반복될 수 있다.

향후 필요해지면 다음을 검토한다.

- Gradle 설정 및 Wrapper 복사와 소스 복사를 분리해 Docker 레이어 캐시 효율 개선
- BuildKit 캐시 마운트 등을 사용해 Gradle 다운로드 캐시 재사용
- 최종 이미지 크기 최적화와 비루트 사용자 실행
- 테스트 실행을 별도 CI 단계에 명시적으로 포함

현재는 **기본 멀티스테이지 동작 이해와 검증이 우선**이므로 최적화는 후순위로 둔다.

## 9. 다음 단계 — Docker Compose에서 app과 postgres 연결

현재 PostgreSQL은 Compose로 실행 중이지만, MyShelf 애플리케이션 자체는 아직 Compose로 실행하지 않았다.

다음 작업에서는 `compose.yaml`에 `app` 서비스를 추가하고 아래 항목을 확인한다.

- `app`과 `postgres`가 같은 Compose 네트워크를 사용하도록 구성
- 컨테이너 내부 JDBC 주소를 `localhost`가 아닌 `postgres:5432`로 지정
- `SPRING_DATASOURCE_URL`, 사용자 이름, 비밀번호 등 연결 설정 주입
- `SPRING_PROFILES_ACTIVE`와 `JWT_SECRET` 등 실행에 필요한 환경변수 주입
- PostgreSQL `healthcheck`와 `depends_on`의 역할 확인
- Spring Boot 실제 기동, Flyway 검증, DB 연결을 로그 및 API 호출로 확인

컨테이너 내부의 `localhost`는 **호스트 PC도 다른 컨테이너도 아닌 해당 컨테이너 자신**을 가리킨다. Compose에서는 서비스 이름 `postgres`를 컨테이너 간 호스트 이름으로 사용할 수 있다.

> **이번 단계의 결론:** 로컬 PC에 설정된 Java 8과 무관하게 Java 21 JDK로 실행 JAR를 빌드하고, Java 21 JRE 기반의 최종 Docker 이미지를 생성했다. 이후에는 이미지 생성이 아닌 **애플리케이션 실행과 컨테이너 간 연결**을 검증한다.
