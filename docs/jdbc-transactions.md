# C3 JDBC 조회·매핑·트랜잭션 실습

C3는 테스트 전용 fixture로 SQL과 트랜잭션 사용 방식을 검증한다. 쇼핑몰 계정·잔액·결제 기능이 구현된 것은 아니다. `backend/src/test/java/com/example/marketplace/jdbcfixture`와 `src/test/resources/db/c3fixture`는 배포 JAR에 포함되지 않는다. 앱의 기존 health와 DB 프로필은 변경하지 않았다.

## 코드 흐름

`verify-jdbc.ps1` → URL 보호 검사 → 새 전용 DB 생성 → 실제 연결 신원 검사 → Flyway V1/V2 → Spring 컨텍스트 → 서비스 프록시 → Repository → PostgreSQL 순서다.

- `AccountRepository`: `NamedParameterJdbcTemplate`으로 `:label`, `:amount` 등의 값을 바인딩한다. SQL 문자열에 사용자 값을 붙이지 않는다. `RowMapper<Account>`는 DB 행을 Java record로 바꾸며 금액은 `BigDecimal`, nullable 문자열은 null로 유지한다. 조회 결과가 없으면 `Optional.empty()`다. [Spring JDBC API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate.html)
- `TransferService`: 출금 → 입금 → 이체 기록을 하나의 공개 메서드로 묶는다. Spring이 만든 프록시를 통해 호출하고 `@Transactional(rollbackFor = TransferRejectedException.class)`로 checked 업무 실패도 롤백한다. 같은 객체 내부의 자기 호출에 의존하지 않는다. [Spring 롤백 규칙](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)
- `BatchService`: 별도 서비스 프록시의 기본 REQUIRED 전파를 사용한다. 앞 이체가 성공했더라도 다음 내부 이체가 실패하면 전체가 롤백된다. 내부 예외를 catch해도 rollback-only가 취소되지 않으며 바깥 커밋 시 `UnexpectedRollbackException`으로 실패를 알린다. [Spring 전파 규칙](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- `JdbcContract`: 테스트 전체를 트랜잭션으로 감싸지 않는다. 결과를 새 JDBC 연결로 읽어 실제 커밋·롤백 여부를 확인한다. `FixtureConfiguration`에서 같은 DataSource를 쓰는 `JdbcTransactionManager`와 Repository/Service를 연결한다.

Oracle에서 SQL을 직접 작성했던 경험에 대응하면 파라미터 바인딩은 SQL과 값을 분리하는 부분, RowMapper는 SELECT 결과를 Java 객체에 옮기는 부분, 트랜잭션 경계는 여러 SQL을 하나의 COMMIT/ROLLBACK 단위로 묶는 부분이다. 이번 fixture는 단일 DataSource이며 여러 서비스/DB를 묶는 분산 트랜잭션을 검증하지 않는다.

## 실행

전제: C1의 db-test가 이미 실행 중이고 루트 `.env`에 `TEST_DB_PASSWORD`, `TEST_ADMIN_PASSWORD`가 있으며 기존 로컬 JDK21·Maven 캐시를 사용할 수 있어야 한다. 스크립트는 컨테이너를 시작·중지하지 않는다. `.env` 형식은 현재 프로젝트가 생성한 인용부호 없는 `KEY=value`를 사용한다.

저장소 루트의 PowerShell7에서 매번 새로운 증거 디렉터리로 실행한다.

```powershell
./backend/verify-jdbc.ps1 -EvidenceDirectory .local-tools/c3-my-run-01
```

오프라인 Maven `verify`로 `.local-tools/c3-build`에 빌드하고 실제 JAR의 라이브러리를 사용해 두 Java 실행 검사를 실행한다. 기존 `backend/target`과 8080·5174 서버는 건드리지 않는다. Maven에서 JUnit 0건은 이 검사들의 통과를 의미하지 않는다. `TargetContract` 13개와 `JdbcContract` 26개를 별도로 확인해야 한다. D1/D2의 JUnit·MockMvc·Failsafe 체계는 아직 미구현이다.

`build.txt`, `guard.txt`, `runtime.txt`, `results.txt`, `target.txt`가 새 디렉터리에 기록된다. 기존 디렉터리는 덮어쓰지 않는다. 캐시 누락·DB 접근 거부·검사 실패는 비정상 종료하며 미검증을 통과로 처리하지 않는다. 제한 환경에서 로컬 TCP 접근이 막히면 실행 권한이 필요할 수 있다.

## DB 보호 범위

목적지는 `jdbc:postgresql://127.0.0.1:15433/c3_verify_<32자리 UUID>`, 앱 계정은 `marketplace_test`로 고정한다. 일반 개발·공용 테스트 DB, 원격/localhost 별칭·다른 포트·URL 옵션·관리자 앱 계정은 보호 검사에서 거부한다. 실제 연결에서도 DB명·사용자·superuser/CREATEDB/CREATEROLE을 확인한다. 초기화 전에 이 검사를 통과해야 한다.

새 UUID DB를 postgres 계정으로 생성한 뒤 앱 계정으로만 Flyway·fixture SQL을 실행한다. 기존 이름 재사용 옵션과 drop/clean/reset/truncate 명령은 없다. 기존 V1을 그대로 사용하고 테스트 클래스패스의 V2만 더한다. 테스트는 `marketplace` 이력과 `c3_fixture`의 가상 계좌·이체 기록을 남긴다. 반복 실행은 새 DB를 추가하므로 디스크 사용량이 늘며 자동 정리는 하지 않는다. 관리자 암호는 자식 프로세스 환경으로 전달한다. Java 출력은 runtime.txt에 먼저 저장하고 프로세스 종료 후 로컬 암호값을 마스킹해 다시 저장한다. 실행 중이거나 도구가 중단되면 원본 출력이 남을 수 있으므로 마스킹 전 로그를 공유하지 않는다.

이는 도구의 실수 방지 장치다. DB 관리자 권한을 가진 사람이 코드를 바꾸거나 포트 연결을 다른 서버로 바꾸는 것까지 막는 보안 경계는 아니다. 운영에서 사용할 최소 권한 정책이나 DB 전체 삭제 보호가 완성됐다는 뜻도 아니다.

## 직접 해볼 작은 실습

1. `AccountRepository.findByLabel`의 SQL·바인딩·RowMapper를 순서대로 읽는다.
2. 새 증거 디렉터리로 검사하고 `target.txt`의 DB를 읽기 전용으로 조회한다. 성공 fixture의 1번/2번 잔액은 각각 75.25/125.75, 실패 fixture의 잔액은 100.50이다.
3. 별도 학습 변경에서 checked 예외의 rollbackFor가 빠지면 무엇이 남을지 먼저 예상한다. 공유 소스는 원복하고 새로운 전용 DB에서만 실험한다. 기존 DB 초기화나 운영 V1 수정은 필요하지 않다.

자동 검증 결과와 사용자 이해·직접 실습 완료는 구분한다. 현재 사용자 실습은 미확인이다. 실행 결과·잔여 DB·검토 상태는 [P01-C3](work-items/P01-C3.md)를 따른다.
