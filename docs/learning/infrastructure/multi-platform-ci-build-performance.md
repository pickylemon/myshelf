# 멀티 플랫폼 CI 이미지 빌드 지연과 개선 검토

- **유형:** Learning / 성능 개선 후보 기록
- **작성일:** 2026-11-12
- **상태:** 관찰 및 개선안 정리 (최적화 미적용, 개선 효과 미측정)
- **적용 프로젝트:** MyShelf
- **관련 문서:** [멀티 플랫폼 이미지와 GitHub Actions CI](docker-multi-platform-ci.md)

## 1. 관찰한 현상

기존 CI에 `linux/arm64`를 추가한 뒤 PR의 `verify-image` 작업이 로컬 Gradle 빌드보다 훨씬 오래 걸렸다. `Verify Docker image build` 단계가 수 분 이상 지속되는 것이 관찰되었다.

개발자가 확인한 전체 CI 실행 시간은 약 13분이었다. 다만 **개별 작업·Docker 빌드 단계의 정확한 소요 시간과 캐시 적중률은 아직 분석하지 않았다.** 아래 원인은 Dockerfile 및 CI 구조로부터 추정한 것이며, 실제 로그로 확인해야 한다. 전체 시간을 ARM64 빌드 시간으로 해석하지 않는다.

## 2. 현재 CI의 빌드 경로

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

## 4. 권장 개선 우선순위

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

## 7. 검증 계획

아래 표에 개선 전후 같은 조건의 시간을 기록한다. 미측정 항목에 임의 수치를 채우지 않는다.

| 측정 항목 | 현재(기준선) | 변경 후 | 비교 조건 |
|---|---|---|---|
| 전체 CI 실행 시간 | 약 13분 (개발자 관찰값) | 미측정 | 동일 실행의 작업별 시간·캐시 상태 확인 필요 |
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

## 8. 다음 작업으로 이관

이번 멀티 플랫폼 지원 작업에서는 **호환성 확보를 우선**하고, 빌드 최적화는 측정 결과를 바탕으로 후속 이슈에서 처리한다.

추천 이슈 제목: `ci: 멀티 플랫폼 이미지 빌드 시간 최적화`

## 9. 참고 자료

- [Docker — Multi-platform builds](https://docs.docker.com/build/building/multi-platform/)
- [Docker — Multi-platform image with GitHub Actions](https://docs.docker.com/build/ci/github-actions/multi-platform/)
- [ADR 014 — 멀티 플랫폼 이미지 빌드](../../decisions/014-multi-platform-image-build.md)
