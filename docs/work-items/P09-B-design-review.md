# P09-B B1 설계 검토 — 직접·자동 확정과 원단위 보류

2026-10-30. 기존 지정 검토세션 `01a189b0-2b5e-70d3-8276-214a42f6570d`의 정적 설계 판단이다.

## 활성 작업·판정·승인 경계

**최신 H03 결정: 사용자가 선택2(기존 원 배송완료부터7일 적용)를 명시 승인했다.** 아래 승인 대기·LEGACY_UNSET 유지안·선택1 권고는 승인 전 이력이다. 현재 구현에는 마지막 **§10 승인 후 적용 계약**이 우선한다. 기존 정책 결정을 다시 묻지 않으며 B08 구현을 진행할 수 있다. B 제품 검증/완료 판정은 별개로 아직 NOT RUN이다. 기존 DB에 이행이나 자동확정을 실행할 권한은 이번 로컬 구현 승인에 포함되지 않는다.

활성 작업은 **P09-B B1**이다. P08 EOF 정리와 P09-A 마감은 완료된 과거 이력이며 재개하지 않는다. 전달된 최신 사용자 요청은 “P09-A 인계 내용과 작업 계획을 확인하고 P09-B 작업을 시작해줘. 검토는 검토세션에서 진행하고, 커밋 및 배포는 진행하지 말아줘.”다. 이번 세션은 이 문서만 작성한다. 제품·검사·하네스 수정, 전체 검증, 새 에이전트, 커밋·배포·푸시, C 이후 구현은 하지 않는다. 구현 담당은 부모의 기존 Sol이며 실제 실행 모델을 이번 문서로 변경했다고 주장하지 않는다.

**B1 판정: 아래 기술 계약은 구현 가능하게 확정한다. H03의 기존 주문 사업정책은 사용자 결정이 필요하므로 해당 이행·B08 최종 판정과 B 전체 완료는 보류한다.** 신규 주문7일 snapshot·직접 확정·실제 hold/출고 경합·worker·CLOSED 호환성은 독립 진행할 수 있다. H05 교환 완료 후 새 기한은 E1 게이트이며 B에서 임의 값을 정하지 않는다. 승인 대기라는 이유로 기술 계약까지 미정으로 남기지 않는다.

## 근거와 실제 코드에서 확인한 차이

읽은 근거: README, development-workflow, verification-harness, model-routing, P09/P09-B, P09-A 최종 인계·design-review·review, requirements F09/F12·§9, shop-design §8~12. A 개발3·독립1은 Java467/browser110/frontend225/Node75와 필수51묶음 통과 이력이다. 기준 HEAD `ed96936740a6295affb30c5c6046ca3a3553513a`, `codex/p05-d` dirty, A 입력476/SHA `052D974EA9F83B18667C84D496230C4C94E29FDDCEC14F655D2D88CD8D2FBC7A`다. 이 설계 검토는 A 실행 결과를 B 신규 기능의 통과로 재사용하지 않는다. 현재 migration 최대 번호는 **V37**, B 신규 번호는 구현 직전 충돌을 다시 확인하고 V38부터 사용한다. V31/V36/V37은 수정하지 않는다.

| 실제 위치 | 확인한 내용과 B 영향 |
| --- | --- |
| `order/api/FulfillmentOrders`, `InitialDeliveryWriter`, V37 | order가 원 eventId·시각·shipmentId·원단위 불변 사실을 소유한다. writer는 현재 PAID 검사 후 exact receipt를 검사하여 CLOSED 뒤 replay가 막힌다. |
| `JdbcOrders.save` | `policy_version`은 견적 snapshot 값이다. 신규 주문 생성 거래에 별도 기간 snapshot을 넣어야 한다. 주문 생성 exact retry에서 현재 정책으로 덮어쓰면 안 된다. |
| `JdbcOrderQueries.requireOwned` | 현재 계정/GAS→order FOR SHARE→권한 시간 재검사다. 변경 명령에서 SHARE 후 UPDATE 승격은 두 동시 요청의 교착을 유발할 수 있으므로 처음부터 배타 order 잠금인 별도 명령용 경로가 필요하다. |
| `JdbcShipments.sellerLock/workerLock` | `writePaidSellerOrder/lockPaid`가 receipt보다 앞선다. CLOSED 후 ship/split/tracking/progress/migrateDelivery의 합법 replay를 별도로 보존해야 한다. prepare는 CLOSED에서 생성 없이 no-op여야 한다. |
| `JdbcShipments.ship` | 현재 order 소유의 출고 원단위 사실은 없다. hold가 shipment를 역조회하지 않고 발송 후 취소 보류를 거부하려면 실제 출고 사실을 같은 거래로 전달해야 한다. “hold가 없으면 true”만 추가하면 ship 선승 후 hold도 성공할 수 있다. |
| `PaymentCoordinator.applyLocked` | 성공 결과에서 PAID만 이미 결제 완료로 처리한다. CLOSED 뒤 같은 성공 결과가 오면 LATE_SUCCESS 보상 경로로 떨어질 수 있다. B09 필수 회귀이며 실제 PG 호출 없이 stub으로 검증한다. |
| `JdbcJobs/WorkerEngine/JdbcOutbox` | at-least-once,30초 lease·10초 heartbeat·token별 complete/fail, enqueue와 outbox 동일 거래가 있다. 현재 fencing은 job 완료/실패를 보호하며 업무 handler의 모든 쓰기를 자동 차단하는 계약은 아니다. |
| V31 `seller_order`, `OrderQueries`·UI | 하위주문에 별도 lifecycle state가 없다. 부모 state를 판매자 projection에 사용한다. 부분 확정으로 부모 CLOSED를 만드는 shortcut을 추가하면 안 된다. |

이하 파일명은 `backend/src/main/java/com/example/marketplace/` 기준이다. 새 API·테이블·검사명은 이번 설계의 확정 목표이며 아직 구현되거나 실행된 것으로 표시하지 않는다.

## 1. H03 기간 정책: 신규 주문 확정, 기존 주문 승인 대기

신규 주문은 주문 INSERT와 같은 거래에서 order 소유 `order_aftercare_policy`를 저장한다. `order_id` PK/FK, `schema_version=1`, `status=ACTIVE`, `policy_version=P09-B-1`, `confirmation_delay_seconds=604800`, `claim_window_seconds=604800`, `captured_at`을 명시한다. UTC elapsed duration7×24시간이며 달력 날짜/브라우저 시간대 기준이 아니다. 값과 버전을 함께 저장하며 견적 `P07-C-1`·기존 금액·quote JSON은 그대로 둔다. 후속 설정 변경은 신규 주문에만 적용하고 기존 snapshot은 UPDATE하지 않는다. 아직 없는 운영 설정 UI/정책 모듈은 만들지 않는다. 버전과 값이 명시된 서버 정책 공급자를 사용하고 새 버전 변경 fixture로 snapshot 불변을 검증한다.

주문 생성 exact retry는 최초 snapshot을 반환한다. 과거 주문 생성 요청의 retry를 “신규 주문”으로 취급해 기간값을 채우지 않는다. V38에서 기존 모든 주문(PENDING_PAYMENT 포함)은 명시적 `LEGACY_UNSET`으로 표시하고 기간/업무 정책 버전은 NULL로 둔다. 상태별 CHECK로 ACTIVE의 값 누락과 LEGACY_UNSET의 가짜7일 값을 거부한다. 이는 무근거 기간을 사용하는 것을 방지하는 기술상 기본 상태이며 영구 사업정책 승인이 아니다.

신규 ACTIVE의 기한은 각 unit의 A 원 완료시각을 microsecond로 정규화한 값+저장 delay다. `now < dueAt`은 반품/교환 신청 가능, `now >= dueAt`은 자동확정 가능이다. Clock은 마지막 관련 잠금 대기 후 다시 읽고 MICROS 정밀도로 비교한다. `transaction_timestamp()/now()`처럼 거래 시작 시각에 고정된 값을 경계 판정에 사용하지 않는다. 직접 확정은 완료 사실이 있으면7일 전후 모두 가능하며 클레임 hold/환불/이미확정은 제외한다. 완료 사실 없는 예외/미배송 단위는 확정할 수 없다.

### 사용자 결정이 필요한 기존 주문 적용

| 선택 | 실제 영향 | 판단 |
| --- | --- | --- |
| 1. 기존 주문은 자동확정 제외를 명시적으로 채택, 본인 직접 확정은 허용 | 기존 시각을 유지하고7일 자동확정/기간 만료를 소급하지 않는다. 기간 기반 반품·교환 자격은 정책 미설정 안내와 별도 검토로 남는다. 신규 주문만 ACTIVE7일 | **권고**. 기존 기록을 바꾸지 않는 최소안. 영구 예외로 채택할지는 사용자 결정이며 결정 전에는 보호 상태로만 구현 가능 |
| 2. 기존 주문도 원 배송완료+7일 적용 | 이미7일 지난 미보류 단위가 즉시 자동확정 대상이 되고 자동 신청 권리가 닫힐 수 있다. 명시 대상·적용 버전·원인·dry-run 영향 수량 필요 | 명시 승인 없이는 실행 금지 |
| 3. 기존 주문에 별도 유예 기간 부여 | 예: 승인한 적용시각부터 별도7일. 원 배송 사실은 유지하고 별도 grace 정책/기준시각을 저장해야 한다. 기존 요구사항의 기산점과 다름 | 추가 사업정책 변경. 이번 시작 요청으로 승인된 것으로 간주 금지 |

결정 전 LEGACY_UNSET에서 자동확정 job 생성/실행은 쓰기0, 기간 의존 RETURN/EXCHANGE 신규 hold는 `409 POLICY_UNSET`로 명시한다. 이를 “기한 만료”로 표시하지 않는다. 본인이 명시하는 직접 확정과 미출고 CANCEL hold는 기간값이 필요 없으므로 기존 배송 사실·현재 자격·원단위 제약 하에서 진행할 수 있다. 과거 주문의 금액·시간·이벤트를 갱신하지 않는다. B08의 **선택된 기존 정책에 대한 실제 이행 assertion**이 남아 있으므로 승인 전 B 전체 완료는 불가하다. 부모가 이 표를 근거로 사용자에게 한 번 결정받고 정책 결과를 작업 문서에 고정한다.

## 2. 원단위 상태·스키마

원 식별은 A와 같은 `(order_item_id, ordinal)`, ordinal0..quantity-1이다. 새 선택체계/수량만으로 된 두 번째 원장은 만들지 않는다. 최소 구조는 다음과 같다.

| order 소유 구조 | 필수 키·불변식 |
| --- | --- |
| `order_unit_lifecycle` | 원단위 PK/FK, order/seller/item 복합 귀속, state `OPEN/HELD/CONFIRMED/REFUNDED`, revision≥0, active_hold_id/confirmation_id/refund_cause_id의 상태별 CHECK. 모든 기존/신규 원단위에 정확히1행. 직접 없는ordinal/다른order·seller 참조 거부 |
| `order_confirmation` + command receipt | 주문·요청키 unique, 정렬된 요청과 expected revision의 canonical hash, 원인 DIRECT/AUTO·actor·처리시각·최초 결과. unit별 최초 확정 연결은 unique. 같은키 다른 내용409, exact replay는 이후 상태보다 먼저 처리 |
| `order_unit_hold` 및 append-only event | holdId/causeId, 종류 CANCEL/RETURN/EXCHANGE, 정확 단위집합, 상태ACTIVE/RELEASED/REFUNDED, cause별 최초결과·해제이력. 단위별 ACTIVE 중복0. 취소된 hold의 키 재사용으로 새 hold를 만들지 않음 |
| `order_dispatch` / `order_dispatch_unit` | 원 SHIPPED eventId·shipmentId·원시각·정렬 단위 fingerprint, 원단위별1회. A 출고와 취소 보류의 직렬화 근거. order→fulfillment FK/런타임 조회 없음 |
| `order_confirmation_schedule` | unit·generation/revision별 dueAt·policy version·배송 근거, job dedupe·payload·최초 스케줄을 불변 저장. hold 해제는 새 generation으로 재평가; 완료/실패 job 초기화 금지 |

원 `order_unit` 금액 및 A `order_initial_delivery(_unit)`은 UPDATE하지 않는다. 현재 lifecycle와 append-only 이력·receipt·Outbox는 같은 거래다. 최소 DB 제약으로 잘못된 귀속·중복 종결·상태별 누락을 거부하고, 과거 확정 뒤 환불 같은 추가 전이는 C 이후 명시 설계 전까지 허용하지 않는다. 직접 confirmation 요청은 선택된 전부를 원자 처리하며 일부가 부적격이면 전부409다. 자동은 단위별 job으로 보류되지 않은 다른 단위를 처리할 수 있다.

GET/POST의 `expectedRevision`은 별도 `order_confirmation_state(order_id PK, revision)`의 주문별 revision이다. 새 배송/출고 사실, 실제 hold 전이, 확정/환불 단위 전이마다 같은 부모 잠금 안에서 증가하고 exact replay는 증가시키지 않는다. 기존 purchase_order.version과 배송 version을 혼용하지 않는다. unit revision/generation은 worker의 오래된 평가 판정에 사용한다. 이 aggregate 상태행도 기존/신규 주문 생성·이행과 같은 거래에서 정확히1개 생성한다.

## 3. 공개 API와 수량 선택

`order/api/OrderConfirmations`를 추가한다. `Unit(itemId,ordinal)`, `Command(key,expectedRevision,units)`, `Result(receiptId,units,confirmedAt,cause,revision)` 값 타입만 공개한다. GET은 단위별 원 배송시각/dueAt/policyStatus/lifecycle/현재revision·직접확정 가능 여부와 사유를 반환한다. HTTP는 `GET /api/orders/{number}/confirmations`, `POST /api/orders/{number}/confirm`로 고정한다. POST는 key·expectedRevision·units만 허용하고 owner/source/confirmed/deliveredAt/policy/dueAt/actor 입력은400이다. UI는 이 GET에서 받은 실제 원단위를 명시 선택한다. A의 배송 split이 서버 ordinal 오름차순을 선택하는 규칙은 바꾸지 않는다.

key 형식은 기존 배송과 같은 ASCII `[A-Za-z0-9_-]{1,100}`. unit 배열은 비어있거나 중복이면400, 최대 원 주문 단위 수 안에서 제한하고 서버가 UUID문자열+ordinal 정렬한다. 타 주문/없는 단위는 소유권 확인 뒤409, 타 주문 자체는404. 같은 order/key의 원 요청·expectedRevision hash는 최초 receipt와 비교한다. current authority→배타 order lock→권한 재검사→exact receipt→현재 revision/eligibility 순서이며 CLOSED 또는 다른 확정이 진행됐어도 exact receipt는 최초 결과를 반환한다. 새 키로 이미확정 단위를 포함하면409. 현재 권한 상실을 과거 receipt로 우회하지 않는다.

`OrderQueries`에는 명령용 `lockOwned(number, Authority)`를 추가해 처음부터 FOR UPDATE를 사용한다. 회원은 현재 principal/session/authVersion와 ACTIVE를 기존 HistorySessions로 확인한다. GAS는 별도 GUEST_ACCESS의 buyer/passwordVersion·idle/max·폐기/회전을 현재 공개 포트로 확인한다. 구매 인증/결제 승인 권한과 혼합하지 않는다. POST CSRF·no-store·unknown field 거부는 기존 HTTP 패턴을 따른다. GAS는 반환된 buyer가 원 주문 owner와 같은지 확인하며 로그인한 다른 회원으로 fallback하지 않는다. 마지막 unit 잠금 뒤에도 세션 시간·현재 권한을 재검사한다.

최소 UI는 실제 GET 적격 단위를 표시하고 명시 확인 후 POST한다. 성공 뒤 현재 GET, 응답유실은 동일 body/key만 명시 재시도,409는 자동POST 없이 재조회,401/403·계정전환·늦은응답은 사적 데이터와 새 명령 가능 상태를 폐기한다. CLOSED 상세/이력은 유지하고 신규 명령만 제한한다. 일반 React UI에 내부 job/token/DB용어를 노출하지 않는다.

## 4. 내부 hold/release/refunded와 출고 경합

`order/api/OrderUnitHolds`를 실제 저장 포트로 추가한다. `acquire(orderId,holdId,causeId,kind,units)`, `release(orderId,holdId,causeId,outcome)`, `completeRefund(orderId,holdId,causeId,refundReference)`는 신뢰된 내부 호출에만 제공한다. 외부 hold HTTP·가짜 claim 구현은 없다. caller의 REQUIRED 거래를 요구하고 order를 배타 잠근다. 향후 claim은 현재 신청자 권한과 신청 원자성을 담당하며 order는 실제 기한·단위 상태·중복을 다시 검증한다. causeId/holdId 소유·정확 단위집합·결과 hash를 저장하여 다른 claim의 해제·부분 키 재사용을 거부한다.

- CANCEL acquire: 아직 order_dispatch_unit이 없는 OPEN만 가능. A ship과 동일 order/unit 잠금 아래 한쪽만 성공한다. 단위 일부 취소 hold면 해당 unit을 포함하는 전체 배송 ship은409; A split으로 묶음을 나눈 뒤 다른 단위만 발송할 수 있다. split은 실제 held 원단위를 몰래 제외하거나 새로운 ordinal로 대체하지 않는다.
- RETURN/EXCHANGE acquire: 완료 사실이 있는 OPEN, ACTIVE 기간 snapshot, now<dueAt만 가능. 확정과 경쟁하면 한쪽만 성공한다. 만료 경계에서는 hold 거부·자동확정 가능이다. 미래 claim API 연결 검증은 C/D/E에서 다시 한다.
- RELEASED: 같은hold exact replay 무효과. REJECTED 또는 교환출고가 전혀 없었던 WITHDRAWN만 기존 배송 기준 재평가한다. now≥due이면 새 generation 즉시평가 job, 미래이면 원 due job을 보존/새 원인 job 예약. hold 해제로 새7일을 부여하지 않는다.
- REFUNDED: 활성 hold의 같은 단위를 원자 종결하고 confirm 대상에서 영구 제외한다. 반환 값은 order 단위 상태 사실이며 payment 성공·재고·쿠폰 처리 완료를 B가 검증한 것으로 주장하지 않는다. C/D/F가 실제 금전 성공 원인과 필요한 조정을 같은 orchestration에서 확정한 뒤 호출하는 신뢰 계약이다. B는 실제 포트 fixture/rollback을 검사하되 실제 환불 구현은 하지 않는다.
- EXCHANGE_COMPLETED release는 **E1 계약이 생기기 전 지원하지 않는다**(명시409, hold 유지). 원 배송+7일로 즉시 확정하거나 임의 새 배송시각을 HTTP로 받지 않는다. E는 교환회차·새 신뢰 배송완료 사실과 확정 기준을 별도로 전달하고 최초 fact를 보존해야 한다. 반려/교환 전 철회와 교환 완료를 같은 release enum으로 합치지 않는다.

출고 근거를 연결하려면 `FulfillmentOrders.recordDispatch(Dispatch)`를 추가한다. A의 실제 ship 거래가 생성한 SHIPPED event/저장시각/정확 단위를 전달하고 order는 HELD/REFUNDED/CONFIRMED를 거부한 뒤 출고 receipt를 저장한다. 이후 A state/event/Outbox가 실패하면 이 사실도 rollback한다. 한 번 보낸 단위의 다른 shipment/event 재할당은409, 같은 fact exact replay 무효과다. 선행 읽기만 하고 잠금을 풀어서는 안 된다.

기존 A SHIPPED/IN_TRANSIT/DELIVERED는 신규 migration에서 V37의 원 shipment_unit과 유일한 applied SHIPPED event를 읽어 **추가 사실만** backfill한다. order 런타임이 shipment 테이블을 읽는 구조는 금지한다. 이는 A의 V37 준비 marker 이행과 같은 일회성 이행 경계이며 old event/state/time은 수정하지 않는다. 원 event 부재/복수·단위 모순은 guessed now로 채우지 않고 migration 명시 실패다. V37→V38 새 DB fixture에서 이행 후 실제 acquire가 과거 발송단위를 거부하고 PREPARING만 허용하는지를 검증한다. 안전한 현재 스키마에 연결할 수 없는 이행 데이터가 있으면 별도 검토 대상으로 막는다. 이 backfill을 완료하지 않은 상태에서 “dispatch 행 없음=미출고”라고 판단하는 구현은 불가하다.

## 5. 잠금·거래 순서

| 참여자 | 순서와 재검사 |
| --- | --- |
| 직접 확정 | identity/session 또는 guestBuyer/GAS → purchase_order FOR UPDATE → unit UUID/ordinal 정렬 → 관련 receipt/hold → 현재 자격/Clock 재검사 → 원자 변경/Outbox |
| 미래 claim acquire/release/refund | 호출자가 필요한 현재 권한을 먼저 확보 → order → 정렬 unit → hold/receipt. claim 행을 잠근 뒤 order를 역순으로 잡지 않도록 C/D/E가 이 순서에 맞춰 연결 |
| 판매자 ship/split/tracking | 기존 seller account/session/store → order → **그 하위주문의 관련 unit을 정렬 잠금** → 정렬 shipment → 재검사. A 최초 배송 사실 기록의 order 재잠금은 이미 소유한 부모의 재확인 |
| 배송 worker | order → 정렬 unit → shipment → order fact. order 내부 확정/보류는 shipment를 잠그거나 역조회하지 않음 |
| 자동확정 | order → 해당 unit → schedule/receipt → 필요 job lease guard → Clock 재검사/변경. 고객 세션을 요구하지 않음 |

부모 order 잠금은 서로 다른 단위도 잠깐 직렬화한다. “다른 묶음 독립 처리”는 한 묶음의 hold가 다른 묶음 확정을 막지 않는 논리적 독립성이지 현재 구현에서 병렬 commit을 보장한다는 뜻이 아니다. 보류/자동확정/ship은 이 공통 잠금을 생략할 수 없다. 잠금 후 외부 제공자 호출·느린 비밀번호 hash를 넣지 않는다. 다중 주문을 한 hold 명령에 허용하지 않는다.

## 6. durable producer·worker·Outbox·fencing

현재 `recordInitialDelivery`의 **새** 사실 INSERT와 같은 거래에서 ACTIVE 대상 unit별 `order_confirmation_schedule`와 `Outbox.append`를 생성한다. 동일 fact 재처리는 같은 schedule을 반환하고 current clock으로 다른 scheduledAt을 만들지 않는다. LEGACY_UNSET은 기한/자동 job을 만들지 않는다. 새로운 모듈의 `ConfirmationJobs`가 기존 platform JobHandler로 `ORDER_CONFIRM_DUE`/`ORDER_CONFIRM_REEVALUATE`를 등록한다. 한 job은 unit+generation+policy/delivery 참조만 가지며 브라우저 timestamp를 신뢰하지 않는다. payload의 dueAt이 있더라도 저장 schedule과 비교한다.

job은 현재 order/unit/policy/hold/배송 근거를 다시 읽는다. CONFIRMED/REFUNDED/stale generation은 무효과 완료, HELD는 해당 평가 완료 후 해제 producer를 기다린다. 미도래라면 일찍 확정하거나 기존 job의 불변 scheduled_at을 바꾸지 않는다. 저장 dueAt에 대한 별도 지연 평가를 원자 예약하거나 RETRY를 반환하며 Clock 이상으로 계속 실패하면 명시 실패로 남긴다. 정상 경로에서 job 예약 시각과 업무 Clock을 동일 UTC/MICROS 계약으로 사용한다.

단순 startup START 키를 재사용해 항상 SUCCEEDED job으로 끝나는 스캐너만으로 복구를 보장하지 않는다. live fact/hold release producer가 필수이며, 이행 시점의 ACTIVE 미스케줄 unit을 찾는 버전별 keyset scan50·다음cursor enqueue도 같은 거래로 실행한다. 운영 재스캔은 새로운 명시 runId, unit+generation unique로 효과중복0. 기존 A job/event/payload/완료 상태를 reset하지 않는다. 정책 미결 legacy는 scan에서 명시 제외하며 자동으로 ACTIVE 전환하지 않는다.

fencing은 두 층으로 검증한다. 같은 원인 receipt와 단위 잠금이 업무효과1회를 보장하며, 기존 Jobs token/lease가 낡은 worker의 complete/fail을 거부한다. **낡은 token의 업무 쓰기 자체도 차단하려면** platform 공개 `JobLeases.requireOwned(jobId,ownerToken)` 같은 일반 포트를 추가한다: caller transaction 안에서 job행을 잠그고 현재 RUNNING/token/lease를 잠금 후 검사하며 업무정보를 받지 않는다. B 자동 handler는 order/unit 뒤 이 guard를 호출하고 잠금을 commit까지 유지한다. platform은 order를 참조하지 않는다. claim은 REQUIRES_NEW로 job 잠금을 풀고 handler에 진입하는 기존 순서를 유지한다. order가 platform 테이블을 직접 읽는 우회는 금지한다. 검사 이후 lease 시각이 지나도 같은 job행 잠금 동안 소유권이 교체될 수 없다는 직렬화 지점을 문서화하며, 완료 ack는 기존 token 검사로 다시 보호한다. 외부 작업을 이 거래에 넣지 않는다.

실제 child worker를 확정 효과 commit 후 complete 직전에 중단하고 lease 만료/재시작으로 최초 confirmation/event/Outbox가 정확히1회인지 검증한다. 원 lease를 잃은 child가 다시 진행하려 할 때 새 handler의 결과를 덮지 못하고 guard·complete/fail이 거부되는 검사도 별도 필요하다. generic worker_probe_effect 통과로 구매확정 검사를 대신하지 않는다. scanner50 commit 전/후 중단·enqueue 실패의 rollback·51번째 재개를 검사한다.

## 7. H06 CLOSED 집계와 기존 포트 호환

B의 CLOSED 조건은 **모든 seller의 모든 원단위가 CONFIRMED이고 active hold가0**인 경우다. 각 seller의 종결 여부는 자기 원단위 all-confirmed로 계산하고 부모는 전 seller가 충족해야 닫는다. 초기 B에는 추가 mutable seller state 대신 order 조회의 파생 `fulfillmentClosure`(OPEN/CLOSED)를 제공할 수 있다. 이것은 배송 상태나 결제 상태를 덮어쓰는 enum이 아니다. order_event CLOSED 및 version 증가와 confirmation/Outbox는 같은 거래에서1회다. 원 금액·배송·결제이력은 보존한다.

B 내부 REFUNDED 단위는 확정 제외 사실만 제공한다. CONFIRMED+REFUNDED가 섞인 주문/전체환불의 최종 CLOSED/CANCELED는 C의 금전·재고·쿠폰·미완료 의무를 포함한 H06 확장 전에는 자동 결정하지 않는다. 따라서 B에서는 이런 fixture를 PAID에 남기되 “미확정 수량”과 “환불 사실”을 구분한다. 이는 영구 사업정책이 아니라 C가 구현할 상태 집계의 명시 경계다. “미보류 수량이0” 또는 “일부 배송완료”는 CLOSED 근거가 아니다.

필수 호환 수정은 다음과 같다.

1. 기존 detail/member/GAS/seller/payment-history는 CLOSED에서도 현재 소유권/세션으로 조회한다. 현재 `requireOwned`는 상태 제한이 없으므로 유지한다. `paidSellerOrder/writePaidSellerOrder/lockPaid`를 전역적으로 느슨하게 바꾸지 않고 **현재 권한+기존 거래 잠금**과 **신규 쓰기 PAID 조건**을 분리한 포트를 추가/명명한다.
2. seller ship/split/tracking는 현재 권한·잠금 후 기존 receipt/event의 exact replay를 먼저 반환한다. 새로운 key는 CLOSED에서409. 기본배송과 변경된 배송 DTO의 조회도 정상이어야 한다.
3. progress/exception의 기존 event replay, `recordInitialDelivery` exact fingerprint, migration handler의 이미 기록된 fact는 CLOSED에서도 무효과로 재조회한다. CLOSED에서 새로운 delivery/새 출고·분할은 거부한다. stale 새로운 progress는 기존 계약의 무효과 처리 여부를 명시하며 상태·fact를 새로 바꾸지 않는다. prepare/PAYMENT_SETTLED/backfill은 CLOSED에서 새 BASIC을 생성하지 않는다.
4. `PaymentCoordinator.applyLocked`는 **CLOSED이면서 동일 원 attempt가 이미 SUCCEEDED**인 재통지/조회 결과를 기존 결제 성공으로 인정해 보상 job/취소 요청/재고·쿠폰 재처리0을 보장한다. CLOSED라는 이유만으로 모순된 attempt까지 성공으로 삼지 않는다. 미결/다른 attempt는 명시 충돌·검토로 남긴다. 성공 webhook의 새 transmission/digest도 확인해 기존 receipt 존재만으로 검사가 우회되지 않게 한다. 실제 PG 접근은 불필요하며 기존 sandbox stub 계약으로 검증한다.

## 8. B01~09 자동 assertion 계획

다음은 **모두 미구현·NOT RUN**이다. 구현 담당은 실제 수집명을 작업 문서와 필수 목록에 등록하고 RED/GREEN·집중/전체 실패를 구분한다. 기존 필수 Java402/browser110/frontend225 및 기존 assertion을 삭제하거나 timeout을 늘려 통과시키지 않는다.

| ID | 실제 추가/확장 검사 목표 | 필수 기대 결과 |
| --- | --- | --- |
| B01 | `ConfirmationRulesTest#sevenDayBoundaryUsesDeliveredUnits` 및 DB `confirmationUsesStoredMicrosecondBoundary` | due-1µs hold허용/auto0, due hold거부/auto1, due+1µs, Clock 역행·잠금대기 경계, 두 묶음 다른 완료시각, 미배송/예외0. 저장 시각과 계산 정밀도 일치 |
| B02 | `confirmationAndClaimHoldHaveOneWinner` | 실제 두 connection/barrier로 direct-v-hold, auto-v-hold, direct-v-auto; 각 공통 단위 winner1, 중복종결0. 다른 단위 hold는 정상 대상 처리에 영향0. 입력 배열 역순도 같은 잠금 순서 |
| B03 | `confirmationAuthorityAndReplay` | member/다른회원/GAS/다른buyer/reset/version/idle/max/회전/무CSRF, order와unit 잠금 대기 후 자연만료 재검사. key replay 최초결과, 역순 동일집합 replay, unit/revision 내용충돌409. CLOSED exact retry는 허용·현재권한상실은 거부 |
| B04 | `confirmationJobsRecoverAndReevaluateReleasedHolds` + test-only 실제 `ConfirmationWorkerProbe` | live delivery producer→저장job/Outbox→worker 완료, commit/ack child kill·lease/fencing·exact1회, 보류 중쓰기0→해제 후 새generation 재평가, 과거due 즉시/미래due 유지, REFUNDED0, stale job0, scanner51/중단재개·enqueue실패 rollback |
| B05 | `OrderConfirmation.test.tsx`, `order-confirmation.spec.ts` | 회원/새브라우저GAS의 실제 API·DB 원단위2확정/미배송3보존; 둘째묶음 완료·전체CLOSED; PC/mobile, 응답유실 동일body/key,409→GET실패·인증변경·늦은응답 폐기/자동POST0, LEGACY_UNSET 안내와 자동확정 없음 |
| B06 | 기존 ArchUnit + `confirmationBoundaryFixturesRejectReverseDependencies`, `confirmationWritesRollbackTogether` | order→fulfillment/claim/payment 구현·platform→업무 의존 거부; 공개 타입누출 거부. checked/runtime을 unit/hold/receipt/history/seller집계/parent CLOSED/job/outbox 단계마다 주입하여 모든 관련 테이블 전후 동일; 최종 deferred FK 오류도 commit전체 rollback |
| B07 | `claimHoldBlocksShipmentWithoutCyclicRead`, `dispatchFactsUpgradeAndReplay` | 실제 OrderUnitHolds와 A ship 경쟁, hold선승 ship0 및 ship선승 CANCELhold0. 일부hold 포함ship거부/나머지split출고, 해제/중복fact·유실재개. V37→V38 old shipped·delivered 단위 backfill 정확/원행불변/이행 후 acquire거부, 없는 근거 이행실패. HTTP 외부시간/boolean400 |
| B08 | `confirmationUsesPersistedPolicyVersion` | 신규 주문 저장 version/604800 고정, 정책 공급자 변경 뒤 olddue불변/newversion 적용, checkout exact retry불변, LEGACY_UNSET에 NULL/auto0·원시각/금액/event보존. **사용자 선택 후** 별도 기존정책 이행값·대상·재실행/중단재개 assertion 추가; 승인 전 통과 완료표시 금지 |
| B09 | `orderClosurePreservesHistoryAndPendingUnits`, `closedOrderRetriesDoNotTriggerPaymentCompensation` | 일부 seller/미배송/HELD에서CLOSED0, allconfirmed1회. CLOSED member/GAS/seller/payment/배송 GET, confirm/ship/split/tracking/v1/v2/initial fact/PAYMENT_SETTLED exact replay 보존, 새쓰기거부. 성공 결제 재통지·웹훅 뒤 보상/제공자취소/재고쿠폰 변화0. mixedREFUNDED는 C경계대로 성급한 종료0 |

DB snapshot은 기존 A12테이블에 새 policy/lifecycle/confirmation/hold/dispatch/schedule/이력·purchase_order/seller_order/order_event 및 연관 payment/보상·재고·쿠폰 표를 포함한다. 순서와 컬럼을 고정해 실패 전후 실제 행을 비교한다. 총 테스트 수/화면 캡처가 이 assertion을 대신하지 않는다. 각 API·internal 호출은 진짜 주문/결제/배송 fixture를 사용한다. 이행 검사는 새 DB에서 V37까지→구버전 데이터→V38→현재 제품 명령/worker를 연결하고 count-only 검사를 반복하지 않는다.

## 9. 구현 순서·다음 인계

1. 사용자 기존정책 결정 요청과 병행하여 문서에 이번 기술 계약을 반영한다. 불변 A 소스/증거·실패 기록을 보존한다. 새 B 실행 경로를 사용한다.
2. B01/B02/B07과 CLOSED 결제 재통지 회귀 RED를 먼저 보존한다. 최소 스키마·기간 snapshot·실제 보류/출고 사실·명령 잠금을 구현한다. 순환을 피하는 공개 포트는 실제 상태를 저장해야 한다.
3. 완료사실 producer·단위확정·hold 해제 재평가·fencing/Outbox/실제 worker 복구, CLOSED 호환성을 연결한다. H05 지원하지 않는 outcome은 명시 거부한다.
4. 최소 UI와 B05를 구현하고 승인된 legacy 정책의 B08 이행을 완성한다. 승인 전에도 독립 가능한 검사/집중 결과를 얻을 수 있지만 B 전체 완료는 보류한다.
5. 선택 없는 새 개발 전체 Offline→같은 소스 동결→이 지정 검토세션의 새 독립 전체를 요청한다. 이번 설계 문서에는 신규 기능 통과 결과가 없다. 완료 전 A/B 모든 필수 assertion·소스 hash·실패/자원 보존을 다시 확인한다.

C/D/E 인계는 정확 원단위, hold 종류/cause/같은 거래·잠금 순서, 정책 snapshot/legacy 상태·마이크로초 경계, refund 사실과 실제 금전조정의 책임 분리, H05 교환종결 미결, H06 mixed종결 미결이다. 실제 Toss 차단, 실기기/사용자 직접 확인 미실행, C 이후 claim/환불·리뷰/정산 미구현을 유지한다. 이번 B1 산출물은 이 파일1개이며 하네스/DB/브라우저 실행·제품 수정·커밋·배포·푸시는 없다.

문서 작성 후 A 동결476파일을 현재 SHA256과 읽기 전용 대조해 불일치0을 확인했다. 새 문서의 `git diff --no-index --check -- NUL`은 새 파일 차이로 종료1이며 공백 오류 진단은 없고 LF→CRLF Git 안내만 있다. 이는 B 제품 검증 통과가 아니다.

## 10. H03 선택2 승인 후 적용 계약 — 2026-10-30

### 승인 근거와 이전 계약의 변경 범위

부모 개발세션 `01a189ad-17dd-79f3-ad2b-1e6d7af85a3b`이 전달한 사용자 선택 원문은 다음과 같다.

> 기존 배송 완료 시각부터 7일 적용. 이미 7일이 지난 주문은 즉시 자동 확정 대상이 됩니다.

이는 위 선택2의 명시 승인이다. 기존 주문 자동확정 소급 적용에 대한 사업판단은 해결됐으며 추가 확인을 요구하지 않는다. 승인 전 LEGACY_UNSET 유지/선택1 권고/B08 승인 대기는 당시 이력으로만 보존한다. 기존7일 신청 창과 자동확정 창의 동일 경계를 사용하며 지난 단위의 신청 창도 원 완료+7일로 판단한다. 새로운 유예7일·migration 시각부터7일·현재 설정값 fallback을 도입하지 않는다. 직접 확정, hold, CLOSED, 교환완료 미지원 및 C/E 후속 경계 등 나머지 기술 계약은 유지한다.

결정 식별자는 `P09-B-H03-20261030-OPTION2`로 고정한다. 승인 날짜는2026-10-30 Asia/Seoul이며 실제 사용자의 선택 시각은 이 인계에 없으므로 임의의 `approved_at` 시·분·초를 만들지 않는다. 이 문서 작성/수신 시간이나 migration 시간을 사용자 승인 시각으로 표시하지 않는다. 실제 승인 메시지 참조와 승인 날짜, 전달 세션, 선택 원문을 정책 이행 근거로 보존한다.

### 10.1 신규·기존 snapshot과 적용시각

| 항목 | 신규 주문 | 기존 주문 이행 |
| --- | --- | --- |
| schema/status | schemaVersion1/ACTIVE | schemaVersion1/ACTIVE |
| policy version | `P09-B-1` | `P09-B-LEGACY-1` |
| delay/window | confirmation_delay_seconds=604800, claim_window_seconds=604800 | 같은 두 값604800. 별도 버전은 다른 기간이 아니라 기존 데이터 적용 출처를 식별 |
| source | `ORDER_CREATION` | `APPROVED_LEGACY_MIGRATION` |
| captured_at | 최초 주문 생성 거래에서 snapshot을 실제 저장한 UTC/MICROS 시각 | 최초 이행 거래에서 snapshot을 실제 저장한 UTC/MICROS 시각 |
| effective_at | 해당 snapshot이 신규 주문에 적용되는 최초 생성 시각 | 승인된 정책 이행의 최초 활성화 시각. 한 이행 run의 고정 기준시각을 저장하고 재시도 때 갱신하지 않음 |
| approval 근거 | 현재 B 정책 정의 | 위 decisionId·승인 날짜·원문/문서 참조를 가진 불변 이행 메타데이터 참조 |

정책값과 실제 DB 이행 시각을 분리한다. `captured_at/effective_at`은 기산점이 아니다. **dueAt은 항상 A의 원 `occurred_at + 604800초`**이며 승인·captured·effective 시각으로 원 배송시각을 옮기지 않는다. `confirmed_at`은 실제 worker가 잠금/자격 확인 뒤 확정 효과를 기록한 현재 시각이다. 과거 dueAt을 confirmed_at으로 소급 저장하지 않는다. dueAt·원 완료 eventId·실제 confirmed_at·policy version을 이력에서 각각 식별한다.

이행 run 메타데이터에는 decisionId, migration version, 실행 대상DB 식별, 최초 effectiveAt, 정책 버전/값, source를 남긴다. V38이 아직 적용되지 않았다면 새 migration에서 기존 주문에 바로 LEGACY ACTIVE snapshot을 INSERT한다. 승인 전 보호 상태가 존재하는 새 격리 fixture를 검증할 경우에는 명시된 일회성 전환으로 LEGACY_UNSET→ACTIVE를 기록하고 이전 상태/결정 원인을 append-only 이력으로 남긴다. 이미 ACTIVE인 snapshot은 현재 설정으로 덮어쓰지 않고 exact 일치만 no-op, 다른 내용은 명시 충돌이다. 적용된 migration 파일을 나중에 고쳐 재사용하지 않는다.

적용 대상은 이행 기준점에 이미 저장된 **모든 purchase_order**다. PENDING_PAYMENT/PAID/CLOSED/CANCELED/EXPIRED를 누락하지 않고 snapshot 출처만 표시한다. PENDING_PAYMENT가 이후 실제 PAID·배송완료되면 동일 LEGACY snapshot을 사용한다. CANCELED/EXPIRED/CLOSED에 snapshot이 생긴다고 해당 주문을 재개하거나 자동확정하지 않는다. 새 주문과 기존 주문의 구분은 migration 당시 존재 여부/저장된 provenance로 고정하며 매번 created_at과 현재 날짜를 비교해 재분류하지 않는다.

과거 checkout exact retry는 기존 order와 **이미 이행된 LEGACY ACTIVE snapshot 그대로** 반환한다. `P09-B-1` 신규 정책으로 바꾸거나 현재 설정값을 채우지 않는다. 정상 이행 이후 snapshot이 없는 주문은 `POLICY_MISSING` 등 명시 오류/검토 대상으로 취급한다. 조회·자동 worker·retry에서 누락을 조용히 보정하지 않는다. 정책 공급자가 나중에 새 버전/기간을 제공해도 신규/기존 ACTIVE snapshot과 dueAt은 불변이다.

### 10.2 실제 자동확정 적격 조건

policy ACTIVE만으로 확정하지 않는다. worker는 동일 order/unit 잠금과 lease guard 아래 다음을 모두 다시 확인한다.

1. 부모 주문은 PAID이고 해당 원단위 귀속이 일치한다.
2. A의 신뢰된 initial delivery event/unit 사실이 존재한다. shipment 상태 요약이나 브라우저 입력으로 대체하지 않는다.
3. lifecycle은 OPEN, 활성 hold 없음, REFUNDED/CONFIRMED 아님, 교환 완료 기준 대기 등 후속 보류 없음이다.
4. 저장 policy version/값과 schedule generation·배송 근거가 현재 단위에 맞는다.
5. 마지막 잠금 대기 후 now≥dueAt이고 정책 effectiveAt 이전 실행이 아니다.

과거 dueAt 단위는 활성화 직후 **예약 가능한 대상**이다. 실제 처리 완료는 durable worker가 실행·commit한 뒤에만 기록한다. 스캔/이행 자체가 confirmation이나 CLOSED를 쓰지 않는다. hold가 있는 과거 due 단위는 확정하지 않고, 적법한 release가 새 generation 재평가를 생성한다. already CONFIRMED/REFUNDED, 미배송, terminal 부모는 쓰기0이다. 부모 CLOSED 조건은 앞의 B 범위 all-confirmed 집계를 유지한다.

### 10.3 이행 producer·keyset50·재개

정책/dispatch/lifecycle 이행 commit 후 버전별 **새** B backfill producer를 실행한다. 기존 A initial-delivery scanner의 job을 reset하거나 그 START 키를 재사용하지 않는다. B scanner는 원단위 fact가 현재 order에 저장된 경우를 소비하고 order→fulfillment 조회를 만들지 않는다. A 사실이 뒤늦게 들어오는 경우에도 live `recordInitialDelivery` producer가 해당 snapshot으로 예약하므로 scan cursor 뒤로 삽입된 사실을 놓치지 않는다.

- orderId 기준 keyset50을 사용하고 한 페이지의 관련 unit schedule/Outbox와 다음cursor job을 한 거래로 commit한다. 페이지 수는 주문 수 기준이며 unit 수와 혼동하지 않는다. 최초 run의 상한 orderId 또는 명시 target 집합을 고정해 이행 범위를 식별한다. 이후 신규 주문은 live producer 책임이다.
- unit별 `due_at`과 최초 `scheduled_at=max(due_at,effective_at)`을 저장한다. 이미 지난 due는 즉시 claim 가능한 예약시각이지만 due 자체는 보존한다. 재시도 때 `max(due, now)`로 payload/시간을 바꾸지 않는다. 새로운 release 평가의 scheduledAt은 그 release 원인별 최초 평가시각으로 고정하고 원 dueAt은 유지한다.
- dedupe는 policy version·원단위·generation으로 고정한다. live producer와 backfill이 동일 대상을 만나면 저장 schedule을 잠가 기존 payload/시각/Outbox id를 재사용한다. job 유일키만 우연히 같고 새 event UUID를 계속 생성하는 구현은 금지한다. same-key 다른 저장 근거는409/명시 실패다.
- parent→정렬 unit→schedule→job/Outbox 순서를 유지한다. 페이지 중 실패는 다음cursor만 진행시키지 않고 전부 rollback한다. commit 후 ack 전 중단은 같은 페이지/원인 replay로 무효과이며 다음 페이지를 이어간다.51주문 fixture로 두 페이지와 마지막 단위를 검증한다.
- 최초 scanner가 SUCCEEDED라는 이유로 미래 live 배송을 놓치지 않게 한다. 운영 재스캔 설계에는 새 runId와 동일 unit schedule 중복 방지가 필요하지만 이번 검증에서는 새 격리 DB의 명시 호출만 수행한다. 기존 DB에 startup worker나 scanner를 실행하지 않는다.

### 10.4 사전 영향 집계와 실행 권한

**이 승인은 로컬 구현·새 격리 검사에서 선택2를 검증하는 승인이다. 기존 개발 DB·데모 DB·과거 검증 DB에는 migration·확정·job 재설정·worker 실행을 하지 않는다.** 기존 서버와 그 DB·미디어·실패 원문은 보존한다. migration을 포함하는 새 버전 앱을 기존 DB로 띄워 간접 적용하는 것도 금지한다. 검사 시작 전에 strict 새 `d2_it_<32hex>` 및 실제 연결대상/role을 확인하고 새 미디어 경로를 사용한다.

dry-run은 **새 격리 DB의 합성 V37 fixture**에서 고정 기준시각(asOf)을 정하고 변경 전 조회만 수행한다. 정책 적용 대상 주문 수/원단위 수와 실제 즉시확정 후보 수를 분리한다. evidence에는 다음을 남긴다.

- fixture DB명·asOf·decisionId·예정정책/값, 정렬된 원 order/item/ordinal/event/deliveredAt/dueAt 목록과 hash.
- 전체 대상 주문/단위 수 및 상태별 주문 수. 단위의 즉시후보(PAID+완료+OPEN+unheld+due≤asOf), 미래due, 미배송, HELD, CONFIRMED, REFUNDED, 비PAID 제외 수를 상호배타 우선순위로 분류하고 전체합과 대조한다. 원인별 보조 통계는 중복 가능성을 별도 표시한다.
- 권한·시각이 고정된 fixture에서 전체 후보 처리 후 CLOSED가 될 주문 수와 미배송/hold/다른seller로 PAID에 남을 수를 예상한다. 실제 job 완료 수·확정단위 수·CLOSED 전이 수와 각각 대조한다. 실시간 경합 fixture에서는 dry-run 시점과 worker 시점의 변경 원인도 기록한다.

이 문서에는 아직 실행한 dry-run이 없으므로 **영향 수량 NOT RUN**이다. 운영·개발 기존 DB의 실제 데이터 수나 처리 완료를 추정하지 않는다. 승인 판단을 다시 묻기 위한 단계가 아니라 선택2가 지정된 단위에만 적용되는지 검증하는 증거다.

### 10.5 B08 및 인접 검사 갱신

앞의 B08 승인 대기 조건은 해제한다. 검사 구현/실행은 여전히 NOT RUN이며 다음 assertion을 실제 `confirmationUsesPersistedPolicyVersion` 및 별도 이행·worker 검사에 연결한다.

| 검사 | 필수 assertion |
| --- | --- |
| 저장·이행 | 신규 P09-B-1/기존 P09-B-LEGACY-1, 두 값604800·ACTIVE·provenance/decisionId·최초 effective/captured 값 정확. PENDING 포함 모든 과거 상태의 snapshot 수/귀속 정확. policy만 추가하고 원 배송/quote/금액/이벤트/원 job 불변 |
| 시간·집계 | 원완료+7일의 -1µs/정확/+1µs, 과거due 즉시후보·미래due 대기, captured/effective를 더한 새7일이 아님. 실제 confirmed_at은 worker 처리시각, 원due/occurred_at 불변. dry-run 예상집합과 실제확정집합 정확히 일치 |
| 제외·경합 | PAID/배송완료/OPEN/unheld 조건 중 하나라도 불충족이면0. held 과거due→정상해제 새generation에서1회. 같은 단위 hold/직접/자동 경쟁, terminal부모 재개0, refund0, oldgeneration0 |
| 재개·후행사실 | V37→신규 migration→현재 제품 producer/worker를 같은 새DB에서 실행.51주문 keyset/다음cursor·페이지 중 실패/commit후ack전 child kill·재시작, live와scan 경합 schedule/confirmation/Outbox 중복0. scan 후 들어온 배송 fact도 live 예약1회 |
| 재시도·설정변경 | 과거 checkout exact retry는 이행 snapshot 그대로, migration/scan 재실행 snapshot·due·scheduledAt·receipt 불변. 정책 공급자 변경 뒤 기존두버전 불변·새주문만 새버전. 이미ACTIVE 다른내용을 덮거나 누락snapshot을 runtime fallback하지 않음 |
| 이력 보존·최종 상태 | 원 eventId/time/unit/net/기존 A job과 failure 원문 불변. CLOSED exact replay·조회, 성공 결제 재통지 보상0의 B09 회귀 포함 |

앞의 B05 LEGACY_UNSET 일반 안내는 승인 전 보호 상태 검사로 분리한다. 정상 이행된 legacy 화면은 원 배송 기반 dueAt과 현재 적격 상태를 보여주고 영구 자동확정 제외로 표시하지 않는다. 누락/모순 정책의 fail-closed 검사 자체는 유지한다. B01의 동일7일 경계, B04의 실제 worker/fencing, B07 출고/hold, B09 CLOSED 회귀와 함께 선택 없는 개발 전체·지정 검토세션 독립 전체가 필요하다.

**후속 판정:** H03 기존 적용 사업정책 게이트는 승인으로 닫혔다. 구현 담당은 선택2 이행을 포함한 B 작업을 추가 승인 질문 없이 진행할 수 있다. B 기능 완료·독립 검증을 통과한 것은 아니며 H05/H06의 C/E 담당 미결은 그대로다. 이번 보완도 이 문서만 수정했고 제품·DB·하네스·새 에이전트·커밋·배포·푸시는 실행하지 않았다.
