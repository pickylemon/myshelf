# Gradle 실행 JVM 버전 불일치로 `bootJar` 빌드 실패

## 1. 문제 상황

MyShelf의 Spring Boot 애플리케이션을 Docker 이미지로 패키징하기 위해 Windows PowerShell에서 Gradle Wrapper를 실행했다.

```powershell
.\gradlew.bat clean bootJar
```

그러나 빌드가 시작되지 않고 다음 오류가 발생했다.

```text
FAILURE: Build failed with an exception.

* What went wrong:
Gradle requires JVM 17 or later to run. Your build is currently configured to use JVM 8.
```

반면 IntelliJ에서는 애플리케이션을 실행할 수 있었고, Gradle 도구 창의 `bootJar` 태스크도 실행할 수 있었다.

## 2. 원인 분석

**Gradle Wrapper는 Gradle 버전을 관리하지만, Gradle을 실행할 JVM까지 자동으로 맞춰 주지는 않는다.**

오류 메시지에 따르면 **PowerShell에서 실행한 Gradle 프로세스가 Java 8을 사용**하려 했고, 현재 Gradle 버전은 실행 JVM으로 Java 17 이상을 요구했다. 따라서 Spring Boot 컴파일이나 Docker 이미지 생성에 도달하기 전에 실패한 것이다.

IntelliJ 설정 화면에서 Gradle JVM은 `Project SDK corretto-26`으로 표시되었다. IntelliJ의 Gradle 태스크와 외부 PowerShell 명령은 서로 다른 JVM을 사용할 수 있으므로, IntelliJ에서는 동작하지만 PowerShell에서는 실패하는 현상이 가능하다.

이때 세 가지를 구분해야 한다.

| 구분 | 역할 | 이번 사례 |
| --- | --- | --- |
| Gradle Wrapper | 프로젝트가 사용하는 Gradle 버전 선택 | `gradlew.bat`로 실행 |
| Gradle 실행 JVM | Gradle 자체를 구동하는 Java | PowerShell 실행 시 Java 8 사용으로 실패 |
| 프로젝트 Java 버전 | 애플리케이션 컴파일 및 실행 대상 | MyShelf는 Java 21 사용 |

> PowerShell에서 `java -version`, `$env:JAVA_HOME` 및 `where.exe java`의 실제 출력까지 확인한 것은 아니다. 따라서 **PC에 Java 8만 설치되어 있었다고 단정할 수는 없다.** 확인된 것은 해당 Gradle 실행이 JVM 8로 설정되어 실패했다는 사실이다.

## 3. 가능한 해결 방법

### 방법 A. 로컬 Gradle 실행 JVM 변경

로컬 또는 PowerShell 세션의 Gradle 실행 JVM을 호환되는 JDK로 변경할 수 있다. MyShelf에서는 Java 21을 사용하는 것이 일관적이다.

```powershell
java -version
$env:JAVA_HOME
where.exe java
```

JDK 21이 설치된 경로를 확인한 다음, 필요하다면 **현재 PowerShell 세션에 한해** `JAVA_HOME`을 지정할 수 있다.

```powershell
# 실제 설치 경로로 변경할 것
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat clean bootJar
```

또는 IntelliJ의 `Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM`에서 적절한 JDK를 선택해 IDE 안에서 빌드할 수 있다. 다만 이 설정만으로 외부 PowerShell의 Java 환경이 자동으로 바뀌지는 않는다.

### 방법 B. Docker 멀티스테이지 빌드 사용 — 이번에 선택

MyShelf에서는 로컬 Java 설정에 영향을 받지 않도록, Docker 빌드 과정에서 Java 21 JDK로 JAR를 생성하고 Java 21 JRE 이미지에서 실행하도록 구성했다.

```dockerfile
# Stage 1: JAR 빌드
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace
COPY . .
RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew --no-daemon clean bootJar

# Stage 2: 애플리케이션 실행
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- 1단계의 JDK 21에서 Gradle Wrapper 및 `bootJar` 실행
- 2단계의 JRE 21에는 실행 JAR만 복사
- 로컬에 생성한 `build/` 산출물은 `.dockerignore`에서 제외하여 Docker 내부에서 빌드하도록 구성

이 방식은 로컬 Gradle 환경 오류를 직접 수정하는 대신 **빌드 실행 환경을 Docker 이미지로 명시**한다. 로컬 Java 설정을 고치는 방법 역시 유효하며, 멀티스테이지가 모든 상황에서 유일한 해결책은 아니다.

## 4. 적용 및 검증

PowerShell에서 다음 명령으로 이미지를 생성했다.

```powershell
docker build -t myshelf-app:local .
docker image ls
```

`myshelf-app:local` 이미지가 생성된 것을 확인했다.

최종 이미지의 Java 버전도 확인했다.

```powershell
docker run --rm --entrypoint java myshelf-app:local -version
```

실제 출력에는 다음 버전이 표시되었다.

```text
openjdk version "21.0.12.1" 2026-08-18 LTS
OpenJDK Runtime Environment Temurin-21.0.12.1+1
```

따라서 **호스트에서 Gradle 실행 JVM을 변경하지 않고 Docker 내부에서 JAR를 빌드하여 이미지 생성에 성공했으며**, 최종 이미지의 Java 21 실행 환경까지 검증했다.

단, 이 단계에서 확인한 것은 **이미지 빌드와 Java 실행 환경**이다. Spring Boot 애플리케이션의 실제 기동, JWT 설정 및 PostgreSQL 컨테이너 연결은 이후 Docker Compose 통합 단계에서 검증할 예정이다.

## 5. 배운 점

- `gradlew`는 Gradle 버전을 고정하는 수단이지, 호스트 JVM 버전까지 보장하는 수단은 아니다.
- IntelliJ의 Gradle JVM과 외부 PowerShell에서 Gradle을 실행하는 JVM은 다를 수 있다.
- 빌드 오류가 발생하면 **Gradle 실행 JVM 문제인지, 프로젝트 코드 컴파일 문제인지** 먼저 구분해야 한다.
- Docker 멀티스테이지 빌드는 빌드용 JDK와 실행용 JRE를 분리하고, 개발자 PC의 Java 설정에 대한 의존도를 줄일 수 있다.

관련 개념과 Dockerfile 명령어별 설명은 `dockerfile-multistage.md`에서 다룬다.
