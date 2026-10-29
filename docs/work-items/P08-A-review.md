# P08-A 독립 검토

2026-10-27. 구현자와 분리된 검토자가 A 계약과 동결 구현을 읽었다. 기준 HEAD는 `460aa8bb0bb22f5138849eb00c050abd18f6fb53` 위 미커밋 변경이다. 개발 전체 `.local-tools/p08-a-dev1-20261027` 실행 중 작성한 정적 검토이며 **독립 전체 하네스는 미실행, A 완료 판정 보류**다. 제품·검사·하네스를 수정하거나 실행하지 않고 이 문서만 작성했다.

## 검토 범위와 제약 연결

README, 개발 작업 흐름, 검증 하네스, P08 전체/A/진행 기록, 실제 Quotes/CouponReservations/Reservations, guest 연결과 P06-E/P07-D 인계를 대조했다. Orders, OrderRules, JdbcOrders, V31, OrderRulesTest 및 PostgresJdbcIT 주문 검사7개와 필수 목록을 읽었다. 아래는 assertion의 정적 적합성 판단이며 실행 통과를 뜻하지 않는다.

| 제약 | 현재 검사와 기대 결과 | 정적 판정 |
| --- | --- | --- |
| A01 소유자·키·모드 | orderOwnerAndAttemptConstraints, orderGuestAttemptBindingSurvivesCheckoutRestart, checkoutAttemptsAndOrderNumbersAreDistinct: 양쪽 null/동시소유 거부, 세션+키 고유, guest attempt 한주문, 모드 allowlist | 구조와 검사 연결 확인; 동시 저장은 R-A08 |
| A02 금액·보존 | snapshotAmountsConserve, snapshotStructureAndUnitAmountsAreValidated: 단위 항등식/개수/ordinal/SKU중복/0원/long 경계. orderSnapshotsSurviveSourceChanges: 행사중지·상품비공개·제목/가격/쿠폰 변경 후 snapshot 동일 | 단위 규칙 및 원천 변경 assertion 확인; 정규화 저장열 검사는 R-A05 잔여 |
| A02 기한 | orderDeadlineAndMalformedSnapshotsLeaveNoRows: created 정각/이전·15분초과·nanos 거부, 15분·쿠폰끝 정각 허용, 끝+1microsecond 거부 | A 저장 경계 확인. 실제 서버 now/최소기한 계산은 C 책임 |
| A03 상태·모듈 | forbiddenTransitionsAreRejected의 5×5 표·알수없는 상태 거부; 기존 실제 production/잘못된 fixture ArchUnit 필수 목록 | 순수 규칙은 상태변경 부작용 없음. 실제 전환은 D/P09 책임; 독립 실행 대기 |
| A04 번호·시도 | checkoutAttemptsAndOrderNumbersAreDistinct, orderNumberCollisionsRetryWithoutOrphans: 다른키 분리·순차 같은키 동일·강제충돌 재발급·5회 소진 rollback | 동시 같은키/상충키는 R-A08 |
| A04 게스트 | orderGuestAttemptBindingSurvivesCheckoutRestart: current_attempt 초기화 뒤 이전 연결/동일키 결과 유지, 다른키의 동일 인증 attempt 거부 | 저장 fixture의 연결 검증. 실제 인증소비·응답유실은 C에서 수행 |

## 사전 지적 이력

- R-A01: guest_checkout 재사용과 주문 시도 관계가 미확정이었다. A 문서에 브라우저 구매 세션→복수 주문 시도, 성공 게스트 시도→불변 guest_attempt ID로 정정됐고 연결 보존 검사가 추가됐다.
- R-A02: 합계 외 단위 구조 assertion이 없었다. 단위 개수·0기반 ordinal·SKU 중복·비음수·항등식 검사가 추가됐다.
- R-A03: DEMO/SANDBOX 저장 검사 연결이 없었다. 두 모드 round-trip과 null/알수없는 모드 DB 거부가 추가됐다.
- R-A04: 주문 저장의 기한 경계 검사가 없었다. 신규 기한 검사의 정상·거부 경계를 확인했다.
- R-A06: 원천 변경 검사가 제목/가격/쿠폰 상태에 한정됐다. 저장 전에 실제 행사를 적용하고 행사중지/상품비공개/원천 변경 뒤 전체 snapshot 동등성을 확인하도록 보완됐다.
- R-A07: SKU 중복 검사가 매대 중복에서 먼저 실패하고 곱셈 overflow 검사가 음수에서 먼저 거부됐다. 별도 매대의 동일 SKU 및 비음수 입력의 ArithmeticException assertion으로 보완됐다.

이 지적들은 정적 보완 확인이며 개발·독립 최종 실행 결과로 해결 여부를 확정한다.

## R-A05 / P2 — 금액 저장열 검사의 잔여 공백

`orderMultiStoreUnitsAndDatabaseBoundaries`는 두 매대·한 매대 두 SKU·총8단위 및 단위5필드 전체를 대조한다. 그러나 seller_order는 total만 읽으며 item의 gross/할인/net/line_snapshot과 부모 amount를 직접 대조하지 않는다. Quote JSON이 정상이고 단위행이 정상이어도 정규화된 저장열의 배분 오류를 놓칠 수 있다. 현재 fixture의 배송할인은0이라 배송비와 배송할인의 별도 저장을 검증하지 못한다.

상품할인·쿠폰할인·배송할인이 비0인 저장 자료를 포함하고 seller의 subtotal/product_discount/coupon_discount/shipping_base/shipping_discount/total, item의 quantity/unit_price/gross/두 할인/net/line_snapshot, 부모 amount를 대응하는 Quote 값과 직접 비교한다. 단위→항목→매대→부모 합계도 동일 주문의 저장행을 기준으로 연결한다. 순수 계산 검사와 JSON round-trip만으로 저장열 검증을 대신하지 않는다.

## R-A08 / P2 — 같은키 저장의 실제 경합 검사가 없음

JdbcOrders는 attempt INSERT ON CONFLICT 후 행잠금을 통해 같은 구매 세션+키를 직렬화한다. 현재 검사는 순차 retry와 SQL UNIQUE 거부만 다루며 실제 두 거래가 save를 동시에 실행하는 경로를 검증하지 않는다. P08 공통 실행 기준의 경합 assertion과 A04 같은키 동일시도 보장을 연결해야 한다.

독립 연결/거래 두 개를 latch/barrier로 겹치게 하여 같은 입력은 같은 checkout/order/번호에 수렴하고 전체 주문 그래프가 한 개만 생기는지 검사한다. 같은 세션+키·다른 requestHash 경합은 한 성공/한 충돌, 패자의 부분행0과 승자 결과 보존을 확인한다. sleep 또는 단순 순차 호출로 대체하지 않는다.

## 경계·보안·후속 인계

Orders는 신뢰된 내부 포트이며 공개 HTTP가 없다. 현재 find에는 권한 검사가 없으므로 C/F 외부 진입점은 현재 소유권·세션을 확인한 후 사용해야 한다. requestHash의 서버 계산과 실제 현재 견적 재계산도 C 책임이다. A의 연락처 저장을 판매자/공개 응답에 그대로 노출해서는 안 된다. 원문 인증/비밀번호 입력 필드는 Orders에 없으며 실제 인증·로그 비노출 자동 검사는 C/F에서 이어받는다.

V31의 guest_attempt/identity/store FK 획득과 주문 시도 행잠금도 C 통합 잠금 표에 포함해야 한다. 기존 쿠폰 예약의 명시 잠금 순서만으로 이 신규 FK 잠금이 안전하다고 가정하지 않는다. payment→order와 fulfillment→order의 향후 경로는 실제 클래스가 생긴 단계에서 다시 검증한다.

과거 쿠폰 이행 검사의 target30 고정은 V28→V29/V30이라는 역사 이행 범위를 유지하는 변경이며 최신 V31 검증을 대신하지 않는다. 최신 루트 하네스는 새 DB에서 V31을 적용해야 한다.

## 현재 판정과 보존

독립 전체 실행·소스 hash 대조·실패 manifest 보존 비교는 미실행이다. 이 세션의 새 보존 검사 통과를 주장하지 않는다. 개발 전체 종료 후 R-A05/R-A08 보완 및 동결 소스를 확인하고 별도 새 경로에서 루트 `./verify.ps1 -Offline`을 실행해야 한다. 필수 차단과 독립 실행이 남아 A 완료/B 착수를 승인하지 않는다. 기존 서버·DB·미디어·실패 원본을 변경하지 않았고 커밋·배포·푸시·P09를 수행하지 않았다.

구현자는 R-A05/R-A08을 수용했으며 dev1 종료 후 비0 할인 저장열 대조와 실제 두 거래 경합 검사를 추가하고 새 dev2 전체 실행을 하겠다고 전달했다. 이는 보완 예정이며 아직 수정·실행 확인 전이다. dev1 통과 여부와 무관하게 최종 소스 증거로 소급하지 않는다.

## 최종 동결 소스 재검토 — 2026-10-27

앞 절들은 당시 정적 검토 이력이다. 최종 개발3 입력382개와 현재 소스 해시가 모두 일치함을 확인하고 독립 전체 `.local-tools/p08-a-review1-20261027`을 시작했다. 시작 전 MAVEN_ARGS는 설정되지 않았다.

- R-A05 보완: 상품/쿠폰 할인 모두 비0, shipping_base=3000/shipping_discount=700을 assertion하는 fixture가 추가됐다. 부모 amount, seller의6개 금액열, item의5개 금액열 및 전체 line_snapshot, 각 단위5필드를 원 Quote와 대조한다. 단순 JSON round-trip에만 의존하던 공백이 해소됐다.
- R-A08 보완: 별도 스레드·외부 TransactionTemplate·CyclicBarrier로 동시에 저장한다. 동일 내용은 동일 Snapshot, 상충hash는 한 성공/한 충돌, checkout/order/contact 각1개를 확인한다. 게스트 동일키·동일attempt 동시저장도 같은 Snapshot에 수렴하고 다른키의 동일attempt는409를 확인한다.
- 개발2의 쿠폰 중복 unique 경합과 별도 guest-conflict-red의 주문 unique 경합에 대응해 두 INSERT가 대상 제한 없는 ON CONFLICT DO NOTHING을 사용한다. 이후 기존 checkout 행을 잠그고 회원/hash 또는 guest attempt/소유자 일치를 검사한다. 게스트 attempt가 다른키에 이미 쓰여 요청키 행을 찾지 못한 경우에는 명시적인409로 거부한다. 고유 제약 자체를 제거하거나 충돌을 성공으로 처리하지 않았다.
- `guest-conflict-red/db/maven.txt`에서 order_checkout_attempt_guest_attempt_id_key 위반을 직접 확인했다. 새 쿠폰 회귀는8회 각각 동시 동일 요청의 동일 RESERVED 결과·batch1개·감사1개를 검사하며 기존 상충 batch/잠금 순서 검사를 유지한다. 시작 barrier는 실제 같은 시간 경쟁을 유도하지만 모든 반복에서 동일한 내부 인덱스 타이밍을 강제한다는 주장은 하지 않는다.
- 기존 필수 목록은 삭제 없이 신규12개를 추가했다(순수 규칙3, 주문 DB8, 쿠폰 회귀1). verify.ps1 변경은 결과 scope 설명만이다. 이전 정적 검토의 target30 고정 방안은 최종 소스에 적용되지 않는다. 쿠폰 V28→최신V31은 migration3개, 재고 V17→최신V31은14개로 기대치를 갱신하고 과거 데이터 보존 assertion을 유지했다.

현재 정적 필수 잔여 지적은 없으며 독립 실행 최종 결과·전후 소스·보존 대조가 남았다. P08-A는 주문 저장 모델 범위이고 실제 주문 생성 HTTP·예약 조정·전화 인증 소비·결제·판매자 응답 권한은 후속 단계 책임이다.

## 독립 전체 검증 최종 결과

이 검토자가 루트 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-a-review1-20261027`을 실행했다. 2026-10-27 16:06:56.0754729~16:11:20.9632931 KST, 종료0, result.status=passed, preservation=passed다. Java360(기본166+통합194), PC/모바일 브라우저68, 프런트 단위162, Node72가 통과했다. JUnit/ArchUnit·Checkstyle·새 PostgreSQL·제공자 probe·타입·lint·build·하네스 gate와 필수 coverage 모두 통과했고 실패/오류/skip/필수 누락0을 직접 대조했다. 제공자 probe는 통합194에 포함되며 브라우저68과 중복 합산하지 않는다.

개발3/독립 inputs382개의 경로·SHA256 차이0, 독립 실행 후 현재 소스 해시 불일치0이다. inputs SHA256은 `0E8E5E484453BA164126BCAD19F89407D86FEC23C4C523E69DB7A95195A51582`, 기준 HEAD는 `460aa8bb0bb22f5138849eb00c050abd18f6fb53` 위 미커밋 변경이다. 실행 이후 이 검토 문서·증거 사본만 작성했다.

### 제약별 판정

| 제약/지적 | 독립 실행 증거와 결과 |
| --- | --- |
| A01 / R-A01·03 | orderOwnerAndAttemptConstraints, orderGuestAttemptBindingSurvivesCheckoutRestart, checkoutAttemptsAndOrderNumbersAreDistinct 통과. 소유자 XOR·키/게스트 연결 고유·모드 저장 거부/round-trip 확인 |
| A02 / R-A02·05·06·07 | snapshotAmountsConserve, snapshotStructureAndUnitAmountsAreValidated, orderMultiStoreUnitsAndDatabaseBoundaries, orderSnapshotsSurviveSourceChanges 통과. 단위 구조·checked 계산·다매대 저장열·비0 할인/배송분리·원천 변경 보존 확인 |
| A02 / R-A04 | orderDeadlineAndMalformedSnapshotsLeaveNoRows 통과. 정각/최대15분/쿠폰 원만료/microsecond 경계와 잘못된 입력 행0 확인 |
| A03 | forbiddenTransitionsAreRejected 및 실제 production/불법 fixture ArchUnit 통과. 배송 상태를 주문 상태에 넣는 DB 갱신도 거부 |
| A04 / R-A08 | orderConcurrentKeysHaveOneDurableResult, 게스트 동시저장, orderNumberCollisionsRetryWithoutOrphans 통과. 동일키 수렴·상충hash 한승자·다른키 인증 재사용409·번호 충돌/소진 rollback 확인 |
| 선행 쿠폰 경합 회귀 | couponBatchConcurrentIdenticalInsertsRespectAllUniqueKeys 및 기존 couponReservationCrossSellerAndConcurrentRetries/다른batch·교차판매자·같은구매자 잠금 검사 통과. 고유 제약과 입력 충돌 판정 유지 |

위12개 신규 필수 메서드와 기존 전체 회귀는 coverage 및 java-cases에서 passed다. R-A01~08은 이 최종 소스와 독립 실행에서 해소했으며 **P08-A 저장 모델 범위의 필수 잔여 차단 없음**으로 판정한다. 후속 단계의 실제 거래/HTTP 구현까지 완료했다고 판단한 것은 아니다.

### 보존·실패 이력·인계

새 DB `d2_it_6ff64de31449454c818674a980a43aca`, 미디어 `.local-tools/p08-a-review1-20261027/db/media`, 전화 에뮬레이터 `.local-tools/phone-emulator-d274f603044b49d39202dee9db9514b2`를 사용했다. 하네스 before/after는 기존 서버·컨테이너·개발/데모DB·과거 검증DB 존재·미디어 보존 통과다. 과거 검증DB 전체 행의 전수 덤프 비교와는 구분한다.

P06/P07 실패14,158파일 및 최종 P08-A 실패4,721파일의 실행 전후 누락/해시 불일치0을 확인했다. 최초4721 확정 전에는4697파일을 읽었으며, 추가된 에뮬레이터24파일을 포함한 최종 manifest를 실행 중 다시 읽고 종료 후 재대조했다. 원본을 삭제·수정하지 않았다. P08 사전 에뮬레이터 실패, 단위/저장 red, migration 기대개수 실패, dev1/2 실패와 guest unique 실제 red를 그대로 보존한다. 과거 원인 미확정 실패를 이번 성공으로 해결 처리하지 않는다.

`P08-A-coupon-unique-diagnostic.txt`의 2026-10-27 06:55:31.433 UTC PostgreSQL ERROR/DETAIL/STATEMENT를 직접 읽어 coupon_batch_identity와 이전 대상제한 INSERT 충돌을 확인했다. guest red 로그와 함께 다중 unique 충돌의 실제 실패 근거이며 단순 추론과 구분한다. 최종 ON CONFLICT 보완과 신규/기존 회귀 통과는 해당 동시재시도 경로의 해결 근거다.

원본 경로를 유지하고 `evidence/P08-A-review-final-*`에 result/inputs/coverage/Java개별결과/브라우저·프런트 coverage/명령로그/DB요약·대상/브라우저report/제공자probe/before·after/소스·실패 대조/요약 사본22개를 저장했다. 요청받지 않은 커밋·배포·푸시·P09는 수행하지 않았다. C에서는 현재 서버 견적·권한·인증소비·전체예약/잠금순서를, D에서는 실제 상태변경/결제 조정을 별도 검증해야 한다.
