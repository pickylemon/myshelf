# MyShelf 개발 로드맵

## 개발 방향

MyShelf는 Spring Boot 기반 백엔드 프로젝트로, 핵심 기능 구현을 우선하면서 인프라와 성능 개선 기술을 단계적으로 적용한다.

| 단계 | 주요 목표 | 상태 |
|---|---|---|
| 1. 백엔드 기반 구축 | 헥사고날 아키텍처, 회원가입, 로그인, JWT 발급 | 완료 |
| 2. 로컬 실행 환경 구축 | PostgreSQL, Docker, Docker Compose, 테스트 DB 분리 및 자동 생성 | 완료 |
| 3. CI 및 배포 기반 구축 | GitHub Actions 기반 빌드·테스트 자동화, Docker 이미지 빌드·게시, Jenkins 기초 실습 | CI·멀티 플랫폼 이미지 게시 완료. Jenkins는 후속 학습 |
| 4. 핵심 기능 개발 | 도서 관리, 개인 서재, 독서 상태 및 기록 | Feature 4·5 main 병합. 조회 확장·필사는 후속 기능 |
| 5. 성능 및 비동기 처리 | Redis 활용, 비동기 처리, Kafka 적용 검토 | Redis 검색 세션·ISBN 메타데이터 캐시 적용. 일반 조회 캐시·Kafka는 후속 검토 |
| 6. 운영 배포 및 CD 구축 | 운영 환경 구성, 배포 자동화, 배포 후 상태 검사 및 안정성 검증 | S3·SSM 기반 EC2 CD 전환 및 최초 이관·정상 배포 확인. 실패 복구 검증은 후속 |

JWT 발급과 OAuth2 Resource Server 기반 요청 인증을 구현했다. Feature 4에서 Shelf CRUD에 인증·소유권 검증을 연결했고, Feature 5에서는 도서 검색·등록·서재별 메모·연결 삭제·독서 상태 변경·Shelf별 목록의 구현과 회귀 테스트를 완료했다. Feature 5와 EC2 CD는 main에 병합했다. PR #36에서는 S3·SSM 방식으로 전환했고, 현재 CD는 CI가 게시한 동일 SHA의 이미지와 배포 파일을 사용한다.

### 기능별 진행 상태

위 표의 단계 번호와 기능 브랜치의 Feature 번호는 별개다.

| Feature | 범위 | 상태 |
|---|---|---|
| Feature 4 | Shelf 생성·목록/상세 조회·수정·삭제, 소유권·중복·DB 정합성 | main 병합 (PR #23) |
| [Feature 5](./feature-5-book-management.md) | 도서 검색·등록, 메모·독서 상태 변경, 연결 삭제, Shelf별 목록 | main 병합 (PR #25) |
| [Feature 6](./feature-6-book-browsing-and-ordering.md) | 전체 도서·상세 조회, Shelf 내 순서 변경, 검색 응답 보완 | 후속 기능 |
| [Feature 7](./feature-7-transcription.md) | 문장 수집·필사 및 Paper | 후속 기능, 세부 범위 확정 전 |

Feature 4의 계층별 테스트 범위와 당시 로컬 실행 결과는 [Shelf 테스트 학습·검증 기록](../learning/testing/shelf-testing-strategy.md#12-기존-실행-검증-기록)에서 관리한다. 로드맵에는 테스트 수를 중복 기재하지 않는다.

Feature 5에는 실제 JWT·PostgreSQL·Redis를 연결하고 외부 API만 Mock으로 대체한 검색·등록 통합 테스트와 관리·목록 API의 회귀 테스트가 있다. 로컬 실행 범위와 개발자가 확인한 CI 결과는 [Feature 5 검증 기록](./feature-5-book-management.md#6-구현-단계-및-완료-기준)에서 관리한다. [검색 설계](../architecture/book-search.md), [테스트 전략](../learning/testing/book-search-testing-strategy.md)을 참고한다.

Feature 5 이후 최초 SSH 배포를 검증했고, 2026-11-16 S3·SSM 전환 후 최초 이관과 CD 재실행 성공을 확인했다. 현재는 포트폴리오 1차 정리와 운영 보완을 우선하며, Feature 6·7은 후속 기능으로 유지한다. 배포 검증이 전체 비즈니스 API·외부 공개·자동 롤백 완료를 뜻하지는 않는다.

### CI/CD 단계 구분

3단계에서는 GitHub Actions를 이용해 로컬 환경과 독립적인 빌드 및 테스트 자동화 체계를 구축한다.

CI는 빌드·테스트 후 AMD64·ARM64 이미지를 GHCR에, 동일 커밋의 배포 파일을 S3에 게시한다. 동일 SHA의 이미지는 아키텍처를 확인하여 재사용하고, 같은 S3 아티팩트도 재사용한다. Jenkins는 기존 CI 파이프라인을 재현하고 비교하는 기초 학습 대상으로 활용한다.

6단계에서는 main Push의 CI 성공을 조건으로 OIDC·S3·SSM 기반 EC2 배포와 기본 HTTP 확인까지 연결했다. [SSM 전환 검증 기록](../learning/infrastructure/github-actions-cd.md#8-ssm-전환-및-실제-배포-검증)에 확인한 결과와 한계를 모아 둔다. 전용 상태 점검, 자동 복구의 실제 실패 시나리오 검증, HTTPS·외부 공개 및 모니터링은 후속 과제다.

CI/CD의 세부 구조는 [CI/CD 아키텍처](../architecture/ci-cd.md), 문서의 역할과 읽는 경로는 [문서 구성 및 관리 가이드](../documentation-guide.md)에서 관리한다.

## 진행 원칙

- 핵심 기능과 테스트를 우선하며, 기술 도입 자체를 목표로 하지 않는다.
- Redis, Kafka 등은 실제 요구사항에 따라 적용 여부를 결정한다.
- 개발 순서는 학습 및 프로젝트 진행 상황에 따라 조정한다.
- 구체적인 작업과 진행 현황은 GitHub Issues 및 Pull Request에서 관리한다.
- CI/CD는 빌드·테스트 자동화, 이미지 게시, 운영 배포 자동화를 단계적으로 구축한다.
- 문서에는 계획된 구성과 실제 구현·검증된 구성을 구분하여 기록한다.

## 관련 문서

- `README.md`: 프로젝트 소개 및 실행 방법
- `docs/architecture/`: 아키텍처 설계
- `docs/architecture/ci-cd.md`: CI/CD 설계 및 구성
- `docs/decisions/`: 주요 기술·설계 결정
- `docs/learning/`: 기술 학습 및 적용 과정
- `docs/troubleshooting/`: 문제 분석 및 해결 기록
