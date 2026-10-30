# P09-A 구현 독립 검토

## 활성 인계 메모 — 2026-10-30 재검토

최신 상태: 아래 최종 독립 실행이 종료0/status·preservation passed로 끝났다. **P09-A 로컬·모의 범위의 독립 검증 완료**, R-A01~03 해소, 잔여 필수 차단 없음이다. B 진입·커밋·배포·푸시는 수행하지 않는다. 진행 중/미실행 표기는 각 이전 시점의 이력이다.

후속 인계: 개발3 종료·통과 확인 후 **새 독립 전체 실행이 요청되어 진행 중**이다. 실행 경로는 `.local-tools/p09-a-independent1-20261030`, 선택 없는 `./verify.ps1 -Offline`이며 MAVEN_ARGS/MAVEN_OPTS를 실행 프로세스에서 비웠다. 개발 하네스와 겹치지 않는다. 제품·테스트·하네스 동결과 문서/새 증거만 작성, 새 에이전트·커밋·배포·푸시·B 진행 금지는 계속 유지한다. 아래 정적 검토의 NOT RUN/대기는 해당 시점 기록이며 새 독립 결과는 마지막에 별도 기록한다.

현재 작업은 P09-A의 R-A01~03 정적 재검토다. 과거 P08 EOF 정리는 `ed96936`에서 끝났으며 현재 작업으로 재개하지 않는다. 승인 범위는 P09-A 로컬·모의 구현의 지정 검토세션 검토이며 커밋·배포·푸시는 금지한다. 개발 전체 `.local-tools/p09-a-dev3-20261030`와 동시 하네스를 실행하지 않는다. 이번 세션은 이 검토 문서만 갱신하고 제품·테스트·하네스를 수정하지 않는다. 동결3의 476파일과 보완 assertion을 대조해 정적 해소 여부를 기록하며, 독립 전체 실행은 개발 종료 후 별도 요청을 기다린다. 아래 1차 지적과 실패 이력은 당시 기록으로 보존한다.

2026-10-30. 지정 검토세션 `01a189b0-2b5e-70d3-8276-214a42f6570d`의 **정적 검토 1차**다. 개발세션의 전체 `.local-tools/p09-a-dev2-20261030` 실행과 겹치지 않도록 테스트·하네스를 실행하지 않았다. 제품·테스트·migration·필수 목록·하네스를 수정하지 않았으며 이 문서만 작성한다. 새 에이전트·커밋·배포·푸시는 수행하지 않는다.

**판정: 수정/검사 보완 및 새 독립 전체 검증 전까지 A 완료 보류.** 아래 R-A01은 응답 검증 구현의 누락이며 R-A02/R-A03은 필수 계약의 assertion 공백이다. 제품 결함의 실행 재현과 정적 추론을 혼합하지 않는다. 개발 전체 결과가 통과하더라도 아래 미검증 조건이 자동으로 검증되는 것은 아니다.

## 입력·소스 동일성·권한 범위

- 기준: HEAD `ed96936740a6295affb30c5c6046ca3a3553513a`, `codex/p05-d` dirty. `.local-tools/p09-a-execution-20261030/development-freeze2.json`의 **475파일을 현재 SHA256과 읽기 전용 대조해 불일치0**을 확인했다. dev2 inputs.json SHA256은 `2C400C4FD15A4722AEBD00F9328AC48D30811422E2D3C74574651A866B07118B`로 인계와 같다. 정적 검토 시작 후 소스를 수정하지 않았다.
- 읽은 문서: README, development-workflow, verification-harness, model-routing, P09/P09-A/P09-plan-review, [A1 설계 검토](P09-A-design-review.md), P08-C/F1/F2/G 인계. A1 세부 계약과 실제 V31/V36/V37, fulfillment/order 공개 포트와 구현, Job/Outbox/worker 및 신규·변경 assertion을 대조했다.
- 개발 증거는 개발자의 실행 이력으로 참조한다. dev1의 dispatcher 기대목록 실패, 원 에뮬레이터 시작 실패, 집중 실행의 필수목록 누락과 UI RED를 독립 통과로 바꾸지 않는다. dev2의 종료/동결은 개발세션에서 확정 후 인계할 사항이다.
- 실제 Toss·P09-B 이후·정책 소급 적용은 범위 밖이다. 모델 역할과 실제 실행 설정을 혼동하지 않는다. 이번 검토는 기존 지정 세션에서 수행했으며 새 Astra 에이전트를 호출하거나 모델 설정을 변경하지 않았다.

## R-A01 / P2 — 필수 원단위 배열이 빠져도 배송 응답을 정상으로 허용

**위치:** `frontend/src/fulfillment/ShipmentStore.ts:5,13,16`(특히16행의 `s.units===undefined || ...`), seller 소비42행, buyer 소비 `frontend/src/fulfillment/ShipmentViews.tsx:12`. `frontend/tests/unit/ShipmentStore.test.tsx:11`의 정상2+3 fixture도 units 없이 성공하도록 되어 있다.

새 서버 `Shipments.View`는 units를 항상 반환하고, A1/A06은 원단위 집합·수량·중복 대조를 요구한다. 그런데 프런트는 units를 optional로 선언하고 배열 자체가 없으면 모든 원단위 검사를 우회한다. 일반 shipment 형태 검사도 units 존재를 검사하지 않는다. 수량합만5가 되는2+3 응답은 한쪽 또는 양쪽 units가 누락되어도 seller/buyer 화면에서 유효한 배송으로 취급된다. 기존 배송의 **DB 이행**과 API에서 units가 없는 응답을 허용하는 것은 다른 문제다. 현재 API에는 이 우회를 구분할 명시적 구버전 schema 계약이 없다.

정적 재현 조건: 같은 sellerOrderId와 서로 다른 shipmentId, 원 항목 quantity5에 대한 items2/3을 준비하고 units 필드를 제거한다. 다른 필드는 정상이다. 현재16행은 units 조건을 true로 처리하고 마지막 수량합 조건도 true이므로 결과를 수용한다. 이는 코드 분기와 현재 정상 fixture에서 확인한 경로이며 이번 세션에서 브라우저/단위 실행을 재현한 것은 아니다.

**기대 보완:** 현재 View 계약에 대해 units를 필수 배열로 검증한다. 기존 단위/패널 fixture도 실제 v37 응답처럼 유효한 ordinal을 넣어 기존 보장 assertion을 유지한다. 구버전 API 응답 지원이 꼭 필요하다면 명시적 응답 버전과 제한을 먼저 정의해야 하며, 필드 누락을 묵시적으로 구버전으로 간주하지 않는다. seller와 buyer 양쪽에 units 누락·null·잘못된 타입·중복·수량 불일치의 거부 assertion 및 정상2+3 수용 assertion을 연결한다. 거부 시 사적 배송 표시와 새 명령 가능 상태가 남지 않아야 한다.

상태: **OPEN / 구현 보완 필요**, 실행 재현 NOT RUN.

## R-A02 / P2 — 다항목 분할과 새 명령의 canonical 충돌 계약에 실행 가능한 assertion 부족

**위치:** `backend/src/test/java/com/example/marketplace/dbtest/PostgresJdbcIT.java:655-705`, 실제 분기 `fulfillment/infrastructure/JdbcShipments.java:78-95`; JUnit `fulfillment/domain/ShipmentRulesTest.java:6-11`, 규칙 `fulfillment/domain/ShipmentRules.java:4-6`.

실제 DB 분할 성공 검사는 모두 같은 판매자의 단일 항목 quantity5를2/1로 이동한다. 따라서 다음 A1 계약을 검증하지 않는다.

1. 같은 배송의 두 원 항목 중 하나를 전량 옮기고 다른 항목을 남기는 합법 분할. 실제88행의 donor `shipment_item DELETE`와 deferred unit FK/count trigger 조합은 이 경로에서 처음 사용된다. 현재 양쪽 수량만 줄이는 단일 항목 분할 통과로 대신할 수 없다.
2. 같은 split 키로 동일한 다항목 요청의 배열 순서만 바꿔 재시도하면 최초 receipt/선택 ordinal/targetId를 반환하는 canonical 계약, 같은 키로 quantity·version을 바꾸면409인 계약.
3. tracking 성공키로 번호 또는 사유·version만 바꾸면409이며 새 event/history/version이 생기지 않는 계약. 현재671/705행은 동일내용 replay·인증 거부를 검사하지만 내용충돌을 검사하지 않는다. 기존 ship의 `shipmentEventsAreIdempotent`는 별도 event `prior` 경로여서 새 `shipment_command` receipt의 증거가 아니다.

JUnit의 `[2,3]→[2,0]` assertion은 `ShipmentRules.splitQuantities`라는 **제품 분할 경로가 호출하지 않는 함수**만 검사한다. 현재 `JdbcShipments.split`은83~88행에서 별도로 검증/변경하므로 이 단위검사가 실제 donor 삭제를 검증한 것으로 연결하면 안 된다. 함수가 논리적으로 맞는지와 제품 경로의 assertion 범위를 분리해야 한다.

**기대 보완:** 같은 sellerOrder의 최소2항목 fixture에서 한 항목 전량과 다른 항목 일부/0 이동을 수행하고, source/target 항목행 존재·수량, 원단위 FK·정확한 ordinal 집합/금액, marker/원 주문 불변을 대조한다. 성공 후 배열 역순 exact replay와 같은키 내용충돌을 모두 실행하고, 실패 전후12테이블 snapshot이 같음을 확인한다. tracking도 동일키 다른번호/사유/version 각각409·변화0을 검사한다. 실제 제품 검증을 JUnit 규칙에 연결하거나, 해당 단위검사의 증명 범위를 낮추고 실제 DB 경로로 필수 조건을 충족한다. 기존 테스트를 삭제해 목록을 줄이지 않는다.

상태: **OPEN / assertion 공백**, 다항목 동작이 현재 실패한다고 단정한 것은 아니다.

## R-A03 / P2 — V36 이행 데이터와 실제 prepare/v1 job/scanner 소비가 별도 fixture로 끊겨 있음

**위치:** `backend/src/test/java/com/example/marketplace/dbtest/PostgresJdbcIT.java:723-743`, 특히731~732행; legacy job 검사686~691행. 실제 생산/소비는 V37 39~42행의 marker digest와 `JdbcShipments.prepare:61-63`, `MockShipmentJobs:22-24,43`이다.

현재 upgrade 검사는 별도 `p09_a_legacy` 스키마를 V36→V37로 올려8개 marker·24개 units의 **개수** 및 과거행 보존을 확인한다. 하지만 이후 제품 prepare/ship/job/scanner를 이 업그레이드된 데이터에 적용하지 않는다. 별도 runtime 검사는 처음부터 V37인 `marketplace`에 새 Java 경로로 marker를 생성하거나 최신 delivery receipt를 삭제해 legacy를 흉내 낸다. 따라서 다음 접합부를 자동으로 증명하지 못한다.

- SQL 이행이 생성한 marker의 digest/순서와 Java `digest/originalUnits`의 값이 같아서 prepare 재호출이409 없이 쓰기0인지.
- 원 v1 pending job과 원 applied event가 **이행 후 실제 handler**에 의해 처리/재조회될 때 원 payload·content·최초 Result가 보존되는지.
- SQL로 추가한 기존 DELIVERED의 units를 scanner/`recordInitialDelivery`가 받아 원 eventId·시각·전체단위를 정확히1회 저장하는지.

마이그레이션에서 digest 표현이 바뀌어도 현재 upgrade count 검사는 통과할 수 있고, runtime fixture는 Java가 만든 marker만 사용하므로 그 불일치를 놓칠 수 있다. 이 정적 검토에서 현재 SQL/Java digest가 실제로 다르다는 주장은 하지 않는다. **A08/A09의 이행→재실행 계약이 분리된 assertion 사이에서 빠진 것**이다.

**기대 보완:** 새 격리 PostgreSQL에서 V36 네 상태 및 실제 v1 pending/완료 job을 만든 뒤 V37을 적용하고, 그 데이터에 제품 경로를 연결하는 검사를 추가한다. 최소한 SQL marker의 정확한 값/귀속/원단위 집합을 Java 계약과 대조하고, 업그레이드된 PREPARING에서2+3 분할→prepare 반복, SHIPPED의 원 v1 handler→정상완료, 기존 DELIVERED의 실제 migration handler→exact replay를 확인해야 한다. 모든 경우 원 event/job/배송 id·원시각 불변, 추가배송/수량복원/409루프0, 완료 unit 사실1회가 기대 결과다. schema 때문에 제품 경로가 연결되지 않으면 별도 **새 DB** fixture를 사용하고 기존 DB를 고치지 않는다.

상태: **OPEN / 이행 접합부 assertion 공백**, 새 migration과 정상 코드의 실행 실패를 재현한 상태는 아니다.

## 제약별 정적 대조와 후속 독립 실행 범위

| 제약 | 확인한 코드/assertion | 정적 판정·남은 항목 |
| --- | --- | --- |
| A01/A02·H01 | 원단위 PK/FK·deferred count trigger, split의 current ordinal 선택·proper subset·order 잠금, 두 connection 경쟁1승자, 없는ordinal/중복/quantity99 거부 | 설계 방향 일치. R-A02의 다항목/명령충돌 assertion 필요 |
| A03 | base state와 exception overlay/epoch 분리, 저장 이벤트 시각을 다시 읽어 전달, stale v1/v2 무효화·tracking 정정·완료 뒤 예외 영향0 | 신규 예외 이력과 구세대 경로는 코드상 존재. 독립 실행 대기. 미래 B의 기한 보정/정책을 여기서 승인하지 않음 |
| A04 | sellerLock에서 current 자격→order→배송 정렬잠금→자격 재검사;3명령×6조건의 실제 lock 대기, HTTP CSRF/타판매자, 동일키 재시도의 권한 재검사 |18조건 assertion 구조 확인. 실행/실제 fixture 결과는 후속 전체에서 확인 |
| A05 | `shipmentState`는 신규 receipt/facts/units 포함12테이블; split/tracking의 checked/runtime26 주입; 실제 delivery job에 ShipmentWorkerProbe를 연결해 commit 후 complete 직전 child kill/restart | generic worker 효과를 대체물로 쓴 것이 아님을 확인. 독립 실행 대기 |
| A06 | 부분2+3/POST 유실 동일body·409→GET503·tracking 인증상실의 PC/mobile 브라우저, 프런트 늦은응답 폐기/400 수정 가능 | R-A01 필드 누락 거부 보완 필요. 정상 browser assertions는 실제 API/DB 값에 연결됨 |
| A07·H02 | order.api 값 타입, InitialDeliveryWriter의 order 테이블만 쓰기, same caller transaction, 역방향 order→fulfillment/claim fixture 추가 | 코드 방향 일치, 기존 ArchUnit assertion 유지. 독립 실행 대기 |
| A08 | 기존 migration 불변, 새 BASIC marker+현재 합집합 비교, 원수량/금액/원 event/marker 보존, missing marker 거부 | count 보존만으로 이행 후 제품 소비를 증명하지 못함: R-A03 |
| A09 | 원 eventId/fingerprint·unit PK/FK, delivery unit INSERT 실패 시 전체 rollback, exact replay/변경된 시각·shipment 충돌, live 완료와 migrate의 order lock 경합 | 분리된 정상 경로는 확인. R-A03의 실제 이행 접합부 및 후속 전체 필요 |
| scanner 복구 |51개 사례, 두 번째 enqueue trigger 실패시 batch rollback,50개 commit 후 실제 child kill/restart, 다음cursor 및51 receipt/255unit, v1 job 불변·원 완료event 없는 데이터409 | 실제 handler/복구 assertion 구조 확인. 위 이행 데이터 연결 문제와 별도로 유지할 검사 |
| H03 | 기존 `P07-C-1`/원 주문 금액 불변, A는 event/unit fact만 저장하고7일·회수비·claim·확정 구현 없음 | A 담당 경계와 B/C/E 미결 정책 유지. H03 전체 사업정책 완료를 뜻하지 않음 |

기존 필수 목록 삭제/시간 제한 확대는 읽은 diff에서 발견하지 않았다. `verify.ps1` 변경은 scope 설명이며 gate를 완화하지 않는다. dispatcher 등록 기대값에 INITIAL_DELIVERY_SCAN/MIGRATE를 더한 변경은 새 handler와 맞추는 범위이며 duplicate-handler/transaction assertion을 유지한다. 이 판단은 실제 전체 실행 통과를 대신하지 않는다.

## 다음 인계와 실제 실행 상태

개발 전체가 실행 중인 동결 소스는 이 검토 때문에 변경하지 않는다. 개발세션이 종료·원본 보존 후 R-A01~03을 실제 재현/검사 보완하고, 변경된 소스의 새 개발 전체 결과·제약별 assertion·실패 이력·동결 SHA를 인계해야 한다. 그 뒤 별도 요청에 따라 검토세션에서 새 증거 경로의 전체 Offline을 실행한다. 현재 A 완료나 B 진입을 승인하지 않는다.

이번 실행은 파일 읽기·diff·475파일 SHA 대조와 검토 문서 형식/링크 확인에 한정한다. DB·서버·미디어·실패 원본을 수정하거나 테스트로 사용하지 않았다. **독립 JUnit/ArchUnit/PostgreSQL/브라우저/프런트/Node/보존 검사 모두 NOT RUN**이다. 이 문서에 개발1/개발2의 결과를 독립 실행 결과로 소급 기재하지 않는다.

## 동결3 보완 정적 재검토 — 2026-10-30

**판정: R-A01~03은 코드·실행 가능한 assertion 기준으로 정적 해소. 신규 정적 차단 지적 없음. 독립 전체 검증은 대기이며 P09-A 완료/B 진입은 계속 보류한다.** 앞의 OPEN은 동결2에 대한 당시 지적이며 삭제하거나 소급 변경하지 않는다.

입력은 `development-freeze3.json`의476파일이다. 현재 파일 SHA256을 읽기 전용으로 전수 대조해 불일치0을 확인했다. HEAD는 `ed96936740a6295affb30c5c6046ca3a3553513a`, branch `codex/p05-d`+dirty이며 개발3 `inputs.json` SHA256은 `052D974EA9F83B18667C84D496230C4C94E29FDDCEC14F655D2D88CD8D2FBC7A`로 인계와 일치한다. 개발 전체와 동시 하네스를 실행하지 않았으며 아래 실행 증거는 기존 개발 산출물을 읽은 결과다.

| 지적 | 실제 코드·assertion 대조 | 정적 판정 |
| --- | --- | --- |
| R-A01 | `ShipmentStore.ts:5,13,16,43,45`에서 units 필수 타입/배열/원단위 형태를 확인하고 seller와 buyer 모두 중복·항목별 수량 불일치를 거부한다. optional 우회는 제거됐다. `ShipmentStore.test.tsx:17`은 seller/member 각각 정상 배송을 먼저 읽은 뒤 누락/null/문자열/빈 배열/중복/수량 부족 응답을 읽어 error, shipments=[], order=null, confirmation/uncertain/editUncertain 초기화 및 ship/split/tracking POST0을 검사한다. 정상2+3과 응답유실 재시도 fixture에도 실제 ordinal을 넣었다. | 원 지적의 응답 누락 우회와 필요한 회귀 assertion 해소. |
| R-A02 | `PostgresJdbcIT#splitMultiItemMovesWholeLineAndCanonicalReceiptsRejectChangedContent`는 같은 매대의 실제 두 상품5/3을 생성하고5전량+다른0/1 이동 두 경우를 검사한다. donor 항목행 삭제, source/target 수량·정확 ordinal·target net, 원 주문/원단위/marker 불변을 대조한다. 역순 exact replay의 Result 동일성, quantity/version 충돌409, tracking 번호/사유/version 충돌409 및 각12테이블 snapshot 불변을 검사한다. `JdbcShipments.java:85`가 실제로 `ShipmentRules.splitQuantities`를 호출한다. 이 helper는 전체 proper-subset 수량 검증이며 물리 donor 삭제·귀속·금액의 증거는 DB assertion이다. | 다항목 donor 삭제와 canonical 충돌의 assertion 공백 해소. |
| R-A03 | `shipmentV36UpgradeResumesProductPortsAndOriginalV1Jobs`는 새 allowlist DB에 V36 스키마와 네 상태/원 v1 READY·SUCCEEDED job을 구성하고 V37 후 원 header/item/event/audit/job/outbox를 비교한다.8marker/24unit 개수 외에 digest를 정확 재계산한다. `LegacyShipmentMigrationProbe`가 같은 이행 DB에 현재 제품 context를 연결하여 prepare 반복 쓰기0,2+3 분할 후 prepare/receipt/PAYMENT_SETTLED 재처리 불변, tracking 후 원 v1 worker 실행과 v2 완료, 원 content/시각/최초 Result replay를 검사한다. 실제 scanner/migrate 실행·반복 후 원 DELIVERED eventId/시각/전체 unit 일치와 원 이벤트/완료 job/marker/금액/주문 불변을 확인한다. | SQL 이행 데이터에서 실제 제품 소비로 이어지는 접합부 assertion 공백 해소. |

R03 fixture는 현재 생성한 주문·배송의 V36 호환 열과 명시적 v1 job을 새 V36 DB로 복사한 합성 이행 검사다. 운영 데이터 전수 이행이나 과거 바이너리 실행을 주장하지 않는다. pending job은 정상 실행에 필요한 status/attempts가 변하고 원 identity/payload/schedule은 유지되며, 이미 SUCCEEDED인 원 job은 전체 행이 같다. 이 구분은 기존 자원 보존과 호환성 계약에 맞는다.

### 읽은 개발 실행 증거와 남은 실행

- `p09-a-execution-20261030/review-r01-green2.txt`: 배송 Store/Panels28 통과. 이는 개발 실행이며 독립 실행이 아니다.
- `p09-a-r02-r03-focused3-20261030/db`의 PostgresJdbcIT XML: 위 두 새 테스트 tests2/failures0/errors0/skipped0과 정확한 이름을 확인했다. root `result.json`은 미선택 기존 필수 검사 누락으로 **failed**, preservation passed다. 집중 결과를 전체 통과로 바꾸지 않는다.
- focused2의 `db/v36-runtime-9825eb52-1a11-4d4e-a662-3ff28d35d290/runtime-result.json`: 새 DB `d2_it_6d7531ae59204b67baf7248799dad5e7`, 배송9·receipt3·완료unit15, 원 event/job 보존 및 SQL marker 재작성0 결과를 확인했다. 이 count만으로 판정하지 않고 위 assertion 본문과 연결했다.
- R01 RED, R02 첫 fixture setup 실패, 집중 실행의 필수 누락, 개발1 실패와 개발2 결과는 기존 이력대로 유지한다. 이번 검토에서는 테스트·하네스·제품·필수 목록을 수정하거나 실행하지 않았다. 변경 파일은 이 검토 문서뿐이며 새 에이전트·커밋·배포·푸시는 없다.
- 개발3 종료·최종 소스 동결·실패 보존 인계 후 별도 요청에 따라 새 증거 경로의 독립 전체 Offline을 수행해야 한다. 현재 독립 JUnit/ArchUnit/격리 PostgreSQL/브라우저/프런트/Node/보존은 모두 **NOT RUN**이다. 실제 Toss 차단 및 P09-B 이후 범위 제외도 유지한다.

## 최종 독립 전체 실행 — 2026-10-30

지정 검토세션에서 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-a-independent1-20261030`를 직접 실행했다. 개발3 종료 후 시작했으며 선택 옵션 없이 MAVEN_ARGS/MAVEN_OPTS를 비운 실행이다. 로컬 검증 연결을 허용하는 실행 권한으로 수행했다. **09:39:30~09:49:38 KST, 종료0, status=passed, preservation=passed, error=null**이다. 이번 독립 시도에서 실패·누락·skip·flaky는 없다. 앞의 개발 실패/집중검사 failed/정적 NOT RUN 기록을 새 결과로 덮어쓰지 않는다.

- Java467: JUnit·ArchUnit187, PostgreSQL279, 전화 제공자 에뮬레이터1. 브라우저110, 프런트225, Node75 및 typecheck/lint/build 통과. Java 필수51묶음/402이름, 브라우저110·프런트225 필수 이름 모두 충족했다. 모든 Java/프런트 case passed, 브라우저 skipped/unexpected/flaky0, Node fail/cancelled/skipped/todo0이다.
- 소스 HEAD `ed96936740a6295affb30c5c6046ca3a3553513a`, branch `codex/p05-d`+dirty. 개발 동결3·독립 inputs476파일이 일치하고 종료 후 현재 파일 SHA256 불일치0이다. inputs SHA256 `052D974EA9F83B18667C84D496230C4C94E29FDDCEC14F655D2D88CD8D2FBC7A`는 개발3과 같다. 실행 중 제품·테스트·하네스 수정 없음.
- 새 주 DB `d2_it_c44c911ed8cf42649214a4e30c7bedc4`, 새 미디어 `p09-a-independent1-20261030/db/media`. V36 runtime 전용 새 DB `d2_it_210197f7bcec415aa1b4762c09c6c8f8`, 증거 하위 `db/v36-runtime-f361179f-4f1e-49d9-aa9f-82cf1b4ca47b`에서 child exit0/배송9/receipt3/완료unit15 및 원 event/job/SQL marker 보존을 확인했다. 에뮬레이터 증거는 `.local-tools/phone-emulator-0b96845a65a042d092c31869d94f6f76`에 보존한다.
- 실행 원본: root `result.json`, `inputs.json`, `coverage.json`, `java-cases.json`, `browser-coverage.json`, `frontend-coverage.json`, `TEST-frontend.xml`, 명령별 로그와 `db/build` XML·`db/browser-report.json`. 검토 대조 원본은 `.local-tools/p09-a-independent1-audit-20261030/final-audit.json`이다.

### 제약별 실제 assertion·독립 실행 연결

아래 Java 메서드는 독립 `java-cases.json`에서 모두 passed이며 세부 assertion은 앞의 정적 대조와 동일한 동결 소스다. 프런트/브라우저는 각각 XML/필수 coverage에 실제 수집된 이름으로 연결한다.

| 제약 | 실제 검사명과 기대 assertion | 독립 결과 |
| --- | --- | --- |
| A01·A02 / H01 | `splitQuantitiesStayWithinOriginal`, `splitShipmentsPreserveScopeAndQuantities`, `splitUnitsPreserveLegacyBasicAndReplay`: 진부분집합, 없는/중복/타귀속 단위와 count 불일치 거부, 경쟁 한 승자, 정확 ordinal·금액 보존 | passed |
| A02 / R-A02 | `splitMultiItemMovesWholeLineAndCanonicalReceiptsRejectChangedContent`: 다항목 전량+일부/0, donor 삭제, 역순 최초 Result, split quantity/version·tracking 번호/사유/version 충돌409와12테이블 불변 | passed, 지적 해소 |
| A03 | `trackingAndExceptionContracts`, `shipmentTrackingEventsNeverRegress`, `shipmentProgressEpochSupportsLegacyJobs`, `shipmentHistoryUsesCausalOrder`: 번호/사유 경계·정정 이력, 예외 overlay/epoch, v1/v2 stale 및 원시각·처리순서·완료fact 보존 | passed |
| A04 | `splitShipmentCommandsRecheckAuthorityAfterWait`, `shipmentWriteRechecksSellerAuthority`, `shipmentExpiryRecheckedAfterOrderAndShipmentWaits`: split/tracking/ship의 HTTP 권한·CSRF, 실제 잠금 대기 뒤 현재 자격/자연만료 거부·쓰기0 | passed |
| A05 | `splitShipmentRollbackAndWorkerRecovery`, `shipmentOutboxAndWorkerRecovery`: checked/runtime 주입 rollback, 실제 완료 child의 commit 후 ack 전 종료/재개에서 원 이벤트·fact1회 | passed |
| A06 / R-A01 | `ShipmentStore.test.tsx`의 `seller and buyer reject missing null malformed duplicate and mismatched units without private state or new commands`: 정상 읽기 후 malformed 폐기·POST0. 정상2+3와 응답유실 exact command 검사도 유지 | passed, 지적 해소 |
| A06 실제 UI | `shipments-split.spec.ts:splitShipmentQuantitiesTrackingAndExceptionsMatchSavedApiAndDatabase`, `splitConflictReadFailureAndTrackingAuthLossRequireExplicitRecovery`: 실제 저장 단위/API/DB, 동일 요청 재시도,409→GET 실패와 인증상실 후 명시 복구 | desktop/mobile 네 case passed |
| A07 / H02 | `productionClassesRespectModuleBoundaries`, `cyclesThroughPublicApisAreRejected`, `initialDeliveryDoesNotPermitReverseDependencies` 두 파라미터 및 기존 API 내부/JDBC 누출 거부 fixture | passed; fulfillment→order 방향 유지 |
| A08 / R-A03 | `shipmentV36MigrationPreservesLegacy`, `shipmentV36UpgradeResumesProductPortsAndOriginalV1Jobs`: V36 네 상태→V37 원행 보존, SQL digest 정확성과 실제 prepare 반복 쓰기0, 분할 뒤 PAYMENT_SETTLED 재처리, 원 v1 job/content/Result 호환 | passed, 지적 해소 |
| A09 / H02 | `shipmentCompletionFactsReplayWithoutCycles`, `shipmentLegacyScannerResumesWithoutResettingV1`, `shipmentLegacyScannerRejectsMissingAppliedCompletion`와 위 V36 runtime: 원 완료 eventId/시각/전체 unit exact replay, 저장실패 rollback·live 경합,51개 pagination/중단재개, 근거없는 완료 거부 | passed |

### 보존·인계·판정의 한계

기존32manifest·65,824실패파일의 사전/사후 SHA256·존재 불일치0이다. `.local-tools/p09-a-execution-20261030/failure-audit-independent1-before.json`과 `failure-audit-independent1-after.json`에 새 이름으로 저장했다. 신규 실패 manifest01~05의6,219파일과 index SHA도 사전/사후 불일치0이며 검토 audit 폴더의 `failures-before.json`/`failures-after.json`에 기록했다. manifest 수치는 별도 집합이므로 단순 합산한 고유 파일 수를 주장하지 않는다. 기존 서버·DB·과거 검증 DB·미디어 보존은 하네스 before/after 비교를 통과했다. 과거 검증 DB 전체 행 전수 비교는 이 보존 검사의 범위가 아니다.

H01은 원 `(item_id,ordinal)`·원금액·불변 preparation marker와 현재 배정 구분을 인계한다. H02는 order 소유 receipt/unit fact, 원 applied eventId/occurred_at, 같은 caller transaction, 재개 가능한 v2 scanner 및 역방향 의존 금지를 인계한다. H03은 A의 담당 경계만 충족한다. 기간/확정·회수비·교환예약 정책 snapshot 및 기존 주문 적용은 B1/C1/E1에서 구체화하며 기존 주문에7일/비용을 소급 적용하지 않는다. 원 `P07-C-1` 견적 버전과 fact schemaVersion을 새 사업정책으로 취급하지 않는다.

**최종 판정: 동일 소스의 개발3 전체와 이번 독립 전체 및 제약별 assertion을 근거로 P09-A 로컬·모의 범위 검증 완료. R-A01~03 해소, 잔여 필수 차단 없음.** 실제 Toss SANDBOX의 기존 외부 차단, 실기기·사용자 직접 확인 미실행, 실제 택배 연동·상용 부하 및 후속 기간/클레임 정책 미구현은 유지한다. P09 전체 완료나 운영 준비 완료를 뜻하지 않는다. 검토 문서와 새 증거만 작성했고 새 에이전트·제품 수정·커밋·배포·푸시·P09-B 진행은 하지 않았다.
