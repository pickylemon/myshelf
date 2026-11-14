# GitHub Actions EC2 CD 구축 트러블슈팅

> 대상: MyShelf SSH 기반 CD 구축 과정 · 2026-11-13
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
| OIDC 역할 인수 | CD의 `Configure AWS credentials` 단계 및 IAM Role 신뢰 정책 |
| 보안 그룹 임시 규칙 | CD의 `Allow runner SSH access` / `Remove runner SSH access` |
| SSH 로그인·SCP | `Configure SSH`, `Test EC2 SSH connection`, `Upload deployment files` |
| 이미지 Pull·Compose·HTTP | CD의 `Deploy MyShelf` 로그와 EC2 `deployment-history.log` |
| 실제 앱 오류 | EC2 Docker Compose `logs app` |

EC2에서 사용하는 명령:

```bash
cd /opt/myshelf

tail -n 20 deployment-history.log
sudo docker compose -f compose.yaml -f compose.prod.yaml ps
sudo docker compose -f compose.yaml -f compose.prod.yaml logs --tail=200 app
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/v3/api-docs
```

`deployment-history.log`에 `FAILED`가 없다고 해서 GitHub Actions 전체가 성공한 것은 아니다. **스크립트 시작 전의 AWS 인증·SSH 접속 실패는 EC2 배포 이력에 기록되지 않는다.**

## 6. 검증 완료 사실과 미검증 항목

### 이번에 검증한 사실

최초 통합 검증의 결과는 [GitHub Actions CD 7절](../learning/infrastructure/github-actions-cd.md#7-실제-최초-통합-검증)에서 관리한다. EC2에서 상태와 이력을 다시 확인하는 방법은 [배포 검증 명령](../learning/infrastructure/ec2-automated-deployment.md#6-운영-환경-검증-명령)을 참고한다.

### 아직 검증하지 않은 기능

- 배포 실패 시 자동 롤백 (**구현하지 않음**)
- 모든 API의 정상 동작과 데이터 영속성에 대한 종합 테스트
- 비정상 Runner 종료 시 임시 보안 그룹 규칙 자동 청소 보장
- 부하 상황의 응답 지연·장애 감지
- AWS SSM을 이용한 CD (**전환 전**)

## 관련 문서

- [GitHub Actions CD](../learning/infrastructure/github-actions-cd.md)
- [GitHub OIDC와 AWS IAM](../learning/infrastructure/github-oidc-aws-iam.md)
- [EC2 자동 배포](../learning/infrastructure/ec2-automated-deployment.md)
