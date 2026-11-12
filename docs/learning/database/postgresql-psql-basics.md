# PostgreSQL psql 기본 명령어

MyShelf의 PostgreSQL 개발·테스트 DB를 CLI에서 확인할 때 사용하는 명령어를 정리한다.

## 1. DB 및 테이블 확인

| 명령어 | 용도 |
|---|---|
| `\l` | 데이터베이스 목록 |
| `\c myshelf` | `myshelf` DB로 접속 전환 |
| `\conninfo` | 현재 접속 정보 |
| `\dt` | 현재 검색 경로의 테이블 목록 |
| `\dt *.*` | 모든 스키마의 테이블 목록 |
| `\d user_books` | 테이블 컬럼, 타입, 인덱스, 제약조건 확인 |
| `\d+ user_books` | 테이블 상세 정보 |
| `\di` | 인덱스 목록 |
| `\du` | DB 사용자·역할 목록 |

## 2. 데이터 조회

```sql
SELECT current_database();
SELECT * FROM user_books LIMIT 10;
SELECT COUNT(*) FROM user_books;
SELECT * FROM shelf_books LIMIT 10;
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC;
```

- `WHERE`로 조건을 제한하고, `ORDER BY`로 정렬한다.
- 데이터를 수정·삭제하기 전에는 `SELECT current_database();`로 대상 DB를 확인한다.

## 3. 편의 명령어

| 명령어 | 용도 |
|---|---|
| `\x auto` | 출력이 길면 세로 형식으로 표시 |
| `\timing` | SQL 실행 시간 표시 전환 |
| `\?` | psql 명령어 도움말 |
| `\h SELECT` | SELECT 문법 도움말 |
| `\q` | psql 종료 |

## 4. MyShelf 확인 예시

```text
\c myshelf
\dt
\d user_books
\x auto
```

**주의:** `\`로 시작하는 psql 메타 명령어에는 세미콜론을 붙이지 않는다. 일반 SQL은 `;`로 종료한다. `myshelf`와 `myshelf_test`는 별도 데이터베이스이므로 현재 접속 대상을 확인한다.
