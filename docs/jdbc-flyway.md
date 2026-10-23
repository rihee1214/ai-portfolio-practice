# JDBC·Flyway 실행 안내 — P01-C2

C3의 테스트 전용 조회·트랜잭션 패턴과 실행 안내는 [JDBC 트랜잭션 실습](jdbc-transactions.md)을 따른다. 아래 C3 이후 표현은 C2 당시 기록이다.

JDBC는 Java와 PostgreSQL 사이의 SQL 연결, HikariCP는 연결 풀, Flyway는 SQL 변경 이력을 담당한다. Spring JDBC의 JdbcTemplate/JdbcClient 자동 구성을 사용할 준비를 했으며 업무 조회와 트랜잭션 구현은 P01-C3 이후다. JPA·Spring Data JDBC를 추가하지 않았다.

## 파일과 시작 흐름

1. `backend/pom.xml`이 Boot 4.1.1 관리 버전의 JDBC·Flyway starter와 PostgreSQL 모듈·드라이버를 제공한다. 실제 조합은 Spring JDBC 7.0.9, HikariCP 7.0.2, Flyway core/PostgreSQL 12.4.0, pgJDBC 42.7.13이다.
2. `application.properties`는 공통 health 제한과 SQL init never를 설정한다. 기본/local 프로필에서는 DataSource·Flyway 자동 구성을 제외한다.
3. `application-db.properties`는 제외 목록을 비워 DB 자동 구성을 켜고, DB_URL·DB_USERNAME·DB_PASSWORD를 읽는다. DB_SCHEMA의 기본값은 marketplace다. 루트 `.env`와 `backend/.env.example`은 Boot가 자동으로 읽지 않는다.
4. Hikari DataSource를 만든 뒤 Flyway가 `db/migration/V1__initialize_schema.sql`을 실행한다. 지정 스키마를 생성하고 설명을 붙이며 `flyway_schema_history`에 V1·체크섬·성공 여부를 기록한다. 업무 테이블은 없다.
5. 시작 완료 후 Actuator health는 DB 사용 프로필에서 연결 상태도 포함해 판단하지만 외부 응답에는 status만 노출한다. 기본/local의 UP은 DB 검증을 뜻하지 않는다.

Flyway가 유일한 스키마 초기화 도구다. `spring.sql.init.mode=never`, `clean-disabled=true`, `baseline-on-migrate=false`, `validate-on-migrate=true`, `out-of-order=false`를 명시했다. schema.sql·data.sql·Hibernate 자동 DDL은 추가하지 않았다. 설정값을 외부에서 강제로 바꿀 수 있는 구성 방식이므로 운영 권한 통제를 대신하지는 않는다. 이미 적용한 V1은 수정하지 않고 다음 변경을 V2로 추가한다.

## 기존 서버를 보존하는 빌드

Windows에서는 실행 중인 JAR를 지우거나 덮어쓸 수 없다. 현재 8080 서버가 기존 backend/target JAR를 사용하므로 별도 경로로 빌드한다. 아래는 PowerShell 7·저장소 루트 기준이다.

```powershell
$env:JAVA_HOME = Join-Path $PWD '.local-tools/jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME = Join-Path $PWD '.local-tools/m2'
$buildDir = Join-Path $PWD '.local-tools/c2-build'
Push-Location backend
try {
    ./mvnw.cmd -B -ntp '-Dmaven.repo.local=../.local-tools/m2/repository' "-Dmarketplace.build.directory=$buildDir" verify
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
} finally { Pop-Location }
```

출력 경로 옵션을 생략하면 기존 target을 사용한다. 실행 중인 대상에 clean을 사용하지 않는다. 이번 Java 빌드의 JUnit 실행은 0건이며 아래 실DB 검사를 별도로 수행했다.

## 명시적인 DB 실행

다음은 실행 형식 예제다. 이번에는 기존 개발/테스트 DB에 이행하지 않았다. 실행 전 대상 DB·스키마의 소유와 기존 내용을 확인해야 한다. URL은 C1의 개발15432·테스트15433과 DB명을 함께 확인하며, 임시 검증 DB도 테스트 포트15433에 있다. 사용자·암호는 해당 환경의 앱 계정이다.

```powershell
$env:DB_URL = Read-Host '확인된 jdbc:postgresql://127.0.0.1:포트/DB명'
$env:DB_USERNAME = Read-Host 'DB 앱 사용자'
$env:DB_PASSWORD = Read-Host 'DB 앱 암호' -MaskInput
$env:DB_SCHEMA = 'marketplace'
try {
    & ./.local-tools/jdk-21.0.12.1+1/bin/java.exe -jar .local-tools/c2-build/marketplace-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=db --server.address=127.0.0.1 --server.port=18080
} finally {
    Remove-Item Env:DB_URL,Env:DB_USERNAME,Env:DB_PASSWORD,Env:DB_SCHEMA -ErrorAction SilentlyContinue
}
```

18080이 이미 사용 중이면 새 포트를 고르고 기존 서버를 종료하지 않는다. 이 명령은 현재 셸의 DB_* 값을 임시로 사용하므로 기존 DB_*를 사용하는 다른 작업과 별도 터미널에서 실행한다. db 프로필의 시작은 migration 실행을 포함한다. 암호를 CLI 인자·문서·Git에 넣지 않는다. DB_URL이 없으면 현재 Boot는 `'url' must start with "jdbc"`로 시작을 거부한다. 암호 오류는 PostgreSQL 인증 실패, 이미 적용한 SQL 수정은 checksum mismatch로 구분한다.

## 검증과 작은 실습

[C2 결과](work-items/P01-C2.md)의 21개 계약 검사는 새 격리 DB에서 실제 JAR를 여러 번 실행했다. [검사 원문](work-items/evidence/P01-C2-verifier.txt)을 `.local-tools/c2-verification/VerifyC2.java`에 저장하면 다음 형식으로 재현할 수 있다. C1의 로컬 `.env`와 빌드 JAR가 필요하다. 첫 인자 없이 실행하면 새로운 c2_verify_* DB를 생성하며 기존 DB를 삭제하지 않는다. 생성된 DB는 남으므로 실행 횟수만큼 늘어난다. 기존 DB를 지정하는 재개 인자는 기록에 있는 소유가 확인된 검증 DB에만 사용한다.

```powershell
& ./.local-tools/jdk-21.0.12.1+1/bin/java.exe --class-path .local-tools/m2/repository/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar .local-tools/c2-verification/VerifyC2.java
```

학습 실습은 먼저 이력의 version·description·checksum·success를 SELECT로 읽는 것이다. V1을 실행한 스키마에서 원본을 그대로 재실행하면 V1 행은 늘지 않는다. SQL 변경 실습은 기존 V1 수정이나 개발 DB 초기화 대신 본인 소유의 별도 검증 DB에서 다음 버전을 추가해 수행한다. 실제 사용자 실습 완료는 미확인이다. 이행 SQL의 실패 롤백 확인은 C2의 실패 경로이며 업무 서비스 트랜잭션 구현(C3)이 아니다.
