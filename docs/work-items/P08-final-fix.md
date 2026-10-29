# P08 최종 검토 보완

2026-10-28. R-FINAL-01·02만 수행한다. SOURCE RELEASE는 G 개발1 종료 후 받았다. 기준472입력 SHA690D1D23868242821F8C2554463A50C425D7CD49D11FD001B174ADDA8A3C2658. **수정·집중 검증 인계 준비 / 선택 없는 개발·독립 전체 대기**다. P08 전체 완료가 아니다. 실제 Toss PG 외부차단과 이전 실패 원본은 유지한다.

| 제약 | 검사와 기대 결과 | 실제 증거 |
|---|---|---|
| R-FINAL-01 인증 변경 직후 금액/결제 상태 폐기 | PaymentStore/PaymentPage 실제 DOM: 인증 무효화 즉시 view0, A→B 및 같은 ID 새 인증 세대의 늦은 성공/실패 게시0, 늦은401의 새Auth무효화0, 현재401 view0, StrictMode 재구독, 자동 POST0 | `finalfix-frontend-all1.txt` 신규10 포함216통과; focused2 PC/mobile 신규2통과 |
| R-FINAL-01 기존 사용자 명령 보존 | 기존 CSRF prePOST 실패의 명시적 재시도, UNKNOWN 재승인0, SDK 명시적 확인/응답유실 재조회 유지 | 같은 단위 전체·focused2 payments-mock/sandbox 기존시나리오 모두통과 |
| R-FINAL-02 마지막 예약 잠금 대기 뒤 현재 세션 만료 | `paymentPreparationRechecksExpiryAfterReservationWait` parameter5: PostgreSQL issued_coupon/inventory_balance 실제 대기 관측 후 injected session Clock을 저장 expiry_time으로 이동, 주문/예약은 유효. 승인401, 모든 참여 행/제공자 효과 불변. DEMO·0원·SANDBOX 준비/확인 경계 포함 | focused2 JUnit 5통과 |
| R-FINAL-02 EXISTING guest 최대 만료 | `paymentExistingGuestRechecksMaximumAfterOrderWait` parameter2: 실제 EXISTING 주문, idle==max이며 max−1초에 order 잠금 대기 관측→정확max 도달, HTTP 세션 유효 유지. status/approve401, 모든 참여 행/제공자 효과 불변 | focused2 JUnit 2통과 |
| 기존 비회원/worker 계약 | `realGuestOrdersUseIndependentAccessSessions`, `paymentHttpWaitRechecksSessionAndWorkerSurvivesSuspension`, 실제0원crash/late-success/orderlock 경합: 별도 GAS 이력과 구매브라우저 결제 분리, 이미 시작한 결제 worker는 현재 고객 인증과 독립 | focused2 선택17 전체통과 및 전체browser106통과 |

집중 루트는 누락된 과거 필수 검사로 실패할 수 있으며 전체 통과로 기록하지 않는다. 최종 선택 없는 개발·독립 루트는 부모가 순차 조정한다. 커밋·푸시·배포·P09·기존 DB/미디어/서버 삭제 없음.

## 재현과 수정 이력

- `finalfix-frontend-red1.txt`: 기존 제품에서 실제 assertion4 실패/7통과. StrictMode 실제 PaymentPage의 1,000원 DOM 잔류, A→B 늦은401이 새 AuthState를 무효화, 같은identity 새세대 옛성공 게시, 현재401 뒤 view잔류. A→B 늦은성공의 단순identity검사는 기존에도 통과했으며 다른 실패와 혼동하지 않는다.
- `.local-tools/p08-finalfix-backend-red1-20261028`: 테스트 컴파일2 오류(Timestamp 한정명·JUnit Boolean overload). 제약조건 RED가 아니다. 원본/미디어/에뮬레이터 보존, preservationpassed.
- `.local-tools/p08-finalfix-backend-red2-20261028`: 기본183 통과, 신규 실제 PG parameter7 모두 expected401/actual200 assertion실패, error/skip0, preservationpassed. DEMO 쿠폰/재고·0원·SANDBOX 준비/확인과 EXISTINGguest status/approve 모두 실제 잠금 대기를 먼저 관측했다. read-only `finalfix-backend-red2-effects.json`에 잘못 확정된 PAID와 DEMO 승인호출/효과1을 기록했다. SANDBOX의 payment_mock0은 실제 PG호출0 뜻이 아니며 별도 provider stub 계약으로 구분한다.
- 첫 화면 수정의 집중11 통과 후 주변 흐름 검사 `finalfix-frontend-red2.txt`에서3실패/13통과. 하나는 첫 수정이 정상 AuthProvider refresh의 재조회를 막은 회귀이며, 둘은 기존 CSRF401에서 DEMO/SANDBOX view가 남는 같은 R-FINAL-01 경계다. 각 실패를 유지한 뒤 정상 인증 갱신은 새로운 GET만 허용하고 승인 latch는 유지하도록 수정했다. 현재401/403은 view폐기 후 계속blocked다.
- `finalfix-frontend-all1.txt`: 전체프런트216 통과, `finalfix-typecheck1.txt`·`finalfix-lint1.txt` 종료0. 신규 단위10개는 기존206개에 추가했으며 삭제 없음. 이후 집중루트에서 브라우저/서버를 확인한다.

## 구현 경계

PaymentStore는 AuthStore 구독·인증 generation·abort를 소유하고 await 뒤와 최종 게시 직전에 동일세대를 검사한다. 옛401도 새 인증에 전달하지 않는다. 인증 갱신은 view를 즉시 지우고 승인 latch를 보유하며, 새 인증의 서버 NONE/PENDING 응답 뒤 사용자가 직접 승인한다. CSRF 준비 실패는 기존 명시적 재시도, UNKNOWN은 기존 재승인 금지를 유지한다. PaymentPage는 실제 종료/StrictMode cleanup 때 store를 정리하고 재구독한다.

PaymentCoordinator 준비와 SANDBOX setup은 전체 예약 inspect 뒤 같은 권한을 재확인한다. JdbcOrders는 기존 잠금 순서로 이미 확보한 guest checkout/attempt/buyer/session과 HTTP session/order를 동일거래에서 다시 검사한다. 행잠금은 시간 경과를 막지 않으므로 postwait 검사가 필요하다. 새 키/행을 역순으로 잠그지 않으며, 결제 결과 apply/query/worker에는 고객 인증을 추가하지 않는다. 독립 GAS 이력과 최초 구매브라우저 status/approve의 차이는 유지한다.

## 집중1 결과와 추가 경계 정밀화

`.local-tools/p08-finalfix-focused1-20261028`은 기본183통과, 실제 DB17중16통과·브라우저host1실패, browser105/106이며 rootfailed/preservationpassed다. 신규7 만료 경계와 기존 정지 후worker settlement·0원 실제프로세스중단·늦은성공·주문lock경합·SANDBOX·비회원독립권한은 통과했다. 신규 인증변경 브라우저 PC/mobile2도 통과했다. 이를 전체 통과로 표시하지 않는다.

실패는 기존 `coupon-reservations.spec.ts` desktop 마지막 release→reload의 `사용 가능` DOM assertion이다. error-context에는 서버 연결 오류가 있고 read-only `finalfix-focused1-coupon-readonly.json`의 desktop/mobile 양쪽은 AVAILABLE/version5, 예약 RESTORED/RELEASED다. 쿠폰 상태 변경 실패나 이번 제품 수정이 원인이라고 단정할 근거는 없다. 원인은 미확정으로 유지한다. timeout·검사·쿠폰/인증 제품 코드를 변경하지 않고 집중2에서 기존 검사 전체를 재실행한다.

집중2의 guest 최대 경계는 실제 GAS `requireCurrent`를25분 이하 간격으로 호출해 idle을 갱신한 뒤 max−61초까지 전진한다. 실제 재구매 전화/EXISTING 연결은 max−1초에 수행한다. 주문 대기 전 idle==max를 assertion하고 실제 order SQL 대기 관측 뒤 정확히1초만 전진한다. HTTP session은 여전히 유효하다. 최대 만료와30분 idle 초과를 혼동하지 않으며 guest/HTTP 세션 기한을 대기 중 직접 변경하지 않는다. 첫 RED와 집중1의 이전 fixture조건은 과거 증거로 그대로 보존한다.

## 집중2 최종 인계 증거

명령은 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-finalfix-focused2-20261028`이며 MAVEN_ARGS의 PostgresJdbcIT 선택을 사용했다. 2026-10-28 22:32:42~22:38:27 KST. 기본JUnit/ArchUnit183+선택PostgreSQL17=200 통과, browser106 통과·failure/error/skip/flaky0, 보존passed. 루트는 과거 필수 `accountHttpOwnerCsrfValidationAndPrivacyBoundaries` 미선택을 거부하여 **종료1/statusfailed**다. 이 결과를 선택 없는 전체 하네스 통과로 표시하지 않는다. focused2/db/target.txt의 새 DB는 `d2_it_7a302836a30b41c3995318754ac7334d`, 미디어는 같은 실행의 db/media다. 새 emulator는 `.local-tools/phone-emulator-3f9effb6aa8b4ea380de8005bd3c18c4`이며 실패 root와 함께 보존한다.

최종 소스 HEAD460aa8bb0bb22f5138849eb00c050abd18f6fb53 dirty, 472입력 SHA`A20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF`. focused2 입력=현재=동결본, 불일치0. G개발1대비 파일10개 변경/추가삭제0이며 제품4개(PaymentStore/PaymentPage/PaymentCoordinator/JdbcOrders), 검사3개, 필수목록3개다. 기존 이름·중복 항목의 multiplicity도 그대로 유지했다. 상세 `.local-tools/p08-execution/finalfix-focused2-verification.json`, `finalfix-source-freeze.json`, `finalfix-source-changes.json`을 따른다. 이후 제품/검사/하네스 변경 없음.

실패 manifest `evidence/P08-final-fix-development-failure-manifest.json`은 신규2458파일/4실패root/4emulator/8sidecar, SHA`B73AD0841A11AFCBDD50629D0DEC3F6152C15868FADABAAF4E46D8E7F5E41103`이다. compile실패, 실제RED, 첫집중browser실패, 최종집중의 의도된 필수누락 실패를 모두 보존하며 소급통과로 바꾸지 않았다. 기존31manifest63362파일 사전감사도 불일치0이다. 최종 합산32manifest65820파일의 해시·존재·중복충돌0, 기존31manifest 자체SHA변경0은 `finalfix-final-failure-audit.json`에 기록했다.

필수누락 gate에서 종료되어 focused2의 coverage.json/java-cases.json은 생성되지 않았다. 이 두 파일의 복사 시도 오류는 `finalfix-evidence-copy-note.md`에 기록했다. JUnit 원본·별도 Java summary·실제browser JSON과 소스/보존감사19개 복사본을 `evidence/P08-final-fix-focused2-*`에 보존하며 전체 coverage 통과로 대신하지 않는다.

부모가 동일동결소스로 단일 scoped 재검토 후 새 선택 없는 개발·독립 전체를 순차 실행해야 한다. 원래 phone fetch 실패 원인 미확정과 집중1 coupon reload 연결 오류 원인 미확정은 유지한다. 실제 Toss SANDBOX·외부 webhook/실카드·실문자·실기기는 미검증이며 P08전체 완료·P09·커밋·푸시·배포를 수행하지 않는다. 사용자 직접 화면/학습 확인도 미확인이다.

## 후속 최종 전체 검증
수정소스 A20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF의G개발2/독립1전체통과(Java451/browser106/frontend216/Node75·필수50묶음/보존),R-FINAL01/02 실제7개서버경계및화면회귀재실행. P08-G-review.md/P08-execution-summary.md 참조. 선택집중실행의미선택coverage실패기록은그대로유지한다.
