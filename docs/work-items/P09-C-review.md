# P09-C 중간 정적 검토

**최신 판정:** 아래 최초 지적은 이력으로 유지한다. 마지막 절의 `core-fix-review1` 한정 재검토에서 R-C01/R-C02를 모두 **ADDRESSED**로 판정했다. C 전체/후속 common fee/최종 독립 전체 검증 승인은 아니다.

**후속 common-fee-review1 판정:** R-C01/R-C02 ADDRESSED 유지. 공통 회수비 중간 검토에서 R-C03/R-C04(P2) 수정 필요를 추가했다. 아래 마지막 절을 따른다.

**최신 common-fee-fix-review1 판정:** R-C03/R-C04도 **ADDRESSED**. 네 지적의 해결은 각 고정 수정 패키지와 검증 범위에 한정하며, C 전체/동시 추가된 GET·철회/최종 독립 전체 승인은 아니다.

## 활성 범위 — 2026-10-31 KST

부모가 요청한 C2/C3 취소 core의 **불변 artifact 검토**다. 최종 독립 전체 검토가 아니며 현재 변경 중인 live 제품은 판정 대상이 아니다. 본 세션은 이 문서만 작성했다. 제품/테스트/하네스 수정, 검사 실행, 새 agent, 커밋·푸시·배포·D 진행은 하지 않았다.

대상: `.local-tools/p09-c-execution-20261030/core-review1/review.patch`와 `source/`25파일. 기준 HEAD `c9f924123b42e8d413f3ff70a095ffec06fd9593`. 패치 SHA256 `503A8088865BDF2ABC3DB878CF35BC5D223D81D7C5813CBF575995A3B28E3D20`. manifest의25파일 SHA 대조는 불일치0이다. 패치를 먼저 읽고 출력에서 잘린 핵심 함수 및 위험 경로는 snapshot source로 확인했다. 아래 줄번호는 모두 해당 **source 사본** 기준이다. 이후 live 수정에 자동 적용되는 판정이 아니다.

설계 기준은 `P09-C-design-review.md` H04/H06 및 §11이다. 상품 회수비 코드는 이번 core 완료 범위가 아니며 테스트 파일의 fee RED 준비를 상품 기능 완료로 평가하지 않는다.

## 판정

**구현된 core에서 수정 필요2건: R-C01(P1), R-C02(P2).** 외부 호출 분리·원단위 hold·부모 한도·후속 입고·배송비 별도 leg·종결 출처 보호의 구조는 설계와 대체로 일치하지만, 현재 artifact를 실패/재개까지 안전한 core로 승인할 수 없다. 아래 결함은 정적 실행 경로로 확인했으며 새 재현 검사는 실행하지 않았다.

### R-C01 — P1: 제출 기록 부재를 확정 실패로 처리하면 늦은 worker의 환불이 뒤늦게 실행될 수 있음

위치: `source/backend/src/main/java/com/example/marketplace/payment/infrastructure/MockRefundProvider.java:29`, 같은 파일22행; `JdbcRefunds.java:61`~64행.

`JdbcRefunds.process`는 PROCESSING/token을 commit한 다음 `after("PREPARED")` 뒤 실제 submit을 한다. takeover worker는 PROCESSING을 보고 query한다. MockRefundProvider.query는 provider row가 아직 없으면 아무 부재 확정 영수증/차단도 저장하지 않고 FAILED를 반환한다. 반면 submit은 원 attempt가 FAILED인지와 무관하게 provider row를 생성해 성공 효과를 낼 수 있다.

구체 순서:

1. worker A가 T2를 commit하고 provider.submit 직전 중단된다.
2. A lease 만료 후 B가 같은 작업을 인수해 query한다. provider row 없음→FAILED, T3가 금액예약을 해제한다.
3. A가 재개해 submit한다. provider에는 아직 row가 없으므로 SUCCEEDED/effects1을 저장한다.
4. A의 T3는 오래된 lease/token 때문에 거부되지만 **외부 효과는 이미 발생**했다. 로컬 attempt는 불변 FAILED이며 성공액0/예약0이라 실제 환불과 불일치한다. 후속 철회/새 시도가 연결되면 출고 허용 또는 중복 환불로 확대된다.

현재 T3 fencing은 로컬 쓰기만 차단하므로 이 위험을 해결하지 못한다. 부재는 후발 제출이 영구 차단된 경우에만 definitive failure다. 권장 보완: provider 부재 query는 UNKNOWN을 반환해 예약을 유지하거나, DEMO에서 query/submit을 같은 영구 idempotency key로 직렬화하고 '확정 미실행' tombstone을 원자 저장해 이후 submit도 동일 FAILED를 반환하도록 한다. 직전 lease 확인 한 번만 추가하면 확인과 호출 사이 경쟁이 남는다.

필수 증거(C06/C10): PREPARED barrier에서 A를 정지→실제 lease 만료/takeover→B query/T3→A 재개 순서를 재현한다. 허용 결과는 reservation 유지 UNKNOWN 후 같은 attempt 수렴, 또는 영구 no-effect FAILED와 provider effects0이다. **FAILED/reserved0/provider effects1은 금지**한다. 재시도/철회 연결 후에도 중복 환불/출고 충돌0을 확인한다. 이 검토의 재현 상태는 NOT RUN.

### R-C02 — P2: FAILED/UNKNOWN 결과의 업무 전이가 이력 없이 반복 적용됨

위치: `source/backend/src/main/java/com/example/marketplace/claim/infrastructure/JdbcClaims.java:74`~75행.

성공 결과는 `claim_refund_effect`로 중복을 막지만 FAILED/UNKNOWN은 receipt 없이 매번 `state='REVIEW_REQUIRED',version=version+1`을 실행하고 반환한다. 이 분기에 claim_event/Outbox 기록도 없다. worker가 이 거래를 commit한 뒤 ACK 전에 죽어 같은 결과 job을 재처리하면 동일 payment 결과가 claim revision을 다시 증가시킨다. 정상 최초 실패조차 claim 업무 이력에서 REVIEW_REQUIRED 전이를 확인할 수 없다.

이는 아직 계획된 SUPPORT 화면 부재와 별개의 **이미 구현된 결과 처리 분기 결함**이다. 결과 식별자/실제 적용 version에 대한 처리 영수증을 두고 최초 적용 시 claim state/version·이력·Outbox를 원자 저장해야 한다. 같은 결과 재생은 무효과, UNKNOWN 이후 더 최신 SUCCEEDED는 정상 완료로 진행되어야 한다. 최종 lease 확인은 이력/Outbox 포함 마지막 대기 뒤 수행한다.

필수 증거(C06/C10): FAILED와 UNKNOWN 각각 최초 적용1·이력1·동일 job 재생 revision 불변·Outbox1, commit-before-ACK 재처리도 동일. 이력/Outbox 실패 주입 시 상태까지rollback. 이후 최신 성공 처리로 수렴하되 옛 UNKNOWN 재생이 상태를 되돌리지 않는지 확인한다. 이 검토의 재현 상태는 NOT RUN.

## 설계 준수 및 코드 품질 대조

| core 경로 | artifact에서 확인한 사항 / 한계 |
| --- | --- |
| quote/T1 | order 소유 원 net/배송비를 읽고 단일 seller·OPEN·미출고·revision 검사. HTTP 필드 수/형식을 제한해 full/amount 등을 받지 않음. 기존 key 영수증은 현재 권한 확인 후 재생. hold/claim/refund 예약/이력/Outbox는 하나의 TransactionTemplate에 참여. 마지막 refund 대기 후 현재 자격을 재검사한다. 실제 자연만료/경합 증거는 후속 필요. |
| 부모 한도/귀속 | order→budget→attempt 잠금과 조건부 reserved 증가, paid/reserved/succeeded CHECK 및 deferred 합계 대조가 있다. attempt immutable trigger와 seller/order FK가 있다. 이미 계획된 claim/attempt/원단위 DB 귀속 강화와 direct-SQL 위반 검사는 아직 완료로 판단하지 않는다. |
| T2/외부/T3 | 외부 거래 금지 검사와 T2 commit→provider→T3 구조, attempt/amount/result reference 검증, token/lease 확인, 결과·budget·이력·Outbox의 동일 거래가 있다. R-C01로 definitive FAILED 안전성은 차단이다. |
| T4/재고 | 공개 Refunds.get의 성공/금액/귀속을 확인하고 B completeRefund→inventory 후속 입고→효과영수증을 같은 거래로 적용. 원 COMMITTED 예약은 유지. restitution의 원 reservation+ordinal 고유, COMMITTED/범위 검사, balance→reservation 잠금이 있다. 모든 단계 실패 원자성/동시 원인 충돌은 후속 검사 필요. |
| SHIPPING | 마지막 성공 원단위 이후 고유 seller obligation/별도 attempt를 생성한다. SHIPPING 성공 후에만 coupon restore와 배송비 settlement를 기록한다. 일부 취소의 쿠폰 복구0과 누적 전체 완료를 분리한다. SHIPPING 실패/UNKNOWN/retry 시나리오는 후속 범위. |
| H06 | B 직접/자동 확정 공통 write가 order 소유 evaluator를 호출한다. 모든 원단위 terminal+취소근거 및 전체취소 seller의 배송비근거를 확인하고 allcancel=CANCELED/mixed=CLOSED. PaymentCoordinator는 원 성공+보상NONE+고객취소 종결 증거로 재통지를 무효과 처리한다. 혼합 완료 양쪽 순서의 실제 증거는 아직 필요. |
| 모듈/품질 | 신규 core는 다른 모듈의 api를 사용하고 payment→claim 호출은 없다. `JdbcOrderConfirmations`에서 동일 order 모듈의 evaluator를 직접 new하는 것은 현재 모듈 위반은 아니나 추후 생성 의존 변경에 취약하므로 공통 주입으로 정리하면 좋다. 긴 단일행 결과 함수는 단계별 실패 지점/원자성 검토가 어려워 private 메서드 분리를 권장하되 별도 차단으로 세지 않는다. ArchUnit 전체 실행 판정은 하지 않았다. |

## 실제 실행 증거의 범위

아래는 부모가 실행한 보존된 XML/result.json을 읽어 확인한 결과이며 본 검토가 실행한 검사가 아니다.

| 경로 | 확인한 실제 결과 |
| --- | --- |
| `.local-tools/p09-c-cancellation-focused1-20261031` | DB Failsafe2, 실패0/오류0/skip0. `cancellationQuoteUsesOriginalUnitsAndShipping`, `cancellationReservesBeforeRefundAndRestoresOriginalUnitOnce`. root status=failed, preservation=passed, 오류는 필수검사 `accountHttpOwnerCsrfValidationAndPrivacyBoundaries` 미수집. |
| `.local-tools/p09-c-shipping-focused1-20261031` | DB Failsafe5, 실패0/오류0/skip0. 위2개+`fullCancellationRefundsOriginalShippingAndClosesOnlyAfterEverySeller`, `closedOrderRetriesDoNotTriggerPaymentCompensation`, `paymentUnknownAndLateSuccessConverge`. root status=failed/preservation=passed. 선택 실행이며 전체 통과 아님. |

각 DB 결과 원문은 해당 경로의 `db/build/failsafe-reports/TEST-com.example.marketplace.dbtest.PostgresJdbcIT.xml`이다. 신규 R-C01/R-C02, 혼합 종결 순서, FAILED/UNKNOWN 후속 명령, 실제 부모 한도 경쟁, 자연 만료, 단계별 rollback, child 종료/lease 경합을 위5개 이름/통과 수로 검증했다고 주장하지 않는다. 실행 당시 source와 artifact 전체의 동등성은 별도 소스 manifest 대조 없이 단정하지 않는다.

각 경로의 Surefire XML38개를 읽은 합계도 각각191검사/실패·오류0/skip0으로 확인했다. 이 단위 검사 통과 역시 선택 실행의 root 실패 또는 신규 core의 미검증 경로를 대체하지 않는다.

## 계획된 후속과 재검토 조건

get/withdraw/SUPPORT 명령, 실패·경합·child 종료 검사, DB 귀속 강화, 상품 fee/프런트는 부모가 명시한 미완성 후속이다. 이번 중간 범위에서 기능 누락을 새 회귀 결함으로 세지 않지만 C 종료 전에 C01~11 및 H03/H04/H06 근거로 반드시 검증해야 한다.

Sol은 R-C01/R-C02의 구체 재현과 보완을 먼저 연결하고 실패 원문을 보존한다. 실행된 V41~44를 수정하지 않고 필요한 schema 보완은 당시 미사용 후속 migration으로 한다. 최종 개발 전체·소스 동결·지정 검토세션 독립 전체는 별도 단계이며 아직 이 검토에서 수행하지 않았다. 기존 DB/미디어/실패 이력은 보존하고 실제 Toss 외부 차단·사용자 직접 확인 미실행은 유지한다. **중간 검토 완료, core 승인 보류, C 전체 미완료**다.

## R-C01/R-C02 수정 한정 재검토 — 2026-10-31 KST

### 불변 대상과 검토 방법

대상은 `.local-tools/p09-c-execution-20261030/core-fix-review1/`의 R 전용6경로 패치 및 contextual source27파일이다. 원 `core-review1`을 기준으로 비교했으며 HEAD는 `c9f924123b42e8d413f3ff70a095ffec06fd9593`이다. 패치를 먼저 읽고 출력에서 잘린 PREPARED 경쟁 검사는 사본의 전체 함수로 확인했다. live 소스는 이번 판단에 사용하지 않았다.

패치 SHA256 `701C5C6D4DDA73CC1B2E31D3666F106188ABA2155FA60B3147FECAE6ADE1EE9E`를 직접 확인했다. manifest의 source27파일 SHA도 다시 대조하여 불일치0이다. focused3의 inputs.json SHA `6EEF259AD3E32DA651F8062F4BAD8CE0B6BD7D3678AE4682B97D681FFBE12AB2`가 identity/fix-report와 일치한다. 이는 정적 검토 묶음이며 전체 실행 가능한 독립 checkout을 제공했다는 뜻은 아니다.

### 지적별 판정

| ID | 판정 | 근거와 한계 |
| --- | --- | --- |
| R-C01 | **ADDRESSED** | MockRefundProvider.query가 provider row 부재를 UNKNOWN으로 반환한다. 늦은 submit 효과가 생겨도 예약/hold가 유지되며 stale T3는 기존 fencing으로 거부된다. 실제 PREPARED→takeover→late submit 경쟁 이후 같은 attempt query로 성공 수렴을 검증했다. UNKNOWN 철회/새 시도409도 focused3에서 확인했다. |
| R-C02 | **ADDRESSED** | V46이 `(attemptId, 실제 현재 resultVersion)` 고유 불변 영수증을 추가한다. 최초 FAILED/UNKNOWN 처리는 state/revision·receipt·이력·Outbox를 같은 거래로 저장하고 마지막 lease를 검사한다. 동일 결과 재생은 no-op이며 실제 ACK 전 child 종료/재시작,6개 rollback 사례 및 최신 성공 뒤 옛 UNKNOWN 재생을 검사했다. |

이 수정으로 새로 발생한 중대 결함은 **검토한 변경 범위에서 발견하지 않았다**. 부재 UNKNOWN은 요청이 실제 제출되지 않은 채 남는 경우 자동 실패로 풀지 않고 보류한다. 이는 설계의 안전한 불명확 상태이며 미구현 운영/query 경로까지 완료됐다는 주장은 아니다.

### 실제 RED → 수정 → GREEN 증거

- `p09-c-review-red2-20261031/db/refund-prepared-race-*/observed.txt` 원문에서 `state=FAILED;reserved=0;providerEffects=1`을 확인했다. 최초 red1의 TARGET_NOT_CLAIMED는 PREPARED 도달 전 준비 실패이므로 R-C01 재현 근거로 사용하지 않는다. red1의 FAILED/UNKNOWN history expected1/actual0은 R-C02 제품 RED로 fix-report가 별도 구분한다.
- `p09-c-review-focused3-20261031/db/refund-prepared-race-928ecef4-0b32-4d89-9a36-7b4755026ca5/observed.txt`는 `state=UNKNOWN;reserved=9999;providerEffects=1`이다. snapshot 검사 함수는 실제 HELD, cancelUnsent409, retryFailed409, attempt수1·예약불변, 같은 attempt query 후 SUCCEEDED/submit_calls1/effects1을 assertion한다. 이 파일은 최종 조회 전의 경쟁 직후 관측치이므로 UNKNOWN을 최종 미수렴 결과로 오해하지 않는다.
- focused2의 `db/refund-review-ack-09088aa0-d7e2-4223-86ce-36fd2dc7683b`와 `refund-review-ack-2d9575cd-2e91-4c18-ad54-aeddf2439f8e`에서 commit-before-ACK marker와 별도 restart-completed marker/PID를 확인했다. 각각61148→49976,20764→50332의 별도 프로세스다. 테스트는 첫 child를 종료하고 lease takeover 재처리 후 claim row/영수증/이력/Outbox가1회임을 검사한다.
- `refundReviewHistoryOutboxAndFinalLeaseRollbackAtomically`는 FAILED/UNKNOWN×HISTORY/OUTBOX/final lease6개 사례를 실행한다. 상태/receipt/event/후속 job rollback과 ACTIVE hold 보존, 다음 worker 복구를 검사한다. fixture의 Clock은 저장된 due/lease를 기준으로 전진하며 production claim/lease SQL을 교체하거나 타임아웃을 확대하지 않는다.
- 옛 UNKNOWN 이후 성공 검사는 **DEMO provider의 응답 상태**를 테스트에서 변경한 다음 실제 Refunds query/T3/T4로 수렴시킨다. payment 성공이나 claim 완료를 SQL로 주입하지 않는다. 따라서 로컬 모의 제공자 회복 검사로 인정하며 실제 외부 PG 검증이라고 표현하지 않는다.

### 실행 결과 확인

| 증거 경로 | 원문 확인 결과 |
| --- | --- |
| `.local-tools/p09-c-review-focused2-20261031` | Failsafe DB9·실패0/오류0/skip0. R-C01, R-C02 FAILED/UNKNOWN, ACK 종료/재생,6개 rollback 및 기존 취소 quote/부분/전체배송비·상품snapshot 회귀 포함. Surefire 합계191·실패/오류/skip0. |
| `.local-tools/p09-c-review-focused3-20261031` | 명시 UNKNOWN retryFailed409가 추가된 R-C01 DB1·실패0/오류0/skip0. Surefire191·실패/오류/skip0. |

두 경로의 `db/build/failsafe-reports/TEST-com.example.marketplace.dbtest.PostgresJdbcIT.xml`, Surefire XML 및 result.json을 직접 읽었다. **두 root status는 failed**, preservation은 passed이며 오류는 선택하지 않은 필수 `accountHttpOwnerCsrfValidationAndPrivacyBoundaries` 미수집이다. 선택 집중 검사의 GREEN과 전체 하네스 failed를 구분한다. focused1의 helper 준비 실패5건도 삭제/재해석하지 않는다.

### 후속 경계

R-C01/R-C02를 이 수정 artifact와 집중 증거 기준으로 닫는다. common fee/RETURN intent, 미완성 명령, 모든 DB 제약, 전체 C 요구의 충족 여부는 이번 판단 범위 밖이다. 본 세션은 보존된 증거 읽기와 문서 추가만 했고 검사·하네스를 실행하지 않았다. 최종 독립 전체는 여전히 별도 요청/소스 동결 뒤 수행해야 한다. 실행된 V41~V46과 이전 실패 기록을 보존하며 제품 수정·새 agent·커밋·푸시·배포·D 진행은 하지 않았다.

## 정책 문서 정합성 짧은 검토 — 2026-10-31 KST

부모가 변경한 `requirements.md` §9, `P09.md` 확정 정책, `P09-D.md` 상세 계약/인계 설명의 diff를 C 설계 §11~12와 대조했다. **변경된 회수비 정책에 차단할 누락·모순은 없다.** 상품별 기본3,000원/0원, 구매 전 공시·주문 snapshot, 원 배송의 반품 대상 누적max/차액, 다른 배송합산·판매자 책임 제외, legacy 명시동의가 일치한다. D 인계의 누적 부과액에 미수납 포함·자동상계 금지·fee 금융 적용과 반품 업무 완료 분리도 일치한다. 상세 금액/원장 계약은 연결된 C 설계를 통해 유지된다.

다만 변경 구간에 인접한 기존 문구2곳은 다음 문서 정리 때 명확히 하는 것이 좋다.

1. P09의 “다음은 … 상세 결정 … C 회수비 분할 공제·추가 비용 상태 및 환불 완료 조정 경계”는 아직 미결인 것처럼 읽힌다. C1에서 확정되었고 구현/검증은 진행 중이라는 시점 설명 또는 §11~12 참조로 갱신한다. 새 정책 결정을 요구하는 지적은 아니다.
2. P09-D의 “전체 반품 시 원 배송비 반환 여부 … C1의 확정표를 따라야 한다”는 C 계약에 없는 확정을 참조한다. C는 **미발송 전체 취소의 원배송비 반환**을 정했으며 발송 후 전체 반품의 원배송비 반환까지 승인하지 않았다. 해당 부분은 D 착수 때 결정할 항목으로 명시하고 C 취소 규칙을 자동 적용하지 않는다. 이번 변경으로 생긴 결함은 아니지만 인계의 오해 가능성이다. 지금 D 작업/정책 선택을 시작할 필요는 없다.

R-C01/R-C02 ADDRESSED는 유지한다. 이 확인은 문서 diff 정적 대조만이며 제품 fee 수직계약이나 C 전체 승인으로 확대하지 않는다. 제품 변경·검사/하네스 실행·커밋·D 착수 없이 본 검토 문서만 추가했다.

### 인접 문구2건 보완 확인 — 2026-10-31 KST

P09.md56행은 최초 게이트 목록과 현재 A/B 완료·C1 확정/구현검증 진행을 구분하도록 수정되었다. P09-D.md17행은 회수비 차액/미정산의 C 확정 계약과 발송 후 전체반품 원배송비 반환의 D 미확정 정책을 분리하고, C 미발송취소 규칙 자동 적용 금지를 명시했다. 앞선 문서 정리 의견2건 모두 반영 확인했다. 추가 정책 선택이나 D 착수를 수행하지 않았으며 제품/하네스/커밋 없이 본 기록만 추가했다. R-C01/R-C02 ADDRESSED와 C 전체 미완료 판정은 유지한다.

## common-fee-review1 공통 회수비 중간 검토 — 2026-10-31 KST

### 범위·식별·판정

`.local-tools/p09-c-execution-20261030/common-fee-review1/review-report.md`를 먼저 읽고 source 사본의 상품 정책/견적/주문 snapshot, common intent, RETURN 결과 보존, fee quote/accept/bind/apply/withdraw, V45~52 제약 및 변경된 CANCEL 연결을 §11~12와 대조했다. 판정은 불변 사본에 한정한다. 소스63파일과 artifacts148파일을 각각 manifest SHA로 직접 대조해 불일치0을 확인했다. 패키지 identity의 source 식별은 `861CDDD801CFE4675C533CF5A33195758F216C88351FB086DFE8CC50380B6E7C`, artifact-manifest 파일 SHA는 `07FAB0753C0BF33E907BB0897E70870488C48F432131B12085C3C4C4D56127C5`다. source-manifest JSON 파일 자체 SHA는 `F70AFB1937C6C3998FBF4E8BACC5C9DDA89010F1B41B08514F2E9A59E22EFD52`이며 source 식별값과 혼동하지 않는다.

**R-C03/R-C04 각각 P2 수정 필요.** 정상 금액/공통 금융 흐름의 집중 검증은 인정하지만 잠금 및 완료 원자성 제약에 아래 결함이 있다. 미완성 UI·SUPPORT·legacy 등 보고서에 명시된 후속 항목 자체는 이번 완료 범위의 결함으로 세지 않았다. 두 지적은 이미 구현된 코드/제약의 실제 경로 문제다. 이 검토에서 새 재현이나 하네스를 실행하지 않았다.

### R-C03 — P2: 상품 저장의 신규 세션 검증이 계정 잠금 순서를 역전시킴

위치: `source/backend/src/main/java/com/example/marketplace/catalog/infrastructure/JdbcProducts.java:70`~71. `saveForSession`의 currentAuthority는 `PurchaseSessions.lockCurrent`이며 상품 저장 거래가 **세션 FOR SHARE를 먼저** 획득하고 이후 `stores.requireCapability`로 계정/매대 잠금을 얻는다.

불변 기본 HEAD `c9f9241`의 미변경 구현도 읽어 연결을 확인했다: `JdbcPurchaseSessions.lockCurrent`는 spring_session FOR SHARE, `JdbcStores.requireCapability`→`JdbcSellerMembership.lockActiveBuyer`는 identity_user FOR NO KEY UPDATE, `JdbcAccountStateOperations.lockTarget/transition`은 identity_user FOR UPDATE 뒤 spring_session DELETE다. API 주석도 account 잠금 뒤 세션 확인을 요구한다. 이 비교는 live 코드가 아닌 Git의 고정 기준 소스다.

재현 조건: 상품 편집 A가 세션 SHARE를 보유한 채 계정 잠금 전에 멈춘다. 계정 정지/탈퇴 B가 같은 계정 UPDATE 잠금을 얻고 세션 삭제에서 A를 기다린다. A가 capability 검사를 재개하면 B의 계정을 기다려 순환 대기가 된다. PostgreSQL이 한 거래를 deadlock으로 중단하므로 정상 상품 수정이나 권한 폐기가 예측한 상태 오류 대신 실패한다. 새 current-session 검사로 생긴 문제이며 뒤에서 자격을 재검사해도 최초 역순 잠금은 해소되지 않는다.

수정 기준: 기존 canonical 계정/매대 잠금 경로를 먼저 확보하고 세션을 잠그며, 마지막 상품/분류 등 대기 후 현재 세션 만료를 다시 확인한다. 다른 모듈의 identity 테이블을 catalog가 직접 잠그는 우회 대신 기존 공개 자격 포트를 사용한다. 세션 검사를 삭제하거나 deadlock 재시도로만 감추지 않는다.

필수 재현: 실제 두 연결 barrier로 상품 create/update와 계정 정지 또는 탈퇴의 위 순서를 겹친다. 교착0, 정지 선행이면 저장 부작용0/현재 권한 거부, 저장 선행이면 일관된 저장 후 정지 성공을 확인한다. 자연 세션 만료와 stale authVersion도 유지한다. **현재 정적 근거, 재현 NOT RUN**.

### R-C04 — P2: 완료 영수증만 추가한 거래가 deferred 검증을 우회함

위치: `source/backend/src/main/resources/db/migration/V52__fee_application_and_withdrawal_receipts.sql:38`; `source/.../claim/infrastructure/JdbcReturnFees.java:112,122`.

`validate_fee_terminal_receipt`는 assessment INSERT/UPDATE에만 걸려 있다. 반면 application/withdrawal INSERT는 BEFORE 검증만 수행한다. 이미 이전 거래에서 BOUND 상태인 assessment에 실제 성공 결과가 저장된 후, **올바른 application 영수증만 INSERT하고 assessment/bucket 갱신 없이 commit**하면 BEFORE 조건(BOUND+실제 SUCCEEDED+ACTIVE hold)과 FK를 모두 만족하지만 terminal deferred trigger는 실행되지 않는다. 이로써 APPLIED 영수증이 존재하면서 assessment=BOUND/A0/R유지인 상태를 저장할 수 있다.

이후 정상 `ReturnFees.apply`는 prior receipt 분기에서 APPLIED 상태/원장 완료를 검증하지 않고 영수증을 반환하므로 누적 부과액이 반영되지 않은 채 예약 slot이 남는다. withdrawal도 실제 cancelUnsent/hold release 뒤 영수증만 INSERT하면 유사하게 BOUND/R유지 상태를 commit할 수 있고 정상 재호출은 prior receipt로 조기 반환한다. 현재 제품 메서드는 하나의 거래로 올바르게 갱신하지만, **선언한 DB 전체완료 제약이 부분 저장을 차단하지 못하며 재시도도 이를 완료로 오인**한다. 직접 SQL 위반 검사 미실행과 별개로 구현된 trigger의 누락이다.

수정 기준: receipt INSERT에도 assessment 식별자로 terminal receipt↔state 및 bucket 원장을 검사하는 deferred constraint를 연결해 거래 끝의 양방향 일치를 강제한다. 기존 assessment 쪽 검사도 유지하고 정상 INSERT→상태/원장 갱신 거래는 허용한다. public replay는 저장 영수증과 terminal state/귀속 불일치에서 성공을 반환하지 않도록 방어한다. 이미 실행된 V52는 수정하지 않고 새 migration으로 보완한다.

필수 재현: 실제 paid/delivered/RETURN hold/동의/bind/provider 결과 fixture에서 application-only INSERT commit 거부 및 전후 상태 동일. withdrawal-only도 실제 RESERVED cancel/hold release 후 같은 거래에서 부분 INSERT를 시도해 전체rollback 확인. 정상 apply/withdraw와 ACK/replay는 효과1회, history/Outbox 실패주입도 전체rollback. **현재 정적 근거, 재현 NOT RUN**. 성공 상태를 SQL로 만드는 테스트가 아니라 실제 성공 결과를 사용한 의도적 불완전 쓰기 제약 검사로 기록한다.

### 구현 계약 대조와 인정하는 근거

| 영역 | 확인한 구현 / 제한 |
| --- | --- |
| 상품 정책 | 기본3,000·명시0·수정 누락 시 기존0 유지, 문자열 long 검사, product revision/source, 공개 DTO/quote 전달이 있다. R-C03 잠금 보완 필요. 실제 편집/구매 전 UI 완료는 이번 주장에 포함되지 않는다. |
| 구매 snapshot | 정책 전체를 quote fingerprint에 포함하고 item별 불변 policy 사본과 line snapshot을 연결한다. legacy 이행 UNRESOLVED와 기본상품 이행 source를 구분한다. 실제 legacy/정책변경-vs-주문 경쟁 검사는 보고된 대로 아직 후속이다. |
| common intent | CANCEL/RETURN을 분리하고 실제 hold/cause/원단위 집합·order/seller·refund 금액/참조에 FK/불변 영수증을 연결한다. RETURN을 가짜 CANCEL 행으로 만들지 않는다. 누락 binding의 deferred FK가 있다. |
| quote/accept/bind | 실제 원 배송/저장 net·정책과 revision, 명시 동의/quote hash, canonical 배분, bucket active slot/R를 연결한다. quote는 금액/hold 예약0이며0값 bucket 행 생성은 예약 효과가 아니다. |
| apply/withdraw | 실제 payment 결과와 보존된 RETURN 결과를 검증한다. 누적A·공제·미수납을 분리하고 다른 원배송 간 상계를 하지 않는다. apply는 RETURN hold/재고/쿠폰/주문을 완료하지 않는다. RESERVED/FAILED 철회와 UNKNOWN 거부 구현은 확인했지만 R-C04 DB 원자성 보완 필요. |
| RETURN 결과 | 공통 router가 immutable binding의 kind를 읽어 CANCEL은 기존 handler로, RETURN은 실제 Refunds.get 결과·영수증/이력/Outbox 저장으로 보낸다. 후속 조정자가 결과를 읽을 공개 포트가 있으며 RETURN 완료 효과를 발생시키지 않는다. |
| R-C01/R-C02 | 부재 provider UNKNOWN 유지와 CANCEL result receipt/이력/Outbox/final lease를 사본에서 확인했다. 새로운 router의 CANCEL 위임 및 focused RETURN/CANCEL 회귀와 모순 없음. 기존 ADDRESSED 판정 유지. R-C01 실제 child 전체 재실행이 이 패키지에서 수행됐다고 주장하지 않는다. |

### 보존된 실행 결과와 GREEN 이후 변경

원본 Failsafe XML/result.json에서 다음을 직접 확인했다. 모든 DB 검사는 failures/errors/skipped0이고 root는 **필수 미선택 failed / preservation passed**다.

- `.local-tools/p09-c-fee-accept-focused3-20261031`: DB2, 원정책/원배송 quote와 실제 refund bind/예약.
- `.local-tools/p09-c-return-result-focused2-20261031`: DB7, RETURN 결과 저장과 quote/accept, CANCEL 부분/전체 및 R-C02 FAILED/UNKNOWN 재생.
- `.local-tools/p09-c-fee-effect-focused1-20261031`: DB6, 누적채무 적용·철회, RETURN 결과, quote/accept, CANCEL 전체배송비/종결.

source의 실제 assertion은 net2,000/fee3,000 뒤 net1,000/fee5,000에서 A5,000·공제3,000·미수납2,000·수납0·R0/slotNULL, RETURN hold2 ACTIVE/lifecycle HELD, restitution0/부모 PAID를 대조한다. 이는 **테스트 조정 코드의 공통 금융 계약 검사**이며 실반품 E2E가 아니다. RESERVED/FAILED 철회 및 UNKNOWN409·영수증 재생도 별도 assertion이다. 원본 XML의 해당 test 통과와 연결하며 실행하지 않은 권한경합/추가rollback/legacy/지원retry/UI 검사를 이 수치로 대체하지 않는다.

GREEN 실행 inputs와 source63 manifest를 비교한 변경은 `claim/api/ReturnFees.java` 한 파일뿐이다. 본문의 `JdbcReturnFees.apply/withdraw`는 동일하고 사본 인터페이스는 override된 두 메서드를 abstract로 선언한다. `compile20-maven.txt`의 BUILD SUCCESS/16.779초를 확인했다. 이 선언 정리에서 새 업무 동작 변경은 발견하지 않았지만 **정리 뒤 새 하네스 통과가 있었다고 표시하지 않는다**. 세 경로의 Surefire XML 합계도 각각31/실패·오류·skip0을 직접 확인했다. 이는 집중 검사 범위이며 최종 전체 검증을 대신하지 않는다.

V49 parser/이후 변수 모호성 준비 실패와 V50 후속 보완, RETURN 결과/fee 기능 부재 RED 등은 review-report의 구분을 유지한다. 과거 실패·DB·미디어를 수정하지 않았다. 필수 등록 보존 보고서는421기존+추가, missing0이지만 전체 검사 수집/통과는 별도 확인 대상이다.

### 인계

R-C03/R-C04 재현 및 수정 증거를 새 경로에 보존하고 수정 artifact로 재검토한다. 보고서의 나머지 미완료 항목은 기존 C 구현 담당이 계속 수행한다. 본 검토는 source/evidence 읽기와 이 문서 추가만 했으며 제품 수정·하네스 실행·새 agent·커밋·푸시·배포·D 진행은 하지 않았다. **공통 회수비 중간 승인 보류 / R-C01·R-C02 ADDRESSED 유지 / C 전체·독립 전체 NOT RUN**.

## common-fee-fix-review1: R-C03/R-C04 한정 재검토 — 2026-10-31 KST

`common-fee-fix-review1/fix-report.md`와 R 패치를 먼저 읽고, snapshot source/전체 테스트 발췌 및 원본 실행 증거를 대조했다. source65와 artifact127의 manifest 파일별 해시 불일치0을 직접 확인했다. source canonical 식별은 `D724DD39D5676E8DC100DE6F59D8ECB22B0985DF9A2B6187E1B265173A9F5A48`, R 패치 SHA는 `13DE53F71F704461C3965A070ECDE3A8B1A9A7141CD717F828AE545617516CD2`, artifact-manifest SHA는 `AAA381EFC1D356DD15992B83AD32EAB0B2208978861A8632F4841352DA90AD8A`다. focused2 inputs537 파일 SHA `323B33CAFC8637F73597CC831A521BED1A0B35A6E95EABDDD4357121E37B5CAB`도 직접 확인했다. 캡처 시 inputs/current mismatch0 기록과 구분하며 변경 중인 live 제품은 읽어 판정하지 않았다.

| 지적 | 판정 | 수정 및 근거 |
| --- | --- | --- |
| R-C03 | **ADDRESSED** | JdbcProducts가 공개 Stores.requireCapability의 계정/매대 잠금을 먼저 얻고 세션을 확인한다. 상품/분류/쓰기 대기 뒤 최종 현재 세션 검사도 추가했다. 실제 create/update×suspend/self-withdraw×save/revoke 선행8경합, 자연 만료·stale authVersion 검사가 통과했다. 권한 검사를 제거하거나 교착 재시도로 숨기지 않았다. |
| R-C04 | **ADDRESSED** | V54가 application/withdrawal receipt INSERT에도 deferred terminal-state/원장 검사를 연결한다. 기존 assessment 검사와 양방향으로 동작하며 기존 불일치를 자동 수정하지 않고 거부한다. API replay도 terminal 상태와 금융 원장 근거가 맞아야 반환한다. 실제 receipt-only commit 및 commit 전 잘못된 replay를 거부하고 정상 적용/철회·rollback 재개를 유지했다. |

검토한 수정으로 발생한 새 중대 문제는 발견하지 않았다. V41~52는 이전 manifest와 비교해 변경0이며 R-C04는 새 V54로 보완했다. V53 및 Claims/JdbcClaims/ClaimController의 동시 GET·철회 기능은 별도 패치로 구분되어 있고 **이번 지적 해결 승인 범위 밖**이다.

### 실제 재현 및 검증 확인

원본 `.local-tools/p09-c-fee-review-red1-20261031`의 DB3는 failures3/errors0/skip0이다. 관측 파일에서 R-C03의 `save=OK/revoke=PessimisticLockingFailureException`, R-C04의 `rejected=false;assessment=BOUND;debt=0;receipts=1` 및 `rejected=false;refund=WITHDRAWN;hold=RELEASED`를 확인했다. 제품 결함 RED와 보고서의 compile/fixture 준비 실패를 구분한다.

원본 `.local-tools/p09-c-fee-review-focused2-20261031`의 Failsafe XML은 DB6·실패/오류/skip0, Surefire XML 합계는196·실패/오류/skip0이다. 다음 관측/실제 assertion과 연결된다.

- `db/catalog-account-lock-observed.txt`:8가지 경합 모두 revoke=OK. 저장 선행은 save=OK, 권한 폐기 선행은 save=AccountException/제품 효과0. 테스트는 실제 계정 연산과 연결을 사용하며 세션 wrapper는 barrier만 추가한다.
- 자연 만료 검사는 실제 catalog_category 잠금 대기 중 벽시계 만료를 지나게 하고 create/update401·정책/제품/version 부작용0을 확인한다. 별도 stale authVersion도401이다.
- `db/fee-receipt-only-observed.txt`: `rejected=true;assessment=BOUND;debt=0;receipts=0`. 실제 성공 결과를 사용한 불완전 application INSERT가 commit에서 거부되며 공개 replay도409다.
- `db/fee-withdraw-receipt-only-observed.txt`: `rejected=true;refund=RESERVED;hold=ACTIVE`. 실제 cancelUnsent/B release까지 포함한 불완전 거래 전체가 rollback된다.
- `feeTerminalHistoryOutboxAndLostLeaseRollBackEveryParticipant`: APPLY HISTORY/OUTBOX/실제 job owner-token 상실, WITHDRAW HISTORY/OUTBOX의5사례에서 assessment/bucket/receipt/event/payment/budget/hold/lifecycle/Outbox를 비교하고 같은 저장 결과로 복구한다. 실제 token 상실은 별도 DB 연결로 production job 소유 token을 바꾼 fencing 검사이며 **추가 child-kill 증거라고 부르지 않는다**. 성공 환불은 provider effects1/submit1을 유지한다.

focused2의6번째 owner GET 검사는 실행 수에는 포함되지만 기능 승인으로 확대하지 않는다. focused1의 정상 fee 적용/철회·재생 통과 및 준비 오류는 fix-report의 구분을 유지한다. 두 집중 실행 모두 root 전체 통과가 아니며, 최종 focused2도 필수 미선택으로 **root failed / preservation passed**다.

R-C01/R-C02의 기존 ADDRESSED는 유지한다. 이번 범위에서 R-C03/R-C04를 닫지만 아직 남은 SUPPORT/추가 경쟁·legacy/프런트·브라우저·전체 검증까지 승인하지 않는다. 본 세션은 보존된 소스/증거 읽기와 이 문서 추가만 했으며 새 하네스·제품수정·새 agent·커밋·푸시·배포·D 진행은 하지 않았다. **C 전체와 지정 검토세션 독립 전체는 여전히 NOT RUN**이다.

## command-review1: 구매자·SUPPORT 명령 중간 검토 — 2026-10-31 KST

판정: **R-C05/P2·R-C06/P2 OPEN, 명령 범위 승인 보류**. R-C01~04는 기존 ADDRESSED를 유지한다. 이번 검토는 owner GET/withdraw, SUPPORT query/FAILED retry, 공통 RETURN fee retry 및 V53/V55/V56의 정적 검토와 기존 실행 근거 확인이다. 미완성 UI·계획된 추가 검사 자체를 새 결함으로 집계하지 않는다.

고정 경로 `.local-tools/p09-c-execution-20261030/command-review1`의 review-report/identity 및 부모 progress를 기준으로 복원했다. source71 파일 해시 불일치0, artifact78 파일 해시 불일치0을 직접 확인했다. source-manifest SHA256은 `81C01F65347F2058F2D482474954D0D9D1A459F6F60B5A8BB9B22492538C7D12`, artifact-manifest SHA256은 `F1CE35436B1883EAE512418BE1E0CBD2D8EFBD6BF470E2D784FCC8BF00380A00`이다. 아래 위치는 이 고정 source 사본을 뜻한다. 현재 변경 중인 제품을 검토 대상과 섞지 않았다. 기존 identity 잠금 구현은 기준 HEAD `c9f924123b42e8d413f3ff70a095ffec06fd9593`의 JdbcPurchaseSessions를 읽어 연결했다.

### R-C05 — P2: FAILED 결과 적용 전에 철회하면 늦은 결과 작업이 완료되지 않음

위치: `source/backend/src/main/java/com/example/marketplace/claim/infrastructure/JdbcClaims.java:93,96`, `source/backend/src/main/resources/db/migration/V53__cancellation_withdrawal_receipts.sql:17`.

실제 제공자가 확정 FAILED를 T3에 저장했지만 T4 결과 작업은 아직 실행하지 않은 상태에서 구매자가 철회할 수 있다. withdraw는 실제 FAILED와 claim APPROVED, 효과0을 허용하고 claim WITHDRAWN/영수증/hold RELEASED를 정상 commit한다. 이때 refund_attempt_id는 기존 실패 시도 그대로다. 이후 늦은 T4는 결과 영수증이 없고 현재 시도와 동일하므로 OLD_FAILED 분기로 가지 않고, 무조건 claim을 REVIEW_REQUIRED/version+1로 바꾼다. V53의 deferred 검사는 철회 영수증이 있는 claim이 WITHDRAWN이 아니므로 `CANCELLATION_WITHDRAWAL_RECEIPT_REQUIRED`로 거래를 거부한다. 결과 영수증·감사도 rollback되어 같은 작업을 다시 실행해도 반복 실패한다. DB가 상태 역행을 막으므로 실제 철회 상태가 되돌아간다고 주장하지 않는다. 결함은 합법적인 철회 뒤 지연 결과를 ACK 가능한 상태로 정리하지 못하는 것이다.

수정 기준: 실제 FAILED·철회 영수증의 귀속을 확인한 terminal 결과 경로에서 철회 상태/revision과 보류 해제를 유지하면서 결과 수신 영수증·필요한 감사/Outbox를 원자적으로 보존하고 정상 완료한다. V53 terminal 제약을 완화하거나 결과를 근거 없이 버리지 않는다. 이미 처리한 FAILED 결과의 재생과 SUPPORT 새 시도 후 OLD_FAILED 경로도 유지한다.

필수 재현: 실제 paid CANCEL→DEMO FAILED T3만 완료→owner withdraw→지연 T4 및 재생. 작업 정상 완료/ACK, claim WITHDRAWN·revision 불변, hold RELEASED/원단위 OPEN, provider 효과0/입고0/새 시도0, 결과 영수증과 감사 효과1회를 확인한다. 감사·Outbox·마지막 lease 실패 rollback/복구도 연결한다. **정적 결함 근거이며 본 검토에서 재현 NOT RUN**. 기존 RESERVED 철회 검사와 새 시도로 교체한 뒤 과거 FAILED 처리 검사는 이 순서를 검사하지 않는다.

### R-C06 — P2: 취소 철회 영수증만 저장하는 거래가 terminal 제약을 우회함

위치: `source/backend/src/main/resources/db/migration/V53__cancellation_withdrawal_receipts.sql:7,21`, `source/backend/src/main/java/com/example/marketplace/claim/infrastructure/JdbcClaims.java:80`.

V53의 BEFORE INSERT는 claim APPROVED/REVIEW_REQUIRED, 실제 RESERVED 취소 또는 FAILED, RELEASED/WITHDRAWN hold, 환불 효과0을 검사한다. 그러나 영수증↔claim WITHDRAWN의 deferred 검사는 **claim INSERT/UPDATE에만** 등록되어 있다. 기존 claim에 대해 실제 `refunds.cancelUnsent`와 `holds.release`를 수행하고 올바른 claim_withdrawal 영수증만 INSERT한 뒤 claim 상태 변경 없이 commit하면, BEFORE 조건을 만족하면서 terminal 검사가 예약되지 않는다. claim은 APPROVED/REVIEW_REQUIRED인데 돈·수량 보류는 풀리고 철회 영수증이 존재하는 불완전 상태가 허용된다. 이후 동일 요청의 withdraw는 prior receipt에서 상태·hold·환불 완료 근거를 검증하지 않고 성공을 반환한다.

정상 withdraw 구현의 한 거래 갱신 자체가 누락됐다는 지적은 아니다. 선언된 DB 완전성 및 불완전 저장에 대한 replay 방어의 누락이며, 이미 닫은 R-C04의 fee receipt 문제와 별개로 새 V53 취소 receipt에 같은 패턴이 남아 있다.

수정 기준: 새 migration에서 claim_withdrawal INSERT에도 해당 claim의 terminal 일치 검사를 deferred로 연결하고 기존 claim 측 검사를 유지한다. 정상 INSERT→claim 상태 변경은 허용하되 receipt-only commit은 전체 rollback되어야 한다. API replay도 실제 terminal 상태·영수증 귀속/보류 해제 근거가 맞지 않으면 성공을 반환하지 않는다. 실행된 V53을 수정하지 않는다.

필수 재현: 실제 접수된 RESERVED 및 실제 제공자 FAILED 사례에서 cancelUnsent/hold release/receipt-only INSERT 후 commit 거부, payment/budget/hold/lifecycle/claim/receipt 전체 원복을 확인한다. commit 전 동일 요청 API replay는409, 정상 철회와 재생은 동일 영수증/효과1회를 확인한다. **정적 근거이며 재현 NOT RUN**. 단순 SQL로 환불 성공을 만드는 검사가 아니라 실제 업무 상태에서 의도적 부분 쓰기를 검증한다.

### 확인한 계약과 실제 실행 근거

| 영역 | 정적 확인 및 보존된 assertion |
| --- | --- |
| owner GET | 현재 lockOwned를 시작/반환 전에 확인한다. 불변 접수 영수증과 현재 claim 상태·모든 귀속 환불 시도·이력을 분리한다. 실제 회원 GET의 no-store/다른 로그인 사용자404/익명401 및 접수 원본 불변 검사를 확인했다. 비회원·전체 권한 경합까지 승인하지 않는다. |
| owner withdraw | 실제 RESERVED/FAILED와 효과0, CANCEL hold/cause/units를 검사하고 환불·hold·영수증·상태·감사·Outbox를 같은 거래로 처리한다. 정상 RESERVED 철회/동일키 재생/다른키409/CSRF403은 기존 검사에서 통과했다. FAILED 지연 결과와 DB 부분 저장은 R-C05/06에 남는다. |
| SUPPORT 자격/query | CurrentSupport는 HistorySessions.lockReadable의 계정 SHARE→현재 세션 순서를 사용하고 SUPPORT 권한을 검사한다. 업무 잠금/쓰기 뒤 다시 확인한다. query는 PROCESSING/UNKNOWN의 같은 attempt에 별도 원인/사유/불변 명령 영수증과 조회 job을 연결한다. SYSTEM-only403·CSRF403·위조 success 필드400, 실제 query T3/T4 및 효과1회 assertion을 확인했다. 권한 폐기/만료 경쟁 전체는 후속이다. |
| CANCEL FAILED retry | 실제 FAILED에서만 다음 sequence를 만들고 원금/목적/원참조·불변 접수/이전 시도를 보존한다. 명령키·actor·사유·revision fingerprint와 최종 자격 검사, V56의 이전/새 시도 귀속 검사가 있다. 교체된 옛 FAILED 결과는 별도 감사 후 현재 claim 상태/revision을 유지한다. 실제 새 seq2/옛 FAILED 불변/새 성공 뒤 옛 결과 ACK/입고1 assertion을 확인했다. SHIPPING retry의 실제 별도 증거까지 확대하지 않는다. |
| RETURN fee retry | 현재 SUPPORT를 요구하고 동일 BOUND assessment/실제 FAILED/ACTIVE hold를 검증한다. 새 시도와 binding history만 연결해 기존 consent/quote/fee bucket R를 유지한다. 실제 같은 assessment·bucket 불변→새 성공→A 적용·R0, RETURN hold ACTIVE/부모 PAID/입고0 assertion을 확인했다. 이는 공통 금융 계약 검사이며 D 실반품 완료가 아니다. |

원본 Failsafe/Surefire XML과 result.json을 직접 읽었다. `.local-tools/p09-c-fee-review-focused1-20261031`은 DB11 중 실패1/오류2/skip0(정상 owner withdraw 포함), focused2는 DB6/실패·오류·skip0이다. `.local-tools/p09-c-support-query-focused1-20261031`은 DB5, `.local-tools/p09-c-support-retry-focused1-20261031`은 DB10으로 모두 실패·오류·skip0이며, 이 네 실행의 단위/ArchUnit은 각각196/실패·오류·skip0이다. query 검사는 DEMO 제공자의 후속 응답 저장소만 변경하고 실제 payment query→T3/T4로 소비한다. 실제 외부 제공자 성공 증거라고 표현하지 않는다.

RED 원본도 확인했다. cancellation-command-red1 DB2/실패2, support-query-red1 DB1/실패1, support-retry-red1 DB2/실패2이며 모두 오류·skip0이다. 위 모든 실행은 **root failed / preservation passed**다. 선택한 assertion의 통과와 필수 미선택으로 인한 전체 실패를 구분한다. 기존 R-C01 실제 child 및 R-C02 ACK/역순/rollback, R-C04 fee rollback 회귀가 포함된 범위는 report와 해당 assertion에 한정한다. R-C03의 기존 독립 재검토 판정을 새 실행으로 대체하지 않는다.

### 범위·인계

초기에 컨텍스트 혼선으로 B 커밋/기존 증거를 읽었으며 파일 수정·검증 실행·커밋은 하지 않았다. 부모의 재고정 후 위 고정 C 자료로 복원했다. 이번 쓰기는 이 검토 절 추가뿐이다. 제품·고정 artifact·기존 실패/자원은 수정하지 않았으며 새 하네스·새 agent·커밋·푸시·배포·D 작업은 하지 않았다.

R-C05/06의 실제 RED→수정→GREEN을 원 담당에게 인계한다. UNKNOWN retry/terminal replay의 추가 경계, 전체 권한표·경합·legacy/다중bucket/직접SQL 전수·프런트/브라우저·보존 재감사 및 새 개발 전체/지정 독립 전체는 여전히 후속이다. **C 전체·독립 전체 NOT RUN**이며 이번 정적 검토를 전체 통과로 표시하지 않는다.

## command-fix-review1: R-C05/R-C06 수정 재검토 — 2026-11-01 KST

판정: **R-C05·R-C06 ADDRESSED**. 수정 범위에서 새 중대 문제를 발견하지 않았다. 별도 배송비 금융 근거 보완도 아래 음성/정상 회귀 범위에서 적합하다. R-C01~04의 기존 ADDRESSED를 유지하며 **C 전체·지정 독립 전체 NOT RUN**은 바뀌지 않는다.

### 고정 대상과 소스 대조

`.local-tools/p09-c-execution-20261030/command-fix-review1/review-report.md`와 identity를 먼저 읽고 고정 source72만 검토했다. source72/artifact79 파일별 SHA256 불일치0을 직접 확인했다. source-manifest SHA256은 `17E3DE04CC4562C27C0C24B472A49EFD992012CE47CB9C09EAF5213D2515D20F`, artifact-manifest SHA256은 `37541659B8B9A8FD1B7E1FE373873F7670A8DD0C39E8CD6BD4B77EC26F881390`이다. focused2 원 inputs.json SHA256 `8A4CC2A95557C66CB9A4EC01854EA86183CF8A65F50CA24F95C1C718E33C6D47`을 확인했고, 고정 source72와 GREEN inputs544의 해당 파일 해시 불일치0이다. 변경 중인 현재 제품과의 동일성을 새로 주장하지 않는다.

command-review1 대비 JdbcClaims, JdbcCancellationOrders, ReturnFees 선언, DB 검사/필수 목록과 신규 V57을 대조했다. V41~56의 변경은0이다. ReturnFees.retryFailed의 기본 구현 제거는 이미 override된 메서드의 abstract 선언 정리이며 이번 GREEN에 포함돼 있다.

| 지적 | 판정 | 수정과 실제 assertion |
| --- | --- | --- |
| R-C05 | **ADDRESSED** | 현재 시도에 대한 WITHDRAWN terminal 분기는 withdrawalProof로 실제 FAILED·철회 영수증·시도/order/seller/금액·claim revision·해제 hold/cause/units를 확인한다. 상태/revision을 다시 쓰지 않고 결과 영수증·감사·Outbox와 최종 lease 확인을 같은 거래로 처리한다. 실제 FAILED T3→HTTP 철회→지연 T4가 SUCCEEDED/receipt1로 끝나며 재생 후에도 상태/revision·효과1회가 유지된다. 이미 적용한 FAILED 뒤 철회/재생도 participant 불변이다. |
| R-C06 | **ADDRESSED** | 신규 V57이 receipt INSERT에도 deferred terminal 검사 함수를 연결한다. 기존 claim 측 검사도 같은 함수로 연결하고 기존 데이터의 불일치는 자동 보정하지 않고 거부한다. WITHDRAWN 상태↔receipt 존재뿐 아니라 실제 환불/해제 hold/outcome·JSON 귀속/version/time·환불 효과0을 검사한다. API replay도 WITHDRAWN 상태와 withdrawalProof를 요구한다. 실제 RESERVED/FAILED 각각 receipt-only commit 거부·전체 participant 원복, commit 전 replay409, 정상 철회/동일 재생을 확인했다. |

### 원본 RED·GREEN 및 rollback 근거

원본 `.local-tools/p09-c-command-review-red1-20261031` Failsafe XML은 DB3/실패3/오류·skip0이다. 관측 파일은 R-C05 `job=READY;claim=WITHDRAWN;resultReceipts=0`, R-C06 두 상태 모두 `rejected=false;claim=APPROVED;apiReplay=WITHDRAWN`이다. 정적 추론만으로 닫지 않고 이 실제 재현과 후속 결과를 연결했다.

원본 `.local-tools/p09-c-command-review-focused2-20261101` XML은 보고된 DB14 모두 실행/실패·오류·skip0이며 Surefire 합계196/실패·오류·skip0이다. 관측 파일은 지연 결과 `job=SUCCEEDED;claim=WITHDRAWN;resultReceipts=1`, receipt-only 두 상태 모두 `rejected=true`, `apiPrecommitReplay=409;normalReplay=true`다. RESERVED는 환불 RESERVED로 원복되고 FAILED는 FAILED를 유지한다. 테스트는 claim/receipt/event/payment/budget/hold/lifecycle/Outbox 등 participant 전체 snapshot을 비교한다. 정상 지연 결과와 재생은 provider 효과0/입고0/시도1, 결과 영수증·terminal 감사·Outbox 각각1 및 부모 PAID를 확인한다.

`withdrawnFailedResultHistoryOutboxAndLostLeaseRollBackAtomically`는 HISTORY·OUTBOX 실패 주입과 실제 lease 만료 세 경우를 검사한다. LEASE는 실제 JdbcJobs의 저장된 leaseUntil을 확인한 후 WITHDRAWN_OUTBOX 시점에서 mutable Clock을 deadline 뒤로 옮긴다. 기준 HEAD의 production requireOwned가 job 행을 잠그고 동일 clock으로 만료를 거부하는 것을 확인했다. 최종 검사를 mock으로 실패시키거나 제품 로직을 바꾸지 않는다. 세 경우 모두 job 성공 금지/업무 participant 원복 뒤 원 결과 재처리로 영수증·감사1회가 복구된다. **실제 child kill 또는 실제 벽시계 대기 검사로 표현하지 않는다**.

선행 focused1은 DB14 중13통과/실패1, 단위196통과다. XML의 실패는 LEASE checkpoint 미도달이다. 보고된 자기 대기 준비 오류와 후속 Clock 준비 수정은 구분하며, focused1을 통과로 소급하지 않는다. 본 재검토는 당시 DB 연결 취소를 직접 관찰한 것으로 주장하지 않는다. 기존 실패 원문은 보존한다.

### 별도 배송비 금융 근거 보완

JdbcCancellationOrders.allCanceled는 기존 미발송·CANCEL/REFUNDED 조건에 `order_cancellation_settlement_unit`의 같은 item/ordinal/order/cause 존재를 추가한다. order 소유 테이블만 읽고, claim의 실제 성공 결과 적용이 공개 AftercareSettlements.record를 통해 기록한 원단위 근거와 연결한다. 수량 상태만으로 배송비 환불을 시작하는 경로를 닫는 변경이며 반품 배송비 정책을 새로 정하지 않는다.

`.local-tools/p09-c-shipping-proof-red1-20261031`은 DB5 중실패1/오류·skip0이며 관측 `unitFinancialProofs=1;shippingRefunds=1`이다. 공개 B 포트로 나머지4단위의 REFUNDED 상태만 만든 음성 fixture와 실제1단위 환불을 조합한다. 이는 정상 거래 성공을 꾸미는 검사가 아니라 **금융 근거 없는 수량 상태를 거부하는 검사**다. focused2의 같은 검사에서는 `unitFinancialProofs=1;shippingRefunds=0`, 부모 PAID/쿠폰복구0/실제1단위 입고1을 확인했다. 정상 전체 취소의 SHIPPING·판매자별 종결 회귀도 함께 통과했다. 이 보완은 R-C05/06 해결과 별도 판정이며 전체 금액·할인·무료배송 조합의 검증을 대신하지 않는다.

### 인계와 한계

위 RED·focused1·focused2·shipping RED 모두 원 result.json은 **root failed / preservation passed**다. 필수 미선택 집중 실행이므로 DB14/단위196을 전체 Offline 통과라고 표시하지 않는다. SUPPORT query/retry, 같은 RETURN assessment 재시도, FAILED/UNKNOWN 결과 재생 및 정상 owner 명령 회귀가 포함되지만 기존 R01~04의 모든 재현을 이번에 다시 실행한 것은 아니다.

본 세션은 고정 소스·기존 원문 읽기와 이 검토 절 추가만 수행했다. 제품·하네스·고정 artifact·기존 자원/실패 기록을 수정하지 않았으며 새 검사·새 agent·커밋·푸시·배포·D 작업은 하지 않았다. 남은 C 권한·경합·legacy·프런트/브라우저 및 새 개발 전체·지정 독립 전체를 계속 완료해야 한다.

## frontend-review1: 구매 전 회수비·취소 화면 중간 검토 — 2026-11-01 KST

판정: **R-C07/P2·R-C08/P2 OPEN, 화면 범위 승인 보류**. 기존 R-C01~06의 ADDRESSED는 그대로 유지한다. 현재 화면 구현에 대한 정적 판정이며 브라우저 또는 C 전체 승인이 아니다.

고정 `.local-tools/p09-c-execution-20261030/frontend-review1`의 review-report, identity, changed-files.txt를 기준으로 실제 변경23파일과 주변 frontend/API 맥락을 읽었다. source130의 파일별 해시 불일치0, source-manifest SHA256 `308D699B3D246461AA736107EBA9340DDC74B05AD4CEE8D68072945E4D2F1005`를 직접 확인했다. evidence7개와 원 `.local-tools/p09-c-frontend-focused5-20261101` 사본의 해시 불일치0이다. 실행 전 전체 inputs manifest가 없는 standalone 결과이므로 고정 소스와 실행 입력 전체의 동등성을 독립 검증한 것으로 표현하지 않는다.

### R-C07 — P2: 과거 FAILED 시도 때문에 최신 UNKNOWN/PROCESSING에도 철회 입력을 표시함

위치: 고정 `source/frontend/src/claim/ClaimPanel.tsx:13`, `source/frontend/src/claim/ClaimStore.ts`의 withdraw.

패널은 모든 환불 이력 중 ITEM RESERVED/FAILED가 하나라도 있으면 철회 form을 표시한다. 반면 store.withdraw는 sequence가 가장 큰 ITEM 시도만 검사한다. 확정 FAILED seq1 이후 SUPPORT가 seq2로 재시도하고 최신 결과가 UNKNOWN(또는 진행 중 PROCESSING)이면 과거 FAILED는 의도적으로 보존된다. 이 정상 진행에서 claim APPROVED/REVIEW_REQUIRED 조건과 `some`이 참이므로 활성 철회 form이 보인다. 사용자가 사유를 입력해 제출하면 store는 최신 UNKNOWN/PROCESSING 때문에 아무 요청·오류·안내 없이 return한다. 허용되지 않는 철회가 가능한 것처럼 표시되는 실제 상태 조합이다. 서버가 철회를 허용하거나 금전 효과를 중복 발생시킨다는 주장은 아니다.

수정 기준: 화면과 명령이 공유하는 최신 ITEM 시도 기반 철회 가능 판정을 사용한다. 최신 UNKNOWN/PROCESSING/SUCCEEDED에서는 과거 FAILED를 근거로 철회 form을 열지 않고 현재 진행 상태를 설명한다. 최신 RESERVED/FAILED의 정상 철회는 유지한다.

필수 재현: seq1 FAILED+seq2 UNKNOWN 및 PROCESSING의 실제 API 모양을 패널에 공급해 철회 입력/제출이 제공되지 않고 확인 중 안내가 있는지 검사한다. seq2 RESERVED/FAILED에서는 form이 열리고 최신 claim revision으로 제출되는 양성 검사도 연결한다. **정적 근거, 본 검토 재현 NOT RUN**. 기존 store 단독 철회 재생 검사에는 다중 시도 UI 표시 assertion이 없다.

### R-C08 — P2: COMPLETED와 환불 성공 근거의 모순을 그대로 완료 표시함

위치: 고정 `source/frontend/src/claim/ClaimStore.ts:32,48`, `source/frontend/src/claim/ClaimPanel.tsx`의 COMPLETED label.

progressValid는 claim 상태가 허용 문자열인지와 개별 환불의 구조만 검사하고 둘 사이의 일치를 검사하지 않는다. 정상 APPROVED/RESERVED fixture의 최상위 state만 COMPLETED로 바꾸어도 검증을 통과한다. ITEM이 FAILED/UNKNOWN이고 reference/resultAt이 null인 경우도 허용된다. 패널은 이 값을 그대로 `환불 처리 완료`로 표시하므로 실제 성공 근거가 없는 모순 응답을 정상 완료로 보여준다. SHIPPING 시도가 존재하지만 그 최신 상태가 FAILED/UNKNOWN인 COMPLETED도 같은 문제가 있다. 개별 `SUCCEEDED` 레코드의 reference 누락을 거부하는 기존 검사는 이 상태 간 모순을 다루지 않는다.

수정 기준: 서버가 반환하는 현재 상태와 목적별 최신 시도의 증거를 함께 검증한다. COMPLETED에는 최신 ITEM의 유효한 SUCCEEDED 근거가 필요하고, SHIPPING이 생성된 경우 그 최신 성공도 요구한다. SETTLEMENT_PENDING 등 상태별 계약도 명시하되 과거 FAILED 시도는 그대로 허용해 정상 seq1 FAILED→seq2 SUCCEEDED를 거부하지 않는다. 모순 응답은 완료 표시 없이 조회 오류/재조회 상태로 처리한다. 프런트가 환불 성공을 추정해 보정하지 않는다.

필수 재현: COMPLETED+ITEM RESERVED/FAILED/UNKNOWN, COMPLETED+ITEM SUCCEEDED/SHIPPING UNKNOWN·FAILED를 거부하는 store 및 패널 assertion. 정상 부분 취소 완료, 정상 SHIPPING 완료, 과거 실패를 포함한 최신 성공 완료는 허용하는 양성 검사를 추가한다. **정적 근거, 본 검토 재현 NOT RUN**. 서버가 현재 이러한 모순을 실제 생성했다는 재현은 아니며, 요청된 불완전 성공 응답 방어의 누락이다.

### 나머지 구현 대조와 남은 확인

| 영역 | 확인과 한계 |
| --- | --- |
| 상품 회수비 | 신규 기본3000/저장된0을 nullish 처리로 구분한다. 문자열 입력과 BigInt long 상한 검사를 사용하며 저장 시 returnFee 문자열을 보낸다. 공개 상품/quote 정책은 version/basis/누적max/delta 및 허용 source를 검증한다. 상품·quote productId 결합이 있는 경로를 확인했다. |
| 구매 전·주문 당시 표시 | 공개 상세와 구매 quote에 저장 정책을 표시하고 구매 총액 계산에 가산하지 않는다. 주문 상세는 주문 응답의 정책만 사용하고 누락/null은 기존 주문의 미확정·새 동의 필요로 표시한다. 현재 상품값 조회로 소급하지 않는다. quote의 설명은 접힌 details 내부이므로 실제 구매 흐름에서의 발견 가능성은 브라우저 인수 때 확인해야 한다. |
| 견적·취소 | 원단위 중복/원 net/합계/한 매대·원 배송비·policy/revision을 검사하며 금액을 Number로 바꾸지 않는다. 서버 quote fingerprint와 선택 단위·사유·revision을 명령에 고정한다. 통신/잘못된 응답 뒤 재시도는 pending 키/본문을 유지하고 변경 입력을 받지 않는다. 400/409 뒤 조회 실패를 성공으로 바꾸지 않는다. |
| 현재 자격·지연 응답 | 생성 시 auth 구독, generation/abort/ready 검사, 401/403/404 시 개인 데이터·pending 제거를 확인했다. 원문 단위 검사는 logout 뒤 지연 GET/POST 폐기와 guest 초기 readiness를 확인한다. 실제 비회원 조회 자격 교체·다중 탭·컴포넌트 전환의 브라우저 증거는 아직 없다. |
| 컴포넌트 수명 | main의 StrictMode와 달리 ClaimStore는 constructor에서만 auth 구독하고 dispose 후 subscribe/open에서 재연결하지 않는다. 부모 OrderQueryStore의 정리/재조회가 별도로 보호하므로 이번에 사용자 간 실제 노출이라고 단정하지 않는다. effect 재설정 후 구독 유지/해제와 지연 응답 폐기를 브라우저·컴포넌트 인수에서 반드시 확인할 후속 항목이다. |

### 보존된 실행 결과

focused5 원문 XML을 직접 읽어 unit248/실패0/오류0/skip0, coverage status passed/필수248을 확인했다. typecheck/lint 원문에는 진단이 없고 frontend-result는 standalone passed다. build 원문은 완료이며 기존 chunk 크기 경고가 남아 있다. 정책 오류/long 상한, 저장된0 입력, 주문 상세 취소 영역, ClaimStore의 불변키/권한 상실/지연 응답/잘못된 금액 및 성공 참조 검사를 소스와 연결했다. 숫자248만으로 위 두 UI/상태 공백을 충족한다고 보지 않는다.

과거 RED·fixture/준비 실패는 report의 원본 경로 설명을 유지하며 이번에 전수 재감사했다고 주장하지 않는다. **브라우저·전체 Offline NOT RUN**, 실행 전 전체 inputs 부재도 유지한다. 검토 문서 추가 외 제품·고정 사본·원문 증거 변경, 새 검사·새 agent·커밋·푸시·배포·D 진행은 하지 않았다. R-C07/08 보완과 실제 화면·전체 검증을 원 담당에게 인계한다.

## frontend-fix-review1: R-C07/R-C08 수정 재검토 — 2026-11-01 KST

판정: **R-C07·R-C08 ADDRESSED**. 추가 StrictMode 수명 보완도 제시된 컴포넌트 범위에서 적합하며 수정 범위에서 새 중대 문제를 발견하지 않았다. R-C01~06의 기존 범위 ADDRESSED는 유지한다. 브라우저·전체 Offline 및 C 전체 승인을 뜻하지 않는다.

### 대상·실행 식별

고정 `.local-tools/p09-c-execution-20261030/frontend-fix-review1/review-report.md`와 identity를 먼저 읽고 이전 frontend-review1과 대조했다. 변경은 ClaimStore/ClaimPanel/신규 ClaimReview.test.tsx/required-frontend의4파일뿐이다. source131 파일별 해시 불일치0과 manifest SHA256 `5DEC70E8F9E0BC3414C7D5FAA41EFDFFF19F96208655DF60121B1F90B9723AFF`를 직접 확인했다. evidence7개와 원 `.local-tools/p09-c-frontend-review-focused1-20261101` 파일의 SHA256 불일치0이다. 입력 식별은 실행 후 캡처이므로 실행 전 전체 입력 동등성을 증명했다고 확대하지 않는다.

| 항목 | 판정과 근거 |
| --- | --- |
| R-C07 | **ADDRESSED**. latestRefund/canWithdrawClaim을 화면과 명령에서 공유한다. 같은 목적의 최대 sequence만 판정하며, UNKNOWN/PROCESSING이면 form 대신 철회 불가·결과 확인 안내를 표시한다. 실제 패널 검사에서 과거 FAILED+최신 UNKNOWN/PROCESSING의 입력·버튼 부재/POST0, 최신 RESERVED/FAILED의 정상 form 및 expectedRevision7 제출을 확인했다. |
| R-C08 | **ADDRESSED**. progressValid는 목적별 sequence 중복을 거부하고 접수 시도가 ITEM인지 확인한다. COMPLETED에는 최신 ITEM SUCCEEDED와, 존재하는 최신 SHIPPING의 SUCCEEDED를 요구하며 각 성공 참조/시각 검사는 유지한다. SETTLEMENT_PENDING은 ITEM 성공과 SHIPPING 존재, WITHDRAWN은 최신 ITEM WITHDRAWN/FAILED 및 SHIPPING 부재를 요구한다. T3와 업무 결과 처리 사이의 정상 지연을 실패로 오인하지 않는다. |
| 수명 보완 | constructor의 즉시 auth 구독을 제거하고 subscribe/open 등 사용 시 지연 연결한다. dispose 뒤 재사용 시 재연결하고 마지막 UI 구독 해제 시 정리한다. 실제 StrictMode effect 재설정 후 auth.stop으로 private progress가 사라지고 unmount 뒤 active 구독0, 미사용 constructor의 구독0을 검사했다. 실제 브라우저 사용자 간 노출 재현으로 표현하지 않는다. |

R-C08의 음성 검사는 COMPLETED+최신 ITEM RESERVED/FAILED/UNKNOWN 및 ITEM 성공+SHIPPING FAILED/UNKNOWN을 store에서 거부하고, 패널에도 모순 완료를 표시하지 않고 alert를 보여준다. 양성 검사는 단일 시도 부분 완료, 과거 FAILED 뒤 최신 ITEM 성공, SHIPPING 성공을 포함한 완료를 그대로 허용한다. ITEM 성공/SHIPPING UNKNOWN인 SETTLEMENT_PENDING도 정상 진행으로 유지한다. 과거 실패 이력을 지우거나 최신 실패를 성공으로 보정하지 않는다.

### 실제 RED·GREEN 확인

원 `.local-tools/p09-c-frontend-review-red1-20261101/TEST-frontend.xml`은 신규7 중실패5/양성통과2/오류·skip0이다. 실패 이름은 R-C07 잘못된 form, R-C08 store 모순 완료 허용, R-C08 panel 완료 표시, StrictMode 후 정보 잔류, 미사용 constructor 구독이다. 기존 정적 지적과 추가 수명 우려가 각각 어떤 assertion으로 재현됐는지 확인했다.

focused1 원문 XML은 **unit255/실패0/오류0/skip0**, coverage는 passed/필수255다. 기존248과 신규7을 포함한다. typecheck/lint 로그에 진단이 없고 standalone frontend-result passed, build 완료를 확인했다. 새 실행이나 독립 브라우저 검사를 수행한 것은 아니다. 원문 실패 이력을 보존하며 RED를 후속 통과로 소급 변경하지 않는다.

본 수정 재검토는 고정 소스·보존된 실행 결과 읽기와 이 문서 추가만 수행했다. 제품·고정 사본·원 증거를 수정하지 않았으며 새 검사·새 agent·커밋·푸시·배포·D 작업은 하지 않았다. **브라우저·전체 Offline NOT RUN**, C 전체·지정 독립 전체 미완료 및 실행 후 입력 캡처 한계는 계속 유지한다.

## backend-contract-review1: 제약·assertion 대응표 검토 — 2026-11-01 KST

판정: 새로 확인한 제품 동작 결함은 없으나 **R-C09/P2·R-C10/P2 검증 공백 OPEN**이다. 이는 실행해 실패한 제품 결함 판정과 구분한다. C01~11/§11.5·12.4 대응표 중 아래 공통 RETURN fee 항목은 최종 전체 재수집만으로 닫을 수 없으며 실제 assertion 추가가 필요하다. R-C01~08의 기존 scoped ADDRESSED는 유지한다. C 전체·브라우저·선택없는 개발 전체·지정 독립 전체는 이번 승인 대상이 아니다.

### 고정 사본과 범위

`.local-tools/p09-c-execution-20261030/backend-contract-review1/review-report.md`를 먼저 읽고 고정 backend main/test와 설계·작업 대응표를 대조했다. source375 파일 해시 불일치0, source-manifest SHA256 `2E0F71B5BB1DD12599E63A2DEFCDFE747B0F80FFA1EC6BE1A7CCE9E44A3871F7`을 확인했다. artifact443 파일 해시 불일치0, artifact-manifest SHA256 `F44DCF7F612274570BDF9064BAD45FAEE73C7EF9748BEFF7687DC6D9468EE4C6`, 보존 green-inputs SHA256 `F16F78595A6EBFB4B45EE4A9D719A9F918E658AB99694F16F07C6103D46243F2`다. command-fix-review1과 공통 backend main 경로를 대조한 변경0이며 V41~57도 그대로다. 실행 중인 live 제품을 판정에 사용하지 않았다.

### R-C09 — P2 / 검증 공백: 공통 fee 경로의 최종 잠금 뒤 현재 자격 검사가 빠짐

위치: 고정 `source/docs/work-items/P09-C-design-review.md` §12.4의 공통 fee 현재 자격/자연 만료 요구, `source/backend/src/test/java/com/example/marketplace/dbtest/PostgresJdbcIT.java:636,651,659,825` 및 `JdbcReturnFees`의 quote/accept/bindRefund/withdraw/retryFailed.

이번 회원/GAS parent·unit·budget 대기 검사는 JdbcClaims.cancel, SUPPORT attempt 대기는 JdbcClaimSupport.queryRefund/retryFailed를 호출한다. `returnFinancialIntentRequiresCurrentOwnerAndActualDeliveredHold`는 정상 등록·재생과 다른 소유자 거부를 검사하지만 잠금 대기 중 만료는 검사하지 않는다. 공통 fee의 bucket/assessment/refund 대기 뒤 회원·GAS 및 RETURN retry의 SUPPORT 최종 자격 재검사를 검증하는 호출/부작용0 assertion을 고정 테스트에서 찾지 못했다. 이 메서드들은 별도 구현 경로이므로 CANCEL 검사가 공통 fee의 마지막 자격 확인 제거를 탐지하지 못한다. 설계가 요구한 포트 검증이며 D HTTP/UI를 만들라는 요청이 아니다.

필요 보완: 실제 paid/delivered/RETURN fixture와 현재 Authority를 사용해 quote·accept/bind·withdraw 및 SUPPORT retry의 실제 마지막 업무 잠금을 별도 연결로 막고, 실제 대기를 확인한 뒤 저장 세션/GAS 만료 조건을 넘긴다. 허용된 제어 Clock을 쓸 경우 그 사실을 명시하고 실제 권한 포트는 유지한다. 재개 시 권한 거부 및 quote/consent/assessment/bucket/hold/refund/이력/Outbox 전체 부작용0을 검증한다. 정상 자격의 양성 대조와 owner/SUPPORT의 다른 권한도 연결한다. 대응표에 별도 메서드/실행 증거를 추가하기 전에는 해당 공통 fee 제약을 미검증으로 남긴다. **현재 검증 누락이며 권한 우회가 실제 발생했다고 단정하지 않는다.**

### R-C10 — P2 / 검증 공백: 공통 fee 잘못된 귀속·비성공 apply 거부 assertion 누락

위치: 고정 설계 §12.4 두 번째·세 번째 항목, `PostgresJdbcIT.java:822,834,838,846` 및 `LegacyReturnPolicyMigrationProbe.java`의 동의 검사.

기존 orphanRefundAttempt 검사는 임의 claim UUID 없는 환불을 거부한다. quote JSON 변경, order snapshot 변경/삭제, R04/R06 receipt-only 부분 commit 거부도 존재한다. 그러나 이것이 RETURN의 intent/hold/unit/seller/attempt/amount/reference를 각각 잘못 연결한 API/FK 거부 및 participant 불변 표를 대체하지는 않는다. 정상 다른 주문의 유효한 식별자를 교차 연결하는 음성 검사도 필요하다. legacy child의 false 동의 rollback/true 동의 적용은 확인했지만, 같은 수락 키의 동의·내용 변경 거부와 저장 consent 불변을 직접 대조하는 assertion은 없다.

또한 `returnRefundResultIsRetainedWithoutCancellationEffects`는 FAILED/UNKNOWN 결과 저장·재생과 BOUND/A0을 확인하지만 **fees.apply를 호출하지 않는다**. 같은 bucket 경쟁/철회 검사도 R 유지 및 UNKNOWN withdraw 거부를 확인할 뿐이다. 따라서 FAILED/UNKNOWN의 실제 보존 resultVersion을 apply에 전달해409·원장/영수증 무효과를 확인해야 한다는 설계 조건은 아직 직접 검증되지 않았다. 제품의 실제 성공 검사·귀속 검증 코드는 존재하며 이를 읽은 사실을 실행 assertion으로 치환하지 않는다.

필요 보완: 정상 공통 fee 거래에서 잘못된 intent/hold/원단위/다른 seller·order의 attempt/금액/원참조를 한 축씩 바꾼 API 또는 의도적 SQL 위반 거래를 구성한다. 409 또는 제약 위반과 전체 participant 불변을 확인하고, 정상 binding·동일 명령 재생은 유지한다. 실제 FAILED·UNKNOWN 결과 저장 후 동일 assessment/attempt/resultVersion으로 apply 거부 및 A0/R유지/receipt0·hold유지를 확인한다. 동의 false 거부, true 동일키 재생, 같은키 동의/quote/내용 변경 거부와 저장 consent 수정/삭제 거부를 구분한다. 잘못된 SQL 쓰기는 제약 음성 검사로 명시하고 정상 fixture의 성공을 SQL로 주입하지 않는다. **현재 검증 공백이며 새 제품 결함 재현 NOT RUN**이다.

### 확인한 대응표와 복구 근거

| 범위 | 확인한 assertion과 인정 범위 |
| --- | --- |
| C01/C09 원금 | 실제 상품/쿠폰/행사/주문으로 33,001의 원단위10,000/10,000/10,001, 26,004의8,001/8,001/8,002+배송2,000, 무료배송60,000을 만든다. 선택 ordinal 잔여1원·부분/나머지/별도배송 합계와 parent budget를 확인한다. 이후 상품가격/회수비 변경은 원 snapshot에 영향0, 원 COMMITTED 예약 불변, 각 원단위 입고1·최종 쿠폰복구/재통지 보상0을 연결했다. 무료 잔여 재가격 계산0과 0원 SHIPPING provider 호출0도 있다. |
| C02/C03/C05 | 발송과 같은 원단위 취소의 barrier 경쟁 성공1/409, 두 seller 명령의 부모 revision 직렬화와 재견적·UNKNOWN 예약 포함 한도, DB budget 초과쓰기 거부를 확인했다. 회원/GAS 취소의 parent/unit/budget 실제 대기, SUPPORT CANCEL의 attempt 실제 대기 후 제어 시계 만료·전체 rollback이 있다. R-C09의 공통 fee 경로로 확대하지 않는다. |
| C04/C06 T1/T4 | T1 HOLD/CLAIM/REFUND/HISTORY/OUTBOX5지점의 checked 예외를 실제로 도달시키고 전체 participant 원복/같은 명령 복구를 확인한다. 성공 T4는 ITEM7·SHIPPING7 각각 실제 T3 SUCCEEDED 이후 result receipt/수량/입고/배송비예약 또는 쿠폰/종결/감사/Outbox 및 마지막 lease를 검증한다. |
| C07/§11.5·12.4 | 누적A5,000=공제3,000+미수납2,000·이전 미수납 자동상계0, 같은 원배송 경쟁의 slot1/409, 실제 split한 두 원배송8,000과 refund12,000, accept/bind8단계 두 bucket 전체 rollback을 확인했다. FAILED의 payment예약0/feeR유지, UNKNOWN의 양쪽예약 유지·철회409가 있다. apply 뒤 RETURN hold HELD/ACTIVE, 부모 PAID/재고·쿠폰복구0은 금융효과와 물리 반품 완료를 분리한다. |
| C09 정책 경합 | buyer/seller가 서로 다른 실제 actor다. 저장 선행은 옛 견적409/주문효과0, 주문 선행은 정책3,000 snapshot 보존/상품만5,000이다. 관측 파일의 공유 seller identity 잠금과 blocking PID를 확인했다. |
| C10/C11 | SHIPPING FAILED의 새 sequence, UNKNOWN 예약 유지/새시도 거부/동일 시도 query, ITEM 성공 불가역, terminal 명령 재생을 확인했다. CONFIRMED+금융 REFUNDED의 마지막 확정/마지막 SHIPPING 두 순서에서 조기종결0→CLOSED1·재통지 보상0을 확인했다. |

성공 T414지점 검사는 `cancellationEffectParticipants`가 job 실행 메타데이터만 제외하고 재고 balance/원 reservation/입고unit·movement, 금액·수량, settlement/closure, 영수증·이력·Outbox를 비교한다. 매 지점 reached 및 job 비성공을 확인하고 실제 provider submit/effect1을 유지한다. 각 leg의 주입을 마친 뒤 같은 저장 결과로 정상 재개하고 재생 후 불변을 확인한다. 마지막 lease는 저장 RUNNING/leaseUntil을 확인한 후 실제 JdbcJobs에 주입한 Clock을 만료시켜 production requireOwned가 거부한다. 실제 벽시계 대기나14회 child kill로 표현하지 않는다.

`succeededCancellationChildCommitBeforeAckReplaysWithoutAnotherEffect`는 별도 실제 child가 성공 T4를 commit한 뒤 Jobs.complete 직전 멈추는 ACK 모드다. `first-effect` 원문 marker와 테스트의 COMPLETED/job RUNNING 관측 후 강제종료, 저장 lease 뒤의 시각을 쓰는 새 child 재시작을 확인했다. job SUCCEEDED/attempts2, 업무 snapshot 불변, provider submit/effect1·입고unit1·refund effect1을 검사한다. 이는 **성공 ITEM T4의 ACK 재시작 증거**이며 SHIPPING 성공 ACK child까지 별도 실행한 것으로 확대하지 않는다. SHIPPING은 앞의 rollback/재생 검사로 연결한다.

### 구형 JSON·이행·명시 동의

`legacyReturnPolicyUsesTrueMissingJsonExplicitConsentAndCurrentRefundPorts`는 새 독립 DB를 V36까지 만든 뒤 당시 컬럼/정책 필드 없는 quote·line JSON과 재구성한 canonical fingerprint/request hash를 복사한다. 최신 DTO의 null 필드만 둔 fixture가 아니다. 21개 migration 적용 후 repeat0, 원 JSON·원단위 금액 불변, 새 child의 공개 OrderCreationService 옛 동일키 exact retry를 확인한다. **과거 바이너리를 실행한 것은 아니다**. 기본 marketplace schema의 지원 환경이며 임의 schema 지원을 증명하지 않는다.

child는 기존 UNRESOLVED와 미래 MIGRATED_DEFAULT_3000 주문을 구분하고 현재 상품5,000 변경에도 과거 JSON/금액/7일/원 배송완료+604800초를 유지한다. 새 legacy quote3,000·명시 동의 필요, 미동의 거래의 hold/intent 포함 전체 rollback, 동의 뒤 실제 Refunds/provider/result/apply·재생1회와 물리 반품 효과0을 확인한다. 원 `transition-focused1` child runtime-result의 trueMissingPolicyJson/explicitConsentAndActualRefund/oldMoneyPeriodAndJsonUnchanged=true, physicalReturnCompleted=false를 읽었다. R-C10의 추가 동의/귀속 음성 검사는 이 정상 경로로 대체하지 않는다.

### 원문 실행 결과와 한계

고정 evidence의 Failsafe XML/result를 직접 대조했다. money-race-focused2 DB9, auth-closure-focused2 DB9, fee-policy-focused3 DB1, transition-focused2 DB1, success-effect-focused1 DB1, success-ack-focused1 DB1은 각각 실패·오류·skip0이다. fee-policy-focused1은 DB6/실패1, legacy-focused1은 DB6/실패1·오류4, legacy-focused2는 DB5/실패1, transition-focused1은 DB5/실패1이다. 이 실패 실행에 포함된 통과 assertion만 인정하며 전체 GREEN으로 부르지 않는다. fee-policy-focused2는 DB XML 없는 컴파일 준비 실패로 업무 assertion NOT RUN을 유지한다. 실제 금액·legacy·정책 경합·성공 복구의 각 이름과 assertion을 대조했으며 테스트 총수로 계약 충족을 대신하지 않았다.

success-effect/ack, transition-focused1, fee-policy-focused3 원 Surefire도 각각196/실패·오류·skip0을 확인했다. 모든 위 root 결과는 **failed / preservation passed**다. 새 선택없는 전체/독립 전체, 브라우저, 실제 Toss와 사용자 직접 확인은 이 증거로 통과 처리하지 않는다. 과거 실패/자원의 전수 보존 재감사를 이번에 중복 수행한 것으로 주장하지 않는다.

이번 쓰기는 이 검토 절뿐이다. 제품·고정 artifact·기존 증거를 수정하지 않았고 새 하네스·새 agent·커밋·푸시·배포·D 작업은 하지 않았다. R-C09/10을 대응표의 별도 backend 미검증 항목으로 인계한다. 기존 성공 T4 실행 중 문구는 작업 문서 마지막의 실제 완료 결과와 함께 읽으며, 남은 공백을 단순 최종 재수집으로 축약하지 않는다.

## backend-contract-fix-review1: R-C09/R-C10 보강 재검토 — 2026-11-01 KST

판정: **R-C09 ADDRESSED, R-C10 ADDRESSED**. 고정 소스의 직접 assertion과 보존된 선택 실행을 대조한 검증 공백 해소 판정이다. R-C01~08의 기존 scoped ADDRESSED는 유지한다. C08 실제 비회원 HTTP 후보·브라우저·선택없는 개발 전체·지정 독립 전체는 이 판정에 포함하지 않으며 **P09-C 전체는 미완료**다.

### 고정 식별과 변경 범위

지정 `.local-tools/p09-c-execution-20261030/backend-contract-fix-review1/review-report.md`를 먼저 읽었다. source375 및 artifact401의 파일별 SHA256 불일치가 각각0임을 직접 확인했다. source-manifest SHA256은 `41C501F184D473319BDE209CB7FA1057F54331E8C8F91D8A6007B914C7FC2930`, artifact-manifest는 `F33762B4BB40EEE68BDC9DAAE3CE611E73D15C0F89AACCEAA517A55B8C6017C2`, green-inputs는 `2AA933E130756F771F7746EB9BFE94276E489B4305B09F02E372842EFF1B7F10`이다. 이전 backend-contract-review1 대비 backend main/V41~57 변경0을 확인했다. live 제품 변경을 판정 근거로 사용하지 않았다. 같은 IT 파일의 별도 브라우저 fixture와 browser/frontend 필수 등록 증가는 이번 해소 범위에서 제외한다.

### R-C09 — ADDRESSED

`returnFeeOwnerCommandsRecheckMemberAndGasAfterLastActualLockWait`는 실제 paid/delivered RETURN 준비와 실제 권한 포트를 사용한다. member/GAS 각각 QUOTE(bucket), ACCEPT_BIND(기존 payment budget), WITHDRAW(refund attempt)의 별도 JDBC 연결 잠금을 잡고 `pg_stat_activity`의 실제 Lock 대기를 확인한다. 정상/만료의12대조에서 정상200 및 quote 추가/BOUND/WITHDRAWN, 만료404 및 `returnFinancialParticipants` 전체 불변을 검사한다. accept→reserve→bind는 같은 거래로 묶이므로 마지막 bind 대기에서 만료되면 앞선 consent/assessment도 함께 원복되어야 한다. 단순 API 진입 시 만료 검사로 대체하지 않는다.

회원은 저장 expiry_time에 도달하는 제어 Clock, GAS는 저장 idle_expires_at을 읽어1microsecond 뒤로 주입한 Clock을 사용한다. GAS는 실제 저장 deadline 초과를 별도로 assert하며 세 진단 JSON도 이를 뒷받침한다. 실제 벽시계 자연 만료를 기다린 검사로 확대하지 않는다. participant 비교는 기존 취소/환불/hold/원단위/이력/Outbox에 fee quote/bucket/assessment/consent/binding/application/withdrawal/result/support-command를 더한다.

`returnFeeSupportRetryRechecksActualSupportSessionAfterRefundWait`는 실제 FAILED RETURN에 대해 owner403과 무효과를 먼저 확인한다. 실제 SUPPORT 세션 포트로 attempt 잠금 대기 뒤 정상200/새 시도2개·support-command1·fee R 유지, 만료401/전체 participant 불변을 대조한다. CANCEL SUPPORT 검사와 다른 공통 RETURN retry 경로의 공백을 직접 보완했다.

### R-C10 — ADDRESSED

`returnFeeCrossOrderBindingsAndWrongMoneyOrReferenceRollBackEveryParticipant`는 다른 정상 주문에서 얻은 유효한 attempt/intent/hold/unit를 교차 연결해409 또는404/409와 전체 participant 불변을 검사한다. intent-unit의 hold/order/seller-order/item 및 fee binding의 foreign attempt SQL 변경은 제약 위반과 불변을 확인하는 음성 검사다. SQL로 정상 금융 성공을 주입한 것이 아니다. 정상 동일 accept/bind 재생은 같은 결과/무효과로 남는다. 별도 정상 후보에서 실제 hold→intent→accept→reserve→bind 거래의 금액 또는 원 quote fingerprint를 다른 거래 값으로 바꿔409와 전체 원복을 확인하고, 이후 정상 binding의 정확한 환불액도 확인한다.

`returnFeeFailedAndUnknownActualResultCannotApplyFinancialAssessment`는 실제 제공자 모의 결과를 저장한 `resultVersion`으로 `fees.apply`를 호출한다. FAILED/UNKNOWN 각각409, 전체 participant 불변, A0/R 유지/application receipt0/hold ACTIVE를 assert한다. payment 예약은 FAILED0·UNKNOWN 환불액 유지로 구분한다. 과거 결과 조회만 하던 검사를 apply 거부 근거로 대체한 공백이 해소됐다.

legacy child는 명시 동의 true의 동일 키·동일 내용 재생과 무효과, 같은 키의 다른 quote ID/false 동의/변경 fingerprint409와 무효과를 직접 검사한다. 저장 consent UPDATE/DELETE 및 quote JSON 내용 UPDATE도 제약 위반/전체 financial 불변으로 확인한다. 기존 미동의 전체 rollback과 true 동의 실제 provider/result/apply 정상 경로는 유지된다. 신규4개 IT 메서드는 고정 required-tests에도 등록되어 있다.

### 실제 실행·실패 이력과 남은 범위

| 실행 원문 | 직접 확인한 결과와 인정 범위 |
| --- | --- |
| `.local-tools/p09-c-backend-contract-focused1-20261101` | DB5 중4통과/준비 오류1/실패0/skip0. SUPPORT 대기, 교차귀속·금액/참조, 비성공 apply, legacy 동의 검사는 통과. owner 검사는 STORE_REJECTED 준비 오류이므로 이 실행으로 통과 처리하지 않는다. |
| `.local-tools/p09-c-backend-contract-focused2-20261101` | owner DB1 실패1/오류·skip0. gas-quote-true 기대404/실제200을 그대로 보존한다. |
| `.local-tools/p09-c-backend-contract-focused3-20261101` | owner DB1 실패·오류·skip0. 위12대조와 저장 deadline 초과 assertion이 완료됐다. |

각 원 Surefire XML은196/실패·오류·skip0이다. 고정 사본에는 DB XML이 있고 Surefire 원문은 각 원 실행 경로에서 확인했다. focused3 진단의 GAS quote 예는 이전1800초 advance `03:00:52.096875500`이 저장 deadline `03:00:52.096876`보다 앞임을 보여준다. 보강 후 주입값은 `03:00:52.096877`이다. 제품 변경이나 기대404 완화 없이 저장 정밀도에 맞게 실제 만료를 보장한 fixture 수정으로 판단한다. focused2 실패를 성공으로 소급 변경하지 않는다.

세 root result는 모두 **failed / preservation passed**다. 선택 실행의 통과 메서드를 합쳐 전체 하네스 통과라고 표현하지 않는다. 마지막 입력 식별과 과거 선택 실행 결과를 하나의 전체 실행으로 합치지 않으며, 고정 소스의 선택없는 전체 재수집은 남아 있다. 실제 Toss·사용자 직접 확인도 이번 범위가 아니다.

이번 작업은 고정 증거 읽기와 이 검토 절 추가뿐이다. 제품·하네스·기존 증거·실패 기록을 수정하거나 새 검사·새 agent·커밋·푸시·배포·D 작업을 수행하지 않았다.

## c08-review1: 실제 GAS HTTP 수정·UI/브라우저 준비 정적 검토 — 2026-11-01 KST

판정: **C08 실제 GAS HTTP 라우팅 수정과 익명401→404 보호계약 변경은 이 범위에서 수용**한다. QuotePanel 표시 변경과 테스트 fixture에서 새 제품 결함은 확인하지 않았다. 다만 아래 실제 Playwright 설정 사본 누락 때문에 브라우저 설정 검토는 부분 미검증이다. R-C01~10 scoped ADDRESSED는 유지하며 C08 브라우저 실행·C 전체·선택없는 개발 전체·지정 독립 전체는 미완료다.

### 고정 증거 식별

지정 `.local-tools/p09-c-execution-20261030/c08-review1/review-report.md`를 먼저 읽고 고정 source/evidence를 확인했다. source379 및 artifact402 파일별 SHA 불일치0이다. source-manifest SHA256 `855B29D1EE115D6C50CB98526ECA684921EC0B54280EE80AC7A564504CF463A6`, artifact-manifest `A03D1C6709C79AF9BD80D61EE29A745259425B35071C55C033646C76700FBE6B`, green-inputs `7322352E46497065EFBD55F7CAE750096A85CC57233D7AEC882F7055A53F8EBB`를 직접 확인했다. live 제품 소스는 판정에 사용하지 않았다.

### 현재 GAS·소유권·CSRF와 응답 계약

AuthConfiguration의 변경은 `/api/orders/{number}/cancellations` GET과 해당 접수·quote·`{id}/withdraw` POST만 permitAll 경로에 추가한다. 취소 전체 하위 경로나 SUPPORT 경로를 공개하는 wildcard가 아니다. 계정 인증 게이트를 지나도록 허용하지만 업무 권한을 부여하는 것은 아니다. ClaimController는 서버 세션/현재 principal/version/GUEST_ACCESS로 Authority를 구성하고, JdbcClaims의 get/cancel/withdraw 및 quote의 JdbcCancellationOrders는 현재 `queries.lockOwned`를 호출한다. JdbcOrderQueries는 실제 회원 세션 또는 `guests.requireCurrent`의 소유자를 주문 owner 조건과 결합하고 잠금 뒤에도 재검사한다.

기존 CSRF 설정은 변경하지 않았으며 취소 POST는 webhook 예외에 포함되지 않는다. 컨트롤러의 정상 응답 no-store도 유지된다. 익명 GET의401→404는 계정 게이트의 거부가 현재 소유권 숨김 거부로 이동한 결과다. 다른 주문·무효/로그아웃 GAS도404로 거부하고 정상 데이터나 명령 권한을 제공하지 않으므로 기존 GAS/주문 소유권 계약과 양립한다. 이 기대 변경은 실제 GAS 접근 허용이라는 계약 수정에 따른 것이며, 기존 실패 결과를 삭제하거나 통과로 소급하지 않는다.

원 DB XML의 `cancellationHttpGuestUsesCurrentGasWithoutAccountLogin` RED는 실제 CSRF·GAS 로그인 후 GET 기대200/실제401이다. focused1에서 실제 계정 로그인 없는 GAS GET/no-store/quote/cancel202/exact replay/withdraw200, 익명·다른 주문404, CSRF 없는 quote403, logout 뒤 GET/quote404와 participant 불변이 통과했다. 철회 후 payment 예약0/provider0도 직접 검사한다. 기존 member quote/withdraw도 같은 실행에서 통과했다. 기존 private GET 검사는 익명 기대401/실제404로 실패했고, 기대404 및 소유권 숨김 설명만 바꾼 focused2에서 기존 private progress/receipt assertions와 함께 통과했다.

### QuotePanel·테스트 전용 fixture

상품별 정책 문구를 allocation details의 바깥 형제 paragraph로 옮겼다. 닫힌 details를 펼치지 않아도 구매 전 회수비를 읽을 수 있고 금액 배분·합계 계산은 바꾸지 않는다. 신규 단위 assertion은 `toBeVisible`, `closest('details') === null`, 기존 큰 정수 합계 보존을 함께 검사한다. 보고서의 standalone256 통과를 이번 고정 artifact의 직접 실행 검증으로 확대하지 않는다. 최종 전체 재수집이 필요하다.

ShipmentBrowserFixture는 `src/test` 안의 `c3-fixture` 컨트롤러이며 별도 test security chain도 해당 profile의 test class에만 존재한다. seed lookup은 테스트 초기화를 위한 인증 없는 합성 식별자 조회다. 실제 주문 database 조회와 worker 조작은 현재 `queries.requireOwned`를 먼저 호출하고 worker의 attempt가 그 주문에 속하는지도 확인한다. 새 job을 임의 성공 처리하지 않고 대상 기존 job을 OneJobJdbc/실제 WorkerEngine으로 처리하며 제어 Clock을 사용한다. fixture의 권한 검사와 worker 실행은 별도 거래이므로 제품 endpoint의 잠금 후 권한 보장을 이 fixture로 대신 증명하지 않는다.

seed는 실제 생성/결제/배송 포트로 준비하며 latest 시나리오는 실제 FAILED 결과→현재 SUPPORT retry→새 UNKNOWN 결과를 만든다. SQL은 모의 제공자 동작 설정에 사용하며 성공 금융 결과를 직접 주입하지 않는다. GAS 브라우저 준비는 실제 CSRF/login에서 발급된 쿠키를 새 문맥으로 옮긴다. 기존 로그인 폼 E2E를 대체하거나 새로운 로그인 폼 검증으로 세지 않는다. 여섯 신규 시나리오의 API/DB assertion은 정적으로 확인했으나 이 사본의 브라우저 동작은 NOT RUN이다.

### 선택 모드와 고정 설정 사본 공백

고정 PostgresJdbcIT는 `P09_C_BROWSER_ONLY`가 정확히 `1`일 때만 `order-cancellation.spec.ts` 필터를 추가하고 기본 실행에는 필터를 넣지 않는다. child 전체 제한260초를 유지한다. 고정 required-browser는130개이며 루트 verify.ps1은 선택 모드와 무관하게 전체 필수 이름에 `Assert-Coverage -ExactNames`를 적용한다. 따라서 신규12 선택 실행을 전체 통과로 오인하는 경로는 이 코드에서 확인되지 않았다.

**증거 보완 필요:** 실제 child 명령은 `--config=auth-e2e/playwright.config.ts`인데 source379에는 이 파일이 없고 `frontend/playwright.config.ts`만 있다. 후자는 `./e2e`와 D4 서버를 사용하는 다른 설정이다. 그러므로 현재 auth-e2e의 기본 testMatch, workers1/retries0/개별 timeout 유지 여부를 이 고정 사본으로 승인할 수 없다. 해당 실제 설정의 고정 원문과 SHA를 별도 보충해야 한다. live 파일로 대신 판단하거나 기존379 manifest를 덮어쓰지 않는다. 이는 제품 결함 판정이 아니라 요청된 정적 설정 검토의 증거 공백이다.

### 실제 결과와 한계

고정 DB XML은 gas-http-red1 DB1/실패1, focused1 DB4/실패1(나머지3통과), focused2 DB1/실패0이며 모두 오류·skip0이다. 각 원 실행 Surefire는196/실패·오류·skip0을 직접 확인했다. 세 root는 모두 failed/preservation passed다. 원문 경로는 `.local-tools/p09-c-gas-http-{red1,focused1,focused2}-20261101`이며 모든 실패 기록을 유지한다. 수집130은 실제 브라우저 실행·시간 통과가 아니다. 실제 Toss·사용자 직접 확인·전체 Offline도 이번 승인 범위가 아니다.

이번 작업은 이 검토 절 추가만 수행했다. 제품 수정·새 하네스·커밋·푸시·배포 및 후속 단계 작업은 수행하지 않았다.

### c08-config-supplement1 후속 확인 — 설정 사본 공백 해소

기존 c08-review1을 변경하지 않은 별도 `.local-tools/p09-c-execution-20261030/c08-config-supplement1`의 source3개를 읽었다. artifact-manifest SHA256 `23ADE93A4F6D1E5A8014FAD6BB3F8999EAC7096DA9684338E75E2E8E13AA2028`, 파일별 해시 불일치0이다. 실제 `frontend/auth-e2e/playwright.config.ts` SHA256은 `5A217076229491C4EB21A184A294BC7BA92B946D626CAB34EB6952A79BD2CE46`이다. 위의 설정 사본 누락은 이 보충으로 **해소**되었다.

실제 설정은 testDir `.`/testMatch `*.spec.ts`, desktop/mobile, workers1/retries0/forbidOnly true/기본 timeout30000을 유지한다. 기존 서버 재사용은 false다. package.json의 일반 test:e2e와 달리 통합 IT는 위 auth-e2e 설정을 직접 명시한다. backend/verify-db.ps1은 RunBrowser를 A3_RUN_BROWSER로 전달하며 C 전용 필터를 기본 주입하지 않는다. C 전용 선택은 앞서 확인한 IT의 명시 환경값1에만 적용되고,260초 child 제한 및 루트130개 필수 이름 검사는 그대로다. 환경에 C 전용 값이 남으면 선택 실행이 될 수 있으므로 최종 전체는 선택값 없이 실행해야 하며, 남은 값으로12개만 실행해도 전체 coverage 통과로 인정되지 않는다.

신규12 실행은 보충 요청 시 진행 중이며 이번에 실행 결과·시간 통과를 주장하지 않는다. 사본 이후 late GET 검사를 실제 비어 있지 않은 private progress로 강화했다는 후속 알림은 확인했으나 해당 delta는 이 고정 사본 밖이다. 최종 고정 소스·실행 증거에서 별도로 대조해야 한다. 이 보충 확인 뒤에도 C08 브라우저·전체 Offline·P09-C 완료 판정은 열려 있다.

## browser-review1: 신규12 실제 결과·delta·첫 전체 용량 검토 — 2026-11-01 KST

판정: **신규6종×desktop/mobile12의 보존된 실행 결과와 assertion을 이 범위에서 수용**한다. 아래 준비 delta에서 새 제품 결함이나 assertion 약화는 확인하지 않았다. **첫 선택없는 전체 실행의 child aggregate260→287초 제안도 기술적으로 수용**한다. 이는 전체 실행 통과 판정이 아니며 현재 고정 소스의 값은 여전히260초다. R-C01~10 scoped ADDRESSED와 C08 GAS/404 판정은 유지한다. 기존118 회귀·선택없는 개발 전체·같은 소스 지정 독립 전체는 아직 NOT RUN이다.

### 고정 식별·실제 결과

`.local-tools/p09-c-execution-20261030/browser-review1/review-report.md`를 먼저 읽고 source414/artifact438 파일별 SHA256 불일치0을 직접 확인했다. source-manifest는 `6DD660A3453726ED5B3243EEC50B30C34C238D2F513522D8E8DA4F15CFA40B27`, artifact-manifest는 `93EDA5DB943AB304FE17A71156B637DF80C36E783F2EFC307B76CBD3B6A6B26C`, green-inputs는 `EDB50F959E1E78A2B7F5F0A5EFF697657C4A7BFF8928035CDC6F45D01D6C2AA0`이다. c08-review1 대비 backend main 변경0이다.

고정 원 browser-report와 child timing을 직접 읽었다. `.local-tools/p09-c-browser-focused1-20261101`은12중8통과/4실패/skip0/retry0, child exit1/75312ms다. guest desktop/mobile 로그인 기대200/실제401 두 건과 conflict desktop/mobile30000ms timeout 두 건을 유지한다. 전자는 다른 fixture 비밀번호 사용, 후자는 Panel 준비 전 count0으로 열기 생략이라는 소스 delta와 맞는다. 이 실행에서 해당 본문까지 통과했다고 간주하지 않는다. 실패 대기를 포함한75.312초를 정상 용량 근거로 쓰지 않는다.

`.local-tools/p09-c-browser-focused2-20261101`은 실제12/실패0/skip0/flaky0, 모든 result retry0/expectedStatus passed이며 child exit0/18648ms다. 개별 duration 합15915ms와 child 차2733ms를 원 JSON에서 재계산했다. driver DB는 focused1에서1/실패1, focused2에서1/실패·오류·skip0이다. 두 원 실행 Surefire는 각각196/실패·오류·skip0이다. 두 root는 **failed/preservation passed**이며 필수 나머지를 선택하지 않았으므로 전체 GREEN으로 표현하지 않는다.

### 신규 assertion과 준비 delta

| 범위 | 확인한 실제 검사와 해석 |
| --- | --- |
| member 원단위/응답 유실 | 실제 API 응답을 얻은 뒤 route abort로 응답 유실을 모의한다. 같은 body 재전송, claim/hold/refund1, 원단위 금액, T3 성공 뒤 입고0→T4 뒤 선택단위 입고1/provider1/effect1, 기존 snapshot/COMMITTED 예약 보존을 DB와 대조한다. 실제 외부망 장애 재현으로 확대하지 않는다. |
| 실제 GAS | fixture와 같은 정확한 GUEST_PASSWORD로 실제 CSRF/login 쿠키를 발급한다. 계정 로그인 없이 quote/철회, 타인·익명404, 구매 승인 권한 비확장, 철회 뒤 예약0/provider0/입고0 및 logout 뒤 숨김을 확인한다. 비밀번호 수정은 인증 우회가 아니다. |
| 충돌/조회 실패/logout | 다른 현재 owner 요청으로409를 만들고 GET503만 네트워크 응답으로 주입한다. private command 재사용 금지, 실제 logout 후 데이터/명령 소거와 DB 불변을 확인한다. helper는 진행 재조회 버튼이 enabled일 때까지 기다린 뒤 열기 버튼 존재를 판단하도록 보강했다. timeout 확대나 assertion 삭제가 아니다. |
| 늦은 private GET | late fixture가 실제 acceptCancellation으로 APPROVED 진행을 먼저 만든다. GET 길이1/화면 접수 표시 및 지연시킨 실제 응답이 원 progress와 같음을 확인한 뒤 logout/focus/응답 해제 후 주문번호·접수 금액·철회 사유·명령 부재를 검사한다. 빈 배열 응답만 지연시키던 공백을 해소했다. |
| 구매 전 정책 | seller0/5000 변경, 펼치지 않은 구매 전 문구, 옛 견적409·주문생성0, 새 견적으로 저장된5000 정책 및 기존 주문 snapshot 불변을 실제 API/DB와 대조한다. |
| 최신 결과 | 실제 FAILED→SUPPORT 새 시도→UNKNOWN의 sequence와 금액 예약을 확인한다. 철회 form/POST0 및 DB 불변, 응답 state만 COMPLETED로 바꾼 음성 UI 검사의 alert/완료문구0을 확인한다. 모순 완료는 서버에 저장한 성공이 아니라 UI 입력 거부 검사다. |

기존 `purchase-complete.spec.ts`는 B 커밋 원문과 대조했으며 `...product` 쓰기를 categoryId/slug/title/description/state/version 명시로 바꾼 한 부분만 다르다. ProductController 쓰기 whitelist와 맞고 기존 이름·주문 이력/금액/원 제목/배송/DB assertion은 그대로다. 조회 전용 returnPolicy를 PUT에 되돌려 보내는 준비 오류를 피하는 변경이다. 이 기존 시나리오는 신규12 실행에서 실행되지 않았으므로 최종 전체에서 확인해야 한다. 기존 browser 필수118 이름이 새130에 모두 남아 있음도 직접 대조했다.

### aggregate287초 수용 근거와 조건

B 개발 원 child timing의252477ms와 신규12의18648ms를 직접 확인했다. 합271125ms는 현260000ms보다11125ms 크다. 두 별도 child의 시작 비용을 포함한 보수 추정이며 실제130 전체 시간이나 선형 성능을 증명하지 않는다. 검사 수에 비례한 `ceil(260×130/118)=287`초를 재계산했고, 이 안은 추정합보다15875ms 여유를 둔다.

따라서 **첫 전체 실행 전에 범위 증가를 반영하는 aggregate 한 곳의287초 변경을 수용**한다. 기본 개별30000ms·catalog60000ms, workers1/retries0, forbidOnly, 기본 전체130 및 필수 이름·누락/실패/skip/flaky 거부는 유지한다. 기존 개별 실패를 시간 확대로 덮지 않았고 이미 정상 통과한 신규12 비용을 근거로 삼았다. 제품 성능 개선·원260초 충족으로 표현하지 않는다. 변경 후 새 입력 식별로 선택값 없는 전체를 실행하고 초과/실패 시 원문과 원인을 남긴다. **자동 추가 확대는 승인하지 않는다.** 이 검토에서 하네스 값을 직접 수정하거나 실행하지 않았다.

이번 쓰기는 이 검토 절뿐이다. 고정 사본·기존 실패·자원은 그대로 두었고 제품 수정·새 하네스·새 agent·커밋·푸시·배포·후속 단계는 수행하지 않았다. P09-C 전체 완료 판정은 개발 전체와 같은 소스의 지정 독립 전체 결과까지 보류한다.

## dev1 실패 원문·fixture 격리 방향 검토 — 2026-11-01 KST

**제안된 세 fixture 보완 방향은 기술적으로 적합하나 수정본·실행 증거 확인 전 해결 판정은 보류**한다. 최종 독립 전체는 시작하지 않았다. 제품 cap/보호계약/실행된 migration을 바꾸거나 실패 검사를 제외하는 안은 수용하지 않는다.

`.local-tools/p09-c-dev1-20261101` 원 result는 failed/preservation passed다. 입력 SHA256 `F7DF70BAE3588B1617F97B875A5B043D04EBAF63CC1A9C30A23C92FB1FCDCB4F`,552입력/변경0은 dev1-final-classification 기록과 연결한다. 원 XML에서 unit196·phone1 통과, PG355=304통과/실패2/오류49/skip0을 확인했다. 오류48개는 LOCAL_MAILBOX_FULL, 하나는 p4d 이행의 V51 relation already exists다. 실패는 GAS 예상 거부 미발생과 HTTP signup 기대201/실제503이다. 브라우저 원 JSON은130통과/skip0/unexpected0/flaky0, child exit0/276090ms로287초 안이다. 원260초 충족이나 전체 통과로 표현하지 않는다. frontend·Node NOT RUN을 유지한다. dev1 원문1067개와 emulator3개 manifest의 파일 해시 불일치0을 확인했다.

| 방향 | 수용 근거와 수정본에서 필요한 직접 assertion |
| --- | --- |
| activeMember 전용 mailbox | 기존 helper가 공유 signup/mailbox를 계속 사용하며 LocalTestMailbox는 유효 메시지1000개에서 명시 거부한다. 업무 준비용 회원마다 실제 SignupService·실제 store/hasher/transaction·전용 LocalTestMailbox·실제 verifyEmail을 쓰는 격리는 적합하다. SQL ACTIVE 주입이나 가짜 인증으로 대체하지 않는다. shared mailbox의 메시지 수/기존 내용이 helper 전후 불변이고, 전용 mailbox 발행 메시지의 실제 token으로 활성화·token 재사용 거부가 동작함을 확인한다. 여러 helper의 수신자/토큰 혼선이 없어야 하며 보존 map은 같은 email 재사용 시 기존 mailbox를 덮어 버리지 않도록 식별한다. 실제 HTTP signup/CSRF/오류/공유 mailbox 경로와 cap1000 거부·rollback 검사는 유지한다. 기존 mailbox를 clear하거나 cap을 늘려 통과시키지 않는다. |
| GAS 저장 deadline | dev1 실패 줄은 C05 IDLE의 advance1800 뒤 거부 미발생이다. 원 기록만으로 해당 시각의 nanos/micro 차이를 확정할 수는 없다. 실제 저장 idle deadline+1micro를 주입하고 isAfter assertion·before/oldAdvance/deadline/injected 진단을 남기는 보완은 적합하다. IDLE뿐 아니라 parent/unit/budget 실제 대기에도 동일 원칙을 적용하고, 저장된 대상 세션을 정확히 식별한다. 실제 authority 포트·현재404·전체 cancellation participant 불변 및 VALID/OTHER/VERSION/RESET 대조를 유지한다. 제어 Clock 검사이며 실제 벽시계 자연 만료로 표현하지 않는다. 만료를 넘긴 뒤에도 허용되면 fixture 원인으로 단정하지 말고 제품 결함을 조사해야 한다. |
| p4d 이행 독립 DB | 기존 보조 schema에서 최신 migration을 실행하면 명시 marketplace V51과 충돌한다. 새 고유 PostgreSQL DB의 default marketplace에서 V16 준비→실제 기존 SKU 자료 복사→최신 이행→반복 적용0을 검사하는 방향은 지원 환경과 맞는다. 과거 schema/실패 DB는 삭제하지 않는다. 기존 history20/5·version순서 및 SKU code/초기 inventory0·reserved0·version0 assertion을 보존하고, 복사 전후 원 SKU 식별/자료와 원 DB 불변, 실제 새 DB 식별과 migration 결과를 기록한다. 필요한 컬럼을 명시해 과거 shape를 구성하고 최신 상태를 직접 성공 주입하지 않는다. V41~57 수정이나 이행 skip으로 해결하지 않는다. 임의 schema 지원을 입증한 것으로 표현하지 않는다. |

공유 mailbox 고갈과 HTTP503의 인과는 수정본의 공유 자원 불변·실제 HTTP 회귀를 통해 닫아야 한다. 오류49개를 총수만으로 모두 해결됐다고 간주하지 않는다. 수정 고정본에서 위 직접 assertion을 대조한 뒤 새 선택없는 개발 전체로 필수 누락/실패/skip 및 frontend·Node까지 재검증해야 한다. 기존 실패 원문은 그대로 남기며 자동 timeout/용량 확대는 하지 않는다. 이번 작업은 원문 읽기와 이 짧은 검토 절 추가뿐이며 제품 변경·새 하네스·커밋·푸시·배포를 수행하지 않았다.

## dev1-fixture-review1: 세 fixture 보완·읽기 전용 필드 거부 재검토 — 2026-11-01 KST

판정: **mailbox 격리, C05 저장 deadline 만료, p4d/p4b 독립 DB 이행 보완을 선택 실행 범위에서 수용**한다. **catalog 읽기 전용 필드의200→400 기대 변경도 수용**한다. dev1 실패를 소급 통과시키지 않으며 dev2·지정 독립 전체는 NOT RUN이다. R-C01~10 scoped ADDRESSED와 dev1 브라우저130 통과는 유지하되 P09-C 전체 완료로 확대하지 않는다.

지정 `.local-tools/p09-c-execution-20261030/dev1-fixture-review1/review-report.md`부터 읽었다. 고정 source414/artifact461 파일별 해시 불일치0, source-manifest SHA256 `D994092EDA68EDFB23F7D783A8B851E111B45DE3260E0FB6E60C903D0D906035`, artifact-manifest `81A4947816D144DD1DF15304D486C1FA9688BD7D307685D9C417A46DF156B4A3`, green-inputs `6A153776910635B0DD5436C21ABF6D4396D739BE28B0E49934A0FAC8782ABD21`를 확인했다. runtime-freeze1 입력과 겹치는 파일의 변경은 PostgresJdbcIT/required-tests 두 개다. 제품·실행 SQL·프런트/브라우저·이미 승인된287초는 이번 보완으로 변경되지 않았다.

| 항목 | 직접 확인한 보완과 판정 |
| --- | --- |
| mailbox | activeMember만 실제 IdentityStore/hasher/transaction을 사용하는 SignupService와 전용 LocalTestMailbox로 register/verifyEmail한다. mailbox는 UUID 키 map에 남기며 기존 shared를 clear하지 않는다. mail(email)은 shared 우선/fixture fallback으로 기존 소비된 토큰·잘못된 용도 검사에 접근한다. 최종 직접 검사는 실제 shared 메시지를 먼저 하나 발행하고 전후 list 동등성을 확인한다. Message의 동일 객체와 내용이 유지되고 서로 다른 전용 token, ACTIVE/실제 HTTP login200, pending의 PENDING_EMAIL/login401→실제 verify→ACTIVE/login200, token 재사용 거부 및 메시지 보존을 검사한다. SQL ACTIVE 주입이 아니다. |
| C05 | guest buyer와 현재 token hash 및 revoked_at 조건으로 실제 저장 idle deadline을 선택한다. IDLE와 parent/unit/budget 실제 잠금 대기에서 deadline+1micro 주입/isAfter assertion/404/전체 participant 불변을 유지하고 VALID/OTHER/VERSION/RESET도 보존한다. focused1 원 진단 IDLE의 oldAdvance `04:05:00.940557700`은 저장 `04:05:00.940558`보다 앞이고 새 주입은 `04:05:00.940559`다. 해당 재실행에서 과거 준비 방식의 정밀도 한계를 직접 보여주지만 dev1 당시 미기록 nanos를 소급 확정하지 않는다. 제어 Clock 검사다. |
| p4d/p4b | freshLegacyDatabase는 고유 새 DB명을 검증하고 실제 연결 대상을 확인하며 DB 식별 파일을 남긴다. default marketplace에서 V16/V14 준비 자료를 명시 컬럼으로 복사하고 실제 최신 migration/replay0을 실행한다. p4d의 history20/5·version 순서, 복사 SKU 전체 동등성, 새 inventory0/reserved0/version0, 원 DB SKU/재고/이력 불변이 유지된다. p4b는 기본 SKU backfill과 실제 생성 중 제약 예외/상품 생성0 rollback을 유지한다. 원 Maven의41/43개 이행→57과 반복 적용을 연결하며 임의 schema 지원으로 확대하지 않는다. |
| readonly400 | 실제 요청 필드명은 `isHiddenByAdmin`이다. 이 필드를 포함한 판매자 PUT400과 전체 catalog_product 행 불변을 먼저 assert하고, 필드 제거 후 정상PUT200 및 공개404/목록 숨김을 유지한다. 현재 ProductController 명시 whitelist와 일치한다. 운영자 숨김을 판매자가 바꾸지 못한다는 보호는 유지·강화되며, 잘못된 입력을 조용히 무시하던 응답과의 차이를 명시했다. 이후 stale/store closed/rollback 등 기존 assertion을 제거하지 않는다. |

원 XML 대조 결과 mailbox-isolation-red1은 DB1/실패1(공유 증가0 기대/실제2), dev1-fixture-focused1은 DB53/52통과·실패1·오류0·skip0이며 남은 실패는 catalog 기대200/실제400이다. focused2는 catalog DB1, focused3은 비어있지 않은 shared를 포함한 강화 mailbox DB1이 각각 실패·오류·skip0이다. 네 원 실행 Surefire도 각각196/실패·오류·skip0이다. 원 경로는 `.local-tools/p09-c-{mailbox-isolation-red1,dev1-fixture-focused1,dev1-fixture-focused2,dev1-fixture-focused3}-20261101`이다. 모두 root failed/preservation passed이며 선택 실행 전체를 GREEN으로 부르지 않는다.

집계 정정: 전달 보고서의 'dev1 실패50 unique'와 달리 원 dev1 XML의 실패/오류 메서드명은 **51 unique**이며 focused1의53 메서드에51개 모두 포함됨을 직접 대조했다. 기존 실패군 누락0이다. catalog는 focused2, 최종 mailbox 강화는 focused3 결과로 구분해 연결한다. 이 숫자 정정은 제품 차단 사항이 아니며 최종 작업 기록에서도51로 맞춰야 한다.

이번 범위에서 추가 제품 수정이나 필수 검사 축소가 필요한 지적은 없다. 새 입력을 고정한 선택없는 dev2에서 전체 Java/phone/브라우저·frontend/Node를 다시 확인해야 한다. 이번에는 이 검토 절만 추가했으며 새 하네스·제품 수정·최종 독립 전체·커밋·푸시·배포를 수행하지 않았다.

## dev2-browser-review1: 두 브라우저 경계 보완 재검토 — 2026-11-01 KST

판정: **환불 tuple 비교와 guest logout 준비 경계 보완을 수용**한다. 기존 UI/권한/무효과 assertion을 약화하지 않았으며 진단 selector의 기본 전체 선택·필수 누락 거부도 유지된다. dev2 실패는 그대로 남긴다. dev3·지정 독립 전체는 NOT RUN이며 P09-C 전체 완료 판정은 보류한다.

지정 보고서를 먼저 읽고 `.local-tools/p09-c-execution-20261030/dev2-browser-review1` source414/artifact441의 파일별 해시 불일치0을 확인했다. source-manifest SHA256 `EB3B60AB97F9FCD5B42A499FD566DB4C79177A1738DF6E569A4C31FB993FEB34`, artifact-manifest `052D06C1AE793E3A261423DC3ABA1EA6987C9C903BA1049844ABABC247808422`, green-inputs `2BCE564B1EE31166FCCD5F0AB9B22F7E455FEB98CB66A4BE05D92CE6BEE6425C`다. 이전 고정본 대비 runtime 변경은 IT의 진단 선택 분기와 두 spec뿐이다. 제품·SQL·required·287초·개별30/catalog60·workers1/retries0은 변경되지 않았다.

### dev2 원문 결과와 실패 해석

dev2 원 PG XML은356중355통과/브라우저 driver1실패·오류/skip0이며 unit196도 실패·오류·skip0이다. phone1 통과와 합553 실행은 dev2 기록에 연결한다. root failed/preservation passed, frontend/Node NOT RUN이다. browser JSON은 강제종료로 없으며 콘솔의 `ok`를 직접 세면 **127개**다. mobile guest-lookup 및 latestUnknown 두 실패와 마지막 coupon 한 개 미완료를 구분한다.128은 진행번호 오독이므로 통과 수로 쓰지 않는다. child timeout은287021ms다. 개별 실패를 단순 총예산 초과로 재분류하거나 예산을 추가 확대하지 않는다.

원 error-context에서 latestUnknown은 seq2 UNKNOWN/seq1 FAILED의 역순만 다르고, guest lookup은 retry 버튼 disabled→detached로 click10000ms timeout이다. 후자의 정확한 network 순서는 trace off이므로 확정할 수 없다. 첫 route의 abort가 끝나기 전에 바꾸었을 가능성을 검토 가설로만 유지한다.

### 보완의 계약 적합성

- **환불 비교:** JdbcClaims.progress는 purpose/attempt_id로 정렬하므로 sequence 순서를 보장하지 않는다. 검사에서 `[...p.refunds]` 복사본만 purpose/sequence로 정렬하고 정확히 ITEM1 FAILED·ITEM2 UNKNOWN 두 tuple을 요구한다. 누락·중복·잘못된 목적/상태는 여전히 실패한다. 실제 UI는 원 서버 응답을 그대로 받고 최신 UNKNOWN 철회 불가·POST0·모순 COMPLETED 입력 거부·DB 불변 assertion을 유지한다. UI에 정렬한 가짜 응답을 주입해 제품 결함을 가린 변경이 아니다.
- **로그아웃 경계:** 첫 요청의 `route.abort` 완료 후 count를 올리고 count1·retry enabled·실제 현재 GAS references200을 확인한 뒤에만 route를 교체한다. 이후 `route.fetch`로 실제 logout200을 commit한 후 응답 유실을 모의하고 alert/retry enabled·기존 cookie401·명시 재시도·로그인 화면/새로고침 후 연결 주문 부재를 그대로 확인한다. 버튼 대기나 timeout을 늘리는 대신 실패 전송과 성공 commit의 두 상태를 직접 분리했다. 이는 보완 후 경계 입증이며 dev2 당시 순서의 소급 증명이 아니다.
- **진단 선택:** `P09_C_BROWSER_ONLY=retry-boundaries`일 때만 두 파일과 두 제목 grep을 추가한다. unset은 전체, 기존1은 신규12로 유지된다. grep 문자열 자체는 정규식 부분일치이나 현재 collection은 정확히 요청된 두 제목×두 project4임을 확인했다. 원 `.local-tools/p09-c-browser-prepare4-20261101` collection도 default130/기존12/새4다. required130과 루트 ExactNames 검사는 그대로여서 선택4를 전체 통과로 인정할 수 없다. dev3는 선택 환경값 없이 실행해야 한다.

### 보완 후 실제 결과와 남은 검증

고정 browser-retry-focused1 원 browser JSON은 desktop/mobile의 두 제목 총4개가 expected passed/actual passed/retry0이고 skipped/unexpected/flaky0이다. child exit0/8103ms, driver DB1·원 Surefire196은 실패·오류·skip0이다. root는 필수 미선택으로 failed/preservation passed다. 원 경로 `.local-tools/p09-c-browser-retry-focused1-20261101`의 결과이며 전체130 시간·성공 증거로 확대하지 않는다.

추가 제품 결함이나 assertion 삭제 지적은 없다. dev2의 전체 실패·미완료 coupon 및 미실행 frontend/Node는 새 선택없는 dev3에서 다시 확인해야 한다. 기존 R-C01~10과 이전 수용은 해당 범위에서 유지한다. 이번 작업은 고정 증거 읽기와 이 절 추가뿐이며 새 하네스·제품 변경·최종 독립 전체·커밋·푸시·배포를 수행하지 않았다.

## P09-C 최종 동일 소스 독립 전체 검증 — 2026-11-01 KST

**최종 판정: 승인된 로컬·DEMO P09-C 구현과 공통 fee 계약을 독립 검토 범위에서 수용한다. 필수 미해결 검토 지적 없음.** R-C01~10과 후속 fixture/UI/브라우저 보완의 직접 assertion을 유지한 동일 소스에서 개발 dev3와 이번 지정 독립 전체가 모두 통과했다. 이전 '독립 NOT RUN/전체 미완료'는 당시 기록이며 이 절이 독립 실행에 대한 최신 판정이다. 부모의 과거 원문 전수 최종 감사·상위 문서 마감은 별도 담당이며 이번 실행 보존으로 이를 대체하지 않는다.

### 실행·소스 식별

개발 dev3의 실제 종료·passed/preservation passed와 runtime-freeze3를 확인한 뒤 별도 새 경로에서 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-c-independent1-20261101`을 직접 실행했다. 경로 부재를 먼저 확인했고 MAVEN_ARGS/MAVEN_OPTS/P09_C_BROWSER_ONLY/P09_B_BROWSER_DIAGNOSTICS를 명시적으로 비웠다. JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS는 없었다. root wrapper가 새 전화 emulator를 자동 기동·종료했으며 수동 PhoneEmulatorReady나 과거 emulator를 재사용하지 않았다.

root 실행은 **13:31:21~13:48:46 KST, status passed/preservation passed**다. 바깥 프로세스는 **13:48:47 실제 exit0/nativeExit0**으로 종료했다. 증거는 `.local-tools/p09-c-independent1-20261101`, outer 로그/종료 기록은 `.local-tools/p09-c-execution-20261030/independent1-outer.log`와 `independent1-outer-exit.json`이다.

HEAD `c9f924123b42e8d413f3ff70a095ffec06fd9593`, 입력552 SHA256 `2BCE564B1EE31166FCCD5F0AB9B22F7E455FEB98CB66A4BE05D92CE6BEE6425C`로 dev3/runtime-freeze3와 같다. 시작 전·종료 후 파일별 불일치0, 종료 후 입력 추가/삭제0을 직접 확인했다. 문서 추가는 하네스 runtime 입력 밖이며 제품·검사·migration·하네스 변경은 하지 않았다.

### 실제 전체 결과

| 검사 | 직접 확인한 결과·원문 |
| --- | --- |
| Java553 | Surefire unit·Arch196 + Failsafe PostgreSQL356 + PhoneProviderEmulatorIT1. 모든 suite/case 실패·오류·skip0. `db/build/{surefire-reports,failsafe-reports}/TEST-*.xml`, `java-cases.json` |
| 브라우저130 | 기존118+C12, 필수 이름 누락0·각 실행1회·expected/actual passed·retry0·skip/unexpected/flaky0. `db/browser-report.json`, `browser-coverage.json` |
| 프런트256 | XML/ExactNames 필수 대조 passed, typecheck/lint/unit/build 통과. `TEST-frontend.xml`, `frontend-coverage.json`, 각 명령 로그. build의 크기 안내는 출력된 경고이며 실패나 검사 생략이 아니다. |
| Node75 | tests75/pass75/fail0/cancelled0/skipped0/todo0. `test.txt` |
| 필수55묶음 | Java53+frontend1+browser1 모두 passed. 저장된 coverage뿐 아니라 원 XML/브라우저 JSON을 Gate parser로 다시 읽어 필수 대조를 확인했다. |
| 보존 | 원 before/after에 Assert-Preservation을 다시 적용해 passed 확인. 기존 DB·컨테이너·리스너·미디어에 대한 하네스 정의 범위를 따른다. 과거 DB 전체 행의 재감사로 확대하지 않는다. |

브라우저 child는 **exit0/272388ms**다. 승인287초보다14612ms 짧고 이전260초보다12388ms 길다. 개별30초/catalog60초·workers1/retries0·정식130·누락/실패/skip 거부를 유지했다. 제품 성능 개선이나 원260초 충족으로 표현하지 않으며 추가 시간 확대는 없었다.

### 제약과 인계 최종 대조

앞선 고정 사본 검토의 assertion 적합성 판단을 이번 전체 수집과 연결했다. 최신 P09-C.md의 C01~11·§11.5/12.4 표에 이름으로 명시된 대표18메서드는 모두 독립 Java 결과에 존재하며 실패/skip0이다. 명칭 목록은 `independent1-constraint-methods.json`에 남겼다. 총수만으로 계약을 승인한 것이 아니라 다음 직접 근거를 기존 검토와 연결한다.

- C01~04/09: 실제33,001·26,004·무료배송의 원단위/원배송비 보존, 발송/취소 실제 경합, 부모 결제 예약 한도, COMMITTED 불변·선택단위 입고/누적 전체 쿠폰1회.
- C05/06/10: 실제 회원/GAS/SUPPORT·CSRF/소유권·잠금 뒤 만료, UNKNOWN 유지/FAILED 명시 재시도, 실제 child takeover와 commit-beforeACK 재생, T4/이력/Outbox/최종 lease 전체 rollback·복구. 제어 Clock·오류 주입과 실제 벽시계/외부망 장애를 구분한다.
- C07/§11.5/12.4: 원배송별 누적max/후속 차액·A/R·미수납, 실제 RETURN 귀속/동의/FAILED·UNKNOWN apply 거부, fee 금융 적용 후 hold 유지·재고/쿠폰/물리종결0. receipt-only/교차귀속 음성 SQL은 정상 거래 성공 주입이 아니다.
- C08/09/11: 실제 API·DB와 UI 금액/수량, 응답 유실 동일키·logout/늦은 private 응답 소거·최신 상태 검증, 구매 전0/5,000 정책 표시와 구형 JSON/명시 동의 보존, 미완료 주문 조기종결 금지·최종 혼합 상태 종결.

최신 작업 문서의 D 인계는 실제 ReturnFees/FinancialIntents/ReturnSources/Refunds/ClaimSupport 공개 서명과 대조했다. 호출자 거래·현재 권한·실제 hold/attempt/result 귀속, RETURN 결과 보존/재개, 금융 apply와 물리 반품 완료의 분리, 기존 미수납 자동상계/실수납/면제 미구현, legacy UNRESOLVED와 새 명시 동의 경계가 명시되어 있다. D/E/F 구현이나 존재하지 않는 RETURN HTTP/UI의 검증으로 확대하지 않는다.

### 원문 보존·실패 이력·한계

새 root 원문 **1146파일**, 새 `.local-tools/phone-emulator-3a874193ba194ff0ae2e498692164218` **3파일**을 execution 루트의 `independent1-manifest.json`/`independent1-emulator-manifest.json`에 기록하고 파일별 해시 불일치0을 확인했다. emulator 결과도 exit0이며 독립 증거 경로를 가리킨다. `independent1-final-audit.json`은 소스·원 XML·필수·보존 재대조를 연결한다. 종료 후 읽기 감사의 첫 parser 호출은 report 폴더 대신 build 상위를 지정해 XML 미발견으로 끝났고, 올바른 두 report 경로로 수정해 읽었다. 검사 재실행이나 소스 변경은 없었다.

dev1의 mailbox/GAS/schema 실패, dev2의 두 브라우저 실패·마지막 미완료/287초 timeout 및 모든 이전 RED/준비 오류/선택 root failed는 그대로 유지한다. 이번 통과로 과거 결과를 소급 변경하지 않는다. 부모 담당의 과거 전체 증거 해시 감사를 중복 수행하지 않았다.

실제 Toss SANDBOX·사용자 직접 화면 확인·실기기/상용 부하는 미검증이다. 공통 fee 계약 통합은 테스트 조정 코드와 실제 로컬 포트/DEMO 결과를 사용하며 사용자 실반품 E2E가 아니다. D 물리 회수·검수·반품 입고/완료는 범위 밖이다. 이번에는 독립 전체 실행·새 증거 manifest·이 검토 기록만 수행했으며 커밋·푸시·배포·다음 단계는 진행하지 않았다.
