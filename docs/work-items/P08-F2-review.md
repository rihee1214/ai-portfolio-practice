# P08-F2 독립 사전 검토 — 2026-10-28

상태: **계약 정적 검토 / 구현·하네스 미검증**. F1 완료 후 F2 DEMO·모의배송 범위만 검토한다. E 실제 SANDBOX/카드 webhook 계약은 외부 차단을 유지한다. 제품 변경·하네스·G·커밋·배포·푸시는 하지 않는다.

## R-F201 판매자 실제 진입과 최소 목록

현재 F/F1은 sellerOrder UUID를 아는 상세/배송 조회만 제공하며 판매자 목록은 없다. App의 /seller/orders는 아직 SellerFeaturePage placeholder다. 본인 store의 안정 커서 목록과 실제 navigation→목록→상세/발송을 연결해야 한다. 구매자 전체 주문을 재사용하거나 모든 상세 recipient를 목록에 실어 보내지 않는다. 목록은 최소필드와 현재 owner/role/session·EXISTING_TRADES 권한을 검사하고 no-store를 유지한다.

assertion: UI 실제 판매자 메뉴 클릭으로 접근, 두 판매자 각각 자기 SellerOrder만, 타인 cursor/URL변조로 다른 데이터0, createdAt+id 동률/페이지 경계 중복누락0·최대size·잘못된cursor, private/closed 기존거래 목록 유지·정지/만료거부. 목록JSON 허용필드 검사와 상세 recipient 범위를 분리한다.

## R-F202 유실 재시도와 과거 최초결과

F1의 동일키 재전송은 최초 Result(SHIPPED/version1)를 반환한다. 이후 worker가 DELIVERED여도 이 최초결과는 같으므로 POST 응답을 현재상태로 덮으면 UI가 후퇴한다. 성공·유실복구 후 권한 있는 GET을 재조회해 현재 상태를 표시해야 한다. 키/버전/항목은 첫 실제 POST 전 확정하고 유실재시도에서 유지한다. CSRF preflight 실패는 POST0이므로 명시적인 재시도를 막지 않는다.

assertion: double click POST1·같은key, 실제 서버commit후응답유실→같은 body/key 재시도·효과1, 재시도 전 provider가 진행된 경우 현재 GET상태 유지. 409는 GET만 하고 자동POST0/새키0; 401/403은 즉시 private data폐기·자동POST0; CSRF요청실패 POST0 후 명시재시도 가능. 실패시 선택/입력보존과 첫 오류 focus를 확인한다.

## R-F203 비동기·정체성·현재 서버 기준

목록·상세·payment·shipments 응답은 모두 요청generation/current identity에 귀속한다. await/Promise.all 내부 확인만으로 부족하며 최종 this.set 직전에 다시 확인한다. logout/계정전환/게스트 재인증/번호변경 즉시 이전 데이터 제거, 늦은 응답이 복원하지 못하게 한다. 동일 주문의 여러 응답 시점이 다르면 잘못 결합하지 않고 재조회/오류 처리한다.

assertion: A지연→B성공→A늦은 응답, 최종 publication microtask 경계, logout 유실, 다른 sellerOrder로 이동, 401/403 후 다시로그인에도 자동발송0. 로딩/오류/미결제/PAID배송생성대기/빈상태를 구별한다. 식별자 배열 순서나 store 표시이름으로 매대를 매칭하지 않고 sellerOrderId/orderItemId를 쓴다.

## R-F204 실제 두 매대·새 비회원 context

고정 JSON이나 browser intercept로만 만든 상태는 F201 증거가 아니다. 실제 C/D 주문·결제→F1 producer/worker 배송생성→A ship/B PREPARING, 이후 실제 내부 모의worker A DELIVERED/B IN_TRANSIT을 만들고 각 화면/API/DB를 대조한다. F1 handler/시간진행은 테스트 코드에서만 쓰며 공개 사용자 버튼으로 노출하지 않는다.

assertion: 두 매대의 서로 다른 state/items/quantity/events·시각이 각각 DB와 같고 새로고침후동일. 새 브라우저에서 원주문번호/전화/비밀번호로 GAS 로그인→본인배송, 다른 NEW묶음·타인번호404/no-store·개인정보0, reset/만료거부. 판매자 발송화면에서 타매대/부모전체결제/guest자격 필드0.

## R-F205 3폭·키보드·오류 연결

기존 desktop/mobile 프로젝트만으로 768px를 증명하지 않는다. 375/768/1440 각각 실제 서버 화면에 긴 상품명/주소/두매대/이벤트를 표시하고 내용·버튼 누락0 및 scrollWidth 경계를 확인한다. 키보드로 메뉴→대상→발송 버튼을 실행하고 aria 오류연결/첫 오류focus를 확인한다. 단순 screenshot은 보조다.

assertion: 3폭 각각 가로넘침0·항목/상태/발송 접근 가능, 모의 배송임을 표시, 구매자 직접 DELIVERED 버튼/POST경로0, 실제 keyboard Enter/Space 제출·중복방지·실패focus. 아직 배송이 생성되지 않은 PAID에서 '완료/없음'을 단정하지 않는다.

## 사전 판정

현재 구현 전 필수 assertion 제안이다. 개발 전체 통과·소스 동결 후 별도 새 경로 독립 전체 및 전후 source/failure 보존 대조가 필요하다.

## 구현 중 정적 검토 — 초기 UI/목록

ShipmentStore는 성공 POST의 최초 결과를 화면에 직접 쓰지 않고 GET을 재조회하며, 동일 pending key/version/items 보존, 409 GET만, 401/403 clear, 최종 generation 검사와 StrictMode 재구독 구조를 가진다. seller 목록은 자기 store 필터·최소필드와 created_at/sellerOrderId 커서, 현재권한 후검사를 적용했다. 실행 통과 판정은 아직 아니다.

### R-F206 실제 모의 제공자 출처 오표시

ShipmentRows는 source==='MOCK'일 때만 모의제공자로 표시하지만 F1의 실제 source는 MOCK_PROVIDER다. 따라서 IN_TRANSIT/DELIVERED provider 이벤트가 시스템으로 표시된다. 실제 enum mapping과 provider이력 화면 assertion이 필요하다.

### R-F207 취소된 준비 배송의 발송 UI

SellerShipmentsPanel과 ShipmentStore.ship에는 orderState==='PAID' 조건이 없다. F1의 prepare선행→취소 경합 후 PREPARING 배송이 남는 유효 상태에서 발송 버튼과 POST를 제공한다. 서버409는 막지만 불가능한 행동을 반복하게 한다. 새발송은 PAID에서만 제공하고 취소/만료 상태 안내와 POST0 assertion이 필요하다. 이미 제출한 동일키 결과 확인은 새발송과 구분해 정책을 정한다.

현재 POST 성공 후 GET 실패에서도 pending body가 유지되나 uncertain 표시 없이 일반 발송 버튼으로 남을 수 있으므로 실제 통합 흐름에서 실패 안내·samekey 결과 확인과 재조회 동작을 함께 검사해야 한다. seller 목록/상세 서버 권한을 브라우저 역할 표시만으로 대체하지 않는다.

## 재시도 최종 로직 정적 확인

R-F206 MOCK_PROVIDER 표시와 R-F207 PAID 새발송 gate를 확인했다. pending은 sellerOrderId+shipmentId+최초key/version/items를 보존하며 order가 null이어도 동일 shipment와 확인값을 통해 재시도한다. 독립 form은 실제 ship 메서드에 연결돼 있다. POST실패 또는 POST성공후GET실패에서 order/shipments를 숨기고 uncertain을 표시한다. 401/403은 pending/확인값까지 폐기한다. 409→GET실패는 pending삭제·uncertainnull 상태로 명시적 조회 버튼을 열고 자동 POST하지 않는다. 최종 generation 검사는 유지된다.

현재 정적 코드에서 추가 차단은 발견하지 않았다. 다만 store 직접 호출과 별도로 standalone form의 실제 클릭·오류focus 후 동일body 재시도, 409+GET503의 private0/재POST0/명시GET복구 assertion을 보완 요청했다. 이것은 실행 전 테스트 공백 기록이며 최종 통과 판정이 아니다.

## 추가 assertion 확인 (focused4 실행 중)

standalone panel 검사는 실제 checkbox·발송버튼 클릭 후 POST성공/GET503, private 수령인·배송 DOM 제거, alert focus, 확인값 유지, 독립 retry버튼 클릭, 두 POST body 완전일치, 현재 DELIVERED 및 수령인 복원을 검증한다. 409→GET503 검사는 private0/uncertainnull, ship 재호출해도 POST 증가0, 명시적 openSeller GET 후 SHIPPED 복구까지 확인한다. 앞서 기록한 두 테스트 공백은 정적 assertion 기준 해소됐다.

목록 만료 fixture는 last_access_time뿐 아니라 실제 expiry_time을 과거로 설정한다. F2 browser의 다른 비회원 NEW그룹은 별도 전화번호로 생성하며 그 검사는 다른 묶음404 범위다. 같은 전화번호 NEW 분리·reset/expiry는 기존 F realGuestOrdersUseIndependentAccessSessions 필수 DB 검사를 유지하여 증명한다. 전화 quota429를 피하기 위해 기존 DB quota나 clock을 임의 변경하지 않는다. POST body assertion은 비동기 CSRF 이후 실제 POST 관찰을 기다린 뒤 비교한다.

현재 추가 정적 로컬 차단은 없으며 focused4/개발전체/독립전체 결과와 전후 보존 대조 전 완료 판정하지 않는다.

## 최종 독립 전체 검토 — 2026-10-28

루트 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-f2-review1-20261028`을 새 경로에서 실행했다. MAVEN_ARGS 제거, 선택옵션 없음. 21:23:16~21:32:05 KST, 외부 프로세스 종료0, status/preservation passed. Java443(단위183+격리DB260), browser96, frontend206, Node75, 필수49묶음 passed, 누락/실패/오류/skip0 및 typecheck/lint/build 통과. 기존 F1 필수 이름 대비 Java382→384/browser90→96/frontend187→206의 이전 이름 누락0이다.

| 제약/검토 항목 | 독립 실행 assertion | 판정 |
|---|---|---|
| F201/R-F201·204 | sellerOrdersShowDifferentDeliveryStates: 실제 주문/결제/내부worker 배송생성, 판매자 실제 메뉴→목록→상세→발송, A발송/B준비 및 A완료/B배송중을 sellerOrderId로 구분해 API·DB·화면/새로고침 대조 | 통과 |
| F202 목록·개인정보 | sellerOrderListIsBoundedScopedAndCurrent: 자기 매대 최소필드·20/최대50·안정커서·타인/만료/현재역할, no-store. 기존 F/F1 권한·item 귀속 필수 유지 | 통과 |
| F202 새기기 | guestDeliveryOnNewContext: 새 context 실제 GAS 로그인, 자기 배송/새로고침·다른 NEW그룹/없는번호404·개인정보0·logout거부·구매승인권한 분리 | 통과 |
| F203/R-F202·203 | ShipmentStore/ShipmentPanels: doubleclick1, immutable samekey/version/items 유실복구·POST최초결과 대신 GET현재상태,409 GET만/GET503복구,CSRF실패POST0·명시retry,401/403전체폐기,identity 및 final microtask publication 폐기 | 통과 |
| R-F206·207 | 실제 MOCK_PROVIDER 표시, 취소/만료/PENDING의 새발송POST0·안내, POST성공/GET실패 private DOM0와 독립 retry실제click·같은body·현재DELIVERED·오류focus | 통과 |
| F204 | keyboardAndResponsiveShipmentFlow: 각 PC/모바일 프로젝트에서375/768/1440px buyer/seller 내용·버튼/주소 가시성·가로넘침0, Space/Enter 실제발송 충돌409·확인값보존·aria-describedby·오류focus·현재서버상태 | 통과 |
| 테스트 전용 내부 조작 | shipmentBrowserFixtureIsNotProductionApi: fixture는 test 전용이며 일반 제품 API의 공개 배송완료 조작 경로가 아님 | 통과 |

F2의 다른 NEW그룹 browser검사는 다른 전화번호를 쓴 다른묶음404 증거다. 동일 전화번호 NEW 분리·reset/만료는 기존 F DB 필수 검사로 유지했다. quota 회피를 위해 DB quota/clock을 조작하지 않았다. 과거 placeholder 기대를 실제 판매자 화면·본인목록200 assertion으로 교체하면서 기존 생명주기 권한 검사는 유지했다.

개발dev1의 PhoneProviderEmulatorIT TypeError fetch failed는 원인 미확정 실패로 보존한다. 추가 진단은 고정 stage/name/허용 network code와 최대4개 aggregate만 출력하고 비밀/message/stack/body를 제외하며 요청/재시도/timeout을 변경하지 않았다. 두 진단검사와 이번 전화브라우저 및 Node75가 통과했지만 과거 원인이 해결됐다고 주장하지 않는다.

새 DB `d2_it_39bebeeb93f44ca78818581c9ceec171`, 미디어 `.local-tools/p08-f2-review1-20261028/db/media`, 전화 emulator `.local-tools/phone-emulator-949790298f6145f09ee90b157def21af`를 보존했다. 실행 원본은 review1 폴더, 주요 result/inputs/coverage/전후/DB/프런트 사본은 `evidence/P08-F2-review-final-*`다. 기존 자원 삭제/중지는 수행하지 않았다.

전후 감사 완료: HEAD `460aa8bb0bb22f5138849eb00c050abd18f6fb53`(미커밋 변경 포함), 입력469파일 SHA256 `B095D13D07F1E2D9C19CBA8218C91BCE78C137B8CCA500784AD1A2B250F450F2`로 개발dev2/독립review1 manifest가 동일하다. 현재 소스 전후불일치0. 실패 manifest30개/중복제거60795파일의 존재/내용SHA 및 manifest 자체SHA 전후불일치0. 원 Phone 실패를 포함한 F2 followup1431파일 manifest SHA `F98C434157C9F5434445426279CD348DC9B0F6468AC94EC08893EFE115F72828`도 유지했다. 감사 원본은 `.local-tools/p08-f2-review1-audit-20261028/{before,after}.json`, 사본도 보존했다.

**최종 판정: F2 DEMO·모의배송 화면 범위 독립 검증 통과, 필수 잔여 차단 없음.** R-F201~207과 추가 pending-read/실제 retry버튼 assertion 공백이 해소됐다. 과거 전화 통신 실패의 원인은 미확정으로 남기며 이번 실제 전체 통과와 분리한다. E 실제 SANDBOX·카드 webhook 계약은 계속 외부 차단이고 실제 택배사/분할/클레임은 제외된다. G·P09·커밋·배포·푸시는 진행하지 않았다.
