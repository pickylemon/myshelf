# Docker Compose 설정 병합과 독립적인 검증 환경 구성

> 1~7절의 예시와 실행 결과는 초기화 SQL이 공통 `compose.yaml`에 있던 당시 기록이다. 현재 초기화 SQL 마운트는 `compose.override.yaml`에 있다. 현재 파일 조합으로 재현할 때는 [6.7절](#67-현재-compose-구성에서-재현하는-방법)을 따른다.

## 1. 학습 배경

MyShelf에서는 로컬 개발 DB(`myshelf`)와 통합 테스트 DB(`myshelf_test`)를 PostgreSQL에서 분리하여 사용한다.

이후 신규 개발 환경에서 `myshelf_test`가 자동 생성되도록 PostgreSQL 초기화 SQL을 추가했다.

문제는 해당 초기화 SQL이 PostgreSQL 데이터 디렉터리가 비어 있는 최초 초기화 시점에만 실행된다는 점이었다.

기존 개발 환경의 Named Volume에는 이미 데이터가 저장되어 있었으므로, 초기화 SQL을 다시 실행하려면 별도의 신규 데이터 볼륨이 필요했다.

그러나 개발용 Named Volume을 삭제하면 기존 데이터도 사라진다.

따라서 다음 조건을 충족하는 검증 환경을 구성했다.

- 기존 PostgreSQL 컨테이너와 데이터 볼륨은 변경하지 않는다.
- 신규 PostgreSQL 컨테이너와 Named Volume을 생성한다.
- 기존 `compose.yaml`의 PostgreSQL 설정을 최대한 재사용한다.
- 개발 환경과 검증 환경의 컨테이너 이름 및 호스트 포트 충돌을 방지한다.
- 검증이 끝나면 검증용 리소스만 제거한다.

이 과정에서 Docker Compose의 프로젝트 식별, 설정 파일 병합, `!reset` 태그의 동작을 학습했다.

---

## 2. Docker Compose 프로젝트와 리소스 관리

### 2.1. Compose 프로젝트란?

Docker Compose는 여러 서비스를 하나의 프로젝트 단위로 관리한다.

프로젝트에는 다음과 같은 리소스가 포함될 수 있다.

- 컨테이너
- 네트워크
- Named Volume
- Compose 서비스 구성

예를 들어 MyShelf에서 다음 명령어를 실행한다.

```bash
docker compose up -d postgres
```

별도로 프로젝트 이름을 지정하지 않았다면 Docker Compose는 기본 규칙에 따라 프로젝트 이름을 결정한다.

일반적으로 Compose 설정 파일이 위치한 디렉터리 이름을 사용한다.

MyShelf의 프로젝트 디렉터리 이름이 `myshelf`이고 별도의 이름 설정이 없다면, 기본 프로젝트 이름은 `myshelf`가 된다.

```text
Compose 프로젝트: myshelf
├── 서비스: postgres
├── 컨테이너: myshelf-postgres
├── 네트워크: myshelf_default
└── Named Volume: myshelf_postgres-data
```

여기서 `myshelf-postgres`는 `container_name`으로 명시한 이름이다.

컨테이너 이름을 직접 지정하지 않았다면 Compose가 프로젝트 이름, 서비스 이름, 번호를 조합하여 자동 생성한다.

### 2.2. `-p` 옵션으로 프로젝트 이름 지정

`-p`는 Docker Compose 프로젝트 이름을 지정하는 옵션이다.

```bash
docker compose -p myshelf-verify up -d postgres
```

이 명령어는 `myshelf-verify` 프로젝트를 대상으로 PostgreSQL 서비스를 실행한다.

기본 프로젝트인 `myshelf`와는 별도의 Compose 프로젝트로 관리된다.

일반적으로 프로젝트 이름에 따라 다음과 같은 리소스가 생성된다.

```text
Compose 프로젝트: myshelf-verify
├── 서비스: postgres
├── 컨테이너: myshelf-verify-postgres-1
├── 네트워크: myshelf-verify_default
└── Named Volume: myshelf-verify_postgres-data
```

단, 위 컨테이너 이름은 `container_name`을 별도로 지정하지 않은 경우의 예시다.

또한 Named Volume에 `name` 또는 `external`을 명시한 경우 프로젝트 이름만으로 볼륨이 분리된다고 보장할 수 없다.

MyShelf의 `postgres-data`는 별도의 고정 이름이나 외부 볼륨 설정 없이 선언되어 있으므로 프로젝트별로 분리된다.

### 2.3. 프로젝트 이름은 설정 파일의 이름이 아니다

`-p`와 `-f`는 서로 다른 역할을 한다.

| 옵션 | 역할 |
|---|---|
| `-p` | 작업 대상 Compose 프로젝트 지정 |
| `-f` | 명령 실행 시 사용할 Compose 설정 파일 지정 |

예를 들어 다음과 같이 실행한다.

```bash
docker compose -p myshelf-verify -f compose.yaml up -d postgres
```

이 명령어는 다음 의미를 가진다.

- 프로젝트 이름: `myshelf-verify`
- 사용할 설정 파일: `compose.yaml`
- 실행할 서비스: `postgres`

프로젝트 이름은 리소스를 관리하기 위한 식별자다.

특정 YAML 파일의 설정이 프로젝트 이름에 영구적으로 저장되는 것은 아니다.

같은 프로젝트 이름에 서로 다른 설정 파일을 적용하여 `up`을 실행하면, Compose는 새 설정과 기존 리소스 상태를 비교한다.

설정이 달라졌다면 기존 컨테이너를 재생성할 수 있다.

따라서 같은 프로젝트 이름과 같은 서비스 이름을 사용하면서 `-f` 옵션만 변경한다고 독립적인 서비스 인스턴스가 계속 추가되는 것은 아니다.

반면 서로 다른 서비스 이름을 사용한다면 하나의 프로젝트 안에서도 여러 서비스를 함께 관리할 수 있다.

**독립적인 환경을 동시에 실행하려면 서로 다른 Compose 프로젝트 이름을 사용하는 것이 적절하다.**

---

## 3. Compose 프로젝트를 분리해도 발생하는 리소스 충돌

### 3.1. 기존 MyShelf 설정

기존 `compose.yaml`의 PostgreSQL 서비스에는 다음 설정이 있다.

```yaml
services:
  postgres:
    image: postgres:17
    container_name: myshelf-postgres
    ports:
      - "127.0.0.1:5432:5432"
```

여기서 중요한 설정은 두 가지다.

**`container_name`**

```yaml
container_name: myshelf-postgres
```

실제 Docker 컨테이너의 이름을 `myshelf-postgres`로 고정한다.

**`ports`**

```yaml
ports:
  - "127.0.0.1:5432:5432"
```

호스트의 `127.0.0.1:5432`로 들어오는 연결을 컨테이너의 `5432` 포트로 전달한다.

### 3.2. `-p`만 변경하면 왜 충돌할까?

다음 명령어를 실행한다고 가정한다.

```bash
docker compose -p myshelf-verify -f compose.yaml up -d postgres
```

프로젝트 이름은 `myshelf-verify`로 지정했지만, 설정 파일은 기존 `compose.yaml`을 사용한다.

따라서 기존 설정에 선언된 다음 값도 그대로 적용된다.

```yaml
container_name: myshelf-postgres
ports:
  - "127.0.0.1:5432:5432"
```

두 환경을 동시에 실행한다면 다음 문제가 발생한다.

| 항목 | 기존 개발 환경 | 신규 검증 환경 |
|---|---|---|
| 프로젝트 이름 | `myshelf` | `myshelf-verify` |
| 네트워크 | `myshelf_default` | `myshelf-verify_default` |
| Named Volume | `myshelf_postgres-data` | `myshelf-verify_postgres-data` |
| 컨테이너 이름 | `myshelf-postgres` | `myshelf-postgres` |
| 호스트 포트 | 5432 | 5432 |

네트워크와 Named Volume은 프로젝트별로 분리되지만, 고정된 컨테이너 이름과 호스트 포트는 충돌한다.

컨테이너 이름은 동일한 Docker Engine 안에서 중복될 수 없으며, 같은 호스트 주소와 포트를 여러 컨테이너가 동시에 점유할 수도 없다.

**Compose 프로젝트 분리는 리소스의 관리 범위를 구분하는 것이지, 호스트의 포트나 명시적으로 지정한 컨테이너 이름까지 자동으로 변경하는 기능은 아니다.**

---

## 4. 여러 Compose 설정 파일 병합

### 4.1. `-f` 옵션의 역할

Docker Compose는 `-f` 옵션으로 사용할 설정 파일을 지정할 수 있다.

```bash
docker compose -f compose.yaml up -d
```

여러 파일도 함께 지정할 수 있다.

```bash
docker compose \
  -f compose.yaml \
  -f compose.verify.yaml \
  up -d postgres
```

이 경우 두 파일을 순서대로 읽고 병합한다.

- `compose.yaml`: 기본 설정
- `compose.verify.yaml`: 기본 설정에 추가하거나 변경할 설정

파일을 단순히 문자열로 연결하는 것이 아니라, Compose Specification의 병합 규칙에 따라 최종 설정을 구성한다.

### 4.2. 설정 병합 방식

일반적으로 설정값의 형태에 따라 병합 동작이 달라진다.

| 설정 형태 | 일반적인 병합 방식 |
|---|---|
| 단일 값 | 뒤에 지정한 설정으로 대체 |
| 매핑 | 키를 기준으로 병합 |
| 목록 | 항목을 추가하거나 해당 필드의 병합 규칙 적용 |

예를 들어 기본 설정에 다음 값이 있다고 가정한다.

```yaml
services:
  postgres:
    image: postgres:17
    container_name: myshelf-postgres
    ports:
      - "127.0.0.1:5432:5432"
```

검증용 파일을 다음처럼 작성한다.

```yaml
services:
  postgres:
    ports: []
```

이 경우 빈 목록을 지정했다고 해서 기본 파일의 포트 설정이 제거되는 것은 아니다.

Compose의 목록 병합 규칙이 적용되므로 기존 포트 설정이 유지될 수 있다.

또한 검증용 파일에서 `container_name`을 생략하면 기존 파일의 설정을 그대로 사용한다.

즉, 단순히 항목을 생략하거나 빈 목록을 지정하는 방식으로는 기존 설정을 확실하게 제거할 수 없다.

### 4.3. 왜 기존 설정을 제거해야 할까?

이번 검증에서는 기존 PostgreSQL 설정 중 이미지, 환경변수, 볼륨, Healthcheck 등은 그대로 사용하고 싶었다.

반면 아래 두 항목은 제거해야 했다.

- `container_name`: 기존 컨테이너 이름과 충돌
- `ports`: 기존 호스트 포트와 충돌

이처럼 여러 파일을 병합하면서 특정 설정만 의도적으로 제거하기 위해 `!reset`을 사용한다.

---

## 5. `!reset` 태그의 동작 원리

### 5.1. `!reset`이란?

`!reset`은 Docker Compose가 지원하는 YAML 태그다.

여러 Compose 설정 파일을 병합할 때 기존 필드의 값을 해당 필드의 기본값으로 초기화하도록 지시한다.

이번 검증에서는 다음과 같이 사용했다.

`compose.verify.yaml`

```yaml
services:
  postgres:
    container_name: !reset null
    ports: !reset []
```

`!reset` 뒤에 작성한 `null`, `[]`는 각각 값이 없음과 빈 목록을 나타내는 YAML 표현이다.

실제로 병합 과정에서 기존 값을 초기화하도록 만드는 핵심은 `!reset` 태그다.

### 5.2. `container_name: !reset null`

기존 설정:

```yaml
container_name: myshelf-postgres
```

검증용 설정:

```yaml
container_name: !reset null
```

병합 결과 기존 고정 컨테이너 이름이 제거된다.

그러면 Docker Compose가 기본 명명 규칙에 따라 컨테이너 이름을 생성한다.

일반적인 명명 규칙은 다음과 같다.

```text
프로젝트명-서비스명-번호
```

이번 검증 환경에서는 다음 이름이 생성되었다.

```text
myshelf-verify-postgres-1
```

구성 요소는 다음과 같다.

- 프로젝트 이름: `myshelf-verify`
- 서비스 이름: `postgres`
- 컨테이너 번호: `1`

이렇게 하면 기존 `myshelf-postgres` 컨테이너와 이름이 충돌하지 않는다.

### 5.3. `ports: !reset []`

기존 설정:

```yaml
ports:
  - "127.0.0.1:5432:5432"
```

검증용 설정:

```yaml
ports: !reset []
```

병합 결과 호스트 포트 매핑이 제거된다.

단, 이 설정으로 PostgreSQL 서버의 내부 포트까지 제거되는 것은 아니다.

PostgreSQL은 검증용 컨테이너 내부에서도 여전히 `5432` 포트를 사용한다.

달라지는 것은 호스트와의 포트 연결 여부다.

```text
기존 개발용 PostgreSQL
└── 호스트 127.0.0.1:5432 → 컨테이너 5432

검증용 PostgreSQL
└── 호스트 포트 매핑 없음
    └── 컨테이너 내부 5432 사용
```

검증용 PostgreSQL에는 `docker compose exec`로 직접 명령어를 실행할 예정이므로 호스트 포트를 게시할 필요가 없다.

### 5.4. `!reset`은 컨테이너를 초기화하지 않는다

여기서 주의해야 할 점은 `!reset`의 적용 대상이다.

`!reset`은 **Compose 설정 파일을 병합하는 과정에서 설정값을 초기화하는 기능**이다.

Docker 컨테이너나 PostgreSQL 데이터베이스를 초기화하는 명령어가 아니다.

| 명령어 또는 기능 | 역할 |
|---|---|
| `!reset` | Compose 설정값 초기화 |
| `docker compose down` | Compose 컨테이너 및 네트워크 제거 |
| `docker compose down -v` | Compose가 관리하는 볼륨까지 제거 |
| `docker-entrypoint-initdb.d` | PostgreSQL 최초 데이터 디렉터리 초기화 시 스크립트 실행 |

이 네 가지는 서로 다른 계층에서 동작한다.

---

## 6. 실제 MyShelf 검증 과정

### 6.1. 검증 환경 구성

기존 개발 환경의 데이터를 유지하기 위해 별도의 프로젝트 이름을 사용했다.

`compose.verify.yaml`은 일회성 검증을 위해 생성한 임시 파일이다.

```yaml
services:
  postgres:
    container_name: !reset null
    ports: !reset []
```

당시 기본 `compose.yaml`과 검증용 파일을 병합하면 다음과 같은 PostgreSQL 설정이 완성됐다. 현재는 초기화 SQL 마운트를 포함하기 위해 `compose.override.yaml`도 명시해야 한다.

- PostgreSQL 이미지: `postgres:17`
- 데이터베이스 사용자: `myshelf`
- 개발 DB: `myshelf`
- 초기화 SQL 디렉터리 마운트 유지
- PostgreSQL 데이터 Named Volume 유지
- 고정 컨테이너 이름 제거
- 호스트 포트 매핑 제거
- Healthcheck 유지

즉, PostgreSQL 초기화에 필요한 실제 설정은 유지하면서 기존 개발 환경과 충돌하는 항목만 변경했다.

### 6.2. 최종 Compose 설정 확인

컨테이너를 생성하기 전에 두 설정 파일의 병합 결과를 확인할 수 있다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  config
```

`config`는 설정을 해석하고 병합된 결과를 출력한다.

실제 컨테이너를 실행하지 않아도 사용할 수 있다.

여기서 확인할 항목은 다음과 같다.

- `postgres` 서비스의 `container_name` 고정 설정이 제거되었는가?
- `postgres` 서비스의 호스트 포트 매핑이 제거되었는가?
- 초기화 SQL Bind Mount가 유지되었는가?
- Named Volume과 Healthcheck 설정이 유지되었는가?

`config`는 설정 결과를 확인하는 명령이며, 실제 리소스 생성 여부를 검증하지는 않는다.

### 6.3. 별도 프로젝트로 PostgreSQL 실행

실제 실행 명령어:

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  up -d --wait postgres
```

각 옵션의 의미는 다음과 같다.

| 요소 | 의미 |
|---|---|
| `-p myshelf-verify` | 검증용 프로젝트 이름 지정 |
| `-f compose.yaml` | 기존 기본 설정 사용 |
| `-f compose.verify.yaml` | 검증용 설정 병합 |
| `up` | 서비스 생성 및 실행 |
| `-d` | 백그라운드 실행 |
| `--wait` | 실행 상태 및 설정된 Healthcheck의 정상 상태까지 대기 |
| `postgres` | PostgreSQL 서비스만 실행 |

`--wait`는 검증 직후 PostgreSQL에 접속할 예정이므로 준비 상태를 기다리기 위해 사용했다.

다만 Healthcheck가 `healthy`가 되었다는 사실만으로 모든 초기화 SQL의 성공을 별도로 보장하는 것은 아니다. 데이터베이스 생성 여부는 이후 `psql` 명령으로 직접 확인했다.

실제 실행 결과:

```text
Network myshelf-verify_default         Created
Volume myshelf-verify_postgres-data   Created
Container myshelf-verify-postgres-1    Healthy
```

기존 프로젝트와 분리된 네트워크, 볼륨, 컨테이너가 생성되었다.

### 6.4. 컨테이너와 포트 상태 확인

검증용 프로젝트의 컨테이너 상태는 다음 명령으로 확인한다.

```bash
docker compose -p myshelf-verify ps
```

`-p`로 검증용 프로젝트를 지정했기 때문에 기본 `myshelf` 프로젝트가 아니라 `myshelf-verify`에 속한 컨테이너를 조회한다.

검증 환경의 컨테이너 이름은 다음과 같다.

```text
myshelf-verify-postgres-1
```

포트 정보에 다음과 같이 표시될 수 있다.

```text
5432/tcp
```

이는 컨테이너 내부에서 PostgreSQL이 사용하는 포트다.

호스트 포트 매핑을 설정한 경우 표시되는 `127.0.0.1:5432->5432/tcp`와는 다르다.

프로젝트 구분 없이 Docker Engine의 실행 중인 컨테이너를 확인하려면 다음 명령을 사용한다.

```bash
docker ps
```

### 6.5. 테스트 DB 생성 확인

검증용 PostgreSQL 컨테이너에서 `psql`을 실행했다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  exec postgres \
  psql -U myshelf -d myshelf_test \
  -c "SELECT current_database();"
```

실제 실행 결과:

```text
current_database
----------------
myshelf_test
(1 row)
```

추가로 전체 데이터베이스 목록을 조회하여 다음 두 DB가 모두 생성되었음을 확인했다.

| 데이터베이스 | 소유자 |
|---|---|
| `myshelf` | `myshelf` |
| `myshelf_test` | `myshelf` |

이로써 신규 데이터 볼륨에서 PostgreSQL 초기화 SQL을 통한 테스트 DB 자동 생성이 정상 동작함을 확인했다.

### 6.6. 검증용 리소스 정리

검증이 끝난 뒤 다음 명령어를 실행했다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  down -v
```

실제 실행 결과:

```text
Container myshelf-verify-postgres-1  Removed
Volume myshelf-verify_postgres-data  Removed
Network myshelf-verify_default       Removed
```

`-p myshelf-verify`로 지정한 검증 프로젝트의 리소스가 제거되었다.

기존 `myshelf` 프로젝트의 Named Volume은 제거하지 않았다.

다만 `down -v`는 데이터 삭제를 동반할 수 있으므로, 실행 전에 프로젝트 이름과 볼륨 구성을 확인해야 한다. 특히 외부 또는 고정 이름의 공유 볼륨을 사용하는 구성에서는 프로젝트 이름만으로 완전한 격리를 보장할 수 없다.

임시 `compose.verify.yaml` 파일은 검증 후 삭제 대상으로 두었으며, Git에 포함하지 않는다.

### 6.7. 현재 Compose 구성에서 재현하는 방법

현재 공통 파일에는 PostgreSQL Named Volume이 있고, 로컬 Override에는 초기화 SQL 마운트와 호스트 포트가 있다. `-f`를 지정하면 로컬 Override가 자동 포함되지 않으므로, 위 `compose.verify.yaml`을 준비한 뒤 **공통 → 로컬 → 검증용** 순서로 세 파일을 명시한다.

```bash
# 프로젝트 루트에서 실행. README의 로컬 필수 환경변수가 필요하다.
docker compose -p myshelf-verify \
  -f compose.yaml -f compose.override.yaml -f compose.verify.yaml \
  config --quiet

# 기존 개발 볼륨과 다른 신규 검증 볼륨을 사용하는지 먼저 확인한다.
docker compose -p myshelf-verify \
  -f compose.yaml -f compose.override.yaml -f compose.verify.yaml \
  up -d --wait postgres

docker compose -p myshelf-verify \
  -f compose.yaml -f compose.override.yaml -f compose.verify.yaml \
  exec postgres psql -U myshelf -d postgres \
  -c "SELECT datname FROM pg_database WHERE datname IN ('myshelf', 'myshelf_test');"
```

검증용 파일의 `!reset`으로 PostgreSQL의 고정 컨테이너 이름과 호스트 포트를 제거한다. `up`의 대상은 `postgres`뿐이며 앱·Redis는 시작하지 않는다. `config --quiet`는 문법·필수 변수 확인이므로, 실제 병합 결과에서 초기화 SQL 마운트 유지와 이름·포트 제거도 별도로 확인한다. 전체 `config` 출력에는 비밀값이 포함될 수 있다.

검증을 끝내고 **대상이 폐기 가능한 `myshelf-verify` 프로젝트와 볼륨인지 확인한 뒤에만** 아래 명령으로 정리한다. 기존 개발·운영 볼륨에 사용하지 않는다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml -f compose.override.yaml -f compose.verify.yaml \
  down -v
```

위 명령은 현재 파일 구성을 반영한 재현 절차이며, 이번 문서 정리에서 컨테이너 생성·DB 조회·삭제를 다시 실행한 결과는 아니다. 앞 절의 실제 출력은 당시 검증 기록으로 유지한다.

---

## 7. `-p`와 `-f`를 매번 지정해야 할까?

### 7.1. 컨테이너 생성 시점

검증용 컨테이너를 처음 실행할 때는 다음 명령어를 사용한다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  up -d postgres
```

여기서 `-f`는 검증용 설정을 적용하는 데 필요하다.

`-f`를 생략하면 이전에 사용한 설정 파일의 조합을 자동으로 기억하여 적용하지 않는다.

기본 명령으로 실행하면 현재는 `compose.yaml`과 `compose.override.yaml`이 자동 병합되어 기존 `container_name`과 `ports`가 다시 포함될 수 있다. 검증용 파일을 포함한 조합을 유지해야 한다. 아래 비교 명령은 환경 분리 전 예시이며 현재 실행 조합은 6.7절을 따른다.

### 7.2. 이미 생성된 컨테이너 조회

이미 생성된 컨테이너의 상태를 확인하는 경우에는 프로젝트 이름만으로 충분한 경우가 있다.

```bash
docker compose -p myshelf-verify ps
```

또는:

```bash
docker compose -p myshelf-verify logs postgres
```

현재 MyShelf에서는 기본 `compose.yaml`에 `postgres` 서비스가 정의되어 있으므로 위 명령어로 검증용 컨테이너를 조회할 수 있다.

`exec`도 같은 방식으로 사용할 수 있다.

```bash
docker compose -p myshelf-verify \
  exec postgres \
  psql -U myshelf -d myshelf_test \
  -c "SELECT current_database();"
```

다만 이는 현재 기본 설정에서 해당 서비스를 식별할 수 있기 때문에 가능한 것이다.

모든 Compose 구성에서 `-f`를 생략해도 된다는 뜻은 아니다.

### 7.3. 같은 프로젝트 이름으로 다른 설정을 적용하면?

다음 두 명령어를 비교한다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  up -d postgres
```

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  up -d postgres
```

프로젝트 이름과 서비스 이름은 동일하지만 적용할 설정은 다르다.

당시 두 번째 명령어에서는 검증용 설정이 제외되어 기존 컨테이너 이름과 호스트 포트 설정이 다시 적용됐다. 현재 공통 파일에는 PostgreSQL 호스트 포트와 초기화 SQL 마운트가 없고 앱의 이미지·빌드 설정도 환경별 파일에 있으므로, `-f compose.yaml`만으로 현재 로컬 구성을 재현할 수 없다.

Compose는 새로운 설정과 실제 컨테이너 상태를 비교하여 컨테이너 재생성을 시도할 수 있다.

따라서 **`-p`는 프로젝트를 식별하지만, 그 프로젝트에 특정 `-f` 파일 조합이 영구적으로 저장되는 것은 아니다.**

### 7.4. 프로젝트와 컨테이너 조회 명령어

| 명령어 | 조회 대상 |
|---|---|
| `docker compose ps` | 현재 기본 Compose 프로젝트의 실행 중인 컨테이너 |
| `docker compose -p myshelf-verify ps` | `myshelf-verify` 프로젝트의 실행 중인 컨테이너 |
| `docker compose ps -a` | 현재 Compose 프로젝트의 중지된 컨테이너까지 포함 |
| `docker compose ls -a` | 중지된 프로젝트를 포함한 전체 Compose 프로젝트 |
| `docker ps` | Docker Engine에서 실행 중인 전체 컨테이너 |
| `docker ps -a` | Docker Engine의 중지된 컨테이너까지 포함 |

`docker compose ls`와 `docker compose ps`의 차이도 중요하다.

- `ls`: Compose 프로젝트 조회
- `ps`: Compose 서비스 컨테이너 조회

### 7.5. 리소스 삭제 시 주의사항

현재 MyShelf 구성에서는 프로젝트 이름만 지정해 검증용 리소스를 조회하거나 제거할 수 있는 경우가 있다.

그러나 설정 파일에 따라 서비스 및 볼륨 구성이 달라질 수 있으므로, 리소스를 제거할 때는 생성 시 사용한 Compose 설정을 동일하게 지정하는 것이 안전하다.

특히 볼륨 삭제까지 수행하는 다음 명령어에서는 명시적으로 프로젝트와 설정 파일을 지정했다.

```bash
docker compose -p myshelf-verify \
  -f compose.yaml \
  -f compose.verify.yaml \
  down -v
```

이렇게 하면 제거 대상 프로젝트와 적용할 설정의 범위를 명령어에서 직접 확인할 수 있다.

---

## 8. 학습 정리

이번 MyShelf의 테스트 DB 자동 생성 검증을 통해 다음 내용을 학습했다.

1. **Compose 프로젝트 이름은 리소스를 식별하고 묶어 관리하기 위한 기준이다.** `-p`로 프로젝트 이름을 지정할 수 있으며, 별도 지정이 없으면 기본 규칙에 따라 이름이 결정된다.

2. **프로젝트 이름이 달라도 모든 리소스가 자동으로 격리되는 것은 아니다.** 프로젝트별 네트워크와 Named Volume은 분리할 수 있지만, 고정된 컨테이너 이름과 호스트 포트는 충돌할 수 있다.

3. **`-f`는 실행 시 적용할 설정을 지정한다.** 여러 Compose 파일을 순서대로 읽고 병합할 수 있으며, 기존 프로젝트 이름에 설정 파일 조합이 영구적으로 저장되는 것은 아니다.

4. **`!reset`은 설정 병합 과정에서 기존 필드의 값을 초기화한다.** 컨테이너나 DB를 초기화하는 명령어와는 다르다.

5. **컨테이너가 이미 생성된 뒤에는 프로젝트 이름으로 조회하고 관리할 수 있다.** 단, 새로운 `up` 명령에서 설정을 다시 평가하므로 기존 설정을 유지하려면 적절한 `-f` 옵션이 필요하다.

6. **검증을 위해 기존 개발 데이터를 삭제할 필요는 없다.** 별도의 Compose 프로젝트와 신규 Named Volume을 이용하면 개발 환경을 보존하면서 최초 초기화 동작을 검증할 수 있다.

이번 검증에서 가장 중요한 점은 **Compose 프로젝트의 분리와 Docker 리소스의 실제 격리는 동일한 개념이 아니라는 것**이다.

프로젝트 이름, 컨테이너 이름, 호스트 포트, Named Volume의 관리 범위를 구분하고 필요한 설정만 재정의함으로써 기존 개발 환경과 충돌하지 않는 검증 환경을 구성할 수 있었다.

---

## 9. 관련 문서

- [Docker Compose를 이용한 PostgreSQL 로컬 개발 환경 구성](docker-compose-postgresql.md) — Docker 기본 구조, Named Volume, PostgreSQL 최초 초기화
- [PostgreSQL 테스트 DB 자동 생성 누락 문제](../../troubleshooting/postgres-test-database-initialization.md) — 문제 원인, 해결 과정 및 검증 결과
- [ADR 009 — 개발 환경 데이터베이스를 H2에서 PostgreSQL로 전환](../../decisions/009-migrate-local-db-to-postgresql.md) — PostgreSQL 전환과 테스트 DB 분리 결정
