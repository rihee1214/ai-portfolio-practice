# P08-C 독립 사전 검토

2026-10-27. A/B 마감 후 C 계획과 실제 Orders/Quotes/CouponReservations/Reservations, guest 연결/인증, cart 계약과 잠금 경로를 읽었다. **C 구현 완료 검토·검사 실행·독립 하네스는 미실행**이다. 아래는 현재 인계 계약의 구체적인 통합 위험이며 C 결함이 이미 재현됐다는 주장은 아니다. 제품/검사 수정·D 착수·하위 agent 생성은 하지 않았다.

## R-C01 / P2 — 주문 INSERT의 FK 잠금이 계정 정렬을 앞설 수 있음

현재 Orders.save는 purchase_order.member_id FK와 seller_order.store_id FK를 획득한다. 이후 CouponReservations.reserve는 batch→전체 buyer/seller UUID정렬 계정 FOR UPDATE→매대→쿠폰 순서다. 주문을 먼저 저장하면 이미 잡힌 FK KEY SHARE 때문에 정렬 계정 잠금만으로 교착을 방지하지 못한다. P07 V29에서 수정한 문제 유형이 주문 FK로 다시 나타날 수 있다.

C는 전체 buyer+seller 계정/매대/상품 잠금을 주문 INSERT와 guest 관련 FK보다 앞서 일관되게 준비하는 공개 API 계약을 확정해야 한다. 주문에 필요한 예약/하위/항목 ID를 얻기 위해 먼저 저장하는 현재 인터페이스와 잠금 준비를 연결한다. coupon batch 잠금도 자기 API 내부에 숨겨져 있으므로 단순 호출 순서 도표만으로 완료하지 않는다. 교차판매자 및 같은buyer 두 주문을 실제 FK 획득 구간에서 barrier로 겹쳐 두 거래 완료/교착0·감사1을 검사한다. catalog 수동 재고변경은 매대/상품 접근 후 balance를 잠그므로 이 경로도 표에 넣는다.

## R-C02 / P2 — 재고 선예약 후 쿠폰 재견적은 자기 예약량을 재고부족으로 판단

CouponReservations.reserve는 내부에서 Quotes.calculate를 다시 호출한다. Quotes는 Browse.QuoteOffer.available과 요청수량을 비교한다. inventory.reserve를 먼저 호출하면 같은 거래의 reserved 증가를 내부 재견적이 읽으므로 재고가 정확히 주문수량인 정상 구매가409로 거부될 수 있다.

쿠폰 검증/예약→재고 예약 순서 또는 자기 예약을 명시적으로 고려하는 계약을 정하고, 재고1/주문1+쿠폰의 정상 성공을 반드시 검사한다. 재고1 두 구매의 한승자와 쿠폰/주문/Outbox 패자rollback도 함께 확인한다. 예약 후 재견적을 막연히 한 번 더 하는 방식은 사용하지 않는다.

## R-C03 / P2 — Quotes의 RR 선언은 외부 거래의 최신 권한 잠금을 대신하지 않음

JdbcQuotes의 readOnly/REPEATABLE_READ TransactionTemplate는 REQUIRED이므로 C 외부 READ_COMMITTED 거래에 참여할 때 새로운 RR 스냅샷을 만드는 것으로 간주할 수 없다. Quotes는 조회이며 상품/계정 상태를 동시 변경으로부터 고정하는 계약도 아니다. 쿠폰이 없는 주문은 CouponReservations의 계정/매대 잠금 경로를 아예 지나지 않는다.

쿠폰 유무/회원·게스트 모두 동일한 현재 판매 가능성·계정/판매자/상품/카테고리·행사 검증이 필요하다. 잠금 대기 뒤 다시 읽고 권한·상태·금액을 검증한다. 상품/매대 변경·계정정지·재고수동조정과 실제 lock wait를 관측한 거부 assertion을 연결한다. 새 카테고리/행사 변경까지 어떤 잠금이 직렬화하는지 명시한다.

## R-C04 / P2 — 저장된 구매세션 UUID는 인증 수단이 아니며 성공 retry는 신규 인증과 분리

A의 Draft.purchaseSessionId는 내부 입력 UUID이고 order_checkout_attempt는 이 UUID와 key만 저장한다. 실제 브라우저와 인증된 세션의 결합은 아직 없다. C 공개 요청이 UUID/memberId/guestBuyerId를 직접 받아 저장 포트를 호출하면 접근 경계를 잃는다. 서버 쿠키의 안전한 세션 결합과 현재 회원 일치를 먼저 확정해야 한다.

guest의 credential은 프로세스마다 바뀌는 PRF이며 재시작은 로컬 전화 인증을 무효화한다. 성공 주문 retry를 purchases.verified/attachOrder부터 시작하면 이미 CONSUMED이거나 프로세스 재시작 후 거부된다. 저장된 결과의 권한 있는 조회와 신규 인증소비를 별도 분기하고, NEW 구매자의 생성된 buyerId를 다시 생성해 hash에 넣지 않는다. 같은browser 계정전환·다른browser UUID/키 재사용·소비뒤 동일키·현재 인증 새attempt로 바뀐 뒤 이전 성공 retry의 정책을 명시해 검사한다. 원문 쿠키/비밀번호/복구코드는 hash/log에 넣지 않는다.

## R-C05 / P2 — guest 공개 연결 계약에 expected revision 조건 필요

기존 JdbcGuestAccess.attachOrder(browser,attempt,token,order)는 현재 link_mode/link_revision을 읽지만 호출자가 승인한 revision을 인자로 받지 않는다. NEW 경로 createBuyer도 requireNew(attempt,null)을 사용한다. C 요청 hash에 revision을 넣는 것만으로는 두 탭의 연결선택 변경을 막지 못한다.

추출할 GuestOrders 계약은 예상 attempt/revision/모드와 실제 소비를 같은 거래·잠금에서 검증해야 한다. 준비된 NEW 비밀번호를 거래중 다시 해시하지 않는다. EXISTING은 현재 조회세션+현재 구매인증+동일전화 조건을 그대로 유지한다. 요청 승인 후 다른탭 revision변경 또는 세션폐기/전화 재인증 변경이 일어나면 소비/참조/주문/예약 전체 변화0을 검사한다.

## R-C06 / P2 — 기존 cart API에는 주문 항목의 안전한 일괄 반영 계약이 없음

Carts는 set/clear/merge만 있고 전역 version을 사용한다. 성공 응답 뒤 단순 clear나 SKU별 set(quantity0)를 하면 새 탭의 추가·수량변경을 지우거나 첫 삭제 뒤 version충돌로 일부만 반영된다. 주문 성공 후 cart처리 실패를 주문 실패처럼 표시해 새키 재구매로 이어져서도 안 된다.

서버/방문자 저장소 각각 주문 시작 시 snapshot/revision을 기준으로 해당 항목만 반영하고 변경된 항목은 보존하는 계약을 확정한다. 같은SKU 수량변경·새SKU 추가·주문 응답유실 뒤 같은키복구·cart 반영 재시도에서 추가 삭제0을 검사한다. cart 데이터는 order가 직접 수정하지 말고 공개 cart 계약으로 연결한다.

## C01/C02 보완해야 할 검사 의미

hash는 요청의 정규화된 SKU/수량/선택쿠폰·수령정보·사용자가 확인한 견적 fingerprint·연결revision 같은 의미입력에 바인딩한다. 재계산된 evaluatedAt/새 예약ID/변화한 쿠폰version을 retry hash에 섞지 않는다. 배열순서 차이는 동일하고 내용차이는409가 되는 assertion과 비밀 비포함을 함께 검사한다.

checked exception rollback은 단순 RuntimeException 주입을 이름만 checked로 바꾼 검사로 대신하지 않는다. 실제 거래 경계의 checked 예외 정책 또는 명시적인 rollback-only/안전한 포장 경로를 검증한다. 모든 참여 테이블의 전후 전체행과 감사/Outbox를 비교하고, 일부 참가 API가 REQUIRES_NEW로 빠져나오지 않는지 확인한다. B의 enqueue/append는 REQUIRED지만 claim/ack는 REQUIRES_NEW이므로 주문 생성 중 실행기로 작업을 즉시 돌리는 구조를 사용하지 않는다.

현재 실행 판정은 전부 미검증이다. 통합 잠금 및 retry/guest/cart 계약을 구체화한 뒤 제약별 RED→보완→개발 전체→독립 전체로 검증해야 C 완료를 판단할 수 있다.

## 제안된 통합 순서에 대한 추가 확인 — R-C07 / P2 관리자 감사 FK

구현자가 제안한 guest checkout/attempt/buyer/session→정렬 buyer/seller accounts→stores→categories→products→SKUs→order INSERT→coupon batch→inventory 순서를 실제 코드와 대조했다. V30은 coupon batch의 회원 FK를 이미 제거했으므로 이를 이유로 계정 FOR UPDATE를 약화할 필요는 없다. JdbcPromotions.save는 owner/store를 먼저 잠그므로 store 잠금으로 상품/배송 행사 수정이 직렬화된다는 판단은 현재 코드와 일치한다. product save는 store→category→product, SKU save는 store→product→SKU, 수동재고는 store/product→balance 순서다. 대상 카테고리 등은 선행 store 잠금 후 현재 값으로 다시 확인해야 한다.

남은 숨은 역방향: JdbcCategories.save는 category FOR UPDATE 후 category_event.actor_id FK(V13)를 INSERT하고, JdbcAccountStateOperations는 target account FOR UPDATE 후 account_state_audit.actor_id FK(V7)를 INSERT한다. C가 구매자/판매자에 포함된 관리자 actor A를 먼저 FOR UPDATE하고 category 또는 target B를 기다리면, 관리자 거래가 해당 자원을 보유한 뒤 A의 FK KEY SHARE에서 기다리는 순환이 가능하다. 역할을 겸임하는 회원의 구매를 금지한 정책은 없으므로 평소 race fixture의 별도 관리자만 검사하면 놓칠 수 있다.

관리자 구매/판매 겸임 조건을 포함해 실제 barrier/lock wait로 재현하고, 필요한 actor/target 정렬 준비 또는 다른 근거 있는 설계로 보완한다. 모든 identity 잠금을 일괄 FOR NO KEY UPDATE로 바꿔 우회하지 말고 정지/권한 변경과 기존 FK·잠금 승격 경로를 함께 검증한다. 위는 정적 경로 분석이고 실제 DB 재현 결과가 아니다.

## C 구현 중 정적 재검토 — 2026-10-27, 전체 실행 전

이번 검토는 소스 독해이며 하네스/테스트를 실행하지 않았다. 구현자의 집중 실행과 최종 개발 전체는 별도 근거가 필요하다.

R-C01/02/03/05/07의 구현 방향은 현재 소스와 일치한다. PurchaseOffers는 canonical account→store→현재 category→product→SKU를 잠그며, 주문은 coupon→inventory 순서다. GuestOrders는 expected revision을 같은 거래에서 재검사한다. orderCreationRollsBackAllParticipants는 회원/guest, 실제 IOException 포장/runtime, 7 checkpoint 및 22개 참여 테이블의 전체행 동일성을 검사한다. 관리자 actor FK 회귀는 실제 Categories.save/AccountStateOperations.suspend와 잠금 대기를 사용한다. purchaseAccountLockAllowsAuditForeignKeysButExcludesMutations는 KEY SHARE 성공과 UPDATE/NKU의 55P03을 직접 검증한다. NKU가 역할행을 직접 보호하는 것은 아니며 역할 변경의 부모 account 잠금 규칙이 전제다. 기존 buyer/store 재진입도 NKU여야 하고 cart FOR UPDATE는 주문 commit 이후에만 호출한다.

### R-C08 / P2 — 승인한 견적과 cleanup snapshot의 불일치

OrderCreationPanel은 주문 lines를 quote에서 가져오면서 같은 submit에서 cart.capturePurchase()의 전체 현재 항목을 cleanup snapshot으로 저장한다. GuestCart.capture는 localStorage의 최신 값을 읽는다. 따라서 다른 탭이 견적 A1 이후 B를 추가하거나 A를 2개로 변경했고 이 탭 견적은 A1인 경우, 주문은 A1이지만 cleanup snapshot에 B 또는 A2가 들어가 성공 후 구매하지 않은 항목도 삭제할 수 있다.

cleanup 대상을 승인한 견적의 SKU/수량 및 그 시점의 revision과 묶고 다른 revision은 보존해야 한다. 필요한 assertion: 견적 A1→다른 탭 B추가/A2변경/같은수량 삭제후재추가→A1 주문 성공→변경 항목 모두 보존. 기존 submit 이후 변경·DB revision 검사는 이 견적→submit 간격을 대신하지 않는다. 수정·실행 근거 전까지 열린 지적이다.

### R-C04 추가 — 대기 중 현재 HTTP 세션 철회

CurrentMemberFilter가 요청 초기에 검증한 authVersion은 주문 account 잠금 대기 중 password reset/logout으로 바뀔 수 있다. 현재 active BUYER만 다시 검사하는 것으로는 충분하지 않다. 구현자가 발견한 이 공백에 동의한다. 서버가 얻은 원래 memberId/authVersion/sessionId를 identity 공개 validator로 전달하여 canonical account 잠금 후 session principal 귀속·expiry·authVersion을 검증해야 한다. 신규 주문과 성공 retry 모두 적용하고, HTTP 경로에서 trusted 내부 create로 우회하지 않아야 한다. session 잠금이 필요하면 account→session 순서로 reset의 identity→session 삭제와 맞춘다. 실제 HTTP 잠금 대기 동안 logout/reset 후 401 및 전체 참여행 불변을 검사한다. 이 보완은 구현 중이며 아직 검증되지 않았다.

C 완료 또는 독립 전체 실행 준비로 판정하지 않는다. R-C08 및 HTTP 세션 보완 후 개발 전체 통과·소스 동결을 확인하고 별도 승인된 새 증거 경로에서 독립 전체를 실행한다.

## 동결 구현 최종 assertion 대조 — 독립 전체 진행 중

| 제약 | 실제 검사와 판정 기준 |
|---|---|
| C01 | OrderRequestHashTest, orderRetryReturnsSameResult: 정규화 입력/hash, 동시 동일키 동일번호, 재고1 예약1, coupon/inventory 인계 각1, order_event/outbox 각1, 같은키 다른내용409·다른회원401. |
| C02 | orderCreationRollsBackAllParticipants: 회원/guest × runtime/실제 IOException 포장 × GUEST/ORDER/COUPON/INVENTORY/REFERENCE/AUDIT/OUTBOX checkpoint에서 22개 참여 테이블 전체행 동일. |
| C03 | orderCreationLockOrderAndAuthorityRaces, orderAuthorityChangesAfterActualRowWait, orderAdministratorAuditLocksDoNotCycle, orderCrossSellerAndSameBuyerBatchesSerializeBeforeForeignKeys, orderLastStockHasOneWinnerAndNoPartialCoupon, purchaseAccountLockAllowsAuditForeignKeysButExcludesMutations, orderManualStockAdjustmentSerializesAndRechecksAvailability: 실제 row wait 후 현재 권한 재검사·관리자 감사FK·교차판매자/동일구매자·마지막재고/쿠폰 원자성. |
| C04 | guestOrderConsumesVerificationAtomically, guestOrderRejectsStaleQuoteAndCurrentCredentialChanges: NEW 분리/EXISTING 귀속·revision/조회세션폐기·만료·전화재인증·guest쿠폰금지·stalequote/checkoutwait 실패 전체행 동일. orderHttpRechecksSessionAfterActualAccountWait는 신규/기존성공retry × logout/expired/password 6조합에 HTTP401과 전체 participant 동일. |
| C05 | OrderCreationStore/OrderCart 단위 및 orders-create desktop/mobile: 같은키 응답유실복구·중복클릭·409 새확인·401 재전송0·승인 전 SKU/수량필터·승인 후 revision불변·같은수량 삭제재추가 보존·빈 cleanup no-op·실제 회원/guest 두매대 생성. |

R-C04 보완: CurrentMemberFilter의 서버 request attribute로 authVersion을 전달하고 HTTP controller는 createForSession만 호출한다. canonical account 잠금 뒤 PurchaseSessions가 session FOR SHARE, principal 귀속·expiry·authVersion을 검사한다. 신규와 성공 retry 모두 적용하고 reset의 account→session 삭제와 순서를 맞춘다. 내부 trusted create는 HTTP에서 직접 노출하지 않는다.

R-C08 보완: purchaseWorkflow의 snapshot은 단일 OrderCreationStore에 결합한 closure이며 checkbox 명시 승인에서만 quote SKU/수량 필터 및 revision 복사한다. submit/retry에서 다시 읽지 않는다. 실제 Provider는 고정 auth/cart 인스턴스를 제공하고 cleanup은 현재 owner 일치를 검사한다. 승인 전에 변경된 동일수량 항목의 새 revision은 새 승인에 포함하며, 승인 이후 변경은 보존하는 계약이다.

개발 dev1은 기존 inventory/media/SKU 실제잠금 검사의 literal FOR UPDATE 감지와 NKU 불일치로 3실패했다. 수정은 identity probe만 FOR NO KEY UPDATE로 정밀화하고 product FOR UPDATE, 실제 wait, 5초 제한, 상태변경 후409/CONFLICT 및 데이터불변 assertion을 유지했다. 성공 화면에 남던 미생성 문구도 제거하고 성공 browser에서 부재 assertion을 추가했다. 이 실패는 최종 성공으로 소급 변경하지 않는다.

## 독립 전체 최종 결과 — 2026-10-27

**판정: P08-C 범위 통과. 필수 미해결 차단 없음.** 위 정적 검토의 R-C01~08은 동결 구현·구체 assertion·아래 새 독립 실행을 연결해 해소로 판정한다. D 구현/결제승인/해제 worker 완료를 뜻하지 않는다.

- 명령: `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-c-review1-20261027`. 선택 실행 옵션 없음, MAVEN_ARGS unset, 종료0.
- 실행: 2026-10-27 18:17:27~18:22:33 KST. result.status=passed, preservation=passed.
- Java388(단위171/DB217), desktop/mobile browser74, frontend unit170, Node73 통과. coverage44개 그룹 전부passed. 필수 누락·실패·error·skip0, typecheck/lint/build 통과.
- 소스 HEAD `460aa8bb0bb22f5138849eb00c050abd18f6fb53` + 미커밋 구현. inputs412파일 SHA256 `11FCDABD57149864B01808E0EC108A961FA41C0D8AA482BD1F92F7EA51A2F633`. 개발 dev2/독립 review1 inputs 완전동일. 실행 전후 현재파일 hash불일치0.
- 새 DB `d2_it_4700d9aeb72e486dbc9552e66e505041`, 미디어 `.local-tools/p08-c-review1-20261027/db/media`, 전화 emulator `.local-tools/phone-emulator-7278d73eefe94f08bcc6f0151b9e1d7c`. 삭제/기존 서버 중단 없음.
- 과거 P06/P07 14158 + A4721 + B2894 + B review1 576 + cachefix1 + C development5388 + C followup1109 = 실패/역사파일28847. 실행 전후 모든 파일 hash불일치0, manifest 자체해시 변경0. 감사 원본 `.local-tools/p08-c-review1-audit-20261027/{before,after}.json`.
- 별도 증거 사본23개: `docs/work-items/evidence/P08-C-review-final-*`. result/inputs/Java·browser·frontend coverage/환경before·after/실제 test 로그/DB summary·target/소스·실패대조/최종summary를 포함한다. 하네스 원본·기존 실패·DB·미디어는 보존했다.

이번 실행에서 새 C 검사와 기존 inventory/media/SKU 실제잠금 검사, 새 현재세션 6조합, 기존 전체 검사들이 함께 통과했다. 승인 closure 변경 이후 실제 panel 필터/불변snapshot 및 GuestCart revision 검사도 최종170개 frontend 실행에 포함된다. 개발 dev1의 3개 literal lock probe 실패와 이전 focused RED/컴파일/lint 실패는 별도 실패 기록으로 유지한다.

한계: 로컬 모의 전화 인증과 DEMO 주문 생성 범위다. 실제 외부 전화 전달·운영 배포는 검증하지 않았다. guest의 견적/승인/다른탭 revision 경계는 실제 panel 및 GuestCart 단위 assertion을 조합해 검증했고, 서버 cart 경쟁·응답유실은 회원 desktop/mobile 실제 브라우저로 검증했다. D의 결제/예약해제 처리는 아직 구현하지 않았으며 이 검토에서 시작하지 않았다. 커밋·푸시·배포 없음.
