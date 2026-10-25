# P05-B 독립 검토

2026-10-25. `codex/p05-b`, 기준 `a069bf2154718071bf68d6bab77ac651c6bed32d`의 미커밋 구현.

**최종 판정: 마감용 catalog 검사 보완본까지 독립 전체 하네스·보존 검사 통과. 확인된 필수 잔여 차단 없음.** R-B01~03 및 이전 실행은 당시 기록으로 유지하고 최신 결과는 마지막 절을 따른다. 제품·검사·하네스 수정, 커밋·푸시·P05-C 진행은 검토 세션에서 하지 않았다.

## R-B01 — P2: 방문자 담기 합산이 탭 잠금 밖의 오래된 화면 수량을 사용

`frontend/src/cart/CartStore.ts`의 `add`는 `this.state.items`의 수량에 증가량을 더한 뒤 `set`을 호출한다. `set`은 이후 Web Lock을 얻어 `guest.set(sku, quantity)`로 절대 수량을 저장한다. 잠금 안에서 최신 저장 수량을 다시 읽고 합산하지 않는다.

두 탭이 같은 SKU의 수량 1을 표시하는 상황에서 A가 1개를 추가한 후 B의 storage 이벤트 처리가 늦거나 B 작업이 이미 잠금 대기 중이면, B도 1+1=2를 저장할 수 있다. 두 번의 담기 결과가 3이 아닌 2가 된다. Web Lock은 쓰기 실행만 직렬화하며 앞서 계산한 수량의 최신성까지 보장하지 않는다. storage 이벤트가 실행 중 작업을 취소하는 경우도 사용자 변경의 성공/충돌 처리를 별도로 확인해야 한다.

보완: 방문자 증가 동작은 잠금 안에서 `guest.read()`의 최신 값으로 합산하고 99 상한을 적용한다. 수량 직접 편집은 절대 수량 계약을 유지한다. 두 CartStore/실제 두 탭이 모두 오래된 값을 읽은 뒤 담기를 요청하도록 장벽을 두어 성공한 두 증가가 한 번씩 반영되는 회귀를 추가한다. 이벤트로 취소되면 성공 안내 없이 명확히 재확인/재시도 가능해야 한다.

## R-B02 — P2: 회원 조회에는 예상 계정 사전조건이 없어 다른 계정 응답을 수용 가능

`CartStore.refresh`의 회원 조회는 `send('/api/cart')`이며, `CartController.get`은 현재 쿠키 principal의 장바구니를 반환한다. 응답 `View`에는 owner가 없고 프런트의 `check`는 로컬 AuthStore의 id만 비교한다. PUT/DELETE/merge의 expected-owner 검사는 이 GET 경로에 없다. `discardGuest` 뒤의 GET도 같다.

예상 경합: 탭 A의 AuthStore는 회원 A로 확인된 상태 → 다른 탭에서 B 로그인으로 공유 세션 쿠키 변경 → BroadcastChannel/포커스 인증 재확인보다 먼저 A 탭 GET cart 실행 → 서버는 B cart 반환 → A 탭 로컬 id는 아직 A이므로 응답을 정상 수용한다. 늦은 응답의 세대 번호만으로는 서버가 어느 계정의 응답을 보냈는지 판별할 수 없다. 이는 변경 요청의 잘못된 대상 방지는 구현됐지만 조회의 계정 격리 계약은 같은 수준으로 보호되지 않는 경로다.

보완: GET도 예상 owner 사전조건을 보내 principal 불일치를 거부하거나, 응답 소유자를 포함하고 요청 시점 owner와 엄격히 대조한다. `refresh` 및 `discardGuest` 두 경로에 적용한다. 로컬 auth=A를 유지한 채 서버 세션=B인 응답 경합에서 B 품목을 표시하지 않는 검사와 실제 HTTP 조회 사전조건 검사를 추가한다.

## R-B03 — P2: 다중 탭·계정 변경·복구 경계의 필수 assertion 부족

현재 `Cart.test.tsx`의 CartStore 검사는 `work => work()`를 잠금 대신 주입한다. `cart.spec.ts`는 단일 page 흐름이다. 따라서 B05/B06의 Web Locks 직렬화, 두 탭의 동시 병합/ack, pending journal의 다른 계정409 후 보존·복구를 검사하지 않는다. 늦은 회원 조회 검사도 회원→익명만 다루며 회원 A→B를 다루지 않는다.

`저장 거부와 병합 확인 정리 실패` 검사는 removeItem이 항상 첫 호출부터 실패한다. 실제 복구 설계에서 중요한 guest 품목 삭제 성공 후 journal 삭제 실패의 중간 상태는 확인하지 않는다. 브라우저 시나리오 이름에 재시도가 들어 있지만 응답을 실제로 유실시키지 않고 정상 완료 뒤 새로고침만 한다. 상태 단위의 유실 검사는 별도 존재하므로 이 차이를 기록하고 전체 브라우저 유실 검증으로 과장하지 않아야 한다.

보완: R-B01/R-B02 회귀와 함께 두 탭/동등한 결정적 잠금 장벽, 회원 A→B 및 journal409 보존, 두 번째 removeItem만 실패 후 동일 requestId 복구·중복 합산 없음의 assertion을 연결한다. 주요 Web Locks 실제 연결은 두 browser page가 같은 context의 저장소를 공유하는 검사로 확인한다. 해당 이름을 필수 목록에 넣고 B05~B07 매핑을 갱신한다.

## 확인한 구조와 후속 검증

- 서버 변경은 회원 행→cart 행 잠금 순서를 사용하고 BUYER를 잠금 뒤 재검사한다. 병합은 전역 request UUID에 소유자/정규화 payload를 결합하며 capacity 초과는 트랜잭션 rollback한다. 실제 DB 검사에는 동일 ID 동시 호출·payload/owner 충돌·버전 경합·정지 잠금 대기가 있다.
- SKU 외래키 없이 사라진 SKU 식별자를 유지하는 것은 비판매 항목을 안내/삭제한다는 계약에 맞는다. `Browse.forSku`는 공개 detail에서 해당 활성 SKU만 남기고 비공개/비활성/없는 SKU는 null로 반환하는 경로다.
- journal은 requestId와 SKU/수량만 저장하며 회원 ID·인증·가격을 localStorage에 복사하지 않는다. 서버 처리 여부가 불확실할 때 같은 ID를 유지하는 방향은 적절하다. 위 복구 경계는 별도 실행 근거가 필요하다.
- preview의 익명 허용과 CSRF, 변경 owner 사전조건, 수량/100종 상한, 재고·예약 무변경 검사가 존재한다. 코드에 검사가 있다는 것과 독립 실행 통과는 구분한다.

현재 B01~B08 완료 판정은 보류한다. 개발 보완과 전체 하네스 성공·소스 고정 안내 후, 새 고유 DB·미디어 경로에서 `./verify.ps1 -Offline`을 직접 실행하고 결과/소스 해시/보존 증거를 추가한다. 기존 실패 기록은 유지한다.

## 보완 코드·assertion 재검토

- R-B01 해결: 방문자 `add`는 `run`의 Web Lock 안에서 최신 `guest.read()`로 수량을 합산한다. 서로 같은 오래된 수량1을 가진 두 CartStore가 직렬 잠금에 동시 add를 제출하는 단위 검사에서 최종3을 확인한다. 실제 브라우저 두 page 검사는 동일 이름의 Web Lock을 먼저 점유하고, 담기 대기 중 저장값이 없는지 확인한 뒤 잠금을 열어 다른 탭에 수량1→2가 전파되는지 검사한다. 실제 브라우저에서 두 번의 동시 add 자체를 수행한 것은 아니며 결정적 동시 합산 단위 검사와 실제 잠금/저장 이벤트 검사를 결합한 근거다.
- R-B02 해결: GET `/api/cart?owner=`도 controller에서 principal과 예상 owner를 비교한다. refresh와 discardGuest 뒤 조회 모두 이 사전조건을 보낸다. HTTP 불일치409 및 로컬 A 인증/서버 계정 불일치 시 두 경로의 품목 비노출 검사, A→B 전환 후 늦은 A 응답 무시 검사가 있다.
- R-B03 해결: 품목 삭제 성공 후 journal 삭제만 실패하는 상태에서 같은 requestId/items를 복구하고 정리하는 검사, 다른 계정의 pending 병합409에서 journal/품목 보존 검사, A→B 및 로그아웃 늦은 응답 검사, 실제 두 탭 잠금 검사로 보완됐다. 브라우저는 이제 `route.fetch()`로 서버 병합을 처리한 후 첫 응답을 abort하고 재시도 ID 동일·서버 version2·품목99를 확인하므로 응답 유실 후 중복 반영 방지를 실제 DB와 연결한다. 모든 가능한 탭/중단 순서의 전수 검증을 뜻하지 않는다.
- 추가 수정: AuthProvider의 location.key 변화 시 invalidate로 계정 화면 unmount.stop 이후 장바구니 경로에서 인증을 다시 확인한다. PC/모바일의 로그아웃→장바구니 빈 상태 회귀에서 확인했다. quantity/version은 웹 DTO BigDecimal의 exact 변환과 ArithmeticException→400으로 소수 절삭을 막는다. PUT 수량/버전, preview/merge 수량1.5의 400 assertion을 확인했다.
- 기존 인증 fixture: `bad`→`invalid-email-input` 한 값 변경이며 401/code/header requestId/location/전체 본문 비노출 assertion은 유지했다. 저장된 collision JSON은 정상 UUID가 `bad`를 포함할 수 있다는 결정적 문자열 예시다. 과거 실패의 실제 응답은 없으므로 해당 실패 원인을 UUID 충돌로 확정하지 않는다. 새 malformed 입력도 원래 검사 목적을 유지하며 이번 독립 실행에서 해당 메서드가 통과했다.

## 최종 독립 전체 실행

2026-10-25 14:24:25~14:27:29 KST. 개발 실행 종료와 소스 고정 후 이 검토 세션에서 직접 실행했다.

```powershell
./verify.ps1 -Offline -EvidenceDirectory .local-tools/p05-b-review-final-20261025
```

- exit=0, result status=passed / preservation=passed.
- Java245: 기본 JUnit139(ArchUnit18 포함)·실제 DB106, 실패/오류/skip0. 브라우저28, Vitest114, HTTP/프록시 Node68, 하네스 거부 회귀13 통과. 타입·린트·빌드 통과. 이전 필수 검사 삭제0.
- 기준 HEAD `a069bf2154718071bf68d6bab77ac651c6bed32d`의 미커밋 P05-B 소스269개. 개발 최종 `.local-tools/p05-b-auth-fixture-full-20261025`와 각 경로/해시 전부 일치하고 실행 중·종료 후 현재 소스 해시 불일치0. inputs.json SHA256 `9311DAFC180C1F96F8A6131AE86D76E5AF84A69F18F6F7B8BF7BF9647CB04C50`.
- 새 DB `d2_it_adcea908e79049f580725d47bd422a6c`, 새 미디어 `.local-tools/p05-b-review-final-20261025/db/media` 사용. 기존 테스트 서버 DB 목록144개 전부 유지→145개, 기존 미디어657개 해시 유지→675개. 새 DB·미디어·기존 실패 기록 모두 남겨 두었다.
- 개발/테스트 DB 덤프, 개발 DB 목록5개, 기존 컨테이너/알려진 리스너 보존 통과. 데모 `app_42326df08aff4829b301a47e40379368` 덤프 SHA256 전후 `CA2B1EB67642E28EE51D10E1E2716F0608892A8D5A1D576344145293482378D1`로 동일. 기존 서버와 데모를 시작/종료/변경하지 않았다.

| 제약 | 독립 확인 근거 | 판정 |
| --- | --- | --- |
| B01 | CartRulesTest2: 수량·상한·null/UUID·중복 합산·정렬 | 통과 |
| B02 | cartOwnershipCrudAndPublicAvailability: 소유권·CRUD·CSRF·예상계정·소수 거부·가격 갱신·비공개 null·재고/예약 유지 | 통과 |
| B03 | cartMergeReplayAndConcurrentUpdates 및 브라우저 유실 재시도: 동일 ID 한 번 반영·다른 내용/회원 충돌·버전 경쟁 | 통과 |
| B04 | cartCapacityRollbackAndSuspension: 101종 rollback·DB수량 제약·실제 잠금 대기 후 정지 거부 | 통과 |
| B05 | GuestCart 저장 형식·손상/저장 거부·상한·journal 유지/부분 정리 복구 검사 | 통과 |
| B06 | 동시 add3·쿠키 계정 불일치·pending409·늦은 A/로그아웃 응답·CartPages 상태/수량/삭제 | 통과 |
| B07 | PC/모바일 각 장바구니 여정과 두 탭 Web Locks 검사, 합계 신규4개 | 통과 |
| B08 | 전체 필수 회귀·ArchUnit·소스 해시·보존 gate | 통과 |

결과·소스 목록·필수 검사 대조·개별 Java 결과·프런트 JUnit·명령 로그·DB 요약·보존 전후를 `docs/work-items/evidence/P05-B-review-final-*` 19개 파일에 저장했다. 상세 원본은 `.local-tools/p05-b-review-final-20261025`에 유지한다. 개발의 RED/중단/실패 실행은 `P05-B.md`의 당시 기록과 원본 경로에 남기며 이 성공으로 소급 통과 처리하지 않는다. 검토 세션은 수정 전 소스로 되돌려 RED를 재실행하지 않았다.

제한: 과거 검증 DB는 존재 목록을 비교하며 전체 행 덤프를 전수 비교하지 않는다. 실제 기기·전체 브라우저 호환성·상용 부하·전체 접근성·사용자 학습은 미검증이다. Web Locks 미지원 환경은 오류 안내를 사용한다. 항목별 최신 표시가격은 주문 견적/재고 확정이 아니다. P05-C·주문·결제·커밋·푸시·기존 데모 갱신은 진행하지 않았다.

## 로컬 커밋 마감 보완 — 정적 재검토

개발의 `.local-tools/p05-b-commit-closeout-20261025` 실패 후 `frontend/auth-e2e/catalog.spec.ts` 보완을 읽었다. 원 독립 실행 성공과 이번 후속 실패를 구분한다. 현재 개발 `.local-tools/p05-b-commit-fixed-20261025` 실행 중이므로 검토 세션은 검증 명령을 실행하지 않았다.

- `findOnPages`가 운영자 카테고리 목록과 판매자 옵션 목록의 실제 다음 버튼을 누르고 페이지 표시 변경 후 대상을 다시 찾는다. `CatalogStore.load`는 조회 완료 후 items/page를 함께 갱신하므로 페이지 표시 대기가 다음 결과 확인과 연결된다.
- 신규 등록 후 목록은 0페이지로 재조회하므로 임의 UUID가 뒤에 있으면 다음 페이지에서 찾는다. 수정 후에는 같은 페이지를 유지하고 기존 대상 assertion을 다시 확인한다.
- 실제 API page1 첫 항목을 UI로 찾아 현재 표시가 `1 페이지`가 아님을 assert하여 새 UUID가 첫 페이지에 들어간 실행에서도 탐색 분기를 실행한다. API는 탐색 대상 선정용이며 UI 이동 자체를 대체하지 않는다.
- 기존 등록·활성·공개/비공개·SKU·재고·중지·보관 assertion을 제거하지 않았다. 기존 test/default timeout 값도 그대로다. 루프는 최대100페이지로 제한하고 대상을 못 찾으면 `toHaveCount(1)`로 실패한다.

현재 변경에서 추가로 확인된 정적 차단은 없다. 다만 새 검사 파일은 이전 inputs 해시와 다르므로 이전 전체 통과를 이번 보완본 통과로 간주하지 않는다. 개발 성공·소스 고정 안내 후 새 경로에서 독립 전체 하네스를 실행하고 `P05-B-review-commit-*` 증거를 추가한다. 기존 검토/실패 기록과 모든 DB·미디어는 보존한다.

### 마감 fixture 추가 보완

개발 `.local-tools/p05-b-commit-fixed-20261025`에서 API page1이 비어 있다는 assertion 실패가 보고됐다. 앞 절의 결정적 분기는 다른 검사 데이터에 의존해 두 번째 페이지 존재를 보장하지 못했다. 해당 정적 검토의 한계와 실패를 이력으로 유지한다.

최신 `catalog.spec.ts`는 검증 서버에 연결된 관리자 브라우저의 CSRF로 카테고리21개를 직접 생성하고 각각201을 assert한다. 이름에 desktop/mobile 접미사와 index를 사용한다. 이후 UI의 목록 다시 확인과 로딩 완료를 거쳐 API page1 대상을 선정하고 실제 UI 탐색·1페이지 아님 assertion을 유지한다. fixture API 생성은 페이지를 채우기 위한 준비이며 본래 신규 카테고리 UI 등록·상태/상품 검사를 대신하지 않는다. 독립 전체 실행은 아직 대기 중이고, 개발 `.local-tools/p05-b-commit-final-20261025` 종료 후 새 실행으로 확인할 예정이다.

### 마감 보완본 독립 전체 검증 — 통과

2026-10-25 14:41:39~14:44:47 KST, 개발 실행 종료 후 이 검토 세션에서 다음을 직접 실행했다.

```powershell
./verify.ps1 -Offline -EvidenceDirectory .local-tools/p05-b-review-commit-final-20261025
```

- exit0, status=passed, preservation=passed. Java245(기본139·DB106, ArchUnit18 포함), 브라우저28, Vitest114, Node68, 하네스 거부 검사13, 타입·린트·빌드 통과. 실패·오류·skip 없이 필수 목록 대조 통과.
- 보완된 catalog 시나리오는 desktop 6.0초, mobile 7.1초로 통과했다. 카테고리21개 준비/201, 실제 2페이지 대상 UI 탐색, 신규 카테고리와 판매자 선택 탐색, 기존 상품·SKU·재고·상태 assertion을 실행했다. 테스트/기본 대기 시간은 기존 값 유지다.
- 원 독립 최종 실행 대비 소스 변경은 `frontend/auth-e2e/catalog.spec.ts` 한 파일이다. 최신 소스269개는 개발 `.local-tools/p05-b-commit-final-20261025`와 전부 일치하고 실행 중·종료 후 현재 파일 해시 불일치0. inputs.json SHA256은 `B7F87FCF4ED621EC42D78FAA99B58FF28D98421656E2255E27B17CFF2E369289`. 기준 HEAD는 여전히 `a069bf2154718071bf68d6bab77ac651c6bed32d`이며 P05-B는 미커밋 상태에서 검증했다.
- 새 DB `d2_it_b8e56e30ef5b48fe8e8db13e209ae641` 및 해당 실행의 `db/media`를 사용했다. 기존 테스트 서버 DB 목록148개 전부 유지→149개, 기존 미디어723개 해시 유지→741개. 기존 개발 DB 목록5개, 개발/테스트/데모 덤프·컨테이너·알려진 리스너 보존 통과. 데모 덤프 해시는 이전 독립 결과와 동일하다.
- 결과·소스 목록·coverage·개별 Java 결과·프런트 JUnit·로그·DB 요약·보존 전후 19개 파일을 `docs/work-items/evidence/P05-B-review-commit-*`에 저장했다. 상세 원본, 새 DB·미디어, 앞선 두 마감 실패의 기록/DB/미디어도 유지한다. 앞선 실패를 성공으로 소급 변경하지 않는다.

마감 보완본까지 독립 검토 통과이며 확인된 잔여 필수 차단은 없다. 기존에 기록한 실제 기기·전체 호환성·부하·과거 검증 DB 전체 행 비교 등의 제한은 유지한다. 이 검토 세션은 로컬 커밋·푸시·P05-C를 진행하지 않았다.
