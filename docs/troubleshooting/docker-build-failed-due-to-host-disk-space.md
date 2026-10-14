# 호스트 디스크 용량 부족으로 인한 Docker 빌드 실패 및 Docker Desktop 복구

## 1. 상황

MyShelf에서 Spring Boot와 PostgreSQL을 Docker Compose로 통합 실행하는 환경을 구성했다.

`infra/docker-setup` 브랜치에서 Docker 설정을 작업한 후 다음 명령으로 이미지 빌드와 컨테이너 실행을 시도했다.

```powershell
docker compose up -d --build
```

이 과정에서 Windows 호스트의 디스크 용량 부족 문제가 발생하여 작업을 정상적으로 진행할 수 없었다.

이후 Docker Desktop 화면이 정상적으로 열리지 않는 현상도 겪었다. Docker 관련 프로세스가 백그라운드에서 동작하는 것과 Docker Desktop 화면이 정상적으로 실행되는 것은 별개의 상태였다.

문제를 해결하기 위해 Windows 저장 공간을 확보하고, Docker 데이터의 저장 위치를 여유 공간이 있는 드라이브로 변경하는 작업을 진행했다. Windows 재부팅 이후 Docker Desktop을 정상적으로 사용할 수 있었으며, 최종적으로 Compose 환경과 애플리케이션의 동작을 검증했다.

이번 사례는 단순한 Docker 이미지 빌드 오류가 아니라 **호스트 저장 공간 부족과 Docker Desktop 실행 문제가 함께 발생한 장애 사례**다.

---

## 2. 발생한 증상

### 2.1 Docker 이미지 빌드 중단

다음 명령을 실행하는 과정에서 디스크 용량 부족 문제가 발생했다.

```powershell
docker compose up -d --build
```

당시 Dockerfile은 멀티스테이지 빌드로 구성되어 있었다.

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

이미지 빌드 과정에서는 JDK 기반 이미지, Gradle 의존성, 빌드 산출물, Docker 이미지 레이어 등을 저장할 공간이 필요하다.


### 2.2 Docker Desktop 화면 실행 문제

디스크 용량 문제를 겪는 과정에서 Docker Desktop 애플리케이션 화면이 정상적으로 열리지 않았다.

특이한 점은 Docker 관련 기능이 백그라운드에서는 동작하는 것으로 보였다는 것이다.

즉, 다음 두 가지 상태가 반드시 일치하지는 않았다.

- Docker Desktop의 그래픽 사용자 인터페이스(GUI)가 정상적으로 열리는 상태
- Docker Engine이 실행되어 Docker 명령을 처리할 수 있는 상태

따라서 Docker Desktop 화면이 열리지 않는다는 이유만으로 Docker Engine이 완전히 중단되었다고 단정할 수 없었다.

---

## 3. 원인 분석

### 3.1 확인된 문제: 호스트 디스크 용량 부족

Docker는 컨테이너 내부에서 실행되지만, 이미지와 컨테이너 데이터를 저장하기 위해 호스트의 디스크 공간을 사용한다.

Docker Desktop을 Windows에서 WSL 2 기반으로 실행할 경우 Docker 데이터는 가상 디스크 파일을 통해 저장될 수 있다.

Docker가 저장 공간을 사용하는 대표적인 항목은 다음과 같다.

| 항목 | 내용 |
|---|---|
| Images | 다운로드하거나 빌드한 이미지 |
| Build Cache | 이미지 빌드 과정의 캐시 |
| Containers | 컨테이너의 쓰기 가능 계층 및 메타데이터 |
| Volumes | PostgreSQL 등의 영속 데이터 |
| Gradle Build Data | 이미지 빌드 중 생성되는 의존성 및 산출물 |

멀티스테이지 빌드는 최종 실행 이미지에 빌드 도구를 포함하지 않도록 해주지만, **이미지를 빌드하는 과정 자체에서 필요한 디스크 공간까지 없애는 것은 아니다.**

다만 당시 각 항목의 사용량을 별도로 측정하지 않았으므로 어떤 항목이 가장 많은 공간을 사용했는지는 확정할 수 없다.

### 3.2 Docker Desktop 화면이 열리지 않은 원인

Docker Desktop 화면 실행 문제는 Windows 재부팅 이후 해소되었다.

하지만 당시 Docker Desktop의 상세 오류 로그나 Windows 이벤트 로그를 분석한 것은 아니다.

따라서 다음과 같은 가능성은 구분해야 한다.

- 디스크 공간 부족으로 Docker Desktop의 정상 동작에 문제가 발생했을 가능성
- Docker Desktop 관련 프로세스나 WSL 상태에 문제가 있었을 가능성
- Windows 재부팅 과정에서 관련 프로세스와 서비스가 다시 초기화되면서 정상화되었을 가능성

**재부팅 후 복구되었다는 사실만으로 정확한 내부 원인이 확인된 것은 아니다.**

---

## 4. 실제 해결 과정

### 4.1 Windows 호스트 저장 공간 확보

우선 Docker를 재설치하거나 관련 데이터를 일괄 삭제하지 않고 Windows의 저장 공간 사용 현황을 확인했다.

공간을 확보할 수 있는 항목을 검토하고 불필요한 파일 및 애플리케이션을 정리하는 방향으로 진행했다.

이때 다음 사항을 고려했다.

- Docker 이미지를 삭제하면 이후 다시 다운로드하거나 빌드해야 할 수 있음
- Docker 볼륨을 삭제하면 PostgreSQL 데이터가 손실될 수 있음
- 원인이 불명확한 상태에서 Docker 전체를 초기화하는 것은 불필요한 위험이 있음

따라서 호스트의 가용 디스크 공간을 우선 확보하는 방향을 선택했다.

### 4.2 Docker 데이터 저장 위치 변경

호스트 저장 공간을 확보하는 것과 별개로, 이후 Docker가 사용하는 데이터 저장 위치를 여유 공간이 있는 드라이브로 변경하는 작업을 진행했다.

이 작업은 단순히 불필요한 파일을 삭제하는 것과 목적이 다르다.

- **공간 확보:** 현재 부족한 디스크 공간을 회복
- **저장 위치 변경:** 향후 Docker 데이터가 저장되는 위치를 조정하여 기존 드라이브의 공간 부담 완화

Docker Desktop이 WSL 2 기반으로 동작하는 환경에서는 이미지, 컨테이너, 볼륨 등 Docker 데이터가 저장되는 디스크 이미지의 위치가 중요하다.

Docker Desktop 버전에 따라 Settings의 Resources 관련 메뉴에서 디스크 이미지 저장 위치를 관리할 수 있다.


### 4.3 Windows 재부팅

저장 공간 확보 및 저장 위치 변경 작업을 진행한 뒤 Windows를 재부팅했다.

재부팅 이후 Docker Desktop을 정상적으로 사용할 수 있게 되었다.

이번 복구 과정에서 재부팅은 중요한 조치였지만, 재부팅 자체가 디스크 용량 부족 문제를 근본적으로 해결했다고 볼 수는 없다.

저장 공간 확보, 저장 위치 변경, 재부팅이 각각 다른 문제에 영향을 주었을 수 있으므로 조치별 효과를 단정하지 않는다.

---

## 5. 복구 후 검증

### 5.1 Docker Compose 실행

Docker 환경을 복구한 뒤 MyShelf의 Compose 구성을 실행했다.

이후 다음 명령으로 애플리케이션 로그를 확인했다.

```powershell
docker compose logs --tail=100
```

Spring Boot 시작 로그:

```text
The following 1 profile is active: "local"
Tomcat initialized with port 8080 (http)
HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection
Database: jdbc:postgresql://postgres:5432/myshelf (PostgreSQL 17.11)
Successfully validated 1 migration
Schema "public" is up to date. No migration necessary.
Initialized JPA EntityManagerFactory for persistence unit 'default'
Tomcat started on port 8080 (http)
Started MyshelfApplication in 5.105 seconds
```

이를 통해 다음 사항을 확인했다.

- Spring Boot가 `local` 프로필로 정상 실행됨
- PostgreSQL에 JDBC 연결 성공
- Flyway 마이그레이션 검증 성공
- JPA EntityManagerFactory 초기화 성공
- Tomcat 8080 포트 시작 완료

### 5.2 PostgreSQL 자동 복구

PostgreSQL 로그에서는 이전에 정상 종료되지 않았음을 나타내는 메시지가 확인되었다.

```text
database system was not properly shut down; automatic recovery in progress
redo starts at 0/19A6948
redo done at 0/19A6A18
database system is ready to accept connections
```

PostgreSQL은 비정상 종료 상태를 감지한 후 자동 복구를 수행했으며, 이후 정상적으로 연결을 수락했다.

해당 로그만으로 비정상 종료의 직접적인 원인이 디스크 부족이었는지, Docker 종료 과정이었는지까지는 확인할 수 없다.

### 5.3 HTTP 요청 및 데이터 영속성 검증

애플리케이션 시작 로그뿐 아니라 실제 API 요청도 검증했다.

| 검증 항목 | 결과 |
|---|---|
| 회원가입 | HTTP 201 |
| PostgreSQL INSERT | 실행 로그 확인 |
| 기존 이메일로 재가입 | HTTP 409 |
| 중복 이메일 예외 | `DUPLICATE_EMAIL` |
| 기존 회원 로그인 | JWT 발급 성공 |
| Compose 종료 및 재실행 | 성공 |
| 재실행 이후 기존 회원 데이터 | 유지 확인 |

따라서 Docker 환경 복구 이후 Spring Boot와 PostgreSQL이 실제로 연동되어 동작하고 있음을 확인했다.

이는 로컬 개발 환경에서의 기능 검증이며, 운영 환경의 장애 복구나 데이터 백업까지 검증한 것은 아니다.

---

## 6. 재발 시 진단 방법

### 6.1 호스트 디스크 공간 확인

가장 먼저 Windows의 저장소 사용량과 가용 공간을 확인한다.

Docker 이미지 빌드에 실패했더라도 반드시 Docker 데이터만이 원인인 것은 아니다.

호스트 전체 저장 공간과 Docker 사용량을 구분하여 확인해야 한다.

### 6.2 Docker 디스크 사용량 확인

Docker 명령이 실행 가능한 상태라면 다음 명령을 사용한다.

```powershell
docker system df
docker system df -v
```

이를 통해 Docker 이미지, 컨테이너, 로컬 볼륨, 빌드 캐시의 사용량 등을 확인할 수 있다.

Docker Desktop 화면이 열리지 않더라도 Docker Engine이 정상 동작하고 명령줄에서 연결할 수 있다면 확인 가능하다.

### 6.3 Docker Engine 상태 확인

```powershell
docker info
docker compose ps -a
```

- `docker info`: Docker CLI가 Engine과 통신할 수 있는지 확인
- `docker compose ps -a`: Compose 컨테이너의 실행 및 종료 상태 확인

Docker Desktop GUI가 열리지 않는 현상과 Docker Engine 자체의 연결 실패를 구분한다.

### 6.4 WSL 상태 확인

Docker Desktop이 WSL 2 기반이라면 다음 명령으로 관련 상태를 확인할 수 있다.

```powershell
wsl --status
wsl --list --verbose
```

WSL 상태 문제가 의심되면 관련 로그를 확인한 뒤 필요한 경우 종료 및 재시작을 검토한다.

```powershell
wsl --shutdown
```

이 명령은 실행 중인 WSL 배포판을 종료하므로 다른 WSL 작업에도 영향을 줄 수 있다.

또한 이는 재발 시 고려할 수 있는 방법이며, 이번 장애에서 실제로 사용한 복구 명령으로 확인된 것은 아니다.

### 6.5 빌드 캐시 정리

빌드 캐시의 사용량이 과도하다고 확인된 경우 다음 명령을 사용할 수 있다.

```powershell
docker builder prune
```

사용하지 않는 빌드 캐시를 정리하지만, 이후 빌드에서 캐시를 다시 생성해야 할 수 있다.

공간 부족이 발생했다고 해서 무조건 실행할 필요는 없다.

### 6.6 데이터 삭제에 주의

다음 명령은 데이터 보존 여부를 확인하기 전에는 실행하지 않는다.

```powershell
docker compose down -v
```

`-v` 옵션은 Compose에서 관리하는 볼륨을 제거할 수 있으므로 PostgreSQL 데이터가 삭제될 수 있다.

`docker system prune --volumes` 역시 사용하지 않는 Docker 리소스와 익명 볼륨 등을 정리할 수 있으므로 영향을 확인해야 한다.

**저장 공간 확보가 목적이라고 해서 Docker 리소스를 무조건 삭제하는 것은 적절하지 않다.**

---

## 7. 학습 및 정리

이번 사례에서 확인한 내용은 다음과 같다.

1. Docker 컨테이너는 격리된 환경에서 동작하지만 이미지와 컨테이너 데이터는 호스트의 디스크 공간을 사용한다.
2. 멀티스테이지 빌드는 최종 이미지 크기를 줄이는 데 유용하지만, 빌드 과정에서 필요한 공간까지 제거하지는 않는다.
3. Docker Desktop GUI와 Docker Engine의 실행 상태는 구분해야 한다.
4. Docker 데이터 저장 위치를 변경하면 특정 드라이브의 저장 공간 부담을 줄일 수 있다.
5. 재부팅으로 문제가 해결되었더라도 정확한 장애 원인이 확인된 것은 아닐 수 있다.
6. 장애 복구 후에는 컨테이너 실행 상태뿐 아니라 실제 애플리케이션 및 데이터베이스 동작까지 검증해야 한다.

### 결론

이번 장애는 **호스트 디스크 용량 부족으로 Docker 이미지 빌드가 중단되고, Docker Desktop 실행에도 문제가 발생한 사례**다.

Windows 저장 공간 확보, Docker 데이터 저장 위치 변경, 재부팅을 거쳐 Docker 환경을 정상화했다.

이후 Docker Compose를 재실행하고 PostgreSQL 연결, Flyway 마이그레이션, 회원가입, 로그인 및 데이터 영속성까지 검증했다.

이번 경험을 통해 Docker 장애를 분석할 때 **호스트 자원, Docker Desktop, Docker Engine, 컨테이너 및 애플리케이션 상태를 구분해서 확인해야 한다**는 점을 학습했다.