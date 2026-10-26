# P07-A 독립 검토

**최신 판정 — 2026-10-26: P07-A 독립 전체 검토 통과. R-PA01~05 해결, 필수 잔여 차단 없음.** 아래 사전 지적과 중간 보류는 당시 이력이며 최종 실행·판정은 문서 마지막 절을 따른다. 제품/검사 소스는 수정하지 않았다.

## 범위·검토 상태 — 2026-10-26

개발 세션 실제 사용자 메시지(turn `01a17837-cbee-7922-8bf4-8e6fbec536dc`)의 P07-A~D 순차 실행 요청을 확인했다. 이 검토는 P07-A에 한정한다. 기준 HEAD `cda1486b885a1913714ba70f4a938fbaedb1748a` 위 현재 미커밋 변경을 읽었다. README, development-workflow, verification-harness, P07.md/P07-A.md/P07-progress.md와 요구사항 행사 정책을 대조했다. 계획 문서의 '구현 미착수/문서 작성 요청'은 작성 당시 이력으로, 최신 실제 사용자 지시와 구분한다.

개발1 하네스 실행 중의 정적 검토다. 이 세션은 하네스를 실행하지 않았으며 이 문서만 작성했다. 제품/검사 소스·기존 서버/DB/미디어·실패 기록은 수정하지 않았다. 개발 전체 통과·소스 동결 후 독립 전체 검증을 별도로 요청받아 실행한다. P07-B 이후 구현·커밋·배포·푸시는 이 검토 범위 밖이다.

## 제약 → 검사 연결

| 제약 | 현재 검사/필요 assertion | 실행 판정 |
| --- | --- | --- |
| 기간·정액/정률·상한 | PromotionRulesTest#periodAndDiscountBoundaries: 시작 포함/종료 제외,10001×1000bp 버림, 할인≤base,Long.MAX_VALUE 정률 계산. 동률·무료 상한 정책은 아래 보완 필요 | 독립 미실행 |
| 소유권·version·감사 원자성 | PostgresJdbcIT#promotionOwnershipVersionAndAudit: 타 매대 target 거부, 같은 version 한 승자, 감사 INSERT 실패 시 state/version rollback, 보관 후 수정 거부 | 독립 미실행 |
| 공개 조건·시간·회원 동일성 | promotionPublicationAndTime: 시작/종료 후보, 회원/비회원 동일 JSON, 숨김 상품 제외. 매대/판매자/분류/대상 조합 추가 필요 | 독립 미실행 |
| 잠금 후 권한 | promotionAccessAfterLockWait: 실제 identity_user 잠금 대기 관찰 후 SUSPENDED 갱신, 쓰기 거부/DRAFT 유지 | 독립 미실행 |
| HTTP·모듈 | 쓰기 CSRF403, 기존 ArchUnit 및 오류 계약. 공개 필드/정밀도/페이지 경계 추가 필요 | 독립 미실행 |
| UI 상태·유실 | PromotionStore.test의 DTO/한국시간2개, promotions.spec의 PC/모바일 생성/수정응답 유실/409 재조회/중지·보관/배송 생성. 생성 응답 유실과 페이지 검사는 별도 보완 필요 | 독립 미실행 |
| 전체·보존 | 새 독립 ./verify.ps1 -Offline, 필수목록/소스/보존/실패 manifest 대조 | 미실행 |

## R-PA01 / P2 — 저장 가능한 금액과 JSON/프런트 정밀도 계약 불일치

위치: JdbcPromotions.validate, Promotions.Input/Promotion, frontend PromotionStore.promotionValid.

서버는 FIXED value/cap을 Long.MAX_VALUE까지 받아 저장하고 JSON number로 반환한다. 화면은 Number.isSafeInteger만 허용한다. 예를 들어 value=9007199254740992인 유효한 정액 행사를 API로 등록하면 서버는 성공하지만 관리·공개 화면 검증에서 거부한다. 홀수 큰 정수는 JSON 파싱 때 이미 반올림될 수 있다. 잘못된 항목 하나로 목록 전체가 표시되지 않을 수 있다. version에는 안전 정수 경계가 있지만 value/cap에는 없다.

HTTP 금액을 안전 정수로 제한하거나 decimal string 직렬화/파싱을 채택해 양쪽 계약을 일치시킨다. 내부 할인 함수의 long 계산 범위를 줄일 필요는 없다. 안전 정수 최댓값/그 다음 값/큰 홀수 및 cap에 대해 API 저장·조회·프런트 왕복 결과를 assertion한다.

## R-PA02 / P2 — 공개 행사 목록의 다음 페이지와 상세 진입 누락

위치: PublicPromotionPage.

서버 published(page)는20개와 hasMore를 반환하지만 화면은 항상 `/api/events` 첫 페이지만 가져오고 page/hasMore를 사용하지 않는다. 21번째 이후 공개 행사는 목록에서 접근할 수 없다. 목록 항목에도 `/events/{id}` 링크가 없다. 서버 페이지 정책과 사용자 조회 경로를 연결해야 한다.

21개 이상의 활성 행사 fixture에서 다음/이전 페이지·중복/누락 없음·상세 진입을 PC/모바일로 검사한다. 페이지 전환 중 늦은 응답이 현재 페이지를 덮지 않는지, 실패 재시도와 종료/숨김 제외도 확인한다. 공개 상세에서 어느 매대/상품 대상인지 알 수 있는 최소 안내도 함께 검토한다. 현재 응답에는 대상 ID가 있지만 화면에는 대상 정보가 없다.

## R-PA03 / P2 — 무료 배송과 상한 조합이 화면 약속과 다름

위치: PromotionRules.discount 및 JdbcPromotions.validate, PromotionForm/PublicPromotionPage.

SHIPPING/FREE/value0/cap1000 입력은 허용된다. base3000이면 discount는 cap을 적용해1000만 할인하지만 공개 화면은 '무료 배송 · 최대 1,000원'으로 표시한다. 결제 전 안내라 해도 '무료 배송'과 실제 규칙이 불일치한다. 무료 배송은 base 전액을 할인하도록 cap을 거부/비활성화하거나, 부분 지원을 별도 방식으로 명확히 표현해야 한다. 계획의 '배송 정액/무료'와 일치하는 정책을 확정하고 FREE+cap(null/0/양수) DB/API/단위·화면 기대값을 검사한다.

## R-PA04 / P2 — 계획의 공개·선택·실패 복구 검사 근거 부족

현재 검사에서 다음을 직접 입증하지 못한다. 기존 검사로 충족하면 정확한 메서드/해당 assertion을 연결하고 중복 구현은 피한다.

- P07-A A1에 명시한 최대 할인1개·동률 UUID 문자열 정규 순서: 현재 PromotionRules에는 선택 함수가 없고 후보는 DB p.id 순서만 제공한다. 후보 정렬은 할인액 최대 선택을 대신하지 않는다. 전체 견적은 C 범위지만 A가 넘길 선택 계약의 구현 위치/순수 검사 또는 명시적인 단계 경계를 확정해야 한다.
- 공개 SQL은 판매자 ACTIVE/SELLER, 매대 OPEN/visible, 상품 ACTIVE/관리자숨김/분류 active를 직접 조합한다. 현재 신규 공개 검사는 관리자 숨김만 바꾼다. 매대 정지/비공개, 판매자 정지/역할 상실, 비활성 분류·상품, 일부 target만 공개인 복합 행사, 배송 행사 공개/종료를 assertion한다. catalog와 같은 read projection 사용 사실만으로 모든 공개 조건을 검증했다고 표시하지 않는다.
- 실제 잠금 후 정지는 검사하지만 시간은 candidates의 명시 at 경계만 검사한다. 현재 공개 읽기는 단일 statement snapshot이며 잠금 대기가 없는 구조다. '실제 잠금 대기 후 만료' 요구가 save의 ACTIVE 전환 제한인지 미래 견적 재검증인지 작업 계약을 명확히 하고, 해당 경로의 현재시각 재검사를 검증한다. 만료 행사 편집을 허용할 정책이라면 이를 무조건 오류로 바꾸지 않는다.
- 감사 실패 검사에서 state/version 외 target/title/금액/행사 이력 개수까지 rollback되는지 확인한다. 현재 테스트는 동일 target/조건을 보낸다.
- 공개 JSON은 관리 Promotion DTO와 동일해 storeId/state/version 등도 그대로 반환한다. 계획상 별도 PublicPromotion 최소 필드 계약 대신 공유 DTO를 선택한 근거를 기록하고 노출 허용 필드의 정확한 집합을 HTTP assertion한다. 현재 필드 자체가 비밀이라는 판단은 아니다.
- 브라우저는 PUT 커밋 후 응답 유실만 검사한다. POST 생성 응답 유실 후 목록 재확인으로 생성 여부를 복원하고 무심코 재등록해 중복을 만들지 않는 사용자 흐름을 검증한다. 현재 단위2개는 DTO 검증/시간 표시만 검사하며 중복 요청·늦은 응답·로그아웃 중 상태 폐기는 공통 CatalogStore의 구체 회귀와 연결해야 한다.

## 구조 확인·판정

save는 Stores의 거래 내 권한 잠금을 먼저 얻고 Products.lockForMedia로 대상 소유권/보관 상태를 확인하며 version을 잠금 뒤 다시 읽는다. 감사 event는 같은 거래에 저장한다. PUBLIC은 같은 SQL snapshot에서 현재 공개 조건을 확인하며 익명/회원은 같은 endpoint를 쓴다. 타 모듈 테이블 직접 쓰기는 발견하지 않았다. 외부 모듈 테이블 read projection은 기존 catalog 패턴과 유사하지만 스키마 의존이 있으므로 문서에 구분한다.

현재 R-PA01~04 미해결. 개발1 결과와 추가 검증 전에는 P07-A 완료로 판정하지 않는다. 실제 주문 견적·쿠폰·결제/예약은 이번 검토에 포함하지 않는다.

## 개발2 동결 소스 후속 정적 검토

수정/추가 assertion을 읽었으며 하네스는 실행하지 않았다. 추가 확정 제품 결함은 발견하지 않았다. 아래 항목의 최종 해결은 개발/독립 전체 실행 결과와 연결해 판정한다.

- **R-PA01:** validate가 inputAmount를 호출하여 value/cap을 각각 JS 안전 정수 이하로 제한한다. 내부 discount의 long/BigInteger 계산은 유지한다. 신규 HTTP 검사에서 안전 최댓값201 및 다음 값/큰 홀수400, 성공 응답의 value/cap 원값을 대조한다. 프런트도 안전 정수를 요구한다.
- **R-PA02:** 공개 페이지 상태/page 쿼리와 이전/다음, 상세 링크, store/target 표시가 추가됐다. SQL은 같은 statement에서 매대와 공개 target의 slug/title을 조회하고 각 target 배열을 같은 ID 순서로 정렬한다. 21개 생성 fixture로 첫20개/다음 페이지와 상세·매대 링크를 확인하는 PC/모바일 시나리오를 추가했다.
- **R-PA03:** FREE는 cap이 null이 아니면 거부하도록 변경됐다. DB 서비스 검사에는 cap0/1000 거부와 cap없는 FREE 공개/종료가 포함된다. 부분 지원을 무료 배송으로 수락하던 계약은 제거됐다.
- **R-PA04 주요 보완:** best는 할인액 내림차순·UUID 문자열 오름차순 선택을 제공한다. 공개 매트릭스는 매대 비공개/정지, 회원 정지, SELLER 역할 제거, 분류 비활성, 상품 중지와 일부 비공개 target 제외를 추가한다. 감사 실패에서는 title/value/targets/state/version/event 개수를 대조한다. POST201 후 응답 abort→목록 재조회→생성 항목1개 assertion이 추가됐다. A의 과거기간 편집은 허용하고 공개 조회는 단일SQL시각 snapshot, 실제 소비 잠금 후 만료 재검사는 C/D로 구분한 결정을 P07-progress에서 확인했다. A 읽기에 존재하지 않는 잠금 대기 경로를 검사했다고 주장하지 않는다.

### 남은 작은 검사 보완 — R-PA04 유지

현재 공개 JSON 검사는 `tree.size()==15`, actorId 부재, 일부 필드만 확인한다. 이는 **정확한15필드 집합** 검사가 아니다. 다른 필드가 없어지고 불필요한 필드가 들어와도 개수15이면 통과할 수 있다. 합의한 최상위 필드 이름 집합과 target 내부 허용 필드 집합을 정확히 대조한다.

금액 HTTP 경계 검사는 value와 cap을 동시에 같은 큰 값으로 넣는다. cap 검증이 빠져도 value 거부로 통과하므로, 정상 value+초과 cap(및 정상 cap+초과 value)을 분리해 assertion한다. 순수 규칙 검사에서 이미 각 조건을 독립 검증했다면 그 메서드/행과 연결하고 HTTP 경계의 한계를 명확히 남긴다.

현 단계는 R-PA01~03 수정 정적 확인, R-PA04 대부분 보완 및 위 assertion 연결 대기다. 개발2 실행 결과·소스 동결·독립 전체 검증 전에는 완료 판정하지 않는다. 문서만 수정했다.

## R-PA05 / P2 — 대상 상품 보관 후 행사 중지·보관도 거부됨

개발 세션이 발견한 경계를 현재 동결 소스로 확인했다. `JdbcPromotions.save:75`는 요청 상태와 무관하게 모든 target에 `Products.lockForMedia`를 호출한다. 해당 catalog 구현은 소유권 확인/잠금 뒤 상품 state=ARCHIVED이면409를 던진다. 따라서 공개 행사에 연결된 상품을 나중에 보관하고, 기존 target을 유지한 채 행사만 STOPPED 또는 ARCHIVED로 저장하면 실패한다. 상품 일부만 보관돼도 동일하다. 정적으로 확인한 결함이며 아직 이 세션의 실행 재현은 없다.

공개 SQL은 보관 상품을 제외하므로 보관 상품에 할인이 새로 노출되는 문제는 아니다. 행사 종료·이력 관리가 막히는 기능 결함이다. 사용자가 대상을 다른 상품으로 바꾸거나 SHIPPING으로 바꿔야만 종료할 수 있게 하는 우회는 기존 행사 의미를 불필요하게 바꾸므로 적절한 해결이 아니다.

최소 수정 방향은 미디어 편집의 '보관 상품 편집 금지'와 행사 종료의 '본인 소유 대상 확인'을 분리하는 것이다. catalog.api에 좁은 소유권/잠금 계약을 제공하거나 행사 상태 전환용 계약을 둔다. 기존 lockForMedia의 규칙을 전체적으로 약화하지 않는다. ACTIVE의 보관 target 허용 여부는 별도로 정하되, 새 target 추가·타 매대 target·존재하지 않는 target을 종료 요청이라는 이유만으로 허용하지 않는다. 이전 행사 target을 유지하는 종료와 임의 target 교체를 구분한다.

필수 assertion: (1) ACTIVE 행사→연결 상품 ARCHIVED→동일 target의 STOPPED 성공→ARCHIVED 성공, version/감사 증가 및 공개 제외, (2) 일부 target 보관의 복합 행사도 종료 가능, (3) 타 소유자·잘못된 version은 계속 거부, (4) 종료 요청의 target 조작은 소유권 정책 유지, (5) 감사 실패 시 상태/target/version rollback. 상품 보관과 행사 종료의 동시 요청도 기존 잠금 순서를 유지해야 한다.

개발2의 추가 실패와 개발3 실행은 개발 세션에서 진행 중이며 이 문서는 그 결과를 통과로 표시하지 않는다. 정확한 공개 필드 집합/금액 독립 경계 보완의 최종 확인과 R-PA05 해결 후 새 전체 증거가 필요하다. 이 세션은 검토 문서만 수정했으며 하네스는 실행하지 않았다.

## 최종 독립 실행·해결 판정 — 2026-10-26

개발5 종료와 소스 동결을 확인하고 검토 세션이 직접 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p07-a-review1-20261026`을 새 경로에서 실행했다. 22:27:18.0107264~22:31:29.0695744 KST, 종료코드0, status=passed/preservation=passed. Java314(기본159+DB154+실제 SDK1), 브라우저62, 프런트152, Node72 통과. 타입·lint·build·하네스 gate 및 필수 대조 통과, 실패·skip·누락0. Java 개별314개와 coverage38개 모두 passed를 직접 확인했다.

기준 HEAD `cda1486b885a1913714ba70f4a938fbaedb1748a` 위 미커밋 P07-A 소스349개. 개발5 inputs와 경로/해시 차이0, 실행 후 현재 소스 불일치0. inputs SHA256 `07CC42C0F1BD0C75CC948F21D7BF99729BAFC0F4C8B19882C77BF05EDE34E021`. 이 결과는 검토 문서 변경을 포함한 새 커밋을 생성했다는 뜻이 아니다.

### 제약·지적별 최종 근거

| 항목 | 실제 assertion 및 수정 | 판정 |
| --- | --- | --- |
| R-PA01 금액 계약 | PromotionRules.inputAmount와 promotionPublicationMatrixAndNumericContract: value/cap 각각 정상 상대 필드와 MAXSAFE·상한+1·상한+2를 조합해201/400 및 정확한 반환값 검사 | 해결 |
| R-PA02 공개 탐색 | PublicPromotionPage 페이지/상세/매대·대상 링크, PromotionStore 단위와 PC/모바일 공개 목록 시나리오:21개 생성, 첫20개·다음 페이지 중복 없음, 상세 URL·article1개·매대 href | 해결 |
| R-PA03 무료 배송 | freeShippingCannotPromisePartialDiscount와 실제 DB/HTTP 연계 매트릭스: FREE cap0/1000 거부, capnull 전액 할인. 폼은 FREE value0/capnull 강제 | 해결 |
| R-PA04 검사 공백 | safeInputsAndDeterministicMaximum 최대 할인/UUID 문자열 동률, periodAndDiscountBoundaries 기간·버림·long 중간곱, publication 매트릭스 판매자/매대/역할/분류/상품/일부 대상 제외 및 배송 종료, 공개15필드·target3필드 정확한 집합, 회원/비회원 JSON 일치 | 해결 |
| R-PA04 거래·UI | promotionOwnershipVersionAndAudit 동일version 한 승자·타인 거부·감사 실패 시 title/value/target/state/version/event 유지; promotionAccessAfterLockWait 실제 잠금 대기 뒤 정지 거부; 브라우저 POST/PUT 서버 커밋 후 응답 중단·명시 재조회·409 복구. 공통 CatalogStore의 CSRF/늦은 응답/로그아웃 회귀 유지 | 해결 |
| R-PA05 보관 대상 종료 | 동일 target 집합의 STOPPED/ARCHIVED만 owner/store 잠금 안에서 Products.own 확인. 변경 대상·신규·ACTIVE는 lockForMedia 유지. promotionArchivedTargetsCanBeRetiredWithoutBypassingOwnership: 종료 성공·stale/타인/없는 대상 거부·감사 rollback·이력4개·공개 제외, 실제 Products.save 보관 거래와 복합 대상 종료의 잠금 대기 및 version2/동일 대상 확인 | 해결 |
| 모듈·보안·전체 | 기존 JUnit/ArchUnit 위반 fixture 및 실제 모듈 검사, CSRF403·공개 no-store, 전체 기존 필수 목록 유지 | 통과 |

R-PA05의 소유권 읽기는 catalog 쓰기와 공유하는 owner/store 잠금을 이미 획득한 거래 안에서 수행하므로 해당 읽기를 위해 기존 미디어 편집 규칙을 완화하지 않았다. R-PA04 시간 정책은 만료 행사 편집 허용·공개 조회 단일 SQL 시각 판정으로 명시됐다. 실제 소비 잠금 후 만료·최종 견적 재검증은 C/D의 계약이며 A에서 실행했다고 주장하지 않는다.

### 보존·실패 이력·제한

새 DB `d2_it_ab0fd78ed99d4fafa7502c29496d0049`, 새 미디어 `.local-tools/p07-a-review1-20261026/db/media`, 에뮬레이터 `.local-tools/phone-emulator-36494c1003ae4ac4b8c93f3b52685586`. before/after 보존 passed: DB 목록194→195, 미디어1470→1488. 기존 서버·컨테이너·개발/데모 DB 및 이전 검증 DB·미디어를 하네스 정의 범위에서 보존했다. 과거 검증 DB는 존재 목록 비교이며 모든 행을 전수 비교한 것은 아니다.

독립 실행 중 수집한 기준과 종료 후 대조에서 기존 A2162+B405+C791+D882+D커밋5+E1375 및 P07-A2577, 총8197개 실패 증거 해시 불일치0. P07-A 개발1 라벨 검색, 개발2 상세 전환/키보드 동기화 및 개발2·3 잘못된 매대 fixture, 개발4 필수 이름 대조 실패를 그대로 보존했다. 개발4는 실제 실행 검사 통과와 별개로 최종 gate 실패이며 성공으로 소급하지 않는다. 이름 교정은 실제 XML의 describe 접두어와 맞춘 것이며 필수 검사 삭제·축소가 아니다. R-PA05 집중 실행의 환경변수/경로 준비 실패와 실제 결함 RED도 원래 결과로 유지한다. 과거 P05-D 연결·P06-A/B 기동·P06-C 시간초과의 원인 미확정 이력은 이번 성공으로 해결 처리하지 않는다.

원본은 `.local-tools/p07-a-review1-20261026`에 유지하고 `evidence/P07-A-review-final-*`19개 파일에 결과·입력·coverage·Java 사례·명령 로그·DB 대상·브라우저 보고서·before/after·소스/실패 대조를 저장했다. [실행 결과](evidence/P07-A-review-final-result.json), [소스·실패 보존 대조](evidence/P07-A-review-final-review-verification.json)를 따른다.

제한: 로컬 에뮬레이터와 Chromium PC/모바일 자동 검사 범위다. 실제 SMS·실기기·상용 부하·전체 접근성·사용자 직접 학습, 주문/쿠폰/결제와 최종 할인 소비 통합은 검증하지 않았다. 검토 세션은 문서·증거 사본만 추가했으며 제품/검사 변경·P07-B 이후 구현·커밋·푸시·배포는 수행하지 않았다.
