# P08-E 독립 사전 검토

2026-10-28. E 계획과 D의 실제 PaymentProvider/PaymentCoordinator/Orders 인증 계약을 읽고 공식 문서를 확인했다. **구현·키 값 확인·테스트·전체 하네스·실제 SANDBOX 실행은 하지 않았다.** 아래는 구현 전 계약/검사 항목이며 현재 E 통과를 뜻하지 않는다.

## 공식 근거와 확인 범위

- [결제 흐름 v2](https://docs.tosspayments.com/guides/v2/get-started/payment-flow): SDK 인증 return의 paymentKey/orderId/amount를 저장한 요청과 대조한 후 서버 승인하는 흐름이다.
- [코어 API](https://docs.tosspayments.com/reference): confirm, 결제 조회/취소, Payment의 orderId/paymentKey/totalAmount/balanceAmount/status를 확인했다. 최초금액과 취소가능잔액은 다르며 PARTIAL_CANCELED는 전체취소가 아니다.
- [인증/헤더](https://docs.tosspayments.com/reference/using-api/authorization): 서버 시크릿의 Basic 인증, 테스트/라이브 분리, 클라이언트·로그 비노출 조건을 확인했다.
- [웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events), [웹훅 연결 v2](https://docs.tosspayments.com/guides/v2/webhook): 이벤트별 계약·전송ID·재전송 및 외부접근 가능한 수신주소 요구를 확인했다. 이벤트 문서의 서명 설명은 보안키 링크가 지급대행 가이드로 연결되므로 일반결제의 실제 서명 제공 여부/키 종류는 별도 확인이 필요하다. 이 불확실성을 구현으로 추정해 메우지 않는다.

## R-E01 — 현재 D port에는 SDK 인증결과 결합이 없다 (E01/E04)

PaymentProvider.Request는 attemptId/order UUID/저장금액/mode만 전달하고 D approve는 준비거래 직후 provider.approve를 호출한다. Toss 인증결과 paymentKey와 provider orderId를 저장·불변결합할 포트/단계는 아직 없다. SDK 요청 전에 준비한 서버 주문 정보와 return 확인을 분리하고, confirm은 현재 소유권·세션·CSRF·저장금액·기존attempt를 재검증해야 한다. 브라우저 key가 다른 주문/다른 attempt에 재결합되거나 query 때 임의 클라이언트 key를 쓰면 안 된다.

assertion: prepare에는 외부승인0, returnURL만으로 PAID0, tampered orderId/amount/key/다른소유자409/401 및 모든참여행불변, 동일return/confirm중복효과1, 다른key 재결합거부, 응답유실후 저장된 동일거래조회. 원 paymentKey는 필요한 서버 저장에만 두고 요청URL·오류로그·브라우저 trace/스크린샷 증거에 남기지 않도록 수집을 통제한다.

## R-E02 — mode 전환은 저장 주문 기준, 키 종류/정수범위/요청주소 통제 (E01/E03)

D는 SANDBOX 승인을409로 막고 MockPaymentProvider 한 개를 주입한다. E가 이를 해제할 때 현재 전역설정으로 기존DEMO 주문을 Toss에 보내거나 SANDBOX를 mock성공으로 처리하지 않도록 저장mode→provider 선택을 고정한다. 키미설정/라이브키/잘못된조합은 설정실패 또는 해당mode unavailable로 노출하고 DEMO 회귀는 유지한다. 테스트키는 빈값없는 준비 여부만 확인하고 값은 출력하지 않는다.

backend금액은 long, frontend표시는 문자열/BigInt다. SDK에 number를 넘길 때 무검사 Number 변환은 큰금액을 반올림한다. 선택수단/통화/허용금액의 명시적인 제한과 안전정수 변환을 검사한다. API호스트·return URL은 서버설정/allowlist로 정하고 client입력 주소로 시크릿헤더를 전송하지 않는다. SDK/API의 실제선택버전과 키 제품군을 문서에 기록한다.

## R-E03 — 세가지 Result로 제공자 미확정/부분취소를 축약할 때 안전성 유지 (E03)

D Result는 SUCCEEDED/FAILED/UNKNOWN뿐이다. Toss DONE만 정상승인 근거로 삼고 pending/미인식/응답파싱실패/타임아웃·5xx를 확정실패로 단정하지 않는다. D mock의 query시 row없음=FAILED 규칙을 실제 네트워크 제공자에 그대로 복사하면 안 된다. 응답 orderId/paymentKey/금액/통화/상점 결합은 confirm뿐 아니라 query/cancel/queryCancellation에도 적용한다.

취소성공은 단순 HTTP200 또는 cancels 배열 존재만으로 판정하지 말고 대상거래의 전체취소를 확인한다. PARTIAL_CANCELED/취소처리중은 D 전체예약해제의 근거가 아니다. approve와 cancel의 멱등키는 같은 attempt에서 파생하되 작업종류를 분리하고, timeout후 새키발급 없이 조회한다. assertion: 다른주문·다른금액 DONE, 미인식상태, 부분취소/중복취소/취소응답유실, 외부성공후local실패·기한후성공 보상에서 PAID선반영0/전체해제오판0.

## R-E04 — 웹훅 전송 인증과 서버조회 증거를 구분 (E02)

공식 웹훅 페이지에는 transmission-time/ID 및 signature의 HMAC SHA-256(payload:time), v1:base64 후보들이 설명돼 있다. 그러나 연결된 보안키가 지급대행용이므로 일반 카드결제에 임의 secret/HMAC헤더를 발명하거나 API시크릿을 서명키로 재사용하면 안 된다. 선택한 제품과 이벤트에 해당하는 실제 계약·키를 확인해야 한다. 가상계좌 secret도 카드결제 공통비밀로 일반화하지 않는다.

서명 제공이 확인되면 원본문 바이트/다중서명/회전·변조·재전송을 검증한다. 미확인이라면 webhook본문을 승인증거로 쓰지 말고 서버에 저장된거래와 일치하는 알림을 제한된 재조회 trigger로 취급하며, 미인증receipt를 '인증된웹훅'으로 기록하지 않는다. 본문status를 직접 apply하지 않는다. 임의paymentKey로 DB연결 생성·임의주문 조회·보상호출을 허용하지 않는다. 정확한 POST webhook경로만 CSRF예외로 두고 고객confirm/운영대사에는 기존CSRF를 유지한다.

dedupe 기록은 유효성/귀속확인과 재조회작업 등록이 원자적이어야 한다. 공격자가 가짜eventID를 먼저 보내 정상알림을 영구무시하게 만들지 않는다. 재조회 timeout은 재전송/작업으로 복구하고 duplicate/역순알림은 현재 provider결과로 수렴한다. assertion: 미서명/잘못된키·본문·주문, duplicate선점, 재전송/순서역전, verify후enqueue 실패rollback, 현재고객session 없는 실제webhook처리, 승인CSRF403.

## R-E05 — 실제 SANDBOX 증거와 Offline stub 결과 분리 (E04)

로컬 stub 통과는 실제 SDK·테스트계정·카드인증·Toss confirm/query·등록webhook 전달의 증거가 아니다. 실제 외부접근/키/결제수단이 준비되지 않으면 구체 준비항목을 미검증/차단으로 남긴다. 특히 localhost stub에 SANDBOX라는 mode 문자열만 붙인 테스트는 실제연동 성공으로 기록하지 않는다.

외부실행은 새주문/DB/미디어를 사용하고 저장금액·제공자승인·서버재조회·주문예약확정 연결을 비밀을 제거한 증거로 남긴다. 실제 callback수신주소를 임의로 공개하거나 기존서버를 바꾸지 않는다. 성공주소·조작된browser본문만으로 PAID가 되지 않음을 실제 화면에서도 검사한다. 카드 등 승인확정 수단만 초기지원한다면 제외수단을 명시하고 해당상태를 실패로 억지매핑하지 않는다.

## 판정 기준

현재 E는 사전검토/미검증이다. E01~04의 실제assertion·로컬개발전체·별도독립전체·실제SANDBOX 근거를 각각 기록한다. 외부키/접근 차단 시 로컬검사 통과를 E전체완료로 확대하지 않는다. D의0원 원자적거래/provider0, 실제kill복구, 현재세션, guest권한, UNKNOWN운영노출 회귀를 유지한다. F나 다음단계는 이번 검토에서 시작하지 않았다.

## 구현자 제안에 대한 사전 판정 추가

현재 제안은 webhook security key가 없으면 failclosed하고, 서명된 HTTP stub로 문서의 HMAC 모델/재조회/dedupe를 로컬 검증하는 방식이다. E02의 미인증·위조 수신 부작용0에 부합한다. 일반 카드 제품/MID에 실제 보안키와 서명헤더가 제공되는지는 여전히 외부 확인사항이며, 해당 확인 전에는 '실제 일반카드 웹훅 검증완료'로 기록하지 않는다. 키 미준비는 구현자의 이름/존재여부 확인으로 보고됐으며 본 검토자는 키 값을 읽지 않았다.

freshness는 이벤트발생 createdAt과 재전송 transmission-time을 혼동하지 않는다. 다중v1후보·원본문 바이트변형·키없음·서명실패에서 enqueue/dedupe/D상태 변경0을 검사하고 정상재전송은 수용한다. webhook이 차단돼도 D의 기존 durable provider조회 경로는 유지한다. unsigned boundhint 설계는 서버조회만 진실로 사용하더라도 공격자 입력이 작업/조회 부작용을 일으켜 E02의 엄격한 부작용0과 다르므로, 이번 failclosed 계약을 우선한다.

## 구현 정적 재검토 — 2026-10-28 (독립 전체 결과 대기)

R-E01: 준비/확인에 현재 소유권·세션과 CSRF를 적용하고, 저장 UUID/금액과 paymentKey 불변 결합을 확인했다. 동일 주문 동시 confirm 및 다른 주문의 key 재사용은 ON CONFLICT DO NOTHING 이후 거부하여 새 SQL 고유오류에 key를 노출하는 경로를 줄였다. sandboxConfirmationBindsStoredOrderAndCurrentAuthority / sandboxConcurrentConfirmationsAndReusedProviderKeyHaveOneEffect / sandboxConfirmationWaitRechecksCurrentSession가 대조·경합·실제 세션 대기 assertion을 제공한다.

R-E02: production endpoint는 공식 HTTPS 고정, 테스트 loopback은 테스트 Settings에서만 제공한다. 기본 router 선택, DEMO 네 연산 위임, 미설정 SANDBOX 네 연산 UNKNOWN·Mock 호출0, 명시적 테스트 Primary 선택을 PaymentProviderConfigurationTest가 검사한다. E dev1에서 두 Primary로 실제 child 프로세스가 시작되지 않은 회귀를 보존했고, router의 Primary 제거·Mock의 Fallback 표기로 해결했다. Fallback은 bean 선택 우선순위이며 provider 오류 시 mock으로 전환하는 로직이 아니다. D 원래 프로세스 검사나 시간 제한은 변경하지 않았다.

R-E03: 제공자 응답의 key/order/amount/mId/version/currency/method 및 잔액 범위를 검사하고, 부분취소/미확정/잘못된 응답은 UNKNOWN으로 남긴다. body deadline의 실제 RED와 제한된 비동기 body subscriber 수정을 확인했다. overflow baseline은 원래 failclosed였으므로 새 RED로 주장하지 않는다. 멱등키 관련 사전 제안 보충: 공식 문서의 범위가 API키+주소+메서드이므로 confirm/cancel에 같은 attempt 키를 사용하는 것은 결함으로 판정하지 않았다.

R-E04: 키 부재/위조 서명은 조회나 저장 효과 전에 거부한다. 원문 바이트 HMAC, 시간·다중 후보 검증과 저장 binding 후 credential 조회, receipt 원자성 및 중복/역순 처리를 검토했다. webhook totalAmount는 integral/canConvertToLong/positive와 저장값 일치를 검사하며 overflow/다른 주문·key·MID는 조회0 및 전체 참여 상태 불변 assertion을 가진다. 이 서명 모델은 로컬 계약 검증이고, 실제 일반 카드 제품에 대한 security key 제공·timestamp 형식은 미검증이다.

R-E05: E04 브라우저 네 시나리오는 fake SDK + HTTP stub + 실제 격리 DB 검사이다. 실제 Toss SDK 인증/테스트카드/제공자 승인/등록 webhook 전달을 증명하지 않는다. 키·MID·외부 접근 준비와 제품별 서명 계약 확인 전까지 E 전체 완료를 선언하지 않는다.

필수 목록을 D dev2 coverage의 이름과 대조하여 Java348→361, browser80→84, frontend175→179의 이전 이름 누락0을 확인했다. 이후 router override 단위 검사 한 개가 추가 등록됐다. 이름/총수는 assertion 적합성이나 실제 실행을 대신하지 않는다.

focused3 DB 로그 감사는 해당 실행 구간8줄과 합성 collision binding key1개·fixture secrets를 메모리 대조하여 일치0이다. 원문/값은 저장하지 않았으며 해당 실행의 관찰 근거에 한정한다. 모든 가능한 SQL 오류의 비노출 보장이나 실제 제공자 비밀의 검증으로 확대하지 않는다.

## 독립 전체 실행 결과 — 2026-10-28

독립 명령은 저장소 루트 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-e-review1-20261028`이며 MAVEN_ARGS를 제거하고 검사 선택 없이 실행했다. 17:49:48~17:56:49 KST, 외부 프로세스 종료0, result.status=passed, preservation=passed다. Java421(단위182+DB239), 브라우저84, 프런트179, Node73; 필수 coverage46묶음 passed, 누락/실패/오류/skip0. typecheck/lint/build와 기존 프로세스 종료·복구 검사를 포함한다.

새 DB는 `d2_it_9fd636bdd5a24841b50a0ae356f2f2da`, 미디어는 `.local-tools/p08-e-review1-20261028/db/media`, 전화 emulator 증거는 `.local-tools/phone-emulator-2c3ff89ce1a644519b94417268e846aa`에 보존했다. 다른 기존 DB·서버·미디어·실패 로그를 삭제하거나 중지하지 않았다. 실행 원본은 해당 경로, 주요 사본은 `evidence/P08-E-review-final-*`이다.

| 제약 | 독립 실행 assertion 근거 | 로컬 판정 |
|---|---|---|
| E01 저장 거래·현재 자격 | storedOrderAndAmountAreVerified, sandboxConfirmationBindsStoredOrderAndCurrentAuthority, sandboxConcurrentConfirmationsAndReusedProviderKeyHaveOneEffect, sandboxConfirmationWaitRechecksCurrentSession: 위조400/401/403, 동시효과1, 재결합0, 실제 대기 후 세션401·provider0 | 통과 |
| E02 webhook | signedRawPayloadAndRotationAreRequired, paymentWebhookRequiresProviderEvidence: 원문/시간/서명 failclosed, 미구성503, 불일치 조회0·상태불변, credential 조회 후만 확정, 중복/역순·원자 receipt | 로컬 모델 통과; 실제 카드 계약 미검증 |
| E03 유실·오류·보상 | timeoutAndQueryFollowCoordinator, responseBodyDeadlineBoundsUnknown, overflowingOrNegativeBalancesAreUnknownForEveryOperation, sandboxLostResponseAndLateSuccessUseSameAttempt 및 기존 D actual process kill: UNKNOWN·동일 시도 조회·늦은 성공 보상·0원 외부0 | 통과 |
| E04 로컬 UI | TossPaymentAdapter3, PaymentStore confirm1, payments-sandbox PC/모바일4: URL만으로 승인0, 명시 CSRF 확인 후 DB 전체예약 확정, 유실 조회 수렴, 저장된 금액/공개 test key | 통과 |
| E04 실제 제공자 | 실제 테스트키/MID/API버전/공개 수신주소, 일반 카드 webhook 보안키와 transmission-time 형식, 실제 SDK·제공자 승인/조회/전달 | 미실행·외부 차단 |

소스 HEAD는 `460aa8bb0bb22f5138849eb00c050abd18f6fb53`(미커밋 변경 포함), 입력439파일 SHA256은 `B66F405B767EDF211C112B666FE1ED4F42BB4D81337D2DFBDDDC597079A10EBF`다. 개발dev2/독립review1 입력 manifest가 정확히 같고 실행 전·후 현재 파일 불일치0이다. 실패 manifest26개/중복제거42302파일의 존재·SHA 및 manifest 자체 SHA도 실행 전·후 불일치0이다. 최초 E3964파일과 후속 E1786파일, 개발dev1의 실제 Primary 충돌 실패를 성공 기록으로 바꾸지 않고 보존했다. 감사 원본은 `.local-tools/p08-e-review1-audit-20261028/{before,after}.json`, 사본은 `evidence/P08-E-review-final-audit-{before,after}.json`이다.

**최종 판정: E 로컬 범위 독립 검증 통과, 추가 필수 로컬 차단 없음. 실제 SANDBOX 및 제품별 webhook 계약은 외부 차단으로 남아 E 전체는 미완료다.** 미실행 외부 검사를 통과로 표시하지 않았다. F 구현·커밋·푸시·배포는 본 검토에서 수행하지 않았다.
