# P04-E 독립 검토

2026-10-25. **최종 판정: 독립 재검토 통과 — E-R1·E-R2 해결, 확인된 필수 잔여 차단 없음.** 개발 세션의 실제 사용자 메시지에서 P04-E 재개·독립 검토 승인을 확인했다. 기준 커밋 `d390c10f76f54062b62a23dde186a99d30583bfa`, 브랜치 `codex/p04-e-resume`의 미커밋 구현을 검토했다. 제품·기존 검사·하네스를 편집하지 않았으며 이 문서와 `evidence/P04-E-review-*`만 작성했다.

아래 E-R1·E-R2와 최초 실패 결과는 이력으로 유지한다. 보완 검토와 최신 독립 실행 근거는 문서 끝의 **최종 재검토**를 따른다.

## E-R1 [P2] 서로 다른 SKU 묶음의 역순 전역 키 충돌에서 계약 밖 교착상태 예외

위치: `JdbcReservations.java:27–28, 40–51`. SKU 잔액은 정렬해 잠그지만 신규 예약은 입력 순서로 INSERT한다. 두 거래가 서로 다른 SKU를 사용하면 잔액 잠금은 서로를 직렬화하지 않는다. 첫 거래가 예약 ID X→Y, 두 번째가 Y→X 순서로 삽입하면 각자의 첫 INSERT가 상대의 두 번째 INSERT를 막는다. `transaction()`은 DuplicateKeyException만409로 변환하므로 PostgreSQL40P01은 그대로 호출자에게 노출된다.

독립 실행에서 컴파일된 **실제 JdbcReservations**를 사용해 재현했다. JDBC 도우미는 각 거래의 첫 INSERT가 끝난 직후 barrier로 동기화했을 뿐 SQL과 제품 구현을 바꾸지 않았다. 두 거래는 서로 다른 SKU를 사용하고 서로 다른 orderItemId를 가지며, 예약 ID 두 개만 역순으로 공유한다. 테스트는 이번 독립 검토가 만든 새 DB에 새 SKU·잔액만 추가해 실행했다.

[재현 코드](evidence/P04-E-review-reservation-probe.java), [실행 출력](evidence/P04-E-review-reservation-probe.txt):

```text
LEFT=ok
RIGHT=PessimisticLockingFailureException:40P01
PROBE_ROWS=2
```

승자 reserved2/version2, 패자 reserved0/version0으로 rollback은 유지됐다. 데이터 손실로 주장하지 않는다. 문제는 충돌을409로 처리한다는 계약과 호출자가 처리할 수 있는 예외 범위를 어긴다는 점이다. E02는 단일 키 경쟁, E06은 같은 SKU 집합의 역순 경쟁을 검사하므로 이 경우를 놓친다.

요청: 전역 reservationId와 orderItemId 충돌까지 다루는 순서/직렬화 또는 안전한 충돌 변환을 설계하고, 서로 다른 SKU의 역순 다중 키 경쟁을 필수 실제 DB 회귀 검사에 추가한다. 기대 결과는 한 성공·한 계약상409와 패자 잔액/예약/이력 원복이다. orderItemId의 교차 충돌도 함께 검사해야 한다. 단순 입력 재정렬만으로 서로 다른 두 유일 키의 모든 교차 충돌이 해결됐다고 가정하지 않는다. 반환 순서는 기존 계약을 유지한다.

## E-R2 [P2] 독립 전체 하네스에서 잠금 대기 후 만료 검사 실패

위치: `PostgresJdbcIT.java:131–150`, 특히147행. `reservationExpiryIsCheckedAfterBalanceLock`가 실제 잠금 대기를 확인한 뒤 `pg_sleep(1.1)`을 호출하지만, 다음 `expires_at<=clock_timestamp()` assertion은 false였다. 이 실행은 후속149행의 확정409 및 잔액 유지 검증까지 도달하지 못했다.

[JUnit 실패 원문](evidence/P04-E-review-db-failure.txt), [하네스 결과](evidence/P04-E-review-result.json). 개발 실행의 통과를 독립 실행의 실패 대신 사용하지 않는다. 고정 sleep 뒤 실제 기한 경과라는 준비 조건이 충족되지 않은 사실은 확인했지만, DB 시계 변동 등 세부 원인은 이번 검토에서 확정하지 않았다. 이것만으로 제품의 만료 판정 자체가 잘못됐다고 단정하지 않는다.

요청: 잠금 보유/대기와 DB 기준 기한 경과를 관측 가능한 조건으로 동기화하고, 실패 시 기한·DB 현재 시각을 남긴다. 예를 들어 실제 잠금 대기를 관측한 상태에서 검사 전용 기한을 과거로 변경한 뒤 잠금을 풀어 재확인 여부를 검증하거나, DB 조건을 제한 시간 안에 직접 관측한다. sleep/전체 timeout 증가나 검사 제외만으로 처리하지 않는다. 수정 후 전체 하네스를 새 경로에서 다시 실행해야 한다.

## 실행 결과와 근거

```powershell
./verify.ps1 -Offline -EvidenceDirectory .local-tools/p04-e-review-20261025
```

| 구분 | 독립 결과 |
| --- | --- |
| JUnit 기본·ArchUnit | 137 통과 (ArchUnit18 포함) |
| 실제 DB | 97 중96 통과·1 실패, 오류/skip0 |
| 실제 PC·모바일 브라우저 | 22 통과 |
| 전체 하네스 | 종료 코드1, status=failed, preservation=passed |
| 별도 프런트 검사 | 타입·린트·Vitest96·빌드·Node HTTP/프록시68 통과 |
| 추가 실제 구현 재현 | E-R1의40P01 노출 확인 |

하네스가 DB 단계에서 중단돼 프런트 단계는 해당 하네스 실행 안에서는 미실행이다. 이후 동일 소스에서 프런트 명령들을 별도로 실행했다. 이를 전체 하네스 통과로 합산하지 않는다. [집계](evidence/P04-E-review-summary.json), [브라우저](evidence/P04-E-review-browser.txt), [프런트 단위](evidence/P04-E-review-frontend-test-unit.txt), [Node 검사](evidence/P04-E-review-frontend-test.txt)를 보존한다.

제약별로 E01의 잔액/이력/멱등성, E03의 종착 상태 경쟁·만료 무효과/중복, E05의 묶음·외부 REQUIRED·감사 실패 rollback, E07의 경계·V17 이행 보존 assertion을 직접 대조했다. E02/E06의 기존 assertion은 통과했지만 E-R1의 범위가 빠져 있다. E04는 E-R2로 차단된다. E08의 실제 클래스 ArchUnit 검사는 통과했다.

P08이 구매 자격·상품 판매 가능·주문/결제 상태와 PROCESSING/UNKNOWN 해제 보류를 책임진다는 현재 신뢰된 내부 Java 계약 범위를 확인했다. 이번 기능을 공개 인증 API나 결제 연동으로 해석하지 않는다. 기본15분 정책·스케줄러·상용 부하·전체 요구사항 전수 검증도 이번 판정 범위가 아니다.

## 소스와 환경 보존

[입력 SHA256](evidence/P04-E-review-inputs.json)을 실행 전후와 추가 재현 후 대조했고 동일했다. 개발 실행 이후 변경 없이 검토했으며, 검토 종료 시점에도 제품·검사·하네스를 편집하지 않았다.

[대상 DB](evidence/P04-E-review-target.txt)는 `d2_it_519514e7d51d42a7bc9c7083ea48ebaa`, media는 해당 실행의 `db/media`다. 기존 DB 목록124개, 미디어354개 SHA256, 개발/테스트 DB 덤프 해시, 컨테이너·서버 리스너가 [추가 재현 후 보존 비교](evidence/P04-E-review-preservation.json)에서도 유지됐다. 과거 검증 DB 전체 행의 전수 덤프 비교를 했다는 뜻은 아니다.

추가 재현은 이 새 독립 DB에만 새 SKU·예약 fixture를 남겼다. 기존 SKU 잔액/제품 데이터는 변경하지 않았고 모든 실패/성공 DB·미디어를 유지했다. 커밋·푸시·P05는 진행하지 않았다. E-R1·E-R2를 수정하고 독립 하네스를 다시 통과하기 전 P04-E 완료로 기록하지 않는다.

## 최종 재검토 — 2026-10-25 12:48 KST

보완된 실제 소스와 회귀 assertion을 확인한 뒤 아래 명령을 독립 실행했다. 개발 실행 결과를 재사용하지 않았다.

```powershell
./verify.ps1 -Offline -EvidenceDirectory .local-tools/p04-e-review-final-20261025
```

### E-R1 해결 확인

`lockRequestKeys`가 reservationId와 orderItemId를 서로 다른 이름공간 문자열로 만들고, PostgreSQL `hashtextextended`의 **실제 bigint 값순**으로 중복 제거·정렬해 거래 advisory 잠금을 취득한다. 그 뒤 SKU 잔액과 예약 행을 잠근다. 유일 제약과 본문 비교는 유지한다. 해시가 우연히 충돌해도 같은 실제 잠금 순서를 사용하므로 그 충돌 자체가 역순 취득을 만들지 않는다. 충돌 예외를 숨기거나 자동 재시도로 성공시키는 수정이 아니다.

E09/E10은 서로 다른 SKU를 사용하면서 예약ID 또는 주문항목ID를 역순으로 공유한다. 제어 거래가 INSERT 후의 공유 advisory gate를 먼저 막고, 두 작업 거래가 실제 advisory 대기에 들어갔음을 확인한 뒤 연다. 이전 구현에서는 양쪽이 첫 INSERT를 끝내 gate에서 대기하고 이후 두 번째 INSERT끼리 교착한다. 수정 구현에서는 하나가 gate, 다른 하나가 전역 키 잠금에서 대기하므로 첫 거래가 끝난 뒤 후속 거래가 계약상의409를 받는다.

개발 RED 원문의 두 실패가 각각 `inventory_reservation_pkey`와 `inventory_reservation_order_item_id_key`의 교착임을 [직접 확인했다](evidence/P04-E-review-fix-red-db.txt). 이번 독립 실행의 E09/E10은 모두 성공1·409 하나, 예약/이력2건, reserved 합2, onHand 합20, 잔액 version1·3을 검증했다. 즉 패자 거래가 잔액·예약·이력을 남기지 않는다. 기존 E02/E06도 유지됐다.

최초 독립 재현 코드의 ‘두 거래 모두 첫 INSERT를 끝낸 뒤 Java barrier에서 만남’은 수정 후 키 직렬화와 양립하지 않는다. 이를 그대로 재실행하면 수정으로 막힌 두 번째 INSERT 진입을 기다리는 인위적 정지가 되므로 재사용하지 않았다. 원래 증거는 수정 전 실패 이력으로 보존하고, 동일 오류를 기존 구현에서 실제 재현한 제어 gate 회귀를 독립 하네스로 검증했다. 이 변경을 검사 제외나 실패 완화로 보지 않는다.

### E-R2 해결 확인

E04는 실제 잔액 잠금 대기를 관측하는 assertion을 유지했다. 대기를 확인한 뒤 제어 거래가 검사 대상 예약의 기한을 DB `clock_timestamp()` 경계로 옮기고, 기한·DB 현재 시각·due 조건을 확인한 후 잠금을 푼다. 확정409와 잔액3/예약1/version2 유지, 후속 만료 해제 후 잔액3/예약0/version3을 검증한다. E03의 중복 만료 한 효과 검사도 유지됐다.

고정 `pg_sleep(1.1)` 뒤 실제 시간이 지났다는 가정을 제거했으며, 테스트 timeout을 늘리거나 기한 검사를 삭제하지 않았다. E04가 이번 독립 DB에서 통과했다. 최초 실행에서 DB 시계가 어떻게 움직였는지는 미확정 이력으로 남기며, 이를 제품 만료 결함으로 소급 단정하지 않는다.

### 최신 실행 결과

| 검사 | 최종 독립 결과 |
| --- | --- |
| JUnit 기본·ArchUnit | 137 통과, ArchUnit18 포함 |
| 실제 PostgreSQL | 99 통과, 실패/오류/skip0 |
| PC·모바일 브라우저 | 22 통과, 재시도 통과 없음 |
| Vitest / Node HTTP·프록시 | 96 / 68 통과 |
| 하네스 자체 거부 사례 | 13 통과 |
| 타입·린트·빌드 | 통과 |
| 전체 하네스 | 종료0, status=passed, preservation=passed |

[최종 결과](evidence/P04-E-review-final-result.json), [DB 집계](evidence/P04-E-review-final-summary.json), [필수 검사 매핑](evidence/P04-E-review-final-coverage.json), [브라우저](evidence/P04-E-review-final-browser.txt), [프런트 단위](evidence/P04-E-review-final-test-unit.txt), [Node 검사](evidence/P04-E-review-final-test.txt), [하네스 자체 검사](evidence/P04-E-review-final-gate-tests.txt)를 보존했다. 이번에는 별도 프런트 결과를 합산한 것이 아니라 하나의 전체 하네스 안에서 모두 실행됐다.

### 최종 보존·한계

새 DB는 `d2_it_2e81525ba03246ec824dfdfa5f63dcc1`, media는 `.local-tools/p04-e-review-final-20261025/db/media`다. [시작 전](evidence/P04-E-review-final-before.json)·[종료 후](evidence/P04-E-review-final-after.json)를 비교해 기존 DB 목록127개, 미디어402개 SHA256, 개발/테스트 DB 덤프 해시와 컨테이너·리스너가 유지됨을 확인했다. [실행 입력 SHA256](evidence/P04-E-review-final-inputs.json)도 종료 후 실제 파일과 다시 대조해 동일했다.

기존 실패·추가 재현 DB와 미디어를 모두 유지했다. 여러 모듈/예약 호출을 한 외부 거래에서 섞는 경우의 전체 잠금 순서는 문서화된 호출자 계약을 따라야 한다. 실제 주문/결제 상태, 기본15분 정책, PROCESSING/UNKNOWN 해제 보류, 스케줄러와 운영 부하는 후속 범위다. 이 조건 아래 P04-E 확정 범위의 독립 검토에 필수 차단이 남지 않았다. 제품·검사·하네스 편집, 커밋·푸시·P05 진행은 하지 않았다.
