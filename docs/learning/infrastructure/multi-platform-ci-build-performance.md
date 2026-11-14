# 멀티 플랫폼 CI 이미지 빌드 지연과 개선 검토·검증

- **유형:** Learning / 성능 개선 과정 기록
- **작성일:** 2026-11-12
- **최종 갱신일:** 2026-11-14
- **상태:** 최적화 검증 완료 — PR·main CI 성능 개선, CD 성공 및 EC2 실행 이미지 SHA 일치 확인
- **적용 프로젝트:** MyShelf
- **관련 문서:** [멀티 플랫폼 이미지와 GitHub Actions CI](docker-multi-platform-ci.md), [빌드 지연 트러블슈팅](../../troubleshooting/ci-multi-platform-build-slow.md)

## 1. 관찰한 현상

2026-11-12 첫 분석 당시, CI에 `linux/arm64`를 추가한 뒤 PR의 `verify-image` 작업이 로컬 Gradle 빌드보다 훨씬 오래 걸렸다. `Verify Docker image build` 단계가 수 분 이상 지속되는 것이 관찰되었다.

당시에는 **개별 Docker 빌드 단계의 시간과 전체 CI 실행 시간을 측정하지 않았다.** 이후 2026-11-14에 PR Job별 시간을 측정하고 Dockerfile 변경 효과를 검증했다. 결과는 아래 8절에 기록한다.

## 2. 최적화 전 CI의 빌드 경로

```text
PR 발생
 ├── build-and-test (ubuntu-latest)
 │     └── ./gradlew clean build --no-daemon
 │
 └── verify-image (build-and-test 통과 후)
       ├── linux/amd64 이미지 빌드
       │     └── Dockerfile 내부 ./gradlew bootJar
       └── linux/arm64 이미지 빌드
             └── QEMU 에뮬레이션 환경에서 ./gradlew bootJar

main Push
 ├── build-and-test
 └── publish-image
       └── 동일한 두 플랫폼용 이미지 빌드 및 GHCR 게시
```

Gradle 빌드가 여러 작업에서 반복된다. 앞선 `build-and-test`가 만든 JAR가 현재 이미지 빌드 작업에 자동으로 전달되는 것은 아니다. 기존 Gradle 캐시가 Docker Buildx의 컨테이너 내부 빌드에 자동 공유되는 것도 아니다.

## 3. 왜 ARM64 이미지 빌드가 느릴 수 있는가?

GitHub Actions의 일반적인 AMD64 러너에서 ARM64 이미지를 만들 때 QEMU 에뮬레이션이 사용될 수 있다. ARM64 JVM 프로세스를 에뮬레이션하면서 Java 컴파일 및 압축 등의 CPU 집약적 작업을 수행하면 네이티브 빌드보다 상당히 느릴 수 있다.

추가로 아래 요소를 확인해야 한다.

- Docker 이미지 안에서 Gradle 의존성을 다시 다운로드하는지
- Dockerfile의 레이어 캐시가 CI 실행 간 재사용되는지
- 소스 변경 시 어느 Dockerfile 단계부터 다시 빌드되는지
- 빌드 도구·의존성 캐시가 플랫폼별로 중복되는지
- 러너의 CPU·메모리 제한과 GitHub 호스팅 환경의 자원 경합이 있는지

**주의:** 장시간 실행이 곧 오류를 의미하지는 않지만, 단순히 'QEMU 때문'이라고 확정하기 전에 실행 로그의 단계별 시간을 확인해야 한다.

## 4. 당시 검토한 개선 우선순위

| 순위 | 개선 방안 | 기대 효과 | 유의점 | 현 상태 |
|---|---|---|---|---|
| 1 | 단계별 빌드 시간·캐시 적중 여부 측정 | 실제 병목 확정 | 비교 가능한 기준선 필요 | 미측정 |
| 2 | **Gradle `bootJar`를 CI에서 한 번만 수행하고, JAR를 각 플랫폼 런타임 이미지에 복사** | ARM64 QEMU에서의 중복 Gradle 빌드 제거 | 아티팩트 전달 및 Docker 빌드 컨텍스트 변경 필요 | **우선 검토** |
| 3 | Buildx 원격 캐시(`cache-from`, `cache-to`) 적용 | 변경 범위가 작을 때 이미지 레이어 재사용 | 최초 빌드 및 소스 변경 시 한계 | 미적용 |
| 4 | ARM64 네이티브 빌더·플랫폼별 러너 활용 | 에뮬레이션 부담 축소 | 러너 지원·비용·구성 복잡도 검토 필요 | 장기 후보 |

## 5. 개선안 A: JAR 한 번 빌드하고 런타임 이미지만 멀티 플랫폼으로 구성

핵심은 **Java 컴파일을 플랫폼별로 다시 하지 않는 것**이다.

```text
GitHub Actions: build-and-test
  └── Gradle build / bootJar (한 번)
           ↓
       실행 JAR 아티팩트
           ↓
  ┌────────┴─────────┐
  ▼                  ▼
AMD64 JRE 이미지     ARM64 JRE 이미지
  └────────┬─────────┘
           ↓
   멀티 플랫폼 GHCR 태그
```

예시 런타임 전용 Dockerfile(설계안, 실제 파일 아님):

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY dist/app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

이 Dockerfile은 애플리케이션을 컴파일하지 않고 JAR를 복사한다. `eclipse-temurin:21-jre`가 대상 플랫폼을 지원한다는 것을 전제로, Buildx가 각 플랫폼에 맞는 JRE 기반 이미지를 선택하도록 한다.

구현할 경우 필요한 변경:

1. `build-and-test`에서 생성된 **실행 가능한 Spring Boot JAR**를 아티팩트로 업로드한다.
2. `verify-image`와 `publish-image` 작업에서 아티팩트를 내려받아 `dist/app.jar`처럼 빌드 컨텍스트의 고정 경로에 둔다.
3. `.dockerignore`가 해당 JAR를 제외하지 않는지 확인한다.
4. 이미지를 JAR 복사만 수행하는 런타임 Dockerfile로 빌드한다.
5. PR 빌드와 `main` 게시 빌드가 동일한 커밋·JAR를 사용하는지 확인한다.
6. AMD64·ARM64 이미지의 실행과 EC2 HTTP 응답을 다시 검증한다.

Gradle `build`에 `bootJar`가 포함되는지, `build/libs`에 실행 가능한 JAR와 `plain` JAR가 함께 생성되는지 확인해야 한다. 잘못된 JAR를 배포하지 않도록 아티팩트 경로를 명확히 고정한다.

**예상 효과:** ARM64 이미지 빌드 과정에서 Gradle과 JVM 컴파일을 다시 실행하지 않으므로 큰 개선 가능성이 있다. 실제 절감 시간은 측정 후 판단한다.

## 6. 개선안 B: Buildx 캐시

빌드 구조를 유지한 상태에서도 GitHub Actions 캐시 백엔드를 검토할 수 있다.

```yaml
# 실험용 설정 예시. 현재 ci.yaml에 아직 적용되지 않음.
with:
  cache-from: type=gha
  cache-to: type=gha,mode=max
```

단, 소스가 자주 변경되어 `bootJar` 레이어가 무효화되거나 ARM64 에뮬레이션이 계속 실행된다면 캐시만으로 근본 원인을 해결하기 어렵다. 캐시의 범위, 권한, 크기 및 플랫폼별 적중 여부를 로그로 확인해야 한다.

## 7. 검증 계획 (작성 당시)

아래 표에 개선 전후 같은 조건의 시간을 기록한다. 미측정 항목에 임의 수치를 채우지 않는다.

| 측정 항목 | 현재(기준선) | 변경 후 | 비교 조건 |
|---|---|---|---|
| `build-and-test` 총 시간 | 미측정 | 미측정 | 동일 커밋 또는 유사한 변경 |
| `verify-image` 총 시간 | 수 분 이상 지연 관찰, 정확한 값 미측정 | 미측정 | 캐시 상태 기록 |
| AMD64 이미지 빌드 시간 | 미측정 | 미측정 | Buildx 로그 기준 |
| ARM64 이미지 빌드 시간 | 미측정 | 미측정 | QEMU 사용 여부 기록 |
| `publish-image` 총 시간 | 미측정 | 미측정 | 이미지 게시 포함 |
| EC2 배포 후 HTTP 응답 | 2026-11-12 `HTTP 200` | 재검증 필요 | `/v3/api-docs` |

비교 기준:

- 빌드의 성공 여부가 최우선이다.
- `verify-image`의 두 플랫폼 빌드 검증을 없애는 방식으로 속도를 개선하지 않는다.
- 변경 후에도 GHCR에 `linux/amd64`·`linux/arm64`가 모두 존재해야 한다.
- ARM64 EC2 Pull, 컨테이너 상태, HTTP 응답까지 재검증한다.
- 빌드 분 단위 비용은 GitHub 저장소의 공개 여부·러너·요금제에 따라 달라질 수 있으므로 별도로 확인한다.

## 8. 최적화 적용 및 실제 측정 (2026-11-14)

### 8.1 적용한 변경

당초 5절에서 검토한 'JAR를 CI 아티팩트로 한 번만 만들고 전달'하는 방식 대신, 더 작은 변경인 **Dockerfile Builder Stage의 빌드 플랫폼 고정**을 먼저 적용했다.

```diff
-FROM eclipse-temurin:21-jdk AS builder
+FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS builder
```

- `BUILDPLATFORM`은 BuildKit이 빌드를 수행하는 머신의 플랫폼이다. MyShelf의 GitHub-hosted AMD64 Runner에서는 Builder Stage가 AMD64 JDK로 실행된다.
- 최종 Runtime Stage의 `FROM eclipse-temurin:21-jre`에는 플랫폼을 고정하지 않아 대상 플랫폼별 JRE 이미지가 선택된다.
- `ci.yaml`의 QEMU 설정과 `platforms: linux/amd64,linux/arm64`는 유지했다.
- **ARM64용 이미지 생성을 생략하는 변경이 아니다.** Builder의 Gradle 실행에 필요한 에뮬레이션 비용을 줄이는 변경이다.
- BuildKit의 단계 재사용 여부에 따라 실제 JAR 빌드 횟수는 달라질 수 있다. 'Gradle을 무조건 한 번만 실행한다'는 의미는 아니다.

### 8.2 실행 시간 비교

| CI | 이벤트 | `build-and-test` | `verify-image` | 전체 |
|---|---|---:|---:|---:|
| #47 (변경 전) | PR | 1분 45초 | **10분 45초** | 12분 37초 |
| #49 Attempt 1 | PR | 1분 51초 | **2분 0초** | 3분 58초 |
| #49 Attempt 2 | PR 재실행 | 1분 54초 | **1분 37초** | 3분 37초 |

변경 전 #47 대비 `verify-image`는 **8분 45초~9분 8초(약 81~85%) 단축**됐다. 두 번의 변경 후 실행에서 모두 성공해 개선 효과가 반복 관찰됐다.

이는 QEMU에서 Gradle을 실행하던 비용이 컸다는 가설을 뒷받침하지만, 플랫폼별 개별 빌드 단계의 시간을 따로 측정하지 않았으므로 단축분 전체를 QEMU 비용이라고 단정하지 않는다. `main`의 `publish-image` 측정 결과는 아래에 별도로 기록한다.

#### main 이미지 게시 최종 측정

아래 수치와 CD 성공 여부는 개발자가 제공한 CI #50 확인 결과를 기준으로 한다. 문서 갱신 과정에서 GitHub Actions나 EC2를 직접 재검증하지 않았다.

| 항목 | 변경 전 CI #48 | 변경 후 CI #50 | 변화 |
|---|---:|---:|---:|
| `build-and-test` | 1분 42초 | 1분 58초 | 16초 증가 |
| `publish-image` | 8분 21초 | 1분 50초 | 6분 31초, 약 78% 단축 |
| 전체 CI | 10분 9초 | 3분 53초 | 6분 16초, 약 62% 단축 |

PR뿐 아니라 main 이미지 게시에서도 개선 효과를 확인했다. AMD64·ARM64 빌드와 기존 검증 단계를 유지했고, 이후 CD 자동 배포도 성공했다. `build-and-test`는 빨라지지 않았으며, 이미지 빌드·게시 단계의 단축이 전체 시간 개선으로 이어졌다.

참고로 변경 전 CI #46의 전체 시간은 16분 58초였다. 변경 전 실행끼리도 시간 차이가 있으므로 #46은 참고값으로 두고, 위 비교에서는 #48을 기준선으로 사용한다. 플랫폼별 단계 시간을 분리 측정하지 않았으므로 단축분 전체를 QEMU 비용으로 단정하지 않는다.

### 8.3 캐시와 Docker 빌드 컨텍스트

- 현재 `ci.yaml`의 Docker 빌드 Job에는 외부 캐시용 `cache-from`/`cache-to` 설정이 없다. 이번 성능 개선은 캐시 추가 없이 관찰됐다.
- `.dockerignore`에는 `docs/`, `*.md`, `.gradle`, `build` 등이 있어 문서 변경이 Docker 빌드 컨텍스트에 포함되지 않는다.
- `build-and-test`의 Gradle 캐시가 별도 Docker Builder Stage로 자동 이전되지는 않는다.
- Dockerfile의 `COPY . .` 때문에 Java 소스가 변경되면 이후 `RUN ... bootJar` 레이어 캐시 재사용이 어려울 수 있다. 캐시 도입은 별도 측정 후 판단한다.

### 8.4 검증 경계

PR의 AMD64·ARM64 이미지 빌드와 main의 GHCR 이미지 게시에서 성능 개선을 확인했고, CD 자동 배포도 성공했다. CI 성능 최적화의 효과 검증은 완료한 것으로 판단한다.

개발자가 EC2 실행 이미지 태그와 CI #50의 대상 커밋 SHA가 일치하는 것까지 확인했다. PR 검증부터 main 이미지 게시·CD 배포·실행 이미지 확인까지 이번 최적화의 검증을 완료했다. 이는 전체 비즈니스 API의 운영 검증을 뜻하지 않는다.

자세한 문제·조치 기록: [QEMU 기반 Gradle 빌드 지연](../../troubleshooting/ci-multi-platform-build-slow.md)

## 9. 후속 작업

2026-11-14에 Dockerfile Builder Stage의 플랫폼 선택만 변경하여 PR과 main 이미지 빌드·게시 시간을 단축했다. 상세 결과는 8절을 참고한다.

이번 CI 성능 최적화 작업은 마무리했다. 외부 Buildx 캐시나 JAR 아티팩트 재사용 구조는 당장 추가하지 않는다. 현재 약 4분의 전체 CI 시간을 기준으로 운영하다가 다시 병목이 확인되면 검토한다. 이후 인프라 작업은 AWS SSM 전환으로 이어간다.

## 10. 참고 자료

- [Docker — Multi-platform builds](https://docs.docker.com/build/building/multi-platform/)
- [Docker — Multi-platform image with GitHub Actions](https://docs.docker.com/build/ci/github-actions/multi-platform/)
- [ADR 014 — 멀티 플랫폼 이미지 빌드](../../decisions/014-multi-platform-image-build.md)
