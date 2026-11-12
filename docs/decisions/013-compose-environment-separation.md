# ADR: Docker Compose 환경별 설정 분리와 배포 이미지 사용 방식

- **상태:** Accepted
- **작성일:** 2026-11-12
- **관련 작업:** `infra/compose-env-separation`
- **후속 작업:** `cd/ec2-deployment`
- **대상:** MyShelf의 Spring Boot, PostgreSQL, Redis 실행 구성

## 1. 배경

MyShelf는 Docker Compose로 Spring Boot 애플리케이션, PostgreSQL, Redis를 함께 실행한다. 로컬 개발에서는 `myshelf` 데이터베이스를, 통합 테스트에서는 `myshelf_test` 데이터베이스를 사용한다. 두 데이터베이스의 분리는 테스트 실행이 개발 데이터를 오염시키지 않도록 하기 위한 조치이다.

기존 `compose.yaml`은 로컬 개발을 중심으로 작성되어 있었다. 특히 PostgreSQL의 초기화 SQL 마운트, Dockerfile 기반 애플리케이션 빌드, PostgreSQL·Redis의 호스트 포트 게시가 공통 파일에 함께 들어 있었다.

EC2 운영 배포는 GitHub Actions가 빌드하여 GHCR에 게시한 이미지를 내려받아 실행하는 **Build once, deploy many** 방식을 목표로 한다. 운영 PostgreSQL에는 애플리케이션 데이터베이스 `myshelf`만 필요하다.

따라서 환경에 관계없이 필요한 설정과 로컬·운영에서만 필요한 설정을 구분해야 한다.

## 2. 문제

### 2.1. 테스트 DB 초기화 설정의 운영 전파

기존 공통 파일에는 아래 마운트가 포함되어 있었다.

```yaml
services:
  postgres:
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
```

`docker/postgres/init`에 `myshelf_test` 생성 SQL이 있으면, 운영에서 공통 파일을 사용해 **빈 데이터 볼륨으로 PostgreSQL을 최초 초기화할 때** 해당 SQL도 실행될 수 있다. 운영에서는 이 초기화 경로 자체를 제외하고자 한다.

### 2.2. 로컬 빌드와 운영 배포 방식의 혼재

기존 공통 파일의 `app.build` 및 `image: myshelf-app:local`은 로컬 실행에 적합하지만, EC2에서 GHCR 이미지를 사용한다는 배포 원칙과는 역할이 다르다.

### 2.3. 불필요한 호스트 포트 게시

로컬의 DB 관리 도구 및 Redis 점검을 위해 `127.0.0.1:5432:5432`, `127.0.0.1:6379:6379`가 필요할 수 있다. 운영의 애플리케이션 컨테이너는 Compose 내부 네트워크에서 서비스 이름 `postgres`, `redis`로 접근하므로 해당 호스트 포트 게시가 필요하지 않다.

> `127.0.0.1` 바인딩은 모든 외부 인터페이스에 공개하는 것과 다르지만, 운영에서 불필요한 포트 게시 자체를 제거한다는 목적은 여전히 유효하다.

## 3. 검토한 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| A. 공통 파일의 초기화 마운트를 운영에서 `!override`로 제거 | 변경 파일이 적음 | 공통 설정에 로컬 전용 책임이 남고, 운영 쪽에서 볼륨 목록을 재정의해야 함 | 미채택 |
| B. 로컬·운영에서 PostgreSQL 설정 전체를 각각 정의 | 환경별 구성이 독립적 | 이미지 버전, 공통 환경변수, 상태 확인, 볼륨 설정이 중복됨 | 미채택 |
| C. 공통 설정을 최소화하고 로컬·운영의 차이만 별도 파일에 추가 | 역할 분리, 공통 설정 재사용, 운영에 로컬 설정이 유입되지 않음 | 파일 병합 규칙과 실행 명령을 명확히 관리해야 함 | **채택** |

## 4. 결정

Compose 설정을 다음 세 파일로 분리한다.

| 파일 | 책임 |
| --- | --- |
| [`compose.yaml`](../../compose.yaml) | 공통 서비스 정의, 컨테이너 간 접속 정보, `depends_on`, 헬스체크, PostgreSQL 영속 볼륨 |
| [`compose.override.yaml`](../../compose.override.yaml) | 로컬 빌드, `local,redis` 프로필, 로컬 전용 DB 초기화 SQL 마운트, PostgreSQL·Redis 호스트 포트 바인딩 |
| [`compose.prod.yaml`](../../compose.prod.yaml) | GHCR 애플리케이션 이미지, 이미지 Pull 정책, 운영 Spring Profile |

각 파일의 주요 변경은 다음과 같다.

기존 `compose.local.yaml`의 `local,redis` 프로필을 `compose.override.yaml`로 통합하고 `compose.local.yaml`은 삭제했다. 로컬 실행은 기본 병합 명령으로 통일한다. 별도의 일회성 검증 파일인 `compose.verify.yaml`은 이 실행 구성과 구분한다.

**공통 파일**

- `app.build`, `app.image`는 두지 않는다. 환경별 파일에서 둘 중 필요한 방식을 구성한다.
- PostgreSQL에는 `postgres-data:/var/lib/postgresql/data`만 마운트한다.
- PostgreSQL과 Redis의 호스트 `ports`는 두지 않는다.
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `REDIS_HOST` 및 서비스 의존관계를 유지하고, `NATIONAL_LIBRARY_API_KEY`를 앱 컨테이너에 전달한다.
- 현 단계에서 애플리케이션 포트 `127.0.0.1:8080:8080`은 유지한다. 외부 공개 및 리버스 프록시 구성은 별도로 결정한다.

**로컬 파일**

```yaml
services:
  app:
    build:
      context: .
      dockerfile: Dockerfile
    image: myshelf-app:local
    environment:
      SPRING_PROFILES_ACTIVE: "local,redis"
  postgres:
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
  redis:
    ports:
      - "127.0.0.1:6379:6379"
```

**운영 파일**

```yaml
services:
  app:
    image: ${MYSHELF_IMAGE:?Set MYSHELF_IMAGE to a GHCR image reference}
    pull_policy: always
    environment:
      SPRING_PROFILES_ACTIVE: "prod,redis"
```

`MYSHELF_IMAGE`는 배포 대상 이미지 주소와 태그 또는 다이제스트를 지정하는 **비밀정보가 아닌 구성값**이다. 실제 GHCR 패키지의 이미지 이름과 지원 플랫폼은 별도로 확인한다.

> 이 문서의 YAML은 결정의 핵심을 보여 주는 예시다. 상세 설정의 최종 기준은 저장소의 실제 Compose 파일이며, 중복된 전체 YAML을 ADR에 유지하지 않는다.

## 5. 실행 및 운영 규칙

로컬에서는 기본 병합 규칙을 이용한다.

```bash
docker compose up -d --build
```

이 명령은 같은 디렉터리의 `compose.yaml`과 `compose.override.yaml`을 자동으로 병합한다(별도 `COMPOSE_FILE` 설정이 없는 일반적인 경우). 로컬 빌드와 `local,redis` 프로필을 함께 적용하므로 `compose.local.yaml`은 별도로 지정하지 않는다. 상태 확인·로그·종료도 `docker compose ps`, `docker compose logs app`, `docker compose down`으로 통일한다.

EC2에서는 운영 파일을 명시하여 **로컬 전용 Override 파일을 제외**한다.

```bash
docker compose -f compose.yaml -f compose.prod.yaml up -d
```

EC2의 배포 디렉터리는 다음을 목표로 한다.

```text
/opt/myshelf/
├── compose.yaml
├── compose.prod.yaml
└── .env
```

로컬 `.env`에는 `JWT_SECRET`, `DB_PASSWORD`, `NATIONAL_LIBRARY_API_KEY`를 설정한다. 운영에는 배포할 이미지를 지정하는 `MYSHELF_IMAGE`도 필요하다. 비밀값은 Git 저장소에 커밋하지 않고 파일 권한을 제한한다. `.env`를 사용하는 것만으로 자동으로 컨테이너 환경변수가 되는 것은 아니며, Compose 파일의 `${...}` 참조 및 `environment` 선언을 통해 전달된다. 이미지 주소는 Compose가 사용하고, JWT 서명키·DB 비밀번호·외부 API 키는 앱 컨테이너에 전달한다.

## 6. 영향과 주의사항

**장점**

- 로컬 전용 PostgreSQL 초기화 SQL이 운영에 포함되지 않는다.
- 공통 서비스 설정의 중복을 줄인다.
- 운영 EC2에 Dockerfile이나 애플리케이션 소스 없이 GHCR 이미지를 실행할 수 있다.
- 운영에서 DB·Redis의 불필요한 호스트 포트 게시가 사라진다.

**제약 및 위험**

- PostgreSQL 초기화 스크립트는 **빈 데이터 디렉터리에서 최초 실행될 때만** 수행된다. 마운트 제거는 이미 생성된 `myshelf_test`를 삭제하지 않는다.
- 기존 로컬 데이터 볼륨은 보존해야 한다. `docker compose down -v`는 볼륨을 삭제하므로 사용하지 않는다.
- Compose 프로젝트 이름 또는 실행 디렉터리가 바뀌면 다른 이름의 데이터 볼륨을 참조할 수 있다. 변경 전후 볼륨 이름을 확인한다.
- 포트 바인딩 및 파일 분리 자체가 운영 데이터 보안, DB 백업, 비밀정보 관리, 서비스 재시작 정책을 완성하는 것은 아니다.
- `pull_policy: always`는 레지스트리 이미지를 매번 확인한다. 재현 가능한 배포를 위해 고정된 커밋 태그 또는 다이제스트를 권장한다.
- CI/통합 테스트에서 `myshelf_test`를 생성하는 경로가 새 파일 분리 후에도 유효한지 확인해야 한다. 실제 테스트 실행 방식에 따라 별도 Compose 파일을 지정해야 할 수 있다.
- 운영 이미지의 ARM64 지원, GHCR 접근 권한과 각 환경변수의 실제 값은 배포 전에 확인한다. API 키 전달 선언이 있다는 사실만으로 외부 API 인증 성공이 보장되지는 않는다.

## 7. 검증 기준

1. 로컬에서 `docker compose config --quiet` 및 기존 개발·통합 테스트를 통과한다.
2. 운영 구성을 `docker compose -f compose.yaml -f compose.prod.yaml config --quiet`로 검증한다.
3. **실제 병합 결과**에서 운영 `postgres`에 `/docker-entrypoint-initdb.d` 마운트와 호스트 포트 게시가 없는지 확인한다. 단순한 `config --quiet` 성공만으로 이 조건이 증명되지는 않는다.
4. 운영 `app`에 `build`가 없고, 의도한 `MYSHELF_IMAGE`가 지정되는지 확인한다.
5. 운영 PostgreSQL을 신규 볼륨으로 시작할 경우 `myshelf`는 생성되지만 `myshelf_test`는 생성되지 않는지 검증한다.
6. EC2 수동 배포의 실제 성공과 GitHub Actions CD 자동화는 후속 작업에서 별도 검증한다.
7. 로컬·운영 병합 모델에 각각 `local,redis`·`prod,redis` 프로필이 적용되고, 앱에 `NATIONAL_LIBRARY_API_KEY` 전달 선언이 있는지 확인한다. 비밀값 자체는 출력하지 않는다.

### 검증 결과 (2026-11-12)

다음 결과는 개발자가 로컬 Docker 환경에서 직접 확인했다.

- 로컬·운영의 `docker compose config` 병합 결과에서 환경별 프로필과 외부 API 키 전달 설정을 확인했다.
- 로컬 구성과 운영 구성을 각각 기동했다. 운영 구성은 `-p myshelf-prod-test -f compose.yaml -f compose.prod.yaml`로 실행했다.
- 운영 병합 결과에서 로컬 빌드 설정·PostgreSQL 초기화 SQL 마운트·PostgreSQL 및 Redis 호스트 포트 게시가 제외된 것을 확인했다.
- 신규 운영 볼륨에서 `myshelf`가 생성되고 `myshelf_test`는 생성되지 않는 것을 확인했다. 기존 로컬 데이터 볼륨과 구분해 검증했다.
- IntelliJ에서 실행한 Gradle `build` 성공을 확인했다. Dockerfile의 `bootJar`는 테스트를 실행하지 않으므로 컨테이너 이미지 빌드와 Gradle 테스트 실행은 구분한다.

이 결과는 로컬 Docker 환경에서 운영 구성을 검증한 것이며, 실제 EC2 배포나 CD 자동화 성공을 뜻하지 않는다.

## 8. 후속 결정 범위

- `cd/ec2-deployment`: GitHub Actions를 통한 EC2 배포 자동화, 이미지 태그 정책, 롤백 및 GHCR 인증
- 별도 운영 과제: HTTPS·리버스 프록시, 로그·모니터링, 장애 시 재기동, 백업·복구, 비밀정보 관리 개선

## 참고 자료

- [Docker Docs — Merge Compose files](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/)
- [Docker Compose Specification — Merge](https://docs.docker.com/reference/compose-file/merge/)
- [Docker Official Image — PostgreSQL initialization scripts](https://github.com/docker-library/docs/blob/master/postgres/README.md)
