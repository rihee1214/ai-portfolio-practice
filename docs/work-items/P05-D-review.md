# P05-D 독립 검토

2026-10-25, `codex/p05-d`, 기준 `bd3b296`의 미커밋 구현. D01~D06 정적 검토. 개발 전체 하네스 진행 중이므로 독립 실행과 제품/검사 수정은 하지 않았다.

**최종 판정: 동일 소스의 독립 검토 통과 및 새 전체 마감 재검증·보존 조건 충족. 로컬 마감 가능.** 최초 마감 Node 즉시 network 실패의 원인은 미확인으로 남기며 해결됐다고 주장하지 않는다. 과거 실패와 독립 성공은 당시 결과로 유지하고 최신 판단은 마지막 절을 따른다. 검토 세션은 제품·검사·하네스 수정, 커밋·푸시·P06을 진행하지 않았다.

## R-D01 — P2: End 키 선택 기대값이 무작위 UUID 순서에 의존

`PostgresJdbcIT.runAuthBrowsersIfRequested`의 P05-D fixture는 기본 SKU와 추가 옵션 SKU를 각각 무작위 UUID로 만든다. `JdbcBrowse.detail`은 `ORDER BY k.id`로 옵션을 반환하고 `ProductDetailPage`는 반환 순서대로 option을 표시한다. 그러나 `input-flow.spec.ts`는 End/Enter 후 항상 `선택 가격: 2,500원`을 기대한다. 추가 SKU의 UUID가 기본 SKU보다 작으면 마지막은 1,500원이며, 올바른 화면도 검사가 실패한다. 이후 장바구니 5,000원 assertion도 같은 순서 가정에 의존한다. 이번에는 코드로 확인한 비결정적 fixture 경로이며 실행 재현을 주장하지 않는다.

보완: 준비 데이터를 명시적이고 안정적인 순서로 만들거나, 선택 목록의 목표 옵션을 확인한 뒤 실제 키보드 이동으로 그 옵션을 선택한다. End가 무조건 비싼 옵션이라는 가정을 제거하고 실제 선택 id/가격을 함께 확인한다. 키보드 시나리오와 금액 assertion은 유지한다.

동일 fixture는 `browseFixture`의 활성 기본 SKU(options=[])를 남긴 채 활성 명명 옵션을 직접 SQL로 추가한다. 기존 `JdbcSkus.save`는 명명 옵션 추가 시 기본 SKU를 비활성화하고 명명 옵션이 존재하면 기본 SKU 재활성화를 거부한다. 따라서 현재 fixture는 사용자 API로 만들 수 없는 활성 조합이다. 가능하면 기존 SKU API로 두 명명 옵션을 만들고 재고를 준비하여 현실적인 옵션 전환을 검사한다. 직접 fixture SQL을 쓴다면 기본 SKU 비활성화와 두 명명 옵션 등 기존 도메인 불변조건을 지켜야 한다.

## 그 밖의 정적 확인

- useFormDraft는 마운트별 문자열 상태·첫 필드 오류·포커스·aria 연결을 제공한다. QuantityForm은 noValidate 상태에서도 빈/0/100/소수를 거부하고 disabled 중 제출을 중단한다. InputFlow 단위 검사에서 요청 차단·연결된 설명·포커스·정상 한 번 제출을 확인한다.
- AddressForm은 공통 훅으로 draft를 관리하고 required/길이/전화 길이/숫자5자리 우편번호를 명시 검사한다. AccountStore.save의 기존 CSRF/서버검증/재조회는 유지한다. 실패 시 같은 폼을 보존하고 인증 상태가 초기화되면 AccountDetailsPage가 폼을 내려 draft를 폐기한다. 주소 단위 검사는 500 후 값 유지·수동재시도와 auth.stop 후 입력 제거를 확인한다.
- 브라우저 검사는 375/768/1440을 각 프로젝트에서 실행하며 상품/옵션/수량 키보드, 주소 필드 Tab 이동·기본주소 Space·Enter 저장, 500 후 재시도, 가로 넘침, 상품 조회 보류/500/재시도를 다룬다. 주소500은 가로챈 응답이며 재시도 저장/재조회는 실제 DB 경로다.
- 기존 필수 시나리오를 유지하고 신규 단위2/브라우저8을 등록했다. 학습 문서는 props/state→요청/응답과 서버 검증 유지, 회원 주소와 주문서 주소 범위 구분, 작은 실습과 사용자 직접 이해 미확인을 설명한다.

R-D01 보완과 개발 전체 성공·소스 고정 후 새 고유 DB/미디어에서 `./verify.ps1 -Offline`을 직접 실행하고 D01~D06 판정·소스 식별·보존·제한을 추가한다. 현재는 전체 통과로 표시하지 않는다. 커밋·푸시·P06은 진행하지 않는다.

## 보완 코드 재검토 (독립 실행 전)

- R-D01 fixture는 Skus.save API로 작은 것1500/큰 것2500 명명 옵션2개를 생성하도록 변경됐다. 기본 SKU 비활성화는 기존 API 정책을 따르며 재고만 새 검증 fixture SQL로 준비한다.
- 브라우저는 실제 option 목록이2개인지 확인하고 큰 것의 index/id를 읽은 뒤 Home/ArrowDown/Enter로 이동한다. 선택 id·가격2500·장바구니 합계5000 assertion을 유지하여 UUID 순서에 독립적이다. DOM 조회는 목표 파악용이며 키보드 선택 자체를 대체하지 않는다.
- 훅은 ref를 반환하지 않고 제출 이벤트의 currentTarget을 validate에 전달한다. QuantityForm·AddressForm 모두 await 전에 동기적으로 검증/포커스를 처리하므로 제출 이벤트의 폼 참조를 안전하게 사용한다. 입력 상태·오류 속성·busy 차단은 유지한다.
- 첫 개발 전체 실행은 브라우저 통과 뒤 lint 실패로 보고됐으므로 전체 통과로 간주하지 않는다. 관련 실패 기록을 유지하며 새 `.local-tools/p05-d-final-20261025` 결과와 이후 독립 실행으로 확인한다.

R-D01은 **소스 보완 확인, 독립 전체 실행 대기** 상태다. 이번 재검토에서 코드·검사 변경이나 검증 실행을 하지 않았다.

## 최종 독립 전체 검증

2026-10-25 15:24:35~15:27:53 KST. 개발 최종 실행 종료와 소스 고정 후 이 검토 세션에서 직접 실행했다.

```powershell
./verify.ps1 -Offline -EvidenceDirectory .local-tools/p05-d-review-final-20261025
```

- exit0, result status=passed / preservation=passed.
- Java247: 기본 JUnit139(ArchUnit18 포함)·실제 PostgreSQL108, 실패/오류/skip0. 브라우저40, Vitest125, Node HTTP/프록시68, 하네스 거부 회귀13, 타입·린트·빌드 모두 통과. 이전 필수 검사 삭제0.
- 기준 HEAD `bd3b296c27179a94964689e4b74817b1ff03f18d`의 미커밋 P05-D 소스285개. 개발 `.local-tools/p05-d-final-20261025`와 경로·해시 전부 일치하고 실행 중·종료 후 현재 소스 해시 불일치0. inputs.json SHA256 `289186B58D509A4A299FB1228152880A54BC119B9CCCD334925F37D4F2E31D23`.
- 새 DB `d2_it_524f0cd0594e4a2c8d8ecb7d96b6203e`, 새 미디어 `.local-tools/p05-d-review-final-20261025/db/media` 사용. 기존 테스트 서버 DB 목록157개 전부 유지→158개, 기존 미디어873개 해시 유지→891개. 새 검증 DB·미디어도 남겨 두었다.
- 기존 개발 DB 목록5개와 개발/테스트 DB 덤프, 컨테이너, 알려진 리스너 비교 통과. 데모 `app_42326df08aff4829b301a47e40379368` 덤프 SHA256 전후 `CA2B1EB67642E28EE51D10E1E2716F0608892A8D5A1D576344145293482378D1`로 동일. 기존 서버·데모를 시작/종료/변경하지 않았다.
- `P05-D-failure-manifest.json`의 기존 실패/실행 증거402개를 시작 후와 종료 후 직접 확인하여 누락/해시 변경0. 이전 lint 실패를 성공으로 소급 처리하지 않는다.

| 제약 | 독립 확인한 근거 | 판정 |
| --- | --- | --- |
| D01 | InputFlow 수량 검사: 빈/0/100/소수 요청 차단, 오류·input 연결/포커스, 수정 후 정확히 한 번 제출, busy 비활성 | 통과 |
| D02 | InputFlow 주소 검사: 첫 오류 필드 포커스/aria, 500 후 draft 유지·수동 재시도, auth.stop 후 폼 제거; 기존 계정 상태 검사 유지 | 통과 |
| D03 | desktop/mobile 각각375/768/1440: 상품 진입·목표 옵션 키보드 선택 id/가격, 수량 Tab/Enter, 주소 필드 Tab·기본주소 Space·저장 Enter, 실제 저장/재조회·가로 넘침 | 통과 |
| D04 | 각 프로젝트의 상품 요청 보류→500→키보드 재시도, 주소500→입력 보존→실제 저장, 기존 비동기 회귀 | 통과 |
| D05 | 전체 필수 회귀·ArchUnit·소스 해시·보존 gate·이전 실패 증거402개 | 통과 |
| D06 | P05-D-learning.md의 실제 훅/폼/저장소 연결, props/state·명시검증·서버검증 구분, 작은 실습과 기대 결과 | 문서 확인; 사용자 직접 이해는 미확인 |

R-D01은 기존 API의 기본 SKU 비활성화 정책을 따르는 두 명명 옵션 fixture와 실제 DOM 목표 기반 키보드 선택으로 보완됐다. 신규8개 브라우저 시나리오 및 관련 단위2개가 이 독립 실행에서 통과했다. 최초 무작위 순서 문제는 정적 발견이며 이 검토 세션에서 수정 전 소스를 되돌려 실패 재현한 것은 아니다.

`docs/work-items/evidence/P05-D-review-final-*`에 결과·소스 목록·필수 검사 대조·개별 Java 결과·프런트 JUnit·명령 로그·DB 요약·보존 전후19개를 저장하고 실패 증거 재확인은 `P05-D-review-failure-preservation.json`에 별도 기록했다. 상세 원본은 `.local-tools/p05-d-review-final-20261025`에 유지한다. 작업 최초 보존 기준 `P05-D-task-before.json`과 개발 RED/실패 이력은 그대로 유지한다.

제한: 과거 검증 DB는 존재 목록을 비교하며 전체 행 덤프를 전수 비교하지 않는다. 주소/상품500은 브라우저 응답 가로채기로 재현하고 이후 정상 처리만 실제 서버/DB로 확인했다. 375/768/1440 viewport 자동 검사는 물리 기기·전체 브라우저 호환성·스크린리더·전체 접근성 검증이 아니다. 사용자 직접 이해/실습은 미확인이다. 주문서 주소/구매 인증/주문·결제 확장, P06, 커밋·푸시·기존 데모 갱신은 진행하지 않았다.

## 커밋 마감의 후속 Node 실패 — 읽기 전용 검토

`.local-tools/p05-d-commit-final-20261025/result.json`은 status=failed, error=`Frontend test failed`, preservation=passed다. 이 실행 inputs285개는 앞선 독립 통과본과 경로/해시 차이0이다. 마감 실행을 통과로 표시할 수 없다.

원본 test.txt에서는 Node68개 중 pass62/fail6으로 집계된다. 실패6은 preview partial-response 부모1개와 자식5개이며 별개의 제품 결함6개라는 뜻은 아니다. 같은 파일의 dev 경로는 정상이며 preview의 silent/stall/disconnect/stream이1~6ms 내 network로 반환되고 정상 healthy 재시도도 network다. 기대하던 10초 idle 처리나12초 연속 스트림에 도달하기 전 공통 연결 경로가 실패한 양상이다. 이 시간/반환값만으로 포트·서버 준비·연결풀·운영체제 등의 특정 원인을 확정할 수 없다.

`proxy-stream.test.mjs`는 upstream과 Vite를127.0.0.1 동적 포트로 생성하고, preview 반환 후 실제 listen 주소를 사용한다. 이 파일과 제품 프록시 코드는 P05-D에서 변경하지 않았다. `requestJson`은 fetch/본문 실패를 network로 분류하므로 기존 실패 로그에는 원래 예외 cause/주소가 남지 않았다. 따라서 P05-D 제품 회귀나 단순 timeout 문제로 단정할 근거는 없다.

진단 `.local-tools/p05-d-commit-network-diagnostic-20261025/trace-fetch.mjs`는 fetch 예외를 기록하고 다시 throw하는 관찰용 wrapper다. 읽은 진단 test.txt에서는68개 통과하며 FETCH_DIAGNOSTIC 행은 없다. 이는 이번 진단 실행에서 기존 즉시 실패가 재현되지 않았다는 뜻이며 원인을 찾거나 수정했다는 증거가 아니다. 진단 Node 단독 성공은 실패한 루트 전체 결과를 대체하지 않는다.

마감 판단: 소스/검사를 그대로 유지한 새 고유 경로의 전체 하네스가 exit0/status=passed/preservation=passed이고 소스 및 실패 증거 보존이 확인되면, 이 일회성 실패의 원인 미확인을 제한으로 명시하여 로컬 마감 판단에 사용할 수 있다. 실패를 없던 일로 하거나 원인 해결로 표현하면 안 된다. 다시 발생하면 반복 실행으로 통과만 찾지 말고 원래 오류 cause와 실제 listener 주소/상태를 확보해야 한다. 이번 검토는 로그·코드 읽기와 이 문서 추가만 수행했고 진단·DB·제품 실행/변경은 하지 않았다.

### 마감 조건 충족 확인

개발 세션의 새 전체 실행 `.local-tools/p05-d-commit-recheck-20261025`(2026-10-25 15:35:44~15:39:02 KST)는 exit0로 보고됐고, 이 검토 세션에서 원본 result.json의 status=passed/preservation=passed/error 없음 및 Node test.txt의68개 전부 통과를 직접 확인했다. Java247·브라우저40·프런트125도 결과에 기록돼 있다. 이는 개발 세션이 실행한 마감 재검증이며 검토 세션이 다시 실행한 것으로 표기하지 않는다.

원 독립 검증 inputs와 새 마감 inputs285개 경로/해시 차이0, 현재 소스와도 불일치0을 직접 확인했다. 최초 마감 before와 재검증 after를 저장된 스냅샷으로 읽어 Assert-Preservation을 다시 적용해 통과했다. 기존 DB 목록158개·미디어891개 유지, 새 검증 환경 포함160개·927개이며 기본/데모 DB·컨테이너·리스너 보존 조건을 충족한다. `P05-D-commit-failure-manifest.json`의503개 파일도 실제 파일을 읽어 누락/해시 불일치0을 확인했다. 관련 요약은 `evidence/P05-D-commit-preservation.json`에 있다.

따라서 앞서 명시한 마감 조건은 충족됐으며 **원인 미확인인 비재현 Node 실패를 제한으로 남긴 상태에서 로컬 마감 가능**으로 판정한다. 최초 실패 결과는 유지하고 오류 수정/근본 원인 해결로 표현하지 않는다. 검토 세션은 이번 확인에서 문서만 수정했으며 커밋은 수행하지 않았다.
