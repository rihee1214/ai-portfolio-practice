# P01 최신 DB 통합 보완 독립 검토

2026-10-24 / 기준: `67960e7` + PostgresJdbcIT 및 작업·실행 문서 변경

판정: **요청한 최신 db 프로필 통합 보완 범위 검토 통과. 필수 수정 결함 없음.** 실제 PostgreSQL에서 E4·E5와 기존 JDBC 검사를 함께 독립 실행했다. P01 종합 점검 당시 남긴 최신 DB 조합 미검증은 아래 실행 범위에서 보완됐다. 업무 CRUD·실제 사이트 가동·배포·장애 복구 검증까지 완료한 것은 아니다.

## 직접 실행한 증거

명령: `./backend/verify-db.ps1 -EvidenceDirectory .local-tools/p01-db-followup-review -Offline`

- 종료 코드 0, BUILD SUCCESS, Checkstyle·컴파일·패키징 성공.
- 기본 Surefire **117개**, Failsafe 실제 PostgreSQL 통합 **12개**, 각각 실패·오류·skip 0.
- 요청 DB와 실제 생성 DB 일치: **`d2_it_f4d47cbfa4604a0f8f9f7a6a0c68fe86`**. 삭제하지 않고 보존했다.
- 독립 실행 자료: `.local-tools/p01-db-followup-review/maven.txt`, `summary.json`, `target.txt`. maven.txt는 기존 스크립트가 비밀값을 치환한 로그이며, XML 원문은 공개 문서로 복사하지 않았다. 이 세션의 추적 파일 소유권은 검토 문서 하나다.
- 완료 시각: 2026-10-24 13:24 KST. 이번 실행 대상은 DB 없는 기본 검사와 새 격리 DB뿐이다.

## 구현·검사 검토

| 항목 | 확인한 내용 |
| --- | --- |
| 대상 보호 | 변경하지 않은 DedicatedPostgresInitializer가 UUID 형식·15433 loopback URL·marketplace_test 계정과 비관리자 권한을 확인한다. 기존 DB 재사용 없이 CREATE DATABASE가 충돌하면 실패한다. 별도 Flyway 연결 설정도 거부한다. |
| 구성 조합 | db,c3-fixture 프로필에서 실제 DataSource·Flyway·DB health contributor가 생성되고 필수 설정 post-processor도 활성화된다. 실제 접속 DB/사용자와 PostgreSQL18 식별 검사는 기존 사례로 유지된다. |
| E4 실패 경로 | DataSource/Flyway 자동 구성을 제외하지 않은 별도 컨텍스트에서 세 필수 키의 공백을 각각 주입한다. E4 진단으로 시작이 실패하고 일반 singleton 및 dataSource/flyway 생성 전 관찰자가 실행되지 않음을 확인한다. 별도 컨텍스트에는 DB 생성 초기화기를 쓰지 않는다. |
| health·E5 | 실제 DB가 연결된 임시 HTTP 서버에서 status-only UP200·UUID v4·HEAD 빈 본문·POST405와 관리/상세/probe 경로404를 확인한다. 전용 JMX domain 등록0과 heapdump bean 부재도 통과했다. |
| 기존 거래 예제 | 기존8개는 실제 Flyway V1/V2·매핑·커밋·제약·checked/REQUIRED 실패 롤백을 검사한다. 결과를 새 JDBC 연결로 조회하고 전체 테스트 자동 롤백으로 결과를 가리지 않는다. |
| 변경 범위 | 제품 Java·설정·마이그레이션은 바뀌지 않았다. 통합 테스트의 RANDOM_PORT 전환과 검사 추가, 실행 문서 갱신으로 한정된다. |

## 해석의 한계

- 통합12개 중 E4 실패 검사1개가 필수 키3개를 순회한다. 이들을 별도 테스트3개로 더해 집계를 늘리지 않는다. 기본117개는 실제 DB 검사 수가 아니다.
- 시작 전 관찰자는 현재 bean 이름 dataSource/flyway의 생성 시도를 확인한다. 모든 드라이버 소켓 연결을 외부에서 계측하는 장치는 아니므로 미래 사용자 정의 DB 초기화 코드까지 포괄한다고 주장하지 않는다. 현재 코드의 post-processor 순서와 실제 구성 성공 사례를 함께 확인했다.
- 이번 통합 관리 경로404는 기본 health-only exposure에서도 발생할 수 있다. access 기본 거부 자체의 방어는 함께 실행한 기존 ActuatorPolicyHttpTest의 exposure=* 검사로 보완된다.
- 실제 DB를 중지하여 DOWN/복구를 재현하지 않았다. 기본 장애 health 검사는 별도 회귀이며 실제 DB 장애 실험과 구분한다. 원격 JMX 접근·전체 MBean 부재·운영 인증 보장을 뜻하지 않는다.
- 프런트·브라우저를 이번에 재실행하지 않았다. 기존 서버8080/5174를 가동하거나 실제 업무 화면을 연결한 결과도 아니다. 새 PC·다른 OS·배포·실기기·사용자 직접 실습은 여전히 미검증이다.
- 중간 실행 오류는 실패 컨텍스트를 테스트가 잘못 조회한 관찰 오류이며 의도적 RED나 제품 결함으로 계산하지 않는 작업 문서 설명이 타당하다. 최종 테스트는 초기화 때 관찰자를 등록하여 이 문제를 피한다.

## 보존과 인계

이 세션은 기존 서버·컨테이너를 실행/종료/재시작하지 않았고 기존 DB를 변경·삭제하지 않았다. 새 UUID DB 생성과 해당 DB의 migration/fixture 쓰기만 승인된 독립 검증 범위로 수행했다. 루트 .env는 기존 실행 스크립트가 읽고 프로세스 환경을 복원했으며 실제 자격증명을 출력하지 않았다.

기존 DB 해시 및 최종 컨테이너/리스너 전후 비교는 개발세션이 담당하므로 이 문서에서 해당 후속 확인을 대신 완료로 선언하지 않는다. 개발세션은 위 새 DB를 잔여물 목록에 포함하고 독립 실행 자료를 확인하여 보존 결과·최종 완료 기록을 반영할 수 있다. 이 세션은 검토 문서 외 제품/테스트/상태 문서를 수정하지 않았고 커밋·push·P02를 진행하지 않았다.
