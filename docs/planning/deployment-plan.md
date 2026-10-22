# MyShelf 운영 배포 계획 (초안)

- 작성일: 2026-10-22
- 상태: **설계 방향 정리 / 운영 배포 미구현**
- 목적: 향후 온프레미스 모사 환경과 AWS EC2 배포를 진행할 때 참고할 기본 원칙과 실습 순서를 기록한다.

## 1. 현재까지 완료한 작업

- Docker 이미지 빌드 및 실행 검증
- GitHub Actions 기반 CI 구성
- GHCR(GitHub Container Registry) 이미지 게시 및 실행 검증
- 개발용·통합 테스트용 PostgreSQL 환경 분리

> 운영용 Docker Compose, 서버 배포 및 CD는 아직 구현하지 않았다.

## 2. 배포 대상 및 학습 범위

| 환경 | 목적 | 배포 방식(계획) |
| --- | --- | --- |
| 맥북 내 Linux VM | 온프레미스 운영 서버 환경 모사 | SSH 접속 후 수동 배포 |
| AWS EC2 | 클라우드 운영 환경 경험 및 포트폴리오 | 수동 배포 검증 후 GitHub Actions 기반 CD |

- VM은 실제 독립 물리 서버가 아닌 온프레미스 *모사 환경*이다.
- Jenkins는 별도 기초 실습 대상으로 두고 운영 배포의 필수 구성 요소에 포함하지 않는다.
- 우선 Spring Boot 애플리케이션과 PostgreSQL을 Docker Compose로 실행한다. AWS RDS 도입은 후속 검토 사항이다.

## 3. 공통 배포 원칙

1. **동일한 이미지 사용**: GitHub Actions에서 테스트·빌드하여 GHCR에 게시한 이미지를 두 운영 환경에서 사용한다. 운영 서버에서 애플리케이션을 다시 빌드하지 않는다.
2. **설정의 버전 관리**: `compose.prod.yaml`은 MyShelf Git 저장소에 보관한다. 초기에는 맥북 VM과 EC2에서 최대한 같은 파일을 사용한다.
3. **환경 차이 분리**: 도메인, 포트, 자격 증명 등 환경별 값은 환경변수나 별도 설정으로 분리한다. 운영 비밀번호·JWT 비밀키·실제 `.env` 파일은 Git에 커밋하지 않는다.
4. **서버가 이미지 다운로드**: 운영 서버가 GHCR에서 직접 이미지를 Pull한다. 맥북에서 이미지를 내려받아 SCP로 전송하지 않는다.
5. **배포 버전 고정**: 운영 배포 시 `latest`에만 의존하지 않고 검증된 커밋 기반 이미지 태그 또는 다이제스트를 지정하는 방향으로 설계한다.
6. **데이터 영속성**: PostgreSQL 데이터는 컨테이너 수명과 독립된 영속 볼륨에 보관한다. 백업·복구 방법은 실제 운영 배포 전에 결정한다.

### 파일 구성(예정)

```text
myshelf/
├── compose.yaml              # 기존 로컬 개발 환경
├── compose.prod.yaml         # 운영 환경 공통 구성(추후 작성)
├── Dockerfile
├── .github/workflows/        # 기존 CI, 향후 CD 추가 검토
└── docs/
    ├── learning/           # 실습 후 학습 기록
    └── planning/
        └── deployment-plan.md   # 이 문서
```

실제 환경별 구성이 크게 달라지는 경우에만 추가 Compose 오버라이드 파일을 검토한다. 현 단계에서는 파일을 미리 분리하지 않는다.

## 4. 배포 흐름(예정)

```text
GitHub 저장소
    │
    ▼
GitHub Actions (CI: 테스트 → 이미지 빌드·게시)
    │
    ▼
GHCR
    ├──▶ 맥북 VM: 수동 명령으로 Pull → Compose 실행
    └──▶ AWS EC2: 수동 배포 검증 → 이후 CD로 Pull·실행 자동화
```

### 4.1 맥북 VM — 수동 배포

1. VM에 Linux, Docker 및 Docker Compose 실행 환경을 준비한다.
2. 맥북에서 VM에 SSH로 접속한다.
3. VM에 운영용 Compose 파일과 비밀 설정을 준비한다.
4. VM에서 GHCR 이미지를 Pull하고 `docker compose up -d`로 실행한다.
5. 애플리케이션 정상 동작, 재시작 및 DB 데이터 보존을 확인한다.

### 4.2 AWS EC2 — 수동 배포 후 CD

1. EC2, 네트워크 및 스토리지를 구성하고 동일한 이미지로 수동 배포를 검증한다.
2. 불필요한 인바운드 포트는 개방하지 않는다. 관리 접속은 AWS Systems Manager Session Manager 등을 우선 검토한다.
3. CD는 GitHub Actions에서 AWS API를 통해 Systems Manager에 배포 명령을 요청하는 방식을 우선 검토한다.
4. EC2가 GHCR에서 이미지를 직접 Pull하고 Docker Compose로 교체하도록 한다.
5. 배포 트리거는 최초에는 수동 승인·실행 가능한 방식으로 시작하고, 정상 동작 확인 후 자동화 범위를 결정한다.

> Systems Manager 활용에는 EC2 측 Agent, IAM 권한, AWS 서비스 연결 경로 등의 선행 조건이 있다. 구체적인 구성은 구축 단계에서 검증한다.

## 5. 구현 시 확인할 사항

- [ ] 맥북 VM과 EC2의 CPU 아키텍처 및 GHCR 이미지 플랫폼 호환성 확인
- [ ] 운영 전용 Spring Profile, 환경변수 및 Secret 관리 방식 확정
- [ ] 운영용 Compose 작성 및 Git 버전 관리
- [ ] PostgreSQL 데이터 백업·복구 절차 검증
- [ ] 헬스 체크, 로그 확인, 장애 시 이전 이미지로 되돌리는 절차 검증
- [ ] EC2 인바운드 규칙, IAM 최소 권한, GitHub Actions의 AWS 인증 방식 검토
- [ ] AWS 비용·스토리지 정리 기준 마련

## 6. 진행 순서

1. Feature 3 개발을 우선 진행한다. Jenkins 기초 실습은 별도 병행 가능 항목으로 둔다.
2. 맥북 VM에 수동 배포하여 운영용 Compose와 배포 절차를 검증한다.
3. AWS EC2에 동일한 이미지와 유사한 구성으로 수동 배포한다.
4. GitHub Actions 기반 EC2 CD를 구축한다.
5. 두 환경의 차이, 보안 판단, 장애 대응 과정을 문서화한다.

## 7. 아직 결정하지 않은 사항

- EC2 인스턴스 종류 및 운영 비용 범위
- 외부 공개 방식(도메인, HTTPS, 리버스 프록시 등)
- PostgreSQL을 EC2 내부 컨테이너로 유지할지, 이후 RDS로 분리할지
- CD 구체적 워크플로, 배포 승인 및 롤백 정책

이 문서는 확정된 운영 명세가 아니라 **추후 배포 구현을 위한 설계 초안**이며, 실습 결과에 따라 갱신한다.
