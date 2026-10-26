# P07-C 독립 검토

**최신 판정 — 2026-10-27: P07-C 독립 전체 검토 통과. R-PC01~03 해결, 필수 잔여 차단 없음.** 아래 중간 지적·보류는 당시 이력이며 최종 실행은 마지막 절을 따른다. 제품/검사 소스는 수정하지 않았다.

## 범위·현재 판정 — 2026-10-26

**정적 검토: R-PC01~03 보완과 독립 전체 검증 전까지 완료 판정 보류.** 개발1 `.local-tools/p07-c-dev1-20261026` 실행 중에 문서·제품·검사 코드를 읽었다. 검토 세션은 하네스나 테스트를 실행하지 않았고 제품/검사 소스도 수정하지 않았다. 아래는 정적 결함/검사 공백이며 독립 실행 재현과 구분한다.

README, development-workflow, verification-harness, P07 공통 계약, P07-C.md/P07-progress.md와 요구사항의 매대별 배송·할인 순서를 대조했다. B 독립 통과와 개발 세션 실제 사용자의 A~D 순차 요청을 선행 근거로 삼았다. 기준 HEAD는 `cda1486b885a1913714ba70f4a938fbaedb1748a` 위 미커밋 A/B/C 변경이다. D·P08·배포·푸시·커밋은 범위 밖이다.

## 제약 → 검사 연결

| 제약 | 현재 실제 assertion | 현재 판정 |
| --- | --- | --- |
| C01 배송·할인 순서 | shippingThresholdAndDiscountOrder:49999/50000, 쿠폰 전 배송기준, 두 매대76000/73000. quoteShippingAndZeroTotalsUsePromotionOrder: 상품 행사 후45000→배송3000, 전액쿠폰/배송행사 후0 및 동률ID | 코드 확인·독립 미실행 |
| C02 배분·오버플로 | allocationConservesEveryWon:34/33/33·입력 역순·0·long 상한·수량99·불변 List. quoteSnapshotsRemainStable:수량3의 첫 단위34 및 합계 | 코드 확인; 다항목 서비스 통합 보강 필요 |
| C03 현재 서버 입력 | quotesUseCurrentAuthorizedInputs:76000/73000, 회원/비회원 공개합계 동일, 쿠폰 타인/익명/매대·중복·수량 거부, client금액 무시, CSRF403/no-store, 상품숨김 거부 | 코드 확인; 판매 가능 매트릭스 보강 필요 |
| C03 읽기 일관성 | quoteReadSnapshotSurvivesConcurrentPrices:실제 REPEATABLE READ 격리 확인·첫 읽기 뒤 두 가격 변경 commit·기존76000/다음100000 | 코드 확인 |
| C04 스냅샷·지문 | quoteSnapshotsRemainStable:가격/행사 변경 후 새 견적 변경·이전 금액/수량순액 불변, evaluatedAt만 바꿔도 지문 동일, List 수정 거부 | 코드 확인; 정규 순서의 전체 지문 assertion 보강 필요 |
| C05 화면·정밀도 | QuoteStore3개:큰 십진 문자열·BigInt 합계·다른 수량 거부·느린 이전 요청 폐기. quotes.spec PC/모바일:76000→73000→90000→재로드93000·로그아웃 | 코드 확인; 아래 UI/세션 지적 |

## R-PC01 / P2 — 쿠폰 선택 상자의 이름이 옵션 텍스트에 의존

위치: `frontend/src/promotion/QuotePanel.tsx:16`, `frontend/auth-e2e/quotes.spec.ts`의 정확한 getByLabel 검색.

`<label>적용 쿠폰 — slug<select>...options...</select></label>` 구조로 label 텍스트에 '쿠폰 사용 안 함'과 쿠폰 제목들이 함께 포함된다. 실제 label 기반 검색은 단순 `적용 쿠폰 — slug`와 일치하지 않을 수 있다. 특히 새 브라우저 검사는 그 문자열을 exact:true로 검색하므로 선택 상자에 도달하지 못하는 시간초과 위험이 있다. A에서 이미 보완한 select 라벨 유형과 동일한 구조다.

매대 ID를 사용한 고유 id/htmlFor 등으로 label과 select를 명시 연결해 이름을 안정화한다. QuotePanel 렌더 검사에서 옵션 추가·다른 보유 페이지·선택된 쿠폰 옵션 상태에서도 정확한 이름으로 찾고 선택할 수 있음을 assertion한다. 브라우저 검사 이름을 느슨하게 바꾸거나 timeout을 늘리는 방식으로 가리지 않는다. 이번 세션에서는 실제 시간초과를 재현하지 않았으므로 개발1 실패 원인으로 선단정하지 않는다.

## R-PC02 / P2 — 견적의 인증 만료 응답이 공통 로그인 상태에 반영되지 않음

위치: `frontend/src/promotion/QuoteStore.ts:59` request helper 및 catch, QuotePanel의 member 기반 쿠폰 UI.

회원이 쿠폰을 선택한 뒤 서버 세션이 만료되어 `/api/quotes`가401을 반환해도 request helper는 일반 오류만 던진다. catch는 quote만 비우고 selected를 유지하며 AuthStore는 여전히 회원 상태다. 공통 CouponStore와 달리 rejectSession 또는 세션 재확인 경로가 없어, '견적 다시 계산'은 같은 만료 자격/쿠폰 선택으로 반복 요청하고 화면도 회원 쿠폰 UI를 계속 표시한다. 명시적인 서버401을 받았는데 인증 상태와 선택이 정리되지 않는 복구 결함이다.

인증 만료를 공통 세션 상태에 반영하고 이전 쿠폰 선택·견적·진행 중 응답을 폐기하며 재로그인 경로를 제공한다. 403은 CSRF/권한 오류 의미를 구분해 프로젝트의 기존 정책에 맞춘다. 업무409·네트워크 오류를 인증 만료로 오인하거나 조용히 익명 성공으로 전환하지 않는다. 실제 쿠폰을 선택한 상태에서401, 오래된 성공 응답 도착, 재로그인/명시 재조회 복구를 검사한다.

## R-PC03 / P2 — 선택 초기화·다항목 배분·현재 판매 조건의 증명 공백

코드 구조는 전반적으로 계약에 부합하지만 아래는 현재 검사만으로 입증되지 않는다.

1. **선택 초기화 검사의 빈 전제:** `discards stale quotes and clears selections when the session changes`는 쿠폰을 한 번도 선택하지 않고 selected={}인 상태에서 rejectSession 후 다시 {}를 검사한다. 브라우저도 reload로 선택을 먼저 비운 뒤 로그아웃한다. 실제 selected가 비어 있지 않음을 확인하고 세션 종료/계정 교체 및 늦은 응답 후 선택·견적이 없어야 한다. R-PC02의401 경로도 직접 포함한다.
2. **다항목 서비스 배분/정규 지문:**34/33/33은 순수 allocate만 검사하고 DB 견적은 한 SKU의 수량3을 검사한다. 같은 매대의 서로 다른 SKU3개에100원 쿠폰을 넣어 서비스가 반환한 항목34/33/33·수량별합계·매대/부모합계가 일치하는지 검사한다. 요청 SKU/매대/쿠폰 선택 순서를 뒤집어 전체 금액·배분·fingerprint가 동일한지 확인한다. evaluatedAt 제외만으로 전체 정규화 검증을 대신하지 않는다.
3. **현재 판매 가능 조건:**현재 quote 경로는 관리자 상품 숨김 하나만 검사한다. Browse.quoteOffer의 새 projection에 대해 inactive SKU/판매중지 상품/비활성 분류/매대 비공개·정지/판매자 자격상실과 가용량 부족을 실제 견적 거부에 연결한다. A/B의 후보 검사만으로 새 quoteOffer 조합을 전부 증명했다고 표시하지 않는다.
4. **HTTP 계약·부작용:**현재 HTTP는 total만 문자열인지 확인한다. 단위·항목·매대 금액 전체와 혜택 ID/version·응답 필드의 계약을 대조한다. 조회 부작용0 검사는 coupon_event 수와 inventory_reservation 수·선택 쿠폰 DTO만 비교한다. 문서의 발급/인증소비/재고 변경 없음 주장에 맞게 관련 상태·카운터·잔량을 전후 비교하거나 실제 assertion 범위로 표현을 제한한다.

순수 계산에서 BigInteger 중간곱과 checked 덧셈/곱셈을 사용하고, DTO 컬렉션을 방어 복사하며, HTTP 금액을 십진 문자열로 반환하는 점은 확인했다. 기존 SKU 가격 상한999999999·수량99·SKU20 범위에서 기존 준비 내역의 Number 합계가 안전정수 밖으로 나간다는 지적은 하지 않는다. 서버 견적 영역의 정수 문자열 계약은 별도로 유지한다.

## 거래·미검증 범위·후속 절차

JdbcQuotes의 standalone TransactionTemplate은 readOnly와 REPEATABLE_READ를 설정하고 하나의 at으로 행사/쿠폰을 판단한다. 기존 외부 거래에 참여할 때 격리를 자동 승격하지 않는다는 문서 설명도 타당하다. 후속 D/P08은 현재 자원 잠금과 최신 견적 재확인을 자체 검증해야 하며 C의 지문을 권한 토큰으로 취급하면 안 된다. 이번 C의 읽기 일관성 검사만으로 주문 시점의 유효성까지 통과했다고 주장하지 않는다.

개발1 종료 후 보완된 소스/검사 및 동결 상태를 확인하고 새 경로의 `./verify.ps1 -Offline` 독립 전체 실행을 요청받아 수행한다. 필수 누락·실패·skip 또는 제약 근거 미흡이면 완료로 선언하지 않는다. 소스 식별·실행 결과·기존 자원과 실패 manifest를 대조해 최종 판정한다. 이번은 문서만 작성했으며 기존 서버/DB/검증 DB/미디어/데모와 실패 기록을 변경하지 않았다. 과거 B 개발2 등 원인 미확정 실패도 그대로 유지한다.

## 개발2 진행 중 보완 재검토 — 2026-10-26

**R-PC01~03의 수정·검사 연결을 확인했다. 새 확정 결함은 없으며 개발2 결과와 독립 전체 실행 전까지 최종 완료 판정은 보류한다.** 이번 검토도 문서/코드/저장된 증거만 읽고 전체 하네스를 실행하지 않았다.

- R-PC01: QuotePanel label은 매대 UUID 기반 고유 id/htmlFor로 select와 분리됐다. 신규 렌더 단위는 정확한 label 검색·id·label 텍스트를 확인한다. 개발1 result의 failed/preservation passed 및 browser.txt의 PC/모바일 정확한 쿠폰 label 검색 실패2건(기존64통과)을 직접 읽었다. 최초 정적 지적은 개발 실행에서 재현됐고, 원본 실패를 유지한다.
- R-PC02: QuoteStore request가401/403에서 공통 AuthStore.rejectSession을 호출한다. 이는 기존 CouponStore와 같은 정책이며403을 모두 서버 세션 만료의 증거라고 해석하지 않는다. auth 구독이 quote/selected를 비우고 세대/요청을 취소하며, QuotePanel은 공통 오류와 재로그인 링크를 표시한다.409/네트워크 실패는 일반 견적 오류로 남는다. 새 단위는 실제 쿠폰 선택·반환 couponId/selected 확인→과거 요청 보류→새 요청401→회원/선택/견적 초기화→과거 성공 응답 무효→인증 재확인 후 쿠폰 없는 새 견적을 검사한다. 앞선 빈 전제 검사를 이 신규 검사로 보완한다. 브라우저도 reload 뒤 쿠폰을 재선택하고90,000원/선택값 존재를 확인한 후 로그아웃한다.
- R-PC03: quoteMultipleLinesFingerprintAndHttpContract는 같은 매대3 SKU의34/33/33 및 항목 단위합계·매대3200/부모6200, 두 매대의 요청/쿠폰 선택 역순에서 stores/fingerprint 동일을 검사한다. HTTP 최상위 필드 집합과 단위/항목/매대 금액 문자열, coupon version/templateVersion을 확인한다. 별도 배송 검사에서 상품/배송 행사 ID·배송 version·선택 이유도 확인한다.
- R-PC03: quoteReadOnlyState는 coupon_template/issued_coupon/coupon_event/inventory_balance/inventory_reservation/guest_checkout/guest_attempt의 정렬된 전체 행을 견적 전후 비교한다. quoteRejectsEveryUnavailableProjection는 inactive SKU/상품중지/분류비활성/매대비공개·정지/판매자정지·SELLER상실/재고잔량0의8조건을 각각 변경·견적 거부·rollback 후 정상3100으로 확인한다. 이 검사는 외부 거래 안의 projection 거부이며 standalone 격리 증명은 기존 별도 동시 가격 검사에 둔다.

`.local-tools/p07-c-review-green2-20261026/summary.json`의 규칙2+DB3, 실패/오류/skip0과 maven BUILD SUCCESS를 직접 확인했다. 개발 측 집중 실행이며 독립 전체 결과는 아니다. 수정 전/후 집중 검사와 개발1 실패를 새 전체 결과로 덮지 않는다. 개발2 `.local-tools/p07-c-dev2-20261026` 소스 동결을 유지하고 종료 후 독립 전체 검증을 별도 수행한다.

## 최종 독립 실행·판정 — 2026-10-27

개발2 전체 종료/보존 통과와 소스 동결을 확인하고 검토 세션이 직접 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p07-c-review1-20261026`을 실행했다. 실제 실행 시간은 **2026-10-26 23:57:24.6094052~2026-10-27 00:01:41.1426708 KST**다. 경로의20261026은 시작 날짜이며 종료 날짜를 소급하지 않는다. 종료코드0/status=passed/preservation=passed, Java329·브라우저66·프런트161·Node72 통과. 타입·lint·build·하네스 gate와 필수 대조 통과, 실패·skip·누락0. 개별 Java329개 및 coverage40개 모두 passed, frontend/browser coverage도 passed를 직접 확인했다.

기준 HEAD `cda1486b885a1913714ba70f4a938fbaedb1748a` 위 미커밋 A/B/C 소스368개. 개발2 inputs와 경로/해시 차이0, 종료 후 현재 소스 불일치0. inputs SHA256 `C1E3E0B23E6BCA528ADBF20FD618BE3D6C8E8548BF6147857D466018255A27C4`. 검증 후 제품/검사 소스는 변경하지 않고 이 문서와 증거 사본만 추가했다.

### 제약·지적별 최종 실행 근거

| 항목 | 실제 assertion 및 결과 | 판정 |
| --- | --- | --- |
| C01 배송·혜택 순서 | shippingThresholdAndDiscountOrder 및 quoteShippingAndZeroTotalsUsePromotionOrder:49999/50000, 쿠폰전 배송기준, 두 매대76000/73000, 전액쿠폰/상품행사/무료배송 순서와0원·배송행사 동률ID | 통과 |
| C02 배분·정수 | allocationConservesEveryWon:34/33/33·역순·0·Long.MAX_VALUE·수량99·오버플로 거부. quoteMultipleLinesFingerprintAndHttpContract:실제 SKU3개 배분·단위/매대/부모합계·요청/쿠폰 역순 stores와fingerprint 동일 | 통과 |
| C03 권한·현재 입력 | quotesUseCurrentAuthorizedInputs:서버가격·주입값 무시·CSRF·회원/비회원 동일 공개금액·쿠폰 타인/익명/매대/중복 및 수량·관리자숨김 거부. quoteRejectsEveryUnavailableProjection:SKU/상품/분류/매대/판매자/가용량8조건 거부와복원 | 통과 |
| C03 읽기 거래 | quoteReadSnapshotSurvivesConcurrentPrices:실제 REPEATABLE READ와 두가격 동시 변경 중 이전76000/다음100000. quoteReadOnlyState:쿠폰3·재고2·비회원2테이블 전체행 전후 동일 | 통과 |
| C04 불변 근거 | quoteSnapshotsRemainStable:가격/행사 수정 후 새 견적만 변경, 이전 DTO/단위순액 유지, 컬렉션 수정 거부·evaluatedAt 제외 지문. HTTP 금액문자열/쿠폰version/혜택id·version·선택이유 | 통과 |
| R-PC01 라벨 | 고유 htmlFor/id, 정확한 label 렌더 검사 및 실제 PC/모바일 쿠폰 선택 성공 | 해결 |
| R-PC02 세션 복구 | 실제 선택→401 공통 rejectSession→선택/견적 초기화→늦은 성공 폐기→인증 재확인 후 쿠폰 없는 견적. 재로그인 링크,409/네트워크 일반 오류 유지 | 해결 |
| R-PC03 검사 공백 | 위 다항목/역순지문/판매조건/7테이블/HTTP 검사와 실제 선택 존재 후 세션 만료·브라우저 쿠폰 재선택 후 로그아웃을 실행 | 해결 |
| C05 화면·기존 회귀 | QuoteStore5 및 PC/모바일 견적:76000→쿠폰73000→가격변경90000→reload93000→재선택90000/로그아웃, BigInt 큰 금액·잘못된 금액/수량 응답·느린 이전 요청 거부. 기존 JUnit/ArchUnit/위반 fixture 유지 | 통과 |

standalone 읽기 일관성과 미래 D/P08의 잠금·소비 검증은 구분한다. 기존 외부 거래를 자동으로 REPEATABLE READ로 승격한다고 주장하지 않는다. fingerprint는 비교 정보이며 권한 토큰이 아니다. 실제 주문 스냅샷 영속화·주문409·결제/환불은 이번 C에서 구현·검증한 범위가 아니다.

### 보존·실패 이력·미검증 범위

새 DB `d2_it_7bddd4b15a584cdab476e357a95e8158`, 새 미디어 `.local-tools/p07-c-review1-20261026/db/media`, 에뮬레이터 `.local-tools/phone-emulator-52d8297a8ca743058cfd1dc3eceece3d`. 보존 before/after passed: DB 목록211→212, 미디어1640→1658. 기존 서버·컨테이너·개발/데모 DB·과거 검증 DB·미디어를 하네스 정의 범위에서 보존했다. 과거 검증 DB는 존재 목록을 비교하며 전체 행 전수 비교는 아니다.

실행 전후 기존 실패 manifest5620+A2577+B2663+C1196=총12056파일, 누락/해시 불일치0. C 개발1 PC/모바일 라벨 실패2건, 인증 초기화 RED, 미구현·검사 도우미 컴파일 오류 등 준비/집중 실패를 원래 결과로 유지한다. 개발2와 이번 독립 성공으로 과거 실행을 소급 성공 처리하지 않는다. P07-B 개발2 최초 지점 미상의 시간초과 및 과거 P05-D/P06 원인 미확정 이력은 계속 미확정이다.

원본 `.local-tools/p07-c-review1-20261026`을 유지하며 `evidence/P07-C-review-final-*`19개에 실행 결과·inputs·coverage·Java 사례·명령 로그·DB 대상·브라우저 보고서·before/after·소스/실패 대조를 저장했다. [독립 실행 결과](evidence/P07-C-review-final-result.json), [소스·실패 보존 대조](evidence/P07-C-review-final-review-verification.json)를 따른다.

미검증: 실제 SMS·실기기·상용 부하·전체 접근성·사용자 직접 학습, 쿠폰 예약/확정·주문/결제/환불 통합은 후속 범위다. 검토 세션은 문서·증거만 추가했고 제품/검사 수정·D/P08·커밋·푸시·배포는 수행하지 않았다.
