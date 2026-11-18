# ADR 014: GHCR 이미지의 AMD64·ARM64 멀티 플랫폼 빌드 지원

- **상태:** Accepted
- **기록일:** 2026-11-12
- **관련 작업:** CI 멀티 플랫폼 지원 PR #29, `cd/ec2-deployment`
- **대상:** GitHub Actions CI, Docker Buildx, GHCR, AWS EC2

## 1. 배경

MyShelf는 GitHub Actions에서 Docker 이미지를 빌드하여 GHCR에 게시하고, AWS EC2에서 이미지를 내려받아 Docker Compose로 실행한다. 배포 서버는 AWS Graviton 기반 `t4g.small`로, CPU 아키텍처가 **ARM64**이다.

기존 CI는 일반적인 `ubuntu-latest` 실행 환경에서 이미지 빌드를 수행했고, GHCR에서 확인된 실행 이미지의 플랫폼은 `linux/amd64`뿐이었다. `unknown/unknown` 항목은 ARM64 실행 이미지가 아니라 출처 증명 등의 메타데이터일 수 있다.

EC2에서 기존 이미지 태그를 Pull하려고 하자 다음 오류가 발생했다.

```text
no matching manifest for linux/arm64/v8 in the manifest list entries
```

이는 GHCR 접근 권한을 해결한 뒤 나타난 오류이며, **비공개 패키지 인증 문제와 CPU 아키텍처 불일치는 별개 문제**임을 확인했다.

## 2. 결정

기존 GitHub Actions CI의 이미지 검증·게시 단계에서 Docker Buildx와 QEMU를 사용하여 `linux/amd64,linux/arm64` 두 플랫폼용 이미지를 빌드한다.

- PR의 `verify-image`: 두 플랫폼을 **빌드만 검증**하고 게시하지 않는다(`push: false`).
- `main` Push의 `publish-image`: 두 플랫폼용 이미지를 **동일한 커밋 SHA 기반 태그**로 GHCR에 게시한다.
- `build-and-test`: 기존 Gradle 빌드·테스트 흐름을 유지한다.
- 런타임에서 AMD64 이미지를 ARM64로 강제 실행하는 방식은 채택하지 않는다.
- 최종 실행 검증은 ARM64 EC2에서 별도로 수행한다.

### 대상 파일

[`.github/workflows/ci.yaml`](../../.github/workflows/ci.yaml)

```yaml
- name: Set up QEMU
  uses: docker/setup-qemu-action@v4

- name: Set up Docker Buildx
  uses: docker/setup-buildx-action@v3

- name: Build and push Docker image
  uses: docker/build-push-action@v6
  with:
    context: .
    push: true
    platforms: linux/amd64,linux/arm64
    tags: ghcr.io/${{ github.repository }}:sha-${{ github.sha }}
```

위 예시는 게시 단계의 핵심만 보여준다. 실제 워크플로에는 `checkout`, GHCR 로그인, 권한 설정 등이 포함된다.

## 3. 검토한 대안

| 대안 | 장점 | 제약 | 판단 |
|---|---|---|---|
| EC2를 AMD64 인스턴스로 교체 | 기존 단일 플랫폼 이미지를 그대로 사용 | 기존 서버 구성 변경 및 인스턴스 선택 제약 | 미채택 |
| ARM64 전용 이미지만 게시 | 현재 EC2에는 충분 | AMD64 로컬·서버 환경에서 동일 태그 재사용이 어려움 | 미채택 |
| QEMU + Buildx 멀티 플랫폼 빌드 | 현재 CI 구조를 유지하면서 두 아키텍처 지원 | 에뮬레이션 빌드가 느릴 수 있음 | **채택** |
| 플랫폼별 네이티브 빌더 사용 | CPU 집약적인 빌드를 빠르게 처리할 가능성 | 러너·빌더 운영 복잡도 상승 | 후속 개선 후보 |

## 4. 결과와 제약

- CI 변경이 `main`에 반영되었고, 새로운 GHCR 이미지에서 `linux/amd64`, `linux/arm64`가 확인되었다.
- ARM64 EC2에서 새 태그의 이미지 Pull과 컨테이너 실행에 성공했다.
- EC2 내부에서 `GET /v3/api-docs` 요청에 `HTTP 200`을 확인했다.
- 위 HTTP 응답은 **배포된 애플리케이션의 기본 응답 확인**이며, 도서 검색·등록 기능 전체 또는 DB·Redis의 모든 기능을 검증한 것은 아니다.
- 최초 멀티 플랫폼 구성에서는 ARM64 이미지의 Builder도 ARM64로 실행되어 QEMU에서 Gradle `bootJar`를 실행하는 비용이 있었다. 이후 Dockerfile에 `FROM --platform=$BUILDPLATFORM`을 적용해 Builder를 빌드 머신의 플랫폼으로 실행한다. Runtime은 대상 플랫폼별 JRE를 유지한다. [최적화 적용과 측정 결과](../learning/infrastructure/multi-platform-ci-build-performance.md#8-최적화-적용-및-실제-측정-2026-11-14)를 참고한다.
- 기존에 빌드한 이미지는 AMD64만 지원하므로 ARM64 기반 EC2에서는 실행할 수 없다. 멀티 플랫폼 빌드 적용 후 새로 생성한 이미지를 사용해야 한다.

## 5. 관련 문서

- [멀티 플랫폼 이미지와 CI 실행 원리](../learning/infrastructure/docker-multi-platform-ci.md)
- [멀티 플랫폼 CI 빌드 시간 개선 검토](../learning/infrastructure/multi-platform-ci-build-performance.md)
- [EC2 수동 배포 Runbook](../learning/infrastructure/ec2-manual-deployment.md)

## 6. 참고 자료

- [Docker — Multi-platform builds](https://docs.docker.com/build/building/multi-platform/)
- [Docker — Multi-platform image with GitHub Actions](https://docs.docker.com/build/ci/github-actions/multi-platform/)
