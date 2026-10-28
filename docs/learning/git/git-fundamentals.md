# Git 핵심 개념과 커밋 이력 관리

## 1. 학습 배경

MyShelf를 회사 Windows PC와 집 MacBook에서 번갈아 개발하면서 Git의 브랜치, 원격 추적 브랜치, Rebase, Reset 등의 동작을 학습했다.

특히 이미 Push한 커밋 메시지를 수정하는 과정에서 다음 의문이 생겼다.

- `origin/main`은 GitHub 서버의 브랜치인가?
- `fetch`와 `merge`는 왜 별도의 명령어인가?
- 커밋 메시지만 변경해도 왜 해시가 바뀌는가?
- Rebase와 Reset은 어떤 차이가 있는가?
- Rebase 이후 다른 PC에서 단순 Merge하면 왜 문제가 되는가?
- `--force-with-lease`와 `--hard`는 어떤 의미인가?

명령어를 암기하기보다 Git이 커밋과 브랜치를 관리하는 구조부터 이해하는 것을 목표로 했다.

## 2. Git의 기본 구조

Git 작업은 크게 세 영역으로 구분할 수 있다.

| 영역 | 의미 |
|---|---|
| Working Directory | 실제 파일을 수정하는 작업 공간 |
| Staging Area (Index) | 다음 커밋에 포함할 파일 상태를 준비하는 영역 |
| Local Repository | 커밋과 파일 내용 등의 Git 객체 및 참조를 저장하는 로컬 저장소 |

일반적인 작업 흐름은 다음과 같다.

```text
Working Directory
        |
        | git add
        v
Staging Area (Index)
        |
        | git commit
        v
Local Repository
        |
        | git push
        v
Remote Repository (GitHub)
```

- `git add`: 변경된 파일 내용을 Staging Area에 반영한다.
- `git commit`: Staging Area의 내용을 기반으로 새 커밋을 생성한다.
- `git push`: 로컬 커밋을 원격 저장소에 전송하고 원격 브랜치 갱신을 요청한다.

**Git에서 Staging은 커밋할 내용을 선택하는 과정이며, Push는 이미 만들어진 로컬 커밋을 원격에 반영하는 과정이다.**

## 3. Commit과 HEAD

### 3.1 Commit의 정체

커밋은 특정 시점의 프로젝트 파일 상태를 나타내는 스냅샷과 메타데이터를 연결하는 객체다.

주요 정보는 다음과 같다.

- 파일 상태를 가리키는 트리 정보
- 부모 커밋의 해시
- 작성자와 커미터 정보 및 시각
- 커밋 메시지

커밋은 자신의 내용을 기반으로 계산된 해시로 식별된다.

따라서 **커밋 메시지만 변경해도 기존 커밋이 수정되는 것이 아니라 다른 해시를 가진 새 커밋이 만들어진다.**

```text
A ── B ── C

B의 메시지 변경

A ── B' ── C'
```

B가 B'로 바뀌면 부모 커밋을 참조하는 후속 커밋 C도 C'로 재작성된다.

### 3.2 HEAD의 의미

`HEAD`는 현재 체크아웃한 위치를 나타내는 특별한 참조다.

일반적으로는 현재 브랜치를 가리킨다.

```text
A ── B ── C
          ↑
         main
          ↑
         HEAD
```

새 커밋을 생성하면 `main`이 새 커밋으로 이동하고 HEAD도 이를 따라간다.

특정 커밋을 직접 체크아웃해 HEAD가 브랜치 대신 그 커밋을 가리키는 상태를 `Detached HEAD`라고 한다.

자주 사용하는 표현은 다음과 같다.

| 표현 | 의미 |
|---|---|
| `HEAD` | 현재 체크아웃한 커밋 |
| `HEAD^` | 현재 커밋의 첫 번째 부모 |
| `HEAD~2` | 첫 번째 부모를 두 번 따라간 커밋 |
| `8b8968f^` | 해당 커밋의 첫 번째 부모 |

## 4. Branch와 원격 추적 브랜치

### 4.1 Branch의 정체

**Git 브랜치는 특정 커밋을 가리키는 참조다.**

브랜치 자체가 프로젝트 파일을 별도로 복사하여 저장하는 것은 아니다.

```text
A ── B ── C
          ↑
         main
```

새 커밋 D를 생성하면 `main`이 D를 가리키도록 변경된다.

```text
A ── B ── C ── D
               ↑
              main
```

### 4.2 로컬 브랜치, 원격 브랜치, 원격 추적 브랜치

Git을 이해하면서 가장 혼동했던 개념이다.

| 구분 | 위치 | 의미 |
|---|---|---|
| `main` | 내 PC | 직접 작업하는 로컬 브랜치 |
| `origin/main` | 내 PC | 원격 브랜치의 마지막으로 가져온 위치를 나타내는 원격 추적 브랜치 |
| GitHub의 `main` | GitHub 서버 | 실제 원격 저장소의 브랜치 |

여기서 `origin`은 원격 저장소에 부여한 이름이다.

**`origin/main`은 GitHub 서버에 직접 접속하는 명령이나 객체가 아니라, 로컬 Git 저장소에 존재하는 참조다.**

```text
GitHub 서버
  main ────────────→ D

        git fetch origin
               |
               v

내 PC
  origin/main ──────→ D
  main ─────────────→ B
```

`git fetch origin`을 실행하면 필요한 커밋 객체를 가져오고 `origin/main`을 갱신한다.

이때 현재 작업 브랜치인 `main`과 작업 파일은 자동으로 변경되지 않는다.

원격 추적 브랜치는 현재 서버 상태와 언제나 일치하는 것이 아니라 **마지막으로 가져온 원격 상태**를 나타낸다.

## 5. Fetch, Merge, Pull

### 5.1 git fetch

```bash
git fetch origin
```

원격 저장소의 변경사항을 가져와 원격 추적 브랜치를 갱신한다.

현재 작업 브랜치에 자동으로 합치지는 않는다.

### 5.2 git merge

```bash
git merge origin/main
```

현재 브랜치에 `origin/main`의 이력을 병합한다.

원격 추적 브랜치가 현재 브랜치보다 앞서 있고 이력이 갈라지지 않았다면 Fast-forward가 가능하다.

```text
변경 전

main:        A ─ B
origin/main: A ─ B ─ C

변경 후 (Fast-forward)

A ─ B ─ C
        └─ main, origin/main
```

Fast-forward는 새로운 Merge Commit을 생성하지 않고 브랜치 포인터를 앞으로 이동시키는 방식이다.

반대로 양쪽에 별도의 커밋이 생겨 이력이 갈라졌다면 Merge Commit이 만들어질 수 있다.

### 5.3 git pull

```bash
git pull
```

기본적으로 Fetch 이후 Merge 또는 설정된 통합 방식(Rebase 등)을 수행한다.

따라서 단순히 원격 정보를 가져오는 `fetch`와는 다르다.

Fast-forward가 가능한 경우에만 반영하고 싶다면 다음 명령을 사용할 수 있다.

```bash
git pull --ff-only
```

`--ff-only`는 병합 커밋 없이 브랜치를 앞으로 이동할 수 있는 경우에만 성공하며, 이력이 갈라졌다면 중단한다.

## 6. Rebase의 개념

Rebase는 **커밋을 다른 기반 위에 다시 적용하여 새로운 커밋 이력을 만드는 작업**이다.

예를 들어 다음과 같은 이력이 있다고 하자.

```text
A ── B ── C       main
     \
      D ── E      feature
```

feature 브랜치에서 다음 명령을 실행하면:

```bash
git rebase main
```

feature의 변경사항 D와 E를 main의 최신 커밋 C 위에 다시 적용한다.

```text
A ── B ── C ── D' ── E'
                        ↑
                      feature
```

새로운 기반에서 커밋이 만들어지므로 해시가 달라질 수 있다.

Merge는 분기된 이력을 합치는 방식이고, Rebase는 커밋을 다시 적용하여 이력을 재구성하는 방식이다.

**이미 다른 사람과 공유한 커밋을 Rebase하면 다른 개발자의 로컬 이력과 충돌할 수 있으므로 주의해야 한다.**

## 7. Interactive Rebase와 주요 옵션

Interactive Rebase는 어떤 커밋을 어떻게 재작성할지 직접 선택하는 기능이다.

```bash
git rebase -i 8b8968f^
```

`8b8968f^`는 수정하려는 커밋의 부모를 의미한다.

Git은 지정한 기준 커밋 이후부터 현재 HEAD까지의 커밋을 편집 대상으로 표시한다.

```text
pick 8b8968f fix: Shelf 목록 및 상세 조회 기능 구현
pick d12e9b8 docs: ...
pick 05af096 feat: ...
```

특정 커밋의 메시지만 변경하려면 다음처럼 수정한다.

```text
reword 8b8968f fix: Shelf 목록 및 상세 조회 기능 구현
pick d12e9b8 docs: ...
pick 05af096 feat: ...
```

저장하면 Git이 해당 커밋의 메시지를 편집할 수 있도록 별도 편집기를 연다.

주요 명령은 다음과 같다.

| 명령 | 의미 |
|---|---|
| `pick` | 커밋을 그대로 적용 |
| `reword` | 커밋 메시지 변경 |
| `edit` | 해당 커밋에서 멈춰 수정할 기회 제공 |
| `squash` | 이전 커밋과 합치고 메시지 편집 |
| `fixup` | 이전 커밋과 합치되 기본적으로 해당 커밋 메시지 폐기 |
| `drop` | 해당 커밋 제외 |

문제가 발생하면 상황에 따라 다음 명령을 사용한다.

```bash
git rebase --continue  # 충돌 해결 등의 작업 후 진행
git rebase --abort     # 진행 중인 Rebase 취소
```

## 8. Reset의 개념과 옵션

**Reset은 현재 브랜치가 가리키는 커밋 위치를 변경하는 명령어다.**

일반적인 브랜치 작업 상황에서:

```text
A ── B ── C ── D
               ↑
              main
```

다음 명령을 실행하면:

```bash
git reset --hard B
```

브랜치가 B로 이동한다.

```text
A ── B ── C ── D
     ↑
    main
```

C와 D는 브랜치 이력에서 제외되지만 즉시 Git 내부에서 완전히 삭제되는 것은 아니다.

### 8.1 Reset 옵션 비교

| 옵션 | 브랜치 위치 | Staging Area | Working Directory |
|---|---|---|---|
| `--soft` | 이동 | 유지 | 유지 |
| `--mixed` | 이동 | 대상 커밋 상태로 변경 | 유지 |
| `--hard` | 이동 | 대상 커밋 상태로 변경 | 대상 커밋 상태로 변경 |

`--mixed`는 옵션을 생략했을 때의 기본 동작이다.

예를 들어 마지막 커밋만 취소하고 변경사항을 작업 파일에 남기려면:

```bash
git reset --mixed HEAD~1
```

마지막 커밋을 취소하면서 변경사항을 Staging Area에 유지하려면:

```bash
git reset --soft HEAD~1
```

### 8.2 reset --hard 주의사항

```bash
git reset --hard origin/feature/4-shelf-management
```

현재 브랜치의 위치와 Staging Area, 추적 중인 작업 파일을 지정한 커밋 상태에 맞춘다.

이때 미커밋 변경사항이 사라지고, 원격에 없는 로컬 커밋이 현재 브랜치 이력에서 제외될 수 있다.

일반적으로 Untracked 파일은 그대로 남지만, 대상 파일과 충돌하여 방해되는 경우 제거될 수 있다.

따라서 실행 전에 반드시 작업 상태와 보존할 로컬 커밋을 확인해야 한다.

## 9. Push와 강제 Push

| 명령 | 의미 |
|---|---|
| `git push` | 일반적인 원격 브랜치 갱신 |
| `git push --force` | Fast-forward 조건을 무시하고 원격 참조 강제 갱신 |
| `git push --force-with-lease` | 원격 참조가 예상한 상태일 때만 강제 갱신 |

이미 Push한 커밋을 Rebase로 재작성하면 새 이력은 기존 원격 이력의 단순한 연장이 아닐 수 있다.

따라서 일반 Push가 거부될 수 있다.

```bash
git push --force-with-lease origin feature/4-shelf-management
```

`--force-with-lease`는 기본적으로 로컬의 원격 추적 브랜치가 기록한 값과 실제 원격 브랜치 값을 비교한다.

예상과 다르면 강제 갱신을 거부한다.

이는 다른 작업자의 커밋을 실수로 덮어쓰는 위험을 줄이지만, 완전한 보호 장치는 아니다. 원격 추적 브랜치가 백그라운드 Fetch 등으로 갱신되었다면 보호 효과가 약해질 수 있다.

또한 GitHub의 브랜치 보호 규칙에서 Force Push를 금지했다면 이 옵션으로도 우회할 수 없다.

## 10. Stash의 개념과 옵션

Stash는 아직 커밋하지 않은 변경사항을 임시로 보관하는 기능이다.

Rebase 전에 작업 공간을 정리할 때 사용할 수 있다.

```bash
git stash push -u -m "WIP: 학습 문서 작성"
```

| 옵션 및 명령 | 의미 |
|---|---|
| `push` | 변경사항 보관 |
| `-u` | Untracked 파일도 포함 |
| `-m` | Stash 메시지 지정 |
| `git stash list` | 보관 목록 확인 |
| `git stash apply` | 변경사항 복원, Stash 유지 |
| `git stash pop` | 변경사항 복원 후 성공 시 Stash 항목 제거 |
| `git stash pop --index` | 가능한 경우 기존 Staging 상태까지 복원 |

일반적인 Stash는 추적 중인 파일의 Staged 및 Unstaged 변경을 포함하지만, Untracked 파일은 기본적으로 제외한다.

Stash는 임시 작업 보관 수단이므로 장기적인 백업이나 커밋을 대신하는 용도로 사용하지 않는다.

## 11. Git 이력 확인과 복구

### 11.1 로컬과 원격 추적 브랜치 비교

```bash
git fetch origin

git log --oneline HEAD..origin/feature/4-shelf-management
git log --oneline origin/feature/4-shelf-management..HEAD
```

Git에서 `A..B`는 **B에서 도달할 수 있지만 A에서는 도달할 수 없는 커밋**을 의미한다.

| 표현 | 의미 |
|---|---|
| `HEAD..origin/feature/...` | 원격 추적 브랜치에만 있는 커밋 |
| `origin/feature/.....HEAD` | 현재 로컬 HEAD 쪽에만 있는 커밋 |

Rebase로 이력이 재작성됐다면 변경 전 커밋이 로컬에만 있는 커밋처럼 표시될 수 있다.

이는 새로운 작업이 있다는 뜻이 아니라 커밋 해시와 연결 관계가 달라졌기 때문일 수도 있다.

### 11.2 git reflog

```bash
git reflog
```

Reflog는 로컬에서 HEAD나 브랜치 참조가 이동한 기록을 확인하는 데 사용한다.

예를 들어 실수로 Reset한 이전 위치를 찾는 데 도움이 된다.

```bash
git reflog
git show <commit-hash>
```

이전 커밋을 찾았다면 필요한 작업을 복구할 수 있지만, 모든 변경사항의 복구가 보장되는 것은 아니다.

특히 커밋하지 않은 변경사항은 `reset --hard` 이후 복구하기 어려울 수 있다.

### 11.3 git revert

```bash
git revert <commit-hash>
```

Revert는 기존 커밋을 이력에서 제거하는 대신 **해당 변경을 되돌리는 새로운 커밋**을 만든다.

| 명령 | 핵심 차이 |
|---|---|
| `reset` | 브랜치 위치를 변경하여 현재 브랜치의 이력을 이동 |
| `revert` | 기존 이력을 유지하면서 변경을 취소하는 새 커밋 생성 |
| `rebase` | 커밋을 다시 적용하여 이력을 재작성 |

이미 공유된 커밋의 변경사항을 취소해야 한다면 Revert가 일반적으로 더 안전하다.

## 12. 핵심 정리

| 개념 | 기억할 내용 |
|---|---|
| Commit | 프로젝트의 스냅샷과 메타데이터를 연결한 객체 |
| Branch | 특정 커밋을 가리키는 참조 |
| HEAD | 현재 체크아웃한 위치 |
| `origin/main` | 로컬에 존재하는 원격 추적 브랜치 |
| Fetch | 원격 정보 및 필요한 객체를 가져옴 |
| Merge | 분기된 이력을 병합 |
| Rebase | 커밋을 다시 적용해 새로운 이력 생성 |
| Reset | 현재 브랜치의 위치 이동 |
| Revert | 변경을 되돌리는 새 커밋 생성 |
| Stash | 미커밋 변경사항 임시 보관 |
| Reflog | 로컬 참조 이동 기록 확인 |

이번 학습에서 가장 중요했던 사실은 **Git이 커밋 객체와 이를 가리키는 참조를 구분하여 관리한다는 점**이다.

`origin/main`도 로컬의 참조이며, Fetch는 이를 갱신하고, Merge는 이력을 합치며, Rebase는 새로운 커밋 이력을 만들고, Reset은 브랜치 위치를 옮긴다.

**각 명령어가 커밋 객체를 새로 만드는지, 기존 참조를 이동시키는지, 작업 파일까지 변경하는지를 구분하면 Git 이력 관리 명령어를 더 정확하게 이해할 수 있다.**