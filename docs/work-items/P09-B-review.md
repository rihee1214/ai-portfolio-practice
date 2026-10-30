# P09-B 구현 독립 검토

## 활성 작업 메모 — 2026-10-30

현재 작업은 **P09-B 구현의 정적 독립 검토**다. P08 EOF/P09-A는 완료된 이력으로 재개하지 않는다. H03은 사용자가 선택2(기존 원 배송 완료+7일, 이미 지난 단위 즉시 대상)를 승인했으며 `P09-B-design-review.md` §10이 최신 계약이다. 개발 전체 `.local-tools/p09-b-dev1-20261030` 실행 중이므로 이번 검토는 하네스를 실행하지 않는다. 독립 전체는 개발 종료 후 별도 요청에 따라 수행한다.

이 세션은 이 문서만 소유한다. 제품·테스트·하네스 수정, 새 에이전트, 커밋·배포·푸시·P09-C 진행 금지. 정적 검토가 끝나도 독립 전체 전에는 B 완료를 선언하지 않는다.

입력은 `.local-tools/p09-b-execution-20261030/development-freeze1.json`의494파일, HEAD `ed96936740a6295affb30c5c6046ca3a3553513a`+dirty다. 현재 SHA 전수 대조 불일치0, 개발1 inputs SHA `9C533EB89B63519043BBEEFDC56F04B50C870D73E2EB0A73A51CF05BCDECD7DC` 일치를 확인했다. `source-delta-freeze1.json`의 A대비18추가/17변경/0삭제를 B 범위로 삼는다. HEAD diff에 포함된 기존 미커밋 A를 새 B 변경으로 오인하지 않는다.

검토 진행 중이며 판정은 후속 절에 기록한다. 독립 JUnit/ArchUnit/PostgreSQL/브라우저/프런트/Node/보존 검사는 현재 모두 **NOT RUN**이다.

## 정적 검토 결과 — freeze1

판정은 **수정 필요, 독립 전체 미실행**이다. 아래는 코드·assertion 대조 결과이며 실행 통과를 뜻하지 않는다. 개발 세션의 최신 전달은 dev1 브라우저113개 ok 이후 종료 원인을 조사 중이라는 내용이다. 전체 통과로 간주하지 않으며 개발 종료/수정 소스 인계 전 독립 하네스를 시작하지 않는다.

### R-B01 [P2] 환불 참조값의 허용 길이와 저장 열 불일치

`JdbcOrderConfirmations.java:68`의 `completeRefund`는 비어 있지 않은200자 이하 참조값을 허용하고, `finishHold`의73행은 이를 그대로 `order_unit_hold.outcome`에 저장한다. 그러나 V38의28행은 `outcome varchar(40)`이며 V39에도 길이 변경은 없다. 따라서 유효한 ACTIVE 보류에서41~200자의 참조값으로 완료하면 입력 검사는 통과하지만 PostgreSQL 길이 제약 때문에 트랜잭션이 실패하고 REFUNDED 종결이 남지 않는다. 공개 HTTP가 아니라 B가 제공하는 내부 업무 포트의 결함이다. 이번 정적 검토에서 실행 재현은 하지 않았다.

현재 rollback/환불 제외 검사에는 짧은 참조값만 있어 이 경계를 검증하지 않는다. 계약에 맞는 저장 길이를 후속 migration으로 제공하거나 명시 계약과 입력 검사를 일치시켜야 한다. 이미 실행된 V38을 덮어쓰지 않는다. 새 격리 DB에서40/41/200자의 성공·동일 참조 재시도 무효과,201자의400 및 전체 상태 불변을 검증하고, 실제 환불 종결·이력·Outbox와 원단위의 자동확정 제외를 함께 확인할 것을 요구한다(200자 계약 유지 기준). 수정 전 RED와 수정 후 결과를 보존한다.

### 제약조건과 읽은 assertion 연결

아래 각 행의 실행 증거는 독립 검토 기준 **NOT RUN**이다. 향후 같은 수정 소스의 새 Offline 전체 경로에서 XML/브라우저 JSON/프런트 결과와 연결해야 한다.

| 제약 | 정적 확인한 검사와 기대 결과 |
| --- | --- |
| B01 저장 시각·7일 경계 | `ConfirmationRulesTest`, `confirmationUsesStoredMicrosecondBoundary`: microsecond 경계 직전 RETRY·변경0, 경과 후 배송 단위만 확정; 이행 probe는 정확한 due에서 실제 처리 |
| B02 보류/직접/자동 경합 | `confirmationAndClaimHoldHaveOneWinner`: 실제 병렬 요청 후 한 원단위의 HELD 또는 CONFIRMED 단일 상태, 확정 중복0·상충 active hold0 |
| B03 현재 권한·멱등 | `confirmationAuthorityAndReplay`, `confirmationAuthorityRechecksAfterActualOrderAndUnitWaits`, `confirmationGuestAuthorityAndNaturalWaits`: 타인/CSRF/변경키 거부, order/unit 잠금 대기 후 자연 만료 거부와 전체 상태 불변, 동일 요청 결과 유지 |
| B04 worker·보류 재평가 | `confirmationJobsRecoverAndReevaluateReleasedHolds`, `confirmationLeaseAndClockAreRecheckedAfterLastWait`, `confirmationActualWorkerCrashAndStaleLeaseCannotWrite`: 실제 child ACK 후 강제 종료/재개, CLAIM stale worker 거부, 효과·이력·job1회, 마지막 lease 잠금 뒤 만료 거부; R-B01 경계 보완 필요 |
| B05 UI/API 연결 | 프런트9개와 `order-confirmation.spec.ts`의 desktop/mobile4시나리오: 일부 원단위·금액 불변, 응답 유실 동일 body,409 재조회/실패 시 추가 POST0,401/403/404 비공개 정보 제거, 늦은 응답 폐기 |
| B06 경계·원자성 | ModuleArchitectureTest/ModuleRulesTest, `confirmationWritesRollbackTogether`, `confirmationDatabaseLifecycleAndDispatchConstraints`: 모듈 역참조 거부, checked/runtime 단계별 전체 snapshot rollback, DB 우회 중복 active hold·누락 lifecycle/policy/state 거부 |
| B07 출고 경합 | `claimHoldBlocksShipmentWithoutCyclicRead`, `dispatchFactsUpgradeAndReplay`: hold-first 출고 거부/ship-first CANCEL 거부, 원 ordinal 유지, 실제 동시 경합과 dispatch replay 무효과 |
| B08 정책·기존 데이터 | `confirmationUsesPersistedPolicyVersion`, `dispatchFactsUpgradeAndReplay`와 `LegacyConfirmationMigrationProbe`: 새 V37 DB51주문306단위, dry-run 적격 집합269와 확정 집합 일치, 미래5/미배송/비PAID 제외,50+1 keyset·enqueue rollback·ACK 재개, 늦은 배송+scan 경합 schedule1회, 기존 금액/이벤트/job/정책 보존, 새 정책과 기존 checkout replay 분리 |
| B09 CLOSED | `orderClosurePreservesHistoryAndPendingUnits`, `closedOrderRetriesDoNotTriggerPaymentCompensation`, `closedSandboxNewWebhookDigestsNeverCompensateSettledAttempt`: 미종결 포함 주문은 PAID, 전부 CONFIRMED 때 CLOSED1회, 기존 배송/확정 재시도 무효과·현재 조회 권한 유지, 결제 성공 재통지의 취소 호출0·모순 상태는 운영 확인 |

### 신규 비회원 브라우저 준비 경계

신규 B 비회원 검사는 별도 loopback source `127.0.0.3`에서 실제 CSRF 취득·비회원 로그인 HTTP를 수행하고 발급된 쿠키를 새 브라우저에 옮긴다. 인증을 모의로 만들어 넣거나 제한을 해제하는 구현은 아니다. 기존110개 UI 로그인 시나리오와 rate20/global240초/workers1 설정을 유지하는 구조를 확인했다. 신규 B 검사는 로그인 폼 조작 자체를 추가 검증하는 것이 아니라 실제 인증 후 B 화면/API/DB 연결을 검증한다. 이 범위를 구분하며 dev1 종료 문제의 해결/전체 통과를 주장하지 않는다.

제품·테스트·하네스는 수정하지 않았고 새 검증 DB/미디어/프로세스를 시작하지 않았다. 기존 자원과 실패 기록을 삭제하거나 수정하지 않았다. R-B01 보완 및 개발 전체 통과 후 별도 인계된 소스에서 독립 전체가 필요하다. 커밋·배포·푸시·P09-C는 진행하지 않았다.

## 개발1 시간 예산에 대한 정적 설계 판단

개발 세션 전달 수치는 완료113case 합232.330초, 신규B8 합11.471초, A개발3과 공통105case 합 차이+12.429초, 잔여 기존5case의 A실행 합15.6초다. 이는 전달받은 분석이며 여기서 실행 로그/벽시계 총시간을 독립 재측정하지 않았다. case duration 합은 준비·종료를 포함한 global elapsed와 같지 않으며 예상252초는 추정이다. 0.1~0.7초 분산 증가만으로 V39 또는 UI가 원인이라고 단정할 수 없다. global240초/개별30초/workers1, 기존110+신규8의 이름·필수 목록·검증 본문은 유지한다. 실패 실행과 미완료 case는 그대로 실패/미실행으로 기록한다.

### 허용 가능한 최소 준비 변경

**배송 전용 회원3시나리오의 중복 checkout 준비만 실제 업무 포트 fixture로 대체하는 방향은 계약을 보존할 수 있다.** 대상은 `splitShipmentQuantitiesTrackingAndExceptionsMatchSavedApiAndDatabase`, `splitConflictReadFailureAndTrackingAuthLossRequireExplicitRecovery`, `keyboardAndResponsiveShipmentFlow`의 desktop/mobile6case다. 현재 공통 `create`는 상품2개 장바구니 담기, 주문서 이동·주소 입력·확인·주문 생성까지 반복한다. 이 세 검사의 검증 대상은 그 뒤 배송 조작/복구/키보드 흐름이다. `orders-create.spec.ts`의 회원 응답 유실·비회원 실제 전화 인증·가격 충돌 및 `purchase-complete.spec.ts`의 회원/비회원 전체 구매 흐름은 그대로 두어 주문 생성 UI 연결을 유지한다. 공통 `create` 자체를 fixture로 교체하지 말고 대상 호출만 명시적으로 분리한다.

조건은 다음과 같다.

- 별도 scenario/project별 신규 주문을 실제 Quotes 및 OrderCreationService로 생성한다. 직접 SQL로 PAID/배송/확정 상태를 조작하거나 API 응답을 가짜로 반환하지 않는다. 현재 `seedConfirmationBrowserOrders`의 업무 포트 사용 방식은 참고할 수 있지만, 그 함수는 이미 결제·분할·배송 완료까지 수행하므로 그대로 재사용해서는 안 된다.
- fixture 종료는 **PENDING_PAYMENT, 배송 없음**이다. 기존 브라우저의 실제 회원 로그인, `approve`, `worker prepare`, 판매자 UI 로그인과 `sellerPage`의 키보드 메뉴 진입은 유지한다. 브라우저에서 현재 소유자 주문을 실제 조회하고 두 매대·원수량(분할5+1, 키보드1+1)·상태·배송0을 assertion으로 확인한다. 같은 사용자에게 기존 다른 주문이 있을 때 첫 주문을 임의 선택하지 않는다.
- 키보드/반응형 검사가 사용하던 긴 주소(`긴주소` 반복18 포함), 제목·수량과 수령인 데이터를 유지한다. B fixture의 짧은 주소로 교체하면 반응형 스트레스 조건이 줄어든다.
- 분할 UI2+3, 응답 유실의 동일 body/키, 운송장 입력·정정 이력, 실제 발송/예외/복귀, ordinal 전수/API/DB 대조,409 뒤503와 명시 복구, 권한 상실·추가 POST0·비공개 제거,375/768/1440 및 Space/Enter/포커스/aria assertion은 그대로 유지한다. 준비용 assertion 제거/이동은 기존·대체 근거를 작업 문서에 개별 연결한다.
- `sellerOrdersShowDifferentDeliveryStates`의 미결제→결제 후 생성대기→PREPARING UI 연결과 `guestDeliveryOnNewContext`의 실제 비회원 생성/새 브라우저 로그인/타 주문 거부는 이번 최소 변경에서 유지한다. 회원 전체구매·실제 비회원 인증 검사를 시간 절약 대상으로 확대하지 않는다.
- 새 fixture 준비 시간도 루트 하네스 안에서 실행·기록한다. 브라우저 예산 밖으로 준비를 분리하는 것이며 전체 실행비용이 사라졌다고 주장하지 않는다. 절약 시간은 아직 미측정이다. 6case로 부족하다고 즉시 더 많은 검사를 바꾸지 말고 측정 결과를 다시 판단한다.

### 제품 DB 비용 조사 근거와 경계

V39의 `order_lifecycle_matches_hold`는 lifecycle INSERT/UPDATE/DELETE **행마다** deferred `assert_order_active_holds(order_id)`를 호출한다. 함수는 해당 주문의 hold와 lifecycle을 다시 검사하며, V38/V39에는 `order_unit_lifecycle(order_id,...)`, `order_unit_hold(order_id,...)`를 선두로 하는 인덱스가 없다. lifecycle PK와 복합 UNIQUE는 item_id 선두라 order_id 필터 지원을 보장하지 않는다. 또 active hold 역탐색은 `order_hold_unit(item_id,ordinal)`로 조회하지만 PK는 hold_id 선두다. 신규 주문의 모든 단위 삽입과 확정/보류 처리에 반복 비용이 생길 수 있는 **조사 후보**이며, 현재 데이터 크기에서 병목이라는 증거는 아니다.

개발1 종료 후 새 격리 성능 조사 DB/증거 경로에서 실제 실행 규모와 더 큰 주문/단위 수를 기록하고, 위 함수 내부 SELECT의 `EXPLAIN (ANALYZE, BUFFERS)` 및 주문 생성/확정 트랜잭션 **commit을 포함한** 경과시간을 측정하는 것이 타당하다. 동일 데이터·요청에서 비교하며 세션 잠금 대기/CPU/브라우저 요청 지연과 구분한다. 검토 후보 인덱스는 lifecycle `(order_id,item_id,ordinal)`, hold `(order_id,state,id)`, membership `(item_id,ordinal,hold_id)`다. 실제 계획·반복횟수·buffer/시간 근거가 개선을 지지할 때만 새 migration으로 최소 추가하고 쓰기 비용도 확인한다. 순차 스캔 존재만으로 무조건 추가하지 않는다.

트리거 해제, 신규 OPEN 삽입 무조건 우회, deferred 의미 변경, 전역 검사를 단순 캐시하거나 검증 횟수를 줄이는 변경은 이 최적화 판단에 포함되지 않는다. DB를 직접 우회한 중복 ACTIVE 보류/역참조 불일치를 거부하는 B06 assertion을 반드시 유지해야 한다. 이번 검토에서는 쿼리 실행·DB 변경·제품 최적화를 하지 않았다.

우선순위는 R-B01의 실제 경계 RED/GREEN 보완과 위 좁은 준비 분리다. DB 비용은 측정 근거가 있을 때 별도 최소 보완한다. 변경 후 전체 소스를 다시 동결하고 개발 전체 Offline을 새 경로에서 실행하여118개 모두 정상 종료·skip/retry0 및 나머지 필수/보존 검사를 확인해야 한다. 그 후에만 독립 전체를 별도 요청으로 시작한다. 현재는 개발1 진행 중이며 소스동결/독립 하네스 금지를 유지한다.

### 후속 개발 종료 전달

위 판단 작성 직후 개발 세션에서 dev1 종료를 전달했다: 2026-10-30 21:06:40~21:18:29 KST, exit1/root failed/preservation passed; Java unit191 pass, DB295 중293 pass/2 failure/error·skip0, phone1 pass. 브라우저 global240 timeout으로113 ok·5미실행이며, 별도 `paymentWebhookRequiresProviderEvidence`의 전역 receipt0 기대가 신규 CLOSED 웹훅 검사의 정상2행 때문에 실패했다고 전달했다. 실패992파일/manifest14 보존도 전달받았다. 이 수치는 개발 세션 보고이며 독립 실행 결과가 아니다. 위의 '개발1 진행 중'은 판단 당시 상태로 보존한다.

현재 attempt receipt0과 거부 전후 전역 receipt count 불변을 함께 확인하는 방향은 기존 거부 안전성을 유지하면서 타 검사 정상행에 대한 의존을 제거할 수 있다. 단순히 기대0을2로 바꾸거나 거부 assertion을 삭제해서는 안 된다. 실제 수정 diff/RED·GREEN은 아직 검토하지 않았으며 수정 소스 인계 전 독립 하네스 대기는 계속된다.

## 보완 소스 정적 재검토 — review-focused1 진행 중

개발 세션의 집중검증용 고정 소스를 읽었다. 전체용 freeze2는 아직 미인계이며 집중검증/개발전체/독립전체의 통과를 선언하지 않는다. 이번 수정의 정적 판정은 **R-B01 구현 보완 적합, 배송 준비 변경 범위 적합, 실행 검증 대기**다. 새 정적 차단 지적은 없다.

### R-B01

`refund-red2`의 보존된 `db/build/failsafe-reports/TEST-com.example.marketplace.dbtest.PostgresJdbcIT.xml`을 직접 읽었다. tests1/failures1/errors0/skipped0이고 실패는 `valid refund reference length=41`의 `value too long for type character varying(40)`다. 40자 분기를 먼저 수행하는 검사 본문과 일치하며 컴파일 오류가 아닌 실제 경계 RED다. failure-manifest16 보존은 개발 전달 사항이다.

새 `V40__refund_reference_capacity.sql`은 outcome 열만 varchar200으로 확장한다. V38/V39의 SHA256은 development-freeze1과 각각 일치함을 직접 확인했다. `refundReferenceBoundaryPersistsAndExcludesAutomaticConfirmation`은 필수 목록에 추가되었고 실제40/41/200자에 대해 저장값 정확 일치·hold/unit REFUNDED·이력/Outbox1회·동일참조 replay 전체 snapshot 불변을 요구한다. 기존 실제 자동확정 job을 due에서 실행한 뒤 job 상태 이외 전체 snapshot 불변·확정 연결0·부모PAID를 확인한다. 201자는400 및 전체 snapshot 불변이다. 따라서 앞선 R-B01 요구에 맞는 제품 변경과 assertion이 존재한다. GREEN과 새 전체 증거가 나오기 전 실행상 해결 완료로 표시하지 않는다.

### 웹훅 순서 의존

`paymentWebhookRequiresProviderEvidence`는 거부 전 receipt 총수를 저장하고 거부 후 동일 총수와 현재 attempt receipt0을 함께 검사한다. 원 서명 누락/위조/본문 변조/오래된 서명403, 전체 paymentParticipantState 불변 및 provider GET0을 유지한다. 기존 정상 성공·중복·역전 통지 검증도 남아 있다. 타 검사의 정상 receipt 존재를 허용하면서 이 거부 요청의 부작용0을 요구하므로 정적 보완은 적합하다.

### 배송 준비 범위

`seedPendingShipmentBrowserOrders`는 승인된 shipmentsplit/shipmenteditfailure/shipmentkeyboard만 project별 생성한다. 실제 회원 세션·Quotes·OrderCreationService를 사용하고 PENDING_PAYMENT·seller2·shipment0을 검사한 뒤 끝낸다. 별도 JSON에 준비 경과시간도 남긴다. 조회용 테스트 endpoint는 정확한 `p09-b-pending-{scenario}-{project}` request key로 주문을 찾고 `own`으로 현재 소유권을 검사한다. 제품 상태를 SQL로 조작하거나 결제/분할/완료를 미리 수행하지 않는다.

`preparedShipmentOrder`는 실제 회원 UI 로그인 후 위 주문과 제품 조회 API를 사용한다. 현재 주문번호·PENDING_PAYMENT·서로 다른 seller2·예상 상품 제목·수량5+1/1+1·원 긴주소 및 배송0을 assertion으로 확인한다. 해당3case만 create 호출을 바꾸고 실제 approve/prepare·판매자 UI/키보드 진입과 기존 배송 본문을 유지한다. `shipments.spec.ts`의 diff에서도 다른2시나리오의 본문 변경은 없다. `shipments-split.spec.ts`의 분할·응답유실·운송장·예외/복귀·권한상실·API/DB·반응형 본문을 재대조했다.

`orders-create.spec.ts`, `purchase-complete.spec.ts`, 양쪽 Playwright 설정은 development-freeze1 해시와 일치한다. 공통 create의 checkout UI도 남아 있다. 따라서 허용한 준비 분리이며 UI 검증을 fixture 결과로 대체한 것은 아니다. 절약시간과118개 정상 종료는 아직 확인하지 않았다. P09-B.md의 앞선 '예정/RED 대기' 문장은 당시 기록이며 뒤 실패 이력에는 실제 RED가 기록되어 있다. 집중검증·개발전체 결과는 후속 절에서 구분해 남겨야 한다.

이번 재검토는 이 문서만 수정했다. 독립 하네스·DB 쿼리/변경·커밋·배포·푸시·C 진행은 하지 않았다. 다음 입력은 집중 결과와 전체용 freeze2이며, 독립 하네스는 개발전체 통과 후 명시 인계까지 계속 대기한다.

### 집중1 준비 assertion 오류 전달

개발 세션에서 신규 배송 준비의 recipientPhone 기대값 `01087654321`과 실제 제품 정규화 값 `+821087654321` 불일치로 desktop3case가 배송 UI 본문 전에 실패했다고 전달했다. 입력 전화번호가 조회 DTO에서도 그대로 반환된다고 가정한 검사 준비 오류다. 앞선 정적 적합 판정은 이 실행 실패를 통과로 바꾸지 않는다. 현재 실행 종료 후 E.164 기대값만 맞추고 원 실패를 보존하는 방향이 적합하다. 제품 정규화 변경이나 assertion 삭제는 필요하지 않다. 이번 총시간은 대상 배송 본문 미실행이므로 성능 개선·118case 통과 근거에서 제외한다. 수정 diff와 재실행 결과는 아직 미검토이며 독립 하네스 대기를 유지한다.

## focused2 정체에 대한 추가 측정 판단

실행 중 소스/프로세스를 변경하지 않고 `browser.txt` 말미와 실행 코드만 읽었다. 마지막 완료114는 mobile `sellerOrdersShowDifferentDeliveryStates`(4.5초), 직전 분할2case는3.7/2.1초다. 다음 선언 순서는 `guestDeliveryOnNewContext`이므로 **전체 예산 부족과 비회원 준비/로그인의 국소 대기를 아직 구분할 수 없다.** focused2 전체 종료/최종 보고 전에는 timeout 확정이나 개선 통과를 선언하지 않는다. 이전 source 제한 문제가 있었다는 이유로 이번 원인도 같다고 단정하지 않는다.

### 먼저 확보할 증거

1. 종료 후 원 browser 로그·JUnit 실패·존재하는 JSON을 보존하고, 마지막 완료/다음 case 시작·프로세스 시작/종료 시각을 연결한다. 목록 로그는 완료 때만 출력하므로114 뒤 무출력은 정지 위치의 증거가 아니다. 다음 case가 실제 시작했는지부터 확인한다.
2. 새 격리 진단 실행에서 `guestDeliveryOnNewContext`의 첫 create, 첫 approve, 다른 구매자의 create/approve, 새 context의 GET404, 실제 GAS 로그인 POST, 이후 배송 조회/로그아웃을 단계별로 계측한다. 요청 시작/종료·경로 종류·status·elapsed만 저장하고 전화번호/쿠키/인증코드/본문/토큰은 기록하지 않는다. 실제 로그인 응답429/401/200과 단계 체류 시간을 확인한다. 종료한 해당 새 DB의 source별 rate counter 및 해당 합성 주문의 GAS 존재를 읽기 전용으로 대조한다. 성공할 때까지 재실행하거나 제한을 reset하지 않는다.
3. 동일 소스/동일 표본의 진단 한 실행에서 case 시작/종료, context/page 생성·종료, Vite 준비 완료와 runner 종료의 단조시계 시각을 기록한다. `PostgresJdbcIT`의240초는 Playwright child 시작부터 종료까지이며 Vite 시작·테스트 수집·브라우저 준비·보고서/종료까지 포함한다. Java의 주문 seed는 child 시작 전이다. case duration 합만으로 예산 초과 원인이나 절약분을 계산하지 않는다. 기존 list 로그의 ms/s 단위를 모두 처리하고 JSON이 있으면 원밀리초를 우선한다.

### 구체 비용 후보와 최소 변경 경계

| 후보/소스 근거 | 측정 | 측정 후 허용 가능한 최소 방향 |
| --- | --- | --- |
| `guestDeliveryOnNewContext`는 실제 guest checkout을 서로 다른2 context에서 수행하고 새 GAS UI로그인을 수행 | 위 단계 시간·실제 HTTP status·에뮬레이터 코드 조회/로그인 시간을 분리 | 오류/불필요 대기의 실제 원인이 있으면 그 원인을 수정. 기존 guest UI/격리 assertion은 유지하며 추가 fixture 대체를 이번 판단으로 허용하지 않음 |
| 매번 `page.goto`로 문서를 새로 로드하고 Vite가 React/모듈을 제공 | navigation document/JS 요청 수·시간, API 대기와 main-thread/actionability 시간을 구분; 대표 빠른/느린 case를 같은 실행에서 비교 | 실제 중복 탐색이 입증되고 탐색 자체가 검증 대상이 아닐 때만 한 중복 준비 이동 제거. SPA 링크·키보드 탐색·reload 검증을 통째로 바꾸거나 Vite를 다른 서버로 교체하지 않음 |
| `AuthProvider`의 location/pageshow/focus/visibility/BroadcastChannel로 현재 권한 재조회 | `/api/auth/me` 등 동일 시점 요청 수·취소/완료·elapsed와 UI 준비 대기를 분리 | 측정 전 debounce/cache/검사 우회 금지. 제품 중복 요청 결함이 확인되면 별도 설계 판단과 권한 세대·만료/늦은응답 회귀 assertion이 필요 |
| 회원 로그인 Argon2id(19456KiB,2 iterations)와 실제 GAS/전화 인증 | 인증 HTTP 및 서버 구간 CPU/벽시계, DB wait와 구분 | 암호 비용·세션/권한·rate 제한 완화 금지. 로그인은 현재 준비 분리 범위 밖에서 일괄 API/storageState 재사용으로 바꾸지 않음 |
| V39 deferred 행별 hold 정합성 검사, order_id 선두 인덱스 부재 | 앞 절의 새 DB EXPLAIN/BUFFERS와 commit 포함 주문/확정 시간, 데이터 행수/trigger 호출수 기록 | 실제 비용 근거가 있으면 신규 migration의 최소 인덱스 추가 검토. 제약/trigger 비활성화·검사 생략 금지 |
| proxy 및 runner 공통 오버헤드 | frontend proxy 왕복과 backend 처리시간, child 준비/종료 시간 분리 | 현재 proxy에 고정 sleep은 없고 proxyTimeout10초는 상한이다. 경로 guard·중단 응답 처리·timeout을 비용 가정만으로 변경하지 않음 |

`auth-e2e` 검색에서 일반적인 `waitForTimeout`/`networkidle`/공통 beforeEach 고정 sleep은 발견하지 못했다. `expect.poll`은 profile/address/cart/image/권한 반영 assertion이므로 통째 삭제할 대상이 아니다. locator auto-wait를 `force`/직접 DOM 조작으로 우회하지 않는다. 또한 기본 개별 timeout은30초지만 기존 `catalog.spec.ts`에는60초 override가 이미 있다. 앞 문서의 '개별30초 유지'는 기본 설정을 뜻하며 이 기존 예외를 새 변경으로 오인하거나 조정하지 않는다.

진단 계측은 현재 실행 종료 뒤 별도 새 경로/새 자원으로 수행하고 기존 결과를 덮지 않는다. trace를 쓸 경우 대표 case만 별도 진단 실행에서 수집하고, 계측 오버헤드를 정상 성능 기준과 구분한다. 전체 하네스 반복을 원인 분석 대신 사용하지 않는다. 지금은 추가 checkout fixture 확대나 제품 코드 변경의 근거가 부족하다. 우선115번째 후보의 실제 대기 위치와240초의 비본문 비용을 밝혀 그 결과로 최소 변경을 결정한다. 독립 전체/제품 수정 금지는 유지한다.

## 진단1 중간 결과와 B 모듈 로딩 경계 판단

개발 세션 전달 중간 실측은 child→Vite0.85초/첫case1.67초, desktop guest 첫/다른 create1260/1243ms, approve각79ms, context각2ms/page100ms, GET4046ms, 전화 요청14~18ms200, GAS20035ms 및 정상 로그아웃이다. 해당 표본에는 국소 인증 지연 증거가 없다. guest context마다 문서3/JS270, shipment문서6/JS540이라는 요청 수와 동시 JS duration 합을 벽시계 소요로 동일시하지 않는다. 진단1 전체 종료·mobile 잔여 구간은 아직 미확인이다.

### 코드 근거와 가능한 최소 범위

현재 `main.tsx`가 App을 정적 import하고, `App.tsx`가 OrderDetailPage, OrderDetailPage가 OrderConfirmationPanel, Panel이 OrderConfirmationStore를 정적 import한다. Panel의 OrderQueryStore 참조는 type-only이며 Store가 추가로 참조하는 requestJson은 기존 공유 모듈이다. 따라서 B의 Panel/Store 두 모듈은 주문 상세 라우트에 진입하지 않아도 초기 모듈 그래프에 포함된다. 로컬 소스 크기는 각각3132/7203byte이며 Vite 변환 후 전송량/실제 CPU 비용과는 다르다. 전체90개 모듈이 B 때문이라는 근거는 없다.

**전체 라우트 개편 없이 구매 확정 패널 경계만 지연 로딩하는 방향은 가능하다.** OrderDetailPage의 B 정적 import를 작은 지연 로더로 대체하고, 현재와 동일하게 권한 있는 상세·결제 이력 `d && p`가 준비된 영역 안에서만 Panel을 로드한다. 기존 주문/배송/결제 이력 화면 및 라우트·인증 Provider는 그대로 둔다. 구매 확정 Store와 HTTP 계약도 그대로 유지한다. 클릭하거나 스크롤할 때까지 숨기는 방식, PAID만 로드해 CLOSED/기타 상태 표시를 없애는 방식은 허용하지 않는다. 상세 준비 후 자동으로 로드해야 한다.

구현 시 반드시 유지할 경계:

- 로딩 중에는 구매 확정 영역에 일반 진행 안내만 표시하고 선택/확정 명령은 제공하지 않는다. 주문·배송·결제 이력 전체를 Suspense fallback으로 가리지 않는다.
- 모듈 로딩 실패는 해당 영역의 오류와 명시적 화면 재시도/새로고침으로 처리한다. 전체 앱이 사라지거나 무한 spinner에 남아서는 안 된다. 실패를 빈 패널/확정 완료로 취급하거나 GET/POST를 자동 반복하지 않는다. 단순 lazy+Suspense만으로 실패 UI가 보장된다고 가정하지 말고 로더 오류 처리까지 포함한다.
- 모듈 Promise가 늦게 완료되어도 주문 변경·로그아웃으로 해제된 이전 화면을 다시 표시하지 않는다. 캐시가 필요하면 실행 모듈만 공유하고 detail/선택/키/pending/인증 상태는 공유하지 않는다. 부모의 회원/비회원+주문 key, 현재 권한 구독과 데이터 폐기 경계를 유지한다.
- 로드 완료 후 기존 Panel이 현재 auth로 GET하고 Store가 기존 세대·취소·401/403/404 처리를 수행하게 한다. wrapper가 별도 오래된 auth/detail을 캡처해 전달하거나 기존 uncertain POST 상태를 재생성/재전송하지 않도록 한다.
- 재렌더마다 컴포넌트 타입을 새로 만들어 Panel을 remount하지 않는다. 전송 중 선택/동일 키·body 유지, CLOSED 후 상위 재조회 동작을 그대로 보존한다.

### 필요한 assertion과 효과 판정

이 방향을 채택할 경우 지연 로더의 pending/success/failure를 제어하는 검사를 추가한다. pending 때 기존 상세/배송/이력은 표시되고 확정 명령0, 로딩 실패 때 해당 영역 오류와 복구 경로 유지·POST0, pending 중 로그아웃/다른 주문 이동 후 늦은 완료가 이전 사적정보·명령을 게시하지 않음을 검증한다. 정상 로드 후 기존 B 단위9개·브라우저8개의 실제 선택/재시도/권한/종결 assertion과 부모401 사적정보 폐기를 유지한다. 재시도 기능을 구현하면 그 실제 성공 경로도 검사해야 한다.

성능은 같은 대표 비주문 화면의 fresh document에서 Panel/Store 요청이 사라지는지, 주문 상세에서는 자동 로드되는지 먼저 확인한다. Vite 개발 요청 수 감소만이 아니라 navigation→준비완료 및 case/전체 wall time을 전후 비교한다. production build에서도 초기 정적 참조와 별도 chunk 경계를 확인하되 이를 브라우저 통과 대신 쓰지 않는다. 문서당 두 모듈을 늦추는 정도이므로12초 이상 절감이나240초 충족을 보장하지 않는다. 성공 기준은 실제 전체118 정상 종료이며 효과가 부족해도 범위를 모든 route로 자동 확대하지 않는다.

이는 **종료 후 최종 측정으로 선택할 수 있는 B 한정 변경안**이지 현재 제품 수정 승인/실행이 아니다. 진단 실행 중에는 소스를 유지하고, 최종 결과 인계 후 개발 세션이 채택 여부를 결정한다. 이번에는 검토 문서만 수정했고 제품·테스트·하네스·기존 자원은 변경하지 않았다. 독립 전체도 실행하지 않았다.

## B 지연 로더 정적 재검토 — 미동결 보완 소스

개발 세션 요청으로 OrderConfirmationLoader, OrderDetailPage 연결, 신규 controlled4개와 기존 B9개를 읽었다. 현재 타입 보완 중인 소스이므로 전체용 동결/최종 검토로 취급하지 않는다. **B에 한정한 구현 범위는 적합하며 확인된 새 제품 결함은 없다. 시간 문제 해결 판정은 불가하다.**

- 로더는 기존 `d && p` 안의 구매 확정 패널만 감싼다. 기존 주문·배송·결제 이력, 라우트, AuthProvider와 업무 Store는 변경하지 않는 구조다. 명시 클릭 없이 mount 후 자동 import한다.
- 모듈 범위에는 Promise만 캐시하며 detail/선택/키/권한을 저장하지 않는다. component type은 모듈 export를 사용해 부모 재렌더마다 새로 정의하지 않는다. effect cleanup의 current flag와 부모의 주문별 key/unmount로 늦은 완료를 폐기한다. module 실패 Promise도 캐시하지만 UI가 **문서 새로고침**을 안내하므로 새 document에서 초기화되는 복구 계약과 맞는다. 같은 SPA 재마운트만으로 캐시 실패를 복구한다고 주장하면 안 된다.
- pending은 status 안내·명령0, 실패는 영역 alert와 현재 URL 새로고침 링크다. 실패 시 무한 자동 import/GET/POST 재시도나 전체 화면 fallback은 없다. 성공한 모듈의 업무 실행은 기존 Panel/Store가 현재 auth로 처리한다.

`.local-tools/p09-b-execution-20261030/ui-loader2.log`를 직접 읽어 단위2파일/13검사 통과 기록을 확인했다. 이번 검토가 새로 실행한 결과는 아니다. 신규4개는 부모 화면 유지/자동 로드, 실패 격리, logout 뒤 늦은 module, 다른 주문 이동 뒤 이전 module 폐기를 검사한다. 기존9개에는 Store의 uncertain 동일 key/body 재시도와 실제 OrderDetailPage에서401 뒤 부모 사적정보 제거가 남아 있다. 필수 목록은 현재238개다. 전체 기존225개 보존은 최종 동결 목록 대조/전체 수집에서 다시 확인해야 한다.

**검사 증거의 남은 경계:** 실패 복구 단위 검사는 링크 href를 확인한 뒤 컴포넌트를 unmount하고 성공 loader를 주입해 새 mount하는 방식이다. 실제 브라우저 document reload/실패한 dynamic import 캐시 초기화를 수행한 증거는 아니다. 최종 검증 전에 새 격리 환경에서 해당 B 모듈 요청을 한 번 실패시키고 기존 상세/배송/결제 유지·영역 alert·POST0을 확인한 다음, 실패 주입을 해제하고 화면 새로고침 링크를 실제 눌러 정상 GET/명시 전 명령POST0을 확인하는 좁은 검사가 필요하다. 새로고침으로 기존 성공 POST를 자동 재전송하지 않는지도 기존 응답유실 시나리오와 구분한다. 타입 검사와 정상 production build의 chunk 분리도 현재 미확인이다. 이 항목은 제품 결함 확정이 아니라 새 실패 복구 동작의 실행 증거 공백이다.

모듈 probe는 개발 전달 기준 RED fresh login3회 각90JS/B2, GREEN89JS/B0이며 ready189/168/160ms→205/175/191ms다. 새 loader1개 때문에 순감소는1개이고, 현재 표본에서 벽시계 개선 근거가 없다. API401 stub을 사용하는 그래프 전용 probe는 업무 인증/구매확정 통과 증거가 아니다. 로딩 경계가 바뀌었다는 근거와 시간 예산 해결을 분리한다. 이 결과로 전체 하네스를 반복하거나 route 범위를 확대하지 않는 판단이 적합하다.

다음 V39 commit/EXPLAIN 측정은 기존 제약과 index를 바꾸지 않는 새 격리 DB 조사로 진행할 수 있다. 이번 검토에서는 제품·검사·DB를 변경하지 않았으며 독립 전체·커밋·배포·C도 수행하지 않았다.

### 후속 결정 — 로더 실험 미채택

개발 세션은 로더 실험의 patch·측정·실패 이력을 보존한 뒤 **로더 관련 변경만 원복**하기로 결정했다. 비B 화면 요청 순감소1개 외에 실제 시간 개선 근거가 없고 새 실패 경로와 검증 비용이 추가되므로 현재 시간 문제 해결안으로 채택하지 않는다. 원 B Panel/Store eager 연결·B단위9개·프런트필수234로 복원하고 R-B01/V40·웹훅 순서 의존·승인된 배송 준비 분리 등 다른 B 보완은 유지하는 범위다.

따라서 위 실제 chunk 실패 후 document reload 검증 공백은 **미채택 실험의 한계**로 남긴다. 로더 원복이 확인된 제품의 잔여 결함이나 필수 검증 항목으로 이월하지 않는다. 신규 기능 실패를 숨기려고 기존 필수 검사를 삭제하는 경우와 구분하며, 실험 전 필수234개와 원 연결의 정확한 해시/내용 복원 여부를 다음 소스 인계에서 확인한다. 현재는 원복 결정 전달만 받았고 이 검토에서 원복 완료·원본 해시 일치를 확인한 것은 아니다. 앞 실험 및13개 단위 기록은 당시 결과로 보존한다. DB 측정과 독립 전체 대기 상태는 유지한다.

## aggregate240 정책 판단 — 변경 실행 없이 승인안 준비

최상위 적용 지시는 사용자 AGENTS의 **“테스트 삭제·필수 목록 축소·타임아웃 확대만으로 실패를 감추지 않는다.”**다. 이 문장은 모든 시간 한도 변경을 일률 금지하는 문장과 같지 않다. 검사 수가 늘어난 전체 실행의 용량을 근거 있게 조정하면서 개별 실패·미실행을 그대로 드러내는 것은 개념상 구분 가능하다. 그러나 이번 세션은 별도로 aggregate240 유지·수정 금지를 명시했고, 현재 요청도 판단/승인안 준비만 허용한다. **따라서 현재 권한으로 한도를 변경하거나 더 긴 진단 실행을 시작하지 않는다. 이번240 유지 조건을 변경하려면 사용자에게 구체적 범위의 명시 승인을 받는 것이 맞다.** 이는 SKILL이 요구하는 승인 절차가 아니라 사용자와 세션의 현재 제한에 따른 판단이다. 실패 은폐 금지 자체를 예외로 해제해 달라고 요청하는 것은 아니다.

### 현재 근거의 충분한 부분과 부족한 부분

- 신규 필수8case가 약11.17초를 추가하고 기존 case들이 정상 완료하다 child 총시간에 도달했다는 자료는 aggregate 용량 부족 **가설과 제한된 추가 진단 승인안**을 뒷받침한다.115번째 guest의 실제 요청200 및 별도30초 정체 부재도 국소 인증 대기 가설을 약화한다.
- 아직118개 전부의 종료·report/cleanup을 포함한 완료 벽시계는 없다. 미완료 case가 정상일 것이라고 추정하여 통과 처리할 수 없다. 기존 공통 case의 분산 증가, 실행환경 변동과 누적 데이터 비용도 완전히 설명되지 않았다. 신규8개 시간만 더하면 충분하다고 단정할 수 없다.
- 새 DB473주문/1599unit에서 함수 cold5.037ms **한 번**은 트랜잭션 전체 비용을 뜻하지 않는다. deferred 행별 호출 수·commit 시간·warm 분포와 다른 요청의 DB시간이 필요하다. 이 값만으로 인덱스 변경을 정당화하거나 DB 병목을 배제하지 않는다. 진행 중인 commit 측정은 마무리하되 전체 재실행을 무기한 반복할 필요는 없다.
- 로더는 개선 근거가 없어 미채택했고 승인된 준비 중복 분리도 절감이 제한적이다. 더 큰 제품 개편이나 인증/암호/검사 약화로240에 억지로 맞출 근거는 없다.118/110 비율이나 단순 합만으로 영구 한도 값을 산정하지 않는다.

### 사용자에게 제시할 수 있는 구체적 승인안

권고는 **영구 상향을 먼저 확정하는 대신, 동결 소스의 제한된 전체 진단1회에 한해 브라우저 child 총 한도를300초로 허용**하는 것이다.300초는 실측 완료시간에서 계산된 SLA가 아니라 현재240초에60초의 제한된 관찰 여유를 더하는 진단 상한이다. 다음 조건을 함께 제시한다.

1. 정확한 변경은 `PostgresJdbcIT`의 browser child 전체 대기240→300 한 곳이다. 개별 기본30초와 기존 catalog60초 예외, workers1/retries0,118개 필수 이름·모든 assertion, 암호/권한/rate 제한은 유지한다. 다른 하네스 timeout은 바꾸지 않는다. 승인 전에는 코드 변경하지 않는다.
2. 새 증거·DB·미디어에서 같은 전체 Offline을1회 실행한다. 원240초 기준의 초과 여부/초과량도 별도로 기록해 원 성능 예산 미충족을 숨기지 않는다. 과거 failed와 미실행5/4case 기록을 통과로 고치지 않는다.300초 실행이 실패하면 한도를 다시 늘리거나 승인 없는 재시도를 하지 않고 원인을 보고한다.
3.118개 전부의 결과와 개별 시간, child 시작→종료의 실제 벽시계, 준비/보고/종료 비용, Java·프런트·Node·보존 결과를 수집한다. timeout 이외 assertion 실패가 발견되면 기존처럼 실패로 처리한다. 현재 미완료 검사가 정상임을 전제하지 않는다.
4. 결과는 '승인된300초 진단 조건의 결과'로 표시한다. 이것만으로240초 계약 통과 또는 B 완료를 선언하지 않는다. 완료시간과 병목 근거를 검토한 뒤240 유지/원인 수정/근거 있는 영구 aggregate 변경 중 하나를 사용자와 확정한다. 독립 전체는 그 최종 계약과 동일한 소스/설정이 인계된 후 별도로 실행한다.

사용자 질문 문안: “기존110개에 필수8개가 추가되어, 현재는 개별 검사가 정상 진행되는 중 전체240초 한도에서 중단됩니다. 미완료 검사의 실제 결과와 총 소요시간을 확인하기 위해 새 격리 환경의 전체 검증1회만 브라우저 총 한도를300초로 허용할까요? 개별 제한·필수 검사·권한·암호 설정은 유지하고240초 초과와 기존 실패도 그대로 기록합니다. 이 실행만으로 완료 처리하거나 영구 한도를 바꾸지는 않습니다.”

이는 승인안 제시를 위한 검토 기록이며 사용자에게 도구로 승인 질문을 발송하거나 승인받은 상태가 아니다. 현재240 설정·제품 수정 금지·독립 전체 대기를 유지한다.

### 후속 commit 측정 반영

`.local-tools/p09-b-db-cost1-20261030/result.json`을 직접 읽었다. 새 복제 DB에서473주문/1599unit 기준 실제6unit Orders.save commit20표본 median15.45/p95 18.05ms, 실제 auto commit10표본12.55/21.36ms, 함수 왕복50표본0.644/0.746ms다. 실제 save200건을 추가한 뒤698주문/2949unit에서는 각각13.64/15.61ms,10.55/12.99ms,0.608/0.690ms다. JSON에는 indexesAdded0/constraintsChanged0과 HTTP checkout/session 준비 제외가 명시되어 있다. auto의 checked job 수와 실제 효과 commit 표본 수는 구분한다.

이 표본은 V39 관련 실제 commit이 수초의 병목이거나 해당 규모 증가로 악화되었다는 가설을 뒷받침하지 않는다. 따라서 timeout 해결 명목의 index/제약 변경은 정당화되지 않는다. 다만 제한된 표본·warmup 영향이 있으며 HTTP 인증/checkout 전체 지연을 배제하는 결과도 아니다. 현재 근거 없는 제품 최적화를 중단하고 **사용자에게 제한된300초 진단1회 승인안을 제시할 근거는 강화되었다.** 영구 상향의 충분한 근거 또는118개 통과 증거는 여전히 아니며, 사용자 승인 전240 유지와 독립 전체 대기는 바뀌지 않는다.

## 정책 판단 정정 — 사용자 제한과 내부 진단 원칙 구분

개발 세션의 지시 출처 정정을 수용한다. 사용자가 직접 정한 것은 **“타임아웃 확대만으로 실패를 감추지 않는다”**이며240이라는 수치를 영구 고정한 사용자 지시는 없다.240 유지는 개발/검토 중 적용한 보수적 진단 원칙이었다. 앞 절에서 이를 사용자와 세션의 명시 제한으로 묶어 사용자 승인이 필요하다고 판정한 것은 출처를 잘못 구분한 판단이다. **앞의 승인 필수·300초 단발 승인 권고는 철회한다.** 과거 판단은 이력으로 남기되 현재 정책 판단은 이 절을 따른다. 내부 진단 원칙 변경만을 이유로 불필요한 사용자 승인 절차를 만들지 않는다.

### 현재 최종 판단

기존110개에서 필수118개로 증가한 범위에 맞춰 **브라우저 child 총 예산만240→260초로 조정하는 것은 기존 작업 권한 내의 구현 선택이며, 아래 조건하에서는 실패 은폐와 구분된다.** 새 기능8개가 실제 시간을 추가한다는 측정, 기존 실제 결함의 RED/보완, 주문 준비 중복의 좁은 분리, 인증 국소 지연 조사, 효과 없는 loader 원복, DB commit 비용 측정이 선행되었다. 따라서 한도 확대만으로 원인 조사를 대신하는 상황과 다르다. 이 검토는 시간값을 변경하지 않으며 개발 담당자가 최종 소스/증거를 정리하여 적용한다.

- 산정식 `240 × 118 / 110 = 257.45…초`, 반올림 상향260초는 검사 수 증가에 따른 **운영상 예산 산정 기준**이다. case 비용이 동일하다는 증거·260초 충분성 보장·성능 SLA는 아니다. 이미 측정한 신규8개 약11초와 전체 정상 진행 상황을 함께 근거로 남긴다.
- 변경은 child 전체 대기 한 곳으로 제한한다. 기본 개별30초와 기존 catalog60초 예외·workers1·retries0·필수118개 이름/본문을 유지한다. Java/DB/프런트/Node 필수 목록과 실패·skip·미수집 거부, 보존 검사를 완화하지 않는다. 인증·암호·rate 및 제품 DB 제약도 그대로 둔다.
- 기존240초 실행은 실패/미완료로 영구 보존한다. 새 실행의 실제 child 벽시계와240초 초과 여부를 명시하고 **개별 요청 성능이 개선되었다고 주장하지 않는다.** 총 실행 예산 변경과 제품 성능 개선을 구분한다.
- 현재 보완의 RED→GREEN 및 원복 정확성·필수 보존을 확인하고 수정 소스를 새로 동결한다. 새 전체 개발 Offline과 별도 독립 Offline을 **동일 소스·동일260초 계약**으로 수행한다.118개 전부 및 모든 필수 gate의 실제 통과가 있어야 완료를 판단한다. 미완료 case를 정상이라고 추정하거나 집중검사 결과를 전체 통과로 대체하지 않는다.
- 260초에서도 실패하면 자동으로 다시 한도를 늘리거나 통과할 때까지 반복하지 않는다. 새 실패 위치·요청/본문/종료 비용을 근거로 다시 판단한다. 실제 assertion 실패는 시간 조정과 무관하게 해결해야 한다.

위 조건으로260초 조정에 별도 사용자 예외 승인은 필요하지 않다. 사용자 지시의 실패 은폐 금지는 계속 유효하다. 앞에서 제안한300초 승인 질문은 발송하지 않는다. 현재 이 검토 세션의 제품 수정 금지와 독립 실행 대기는 그대로이며, 개발 전체 통과와 수정 동결본 인계를 받은 뒤 독립 검증을 시작한다.

## 개발2 최종 동결 소스 정적 재검토

개발2는 전달 기준2026-10-30 22:15:04 KST부터 선택 없는 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-b-dev2-20261030` 실행 중이다. 입력 `development-freeze2.json`496파일의 현재 SHA256 전수 대조 **불일치0**을 직접 확인했다. 개발2 `inputs.json` 파일 SHA256은 **238FDB81F428062815D49246B716536420304B9EAC95DDEF27F7F6F7340C5FD4**로 전달값과 일치한다. HEAD는 기존 `ed96936740a6295affb30c5c6046ca3a3553513a`/`codex/p05-d`+dirty다. freeze1→2는2추가/7변경/0삭제, A→B 전체 delta는 전달 자료 기준20추가/22변경/0삭제다.

정적 판정은 **보완 범위 적합·추가 차단 지적 없음, 개발 전체와 독립 전체 결과 대기**다.

| 보완 항목 | 확인 결과 |
| --- | --- |
| R-B01 | V40의 outcome varchar200과 필수 환불 경계 검사를 유지한다. focused2의 실제 JUnit XML에서 `refundReferenceBoundaryPersistsAndExcludesAutomaticConfirmation` failure/error 없는 testcase(3.258초)를 직접 확인했다. 이는 해당 집중검사 통과이며 개발2 전체 통과가 아니다. 기존 RED는 보존한다. |
| 웹훅 순서 | 현재 attempt receipt0·전역 사전/사후 count·전체 snapshot 불변 및 원 거부/성공 assertion을 유지한다. |
| 배송3 준비 | 실제 PENDING_PAYMENT·배송0 fixture, 원 UI로그인/정확 key/두 seller/상품·수량·긴주소 및 승인/prepare/본문 유지. recipientPhone 기대는 제품 정규화와 일치하는 `+821087654321`로 수정되어 있다. |
| aggregate | browser child wait만260초이며, 실제 시작/종료/timeout의 elapsedMillis를 `browser-child-timing.ndjson`에 별도로 기록한다. 기본 개별30초·기존 catalog60초·workers1/retries0 유지.240초 초과 여부는 종료 후 실제 기록으로 판정한다. |
| 선택 진단 | `P09_B_BROWSER_DIAGNOSTICS==='1'`일 때만 request listener/파일 진단 및 추가 reporter를 활성화한다. 기본값에서는 listener/diagnostic 파일 쓰기가 없고 measured wrapper는 실제 호출·반환·예외를 그대로 전달한다. Vite ready hook도 diagnostic 함수의 동일 조건을 따른다. child 전체 시각 기록은 선택 진단과 별개로 항상 남긴다. |
| loader 원복 | OrderDetailPage·OrderConfirmationPanel·OrderConfirmationStore·원 OrderConfirmation.test.tsx의 SHA가 freeze1과 전부 일치한다. Loader 제품/검사 파일은 존재하지 않는다. 미채택 실험의 실패 복구 검증 공백을 현재 제품 잔여 항목으로 이월하지 않는다. |
| 필수 검사 | 브라우저118·프런트234 목록 SHA가 freeze1과 각각 일치한다. Java 등록421개이며 신규 refund 경계가 포함된다. 등록 수는 실제 실행 수나 통과 증명이 아니며 전체 수집/coverage gate 결과를 기다린다. |

읽은 변경 파일은 V40, PostgresJdbcIT, diagnostics.ts, auth-e2e의 playwright/vite 설정·purchase-helpers·shipments/split, required-tests다. 진단 코드는 테스트 범위이며 제품 권한/암호/DB 제약·index를 바꾸지 않는다. 실행 중 파일을 수정하지 않았고 이 검토 문서만 갱신했다. 개발2 성공 여부를 추정하지 않으며, 종료 후 통과·보존·소스 재대조 인계 전에는 독립 하네스를 시작하지 않는다. P08 EOF/P09-A를 재개하거나 커밋·배포·푸시·C로 진행하지 않는다.

### 개발2 브라우저 중간 실행 증거

`db/browser-child-timing.ndjson`과 `db/browser-report.json`을 직접 읽었다. 브라우저 child는2026-10-30 22:18:01~22:22:14 KST에 실행되어 exit0, 실제 총252477ms를 기록했다. JSON은 expected118/skipped0/unexpected0/flaky0/errors0이며 보고서 자체 duration은252051.638ms다. child 총시간은 기존240초보다 **12477ms 초과**, 새260초보다7523ms 짧다. 이는 새 총 예산에서118case가 정상 종료한 증거이며 성능 개선이나 기존240초 기준 충족을 뜻하지 않는다.

나머지 Maven/프런트/Node/보존 gate는 개발 세션 전달 시점에 진행 중이다. 이 브라우저 결과를 루트 전체 통과로 확대하지 않으며, 개발 전체 종료·소스/보존 확인 인계 전까지 독립 전체는 계속 대기한다.

## 독립 전체 실행 착수

개발2 `result.json`의 root passed/preservation passed, Java488/프런트234/브라우저118과22:15:10~22:28:05 KST 종료를 확인하고 개발 세션의 명시 인계에 따라 독립 전체를 시작했다. 첫 기본 권한 실행은 전화 에뮬레이터 준비에서 실패했다. `.local-tools/phone-emulator-ab5c39a13e6f45e98b45101e14a04f75`의 원문은 port6240 readiness timeout60000ms·설정 저장소 접근 경고/exit2이며 본 하네스 경로가 생성되기 전이었다. 경고만으로 정확한 근본 원인을 단정하지 않는다. 로그/설정/result를 보존했으며 이 시도는 검증 통과가 아니다.

기존 로컬 의존성 접근 권한으로 같은 명령 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-b-independent1-20261030`을 실행했다. 재시작 시에도 본 증거 경로는 존재하지 않음을 확인했고 기존 에뮬레이터 실패 경로를 재사용하지 않았다. MAVEN_ARGS/OPTS 및 선택 진단 환경변수를 제거했다. 제품/검사/하네스 수정은 없다.

독립 `inputs.json` SHA는 **238FDB81F428062815D49246B716536420304B9EAC95DDEF27F7F6F7340C5FD4**로 개발2/freeze2와 일치한다. 실행 중 별도 읽기 전용 대조로 B failure-manifest01~19의 고유13530파일 누락/해시불일치0을 확인하고 `prior-failures-audit.json`에 기록했다. 실제 전체 결과는 후속 마감 절에 기록하며 현재 실행 중이다.

## 독립 최종 판정 — 2026-10-30

**P09-B의 승인된 로컬·모의 범위는 구현/정적 검토/개발 전체/독립 전체 검증을 완료했다. R-B01은 해결됐으며 필수 잔여 차단 지적은 없다.** 아래 실행 증거를 기준으로 하며 앞의 진행 중/미실행/수정 필요 표기는 당시 이력으로 보존한다. 실제 Toss SANDBOX·사용자 직접 확인·실기기·상용 부하까지 완료했다는 뜻은 아니다. C 구현·커밋·배포·푸시는 수행하지 않았다.

### 독립 전체 결과와 소스

실행은 선택 없는 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-b-independent1-20261030`,2026-10-30 **22:29:39~22:42:42 KST**, 종료코드0이다. `result.json`에서 **status passed/preservation passed**를 직접 확인했다. 개발2와 동일 HEAD `ed96936740a6295affb30c5c6046ca3a3553513a`+dirty,496파일 입력 SHA **238FDB81F428062815D49246B716536420304B9EAC95DDEF27F7F6F7340C5FD4**다. 종료 후 freeze2 전수 해시 불일치0·tracked/untracked 검사 입력 추가/삭제0을 `final-review-audit.json`에 남겼다.

| 검사 | 실제 결과 | 독립 경로 내 근거 |
| --- | --- | --- |
| Java/JUnit/ArchUnit | 488통과: 기본191+PostgreSQL296+전화 제공자1, 실패/오류/skip0 | `db/maven.txt`, `db/build/surefire-reports`, `db/build/failsafe-reports`, `java-cases.json` |
| 필수 Java | 52묶음 전부 passed, 등록421/unique420(기존 중복1 유지) | `coverage.json`, `final-review-audit.json` |
| 브라우저 |118통과, unexpected/skipped/flaky/errors0 | `db/browser-report.json`, `browser-coverage.json` |
| 프런트 단위 |234통과·필수234 passed | `TEST-frontend.xml`, `test-unit.txt`, `frontend-coverage.json` |
| Node/HTTP/proxy |75통과, 실패/cancel/skip/todo0 | `test.txt` |
| 타입·린트·빌드·하네스 gate |정상 완료, 누락/실패/skip 거부 자체검사 유지 | `typecheck.txt`, `lint.txt`, `build.txt`, `gate-tests.txt`, `result.json` |
| 기존 자원 보존 |passed | `before.json`, `after.json`, `result.json` |

독립 browser child 실제 총시간은 **252205ms**(exit0), 원240초보다 **12205ms 초과**,260초보다7795ms 짧다. 보고서 내부 duration251643.711ms와 child 전체시간을 구분한다. 개발2는252477ms로 원240초보다12477ms 초과했다. 두 실행 모두 변경된260초 운영 예산에서 통과했으며,240초 충족 또는 제품 성능 개선으로 표현하지 않는다.

### B01~09 및 R-B01 최종 연결

앞 정적 매핑 표의 assertion을 동일 소스의 실제 결과와 대조했다. 아래 DB 메서드는 모두 독립 `TEST-com.example.marketplace.dbtest.PostgresJdbcIT.xml`의 failure/error/skip 없는 testcase이며 `final-review-audit.json`에도 결과를 모았다.

| 제약 | 실제 근거와 판정 |
| --- | --- |
| B01 | ConfirmationRulesTest 및 `confirmationUsesStoredMicrosecondBoundary` 통과. 원 배송+저장기간, microsecond 직전/도달 경계와 미배송 제외 확인. |
| B02 | `confirmationAndClaimHoldHaveOneWinner` 통과. 직접/자동/보류 경합의 단일 종결·중복/상충 보류 거부 확인. |
| B03 | `confirmationAuthorityAndReplay`, `confirmationAuthorityRechecksAfterActualOrderAndUnitWaits`, `confirmationGuestAuthorityAndNaturalWaits` 통과. 현재 권한·잠금 대기 후 자연만료·현재 GAS·동일 요청/내용충돌 확인. |
| B04 | `confirmationJobsRecoverAndReevaluateReleasedHolds`, `confirmationActualWorkerCrashAndStaleLeaseCannotWrite`, `confirmationLeaseAndClockAreRecheckedAfterLastWait` 통과. 실제 child ACK 종료/재개 및 stale CLAIM fencing·마지막 lease 대기·원 due 재평가·환불 제외 확인. |
| B05 | 원 B단위9개와 실제 desktop/mobile B8case 통과. 원단위2선택/4보존·최종CLOSED, 응답 유실 동일 요청,409/503 복구, 현재 권한 상실·늦은응답 폐기 확인. 미채택 loader4개를 필수/통과 수에 포함하지 않음. |
| B06 | ArchUnit과 `confirmationWritesRollbackTogether`, `confirmationDatabaseLifecycleAndDispatchConstraints` 통과. 모듈 경계, 단계별 checked/runtime 및 commit 실패 rollback, DB 직접 우회 불일치 거부 확인. |
| B07 | `claimHoldBlocksShipmentWithoutCyclicRead`, `dispatchFactsUpgradeAndReplay` 통과. 보류-first/출고-first 경합과 원 ordinal·출고 사실 replay 보존 확인. |
| B08 | `confirmationUsesPersistedPolicyVersion`, `dispatchFactsUpgradeAndReplay` 통과. 새 V37 런타임 결과의51주문/306단위, 예상269=실제 확정집합269, 추가CLOSED44·잔여PAID3, scan50+1, 기존event/job/금액/정책 보존 확인. 늦은5단위 원due 정확경계·schedule/Outbox1회·기존checkout replay 불변도 true. 상세 증거는 `db/v37-aftercare-runtime-65741831-079c-4968-8df5-c14769df759b/runtime-result.json`과 해당 폴더 impact/child 원문. |
| B09 | `orderClosurePreservesHistoryAndPendingUnits`, `closedOrderRetriesDoNotTriggerPaymentCompensation`, `closedSandboxNewWebhookDigestsNeverCompensateSettledAttempt` 통과. 전체CONFIRMED만CLOSED1회·현재 조회·합법 재시도 무효과·성공 결제 재통지 보상0 확인. |
| R-B01 | 실제 RED의41자 varchar40 실패를 보존하고 V40 적용 후 `refundReferenceBoundaryPersistsAndExcludesAutomaticConfirmation` 독립 통과.40/41/200자 정확 저장·동일참조 무효과·이력/Outbox1회·AUTO제외·201자400/전체불변을 확인하여 해결 판정. |

### 보존·실패 이력과 인계 한계

새 독립 주 DB는 `d2_it_a7296e43fc634634817c9fb26abcd6ee`이며 추가 이행/worker 검증 DB·미디어도 이번 실행의 새 경로에 생성됐다. 이 실행에서 기존 서버/DB/미디어를 재사용·삭제하지 않았다. 하네스의 보존 범위는 기존 문서대로 DB 존재·정규화 덤프 대상·컨테이너/알려진 리스너·미디어 SHA이며 모든 과거 DB 행의 전수 검증으로 확대 해석하지 않는다.

종료 후 B failure-manifest01~20의 고유 **13549파일 누락/해시불일치0**을 재확인했다(`prior-failures-final-audit.json`). 실행 중19→20으로 추가된 기록까지 포함하며 처음13530 대조 기록도 덮지 않았다. 독립 최초 에뮬레이터 준비 실패3파일은 `independent-start-failure-manifest.json`으로 별도 SHA를 남겼다. 과거240초 timeout·미실행, 실제 제품 RED, fixture/compile 준비오류, 미채택 loader 및 DB 측정 이력을 새 통과로 소급 변경하지 않는다.

C에 인계 가능한 계약은 원단위 identity/원금액, 저장된 신규·승인 legacy7일 정책, order 소유 dispatch/initial-delivery 사실, 트랜잭션 내부 hold/release/refund 포트, 원due 재평가 job·lease fence, 현재 권한과 CLOSED exact replay 경계다. **실제 취소/환불 결제 처리·혼합REFUNDED 주문의 재무종결·반품/교환 완료·제공자 대사까지 B가 구현했다는 뜻은 아니다.** EXCHANGE_COMPLETED 해제는 현재409/hold유지 계약이며 후속 설계가 필요하다. 실제 Toss 접근 차단과 사용자 직접 확인 미실행은 유지한다. 후속 작업을 시작할지의 결정은 부모/사용자에게 남기며 이번 검토에서 C를 실행하지 않는다.
