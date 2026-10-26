# 실행 기록 — 계획: docs/work-items/P07.md

최종 상태(2026-10-27): **A/B/C/D 모두 순차 구현·개발 전체·독립 전체 검토 완료**. 최종 Java348/browser68/frontend162/Node72, 필수 차단0, 소스376개 일치·실패14158파일 보존. [완료 표와 인계](P07.md), [D 독립 검토](P07-D-review.md). 아래는 시간순 이력이며 당시 진행/실패 상태를 유지한다.


2026-10-26 사용자: P07-A~D 순차 실행 승인. 구현/단계별 독립 검토까지 진행하며 커밋·P08·배포·푸시는 제외한다.

- Ruling: 기존 codex/p05-d 작업 브랜치·현재 체크아웃을 유지한다. 로컬 캐시·검토세션·보존 하네스가 이 경로에 결합되어 있으며 소스 이동 없이 새 DB/미디어로 검증을 격리한다. 기존 미커밋 계획 문서를 유지한다.
- Ruling: 계획의 제안 기본안을 채택한다. 상품 단가별 정액/정률(10000bp, 버림)·배송 정액/무료·가장 큰1개/ID순, 쿠폰 회원당 템플릿1회, 발급조건 스냅샷, 읽기 견적, 내부 예약 계약. 사용자 확정 정책으로 소급하지 않는다.
- Pre-flight A→C: 후보 ID/version/기간/대상·할인 함수 공유. API는 domain 내부 참조 없이 값 DTO만 노출.
- Pre-flight B→C→D: 발급 스냅샷과 서버 Quote를 예약에서 재검증. 단순 fingerprint는 권한이 아니다.
- Pre-flight D→P08: 실제 주문/결제/worker는 이번 범위 밖. 신뢰된 내부 호출과 fixture로 검증.
- A1 진행: 규칙 검사부터 작성. 완료 단계 없음.

각 단계의 red/green/전체 경로와 지적 결과를 아래에 누적하며 실패 증거를 삭제하지 않는다.

- A1: .local-tools/p07-a-unit-red.txt 미구현 컴파일 실패 → unit-green.txt JUnit1 통과. 정률 중간곱은 BigInteger, 원 미만 버림.
- A2/3: p07-a-db-red.txt는 신규 API 부재와 테스트 HTTP helper 잘못 사용을 함께 발견. helper를 기존 accountRequest/authRequest로 수정하고 db-red2.txt에서 신규 Promotions 부재만 확인했다. V26·관리/공개·감사·소유권 구현 후 전체 dev1 실행 중.
- A4: p07-a-ui-red.txt 모듈 부재 실패 → ui-green.txt 단위2 통과, typecheck 통과. 기존 CatalogStore의 CSRF/version/늦은 응답/중복억제 재사용. 신규 PC/모바일 실제 행사진행 검사를 필수 등록.
- A 개발1: .local-tools/p07-a-dev1-20261026. 소스 동결. 검토세션 01a1721f-cb46-7193-8aed-59d9932ab91f에 정적 검토 요청. 독립 하네스는 아직 요청하지 않았다.
- 이전 실패5620파일 불일치0: evidence/P07-prior-failure-preservation.json. 기존 실패 원본 보존.

- A dev1 failed/preservation passed. JUnit157통과, DB152 중 브라우저 wrapper1실패/151통과, SDK IT1통과. 브라우저58통과/신규2실패: implicit label에 option text가 섞여 정확한 행사 대상 이름 검색 timeout. timeout 확대 없이 htmlFor/id로 수정한다.
- 검토 R-PA01: HTTP 행사 금액은 JS 안전정수 상한9007199254740991로 제한(내부 계산 long 유지). C 최종견적은 decimal string계약 예정. 기존SKU와 별도 혜택 입력경계로 명시.
- R-PA03: FREE는 상한null만 허용해 전액 할인 약속 보장. partial 지원은 FIXED 사용.
- R-PA04: 행사 저장은 과거기간도 편집 가능하며 공개 후보 읽기는 단일SQL시각스냅샷. 잠금 후 만료의 실제 소비판정은 C/D, A는 잠금 후 정지 및 시작/종료읽기 경계를 증명한다. 최대할인/동률 선택 함수는 A에서 제공한다.

- A 검토보완: FREE상한거부red→green, 최대/동률선택과금액입력경계신규. 단위3/UI4/typecheck3통과. 중간UIgreen은page nullable초기상태오류로실패하여guard수정후green2통과; 실패원본477파일manifest보존. dev2새전체실행중.

- A dev2 failed/preservation passed: browser59통과/3실패. 신규공개상세2개는라우트이동완료전목록의같은제목이먼저통과하여동일매대링크다수strict실패. URL상세완료·article1개assertion추가. 기존input-flow모바일768은검색응답/주소갱신전이미보이던상품링크focus/Enter후목록에남음. 새검색주소·링크href·focus확인후locator.press로키보드진입검증을강화한다. timeout/assertion축소없음. 제품탐색소스변경없음.
- 검토후속: 공개응답정확한15필드집합검사, value/cap의MAXSAFE/초과/큰홀수각각독립HTTP입력을추가. dev3에서새전체검증한다.

- A dev2/dev3 추가 확인: 공개 상태 매트릭스가 매대 SUSPENDED와 visible=true를 함께 만들면서 기존 store_restricted_not_public 제약에 실패했다. fixture를 SUSPENDED/visible=false로 수정했다. dev3 브라우저62개는 모두 통과했지만 DB 오류로 전체 실패이며 완료로 표시하지 않는다.
- R-PA05 집중 검사: retire-red는 인증 에뮬레이터 환경변수 누락으로 context 시작 실패, red2는 증거 절대경로 처리 거부로 DB 실행 전 실패. red3은 새 실제 에뮬레이터/DB에서 Products.lockForMedia의 CATALOG_REJECTED로 종료 회귀를 재현했다. 각각 실패 원본을 보존한다.
- Ruling: 동일 target 집합의 STOPPED/ARCHIVED 전환은 이미 보유한 owner/store 잠금 안에서 Products.own으로 소유권을 확인한다. 대상 변경/신규/ACTIVE는 기존 lockForMedia 검사를 유지한다. 기존 catalog 보관 규칙은 변경하지 않는다. owner/store 잠금은 catalog 쓰기와 직렬화하므로 종료 전용 새 API 없이 기존 공개 계약으로 충족한다.

- A dev4: Java314(기본159+DB154+SDK1), 브라우저62, 프런트152, Node72 실제 검사는 통과했다. 최종 필수 대조에서 PromotionStore describe prefix 2개 누락으로 failed, preservation passed. 검사 내용/개수를 바꾸지 않고 실제 XML의 `PromotionStore > ...` 이름으로 매핑을 교정했다. `.local-tools/p07-a-dev5-20261026`에서 새 전체 실행 중. dev4는 성공으로 소급하지 않는다.

- A dev5 전체 passed/preservation passed. Java314/browser62/frontend152/Node72, 소스349개 inputs SHA256 07CC42C0F1BD0C75CC948F21D7BF99729BAFC0F4C8B19882C77BF05EDE34E021. 독립 검토세션에 review1 새 전체 실행 요청, 소스 동결 유지. A 실패2577/이전5620 해시 불일치0. B~D 아직 미착수.

- A 독립 review1 완료: status/preservation passed, Java314/browser62/frontend152/Node72, 개발5와349소스동일. R-PA01~05 해결, 필수 차단0. 독립 문서 P07-A-review.md·증거19개. A 완료, B 시작. 커밋/P08/배포/푸시 없음.

- B1: CouponRulesTest 미구현 컴파일 red → 규칙1 green. 기간/최소금액/정률상한/정액잔액 제한과 잘못된 발급한도/기간 거부를 검증했다.
- B2/3: 새 DB red에서 Coupons bean 부재3오류 재현 → V27/서비스/HTTP 구현 후 `.local-tools/p07-b-db-green1-20261026` 규칙1+DB3 통과. 한도1 경쟁 한 승자, 동일회원 동시/재시도 행1·카운터1, 감사 실패 rollback, 발급 스냅샷 불변, 실제 잠금 대기 후 구매자/판매자 정지·발급 종료 거부를 확인했다.
- B4: 첫 UI red는 확장자 .ts가 프로젝트 test.tsx 패턴과 달라 검사 미발견, red2는 새 모듈 부재. UI green1은 mock의 Headers 객체를 일반 객체로 검사하여 assertion이 네트워크 오류로 처리되는 테스트 버그로 실패; Headers.get을 외부 assertion으로 고쳐 green2 3개 통과. typecheck1의 fixture literal widening/메뉴 planned 누락 수정 후 typecheck2/lint 통과. 실패 원본 보존.
- B dev1 `.local-tools/p07-b-dev1-20261026` 전체 실행 중. 소스 동결, 검토세션에 정적 B 검토 요청. 독립 전체는 개발 전체 통과 뒤 별도 실행한다.

- B dev1 failed/preservation passed: browser62통과/쿠폰2실패는 로그아웃 완료 전 다음 문서로 이동해 요청을 중단한 테스트 흐름. 로그아웃 완료 문구 확인 뒤 이동하도록 고쳤다. DB time대기 오류도 발생했고 review-red2에서 time 분기임을 확인했다.
- B 시계 원인: review-green1 로그 `P07_CLOCK java=2026-10-26T13:52:31.055639900Z database=2026-10-26T13:52:31.570906Z`, DB가 약515ms 앞선다. DB clock_timestamp를 종료점으로 쓰면 Java 현재시각에서는 아직 미래여서 정상 발급될 수 있었다. fixture를 서비스와 같은 Instant.now의 과거로 명시하고, 받을 수 있는 목록도 Java의 단일 at으로 조건을 읽는다. 실제 원래 종료점 자연경과 검사가 아니라 잠금 중 기간변경 후 최신 판정 검사임을 유지한다.
- R-PB01: 보유 매대 안내 누락 UI red→green. DTO에 현재 storeSlug/name을 별도 안내로 포함하며 할인/기간 스냅샷을 덮지 않는다. 비공개 매대도 보유행 유지, 동일 제목 두 매대 fixture로 UI 구분/새로고침 검사.
- R-PB02: review-red2 HTTP MAXSAFE+1이200임을 재현→controller에서subtotal0~MAXSAFE만 허용. 내부 long 할인은 유지한다. review-green1에서 MAXSAFE/초과2개/음수, 내부Long.MAX_VALUE 확인.
- R-PB03: review-green1 새 DB4개 통과: 교차발급/관리감사/동일version/한도하향 경쟁, 현재판매자조건/기존retry/본인정지, 보유22개 페이지·회원격리, 입력4필드 독립HTTP경계. 최신 전체 dev2 실행 중. 구문 추가 중 괄호 오류의 review-red도 보존한다.

- B dev2 failed/preservation passed. 기본160·DB160 중 브라우저 wrapper1실패(기타159통과)·SDK1, 브라우저63통과/PC쿠폰1시간초과. 새 DB 제약 오류는 없었다. 실패 시 context.close 오류가 최초 대기 지점을 가렸고 현재 원인은 미확정이다. 해당 브라우저 시나리오에 단계 로그/실패 trace를 추가하고 cleanup은 원래 오류를 덮지 않게 보완했다. timeout/assertion/필수 목록은 축소하지 않았다. dev3 새 전체 재현 실행 중, B 실패1698파일 보존.

- B dev3 시간초과는 단계 로그에서 목표 쿠폰 받기 버튼 click 대기로 특정했다. 실패 trace의 실제 목록 응답은 page0/items20/hasMore=true/목표title없음이었다. R-PB03 페이지 검사용21개 데이터로 목표쿠폰이 첫 페이지 밖에 있을 수 있는데 브라우저가 다음 페이지를 탐색하지 않았다. 실제 다음 발급 쿠폰 버튼과 페이지 번호 확인을 추가했다. timeout은 유지했다. dev2도 같은 선택 위치의 위험이 있었지만 최초 지점 기록이 없어 동일 원인 확정으로 소급하지 않는다.
- B dev4 `.local-tools/p07-b-dev4-20261026` 전체 재검증 중. 실패2180파일 원본 해시 보존.

- B dev4 Java321/browser64 전체 실행검사 통과, frontend lint에서 새진단 cleanup finally throw 규칙위반으로 최종failed/preservation passed. 오류 수집은finally, 최초오류 재전파는뒤로 이동했다. typecheck3/lint2/UI4 선행통과 후 dev5 새전체실행중. 제품소스·검사기대값·timeout은 유지했다. B실패2663파일보존.

- B dev5 전체 passed/preservation passed: Java321/browser64/frontend156/Node72, 소스359개 inputsSHA 0C76C92A1DE0CCE7E3523AF59274F89B707B1C81E76AE2D056C41B148032276A. 독립 review1 요청, 소스 동결 유지. C/D 미착수. 실패10860파일 불일치0.

- B 독립 review1 전체 완료: Java321/browser64/frontend156/Node72, 소스359개동일, status/preservation passed, R-PB01~03해결·필수차단0. 실패10860파일보존. dev2최초원인미확정유지. B완료/C시작. 커밋/P08/배포/푸시없음.

- C1/2: QuoteCalculatorTest 미구현 컴파일 red 후 규칙2 green. C3 새 DB red(Quotes bean 부재) 후 green1 DB2, green2 DB4+규칙2 통과. 실제 두 가격 동시 변경 중 REPEATABLE READ로 이전76000/다음100000, 행사후 배송 기준/전액쿠폰/배송행사 동률/0원 확인. C4 UI missing-module red 후3 green, 타입/린트 통과. C 개발 전체 시작, 독립 정적 검토 요청. D 미착수.

- C dev1 failed/preservation passed: 견적브라우저2 exact label 검색 실패, 기존64통과. R-PC01 id/htmlFor, R-PC02 실제선택401/늦은응답/재로그인, R-PC03 다항목34/33/33·역순지문·현재판매8조건·7테이블조회부작용0/HTTP금액계약 보완. review-red 인증폐기1실패→UI5green; DB review-green1 도우미컴파일실패→green2 규칙2+DB3통과. dev2 전체 진행/소스동결, 독립정적재검토 요청.

- C dev2 전체 passed/preservation passed: Java329/browser66/frontend161/Node72, 소스368/inputsSHA C1E3E0B23E6BCA528ADBF20FD618BE3D6C8E8548BF6147857D466018255A27C4. 독립 review1 요청, 소스 동결. C실패1196+이전10860 보존. D 미착수.

- C 독립 review1 완료: Java329/browser66/frontend161/Node72, 368소스동일, status/preservation passed, R-PC01~03해결/차단0. 실패12056파일보존. C완료/D시작(2026-10-27).

- D1 단위 첫 red/green시도는 새 코드의 중괄호/별표import checkstyle 오류로 중단, unit-red2에서 null상태 오류형 assertion 실패→명시입력거부 수정 후 db-red의 규칙1통과. db-red는 CouponReservations bean부재2오류로 미구현 실패 재현.
- D2 V28/내부서비스 구현 후 db-green1 규칙1+DB2통과. 호출자rollback, 서로다른checkout 동시1승자, 같은입력retry, 변조거부, 전체복구 후새예약에 과거명령영향0.
- D3 db-green2는 새 검사 generic 닫는괄호 오타로 parse실패, green3 규칙1+DB7통과. 실제 잠금대기와 조절시계로 만료정각commit거부/expire승리, 계정정지/기한재검사, 위조지문/현재가격변경거부, 다쿠폰감사실패rollback, 원만료유지/부분·미확정미복구, fixtureprofile/CSRF/미인증거부. D4 UI5/타입/린트통과, 교차판매자 및 동일요청동시retry green4 진행.

- D green4 규칙1+교차판매자/동시retry DB1 통과. dev1 전체 실행 중, 소스동결 및 독립 정적 검토 요청. 실패399파일 보존.

- D dev1 전체 Java338/browser68/frontend162/Node72 및보존passed이나 R-PD01~04로 완료보류. review-red 실제3결함재현→V29batch고정/단일SQLprior/fullconfirmed미사용409 수정. green1 실제대기관측은FK잠금에서먼저막혀실패, fixture NO KEY UPDATE로분리후green2 규칙1+DB8통과. V29기존데이터보존·충돌거부 green3실행중, 독립정적재검토요청.

- D dev2 failed/preservation passed: 브라우저68통과, PG기존예약경합/교차판매자2개 actual deadlock. R-PD05 V29 FK선행 KEY SHARE 확인. lock-red에서 두batch INSERT직후barrier를둔 교차판매자/같은구매자2개 모두 deadlock재현. V30으로 metadata identityFK제거, reservation identityFK유지 및 batch복합FK추가; lock-green 집중6개 진행중.

- D lock-green 규칙1+DB6 모두통과. dev3 전체 실행/소스동결, R-PD05독립정적재검토요청. D실패2102+이전12056 보존.

- D dev3 전체 passed/preservation passed: Java348/browser68/frontend162/Node72, 소스376/inputsSHA 5FFE17663244E3DA156E9D689F2D4EC0E91C056A649E97878A65B7176238E58D. 독립 review1요청/소스동결. 실패14158파일보존.

- D 독립 review1 완료: Java348/browser68/frontend162/Node72, 376소스동일, status/preservation passed, R-PD01~05해결/필수차단0. 실패14158파일보존. A→B→C→D 요청범위완료. README/구현계획/단계별완료기록갱신. 커밋/P08/배포/푸시없음.

- 최종 문서 갱신 뒤 개발/독립 inputs376개와 현재 소스 해시를 다시 대조해 모두 일치했고 P07 문서 로컬 링크 누락0을 확인했다. 추가 git diff 공백 점검은 PostgresJdbcIT.java 끝의 빈 줄1개를 경고했다. 기능/필수 하네스 실패는 아니며 검증된 소스 동일성을 유지하기 위해 마감 후 서식만 별도로 바꾸지 않았다.


## 후속 로컬 커밋 마감 — 2026-10-27

사용자 커밋 요청으로 `.local-tools/p07-commit1-20261027`에서 새 DB·미디어 전체 검증을 실행했다. 종료0/status·preservation passed, Java348/browser68/frontend162/Node72, 필수 누락·실패·skip0. 독립 검토와 소스376개 동일, 과거 실패14158파일 보존. 변경·검토 문서·실패 이력·새 증거를 포함하여 로컬 커밋으로 마감한다. [상세 완료 기록](P07-commit.md). P08·배포·푸시 없음. 앞선 미커밋 기록은 당시 상태로 유지한다.
