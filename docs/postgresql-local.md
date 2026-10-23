# PostgreSQL 개발·테스트 DB 실행 — P01-C1

Docker Desktop의 Linux 엔진과 Compose가 필요하다. 프로젝트 루트에서 실행한다. 앱/API는 컨테이너화하지 않았다.

| 구분 | 서비스 | 호스트 주소 | DB·앱 사용자 | 볼륨 |
| --- | --- | --- | --- | --- |
| 개발 | db-dev | 127.0.0.1:15432 | marketplace_dev | moa-market-local_dev-data |
| 테스트 | db-test | 127.0.0.1:15433 | marketplace_test | moa-market-local_test-data |

두 앱 계정은 해당 DB 소유자이며 superuser·CREATEDB·CREATEROLE 권한이 없다. 초기화용 postgres 관리자 암호와 앱 암호도 환경별로 구분한다. JDBC 주소 후보는 `jdbc:postgresql://127.0.0.1:15432/marketplace_dev`와 `jdbc:postgresql://127.0.0.1:15433/marketplace_test`다. Boot에 연결 설정이나 JDBC 의존성을 추가하지 않았으며 독립 임시 pgJDBC 도구로 호스트 포트 인증은 확인했다. Spring Boot JDBC 통합은 P01-C2에서 검증한다. 프런트 VITE_*에는 DB 설정을 넣지 않는다.

## 최초 준비

공유된 [.env.example](../.env.example)의 빈 암호는 실행용이 아니다. 현재 호스트에는 이미 무작위 암호를 가진 `.env`를 생성했다. 새 환경에서는 아래 PowerShell 7 명령으로 네 개의 서로 다른 암호를 생성할 수 있다. 기존 파일은 덮어쓰지 않는다.

```powershell
if (Test-Path .env) { throw 'Existing .env must not be overwritten' }
$values = @('DEV_ADMIN_PASSWORD','DEV_DB_PASSWORD','TEST_ADMIN_PASSWORD','TEST_DB_PASSWORD') |
    ForEach-Object { $_ + '=' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)) }
($values + 'DEV_DB_PORT=15432' + 'TEST_DB_PORT=15433') | Set-Content .env
docker compose config --quiet
docker compose pull
docker compose up -d --wait
docker compose ps
```

각 명령의 종료 코드가 0인지 확인한 후 다음을 실행한다. 포트 충돌 시 기존 서버를 종료하지 말고 `.env`의 포트를 바꾼다. 동일 프로젝트 이름을 쓰는 다른 복사본이 있다면 같은 자원을 공유할 수 있으므로 먼저 프로젝트/볼륨 소유를 확인한다. `.env`는 Git에서 제외하지만 로컬 평문이며 Docker 접근 권한으로 컨테이너 환경값을 읽을 수 있다. `docker compose config`(quiet 없음)나 전체 inspect 결과에는 암호가 포함될 수 있으므로 공유하지 않는다.

공식 [PostgreSQL 이미지](https://hub.docker.com/_/postgres)의 18.6 태그를 사용한다. 이번 linux/amd64 다운로드 digest는 `sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6`다. 태그는 향후 다른 digest를 가리킬 수 있으므로 재다운로드 후 기록을 비교한다. PG18의 데이터 볼륨 대상은 `/var/lib/postgresql`, 실제 PGDATA는 `/var/lib/postgresql/18/docker`다.

## 접속·중지·재시작

암호를 화면에 출력하지 않고 컨테이너 안에서 TCP 인증 SQL을 실행한다.

```powershell
docker compose exec db-dev sh -c 'PGPASSWORD="$APP_PASSWORD" psql -h 127.0.0.1 -U "$APP_USER" -d "$APP_DB"'
docker compose exec db-test sh -c 'PGPASSWORD="$APP_PASSWORD" psql -h 127.0.0.1 -U "$APP_USER" -d "$APP_DB"'
```

psql에서 `SELECT current_database(), current_user, version();`로 대상을 확인하고 `\q`로 종료한다. 이 접속은 컨테이너 내부 TCP다. 호스트에서 GUI DB 도구를 쓰면 위 주소·포트와 `.env`의 해당 앱 암호를 사용한다. 호스트 두 게시 포트는 독립 Java/pgJDBC 42.7.13 도구로 정상 인증·DB/사용자 식별과 잘못된 암호(SQLSTATE 28P01) 거부까지 확인했다. 이 도구는 .local-tools에만 준비했으며 backend 의존성이나 설정을 변경하지 않았다. GUI 접속은 미실행이다. `pg_isready`의 healthy는 준비 상태일 뿐 암호와 권한의 검증을 대신하지 않는다.

```powershell
docker compose stop db-test
docker compose up -d --wait db-test
docker compose restart db-dev
docker compose up -d --wait
# 컨테이너만 재생성하고 이름 있는 볼륨은 그대로 유지:
docker compose up -d --force-recreate --wait db-dev db-test
```

`stop`은 컨테이너를 멈추며 `restart`는 다시 시작한다. 재생성은 컨테이너 ID가 바뀌지만 이름 있는 볼륨은 재사용한다. 이번 검증에서 임시 표식을 두 DB에 각각 넣어 모든 작업 후 유지됨을 확인하고 표식 테이블만 제거했다. 현재 두 DB는 실행 상태로 남겼다. 사용 후 두 DB만 멈추려면 `docker compose stop db-dev db-test`를 사용한다.

볼륨을 삭제하는 `down -v`나 volume prune은 데이터 삭제이므로 일상 재시작에 사용하지 않는다. 기존 `pgdata` 등 다른 프로젝트 볼륨을 정리하지 않는다. 볼륨은 백업이 아니다.

## 설정 변경과 학습

`init-app.sh`와 POSTGRES_* 초기값은 빈 볼륨에서만 적용된다. `.env` 암호를 바꾸고 컨테이너를 재시작해도 DB 내부 암호가 바뀌지 않는다. 기존 데이터의 암호 변경은 별도 관리 작업이며 볼륨 삭제로 해결하지 않는다. 업무 스키마·Flyway는 C2 이후다.

Oracle의 스키마/사용자와 달리 PostgreSQL은 서버 클러스터 안에 여러 DB와 역할을 두며, 이번에는 환경별 클러스터까지 분리했다. 작은 실습으로 테스트 DB만 중지하고 개발 DB의 `SELECT 1`이 되는지 확인한 뒤 테스트 DB를 다시 시작해 볼 수 있다. 사용자 실습 수행은 미확인이다.
