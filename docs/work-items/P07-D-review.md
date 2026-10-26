# P07-D 독립 검토

**최신 판정: P07-D 범위 독립 전체 검토 통과. R-PD01~05 해결 확인, 필수 잔여 차단 없음.** 아래 최종 독립 검증 절을 따른다. 과거 보류·실패 기록은 당시 결과로 유지한다. 커밋·P08·푸시·배포는 수행하지 않았다.

## 범위·현재 판정 — 2026-10-27

**정적 검토: R-PD01~04 보완과 독립 전체 검증 전까지 완료 판정 보류.** 개발1 `.local-tools/p07-d-dev1-20261027` 실행 중 코드·문서·assertion만 대조했다. 검토 세션은 테스트/하네스를 실행하지 않았고 제품/검사 소스를 변경하지 않았다. 아래는 정적 경로 분석이며 독립 실행 재현 결과와 구분한다.

README, development-workflow, verification-harness, P07 공통 계약 및 P07-D.md 착수 결정, P07-progress.md, 요구사항 쿠폰 사용/복구 규칙을 기준으로 한다. C 독립 통과와 개발 세션 실제 사용자의 A~D 순차 요청을 선행 근거로 삼았다. 기준 HEAD `cda1486b885a1913714ba70f4a938fbaedb1748a` 위 미커밋 A~D다. 제품 주문/결제/환불 worker·공개 mutation·P08·커밋·푸시·배포는 범위 밖이다.

## 제약 → 현재 검사 연결

| 제약 | 실제 assertion | 현재 판정 |
| --- | --- | --- |
| D01 상태·복구 규칙 | transitionsAndRestoration:예약 정각commit 거부/expire허용, 금지전이, 동일commit 재시도, 부분/미확정미복구, 원만료정각 재사용불가 | 코드 확인·독립 미실행 |
| D02 예약 원자성 | couponReservationIsAtomicAndIdempotent:호출자 rollback, 다른checkout 동시1승자, 동일retry/입력변조, 예약/이력1개 | 코드 확인 |
| D02 전체 배치 | couponReservationBatchAndAuditRollback:두매대 선택 전체예약·역순retry·부분retry거부, 감사실패 rollback·commit 호출자rollback·release재시도 | checkout 전체 식별 보완 필요 |
| D02 잠금/재시도 | couponReservationCrossSellerAndConcurrentRetries:교차판매자 예약, 같은예약 동시retry, 같은cause 복구동시retry | 코드 확인 |
| D03 경합·현재조건 | couponCommitReleaseAndExpiryRace:commit/release 경합, 실제 쿠폰잠금 대기 뒤 clock 정각commit거부/expire; couponReservationRechecksWaitsAndQuoteContent:계정/기한대기·위조지문·현재가격 | 코드 확인; 분리된 자격/변경 경계 보강 필요 |
| D04 과거명령·원만료 | couponRestoreCannotAffectNewReservation/couponRestorationPreservesExpiryAndCause:부분/미확정미복구, 동일cause·변조거부, 새예약 후과거release/restore 무영향, 원만료 유지·호출자rollback | 코드 확인 |
| D05 fixture·화면 | couponFixtureIsDisabledOutsideTests:profile없는context에빈없음·test-classes·401/CSRF403. PC/모바일 fixture예약/commit응답유실/재요청/타인404/부분/전체복구·새예약상태, CouponStore 상태단위 | 코드 확인·전체 실행 대기 |

## R-PD01 / P2 — 동일 checkout에 다른 회원·견적의 신규 배치 추가 가능

위치: `JdbcCouponReservations.reserve/prior`, `V28__coupon_reservations.sql`.

현재 prior는 이번 요청의 reservationId들만 조회한다. 신규 ID뿐이면 기존 checkout 예약 존재 여부를 검사하지 않고 진행한다. DB도 `UNIQUE(checkout_id,store_id)`만 두므로 다른 매대는 충돌하지 않는다.

따라서 회원A/매대X/QuoteX를 checkout K로 예약한 뒤, 회원B/매대Y/QuoteY를 **새 reservationId·sellerOrderRef와 같은 K**로 요청하면 각 요청 내부 검증·현재견적 비교를 모두 만족하고 추가 예약될 수 있다. 같은 회원이라도 서로 다른 전체 Quote의 배치를 다른 매대로 이어 붙일 수 있다. 'checkout 한 건·회원 한 명·전체 Quote 선택쿠폰 전체batch, 같은 전체batch만 retry'라는 착수 계약이 호출 간에는 보장되지 않는다. 이후 checkout 기반 commit 등에 서로 다른 회원/견적 예약이 묶이는 위험이 있다. 내부 신뢰 호출자라는 제한이 이 명시적인 입력 일관성 검사를 대체하지 않는다.

checkout별 최초 회원·정규 전체batch/Quote 식별을 영속화하고 직렬화하거나 동등한 DB 보장을 둔다. 서로 다른 계정/매대는 기존 계정·매대 잠금이 겹치지 않을 수 있으므로 단순 존재조회만 추가하면 동시 신규 요청 경쟁을 막지 못한다. 같은 checkout의 다른회원/전체견적/분리batch는 순차·동시 모두409, 원래 전체batch 역순재시도는 동일 결과, 충돌 loser의 쿠폰/예약/감사 변화0을 검증한다. 새 checkout으로의 정상 재사용은 유지한다.

## R-PD02 / P2 — 기존 배치 재시도 결과가 동시 전환 전후를 섞을 수 있음

위치: `JdbcCouponReservations.java` 첫 prior 호출 및 prior의 개별 optional SELECT.

빠른 기존예약 반환 경로는 잠금 없이 reservationId마다 별도 SELECT를 수행한다. 기본 READ COMMITTED에서 두 쿠폰 배치의 첫 행을 RESERVED로 읽은 뒤 다른 거래가 배치 전체를 COMMITTED로 원자적으로 확정하고, 다음 행을 COMMITTED로 읽으면 retry 결과가 `[RESERVED, COMMITTED]`가 된다. DB에 실제 존재한 적 없는 혼합 batch 상태를 반환하며, '기존batch재시도는 현재상태반환' 계약과 배치 단위 후속 처리 판단을 깨뜨린다. request_hash는 입력 고정값이므로 후속 hash조회로 이 문제를 감지하지 못한다.

기존 행·hash를 한 SQL snapshot에서 읽어 전체batch를 확인하거나, 신규 자격을 소급 적용하지 않는 적절한 배치/쿠폰 직렬화로 일관된 결과를 반환한다. 기존 retry의 회원 활성/매대 공개 조건을 새로 요구하는 방식으로 바꾸지 않는다. 실제 두행 배치에서 retry 읽기와 commit/release의 교차 지점을 latch로 고정해 결과가 전환 전 전체 또는 전환 후 전체인지 검사한다. 단일 예약의 동시retry 성공만으로는 이 문제를 검증할 수 없다.

## R-PD03 / P2 — 예약 자격·상태변경 rollback 및 사용회차 경합 증명 공백

다음은 구현 결함 확정이 아니라 요구 제약의 실행 근거 보완 사항이다.

- 현재 reservationRequest는 memberId가 seller owner와 같다. wait-member 검사는 구매자/판매자 계정 역할을 분리하지 못한다. 서로 다른 buyer/seller를 사용해 실제 계정/매대 잠금 대기 후 seller 정지·매대 비공개/정지 또는 자격상실, 쿠폰/혜택 조건 변경이 새 reserve를 거부하는지 확인한다. 가격 변경은 현재 사전 변경만 검사하므로 잠금 대기 중 변경과 구분한다.
- 감사 오류 주입은 reserve에만 있다. commit/release/expire 및 restore에서 쿠폰 변경 후 이력 INSERT가 실패할 때 쿠폰 version/state, 예약 state/version, 복구행/이력 전체가 rollback되는지 검사한다. commit/restore 호출자 후반 실패 검사는 이미 있으나 DB 감사 실패와는 다른 경계다. 다행 배치의 후반 실패는 앞선 행도 모두 되돌아야 한다.
- 같은cause 동시restore는 있으나 서로 다른cause의 동일 사용회차 전체확정 복구 경쟁은 없다. 성공복구1회·복구 이력의 restored=true1개·쿠폰 version1회 증가를 검증하고, 이미 새 예약이 존재할 때 오래된 사용회차에 새cause가 도착해도 새 예약/쿠폰에 영향0인지 확인한다. 기존 검사는 주로 이미 처리한 같은cause를 재조회한다.
- deadline의 미래/15분 상한/원쿠폰만료/microsecond 정규화와 전체batch 재시도 조건을 경계값 assertion에 연결한다. 위조 Quote 검사는 현재 total·fingerprint 변조와 가격변경을 확인하며, 이를 모든 SKU/쿠폰/회원 매핑 변조의 전수 검사라고 표현하지 않는다.

commit/release/expire가 reserve 전체batch의 일부 ID에도 허용되는지는 후속 호출 책임과 함께 문서화한다. 현재 구현은 동일 checkout 소속이면 부분집합 전환을 허용한다. 전체batch 강제가 reserve에만 필요한 정책이면 명시하고, 상태전환도 전체batch가 계약이라면 누락ID를 거부하고 검사한다. 이 부분은 기존 문서만으로 금지 여부를 확정하지 않아 별도 확정 결함으로 세지 않는다.

## R-PD04 / P2 — 미사용 예약의 전체·확정 복구를 충돌로 거부하지 않음

개발 측 자가 점검 제보를 현재 소스와 계약에 대조했다. restore는 full=true/confirmed=true라도 상태가 RESERVED/RELEASED/EXPIRED이면 restorable=false로 계산하고 reason=STATE의 coupon_restoration 행을 저장해 정상 반환한다. 계획의 RESERVED→COMMITTED/RELEASED/EXPIRED, COMMITTED→RESTORED 및 상충 명령 거부와 맞지 않는다. 후속 조정자가 아직 사용되지 않았거나 해제된 예약을 전체환불 완료로 잘못 전달한 경우 오류를 인지하지 못한다. 특히 RESERVED에서 원인ID를 무처리 결과로 소비하면 나중에 COMMITTED가 된 뒤 같은 원인ID로 정당한 복구를 재시도해도 최초 STATE 결과에 고정된다.

쿠폰 잠금 후 full/confirmed가 모두 true인 요청은 COMMITTED에서 복구, RESTORED에서는 기존 정책의 ALREADY_RESTORED 무처리로 구분하고 RESERVED/RELEASED/EXPIRED는409로 거부한다. 부분·미확정은 기존 무처리 정책을 유지한다. 동일 입력의 이미 기록된 원인ID 재조회와 새 원인ID의 상태 검증 순서를 명시한다. 세 금지 상태별409 및 쿠폰/예약/이력/복구행 변화0, 특히 RESERVED에서 거부된 cause가 이후 정상commit/restore에 사용 가능한지 검사한다. 이미RESTORED의 다른cause와 새예약 무영향은 R-PD03 검사와 연결한다. 이번은 정적 확인이며 독립 재현은 하지 않았다.

## 확인한 설계·후속 절차

계정 UUID 정렬→매대 owner 정렬→쿠폰 UUID 정렬 잠금, 실제 Quote 내용 재해시와 현재 서버 재계산 비교, UNIQUE 활성사용/복구회차 제약, REQUIRED 거래 참여를 확인했다. 복구 시 AVAILABLE로 되돌려도 원 ends_at을 바꾸지 않고 reusable=false/RESTORED_EXPIRED를 남기므로 원만료 연장과 구분한다. 신뢰된 내부 RefundCompletion과 src/test fixture가 제품 공개 HTTP에 노출되지 않는 구조도 확인했다.

개발1 종료 후 보완과 동결 소스를 확인하고 새 경로의 루트 `./verify.ps1 -Offline` 독립 실행을 별도 요청받아 수행한다. 최종 문서에는 소스 hash·실제 결과·제약 근거·실패/skip/누락·보존/미검증 범위를 기록한다. 기존 서버·DB·검증 DB·미디어·데모·실패 이력은 유지한다. 이번 정적 검토 자체에서 새 보존 검사 통과를 주장하지 않는다. B 개발2 등 과거 원인 미확정 실패도 후속 성공으로 덮지 않는다.

## R-PD01~04 보완 재검토 — 2026-10-27

- V29가 기존 V28을 수정하지 않고 checkout PK·member·request_hash의 배치 테이블과 예약 FK를 추가한다. 동일checkout의 member/hash 일치 여부를 INSERT/행잠금 후 검사한다. 기존 데이터에서 같은checkout의 서로 다른member/hash가 발견되면 migration을 명시 실패시키며 일치 데이터는 배치로 채운다. 보존/충돌 rollback 검사가 추가됐으나 green3 완료 결과는 아직 확인하지 않았다.
- prior는 행과 request_hash를 한 SQL로 읽는다. 새 latch 검사는 SELECT 반환 후 다른 거래가 전체commit해도 이전 두행이 모두 RESERVED이고 다음retry는 모두COMMITTED임을 확인한다. 개발 review-red 로그에서 이전 구현의 `[RESERVED, COMMITTED]` 혼합 assertion 실패를 직접 확인했다.
- 별도 buyer/seller 실제대기7조건, 모든 전환/restore 감사실패 전후5테이블 전체행 비교, 다른cause 동시복구1회/version1증가·새예약 후늦은새cause무영향, deadline정각/15분/원만료/microsecond/비활성후retry를 코드에서 확인했다. 상태전환은 같은checkout 부분집합을 원자적으로 허용하고 reserve만 전체batch를 고정한다는 정책 구체화를 받아들인다. P08 전체결제 호출자는 전체ids 전달 책임을 별도 검증해야 한다.
- restorable은 full/confirmed 요청의 RESERVED/RELEASED/EXPIRED를409로 거부한다. 각 상태에서5테이블 변화0, RESERVED에서 거부된cause를 정상commit 후복구에 사용할 수 있다는 검사가 추가됐다. 부분/미확정·RESTORED 다른cause 정책은 유지한다.

`.local-tools/p07-d-review-red-20261027/maven.txt`의 DB3 assertion 실패와 `.local-tools/p07-d-review-green2-20261027/summary.json`의 규칙1+DB8 실패/오류/skip0을 직접 확인했다. 이는 개발 집중 실행 증거이며 독립 전체 성공이 아니다. 보완 방향은 기존 지적을 해소하지만 다음 새 잠금 경로가 남아 전체 판정은 보류한다.

## R-PD05 / P2 — 배치 INSERT의 회원 FK 잠금이 정렬된 계정 잠금을 앞서 교착 가능

위치: V29 `coupon_reservation_batch.member_id REFERENCES identity_user(id)` 및 reserve의 배치 INSERT→owners 정렬 lockActiveBuyer 순서.

새 INSERT는 회원 FK 확인 과정에서 해당 identity_user 행에 KEY SHARE를 먼저 획득한다. 이후 lockActiveBuyer는 FOR UPDATE다. FK 확인의 KEY SHARE 사용은 [PostgreSQL FK 구현](https://raw.githubusercontent.com/postgres/postgres/REL_17_STABLE/src/backend/utils/adt/ri_triggers.c), KEY SHARE와 FOR UPDATE의 충돌은 [공식 행 잠금 표](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS)에 근거한다.

다음은 코드와 잠금 규칙에서 도출한 경로이며 이번 세션의 DB 재현은 아니다. UUID A<B인 판매자A/B가 서로의 쿠폰을 다른checkout으로 예약할 때, T1은 batch(A) INSERT로 A의KEY SHARE, T2는 batch(B) INSERT로 B의KEY SHARE를 먼저 보유할 수 있다. 그 후 T1은 A의FOR UPDATE를 얻고 B의FOR UPDATE에서 T2를 기다린다. T2는 정렬 첫행A의FOR UPDATE에서 T1을 기다려 순환이 된다. 혹은 T2가 A의FOR UPDATE를 먼저 얻으면 T1의A KEY SHARE와도 충돌한다. 두 명시적 계정잠금의 정렬만으로는 이미 잡힌 FK 잠금을 제거하지 못한다.

개발green1에서 FK가 먼저 FOR UPDATE fixture에 막혔다는 관측은 이 선행 잠금의 증거다. fixture를 FOR NO KEY UPDATE로 바꾸는 것은 특정 대기 관측에 적합할 수 있으나 제품의 교차예약 교착을 해결하지 않는다. 기존 V28 시점 교차예약 통과도 V29 경로의 보장이 아니다.

회원 FK를 포함한 실제 모든 선행 잠금을 계정 정렬과 일관되게 설계한다. 기존 retry의 비활성 회원 허용과 같은checkout 동시충돌 방지는 유지한다. 단순 timeout 확대·재시도로 교착을 가리지 않는다. 새 DB에서 두 거래가 각 batch INSERT를 마친 지점을 latch로 고정한 후 교차예약을 진행해 원래 실패를 재현하고 수정 후 두 건 성공/예약·감사 각1개·교착 없음으로 검증한다. 같은회원의 서로 다른checkout 동시예약도 FK 공유잠금에서 FOR UPDATE 승격 경합이 되므로 포함한다. 제품 소스/테스트 수정 및 실행은 개발 세션이 담당하며 검토 세션은 이번 문서만 갱신했다.

## R-PD05 보완 재검토 — 2026-10-27

V29는 유지하고 V30에서 batch의 member identity FK만 제거했다. reservation의 기존 identity FK는 유지되며, batch(checkout_id,member_id,request_hash) UNIQUE와 reservation 복합 FK가 회원·입력 지문의 일치를 강제한다. reserve는 batch 직렬화→기존 전체batch 재조회→정렬 계정/매대/쿠폰 잠금→reservation INSERT 순서를 유지한다. 따라서 새 batch INSERT에서 계정 KEY SHARE가 먼저 잡히던 원인이 제거되고, 실제 예약의 회원 참조 검사는 정렬 계정 잠금 뒤에 수행된다. 기존 retry에 신규 활성 자격을 요구하지 않는 경로와 동일checkout 입력 충돌 검증도 유지된다.

직접 읽은 개발 증거:
- `.local-tools/p07-d-dev2-20261027/db/maven.txt`: 기존 동시예약·교차판매자 검사 2건에서 deadlock 오류. 이전 정적 가능성이 실제 실패로 확인됐다.
- `.local-tools/p07-d-lock-red-20261027/maven.txt`: 두 batch INSERT 직후 barrier로 순서를 고정한 같은구매자·교차판매자 검사 각각 deadlock, DB 2건 오류.
- `.local-tools/p07-d-lock-green-20261027/summary.json`: 규칙1+DB6 통과, 실패/오류/skip0. 새 두 경합 검사는 두 거래 RESERVED 결과와 각 감사1개·쿠폰version1을 assertion한다. checkout 회원/hash 변조의 DB 거부, V28→V29/V30 이행과 재실행0·기존 충돌 거부도 확인했다.

R-PD01~04의 앞선 보완과 함께 정적 재검토에서 새 잔여 지적은 발견하지 않았다. 이는 개발 집중 증거 확인이며 이 세션의 독립 실행은 아니다. 개발3 전체 실행 결과와 동결 소스 일치, 이후 별도 독립 전체·보존 검증이 남아 P07-D 완료를 선언하지 않는다. 실패2102개+이전12056개 보존은 개발 보고이며 이번 읽기 검토에서 전체 manifest 해시를 재대조하지 않았다. 제품·검사·기존 서버/DB/미디어/데모는 변경하지 않았다.

## 최종 독립 전체 검증 — 2026-10-27

이 검토 세션에서 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p07-d-review1-20261027`을 직접 실행했다. 00:46:18.0886048~00:50:44.7906102 KST, 종료0/status=passed/preservation=passed. Java348(기본163+PostgreSQL184+제공자1), PC/모바일 브라우저68, 프런트 단위162, Node72 통과. 타입·lint·build·하네스 gate와 필수 목록 대조 통과, 필수 실패/오류/skip/누락 없음. 제공자 브라우저 probe는 제공자 통합 검사에 포함되며 일반 브라우저68과 중복 합산하지 않는다.

소스376개는 개발3 inputs 및 실행 종료 후 현재 파일과 동일하다(개발 대비 차이0·현재 해시 불일치0). inputs.json SHA256 `5FFE17663244E3DA156E9D689F2D4EC0E91C056A649E97878A65B7176238E58D`, 기준 HEAD `cda1486b885a1913714ba70f4a938fbaedb1748a` 위 미커밋 P07 변경이다. 이후 이 검토 문서와 증거 사본만 작성했다.

### 제약별 실제 실행 근거

아래 메서드는 이번 독립 `java-cases.json`에서 passed이며 필수 coverage 대조를 통과했다. 각 기대 결과는 현재 검사 assertion과 연결된다.

| 제약/지적 | 검사와 기대 결과 | 독립 결과 |
| --- | --- | --- |
| D01 상태규칙·R-PD04 | transitionsAndRestoration, couponFullRestoreRejectsUnusedStates: 정각/금지전이, 미사용3상태 전체확정복구409·5테이블 변화0, 거부cause의 정상commit후복구 | 통과 |
| D02 원자성·R-PD01 | couponReservationIsAtomicAndIdempotent, couponReservationBatchAndAuditRollback, couponCheckoutCannotAcceptAnotherBatch: 동일요청 재시도, 한승자, 전체batch 고정·다른회원/견적 추가거부, DB 회원/hash 변조거부, 감사/호출자 실패 전체rollback | 통과 |
| D02 재조회·R-PD02 | couponBatchRetryHasOneReadSnapshot: 읽기 뒤 동시전체commit에도 기존2행 RESERVED, 다음retry2행 COMMITTED | 통과 |
| D03 현재조건·R-PD03 | couponReservationRechecksWaitsAndQuoteContent, couponReservationRechecksDistinctBuyerSellerChanges, couponReservationDeadlineBoundariesAndRetry: 실제잠금 대기 후 분리buyer/seller7조건 재검사, 위조견적·기한 경계거부, 비활성후 과거retry 유지 | 통과 |
| D03 경합·R-PD05 | couponCommitReleaseAndExpiryRace, couponReservationCrossSellerAndConcurrentRetries, couponBatchInsertPreservesCrossSellerLockOrder, couponBatchInsertPreservesSameBuyerLockOrder: 전환한승자·정각만료, INSERT직후barrier의 두예약 성공·각감사1·version1 | 통과 |
| D04 복구회차·R-PD03 | couponRestoreCannotAffectNewReservation, couponRestorationPreservesExpiryAndCause, couponDifferentRestoreCausesCannotAffectNextUse, couponTransitionsAndRestoreAuditFailuresRollback: 원만료유지, 다른cause 동시복구1회, 과거명령 새예약무영향, 전환/복구감사 실패5테이블 복원 | 통과 |
| D02 이행보존 | couponBatchMigrationPreservesPriorDataAndRejectsConflicts: 새 스키마 V28→V29/V30 정상자료 이행·재실행0·충돌자료 거부와 원검증schema 불변 | 통과 |
| D05 fixture·화면 | couponFixtureIsDisabledOutsideTests: 비검사profile bean0·test-classes·401/CSRF403. CouponStore 단위 및 coupon-reservations.spec.ts PC/모바일: 예약/사용/부분·전체복구/만료표시·commit응답유실 재시도·타인404·새예약후 과거명령격리 | 통과 |

### 보존·실패 이력·제한

새 DB `d2_it_105c9546420541a6ae3eeae86ed5c518`, 미디어 `.local-tools/p07-d-review1-20261027/db/media`, 에뮬레이터 `.local-tools/phone-emulator-6e9b5b10780c49598261e9354e01ddfc`를 사용했다. before/after 보존 통과, DB 목록225→226·미디어1722→1740이며 기존 항목 보존을 확인했다. 기존 서버·DB·과거 검증DB·미디어·데모를 변경하지 않았다. 과거 DB 보존은 존재 목록 비교이며 모든 과거 DB 내용의 전수 덤프 대조를 뜻하지 않는다.

실행 전후 이전12056+D2102=14,158개 실패 증거의 누락/해시 불일치0. D 초기 서식·미구현·컴파일 실패, review-red 계약3실패, review-green1 잠금관측 fixture 실패, 개발2 실제교착2건, lock-red 결정적교착2건을 원본 그대로 보존했다. V30 보완과 이번 독립 성공은 교착 원인의 해결 근거이며 개발1 성공을 최신 소스 검증으로 소급하지 않는다. P05-D 연결·P06-A/B 기동·P06-C 및 P07-B 시간초과의 원인 미확정 이력은 해결로 바꾸지 않는다.

원본 실행 경로와 `evidence/P07-D-review-final-*`의 선별19개 사본(result/inputs/coverage/Java개별결과/프런트·브라우저coverage/명령로그/before·after/소스·실패보존대조/DB대상·요약·브라우저report·제공자probe)을 연결했다. 전체 제품 결제·환불 조정자는 이번 범위 밖이며 신뢰된 내부 RefundCompletion의 진실성, 후속 P08 호출자의 전체ids 전달과 전체 거래 잠금 순서까지 검증한 것은 아니다. 실제 시간600초 경과 대신 조절 Clock과 실제 DB 대기로 경계를 검증했다. 운영 부하·실기기·실제 결제·사용자 직접 학습은 미검증이다. 커밋·P08·배포·푸시는 수행하지 않았다.
