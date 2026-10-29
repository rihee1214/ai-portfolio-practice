# P08-D 독립 사전 검토

2026-10-27. P08-C 독립 완료 후 D 계획과 실제 Orders/GuestOrders/PurchaseSessions, JdbcReservations/JdbcCouponReservations, Jobs/WorkerEngine 및 기존 DB 제약을 읽은 **구현 전 정적 검토**다. D 구현·단위·DB·브라우저·전체 하네스를 실행하지 않았다. 아래는 계약/검사 차단 후보이며 D 코드 결함이나 실제 재현으로 주장하지 않는다. 기존 자원·실패 기록을 변경하지 않았고 E를 시작하지 않는다.

## R-D01 — 결과 거래 실패 후 보상 저장이 같은 rollback-only 거래에 갇히지 않아야 함 (D02~04)

inventory와 coupon의 commit은 각각 REQUIRED TransactionTemplate에 참여한다. coupon commit 성공 뒤 inventory commit이 기한초과/상태충돌로 던지면 전체 결과 거래가 rollback-only가 된다. 이를 같은 outer transaction에서 catch해 compensation/job만 저장하려 하면 그 기록도 rollback되거나 UnexpectedRollbackException으로 사라진다. 외부 승인 성공은 되돌려지지 않는다.

성공 결과 반영 전체를 rollback하고 별도 새 거래로 검증된 provider 결과와 보상 원인을 기록하는 경계가 필요하다. 결과 재전달이 새 승인 호출로 이어져서는 안 된다. assertion: 실제 두매대/복수예약에서 coupon 처리 뒤 inventory 경계초과 또는 예외→모든 로컬확정0/고아0, durable compensation1, 주문PAID0; 재시도/프로세스재시작→동일보상키1회. 실패한 거래 안의 추가 SQL로 '보상 저장 성공'을 가정하지 않는다.

## R-D02 — B의 전달 재시도 소진과 D 조회 제한은 별도 상태 (D03~04)

JdbcJobs는 5번째 RUNNING lease 만료를 다음 claim에서 job FAILED/LEASE_EXPIRED로 바꾼다. 이때 handler를 호출하지 않는다. WorkerEngine의 RETRY/예외도 job만 갱신하며 payment REVIEW_REQUIRED를 설정하는 hook이 없다. D 조회5회/15분을 B attempts나 handler finally에만 묶으면 handler 재진입 없이 소진된 UNKNOWN이 예약을 영구 점유하고 운영목록에서 누락될 수 있다.

D의 조회수·최초미확정시각·다음조회·review 원인을 durable하게 고정하고, B 실패/중단도 운영 조회·권한 있는 재대사에서 발견되게 한다. B job FAILED는 결제실패 증거가 아니다. assertion: 최대조회수/정각/반복 timeout, 실행전후5회 crash 소진, worker 미실행 기간이 한도를 넘은 사례에서 새승인0·예약자동해제0·운영노출/감사. 재대사는 같은 제공자결제 identity를 조회하며 제한을 몰래 초기화하지 않는다.

## R-D03 — 예약 포트의 반환 의미와 전체 ID를 그대로 조합해야 함 (D01~03)

현재 Orders.find는 잠금 없는 읽기이며 결제/만료 공통잠금 포트는 아직 없다. Orders.reservations는 저장한 ID 인계다. 두 예약 finish API는 요청한 일부 ID만 처리할 수 있어 D가 전체성을 보증해야 한다. inventory.expire는 아직 기한 전이거나 RESERVED가 아니면 기존 상태를 반환한다. coupon.expire는 기한 전/다른 종결상태에서409를 던진다. 두 모듈의 만료 반환을 동일한 성공 플래그로 취급해서는 안 된다. coupon 없는 주문은 빈 목록 호출(400)을 건너뛰어야 한다.

order 공통잠금 아래 서버저장 전체 inventory/coupon IDs를 snapshot의 item/선택coupon과 대조하고, 반환 상태가 모든 구성원에 대해 원하는 결과인지 검사한 후 order를 바꾼다. 기존예약ID를 HTTP에서 받아 확정하지 않는다. assertion: 일부/중복/다른checkout/누락 인계, 두매대 중1개 기한경계, coupon없음, 0원과 모든예약 확정1회. Clock은 inventory의 DB clock_timestamp와 coupon Clock이 별개이므로 단일 도메인 Clock만 진전한 테스트를 실제 경계 검사로 잘못 기록하지 않는다.

## R-D04 — 외부효과와 local commit 사이 crash를 실제로 재현 (D03~04)

WorkerEngine의 leaseAlive는 ack/fail만 막는다. 오래된 handler의 실행 또는 provider 부작용 자체를 중단하지 않는다. Jobs.complete의 token fence만으로 결제결과의 stale write가 막히지 않는다. provider 승인/보상은 저장된 attempt/cause 기반 멱등키, local 결과는 공통 order 잠금·attempt 귀속·현재 상태 확인이 필요하다.

PROCESSING commit 직후 provider 호출전 kill, provider 승인효과 직후 local결과 commit전 kill, 보상효과 직후 ack전 kill을 구분한다. 재시작 mock도 이전 provider 효과를 조회할 수 있도록 durable해야 한다. 인메모리 mock 초기화 후 호출회수1은 재시작 증거가 아니다. assertion: 자식 프로세스 실제 강제종료/재시작, 제공자 승인/취소 각각효과1, 새attempt/키0, stale worker 늦은결과가 최신상태 회귀시키지 않음. 확정실패·해제 이후 늦은 SUCCEEDED를 단순 terminal-ignore로 버리지 않고 보상으로 다룬다.

## R-D05 — 현재 인증과 이미 발생한 제공자 결과 처리 권한을 분리 (D02/D05)

C의 CurrentMemberFilter만으로는 대기 중 logout/reset을 막지 못해 PurchaseSessions를 추가했다. D 승인 및 고객조회도 서버세션 authVersion/귀속/expiry를 잠금 대기 후 검사해야 한다. guest의 consumed verification을 새로 소비하는 방식은 성공주문 결제를 거부한다. GuestOrders.authorizeRetry는 특정 browser/attempt 정책이므로 새브라우저 조회세션 권한과 혼동하지 않는다. 어떤 공개 guest 소유권 포트를 쓰는지 먼저 정한다.

반면 이미 provider 호출이 시작된 뒤 계정정지·세션삭제가 발생해도 worker는 결과 조회·보상·정산을 마쳐야 한다. 사용자 active BUYER 검사 실패를 provider failure로 바꾸거나 결과 저장을 영구 중단하면 안 된다. account→session 잠금 순서와 order/attempt/예약 잠금을 명시하고, 결과 handler에서 불필요하게 고객세션 잠금을 재획득하지 않는다. assertion: 승인 rowwait 중 logout/reset→401·제공자호출0; 승인효과 후 logout/정지→고객 신규승인 거부지만 worker 결과/보상 수렴; 다른회원/guest/session 재사용·클라이언트금액/mode 변경 거부.

## R-D06 — 만료 job 완료 처리와 durable 후속 조회 공백 (D02~04)

C는 주문당 하나의 ORDER_EXPIRY job을 동일 Outbox 거래로 등록했다. 만료 handler가 PROCESSING/UNKNOWN을 보고 COMPLETE를 반환하면 해당 expiry job은 다시 실행되지 않는다. 이 순간부터 보류된 예약의 후속 조회/확정실패해제/운영확인 책임을 다른 durable job 또는 영속 대사계약이 반드시 인계해야 한다. 단순 RETRY만 반환하면 B의 5회 제한 후 R-D02 문제가 된다. Jobs.enqueue는 dedupe key뿐 아니라 scheduled_at을 포함한 Command 전체 동일성을 검사하므로 같은 키에 새 now를 넣어 재등록하면 JOB_KEY_CONFLICT다.

assertion: deadline 시 PROCESSING/UNKNOWN→expiry 완료/보류 정책에 따라 후속job 존재, 프로세스 재시작 후 조회지속, 확정실패 때 전체해제1, 확정성공이 기한후면 전체확정0/보상1, 계속불명확이면 REVIEW_REQUIRED·예약유지. 중복스케줄링은 저장된 다음시각/세대키를 사용하여 중복효과·무한job 생성을 막는다.

## 최종 검증 예정

D01~05 각각 위 assertion을 실제 테스트 메서드/브라우저 시나리오와 연결한다. 모의 결과 설정은 fixture 전용이며 공개 운영 API에서 지정할 수 없어야 한다. 운영 재대사에는 서버권한·CSRF·이유/대상/결과 감사가 필요하고 단순 ADMIN 여부만으로 기존 세부권한 모델을 우회하지 않는다. 순수 금액0 경로는 실제 저장된0원 주문과 같은 예약/이력/상태 계약을 사용하고 외부호출0을 측정한다.

개발 전체 통과·소스동결 뒤 별도 새 증거 경로의 독립 ./verify.ps1 -Offline 실행과 입력/실패/DB/미디어 보존대조가 끝나기 전 D 완료를 판정하지 않는다. 현재 모든 D 실행결과는 미검증이다.

## D 동결 구현 정적 검토 및 필수 assertion 연결 — 2026-10-28

아래는 preflight 지적 후 실제 구현/검사 대조다. 새 독립 전체 실행은 진행 중이며 이 항목 자체가 실행 통과를 뜻하지 않는다.

| 제약 | 실제 필수 검사 | 검증하는 결과 |
|---|---|---|
| D01 | PaymentRulesTest 2개, zeroPaymentCommitsAllOnce, paymentZeroCrashRecoveryNeverCallsAnyProviderOperation, paymentZeroInitialDeadlineNeverSchedulesCompensation | UNKNOWN≠실패, 조회5회/900초 정각, 실제 두매대0원 전체확정1, provider4종 호출0, COUPON/INVENTORY precommit kill 전체rollback, PREPARED postcommit kill 재시작 동일PAID1, PREPARE→기한정각 rollback/보상0 |
| D02 | paymentApprovalAndExpiryHaveOneOutcome, paymentApprovalExpiryContendOnTheSameOrderLock, paymentRejectsMislinkedOrIncompleteReservationHandoffs | 실제 order row 두waiter 관측·승인/만료 양선점, PROCESSING 해제0, 서버전체ID·item/sku/qty·선택coupon/member/deadline 대조, 기존FK도 보존 |
| D03 | paymentUnknownAndLateSuccessConverge, paymentUnknownHoldsReservationsAndExhaustedJobsAreVisible, paymentRepeatedWorkerCrashExhaustionNeedsReviewWithoutRelease, paymentReviewRequiresAuthorizedReconcile | 응답유실조회·늦은성공/이미해제후성공 보상, 보상유실/거절/계속불명확, 조회한도/시간한도/실제5crash 소진운영노출, SYSTEM권한+감사 재대사, 불명확예약유지 |
| D04 | paymentResultRollbackAndDuplicateEvents, paymentWorkerRestartKeepsEffects, paymentApprovalProcessRecoveryBeforeAndAfterProvider | 5결과checkpoint×checked/runtime 전체rollback, coupon완료후실제inventory기한거부 시 별도거래보상, 중복/역순상태회귀0, 승인전/승인효과후·조회효과후·취소효과후 실제childkill/restart 효과1 |
| D05 | paymentHttpWaitRechecksSessionAndWorkerSurvivesSuspension, paymentHttpRejectsOwnershipAmountModeAndMissingCsrf, paymentHttpOwnershipAndTampering, paymentMockFixtureIsNotProductionApi, PaymentStore unit5, payments-mock 및 기존orders-create desktop/mobile | 고객 현재세션·소유권/CSRF/금액mode변조거부, 정지후worker결과수렴, fixture운영노출0, URL진입조회만/UNKNOWN새승인0/401자동재승인0, 승인전CSRF실패·POST응답유실 후 서버NONE확인과 수동재시도 |

R-D01: 일반 유상 결과apply 거래의 예약예외는 그 거래 밖에서 catch하고 새 compensation 거래를 연다. 전체rollback 후 보상1을 검사한다. R-D02/06: D round/query_count/round_started_at/next_job을 저장하고 소진job FAILED도 고객/운영 조회에서 REVIEW_REQUIRED로 노출한다. 운영 재대사만 권한/감사하에 새 round로 진행하며 불명확을 실패/만료로 바꾸지 않는다. R-D03: Orders 공통잠금과 inspect 포트로 전체구성원을 확인하고 coupon없는 주문의 빈호출을 건너뛴다. R-D04: durable mock 및 실제 독립 프로세스 종료/재시작을 사용하고 provider를 거래 밖에서 호출한다. R-D05: 사용자승인/조회는 account·guest→session→order 잠금과 현재세션 재검증을 사용하며 worker settlement는 고객의 이후 정지로 막히지 않는다.

### R-D07 및 UI 후속 결함의 실제 보완

초기0원 구현은 준비거래 종료 후 조회/보상 경로에 빠질 수 있었다. 준비·모든예약·PAID·payment SUCCEEDED·감사·Outbox를 하나의 거래로 변경했다. 이후 PREPARE→주문기한정각 분기가 외부승인 없는 보상을 만들던 실제 RED도 보완해 amount0이면409로 전체rollback한다. 정상 committed zero는 SUCCEEDED/PAID뿐이며 예정query는 provider 전에 종료된다. 실제 precommit/postcommit kill과 provider4종 호출금지 marker로 검증한다.

게스트 payment GET/approve가 인증필터에서 차단되던 실제 browser 실패를 보존하고 정확한 method/path 두개만 permitAll로 허용했다. CSRF/Orders.authorize/consumed attempt browser귀속/EXISTING token/현재session은 유지한다. owner무CSRF403, clientamount/mode400, foreignGET/CSRFPOST401, anonadminreview/reconcile401, owner실제결제가 desktop/mobile guest 흐름에 포함된다.

UI 승인 latch가 CSRF조회 실패에도 고정돼 활성 버튼이 no-op이던 지적은 실제POST직전 latch 설정 및 검증된 GET NONE/PENDING에서 수동재시도 허용으로 수정했다. 자동POST0·UNKNOWN/401 재승인차단을 유지한다. 단위 csrf/response 두RED 및 실제browser CSRFabort→POST0→서버NONE→수동승인1의 회귀를 유지한다.

개발 dev1은 Java407 통과 후 browser79/1에서 행사제목 중복으로 strict locator 실패했다. 행사ID href를 포함한 article→정확제목으로 locator를 정밀화했으며 목록20/페이지/nooverlap/상세URL·제목·store assertion을 유지한다. 실패를 검사삭제/timeout증대로 숨기지 않았다. 개발 dev2와 이번 독립 실행만 최종 결과 후보이며 앞선 실패는 이력으로 보존한다.

## 독립 전체 최종 판정 — 2026-10-28

**P08-D 범위 통과, 필수 미해결 차단 없음.** D01~05 및 R-D01~07, 게스트 결제 경로·UI 승인 재시도 지적은 위 실제 assertion과 이번 새 전체 실행으로 해소했다. E/P09·운영PG·실제과금/환불 완료를 뜻하지 않는다.

- 루트 명령 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-d-review1-20261028`, MAVEN_ARGS unset, 선택실행 없음, 종료0. 2026-10-28 16:44:04~16:50:48 KST.
- result.status=passed, preservation=passed. Java407(단위173+DB234), desktop/mobile browser80, frontend unit175, Node73. coverage45그룹 전부passed, 필수 누락·실패·error·skip0. typecheck/lint/build 모두통과.
- HEAD `460aa8bb0bb22f5138849eb00c050abd18f6fb53` 및 미커밋 동결소스. 개발dev2/독립review1 inputs428파일 완전동일 SHA256 `EBADA9D50642EFAB23E9F0A368B815A92D5E55678D0E702A34EEBCBD7170EE10`. 실행전후 현재소스 hash불일치0.
- 새 DB `d2_it_3ca14632770e4e04942b9d21e5356276`, 미디어 `.local-tools/p08-d-review1-20261028/db/media`, 모의전화 `.local-tools/phone-emulator-b099b0a7acb04c0ab5424030c1f04645`. 기존 DB·서버·미디어/검증 DB 보존, 삭제 없음.
- 과거 전체 failure manifest24개 union36552파일의 실행 전후 해시불일치0, manifest 자체변경0. D 재개 당시 P05까지 포함해 확장한 기존22manifest union29350을 기준으로 D 실패를 추가했다. C 독립검토 당시 선택manifest28847은 그 당시 결과로 유지하며 이번 확대집계로 소급 수정하지 않는다. 이번 before/after 감사 원본은 `.local-tools/p08-d-review1-audit-20261028/`에 있다.
- `docs/work-items/evidence/P08-D-review-final-*` 사본24개. result/inputs/coverage3종/환경before-after/Java·frontend·Node 로그/DB summary-target/browser·phone 결과/소스·실패대조/summary/실제프로세스43개 marker·PID·완료파일 해시기록을 포함한다. 원본 상세로그와 process 디렉터리를 그대로 보존했다.

실패 이력: 초기 컴파일/fixture slug·member복합FK 오류, 기존 required범위가 선택실행에 미포함된 결과, 0원정각 실제RED, guest 결제 route browser 실패, UI latch 실제RED, dev1 행사중복제목 strict locator 실패를 보존한다. dev2/독립review1 통과를 과거 실패 결과에 덮어쓰지 않았다.

한계: 로컬 DEMO 제공자와 모의전화 검증이다. 실제 PG/실제 환불/운영배포는 미검증이며 E/P09를 시작하지 않았다. 프로세스 중단 검사는 실제 별도 JVM을 강제종료/재시작하지만 test classpath 기반이며 패키징 배포 smoke를 뜻하지 않는다. UNKNOWN 조회/작업한도 소진은 권한있는 고객/운영 조회에서 REVIEW_REQUIRED로 노출되며 운영 모니터링 배포 완료를 주장하지 않는다. 게스트 결제는 기존 구매 browser·consumed attempt와 현재session 정책을 사용하며 새browser 결제권한을 도입하지 않았다. 커밋·푸시·배포 없음.
