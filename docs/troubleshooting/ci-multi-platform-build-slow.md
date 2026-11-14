# 멀티 플랫폼 Docker 이미지 빌드 지연 — QEMU 기반 Gradle 실행 비용

- **유형:** Troubleshooting
- **발생 및 분석:** 2026-11-14
- **상태:** 해결 및 검증 완료 — PR·main CI 성능 개선, CD 성공 및 EC2 실행 이미지 SHA 일치 확인
- **대상:** MyShelf, GitHub Actions CI, Docker Buildx, QEMU, Gradle
- **관련 문서:** [멀티 플랫폼 이미지와 CI](../learning/infrastructure/docker-multi-platform-ci.md), [CI 빌드 성능 분석](../learning/infrastructure/multi-platform-ci-build-performance.md)

## 1. 증상

MyShelf CI는 `linux/amd64`, `linux/arm64` 두 플랫폼의 Docker 이미지를 빌드한다. 

Gradle 테스트 Job인 `build-and-test`는 약 2분 이내로 종료됐지만, PR의 `verify-image`는 10분 이상 소요됐다.

| 구분 | 변경 전: CI #47 | 변경 후: CI #49 (1차) | 변경 후: CI #49 (재실행 2차) |
|---|---:|---:|---:|
| `build-and-test` | 1분 45초 | 1분 51초 | 1분 54초 |
| `verify-image` | **10분 45초** | **2분 0초** | **1분 37초** |
| 전체 CI | 12분 37초 | 3분 58초 | 3분 37초 |

세 실행 모두 **PR의 같은 종류의 Job**을 비교했다. `main` Push에서만 실행되는 `publish-image`와 PR의 `verify-image`는 게시 작업 유무가 달라 동일 지표로 직접 비교하지 않았다.

## 2. 원인 분석

CI는 `ubuntu-latest`의 AMD64 Runner에서 Buildx 멀티 플랫폼 빌드를 수행한다.

변경 전 Dockerfile:

```dockerfile
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace
COPY . .
RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`FROM`에 플랫폼을 지정하지 않았으므로 각 대상 플랫폼의 Builder 이미지가 선택된다. 특히 ARM64 대상 빌드에서는 ARM64 JDK로 Gradle을 실행하며, 

AMD64 Runner에서는 이를 위해 QEMU 에뮬레이션을 사용하게 된다. Java 컴파일·JAR 패키징은 CPU 및 입출력 작업을 포함하므로 에뮬레이션 비용이 커질 수 있다.

또한 `build-and-test`와 Docker 이미지 빌드는 별도 Job이다. 

앞선 Job에서 만든 JAR가 Docker Builder Stage로 자동 전달되지 않아 Dockerfile에서 `bootJar`를 다시 수행한다.

**원인 판단 범위:** Dockerfile 변경 전후의 큰 실행 시간 차이가 위 가설을 강하게 뒷받침한다. 

다만 각 플랫폼별 `RUN` 명령의 정확한 소요 시간을 별도로 측정하지 않았으므로 전체 8분 45초가 모두 QEMU 때문이라고 단정하지 않는다.

## 3. 해결

Dockerfile의 **Builder Stage 한 줄만 수정**했다.

```diff
-FROM eclipse-temurin:21-jdk AS builder
+FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS builder
```

- `BUILDPLATFORM`: Docker 빌드를 수행하는 Builder 머신의 플랫폼. 현재 GitHub-hosted Runner에서는 일반적으로 `linux/amd64`.
- `TARGETPLATFORM`: `docker/build-push-action`의 `platforms`에서 지정한 결과 이미지의 플랫폼.
- **변경 효과:** Java JAR를 만드는 Builder Stage는 빌드 플랫폼에서 실행하고, 마지막 Runtime Stage는 대상 플랫폼용 `eclipse-temurin:21-jre`를 선택한다.

MyShelf의 일반적인 Spring Boot JAR가 플랫폼 독립적인 Java 바이트코드를 포함한다는 전제에서 가능한 방식이다. 

프로젝트에 아키텍처별로 다른 네이티브 바이너리를 패키징하는 빌드가 도입된다면 재검토해야 한다.

```text
AMD64 GitHub Actions Runner
 ├─ Builder Stage (BUILDPLATFORM=AMD64) → Gradle bootJar
 ├─ Runtime Stage (TARGETPLATFORM=AMD64) → AMD64 JRE 이미지
 └─ Runtime Stage (TARGETPLATFORM=ARM64) → ARM64 JRE 이미지
```

이 변경이 **CI의 Gradle 빌드 횟수를 반드시 한 번으로 줄인다는 뜻은 아니다.** 

핵심은 ARM64 대상 빌드에서도 Gradle을 ARM64 에뮬레이션 환경에서 실행하지 않도록 한다는 점이다. 

`ci.yaml`의 `platforms: linux/amd64,linux/arm64`와 QEMU 설정은 그대로 유지했다.

## 4. 해결 검증

- CI #47(PR, 변경 전): `verify-image` **10분 45초**.
- CI #49(PR #34, 변경 후): `verify-image` **2분 0초**, 성공.
- CI #49 재실행 Attempt 2: `verify-image` **1분 37초**, 성공.
- 변경 전과 비교해 약 **81~85%** 단축한 결과를 두 번 관찰했다.
- `build-and-test`도 두 실행에서 성공했다.
- 새로운 Buildx 외부 캐시(`cache-from`/`cache-to`)는 도입하지 않았다.

참고로 `.dockerignore`에서 `docs/`와 `*.md`를 제외하고 있으므로, 이전 CI #46과 #48 사이의 문서 수정이 Docker 빌드 컨텍스트에 그대로 포함된 것은 아니다. 

CI #46과 #48의 시간 차이 자체는 이 최적화를 적용하기 전의 실행 시간 변동이며, 외부 캐시가 없던 상태에서 캐시 효과라고 단정할 수 없다.

### main 게시 최종 검증 — CI #50

개발자가 제공한 실행 결과를 반영했다. 문서 갱신 과정에서 GitHub Actions나 EC2를 직접 재검증하지 않았다.

| 항목 | 변경 전 CI #48 | 변경 후 CI #50 | 변화 |
|---|---:|---:|---:|
| `build-and-test` | 1분 42초 | 1분 58초 | 16초 증가 |
| `publish-image` | 8분 21초 | 1분 50초 | 약 78% 단축 |
| 전체 CI | 10분 9초 | 3분 53초 | 약 62% 단축 |

- AMD64·ARM64 이미지 빌드와 기존 CI 검증 단계를 유지했다.
- main 이미지 게시 후 CD 자동 배포도 성공했다.
- 변경 전 CI #46 전체 시간 16분 58초는 참고값이며, 위 성능 비교 기준은 #48이다.
- PR과 main 양쪽에서 개선 효과를 확인했으므로 CI 성능 최적화의 효과 검증은 완료했다. 추가 Docker 캐시 최적화는 당장 진행하지 않는다.

## 5. 최종 검증과 한계

PR 이미지 빌드, main 이미지 게시와 CD 성공에 이어, 개발자가 EC2 실행 이미지 태그와 CI #50의 대상 커밋 SHA가 일치하는 것까지 확인했다. 이번 최적화의 성능·배포 검증은 완료했으며, 다음 인프라 작업은 AWS SSM 전환이다.

이 결과는 전체 비즈니스 API의 운영 검증을 뜻하지 않는다. QEMU 비용이 주요 병목이었다는 판단은 전후 측정이 뒷받침하지만, 플랫폼별 단계 시간을 분리 측정한 것은 아니다.

향후 더 세밀한 원인 분석이 필요하면 Buildx의 단계별 로그와 `RUN ./gradlew` 소요 시간을 측정한다.

## 6. 재발 시 확인 순서

1. 장시간 소요되는 Job이 `build-and-test`인지 `verify-image`/`publish-image`인지 구분한다.
2. `docker/build-push-action`의 `platforms`와 Dockerfile의 Builder `FROM --platform`을 확인한다.
3. Buildx 로그에서 Gradle의 `RUN` 단계, 이미지 Pull·Push의 소요 시간을 비교한다.
4. QEMU가 필요한 아키텍처 종속 빌드가 새로 추가됐는지 확인한다.
5. 외부 캐시 설정이 있다면 적중 여부와 캐시 저장·복원 비용을 구분한다.

## 7. 참고 자료

- [Docker — Multi-platform builds](https://docs.docker.com/build/building/multi-platform/)
- [Docker — Build variables](https://docs.docker.com/build/building/variables/)
