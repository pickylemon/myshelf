# Docker Compose 파일 병합과 PostgreSQL 초기화 동작

- **유형:** Learning
- **작성일:** 2026-11-12
- **적용 프로젝트:** MyShelf
- **관련 결정:** [ADR — Docker Compose 환경별 설정 분리](../../decisions/013-compose-environment-separation.md)

## 1. 학습 목적

MyShelf의 공통 `compose.yaml`에 로컬 테스트 데이터베이스 초기화 스크립트가 포함되어 있었다. 이를 운영 환경에서 제외하는 방안을 검토하는 과정에서 다음을 이해할 필요가 있었다.

- Compose가 여러 YAML 파일을 읽고 병합하는 기준
- 자동으로 적용되는 `compose.override.yaml`과 `-f`의 차이
- `environment`, `ports`, `volumes`의 병합 방식
- `.env` 값의 치환과 컨테이너 환경변수 전달의 차이
- PostgreSQL 공식 이미지의 최초 초기화 시점과 영속 볼륨의 관계

이 문서는 **Compose 동작 원리 및 검증 방법**을 정리한다. 프로젝트에서 해당 구조를 채택한 근거는 ADR에 기록한다.

## 2. 기본 파일과 Override 파일

Docker Compose는 별도로 파일을 지정하지 않은 기본 실행에서 `compose.yaml`과, 같은 위치에 존재하는 선택적 `compose.override.yaml`을 함께 읽는다.

```text
compose.yaml             # 기본 정의
compose.override.yaml    # 기본 실행 시 자동 병합되는 추가 설정
compose.prod.yaml        # 이름만으로는 자동 병합되지 않는 운영 설정
```

```bash
# 기본: compose.yaml + compose.override.yaml
# (COMPOSE_FILE 등 별도 구성 없음)
docker compose up -d

# 명시: 지정한 파일만 사용
docker compose -f compose.yaml -f compose.prod.yaml up -d
```

**핵심:** `-f`를 명시하면 `compose.override.yaml`이 자동으로 추가되지 않는다. 따라서 두 번째 명령은 로컬 전용 설정을 제외한다.

MyShelf의 로컬 프로필 `local,redis`도 `compose.override.yaml`에 둔다. 기존 `compose.local.yaml`을 명시하는 실행 방식에서 기본 명령으로 통일한 이유는 로컬 빌드와 프로필을 함께 적용하기 위해서다. 공통 파일에서 `build`·`image`를 제거한 뒤 예전처럼 `-f compose.yaml -f compose.local.yaml`만 지정하면, 앱의 빌드·이미지 설정이 없어 유효하지 않은 Compose 구성이 된다.

다른 디렉터리에서 파일을 지정한다면 상대 경로는 일반적으로 **첫 번째 Compose 파일의 위치**를 기준으로 해석한다. 프로젝트 작업 위치와 `COMPOSE_FILE` 설정도 확인해야 한다.

## 3. 병합은 파일 전체 교체가 아니다

여러 파일에서 동일한 서비스를 정의하면 Compose는 서비스 설정을 규칙에 따라 병합한다.

### 3.1. Map 계열: 같은 키는 뒤의 값이 우선

```yaml
# compose.yaml
services:
  app:
    environment:
      REDIS_HOST: redis
```

```yaml
# compose.prod.yaml
services:
  app:
    environment:
      SPRING_PROFILES_ACTIVE: "prod,redis"
```

병합 후 `REDIS_HOST=redis`는 유지되고 운영 파일의 `SPRING_PROFILES_ACTIVE`가 추가된다. 같은 키가 두 파일에 모두 있으면 뒤 파일의 값이 우선한다. **`environment` 전체가 교체되는 것은 아니다.** 실제 로컬 프로필은 `compose.override.yaml`, 운영 프로필은 `compose.prod.yaml`에서 지정한다.

### 3.2. List 계열: 항목별 병합 규칙 확인

일반적인 시퀀스는 추가되기도 하고, 일부 속성은 고유키를 기준으로 병합한다. 특히 `ports`와 `volumes`는 단순히 “뒤 파일이 전부 교체한다”라고 이해하면 안 된다.

- `volumes`의 고유키는 **컨테이너 내부 마운트 대상(`target`)**이다.
- `ports`는 IP·컨테이너 포트·호스트 포트·프로토콜의 조합을 고유키로 사용한다.
- 다른 대상 경로의 볼륨을 뒤 파일에서 추가하면 기존 항목과 함께 유지된다.

MyShelf의 예시:

```yaml
# 공통
services:
  postgres:
    volumes:
      - postgres-data:/var/lib/postgresql/data
```

```yaml
# 로컬 추가
services:
  postgres:
    volumes:
      - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
```

병합 결과에는 `postgres-data`와 초기화 스크립트 마운트 **두 항목 모두** 존재한다. 대상 경로가 서로 다르기 때문이다.

### 3.3. `!override`로 완전히 교체할 수도 있다

```yaml
services:
  postgres:
    volumes: !override
      - postgres-data:/var/lib/postgresql/data
```

`!override`는 기존 속성을 전부 버리고 새 값으로 교체하는 Compose 확장 문법이며, **Docker Compose 2.24.4 이상**에서 사용할 수 있다. `!reset`은 값을 제거할 때 사용한다.

MyShelf에서는 운영에서 로컬 설정을 제거하는 것보다, **처음부터 로컬 전용 설정을 `compose.override.yaml`에만 추가하는 방식**을 선택했다. 그러면 운영 파일에서 볼륨 목록을 강제로 재정의할 필요가 없다.

## 4. `.env`는 컨테이너의 `env_file`과 다르다

다음과 같은 `.env`가 있다고 가정한다.

```dotenv
DB_PASSWORD=example-only
JWT_SECRET=example-only
NATIONAL_LIBRARY_API_KEY=example-only
MYSHELF_IMAGE=ghcr.io/example/myshelf:commit-sha
```

> 예시 값이다. 실제 비밀키·비밀번호를 문서나 Git에 넣지 않는다.

```yaml
services:
  app:
    image: ${MYSHELF_IMAGE:?Set MYSHELF_IMAGE}
    environment:
      JWT_SECRET: ${JWT_SECRET:?Set JWT_SECRET}
      NATIONAL_LIBRARY_API_KEY: ${NATIONAL_LIBRARY_API_KEY:?Set NATIONAL_LIBRARY_API_KEY}
```

- Compose CLI는 `${...}` 표현식을 셸 환경변수 또는 `.env` 값으로 **치환**한다.
- `${VAR:?message}`는 변수가 없거나 빈 값이면 오류를 발생시킨다.
- `.env`에 값을 썼다는 이유만으로 모든 변수가 컨테이너에 자동 전달되지는 않는다. 서비스의 `environment` 또는 `env_file` 등으로 전달 대상을 명시해야 한다.
- 동일 변수가 셸과 `.env`에 모두 있으면 치환 과정에서 **셸 환경변수가 우선**한다.
- `docker compose config`를 출력하면 비밀정보가 치환되어 보일 수 있으므로, 점검 시 주의한다.

MyShelf에서 `JWT_SECRET`, `DB_PASSWORD`, `NATIONAL_LIBRARY_API_KEY`는 비밀정보이고 `MYSHELF_IMAGE`는 배포 대상 지정값이다. 로컬에는 앞의 세 값이 필요하고 운영에는 이미지 지정값도 필요하다. 운영 `.env`는 접근 권한을 제한하고 Git에서 제외한다.

## 5. PostgreSQL 초기화 스크립트는 언제 실행되는가?

PostgreSQL 공식 Docker 이미지는 `/docker-entrypoint-initdb.d` 안의 `.sql`, `.sql.gz`, `.sh` 등을 **초기 데이터 디렉터리가 비어 있는 경우에만** 처리한다.

MyShelf 예시:

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: myshelf
      POSTGRES_USER: myshelf
      POSTGRES_PASSWORD: ${DB_PASSWORD:?Set DB_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
```

최초 실행 흐름은 다음과 같다.

```text
빈 postgres-data 볼륨
    ↓
PostgreSQL 클러스터 초기화
    ↓
POSTGRES_DB=myshelf에 따라 기본 DB 생성
    ↓
/docker-entrypoint-initdb.d 스크립트 실행
    ↓
myshelf_test 생성 SQL이 있다면 추가 DB 생성
```

동일한 볼륨을 재사용한다면 PostgreSQL은 초기화 스크립트를 재실행하지 않는다. 따라서 다음 사실을 구분해야 한다.

- **초기화 스크립트 마운트 제거:** 다음 신규 볼륨의 초기화 때 스크립트가 실행되지 않게 한다.
- **기존 DB 삭제:** 전혀 다른 작업이다. 마운트를 제거한다고 기존 `myshelf_test`가 사라지지는 않는다.
- **`docker compose down -v`:** 볼륨까지 제거하므로 개발 데이터도 삭제할 수 있다. 단순 설정 변경을 위해 실행하지 않는다.

또한 `POSTGRES_DB`는 최초 초기화 때 기본 DB 이름을 지정한다. 이미 초기화된 볼륨에서 환경변수를 변경한다고 기존 DB 이름이 변경되지는 않는다.

## 6. 왜 운영에서 DB·Redis 포트를 게시하지 않아도 되는가?

Compose 내부 네트워크에서는 서비스 이름을 호스트 이름으로 사용할 수 있다.

```text
Spring Boot app
  ├─ jdbc:postgresql://postgres:5432/myshelf
  └─ redis:6379
```

- 컨테이너 간 통신은 서비스 이름과 컨테이너 내부 포트를 사용한다.
- `ports: "127.0.0.1:5432:5432"`는 **호스트에서 컨테이너로 들어오는 경로**를 만든다.
- 애플리케이션이 같은 Compose 네트워크 안에서 PostgreSQL에 접속하는 데 호스트 포트 게시가 필요한 것은 아니다.

로컬에서 DB 관리 도구로 직접 접속하려면 `ports`가 편리하다. 운영에서는 해당 게시를 제거하여 노출 경로를 줄일 수 있다.

## 7. 검증 명령과 각각의 한계

다음 명령은 Compose 파일이 있는 프로젝트 루트에서 실행한다.

**로컬 설정 문법·필수 변수 검증**

```bash
docker compose config --quiet
```

**운영 설정 문법·필수 변수 검증**

```bash
docker compose -f compose.yaml -f compose.prod.yaml config --quiet
```

`--quiet`는 성공 시 결과를 출력하지 않는다. **그러나 문법이 유효하다고 해서 불필요한 볼륨이나 포트가 없는 것은 아니다.** 실제 병합 모델도 검증해야 한다.

**서비스 목록만 확인**

```bash
docker compose -f compose.yaml -f compose.prod.yaml config --services
```

**운영 이미지 이름만 확인**

```bash
docker compose -f compose.yaml -f compose.prod.yaml config --images
```

**실제 볼륨/포트 구성을 비밀값 없이 확인하는 예시 (`jq` 필요)**

```bash
docker compose -f compose.yaml -f compose.prod.yaml config --format json \
  | jq '{postgres_volumes: .services.postgres.volumes, postgres_ports: .services.postgres.ports, redis_ports: .services.redis.ports, app_build: .services.app.build}'
```

확인할 내용:

1. 운영 PostgreSQL의 볼륨에 `/docker-entrypoint-initdb.d`가 없는가?
2. 운영 PostgreSQL·Redis에 호스트 `ports`가 없는가?
3. 운영 `app`에 `build`가 없는가?
4. 운영 `app`의 `image`가 GHCR에 게시한 실제 이미지인가?
5. 로컬의 `docker compose up -d --build`와 통합 테스트가 계속 동작하는가?
6. 앱의 Spring 프로필은 로컬 `local,redis`, 운영 `prod,redis`인가?
7. 앱의 `environment`에 `NATIONAL_LIBRARY_API_KEY`가 선언되어 있는가? 실제 값은 출력하지 않는다.

검증 도중 `docker compose config` 전체 결과를 공유할 때는 치환된 JWT 비밀키·DB 비밀번호·외부 API 키가 유출되지 않도록 주의한다.

## 8. MyShelf에 적용하는 정리

| 구분 | 로컬 | 운영 |
| --- | --- | --- |
| Compose 조합 | `compose.yaml` + `compose.override.yaml` | `compose.yaml` + `compose.prod.yaml` |
| 애플리케이션 이미지 | Dockerfile로 빌드 | GHCR에서 Pull |
| Spring 프로필 | `local,redis` | `prod,redis` |
| DB 초기화 SQL | 마운트 | 마운트하지 않음 |
| PostgreSQL·Redis 호스트 포트 | `127.0.0.1` 바인딩 | 게시하지 않음 |
| PostgreSQL 기본 DB | `myshelf` | `myshelf` |
| 추가 테스트 DB | 최초 초기화 시 `myshelf_test` 생성 가능 | 새 운영 볼륨에선 테스트 초기화 스크립트 미실행 |

**한 줄 요약:** 공통 파일은 서비스가 동작하는 데 필요한 공통 속성을 정의하고, 각 환경은 필요한 차이만 추가한다. Compose의 병합 규칙과 DB 최초 초기화 조건을 알아야 이 분리가 의도대로 동작하는지 검증할 수 있다.

## 참고 자료

- [Docker Docs — Merge Compose files](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/)
- [Compose Specification — Merge / `!override`](https://docs.docker.com/reference/compose-file/merge/)
- [Docker Docs — Environment variable interpolation](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)
- [Docker Docs — `docker compose config`](https://docs.docker.com/reference/cli/docker/compose/config/)
- [Docker Official Image — PostgreSQL](https://github.com/docker-library/docs/blob/master/postgres/README.md)
