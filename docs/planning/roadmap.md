# MyShelf 개발 로드맵

## 개발 방향

MyShelf는 Spring Boot 기반 백엔드 프로젝트로, 핵심 기능 구현을 우선하면서 인프라와 성능 개선 기술을 단계적으로 적용한다.

| 단계 | 주요 목표 | 상태 |
|---|---|---|
| 1. 백엔드 기반 구축 | 헥사고날 아키텍처, 회원가입, 로그인, JWT 발급 | 완료 |
| 2. 로컬 실행 환경 구축 | PostgreSQL, Docker, Docker Compose, 테스트 DB 분리 및 자동 생성 | 완료 |
| 3. CI 및 배포 기반 구축 | GitHub Actions 기반 빌드·테스트 자동화, Docker 이미지 빌드·게시, Jenkins 기초 실습 | 진행 중 |
| 4. 핵심 기능 개발 | 도서 관리, 개인 서재, 독서 상태 및 기록 | 진행 중: Shelf CRUD, 도서 검색·등록·메모·삭제·독서 상태 변경 구현 |
| 5. 성능 및 비동기 처리 | Redis 활용, 비동기 처리, Kafka 적용 검토 | Redis 검색 세션·ISBN 메타데이터 캐시 적용. 일반 조회 캐시·Kafka는 후속 검토 |
| 6. 운영 배포 및 CD 구축 | 운영 환경 구성, 배포 자동화, 배포 후 상태 검사 및 안정성 검증 | 예정 |

JWT 발급과 OAuth2 Resource Server 기반 요청 인증을 구현했다. Feature 4에서 Shelf CRUD에 인증·소유권 검증을 연결했고, Feature 5에서는 도서 검색·등록·서재별 메모·연결 삭제·독서 상태 변경·Shelf별 목록을 구현했다. 남은 테스트·문서 및 최신 전체 빌드·CI 검증은 별도로 진행한다.

### 기능별 진행 상태

위 표의 단계 번호와 기능 브랜치의 Feature 번호는 별개다.

| Feature | 범위 | 상태 |
|---|---|---|
| Feature 4 | Shelf 생성·목록/상세 조회·수정·삭제, 소유권·중복·DB 정합성 | main 병합 (PR #23) |
| [Feature 5](./feature-5-book-management.md) | 도서 검색·등록, 메모·독서 상태 변경, 연결 삭제, Shelf별 목록 | 기능 구현 완료. 남은 테스트·문서 및 최종 회귀 검증 필요 |
| [Feature 6](./feature-6-book-browsing-and-ordering.md) | 전체 도서·상세 조회, Shelf 내 순서 변경, 검색 응답 보완 | 후속 기능 |
| [Feature 7](./feature-7-transcription.md) | 문장 수집·필사 및 Paper | 후속 기능, 세부 범위 확정 전 |

Feature 4는 Service 단위·웹 계층·PostgreSQL Adapter·실제 JWT API 통합 테스트를 갖췄다. 2026-10-28 확인한 로컬 테스트 결과 파일은 프로젝트 전체 123개 통과(실패·오류·스킵 0)이며, Shelf 테스트만의 개수가 아니다. 로컬 `clean test` 통과와 `build`·Spotless·CI 통과는 구분한다.

Feature 5에는 실제 JWT·PostgreSQL·Redis를 연결하고 외부 API만 Mock으로 대체한 검색·등록 통합 테스트가 있다. CI에는 PostgreSQL·Redis 서비스가 구성되어 있으며, 최신 관리 기능의 테스트와 전체 빌드·CI 결과는 별도로 확인한다. [검색 설계](../architecture/book-search.md), [테스트 전략](../learning/testing/book-search-testing-strategy.md)을 참고한다.

Feature 5 검증 이후에는 CD·배포·배포 환경 수동 검증과 포트폴리오 1차 정리를 우선한다. Feature 6·7 구현을 최초 배포의 선행 조건으로 두지 않는다.

### CI/CD 단계 구분

3단계에서는 GitHub Actions를 이용해 로컬 환경과 독립적인 빌드 및 테스트 자동화 체계를 구축한다.

CI 검증이 완료된 이후에는 Docker 이미지 빌드 및 이미지 저장소 게시를 자동화한다. Jenkins는 기존 CI 파이프라인을 재현하고 비교하는 기초 학습 대상으로 활용한다.

6단계에서는 핵심 기능 개발 이후 실제 운영 환경을 구성하고, 검증된 Docker 이미지를 이용한 배포 자동화와 안정성 검증을 진행한다.

CI/CD의 세부 설계와 구현 상태는 `docs/architecture/ci-cd.md`에서 관리한다.

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
