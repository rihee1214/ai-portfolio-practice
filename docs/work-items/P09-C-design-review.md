# P09-C 설계 검토

## 활성 작업 메모 — 2026-10-30

**최종 승인 반영 — 2026-10-31:** 상품별 금액 설정(기본3,000원·0원 허용), 원 배송 묶음별 반품 대상의 누적 최대액과 추가 반품 시 차액 부과가 승인되었다. 최신 구현 계약은 **§11**이며 §9/10의 선택 대기는 당시 이력이다. 추가 사업 선택 없이 해당 계약을 구현 인계할 수 있다. 본 검토는 문서만 소유하며 제품/하네스/커밋/D 실행은 하지 않는다.

**최신 활성 정책 조정 — 2026-10-31:** 사용자 질문 “애초에 회수비 정책은 상품을 등록할때 결정하도록 하는게 맞지 않나?”에 따라 상품 등록/수정 설정→구매 전 노출→주문 snapshot 방향을 코드와 대조 중이다. 기존 P1/P2는 승인 이력으로 보존하지만 상품별 금액 설정 허용까지 승인된 것으로 해석하지 않는다. 회수비 종속 구현은 보류하며 독립 발송 전 취소 작업은 계속 가능하다. 후속 판단은 §10을 따른다. 본 검토는 문서만 수정한다.

활성 작업은 P09-C의 C1 설계 판단이다. 사용자 후속 요청으로 C를 시작하며 기존 B 로컬 마감 `c9f9241`을 기준으로 한다. P08/B 완료 작업을 재개하지 않는다. 이 검토는 본 문서만 소유하며 제품·검사 수정, 하네스 실행, 새 에이전트, 커밋·푸시·배포·D 이후 착수는 하지 않는다. 기존 부모/Sol 구현·검토 세션 구조를 유지한다.

복구 기준은 `.local-tools/p09-c-execution-20261030/progress.md`다. 부모의 README/P09/implementation-plan 변경은 C 착수 기록이며 본 검토가 수정하지 않는다. B 커밋 확인으로 이 작업을 종료하지 않는다. 모든 C 신규 구현·자동 검사·전체 검증은 **NOT RUN**이며 이 문서는 구현 완료 증거가 아니다. 기존 B의 기간정책 승인(원 배송 완료+604800초)은 유지하되 회수비의 기존 주문 적용 승인으로 확대 해석하지 않는다. 실제 Toss 차단·기존 자원/실패 이력 보존을 유지한다.

## 1. C1 판정과 근거

**최신 확정 계약은 §11(2026-10-31)을 우선한다.** 상품별 금액 설정·0원·원 배송별 누적최대/차액과 legacy 새 견적 명시동의가 확정되었다. 아래 미결 표기는 최초 검토 당시 이력이며 구현 완료 또는 실행 검증 통과를 뜻하지 않는다.

기술 계약은 아래 안으로 확정한다. **H04는 구현 가능한 설계 결정, H06은 C 범위 설계 결정이며 실행 검증 전이다. H03의 회수비 부과 단위·기존 주문 적용은 사용자 선택 전 부분 보류**다. C 전체 완료 또는 C1 전체 체크를 선언하지 않는다. 선택과 무관한 발송 전 취소·환불·배송비·금액 한도·재고 복구는 이 문서를 기준으로 C2/C3를 진행할 수 있다. 회수비 정책 분기는 승인 전 구현하지 않는다.

대조 근거: README, development-workflow, verification-harness, model-routing, P09/P09-C, P09-B 작업·설계·독립 검토·마감, P08-G 인계, P07-D 쿠폰 복구, requirements와 shop-design. 실제 코드는 다음과 같다.

| 기존 계약/코드 | C에서 유지하거나 보완할 사항 |
| --- | --- |
| `order/api/OrderUnitHolds`, `JdbcOrderConfirmations.acquire/finishHold` | B 원단위 HELD와 A 출고 사실을 그대로 경쟁시킨다. acquire와 refund 예약은 같은 거래. completeRefund는 신뢰된 내부 포트이며 자체적으로 결제 성공을 증명하지 않으므로 claim 조정자가 검증한다. |
| `JdbcOrderConfirmations.write`의 전 단위 CONFIRMED 검사 | 혼합 CONFIRMED/REFUNDED 종결은 아직 없다. order 소유 종결 평가기로 통합해야 마지막 확정/마지막 환불 어느 순서도 닫힌다. |
| `PaymentCoordinator.applyLocked` | CLOSED의 성공 재수신은 무효과지만 CANCELED는 늦은 승인 보상 경로에 들어갈 수 있다. 고객 전체취소 후 재수신을 별도 회귀로 막는다. 결제 전 CANCELED/EXPIRED의 진짜 늦은 승인 보상은 유지한다. |
| `inventory/api/Reservations`, `JdbcReservations.finish` | COMMITTED는 release 대상이 아니다. 원 예약은 불변 보존하고 inventory 소유의 후속 입고 포트를 추가한다. balances→reservation 정렬 잠금을 유지한다. |
| `promotion/api/CouponReservations.restore` | 서버가 산출한 전체/금전 확정만 전달한다. partial 호출은 하지 않는다. 동일 cause에 false 호출 후 true를 재사용하면 fingerprint 충돌하므로 완결 시 한 번 생성한다. 만료 복구는 restored와 reusable을 구분한다. |
| `OrderQueries.lockOwned/lockSellerOrder`, identity 현재 자격 포트 | 조회 GAS와 구매 세션을 혼동하지 않는다. PAID 전용 신규 쓰기와 종결 후 기존 영수증 조회를 분리한다. |

검토 역할은 기존 Astra 검토세션, 구현은 기존 Sol 담당을 유지한다. 역할 문서만으로 실제 실행 모델이 변경되었다고 주장하지 않는다.

## 2. 소유권·공개 포트·불변 데이터

대안은 (a) payment에서 claim을 역호출하는 단일 조정, (b) claim이 공개 payment 결과를 읽고 업무 효과를 적용하는 내구성 조정이다. **(b)를 채택**한다. payment→claim 의존을 만들지 않으며 외부 성공/로컬 실패를 재개할 수 있다. 프로세스 메모리 콜백이나 제공자 호출을 감싼 긴 DB 거래는 사용하지 않는다.

아래 이름/필드는 구현 계약이다. Java record의 세부 배치는 구현자가 조정할 수 있으나 의미·소유권·잠금·오류는 바꾸지 않는다.

| 소유 모듈/공개 포트 | 명령 및 결과 계약 |
| --- | --- |
| claim `Claims.quote/cancel/get/withdraw/retryFailed` | HTTP 입력은 orderNumber, 원 item/ordinal 또는 수량, reason, requestKey, expectedRevision. 수량 입력은 잠금 아래 원 ordinal 오름차순으로 한 번 확정해 저장. quoteId와 fingerprint에는 선택 단위·금액·배송비 조건·정책 버전·revision을 포함. 명령 시 현재 권한/적격성 재검사. 성공 접수 영수증은 불변, 현재 진행 상태는 get으로 별도 조회. |
| payment `Refunds.reserve/get/cancelUnsent/retryFailed` | Reserve는 원 paymentId, orderId, sellerOrderId, claimId, purpose(ITEM/SHIPPING), stable causeId, attemptId, amount, mode, 원 스냅샷 참조를 받는 신뢰된 내부 계약. 호출자 거래에 REQUIRED로 참여. get은 저장된 금액/버전/결과를 반환하며 HTTP 입력을 성공 근거로 삼지 않는다. retryFailed는 기존 영수증을 덮지 않고 명시적 새 sequence를 만든다. |
| payment `RefundProvider.submit/query` | 원 제공자 결제 참조, 영구 refundAttemptId/idempotencyKey, amount, mode. 결과는 SUCCEEDED/definitive FAILED/UNKNOWN 및 제공자 참조. submit과 query 구분. 기존 `PaymentProvider.cancel`과 ID/작업 종류를 공유하지 않는다. |
| inventory `Restitutions.restoreCancellation` | causeId, checkoutId, committed reservationId, itemId/ordinal, skuId를 받는다. qty는 고유 원단위 개수에서 계산. 원 reservation 귀속·COMMITTED·ordinal 범위 검증, onHand 증가/입고 movement/영수증을 같은 거래로 저장. 원 reserved 수량을 다시 증가시키지 않는다. RETURN 입고는 D의 검수 근거 계약이 생기기 전 허용하지 않는다. |
| order `AftercareSettlements.record/evaluate` (신규) | 원 order/sellerOrder/hold/cause, 처리종류 CANCEL, 단위, 불변 payment 결과 참조와 금융 의무 완료 사실을 저장하는 신뢰된 포트. order는 claim/payment 테이블을 직접 조회하지 않는다. 동일 원인/같은 내용은 동일 결과, 다른 내용409. 모든 seller/단위의 종결을 order 내부에서 평가한다. |
| promotion 기존 `CouponReservations.restore` | 완료된 sellerOrder의 원 coupon reservation별 결정적 causeId로 full=true/confirmed=true를 호출한다. claimId 하나를 전체 완료 판정으로 대체하지 않는다. |

모든 내부 명령은 외부 바인딩 금지. 400은 잘못된 형식/음수·overflow, 404는 비소유/없는 거래, 409는 상태·revision·키 내용 충돌이다. 현재 인증/권한 실패와 CSRF는 기존 HTTP 규약을 유지한다. 같은 requestKey의 저장 영수증을 먼저 찾되 **현재 읽기 권한은 항상 검증**하며, 이미 성공한 요청에는 최신 revision/PAID 조건을 다시 강제하지 않는다. 다른 내용의 키 재사용은409다.

신규 migration은 실행된 V38~40을 수정하지 않고 다음 미사용 버전을 사용한다. 핵심 DB 제약은 다음과 같다.

- claim과 claim_unit은 order/seller/item/ordinal의 복합 귀속 FK를 가진다. 동일 원단위의 활성 claim 점유는 DB unique/부분 unique 또는 기존 B hold 제약과 연결된 제약으로 보장한다. 철회 후 새 claim은 가능하되 성공한 REFUNDED 단위의 재점유는 불가하다.
- 환불 시도는 `(payment_id, claim_id, purpose, sequence)`와 attemptId가 고유하다. cause 및 idempotency key 재사용은 canonical fingerprint 대조를 필수로 한다. amount는 BIGINT CHECK >=0, 성공 상태·제공자 참조·완료시각의 조합을 제약한다. 성공 결과는 불변이다.
- payment 소유 budget행은 원 승인액/성공액/예약액을 보유하고 `success>=0 AND reserved>=0 AND success+reserved<=paid`를 overflow 없는 산식으로 강제한다. 부모 주문 잠금 뒤 이 행을 잠근 조건부 갱신으로 모든 seller의 예약을 직렬화한다. attempt 전이와 budget 갱신은 한 거래이며 실패주입 시 둘 다 롤백한다. 원 승인액은 원 결제의 확정 성공액과 일치해야 한다.
- seller 배송비 obligation은 `(seller_order_id, ORIGINAL_SHIPPING_REFUND)` 고유. 원 청구액 불변, 중복 생성/예약 금지. 원단위 성공 원장과 배송비 성공 원장은 별도 목적이다.
- inventory 입고 영수증은 cause 고유와 **원 reservation+ordinal의 취소 복구 고유 제약**을 함께 둔다. 새 cause로 같은 수량을 또 복구할 수 없다. 원 예약/입고/수량 CHECK와 movement를 같은 거래로 보존한다.
- 업무 완료 영수증·이력·Outbox의 effect key는 결정적이며 고유하다. 원인만 같고 단위/금액/정책/제공자 결과가 다르면409 및 검토 대상으로 남긴다. 충돌을 무조건 ON CONFLICT DO NOTHING으로 숨기지 않는다.

## 3. 잠금·외부 호출·재개 경계 (H04)

사용자 명령의 순서는 현재 identity/GAS 자격 잠금 → 부모 order → 정렬된 원단위 → claim → payment budget/attempt → coupon → inventory balance/원 reservation이다. 동일 종류 여러 행은 기존 포트의 정렬 규약을 따른다. coupon→inventory는 기존 결제 완료/예약 검사 순서와 같다. 신규 restitution은 reservation을 먼저 잠그고 balance를 나중에 잠그지 않는다. 입력 조회에서 얻은 목록은 권한·부모 잠금 후 다시 검증한다.

현재 자격 만료는 **마지막 업무 잠금 대기 이후 DB 현재 시각**으로 다시 확인한다. 운영자도 SUPPORT와 현재 세션·사유가 필요하며 기존 결제 SYSTEM 권한을 복사하지 않는다. 판매자는 자기 하위주문 조회만, 운영 환불 강제 성공 권한은 없다. 판매정지의 기존 거래 지원 규약을 유지한다. worker는 브라우저 자격 대신 저장된 승인 명령과 claim 상태를 사용한다.

| 단계 | 거래와 효과 |
| --- | --- |
| T1 취소 접수 | 본인 확인, PAID/미출고/OPEN 확인, quote 검증, CANCEL hold 획득, claim APPROVED 및 ITEM refund 예약, 이력·Outbox를 한 거래로 저장. 접수 승인 자체를 발송 전 취소 적격성 승인으로 처리하며 별도 판매자 승인 정책은 추가하지 않는다. 어느 단계 실패든 hold까지 rollback. |
| T2 제출 준비 | order→payment 잠금, 아직 RESERVED인 attempt를 PROCESSING으로 바꾸고 영구 제출키·작업 fencing token 저장 후 commit. 이때부터 단순 철회 불가. |
| 외부 | DB 거래 밖에서 submit. 시작 직후 죽어 호출 여부를 모르면 같은 attempt를 query한다. 새 키 submit으로 추측 재시도하지 않는다. DEMO도 이 경계를 실제로 거친다. |
| T3 결과 확정 | order→payment 잠금 후 제공자 결과 식별/금액/작업 token 확인. success는 reserved를 success로 이동, definitive failure는 reserved만 해제, unknown은 예약 유지. 결과·이력·`REFUND_RESULT_RECORDED.v1` Outbox를 함께 commit. payment는 claim을 호출하지 않는다. |
| T4 업무 효과 | claim 소유 조정 worker가 결과 이벤트의 attemptId/version을 받고 Refunds.get으로 현재 확정 결과를 읽는다. order→units→claim→payment→coupon→inventory 순서로 잠근다. ITEM 성공이면 completeRefund, 대상 재고 입고, 업무 원장/이력/Outbox를 한 거래로 적용. 배송비 및 전체 완료 조건을 평가하고 필요한 SHIPPING 예약도 원자적으로 생성한다. |
| T5 전체 완료 | 모든 필요 leg 성공 및 의무 완료를 검증해 seller 전체 증거→coupon restore→order settlement/evaluate→claim COMPLETED→이력/Outbox를 원자적으로 적용. coupon/inventory/종결 어느 단계 실패도 해당 업무 거래 전체 rollback, T3 성공 기록은 유지해 재개한다. |

worker는 기존 JobLeases를 따라 **모든 업무 잠금 뒤 최종 유효 lease/token을 확인하고** 효과를 commit한다. lease 소실 후 외부 응답이 와도 오래된 worker가 강제 확정하지 않는다. 복구 worker는 동일 attempt의 제공자 조회로 수렴한다. 이벤트 ID뿐 아니라 저장 결과/버전으로 중복·역순 이벤트를 검증한다. 새 이벤트 payload는 orderId/paymentId/claimId/attemptId/purpose/version과 결과 참조만 담고 HTTP 요청의 full/amount를 신뢰하지 않는다.

T3 성공 뒤 T4 실패 동안에는 고객 금액은 성공, 업무 상태는 PROCESSING/동기화 대기이며 hold가 유지된다. 환불을 다시 제출하지 않는다. T4가 끝난 단위는 REFUNDED 불변이다. 이 상태를 실패로 표시해 발송 가능하게 만들지 않는다. 실행 횟수/시간의 새로운 숫자 정책은 C에서 임의 도입하지 않는다. 자동 query가 정책 없이 무한 반복되지 않도록 UNKNOWN은 명시적 검토 대기와 같은 attempt 조회 경로를 제공하며 F에서 제공자별 재조회 예산을 확정한다.

부분 claim 자체의 완료와 seller 전체 완료는 다르다. 아직 전체취소가 아닌 부분 claim은 자기 ITEM 성공 및 재고/원단위 효과 적용으로 COMPLETED가 된다. 이때 seller 전체 settlement/coupon은 생성하지 않는다. 마지막 부분 claim은 새로 발생한 SHIPPING 의무까지 담당하고 완료를 기다린다. 앞선 부분 claim의 불변 완료 영수증을 다시 열거나 내용을 바꾸지 않는다.

## 4. 상태·철회·실패·운영 전이표

claim의 업무 상태와 payment 상태를 별도로 보관한다. 아래 RESERVED는 제출 전 REQUESTED의 금액 점유 상태다.

| 환불 상태 | 금액 예약 | 원단위/claim | 허용 후속 처리 |
| --- | --- | --- | --- |
| RESERVED, 아직 제출되지 않음 | 유지 | HELD / APPROVED | worker의 PROCESSING 전이와 같은 order/payment 잠금으로 경쟁하여 본인 철회 가능. cancelUnsent가 먼저 성공하면 예약해제+hold WITHDRAWN+claim WITHDRAWN을 원자 처리. |
| PROCESSING | 유지 | HELD / PROCESSING | query만. 호출 여부 불명확은 FAILED가 아니다. 철회·새 시도·보류 해제 불가. |
| UNKNOWN | 유지 | HELD / REVIEW_REQUIRED | 본인 상태 조회, SUPPORT의 사유 있는 동일 attempt query. 임의 성공/실패 설정 및 새 환불 금지. |
| definitive FAILED | 해제 | HELD / REVIEW_REQUIRED | 자동 새 환불0. 본인 명시 철회 또는 SUPPORT의 사유 있는 새 sequence 재시도. 서로 같은 잠금으로 경쟁하며 새 예약 실패 시 이전 보류 유지. |
| SUCCEEDED, 업무 효과 미적용 | 성공액에 산입 | HELD / PROCESSING | T4만 재개. 철회·새 환불·출고 불가. |
| ITEM 성공 업무 적용, 배송비 미완료 | 상품 성공액 확정, 배송비 상태에 따른 예약 | REFUNDED / SETTLEMENT_PENDING | 원단위 되돌림 불가. 배송비 같은 attempt query 또는 확정 실패 후 SUPPORT 새 sequence. 상품 재환불0. |
| 모든 의무 성공 | 성공액 확정 | REFUNDED / COMPLETED | 현재 권한의 조회·같은 key 영수증 재생만. 재고/쿠폰/종결 효과 추가0. |
| WITHDRAWN | 없음 | OPEN / WITHDRAWN | B release가 원 배송 완료 기반 기존 일정으로 재평가. 이후 새 키 취소/출고/확정은 현재 적격성에 따라 경쟁. 옛 key는 철회된 동일 영수증 반환. |

definitive FAILED는 제공자가 해당 시도가 실행되지 않았으며 이후 성공하지 않는다고 확정한 경우만이다. 네트워크 timeout/응답 파싱 실패/로컬 commit 실패는 UNKNOWN 또는 PROCESSING으로 남긴다. 확정 결과와 모순되는 후발 응답은 review 기록과 차단으로 보존하고 이미 해제된 예산에 무조건 success를 더하지 않는다. 실제 제공자가 이 보장을 제공하지 않으면 F에서 FAILED 판정 자체를 제한해야 한다.

0원은 같은 원인·원장·업무 경로로 SUCCEEDED를 저장하되 provider 호출0이다. FAILED 뒤 새 sequence도 같은 claim/원단위/원 금액에 묶인다. 과거 시도의 실패 내용·제공자 응답·운영 사유는 수정하지 않는다. 운영자 임의 환불액/단위 변경은 허용하지 않는다. C에서는 실제 SANDBOX 고객 환불 어댑터가 없으므로 부작용 발생 전 지원불가로 거부하며 모의 성공을 기록하지 않는다.

## 5. 원금·배송비·회수비 (H03)

원단위 net은 주문 생성 때 저장된 ordinal의 값이다. 현재 상품가/프로모션/무료배송 기준으로 재계산하지 않는다. 할인 fixture `[8001,8001,8002]`의 ordinal2는8,002원, 잔여16,002원+원배송2,000원은18,002원이다. 기본 fixture `[10001,10000,10000]`은 첫10,001원, 잔여상품20,000원+배송3,000원=23,000원이다. 모든 합·차는 checked long, DB BIGINT, HTTP 10진 문자열, 프런트 BigInt다.

### 배송비 반환의 동시성 결정

**배송비는 모든 원단위의 미발송 취소 ITEM 성공이 확인된 뒤 별도 SHIPPING leg로 한 번 예약한다.** 아직 진행중인 다른 claim을 전체취소 성공으로 간주해 먼저 지급하지 않는다. 마지막 단위 성공 적용과 고유 shipping obligation 생성/예약은 같은 부모 잠금 거래다. 여러 동시 부분취소 중 마지막 성공이 이를 생성한다. 이미 발송된 단위, CONFIRMED, 반품 종류가 섞이면 C의 전체 미발송 배송비 반환 자격이 아니다.

견적은 상품환불 확정액과 '미발송 전체취소가 완성되면 원배송비 추가 반환' 조건/최대액을 분리해 표시한다. 하나의 전체취소도 두 leg일 수 있으며 총 기대액은 동일하다. SHIPPING 성공까지 해당 마지막 claim은 SETTLEMENT_PENDING, seller 전체 쿠폰복구/부모 종결은 보류한다. 상품 성공의 재고 입고는 먼저1회 가능하다. SHIPPING 0원도 obligation 완료 영수증을 남기고 외부 호출0이다. 배송비 FAILED/UNKNOWN을 상품 성공 취소나 상품 재환불로 처리하지 않는다.

### 정책 스냅샷과 승인 경계

C 취소는 별도 취소 수수료0이며 기존 요구의 원금/배송비 정책을 `P09-C-CANCEL-1` 같은 명시 버전으로 저장한다. 기존 `P07-C-1`은 원 견적 버전으로 그대로 보존한다. 기존 주문은 저장 원단위/배송비로 취소 견적을 만들고 취소 결정 버전·적용시점을 새 claim에 저장한다. 원 금액 증거가 없거나 항등식이 맞지 않는 과거 주문은 현재 가격으로 보정하지 않고 견적 불가/검토 대상으로 남긴다. B의7일 스냅샷을 바꾸지 않는다.

회수비 정책은 별도 version/effectiveAt/source와 fee basis, assessedFee, deductedFee, additionalDue를 저장한다. 아직 승인 없는 legacy에는 `UNRESOLVED`를 명시하고 실제 반환 견적 승인/공제를 막는다. 신규 설정 변경이 이미 수락된 견적/claim의 원장을 바꾸지 않는다. 새 주문에 회수비 버전을 찍는 시점 및 legacy 적용은 아래 선택 후 구현한다.

| 사용자 선택 필요 | 선택지와 추천 | 선택 전 허용 범위 |
| --- | --- | --- |
| P1: 변심 회수비3,000원의 반복/분할 단위 | A 반품 claim마다3,000원 / **B 같은 실제 회수 묶음마다3,000원(추천)** / C 같은 sellerOrder 전체에서 최대3,000원. B는 한 번 회수한 비용을 여러 부분 요청에 중복 부과하지 않으면서 실제 별도 회수와 구분한다. 묶음은 서버가 확정하고 다른 seller는 합치지 않는다. | 순수 계산 함수에 명시 fee를 주입하는 C07 assertion만 준비. 기본값으로 어느 안도 숨겨 채택하지 않는다. D 회수 구현은 하지 않는다. |
| P2: 회수비 스냅샷 없는 기존 주문 | **A 새로운 반품 견적에서 정책·비용을 명시하고 동의 시 적용(추천)** / B 기존 주문은 자동 공제 없이 운영 견적 확정 전 보류. A는 기존 주문을 수정하지 않고 명시 동의된 새로운 처리에 버전을 남긴다. B도 무료라는 뜻은 아니다. | 취소는 기존 원금 기준 진행 가능. 반품 회수비가 필요한 quote는 UNRESOLVED. 원 견적 버전을 회수비 버전으로 변환하지 않는다. |

P1=B가 승인되면 비용의 분할 공제는 기술적으로 다음으로 고정한다: 서버가 확정한 회수 묶음의 대상 원단위를 `(itemId, ordinal)` 순서로 정렬해 각 net 한도까지 남은 비용을 순차 배정한다. 묶음 합계 환불=`max(0, net합-fee)`, 추가비용=`max(0, fee-net합)`. 실제 반품 범위 확정/사용자 견적 동의 후 배분을 불변 저장하며 동시 claim이 비용을 중복 예약하지 않게 묶음 비용 원장을 잠근다. seller 책임 단위에는 fee0; 혼합 책임/분리 회수는 견적에 분리 표시하고 이미 확정된 묶음을 자동 확대하지 않는다. 다른 안 승인 시 부과 기준키만 바뀌며 비용/금액 원장 분리는 유지한다.

부족 비용은 이미 요구된 **ADDITIONAL_FEE_DUE**로 남긴다. net2,000/fee3,000이면 환불0·부족1,000이며 자동 추가 청구·자동 면제·다른 주문과 상계하지 않는다. C는 추가결제/면제 기능을 구현하지 않는다. D가 실제 수납/승인된 면제의 불변 증거를 정의하기 전에는 금융 의무 미완료로 전체완료·쿠폰 복구·종결을 막는다. 이 '보류' 기술 결정에 별도 승인은 필요 없으며, 자동 면제/수납 기능을 원할 경우에만 새 사업 결정이 필요하다.

## 6. 주문·하위주문 전체 완료와 재조회 (H06)

환불합==원결제액을 전체완료 조건으로 쓰지 않는다. C는 모든 원단위 CANCEL/REFUNDED, 필요한 원배송 반환 완료, 추가 미결 의무0이 필요하다. D용 금융 사실에는 원단위 권리액=환불액+승인된 공제액, 추가비용 정산, 회수·판매가능 검수 근거를 분리할 수 있도록 계약을 열되 D 완료를 C에서 생성하지 않는다.

| 전체 원단위 및 의무 상태 | 부모 상태/효과 |
| --- | --- |
| OPEN/HELD/미배송 잔여 또는 금융 의무 미완료 하나 이상 | PAID 유지. seller 하나 완료만으로 부모 종료 금지. |
| 모두 CONFIRMED | CLOSED, 기존 B 동작 유지. |
| 모두 미발송 CANCEL의 REFUNDED이며 각 seller 배송비 의무 완료 | CANCELED, 전이/이력1회. |
| CONFIRMED와 CANCEL/REFUNDED 혼합, 모두 종결·금융 의무 완료 | CLOSED, 전이1회. 마지막 확정과 마지막 환불 양쪽에서 같은 평가기를 호출. |
| 미래 RETURN/EXCHANGE 포함 | C의 모두취소 분기를 적용하지 않는다. D/E가 회수·검수·교환의 order 소유 완료 사실을 확정하기 전 미완료 유지. |

주문 종결 이유/원 결제 정산 출처를 order 소유 불변 사실로 기록한다. `PaymentCoordinator.applyLocked`의 CANCELED 재수신 처리에는 **원 결제 SUCCEEDED/compensation NONE + 고객취소 종결 증거**를 요구해 무효과로 반환한다. 단순 CANCELED 분기 전체를 무시하면 결제 전 취소 후 늦은 승인 보상이 사라지므로 금지한다. CLOSED의 기존 보호도 유지한다. C11에 고객취소 후 승인재수신의 compensation/provider.cancel0과 진짜 늦은 승인 보상1을 동시에 추가한다.

종결 후 본인/현재 GAS의 상세·이력·claim/환불 진행 조회와 동일키 영수증은 가능하다. 새 취소/출고/확정은409. SUPPORT 대사/query는 종결 자체로 막지 않으나 새 금전 효과를 생성할 권한으로 해석하지 않는다. `lockPaid`를 모든 읽기/재처리 진입점에 사용하지 않는다.

## 7. 제약 → 실행 가능한 assertion (모두 NOT RUN)

| ID | 필수 검사와 실제 기대값/실패 지점 |
| --- | --- |
| C01 | JUnit 원단위10,001/10,000/10,000+배송3,000 보존, 부분10,001·잔여총23,000. 금액 overflow/음수 거부, 현재 가격 변경 무효과. 배송비 leg를 합쳐야 총33,001. |
| C02 | 새 격리 PostgreSQL의 두 독립 connection/barrier로 A dispatch vs CANCEL acquire 경합, 승자1·패자409·같은 단위 이중효과0. 다른 seller 영향0. T1 refund INSERT/Outbox 실패 주입 후 hold/claim/budget 모두0. |
| C03 | 같은 부모의 다른 seller 동시 reserve와 한도초과 직접 요청, budget 성공+예약<=paid. 같은 key 결과1·다른 내용409·새 key 같은 단위409. UNKNOWN 예약 유지. 중복/역순 결과 budget 이동1. |
| C04 | ITEM 성공 전 재고/coupon0, 부분 성공 inventory movement1·coupon0, 재전달/new cause 중복 원단위 복구0. 누적전체+배송비완료 coupon1, 만료 reusable=false. coupon 또는 입고 실패 주입 시 T4/T5 거래 rollback 후 재개1. forged full/confirmed/amount 외부 입력 거부. |
| C05 | 회원/GAS 비소유·만료/reset·CSRF 및 SUPPORT 현재 권한/사유 검사. 마지막 잠금 대기 도중 만료/철회 barrier로 부작용0. 판매자 다른 seller 접근0. 종결 후 유효본인 replay 가능, 폐기 GAS 불가. |
| C06 | 0원 provider0이나 영수증/복구 동일. submit 직후 응답 유실과 외부 성공 후 T3 실패 → 동일 attempt query, submit 새키0. T3 결과/이력/Outbox 각각 실패 시 budget와 상태 함께 rollback. T4 실패 뒤 외부 재환불0. stale lease 효과0. |
| C07 | 명시 fee 입력10,001-3,000=7,001, seller 책임10,001, 2,000-3,000→0/추가1,000. P1 승인 후 같은 부과키의 두 부분 claim fee합3,000 및 별도 부과키 경계. 승인 전 정책 검증 완료 표시 금지. |
| C08 | 실제 브라우저 취소 quote→확인→UNKNOWN/진행중→완료, 응답 유실 같은 key 재요청 후 DB claim/attempt/입고1. frontend 금액문자열/BigInt와 서버 영수증 대조. ArchUnit claim→공개 API만, payment/order/inventory/promotion→claim 역참조0 및 전체 무순환. |
| C09 | 실제 상품/행사/쿠폰/checkout 생성으로 net[8001,8001,8002] 또는 실제 배분 항등식 증명. ordinal2=8002, 잔여상품16002+배송2000=18002, 총26004. 무료배송0 재청구0. 저장 정책 변경 불가/새 견적 새버전, legacy UNRESOLVED 차단. P2 승인 후 legacy 동의 적용 검사 추가. |
| C10 | 상태표 모든 행에 budget/hold/claim/허용 동작 assertion. RESERVED 철회 vs worker 1승자; FAILED 철회 vs retry 1승자; UNKNOWN 해제0. 해제 후 A발송/B확정/새취소 경합. 배송비 FAILED 시 상품재환불0/종결0. fee공제 원장과 ADDITIONAL_FEE_DUE 존재 시 전체완료0. |
| C11 | 다른 seller/OPEN/HELD/미결배송비 존재 시 종결0. 모두CANCEL+shipping완료 CANCELED1. 혼합 CONFIRMED/REFUNDED는 양쪽 마지막 완료 순서 모두 CLOSED1. 같은key 종결후 exact replay. 고객취소 뒤 승인재수신 보상0, 결제전취소 늦은승인 보상 유지. |

Sol의 읽기 전용 준비 기록 `.local-tools/p09-c-execution-20261030/implementation-preflight-20261030/preparation-notes.md`와도 대조했다. 33,001원은 동일 store의10,001원×1 및10,000원×2 SKU로 실제 생성하고,26,004원은 실제 회원 쿠폰/프로모션 경로로 생성 가능하다. SQL로 원단위 금액을 주입하지 않는다. 현재 마지막 migration은 V40이며 첫 신규 후보는 V41이다. Java488은 실행 총수,421은 기존 필수 등록 수로 구분한다.

JUnit은 순수 규칙, ArchUnit은 경계, PostgreSQL은 실제 거래·제약·동시성·실패 원자성, 프런트/브라우저는 실제 화면·HTTP·저장 결과를 검증한다. 단순 테스트 수나 코드 독해를 assertion 대체로 쓰지 않는다. 신규 필수 검사명은 기존 목록에 추가하고 기존 필수 항목은 축소하지 않는다.

## 8. 구현 인계·검증 상태

Sol은 위 기술 계약으로 C2 RED 증거와 C3/C4를 순차 진행한다. 회수비 P1/P2는 부모가 사용자에게 구체 선택을 받아 기록한 뒤 종속 구현을 진행한다. 새 사업 선택을 검토자의 기술 확정으로 위장하지 않는다. 실제 파일/서명/migration이 달라지면 의미를 대조해 작업 기록에 최종 계약을 남긴다.

C 개발 전체와 지정 검토세션 독립 전체는 아직 **NOT RUN**이다. 본 검토는 하네스를 실행하지 않았다. 후속 실행은 각각 새 증거/DB/미디어, 소스 동결, 루트 `./verify.ps1 -Offline` 전체로 수행한다. B Java488/브라우저118/프런트234/Node75는 과거 근거이며 C 통과가 아니다. 브라우저 aggregate260초·개별 기본30초/catalog60초·workers1/retries0을 시작 기준으로 유지하고 추가 검사 비용을 먼저 측정한다. 실패 은폐를 위한 timeout 확대나 필수 수 축소는 금지한다.

기존 자원·실패85643파일·B 소스/실행 증거를 유지한다. 실제 Toss·사용자 직접 확인은 계속 미검증. 제품/테스트 수정·새 하네스·커밋·푸시·배포·D 구현은 이 검토에서 수행하지 않았다. C1 기술 검토 산출물은 본 문서이며 P1/P2 결정 전 H03/C1 전체는 미완료다.

## 9. 사용자 승인 후 정책 계약 — 2026-10-31 KST

부모 세션이 전달한 사용자 원문을 보존한다. 승인 시분초는 제공되지 않았으므로 만들지 않는다.

- P1: “같이 배송된 제품이고 일부만 환불되어도 3000원, 다르게 배송된 거면 6000원 식으로 처리해줘.”
- P2: “새 반품 견적에 비용을 명시하고 동의받아 적용 (권장)”

**P1은 기존 추천인 실제 회수 묶음 기준을 채택한 것이 아니다. 원 배송 묶음 기준으로 대체한다.** 실제 회수를 한 번에 합쳐도 원 배송 두 묶음이면6,000원이다. 원 배송 한 묶음에서 일부만 반품해도 대상 변심 회수비는3,000원이며 수량 비례 할인하지 않는다. 판매자 책임0, 부족 비용 상태, 원금/배송비/7일 불변 계약은 유지한다. 발송 전 C 취소 수수료는 여전히0이다.

### 9.1 원 배송 식별과 귀속

부과 기준은 서버의 `(orderId, sellerOrderId, originalShipmentId)`다. `originalShipmentId`는 A/B가 원단위에 기록한 최초 정상 출고 사실의 shipmentId이며 최초 배송 완료 사실과 해당 단위 집합으로 대조한다. V37의 shipment_unit과 V38의 order_dispatch/order_dispatch_unit은 실제 연결 근거다. 배송 준비의 BASIC marker, 운송장 문자열, 현재 배송 상태, 회수 운송장, 배송 날짜가 부과키를 대신하지 않는다.

발송 전 분리한 두 shipment가 각각 배송되면 두 묶음이다. 운송장 수정·배송 예외 복귀·중복 이벤트는 기존 originalShipmentId를 바꾸지 않는다. 다른 seller의 같은 운송장 번호도 서로 다른 묶음이다. 아직 배송 완료가 없거나 귀속이 모호한 과거 원단위는 임의로 한 묶음에 합치지 않고 반품 견적 불가/검토로 남긴다. 교환 재배송의 귀속은 E에서 결정하며 C가 첫 주문 배송과 자동 합산하지 않는다.

claim은 order의 공개 조회 포트로 원단위/최초출고/최초배송 완료 식별·시각을 받는다. order→fulfillment 역의존을 추가하거나 claim이 fulfillment 테이블을 직접 조회하지 않는다. 필요한 조회 record에 위 참조를 노출하고 서버 잠금 후 재대조한다. HTTP가 보내는 shipmentId/full/fee는 귀속 근거가 아니다.

### 9.2 금액·분할 원장

한 번 수락하는 견적에서는 변심 대상이 포함된 **서로 다른 originalShipmentId 개수×3,000원**이다. 해당 묶음의 대상이 모두 판매자 책임이면0이다. 혼합 책임은 변심 대상 원단위에만 공제하고 판매자 책임 원단위의 환불액을 줄이지 않는다. 이유/책임이 바뀌면 새로운 견적과 동의가 필요하다.

공제는 각 원 배송 묶음 안의 선택된 변심 원단위를 `(itemId, ordinal)` 순서로 정렬해 net 한도까지 배정한다. 배송 묶음 간 남는 환불액으로 다른 묶음의 부족 비용을 상계하지 않는다. 묶음별 `fee = deducted + additionalDue`, `selectedNet = refund + deducted`를 만족하고 모든 값은 음수가 아니다. 판매자 책임 단위는 refund=net이다. 이는 수량 비례 부과가 아니라 정해진3,000원의 재현 가능한 공제 배분이다.

예: 같은 배송의 net10,001원 일부반품은 refund7,001/fee3,000. 다른 두 배송의 변심 net10,001원씩이면 총refund14,002/fee6,000. 한 배송 net2,000원이면 refund0/additionalDue1,000. 두 배송의 net2,000/10,001원이면 refund0+7,001, 추가비용1,000이며 두 번째 배송에서 첫 배송 부족액을 자동 공제하지 않는다. 현 범위에는 추가비용 자동청구·면제·상계 기능이 없다.

claim 소유 `return_fee_assessment`는 assessmentId, order/seller/originalShipment 참조, policyVersion, feeBasis, quoteId, consentId, selectedUnitsDigest, responsibilityDigest, assessed/deducted/additional 금액, state/version을 저장한다. payment에는 fee를 환불 성공액처럼 합산하지 않는다. payment 부모 한도는 현금 환불의 성공+예약만, fee 원장은 별도로 공제의 중복을 막는다.

같은 견적 수락의 분할 처리·환불 attempt 재시도·응답 유실·중복 job은 **같은 assessmentId**를 사용한다. 새 attemptId/causeId를 발급해 fee를 다시3,000원으로 늘리지 않는다. `(acceptedQuoteId, originalShipmentId)` 고유 제약과 `(assessmentId, itemId, ordinal)` 배분 고유 제약, 원단위 활성 claim 점유 제약을 함께 둔다. 부모 order→정렬 units→claim→정렬 fee assessment→payment budget→coupon→inventory→최종 lease 순서로 갱신한다. payment worker는 fee를 잠그거나 갱신하지 않는다.

금액 원장에는 `deductedReserved`, `deductedApplied`, `additionalOutstanding`을 구분한다. 공제 예약/실제 적용은 동일 assessment의 assessedFee를 초과하지 않고, 환불 실패 때 실제 공제로 오인하지 않는다. definitive FAILED 후 재시도는 기존 assessment와 배분을 재사용하며 UNKNOWN은 배분 예약을 유지한다. 제공자 성공 뒤 업무 효과 실패는 공제/업무 영수증을 같은 거래로 재개한다. 제출 전 적법 철회는 실제 적용0을 확인한 뒤 예약만 해제하고 assessment/동의/철회 이력은 보존한다. 추가비용이 남으면 전체 완료·쿠폰 복구·부모 종결은 금지한다.

### 9.3 견적 동의와 현재 revision

반품 견적은 원 배송별 대상 단위·책임·원 net·3,000원 부과 여부·공제액·추가비용·총환불액·정책 버전을 표시한다. 기존 주문에도 비용을 명시한 **새 견적의 명시 동의**가 필요하다. 단순 화면 조회나 과거 주문 결제 동의를 새 회수비 동의로 간주하지 않는다.

`accept(quoteId, quoteFingerprint, expectedRevision, requestKey)`는 현재 본인/GAS·CSRF 검증 후 마지막 잠금 대기까지 자격을 재확인한다. 원단위 적격성/책임/배송 귀속/관련 fee 원장 revision이 달라지면409로 새 견적을 요구하고 자동으로 금액을 바꾸지 않는다. 동의 레코드에는 actor 자격 참조, 실제 DB 수락시각, quote 내용 hash/정책·배송·금액·revision을 저장한다. 같은 key/같은 내용은 현재 조회 권한 하에 같은 동의 영수증을 반환한다. 다른 내용409, 종결 후에도 기존 동의 영수증은 유지한다.

quote 조회만으로 fee나 환불 한도를 점유하지 않는다. accept 거래에서 hold/assessment·배분 예약/claim/환불예약/동의/이력/Outbox를 원자적으로 저장한다. C에서는 공통 견적·원장 계약과 검사를 준비하며 D의 실반품 접수·회수·검수·반품 환불을 이 승인만으로 실행 가능하게 만들지 않는다.

### 9.4 신규 주문과 기존 주문의 정책 저장

정책 식별 후보는 `P09-RETURN-ORIGINAL-SHIPMENT-1`, basis=`ORIGINAL_SHIPMENT`, changeOfMindFee=3000, sellerFaultFee=0, allocation=`CANONICAL_SELECTED_UNIT_NET_CAP`, deficit=`ADDITIONAL_FEE_DUE`다. 반복 부과 선택은 별도 필수 필드로 두며 §9.6 결정 전 완결된 운영 정책으로 활성화하지 않는다. 정책 row는 불변이며 승인 근거는 날짜2026-10-31/사용자 원문/본 절을 연결한다. 배포·활성화 실제 시각은 나중의 실제 기록만 저장한다.

새 주문 생성 때 당시 활성 return fee 정책 ID와 위 값을 **order 소유 별도 정책 snapshot**으로 저장한다. 아직 shipment가 없으므로 shipmentId나 fee 합계를 미리 만들지 않는다. 실제 대상 배송에 대한 평가·동의·배분은 새 quote/claim에서 저장한다. 원 견적의 `P07-C-1` 필드를 덮지 않는다.

기존 주문의 원 금액·정책·배송·B7일 데이터를 갱신하지 않는다. 신규 필드가 필요한 경우 legacy 주문은 명시적 미저장 상태로 유지한다. 기존 주문에 새 반품 견적을 만들 때 당시 활성 정책을 복사하고 source=`LEGACY_QUOTE_EXPLICIT_CONSENT`로 표시한다. 동의 전에는 금전 부작용0, 동의 후에는 수락된 quote/claim 정책을 고정한다. 신규 주문은 source=`ORDER_POLICY_SNAPSHOT`으로 해당 주문 버전을 사용한다. 설정 변경은 이미 수락한 quote/claim이나 이전 신규주문의 snapshot에 소급하지 않는다. 미수락 견적을 새로 발급하면 legacy는 새 활성 버전을 표시하고 다시 동의받는다.

원 배송 완료+604800초의 B 적격 기간, 잔여1원 ordinal, 원 net/배송 할인은 이 정책 승인으로 바뀌지 않는다. 유효 기간 밖에 fee 동의가 있다는 이유만으로 반품을 허용하지 않는다.

### 9.5 C07/C09 및 SQL 동시성 assertion 보완 — NOT RUN

| 검사 | 구체 기대 결과 |
| --- | --- |
| C07 부과 기준 | 같은 배송에서 여러 제품 또는 일부1개 선택 모두fee3,000. 다른 실제 배송2개 선택fee6,000. 회수를 합치거나 운송장을 수정해도 동일. seller 책임만 선택fee0. 혼합 책임에서 seller 책임 net 공제0. |
| C07 부족·배분 | 위10,001/2,000 및 두 배송2,000+10,001 사례를 각각 검증. canonical 순서/checked 합계, 각 배분<=해당 unit net, fee=deducted+additionalDue, 묶음 간 상계0. |
| C09 실제 귀속 | 실제 A 분리→출고→배송 완료 생성으로 한 배송/두 배송을 구분한다. SQL로 shipment 귀속/금액을 주입한 단위 fixture를 실제 경로 증거로 표시하지 않는다. 원 dispatch/delivery 참조 불일치 견적 거부. 운송장 수정·이벤트 재생 부과키 변경0. |
| C09 snapshot/동의 | 새 주문은 생성 당시 정책 유지, legacy 원행/7일/금액 hash 불변. legacy 새quote 명시동의 전 영향0, 동의 후 정책불변. 설정 변경 뒤 legacy 새 견적은 새버전, 기존 수락은 구버전. revision/hash 변경409, 같은key exact replay. |
| PostgreSQL 중복·한도 | 독립 연결에서 같은quote accept 경합→동의/assessment/claim 각1. 같은 unit 다른quote 경합→활성 점유1. 동일 assessment의 두 attempt가 공제예약을 두 번 늘리지 못함. DB unique/CHECK 직접 위반 거부, payment 성공+예약<=paid 및 fee applied+reserved<=assessed 동시 보장. |
| PostgreSQL 원자성 | hold/fee배분/동의/refund/history/Outbox 각 지점 실패→접수 전체rollback. 외부성공 후 공제 적용 실패→원 payment성공 보존/동일assessment 재개1. UNKNOWN 철회·재부과0, definitive FAILED→동일assessment 재시도. |
| 반복정책 검사 | 동일 배송의 **별도 후속 반품** 동시/순차 접수의 fee 기대값은 §9.6 선택 후 고정한다. 미결 정책을 총3,000원이라고 미리 assertion하지 않는다. |

§7의 C07 '같은 부과키의 두 부분 claim fee합3,000'은 같은 수락 견적/assessment의 분할·재시도에 적용한다. 서로 별도 수락한 후속 반품까지3,000원 상한이라고 확대하지 않는다. 기존 필수 검사는 유지한다. C07/C09의 위 신규 검사는 아직 실행하지 않았다.

### 9.6 남은 한 가지 사업 선택: 같은 배송의 별도 후속 반품

사용자 원문은 한 배송에서 일부 반품해도3,000원, 두 배송이면6,000원을 명확히 정했다. 그러나 **같은 배송에서 오늘 일부를 반품하고 나중에 남은 제품을 별도 요청하는 경우**까지 최초3,000원으로 모두 처리하는지는 명시하지 않았다. 기존 추천의 '회수 묶음'을 거부한 사실만으로 반복 부과 무료/유료를 결정하지 않는다.

부모가 확인할 질문: “같은 배송에서 일부를 반품한 뒤 나중에 나머지를 별도로 반품하면, 배송 묶음 전체에서3,000원 한 번만 받을까요, 아니면 별도 반품 요청마다3,000원을 받을까요?” **배송 묶음 전체에서1회(추천)**가 사용자께서 정한 원 배송 기준과 가장 일관적이다. 다만 반복 반품 비용을 달리 정할 수 있으므로 승인 전 확정하지 않는다.

- 1회 선택 시 `(sellerOrderId, originalShipmentId, feeFamily)` 전역 부과 고유키와 누적fee3,000 예산을 둔다. policyVersion을 바꾸어 같은 배송의 fee를 다시 생성하지 않는다. 후속 반품은 기존 assessment 참조, 부족 잔액은 보존하고 새로운3,000원을 만들지 않는다. 아직 수락된 최초 견적의 금액을 변경해 후속단위에서 부족분을 자동 공제하지 않는다.
- 요청마다 선택 시 `(acceptedReturnRequestId, originalShipmentId)`가 부과키다. 같은 요청의 분할/재시도는1회, 별도 동의한 요청은 새fee3,000이다. 별도 요청의 정의는 새 사용자 동의/새 대상 단위이고 worker/환불 attempt가 아니다.

이 선택만 대기한다. 원 배송 식별·3,000/6,000 금액·P2 명시동의·snapshot·현재revision·공통 원장/동시성 계약과 발송 전 취소 구현은 진행 가능하다. 전역 부과 고유키와 후속 별도 반품의 실제 금액을 정하는 종속 구현은 답변 전 보류한다. 본 후속 검토는 문서만 수정했으며 제품/하네스/커밋/푸시/배포/D 구현은 수행하지 않았다.

## 10. 상품 등록 시 회수비 정책 설정 검토 — 2026-10-31 KST

사용자 후속 원문: “애초에 회수비 정책은 상품을 등록할때 결정하도록 하는게 맞지 않나?” 부모는 상품 등록/수정에서 결정→구매 전 표시→주문 시 snapshot 고정이 맞으며 현재 공통3,000원 안에 상품 설정 연결이 빠져 있다고 설명했다. **설정 위치에 대한 조정이며 판매자 자유 금액 입력 승인으로 확대하지 않는다.** 아래는 실제 코드 대조와 구현 전 선택안이다. §9.4의 전역 활성 정책만 복사하는 신규 주문안은 상품별 정책 전달을 포함하도록 보완해야 한다. legacy 새 견적 동의 예외는 유지한다.

### 10.1 현재 코드의 실제 공백

| 코드 | 확인 결과와 변경 지점 |
| --- | --- |
| `catalog/api/Products.Input/Product/PublicProduct`, `JdbcProducts`, `ProductController` | category/slug/title/description/state/version 등은 있지만 회수비 설정이 없다. 상품 저장 권한·낙관적 version과 함께 정책 입력/저장/조회 계약이 필요하다. |
| `seller/api/Stores.Input/Store`, `JdbcStores` | 매대 소개·주소·공개/운영 상태 등이며 회수비/배송비 정책 저장 포트는 없다. 매대 기본값/대표정책을 이미 존재하는 기능으로 가정하면 안 된다. |
| `catalog/api/Browse.Detail/QuoteOffer`, `PurchaseOffers.lock` | 구매 견적용 상품/SKU/매대 version은 전달하지만 회수비는 없다. 상품 공개 상세와 quoteOffer에 정책 snapshot을 전달해야 한다. 구매 잠금은 계정→매대→분류→상품→SKU 순서를 이미 제공한다. |
| `promotion/domain/QuoteCalculator`, `JdbcQuotes`, `Quotes.Line` | 원 배송비는 현재 공통 배송 계산이며 회수비가 아니다. 상품별 version/원금배분은 있지만 회수비 정책은 없다. outgoing shippingBase/shippingDiscount에 회수비를 섞지 않는다. |
| `order/infrastructure/OrderCreationService` | PurchaseOffers 잠금 후 quote 재계산 및 fingerprint 비교, 다르면409, 일치하면 Orders.Draft 저장. 정책 변경 경쟁도 이 경로로 차단할 수 있다. |
| `JdbcOrders.saveStore`, `Orders.Snapshot` | quote_snapshot/각 order_item.line_snapshot을 저장한다. 신규 상품별 회수비 snapshot은 이 주문 생성 거래에서 함께 저장 가능하며 과거 JSON에 누락된 필드 호환 처리가 필요하다. |
| `frontend/catalog/CatalogStore.ts`, `pages/CatalogPages.tsx`, `catalog/Browse.ts`, `promotion/QuoteStore.ts/QuotePanel.tsx` 및 주문 생성 패널 | 상품 편집·상세·견적/주문 전 표시를 연결해야 한다. 현재 주문 전 배송비 표시가 회수비 고지를 대신하지 않는다. |

requirements의 '변심 회수비 기본3,000원'은 현재 정책값이며 상품별 금액을 판매자가 수정할 권한·범위를 이미 정의하지 않는다. 상품 등록에서 고정 정책을 명시/확인하는 안과 상품마다 금액을 입력하는 안은 다른 기능이다.

### 10.2 두 안에 공통으로 확정할 기술 계약

catalog는 상품의 회수비 설정 출처를 소유하고 order는 구매 시의 정책 사본을 소유한다. claim은 이후 현재 상품 정책을 읽어서 과거 주문 비용을 바꾸지 않는다. promotion의 견적은 정책을 전달하고 fingerprint에 포함하지만 실제 반품 채무를 생성하지 않는다. claim↔catalog 또는 order→claim 역의존은 필요 없다.

상품 정책 record의 최소 의미는 `policyId`, `policyRevision`, `source`(PLATFORM_FIXED/SELLER_PRODUCT), `changeOfMindAmount`, `sellerFaultAmount=0`, `basis=ORIGINAL_SHIPMENT`, `aggregationRule`, `repeatRule`다. 금액·부과단위·반복 규칙이 전부 정해져야 구매 전 조건이 된다. 내부 long/DB BIGINT checked 계산, HTTP 금액 문자열/프런트 BigInt를 유지한다. 상품 옵션별 정책은 사용자 요청 범위가 아니므로 기본은 product 수준이며 SKU는 소속 product 정책을 소비한다.

정책 저장은 상품 저장의 현재 판매자/MANAGE_CATALOG 권한, 소유권, CSRF, version 검증 거래에 참여한다. 금액 수정 허용 여부는 §10.3 선택에 따른다. 선택 후에도 음수/비정수/overflow와 다른 판매자 정책 참조는 거부한다. sellerFaultAmount와 부과/반복 규칙은 일반 금액 입력으로 변조할 수 없다. 상품 version 및 정책 revision을 갱신해 stale 편집과 오래된 구매 견적을 구분한다. 유효한 정책 없는 상품의 신규 판매는 막거나 등록 시 명시 설정을 요구해야 하며 legacy 상품을 조용히 '판매자 입력3,000원'으로 표시하지 않는다.

공개 상품 상세에는 회수비 금액, 원 배송 묶음 기준, 혼합 묶음 계산 규칙, 판매자 책임0, 부족 비용 처리와 반복 부과 조건을 일반 언어로 표시한다. 견적/주문 전 화면은 선택 상품별 고정 조건을 다시 표시한다. 주문 전에는 실제 분할 배송 수가 정해지지 않았으므로 장래 총회수비를 확정액으로 만들지 않는다. 실제 반품 견적에서 원 배송과 선택 단위에 따른 합계를 확정·동의받는다.

`Browse.QuoteOffer`→`Quotes.Line`의 정책 사본 전체를 canonical quote fingerprint에 포함한다. productVersion만 믿지 않고 실제 정책 내용도 포함한다. OrderCreationService는 기존 상품 잠금 아래 재계산해 구매 전 견적과 달라지면409/새 견적을 반환한다. 정책을 구버전으로 보았는데 신버전으로 조용히 주문하는 경로는 금지한다. 기존 주문 requestKey 재생은 저장 snapshot 그대로이며 현재 상품 정책 변경으로 새 주문/새 snapshot을 만들지 않는다.

신규 주문은 원 line snapshot에 정책 사본을 저장하고 order 소유 `order_item_return_policy` 같은 정규화 사본을 원 item에1:1로 둘 수 있다. 둘을 저장한다면 같은 거래와 동일 hash 검증으로 일치시킨다. 별도 테이블은 FK·비음수·필수버전 제약과 조회 편의를 위한 것이며 현재 catalog 정책을 FK로 참조하는 것만으로 snapshot을 대신하지 않는다. 원단위는 원 item의 정책 사본을 참조한다. 원 net/할인/배송비·B 기간은 변경하지 않는다.

구매 시 정책 없는 legacy 주문은 null/LEGACY_UNSPECIFIED를 명시하고 기존 row/JSON을 수정하지 않는다. §9 P2처럼 새 반품 견적에 적용 조건과 비용을 보여주고 명시 동의 시 quote/claim에 고정한다. 상품 설정 변경 후의 현재 금액을 과거 주문에 자동 적용하지 않는다. **legacy에 제안할 기본은 기존 승인3,000원이며, 상품별 신금액을 legacy에도 제안하려면 별도 정책 근거가 필요하다.** 구매 snapshot이 있는 신규 주문에는 P2 예외를 사용해 현재 상품가를 소급 적용하지 않는다.

### 10.3 사용자 선택 1: 등록 화면의 의미

| 안 | 효과 | 판단 |
| --- | --- | --- |
| A. 기존 고정3,000원 정책을 상품 등록/수정에서 표시·확인 | 모든 상품3,000원, 판매자가 금액은 바꾸지 못함. 상품별 정책 참조/구매 전 고지/주문 snapshot을 갖춘다. 원배송1묶음3,000·2묶음6,000 유지. | 지금까지 승인한 금액 정책을 유지하는 가장 작은 변경. '등록 때 결정'이 고지/정책 선택을 뜻한다면 적합. |
| B. 판매자가 상품마다 변심 회수비 설정 | 제품 특성에 맞는 금액 입력·수정, 구매 전 표시 및 주문별 고정. 같은 배송의 다른 금액 충돌 규칙 필요. | **추천.** 상품 등록 단계에서 실제 조건을 정하려는 취지에 맞는다. 다만 질문만으로 허용된 것은 아니며 금액 설정 권한은 승인 필요. 기존3,000원은 기본 제안값으로만 남기고 판매자가 확인한다. |

추천 B에서0원은 판매자 부담 정책으로 표현할 수 있으나 **0원 허용도 함께 명시해 승인받는다.** 별도 임의 최고 금액은 만들지 않는다. 기술 표현 한계(long)와 합계 overflow 검사는 사업상 허용 상한이 아니며, 상품 정책 운영 상한이 필요하면 별도 결정한다. 왕복비/교환비/지역별 추가비/옵션별 회수비로 범위를 넓히지 않는다.

부모 질문안: “상품 등록 때 판매자가 회수비 금액을 직접 정하게 할까요(0원 포함), 아니면 기존3,000원 정책을 확인·표시하는 방식으로 둘까요?”

### 10.4 사용자 선택 2: 다른 회수비 상품이 같은 배송에 섞인 경우

B를 선택할 때만 필요하다. 예를 들어 같은 원 배송에 회수비3,000원 상품A와5,000원 상품B가 있을 때다. 어떤 안이든 선택 상품의 구매 당시 snapshot을 사용하고 판매자 책임 대상은 제외한다.

| 안 | A만 반품 / A+B 함께 반품 | 판단 |
| --- | --- | --- |
| 가장 큰 금액1회 | 3,000 / 5,000 | **추천.** 같은 원 배송당 한 비용이라는 기존 의도를 유지하면서 높은 비용 상품도 반영한다. 수량별 합산하지 않는다. |
| 선택 상품별 합산 | 3,000 / 8,000 | 원 배송 묶음당1회라는 기존 기준과 차이가 크며, 같은 상품 여러 수량의 반복 여부까지 추가 정책이 필요하다. 추천하지 않는다. |
| 배송 묶음의 대표 정책 | 대표 정책에 따라 고정 | 대표를 누구/언제 정하는지와 서로 다른 상품의 합배송 허용 기준이 필요하다. 발송 후 판매자가 임의 선택하면 구매 전 조건을 바꾸므로 금지. 명시 배송정책 그룹과 구매 시 결정 규칙이 필요해 범위가 가장 크다. |

최댓값 추천의 정확한 대상은 **반품 선택 중 변심 책임인 상품의 정책 금액**이다. 같은 배송에 포함되어도 반품하지 않은 비싼 정책 상품을 근거로 비용을 올리지 않는다. 다른 원 배송은 각각 계산해 합산한다. 이 기준은 승인 전 구현하지 않는다.

§9.6의 별도 후속 반품 문제는 여전히 중요하다. 최댓값+배송당 누적1회를 함께 승인한다면 A 먼저3,000원을 부과한 뒤 B를 별도 반품할 때 누적상한5,000원에서 차액2,000원을 부과하는 안을 추천한다. **이는 새 선택안이지 확정값이 아니다.** '첫 부과 뒤 영구 추가0' 또는 '새 요청마다5,000원'과 금액이 다르므로 부모가 혼합 계산안과 반복안을 함께 확인해야 한다. 누적 기준은 회수한 현금만이 아니라 이미 확정한 fee 채무를 사용해, 첫 요청의 부족액이 남았다는 이유로 새3,000원을 만드는 오류를 막는다.

### 10.5 선택 후 변경 범위와 필수 검사

구현 변경은 catalog 상품 저장/공개 조회·상품 편집/상세 UI, promotion 견적 DTO/fingerprint·견적 화면, order 생성/원 item 정책 snapshot·이행 호환, claim의 반품 견적/fee 원장까지다. 원 배송 식별은 §9.1을 유지한다. 단순 claim 내부3,000원 상수 변경으로 끝낼 수 없다. requirements의 정책값/상품 등록 조건 및 P09-C 작업 계획도 부모/구현 담당이 승인 선택에 맞춰 갱신해야 한다. 본 검토는 그 파일을 수정하지 않는다.

- JUnit/C07: 고정안은 기존3,000/6,000 assertion 유지. 상품 설정안 승인 후 선택3,000/5,000의 혼합 계산,0원(허용 시), seller 책임0, 저액 공제/부족 비용, checked overflow를 검증한다. max/sum을 승인 전에 필수 기대값으로 고정하지 않는다.
- PostgreSQL/C09: 실제 상품 등록→공개 detail→견적→주문 생성 경로에서 상품 정책/노출/fingerprint/원 item snapshot 일치. 원금 SQL 주입 없이 기존 할인 fixture를 유지한다. 정책 수정과 주문 생성의 실제 connection 경합은 구버전의 일관된 주문 또는409/새 견적으로만 귀결되고 금액/버전 혼합0이다.
- PostgreSQL/권한: 비소유·판매정지·현재 자격 상실·CSRF·stale product version 거부, 상품 정책 revision와 상품 갱신 원자성, order snapshot 삽입 실패 시 주문/예약 전체rollback. 기존 상품/주문/과거 JSON 누락필드 읽기 호환 및 legacy provenance 검증.
- C09/이행: 구매 후 상품 정책 변경에도 기존 주문/수락 견적 불변, legacy 동의 전 부작용0·동의 뒤 snapshot 고정, 원7일/ordinal/net/배송비 hash 불변. fixed→seller 설정 이행이 과거 승인 기록을 덮지 않음.
- 프런트 단위/실브라우저: 상품 설정 저장→구매자 상세와 주문 전 같은 조건 표시, 수정 후 stale 견적 재확인, 큰 금액 문자열/BigInt 일치,0원과 미설정 구분, 개인정보/다른 계정 늦은 응답 차단. 판매자가 설정할 수 없는 필드는 편집 UI와 API 모두 차단.
- fee SQL 동시성: 승인된 부과키/반복규칙 기준으로 같은 배송 두 요청의 assessment 예산 직렬화, 중복 attempt/동의 replay 효과1회, 변경된 snapshot을 후속 요청에 끼워 넣는 공격409. 누적최댓값 차액안 채택 시3,000→5,000 경쟁에서도 총채무5,000 초과0을 실제 barrier로 검증한다.
- ArchUnit: catalog→claim/order 역참조0, order→claim 역참조0, 공개 포트만 사용. 상품 정책 공유 DTO를 핑계로 infra 테이블 직접 접근을 추가하지 않는다.

### 10.6 현재 인계 상태

**현재 회수비 설정·혼합 산정·반복 부과의 종속 구현은 보류**다. 독립 발송 전 취소/원금환불/배송비 반환 구현은 계속 가능하다. 먼저 §10.3 설정 권한을 확인하고, B이면 §10.4 혼합·후속 부과를 함께 정하면 된다. A이면 혼합 금액 문제는 없어지고 기존 §9.6 반복 여부만 남는다. 승인 후 §9의 fixed3,000 계산/원장 키/§9.4 snapshot을 선택과 일치하도록 명시 대체해야 한다.

이번 후속 검토는 실제 코드 읽기와 본 문서 갱신만 수행했다. 제품·검사·하네스·커밋·푸시·배포·D 구현은 수행하지 않았으며 제안된 모든 새 assertion은 **NOT RUN**이다.

## 11. 상품별 회수비 최종 구현 계약 — 2026-10-31 KST

사용자 최종 원문: ① “상품별 금액 설정 (권장): 기본 3,000원, 0원도 허용” ② “반품 대상의 최대 금액 (권장): 3,000원 상품 후 5,000원 상품을 추가 반품하면 차액 2,000원만 추가”. 앞선 원 배송 묶음 구분/다른 묶음 합산과 legacy 새 견적 명시동의도 유지한다. 승인 시분초는 기록하지 않는다.

**H03/C1의 사업 선택은 해소되었다.** §9.6/§10.3/§10.4 선택 대기는 종료한다. §9의 고정3,000원 곱셈은 모든 대상 상품의 저장 회수비가3,000원인 특수 사례로만 유지한다. §10의 상품 설정→공시→주문 snapshot 계약과 아래 누적 원장을 함께 구현한다. H04/H06 기존 기술 계약은 유지하며 C1 설계 확정과 C 구현·검증 완료는 구분한다.

### 11.1 상품 설정·공시·기존 상품 이행

catalog의 product 수준 회수비를 판매자가 생성/수정한다. 생성 기본값3,000원, 명시0원 허용. 동일 product의 SKU는 같은 정책을 소비한다. 금액은0 이상 Java long/DB BIGINT 표현 범위의 원 단위 정수이며 계산은 checked, HTTP는10진 문자열, 프런트는 BigInt다. 소수·음수·범위초과는400, 다른 소유자404/기존 권한 규약, stale product version409. 임의 사업 상한을 새로 만들지 않는다. 누적 합계 overflow는 견적 오류로 거부하며 잘라내거나 반올림하지 않는다.

상품 저장 거래에서 현재 회원/판매자·소유권·MANAGE_CATALOG·CSRF·version과 정책을 함께 검증/저장한다. 판매자 책임0, basis=ORIGINAL_SHIPMENT, aggregation=RETURNED_TARGET_CUMULATIVE_MAX, repeat=DELTA_ONLY는 시스템 규칙으로 고정하고 판매자 입력을 받지 않는다. productVersion 및 policyRevision을 갱신한다. 정책 금액만 바뀌어도 기존 구매 견적 fingerprint가 달라져야 한다.

기존 product row는 새 migration에서3,000원과 source=`MIGRATED_DEFAULT_3000`으로 초기화한다. 이는 **이행 뒤 신규 구매에 공개되는 기본 정책**이며 과거 주문의 정책 생성이 아니다. 새 상품 기본값은 `PRODUCT_DEFAULT_3000`, 명시 저장값은 `SELLER_PRODUCT`로 구분할 수 있다. 기존 상품에 판매자가 직접 입력했다고 기록하지 않는다. 이행 후 구매 전에 상세/견적 화면에 동일 값을 표시하고, 상품 정책 필드 미설정 상태로 신규 주문을 생성하지 않는다. 구형 요청의 누락값은 생성에서 기본3,000원, 수정에서 기존값 유지로 구분해0원 설정을 실수로 되돌리지 않는다.

표시는 상품 편집·공개 상세·구매 견적/주문 전 확인 화면에 연결한다. 원 배송 묶음별 반품 대상 누적최대, 별도 배송 합산, 이전 부과액과의 차액, 판매자 책임0, 부족 비용을 설명한다. 실제 분할 배송 수는 주문 전에 미정이므로 향후 총회수비를 확정 청구액으로 표시하지 않는다. 회수비는 현재 구매 결제액에 더하지 않는다.

### 11.2 구매 견적·주문 snapshot·변경 경쟁

`Browse.QuoteOffer`와 `Quotes.Line`은 product 정책 ID/revision/source/금액/규칙 version을 포함한다. quote의 canonical fingerprint는 이 실제 값들을 포함한다. `PurchaseOffers.lock`의 기존 상품 잠금 뒤 `OrderCreationService`가 quote를 다시 계산해 요청 fingerprint와 비교한다. 정책 변경이 먼저 반영됐으면409와 새 quote, 주문 잠금이 먼저면 그때 정책을 일관되게 저장한다. 한 주문 안에서 구금액/신version 조합은 허용하지 않는다.

원 order item에 불변 회수비 snapshot을 저장하고 각 ordinal은 이를 소비한다. 저장 단위는 원 item이며 실제 refund 대상은 원 ordinal이므로 같은 상품 여러 수량을 선택해도 비용을 수량만큼 곱하지 않는다. quote JSON/line JSON과 별도 정책 테이블을 병행하면 같은 거래로 저장하고 hash 일치를 검사한다. API 조회 시 현재 catalog로 누락된 과거 정책을 채워 넣지 않는다.

주문 생성의 같은 requestKey/같은 내용 재시도는 현재 권한 확인 후 기존 주문/정책을 반환한다. 이후 상품 설정 변경이 기존 주문을 바꾸지 않는다. 같은 key에 다른 fingerprint/입력은409. 미수락 구매 견적은 상품 정책이 바뀌면 새 version/hash로 다시 제시하고 주문 전 재확인을 받는다. 이미 저장된 quoteId의 내용을 갱신하여 hash만 재사용하지 않는다.

정책 식별은 `P09-RETURN-PRODUCT-MAX-1`로 분리하고 기존 `P07-C-1` 원 견적/금액 정책 필드를 덮지 않는다. 신규 정책 revision/버전 변경은 새 상품 견적과 새 주문에만 적용한다. 이미 주문한 원금·할인·배송비·B 원배송+604800초는 불변이다.

### 11.3 legacy 주문의 명시 동의 사본

과거 order의 회수비 snapshot은 `UNRESOLVED/LEGACY_UNSPECIFIED`로 읽고 기존 order row/JSON을 소급 갱신하지 않는다. 새 반품 견적에서 legacy 기준3,000원·동일 원배송 누적최대 규칙·공제/부족 비용을 명시하고 동의하면 quote/claim 소유 immutable override를 저장한다. 현재 상품에서 판매자가 바꾼 금액을 legacy에 자동 적용하지 않는다. source=`LEGACY_QUOTE_EXPLICIT_CONSENT`, 사용자/현재 자격 참조·실제 수락시각·정책 version·선택 원단위·원배송·금액·원장 revision·fingerprint를 보존한다.

동의 전 금액/수량/fee 예약0, 동의 거래 실패 시 전체rollback이다. 수락된 동일 quoteId 내용 변경은 거부한다. 다른 대상/책임/원장 revision은 새 견적과 새 동의가 필요하다. 같은 동의 requestKey의 영수증 재생은 가능하나 내용 충돌409. 이후 같은 배송의 legacy 반품도 고정된 정책 family와 원장에 연결해 이미 부과한 비용을 중복 생성하지 않는다. 신규 주문의 정상 snapshot을 legacy override로 바꾸는 경로는 금지한다.

### 11.4 원 배송별 누적최대 원장과 예약

부과키는 `(orderId, sellerOrderId, originalShipmentId, feeFamily)`다. feeFamily는 이 정책 계열의 고정 식별자이며 상품 정책 revision이나 quote/claim/attempt마다 새 키를 만들지 않는다. 원배송 식별/최초출고·배송완료 귀속은 §9.1을 따른다. 판매자 책임 단위는 fee 최대값 산정에서도 제외한다. 변심 대상 중 반품하지 않은 상품은 제외한다.

한 배송 원장의 확정 누적 부과액을 `A`, 새 요청의 선택 변심 원단위가 참조하는 저장 상품 회수비 최대를 `M`이라 하면 **목표 T=max(A,M), 신규 부과 Δ=T−A**다. 아직 부과 전이면 A=0.0원 또는 기존 최대 이하 상품을 추가 반품하면 Δ=0. 다른 원배송은 각자 계산 후 합산한다. 이미 확정한 A는 '받은 현금'이 아니라 **공제액+미수납액을 포함한 확정 채무**다.

예: A=3,000에서5,000원 상품 추가반품은 Δ=2,000. 첫 반품 net2,000으로 공제2,000/미수납1,000이 남았어도 A=3,000이며 다음 Δ는2,000이다. 미수납을 이유로 새3,000/5,000을 부과하지 않는다. 각 배송에서 새 Δ만 이번 선택 변심 unit net에 canonical 순서로 공제한다. 이전 미수납은 별도 잔액으로 유지하며 새 환불액에서 자동 상계하지 않는다. 새 net이1,000이면 추가Δ2,000 중1,000 공제/새 미수납1,000, 이전 미수납1,000과 합쳐 잔액2,000이다. 전체의 확정 공제3,000+미수납2,000=누적A5,000이다. D에서 정당한 수납 증거가 생기면 미수납→수납 분류만 바꾸고 A를 다시 늘리지 않는다.

동시 의존성은 다음으로 단순화한다. **동일 원배송에는 아직 확정 또는 철회되지 않은 fee assessment를 한 개만 허용**한다. DB의 해당 bucket 활성 assessment 고유 제약과 revision으로 보장한다. 다른 원배송은 진행 가능하며, 여러 배송을 한 견적에서 수락할 때 bucket을 정렬 잠금하고 모두 원자 예약한다. 두 요청이 같은 배송의 같은 A로 견적을 봤더라도 먼저 수락한 요청만 예약되고 나머지는409/새 견적을 받는다. 앞선 미완료 요청의 성공을 가정해 차액을 미리 할인하지 않는다. 이는 별도 새 요금 정책이 아니라 부과액 안정성을 위한 거래 직렬화다.

bucket은 `assessedTotal=A`, `reservedIncrement=R`, `activeAssessmentId`, `revision`을 갖는다. assessment는 baseline A, selected maximum M, target T, delta R, 단위별 공제예정액·추가비용, quote/동의 참조와 fingerprint를 불변 저장한다. 수락 시R=T−A, 적용 성공 시A:=T/R:=0, 적법 철회 시A유지/R:=0이다. active slot은 적용 또는 철회 시 해제한다. Δ=0도 같은 견적·동의·원단위 검증을 거쳐 영수증을 남긴다.

같은 assessment의 attempt 재시도는 R을 새로 더하지 않는다. UNKNOWN과 definitive FAILED 후 미철회 보류는 R/slot을 유지한다. FAILED는 payment 금액예약만 해제하며 fee 원장의 적용 성공을 뜻하지 않는다. 명시 재시도는 같은 assessment, 제출 전/확정 실패 후 적법 철회는 공제효과0을 확인한 뒤 R 해제한다. 제공자 성공 뒤 local 실패는 T4 업무 거래를 재개해 fee 적용1회; 성공 기록이 있는 경우 철회로 R을 풀지 않는다. 반품 거부/철회의 실제 경로는 D가 이 계약을 소비한다.

DB 제약: 부과키 고유, bucket 비음수 및 checked A+R, active slot/R/assessment 상태 일관성, assessment `T>=A`, `Δ=T−A`, 단위별 공제<=선택 원net, `Δ=공제합+추가비용`, 동일 assessment/원단위 배분 고유, 불변 수락 fingerprint. 누적A는 적용 완료 assessment들의 delta 합과 일치한다. `deductedApplied+additionalCollected+additionalOutstanding=A`를 원장 대조로 검사하고 추가 면제 기능은 도입하지 않는다. payment의 성공+예약<=paid 제약은 별도 유지하며 fee를 현금 환불 성공액에 더하지 않는다.

잠금은 현재 자격→order→정렬 원단위→claim→정렬 fee bucket/assessment→payment budget→coupon→inventory→최종 lease다. fee의 read-modify-write는 bucket revision과 같은 거래에서 수행한다. 원금 환불 성공/fee 적용/업무영수증·이력·Outbox는 기존 T3/T4 구분을 유지한다. 과거 설정 변경으로 A/M을 현재 catalog 값으로 다시 계산하지 않는다.

### 11.5 확정 assertion과 실제 연결 — 신규 실행 NOT RUN

| 연결 | 구현해야 할 기대 결과 |
| --- | --- |
| 상품 단위/HTTP/DB | 생성 누락값3,000·명시0 보존, 수정 누락값 기존0 유지. 음수/소수/long범위초과 거부. 타판매자·판매정지/권한·CSRF·stale version 거부 및 부작용0. 정책 수정과 상품version 원자 갱신. |
| C07 순수 규칙 | 같은 배송3,000 상품 여러 수량/부분 선택도3,000,3,000→5,000 추가Δ2,000,5,000→3,000 추가0,0→0 추가0. 같은 배송 동시선택3,000/5,000은5,000. 다른 배송3,000/5,000은8,000. 판매자 책임5,000 제외 후 변심3,000이면3,000. |
| C07/C10 채무 | 위 첫net2,000/fee3,000 후 새net1,000/정책5,000 사례: A5,000·누적공제3,000·미수납2,000. 미수납 존재 시 전체완료0. 이전 미수납의 자동 상계/중복채무0. |
| C09 실제 구매 | 실제 상품 저장→공개상세→quote→주문 생성으로 정책/금액/규칙/hash 일치. 원 상품 할인 fixture와 원배송료 항등식 유지. 생성 뒤 catalog정책 변경에도 원 item snapshot 불변. |
| C09 경쟁/재시도 | 두 실제 DB 연결에서 상품정책 변경 vs 주문 생성: 일관된 구정책 주문 또는 새quote409만. 같은 주문key 재생은 옛snapshot, 다른fingerprint409. 같은quoteId 콘텐츠 변경거부. legacy 과거주문hash/7일 불변·미동의 영향0·동의override불변. |
| C03/C10 원장 경쟁 | 같은bucket 두 quote 수락 경합: 활성1·다른요청409. 첫적용 후 새quoteΔ차액만, 첫철회 후 새quote는 미확정액을 빼지 않음. 두bucket 수락 중 하나 실패 시 둘다예약0. 동일attempt/job/result 재생 후 A/R/공제효과 각각1회. |
| C06 실패주입 | fee예약/동의/refund/history/Outbox 실패 시 접수전체rollback. UNKNOWN에서R유지·철회0. FAILED에서payment예약0/feeR유지, 재시도R증가0. 제공자성공 뒤 fee적용실패→동일결과재개/A증가1·외부재환불0. |
| 프런트/브라우저 C08/C09 | 판매자3,000→0/5,000 편집, 구매상세·주문전표시/API·DB동일, 변경시stale견적재확인, 금액문자열/BigInt,0과미설정 구분. 공개표시가 현재구매총액을 회수비만큼 올리지 않음. |
| ArchUnit/이행 | 공개포트만 사용/무순환. 기존상품source MIGRATED_DEFAULT_3000, 기존주문은UNRESOLVED 유지. 과거 JSON 읽기 성공, 신규상품정책 미저장 신규주문 거부, 기존migration checksum불변. |

C07/C09 신규 fixture는 실제 상품/주문/배송 경로로 만든다. fee 누적·실패 검사는 공통 포트를 실제 DB에서 호출하되 실반품 UI/회수·검수 워크플로가 구현되었다고 표시하지 않는다. 기존 필수 목록을 축소하지 않고 새 assertion을 등록한다.

### 11.6 구현 범위·인계 종료

catalog 설정/구매 전 공시, quote fingerprint, 원 item snapshot, legacy 동의 사본, 공통 fee 견적·원장·동시성 계약은 승인된 C 보완 범위다. D 실반품 접수 UI·회수·검수·판매가능 입고·추가비용 수납/면제는 구현하지 않는다. 공통 포트의0원 성공도 D 회수/검수를 생략할 근거가 아니다. 발송 전 취소 fee0 및 원배송비 반환 계약은 변함없다.

Sol이 V41 이후 migration을 작성/실행 중이므로 본 문서는 신규 번호를 예약하지 않는다. 구현 직전 Sol의 실제 최신 파일·실행 이력과 대조해 다음 미사용 번호를 선정하며 **이미 실행된 migration은 수정하지 않는다**. 상품 정책 초기화와 새 주문 snapshot 이행은 기존 자원/DB/과거 실패 증거를 삭제하지 않는다.

부모의 후속 인계에 따르면 V41 restitution/V42 refund/V43 claim은 새 DB에서 실행되었다. 따라서 이 세 파일도 불변이며 fee 추가는 **V44 이후 실제 미사용 번호를 Sol과 확인**한다. 전달받은 집중 취소 검사 통과는 본 검토의 실행 결과나 C 전체 통과로 대체하지 않는다.

추가 사용자 사업 선택은 필요 없다. 부모가 기존 Sol에 위 범위를 위임할 수 있다. 본 문서의 기술 계약 확정만으로 C 전체 완료를 선언하지 않는다. 이 검토에서는 제품/검사 수정·하네스·커밋·푸시·배포·D를 수행하지 않았다. 신규 설계 assertion은 모두 NOT RUN이며 독립 전체 검증은 구현/소스 동결 후 별도 요청으로 수행한다.

## 12. 공통 회수비 포트와 D 경계 — 2026-10-31 KST

부모/Sol의 제안인 **실제 paid/delivered 원단위와 B RETURN hold를 사용한 테스트용 조정 코드 → 공통 fee 예약 → 실제 Refunds/provider 결과 → fee 적용 검증**은 §11의 의도에 맞는다. C가 반품 HTTP/UI·접수/승인/회수/검수·반품 완료 workflow를 구현하는 것은 아니다. 아래 계약을 적용하면 추가 사용자 정책 선택 없이 진행할 수 있다.

### 12.1 CANCEL 행을 가짜 반품 claim으로 사용하지 않는다

현재 `claim`은 CANCEL 전용 정책·receipt·환불효과를 담는다. 여기에 가짜 CANCEL 행을 넣어 RETURN hold나 반품 공제액을 연결하면 C 종결/재고 복구와 혼동된다. 또한 UUID만 넘기고 fee/환불 귀속을 검사하지 않는 방식도 허용하지 않는다.

최소 공통 귀속은 claim 모듈 소유의 **환불 업무 식별 레코드**(예: `claim_financial_intent`)다. id를 미래 coordinator의 server claimId로 사용하고 orderId/sellerOrderId/holdId/causeId/kind를 고정한다. kind는 CANCEL/RETURN으로 구분하되 RETURN 레코드 자체가 반품 접수·승인·검수 완료를 뜻하지 않는다. 기존 CANCEL 행과 별개 상위 식별자이며 기존 CANCEL은 동일 id로 등록/연결한다. RETURN은 공통 포트 검사용 내부 호출로만 생성하고 외부 endpoint를 노출하지 않는다.

귀속 제약은 `(intentId,sellerOrderId,orderId)` 고유·seller/order 복합FK·hold/order 복합FK·cause 고유다. 별도 intent_unit은 원 item/ordinal/order/seller 귀속과 고유 단위를 참조한다. 생성 포트가 hold.kind=RETURN, cause/단위집합 일치, 실제 원 배송 완료·정책 적격성을 확인한다. 같은 intent/cause의 내용 변경409, 동일 내용 재생은 원 식별자/영수증이다. 기존 생성된 CANCEL/refund의 이행은 실제 원 claim/hold에서만 backfill하며 과거 금액/결과를 바꾸지 않는다.

fee assessment는 위 식별자, 수락 quote/consent, originalShipment bucket, 선택 원단위를 FK로 결합한다. payment refund의 claimId도 같은 공통 식별자에 귀속되도록 복합FK 또는 이에 준하는 DB 제약을 둔다. **payment 코드가 claim 내부를 조회하는 것은 금지**하며 claim→payment 공개 호출과 공통 ID 전달을 유지한다. DB의 선언적 귀속 제약은 역방향 Java 의존이나 payment→claim callback을 만드는 이유가 아니다. 이름은 구현에 맞게 조정할 수 있으나 가짜 CANCEL·미귀속 UUID로 대신하지 않는다.

### 12.2 최소 공개 계약과 책임

공통 포트는 `ReturnFees` 등 claim/api 아래에 두며 web binding을 만들지 않는다. 호출자는 실행 중인 동일 거래를 유지한다. 의미상 다음 단계가 필요하다.

| 연산 | 필수 책임 |
| --- | --- |
| quote | 현재 본인/GAS Authority, order, 선택 원단위·책임을 받아 order 공개 포트로 원 배송/저장 정책/net/현재 revision을 확인. 원장 revision을 포함한 불변 견적을 저장/반환. 조회 자체는 hold/금액 예약0. 외부 제공 금액/full/confirmed는 입력으로 받지 않음. |
| accept/reserve | quoteId/hash/revision/requestKey와 현재 Authority, 서버 intent/hold 참조를 검증. quote 동의 영수증·intent·assessment/배분/bucket slot을 같은 거래로 저장. hold는 coordinator가 B 공개 acquire로 얻되 포트가 실제 귀속/단위/RETURN을 재확인. 마지막 업무 잠금 대기 뒤 현재 권한 재확인. |
| bindRefund | 실제 Refunds.reserve가 반환한 attempt를 assessment와 연결. payment/order/seller/intent·purpose ITEM·원 quote 참조 및 **amount=선택 원net합−이번 공제합**을 검증. 입력 amount나 'success=true'를 믿지 않음. FK와 같은 거래로 저장해 예약만 있고 연결 없는 접수를 commit하지 않음. |
| apply | 저장된 attemptId/version을 받아 **Refunds.get**의 실제 확정 SUCCEEDED/금액/귀속/결과 reference를 다시 검증. 동일 assessment의 A/R 전환·공제/미수납·적용 영수증/이력/Outbox를 원자 적용. 실패/UNKNOWN/다른 attempt/다른 원단위 결과로 apply 불가. |
| withdraw | 저장된 실제 결제 상태와 B hold 상태를 확인. RESERVED는 Refunds.cancelUnsent와 같은 거래, definitive FAILED는 실제 확정 결과로만 허용. PROCESSING/UNKNOWN/SUCCEEDED 거부. fee R/slot 해제와 B release(WITHDRAWN), 업무 영수증/이력을 같은 거래에서 처리. |

각 단계를 합친 façade를 제공해도 된다. 분리하면 coordinator 거래 전체의 끝에 연결 완전성을 검사하는 deferred 제약/동등한 방어가 있어야 한다. payment 재시도는 기존 assessment를 유지하고 새 attempt를 순번별로 연결하며 옛 시도/결과를 지우지 않는다. 새 attempt 성공이 옛 FAILED 결과의 임의 성공 변경이 되지 않도록 한다.

worker 적용은 사용자 세션이 만료되었다고 이미 수락된 환불을 취소하지 않는다. 대신 저장된 유효 동의/승인 명령과 실제 payment 결과·현재 intent/hold를 검증한다. 사용자 accept/withdraw는 현재 권한을 다시 검사한다. 테스트 fixture가 trusted server cause를 생성하더라도 현재 권한 검증을 생략하는 별도 제품용 우회 포트를 만들지 않는다.

apply는 **금융 공제 원장 적용만** 한다. B completeRefund, RETURN 재고 입고, coupon full 복구, order 전체 종결, D COMPLETED를 호출하지 않는다. 회수·검수 근거 없이 그런 효과를 냈다면 C 경계 위반이다. 추가비용 잔액은 그대로 남는다. 0원 실제 refund도 provider0/실제 성공 영수증 경로를 거쳐 같은 금전 근거를 제공하지만 실반품 완료를 뜻하지 않는다.

### 12.3 C 결과 worker와 테스트 조정 코드

C `JdbcClaims.result`는 CANCEL intent만 처리한다. RETURN intent의 결과를 CANCEL 행에서 억지로 찾거나 '알 수 없는 claim' 오류로 무한 재시도하지 않는다. 결과 전달 경계에서 kind를 공개 식별 포트로 구분하고 RETURN 결과는 공통 금융 결과로 보존한다. 이때 no-op 소비/ack는 RETURN 업무 완료가 아니다. D 소비자는 추후 저장된 미적용 결과를 조회·재개할 수 있어야 하며 메모리 callback이나 이미 사라진 job에만 의존하지 않는다. 실제 제품에서 RETURN intent 생성 endpoint는 없으므로 이번에는 테스트 조정 코드만 이 흐름을 호출한다.

테스트는 다음을 실제 공개 포트로 수행한다.

1. 상품/정책/견적/주문 생성과 승인으로 paid 주문을 만들고 A 분리 출고·배송 완료로 원 배송/원단위를 얻는다.
2. 실제 현재 회원/GAS 권한으로 fee quote를 얻는다. 한 거래에서 B RETURN hold, 공통 intent, quote 동의/fee reserve, Refunds.reserve 및 bind를 수행한다. 각 중간 실패는 전체rollback이어야 한다.
3. 실제 Refund worker/provider와 lease를 통해 결과를 생성한다. scripted DEMO 행동은 테스트 설정으로 제어할 수 있으나 payment/fee의 성공 상태를 SQL UPDATE로 만들지 않는다.
4. 테스트용 조정 코드가 저장 결과를 읽고 공통 apply를 호출한다. D 프로세스를 시작하거나 CANCEL 결과 worker에 RETURN을 맡기지 않는다.
5. A/R·공제·미수납·동의/결과 영수증을 검증한다. hold는 RETURN 업무 미완료로 유지되고 재고/쿠폰/부모종결 효과0임을 함께 assertion한다.

성공 후 다음 부분 반품의 max 차액 검증은 같은 배송의 **다른 미점유 원단위**로 새 intent/hold/동의를 만든다. 앞선 RETURN hold를 SQL로 완료해 두 번째 요청 자격을 만들 필요가 없다. 기존 fee 금융적용 완료로 bucket slot은 비어 있지만 실제 반품 hold는 남아 있는 것이 이 공통 포트 검사 범위다. 원 배송별 추가부과/미수납 유지가 이 상태에서도 일치해야 한다.

### 12.4 구체 증거와 완료 표현

- C07/C09: 실제 구매 snapshot3,000/5,000으로 첫3,000·다음차액2,000,0원,다른배송합산,판매자책임제외를 확인한다. 수락 quote/동의 내용은 DB 원문과 대조한다.
- C03/C10: 같은 배송 두 intent 경쟁은 slot1/다른요청409. forged intent/hold/unit/seller/attempt/amount/reference 연결은 FK 또는 포트409로 거부하고 participant 변화0. 같은 quote 내용 변경/동의 replay도 검증한다.
- C06: 실제 provider UNKNOWN/FAILED는 apply 거부와 R 유지, 재시도 성공은 같은 assessment1회 적용. accept/bind/apply/withdraw 각 단계 실패주입으로 fee/payment/hold/동의/이력 원자성을 검증한다. 응답유실·ACK 재처리에도 동일 영수증/효과1회다.
- 현재 자격·CSRF는 실제 HTTP가 존재하는 취소/상품 경로에서 계속 검증하고, 공통 fee의 현재 자격/자연만료는 실제 Authority·DB 잠금 대기 검사로 확인한다. 존재하지 않는 D HTTP의 CSRF 검사를 통과했다고 적지 않는다.
- 원 귀속/금액을 SQL로 조작한 위반 검사는 별도의 DB 제약 검사로 표시한다. 성공 fixture는 공개 포트와 실제 제공자 결과를 사용한다. 결과 명칭은 **“공통 fee 계약 통합 검사(테스트 조정 코드)”**이며 “사용자 실반품 E2E”가 아니다.

정적 검토 R-C01/R-C02의 수정 소식은 부모 인계로만 받았으며 별도 불변 artifact 재검토 전 해결 판정을 하지 않는다. 현재 V46까지 사용됐다는 인계를 따르며 신규 번호는 Sol과 실제 미사용 후속을 확인한다. 과거 migration 수정 금지. 본 절은 문서 판단만 수행했고 제품·테스트·하네스·커밋·D 실행은 하지 않았다. 위 신규 assertion은 NOT RUN이다.
