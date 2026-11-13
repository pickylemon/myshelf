# Docker 멀티 플랫폼 이미지와 GitHub Actions CI

- **유형:** Learning
- **작성일:** 2026-11-12
- **적용 프로젝트:** MyShelf
- **관련 결정:** [ADR 014 — 멀티 플랫폼 이미지 빌드](../../decisions/014-multi-platform-image-build.md)

## 1. 학습 배경

MyShelf의 CI는 `ubuntu-latest`에서 Docker 이미지를 빌드한다. 해당 CI에서 기존에 게시한 이미지에는 `linux/amd64` 실행 이미지가 있었지만, 배포 대상 EC2 `t4g.small`은 ARM64 아키텍처를 사용했다.

이미지 Pull 과정에서 `no matching manifest for linux/arm64/v8` 오류가 나타났고, CI가 게시하는 이미지의 지원 플랫폼을 확장했다.

**중요한 구분:** 빌드가 성공했다는 사실과, 실제 ARM64 서버에서 애플리케이션이 정상 실행된다는 사실은 다르다.

## 2. AMD64와 ARM64

| 항목 | AMD64 (`linux/amd64`) | ARM64 (`linux/arm64`) |
|---|---|---|
| CPU 명령어 체계 | x86-64 | AArch64 |
| 대표 환경 | 일반적인 Intel·AMD 서버 | AWS Graviton, Apple Silicon 등 |
| MyShelf에서의 역할 | GitHub Actions 빌드 실행 환경 | 배포 대상 EC2 `t4g.small` |

AMD64는 AMD CPU 전용이라는 뜻이 아니다. Intel의 x86-64 CPU도 AMD64 아키텍처로 분류한다.

Java의 JAR에 들어 있는 바이트코드는 일반적으로 JVM을 통해 플랫폼 독립적으로 실행할 수 있지만, **Docker 이미지에는 CPU별 네이티브 바이너리인 JVM과 운영체제 구성 요소가 포함**된다. 따라서 이미지 전체의 플랫폼 호환성은 별도로 고려해야 한다.

## 3. 멀티 플랫폼 이미지의 개념

하나의 이미지 태그가 여러 플랫폼별 실행 이미지와 연결될 수 있다. Docker는 Pull할 때 현재 서버 플랫폼과 일치하는 이미지를 선택한다.

```text
GHCR: ghcr.io/pickylemon/myshelf:sha-<COMMIT>
        │
        ├── linux/amd64  → AMD64 호스트
        └── linux/arm64  → ARM64 EC2
```

GHCR의 OS/Arch 화면에 `unknown/unknown`이 별도로 표시될 수 있다. 이는 빌드 출처 증명 등 메타데이터인 경우가 있으며, **ARM64 지원 여부는 반드시 `linux/arm64` 항목으로 확인**한다.

## 4. QEMU, Buildx, 플랫폼 지정의 역할

- **QEMU:** AMD64 실행 환경에서 ARM64 명령어를 에뮬레이션하도록 지원한다.
- **Docker Buildx:** BuildKit을 이용해 멀티 플랫폼 이미지 빌드를 수행한다.
- **`platforms`:** 결과 이미지가 지원해야 할 플랫폼 목록을 지정한다.

실제 CI의 주요 변경:

```yaml
# verify-image 및 publish-image 작업에 공통 적용
- name: Set up QEMU
  uses: docker/setup-qemu-action@v4

- name: Set up Docker Buildx
  uses: docker/setup-buildx-action@v3
```

PR 빌드 검증:

```yaml
- name: Verify Docker image build
  uses: docker/build-push-action@v6
  with:
    context: .
    push: false
    platforms: linux/amd64,linux/arm64
    tags: myshelf:ci
```

`main` 이미지 게시:

```yaml
- name: Build and push Docker image
  uses: docker/build-push-action@v6
  with:
    context: .
    push: true
    platforms: linux/amd64,linux/arm64
    tags: ghcr.io/${{ github.repository }}:sha-${{ github.sha }}
```

위 내용은 [실제 CI 워크플로](../../../.github/workflows/ci.yaml)의 관련 설정을 발췌한 것이다. GHCR 로그인은 `publish-image`에서 별도로 수행한다.

## 5. CI 작업별 책임

| 작업 | 실행 시점 | 검증 또는 생성 결과 |
|---|---|---|
| `build-and-test` | PR 및 main Push | `./gradlew clean build --no-daemon`으로 Gradle 빌드·테스트·Spotless 검사 |
| `verify-image` | PR | AMD64·ARM64 이미지 빌드 가능 여부 (`push: false`) |
| `publish-image` | main Push | 커밋 SHA 태그로 멀티 플랫폼 이미지 GHCR 게시 |

`verify-image`는 이미지를 실제 EC2에서 실행해 보는 작업이 **아니다**. Dockerfile 빌드 실패는 잡을 수 있지만 런타임 환경변수, 데이터베이스 연결, HTTP 동작까지 보장하지는 않는다.

## 6. 검증 방법과 결과

1. PR에서 `build-and-test`, `verify-image` 작업의 성공을 확인한다.
2. main 병합 후 `publish-image` 작업의 성공을 확인한다.
3. GHCR의 새 태그 OS/Arch에 `linux/amd64`, `linux/arm64`가 모두 있는지 확인한다.
4. EC2에서 새 태그로 Pull하고 Compose를 실행한다.
5. 컨테이너 상태와 실제 HTTP 응답을 확인한다.

MyShelf에서 2026-11-12에 확인한 예시 태그:

```text
ghcr.io/pickylemon/myshelf:sha-4e651623b2f855f81d4a023d365632358b0d484b
```

이는 **당시 수동 검증에 사용한 버전**이다. 이후 배포에서는 새 커밋 SHA를 사용한다.

## 7. 인증과 플랫폼 문제는 별개

실제 배포 과정에서 다음 순서로 문제를 확인했다.

```text
GHCR 인증 전: unauthorized
    ↓ 인증 성공
기존 AMD64 전용 태그: no matching manifest for linux/arm64/v8
    ↓ 멀티 플랫폼 이미지 새 태그 사용
EC2 ARM64 이미지 Pull 성공
```

- `unauthorized`는 GHCR 패키지 접근 권한을 우선 점검한다.
- `no matching manifest`는 이미지 태그에 대상 플랫폼이 포함되어 있는지 점검한다.
- `curl -I https://ghcr.io/v2/`의 HTTP 405는 HEAD 요청의 메서드 제한을 뜻할 수 있으며, 네트워크 불통의 증거가 아니다.

## 8. 관련 문서와 참고 자료

- [ADR 014 — 멀티 플랫폼 이미지 빌드](../../decisions/014-multi-platform-image-build.md)
- [멀티 플랫폼 CI 빌드 시간 개선 검토](multi-platform-ci-build-performance.md)
- [EC2 수동 배포 Runbook](ec2-manual-deployment.md)
- [Docker — Multi-platform builds](https://docs.docker.com/build/building/multi-platform/)
- [Docker — Multi-platform image with GitHub Actions](https://docs.docker.com/build/ci/github-actions/multi-platform/)
