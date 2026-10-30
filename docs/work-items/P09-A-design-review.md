# P09-A A1 설계 검토 — 원단위 분할·배송 사실·모의 추적

2026-10-30. **정적 설계 검토이며 구현·실행 검증 통과 판정이 아니다.** 개발세션에서 인계한 P09-A 시작 요청에 따라 이 문서만 작성한다. 전체 Offline 실행은 이번 인계에서 명시적으로 제외했다. 제품·검사·migration·필수 목록·기존 기록은 수정하지 않고 커밋·배포·푸시도 하지 않는다.

기준 HEAD는 `ed96936740a6295affb30c5c6046ca3a3553513a`이며 P09 계획 문서는 미커밋이다. 검토 당시 제품 소스는 P08 기반이다. 현재 검토세션의 역할은 설계 검토이며 새 에이전트를 만들거나 기존 세션을 교체하지 않았다. 도구로 현재 부모의 실제 모델 식별자·추론 설정을 확인할 수 없으므로 이번 실행을 별도 지정한 Astra Medium 실행이라고 주장하지 않는다. 모델 라우팅의 실제 실행 조건은 개발세션이 확인해야 한다.

읽은 근거: README, 개발 흐름·검증 하네스·모델 라우팅, requirements §9/F09/F11, shop-design §7~10, [P09 공통 계약](P09.md), [A 계획](P09-A.md), [계획 검토](P09-plan-review.md), P08-C/F1/F2/G 인계, 실제 V31/V36와 아래 공개 포트·구현·회귀 assertion. 현재 저장소 및 계획에서 확인한 사실과 아래 제안 계약을 구분한다.

## 1. 결론과 구현 전 보완 조건

원단위 식별, BASIC 생성 사실과 현재 배정의 분리, fulfillment→order 방향의 배송 완료 사실 저장은 기존 설계 안에서 구체화할 수 있다. **단일 DB의 같은 Spring 거래에서 완료 사실을 저장하는 안을 권고한다.** 별도 배송 완료 relay와 eventual consistency는 현재 필요하지 않다. 실제 PG 차단은 유지하며 승인된 로컬·MOCK 배송만 다룬다.

다음은 제안대로 구현하기 전에 반드시 계약에 포함할 사항이다. 현재 P08 기능의 결함 판정이 아니라 분할·운송장·예외를 추가할 때 생기는 충돌이다.

| ID | 실제 코드 근거 | A 구현에서 필요한 보완 |
| --- | --- | --- |
| DR-A01 | `JdbcShipments.prepare`는 기존 BASIC의 items를 original 전체와 비교하고, `ship`도 requested=original=items를 요구한다 | 생성 당시 원단위 집합은 불변으로 보존하되 현재 배송은 부분집합을 허용. prepare 재호출로 복원하지 않으며 ship은 현재 배정 전부를 출고 |
| DR-A02 | `ship`은 IN_TRANSIT job에 expected=1, `progress`는 DELIVERED job에 expected=2를 고정 저장한다 | 사용자 변경 version과 물류 진행 세대를 분리. 분할·운송장 정정 후 진행, 예외·재개, 기존 v1 pending job의 호환 규칙 필수 |
| DR-A03 | `ShipmentStore.seller`는 배송 항목 수량과 원 주문 항목 수량의 정확한 동등성을 검사한다 | 부분수량·중복 원단위·하위주문 귀속을 확인하는 새 계약으로 변경. 정상 2+3 배송을 오류로 지우지 않아야 함 |
| DR-A04 | 현재 shipment에 완료시각 열이 없고 실제 완료 근거는 `shipment_event`의 applied DELIVERED 이벤트다 | shipment의 현재 문자열만 보고 완료 사실을 만들지 않음. 원 eventId·원단위·발생시각에 묶인 불변 사실 저장 및 기존 완료 이행 |
| DR-A05 | `MockShipmentJobs.scan`의 v1 START/cursor와 order별 작업은 영구 dedupe되고 완료 job은 다시 실행하지 않는다 | P09 이행을 새 세대·새 타입/키로 추가. 완료된 v1 job 초기화나 PAYMENT_SETTLED handler 이중 등록 금지 |

운송장 입력 제한, 부분집합 선택, 아래 예외 overlay와 원자 저장은 기존 MOCK 범위의 기술 구체화로 판단한다. 금액·7일 기간·기존 주문 권리·운영 권한을 바꾸지 않는다. 후속 정책을 기존 주문에 소급 적용하거나 배송 실패만으로 환불/주문취소를 허용하는 것은 이 문서의 승인 범위가 아니다.

## 2. H01 — 동일한 원단위와 분할 명령

### 식별과 DB 제약

- 원단위는 V31의 `(order_unit.item_id, ordinal)`이다. ordinal은 0부터 시작한다. UUID나 현재 SKU·가격으로 대체하거나 분할 때 새 ordinal을 만들지 않는다. 원 `gross/promotion_discount/coupon_discount/net`, order/item/seller 연결은 불변이다.
- fulfillment 소유의 현재 배정 테이블을 추가한다(예: `shipment_unit`). `(order_item_id,ordinal)`에 전역 UNIQUE/PK, `(order_item_id,ordinal)→order_unit(item_id,ordinal)` FK, `(shipment_id,order_item_id)→shipment_item(shipment_id,order_item_id)` FK를 둔다. V36의 shipment_item→shipment/원 항목 양쪽 seller_order 복합 FK 및 shipment→부모 UUID/번호 복합 FK를 유지한다. 이 연결 전체가 다른 매대·부모·항목 조합을 거부한다.
- FK만으로 quantity와 단위 수가 같아지는 것은 아니다. `shipment_item.quantity = 해당 연결 단위 COUNT`를 트랜잭션 종료 시 검사하는 deferred constraint trigger 등 실제 DB assertion 가능한 제약을 둔다. 이동 중의 임시 불일치는 허용하되 최종 불일치는 실패한다. 새 매핑을 연결한 뒤 수량0인 donor 항목행은 제거하며, 살아 있는 항목행의 quantity는 기존 1..99를 유지한다. 직접 INSERT/UPDATE로 수량을 부풀리는 경우도 거부해야 한다.
- 정상 A 거래에서는 하위주문 전체의 배정 집합이 원단위 집합과 정확히 같다. 유일 제약은 상한·중복을, 애플리케이션의 집합 비교와 거래 검사는 누락까지 확인한다. 현재 FK만으로 원수량 전체 배정이 보장된다고 주장하지 않는다. A에는 취소/보류 수량이 없으므로 이를 가정해 단위를 누락시키지 않는다.

### 최소 명령 계약

새 `split(sourceShipmentId, authority, {key,version,items:[{orderItemId,quantity}]})`를 Shipments 공개 포트와 자기 판매자 POST에 추가한다. 정확한 메서드/경로는 구현 시 A 문서에 기록한다. 기존 ship의 `{key,version,items}` 형식은 유지한다. 사용자에게 ordinal·sellerId·actor·source·가격·완료시각을 쓰기 입력으로 받지 않는다.

1. 대상은 현재 PAID 자기 하위주문의 PREPARING 배송 한 개다. 같은 배송에서 선택된 항목별 quantity를 **현재 배정된 ordinal 오름차순**으로 고정한다. UUID로 항목을 정렬해 canonical 요청을 만들고 중복 항목은 거부한다. 원수량5 중2를 옮기면 새 배송은 ordinal0,1, 원 배송은2,3,4다. 남은 배송에서 다시1을 옮기면 ordinal2다. 다른 배송에 있는 ordinal을 새로 고르지 않는다.
2. 분할은 비어 있지 않은 진부분집합 이동이다. 각 quantity는1..현재항목수량이며 전체 선택 후 원 배송에 최소1단위가 남아야 한다. 어떤 항목의 전량을 이동하는 것은 다른 항목이 남으면 가능하다. 전량 이동은 이 단계의 '분할'이 아니므로409로 거부한다. 합치기·기존 두 배송 사이 재배정은 이번 A에 추가하지 않는다. 수량5를2+3으로 나누는 요구는 충족한다.
3. 출처 배송과 새 배송의 항목/단위, source version 증가, split 명령 receipt, 양쪽 이벤트·감사 및 필요한 Outbox를 같은 거래에 저장한다. 새 목적키는 서버가 만든 `SPLIT:<불변 commandId>`이며 `(seller_order_id,purpose_key)` 고유 제약을 유지한다. 원 BASIC의 id/purpose_key/basic 이벤트를 바꾸지 않는다.
4. 요청키는 명령 종류와 출처 배송 범위로 구분한다. canonical hash에는 종류·sourceShipmentId·요청 version·정렬된 항목/수량을 포함하고 receipt에는 실제 선택 ordinal과 새 shipmentId/양쪽 결과를 저장한다. 현재 자격 검사를 통과한 뒤 **prior receipt를 새 version/현재 배정 선택보다 먼저** 확인한다. 동일키/내용은 최초 결과, 다른 내용409다. 이후 다른 분할이 이루어져도 같은 키가 남은 ordinal을 다시 선택하지 않는다.
5. 형식/숫자범위/중복항목 오류400, 현재 배정 초과·전량 이동·다른 상태·stale version·같은키 내용충돌409, 타인/없는 배송404, CSRF 정책은 기존403이다. 비회원·구매자는 판매자 분할/운송장 명령에 접근하지 못한다. 거부된 명령은 모든 업무행/이력/Outbox 변화0이다.
6. `ship`은 해당 배송의 현재 항목별 수량과 단위 집합 전체를 출고한다. 원 주문 전체와 같아야 한다는 조건은 제거하되 원수량/귀속/중복/정확한 현재 배정 검사는 유지한다. 발송 후에는 해당 배송의 원단위 연결을 변경하지 않는다. 두 판매자의 배송은 서로 섞이지 않는다.

원단위 금액 fixture는 예를 들어 net `[10001,10000,10000,10000,10000]`로 한다. 첫2개 이동의 집합/금액은 `{0,1}`/20001, 나머지는 `{2,3,4}`/30000이어야 한다. 합계만 같고 ordinal이 중복/교체되는 구현은 실패다.

## 3. BASIC 이행·prepare/backfill 재실행

새 migration으로 스키마와 기존 배송의 원단위 연결을 만든다. V31/V36을 수정하지 않는다. 실제 다음 migration 번호는 구현 착수 시 다시 확인한다.

- 이행 전 기존 BASIC마다 원 order/seller/items의 귀속과 원수량 동등성을 확인한다. 기존 PREPARING·SHIPPED·IN_TRANSIT·DELIVERED 모두 같은 원단위 전부를 BASIC에 연결한다. 과거 shipment id/state/version·shipment_item 수량·이벤트/감사/Outbox/job 값은 그대로 둔다. 기존 임의 비-BASIC 배송이나 모순된 수량/중복 배정이 있으면 임의 배분하지 말고 명시 실패·대상 식별 기록으로 남긴다.
- 원 기본 생성 사실을 별도로 고정한다(예: fulfillment 소유 preparation marker의 sellerOrderId, BASIC id, 원단위 canonical digest, 형식 버전). digest에는 부모/하위/원 항목/ordinal을 포함한다. 현재 배정 수량에서 매번 재작성하지 않는다. 기존 basic 이벤트가 사라지거나 marker의 BASIC 귀속이 달라지는 경우는 무조건 성공 처리하지 않는다.
- 새 `prepare`: order 잠금 후 marker가 없고 관련 배송도 없을 때에만 원 전체로 BASIC/marker/단위를 원자 생성한다. marker가 있으면 원 불변 집합과 현재 모든 배송의 배정 합집합을 비교하고, 중복·누락 없는 합법 분할이면 **쓰기0의 재시도 성공**이다. BASIC 하나의 items를 original과 비교하지 않는다. marker 유실/불일치를 원수량 재삽입으로 '수리'하지 않는다.
- 기존 PAYMENT_SETTLED/FULFILLMENT_PREPARE 및 backfill v1은 같은 prepare를 소비한다. 이미 성공한 scanner/job의 상태·payload·schedule·dedupeKey를 리셋하지 않는다. 분할 후 live 이벤트/prepare/scan 재호출은 추가배송0·수량복원0·새 이벤트0·409 반복0이어야 한다.
- 기존 완료 사실 이행이 배치로 필요하면 fulfillment 소유의 새 v2 scanner를 사용한다. UUID keyset와 batch의 enqueue+다음cursor는 같은 거래, delivery key는 원 eventId 기준이다. 이미 완료된 v1 scanner를 재사용하지 않는다. 페이지 경계에서 프로세스가 죽어도 중복 적용이 없고 누락된 완료를 재개할 수 있어야 한다. 새 완료는 아래 동기 저장이 책임지므로 scanner cursor보다 작은 UUID로 새 행이 생겨도 놓치지 않는다.

기존 자원에 이 migration을 시험하지 않는다. **새 격리 DB**를 V36까지 올려 P08의 각 상태·pending job fixture를 만든 뒤 새 migration/worker를 적용하는 검사가 필요하다. 과거 상태 전이 이벤트를 새 이벤트로 가장하거나 기본 배송을 다시 만들지 않는다.

## 4. H02 — 주문이 소비할 신뢰된 배송 완료 사실

### 단일 거래 포트

`order.api.FulfillmentOrders` 또는 같은 order.api의 별도 작은 포트에 `recordInitialDelivery(fact)`를 추가한다. 타입은 UUID·Instant·List·원단위 record 등 공개 값만 사용하고 fulfillment 내부 타입/JDBC/Spring transaction 타입을 노출하지 않는다. fact의 최소 계약은 schemaVersion, orderId, sellerOrderId, shipmentId, **applied DELIVERED eventId**, occurredAt(완료시각), 정렬된 원단위 집합이다. source는 서버 고정이며 외부 boolean/applied 입력을 허용하는 HTTP API를 만들지 않는다.

- fulfillment가 실제 상태 전이/event/audit를 저장하고, 그 **동일 REQUIRED 거래**에서 order 포트를 호출한다. order 구현은 order 소유의 완료 receipt/unit fact 테이블만 쓴다. fulfillment가 order 테이블을 직접 쓰거나 order가 Shipments/fulfillment 테이블을 역조회하지 않는다.
- order 포트는 caller transaction 필수, 부모 exclusive lock을 이미 잡은 흐름에서 실행한다. 같은 order 잠금의 재확인은 가능하지만 shipment 잠금 후 다른 order/account/store 잠금을 새로 얻지 않는다. 부모·seller·원 항목·ordinal 귀속, nonempty/duplicate-free 집합, 지원 버전을 검증한다. 호출자의 event가 실제 applied인지 증명하는 책임은 fulfillment에 있고, order는 브라우저가 조작할 수 없는 내부 포트라는 신뢰 경계를 사용한다. 이를 order의 역조회로 보완하지 않는다.
- 주문 소유 receipt의 eventId는 PK, canonical payload fingerprint를 저장한다. 동일 eventId/동일 전체 내용은 쓰기0, 같은 id/다른 shipment·unit·시각은409/명시 충돌이며 기존 사실을 덮어쓰지 않는다. unit fact는 `(item_id,ordinal)`의 원 **최초배송** 완료가1회만 가능하도록 고유 제약과 order_unit FK를 둔다. receipt·unit의 부모/seller 귀속도 복합 FK로 연결한다. 일부 unit만 성공한 채 완료 receipt가 남을 수 없어야 한다.
- E의 교환배송은 최초배송 사실을 UPDATE/삭제하거나 이 고유 제약을 우회해 저장하지 않는다. H05에서 별도 교환 회차/claim 근거와 B의 유효 완료 기준을 추가하는 것이 E 책임이다. A의 최초배송 테이블을 '모든 미래 배송은 하나'라는 계약으로 일반화하지 않는다.
- 새 완료의 order 저장 실패는 shipment state/event/audit 및 후속 Outbox까지 전부 rollback된다. worker handler가 커밋한 뒤 ack 전에 죽으면 동일 이벤트/완료 사실을 반환하고 효과1회다. 별도 relay를 두지 않으므로 신규 완료의 'shipment 완료 커밋, order 사실 유실' 창을 만들지 않는다.

### 기존 DELIVERED와 시간

기존 DELIVERED 행은 `applied=true AND next_state='DELIVERED'`인 원 최초 완료 이벤트와 그 당시 고정된 전체 원단위를 기준으로 이행한다. receipt의 eventId·shipmentId·occurredAt은 원문 그대로다. scanner는 먼저 order→shipment 순서로 잠그고 재검사한 뒤 같은 포트를 호출한다. 원 이벤트 없음/여러 applied 완료/수량 모순은 추측한 지금 시각으로 채우지 않고 이행 실패·검토 대상으로 남긴다. migrated 완료의 동일 eventId/내용 재호출은 무효과다.

완료시각은 trusted applied DELIVERED 이벤트의 `occurred_at`이며, `processed_at`·job ack·재시도·scanner 실행시각으로 교체하지 않는다. PostgreSQL timestamptz 정밀도로 저장한 값을 다시 읽어 payload/receipt를 고정한다. 나노초 입력을 한쪽에서만 잘라 재시도 hash가 달라지는 일을 막는다. 같은시각·시계후퇴에도 처리순서는 기존 `event_sequence`를 유지한다. 늦은 IN_TRANSIT나 DELAYED/FAILED 기록은 이미 저장된 완료시각을 변경하지 못한다. A에서7일을 계산하거나 시간이 의심스럽다는 이유로 과거 이벤트/시각을 임의 보정하지 않는다. B의 기간 snapshot·이상 시각 처리 정책은 별도 판단 대상이다.

현재 A의 상태는 PAID에 한정한다. B/C가 CLOSED/CANCELED를 도입할 때 이미 수락된 사실의 exact replay와 과거 이행이 PAID 제한에 막히는지 H06/B09/C11에서 반드시 다시 판단한다. A에서 주문 종결 상태를 앞당겨 추가하지 않는다.

## 5. 운송장과 예외 — 기존 모의 상태를 보존하는 모델

### 운송장

MOCK만 사용한다. 새 추적번호의 제안 형식은 ASCII `[A-Za-z0-9][A-Za-z0-9-]{0,63}`(1..64자)이며 공백·제어문자·URL은 거부한다. implicit trim/대문자 변환으로 원입력을 바꾸지 않는다. 실제 택배사 번호 규칙/전세계 고유성을 의미하지 않으며 provider·외부 링크는 사용자 입력으로 받지 않는다.

- 자기 PAID 배송의 PREPARING/SHIPPED/IN_TRANSIT에서 등록·정정 가능, DELIVERED에서는409. 이는 MOCK 오기 정정을 위한 제한적인 기록 변경이며 state·원단위·배송완료시각·이미 예약된 물류 단계를 바꾸지 않는다. 개인정보 주소/전화 변경이나 재배송 생성 기능과 합치지 않는다.
- `{key,version,tracking,reason}`의 엄격한 입력, reason은 공백만인 값을 거부하는1..200코드포인트. 기존 자격·CSRF·대기 후 재검사를 통과하고, 요청키/내용 일치 재시도는 최초 결과를 반환한다. 이전/이후 번호·사유·actor·source·event sequence·시각을 감사 이력에 보존한다. 같은 번호로 새 정정을 요청하면409(변경 없음), 기존 성공키 재전송은 성공 replay다.
- PREPARING의 번호는 아직 null일 수 있다. 기존 ship 요청의 호환성을 위해 번호가 없으면 출고 거래에서 안정적인 서버 생성 MOCK 번호(예: `MOCK-<shipment UUID의 32hex>`)를1회 부여하고 초기 등록 이력을 남긴다. 이미 발송/완료한 과거 배송의 번호를 migration이 지어내지 않는다. 기존 null은 '미등록'으로 읽고, DELIVERED 과거 번호를 소급 작성하지 않는다.
- View의 추가 필드와 실제 운송장 변경 이력 projection을 정의해야 한다. 지금 Event DTO는 content를 내보내지 않으므로 content 문자열만 바꾸고 변경 전후/사유가 조회된다고 주장해서는 안 된다. 구매자/판매자에게 보이는 최소 필드와 내부 감사 actor를 분리하고 기존 개인정보 최소화·no-store 계약을 유지한다.

### 예외 overlay와 정상 진행

기본 `state`는 PREPARING→SHIPPED→IN_TRANSIT→DELIVERED를 유지하고 별도 `exceptionStatus = NONE/DELAYED/FAILED`를 둔다. FAILED는 **배송 예외**이며 결제실패·취소·환불·완료가 아니다. 재개도 이전 배송 단계를 복원하는 행위이지 PREPARING으로 되돌리는 행위가 아니다.

| 현재 상태/예외 | 신뢰된 모의 입력 | 결과 |
| --- | --- | --- |
| SHIPPED 또는 IN_TRANSIT / NONE | DELAYED 또는 FAILED | 원 state 유지, 해당 예외·사유·이벤트 저장, 예정 자동 진행은 세대 차이로 무효화 |
| SHIPPED 또는 IN_TRANSIT / 예외 있음 | 새 예외 | 원 state 유지, 새 예외 이력. 동일키 충돌 규칙 적용 |
| SHIPPED 또는 IN_TRANSIT / 예외 있음 | RESUME | 예외 NONE, 원 state 유지, 바로 다음 정상단계 job을 새 세대키로1회 예약 |
| 예외 있음 | 이전 세대 정상 진행 job | 상태/완료 사실 변화0; 무시된 시도 이력은 applied=false, job은 성공 소비 |
| NONE | 현재 세대 다음 정상단계 | 허용된 한 단계만 적용. IN_TRANSIT를 건너뛴 DELIVERED 금지 |
| DELIVERED | 늦은 과거 진행/예외 | 완료시각/상태/단위 변화0; 무시 이력 보존 |
| PREPARING | 제공자 진행/예외/재개 | 거부, 업무 변경0 |

예외 주입·재개는 등록된 내부 MOCK 제공자/worker만 호출한다. 브라우저가 actor/source/state/completedAt을 지정할 수 있는 일반 API를 만들지 않는다. 테스트용 시나리오는 test profile fixture 또는 명시적 내부 모의 어댑터를 사용하고 제품 JAR/일반 HTTP 접근 경계를 검사한다. A의 일반 판매자 명령은 분할·번호 정정·발송에 한정한다. 운영자에게 임의 완료/환불 권한을 새로 주지 않는다.

### version과 이미 저장된 job의 호환

사용자 optimistic `shipment.version`은 성공한 분할/번호/예외/재개/물류 변화마다 증가한다. 별도 `progressEpoch`(예외/재개 시 세대)와 물류 단계 조건을 써서 worker가 total version의1/2에 의존하지 않게 한다. 단순 번호 정정은 물류 세대를 바꾸지 않는다. split 후 source.version이 올라가도 발송→진행이 가능해야 한다.

새 진행 payload는 버전을 명시하고 shipmentId·epoch·expectedStage·target·불변 occurredAt을 포함한다. dedupeKey는 배송/epoch/목표단계로 안정적이어야 한다. 재시도에서 `now()`로 schedule/key를 바꾸지 않는다. 예외 뒤 RESUME는 epoch를 올려 현재 SHIPPED면 IN_TRANSIT, 현재 IN_TRANSIT면 DELIVERED를 기존 모의 간격5초로 새 예약한다. 구세대 job은 신세대를 움직이지 않는다. 같은 키/다른 payload는 충돌이다.

기존 v1 `id|target|1/2` payload, 기존 key와 receipt는 그대로 읽는다. 새 parser는 v1을 legacy epoch0의 예상 물류단계로 해석하고, 기존 이벤트의 content/hash·최초 Result를 바꾸지 않는다. 단순 운송장 정정 이후에도 pending v1 job이 total version 불일치로 영구409가 되지 않아야 한다. 예외/재개를 거친 v1 job은 구세대로 무시한다. 같은 타입 handler를 두 개 등록하지 않으며 완료된 job payload를 rewrite/reset하지 않는다. v1 정상/재시도·v2 정상·v1에서 v2 재개를 각각 검증한다.

## 6. 트랜잭션·잠금 표

| 경로 | 획득/재검사 순서 | 같은 거래에 포함할 내용 |
| --- | --- | --- |
| 판매자 split/ship/tracking | 잠금 없는 header는 라우팅 힌트만 → 기존 current account/session/store 자격 포트 → `writePaidSellerOrder`의 order exclusive → 관련 shipment UUID 정렬 → 현재 자격·시각 재검사 → prior receipt → 신규 명령 검사 | 단위 이동/수량·version/번호·receipt·이벤트·감사·Outbox |
| prepare/live/backfill | `FulfillmentOrders.lockPaid` order exclusive → 해당 shipment 정렬 → marker/배정 검증 | 새 BASIC·원단위·marker·이벤트·Outbox 또는 쓰기0 |
| 정상 progress/예외/resume | order exclusive → shipment → phase/epoch/prior 검사 | 상태·예외·version·event/audit·후속job; 완료이면 order delivery receipt/단위 facts까지 |
| 기존 완료 사실 이행 | batch scanner enqueue 거래와 단일 배송 적용 거래 분리; 적용은 order exclusive → shipment | 원 이벤트/원단위 재확인 → order의 불변 완료 사실 또는 exact replay |

현재 `JdbcShipments.ship`처럼 shipment 잠금 뒤 `orders.writePaidSellerOrder`를 다시 호출해 자연만료를 재검사하되, 앞에서 이미 획득한 동일 account/store/order만 확인해야 한다. 새 다른 자원 잠금을 shipment 뒤에 추가하지 않는다. worker에는 판매자 account/store 조회가 필요 없으며 이를 추가하면 seller account→order와 교착 가능성이 생긴다. 외부 제공자 호출·네트워크는 거래 안에 넣지 않는다.

한 부모 order lock이 같은 주문의 두 판매자 분할/진행까지 직렬화하는 것은 현재 최소안이다. 이를 성능 목적으로 shipment-only 잠금으로 약화하지 않는다. B의 실제 보류 포트는 order 아래 원단위 순서로 연계해야 하며 A에는 always-unheld 가짜 포트를 넣지 않는다. C의 취소와 출고 상호배제는 B/C에서 실제 연결한 뒤에만 주장한다.

## 7. H03 담당·적용시점 — A에서 사업정책을 앞당기지 않음

`purchase_order.policy_version=P07-C-1`은 견적 snapshot 버전이며 후속 기간/비용 정책이 아니다. A가 이 값을 바꾸거나7일/3,000원을 기존 주문에 저장해 확정하지 않는다. A의 완료 fact schemaVersion도 사업정책 버전으로 재사용하지 않는다.

| 시점/담당 | 책임과 허용 범위 |
| --- | --- |
| A1/A 종료 | 원단위·원 완료 eventId/시각·기존 BASIC 이행 계약을 보존하여 B/C/E가 같은 근거를 소비하게 함. 정책 저장은 order 소유의 별도 snapshot이며 배송 테이블에 분산하지 않는 방향을 인계 |
| B1 | 기간/확정 정책의 불변 snapshot·설정버전과 신규 주문에서 고정하는 시점(원칙적으로 주문 생성 거래)을 구체화. 이미 존재하는 P08/P09-A 주문은 별도 LEGACY/미설정으로 식별하고 승인된 이행 전 자동7일 적용 금지. 기존 적용값/기준시각/당장 확정되는 과거 주문의 처리를 명시적으로 판단 |
| C1 | 회수비·환불/추가비용 정책 snapshot, 분할 공제/누적 공제와 기존 주문 적용 계획. 기간 snapshot을 덮어쓰지 않음 |
| E1 | 동일가격/교환예약 수명·새 배송 완료 후 유효 기한과 H05의 새 이행. 최초배송 receipt를 교환시각으로 덮어쓰지 않음 |

신규 snapshot은 schemaVersion만 있고 값이 빠진 상태를 '정책 확정'으로 취급하지 않는다. A에서 정책 값을 미리 만들지 않는 대신 **B1 이전 기간 동작 미구현**을 명확히 인계한다. H03 전체 해소가 아니라 A가 맡은 담당·적용 경계 확정이다.

별도 사용자 판단이 필요한 경우는 구체적이다: 기존 주문에 새7일/비용을 소급해 즉시 권리가 만료되거나 자동확정되는 선택, 기간 기산점을 원 발생시각 대신 migration 실행시각으로 재설정하는 선택, FAILED를 자동 환불/클레임 종료로 해석하는 선택. requirements의 설정버전 저장·기존 거래 불변과 사용자 승인 정책에 영향을 주므로 이 문서에서 임의 승인하지 않는다. 이 선택들은 B/C/E의 선행 게이트로 남으며, A의 로컬 분할·추적 구현을 위해 지금 사용자에게 결정을 요구할 필요는 없다.

## 8. 구현자가 추가해야 할 assertion과 기존 회귀

아래는 **예정 검사/기대 결과이며 전부 NOT RUN**이다. 메서드가 이미 존재하거나 통과했다고 표시하지 않는다. 기존 메서드명은 실제 소스에서 확인했다. 신규 검사명은 구현자가 실제 수집명으로 연결해야 한다.

| 연결 | 검사와 fixture | 반드시 확인할 결과 |
| --- | --- | --- |
| A01/A02/A08 | `splitQuantitiesStayWithinOriginal`, `splitShipmentsPreserveScopeAndQuantities`, `splitUnitsPreserveLegacyBasicAndReplay` | 5→2+3의 ordinal/금액 정확;0/음수/초과/전량이동 거부; 직접 없는ordinal/타매대/타부모/중복unit/quantity-count 불일치 INSERT·UPDATE 거부. 두 실제 connection이 같은 version을 잡으면 한 승자; 원단위·금액 불변 |
| A02/A05 | 새 split receipt replay/rollback 사례 | 같은 키 응답 유실 후 추가 split을 거쳐도 원 최초 새배송/ordinal 반환; 같은키 다른내용409; 단위 이동 뒤/양쪽 event·audit/Outbox 직전 실패마다 전체 snapshot 불변; 원 배송에만 수량이 빠지는 결과0 |
| A08 | V36→신규 migration fixture | PREPARING/SHIPPED/IN_TRANSIT/DELIVERED 기존 행·event·audit·job의 id/content/state/version/time 해시 불변; 연결만 추가; 원 이벤트 없는 완료/모순 수량은 명시 실패. 2+3 이후 prepare/live/backfill 반복의 추가행0·복원0·409루프0 |
| A03 | `shipmentTrackingEventsNeverRegress` + 새 JUnit 상태표 | 추적번호 길이0/1/64/65·잘못된 문자·사유경계, 정정 전후/actor·권한·이력; SHIPPED/IN_TRANSIT의 DELAYED/FAILED→RESUME에서 원단계 유지, 완료fact0→정상완료 시1, DELIVERED 뒤 예외는 영향0 |
| A03/A05 | 새 progress compatibility fixture | split source.version>0에서 정상발송·완료; v1 pending 상태에서 번호정정 후 진행; v2 예외/재개와 stale v1/v2 race; 원 schedule·dedupe 불변, 중복job/역순·같은시각·clock 후퇴에 처리순서 유지·완료시각 재작성0 |
| A04 | `splitShipmentCommandsRecheckAuthorityAfterWait` | split/tracking/ship 각각 타판매자404·CSRF403·허용된 EXISTING_TRADES; 실제 account/order/shipment 잠금 대기 후 세션 natural expiry/reset/version/역할변경 거부·변화0. 동일키 재시도에도 현재 자격 확인 |
| A05/A09 | `splitShipmentRollbackAndWorkerRecovery`, `shipmentCompletionFactsReplayWithoutCycles` | 실제 delivery handler의 order 저장 실패 시 state/event/audit/fact/Outbox 전부rollback. handler commit 후 ack 전 **소유한 child JVM** 종료·lease 만료·재기동 후 event/원단위 fact1회·시각불변. generic worker_probe_effect만으로 배송 복구를 대신하지 않음 |
| A09 | 기존 완료 scanner + live 경합 | 같은 eventId/내용 재개는 무효과, 다른 unit/time/shipment 충돌 거부; pagination 다음cursor commit 전/후 child 중단에서 누락0·중복0. event없는 DELIVERED를 guessed now로 채우지 않음 |
| A06 | `ShipmentStore.test.tsx`, `shipments-split.spec.ts` | 원수량5의2/3 부분 GET 수용·실제 API/DB 단위대조; 잘못된 합계/중복단위 DTO 거부; PC/mobile 기존 기본배송/번호미등록 표시; same-body 재시도,409 후 GET 실패, 인증변경·늦은응답에서 개인정보 폐기·자동POST0 |
| A07 | 기존 `ModuleArchitectureTest#productionClassesRespectModuleBoundaries`, `ModuleRulesTest#cyclesThroughPublicApisAreRejected`, `publicApiCannotExposeOwnInternalType`와 역방향 fixture | fulfillment→order 공개포트만 허용; order→fulfillment/claim fixture는 실패; API에 JDBC/내부 타입 없음. 거래/업무코드를 platform으로 옮겨 우회하지 않음 |

기존 `basicShipmentRequiresPaidSellerOrder`, `shipmentWriteRechecksSellerAuthority`, `shipmentEventsAreIdempotent`, `shipmentExpiryRecheckedAfterOrderAndShipmentWaits`, `shipmentAtomicRollbackAndCancellationRace`, `shipmentConcurrentPurposeAndVersionHaveOneEffect`, `shipmentHistoryUsesCausalOrder`, `shipmentOutboxAndWorkerRecovery`, `shipmentDatabaseItemScopeAndMalformedInput`, `shipmentBackfillResumesAndSharesLivePurpose`, `shipmentDatabaseParentOwnershipIsLinked`를 유지한다. 순수 BASIC v1의 기존 state/version·이벤트 replay assertion을 새 기능 때문에 무조건 완화하지 않는다. 불가피한 DTO 추가/새 등록 이벤트의 기대값 변경은 이유와 동일한 보장 assertion을 남긴다.

배송 view가 원단위를 공개한다면 검증 가능한 unit 배열을 명시적으로 추가한다. 그렇지 않으면 UI에서는 수량/귀속 상한을 검증하고 unit 중복 검증은 DB/API 검사로 분리하여 'UI가 원단위까지 검증했다'고 과장하지 않는다. `.test.tsx` 실제 수집과 기존 필수 목록 보존, P08 회원/GAS 조회·새브라우저 인증 회귀가 필요하다.

## 9. 영향 파일과 마감 조건

예상 영향은 `fulfillment/api/Shipments.java`, `domain/ShipmentRules.java`, `infrastructure/JdbcShipments.java`, `MockShipmentJobs.java`, `web/ShipmentController.java`, order.api의 배송 사실 포트 및 order 소유 구현/새 migration, `frontend/src/fulfillment/ShipmentStore.ts`와 조회/최소 조작 화면이다. 테스트는 기존 ShipmentRulesTest/PostgresJdbcIT/ShipmentWorkerProbe 및 위 프런트 수집 경로에 추가한다. generic WorkerProcessProbe나 platform WorkerEngine을 업무 구현으로 바꿀 이유는 없다.

개발자는 채택한 API/DB명·migration·v1/v2 payload·DTO·완료 fact 시각 규칙을 [A 작업 문서](P09-A.md)에 확정하고, A01~09의 실제 assertion을 매핑한 뒤 구현한다. H01/H02는 A의 필수 구현·이행 검사, H03은 담당/적용시점 인계 완료와 후속 미결 정책의 구분이 필요하다. A 완료에는 **동일 동결 소스의 새 개발 전체와 지정 검토세션의 새 독립 전체**가 여전히 필요하다.

이번 산출물은 이 설계 검토 문서1개다. JUnit·ArchUnit·새 PostgreSQL·프런트·브라우저·전체 Offline·실자원 보존 비교를 이번에 실행하지 않았다. 기존 P08/P09 계획 검토의 통과 기록을 A 신규 제약의 증거로 재사용하지 않는다. 기존 실패 기록·DB·서버·미디어는 읽기/실행 대상으로 사용하지 않았다. 미실행은 인계가 요청한 정적 A1 범위 때문이며 캐시/도구 부족으로 확인된 차단이 아니다. 구현 독립 검토·A 완료·B 착수 승인은 이 문서로 대체되지 않는다.
