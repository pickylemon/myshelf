# GitHub Actions EC2 CD 구축 트러블슈팅

> 대상: MyShelf SSH 기반 CD 구축 및 S3·SSM 전환 과정 · 2026-11-13, 2026-11-16
>
> 원칙: **실제로 관찰한 현상과 확인한 원인을 구별하고, 아직 발생하지 않은 장애는 '가능한 원인'으로만 기록한다.**

## 1. OIDC 역할 인수 실패: `sts:AssumeRoleWithWebIdentity`

### 증상

기존 테스트용 워크플로 `CD SSH Deployment Test`의 `Configure AWS credentials` 단계에서 다음 오류가 발생했다.

```text
Error: Could not assume role with OIDC:
Not authorized to perform sts:AssumeRoleWithWebIdentity
```

### 당시 상황

1. AWS IAM Role의 신뢰 정책을 `main` 브랜치만 허용하도록 수정했다.
2. 기존 `.github/workflows/cd-aws-auth-test.yaml`은 여전히 `cd/ec2-deployment` 브랜치 Push로 실행되고 있었다.
3. 기능 브랜치에 Push한 뒤 오래된 테스트 워크플로가 실행되어 AWS 역할 인수가 거부됐다.

```text
실제 실행 주체: repo:...:ref:refs/heads/cd/ec2-deployment
IAM 허용 주체: repo:...:ref:refs/heads/main
                              → 불일치
```

### 원인

**OIDC 토큰의 `sub`가 IAM Role 신뢰 정책의 `sub` 조건과 일치하지 않았다.** 이번 오류는 SSH·Docker·배포 스크립트 실행 이전에 발생했다.

### 해결

- 더 이상 사용하지 않는 `cd-aws-auth-test.yaml`을 삭제하고 커밋했다.
- 운영 CD 워크플로는 `workflow_run` + `main` CI 성공 조건만 유지했다.
- 최종 `cd.yaml`을 `main`에 병합한 후 역할 인수와 자동 배포가 성공했다.

### 재발 시 확인

1. 실패한 워크플로의 **실제 실행 이벤트·브랜치**를 확인한다.
2. IAM Role 신뢰 정책의 `aud`, `sub`와 비교한다.
3. `role-to-assume`의 Role ARN, OIDC Provider, `id-token: write` 설정을 확인한다.
4. 필요하다면 실제 OIDC 토큰의 `sub` 형식을 **민감정보가 외부에 유출되지 않도록 주의하며** 안전한 방식으로 확인한다.

같은 오류라도 원인이 항상 브랜치 조건인 것은 아니다. Role ARN, OIDC Provider, Audience 불일치도 점검한다.

## 2. 보안 그룹 권한 부족: IAM 정책의 리소스 대상 혼동

### 현상

GitHub Actions가 EC2 보안 그룹 규칙을 조작하는 과정에서 IAM 권한 오류를 경험했다.

### 원인

**EC2 인스턴스 ID와 Security Group 리소스 ID는 다르다.** `ec2:AuthorizeSecurityGroupIngress`와 `ec2:RevokeSecurityGroupIngress` 권한은 정책에서 대상 Security Group ARN과 맞게 허용해야 한다.

### 확인 방법

- 배포에 사용하는 GitHub Variable `EC2_SG_ID`가 인스턴스 ID(`i-...`)가 아닌 보안 그룹 ID(`sg-...`)인지 확인한다.
- IAM Role의 Permission Policy에서 해당 작업에 필요한 Action 및 Resource를 확인한다.
- Role의 신뢰 정책(역할 인수 허용)과 권한 정책(API 호출 허용)을 따로 점검한다.

### 조치 결과

대상 Security Group을 올바르게 지정하도록 수정한 후 임시 SSH 인바운드 규칙 추가·삭제가 성공했다.

## 3. 이전 테스트 워크플로가 PR 체크에 계속 나타남

### 현상

PR에서 CI는 성공하거나 진행 중인데, 이전 CD 테스트 워크플로가 실패로 표시됐다. 화면에는 `CD SSH Deployment Test / deploy (push)`가 남아 있었다.

### 원인

GitHub Actions는 `.github/workflows/`에 있는 **각 YAML 파일을 별도의 워크플로**로 인식한다. 새 `cd.yaml`을 추가해도 기존 테스트 파일을 자동으로 대체하거나 비활성화하지 않는다.

### 해결

```bash
git rm .github/workflows/cd-aws-auth-test.yaml
git commit -m "cd: 임시 SSH 배포 테스트 워크플로 제거"
git push origin cd/ec2-deployment
```

과거에 실패한 워크플로 실행 이력은 파일을 삭제해도 기록으로 남을 수 있다. 중요한 것은 **새 Push로 더 이상 해당 테스트 워크플로가 실행되지 않는지** 확인하는 것이다.

## 4. PR의 CI는 통과했는데 CD는 왜 실행되지 않는가?

### 현상

Draft PR의 `build-and-test`, `verify-image`가 성공했지만 새 EC2 자동 배포는 실행되지 않았다.

### 설명

이는 현재 설정에서 의도된 동작이다.

- PR 검증 CI: `pull_request` 이벤트 기반으로 실행 가능
- CD의 조건: 선행 CI의 이벤트가 `push`이고 브랜치가 `main`이며 워크플로가 성공해야 함
- `workflow_run` CD 파일: 기본 브랜치에 존재해야 실행 가능

따라서 기능 브랜치 Push → PR CI 성공만으로 EC2에 배포하지 않는다. **PR을 `main`에 병합한 이후의 CI 성공**을 기다려야 한다.

### 실제 결과

PR 병합 이후 CI 성공 → CD 자동 실행을 확인했다. 실행 번호·배포 SHA·서버 확인 결과는 [최초 통합 검증 기록](../learning/infrastructure/github-actions-cd.md#7-실제-최초-통합-검증)을 참고한다.

## 5. 배포 오류를 확인할 위치

배포가 실패하면 어느 단계에서 실패했는지 먼저 구분한다.

| 실패 단계 | 우선 확인할 곳 |
| --- | --- |
| CI 빌드·이미지 게시 | GitHub Actions `CI` 로그 |
| S3 아티팩트 게시 | CI의 `publish-artifacts-to-s3` 로그 |
| OIDC 역할 인수 | CD의 `Configure AWS credentials` 단계 및 IAM Role 신뢰 정책 |
| 보안 그룹 임시 규칙 (기존 SSH 방식) | CD의 `Allow runner SSH access` / `Remove runner SSH access` |
| SSH 로그인·SCP (기존 SSH 방식) | `Configure SSH`, `Test EC2 SSH connection`, `Upload deployment files` |
| SSM 원격 실행·릴리스 준비 및 전환 | CD의 `Deploy MyShelf via SSM` 로그 중 상태·종료 코드·표준 출력·오류 출력 |
| 이미지 Pull·Compose·HTTP | SSM 표준 출력·오류 출력 및 EC2 `deployment-history.log` |
| 실제 앱 오류 | EC2 Docker Compose `logs app` |

릴리스 구조로 전환한 EC2에서 사용하는 조회 명령:

```bash
cd /opt/myshelf

tail -n 20 deployment-history.log
readlink -f current
sudo docker compose -p myshelf --project-directory /opt/myshelf \
  --env-file /opt/myshelf/.env \
  -f current/compose.yaml -f current/compose.prod.yaml ps
sudo docker compose -p myshelf --project-directory /opt/myshelf \
  --env-file /opt/myshelf/.env \
  -f current/compose.yaml -f current/compose.prod.yaml logs --tail=200 app
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/v3/api-docs
```

`deployment-history.log`에 `FAILED`가 없다고 해서 GitHub Actions 전체가 성공한 것은 아니다. **배포 스크립트 시작 전의 인증·다운로드·릴리스 활성화 검사 실패는 이 스크립트의 배포 이력에 기록되지 않는다.** `current`가 아직 없다면 아래 7절의 최초 이관 과정을 먼저 확인한다.

## 6. 검증 완료 사실과 미검증 항목

### 이번에 검증한 사실

최초 통합 검증의 결과는 [GitHub Actions CD 7절](../learning/infrastructure/github-actions-cd.md#7-실제-최초-통합-검증)에서 관리한다. EC2에서 상태와 이력을 다시 확인하는 방법은 [배포 검증 명령](../learning/infrastructure/ec2-automated-deployment.md#6-운영-환경-검증-명령)을 참고한다.

### SSH 최초 통합 검증 당시의 미검증 항목

- 배포 실패 시 자동 롤백 (당시 미구현, SSM 전환 코드에는 복구 시도가 추가되었으나 실제 실패 복구는 별도 검증 필요)
- 모든 API의 정상 동작과 데이터 영속성에 대한 종합 테스트
- 비정상 Runner 종료 시 임시 보안 그룹 규칙 자동 청소 보장
- 부하 상황의 응답 지연·장애 감지
- AWS SSM을 이용한 CD (당시 전환 전, 2026-11-16의 최초 이관과 재실행 결과는 아래 7절 참고)

## 7. SSM 첫 배포 실패: `ERROR: Run bootstrap first`

### 관찰한 현상

2026-11-16, PR #36을 `main`에 병합한 뒤 CI #52가 성공했다.

| 작업 | 확인한 결과 |
| --- | --- |
| `build-and-test` | 성공 |
| `publish-image` | 성공 |
| `publish-artifacts-to-s3` | 성공 |
| `verify-image` | Push에서는 의도적으로 생략 |

이후 CD의 `Configure AWS credentials`는 성공했지만, `Deploy MyShelf via SSM`에서 다음 로그가 출력됐다.

```text
SSM status: Failed
Exit code: 1
Release prepared: /opt/myshelf/releases/<신규 SHA>
ERROR: Run bootstrap first
```

**이미지·S3 게시와 새 릴리스 준비는 성공했지만, 기존 운영 파일을 새 디렉터리 구조로 이관하는 절차가 빠져 있었다.**

### 먼저 이해할 구조: 새 파일 준비와 실제 배포는 별개다

기존 SSH 배포는 `/opt/myshelf` 바로 아래에 있는 Compose 파일을 사용했다. 새 배포는 커밋별 디렉터리에 파일을 보관하고, `current` 심볼릭 링크가 가리키는 릴리스의 파일을 사용한다.

심볼릭 링크는 여기서 **사용할 배포 파일 디렉터리를 가리키는 참조**다. `current`를 바꾼다고 실행 중인 컨테이너가 자동으로 교체되지는 않는다. 이후 배포 스크립트가 Docker Compose를 실행해야 새 이미지가 적용된다.

```text
최초 실패 당시 /opt/myshelf/
├── .env
├── compose.yaml               ← 기존 운영 파일
├── compose.prod.yaml
├── deploy-ec2.sh
└── releases/
    └── <신규 SHA>/            ← S3에서 받아 검증까지 마친 새 파일

current 링크는 아직 없음
```

정상적인 호출 관계는 다음과 같다.

```text
CI: 테스트 → GHCR 이미지 게시·재사용 → S3 파일 묶음 게시·재사용
                                      ↓ CI 성공
CD: SSM 명령 → EC2에서 다운로드 → 준비 → 활성화 → 이미지 배포·HTTP 확인
                                 │       │         │
                                 │       │         └─ deploy-ec2.sh
                                 │       └─ activate-release-ec2.sh
                                 └─ prepare-release-ec2.sh
```

준비 스크립트는 새 파일의 구성·내용·Compose 설정을 검증하여 릴리스 디렉터리를 만든다. 활성화 스크립트는 기존 `current`를 확인한 뒤 새 릴리스로 전환하고 배포 스크립트를 호출한다.

### 원인: 새 배포 코드가 요구하는 출발점이 없었다

활성화 스크립트는 `.env`가 있거나 없는지만 보는 것이 아니라, **기존 운영 릴리스를 가리키는 `current`도 있는지** 검사한다.

```bash
[[ -f "$ENV_FILE" && -L "$CURRENT" ]] || {
  echo 'ERROR: Run bootstrap first' >&2
  exit 1
}
```

따라서 이 오류는 `.env` 또는 `current`가 없을 때 발생할 수 있다. 이번에는 EC2의 파일 목록에서 `.env`는 존재했고 `current`만 없었다.

기존 릴리스를 알아야 전환 전후의 파일을 구분하고, 배포 실패 시 이전 파일과 설정으로 복구를 시도할 수 있다. 이 검사에서 멈췄기 때문에 **최초 실패에서는 링크 전환·환경변수 갱신·새 컨테이너 배포가 시작되지 않았다.**

### bootstrap이 하는 일과 S3 묶음에서 제외한 이유

`bootstrap-release-ec2.sh`는 이번 구조 변경을 위한 최초 이관 도구다.

1. 기존 `.env`의 이미지 SHA와 실제 실행 중인 앱의 이미지 태그가 같은지 확인한다.
2. 기존 운영 파일 세 개를 `releases/<기존 SHA>/`에 복사한다.
3. `current`가 해당 디렉터리를 가리키도록 만든다.

이관 과정은 컨테이너를 재생성하거나 `.env`·데이터 볼륨을 변경하지 않는다. 기존 배포 파일도 원래 위치에 남긴다. 복사된 파일은 당시 EC2의 운영 파일 스냅샷이다.

매 배포의 S3 묶음에는 Compose 파일 두 개와 준비·활성화·배포 스크립트, 총 다섯 파일이 들어간다. bootstrap은 기존 구조를 처음 이관할 때만 필요하므로 이 묶음에서 제외했다. **SSM 자체가 bootstrap을 요구하는 것은 아니며, MyShelf의 릴리스 구조를 도입하면서 생긴 절차다.**

다만 최초 이관용 스크립트를 EC2에 별도로 전달하는 절차가 빠졌다. 그 결과 파일을 실행하라는 안내만으로는 진행할 수 없었다. S3 제외 결정과 별개로, 최초 배포 전에 스크립트를 전달·실행하는 준비가 필요했다.

### 실제 해결 과정

아래는 당시 수행한 이관 기록이다. 최초 이관을 완료한 서버에서 매 배포마다 실행할 명령은 아니다.

1. 저장소의 `scripts/bootstrap-release-ec2.sh` 내용을 EC2의 `/opt/myshelf/bootstrap-release-ec2.sh`에 저장했다.
2. 기존 운영 사용자 `ubuntu`로 실행했다.

```bash
cd /opt/myshelf
chmod +x bootstrap-release-ec2.sh
./bootstrap-release-ec2.sh
```

`./파일명`은 실행 권한과 파일 첫 줄의 `#!/usr/bin/env bash`를 사용한다. `bash bootstrap-release-ec2.sh`로 실행하면 파일 자체의 실행 권한은 필요하지 않다.

실행 결과:

```text
Initial release registered: 8acdf393d996d1f6b716b037eff953296632c358
Running containers, .env and Docker volumes were not changed.
```

EC2의 파일 목록에서도 다음 링크가 생성된 것을 확인했다.

```text
current -> /opt/myshelf/releases/8acdf393d996d1f6b716b037eff953296632c358/
```

3. GitHub Actions에서 실패한 CD의 **Re-run failed jobs**를 실행했다. CI는 다시 실행하지 않았다.
4. 사용자가 CD 재실행 성공을 확인했다.

CI에서 이미지와 아티팩트가 이미 게시되었으므로, 재실행한 CD는 같은 SHA의 파일을 내려받아 준비된 릴리스와 비교하고 재사용할 수 있다. 이후 `current`를 새 릴리스로 전환하여 배포를 진행한다.

```text
최초 이관: current 없음 → 기존 SHA를 가리키도록 생성
CD 재실행: current → 기존 SHA → 신규 SHA로 전환 → 배포·HTTP 확인
```

### 확인한 범위와 남은 검증

- CI 성공과 최초 CD 실패 지점은 제공된 GitHub Actions 화면에서 확인했다.
- bootstrap 실행 성공과 기존 SHA의 `current` 생성은 제공된 EC2 출력에서 확인했다.
- 이후 CD 성공은 사용자의 확인을 기록했다. 이 문서 작성 과정에서 EC2에 접속하거나 CD를 재실행하지 않았다.
- 자동 복구 성공, DB 데이터 보존, 모든 API 동작, 동일 SHA의 CI 전체 재실행까지 검증한 것으로 확대하지 않는다.

이후 배포에서는 일반 CD가 준비·전환·배포를 수행한다. 최초 이관 오류가 다시 나타나면 bootstrap을 무조건 실행하기보다 `.env`, `current`, 링크 대상 디렉터리의 상태를 먼저 확인한다.

## 관련 문서

- [GitHub Actions CD](../learning/infrastructure/github-actions-cd.md)
- [GitHub OIDC와 AWS IAM](../learning/infrastructure/github-oidc-aws-iam.md)
- [EC2 자동 배포](../learning/infrastructure/ec2-automated-deployment.md)
- [S3·SSM 배포 전략](../planning/cd-deployment-strategy.md)
- [최초 이관 스크립트](../../scripts/bootstrap-release-ec2.sh)
