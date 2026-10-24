# 실제 PostgreSQL 통합 검사 — P01 최신 코드 보완

D1은 DB 없는 검사이고 D2는 실제 PostgreSQL·Flyway·Spring JDBC·트랜잭션을 함께 검사한다. H2나 가짜 DataSource로 대체하지 않는다. 기존 Compose db-test를 사용하며 컨테이너를 시작·중지·재생성하지 않는다.

## 두 실행 범위

| 명령/실행기 | 대상 | 현재 결과 |
| --- | --- | --- |
| 기본 Maven verify / Surefire | *Test, DB 없음 | 124개: API·설정·관리 정책·모듈 경계·대상 보호 |
| Maven -Pdb-it verify / Failsafe | 위124개 + *IT | PostgreSQL·db 프로필 HTTP 통합21개 별도 집계(P02-A1 회원9개 포함) |

`db-it`는 Maven 프로필이다. 통합 검사의 Spring 프로필은 `db,c3-fixture,local-mailbox`다. Boot가 기존 application-db.properties를 읽고 실제 Hikari DataSource·Flyway를 구성하며 테스트용 Repository/Service를 연결한다. `@SpringBootTest(webEnvironment=RANDOM_PORT)`로 loopback 임시 HTTP 서버도 실행해 E4·E5 조합을 확인한다. 최초 D2의 NONE·8개 실행은 당시 이력이며 최신 결과는 [보완 검증](work-items/P01-db-followup.md)을 따른다. [Boot 테스트 모드](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)

Failsafe는 integration-test에서 실행하고 verify에서 결과를 판정한다. 따라서 통합 검사 완료 명령은 `verify`까지 사용한다. 현재 부모 POM이 관리하는3.5.6을 사용하며 공식 페이지의 최신 버전으로 임의 업그레이드하지 않았다. [Failsafe 실행 단계](https://maven.apache.org/surefire/maven-failsafe-plugin/usage.html)

## 안전한 실행

PowerShell7, C1의 실행 중 db-test, 프로젝트 로컬 JDK21/Maven 캐시, 루트.env의 TEST_DB_PASSWORD/TEST_ADMIN_PASSWORD가 필요하다. 기존 개발 DB나 기존 marketplace_test를 통합 검사 대상으로 주지 않는다.

저장소 루트에서:

```powershell
./backend/verify-db.ps1 -EvidenceDirectory .local-tools/d2-my-run-01
# 의존성이 준비된 뒤에는 다운로드 없이 실행할 수 있다.
./backend/verify-db.ps1 -EvidenceDirectory .local-tools/d2-my-run-02 -Offline
```

매번 새로운 증거 디렉터리와 `d2_it_<32자리 UUID>` DB를 만든다. 이미 있는 디렉터리는 거부한다. 빌드는 그 디렉터리/build에 격리하고 backend/target·기존 API 프로세스를 대체하지 않는다. 스크립트는 Maven 종료 코드와 Surefire/Failsafe XML 집계를 모두 확인한다. 실패·오류·skip·통합0건을 성공으로 보고하지 않는다. 사용자 환경의 Maven 실패 무시 설정으로 exit0이어도 보고서의 오류를 거부한다.

DB 준비가 안 됐을 때 테스트를 skip하는 옵션은 없다. Docker/DB 미준비·암호/대상 오류는 Maven 비정상 종료 또는 보고서 오류로 드러난다. 캐시가 없으면 -Offline 실행도 실패한다. 제한 환경의 다운로드/로컬 통신에는 실행 권한이 필요할 수 있다.

직접 Maven을 실행할 때는 D2_DB_NAME, D2_DB_URL, D2_DB_USERNAME, D2_DB_PASSWORD, D2_ADMIN_PASSWORD를 프로세스 환경으로 제공해야 한다. 암호를 -D 인자나 명령 기록에 쓰지 않는다. 위 스크립트는 현재 프로젝트의 인용부호 없는 KEY=value 형식.env에서 테스트 암호만 읽고 필요한 환경변수를 전달한 후 복원한다.

## DB 보호와 코드 흐름

`verify-db.ps1` → Failsafe → `DedicatedPostgresInitializer` → Boot db 프로필/Flyway → `PostgresJdbcIT` 순서다.

초기화기는 컨텍스트 refresh 이전에 다음을 확인한다.

1. D2 전용 UUID 이름, 고정127.0.0.1:15433 URL, marketplace_test 계정, 명시적 대상 일치. 개발/공용 DB·다른 포트·원격/옵션이 있는 URL을 거부한다.
2. 별도 spring.flyway.url/user/password 설정을 거부한다. Flyway가 검증된 DataSource와 다른 DB에 접속할 수 없게 한다.
3. 테스트 앱 계정을 postgres 유지관리 DB에 인증하고 superuser/CREATEDB/CREATEROLE이 없는지 확인한다. 잘못된 암호면 신규 DB를 만들기 전에 실패한다.
4. 관리자 연결은 고정 테스트 포트의 새 UUID DB 생성에만 쓴다. 이름이 이미 존재하면 CREATE DATABASE가 실패하며 재사용·삭제·reset하지 않는다.
5. 새 DB의 실제 이름·사용자·권한을 확인한 후 Boot에 연결값을 전달한다. 그 뒤에만 Flyway가 기존 V1과 테스트 전용 V2를 적용한다.

Flyway만 스키마 초기화를 담당하며 SQL init은 never, clean은 disabled다. V1 원문과 C3 V2는 수정하지 않았다. test SQL/코드는 배포 JAR에 포함되지 않는다. 권한 있는 사용자가 코드·DB 포트·관리자 설정을 임의로 바꾸는 것까지 막는 보안 경계는 아니다.

## 기존 JDBC 검사8개와 조합 검사4개

- 실제 PostgreSQL18·검증된 DB/사용자·Boot DataSource.
- V1/V2 성공 이력과 재실행의 추가 migration0건.
- 인용부호/한글·주입 문자열 파라미터, 금액 BigDecimal·null·일반 문자열·없는 행 매핑.
- 정상 이체의 두 잔액과 기록 커밋.
- 두 UPDATE 이후 중복키 오류가 났을 때 모두 롤백하고 기존 성공 이체 보존.
- 출금 이후 checked 예외가 발생했을 때 롤백.
- 잔액 CHECK와 FK 제약 위반 시 데이터 보존.
- REQUIRED 내부 실패를 catch해도 바깥 커밋이 실패하고 앞선 성공 변경까지 롤백.

추가4개는 E4 활성/실제 설정, DB 자동 구성 활성 시 필수 설정 오류의 생성 전 거부(키3개), 실제 DB health 계약·요청 ID, 관리 경로/JMX 비노출을 확인한다. 제품 코드는 변경하지 않았다.

테스트 전체에 @Transactional을 붙이지 않았다. 서비스 호출은 실제 프록시를 거치고 결과는 연결 풀 밖의 새 JDBC 연결에서 읽는다. 각 검사가 별도 가상 ID/이체 참조를 쓰므로 실행 순서에 의존하지 않으며 데이터를 지우는 정리 SQL도 없다. 컨텍스트 종료 시 연결 풀은 닫지만 DB는 남긴다.

## 결과와 잔여물

증거 디렉터리의 maven.txt, summary.json, target.txt를 읽는다. REQUESTED_DATABASE는 요청 이름이고 D2_CREATED_DATABASE가 있어야 실제 생성됐다는 기록이다. setup 오류는8개 ERROR로 집계될 수 있으며, 이는 DB 계약8개를 실행하고 검증 실패했다는 뜻이 아니라 컨텍스트 준비 단계에서 막힌 것이다.

각 정상 DB에는 marketplace의 Flyway 이력과 c3_fixture.account/transfer_entry가 남는다. 자동 삭제·볼륨 삭제는 제공하지 않는다. 반복 실행은 DB/디스크 사용량을 늘리므로 남은 이름을 작업 기록에 남긴다. Surefire/Failsafe XML 전체에는 실행 환경 정보가 있을 수 있어 필요한 집계만 공유한다. Maven 스트림은 로컬 암호를 치환한 뒤 maven.txt에 저장하지만 XML 등 모든 외부 도구 산출물의 비밀값 제거를 보장하는 것은 아니다.

이번 실행과 실패 이력·잔여 DB는 [P01-D2 작업 기록](work-items/P01-D2.md)을 따른다. 기존8080/5174의 운영 상태와 테스트 임시 서버 성공은 별개의 사실이다.

## 작은 직접 실습

1. 기본 verify 결과의33개와 db-it 결과의33+8개를 비교한다.
2. target.txt의 새 DB에서 flyway_schema_history의 V1/V2와 가상 계좌 잔액을 읽기 전용으로 확인한다.
3. PostgresJdbcIT의 중복키/checked 예외 검사에서 어느 SQL까지 실행된 뒤 실패했는지 예상하고, 새 연결로 확인하는 assertion을 읽는다.

자동 검증 통과와 사용자 이해·실습 완료는 구분한다. 현재 사용자 실습은 미확인이다. 동시성·분산 트랜잭션·보안·업무 도메인·D3 이후는 이 단계에 포함하지 않는다.

P02-A1은 V3 회원 테이블과 가입·확인 서비스 검사9개를 추가했다. 현재 실행은 새 격리 DB에 V1/V2/V3를 적용한다. 기존 DB에는 적용하지 않는다. 상세 계약·잔여 DB·검토 상태는 [A1 기록](work-items/P02-A1.md)을 따른다.
