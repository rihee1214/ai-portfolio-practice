# P08-F 독립 사전 검토 — 2026-10-28

상태: **사전 정적 검토 / 구현 최종·자동 실행 미검증**. E 로컬 독립 통과 후 외부 제공자 차단 예외에 따라 F DEMO 범위만 검토한다. 실제 SANDBOX 완료를 전제하지 않는다. 구현·하네스·F1·커밋·푸시·배포는 수행하지 않았다.

## R-F01 새 브라우저 read authority와 구매 attempt authority 분리

현재 JdbcOrders.authorize는 guest authorizeRetry의 원 browser_hash·consumed attempt 결합과 member lockActiveBuyer를 사용한다. PaymentCoordinator.get도 이를 사용하며 checkReview 쓰기까지 한다. 이를 F 읽기 이력에 그대로 적용하면 새 브라우저의 정상 GuestAccessSession이 거부된다. guest 공개 읽기 권한이 password_version/revoked/idle/max를 현재 거래에서 검증하고 buyer 귀속을 제공해야 한다. order 읽기 권한과 payment의 별도 읽기 projection을 연결하고 타 모듈 infrastructure/테이블을 직접 읽지 않는다. 신규 구매 자격과 기존 거래 조회 자격을 혼합하지 않는다.

assertion: 실제 C/D 주문을 생성한 브라우저를 닫고 독립 context에서 P06 자격 로그인→실제 주문 상세·결제 이력 성공; 타인/없는 번호 동일404; 로그아웃/재설정/idle/max 만료 거부 및 실제 권한 lock 대기 후 최신자격 확인. 기존 D status/approve는 기존 browser/attempt401 계약 그대로 검사한다. history 권한이 approve를 열어주지 않는다.

## R-F02 기존 fixture reference 경로 전환

기존 GuestAccessController의 GET /api/guest/orders 및 /{number}는 guest_order_reference의 번호만 반환한다. 동일 mapping 중복을 만들거나 이를 실제 주문 이력으로 간주하지 않는다. 구현자 제안인 /api/guest/access/references[/{number}]로 명시적 분리, 실제 /api/guest/orders는 purchase_order 목록으로 전환하는 방식은 적합하다. 기존 P06 인증/귀속/만료 assertions를 새로운 reference 경로에서 유지한다. GET permitAll 추가는 정확한 reference/detail/history 경로에 한정하고 서비스 인증을 필수로 한다. 고객 POST CSRF나 운영 경로를 넓히지 않는다.

assertion: reference-only fixture가 실제 주문 목록에는 등장하지 않음, 실제 주문은 새 context 조회됨, 잘못된 GAS·다른 buyer reference 동일거부; 모든 개인정보 성공·오류 응답 no-store.

## R-F03 판매자 DTO와 기존 거래 capability

Orders.Snapshot에는 부모 quote 전체, 모든 sellers, buyer/member/guest 식별자, 구매자·수령 연락처가 포함되어 있으므로 그대로 serialize한 뒤 UI에서 숨기는 방식은 불가하다. seller projection은 해당 store의 SellerOrder와 허용된 수령 배송 필드만 새 DTO로 구성해야 한다. 기존 Stores.Capability.EXISTING_TRADES를 검토하여 NEW_SALES/공개카탈로그 자격과 구분하고, 현재 owner·역할·세션을 검증한다. 주문 상세 read authority는 seller를 buyer 전체 이력 권한으로 승격시키지 않는다.

assertion: 서로 다른 두 판매자의 실제 한 주문에서 각 응답의 JSON 필드 allowlist와 자기 금액/항목 정확성; 다른 seller 항목·부모 전체 결제금액/attempt/history·buyer 연락처·guest UUID/비밀번호/복구/세션/provider key 필드0. 타 seller ID 조작404. 매대 비공개/정지에서 기존 거래 조회 정책 유지, 계정/역할 변경에서는 현재 권한 거부. F1 배송 기능은 아직 구현하지 않는다.

## R-F04 안정 페이지와 보존 스냅샷

V31은 (member_id 또는 guest_buyer_id,created_at DESC,id DESC) 인덱스와 저장 quote/line/unit 금액을 이미 가진다. 커서는 created_at+id를 모두 포함하고 엄격한 사전순 경계로 읽어 동일시각 행을 빠뜨리지 않아야 한다. 상태/수정시각을 페이지 정렬키로 사용하면 결제 전환 중 중복/누락이 생긴다. 입력 커서는 권한 근거가 아니며 모든 페이지에 owner 조건을 다시 적용한다. 현재 catalog 공개 여부나 identity 이메일 조인으로 과거 주문을 필터링/재귀속하지 않는다.

assertion: 페이지크기보다 많은 주문과 동일 created_at 행, 다음 페이지 전 신규 주문 삽입·결제상태 전환, 각 기존 ID 정확히1회; malformed cursor/limit0/초과 처리를 고정. 상품·SKU·행사·쿠폰 이름/가격/공개 변경 이후 저장 제목/금액/단위배분 동일. 탈퇴 후 주문/항목/금액 유지, 같은 이메일/전화의 새 identity UUID에 연결0. 정책상 탈퇴·정지 session 접근은 보존 여부와 별도로 검증한다.

## R-F05 주문·결제 projection 일관성

F 상세에서 order 상태와 payment state/compensation을 따로 읽으면 승인 commit 중 서로 다른 시점의 결과를 조합할 수 있다. 공개 projection 경계를 유지하되 동일한 주문 read transaction/lock 순서 또는 명시한 일관성 계약으로 조합한다. payment history는 raw paymentKey·secret·merchant 응답 원문·운영 audit payload를 반환하지 않는다. 조회 시 UNKNOWN·reviewRequired를 표시하되 승인/배송 완료로 번역하지 않는다.

assertion: PAID/SUCCEEDED, 실패/해제, UNKNOWN, 보상PENDING/CANCELED·reviewRequired를 실제 저장값과 각각 대조; API history가 제공자 승인 호출이나 새 attempt를 생성하지 않음. D worker와 읽기 경합이 교착 없이 끝나며 서로 모순된 확정 상태를 노출하지 않음.

## R-F06 UI 이전 응답 폐기

목록·상세·payment history 모두 identity generation에 귀속시킨다. logout/계정전환/guest재로그인/페이지 변경 시 즉시 기존 민감 데이터 제거 후 이전 응답이 늦게 도착해도 복원하지 않는다. abort만으로는 이미 완료된 Promise/가짜fetch 응답을 막지 못하므로 generation 검사도 필요하다. 모든 오류/로딩에서도 과거 타인 정보는 숨긴다.

assertion: A요청 지연→B로그인/새GAS→B성공→A늦은성공/오류, logout 도중 응답, 상세 번호 변경 뒤 늦은 목록/결제응답; 과거 데이터 비노출. 실제 browser 두 매대·성공/실패/UNKNOWN·새 context GAS 흐름을 검사하고 fixture reference UI를 실제 이력 증거로 대체하지 않는다.

## 사전 판정

위 항목은 구현 전 필수 설계·assertion 점검이며 구현 완료 결함 목록이나 통과 판정이 아니다. 개발 전체 통과와 소스 동결 후 별도 새 경로 독립 전체, 소스/실패 보존 대조가 필요하다.

## 진행 중 백엔드 정적 검토 — 2026-10-28

소스가 동결되기 전 읽은 시점의 판정이다. JdbcOrderQueries의 새 GuestAccessSessions/HistorySessions 분리, 정확한 reference 경로 이동, order FOR SHARE를 보유한 payment history 읽기, 판매자 별도 Recipient/Item DTO를 확인했다. 실제 새 guest 브라우저에서 C 주문 NEW/EXISTING 귀속, 원 approve401, 만료/버전 거부 검사도 확인했다. 구현 및 실행 전체 완료 판정은 아니다.

### R-F07 결제 history의 유효 reviewRequired 누락

JdbcPaymentQueries.history는 payment_attempt.review_required만 반환한다. 기존 PaymentCoordinator.checkReview는 pending 시 query count/경과시간 또는 Jobs FAILED를 확인해 review_required를 표시한다. generic worker delivery 소진 직후에는 저장값이 false일 수 있으므로, 새 브라우저가 F history만 읽으면 실제 대사 필요 상태가 표시되지 않는다. 원 D status는 구매 browser 권한이라 F의 대체 읽기 경로가 아니다. payment 내부에서 공개 Jobs API/PaymentRules로 유효 review 상태를 계산하거나 공용 처리 계약을 적용할 필요가 있다.

필수 assertion: 실제 worker exhausted 또는 기한 초과 상태에서 원 status/운영 queue를 먼저 호출하지 않고 F history를 읽어 reviewRequired=true, provider 호출/새 attempt0, 권한 없는 조회 효과0. 독립 전체 전 해결 여부를 재검토한다.

### 기존 F01~03 assertion 보완

현 seller 검사는 한 판매자의 성공과 다른 하위주문 거부를 검증한다. 두 번째 실제 판매자의 성공·자기 total/각 unit 정확성, 비공개/폐점의 기존거래 정책, 역할폐기/세션 변경의 실제 대기 이후 거부가 추가되어야 한다. 페이지의 동일시각23개 검사는 있지만 페이지 사이 신규삽입/상태전환은 아직 없고, 탈퇴 검사는 SQL 상태변경과 다른 이메일 신규 fixture여서 같은 이메일 재가입 후 재귀속0을 증명하지 않는다. 실제 reset/read-lock 대기 검사는 구현자가 추가 예정이며 미실행으로 남긴다.

## focused5 이후 정적 재검토 (focused6 실행 중)

R-F07의 계산은 D와 같은 pending 조건(결제 미확정 또는 보상 PENDING/UNKNOWN/FAILED)에서 COUNT/TIME/Jobs FAILED를 읽고 저장 상태를 변경하지 않는다. COUNT/TIME/JOB, 보상 PENDING/UNKNOWN true 및 terminal false, 전체 참여행·provider mock 무변경 assertions가 추가됐다. R-F03/F04 보완으로 양 판매자 자기금액·수량/unit, private/closed 기존거래 조회, 실제 탈퇴 후 같은 이메일 재가입의 새 UUID·과거 귀속0, 페이지 사이 신규삽입/상태변경을 확인했다.

주문 FOR SHARE 대기 후 member/guest 권한을 현재시각으로 재확인하며 seller도 member 재확인한다. 실제 order lock 대기 뒤 guest idle/max, member detail/payment/seller expiry를 이동해404, 계정/역할 변경 대기 거부를 검사한다. guest buyer/session 선점 후 동일행 재확인은 기존 reset 순서와 충돌하지 않는다.

UI는 auth subscription의 clear/generation, 로딩 직전 empty, detail/history 오류 동시폐기, guest logout의 OrdersPanel unmount를 확인했다. 실제 브라우저 새 guest context의 조회성공/기존 approve401와 유실 로그아웃 숨김, 계정전환 뒤 이전응답 숨김이 포함됐다. 단위 guest logout 검사는 store.clear 직접 호출이며 실제 이벤트 통합 근거는 브라우저 검사다.

추가 좁은 정적 위험(실제 RED 미실행): OrderQueryStore.read 내부 generation 검사와 load/open의 await 이후 this.set 사이에 async continuation 간격이 있다. 특히 Promise.all 완료 직전 auth clear가 먼저 실행되는 경우를 막도록 최종 set 직전 generation/ready를 재검사하고 해당 microtask 경계 검사를 권고했다. 구현자 제안 Owned(id,state,amount,mode) 공개 포트 반환은 동일 외부 transaction/order lock을 유지하면 적합하다. 이 두 후속 사항은 아직 동결 최종 소스로 재검토하지 않았다.

## 개발 전체 전 마지막 정적 재검토

focused7 실행 중 동결 소스에서 load/open 최종 this.set 직전 generation+ready guard를 확인했다. 목록 microtask 경계와 두 상세 응답 Promise.all 결합 경계의 새 검사는 앞서 지적한 사이 구간을 직접 겨냥한다. 구현자가 보존한 실제 RED2→GREEN8 보고와 별도로, 본 검토는 코드/assertion만 확인했고 실행을 추가하지 않았다.

Owned(id,state,amount,mode)는 order FOR SHARE 및 잠금 뒤 현재 권한 재확인 이후 반환한다. payment history는 동일 outer transaction을 유지하면서 자기 payment_attempt/payment_event SQL만 읽는다. 같은 이름의 두 매대는 sellerOrderId로 각 패널을 식별하고 각 상품 exact heading 및 개별 금액 표시 assertion을 유지한다. 검사 범위/대기시간을 줄이거나 실패를 숨기는 변경은 확인되지 않았다.

현재 정적 검토 기준 개발 전체 실행을 막는 추가 필수 로컬 지적은 없다. focused7·개발 전체·후속 독립 전체 결과와 보존/소스 대조 전에는 완료 판정하지 않는다.

## 최종 독립 전체 검토 — 2026-10-28

새 경로 `.local-tools/p08-f-review1-20261028`에서 루트 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-f-review1-20261028`을 실행했다. MAVEN_ARGS 제거, 검사 선택 없이 실행했으며 18:51:54~18:59:27 KST, 외부 프로세스 종료0, status/preservation passed다. Java429(단위182+격리DB247), browser90, frontend187, Node73; 필수47묶음 passed, 누락·실패·오류·skip0, typecheck/lint/build 통과다. E 최종 필수 이름과 대조하여 Java362→370/browser84→90/frontend179→187의 이전 이름 누락0을 확인했다.

| 제약/검토 ID | 실제 실행 assertion | 판정 |
|---|---|---|
| F01/R-F01·02 | realOrdersRequireCurrentOwner, realGuestOrdersUseIndependentAccessSessions: 현재 본인만 조회, 타인/없는번호404·no-store, 새 GAS 브라우저 실제 C주문 NEW/EXISTING 분리, 원 승인401, 실제 reset·2세션 폐기·만료 거부 | 통과 |
| F01 시간·권한 경계 | orderHistoryRechecksAuthorityAfterActualWait, guestHistoryRechecksExpiryAfterOrderWait, memberAndSellerHistoryRecheckExpiryAfterOrderWait: DB 실제 잠금 대기 확인 후 계정/역할 및 idle/max/session deadline 변경 거부 | 통과 |
| F02/R-F03 | sellerOrderProjectionIsScoped: 양 판매자 자기 항목·금액·unit·recipient allowlist, 다른 하위주문404, 전체 부모/타매대/guest자격/provider비밀0, private/closed 기존거래 조회 | 통과 |
| F03/R-F04 | orderHistoryPreservesSnapshotsAndPagination: 동일시각23개·페이지 사이 insert/state변경에도 기존ID1회, 크기/커서 오류400, 상품/혜택변경 뒤 저장금액·스냅샷 불변, 실제 탈퇴·같은email 재가입 후 새UUID·과거연결0 | 통과 |
| 결제/R-F05·07 | paymentHistoryShowsExhaustionWithoutMutation: COUNT/TIME/Jobs FAILED 및 보상 pending/unknown 대사표시, terminal false, 전체참여행/provider mock 변화0; Owned 공개 포트와 동일 transaction/order lock으로 상태 읽기 | 통과 |
| F04/R-F06 | OrderQueryStore8: 로딩·오류 즉시폐기, 계정변경 늦은 응답 및 최종 publication microtask 경계2; orders-query PC/모바일6: 두매대 성공/실패/UNKNOWN, 새context 실제 guest이력, 승인권한분리, 유실로그아웃·이전계정응답 비노출 | 통과 |

사전 R-F01~07 및 최종 비동기 publication 지적은 위 구현·assertion으로 해소됐다. 단위 guest clear 검사의 실제 이벤트 연결은 브라우저 logout/unmount 검사로 보완하며, 코드 독해나 총개수만으로 통과를 판단하지 않았다.

새 DB `d2_it_5d92172c863c4498831ccd447ec2a24d`, 미디어 `.local-tools/p08-f-review1-20261028/db/media`, 전화 emulator `.local-tools/phone-emulator-0a2ef130c1954fae8591661aecd16693`를 보존했다. 실행 원본은 review1 폴더, 주요 결과/inputs/coverage/전후/DB/프런트 사본은 `evidence/P08-F-review-final-*`에 저장했다. 기존 서버·DB·실패 로그·미디어 삭제나 중지를 수행하지 않았다.

전후 감사 완료: HEAD `460aa8bb0bb22f5138849eb00c050abd18f6fb53`(미커밋 변경 포함), 입력453파일 SHA256 `649C318C0A763FD8CBF63A5C3E1ED738C9FD133286FD3CA06C1DDD556D45A5FE`로 개발dev1/독립review1 inputs manifest가 같다. 실행 전후 현재 소스 불일치0. 실패 manifest27개/중복제거49431파일의 존재·내용SHA 및 manifest 자체SHA도 전후 불일치0이다. 새 F 실패7129파일 manifest SHA `F5D901F323E43594E9709DC74434CBAC1ED8639D1E4BFBE2FC52BA17462A36FD`를 포함한다. 기존 실패를 이번 성공으로 소급 변경하지 않았다. 감사 원본은 `.local-tools/p08-f-review1-audit-20261028/{before,after}.json`이다.

**최종 판정: F DEMO 범위 독립 검증 통과, 필수 잔여 차단 없음.** E의 실제 SANDBOX·카드 webhook 키 제공/시간형식·외부 접근 검증은 여전히 차단·미완료이며 본 결과로 대체하지 않는다. F1 배송 기능·P09·커밋·배포·푸시는 진행하지 않았다.
