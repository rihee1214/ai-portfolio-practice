# P09-D 지정 독립 검토 기록

2026-11-02 최신 독립 판정: **같은622입력의 개발·지정 독립 Offline 전체 PASS, 신규 필수 지적0, R-D01/R-D02 ADDRESSED 유지**. 실제 결과와 한계는 §18을 따른다. 부모의 최종 C+D 증거 보존 감사·인계 마감은 별도 후속이며 아직 이 문서에서 D 전체 마감을 선언하지 않는다. 아래 초기·중간 검토의 미실행 표기는 당시 이력이다.

최신 지적 상태(2026-11-01): **R-D01 ADDRESSED / R-D02 ADDRESSED**. R-D02는 아래 `r02-fix-review1`의 typed order 정산 포트·실제 JDBC/귀속 음성 검사로 재검토했다. 앞선 OPEN 판정은 해당 고정본의 당시 이력으로 보존한다. D 전체 검증·최종 승인과 구분한다.

## T1 초기 접수 고정본 정적 검토 — 2026-11-01

**판정: 요청된 초기 T1 범위에서 수정이 필요한 현재 결함을 발견하지 못했다. 후속 개발 진행 가능. D 전체 승인·T1 전체 검증 완료·전체 하네스 통과 판정은 아니다.** 신규 P1/P2 수정 요구0이다. 다음 권한/rollback/window/canonical 검사와 후속 단계는 별도 검토·실행 근거가 필요하다.

부모가 지정한 불변 스냅샷만 읽었다. 현재 live 구현은 Sol이 후속 개발 중이므로 그 변경을 이 판정에 포함하지 않았다. 본 검토세션의 변경 파일은 이 문서뿐이며 코드·검사·하네스 실행/수정·커밋·푸시·배포는 없다. 기존 서버·DB·미디어·실패 기록을 변경하지 않았다. 설계 기준은 [확정 D1](P09-D-design-review.md)이다.

### 소스 식별과 무결성

- 고정 경로: `.local-tools/p09-d-execution-20261101/t1-review1/source`.
- [identity](../../.local-tools/p09-d-execution-20261101/t1-review1/identity.json): 생성2026-11-01 15:02:05 KST, HEAD `80ad8cd4d194766a4e09e7e49c5ecce1b46bc506`, 입력562, artifact567. HEAD만으로 미커밋 구현을 식별하지 않고 inputs/manifest를 함께 사용한다.
- [inputs](../../.local-tools/p09-d-execution-20261101/t1-review1/inputs.json) SHA256 `D2EBE3B96A75DF80DA264C2BE3F7B40B6F8CF9093DA79FA59E72B824402A5627`.
- 독립 읽기 전용 해시 대조: inputs562의 스냅샷 파일 불일치0, [artifact-manifest](../../.local-tools/p09-d-execution-20261101/t1-review1/artifact-manifest.json)567파일 불일치0. manifest가 가리키는 고정 경로와 원 source 표기를 확인했다.
- 개발 `p09-d-eligibility-green1-20261101/inputs.json`의 실제 파일 SHA256도 위와 일치한다. 이 동일성은 아래 제한된 개발 실행과 검토 소스를 연결하며 독립 실행을 뜻하지 않는다.

### 검토 대상과 확인한 계약

아래 파일 위치는 모두 위 `source/backend/src/` 아래 고정본 기준이다.

| 경계 | 정적 검토 근거와 판단 |
| --- | --- |
| HTTP/입력 | `main/java/.../claim/web/ReturnController.java`는 필드 집합·자료형·UUID/책임 enum을 검증하고 서버 세션/Principal/current auth version/GUEST_ACCESS로 Authority를 구성한다. 금액·full/confirmed를 입력받지 않는다. `AuthConfiguration.java:78`의 permitAll은 비회원 접근 통로이며 CSRF 제외를 추가하지 않는다. 현재 소유권은 아래 업무 포트에서 확인한다. |
| 현재 소유권 | `JdbcReturns.java:38`에서 `OrderQueries.lockOwned`를 먼저 호출한다. 고정본 `JdbcOrderQueries.owned`는 현재 member/GAS 자격→소유 order 잠금→대기 후 자격 재검사 순서다. `JdbcReturns.java:53`은 마지막 업무 쓰기 후 재검사한다. 동일키 재생도40행에서 현재 자격을 다시 확인한다. 이 호출 구조를 확인한 것이며 신규 D 권한 음성/실제 잠금대기 검사를 실행했다고 주장하지 않는다. |
| 신청 상태·원단위 | `JdbcReturns.java:41~45`는 PAID/revision, `ReturnApplications.begin`, 실제 RETURN intent 등록 순서다. `JdbcReturnApplications`는 `ReturnSources.lock`으로 실제 원배송/원net/구매 정책·동일 seller를 취득하고 B hold를 확보한다. `ReturnRules.canonical`은 정렬·중복/책임 누락을 거부한다. 기존 order/intent 포트의 원단위 상한·귀속 검증도 유지한다. |
| 시간 근거 | begin은 당시 source와 hold 확보 후 접수시각/저장 window를 보존한다. T1에서 `ReturnRules.withinWindow`와 기존 source/hold 적격성을 검사한다. V59는 실제 원 배송완료와 **저장 requested_at**의 범위를 검사하며 후속 거래의 현재 시각을7일 기준으로 대입하지 않는다. accepted 후속 포트 구현은 이번 범위가 아니다. |
| 거래 완전성 | `JdbcReturns.request` 한 TransactionTemplate 안에서 order application/hold→intent→request/unit→history→Outbox를 저장한다. V58의 복합FK와 V59 deferred guard가 같은 id/hold/cause/order/seller/kind, 정확한 단위집합, 실제 snapshot/net/배송/정책/기한, 최초 receipt·history·Outbox를 commit 시점에 검사한다. 단순 begin만 commit하는 공백은 V59:24에서 거부한다. |
| 현재 hold와 금융 분리 | V59는 REQUESTED의 ACTIVE RETURN hold와 HELD 단위 일치를 요구한다. REQUESTED에서 payment attempt/fee assessment가 있으면112행의 `RETURN_REQUEST_IS_NOT_FINANCIAL_CONSENT`로 거부한다. 해당 테이블의 insert/update에도 guard가 걸린다. request 서비스 자체에 payment/fee/inventory/coupon 효과 호출은 없다. C의 D application 없는 공통 RETURN intent는 새 D guard의 적용 대상에서 제외된다. |
| 불변 receipt·재생 | 입력 fingerprint는 order/revision/canonical units/strip reason을 결합한다. order별 key 잠금·고유 제약으로 동일 내용은 최초 receipt, 다른 내용은409다. 재생은 현재 revision·hold 재취득보다 먼저 처리돼 기존 결과를 다시 만든다고 해석하지 않는다. V58은 application/unit/history 및 request 식별·최초 receipt의 update/delete를 금지한다. V59는 receipt 단위별 책임·식별·시각·원 revision·REQUESTED/version0을 실제 행과 대조한다. |
| 기록 이벤트 | `ReturnJobs`는 기록 이벤트의 payload/cause 일치를 확인하고 ACK할 뿐, 승인·금융·반품완료를 생성하지 않는다. ACK를 업무완료로 주장하지 않는 현재 T1 범위에 맞다. 접수 사실은 DB에 영속된다. |

`...`는 `com/example/marketplace`다. V58/V59는 이미 적용된 migration으로 보존한다. 향후 보완이 필요해도 실행된 파일을 고쳐 과거 검증을 소급하지 않는다.

### 실제 읽어 확인한 개발 근거와 한계

[개발 결과](../../.local-tools/p09-d-eligibility-green1-20261101/result.json)는2026-11-01 14:57:02~14:58:25 KST, **root status=failed / preservation=passed**다. 오류는 선택되지 않은 필수 `PostgresJdbcIT#accountHttpOwnerCsrfValidationAndPrivacyBoundaries` 누락이다. 이는 선택 검사의 성공을 전체 성공으로 올리지 않는 하네스 동작이며 전체 D 통과가 아니다.

| 실제 XML/검사 | 읽어 확인한 결과·assertion |
| --- | --- |
| `db/build/surefire-reports/TEST-*.xml` | 총198, failures0/errors0/skipped0. 이 중 ReturnRulesTest2는 정확7일 경계/직전·이후/이전 시각/null/overflow, 정렬·중복·음수 ordinal·책임누락·빈집합을 검사한다. |
| `db/build/failsafe-reports/TEST-com.example.marketplace.dbtest.PostgresJdbcIT.xml` | tests2, failures0/errors0/skipped0. 아래 두 메서드가 실제 수집돼 있다. |
| `returnRequestAcquiresOriginalUnitsWithoutFinancialReservation` — 고정 test파일961행 | 실제 paid/shipped/delivered fixture에서 HTTP202·REQUESTED, 실제 RETURN hold/intent 각1, 원단위 HELD, payment attempt/fee assessment 수 불변, inventory balance 불변, 부모PAID, 같은key 응답 JSON 동일·hold추가0을 assertion한다. 현재 원금값을 임의 입력하는 경로는 없다. |
| `returnEligibilityReceiptOnlyCannotCommitWithoutApplicationGraph` — 고정 test파일946행 | 실제 공개 `ReturnApplications.begin`만 수행한 transaction commit이 SQLException SQLState23514로 거부되고 application/hold/기존 participant snapshot 전체가 동일함을 assertion한다. 단순 애플리케이션 예외가 아니라 실제 DB 거부를 요구한다. |

첫 정상접수 RED의 expected202/actual404, compile2의 wildcard import Checkstyle 준비 실패, eligibility-red1의 begin-only graph commit 허용 실패는 [실행 ledger](../../.local-tools/p09-d-execution-20261101/progress.md)와 각 원 manifest/로그에 남아 있다. eligibility-red1의 실제 root failed/preservation passed도 읽었다. 준비 실패와 업무 RED를 구분하고, 이후 GREEN으로 과거 실패 결과를 바꾸지 않는다. 본 검토는 과거 전수 증거 감사의 재수행이 아니다.

### 현재 결함 판단과 후속 검토 경계

새로 보고할 재현 가능한 현재 설계 위반·악용 경로는 발견하지 못했다. 따라서 이 고정본을 위한 신규 수정 요구나 임의 우선순위 지적을 만들지 않는다. 검토는 정적 분석과 위 개발 증거 읽기이며 독립 동적 재현은 **NOT RUN**이다. “발견하지 못함”을 모든 DB 위조 쓰기·모든 동시성 경로의 안전 증명으로 확대하지 않는다.

명시된 후속 T1 권한/실제 lock 대기·각 단계 rollback·window/canonical/replay 충돌 검사는 기존 계획대로 이어간다. 아직 실행하지 않은 그 범위는 이번 결함 목록으로 중복 열거하지 않으며, 실행 전 검증됐다고도 표시하지 않는다. 승인·회수·검수·금융·완료와 accepted 후속 포트 역시 이번 좁은 검토의 승인 대상이 아니다.

후속 구현은 원 receipt와 현재 진행 상태를 분리하고, 기한 내 접수 후 계속 처리·실제 회수/SELLER 확인 후 배송비 지급·미수납/미회수 조기종결 금지 등 [D1](P09-D-design-review.md)의 확정 계약을 따른다. 다음 동결본과 실제 실행 근거가 전달되면 그 범위로 재검토한다. 최종 D 판정에는 새 개발/지정 독립 Offline 전체 및 제약별 assertion·기존 자원/실패 보존 근거가 별도로 필요하다.

## 명령 수직 고정본 정적 검토 — 2026-11-01

**판정: P2 R-D01 모듈 소유권 위반1건 수정 필요. 그 외 이번 승인·반려·철회·GET 범위에서 추가 현재 결함을 발견하지 못했다.** 아래는 새 고정본에 대한 판정이며 앞선 T1 기록을 소급 변경하지 않는다. D 전체 승인/독립 하네스 통과가 아니다. 본 검토는 runtime/검사 실행 없이 스냅샷·기존 XML/JSON/ledger를 읽고 이 문서와 설계 §11만 갱신했다.

### 고정 소스와 실행 연결

- 경로 `.local-tools/p09-d-execution-20261101/commands-review1/source`, [identity](../../.local-tools/p09-d-execution-20261101/commands-review1/identity.json) 생성15:41:19 KST, HEAD `80ad8cd4d194766a4e09e7e49c5ecce1b46bc506`.
- 입력565 SHA256 `E1E10444030B782B3E2F00E186FED1C0609174D9D87C7CC9D62541593F375DD1`. 독립 읽기 전용 대조에서 [inputs](../../.local-tools/p09-d-execution-20261101/commands-review1/inputs.json)565·[artifact-manifest](../../.local-tools/p09-d-execution-20261101/commands-review1/artifact-manifest.json)570 각각 불일치0.
- 아래 `race-focused2` inputs와 동일하다. `commands2-green1`에서 이 고정본까지 inputs 차이는 `PostgresJdbcIT.java` 하나이며, `command-green1` 대비는 `ReturnCommands.java`·같은 DB검사 파일·required-tests3파일이다. 따라서 서로 다른 실행의 검사 수를 더해 동일 소스 전체 통과로 표현하지 않는다.
- 검토 대상: 변경 Returns/JdbcReturns/ReturnApplications/JdbcReturnApplications/AuthConfiguration/PostgresJdbcIT/required-tests와 신규 ReturnCommands/ReturnCommandController/V60. 필요한 기존 OrderQueries/hold/guard 경계도 같은 스냅샷에서 확인했다. V58~60은 적용 완료 불변이다.

### R-D01 — P2 / OPEN: order 접수 검증이 claim 내부 저장소를 직접 조회

위치: 고정본 `backend/src/main/java/com/example/marketplace/order/infrastructure/JdbcReturnApplications.java:70`, `validate(Accepted)`.

실제 코드 `SELECT count(*) FROM marketplace.claim_financial_intent i JOIN marketplace.claim_return_request r ...`는 order 모듈이 claim workflow/intent 테이블 구조를 직접 알아야 하게 만든다. [D1 §2/6](P09-D-design-review.md)의 order 소유 증거·claim 조정 책임 분리와 맞지 않는다. import 기반 ArchUnit 통과는 SQL 문자열의 모듈 경계를 입증하지 않는다. 현재 권한 우회가 관측됐다는 지적은 아니며 후속 workflow/schema 변경을 order 내부까지 전파하는 실제 소유권 위반이다.

정적 재현 경로: 정상 접수된 반품에 실제 판매자 approve/reject 또는 본인 withdraw → `ReturnCommands.execute`의 `applications.lockSeller/lockOwner` → `JdbcReturnApplications.validate` → 위 claim 테이블 JOIN. 정상 명령마다 도달하는 경로로, 아직 없는 회수/검수 기능을 가정한 문제가 아니다. 독립 실행 재현은 요청 범위에 따라 NOT RUN이다.

**수정 판단**: [설계 §11](P09-D-design-review.md)에 확정했다. order는 현재 actor/본인 order·seller, application/source/hold/cause/정확한 원단위·HELD lifecycle만 검증한다. claim coordinator가 accepted와 현재 claim 귀속·workflow/revision을 확인하고 기존 FK/deferred graph와 같은 모듈의 intent 경계가 전체 연결을 보장한다. order에서 claim 직접 SQL을 제거하되 현재 검증을 모두 없애거나 order→claim API 역의존으로 바꾸지 않는다. 적용된 V58~60 수정은 불필요하다.

수정 후 요구 근거:

- 좁은 order JDBC SQL 기록 검사 등으로 lockOwner/lockSeller가 claim 내부 테이블을 조회하지 않는지 확인한다. Java import 검사만으로 해결 표시하지 않는다.
- 정상 승인/반려/철회·GET와 동일key 재생, 기존 접수의 기한 뒤 처리, 현재 권한/실제 최종 잠금대기 만료, 전체 participant rollback·재개 검사를 유지·재실행한다.
- begin-only 및 terminal-state 없는 receipt-only commit이 계속 SQL23514로 거부되는지 확인해 책임 분리로 DB 완전성 방어를 잃지 않았음을 입증한다. 다른 주문/hold/단위 귀속 거부도 보존한다.

### 명령 경로의 나머지 검토 결과

| 경계 | 확인한 내용·판단 |
| --- | --- |
| 권한·잠금 | route 읽기는 잠금 없는 불변 식별 라우팅이며, 이후 현재 buyer 또는 자기 seller 권한→부모 order→order 원단위/hold→claim 잠금 순서다. 판매자는 `lockSellerOrder/writePaidSellerOrder`로 EXISTING_TRADES·현재 세션과 자기 seller-order를 검증한다. 마지막 claim 잠금 대기 뒤에는 명령 끝 `checkAuthority`로 재검사한다. buyer Authority를 판매자 명령에 재사용하지 않는다. |
| 상태·재생 | 신규 명령은 REQUESTED+expectedRevision만 허용한다. APPROVED는 hold ACTIVE 유지, REJECTED/WITHDRAWN은 release와 상태/history/별도 command receipt/Outbox를 한 거래로 저장한다. 부모 order 직렬화와 key/fingerprint로 중복은 원 영수증, 변경내용409다. 재생도 현재 actor 권한을 확인하며 이미 종결된 명령의 원 영수증을 새 진행 상태로 덮지 않는다. |
| GET | 현재 소유 order 잠금을 얻은 뒤 return의 order 귀속을 확인하고 최신 Progress와 불변 accepted를 분리한다. 무권한 응답에 반품 내용이 노출되는 경로는 발견하지 못했다. 판매자 전용 조회/UI는 이번 완료 범위로 확대하지 않는다. |
| 기한 뒤 기존 처리 | lockOwner/lockSeller는 저장 application과 현재 hold를 검증하고 `ReturnSources.lock`의 신규 신청 기한 검사를 재호출하지 않는다. 기존 신청에 대한 승인 시각은 제어 Clock으로 검사됐다. 현재 사용자 자격은 별도로 유지해야 한다. |
| DB guard | V60은 현재 version의 command/action과 request state, accepted receipt, 해당 history reason·Outbox를 연결하며 불변 command와 history FK를 둔다. V59는 release 상태/hold outcome, 정확한 단위·최초 영수증을 계속 확인한다. 제품 명령은 읽은 실제 history로 Progress를 만든다. 임의 SQL 모든 변조에 대한 포괄 안전 증명으로 확대하지 않는다. |
| B 재평가 | 반려/철회가 호출하는 기존 hold.release의 같은 거래에서 원단위 OPEN·revision과 재평가 schedule/history/Outbox가 생성된다. 이번 결과로 B 자동확정 worker까지 끝난 E2E를 주장하지 않는다. |

회수·검수·금융·배송비·쿠폰/종결·GAS 추가경계·UI는 명시된 후속 범위다. 이를 신규 결함으로 중복 열거하지 않았다. V60의 명령 action/state 제약은 현재 세 명령을 대상으로 하며 후속 단계 확장은 새 migration/검사로 처리해야 한다.

### 실행 증거의 직접 확인과 검사 적합성

각 실행의 root `result.json`, surefire/failsafe XML을 읽었다. 모든 선택 실행은 **root failed / preservation passed**다. command-green1·race-focused2는 필수 미선택, commands2-green1은 실제 경합 준비 assertion 실패도 있다. unit198은 각 실행에서 failures/errors/skipped0이며 DB 결과는 다음과 같다.

| 실행·종료 KST | 실제 DB 결과 | 이번에 확인한 근거 |
| --- | --- | --- |
| `.local-tools/p09-d-command-green1-20261101`,15:25:22.3622799 |3/실패0·오류0·skip0 | 서로 다른 실제 buyer/seller, 정상 승인/반려/철회 HTTP200, 타 seller 접근 거부, 초기 accepted 보존·hold outcome·금융0·같은key 추가효과0 |
| `.local-tools/p09-d-commands2-green1-20261101`,15:32:55.6798042 |6 중5통과/실패1·오류0·skip0 | GET/내용충돌/승인 뒤 철회·반려409, 3명령×5 SQL실패 지점의23514 및 전체 rollback·재개, receipt-only commit 거부, 기한 뒤 기존 처리와 신규 거부, 마지막 claim 잠금 뒤 현재 actor 만료 거부. 경합1건은 실패 이력으로 유지 |
| `.local-tools/p09-d-race-focused2-20261101`,15:39:35.3814397 |1/실패0·오류0·skip0 | 동일 반품에 승인/반려 두 DB 연결의 실제 대기 체인, winner1/4091, 상태/version1·history2·command1·hold일치·금융0·같은key 추가효과0 |

고정 `PostgresJdbcIT`의 주요 메서드는 `returnActorCommandsRollBackEverySqlStageAndResumeExactlyOnce`(999행), `returnReleasedReceiptWithoutTerminalStateCannotCommit`(1022), `returnAcceptedBeforeDueContinuesAfterDueWhileNewRequestIsRejected`(1040), `returnActorAuthorityExpiresAfterFinalClaimLockWithoutEffects`(1052), `returnApprovalAndRejectionRaceHasOneRevisionAndOneOutcome`(1072)다. rollback 검사는 SQL23514뿐 아니라 주입한 checkpoint 메시지를 확인하고 전체 participant를 비교한다. 단순 결과 수로 원자성을 주장하지 않는다.

[실제 대기 JSON](../../.local-tools/p09-d-race-focused2-20261101/db/return-approve-reject-lock-chain.json)은 parent order waiter1364627←외부1364623, identity_user `FOR NO KEY UPDATE` waiter1364625←1364627을 담는다. 현재 테스트는 assertion 전에 원문을 저장하고, 다른 PID의 blockers에 첫 PID가 포함되는 것을 확인한다. 같은 seller actor 두 명령이 identity에서 먼저 직렬화되는 실제 경로를 입증하며, 두 명령이 모두 order에서 동시에 대기했다고 주장하지 않는다. buyer철회와 seller명령 사이의 모든 경합까지 일반화하지 않는다.

세션 만료 JSON과 코드는 실제 claim 행 잠금 대기를 관측한 뒤 저장 세션 deadline을 제어 Clock으로 넘기며404/전체불변을 검사한다. 기한 뒤 처리도 저장 원배송 due와 주입 Clock의 검사다. **자연시간7일 경과·상용 부하·자연 만료 테스트로 확대하지 않는다.** 정상 Clock 대조는 실제 자격을 DB로 복원한 것이 아니라 같은 검사 내 시간 주입을 되돌린 별도 대조다.

앞선 commands2-red1의 업무시각 assertion 실패와 STORE_REJECTED 준비 오류, commands2-green1/race-focused1의 대기 위치 조기 단정 실패는 ledger와 원문에 보존된다. 앞의 두 race 실패에는 assertion 전 PG 대기 원문이 없으므로 당시 실제 대기 위치를 단정하지 않는다. 최종 실제 체인으로 검사 가정을 정정했으며 제품 잠금 순서 변경으로 해결했다고 기록하지 않는다.

현재 고정본과 commands2-green1의 제품 파일은 동일하지만 테스트 파일은 다르다. command-green1 이후에는 ReturnCommands의 Clock 변경도 있었다. 이 차이와 선택 실행 한계를 유지하고 R-D01 수정 후 새 증거에서 관련 검사 재실행을 확인한다. 독립 하네스는 이번 요청에서 **NOT RUN**, D 최종 수용은 별도다.

## R-D01 수정 고정본 재검토 — 2026-11-01

**판정: R-D01 ADDRESSED.** [설계 §11](P09-D-design-review.md)의 order/claim 책임 분리가 반영됐으며, 이 수정에 대한 필수 미해결 지적은 없다. 현재 범위에서 추가 결함을 발견하지 못했다. 이는 고정 소스 정적 검토와 기존 개발 증거의 독립 대조 결과이며 D 전체 승인·독립 하네스 통과가 아니다.

### 고정 소스와 변경 범위

- `.local-tools/p09-d-execution-20261101/r01-fix-review1/source`, [identity](../../.local-tools/p09-d-execution-20261101/r01-fix-review1/identity.json) 생성15:59:02 KST, HEAD `80ad8cd4d194766a4e09e7e49c5ecce1b46bc506`.
- [inputs](../../.local-tools/p09-d-execution-20261101/r01-fix-review1/inputs.json)565 SHA256 `7D339FB650737C837CBCE9568006E85E4B8B017C63B931E83A04EB395C51558F`. 독립 읽기 전용 대조에서 입력565·[artifact](../../.local-tools/p09-d-execution-20261101/r01-fix-review1/artifact-manifest.json)570 모두 불일치0.
- commands-review1 대비 입력 변경은 제품 `JdbcReturnApplications.java`/`ReturnCommands.java`와 `PostgresJdbcIT.java`/`required-tests.json` 네 파일뿐이다. V58~60은 해시 동일하며 적용본을 수정하지 않았다.
- boundary-green1 대비 이 고정본 변경은 PostgresJdbcIT 한 파일이며 제품 변경0이다. boundary-get-focused1 inputs SHA는 현재 고정본과 동일하다. 입력 대조로 직접 확인했으며 [delta 기록](../../.local-tools/p09-d-execution-20261101/boundary-get-focused1-delta.json)과도 일치한다.

### 수정 계약 확인

`JdbcReturnApplications.validate`의 claim intent/request JOIN이 제거됐다. 동일 클래스의 현재 owner/seller 검증·불변 application load·원 source 귀속·ACTIVE RETURN hold/cause/정확한 단위·HELD lifecycle/seller 검증은 남아 있다. order→claim 공개 API 역호출이나 별도 SQL helper로 옮긴 우회도 없다.

claim의 `ReturnCommands.execute`는 기존 actor→order/원단위→claim 잠금 경계에서 현재 request row를 잠근 다음 `validateClaim`을 호출한다. accepted id/order/seller/hold/cause를 현재 row와 대조하고, 같은 claim 모듈의 `FinancialIntents.lock`으로 실제 RETURN intent의 order/seller/hold/cause와 canonical 원단위집합을 검증한다. 그 뒤 기존 REQUESTED/version 조건·전이·영수증/history/Outbox와 최종 현재 권한 검사가 이어진다. 동일key 재생의 현재 권한 및 불변 영수증 계약도 유지한다.

따라서 order에서 삭제한 검증 책임을 단순히 없앤 것이 아니라 소유 모듈로 옮겼다. 기존 FK/불변성/deferred graph는 그대로이며 begin-only·terminal-state 없는 receipt-only 거부도 아래 실제 재실행에서 유지됐다.

### 실제 JDBC 경계 RED→수정 근거

고정 `PostgresJdbcIT#acceptedReturnOrderJdbcNeverReadsClaimStorage`(1121행)는 기존 실제 DataSource를 TransactionAwareDataSourceProxy로 감싸 실제 거래 연결의 prepareStatement/prepareCall을 기록한다. 반환값을 가짜로 제공하지 않으며 기록된 SQL을 실제 연결로 실행한다. recorder를 `JdbcReturnApplications`에 주입해 해당 adapter의 직접 SQL을 분리해 관측한다. 다른 order 포트 전체의 SQL이나 서버의 모든 SQL을 수집하는 검사라고 확대하지 않는다.

- [boundary-red1 SQL 원문](../../.local-tools/p09-d-boundary-red1-20261101/db/return-order-jdbc-ownership.json): lockOwner/lockSeller 각각에서 order application SELECT 뒤 claim intent/request JOIN이 실제 수집된다. 금지 relation assertion 전에 원문이 저장돼 있으며 DB1/failure1이 원 지적을 재현한다.
- [boundary-green1 SQL 원문](../../.local-tools/p09-d-boundary-green1-20261101/db/return-order-jdbc-ownership.json): lockOwner·lockSeller·foreignOwner·foreignSeller 네 경로 모두 order application SELECT만 있고 claim relation0이다. 정상 두 경로는 실제 접수 id를 반환하고 타주문·타판매자 경로는404 및 전체 participant 불변을 검사한다.
- 검사 본문은 정상 두 entrypoint 각각에서 실제 order SQL이 수집됐는지도 요구한다. 비어 있는 기록으로 claim0을 통과시키는 검사가 아니다. static 코드 대조와 이 좁은 실행 경계가 함께 R-D01 해결을 뒷받침한다.

### 실행 결과·실패 이력의 분리

각 root result와 원 surefire/failsafe XML을 직접 읽었다. 모두 **root failed / preservation passed**이며 unit198은 각 실행 실패/오류/skip0이다.

| 실행·종료 KST | 실제 DB 결과 | 해석 |
| --- | --- | --- |
| `.local-tools/p09-d-boundary-red1-20261101`,15:50:31.5807839 |1/실패1·오류0·skip0 | 실제 claim JOIN 소유권 위반 RED, 원 실패 보존 |
| `.local-tools/p09-d-boundary-green1-20261101`,15:54:33.4789975 |11 중10통과/실패1·오류0·skip0 | JDBC 소유권·정상 명령3·기한 뒤 진행·최종 잠금 뒤 현재 권한·15 SQL rollback/재개·begin-only·terminal receipt-only·실제 경합 통과. GET 비교1건 실패 |
| `.local-tools/p09-d-boundary-get-focused1-20261101`,15:57:38.7369502 |1/실패0·오류0·skip0 | 수정된 GET 전체 메서드 통과. 제품 변경 없이 실제 Progress DTO 전체 동등성 및 이후 private/replay 검증까지 완료 |

boundary-green1의 실패는 `returnCommandGetConflictsAndApprovedWithdrawalRemainPrivate`996행 ObjectNode 동등성 비교이며, HTTP GET200 뒤 실패했다. ledger는 LongNode/IntNode 표현 차이를 원인으로 기록한다. 현재 고정본은 응답을 실제 `Returns.Progress`로 역직렬화해 accepted·현재 state/version·history 전체 record 동등성을 검사한다. 필드를 빼거나 상태코드만 확인하도록 약화하지 않았다. 원 실패 실행에서는 뒤의 타인/익명 GET·재생 assertion에 도달하지 않았고, 새 focused 실행은 해당 메서드 전체가 통과했다. 두 실행을 합쳐 **단일11개 전체 통과**로 표현하지 않는다.

준비 compile15 실패와 이전 실패 manifest·emulator 증거는 ledger에 남아 있으며 이번 검토에서 변경하지 않았다. 스냅샷에 포함된 회수/검수 RED2와 필수 등록은 명시된 후속 미완료 범위로, R-D01 해결 판정을 물리 반품 구현·검증 완료로 확대하지 않는다.

본 재검토의 변경은 이 리뷰 문서뿐이다. live 코드·검사·하네스·migration·증거를 수정하지 않았고 새 검사·하네스·커밋·푸시·배포는 수행하지 않았다. 지정 구현 담당은 확정된 후속 범위를 계속 진행할 수 있으며, 최종 D 수용에는 같은 동결본의 새 개발/독립 전체와 제약별 근거가 별도로 필요하다.

## 첫 물리 회수·검수·신뢰 입고 고정본 검토 — 2026-11-01

**판정: 이번 고정본의 현재 물리 처리 범위에서 수정이 필요한 결함을 발견하지 못했다. 신규 필수 수정 요구0, R-D01 ADDRESSED 유지.** 확정된 후속 개발을 계속할 수 있다. 이 판정은 정적 검토와 기존 실행 근거의 독립 대조이며 D 전체 승인·독립 하네스 통과가 아니다.

### 고정 범위와 소스 식별

- 경로 `.local-tools/p09-d-execution-20261101/physical-review1/source`, [identity](../../.local-tools/p09-d-execution-20261101/physical-review1/identity.json) 생성16:49:12 KST, HEAD `80ad8cd4d194766a4e09e7e49c5ecce1b46bc506`.
- [inputs](../../.local-tools/p09-d-execution-20261101/physical-review1/inputs.json)575 SHA256 `F99091D4432E94BDD9D810AADB39425DA3141A2E34D41D43CD061EF065FDE967`. 고정 파일575 및 [artifact-manifest](../../.local-tools/p09-d-execution-20261101/physical-review1/artifact-manifest.json)580의 독립 읽기 전용 해시 대조 각각 불일치0.
- r01-fix-review1 대비 기존 변경6: Returns/ReturnCommands/Restitutions/JdbcRestitutions/PostgresJdbcIT/required-tests. 신규10: ReturnCollections/ReturnInspections, ReturnPhysicalFacts/ReturnCollectionCommands/ReturnInspectionCommands, 두 controller, V61~63. 직접 inputs 대조로 확인했다. 기존 V58~60 동일, V61~63도 성공 적용 후 불변으로 취급한다.
- physical-boundary-focused1 inputs는 고정본과 동일하다. inspection-graph-green1 대비 변경은 PostgresJdbcIT/required-tests뿐이고 제품·migration은 동일하다. 서로 다른 실행의 검사 수를 합산한 전체 통과 주장은 하지 않는다.

현재 seller/accepted 근거→원단위 회수/분실 기록→RECEIVED 검수→SALEABLE 신뢰 입고, 물리 projection·조회/재생·원자성·DB graph를 검토했다. live의 후속 구현은 읽거나 판정하지 않았다.

### 소스에서 확인한 제약

| 경계 | 확인한 구현과 판단 |
| --- | --- |
| 현재 권한·귀속 | 두 물리 coordinator는 현재 자기 seller-order 권한을 먼저 얻고 order 공개 accepted 포트→claim row/intent를 잠근다. order/seller/hold/cause/RETURN 및 정확한 원단위집합을 대조한다. 마지막 업무 쓰기 뒤 현재 판매자 자격을 재검사한다. controller는 current Principal/session/version으로 Authority를 만들며 금액·입고 수량 override를 허용하지 않는다. |
| 회수 | APPROVED/COLLECTING/REVIEW_REQUIRED와 expectedRevision에서만 처리한다. 입력은 실제 신청집합의 중복 없는 canonical 원단위다. PENDING→RECEIVED/LOST_REPORTED, LOST_REPORTED→실제 RECEIVED는 새 불변 사실로 남고 이전 분실 기록은 유지한다. 이미 RECEIVED/검수된 단위를 재회수하거나 같은 사실을 새 key로 중복 기록하지 않는다. LOST가 남으면 REVIEW_REQUIRED, 회수만으로 입고/금융 효과0이다. |
| 검수 | 실제 최신 RECEIVED·NOT_INSPECTED 단위만 SALEABLE/DEFECTIVE로 기록한다. collection 참조·원단위·책임·actor/사유·시각이 영속된다. 다른 신청 단위나 미수령/분실 단위의 검수는 거부한다. 부분 검수는 COLLECTING, 미회수 분실은 REVIEW_REQUIRED, 전부 수령·검수되면 AWAITING_CONSENT로 진행하며 완료/금융 성공으로 해석하지 않는다. |
| 입고 | SALEABLE만 원 checkout/COMMITTED reservation/원 item·ordinal·SKU를 대조해 공개 `Restitutions.restoreReturn`에 전달한다. inventory는 자기 테이블만 읽고 쓰며 kind RETURN·inspection cause와 별도 marker를 기록한다. 기존 `restoreCancellation`의 fingerprint 산식은 그대로, 기존 행 kind는 CANCEL 기본값으로 이행한다. 전역 reservation+ordinal 중복 방어로 다른 CANCEL cause의 재입고도 거부한다. |
| 잠금 순서 | Reservations.inspect는 먼저 balance들을 정렬 잠근 뒤 reservation들을 잠근다. restoreReturn의 balance→reservation 순서와 일치하며 검사 경로가 reservation만 먼저 잠그는 역순으로 바뀌지 않았다. 부모 order와 seller 권한 잠금, claim/intent, inventory, 최종 권한 재검사의 현재 경계를 확인했다. 모든 미실행 경합까지 검증됐다는 뜻은 아니다. |
| 동일키·현재 조회 | key fingerprint는 동작/expectedRevision/canonical 단위·판정/사유를 포함한다. 동일키는 현재 권한 아래 최초 command receipt를 재생하며, ReturnPhysicalFacts는 최신 collection과 불변 inspection을 원단위별로 projection한다. 과거 receipt의 physical은 해당 command version 당시 사실과 비교하므로 나중 검수로 과거 응답을 덮지 않는다. |
| 금융과 분리 | physical coordinator에는 payment/fee 예약 호출이 없다. V62 guard는 REQUESTED/APPROVED/COLLECTING/REVIEW_REQUIRED/AWAITING_CONSENT에서 금융 예약을 거부한다. seller의 검수 책임 판정이 현재 구매자의 최종 동의나 금전 처분을 대신하지 않는다. |

전체 증거를 같은 transaction으로 저장하며 검수/입고와 history/command/Outbox 또는 최종 권한 검사가 실패하면 해당 거래가 rollback된다. 검수 완료 후 책임/입고 사실을 덮어쓰는 보정 명령은 현재 경로에 없다.

### V63 양방향 graph와 실제 공격 근거

V62는 inspection header/unit·RECEIVED 복합FK·불변성·RETURN inventory marker를 추가하고 과거 version의 physical projection을 검증한다. V63은 별도 신규 migration으로 검수↔입고의 양방향 연결을 보강했다. inventory Java 코드가 claim 테이블을 조회하는 방식으로 구현하지 않았다.

- inspection은 실제 application/order/seller/checkout·동일 version command/history/actor/사유/시각과 연결되고, 그보다 앞선 실제 RECEIVED collection을 요구한다.
- SALEABLE 집합은 같은 inspection cause의 RETURN marker/stock unit과 정확히 일치해야 한다. COMMITTED reservation/원 SKU·ordinal/checkout 귀속과 SKU별 movement 수량을 대조한다. 전량 DEFECTIVE는 marker/입고 receipt 자체를 거부한다.
- inventory→inspection과 inspection→inventory 양쪽 INSERT에 deferred guard가 걸려, 가짜 inspection으로 입고하거나 SALEABLE inspection만 저장하는 commit을 막는다. RETURN stock evidence의 update/delete도 막는다.
- marker INSERT 직전에는 현재 inventory balance/reserved/version이 방금 만든 movement의 after 값과 맞는지 검사한다. 이 검사는 **marker 시점**의 효과 확인이다. 이후 정상 재고 변경을 금지하거나 과거 영수증의 after 값이 현재 잔고와 영원히 같아야 한다는 계약은 아니다.

최신 [검증 파일 디렉터리](../../.local-tools/p09-d-physical-boundary-focused1-20261101/db/)에서 직접 읽은 네 공격 JSON은 모두 SQL23514·changedParticipants[]이며 정확한 거부 이유는 다음과 같다.

| 실제 공격 | 실제 guard |
| --- | --- |
| `returnInboundWithFictionalInspectionCannotCommit` | `ACTUAL_RETURN_INSPECTION_REQUIRED` |
| `returnSaleableInspectionWithoutActualInboundReceiptCannotCommit` | `ACTUAL_SALEABLE_INBOUND_RECEIPT_REQUIRED` |
| 전량 DEFECTIVE에 입고 시도 | `DEFECTIVE_INSPECTION_CANNOT_RESTOCK` |
| 실제 검수 prefix/stock ledger를 만들되 balance UPDATE 없이 marker 삽입 | `RETURN_STOCK_EFFECT_NOT_RECORDED` |

마지막 공격은 marker의 즉시 guard까지 도달하는 실제 prefix 검사다. 이후 command/history tail을 완성한 정상 거래를 검사한 것처럼 표현하지 않는다. 두 최초 graph RED의 실제 잘못된 commit은 원 실패 DB/JSON 그대로 보존되며 이후 guard 통과로 소급하지 않는다.

### 실제 실행 근거와 검사 적합성

아래 각 root result와 DB XML을 직접 읽었다. 모두 **root failed / preservation passed**다. 선택된 업무 검사가 통과한 실행도 필수 미선택 때문에 전체 통과가 아니다.

| 실행·종료 KST | 실제 DB 결과 | 범위 |
| --- | --- | --- |
| collection-green1,16:05:18 |6통과/실패·오류·skip0 | 첫 물리 회수·기존 명령/소유권 회귀 |
| physical2-red1,16:11:03 |4 중2통과/실패2·오류0·skip0 | 회수 음성/rollback2통과, 아직 없는 검수 경로2 RED |
| inspection-green1,16:17:57 |5 오류/실패0·skip0 | V62 구문42601로 context 준비 실패. 업무 본문 NOT RUN |
| inspection-green2,16:22:13 |5통과/실패·오류·skip0 | 검수·SALEABLE 입고·회수 회귀 및 기존 C 취소 입고 회귀 |
| inspection-graph-red1,16:27:39 |2실패/오류0·skip0 | 가짜 검수 입고/입고 없는 검수의 잘못된 commit 실제 RED |
| inspection-graph-green1,16:32:08 |7통과/실패·오류·skip0 | V63 후 양방향 거부와 정상 검수/회수·C 입고 회귀 |
| physical-boundary-focused1,16:43:46.280132 |8통과/실패·오류·skip0 | 아래 물리 경계 집중 검증, 같은575 입력 |

각 경로는 `.local-tools/p09-d-<실행명>-20261101`이다. 최신 surefire XML 합산은 unit/Arch198, failures/errors/skipped0이다. DB8은 정상 SALEABLE 입고, 부분·전량불량/과거재생, 실제12 SQL rollback, 최종 잠금 뒤 권한 만료, inventory JDBC 소유권 및 위 graph 공격을 포함한다.

고정 PostgresJdbcIT의 assertion을 총수와 별도로 확인했다.

- `returnInspectionRestoresOnlySaleableOriginalUnitsAndKeepsCommittedEvidence`(1181행): 실제3수령→SALEABLE2/DEFECTIVE1의 SKU별 정확한 증가량, 원 COMMITTED reservation 전체 동일, fee/attempt0, 같은key 추가효과0, 다른 CANCEL cause의 동일 원단위 입고409를 검증한다.
- `returnInspectionEverySqlAndStockCheckpointRollsBackAndResumes`(1237행): 검수 header/unit부터 재고 receipt/unit/balance/movement/marker, request/history/command/job/Outbox까지12 실제 SQL 실패지점의 marker·23514·전체 participant 불변을 확인한다. [원 checkpoint 목록](../../.local-tools/p09-d-physical-boundary-focused1-20261101/db/return-inspection-rollback-checkpoints.json)도12지점 일치한다.
- `returnPartialAndDefectiveInspectionsPreserveHistoryAndNeverAutoRefund`(1251행): 부분 SALEABLE 이후 나머지 DEFECTIVE의 추가입고0, 과거 명령의 당시 응답 재생·현재 DB 불변, 새key 중복 검수409, 전량 DEFECTIVE 입고/금융0을 검증한다.
- `returnPhysicalAuthorityExpiresAfterFinalIntentOrStockWaitWithoutEffects`(1269행): 회수는 실제 intent, 검수는 inventory_balance 잠금 대기 뒤 판매자 저장 세션 deadline+1µs 제어 Clock으로404/전체불변을 확인한다. 정상 Clock 대조도 있다. 실제 대기+제어 시간 검사이며 자연시간 만료/부하 검증이 아니다.
- `returnInboundJdbcUsesOnlyInventoryOwnedStorage`(1301행): 실제 transaction-aware JDBC recorder와 정상 검수/입고를 사용한다. 원 JSON의 직접 SQL12개에 balance UPDATE1·marker INSERT1이 있고 claim/order/payment 직접 relation0이다. 테스트는 모든 수집 relation이 inventory 소유임을 검사한다. DB trigger 내부 SQL이나 서버 전체 SQL 수집으로 확대하지 않는다.

rollback participant에는 collection/inspection/unit/marker 및 restitution unit/movement와 기존 잔고·예약·금융·history/Outbox가 포함된다. 반환 DTO만 같은 것으로 원자성을 판정한 검사가 아니다.

### 준비 실패와 보존·후속 경계

V62 실패 당시 원 SQL 사본 SHA `B1BA4F23B529AFB3926939AB49F58F21B8DA2E001B45CEB858A108B1DF2B7188`을 직접 확인했다. 저장된 migration history 원문은60/61 성공·62없음, inspection table없음이다. read-only 상수 CASE probe의 confirmed metadata는 bad exit3/good exit0/tablesWritten0이며 최초 readOnly=null metadata도 보존했다. **성공 적용 전** 괄호 보완과 이후 V63 신규 보강을 구별한다. 기존 적용 V58~63이나 과거 실패 결과를 이번 검토에서 수정하지 않았다.

이번 범위에서 추가 중요 설계 판단이나 수정 요구는 없다. 실제 사람의 물품 확인, 자연시간7일 뒤 운영, 모든 후속 권한·경합 조합이 이 집중 검사로 증명되는 것은 아니다. 최종 견적/현재 구매자·GAS 동의·금융 적용/UNKNOWN 재개·배송비·쿠폰/부모 종결·UI는 명시된 후속 미완료 범위로 유지하고 현재 물리 구현 결함으로 중복 열거하지 않는다. 같은 입력의 선택없는 개발/지정 독립 전체는 여전히 별도 필요하다.

본 검토는 이 리뷰 문서만 갱신했다. 새 검사·하네스·코드/migration 수정·커밋·푸시·배포는 하지 않았으며 기존 자원과 실패 증거를 보존했다.

## 구매자 최종 견적·동의 중간 검토 — buyer-finance-review1 (2026-11-01)

**현재 P09-D buyer-finance 범위에서 새 필수 수정 지적은 발견하지 않았다.** 이는 검수 후 견적과 현재 구매자 동의에 따른 ITEM 예약·binding까지의 한정 판정이다. R-D01의 ADDRESSED 판정은 유지하며 D 전체 완료·전체 하네스 통과를 승인하지 않는다.

검토 대상은 [불변 고정본](../../.local-tools/p09-d-execution-20261101/buyer-finance-review1/)뿐이다. 기준 HEAD `80ad8cd`, 입력582의 SHA256은 `4A3420B620839406D89AF4609642210CA8455BFB2F2C19294860746C7018FEB3`이다. 입력582와 artifact587을 실제 파일 해시로 다시 대조하여 각각 불일치0을 확인했다. 이 입력 파일은 `consent-boundary-focused1` 입력과 바이트 해시가 같다. 이후 live worker 구현은 이번 판정에 포함하지 않았다.

### 계약·구현 확인

| 경계 | 고정본에서 확인한 근거 |
| --- | --- |
| C 계약 보존 | `ReturnFeeQuoting`은 기존 ClaimRules의 원배송별 누적최대/차액 산식과 저장 정책·원단위 net을 사용한다. OPEN fingerprint의 기존 문자열과 policy family를 보존한다. `JdbcReturnFees.currentSelection`의 OPEN은 기존 `quote.orderRevision+1`, INSPECTED_RETURN은 현재 order revision 자체와 return revision/inspection fingerprint를 대조한다. D context가 있는 assessment는 기존 C withdraw로 해제할 수 없다. |
| 실제 검수 기반 견적 | `InspectedReturnQuotes.lock`은 현재 소유자→accepted application→claim/RETURN intent와 정확한 원단위를 잠그고 AWAITING_CONSENT·expectedRevision을 확인한다. 전 단위 RECEIVED/실제 inspection 및 SALEABLE 입고 근거를 요구하고 책임은 검수 사실에서 가져온다. 별도 typed quote/accept 진입점과 INSPECTED_RETURN context로 OPEN 우회를 막는다. 견적은 금융 예약·hold 변경·재입고를 수행하지 않는다. |
| 현재 동의·원자성 | `JdbcReturnFinances.consent`의 한 transaction에서 현재 소유권·동일키/내용·검수 revision을 확인한 뒤 fee accept→실제 원결제 ITEM reserve(sequence1, consent cause, 정확한 refund/quote hash)→bind→request/history/command/consent receipt→Outbox를 기록하고 현재 소유권을 다시 검사한다. 최초 결과를 현재 권한 아래 재생하며 새 key 또는 다른 내용으로 중복 효과를 만들지 않는다. |
| HTTP 경계 | Controller는 엄격한 필드 수/타입과 현재 Principal/session/version/GAS를 사용한다. 금액·책임·worker override를 받지 않는다. AuthConfiguration의 permitAll은 업무 소유권 확인을 대체하지 않으며 해당 POST는 CSRF 예외가 아니다. |
| V64 검수 graph | 불변 purpose/context와 deferred guard는 실제 application/order/seller·AWAITING_CONSENT history, 정확한 중복 없는 검수 원단위, collection/inspection/책임/restitution 및 원 배송·금액·저장 정책을 연결한다. OPEN에 inspection context를 붙이거나 INSPECTED_RETURN의 context를 생략할 수 없다. |
| V65 동의 graph | 불변 consent receipt는 실제 quote context·같은 transaction의 assessment·fee consent·binding history·ITEM attempt·ACTIVE RETURN hold·동일 revision command/history 및 응답을 결합한다. REFUND_PENDING에는 실제 동의가 필요하고 이전 물리 상태에서 금융 예약은 거부한다. 이는 예약 단계 graph이며 후속 상태/재시도·완료 구현에 대한 승인으로 확대하지 않는다. |

### 직접 확인한 실행 증거와 assertion

[최신 집중 실행](../../.local-tools/p09-d-consent-boundary-focused1-20261101/result.json)은 17:32:04.7283838 KST 종료, DB XML6·unit/Arch XML198의 failures/errors/skipped 각각0이다. **root status는 failed, preservation은 passed**다. 필수 미선택 실행이므로 전체 통과가 아니다. DB6은 아래 네 경계 검사와 정상 구매자 동의·기존 C accept/bind 회귀를 포함한다.

| 검사 | 실제 기대 결과·확인한 증거 |
| --- | --- |
| `returnFinalConsentRejectsStaleTermsNonOwnersAndTrustedOverrides` | 판매자/타 구매자/익명404, CSRF403, trusted 추가 필드·false 동의400, revision/hash/legacy 불일치409, 다른 주문 quote404 및 전체 participant 불변. 정상 동의 재생 효과0과 C withdraw 우회409도 확인한다. |
| `returnFinalConsentRollsBackAllFinancialAndHistorySqlStages` | 실제 fee/payment/binding/request/history/command/consent/job/outbox18개 SQL 지점에 trigger 실패를 주입하여 각각 정확한 checkpoint·SQL23514·전체 participant rollback을 assert한다. [실행 JSON](../../.local-tools/p09-d-consent-boundary-focused1-20261101/db/return-consent-sql-rollback.json)은 allReached/allParticipantsRolledBack=true, resume1/replayEffects0이다. |
| `returnFinalQuoteAndConsentRecheckOwnerAfterActualFeeBucketWait` | 별도 연결의 실제 fee bucket FOR UPDATE 대기를 확인하고 저장된 회원 세션 deadline+1µs로 제어 Clock을 이동한다. [견적](../../.local-tools/p09-d-consent-boundary-focused1-20261101/db/return-final-fee-auth-false.json)·[동의](../../.local-tools/p09-d-consent-boundary-focused1-20261101/db/return-final-fee-auth-true.json) 모두404·전체불변이며 정상 Clock 대조가 성공한다. 자연시간 경과나 모든 GAS 경합을 입증한 검사는 아니다. |
| `returnFinalConsentRequiresActualReceiptAndCannotBeMutated` | 실제 coordinator에서 consent INSERT만 저장하지 않는 trigger로 commit을 시도하고 정확한 `RETURN_FINANCIAL_CONSENT_REQUIRED`/23514 및 전체 rollback을 assert한다. 정상 영수증 UPDATE/DELETE도 거부되며 같은 key 재생 시 효과0이다. |

정상 동의 검사는 SELLER 책임의 회수비0·원금전액, 실제 ITEM RESERVED/fee BOUND·binding1, hold ACTIVE·주문 PAID·재고 불변과 같은 응답 재생을 확인한다. 별도 견적 검사는 BUYER 책임의 저장 정책 회수비·차감/추가미수 산식 및 fee/payment 예약0을 확인한다. 총 검사 수만으로 제약 충족을 판정하지 않았다.

이전 `financial-red1`의 quote404 두 실패와 `quote-green1`의 DB4 중 동의404 실패1은 기능 부재 RED로 유지한다. 직접 읽은 quote-green1 XML은 failures1/errors0/skipped0, consent-green1은 DB4 failures/errors/skipped0이다. 이들 root도 failed/preservation passed이며 최신 성공으로 과거 결과를 덮지 않는다. compile26은 UTF8 작성 스크립트 실패 뒤 이전 소스의 컴파일 성공이고 compile27이 새 검사 소스의 컴파일이라는 작업 기록의 구분을 유지한다. 최초 preservation=null 메타데이터와 별도 confirmed 기록도 보존한다.

### 한계와 인계

금융 결과 적용·UNKNOWN/FAILED 복구·§12의 SUPPORT/lease worker·SHIPPING·미수납/쿠폰/반품 및 부모 종결·GAS/legacy 후속 경계·UI와 선택없는 개발/지정 독립 전체는 이번 범위 밖의 미완료 항목이다. 이 명시된 후속 범위를 현재 예약 구현의 결함으로 중복 보고하지 않는다. 실제 Toss·실물 검수·운영 부하도 입증되지 않았다.

이번에는 이 리뷰 문서만 추가했다. 기존 물리 검토를 변경하지 않았고 새 검사/하네스 실행·코드/migration 수정·커밋·푸시·배포는 하지 않았다. 기존 자원·불변 고정본·실패 원문은 보존했다.

## Recorded·금융 worker 중간 검토 — worker-review1 (2026-11-01)

**현재 P09-D recorded+financial worker 고정 범위의 새 필수 수정 지적은 없다.** §12의 저장 접수 조회·동의된 금융 결과 적용·필수 최종 lease 계약과 현재 구현을 대조했다. 기존 R-D01 ADDRESSED와 buyer-finance 판정을 유지한다. §12 전체나 D 전체의 완료 판정은 아니다.

대상은 [worker-review1 불변 소스](../../.local-tools/p09-d-execution-20261101/worker-review1/source/)다. 입력585 SHA256 `5F0CA12AB8BEB6A2AF8117100EF209F585E5389851CFA2E2F0F53C54CB604605`, artifact590의 실제 해시를 대조해 각각 불일치0을 확인했다. 최신 `worker-boundary-green2` 입력 SHA도 같다. live SUPPORT 변경은 읽거나 평가하지 않았다.

### §12 계약과 코드 판단

| 경계 | 확인·판단 |
| --- | --- |
| order 소유권 | `lockRecorded(UUID)`는 transaction을 요구하고 부모 order→정렬 원단위→실제 hold를 잠근다. application/source/order/seller/checkout/mode 및 원 배송·net·정책을 자기 소유 자료로 대조한다. 접수 당시 시각과 저장 window를 검증하며 현재 시각으로 신규7일을 다시 적용하지 않는다. Authority/권한 boolean이나 공개 HTTP 경로가 없다. |
| 현재 hold와 과거 기록 | ACTIVE는 해당 hold의 HELD, REFUNDED는 원cause/해제된 active_hold/저장 outcome, RELEASED는 REJECTED/WITHDRAWN outcome을 요구한다. 해제 후 새 hold가 있는 원단위를 OPEN으로 강제하지 않으며 읽기 자체가 release/refund를 재적용하지 않는다. 실제 REFUNDED 양성은 아직 후속 검사다. |
| 저장 job·동의 귀속 | `ReturnFinancialWorker`는 null/token 누락을 거부하고 Jobs.find의 RUNNING/token/type/dedupe/cause/payload와 canonical route를 대조한다. 부모/claim/intent 잠금 뒤 실제 consent, 정확한 원단위·hold/cause/order/seller, assessment와 현재 binding을 재검증한다. 사용자 세션을 만들어 worker 권한을 대신하지 않는다. |
| 실제 결과 | Refunds.get의 현재 attempt/version/state/amount/ITEM/원 reference와 FinancialIntents.results의 실제 보존 결과·assessment·result reference/time을 맞춘다. payload나 C ACK만으로 금융 성공을 인정하지 않는다. 현재 binding 또는 실제 result version과 다른 오래된 route는409로 거부하므로 최신 상태를 되돌리는 적용 경로가 없다. 다양한 stale 전달 조합의 실제 실행 검증까지 완료됐다는 뜻은 아니다. |
| 순서·최종 lease | order/원단위/hold→claim/intent→정렬 fee bucket/assessment→payment를 거쳐 효과·자체 receipt/history/Outbox를 같은 transaction으로 기록한다. D wrapper 내부 C apply의 null/null은 마지막 쓰기 뒤 `storedJob` 재대조와 필수 `requireOwned`로 감싼다. 호출자가 D lease를 생략할 수 없고 재생 경로도 같은 guard를 수행한다. 최종 guard 뒤 새 업무 잠금을 얻지 않는다. |
| 효과·재생 분리 | 최초 처리는 ACTIVE/PAID와 REFUND_PENDING 또는 REVIEW_REQUIRED를 요구한다. SUCCEEDED만 fee apply, FAILED/UNKNOWN은 BOUND 예약을 유지한 REVIEW_REQUIRED다. 동일 operation의 불변 receipt/fingerprint 재생은 추가 효과 없이 lease를 검사한다. RELEASED는 금융 재생도 거부한다. fee 적용은 물리·쿠폰·부모종결 완료가 아니다. |
| 결과 수집 연결 | JdbcFinancialResults는 실제 결과 보존/기존 결과 재생 때 동의가 있는 RETURN에 전용 resume job을 같은 Outbox 거래로 추가한다. 미동의 RETURN에는 D job을 만들지 않는다. C 결과 보존 경로 자체를 D 완료로 바꾸지 않는다. |

V66의 불변 결과 receipt→현재 binding·실제 payment/C 보존 결과·동일 history/command·저장 RUNNING job 및 fee 효과 연결과, V67의 업무 상태/assessment/binding→operation receipt 역방향 제약을 확인했다. 최초 동의 BOUND 단계는 허용하되 이후 금융 효과·workflow에는 현재 revision의 실제 receipt가 필요하다. SQL graph의 job 식별 검사는 Java의 최종 시간·소유 lease 검사를 대체하지 않는다. 적용된 V66을 고치지 않고 신규 V67로 보강한 이력도 유지한다.

### 실행 근거·실패 이력

아래 원 root result와 DB XML을 직접 읽었다. **전부 root failed / preservation passed**이며 선택 필수 미수집 실행이다. 새 전체 검증 통과로 표시하지 않는다.

| `.local-tools/p09-d-<실행>-20261101` | DB 실제 결과·해석 |
| --- | --- |
| recorded-red1 |1실패: typed API 부재 assertion. 이후 ACTIVE/RELEASED 본문 미도달 |
| recorded-green1 |3통과: transaction·저장 ACTIVE/RELEASED·order JDBC 및 정상 동의 |
| financial-worker-red1 |1실패: 실제 SUCCEEDED/C 결과 보존 후 D job 기대1/실제0. FAILED/UNKNOWN 미도달 |
| financial-worker-green1 |5통과: 실제 세 결과 처리·재생 및 recorded/C 회귀 |
| worker-boundary-red1 |3 중2통과/1실패: SUCCEEDED 결과 receipt 누락에도 실제 commit된 제품 결함 RED |
| worker-boundary-green1 |4 중3통과/1실패: SUCCEEDED 거부 보강 성공 후 FAILED의 실제 기존 guard와 검사 기대가 불일치. UNKNOWN 당시 미도달 |
| worker-boundary-green2 |4통과, failures/errors/skipped0. unit/Arch XML 합계198도 모두0. 18:04:12.9627577 KST 종료 |

최신 입력과 일치하는 고정 테스트의 assertion을 확인했다.

- `recordedReturnPortKeepsActiveAndReleasedOrderEvidence`: transaction 밖 TRANSACTION_REQUIRED, 실제 ACTIVE 및 철회 후 동일 원단위 새 hold가 생긴 상태의 옛 RELEASED/WITHDRAWN 조회, 전체 participant 불변을 검사한다. [JDBC 원문](../../.local-tools/p09-d-recorded-green1-20261101/db/recorded-return-order-jdbc.json)은 adapter의 직접 SQL만 수집하며 claim/payment relation이 없다. 협력 포트·DB trigger를 포함한 서버 전체 SQL 수집으로 확대하지 않는다.
- `returnFinancialWorkerAppliesOnlyRecordedConsentAndActualResultsOnce`: 실제 검수·동의·provider 모의 세 결과와 C 보존 결과 뒤 claim된 전용 job을 처리한다. SUCCEEDED fee APPLIED1, FAILED/UNKNOWN BOUND·REVIEW_REQUIRED, hold ACTIVE·부모 PAID·재고/쿠폰 불변, commit-before-ACK 동일 실제 job 재생 효과0을 확인한다.
- `returnFinancialEffectCannotCommitWithoutItsOutcomeReceipt`: 실제 worker에서 결과 INSERT만 누락시킨다. [SUCCEEDED JSON](../../.local-tools/p09-d-worker-boundary-green2-20261101/db/return-financial-missing-receipt-SUCCEEDED.json)은 `RETURN_FINANCIAL_OPERATION_RECEIPT_REQUIRED`; FAILED/UNKNOWN 원 JSON은 `RETURN_FINANCIAL_REVIEW_RESULT_REQUIRED`다. 모두 실제23514·전체불변이며 테스트는 정상 재개와 재생0도 요구한다. 테스트 기대 정정은 이 정확한 guard 차이만 반영했다.
- `returnWorkerRequiresTheActualStoredJobAndNonNullOwnedLease`: null job/token, 없는 id, 틀린 token·type/dedupe/cause/payload, 실제 저장·claim됐지만 동의 없는 접수를 거부하고 participant 불변을 확인한다. ACK 완료된 token 재사용도409다.
- `returnWorkerLosesLeaseAfterActualFeeWaitAndOnlyTakeoverOwnerWrites`: 실제 fee bucket 잠금 대기 뒤 저장 lease deadline+1µs로 제어 Clock을 이동한다. takeover 없는 만료는 정확 JOB_LEASE_LOST, 실제 JdbcJobs.claim takeover는 구 owner409다. job 자체의 합법적 인계 변경을 제외한 업무 participant 전체 rollback, 새/정상 owner fee 적용1·재생0을 assert한다. [만료](../../.local-tools/p09-d-worker-boundary-green2-20261101/db/return-financial-final-lease-false.json)와 [인계](../../.local-tools/p09-d-worker-boundary-green2-20261101/db/return-financial-final-lease-true.json)는 실제 deadline/후시각을 남긴다. 자연시간 부하·실제 프로세스 중단 검사는 아니다.

compile28/33의 준비 오류와 후속 compile29/34/35, 모든 RED·green1 기대 불일치 원문은 작업 기록대로 유지한다. 뒤의 통과로 이전 실패를 소급 수정하지 않는다.

### 남은 범위

REFUNDED 실제 최종완료·종결 후 재생, SUPPORT 현재권한/FAILED 재시도/UNKNOWN query, 추가 SQL 실패·실제 child 강제중단과 영속 재개, SHIPPING·미수납·쿠폰/부모종결·GAS/legacy/UI 및 선택없는 개발/지정 독립 전체는 후속 미완료다. 현재 한정 범위에서 새 설계 변경 판단은 필요하지 않다.

이번 검토는 이 문서만 갱신했다. 고정본·기존 자원·실패 이력을 보존했고 새 검사/하네스·제품 수정·커밋·푸시·배포는 하지 않았다.

## SUPPORT 중간 검토 — support-review1 (2026-11-01)

**현재 P09-D SUPPORT의 ITEM FAILED 재시도·UNKNOWN/PROCESSING 조회 고정 범위에서 새 필수 수정 지적은 없다.** 기존 R-D01 ADDRESSED와 buyer-finance/worker 판정을 유지한다. 직전 worker 절의 SUPPORT 미완료 표기는 당시 이력이며, 이번에 검토한 범위만 아래와 같이 진전됐다. SHIPPING SUPPORT·종결 및 D 전체 검증을 승인하는 판정은 아니다.

대상은 [support-review1 불변 소스](../../.local-tools/p09-d-execution-20261101/support-review1/source/)다. 입력589 SHA256 `B19ADB452E81F1760FA6DF26711CD49FB7FAC3097F3164A2CE9746849BE24053`, artifact594를 실제 파일 해시로 대조해 각각 불일치0을 확인했다. 최신 `support-boundary-focused2` 입력 SHA도 같다. V68 SHA256은 `EE8F4099D11D8F3FC75CA91E36EEF8233F7C5005F3A041B7C70998A2E3049690`이다. live SQL/child/SHIPPING 변경은 이번 판정 대상이 아니다.

### 현재 권한·원 의무·거래 검토

| 경계 | 구현 확인과 판단 |
| --- | --- |
| 현재 SUPPORT | `CurrentSupport.require`는 실제 회원 세션/version의 lockReadable과 현재 SUPPORT 권한을 검사하며 GAS/guestToken을 허용하지 않는다. JdbcReturnSupport는 transaction 시작 및 최종 업무 쓰기 뒤 이를 호출한다. Controller는 현재 Principal/session/version만 넘기며 key/reason/expectedRevision 세 필드를 엄격히 받는다. 금액·sequence·buyerAuthority·worker·완료 override가 없다. |
| 저장 접수·귀속 | 현재 SUPPORT→부모 order→typed lockRecorded→claim/intent→정렬 fee bucket/assessment→payment 순서다. application/order/seller/hold/cause/RETURN/정확한 원단위와 최초 실제 consent·quote/assessment/current binding·원 refund amount/reference를 대조한다. 새 작업은 ACTIVE/PAID·현재 revision·REFUND_PENDING 또는 REVIEW_REQUIRED와 BOUND를 요구한다. 구매자 Authority를 만들어 호출하지 않는다. |
| FAILED 재시도 | 실제 현재 FAILED만 기존 `ReturnFees.retryFailed`에 전달한다. 원 assessment·동의·quote·금액을 유지하며 실제 common fee 지원 명령과 새 payment sequence/current binding을 함께 저장한다. 과거 FAILED와 최초 consent의 attempt를 덮지 않는다. 기존 C fee retry 계약도 바꾸지 않았다. |
| UNKNOWN 조회 | UNKNOWN/PROCESSING만 실제 `Refunds.requestQuery`에 동일 attempt로 요청한다. 새 attempt·금액 예약·hold 해제·미수납 처분을 만들지 않는다. 상태 REVIEW_REQUIRED와 지원 명령/history/Outbox는 실제 query cause에 연결한다. PROCESSING 분기는 정적으로 확인했으며 최신 집중 검사가 그 상태를 별도로 실행했다고 주장하지 않는다. |
| 원자성·재생 | primitive 호출과 request/history/command/지원 receipt/Outbox를 같은 transaction으로 저장한다. 같은 key는 actor/동작/purpose/revision/reason을 포함한 fingerprint를 검사하고 현재 SUPPORT 아래 최초 receipt를 반환한다. 새 성공 뒤 과거 지원 key는 재생 가능하지만 새 retry key는 현재 상태에 따라409다. 재생 경로도 최종 SUPPORT를 재확인한다. |
| 결과 순서 | worker의 current binding/result version 검사와 지원 명령의 최초 consent 보존이 결합된다. 새 성공 뒤 옛 실제 owned FAILED/UNKNOWN job은409·추가효과0이다. 지원 receipt 재생은 현재 workflow를 과거 REFUND_PENDING/REVIEW_REQUIRED로 되돌리지 않는다. |

V68은 지원 receipt의 불변성·명령키/revision 고유성, 실제 fee retry 또는 payment query primitive와의 FK/정확한 actor·사유·원/새 attempt·같은 금액/payment/reference·sequence 결합을 검사한다. 반대쪽 primitive trigger는 실제 D consent에 연결된 common fee 지원/query만 지원 receipt를 요구하여 기존 C 경로를 무조건 D로 바꾸지 않는다. V66 consent와 V67 operation guard는 최초 동의 BOUND, 현재 실제 금융 결과, 현재 지원 receipt를 구별하도록 신규 migration에서 확장된다. **이 DB proof 판단은 SQL 독해·정상 공개 거래의 실행에 근거하며, 지원 전 SQL 지점/receipt 누락 공격이 이미 실행됐다는 뜻은 아니다.** 추가 실패 주입은 후속 범위다.

### 직접 읽은 실행 결과와 assertion

각 `.local-tools/p09-d-<실행>-20261101`의 원 root result와 DB XML을 확인했다. 모두 **root failed / preservation passed**이며 필수 미선택 집중 실행이다.

| 실행·종료 KST | DB 결과·해석 |
| --- | --- |
| support-red1,18:09:49.7876847 |2실패/errors0/skipped0. 실제 FAILED/UNKNOWN 및 별도 SUPPORT 준비 뒤 HTTP202 기대/404 기능 부재 RED |
| support-green1,18:17:42.6730484 |4통과/failures·errors·skipped0. D 지원2·worker 및 기존 C fee FAILED retry 회귀 |
| support-boundary-focused1,18:25:35.7411261 |5 중3통과/errors2/failures0/skipped0. 잘못된 CATALOG 권한 CHECK, 결과 순서 fixture의 INVALID_SIGNUP 준비 오류 |
| support-boundary-focused2,18:30:49.1535303 |5통과/failures·errors·skipped0. unit/Arch XML 합계198도 각각0 |

CATALOG→CONTENT와 긴 fixture key 단축 후 최신 입력에서 재실행한 결과다. focused1의 UNKNOWN/HTTP 미도달 부분을 통과로 소급하지 않는다. 해당 실패 DB·원로그·manifest는 보존한다.

- `returnSupportRetriesOnlyDefinitiveFailedInspectedRefundWithCurrentAuthority`: 별도 실제 SUPPORT HTTP202, FAILED 원 attempt 보존·동일 원금 sequence2 RESERVED/current binding·예산, 재고/쿠폰 불변을 검증한다. 같은 key 다른 reason409, 새 성공 후 원 receipt 동일·효과0, 새 key retry409를 확인한다.
- `returnSupportQueriesUnknownSameAttemptWithoutReleasingReservations`: HTTP202·동일 attempt/query cause1, payment budget/fee bucket/hold 불변·attempt1, 동일 key 재생0 및 UNKNOWN 새 retry409를 확인한다.
- `returnSupportHttpRejectsOrdinaryAndGuestAuthoritiesAndTrustedFlags`: 실제 일반 구매자·판매자·CONTENT403, GAS/익명401, CSRF403, 추가 trusted 필드/필수 필드 누락/빈 reason400, stale revision409와 전체 participant 불변이다. 구매자 세션을 실제 만료시켜 조회404를 확인한 뒤 별도 현재 SUPPORT202가 성공해 buyer impersonation이 아님을 검증한다.
- `returnSupportRechecksSessionAndPermissionAfterActualFinalPaymentWait`: retry/query × 만료/권한회수 네 경우다. 별도 연결의 실제 payment_refund_budget 잠금 대기를 확인한 뒤 저장 세션 deadline+1µs 제어 Clock은401, 실제 SUPPORT permission DELETE는403·전체 업무 participant 불변이다. [네 원 JSON](../../.local-tools/p09-d-support-boundary-focused2-20261101/db/)의 actualWait/status/allParticipantsUnchanged를 직접 확인했다. 권한회수 경우를 시간 만료로 혼동하지 않는다. 복구 후 정상 명령 revision6·동일키 재생0 대조도 있다.
- `returnOldOwnedResultsCannotReverseSupportedRecoveryInEitherArrivalOrder`: FAILED 및 LOST_RESPONSE의 옛 결과 선처리 유무 네 순서를 실행한다. 실제 이전 owned job을 유지한 채 SUPPORT 복구와 새 성공 뒤 옛 job409·전체불변, 옛 지원 receipt 재생0·최초 consent JSON 완전동일을 확인한다. 원 JSON 네 건은 FAILED sequence2/resultVersion2, LOST_RESPONSE 동일 sequence1/resultVersion4·old UNKNOWN version2·providerEffects1이다. LOST_RESPONSE는 실제 모의 provider 성공 후 응답 유실이며 submit1/query1 assertion을 포함한다. 단순 UNKNOWN fixture를 성공으로 바꾼 검사와 구별한다.

현재 범위에서 중요 설계 변경은 필요하지 않다. 자연시간 만료/운영 부하, 모든 권한 회수 경합, 지원 SQL 전 지점·실제 child 강제중단/영속 재개, §13 SHIPPING 및 그 SUPPORT·최종완료/쿠폰/부모종결·UI·선택없는 개발/지정 독립 전체는 후속 미완료다. 이를 현재 한정 구현의 새 결함으로 중복 열거하지 않는다.

이번 검토는 이 리뷰 문서만 갱신했다. 제품·검사·하네스를 실행하거나 수정하지 않았고 커밋·푸시·배포도 하지 않았다. 불변 고정본·기존 자원·실패 이력을 보존했다.

## 최초 배송비 예약 중간 검토 — shipping-reservation-review1 (2026-11-01)

**현재 P09-D ready·배송비 결정/예약 수직에서 새 필수 수정 지적은 발견하지 않았다. 후속 구현 진행 가능이다.** §13/13.6 전체 구현이나 SHIPPING 정산·D 전체 완료를 승인하는 판정은 아니다. 기존 R-D01 ADDRESSED 및 앞선 한정 판정은 유지한다.

대상은 [shipping-reservation-review1 불변 소스](../../.local-tools/p09-d-execution-20261101/shipping-reservation-review1/source/)다. HEAD `80ad8cd`, 입력594 SHA256 `AD6770A05576CCC2AB39ECE03DE0D1564F488F43AAAF07323AA20A5479DB6FE1`, artifact599를 실제 파일 해시로 대조해 각각 불일치0을 확인했다. `shipping-reserve-focused3` 입력 SHA도 같다. 적용 V69 SHA256은 `DDFDFE7E64318018A3D14DD6E40429D93CBC5780F31BA0766CA02FD250AF670D`다. 동결 해제 뒤 live 후속 구현은 평가하지 않았다.

### 현재 예약 경계의 판단

| 경계 | 확인한 구현·계약 |
| --- | --- |
| 순수 정책 | AftercareShippingRules는 원 seller의 canonical 전체 입력을 전제로 전량 CANCEL/SELLER_READY만 원 S를 반환한다. OPEN/UNRESOLVED 하나라도 있으면 PENDING, 전체 확정 사실에 BUYER 또는 CONFIRMED가 있으면 RETAINED다. 빈/중복/잘못된 ordinal·근거 없는 최종 단위를 거부한다. 이 함수 자체가 DB 전체 집합을 증명하지 않으며 아래 order 조회가 그 책임을 갖는다. |
| order 소유 조회 | lockAftercare는 transaction→부모 order→정렬 원단위 lifecycle 잠금으로 원 net/S·quote total/fingerprint·mode, 현재 hold·발송·confirmation·취소정산 식별을 읽는다. 원 paymentId는 자기 취소정산 자료만 제공하고 claim/payment 직접 SQL은 없다. RETURN completionId는 현재 null이며 실제 완료 projection은 후속이다. |
| 실제 ITEM ready | ReturnItemReady는 불변 D 금융 SUCCEEDED result와 실제 fee application/APPLIED·additional_due0 뒤에 기록한다. 실제 return-unit과 inspection에서 책임/collection을 가져오고 사실별 seller job을 같은 거래에 저장한다. V69는 실제 ITEM 성공/current binding·fee 적용·정확한 원단위 집합·RECEIVED 검수 책임·공유 job을 연결한다. ready가 hold 완료·재입고를 추가하지 않는다. |
| 저장 job·귀속 | SellerAftercareWorker는 실제 Jobs.find의 RUNNING/token/type/dedupe/cause/payload와 RETURN_READY 출처의 order/seller를 대조한다. order가 잠근 전체 단위와 claim ready를 결합하며 현재 HELD의 동일 hold·발송 사실을 요구한다. 금액/책임을 job payload에서 받지 않는다. |
| 결정·예약 원자성 | 부모 잠금 아래 입력 fingerprint/revision 결정과 current pointer, 단일 seller obligation·실제 SHIPPING reserve·typed binding을 기록하고 마지막에 저장 job 재대조/필수 lease를 호출한다. 기존 의무가 있으면 원 order/S·실제 SHIPPING attempt 귀속을 확인해 재예약하지 않는다. 현재 예약 경로는 provider를 직접 호출하지 않는다. |
| 원 S와 C 호환 | 원 S는 order 공개 snapshot, 원 payment/총액/mode는 Refunds.original로 대조한다. 공통 obligation의 seller 고유성과 C의 deterministic attempt/cause 식별을 보존한다. V69는 CANCEL claim FK를 공통 intent FK로 확장하며 기존 C 행을 복제하지 않는다. C/B 마지막 사실 소비는 아직 별도 후속이다. |
| typed SHIPPING | bindReturnShipping은 RETURN·SHIPPING·동일 intent/order/seller/원 obligation/ready/금액을 검증하고 별도 typed 행을 저장한다. V69의 공통 binding→typed binding→실제 payment/원 seller S/REFUND_REQUIRED 연결을 확인했다. 기존 bindReturnRefund의 ITEM·assessment 필수 조건은 그대로이며 SHIPPING을 fee ITEM bind로 전달하는 실제 음성 검사는409다. |

현재 ready/예약 쓰기는 같은 transaction과 최종 lease 아래 있으나, 새 decision 입력 전체/current projection의 양방향 공격·모든 예약 SQL 실패·lease 경합은 아직 실행 증거가 없다. 따라서 SQL 구조 독해와 정상 예약 성공을 해당 공격 검증으로 확대하지 않는다. REFUNDED 완료 경로·단일 의무의 결과 처리/재시도·0원 정산 전체 역시 이번 예약 성공으로 증명되지 않는다.

### 예약 및 order 조회의 실제 증거

아래 원 root result·DB XML을 직접 읽었다. 모두 **root failed / preservation passed**다. 선택 실행이므로 전체 통과가 아니다.

| 실행 경로 `.local-tools/p09-d-<이름>-20261101` | 실제 결과 |
| --- | --- |
| aftercare-view-green1 |19:08:13.1103865 종료, DB3/unit203 failures/errors/skipped0 |
| shipping-reserve-focused1 |19:30:48.7285543 종료, DB3 errors3/failures0/skipped0. V69 SQL42704로 준비 실패·업무 미실행 |
| shipping-reserve-focused2 |19:33:31.8215274 종료, DB3 중2통과/실패1·errors0/skipped0. C 전체취소와 D 금융 worker 회귀 통과. 새 예약 assertion 뒤 ITEM bind 음성 호출이 transaction 밖이어서 기대 예외 불일치 |
| shipping-reserve-focused3 |19:36:15.6518512 종료, DB1/unit204 failures/errors/skipped0. 현재 입력과 일치 |

미적용 V69의 자동축약 FK 이름만 정정한 이력과 실패 원문은 보존한다. focused2에서 적용 성공 후 V69는 불변이다. [focused3 읽기 조회](../../.local-tools/p09-d-execution-20261101/shipping-reserve-focused3-schema-readonly.txt)는 history68/69 성공, SHIPPING RESERVED3000, ready unit4를 기록한다. focused2의 마지막 음성 호출은 TransactionTemplate만 추가해 focused3에서 실제409/전체불변을 검증했다. 앞선 실패를 제품 성공으로 소급하지 않는다.

`sellerShippingReservationRequiresSettledPhysicalItemAndPreservesActiveHold`는 실제4단위 수령·SALEABLE/SELLER 검수만으로 ready/의무0, buyer 동의와 provider ITEM 성공·결과보존만으로도 ready0을 assert한다. D workflow fee 적용 뒤에만 ready1/정확4단위·공유 job1을 확인한다. 실제 공유 worker 후 REFUND_REQUIRED·원 S3000 RESERVED·RETURN SHIPPING binding1, provider 자료0·hold ACTIVE·RETURN REFUND_PENDING·부모 PAID이며 ITEM bind 우회409·전체 participant 불변이다. 이 검사는 SHIPPING 제출/성공/완료까지 실행한 검사가 아니다.

`orderAftercareProjectionReadsCanonicalOriginalFactsWithoutClaimOrPaymentSql`의 실제 C 부분취소 정산 fixture와 transaction 밖 거부·없는 seller404·canonical4/원금·S·자기 정산 payment 식별·전체불변 assertion을 확인했다. 순수 규칙6개는 최신 unit204에 포함되며 CONFIRMED_UNITS_REMAIN 이유와 S0 REFUND_REQUIRED amount0을 검사한다. S0의 실제 provider/정산 통합 검사까지 포함된 것은 아니다.

### 선행 worker/SUPPORT SQL·child 증거의 추가 검토

별도 [sql-child-focused1](../../.local-tools/p09-d-sql-child-focused1-20261101/result.json)은18:45:09.142119 종료, DB5/unit198 실패·오류·skip0, root failed/preservation passed다. **이 실행은 입력589의 V68 시점이며 현재594 입력의 새 ready/배송비 변경 전에 수행됐다.** 아래 성과를 새 SHIPPING SQL까지 통과한 것으로 표현하지 않는다.

- worker SUCCEEDED12/FAILED8/UNKNOWN8, 총28 실제 SQL 지점과 SUPPORT retry17/query9, 총26지점의 정확한 checkpoint/23514·전체 participant rollback·정상 재개1·재생0을 확인했다. 원 JSON의 allReached/allParticipantsRolledBack=true와 실제 지점 목록을 읽었다. 마지막 RETURN_RECORDED job/outbox를 별도 조건으로 지정한 실패 주입도 포함한다.
- SUPPORT own receipt 누락은 query `RETURN_SUPPORT_RECEIPT_REQUIRED`, retry `RETURN_FINANCIAL_OPERATION_RECEIPT_REQUIRED`/23514였다. primitive 누락은 각각 실제 query/fee_command FK23503이며 own receipt INSERT에서 조기 거부된다. 이를 deferred commit guard로 잘못 표현하지 않는다. 네 원 JSON 모두 전체불변이다.
- PREPARED child는 실제 marker 뒤 생성한 프로세스를 강제 종료하고 저장 lease 뒤 새 child가 같은 attempt를 query-only로 재개했다. actual-result는 attempts2/UNKNOWN/providerSubmissions0/budgetAndHoldRetained=true이며 테스트는 새 시도0·fee BOUND·hold ACTIVE·D REVIEW_REQUIRED를 확인한다.
- commit-before-ACK child는 실제 D fee/result/history commit 뒤 강제 종료하고 저장 lease 뒤 새 child가 같은 job을 claim/재생/ACK했다. actual-result는 attempts2/financialEffects1/providerEffects1/additionalBusinessEffects0/physicalCompletion=false다. 자연시간 추정·lease guard 모의 오류가 아니라 실제 별도 프로세스와 저장 lease를 이용하되, 실운영 부하 검사로 확대하지 않는다.

### 인계·후속 필수 검증

현재 구현 진행을 막는 새 지적은 없다. 이후 §13/13.6에 따라 SHIPPING 실제 결과/UNKNOWN/FAILED·현재 SUPPORT, 완료 projection/양방향 graph 공격, C-last/D-last와 B direct/auto fan-out, ready·결정·예약의 새 SQL/lease/child/동시 마지막 사실 경합, 0원/provider0·BUYER/혼합/미수납·쿠폰·부모종결/late APPROVED, 영속 재평가·UI 및 선택없는 개발/지정 독립 전체가 필요하다. 이미 구현한 예약 사실을 완료된 반품이나 전체 D 승인으로 표시하지 않는다.

이번에는 이 리뷰 문서만 갱신했다. 새 검사/하네스 실행·제품/검사 수정·커밋·푸시·배포는 하지 않았으며 기존 자원·고정본·실패 이력을 보존했다.

## SHIPPING 결과·완료·마지막 C/B 사실 검토 — completion-last-facts-review1 (2026-11-01)

**판정: 필수 수정 R-D02(P2) 1건. 현재 수직은 수정 후 재검토가 필요하다.** 독립적인 후속 개발은 계속할 수 있으나 현재 범위 무지적 승인 또는 D 전체 승인으로 표시하지 않는다. 뒤에 명시한 미실행 검사는 현재 결함과 구분한다.

대상은 [completion-last-facts-review1 고정본](../../.local-tools/p09-d-execution-20261101/completion-last-facts-review1/source/)뿐이다. HEAD `80ad8cd`, 입력599 SHA256 `2D5A5DAA1A459268D955FD7CBC29821DDFBCE5B4C01B020503E928CEE279DEAF`, artifact604의 실제 해시 불일치 각각0이며 최신 last-facts-green2 입력 SHA와 같다. 이전 예약본 대비 신규5/수정9의 변경 목록도 대조했다. V70 `3338AAA23371BB7A17741E39847F0D265C6300355813560E2CB3342C8B00E246`, V71 `F7B94E7BBE224C1C6A7DC1DCABE877395ECAADA6DD3A3B144B7531D02B8F0888`을 확인했다. live 후속 구현은 평가하지 않았다.

### R-D02 — P2 / OPEN: claim coordinator가 order 배송비 정산 테이블을 직접 읽음

- 고정본 `claim/infrastructure/ReturnShippingResults.java:52`는 SUCCEEDED 최초 적용/재생 확인에서 `marketplace.order_aftercare_shipping`을 직접 SELECT한다.
- 고정본 `claim/infrastructure/SellerAftercareWorker.java:110`도 REFUND_REQUIRED의 쿠폰 복구 여부를 같은 order 테이블의 직접 SELECT로 판단한다.
- 두 조회 모두 §11/§13.2의 “order는 자기 정산 projection을 공개 포트로 제공하고 claim은 order 내부 SQL을 추가하지 않는다” 계약을 위반한다. 이미 order가 `recordShippingSettlement`와 `lockAftercare`를 소유하는데 확인 조회만 claim에 남아 저장 구조와 판단 책임이 분산됐다. DB migration의 교차 모듈 FK/graph와는 다른 **Java adapter의 직접 저장소 의존**이다. 기존 order→claim 경계를 검사한 테스트나 ArchUnit import만으로 이 반대 방향 SQL 위반은 잡히지 않는다.

**실제 도달 경로:** `sellerReturnReadySchedulesAndSettlesOriginalShippingExactlyOnce`의 실제4단위 SELLER 반품→ITEM 성공→S 예약/제출/성공 후 `REFUND_RESULT_RECORDED.v1` 처리에서 첫 SELECT에 도달한다. 이어 `SELLER_AFTERCARE_REEVALUATE.v1`의 전체 REFUNDED 처리에서 두번째 SELECT가 쿠폰 목록 조회보다 먼저 실행된다. 따라서 쿠폰 없는 fixture에서도 발생하며, 아직 미구현인 후속 경로가 아니다. 이번에는 정적 검토만 했으므로 새 JDBC 녹화 실행을 했다고 주장하지 않는다.

**필수 수정:** order 공개 포트/AftercareView에 같은 transaction의 typed 배송비 정산 receipt 조회·대조를 추가해 order/seller/payment/attempt/원 S/reference 귀속을 반환하거나 검증하게 한다. claim은 자기 obligation/result와 그 공개 결과를 대조한다. 두 SQL을 다른 claim helper로 옮기거나 저장 여부 boolean을 호출자가 만들어 전달하는 방식은 해결이 아니다. 적용 migration을 수정할 필요는 없다.

**검증 요구:** 실제 SHIPPING 성공 최초 적용과 ACK 전 동일 결과 재생, seller 완료/쿠폰 복구 경로에 transaction-aware JDBC recorder를 주입하여 해당 claim adapter가 직접 실행한 order/payment/promotion/inventory relation SQL0을 assert한다(공개 협력 포트 내부의 자기 소유 SQL과 구분). 정상 정산·쿠폰1/재생0, 타 seller/attempt/reference 불일치 거부·전체불변을 확인하고 C/D 양순서·B direct/auto 양순서·기존 Cfull 회귀를 유지한다. 새 전체 검증에서 필수 이름을 수집하고 독립 재검토에 소스/증거를 연결한다.

### 나머지 현재 구조·실행 근거

SHIPPING dispatcher는 저장 binding의 목적을 먼저 나눠 ITEM fee worker로 보내지 않는다. ReturnShippingResults는 실제 stored job/current binding/Refunds 결과·원 S/reference/version을 검증하고 SHIPPING receipt·order 정산·재평가 Outbox와 최종 lease를 같은 거래로 처리한다. 위 소유권 위반 외에 검토한 정상 정산 흐름에서 별도 현재 결함은 발견하지 않았다.

SellerAftercareWorker는 ready와 필요한 S 성공 뒤 개별 RETURN의 hold REFUNDED·order 완료 projection·claim 완료/history/command/Outbox를 묶는다. 이미 completion이 있는 RETURN은 pending 목록에서 제외하므로 늦게 발생한 독립 S가 예전 부분 RETURN을 다시 열지 않는다. 원 coupon cause `cancel-coupon:<reservationId>`를 사용하고 CONFIRMED가 남으면 복구하지 않는다. 쿠폰/다중seller 상세 실제 검증은 아직 후속이다.

C는 단일 SellerShippingReservations를 재사용하며 allCanceled가 false여도 실제 ITEM fact의 공유 재평가를 발행한다. B의 no-op handler는 제거되고 같은 type의 claim handler 하나가 actual job→order typed lockConfirmation으로 DIRECT/AUTO 영수증/단위/이력을 확인해 seller·confirmation별 Outbox를 발행한다. B 원 API/Result는 바꾸지 않는다. order.evaluate는 RETURN 혼합에서 current PENDING을 종결하지 않고 최종 RETAINED/필요 S 정산을 요구한다. 순수 B 및 기존 C+B의 동기 분기는 코드상 유지되며 이번 선택 실행이 그 모든 회귀를 포함하지는 않는다.

V70은 완료·order projection·S 결과/정산 graph를 추가했다. V71은 **새 completion INSERT의 RUNNING/token 요구**와 **과거 immutable 완료 proof의 재검증**을 분리한다. 과거 proof에서도 원단위/hold/cause·ready/실제 ITEM/fee·decision·receipt/history/Outbox 귀속은 유지한다. 과거 SUCCEEDED job으로 새 receipt를 만드는 직접 공격은 아직 실행되지 않았으며 후속 필수다.

다음 원 root/DB XML을 직접 읽었다. 모두 **root failed / preservation passed**이며 필수 미선택 실행이다.

| 실행 | 실제 결과·범위 |
| --- | --- |
| shipping-completion-focused1 |19:45:51.6866646 종료, 입력596 DB4 failures/errors/skipped0. 최초 전체 SHIPPING·완료, 기존 Cfull·order SQL·예약 회귀 |
| last-facts-red1 |19:51:49.2421599 종료, DB2 실패2. 실제 C ITEM/B DIRECT fan-out 기대1/실제0. 뒤 AUTO/후속 순서 미도달 |
| last-facts-green1 |19:56:48.3989531 종료, 입력598 DB4 중3통과/1실패. B DIRECT/AUTO×두 순서4·전체 SHIPPING·Cfull 통과, C-first 성공 후 D-first/C-last의 과거 completion 재검증 실패 |
| last-facts-green2 |20:03:39.0588354 종료, 현재 입력599 DB1(C/D 두 순서) failures/errors/skipped0. unit/Arch XML204도 각각0. B 네 경우를 이 실행에 포함했다고 표시하지 않음 |

최초 fullshipping 검사는 실제4단위 회수·검수/SELLER·buyer 동의·ITEM 성공/fee APPLIED→원 S attempt1/provider effect1→RETURN COMPLETED/hold REFUNDED·CLOSED/ALL_RETURNED를 assert한다. 원 APPROVED 재통지 후 전 participant 동일·compensation NONE이다.

`mixedCancellationAndSellerReturnShareShippingInBothLastFactOrders`는 실제 미발송3취소/발송1반품의 C-first와 D-first에서 독립 S3000 성공1·MIXED_AFTERCARE/CLOSED를 확인한다. D-first는 과거 RETURN row와 own hold/ITEM/fee/consent/history/ready/completion/stock 비교 결과가 완전동일하며 원 APPROVED 추가효과0이다. `directAndAutomaticConfirmationFactsCloseMixedReturnsInBothOrders`는 실제 DIRECT 또는 due 이후 AUTO job으로 나머지3단위를 확정하고, B 직후 PAID→fact 소비 뒤 RETAINED/CLOSED·SHIPPING attempt0·CONFIRMED3·직접 동일키 원 Result 재생/원 APPROVED 추가0을 확인한다.

green1 실패의 [읽기 전용 원문](../../.local-tools/p09-d-execution-20261101/last-facts-green1-readonly-completion-guard.txt)은 완료 version6=receipt revision6, 원 job SUCCEEDED/token 해제 및 `ACTUAL_RETURN_COMPLETION_GRAPH_REQUIRED`를 보여 준다. 이는 실제 구현 오류였으며 timeout/fixture 오류로 바꾸지 않는다. 적용 V70을 보존하고 신규 V71 뒤 두 순서를 재검증한 이력을 유지한다.

### 후속 범위와 인계

R-D02를 고친 뒤 현재 수직 재검토가 필요하다. 별도로 SHIPPING 현재 SUPPORT/query/retry·영속 재평가, 새 ready/배송비/완료 SQL rollback·graph 누락/old job 새receipt 공격·lease/child/경합, 다중seller·0원/BUYER/미수납/쿠폰 상세·잔여 D01~08/UI 및 선택없는 개발/지정 독립 전체가 남는다. 이러한 미실행 후속 항목을 추가 현재 결함으로 세지 않는다.

이번에는 이 리뷰 문서만 갱신했다. 제품/검사/하네스 실행·수정·커밋·푸시·배포는 하지 않았으며 기존 자원과 실패·고정본은 보존했다.

## R-D02 보완 재검토 — r02-fix-review1 (2026-11-01)

**판정: R-D02 ADDRESSED. 이번 보완 범위의 새 필수 수정 지적은 없으며 후속 수직 진행 가능이다.** 이는 모듈 소유권 보완과 명시된 회귀의 한정 판정이며 전체 D 승인·독립 전체 통과가 아니다.

대상은 [r02-fix-review1 불변 소스](../../.local-tools/p09-d-execution-20261101/r02-fix-review1/source/)다. HEAD `80ad8cd`, 입력599 SHA256 `3F3E74B4DAE01E0C4C123AF6B187D59BFB73A9F000DF2B8666C6C87BD4F85865`와 artifact604를 실제 파일 해시로 대조해 각각 불일치0을 확인했다. `r02-green1` 입력 SHA도 같다. 이전 완료 고정본과 입력 목록을 대조한 변경은 제품4(AftercareSettlements/JdbcAftercareSettlements/ReturnShippingResults/SellerAftercareWorker), 검사·필수목록2뿐이며 신규/삭제 입력·migration 변경은 없다. live 후속 구현은 평가하지 않았다.

### 수정의 적합성

`AftercareSettlements.lockShippingSettlement(UUID seller)`는 같은 transaction에서 order 공개 lockAftercare의 부모/원단위 잠금을 사용한다. order 내부에서 기존 C shipping과 공통 aftercare shipping을 조회해 모순되는 복수 영수증을 거부하고, order/seller·원 S·필수 식별/reference·자기 정산 paymentId를 대조한 typed Shipping을 반환한다. 호출자가 supplied boolean을 넘기거나 claim helper로 SQL을 숨기는 방식이 아니다.

ReturnShippingResults는 성공 최초 적용/재생에서 typed receipt를 실제 intent/order/seller/payment/attempt/amount/resultReference 전체와 equals 대조한다. SellerAftercareWorker도 자기 obligation의 SUCCEEDED·원 금액/귀속과 실제 Refunds SHIPPING 결과·원 reference, typed receipt, 실제 claim의 RETURN shipping result 또는 CANCEL shipping effect를 대조한 뒤 쿠폰 복구를 판단한다. 기존 두 order 직접 SELECT는 제거됐고 자기 저장소만 읽는다. 최종 현재 job/lease guard와 기존 같은 거래 경계도 유지한다.

### 실제 RED→GREEN과 검사 적합성

원 root result·JUnit XML·JDBC JSON을 직접 읽었다.

| 실행 | 확인한 실제 결과 |
| --- | --- |
| `.local-tools/p09-d-r02-red1-20261101` |20:18:31.6029297 종료. DB1 failure1/errors0/skipped0. [JDBC 원문](../../.local-tools/p09-d-r02-red1-20261101/db/r02-claim-shipping-jdbc.json)41문장 중 문제의 order 직접조회4개가 실제 수집됨 |
| `.local-tools/p09-d-r02-green1-20261101` |20:24:39.3066313 종료. DB4/unit·Arch204 failures/errors/skipped0. [JDBC 원문](../../.local-tools/p09-d-r02-green1-20261101/db/r02-claim-shipping-jdbc.json)61문장 중 직접 order/payment/promotion/inventory relation0 |

두 root 모두 **필수 미선택 failed / preservation passed**다. 전체 통과로 표시하지 않는다. compile49의 미선택 SHIPPING SUPPORT 검사 import 오류는 test-compile 준비 실패이며 DB 업무 RED가 아니다. 해당 원문과 후속 compile50/51·실제 RED를 분리 보존한다.

`claimShippingCoordinatorsUseOnlyClaimOwnedJdbcStorage`는 transaction-aware recorder를 문제의 두 claim adapter에만 주입한다. 실제 coupon과 원 net10000/10000/10001의 fixture에서 SHIPPING 최초 성공/ACK 전 재생, 완료·쿠폰 restored/reusable1, seller 동일 job 재생 추가0을 수행한다. 협력 order/payment/promotion 포트 내부의 정상 자기 SQL이나 DB trigger를 직접 claim SQL로 오인하지 않는다. RED 원문은 두 문제 SELECT가 최초/재생 각각 실행된4건, GREEN 원문은 수정 후 외부 relation0이므로 단순 문자열 검사만으로 해결을 판정한 것이 아니다.

정상 order write 포트에 타 seller·ITEM attempt·다른 실제 reference를 전달하면409·전체불변이다. 추가로 실제 order projection INSERT에 임시 trigger를 붙여 (1) 다른 실제 seller/order/payment, (2) 실제 ITEM attempt, (3) 다른 실제 ITEM provider reference를 대입한다. 세 경우 모두 실제 SHIPPING coordinator의 typed receipt 대조에서409·전체 participant rollback을 assert하며 trigger 제거 뒤 동일 원 작업은 정상 재개한다. 가짜 payment 결과나 참/거짓 포트 응답으로 검사를 대체하지 않았다.

같은 GREEN 입력에서 `mixedCancellationAndSellerReturnShareShippingInBothLastFactOrders`의 C/D 두 순서, `directAndAutomaticConfirmationFactsCloseMixedReturnsInBothOrders`의 B direct/auto 네 순서, 기존 `fullCancellationRefundsOriginalShippingAndClosesOnlyAfterEverySeller`도 통과했다. 따라서 이전 B 네 경우가 다른 입력에서만 관찰됐다는 제한은 이번 동일 입력 재실행 범위에서는 해소됐다. 그 외 모든 B/C 회귀와 독립 전체까지 실행된 것은 아니다.

SHIPPING SUPPORT의 작성된 검사는 이번 선택에서 미실행이며 해결 근거로 사용하지 않는다. 영속 재평가·새 graph/rollback/lease/child·다중seller/0원/BUYER/미수납/쿠폰 상세·잔여 D01~08/UI·선택없는 개발/지정 독립 전체는 계속 후속 미완료다. 이번에는 이 리뷰 문서만 갱신했고 새 검사/하네스·제품 수정·커밋·푸시·배포는 하지 않았다. 적용 migration과 기존 자원·실패 이력·고정본은 보존했다.

## SHIPPING SUPPORT·primary 위험경계 검토 — shipping-support-risk-review1 (2026-11-01)

**판정: 요청된 고정본 범위에서 새 필수 수정 지적 없음. R-D01/R-D02 ADDRESSED 유지, 후속 수직 진행 가능.** SHIPPING 독립 작업과 명시된 현재 권한·과거 job·최종 lease 검토에 한정하며 D 전체 완료·독립 전체 통과 판정은 아니다.

대상은 [불변 소스](../../.local-tools/p09-d-execution-20261101/shipping-support-risk-review1/source/)이며 HEAD `80ad8cd`, 입력601 SHA256 `B9148298A4BE4BCD28194A86FBD7A53B83515D2B8F7C13CCDB436B363C19C1EA`다. 입력601·artifact606을 실제 파일 해시와 대조해 각각 불일치0을 확인했다. V72 SHA256은 `E7E7D771F5F9D1F4569F5080A42A976568A0AE96EC6144A66A5B45513CEBE97F`다. R02 보완 고정본 대비 변경은 ReturnSupport/JdbcReturnSupport/ReturnShippingResults/ReturnSupportController, 신규 ReturnShippingSupport·V72, PostgresJdbcIT·필수목록의8개 입력이며 기존 migration 변경은 없다. live 후속 구현은 이 판정에 포함하지 않는다.

### 구현과 계약 대조

ReturnShippingSupport는 같은 transaction에서 CurrentSupport→부모 주문·판매자 전체 원단위→recorded 원 RETURN/intent/ready·최초 consent→원 S obligation→실제 payment를 잠그고 대조한다. original order/seller/hold/cause·정렬한 단위·원 S·quote reference·현재 SHIPPING attempt 귀속을 확인한다. RELEASED는 거부하되 이미 완료된 원 RETURN의 REFUNDED source는 독립 S 작업에서 사용할 수 있다. 신규 명령과 동일키 재생, GET 모두 마지막 CurrentSupport를 재확인한다. 주문·결제 저장소는 공개 포트를 사용하므로 R-D01/R-D02의 직접 외부 저장소 조회를 다시 도입하지 않았다.

S obligation에 독립 version을 둬 result→SUPPORT→새 result마다 전진한다. 현재 FAILED의 retry만 새 sequence/attempt를 만들고 같은 원 의무·금액을 유지한다. UNKNOWN/PROCESSING query는 같은 attempt를 사용하며 새 환불 승인이 아니다. RETURN workflow/첫 buyer consent/ITEM fee/재고를 갱신하지 않고, 이미 성공한 뒤에는 원 키 receipt 재생만 허용한다. GET은 S revision/state와 실제 payment state/sequence를 별도로 반환한다. 기존 ITEM 분기는 유지된다.

V72는 원 obligation 식별·금액 불변, 독립 revision 증가, support receipt와 실제 retry/query primitive의 양방향 귀속, 현재 operation proof 하나, 새 shipping result와 현재 obligation/payment/job·정산 receipt 결합을 추가한다. 과거 shipping result의 nullable revision은 역사 증거로 남기고 신규 INSERT는 nonnull/current revision을 요구한다. applied V70/V71을 다시 쓰지 않는다. 이는 정적 대조이며 새 SQL graph의 모든 누락·직접변조·child 경합을 실행했다는 뜻은 아니다.

### 실행 증거와 assertion 적합성

아래 원 root result·DB/단위 XML과 primary 위험경계 JSON을 직접 읽었다. 세 실행 모두 **root failed / preservation passed**다. RED는 실제 업무 assertion 실패이고, 두 GREEN은 선택 검사가 통과했지만 필수 미선택 때문에 root가 실패한 실행이다. 검토세션이 새 하네스를 실행한 것은 아니다.

| 실행 | 실제 결과와 소스 구분 |
| --- | --- |
| `.local-tools/p09-d-shipping-support-red1-20261101` |20:29:17.6687020 종료, DB1 failure1/errors0/skipped0, unit·Arch204 각각0. 실제 FAILED 이후 SHIPPING HTTP202 기대/400 관찰. UNKNOWN 경우는 이 실패로 미도달 |
| `.local-tools/p09-d-shipping-support-green1-20261101` |20:44:56.5466349 종료, DB5/unit·Arch204 failures/errors/skipped0. 입력601 SHA `1BE6DAC47E1D5F3A3FF063F115FA0CD2632615A194ADF3E4108F9A826AFA0E3F` |
| `.local-tools/p09-d-primary-risk-focused1-20261101` |21:03:52.4606390 종료, DB3/unit·Arch204 failures/errors/skipped0. 입력601 SHA는 현재 고정본과 동일 |

두 GREEN 사이 입력 차이는 PostgresJdbcIT와 필수목록뿐이며 제품 소스는 동일하다. 따라서 SHIPPING SUPPORT 기본 검사와 primary 위험경계를 각각의 실행으로 연결하며 하나의 동일 전체 실행으로 합산하지 않는다.

`shippingFailedSupportRetriesAndUnknownQueriesPreserveIndependentOriginalObligation`은 실제 제공자 FAILED/LOST_RESPONSE 뒤 원 S3000, PAID/hold ACTIVE/completion0, FAILED의 예산 해제와 UNKNOWN의 예약 유지부터 확인한다. GET revision1→SUPPORT receipt revision2, FAILED 새 attempt sequence2, UNKNOWN 같은 attempt·submit1/query1·추가 retry409, 성공 SHIPPING provider effect1·CLOSED/REFUNDED, 원 키 성공 후 재생 추가0·새 retry409를 assert한다. SUPPORT 직전/직후 원 RETURN/ITEM/fee/첫 consent/재고 사실 비교도 포함한다. 결과 소비의 revision 증가는 구현·V72에서 확인했으며, 마지막 GET revision3의 별도 직접 assertion으로 과장하지 않는다. 같은 GREEN에서 기존 ITEM retry/query 두 검사, R02 실제 JDBC·receipt 귀속 음성 검사, 전체 SELLER 반품 원 S 정산 검사도 실행됐다.

`shippingSupportRechecksCurrentPermissionAndSessionAfterActualBudgetWait`는 retry/query 각각 실제 payment budget 행 대기에 진입한 뒤 저장된 세션 만료+1µs 또는 실제 SUPPORT permission DELETE를 적용한다. 네 경우 401/403 및 전체 participant 불변, 권한/시계 복구 후 revision2 정상 승인·원 키 재생 불변을 확인한다. [네 JSON](../../.local-tools/p09-d-primary-risk-focused1-20261101/db/)에는 실제 저장 만료·관측 시각·응답·불변 결과가 남는다. 시작 시점 권한 실패만으로 마지막 재확인을 대신한 검사가 아니다.

`ackedOldReturnJobCannotAuthorizeAnotherCompletionReceipt`는 같은 seller의 이미 ACK된 첫 RETURN 완료 job/token을 두 번째 실제 RETURN의 새 completion INSERT에 주입한다. [원문](../../.local-tools/p09-d-primary-risk-focused1-20261101/db/old-acked-job-completion-attack.json)과 assertion에서 SQLSTATE23514·정확한 `RETURN_COMPLETION_CURRENT_JOB_REQUIRED`·전체 rollback을 확인했다. 임시 공격 제거 후 현재 job은 정상 완료하고 과거 RETURN의 개인 사실은 그대로다. 앞선 V71의 old ACK job 새 receipt 방어 미실행 항목은 이 범위에서 해소됐다.

`shippingResultAndCompletionLostLeaseAfterActualBusinessWaitRollBackAndTakeOver`는 SHIPPING result의 실제 budget 대기와 seller 완료의 실제 coupon 대기 뒤 저장 lease+1µs를 적용한다. 두 경우 최종 `JOB_LEASE_LOST`·전체 rollback, 실제 Jobs.claim takeover의 attempts2/새 token, 이전 token409·불변, 새 owner 정상 적용/재생·provider effect1을 assert한다. 완료 경우 CLOSED·쿠폰 복구1, 결과 경우 shipping result1도 확인한다. [result JSON](../../.local-tools/p09-d-primary-risk-focused1-20261101/db/shipping-final-lease-false.json)과 [completion JSON](../../.local-tools/p09-d-primary-risk-focused1-20261101/db/shipping-final-lease-true.json)은 저장 기한과 관측 시각을 구분한다. 가짜 job/null lease로 대체하지 않았다.

### 남은 범위

§14 영속 복구·manifest는 이 고정본에서 미구현/미실행이다. 새 SQL graph·child/경합의 나머지, 다중seller·0원/BUYER/미수납·쿠폰 상세, 잔여 D01~08/UI와 선택없는 개발·지정 독립 전체는 후속 미완료로 유지한다. 이를 이번 수직의 새 결함으로 분류하거나 현재 선택 실행으로 충족됐다고 표시하지 않는다. 실패 RED와 이전 실패·적용 migration·고정 증거는 보존했다. 이번 검토는 이 리뷰 문서만 갱신했으며 제품/검사 수정·새 실행·커밋·푸시·배포를 하지 않았다.

## §14 ITEM_EFFECT 복구 기반 검토 — recovery-item-review1 (2026-11-01)

**판정: 이번 ITEM_EFFECT 고정본 범위에서 새 필수 수정 지적 없음. 후속 구현 진행 가능, R-D01/R-D02 ADDRESSED 유지.** 복구 전체 승인·D 전체 완료·독립 전체 검증 통과와 구분한다. 위 절의 recovery 미구현 표기는 그 당시 고정본의 이력이다.

대상은 [recovery-item-review1 불변 소스](../../.local-tools/p09-d-execution-20261101/recovery-item-review1/source/)다. HEAD `80ad8cd`, 입력604 SHA256 `5C8EDE2B67A144D86BD80918355FBE73CB9D06BCC575025A40F851614C44D940`, artifact609를 실제 파일과 대조해 입력/산출물 불일치 각각0을 확인했다. V73 SHA256은 `BBB35A2EF970A6666254A3131966394FEE4C0927FB112E501AF45EEDD1C03691`다. 앞 SHIPPING 위험경계 고정본 대비 ReturnFinancialWorker/ReturnJobs 수정, ReturnRecoveryScanner/ReturnRecoveryDeliveries/V73 신규, PostgresJdbcIT/필수목록 수정의7개 입력만 다르다. 기존 migration은 바뀌지 않았다. 동시 진행 중인 live 후속 stage는 읽거나 평가하지 않았다.

### 현재 구현과 설계의 연결

startup의 실제 ApplicationReadyEvent는 worker 활성화 시 내부 `start()`를 호출한다. run의 지원 목록은 명시적으로 **ITEM_EFFECT 하나**다. caller가 stage/upper/cursor를 지정하는 외부 endpoint는 없다. run·page·candidate·delivery·page 완료는 불변 기록이고 stage의 최초 job/upper도 변경 금지다. 시작 당시 후보 UUID upper는 유한 상한이며 시간 snapshot이라는 보장은 없다. SQL은 UUID 정렬·upper/cursor 조건으로 최대50 locator를 반환한다.

scan은 실제 Jobs의 RUNNING/token/정확한 command를 검증하고 START는 stage.first_job_id, 후속 page는 이전 page 완료의 next_job_id·cursor와 연결한다. page 계획 transaction 뒤 후보별 transaction을 따로 처리하므로 stage cursor 행을 잠근 채 업무 잠금을 기다리지 않는다. 후보는 order 공개 aftercare 잠금→recorded RETURN→intent/claim 행→실제 Refunds 경로로 확인한다. RELEASED, 현재 보존 결과 부재/변경, 이미 기록된 operation과 terminal source의 누락을 서로 다른 기록으로 남긴다. 현재 Java scanner/validator는 claim 저장소를 읽고 order/payment/platform은 공개 포트를 사용한다. FK/deferred SQL의 교차 귀속과 Java의 직접 외부 저장소 접근을 구별했다.

ITEM 후보는 실제 최초 consent·현재 assessment binding·C 공통 보존 결과가 있어야 하며, 현재 Refunds의 payment/order/seller/purpose/금액/version/state/reference/resultAt/원 reference와 일치해야 새 delivery를 발행한다. source fingerprint만으로 금액이나 실제 결과를 대신하지 않는다. candidate receipt·새 Outbox/job·manifest와 최종 scan lease는 같은 후보 transaction에 있고, 모든 locator의 candidate proof 이후 page 완료·cursor·다음 job/Outbox를 한 transaction으로 기록한다. 같은 page 재시도는 기존 계획/candidate를 재사용한다. SQL의 NULLS NOT DISTINCT 고유성은 같은 run의 같은 원 사실 중복 delivery를 제한한다.

ReturnFinancialWorker는 실제 primary key 또는 exact recovery manifest 중 하나만 허용한다. recovery는 job id/type/dedupe/cause/payload/scheduledAt, run 지원 stage, 원 consent·assessment·보존 결과 fingerprint와 금액/귀속을 대조한다. 이어 기존 worker의 recorded source·정렬한 단위·현재 binding/fee·현재 payment·보존 결과 검증을 수행한다. scanner는 새 submit/query/retry나 SUPPORT/구매자 동의를 생성하지 않으며 기존 ITEM 적용 경로만 재전달한다. 현재 receipt 재생과 새 적용 모두 실제 delivery job의 마지막 lease를 확인한다.

V73의 신규 delivery 발행 graph는 현재 scan job/token과 actual Outbox/job·보존 사실을 요구한다. 이미 발행된 manifest의 identity proof는 발행 scan의 영구 RUNNING을 요구하지 않고, 새 ITEM 효과 INSERT는 **현재 delivery** job/token·원 결과와 기존 financial graph를 요구한다. recovery job/Outbox 식별 변경을 막아 과거 primary를 recovery로 retag하는 우회를 차단하는 구조다. 이전 migration/receipt를 다시 쓰거나 primary job을 READY로 되돌리는 구현은 없다. 이 정적 대조를 모든 SQL 누락쓰기·위조·lease 경합의 실행 통과로 표시하지 않는다.

### 실제 RED→GREEN과 범위

원 result.json, DB XML, 단위/Arch XML 및 GREEN maven 원문을 직접 읽었다.

| 실행 | 확인한 실제 결과 |
| --- | --- |
| `.local-tools/p09-d-recovery-item-red1-20261101` |21:19:04.5425576 종료. DB2 중 복구1 failure/SHIPPING 지원1 pass, errors/skipped0. unit·Arch204 failures/errors/skipped0. 입력 SHA `AF16139D6FEC72695B2E682DBE011137F064F84C66EA92814BD10943F6E86A0A` |
| `.local-tools/p09-d-recovery-item-green1-20261101` |21:49:43.399713 종료. DB3/unit·Arch204 failures/errors/skipped0. 입력604 SHA는 현재 고정본과 동일. 새 DB `d2_it_21362034f127492cb41b4189c3ff51d6`에서 V73 적용 성공 |

두 root는 모두 **failed / preservation passed**다. RED는 `startup recovery ... expected: <1> but was: <0>`의 실제 업무 실패이며, GREEN은 필수 미선택 때문에 root가 실패했다. 검토세션은 새 하네스를 실행하지 않았다.

`recoveryStartupResumesAckedItemEffectWithANewRealDelivery`는 실제 검수/동의→ITEM provider 성공→C 결과 보존 뒤 실제 primary job을 claim/no-op ACK한다. BOUND·D financial receipt0에서 내부 ApplicationReadyEvent와 등록 handler를 사용한 WorkerEngine을 구동한다. GREEN은 별도 실제 delivery job/manifest1·job SUCCEEDED·fee APPLIED·개별 RETURN COMPLETED/부모 PAID, 기존 primary row JSON 완전동일, payment attempt1/provider effect1/submit1을 assert한다. 반복 startup 후 money/stock/coupon/closure 업무 snapshot도 동일하다. provider를 다시 제출해서 누락을 해결한 결과가 아니며, 실제 OS 프로세스 재시작/child 검사는 아니다.

같은 GREEN에서 `returnFinancialWorkerAppliesOnlyRecordedConsentAndActualResultsOnce`와 `sellerReturnReadySchedulesAndSettlesOriginalShippingExactlyOnce`도 통과했다. 복구 FAILED/UNKNOWN·다중page/경합까지 이 단일 성공 복구로 입증한 것은 아니다.

앞 리뷰에서 한계로 남긴 SHIPPING 최종 GET revision3은 이번 **RED 실행에 함께 선택된 지원 검사**에서 직접 통과했다. 현재 보강 assertion은 최초 GET amount의 textual 타입/3000, 최종 GET200·revision3·SUCCEEDED·amount textual 타입을 확인한다. 복구 실패와 별개인 지원 검사 결과로 기록하며 이 지원 검사를 GREEN DB3에 포함했다고 쓰지 않는다.

### 후속 미완료

SHIPPING_RESULT/SELLER_REEVALUATE/B no-op 복구는 아직 구현·실행 완료가 아니다. 0/1/50/51의 전용 경계·복수page/고정 upper/후발 사실, 누락쓰기·위조 graph·오래된 결과·scan/delivery lease·child/경합, operation receipt와 일부 효과/ready 누락의 무결성 보고는 후속 NOT RUN이다. 반복 startup 성공을 전용0/50/51·중단 재개 검사로 대체하지 않는다. C 공통 ITEM 결과 보존 이전 구간은 최소 scan 미지원이다. 나머지 D01~08/UI와 선택없는 개발·지정 독립 전체도 남는다.

이번에는 이 리뷰 문서만 갱신했다. 제품/검사/하네스 수정·동시 하네스 실행·커밋·푸시·배포는 하지 않았고, 기존 자원·실패 기록·고정본과 적용 migration을 보존했다.

## §14 세 stage 첫 수직 검토 — recovery-stages-review1 (2026-11-01)

**판정: 요청된 세 stage 첫 수직 고정본에서 새 필수 수정 지적 없음. 후속 위험검사·구현 진행 가능, R-D01/R-D02 ADDRESSED 유지.** 복구 전체 또는 D 전체 승인, 선택없는 독립 전체 검증 통과를 뜻하지 않는다.

대상은 [recovery-stages-review1 불변 소스](../../.local-tools/p09-d-execution-20261101/recovery-stages-review1/source/)다. 입력606 SHA256 `DDEAD98B9E1330EB9D8D215B43C0E6E2CABFC6F4F86B39C87D4505841CD2CF31`, artifact611의 실제 해시 대조는 각각 불일치0이다. V74 SHA256은 `AEE4768E41CA3FB1D6EE9D804A52625BB644D8F19A2C241DBDF11F5495834D36`다. ITEM 고정본604 대비 신규 ReturnAftercareEvidence/V74와 Scanner/ReturnShippingResults/SellerAftercareWorker/검사/필수목록의7개 입력이 달라졌고 기존 migration은 그대로다. 구현자가 진행 중인 live 후속 변경은 검토하지 않았다.

### 설계·현재 구현 대조

run의 지원 stage가 ITEM_EFFECT/SHIPPING_RESULT/SELLER_REEVALUATE로 확장됐다. SHIPPING 후보는 claim 소유 typed binding·현재 단일 obligation을 찾고 공개 Refunds의 실제 목적/원 금액·quote reference·결과/version을 읽는다. 결과가 없거나 이미 해당 결과 receipt가 있으면 이유를 기록하며 새 submit/query/retry를 만들지 않는다. 새 manifest는 그 현재 결과의 정확한 payment/amount/reference/time/version을 담는다. ReturnShippingResults는 실제 primary 또는 정확한 SHIPPING manifest를 확인하고, 업무 잠금 안에서 현재 obligation/Refunds와 다시 대조한 뒤 기존 정산/결과 경로와 마지막 실제 delivery lease를 사용한다.

SELLER 후보는 실제 immutable ready를 anchor로 삼고 공개 order의 전체 원단위·현재 상태와 claim 근거를 재평가한다. ReturnAftercareEvidence의 단위 분류와 fingerprint는 기존 SellerAftercareWorker에서 그대로 추출되어 scanner의 현재 사실 일치 판단과 worker가 같은 계산을 사용한다. 이미 평가된 사실이라도 미완료 RETURN이 있고 완료 조건을 충족하면 재전달한다. 이전 부분 RETURN이 완료돼 있어도 current decision이 PENDING이면 후보에 남는 구조다. 이 구조 확인을 B no-op 특정 복구의 실행 증거로 대신하지 않는다.

SellerAftercareWorker는 실제 Jobs의 전체 command/RUNNING/token을 확인한 뒤 기존 canonical primary 또는 exact SELLER manifest의 RETURN_READY route만 허용한다. 같은 parent/전체 원단위 잠금 아래 원 ready의 order/seller 귀속과 최신 사실을 다시 읽으며, 결정·필요한 기존 S 예약·개별 완료·쿠폰·주문 평가 후 실제 delivery의 최종 lease를 확인한다. 원 primary 객체로 바꿔 호출하거나 과거 job을 초기화하지 않는다. R-D02 typed order 정산 포트도 유지되며 새 helper/scanner의 Java SQL은 claim 소유 관계에 한정된다. scanner 자체의 실제 JDBC recorder 전수 주입은 별도 후속이다.

V74는 새 SHIPPING 결과의 현재 job과 primary-or-exact-manifest/현재 payment 귀속, 새 seller decision과 새 completion의 정확한 route·현재 job/token을 요구한다. completion의 현재 job 검사는 신규 completion INSERT에만 적용하고, 기존 verify_return_completion의 역사 proof 재검증을 유지한다. 과거 완료 job이 ACK됐다는 이유로 새 사실 처리에서 역사 receipt를 무효화하는 V70 문제를 재도입하지 않았다. ITEM 성공 operation이 있으나 필요한 ready가 없는 경우를 후보로 포함하고 INTEGRITY_ERROR로 기록하는 코드도 추가됐지만, 해당 직접 위반 주입 검사는 아직 실행되지 않았다.

### 원 실행과 검사 보완의 적합성

세 root result와 실제 DB/단위 XML을 직접 읽었다. 모두 **root failed / preservation passed**이며 선택없는 전체 통과가 아니다.

| 실행 | 실제 결과 |
| --- | --- |
| `.local-tools/p09-d-recovery-stages-red1-20261101` |21:53:53.5580642 종료. DB1 failure1/errors0/skipped0, unit·Arch204 각각0. 실제 S 성공 결과 primary를 no-op ACK한 뒤 SHIPPING receipt 기대1/실제0. SELLER 본문 미도달 |
| `.local-tools/p09-d-recovery-stages-green1-20261101` |21:59:40.6117227 종료. DB5 중4통과/1실패, errors/skipped0, unit·Arch204 각각0. SHIPPING 기대1/실제0 유지. B DIRECT/AUTO 검사는 실제 수집 목록에 없음 |
| `.local-tools/p09-d-recovery-stages-green2-20261101` |22:08:37.6009755 종료. 현재 입력606과 동일 SHA, DB6/unit·Arch204 failures/errors/skipped0. B의 정확한 이름을 포함해 아래6개 실제 수집 |

GREEN2 수집은 ITEM 복구, SHIPPING/SELLER 복구, fullshipping, C/D 양순서, B DIRECT/AUTO 양순서, R02 scoped JDBC 검사다. SHIPPING/SELLER 복구 검사는 두 본문 모두 실제 원 primary claim/no-op ACK→새 정확한 stage manifest/실제 job SUCCEEDED→결과 또는 개별 완료1을 assert한다. 원 primary row JSON은 동일하고 SHIPPING 전량은 CLOSED, 부분 seller는 PAID다. 원 payment attempt/provider 효과 합계는 각각2/1을 유지하며 반복 run은 금융/입고/쿠폰/종결 업무 snapshot을 바꾸지 않는다. C/D 두 순서의 기존 개인 사실 보존, B 네 순서, R02 외부 relation0/잘못된 receipt3 rollback도 같은 GREEN2에 포함됐다.

GREEN1→GREEN2 입력 비교에서 바뀐 파일은 **PostgresJdbcIT 하나**다. 제품 guard·V74·필수목록·timeout을 바꾸지 않았다. 새 drain은 실제 startup으로 생성된 run1을 식별하고, 그 run의 scan/manifest 및 candidate seller 후속 job을 실제 저장 nextRunAt 기준으로 선택한다. 기존 OneJobJdbc/실제 JdbcJobs/등록 WorkerEngine을 사용하며 clock은 stored+1µs·DB 관측·host의 최대값이다. READY/attempts0만 처리한 직후 각 job SUCCEEDED·lastError null을 요구하므로 실패한 retry의 시간을 반복 전진시켜 성공으로 숨기지 않는다. 마지막에는 모든 stage completed와 모든 manifest delivery SUCCEEDED도 직접 확인한다. 이 보완은 선택된 정상 수직의 준비 조건으로 타당하지만, 고정 clock과 단일 job 실행이므로 실제 시간 경과 lease·동시 claim·child 재시작을 검증한 것으로 확대하지 않는다.

GREEN1 [읽기 전용 진단2](../../.local-tools/p09-d-execution-20261101/recovery-stages-green1-readonly-diagnosis2.txt)에서는 실패 대상 `fcd39282-9879-4706-a014-093f38656828`의 실제 SHIPPING manifest/result job이 이후 run에서 SUCCEEDED로 소비된 사실을 확인했다. [진단4](../../.local-tools/p09-d-execution-20261101/recovery-stages-green1-readonly-diagnosis4.txt)의 run/page·동의 저장 시각은 비단조 순서를 보이고, 별도 host-before/DB/host-after bracket은 DB 시각이 그 관측 구간 안이다. 특정 VM 시계 원인을 확정하지 않는다. 원 drain의 host+1초 가정·claim 없음 종료를 보완한 근거와 원 업무 실패를 함께 보존한다. compile57의 unused import Checkstyle 준비 실패·DB 미실행도 복구 업무 RED와 구분한다.

### 남은 범위와 보존

전용0/1/50/51·복수page/고정 upper/후발 사실·cursor, 위조 manifest/graph·누락쓰기 rollback, scan/delivery lease·실제 child·경합/stale, B no-op 특정 복구는 후속 NOT RUN이다. 새 ready/S/완료 SQL checkpoint, 나머지 D01~08/UI와 선택없는 개발·지정 독립 전체도 남는다. C 공통 결과 보존 이전 구간은 최소 scan 미지원이다. 현재6개 선택 검사가 이들을 대체하지 않는다.

이번에는 이 리뷰 문서만 갱신했다. 새 하네스 실행이나 제품/검사 수정·커밋·푸시·배포는 하지 않았으며 기존 자원·실패·고정본·적용 migration을 보존했다.

## 복구 candidate 원자성 보완·경계 증거 검토 — recovery-atomicity-fix-review1 (2026-11-01)

**판정: 자체 발견된 delivery/candidate 원자성 결함은 V75와 실제 누락 RED→GREEN 범위에서 보완됨. 새 필수 수정 지적 없음, 후속 진행 가능. R-D01/R-D02 ADDRESSED 유지.** 이전 중간 검토를 원자성 검사의 통과로 소급하지 않으며 복구 전체/D 전체 승인과 구분한다.

대상은 [불변 소스](../../.local-tools/p09-d-execution-20261101/recovery-atomicity-fix-review1/source/) 입력607 SHA256 `9139E7DCB3783EC073D77D4B61426726577C7FB5CC9198AC9CE9A324EB769027`, artifact612다. 실제 파일 해시 대조에서 입력/산출물 불일치 각각0을 확인했다. 직전 stages-review606 대비 제품 변경은 신규 V75 하나이며, 나머지는 PostgresJdbcIT·필수목록이다. 기존 V73/V74와 Java 제품 코드는 변경되지 않았다. V75 SHA256은 `76FBE5F281BB4B5471BE20DF9B77E2D0EE9E272E607E845F58F050770A950901`다. live 후속 변경은 검토하지 않았다.

### 실제 결함과 보완의 충분성

기존 graph는 candidate가 있으면 delivery와 결합하도록 했지만 delivery가 있으면 candidate도 있어야 한다는 역방향 제약이 없었다. 따라서 candidate INSERT를 실제 BEFORE trigger로 누락하면 후보 transaction의 manifest/job/Outbox가 먼저 commit되고 뒤 page 완료에서409가 발생했다. [RED 읽기 전용 원문](../../.local-tools/p09-d-execution-20261101/recovery-graph-red1-readonly-orphan-proof.txt)은 manifest1·delivery job READY1·Outbox1/candidate0·page complete0·금융/fee 효과0을 보여 준다. RED의 메모리 before Map은 파일로 보존되지 않았으므로 과거 before/after 전수 동일성 자료가 있다고 표시하지 않는다.

V75는 새 delivery INSERT의 deferred constraint trigger에서 정확한 DELIVERED candidate의 delivery/run/stage/page/return/scan job/token 일치를 요구한다. 기존 candidate→delivery guard 및 candidate 고유성과 결합해 둘 중 하나만 있는 후보 거래를 허용하지 않는다. 별도 durable page 계획 transaction은 유지하며 cursor를 앞당겨 실패를 감추지 않는다. 정상 SKIP/INTEGRITY_ERROR는 delivery가 없으므로 새 trigger의 적용 대상이 아니고 기존 candidate/page 검사를 유지한다. 역사 manifest/receipt를 UPDATE하거나 ACK된 scan의 영구 RUNNING을 새로 요구하지 않는다. 적용 V73/V74를 고치지 않고 신규 migration으로 보완한 범위가 적절하다.

`recoveryDeliveryCannotCommitWithoutItsCandidateProof`는 실제 원단위 회수/검수·동의·ITEM 제공자 성공·C 결과 보존·primary no-op ACK와 실제 claimed scan에서 candidate 쓰기만 누락한다. GREEN [정확한 원문](../../.local-tools/p09-d-recovery-graph-green1-20261101/db/recovery-candidate-omission-actual.json)의 `org.postgresql.util.PSQLException`, SQLSTATE23514, `EXACT_RECOVERY_DELIVERY_CANDIDATE_REQUIRED`를 확인했다. 파일의 before/after participant 각 값도 직접 대조해 불일치0이었다. 비교에는 업무 participant 외 run/stage/delivery/candidate/page_complete가 포함되고 별도 page plan은 의도적으로 제외하여 실제 plan1을 별도로 assert한다. 이후 공격 trigger 제거→같은 run/현재 scan 재개→ACK 전 같은 page/candidate 재생 불변→정산1·원 primary row JSON 동일을 확인한다. 단순히 뒤 cursor가 실패하는 것을 원자성 성공으로 센 검사가 아니다.

### 실행별 소스·결과 구분

각 root result·실제 DB XML·단위/Arch XML을 직접 읽었다. 모든 실행은 **root failed / preservation passed**다. graph RED는 실제 assertion 실패이고 나머지는 필수 미선택에 따른 root 실패다. 모두 unit·Arch204 failures/errors/skipped0이다.

| 실행 | 입력 SHA256 | DB 실제 결과 |
| --- | --- | --- |
| recovery-pages-focused1 |`282F8FE354F4A5C78DABA157BDAB925FBECDC77C798FF2620672F52AC570C758`|22:18:09.9782722 종료,1검사 failures/errors/skipped0 |
| recovery-boundary-focused1 |`016C03E0A54532F04925E9E1C72A3A21596C431E05B1CD293AD1D45ECF71AE57`|22:25:39.5295022 종료,2검사 failures/errors/skipped0 |
| recovery-graph-red1 |`BFBDE40A9AA2FFFD30B08605376620E0945D9E703EFF0A73EB872DCC070339B5`|22:32:30.5726028 종료,1검사 failure1/errors0/skipped0. 기대한 같은 거래 SQL 거부 대신 뒤 ORDER_REQUEST_CONFLICT |
| recovery-graph-green1 |`9139E7DCB3783EC073D77D4B61426726577C7FB5CC9198AC9CE9A324EB769027`|22:36:55.0159411 종료,3검사 failures/errors/skipped0. 현재607과 동일 입력 |

실행 경로는 각각 `.local-tools/p09-d-<위 실행명>-20261101`이다. 현재 GREEN3은 누락 공격 검사와 ITEM 복구, SHIPPING/SELLER 복구다. 후자의 정상 끝·새 run 반복 추가0은 V75 뒤 정상 전달/기존 skip·empty 경로의 회귀 근거다. 앞 페이지/B 경계 검사를 현재607에서 전부 재실행했다고 합산하지 않는다.

### V74에서 확인한 추가 경계

`recoveryItemPagesBoundZeroOneFiftyAndFiftyOneWithoutResettingAcknowledgedJobs`는 실제51원단위를51개의 원배송 bucket으로 split·완료 배송한 뒤 각 실제 반품 동의/ITEM 결과를 만든다. 51주문이 아니다. empty의 page `[0]`, 후보1/50/51의 `[1]`/`[50,0]`/`[50,1]`, 정확 candidate/delivery distinct 수, scan-only D 금융효과0을 assert한다. 앞1+50개 recovery 전달도 실제 no-op ACK한 뒤 마지막51개 run의 새 전달로 적용하며, 원 primary51·앞 recovery51의 job row JSON 동일·ITEM provider 효과51/submit51·D receipt51·새 run 업무 추가0을 확인한다. [페이지 JSON](../../.local-tools/p09-d-recovery-pages-focused1-20261101/db/recovery-pages-actual.json)도 이 결과와 일치한다. 정상 bounded page와 새 run 재전달의 근거이며 child/page 중단 검사가 아니다.

`recoveryFixedUpperExcludesLaterHigherFinancialFactUntilTheNextRun`는 미리 존재하는 두 실제 RETURN 중 낮은 UUID에만 금융 사실을 보존한 후 upper를 고정하고, 높은 UUID의 실제 동의/provider/C 결과를 나중에 추가한다. 현재 run의 upper 불변·높은 사실 delivery0/효과0, 다음 run의 exact delivery1/정산1·원 primary 불변/provider 효과 각1을 assert한다. [상한 JSON](../../.local-tools/p09-d-recovery-boundary-focused1-20261101/db/recovery-fixed-upper-actual.json)을 대조했다. 높은 RETURN 행 자체가 run 이후 INSERT됐다는 검사가 아니며 UUID upper를 시간 snapshot으로 해석하지 않는다.

`recoveryReadyAnchorClosesAckedDirectAndAutomaticConfirmationFactsInBothOrders`는 실제 coupon COMMITTED 주문에서 DIRECT/AUTO×RETURN-first/CONFIRMED-first 네 경우를 수행한다. 실제 B fact job을 no-op ACK하고 fanout0·원 job/confirmation receipt 불변을 확인한다. RETURN-first는 완료된 개인 RETURN/REFUNDED hold와 PENDING을 보존한 채 새 ready-anchor 전달1로 RETAINED/CLOSED에 수렴한다. CONFIRMED-first는 정상 나중 D 사실이 이미 종결해 own recovery 전달0이다. 네 경우 원 S 환불0·쿠폰 복구0/COMMITTED·CONFIRMED2·late APPROVED 추가0이며 DIRECT 원 명령 재생도 불변이다. 이로써 이전 B no-op 특정 정상 경계 미실행 항목은 해당 V74 실행 범위에서 해소됐다.

### 남은 검증

나머지 SQL 누락 checkpoint·교차 귀속/위조 manifest·page cursor 중단·scan/delivery lease·실제 child·경합/stale, D01~08 잔여/UI와 선택없는 개발·지정 독립 전체는 계속 미완료다. V75 뒤 페이지/상한/B 네 경우 전체 회귀도 후속 실행에서 확인해야 한다. 이번 보완 판정을 복구 전체 승인으로 확대하지 않는다.

이번에는 이 리뷰 문서만 갱신했다. 제품/검사/하네스 수정·동시 검사 실행·커밋·푸시·배포를 하지 않았고 기존 자원·실패 DB/증거·적용 migration·고정본을 보존했다.

## §14 후속 위험검증 검토 — recovery-risks-review1 (2026-11-01)

**판정: 요청된 위험검증 고정본에서 새 필수 수정 지적 없음. V75 candidate 원자성 보완 수용과 R-D01/R-D02 ADDRESSED 유지, 후속 진행 가능.** 확인한 개별 경계 근거의 수용이며 복구 전체/D 전체 승인 또는 독립 전체 통과가 아니다.

대상은 [recovery-risks-review1 불변 소스](../../.local-tools/p09-d-execution-20261101/recovery-risks-review1/source/) 입력607 SHA256 `C520872B8029F2F3D47CFB002D2AF5AE50B859DFFDC7A19FD765C22607A96073`, artifact612다. 실제 입력/산출물 해시 대조 불일치 각각0. 직전 atomicity-fix 고정본 대비 PostgresJdbcIT·RefundWorkerProbe·필수목록만 바뀌었으며 Java 제품과 V41~75는 동일하다. live 후속 구현은 읽지 않았다.

### 실행별 식별과 실제 결과

아래 각 root result, 실제 DB/단위 XML을 직접 읽었다. 모두 root **failed / preservation passed**, unit·Arch204 failures/errors/skipped0이다. race-focused1은 준비 assertion 실패이며 다른 실행의 DB는 표의 선택 범위에서 failures/errors/skipped0이다. 제품은 같지만 검사 입력이 다르므로 동일 소스 전체 실행으로 합산하지 않는다. 실행 경로는 `.local-tools/p09-d-<실행명>-20261101`이다.

| 실행·종료 | 입력 SHA256 | DB |
| --- | --- | --- |
| recovery-risk-focused1 ·22:46:05.6384875 |`84F773D8CBAD595E9FADBB35130CE6921BACB1710F09A301966CD7378EFC4EEC`|1통과 |
| recovery-child-focused1 ·22:57:33.1932591 |`9FE9D9346717245E736F2A2B46E55B31DF231F380BDBD598AF803D8303517C8F`|3통과(복구 child·기존 PREPARED/ACK child) |
| recovery-lease-focused1 ·23:10:59.979121 |`A82AC459DF93CA5EF63AFF8C9D6DBB8D62482C9695232B2DBC7995AED12BB882`|1통과 |
| recovery-race-focused1 ·23:21:59.0454688 |`D288D9F8F74FCC63831553E8668DEE2F6D0470A8054F9642A2F03B3E552C1E89`|1실패,금융 winner assertion 미도달 |
| recovery-race-focused2 ·23:29:26.9730899 |`80994BA66528271AF38072A3A15B3FBD072442AA255256A9D8FA538FEBE6CA45`|1통과 |
| recovery-stale-focused1 ·23:36:52.7354907 |`CD1C99EEB6D3F0BACD3F5E953B0063CF37768BD888DC590052074561BF34AFA8`|1통과 |
| recovery-pages-focused2 ·23:46:44.0138343 |`C520872B8029F2F3D47CFB002D2AF5AE50B859DFFDC7A19FD765C22607A96073`|2통과, 현재 고정본과 동일 |

### 쓰기·변조·페이지 경계

`recoveryManifestForgeriesAndEveryDeliveryWriteFailAtomicallyAndResume`의 최신 [27결과 원문](../../.local-tools/p09-d-recovery-pages-focused2-20261101/db/recovery-atomic-writes-and-forgeries.json)을 코드와 대조했다. candidate/job/Outbox/delivery INSERT4는 실제 주입23514·지점명·전체 participant 불변을 assert한다. manifest의21개 단일 필드 교체는23514 또는23503과 전체 rollback을 assert하고 실제 guard를 저장한다. **21개 각각의 guard명을 테스트 assertion으로 고정한 것은 아니다.** 원문 대조 결과 run/order/seller는 복합 FK, return/attempt/version/stage/type/cause/payload는 route CHECK, key는 key CHECK, payment/source/hash/amount/reference/state/resultReference는 실제 보존 ITEM source guard, job/scan token/scheduledAt는 exact job/Outbox guard에서 거부됐다. 단지 아무 RuntimeException이면 통과하는 검사가 아니다.

최신 amount·original reference·result reference는 다른 실제2단위 반품의 공개 Refunds 값이며 기존 임의값 검사보다 귀속 검증 근거가 강화됐다. 앞 focused1을 이 보강본 실행으로 소급하지 않는다. 실제 current scan의 정상 재개·금융1·원 primary 불변, scanner에만 주입한 transaction-aware SQL466/직접 외부 relation0도 확인했다. 협력 공개 포트 내부 자기 SQL과 구별한다. 각 독립 공격 전에 heartbeat를 한 것은 정상 현재 owner를 유지하는 준비이며 lease 만료 근거로 세지 않는다.

page receipt/cursor2 실패는 이미 commit된 candidate를 보존하고 후속 거래만 rollback한다. 최신51단위 fixture의 [next-page 원문](../../.local-tools/p09-d-recovery-pages-focused2-20261101/db/recovery-next-page-rollback-actual.json)은50후보 시점 next job/Outbox/pageComplete/cursor4의 실제23514·각 지점 guard와 정상 next-page1/replay0을 보여 준다. 코드에서 durable candidate50·manifest/delivery job/Outbox snapshot 동일, 후속 page receipt/cursor/next job0·업무효과0을 assert한다. 기존 `[0]`, `[1]`, `[50,0]`, `[50,1]`·primary51+앞 recovery ACK51 불변·provider51/submit51/새 run 업무0도 끝까지 실행됐다. 이는 V75 이후 정상 page와 다음 page 발행 실패 회귀를 보완한다. 모든 종류의 위조 조합·모든 stage의 graph 전수 검사라고 확대하지 않는다.

### 실제 child·최종 lease

`recoveryActualChildResumesCandidateCursorAndCommitBeforeAckWithoutDuplicateDelivery`의 probe를 읽었다. test-only JDBC는 실제 SQL에 위임하고 candidate INSERT 후 **실 transaction afterCommit**, cursor UPDATE 전 **아직 열린 transaction**, 실제 Jobs.complete 전 **전체 업무 commit 뒤 ACK 전**에만 marker/barrier를 설치한다. 신규 RECOVERY 모드 외 기존 PREPARED/NORMAL/ACK 조건은 유지한다. 실제 별도 JVM의 marker PID와 restart completed PID, [세 구간 결과](../../.local-tools/p09-d-recovery-child-focused1-20261101/db/recovery-actual-child-stages.json)를 확인했다.

자기 Process를 kill한 뒤 저장 lease+1µs에서 같은 job/run을 재claim해 attempts2, immutable candidate/manifest/delivery job/Outbox 및 원 발행 scan token 보존, 미커밋 cursor의 새 owner·이미 commit된 page proof의 옛 owner를 구별한다. 마지막 delivery 적용 뒤 앞 run 전달을 실제 WorkerEngine으로 재생해 효과1/provider1/submit1과 추가0을 확인한다. compile65의 Boolean overload 준비 실패는 DB 미실행 이력으로 남으며, 그 정정 자체가 업무 RED는 아니다. 이 child는 scan의 후보/커서/ACK 경계이며 새 SHIPPING 정산·seller 완료 child를 대신하지 않는다.

`recoveryScanAndDeliveryLoseLeaseAfterActualBusinessWaitAndOnlyTakeoverWrites`는 scan의 purchase_order, ITEM recovery의 claim_fee_bucket을 실제 별도 연결로 잠근다. [scan 원문](../../.local-tools/p09-d-recovery-lease-focused1-20261101/db/recovery-final-lease-wait-false.json)·[delivery 원문](../../.local-tools/p09-d-recovery-lease-focused1-20261101/db/recovery-final-lease-wait-true.json)의 blocker PID/실 SQL/Lock wait와 저장 deadline보다 정확1µs 큰 관측 clock을 확인했다. 실제 JdbcJobs 최종 `JOB_LEASE_LOST`→participant rollback(별도 page plan 제외)→takeover attempts2/새 token→옛 token409·새 owner 적용/재생0을 assert한다. mock lease 실패가 아니다. 실제 stored recovery에 다른 token·primary로 바꾼 command·옛 ACK primary id를 준3객체도409/all0이며 금융/provider1로 재개한다. 이 실측은 scan/ITEM delivery 범위다.

### 경합·오래된 결과

race-focused1은 두 consumer가 main PID에 직접 매달린다는 준비 assertion에서 실패했고 당시 live 관측이 없으므로 원인을 특정 체인으로 소급 확정하지 않는다. focused2는 매 poll NDJSON에 실제 PID/query/blocker, 두 Future와 stored job을 assertion 전에 저장한다. 새 [primary 경합](../../.local-tools/p09-d-recovery-race-focused2-20261101/db/recovery-concurrent-parent-wait-true.json)·[두 run 경합](../../.local-tools/p09-d-recovery-race-focused2-20261101/db/recovery-concurrent-parent-wait-false.json)에서 main→첫 consumer transactionid→둘째 tuple 대기를 직접 확인했다. 코드의 두 Future pending·Lock2·부모 query 확인 뒤 해제/both COMPLETE, winner가 실제 두 job 중 하나, fee/financial receipt/history/ready/provider/submit1 assertion이 유효하다. 두 run에서 원 ACK primary 불변·새 run 반복0도 유지한다. 원 관측 보완을 제품 수정이나 timeout 확대라고 쓰지 않는다. 실제 canonical payload의 job이라도 primary key/manifest가 없으면409/all0인 별도 실행도 포함된다.

`oldRecoveryManifestsCannotReverseSupportedSuccessInEitherArrivalOrder`는 FAILED/LOST_RESPONSE×old-first/new-success-first4를 실행한다. scan-only의 업무/provider 불변·attempt1·SUPPORT0·FAILED 예산0/UNKNOWN 예약 유지, old-first의 REVIEW_REQUIRED/BOUND/ACTIVE/no ready를 assert한다. 합법 SUPPORT retry의 새 sequence2 또는 같은 attempt query 후 실제 새 성공을 적용한다. old recovery가 여전히 RUNNING/같은 token/유효 lease임을 원문 저장·assert한 뒤409와 전체 participant 불변을 확인하므로 단순 만료 거부를 stale result 방어로 오인하지 않는다. 원 consent·retained 결과·ACK primary는 동일하고 provider 효과1이다. 결과 원문의 UNKNOWN 최신 version4/sequence1, FAILED 새 sequence2/version2를 확인했으며 임의 version 가정을 하지 않는다.

### 판정 한계

이번 증거로 위 개별 §14 위험 경계를 수용한다. 나머지 §13 ready/S/completion SQL·child, D02/D07/D08 및 기존 매핑 잔여, §15 조회/UI/browser, 선택없는 개발·지정 독립 전체는 후속 NOT RUN이다. 앞 V74 fixed upper/B no-op 실행도 현재607에서 재수집했다고 합산하지 않는다. C 공통 결과 보존 이전 gap은 최소 scan 미지원으로 유지한다.

이번에는 이 리뷰 문서만 갱신했다. 제품/검사/하네스 수정·동시 실행·커밋·푸시·배포를 하지 않았으며 기존 자원·실패 기록·고정본·적용 migration을 보존했다.

## §15 공통 S origin·지원 경로 기술 판단 — 2026-11-02

현재 편집 중인 JdbcReturnQueries/JdbcClaimSupport/ReturnShippingSupport/allocator·C 결과 분기와 C 공개 인계를 읽고 [설계 §15.6](P09-D-design-review.md#156-seller-공통-배송비의-typed-origin과-support-경로--2026-11-02)에 판단을 추가했다. 불변 whole 검토나 실제 재현 실행은 아니며 앞 recovery-risks-review1 수용 범위와 구분한다.

최소 채택은 **서버가 결합한 typed origin + supportContract/commandRevisionKind로 기존 소유 계약에 연결**하는 것이다. RETURN-origin S는 실제 origin returnId/독립 S revision, 기존 CANCEL-origin S는 실제 origin claimId/C claim revision을 사용한다. 현재 보고 있는 returnId로 seller 공통 S의 명령을 무조건 보내거나 C의 FAILED/UNKNOWN을 RETURN obligation state/version 규칙으로 해석해서는 안 된다. projection의 allowedAction과 실제 명령 허용 조건을 일치시키는 것은 이번 UI 완료 전 필수다.

현재 읽은 코드에서 C preferredCancellation 예약은 실제 전량 미발송 CANCEL일 때만 발생하고, 혼합 공통 예약은 RETURN-ready를 우선한다. **C-last라는 순서만으로 CANCEL-origin이라고 확정할 수 없다.** 실제 혼합 FAILED/UNKNOWN 재현은 origin kind/id·생성 경로부터 기록해야 한다. 다른 RETURN origin을 현재 view returnId로 호출하는 문제도 별도로 포함한다. 기존 C의 terminal guard뿐 아니라 SHIPPING 결과 적용/order 정산도 allCanceled 계약에 묶여 있으므로, CANCEL-origin 혼합/terminal 의무가 실제 확인되면 UI route 또는 guard 한 줄 완화로 충분하지 않다. 그때는 원 개인 기록을 보존하는 공통 독립 S 지원·결과·정산·복구/SQL graph의 수직 보완과 재검토가 필요하다.

이 판단은 기존 업무 정책 안의 기술 경계이며 새 사용자 정책 질문은 없다. 새 origin/authority/revision/실제 FAILED·UNKNOWN→정산 검사는 NOT RUN, 제품 구현은 Sol에 남겼다. R-D01/R-D02 ADDRESSED와 V75 수용을 변경하지 않으며, 현재 UI 분기 또는 D 전체를 승인하지 않는다. 두 문서만 보완했고 제품/검사/하네스 실행·커밋·푸시·배포·E는 진행하지 않았다.

## §16 조회·UI·typed origin 연결 고정본 중간 검토 — 2026-11-02

**판정: 검토한 연결 범위의 신규 필수 지적0, 다음 D06·잔여 제약 검증으로 진행 가능. D 전체 완료 또는 전체 하네스 통과 판정은 아니다.** R-D01/R-D02 ADDRESSED와 앞 V75 보완 수용을 유지한다. 이 절은 §15 기술 판단 뒤의 실제 구현·개발 집중 증거 검토이며, 앞 NOT RUN 기록을 소급 변경하지 않는다.

### 식별과 실제 실행 범위

검토 소스는 `.local-tools/p09-d-execution-20261101/read-ui-origin-review1/source` 불변 사본이다. inputs620 SHA256은 `61704149D1C102041303435BB8301C188D7FE7BBCD710848389BCD1B3ABE0D7F`, artifact625 재해시 불일치0이다. live 작업 트리의 후속 변경을 이 판정에 포함하지 않는다. 부모 commit은 `80ad8cd4d194766a4e09e7e49c5ecce1b46bc506`이며 이 입력은 미커밋 D 소스를 포함한다.

주 근거 `.local-tools/p09-d-typed-origin-green4-20261102`는 같은620입력의 개발 집중 실행이다. 원 JUnit XML의 DB7·unit204 failure/error/skip0, 새 DB `d2_it_8ebdab51764a49229a76403647b99f8a`, root `preservation=passed`를 확인했다. **root status는 필수 미선택 때문에 failed**다. 이 리뷰에서 하네스를 별도로 실행하지 않았으며 독립 전체 실행으로 부르지 않는다.

| 실제 DB 메서드 | 본문 시간 | 검토한 assertion 범위 |
| --- | --- | --- |
| `returnReadFinalAuthorityAfterActualClaimWaitDeniesExpiredMemberGasSellerAndSupport` | 8.191s | 네 actor LIST/DETAIL 정상·최종 잠금 대기 중 저장 deadline+1µs 만료, SUPPORT 권한 제거 추가:18경로 |
| `actualGasReturnReadHttpUsesCurrentCookieOwnershipCsrfAndNoPrivateCache` | 2.169s | 실제 GAS cookie·현재 소유권·no-store·POST CSRF·다른 묶음/로그아웃 거부 |
| `returnReadListsIncludeTerminalHistoryAndBindStableCursorsToCurrentActorAndOrder` | 2.718s | REQUESTED/REJECTED/WITHDRAWN 이력·안정 커서·actor/order/seller 귀속·GET 업무효과0 |
| `mixedCancellationAndSellerReturnShareShippingInBothLastFactOrders` | 5.388s | 실제 CANCEL3/RETURN1 양쪽 마지막 사실 순서·RETURN origin·S1·CLOSED·앞 개인 기록 불변 |
| `shippingRefundFailedRetryAndUnknownQueryKeepOriginalObligation` | 2.565s | physical RETURN0인 순수 C에서도 목록으로 S 발견·CANCEL_CLAIM revision·기존 지원/정산 계약 |
| `returnShippingSupportUsesActualOriginFromAnotherReturnView` | 9.335s | 다른 RETURN view에서 실제 origin 지원·FAILED/UNKNOWN·동일 key 실제 HTTP 경합·오귀속/변경 요청 거부 |
| `shippingFailedSupportRetriesAndUnknownQueriesPreserveIndependentOriginalObligation` | 5.665s | 독립 S GET1→지원 영수증2→결과 GET3·원 개인 완료/ITEM 보존 |

### 현재 조회 권한·이력·업무 projection

`ReturnQueries`의 typed 공개 DTO와 `JdbcReturnQueries`/controller, order 소유 `ReturnApplications.current/eligibility`를 대조했다. owner는 현재 `lockOwned`, seller는 현재 `lockSellerOrder`, SUPPORT는 현재 SUPPORT 권한을 먼저 검사하고, 실제 claim 행과 공개 order/payment 증거를 읽은 뒤 최종 권한을 재검사한다. seller 이력 조회는 결제 진행 중 쓰기 권한에 종속시키지 않아 terminal 이력을 읽는다. 목록은 요청시각/id 순서와 actor/order/seller/current credential에 결합한 커서,1~50 크기 제한을 사용한다. 원 상품 금액/정책은 접수 source 및 원 주문에서 가져오며 현재 상품 가격으로 재계산하지 않는다. eligibility는 반품 기한·원단위 상태이며 구매 확정 eligibility를 재사용하지 않는다.

실제 [최종 권한18경로 원문](../../.local-tools/p09-d-typed-origin-green4-20261102/db/return-read-final-authority-actual.json)과 각 `return-read-wait-*`/scoped JDBC 기록을 코드에 대조했다. 다른 연결이 claim 행을 잡은 동안 Future 미완료·실 pg blocking query를 확인하고 저장된 실제 만료시각보다1µs 뒤로 관측 clock을 옮긴다. 만료 후 member/GAS/seller404, SUPPORT401, SUPPORT 권한 제거403·private body0이다. 단순 사전 만료 fixture나 mock authority 거부가 아니다. 정상 SUPPORT도 실제 미해결 S의 origin/contract/FAILED를 조회한다. claim query에 주입한 JDBC 기록은 직접 외부 order/payment/promotion/inventory relation0이며 협력 공개 포트의 자기 저장소 SQL과 구별된다.

거부 경로는 participant 전체 불변을 assert한다. **정상 GAS GET은 정확한 현재 credential의 sliding idle 연장이 허용되므로 DB 전체 쓰기0이라고 주장하지 않는다.** helper는 다른112개 participant, 다른 credential 및 현재 credential의 나머지 필드 불변, idle 비감소·max 이하를 검사한다. 명시 logout은 그 credential의 revoked_at만 별도로 허용한다. 실제 HTTP는127.0.0.6에 bind한 client의 GUEST_ACCESS cookie를 사용하고 계정 로그인으로 대체하지 않는다. [HTTP 결과](../../.local-tools/p09-d-typed-origin-green4-20261102/db/d-read-actual-gas-http.json)의 현재200/foreign·anonymous404, 각 actor POST CSRF403과 문자열 net을 확인했다. cursor 검사는3개의 실제 현재/철회/반려 이력의 중복·누락0 및 다른 actor/order cursor400, 다른 seller/order 상세404를 직접 확인한다.

ITEM 금융 상태·fee assessment 반영, 독립 S 업무 state·실 금융 resultState, 개인 완료와 주문 종료를 분리한다. S RESERVED/UNKNOWN 또는 RESERVED/SUCCEEDED는 결과 반영 전 유효한 조합이다. 최초 실제 조회409 RED는 `recordedPaymentIds`가 완료 영수증에서 파생되어 비어 있을 수 있음을 오해한 결함이었다. 현재 코드는 공개 `Refunds.original`의 실제 payment/order 금액/mode와 원 배송비·reference를 결합하고 기존 recorded payment의 불일치를 검사한다. 이 수정이 원 payment 귀속 검사를 제거한 것은 아니다.

### 공통 배송비 실제 origin과 지원

§15.6의 기술 판단을 현재 구현·실행이 뒷받침한다. 혼합 C-first/D-first 모두 실제 origin은 RETURN이며, 순서만으로 CANCEL-origin을 추정하지 않는다. `shippingWorkflow`는 실제 원 intent의 hold/cause/원단위와 RETURN ready 또는 C claim을 확인한다. projection은 원 seller·origin kind/id·supportContract·commandRevisionKind/value를 제공하며, RETURN은 독립 의무 revision, CANCEL은 실제 C claim revision을 사용한다. pure C의 obligation RESERVED/version0과 실제 FAILED/UNKNOWN을 RETURN 상태 규칙으로 오인하지 않는다. 목록의 seller 공통 의무는 RETURN 이력 존재와 독립이어서 physical RETURN0도 발견된다.

다른 RETURN view의 실제 HTTP 검사는 coupon이 있는 여러 실제 물리 반품에서 origin과 viewed return이 다름을 먼저 확인한다. wrong view/revision/order/contract/purpose·추가 body contract·동일 key 다른 사유를 거부하고 각 해당 구간의 업무 불변을 검사한다. 두 실제 HTTP Future가 canonical parent blocking chain에 함께 대기한 뒤 같은202 영수증·지원 command1·재생0이다. FAILED는 새 attempt, UNKNOWN은 같은 attempt이며 실제 worker 후 S1/coupon1/CLOSED·기존 개인 효과 불변·final supportActions0을 확인했다. [FAILED 원문](../../.local-tools/p09-d-typed-origin-green4-20261102/db/typed-shipping-origin-FAILED.json)과 LOST_RESPONSE 원문을 대조했다. C 지원도 기존 영수증 재생0·성공 후 새 retry409를 유지한다. CANCEL-origin 혼합 terminal 의무라는 미재현 상황을 새로 지원했다고 확대하지 않는다.

### 화면 수명·응답 유실·실행 한계

실제 `OrderDetailPage`/`SellerShipmentsPage`/App SUPPORT 경로 mount, `ReturnPanel`, `ReturnStore`, `ReturnTypes`, GAS 수명 통지를 읽었다. actor/order/seller/guest context별 새 store, subscribe 해제/StrictMode 재연결, AbortController+generation+현재 auth/GAS stamp의 publish 검사, 로그인/로그아웃/복구 시 개인 데이터·draft·pending 폐기를 확인했다. focus는 GET만 수행하고 재검증 시작부터 이전 private view를 지운다. 최초 조회나 mount에서 금융 POST를 자동 발행하지 않는다.

명령은 pending에 실제 origin/contract/경로/revision/key/canonical body를 고정한다. 응답 유실은 동일 요청만 재전송하고 접수 확인과 최신 GET 성공을 분리한다.409에서는 기존 quote·양쪽 동의·pending을 폐기하며 다른 endpoint로 fallback하지 않는다. RETURN/CANCEL 두 고정 route를 사용하고 서버가 제공한 임의 URL을 실행하지 않는다. strict DTO는 원단위/원금·purpose·origin/contract/revision kind·허용 action과 현재 상태의 모순을 거부한다. 금액은 decimal string/BigInt이고 견적은 일반 동의와 legacy 동의를 따로 요구한다.

`.local-tools/p09-d-origin-ui-green3-20261102`의 타입/린트/빌드/unit exit0, JUnit 전체275 failures/errors0와 필수275 누락0을 확인했다. 그 실행의 frontend/required-frontend 입력189개는 이 고정본과 해시 불일치0이다. 실제 DOM 검사는 기존 주문 상세의 D mount·StrictMode/focus GET·auth loss private input 제거·unmount 구독0, pure C 의무 발견→기존 C revision route의 실제 버튼 처리를 검증한다. store 검사는 늦은 member/GAS 응답 폐기,400/409/401/403/404, 동일 key/body, legacy 동의, 큰 금액과1원, 잘못된 origin DTO 거부 등을 직접 assert한다. 다만 mount 검사 일부는 기존 독립 panel을 mock한다. **실제 API를 연결한 PC/mobile 브라우저 인수 또는 모든 actor DOM 흐름의 대체 증거가 아니다.** origin UI의 TypeScript matcher 준비 실패와 그 뒤 성공은 별도 보존한다.

### 앞 검토 이후 누적 SQL·child·D02/D07/D08 근거

아래는 서로 다른 시점의 개발 집중 실행이다. 전부 preservation passed지만 root는 필수 미선택 failed이며, 현620 전체 재실행 결과로 합산하지 않는다. 현재 고정본의 해당 assertion 코드와 원 실행 summary/기록을 연결했다.

| 경로 suffix (`.local-tools/p09-d-…-20261102`) | 실제 결과 | 수용한 범위 |
| --- | --- | --- |
| `terminal-sql-focused1` | DB2/unit204, 실패·오류·skip0 | ready4/S예약11/S결과5/완료16 실제 SQL 실패 지점36, 명명된23514 guard·전체 rollback·정상 재개/재생0 |
| `terminal-child-focused1` | DB1/unit204, 실패·오류·skip0 | S예약·결과·완료3 실제 child commit-before-ACK 중단/같은 job 저장 lease 뒤 재시작 |
| `request-money-focused2` | DB3 중 D02통과·D08 준비오류2, unit204통과 | DIRECT/AUTO/다른 RETURN 실제 경합의 D02 메서드만 수용; 묶음 전체 성공 아님 |
| `money-focused3` | DB3/unit204, 실패·오류·skip0 | 원 ordinal·상품 정책 변경 무영향·누적 차액/미수납 및 무료 S 직접 검사 |
| `v57-read-green5` | DB3/unit204, 실패·오류·skip0 | V57 실제 제약 적재→18이행·현재 C 처리/물리승격0, 초기 조회 및 S0 projection |

[SQL36 원문](../../.local-tools/p09-d-terminal-sql-focused1-20261102/db/return-terminal-actual-sql-stages.json)은 ready/unit, 양쪽 shipping decision/current, 의무·예산·attempt·binding, S 결과/order receipt, 최종 hold/unit/개인완료/coupon/closure/parent/job/Outbox 지점을 포함한다. 지정 실제 handler와 해당 type의 job/Outbox에 실패를 주입하고 정상 재개 뒤 중복 호출 snapshot 불변을 assert한다. 금융2/submit2로 원합33001·입고3·완료3·coupon1/CLOSED다. 기존 금융 child의 ready1/정확 unit 집합 assertion도 보강됐다. [새 child3 원문](../../.local-tools/p09-d-terminal-child-focused1-20261102/db/return-terminal-actual-child-stages.json)과 코드에서 자기 process 중단·저장 lease+1µs 재시작·attempts2/SUCCEEDED·job 이외 업무/Outbox byte 동일을 확인했다. 이전 financial child를 이 새3경계로 소급하지 않는다.

D02는 두 실제 연결의 parent blocking chain/Future pending 뒤 단일 원단위 승자·겹친 hold/confirmation0·다른 ordinal OPEN을 확인한다. 적법 반품이 남은 경우 기한 뒤 반려와 재생 불변, generation2 자동확정을 검증한다. 다른 RETURN 경합은 반드시 한 반품이 남는 경로다. D08은 원33001/26004의10000·10000·10001/8001·8001·8002와 상품 fee9000 변경에도 저장3000/후속차액0·원JSON 불변을 검사한다. 별도2000/1000 상품은 누적 fee3000→5000/후속차액2000·각 due1000·기존 미수납 상계0·DUE_PENDING/ready0/종결0이다.0원도 실제 submit→내부 ZERO-REFUND→collector→D 결과를 지나며 외부 provider만0이다.

D07은 현재 C 공개 포트가 만든 별도 source graph를 실제 V57 column/FK/deferred guard를 유지한 새 target에 적재한다. 원 사업값·금액·시각·정책/7일·JSON을 대조하고 DB-local created_xid만 target transaction default를 사용한다.18 migrations→V75·재이행0·사업값 불변 뒤 실제 current submit/collector/common fee apply/provider1 및 recovery3stage에서 물리 application/회수/검수/입고/동의/ready/완료/delivery0을 확인한다. **이전 바이너리를 실행한 호환성 검사는 아니다.** FK/trigger를 끈 적재나 C 금융 intent를 D 물리 반품으로 승격한 검사가 아니다.

### 남은 완료 조건과 보존

실제 D06 PC/mobile 브라우저 연결, 기한 후 GAS/legacy 직접 통합 매핑, 나머지 제약 assertion 감사, migration 수·dispatcher 기존 정확 집합 회귀의 실제 실행, 선택 없는 개발·지정 독립 전체, 최종 증거 보존 감사와 인계는 아직 남는다. browser130·전체287초/개별30초/catalog60초·worker1/retry0은 이번 판정으로 변경하지 않는다. baseline 필수 Java485→575/frontend256→275/browser130→130 누락0은 **이름 보존** 근거일 뿐 전체 성공이 아니다. C 공통 결과 보존 이전 복구 gap과 실제 Toss/사용자 직접 확인 미검증은 유지한다.

실제 read405/정상 S조회409·모순 allowedAction/GAS pending private view·잘못된 origin 분기 RED와 후속 GREEN을 구분한다. GAS fixture clock/idle 기대,200/202 기대, coupon 없는 fixture, 식별자 길이, TypeScript matcher, 실패한 편집 뒤 unchanged 실행은 준비 실패로 남기며 제품 수정 증거로 부풀리지 않는다. 기존 증거·실패·자원·migration을 변경하지 않았다. 이번에는 이 리뷰 문서만 갱신했고 제품/검사/하네스 실행·커밋·푸시·배포·E를 수행하지 않았다.

## §17 D06 연결·전체 브라우저 용량 판단 — 2026-11-02

**판정: 신규 필수 지적0. aggregate287→336초 변경을 허용하며, 그 변경을 포함한 최종 소스 고정 후 선택 없는 개발 전체→동일 소스 지정 독립 전체로 진행 가능하다. D 전체 완료 판정은 아니다.** §16 수용과 R-D01/R-D02 ADDRESSED, V75 수용은 유지한다. 이 검토에서는 새 하네스를 실행하거나 용량 설정을 직접 수정하지 않았다.

### 고정 입력·실제 결과

`.local-tools/p09-d-execution-20261101/browser-final-review1/source`만 제품/검사 검토 대상으로 삼았다. runtime622 SHA256 `4F5C5C2F15C79A4EA239B6899F2DCBBFCC7920A8FBBB04FF050F63DA0E732D70`, artifact627·runtime622 재해시 불일치0, 생성04:09:23, 부모 HEAD `80ad8cd4d194766a4e09e7e49c5ecce1b46bc506`이다. §16 고정본 이후 제품 변경은 ReturnStore의 명령 단위 필드 구성이다. 나머지 runtime 차이는 테스트·fixture·필수 목록이며 제품 lease guard·서버 strict body·migration은 그대로다.

주 근거 `.local-tools/p09-d-browser-focused4-20261102`의 browser-report 원문을 재집계해16passed/실패0/skip0/retry0·본문 합48428ms·최장6134ms를 확인했다. child timing은50675ms/exit0, report 자체 duration은50231.4ms로 서로 다른 측정 경계를 구분한다. unit204/DB browser hook1도 실패·오류·skip0이다. **outer exit1/root failed는 기존 필수 미선택 때문이며 preservation passed**다. 기존130을 포함한146 전체 성공으로 확대하지 않는다.

### D06 실제 화면·준비 도구와 명령 수정

`auth-e2e/order-return.spec.ts`의8제목×desktop/mobile16과 `ReturnBrowserFixture`, seed 및 실제 job helper를 읽었다. 신규 context는 별도 cookie 저장소를 사용하고 fresh GAS는 실제127.0.0.7 bind HTTP의 CSRF/login 응답 cookie로 인증한다. DB quota 제거·source header 위조가 아니다. 새 login form 자체는 기존 회귀 범위다. 수동 `browser.newContext({baseURL})`가 project mobile 설정을 잃는지 추가로 확인했다. 설치된 Playwright runner `runBeforeCreateBrowserContext`는 빠진 옵션에 `_combinedContextOptions`를 주입하며 이 호출은 viewport/isMobile 등을 덮어쓰지 않는다. 고정 config의 Desktop Chrome/Pixel7, worker1/retry0/개별30초를 유지한다.

fixture는 test source의 `@Profile("c3-fixture")` controller다. 기본 제품 context에서 두 fixture bean 부재를 검사하고, database 관측은 현재 owner를 앞뒤로 확인한다. prepare는 실제 Returns/Collections/Inspections/Finances 공개 포트를 호출한다. mock 설정만 DEMO 제공자 행동을 지정하며 성공 receipt를 직접 만드는 SQL이 아니다. work는 원 주문에 묶인 실제 attempt/intent·저장 READY job을 골라 JdbcJobs→WorkerEngine→실제 handler/final fence/ACK를 실행하고 SUCCEEDED를 요구한다.32회 bound는 소진 시 실패한다. 별도 fixture 검사에서 현재 GAS 관측200/타주문·익명404/CSRF403·실제 CLOSED/입고/provider 효과와 원금 불변을 확인했다. 이는 test-only 업무 준비 권한이며 제품 공개 권한으로 해석하지 않는다.

| 실제 브라우저 제목의 핵심 범위 | 검토한 끝 assertion |
| --- | --- |
| fresh GAS native 반품 | native 신청202→reload 재발견→별도 seller 승인/실회수·SALEABLE1/DEFECTIVE1 검수→buyer 명시 동의→실 ITEM 후 PAID/S대기→실 S 후 CLOSED, provider2·각1/입고1·원금/COMMITTED 불변 |
| LOST·불량/미수납 | 실제 LOST 표기·견적 동작 부재, 별도 BUYER 불량의 fee5000/refund0/due4000·내부 ZERO 결과, ACTIVE hold/DUE/입고·coupon·S·완료0/PAID |
| 응답 유실·경쟁 동의409 | 실제 POST commit 응답만 버리고 double click→동일 key/body 재전송·hold1, 실제 다른 consent 뒤409+GET503에서 체크/quote/pending 제거·자동 POST0 |
| 실제 GAS logout·늦은 조회 | 실제 private GET200을 보류→실 logout/focus→응답 release, 개인 화면·draft·명령0/새 GET404·업무 관측 불변 |
| actor·keyboard·terminal | 본인 철회/자기 seller 승인·반려, 승인 뒤 철회409·타seller404/private0·업무 불변, Space/Enter 실제 신청202, terminal 이력·375/768/1440 overflow0 |
| ITEM SUPPORT | 별도 실제 주문 FAILED 새sequence2/UNKNOWN 같은 attempt query, 현재 revision·원 consent/재고 유지·실 worker/provider효과1·PAID |
| 다른 RETURN view의 공통 S | 실제 원 origin/독립 revision, FAILED retry→LOST_RESPONSE query·동일 attempt, S1/coupon1/CLOSED·원 개인 기록 불변·성공 후 새retry409/업무0 |
| physical RETURN0인 순수 C S | 기존 C claim revision/route, 실제 지원 commit 응답유실·samekey/body 재생, 실 S/CANCELED/coupon1·원 C receipt 불변, SUPPORT logout/late GET private0 |

이들 native 명령과 실제 API/DB assertion은 §16의 DOM mock 한계를 보완한다. 일부 시나리오의 중간 준비는 fixture 공개 포트이며 모든 사전 상태를 화면 조작으로 만들었다고 주장하지 않는다. 실기기·사용자 직접 확인·실제 Toss 결과도 아니다.

실제 wire400의 원인은 eligibility DTO를 spread해 허용3필드 밖의 값을 보낸 것이었다. 현재 REQUEST는 itemId/ordinal/responsibility, RECEIVE는 itemId/ordinal/status, INSPECT는 itemId/ordinal/condition/responsibility만 구성한다. 정렬·pending key/body·409 폐기 계약을 유지하고 서버 검증을 완화하지 않는다. 실제 source projection을 넣는 신규3 단위 assertion과 `wire-green1` 전체278 JUnit failures/errors0, 필수 누락/skip0, typecheck/lint/build/unit exit0을 확인했다. 같은 묶음 Java 준비 compile1은 그대로 실패이며 뒤 compile 성공과 구분한다. §16에서 발견하지 못했던 실제 native wire 결함을 이번 성공으로 소급해 없애지 않는다.

### 추가 제약 연결과 실패 이력

`existing-upgrade-red1`의 기존 migration 종료값과 dispatcher exact set 차이는 현재 V75/신규4 handler와 일치한다. 고정본은 종료 기대를 정확히18 이행만큼 갱신하고 기존18+신규4 집합을 유지한다. 기존 업무값 보존/원자성 assertion을 제거한 통과가 아니다. `existing-upgrade-green1`은 DB8 중7통과/legacy child1실패이며 묶음 전체 성공은 아니다.

그 실행의 `acceptedGuestReturnAfterDeadlineUsesFreshCurrentGasThroughInspectionConsentAndSettlement`는 원 stored due+1µs 제어 clock, 옛 GAS max 만료·실제 새 GAS 인증, 새 반품409/participant113 불변과 적법 옛 신청의 후기한 승인/회수/DEFECTIVE SELLER/새 자격 동의/실제 환불·개인완료/provider1·입고0·parentPAID를 연결한다. 자연7일 경과나 옛 receipt timestamp 수정을 주장하지 않는다.

`legacy-physical-focused3`의 DB1/unit204 실패·오류·skip0과 확장 probe를 대조했다. 실제 V36 원JSON에 returnPolicy가 없는 주문을 V75까지 이행하고, ordinal0의 기존 C 금융/ACTIVE hold는 물리승격0·bytes 불변으로 유지한다. 다른 ordinal1만 실제 D 흐름→legacy 미동의409/44 participant 불변→명시 양쪽 동의·재생0→실 worker/개인완료를 확인한다. 원JSON/원금/7일/UNRESOLVED·새 상품 정책과 legacy 견적의 구분을 보존한다. 복사 target의 실제 pg sequence metadata로 copied table sequence만 정렬하고 원 event bytes 불변·새3event>copied max를 assert한다. 원 DB/제품 migration 변경 또는 구바이너리 실행이 아니다.

`final-constraints-focused2` DB4/unit204 실패·오류·skip0에는 위 fixture/비제품 검사와 다중 seller·혼합책임 검사가 포함된다. `differentSellerPhysicalReturnsKeepParentOpenThroughUnknownThenCloseOnlyAfterBothShippingObligations`는 첫 seller 완료 효과 불변, 둘째 UNKNOWN/ACTIVE/ready0·parentPAID, 같은 attempt query 후에도 둘째 S 전 PAID, 두 S 뒤 CLOSED/closure1/coupon2·전액과 replay0/lateAPPROVED0을 직접 assert한다. `mixedInspectedResponsibilitiesRetainOriginalShippingAndRestoreExpiredCouponWithoutReusability`는 실제 SELLER2+BUYER1 검수·net30001/fee3000/refund27001·원S3000유지/Sattempt0·입고3, 원 coupon endsAt+1µs에서 복구1/RESTORED_EXPIRED/재사용false·원 만료시각 불변을 확인한다. 서로 다른 실행 입력의 증거이며 현622 전체 재실행으로 합산하지 않는다. 모두 root 필수 미선택 failed/preservation passed다.

browser-focused1의 같은 bucket 예약409/browser0, focused2의 selector 준비 실패·실제 wire400·pure C helper500(4pass/12fail, child266560ms), focused3의14pass/2 helper JOB_LEASE_LOST를 보존한다. focused2는287초 aggregate timeout이 아니다. helper 보완은 claimClock=max(stored nextRunAt+1초, 실제 관측 현재시각)이다. 새 focused4의54개 actual timing·최장 원 job age66.606초와 실제 최종 fence/ACK 성공을 확인했다. 옛 실패의 leaseUntil을 관측하지 않았으므로 과거 lease 수치를 소급하지 않는다. 제품 lease/timeout을 완화하지 않았고 실패 시간은 용량 계산에서 제외한다.

### 명시적 용량 판정과 전체 실행 조건

**aggregate만287→336초 허용**한다. 신규 성공16의48428ms는 report 결과에서 직접 합산됐고 child50675ms와 차이2247ms는 startup/간격이다. 기존287000ms 예산의 startup/여유를 한 번 유지하고 신규 순수 본문만 더하면335428ms, 초 단위 올림336초다. 기존 C130 independent child272388ms는 다른 소스이므로 단순 결합320816ms는 참고 추정일 뿐이다. 개수 비례323초만으로 판단하지 않았으며 성능 개선을 주장하지 않는다.

이 변경은 실패 은폐를 위한 단독 timeout 확장이 아니라 신규 필수16의 성공 실측에 따른 전체 용량 배정이다. 개별30초/catalog60초·worker1·retry0·기존130+새16필수·실패/skip/미수집 거부는 유지해야 한다. 현재 고정본은 여전히287초이므로 Sol이336초 반영 후 새 최종 입력을 고정해야 한다. 같은 소스146 전체의 실제 시간은 아직 없으며 개발 whole에서 확인하고 같은 입력의 지정 독립 whole로 이어간다. 추가 실패/시간초과가 생기면 원본과 원인을 기록하며 이번 판단을 재확대 자동 승인으로 사용하지 않는다.

HEAD와 고정본의 필수 leaf-name을 직접 대조해 누락0을 확인했다. Java unique485→579(현 entries580, 기존 동일 이름 중복1 보존), frontend entries256→278/unique255→277(기존 WorkspaceAccess 동일 이름 중복1 보존), browser130→146이다. frontend278 실제 사례 실행과 필수 항목 수를 unique 제약278로 혼동하지 않는다. 수집 개수는 제약 coverage 완료를 대신하지 않는다.

새 선택 없는 개발·같은 소스 지정 독립 전체, 마지막 제약 매핑/보존 감사·인계가 남는다. 조건부 운영 문서 정비는 실제 D 완료 뒤 별도 범위로 유지한다. 이번에는 이 리뷰 문서만 수정했으며 기존 자원·실패 증거를 보존했다. B/C 마감 재개·제품/검사 변경·하네스 실행·커밋·푸시·배포·E는 수행하지 않았다.

## §18 최종 동일 소스 독립 전체 — 2026-11-02

### 필수 프런트 계수 정정과 실행 착수

§17의 frontend unique277/기존 WorkspaceAccess 중복1 표기는 잘못된 비교 결과다. PowerShell 기본 문자열 집계가 대소문자가 다른 `/admin/users`와 `/ADMIN/users`를 합쳤다. 정확한 ordinal 문자열 비교 결과 현재 raw278/exact unique278/중복0이며, `/admin/users/` 역시 별도의 실제 parameterized 경로다. 검사·필수 목록을 삭제하거나 바꾸지 않고 이 리뷰의 계수 표현을 정정한다.278은 사례 수이지 모든 제약 충족 자체의 증명이 아니다.

개발 `.local-tools/p09-d-dev1-20261102` 실제 root passed/preservation passed·04:14:53~04:49:09를 직접 확인한 뒤 독립 실행을 착수했다. `.local-tools/p09-d-execution-20261101/dev1-full-review1/source`의 runtime622 입력 SHA256 `2067955001DDF882857DC2CE1301080D1EBDFBD332740A131DC18C939740951F`와 현재 workspace622 불일치0·artifact627 불일치0·HEAD `80ad8cd4d194766a4e09e7e49c5ecce1b46bc506`를 실행 전에 확인했다. §17 이후 변경은 승인된 브라우저 aggregate287→336초뿐이다. 고정 D.md의 당시 RUNNING 문구는 복사 시점 이력으로 보존하고 실제 root 결과와 구분한다.

04:51:35 preflight에서 새 `.local-tools/p09-d-independent1-20261102` 경로 부재를 확인하고 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-d-independent1-20261102`를 실행했다. MAVEN_ARGS/MAVEN_OPTS/P09_C_BROWSER_ONLY/P09_D_BROWSER_ONLY/P09_B_BROWSER_DIAGNOSTICS/P09_B_DIAGNOSTIC_REPORTER를 process에서 비웠고 JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS도 unset임을 비밀값 없이 기록했다. 기존 local JDK21.0.12.1+1·offline 도구/캐시와15433 PostgreSQL을 사용하며 root가 자기 새 emulator/DB/media를 준비한다. 개발 wrapper의 hardcoded 경로를 재실행하지 않았다. 최종 결과는 아래 실제 종료 기록으로만 판정하며 이 착수 기록 자체는 통과가 아니다.

### 실제 종료·독립 판정

**독립 PASS: 신규 필수 문제0. 승인된 로컬·DEMO D 구현의 제약/assertion과 같은 소스 전체 검증을 수용한다.** root 시작04:51:40.1730648~종료05:25:48.6525783 KST, 실제 outer05:25:49.2496266/exit0, `result.status=passed`·`preservation=passed`를 직접 확인했다. 실행 후 inputs622 해시가 개발과 같고 현재 runtime 해시 불일치0이다. §17 승인된336초 외 추가 timeout 변경이나 선택·생략 없이 실행했다. 앞 정적/집중 검토를 이 전체 실행으로 소급 기록하지 않는다.

| 검사 | 이번 독립 실제 결과 | 원 근거 |
| --- | --- | --- |
| JUnit·ArchUnit |204, failures/errors/skipped0 | db/build/surefire-reports의41 XML |
| PostgreSQL·전화 emulator |PG442+Phone1, failures/errors/skipped0 | db/build/failsafe-reports의2 XML; 합계 Java647 |
| 실제 PC/mobile browser |146/146 passed, fail/skip/retry0; 신규 D06 16 포함 | db/browser-report.json·browser.txt |
| 브라우저 실제 child |319543ms/exit0≤336000ms | db/browser-child-timing.ndjson; report 자체 duration319128.919ms와 구분 |
| 프런트 |278/278, failures/errors/skipped0·타입/린트/빌드 성공 | TEST-frontend.xml·frontend-coverage.json·각 로그 |
| Node |75pass, fail/cancelled/skipped/todo0 | test.txt |
| 필수 수집 |Java54+frontend1+browser1=56묶음 전부 passed | coverage.json·frontend-coverage.json·browser-coverage.json |
| 소스·기존 자원 |inputs622 동일·현재 불일치0, preservation passed | inputs.json·before.json·after.json·result.json |

결과 경로는 [독립 root](../../.local-tools/p09-d-independent1-20261102/result.json), [outer 종료](../../.local-tools/p09-d-execution-20261101/independent1-outer-exit.json), [독립 요약](../../.local-tools/p09-d-execution-20261101/independent1-full-outcome.json)이다. 새 primary DB는 `d2_it_af41113c8f9b48aab6f5bd176d00cf1a`, 주 media는 `.local-tools/p09-d-independent1-20261102/db/media`이며 이행/probe용 추가 새 DB·각 하위 media도 보존했다. 정확한 DB 생성 기록은 `db/target.txt`와 각 probe 결과를 따른다. 자기 emulator는 `.local-tools/phone-emulator-1923e4eaf4c44214a01cab1b636dabd4`, result exit0·동일 evidence 경로다. 기존 emulator/server를 중지하지 않았다.

### 제약별 최종 실행 연결

[independent1-constraint-outcomes.json](../../.local-tools/p09-d-execution-20261101/independent1-constraint-outcomes.json)에 개발 최종 인덱스의 D01~D08과 이번 원 Java XML의 실제 사례를 연결했다. 인덱스의44개 메서드 연결은 모두 이번 실행에서 passed이며, D06은 실제 browser146/frontend278의 별도 원 결과로 확인했다. 인덱스의 예전 RUNNING/기존130 대기 문구는 생성 시점 설명으로 남기고 actual root/사례 결과로 현재 판정했다. 기존 §1~17 assertion 검토와 다음 실제 실행을 함께 근거로 삼으며 개수만으로 요구사항 완료를 판단하지 않는다.

| 제약 | 확인한 실제 assertion과 이번 전체 포함 여부 |
| --- | --- |
| D01 기한/접수 후 진행 |원 최초 배송+7일 µs 경계·최종 잠금 뒤 신규 신청 거부, 기한내 원 접수의 후기한 진행. `acceptedGuestReturnAfterDeadlineUsesFreshCurrentGasThroughInspectionConsentAndSettlement`의 옛 GAS 만료→실제 새 GAS→새 신청409/기존 접수 검수·동의·실 환불 포함 |
| D02 원단위·경합·명령 |DIRECT/AUTO/다른 RETURN 실제 잠금 경합·단일 승자/다른 ordinal 보존, canonical 중복·같은 key/body 재생/변경 거부, 승인·반려 경합 및 해제 후 due generation2 자동확정 포함 |
| D03 실제 회수·입고 |원단위 수령/분실·회복, SALEABLE만1회 입고·DEFECTIVE0, 허구 검수/입고 누락/실 stock effect 없는 graph 거부, 원 COMMITTED 보존 포함 |
| D04 S·쿠폰·종결 |C/D/B 마지막 사실 양순서, seller별 원S1·개인 완료 불변, 다중 seller UNKNOWN/둘째 S 전 parentPAID→모두 완료 뒤 CLOSED/coupon2, 실제 SELLER2+BUYER1 검수의 S유지·만료쿠폰 restore1/reusable=false 포함 |
| D05 권한·원자성·복구 |HTTP CSRF/현재 actor·최종 실제 잠금 뒤 권한 재검사, SQL 단계 rollback·정확 job/final lease, 실제 child commit-before-ACK 재시작, recovery 후보/cursor/page/고정상한·위조/동시소비·옛 결과/최신지원 역전0 포함 |
| D06 화면/현재 자격 |native GAS→seller physical→명시 동의→실 worker/DB, LOST/DUE, 실제 commit 응답 유실/same key body, stale409+GET실패, logout/lateGET 폐기, keyboard·PC/mobile, ITEM 및 actual-origin RETURN/CANCEL S 지원 신규16과 기존130 포함. wire 허용필드3검사·StrictMode/BigInt/legacy 별도동의 등 frontend278 포함 |
| D07 경계·구형 이행 |실제 ArchUnit과 금지 fixture·직접 JDBC 소유권, V57 C 금융 graph→V75/current C 처리·물리승격0, true V36 missing-policy JSON→V75/다른 ordinal 실제 D legacy 동의·원 C hold/원JSON/금액/7일 불변 포함. 종료 migration count·dispatcher exact set 기존 회귀도 성공 |
| D08 원금·회수비 |33001/26004 ordinal 잔여1원·상품 정책 변경에도 저장 fee 보존, 원배송 누적max/후속 차액·기존 미수납 미상계/DUE 종결0, 원S0/provider0, 실제 최종 검수 quote·현재/legacy 명시 동의·ITEM/S 분리 포함 |

R-D01/R-D02의 공개 포트 및 실제 scoped JDBC 보완, V75 candidate omission 방어, §15.6 actual origin 지원, §17 native wire 보완의 수용을 유지한다. 새 독립 전체에서 필수 실패·skip·누락은 없으며 별도 미해결 필수 지적을 발견하지 않았다. 자연7일 실제 대기·이전 바이너리 실행·모든 조합 전수 검사라고 범위를 넓히지 않는다. 현재 시각을 제어한 실제 저장 deadline/lease 경계와 제약을 유지한 구형 graph 적재의 근거 한계는 앞 절을 따른다.

### 보존 감사·남은 인계 범위

기록된 before/after를 동일 보존 규칙으로 다시 대조해 passed를 확인했다. 이전 내용 해시 대상3개, 기존 test DB 목록656개·dev DB 목록5개, 알려진 컨테이너/리스너, 기존 media2956파일이 규칙 범위에서 보존됐다. 과거 검증 DB는 존재 목록 검사이며 전체 행 전수 해시가 아니다. 프로젝트 밖 임의 자원 전체 보존을 주장하지 않는다.

이번 새 root1568파일과 emulator3파일은 각각 `independent1-manifest.json`/`independent1-emulator-manifest.json`으로 해시 목록을 만들고 재대조 불일치0을 확인했다. 기존 집중 실패·준비 실패·고정본을 삭제하거나 수정하지 않았다. 기존 DB/미디어·원 주문/정책·적용 migration 보존과 원 실패 이력은 유지한다. 실제 RED→GREEN과 selector/fixture/compile/clock 준비 오류의 구분은 §16~17 및 작업 기록을 따른다.

**부모의 최종 C+D 전체 증거 보존 감사와 인계 마감은 아직 별도 후속이다.** 이번 독립 PASS를 그 광범위 감사 완료나 로컬 커밋 완료로 해석하지 않는다. 실제 Toss SANDBOX는 기존 외부 차단, 사용자 직접 확인·실기기는 미검증이다. 미수납 수납/면제·분실 금전처분·E 이후는 현재 구현/검증 범위 밖이다. 조건부 운영 문서 정비는 실제 D 완료 뒤 별도 요청 범위로 유지했다.

이번 세션은 승인된 새 독립 전체 실행과 이 리뷰·해당 새 증거 기록만 작성했다. runtime/test/required/harness는 변경하지 않았고 커밋·푸시·배포·B/C 마감 재개·E 구현을 수행하지 않았다.
