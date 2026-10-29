# P08-G 독립 검토

## 사전 검토 — 2026-10-28, 미실행

G 개발 중 읽기 전용 검토다. README, 개발 흐름, 검증 하네스, P08/G 계약 및 F2 최종 기록과 실제 테스트 전용 로그인/배송 worker 연결을 대조했다. G 신규 검사·개발 전체·독립 전체는 아직 실행하지 않았으며 완료 판정이 아니다. 제품/검사 소스는 수정하지 않았다.

| ID | 구체적 인수 근거 / 필요한 assertion | 현재 판정 |
|---|---|---|
| R-G01 실제 단계 연결 | C 공개 생성→D 승인→실제 PAYMENT_SETTLED job→B WorkerEngine→F1 BASIC 생성→판매자 공개 발송→생성된 progress job→F/F2 조회를 같은 order/sellerOrder/item ID로 대조. fixture의 핵심 주문/결제/배송 상태 직접 INSERT/UPDATE로 대체하지 않는다. worker 제어는 실제 producer job을 선택·실행하고 ack/effect를 확인한다. | 기존 ShipmentBrowserFixture 재사용 구조 적합. G 실제 assertion 대기 |
| R-G02 응답 유실과 합계 | 주문 응답 유실 후 동일 키/내용·동일 주문, 승인 응답 유실 후 승인 효과/성공이력1회. 부모/매대/원단위 합계·예약 수량/확정·배송 수량이 동일 원본 주문으로 연결돼야 한다. 화면 합계만으로 대체하지 않는다. | G assertion 대기 |
| R-G03 실패 및 UNKNOWN | UNKNOWN의 예약 보유/자동 재결제0, 실패 전체 해제, 늦은 성공 보상 및 미출고, 0원 외부 호출0을 기존 필수 D 실제 DB/중단 검사와 G 흐름에 명시 매핑. 직접 fixture 상태 변경을 실제 provider 조정 결과로 주장하지 않는다. | 기존 증거 유지, G 매핑 대기 |
| R-G04 인증 출처 격리 | guest-login-source.ts는 실제 TCP localAddress .3/.4, 목적지127.0.0.1 및 유효 port 제한, 원본 cookie/CSRF/body 전달, 실제 서버 응답·Set-Cookie 반환, request/response error 전파를 사용한다. forwarded header 신뢰/제품 quota 변경/DB 초기화는 없다. 새 G 로그인은 실제 비밀번호와 GAS 발급 후 새 context 조회로 입증하고 타 묶음/오인증 거부를 유지한다. | 정적 적합, 실제 G 실행 대기 |
| R-G05 보존과 전체 판정 | 모든 이전 필수 이름 유지, 개발/독립 입력 SHA 동일 및 현재 전후 소스 동일. 현재 실패30 manifest/60795파일에 새 G 실패를 추가해 중복 제거 전후 해시 검증. 기존 DB/미디어 보존은 행 전체 덤프 동일성 주장과 구분한다. | G 실행 후 판정 |
| R-G06 외부 및 후속 경계 | E 실제 SANDBOX 키·카드 webhook 제공 계약 미검증은 G 로컬 통과로 해소하지 않는다. G05/P08 전체 미완료를 유지한다. P09는 수량/배분/version/event key/잠금/보상·UNKNOWN/restore 신뢰조건 인계 문서만 작성한다. | 계속 외부 차단 |

새 출처 .4는 별도 실제 loopback 테스트 클라이언트이며 배포 프록시/실제 외부 IP 동작을 검증하는 근거는 아니다. 동일 전화번호 NEW/EXISTING 분리의 기존 실제 DB 필수 검사도 유지해야 하며 다른 전화번호의 신규 브라우저 시나리오로 이 근거를 대체하지 않는다. 과거 F2 전화 fetch 실패 원인은 미확정이며 최근 통과로 원인 해결을 소급 주장하지 않는다.

## 후보 소스 정적 검토 — focused 실행 중

purchase-complete.spec.ts 4건과 purchase-helpers.ts, guest-login-source.ts, PostgresJdbcIT의 실제 fixture/worker/합계 검사를 읽었다. 추가 필수 코드 차단은 발견하지 않았다. 실행 결과 판정은 개발 전체 및 독립 전체 이후에 한다.

- G01: memberMultiStorePurchase는 실제 UI 생성의 첫 응답을 서버 저장 후 유실시키고 doubleclick1회·재시도 body 동일·번호 동일을 확인한다. 승인 재요청 후 예약2매대/쿠폰2 COMMITTED, provider approval_calls/effects 및 성공이력1, 주문목록1을 검사한다. producer PAYMENT_SETTLED를 선택한 실제 JdbcJobs/WorkerEngine이 SUCCEEDED로 소비한 뒤 판매자 공개 발송과 서로 다른 PREPARING/SHIPPED를 DB/API/UI에서 비교한다.
- G01 추가 DB: purchaseTotalsAndReservationsConverge는 수량3/2 주문의 단위 항등식·항목/매대/부모/저장금액55800, SKU 실재고 차감, 승인효과/성공이력1, 원 sellerOrder/item IDs 및 수량의 BASIC shipment 생성, prepare 재호출 동일결과를 검사한다. paymentFixture는 C OrderCreationService를 통해 실제 주문/예약을 만들며 직접 핵심 주문행 삽입이 아니다.
- G02: 실제 전화 emulator 인증·직접 비밀번호·주문/승인 후 새 browser context에서 미인증404, 잘못된 비밀번호401, 실제 GAS 로그인200과 자기 주문/배송 조회를 검사한다. 공개행사의 회원/비회원 견적 동일, 비회원 쿠폰401, 타인404/no-store/개인정보 미포함, GAS로 구매 approve401이 연결된다. .4는 원본 cookie/CSRF로 실제 Java 로그인을 실행한다.
- G03: UNKNOWN 예약보유/재승인 효과0추가, LOST_RESPONSE 재조회 후 확정 및 효과/이력1, 실패 전체해제, 0원 mock 호출/효과0·성공이력1을 DB로 확인한다. 이 4건의 시작 주문은 실제 C 서비스 fixture이고 브라우저 생성 자체는 G01/G02 증거다. 늦은 성공 보상/실제 중단복구 및 동일전화 NEW/EXISTING은 선행 필수 검사를 유지하여 문서에 정확한 이름으로 연결해야 한다.
- G04: 두 판매자의 공개 발송/진행 job으로 DELIVERED/IN_TRANSIT을 만들고 원항목ID, API/DB 이벤트/시각·새로고침을 대조한다. 상품명/가격·행사중지·쿠폰명/중지의 공개 수정 뒤 전체 주문 detail 동일, 타 sellerOrder404 및 seller DTO exact keys를 검사한다.
- 신규 purchase database endpoint는 test profile의 기존 own 권한 검사 후 SELECT만 수행한다. worker endpoint는 실제 이미 생성된 job의 예정시각으로 내부 실행기를 구동하며 주문/결제/배송 핵심 상태를 직접 UPDATE하지 않는다. 이 테스트 제어는 공개 제공자 배송 API 증거가 아니다.

G05 실제 SANDBOX/카드 webhook 제공 계약은 계속 외부 차단이다. 현재 후보 검사는 이를 해소하지 않으며 G 로컬 범위와 P08 전체 판정을 구분한다.

## 전화 요청 출처 격리 보완 — 정적 검토

focused3에서 G 자체8건 통과와 별개로 기존 F2 mobile guest의 source .1 전화 전송 quota 실패가 보고됐다. 새 helper는 G 전화 요청도 실제 .4 TCP 출처로 격리한다. private isolateSource의 경로는 두 상수 POST /api/guest/access/login 및 POST /api/guest/verifications로 제한되며 원 요청 hostname127.0.0.1/path/method와 Java port를 검증한다. 고정 loopback으로 원 cookie/CSRF/body를 보내고 실제 status/headers/body/Set-Cookie를 돌려주며 request/response error를 reject한다.

isolatePhoneSource는 G guest 생성 전에만 설치된다. 전화 code 확인·직접 비밀번호 준비·실제 주문 생성 assertion은 유지되며 이전 repurchase .3와 기존 F2 흐름을 변경하지 않았다. 제품 quota/DB 초기화/forwarded header 신뢰/재시도/timeout 변경이 없다. 정적 추가 차단은 없지만 새 focused 및 전체 실행에서 기존 F2 포함 성공과 .1/.4 카운터를 확인하기 전 실행 통과로 판정하지 않는다. 이전 실패 원본은 보존한다.

## 최종 독립 전체 실행 — 2026-10-28

`./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-g-review1-20261028`을 새 경로에서 선택옵션 없이 실행했다. MAVEN_ARGS를 제거했고 제품/검사/하네스는 동결했다. 22:55:23~23:04:52 KST, 외부 종료0, result.status/preservation passed. Java451(단위·ArchUnit183 + PostgreSQL267 + 전화 제공자1), browser106, frontend216, Node75, 필수50묶음이 통과했다. 필수 누락/실패/오류/skip0, browser unexpected/flaky0, typecheck/lint/build 통과다.

| 인수 범위 | 이번 독립 실행의 실제 근거와 판정 |
|---|---|
| G01 회원 통합 / AC01·07·08 | memberMultiStorePurchase PC/mobile 및 purchaseTotalsAndReservationsConverge 통과. 실제 UI 유실동일주문, 두 쿠폰/예약 확정, 승인효과·성공이력1, qty3/2 원단위부터 부모55800/재고감소/원항목 배송수량, producer job 소비·판매자 발송·DB/API/UI 일치 |
| G02 비회원 / AC02·03·04 | guestPurchaseAndNewBrowserLookup PC/mobile 통과. 전화 emulator/직접비밀번호→실제주문→새 GAS, wrongpassword401/owned200/foreign404/no-store, GAS 승인401. 공개행사 회원/guest동일·쿠폰거부. 기존 realGuestOrdersUseIndependentAccessSessions, guestOrderConsumesVerificationAtomically를 포함한 같은번호 NEW/EXISTING·reset/expiry 검사가 유지/통과 |
| G03 실패·UNKNOWN·0원 | uncertainFailureAndZeroPayment PC/mobile 및 기존 paymentUnknownAndLateSuccessConverge, paymentZeroCrashRecoveryNeverCallsAnyProviderOperation, shipmentAtomicRollbackAndCancellationRace 통과. UNKNOWN 예약보유·재승인0추가, 실패전체해제, 유실재조회확정, 늦은성공보상/미출고, 실제 zero중단복구/제공자4연산0을 함께 연결 |
| G04 스냅샷·소유권 / AC04·13 | historyAndSellerIsolation PC/mobile 통과. 공개 상품/행사/쿠폰 변경 후 전체detail불변, seller exact projection/타매대404, 두매대 DELIVERED/IN_TRANSIT DB/API/UI·시간/이벤트/재조회 일치 |
| R-FINAL-01 | PaymentStore 인증변경 즉시 view폐기·abort/generation, 늦은성공/401·동일identity 새세대·StrictMode·현재GET 후 명시승인/자동POST0의 프런트 회귀와 실제 PC/mobile 인증변경 검사 통과 |
| R-FINAL-02 | paymentPreparationRechecksExpiryAfterReservationWait(String)[1..5]와 paymentExistingGuestRechecksMaximumAfterOrderWait(String)[1..2] 실제 수집7개 모두passed. coupon/inventory/zero/sandbox/confirm 대기후401·참여행/provider불변, guest max직전 idle==max 및 HTTP유효→실제order대기→max401을 확인. worker복구에 고객인증을 추가하지 않음 |
| A~F2 선행 회귀 | 기존 필수 Java384→387/browser96→106/frontend206→216 이름 누락0. 주문 snapshot/checked금액, 원자성/동시잠금, job fencing/heartbeat/실제process중단, 결제 보상·권한, sellerprivacy·stablecursor, BASIC item/headerFK/backfill·배송UI재시도 검사가 새 전체에서 재실행됨. 단계별 구체 assertion은 각 원 독립검토와 G 제약 매핑을 함께 따른다 |
| G05 | 실제 Toss SANDBOX/카드 webhook 제공 계약은 NOT RUN/BLOCKED. 로컬 adapter/서명fixture 성공을 실제 제공자 성공으로 합산하지 않는다 |

새 DB `d2_it_1f0485ba3a1c408dbb40e0e278617477`, 미디어 `.local-tools/p08-g-review1-20261028/db/media`, 전화 emulator `.local-tools/phone-emulator-4ca8c434b9504f7097cfc95f2c30619f`를 보존했다. 실행 원본은 review1 폴더, 주요 result/inputs/coverage/전후/DB/browser/frontend 및 권한7개 사본은 `evidence/P08-G-review-final-*`다. 기존 서버·DB·미디어 삭제/중지는 하지 않았다. 보존 결과는 기존 DB 존재/자원 및 파일 hash 기준이며 과거 모든 DB행의 전체덤프 동일성 주장이 아니다.

과거 F2 전화 fetch 실패와 final-fix 첫 쿠폰 reload 연결 실패는 원인 미확정으로 보존한다. 후속 focused/개발/이번 독립 통과는 그 실패의 원인이 해결됐다는 근거로 표시하지 않는다. G의 source .4는 테스트 전용 실제 loopback 클라이언트 격리이며 제품 quota/시간/forwarded header 정책 변경이 아니다.

최종 전후 감사 완료: HEAD `460aa8bb0bb22f5138849eb00c050abd18f6fb53` + dirty, 입력472파일 SHA256 `A20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF`로 개발dev2/독립review1 manifest가 정확히 동일하다. 현재 소스 전후 불일치0. 실패32 manifest/중복제거65820파일의 존재/내용SHA 및 manifest 자체SHA 전후불일치0. 감사 원본 `.local-tools/p08-g-review1-audit-20261028/{before,after}.json`와 필수목록 유지 비교를 사본으로 보존했다.

**최종 판정: G01~04 DEMO·로컬 통합 및 최종 수정의 독립 전체 검증 통과. 로컬 범위 필수 잔여 차단 없음.** R-FINAL-01/02와 기존 단계 지적의 회귀를 같은 동결 소스에서 확인했다. **G05/E 실제 제공자 검증은 외부 차단이며 P08 전체는 미완료**다. P09 인계는 문서 범위이고 분할배송/클레임/부분환불/구매확정/정산 구현, 실제택배, 커밋·푸시·배포는 수행하지 않았다. 이번 검토 이후 제품/검사 소스가 변경되면 이 결과를 새 소스에 재사용하지 않는다.
