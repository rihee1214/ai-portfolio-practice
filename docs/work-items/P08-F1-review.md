# P08-F1 독립 사전 검토 — 2026-10-28

상태: **사전 정적 검토 / 구현·집중·전체·독립 실행 미검증**. F DEMO 독립 완료 후 F1만 검토한다. E 실제 SANDBOX 차단은 유지한다. 제품 소스 변경·하네스·F2·P09·커밋·배포·푸시를 수행하지 않는다.

## R-F101 실제 PAID producer와 단일 consumer

현재 PaymentCoordinator는 성공(settled), 실패(failed), 보상완료(compensated)에 모두 PAYMENT_SETTLED를 발행한다. PaymentJobs.paymentSettled는 현재 같은 type을 no-op COMPLETE로 소비한다. WorkerEngine은 동일 type handler 중복을 DUPLICATE_OR_INVALID_JOB_HANDLER로 거부한다. 새 handler를 단순 추가하지 말고 기존 소비 책임을 명시적으로 이전하거나 별도 fanout 계약을 정의해야 한다. payment/order가 fulfillment를 역호출하지 않는다.

필수 assertion: 실제 D approve→원 outbox/job→B worker→현재 PAID 하위주문별 기본배송1. FAILED/UNKNOWN/보상완료는0, 같은 이벤트 재전달은1. 직접 prepare 호출만으로 producer 통합을 증명하지 않는다. 이미 COMPLETE 처리된 과거 settled를 자동으로 다시 소비할 수 없으므로 과거 PAID의 bootstrap/backfill 또는 제외 정책을 명시하고 기존 job/DB를 임의 수정하지 않는다.

## R-F102 공개 paidSellerOrder는 조회용 계약

F paidSellerOrder는 seller 현재 자격을 요구하고 order FOR SHARE를 얻지만 자체 transaction이 끝나면 lock은 풀린다. worker는 판매자 HTTP session이 없으므로 이를 가짜 authority로 호출하면 안 된다. 시스템용 최소 order 공개 projection과 판매자 명령용 현재 권한을 분리한다. fulfillment outer transaction 안에서 order 잠금을 유지하여 현재 PAID·출고가능 항목/수량 확인→Shipment/Item/Event/Outbox 기록을 원자화한다. shipment부터 잡고 account/store/order로 역행하면 D/C/F 기존 순서와 교착 가능성이 생긴다. source order 확인 전의 shipment 비잠금 lookup으로 부모ID를 찾은 후 account→store→order→shipment처럼 하나의 순서를 정한다.

필수 assertion: PENDING/FAILED/UNKNOWN0, PAID 준비와 취소/보상 경합에서 실제 order lock wait 및 양쪽 winner 결과, 결제 상태를 읽은 뒤 잠금 없이 출고하는 창0. stale PAID 이벤트가 도착해도 현재 취소면 준비/출고0. 이미 준비된 배송의 ship 명령도 현재 PAID를 다시 확인한다. D의 늦은 성공 보상은 PAID 정상취소와 다른 경로이므로 실제 가능한 상태 경합을 명시한다.

## R-F103 현재 권한·자연 만료·최소 필드

판매자 쓰기는 현재 소유권/역할/state/EXISTING_TRADES와 CSRF를 검증한다. 비공개 매대의 기존거래와 정지 계정은 다르다. account/session 선점이 자연 시간 경과를 막지 못하므로 order/shipment 대기 뒤 session expiry를 현재 clock으로 재검증한다. 동일키 성공 재요청도 현재 자격부터 확인해야 한다.

필수 assertion: 타판매자404 또는 기존 일관된 거부·이벤트0, 정지/역할폐기 실제 대기 후 거부, order/shipment 대기 후 정확한 session expiry 거부, CSRF403, 동일 성공키의 권한폐기 후 거부. POST ship 입력의 임의 status/DELIVERED/source/actor/수량 조작으로 내부 모의권한을 얻지 못함. 공개 delivered endpoint가 없고 운영 권한으로도 뜻밖의 우회 경로를 만들지 않는다.

## R-F104 목적별 기본 배송과 수량

sellerOrderId 전체 UNIQUE로 미래 분할을 영구 차단하지 않는다. 기본 생성 dedupe만 sellerOrderId+목적이며 ShipmentItem은 원 order item과 수량을 연결한다. 같은 기본 키의 다른 item/quantity 또는 다른 부모/매대 재사용은409이고 원 결과가 유지돼야 한다. item/quantity 합산은 checked 범위에서 처리하며 다른 매대 항목/중복항목/0·음수/상한초과를 거부한다.

필수 assertion: 동시 기본생성1, 목적키의 정확한 범위, 모든 항목과 수량 대조, 잘못된 매대/FK·수량에서 header/items/events/jobs 전부 rollback. 미래 분할 자체는 이번 구현 대상이 아니다.

## R-F105 상태와 이벤트 내용의 불변 재처리

PREPARING→SHIPPED는 판매자, 이후 IN_TRANSIT/DELIVERED는 인증된 내부 worker 경로로 제한한다. 이벤트 ID/source/actor/occurredAt/처리시각/전후state/version을 서버가 적절히 구분한다. 외부 발생시각만으로 처리 순서를 정하지 않으며 같은 시각이나 역순 전달에서 버전/상태 전이가 후퇴하지 않는다. 재시도 때 새 서버 now를 request hash에 넣어 동일 이벤트를 충돌로 만들지 않는다.

필수 assertion: 동일키 동일내용 최초결과, 동일키 다른 target/내용409, 동시 version경합 한승자, 중복/역순 상태후퇴0·수량재처리0, 전후version·처리자·출처·원시각 정확. 무시한 이벤트와 이미 적용한 이벤트의 반환 계약을 구분한다.

## R-F106 실제 process kill과 atomic rollback

B는 at-least-once 전달이며 lease/ack fencing은 업무효과의 중복을 막지 않는다. 실제 child worker가 배송효과 commit 후 ack 전에 종료되어 새 owner로 재실행돼도 배송/항목/이벤트/후속job이1회여야 한다. 생성 또는 발송 transaction의 이벤트/감사/outbox 실패는 전체 rollback되어야 한다.

필수 assertion: 실제 프로세스 PID·checkpoint·kill·새 process 완료 증거, attempts 증가 및 최종 job 상태, 원 producer payload 보존, 배송/항목/event/outbox 수·내용 대조. handler 직접2회호출이나 sleep만은 실제 재시작 증거가 아니다. 0원 atomic paid와 기존 D kill 검사도 전체 하네스에서 유지한다.

## 사전 판정

현재 구현 전 계약 확인이며 통과 판정이 아니다. 개발 전체 통과 후 별도 새 경로 독립 전체와 전후 source/failure 보존 검사가 필요하다.

## 진행 중 초안 정적 검토

소스가 변경 중인 focusedrun3 시점의 검토이며 실행 검증이 아니다. PAYMENT_SETTLED의 no-op 소유권 이전, 별도 writePaidSellerOrder의 outer transaction 요구/order FOR UPDATE, 내부 lockPaid 공개 포트, worker.enabled에서 ApplicationRunner가 새 FULFILLMENT_BACKFILL START job을 등록하는 실제 런타임 경로를 확인했다. backfill 페이지/자식 enqueue는 한 transaction이고 실시간/복구 prepare는 sellerOrderId+BASIC를 공유한다. progress의 occurred 시각은 JdbcJobs가 immutable scheduled_at을 command.nextRunAt으로 복원하므로 retry용 next_run_at 변화와 혼동하지 않는다.

### R-F107 이벤트 표시의 인과 순서

JdbcShipments.view는 shipment_event를 ORDER BY processed_at,id로 반환한다. fixedClock/같은 microsecond에서는 event UUID 무작위순으로 서로 다른 적용 version의 전후 상태가 뒤섞이고, clock rollback에서도 순서가 역전된다. 상태 쓰기 멱등성이 보장되어도 F2 이력 표시는 잘못된다. 적용 version과 안정된 처리 순번 등으로 ignored 이벤트까지 모호하지 않게 순서를 정하고, 동일시각/시계역행+중복·무시 이벤트의 전후/version 순서 assertion을 추가해야 한다.

shipment header/항목의 실제 FK·수량 검증, 주문취소 양방향 lock 경합, order 및 shipment 대기 후 session 자연만료, 실제 commit후ack전 childkill/restart, 이벤트/감사/Outbox rollback, 50개 경계를 넘는 backfill 재시작은 구현자 추가 예정이며 아직 통과 판정하지 않았다.

## 최종 backend 집중 검증 전 재검토

R-F107의 event_sequence identity와 조회정렬, fixed/역행 시각에 ignored event를 포함한 순서 assertion을 확인했다. ShipmentItem의 (shipment_id,seller_order_id) 및 (order_item_id,seller_order_id) composite FK는 다른 매대 항목 혼입의 실제 RED를 겨냥한다. 실제 D producer job commit후ack전 child kill/restart, 불변 command/attempts2/배송1효과, startup 등록 backfill 50개 경계·중간 enqueue rollback·batch commit후kill·live와 같은목적 경합, 기존 완료 job 불변 assertions를 읽었다. order/shipment 실제 lock wait 뒤 정확한 session 만료 거부, 준비/발송 checked/runtime 실패 rollback, 취소 선행/준비 선행 및 D 보상 미출고 검사를 확인했다. 본 검토자는 아직 실행하지 않았다.

### R-F108 header의 원주문·하위주문 결합

현 V36에서 shipment.seller_order_id와 order_number는 각각 독립 FK다. 따라서 다른 구매주문 A의 order_number와 B의 seller_order_id를 한 header에 결합할 수 있으며 item composite FK도 이를 막지 않는다. buyer 조회는 order_number, seller 조회는 seller_order_id를 기준으로 하므로 잘못 저장된 header가 양쪽에 상이한 거래 귀속을 제공한다. 현재 prepare 정상경로는 올바른 값을 쓰므로 공개 HTTP 공격으로 주장하지 않는다. 원주문/매대 귀속의 DB 검사에 이 조합 거부를 추가하고 composite parent identity로 묶거나 의도된 신뢰경계를 명시할 필요가 있다.

## R-F108 보완 정적 확인

FulfillmentOrders.Paid(orderId,sellers)는 order lock 후 같은 저장 주문에서 만들며 prepare는 그 orderId를 저장한다. V36의 (order_id,order_number)→purchase_order 및 (seller_order_id,order_id)→seller_order와 item 두 composite FK로 parent→seller→item 귀속이 연결된다. seller 공개 DTO는 변경하지 않았고 progress는 nonPAID null을 거부한다. shipmentDatabaseParentOwnershipIsLinked의 A parent+B seller 조합 거부·전체불변 assertion을 확인했다. 같은 검사에서 A parent+A seller+B number 조합도 거부하면 새 두 FK를 각각 직접 검증할 수 있다는 추가 보완을 전달했다.

새 guest shipment 조회는 정상 GAS200, 다른 NEW그룹404, idle/max/version만료 및 실제 reset 두 세션404 assertion이 추가됐다. 현재 정적 검토에서 추가 코드 차단은 없으며 집중/개발전체/독립전체 결과는 별도 확인이 필요하다.

## 최종 독립 전체 검토 — 2026-10-28

루트 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-f1-review1-20261028`을 새 경로에서 직접 실행했다. MAVEN_ARGS 제거, 검사 선택 없음. 19:50:24~19:58:20 KST, 외부 프로세스 종료0, status/preservation passed. Java441(단위183+격리DB258), browser90, frontend187, Node73; 필수48묶음 passed, 누락/실패/오류/skip0, typecheck/lint/build 통과. F 최종 Java 필수370→382의 기존 이름 누락0을 확인했다. 새12개는 단위1+DB11이며 기존 브라우저90/프런트187 검사도 유지된다.

| 제약·검토 항목 | 독립 실행 assertion | 판정 |
|---|---|---|
| F101/R-F101·102 | basicShipmentRequiresPaidSellerOrder: 실제 D 결제 outbox→B worker→각 PAID SellerOrder 기본1, 실패·UNKNOWN0, 원 항목/수량 대조. 기본/live/backfill 동일목적 중복0 | 통과 |
| F101/R-F104·108 | shipmentDatabaseItemScopeAndMalformedInput, shipmentDatabaseParentOwnershipIsLinked: 다른 SellerOrder item FK거부, parent UUID↔번호 불일치 및 parent↔seller 불일치 각각 거부·전체불변, 다른 목적 모델 허용 rollback, 수량오염 재처리409 | 통과 |
| F102/R-F103 | shipmentWriteRechecksSellerAuthority, shipmentExpiryRecheckedAfterOrderAndShipmentWaits: 현재 소유자/정지/역할·CSRF 거부, 실제 account/order/shipment 대기와 정확한 session expiry 거부, 공개 status/source/actor/DELIVERED 조작 거부 | 통과 |
| F103/R-F105·107 | ShipmentRulesTest, shipmentEventsAreIdempotent, shipmentConcurrentPurposeAndVersionHaveOneEffect, shipmentHistoryUsesCausalOrder: 동일키 최초결과·다른내용409, 동시 version한승자, 역순후퇴0, server actor/source·원시각·audit 정확, fixed/역행시각도 event_sequence 인과순서 | 통과 |
| F104/R-F106 | shipmentAtomicRollbackAndCancellationRace: ITEMS/STATE/EVENT/AUDIT/OUTBOX checked/runtime 실패 전체rollback, 취소 선행·준비 선행 양 lock 경합, 기존 D 보상 미출고 | 통과 |
| F104 실제 재시작 | shipmentOutboxAndWorkerRecovery: 실제 원 settled job child commit후ack전 kill, lease후 새 process attempts2·command불변·배송/이벤트1효과·기존job불변 | 통과 |
| 과거 PAID 복구 | shipmentBackfillResumesAndSharesLivePurpose: 실제 ApplicationRunner 등록, 50개 경계/51개 추가 과거주문, 중간enqueue실패 원자rollback, batch commit후kill/restart, live동시 동일purpose1, 이전 SUCCEEDEDjob불변 | 통과 |
| F 조회 회귀 | realGuestOrdersUseIndependentAccessSessions 등의 실제 GAS 조회에 shipment 정상/다른그룹·만료·reset두세션 거부 추가, 기존 F/D/B 전체검사 유지 | 통과 |

R-F101~108의 구체 지적은 구현과 위 assertions로 해소됐다. second header FK assertion도 최종 소스에서 직접 확인했다. 배송 공개 DTO는 판매자 귀속을 유지하며 내부 시스템 권한을 클라이언트 인자로 받지 않는다. seller 명령은 outer transaction에서 account/store→order→shipment 순서와 대기 후 현재 세션 재검증을 유지한다. worker는 order→shipment 순서로 처리한다.

새 DB `d2_it_384c76e7a1e942bfb48f1b0cf45bf18d`, 미디어 `.local-tools/p08-f1-review1-20261028/db/media`, 전화 emulator `.local-tools/phone-emulator-e7d66746679347e1a0b1f5e900bdbc83`를 보존했다. 실제 child PID/log/checkpoint/완료파일은 review1/db의 shipment-worker 및 shipment-backfill-worker 고유 폴더에 보존한다. 주요 result/inputs/coverage/전후/DB/프런트 사본은 `evidence/P08-F1-review-final-*`다. 기존 서버·DB·미디어·실패 로그를 삭제/중지하지 않았다.

전후 감사 완료: HEAD `460aa8bb0bb22f5138849eb00c050abd18f6fb53`(미커밋 변경 포함). 입력463파일 SHA256 `F077524124595397AB87CEA04E19F1BCA7F1C3D11E877679382292C0F66B08EB`로 개발dev1/독립review1 manifest가 같고 현재 소스 전후불일치0. 최신 prefull baseline의 실패 manifest28개/중복제거54949파일, manifest 자체SHA와 원본파일 존재·SHA도 전후불일치0이다. 새 F1 실패5518파일 manifest SHA `3B90C1B12ED63F6B5AC640F7B4394F11DE13FFE5CCA40464D87BA6B9FD7FFE03` 포함. 초기 prior-only27개 확인은 별도 prior-only-before에 남겼으며 최종 전후 판정은 F1 실패를 포함한28개 기준이다. 감사 원본 `.local-tools/p08-f1-review1-audit-20261028/{before,after}.json` 및 사본을 보존했다. child 증거 목록/해시는 P08-F1-review-final-process-evidence.json에 기록했다.

**최종 판정: F1 기본 모의 배송 범위 독립 검증 통과, 필수 잔여 차단 없음.** 실제 택배사·분할·클레임·F2 화면은 미구현/범위외이며 E 실제 SANDBOX 및 카드 webhook 제품 계약은 계속 차단·미검증이다. 완료를 그 범위로 확대하지 않았다. F2·P09·커밋·배포·푸시는 진행하지 않았다.
