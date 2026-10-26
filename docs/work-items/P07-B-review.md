# P07-B 독립 검토

**최신 판정 — 2026-10-26: P07-B 독립 전체 검토 통과. R-PB01~03 해결, 필수 잔여 차단 없음.** 아래 중간 지적·보류·실패는 당시 이력이며 최종 결과는 마지막 절을 따른다. 제품/검사 소스는 수정하지 않았다.

## 범위·현재 판정 — 2026-10-26

**정적 검토: R-PB01~03 보완 및 독립 전체 검증 전까지 완료 판정 보류.** 개발1 `.local-tools/p07-b-dev1-20261026` 실행 중의 검토다. 검토 세션은 하네스·테스트를 실행하지 않았고 제품/검사 소스도 수정하지 않았다. 아래 결함은 코드에서 확인한 경로이며 실행 재현 결과로 표시하지 않는다.

기준 HEAD `cda1486b885a1913714ba70f4a938fbaedb1748a` 위 미커밋 B 변경. README, development-workflow, verification-harness, P07 공통 계약, P07-B.md/P07-progress.md, 요구사항 F08/매대 범위 쿠폰 정책을 대조했다. A 독립 통과와 개발 세션의 실제 사용자 A~D 순차 요청을 선행 근거로 삼았다. 계획 문서의 초기 미착수 표시는 당시 기록이다.

## 제약 → 현재 assertion 연결

| 제약 | 현재 근거 | 판정 |
| --- | --- | --- |
| B01 기간·최소금액·정률/정액 | couponEligibilityAndSnapshot: 시작 포함/종료 제외, 최소 직전/정각, cap, Long.MAX_VALUE 내부계산, 잘못된 kind/기간/한도 | 코드 확인, 독립 미실행 |
| B02 한도·재요청·감사 | couponIssuanceIsBoundedAndIdempotent: 한도1 서로 다른 회원 한 승자, 동일 회원 동시 동일 결과/카운터1, 발급 감사 실패 시 카운터0/행0 | 코드 확인; 교차 판매자 잠금 회귀 보강 필요 |
| B03 소유권·스냅샷 | couponOwnershipAndTemplateSnapshot: 타인 조회 거부, 수정 전 DTO 유지, 새 발급 새 값/version, 중지 후 기존 발급 retry/사용 유지, 한도 하향 거부, 판매자 본인 발급, HTTP401/403/404/no-store | 코드 확인; 매대 표시·관리 rollback 보강 필요 |
| B04 잠금 뒤 자격·기간 | couponIssuanceRechecksAuthority: 실제 identity_user 대기 관측 뒤 구매자/판매자 계정 정지 또는 issue_end 변경, 발급 카운터0 | 코드 확인; 매대/역할·재요청 경계 보강 필요 |
| B05 화면·유실 | CouponStore 단위3: DTO/기간표시, 중복 claim 차단·CSRF·유실 후 재조회, 세션 종료 후 늦은 응답 폐기. coupons.spec PC/모바일: version409/중지/실제 claim 커밋 후 응답 중단/재요청 동일·조건 유지·로그아웃 | 코드 확인; 독립 미실행 |

## R-PB01 / P2 — 보유 쿠폰의 사용 매대를 화면에서 식별할 수 없음

위치: `frontend/src/pages/CouponPages.tsx:15`, `Coupons.Issued`, `JdbcCoupons.owned/own`.

받을 수 있는 템플릿 카드에는 매대 이름과 링크가 있지만, 발급 후 보유 카드에는 쿠폰 제목·상태·금액·최소금액·기간만 표시한다. Issued에는 storeId만 있고 사용자에게 보이는 매대 정보는 없다. claim 후 available에서는 해당 템플릿을 제외하므로, 서로 다른 매대가 같은 이름/조건의 쿠폰을 발급하면 보유 화면에서 어느 매대에 쓸 수 있는지 구분할 수 없다. '해당 매대'라는 안내만으로는 범위 조건을 확인할 수 없다.

보유/단건 DTO와 화면에 식별 가능한 매대 이름과 탐색 수단을 연결한다. 할인 스냅샷을 현재 템플릿으로 덮어쓰지 말고, 현재 매대 안내와 발급 당시 조건을 구분한다. 매대 정지/비공개 때문에 보유 이력이 목록에서 사라져서도 안 된다. 서로 다른 매대의 같은 제목 쿠폰2개를 발급하고, 새로고침 후 각 적용 매대를 구분하며 발급 중지 후에도 보유 조건이 남는 검사를 추가한다.

## R-PB02 / P2 — 조건 확인 HTTP의 정률 할인액이 안전정수 범위를 벗어날 수 있음

위치: `CouponController.java:27`, `JdbcCoupons.java:83~87`, `Coupons.Eligibility`.

템플릿 금액 입력은 안전정수로 제한하지만 eligibility의 subtotal은 음수가 아닌 long 전체를 받는다. 정상 RATE=10000/cap=null 쿠폰에 subtotal=9007199254740993을 전달하면 내부 할인 계산은 같은 long을 반환하고 discount는 JSON number로 출력된다. JavaScript 소비자는 이를 정확히 표현하지 못한다. B 문서의 HTTP 안전정수 계약과 맞지 않으며 현재 검사는 FIXED100이나 cap100만 사용해 이 경계를 드러내지 못한다.

HTTP subtotal에 안전정수 경계를 적용하거나 해당 HTTP 금액을 명시적인 문자열 계약으로 제공한다. 내부 long 계산의 지원 범위는 유지할 수 있다. MAXSAFE·MAXSAFE+1·MAXSAFE+2와 음수, RATE10000/capnull을 실제 HTTP로 검사하고 응답 계약을 문서화한다. C가 장래 문자열 견적을 사용한다는 설명은 현재 B endpoint의 정밀도 검사를 대신하지 않는다.

## R-PB03 / P2 — 새 잠금·관리 원자성 및 입력 경계의 필수 assertion 공백

아래는 코드 결함 확정이 아닌 제약 증명 공백이다. 기존 공통 테스트 통과나 코드 독해만으로 대신하지 않는다.

- **교차 발급:** UUID 정렬은 코드상 적용됐고 lockActiveBuyer의 false를 구매 거부로 처리하지 않는 것도 맞다. 하지만 현재 동시 발급은 한 판매자의 템플릿만 사용한다. 판매자 A가 B의 쿠폰을, B가 A의 쿠폰을 동시에 받는 두 거래와 동일 회원 경합을 실제 DB에서 검증해 정렬된 계정→매대→템플릿 잠금의 교착 방지 및 한 번 발급을 입증한다.
- **관리 저장 감사/경합:** 감사 실패 주입은 CLAIM에만 적용한다. saveTemplate의 TEMPLATE 감사 실패 시 수정 title/terms/기간/한도/state/version·발급 카운터·기존 발급행·감사 수가 모두 이전 상태인지 검사한다. 같은 version 관리 저장 경합은 한 승자/409이며, 발급과 한도 하향 경쟁에서도 `issued_count<=total_limit`가 유지돼야 한다.
- **현재 자격 및 재요청:** 현 대기는 계정 SUSPENDED만 검사한다. 매대 비공개/정지 또는 SELLER 상실 뒤 신규 발급·available·eligibility가 차단되는 assertion이 필요하다. 이미 발급된 쿠폰의 retry는 템플릿 중지뿐 아니라 기간 종료·판매자 불가 상태에서도 활성 본인에게 같은 결과를 주고, 본인 비활성은 거부하는지 확인한다. 기존 성공의 재요청 정책과 신규 발급 정책을 구분한다.
- **HTTP 입력/노출:** value/cap/minimum/totalLimit를 각각 정상 상대 필드와 상한/초과값으로 검사하고, 관리 입력과 보유/조건 응답의 필드 집합을 확인한다. 기존 검사는 memberId 문자열 부재만 검사한다. 페이지별20개 계약과 현재 회원만의 보유 목록도 실제 API assertion으로 연결한다.

발급 종료 대기 검사는 저장된 issue_end를 현재시각으로 바꾼 뒤 대기 거래를 풀어 최신 기간 재읽기를 확인하는 검사다. 실제 시간이 원래 종료점을 넘는 대기 재현과는 다르다. 현재 구현은 템플릿 잠금 뒤 Instant.now()를 얻으므로 정적 경로는 타당하며, 문서처럼 두 증명의 범위를 구분한다.

## 후속 검증·보존

개발 전체 종료 후 보완 소스/실행 증거를 확인하고, 동결된 동일 소스에서 새 경로의 루트 `./verify.ps1 -Offline` 독립 실행을 별도로 수행해야 한다. 전체 결과·필수 목록·소스 식별·기존 자원·실패 manifest를 대조한 뒤 최종 판정을 기록한다. 이번에는 보존 하네스를 실행하지 않았으므로 새 보존 검사 통과를 주장하지 않는다.

기존 서버·DB·검증 DB·미디어·데모 및 A/과거 실패 이력을 유지한다. 과거 원인 미확정 실패를 후속 성공으로 해결 처리하지 않는다. C/D·P08·제품 수정·커밋·푸시·배포는 이 검토에서 수행하지 않았다.

## 개발2 실행 중 보완 재검토 — 2026-10-26

**R-PB01/02 수정과 R-PB03 주요 assertion 보완을 확인했다. 신규 확정 결함은 없으며 개발2 종료·독립 전체 검증 전까지 최종 해결 판정은 보류한다.** 이번에도 코드/저장된 증거만 읽었으며 하네스를 실행하지 않았다.

- R-PB01: Issued의 storeSlug/storeName과 CouponPage 보유 카드 링크를 확인했다. existing/own/owned는 매대 읽기 JOIN을 사용하되 공개 조건으로 보유행을 거르지 않는다. Terms/제목/기간/templateVersion은 발급행에서 읽는다. 신규 단위는 available이 빈 상태에서도 보유 매대 링크를 검사한다. 브라우저 fixture는 같은 제목의 두 매대 쿠폰을 먼저 발급하고 한 매대만 비공개로 바꾼다. PC/모바일 시나리오는 카드2개·서로 다른 href2개·새로고침 후 카드2개를 검사한다.
- R-PB02: HTTP 경계에서 subtotal<0 또는 MAXSAFE 초과를400으로 거부하며 내부 eligible(long)은 유지한다. 개발 RED `.local-tools/p07-b-review-red2-20261026/maven.txt`의 `subtotal=9007199254740992`, 기대400/실제200을 직접 읽었다. 수정 뒤 couponHttpAmountsAndStoreIdentity는 MAXSAFE·초과2값·음수 및 정률100% 반환, 내부 Long.MAX_VALUE, 보유10필드·Terms6필드 정확한 집합을 검사한다.
- R-PB03: couponCrossClaimsAndManagementTransactions의 교차 판매자 발급·관리 감사 실패 전후 전체 Template/기존 Issued/감사수 일치·동일version 한 승자·한도하향/신규발급 경쟁 한 승자 및 수량 불변식을 확인했다. couponAvailabilityRetryAndPagingBoundaries는 매대 비공개/정지/SELLER상실의 신규발급·available·eligibility 거부, 기존 retry/보유 유지, 발급기간 종료 retry, 본인 정지 거부, 보유22개의20/2 페이지와 회원 격리를 검사한다. value/cap/minimum/totalLimit도 각각 정상 상대 필드와 상한/초과2값/음수를 분리한다.

저장된 `.local-tools/p07-b-review-green1-20261026/summary.json`에서 규칙1+DB4, 실패/오류/skip0과 maven BUILD SUCCESS를 확인했다. 이것은 개발 측 집중 실행 증거이며 검토 세션의 독립 실행이나 전체 통과가 아니다.

추가 범위 주의: 정확한 필드 집합 assertion은 현재 보유 DTO/Terms에 적용돼 있다. Eligibility 및 관리 Template 전체 필드 집합까지 검사했다고 확대해서 기록하지 않는다. 관리 감사 rollback의 전체 DTO 비교는 존재하지만 변경 입력은 kind/cap/minimum을 모두 바꾸지는 않으므로 각 필드별 다른 값 주입을 전수 검사했다는 표현도 피한다.

개발1 시간 fixture 실패에 대해 보고된 Java/DB 시계 차이는 개발 측 관측이다. 현재 소스에서는 fixture 종료값을 Instant.now()-1초로 만들고 available도 단일 Java at을 SQL에 전달하여 claim과 시계를 맞췄다. 이는 잠금 뒤 최신 기간 재읽기와 시계 일관성 보완이며, 원래 종료점까지 자연 경과하는 대기 검사를 새로 증명한 것은 아니다. 브라우저 로그아웃도 성공 안내를 기다린 뒤 탐색하도록 보완했다. 개발1 실패는 원본 그대로 유지하고 개발2 결과로 소급 성공 처리하지 않는다.

## 개발2 실패 확인·개발3 대기 — 2026-10-26

`.local-tools/p07-b-dev2-20261026/result.json`을 직접 읽어 22:54:18~22:58:23 KST, status=failed/preservation=passed를 확인했다. db/browser.txt는63통과/desktop 쿠폰1실패를 기록하며, 실패 stack이 finally의 member.close를 가리켜 최초 대기 지점을 판별할 수 없다. **시간초과의 정확한 원인은 미확정**이다. 모바일 동일 시나리오 통과나 DB 업무 검사 통과를 전체 성공 또는 근본 원인 해결로 해석하지 않는다.

현재 coupons.spec.ts는 retain-on-failure trace·단계 로그·최초 예외 출력/재throw를 추가했다. cleanup은 Promise.allSettled로 두 context를 정리하고 최초 오류가 없었을 때만 정리 실패를 던져 원래 실패를 덮지 않는다. 이 진단 보강 자체가 시간초과를 수정했다는 증거는 아니다. 개발3 `.local-tools/p07-b-dev3-20261026`은 진행 중이며 독립 전체 실행은 아직 시작하지 않았다. 개발 측 실패1698파일 보존 보고는 후속 독립 검증에서 manifest 해시로 별도 확인한다. 이 세션은 검토 문서만 갱신했다.

## 개발3 진단·개발4 대기 — 2026-10-26

개발3 result의 failed/preservation passed와 browser.txt의 목표 쿠폰 click 대기 실패, 실패 trace.zip 존재를 확인했다. 개발 측 trace 분석은 available page0/items20/hasMore=true에 목표 쿠폰이 없었다는 내용이다. 코드에서도 새21개 페이지 fixture가 같은 목록에 포함되지만 이전 시나리오는 첫 페이지에서 목표 받기 버튼을 바로 찾았음을 확인했다. UUID 정렬 때문에 목표가 다음 페이지에 놓이는 경우를 처리하지 못하는 브라우저 검사 결함과 일치한다.

현재 시나리오는 목록 로드 후 목표 버튼이 없으면 실제 '다음 발급 쿠폰' 버튼의 enabled·다음 페이지 번호·로드 완료를 확인하며 탐색한다. 탐색 횟수 상한이 있고 전체 timeout은 늘리지 않았다. 이는 제품 발급 로직 수정이 아닌 검사 탐색 보완이다. 개발4 `.local-tools/p07-b-dev4-20261026` 실행 결과와 독립 전체 검증이 남는다. 개발2는 최초 지점이 가려져 같은 원인으로 확정하지 않는다. 실패2180파일 보존은 개발 측 보고이며 독립 실행 시 manifest를 대조한다. 이번 검토는 문서만 갱신했고 하네스는 실행하지 않았다.

## 개발4 린트 실패·개발5 대기 — 2026-10-26

개발4 result.json의 failed/preservation passed, error=`Frontend lint failed`를 직접 확인했다. lint.txt는 coupons.spec.ts finally 내부 throw에 대한 `no-unsafe-finally`1건이다. 개발 측 Java321/브라우저64 통과 보고와 별개로 전체 실패 판정을 유지한다. 현재 소스는 catch에서 최초 오류를 저장하고 finally에서 정리 결과를 수집한 뒤 바깥에서 재전파한다. 제품 코드와 timeout·검사 조건을 바꾸는 수정은 아니다. 개발5 `.local-tools/p07-b-dev5-20261026` 전체 결과 및 독립 전체 요청을 기다린다. B실패2663파일 보존은 개발 측 보고이며 후속 manifest 대조 대상이다. 검토 세션은 문서만 갱신했다.

## 최종 독립 실행·판정 — 2026-10-26

개발5 result.json의 전체/보존 통과와 소스 동결을 확인한 뒤 검토 세션이 직접 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p07-b-review1-20261026`을 실행했다. 23:15:59.9795032~23:20:28.080828 KST, 종료코드0/status=passed/preservation=passed. Java321(기본160+DB160+실제 SDK1), 브라우저64, 프런트156, Node72 통과. 타입·lint·build·하네스 gate 및 필수 대조 모두 통과, 실패·skip·누락0. 개별 Java321개와 coverage39개 모두 passed, frontend/browser coverage의 passed도 직접 확인했다.

기준 HEAD `cda1486b885a1913714ba70f4a938fbaedb1748a` 위 미커밋 A/B 소스359개. 개발5 inputs와 경로/해시 차이0, 종료 후 현재 소스 불일치0. inputs SHA256 `0C76C92A1DE0CCE7E3523AF59274F89B707B1C81E76AE2D056C41B148032276A`. 검사 이후 제품/검사 소스 수정 없이 검토 문서와 증거 사본만 추가했다.

### 제약·지적별 실행 판정

| 제약/지적 | 실제 실행 assertion | 최종 판정 |
| --- | --- | --- |
| B01 기간·금액 | couponEligibilityAndSnapshot: 시작 포함/종료 제외, 최소 직전/정각, 정률 cap·정액 잔액 제한·내부 long, 잘못된 kind/기간/한도 | 통과 |
| B02 한도·재요청·거래 | couponIssuanceIsBoundedAndIdempotent: 다른 회원 한도1 경쟁 한 승자, 동일 회원 동시 동일 DTO/카운터1, CLAIM 감사 실패 시 카운터/발급 rollback | 통과 |
| B03 소유권·스냅샷 | couponOwnershipAndTemplateSnapshot: 타인/비회원/CSRF 거부, 발급 DTO 불변·새 발급에 새 조건/version, 한도 하향 거부, 중지 후 기존 사용·retry, 판매자 본인도 구매 | 통과 |
| B04 잠금 뒤 자격 | couponIssuanceRechecksAuthority: 실제 identity_user 잠금 대기 관측 뒤 구매자/판매자 정지 및 최신 issue_end 변경 반영·발급0 | 통과 |
| R-PB01 매대 식별 | CouponStore 신규 매대 링크 단위 및 PC/모바일 동일 이름 두 매대/서로 다른 링크/비공개 한쪽 보유 유지/새로고침 검사. 현재 매대 안내와 발급 Terms는 분리 | 해결 |
| R-PB02 HTTP 정밀도 | couponHttpAmountsAndStoreIdentity: subtotal MAXSAFE/초과2값/음수, RATE10000/capnull 실제 HTTP200/400과 정확한 discount, 내부 Long.MAX_VALUE 유지 | 해결 |
| R-PB03 경합·관리 원자성 | couponCrossClaimsAndManagementTransactions: 교차 판매자 발급, TEMPLATE 감사 실패 시 전체 DTO·발급·감사수 유지, 동일version 한 승자/409, 한도 하향·발급 경쟁 후 수량 불변식 | 해결 |
| R-PB03 자격·입력·페이지 | couponAvailabilityRetryAndPagingBoundaries: 비공개/정지/SELLER상실 신규 차단·기존retry/보유 유지, 기간종료retry·본인정지거부, 보유22개20/2·중복 없음·회원 격리. HTTP 입력4필드 독립 상한/초과/음수 및 보유10/Terms6 정확한 필드 집합 | 해결 |
| B05 UI·유실·모듈 회귀 | CouponStore 단위4, coupons.spec PC/모바일: 중복 억제·실제 CSRF·claim 커밋 뒤 응답 중단/재조회·같은 쿠폰 재요청·version409·조건 유지·만료 표시·로그아웃. 기존 CatalogStore와 JUnit/ArchUnit·위반 fixture 유지 | 통과 |

R-PB03의 최초 공백은 위 명시된 assertion과 실행으로 해소했다. 필드 집합 전수 검사는 보유 DTO/Terms 범위이고, 관리 rollback은 전체 DTO 비교와 실제 변경 입력을 사용한다는 앞 절의 범위 설명을 유지한다. 실제 시간이 원래 종료점을 넘어가는 대기와 잠금 중 저장된 기간 변경 검사는 구분한다. 이 제한은 현재 B의 구현 경로·기간 규칙 확인을 대신하거나 과장하지 않는다.

### 보존·실패 이력·한계

새 DB `d2_it_8fcb68ea1a1642fab36e0a9728221ac0`, 새 미디어 `.local-tools/p07-b-review1-20261026/db/media`, 에뮬레이터 `.local-tools/phone-emulator-a59b42e4f83848b4b8db40522f25a57f`. 보존 before/after passed: DB 목록204→205, 미디어1582→1600. 기존 서버·컨테이너·개발/데모 DB·과거 검증 DB·미디어를 하네스 정의 범위에서 보존했다. 과거 검증 DB는 존재 목록을 비교하며 전체 행 전수 비교는 아니다.

실행 전후 실패 manifest 대조: 이전 P06의5620+A2577+B2663=총10860파일, 누락/해시 불일치0. B 개발1 로그아웃 대기 누락·기간 fixture 시계 차이, 개발2 최초 실패 지점 미상의 PC 시간초과, 개발3 페이지 탐색 누락, 개발4 finally throw 린트 실패를 구분해 보존한다. 개발2의 정확한 원인은 여전히 미확정이며 개발3과 같은 원인으로 소급하지 않는다. 개발3 페이지 수정은 이후 개발4/5와 이번 독립 브라우저에서 통과했으며, 개발4는 브라우저 통과와 별개로 린트 실패 이력이다. RED/환경·준비 오류와 과거 P05-D/P06 원인 미확정 실패도 이번 성공으로 덮지 않는다.

원본 경로를 유지하고 `evidence/P07-B-review-final-*`19개 파일에 결과·inputs·coverage·Java 사례·명령 로그·DB 대상·브라우저 보고서·before/after·소스/실패 대조를 저장했다. [독립 실행 결과](evidence/P07-B-review-final-result.json), [소스·실패 증거 대조](evidence/P07-B-review-final-review-verification.json)를 따른다.

한계: 로컬 에뮬레이터·격리 PostgreSQL·Chromium PC/모바일 자동 검사 범위다. 실제 SMS·실기기·상용 부하·전체 접근성·사용자 직접 학습은 미검증이다. 쿠폰 실제 사용/예약/복구와 주문·결제 통합은 D/P08/P09 범위이고 이 검토의 통과 대상이 아니다. 제품/검사 소스 변경·C 이후 구현·커밋·푸시·배포는 수행하지 않았다.
