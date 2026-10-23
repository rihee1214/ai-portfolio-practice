# P01-C3 개발 검토

2026-10-23 / 검토 대상: `ca6e96e..5dae1675e4e787435efe378b549061e42b6036c2`

판정: C3 범위 검토 통과. 문서 설명 1건을 보정했으며 제품 코드 수정이 필요한 결함은 발견하지 않았다. 사용자 직접 실습은 미확인이고 D1/D2는 진행하지 않았다.

## 검토 내용

테스트 Java 9개 파일, 테스트 V2 SQL, verify-jdbc.ps1, 실행 안내와 작업 문서 및 개발세션 증거를 대조했다. src/main·pom·React 변경은 없으며 fixture를 쇼핑몰 업무 기능 구현으로 계산하지 않는다.

| 항목 | 판단과 근거 |
| --- | --- |
| 파라미터·매핑 | 조회와 변경 SQL에 이름 있는 파라미터를 바인딩한다. RowMapper는 long·BigDecimal·nullable String을 매핑하며 unique label 조회는 Optional로 반환한다. 인용부호·한글·주입 문자열·없는 행을 검사한다. |
| 트랜잭션 | 같은 DataSource의 JdbcTransactionManager와 Spring 서비스 프록시를 사용한다. TransferService의 checked 예외에 rollbackFor가 명시돼 있고 별도 BatchService를 통해 REQUIRED 전파를 검사한다. 자기 호출로 프록시를 우회하지 않는다. |
| 롤백 검사 | 중복 reference 오류는 출금·입금 이후 발생한다. checked 예외는 출금 후 없는 수취인에서 발생한다. 독립 JDBC 연결의 잔액·기록 비교로 롤백을 판정한다. 내부 실패를 catch한 외부 서비스는 UnexpectedRollbackException을 기대하며 앞선 성공 변경도 사라지는지 검사한다. |
| DB 보호 | 고정 loopback 테스트 포트·UUID DB명·앱 계정을 검증한다. 새 DB 생성은 충돌 시 실패하며 기존 DB 재사용/삭제 경로는 없다. Flyway와 fixture SQL 전 실제 DB/사용자/권한을 확인한다. 관리자 권한 보유자나 포트 재지정을 막는 보안 경계는 아니라는 제한이 문서와 일치한다. |
| SQL·패키징 | V1과 테스트 전용 V2를 함께 이행한다. PK/UNIQUE/CHECK/FK 제약과 재이행 0건을 검사한다. 테스트 fixture 클래스와 V2는 배포 JAR에 포함되지 않는다. |
| 실행 도구 | 기존 target 대신 c3-build를 사용한다. 보호 검사 실패 시 DB 생성 전에 중단한다. 새 증거 디렉터리만 허용하고 환경변수는 finally에서 복원한다. 캐시/DB/검사 실패를 통과로 처리하지 않는다. |

## 증거와 직접 확인의 구분

개발세션의 [보호 RED](evidence/P01-C3-guard-red.txt) 12개 실패 → [최종 보호](evidence/P01-C3-guard.txt) 13개 통과, [실DB RED](evidence/P01-C3-red-results.txt) 12개 실패 → [최종 실DB](evidence/P01-C3-results.txt) 26개 통과를 검사 소스와 대조했다. [Maven 빌드](evidence/P01-C3-build.txt)는 성공했고 JUnit은 0건이다. [Node 회귀](evidence/P01-C3-frontend-tests.txt)는 68개 통과, 실패/skip 0이다. 이 검토에서 실DB 26개 및 Node 68개를 재실행하지는 않았다.

이 세션에서 기존 격리 빌드의 TargetContract를 직접 실행해 보호 검사 13개 통과를 확인했다. ZIP 엔트리 검사로 해당 배포 JAR에 jdbcfixture/c3fixture 항목이 없는 것을 확인했다. 새 DB나 fixture 데이터를 만들지 않았다.

21:05:48 KST 읽기 전용 확인에서 기존 8080·5174 health는 UP이고 PID는 각각 68748·63940이었다. 15432/15433도 loopback 리스너를 유지했다. DB 컨테이너 6a9f494624c9·68d17431a4b1은 healthy다. 기존 DB 데이터 보존은 개발세션 [시작](evidence/P01-C3-before.json)과 [종료](evidence/P01-C3-after.json)의 정규화 덤프 해시 일치 근거를 대조했으며 덤프를 여기서 재생성하지 않았다.

## C3-R1 · P3 · 로그 마스킹 시점 설명 — 문서 보정

jdbc-transactions.md는 로그를 저장 전에 마스킹한다고 설명했으나 verify-jdbc.ps1은 Java 출력을 runtime.txt로 먼저 리디렉션한 후 프로세스가 종료되면 값을 치환해 다시 저장한다. 실행 중 또는 비정상 중단 시점의 파일에 원본 출력이 남을 수 있다. 실행 안내를 실제 순서로 수정했다. 실제 암호 유출이 관찰됐다는 뜻은 아니며 이 로컬 도구가 원본 로그의 디스크 기록 자체를 방지한다고 보장하지 않는다.

## 남은 범위

동시 이체·교착·분산 트랜잭션, 운영 권한 통제, 백업 복원, 새 PC/OS, JUnit/MockMvc/Failsafe와 사용자 직접 실습은 미검증이다. RED·최종 검증 DB 두 개와 가상 데이터는 보존한다. 문서의 검토 상태만 갱신하며 기존 서버·컨테이너·DB 및 제품 코드는 변경하지 않았다. 검토 작성 당시 변경은 미커밋으로 인계했으며, 개발세션에서 확인 후 별도 로컬 커밋으로 마무리한다.
