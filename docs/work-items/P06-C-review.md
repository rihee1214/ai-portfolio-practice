# P06-C 독립 검토

## 최종 독립 검증 판정 (2026-10-26)

**P06-C 로컬 비회원 조회 자격 범위의 독립 전체 검증 통과. R-C01~03 해결, 필수 잔여 차단 없음.** 아래 보류/미검증 문구는 당시 이력으로 보존한다. 실제 주문·운영 인증 및 P06-D 완료를 뜻하지 않는다. 이 세션에서는 검토 문서·검토 증거만 작성했으며 커밋·배포·푸시는 하지 않았다.

직접 실행: `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p06-c-review-final-20261026`. 07:40:07~07:43:36 KST, 종료 코드0, status=passed/preservation=passed. Java280·브라우저46·프런트139·Node72, 타입/lint/build/하네스 gate와 필수 목록 대조 통과. Java 모든 사례 passed, 필수 실패·skip·누락 없음. 실제 제공자 probe는 Java IT에 포함되며 일반 브라우저46에 중복 합산하지 않는다.

소스323개는 개발3 inputs 및 실행 후 현재 파일과 동일하다(개발 대비 차이0, 현재 해시 불일치0). inputs.json SHA256 `B1717B43F425B6B8ABFCBE1F035D7500D2FB52FF97D44CB0F61B4BFD895132B1`, 기준 HEAD `7fcb6409c065c724604e8bf32f8e0351aaaf85c9` 위 미커밋 P06-C다. 이후 검토 문서/증거만 갱신했다. 원본은 위 새 실행 경로, 선별 사본은 `evidence/P06-C-review-final-*`에 보존한다(result/inputs/coverage/Java 사례/프런트·브라우저 coverage/명령 로그/before·after/소스·실패 보존 확인/DB 대상·요약·브라우저 report·제공자 probe).

### 제약별 실제 assertion과 결과

DB 메서드는 `PostgresJdbcIT`, 각 실제 실행 결과는 `P06-C-review-final-java-cases.json`과 coverage 및 해당 원본 JUnit XML에 연결된다. 총 개수만으로 판정하지 않고 아래 assertion과 실제 passed를 대조했다.

| 조건 | 실제 근거·판정 |
| --- | --- |
| C01 비밀번호 | GuestPasswordTest 두 메서드와 guestPasswordHashPreparationAndCreationAreBoundAtomicAndNeverMerge:11/129 거부·12/128 및 emoji·공백 보존·불일치/surrogate 거부·Argon2id/salt·trim/접미부 차이·원문 비저장. 통과 |
| C02 인증·준비·생성 | guestPasswordHashPreparationAndCreationAreBoundAtomicAndNeverMerge, guestPasswordHashingRechecksExpiryOutsideTransactions, guestPasswordPreparationReplacementsCannotChangeConsumedBuyer:미인증/타 브라우저 거부·해시 중 만료 재검사·생성/소비 rollback·한 승자·동일/다른 준비 재시도·소비 뒤 늦은 저장409/해시 불변. 통과 |
| C03 조회·제한 | guestLookupHttpChecksCsrfCookieUniformErrorsAndMemberSeparation, guestLookupLimitsAndCredentialFailuresAreUniformAndDurable:주문/번호/비밀번호 오류 공통401·주문 상한 경합1승자·출처 상한·899/900초. 통과 |
| C04 묶음 격리 | guestPasswordHashPreparationAndCreationAreBoundAtomicAndNeverMerge 및 실제 guest-lookup PC/모바일:같은 번호 별도 buyer·주문번호 재배정 거부·타 묶음404·구매 세션만으로 조회401·조회 세션의 회원API401. 통과 |
| C05 수명·재검사·폐기 | guestLookupSessionsEnforceIdleMaximumVersionRotationAndLogout, guestLookupLockWaitRechecksExpiryAndRevocation, guestLookupRechecksVersionAfterHashAndEnforcesLocalAndSecureBoundaries, guestSessionRotationFailureRollsBackAndLookupSerializesWithLogout:유휴/절대 경계·해시만 저장·변조/옛 자격 거부·hash중version변경 무발급·잠금 대기 뒤 만료·회전 INSERT 실패 rollback·실제 조회 거래 뒤 logout 대기 및401. 통과 |
| C06 HTTP·모드 | guestLookupHttpChecksCsrfCookieUniformErrorsAndMemberSeparation 및 guestLookupRechecksVersionAfterHashAndEnforcesLocalAndSecureBoundaries:CSRF·HttpOnly/Lax·localHTTP 예외/Secure true·no-store·토큰 응답 비노출·disabled 준비/조회/로그인503·비fixture bean 부재. 통과 |
| C07 화면·복구 | GuestStore 준비 단위 및 GuestAccessStore4개, guest-lookup.spec.ts PC/모바일:별도 context 조회·준비 응답 유실 reload·로그인 응답 유실 후 재로그인·로그아웃 전송/성공응답 유실 재시도·비밀번호 초기화·저장소 비노출·이전 쿠키401. 통과 |
| C08 경계·기존 회귀 | ModuleArchitectureTest/ModuleRulesTest 실제 클래스와 부정 fixture·기존 회원/전화 인증 회귀, V23 새 DB 마이그레이션과 DB 제약/거래 검사. 통과 |
| C09 전체·보존 | 신규 필수 등록 및 전체 하네스 gate/coverage, 소스323개 동일, before/after 및 실패 manifest 대조. 통과 |

R-C01은 미확정 로그아웃 상태와 명시적 재시도 UI 및 전송 전/서버 성공 후 응답 유실 브라우저 assertion으로 해결했다. R-C02는 해시 후 상태 재검사, 세션 INSERT 실패 rollback, 조회·logout 실제 잠금 경합과 로그인 응답 유실 검사로 해결했다. R-C03은 로컬/쿠키/fixture 경계 검사와 소비 전 준비 교체·소비 뒤409 계약 및 준비/소비 경합 검사로 해결했다. 새 증거의 실제 통과를 근거로 판정하며 이전 실패를 소급 변경하지 않는다.

### 보존 및 실패 이력

새 DB `d2_it_5e90a9fb153541cc89d5fc62d70d88d4`, 새 미디어 `.local-tools/p06-c-review-final-20261026/db/media`, 새 에뮬레이터 `.local-tools/phone-emulator-7ab9e985adba490ab6cdfda14f975fdf`. 에뮬레이터를 포함한 전체 명령 정상 종료를 확인했다. 기존 DB171개 유지 후172개, 미디어1108개 유지 후1126개. 기존 기본/데모 DB 덤프·컨테이너·알려진 리스너·미디어 보존 비교 통과. 과거 검증 DB는 존재 목록 검사이며 전체 행 전수 덤프 비교가 아니다.

실행 전후 A 실패2162개·B405개·C791개 모두 누락/해시 불일치0. C manifest는 최초 미구현 컴파일 실패를 포함하여 이전790개 보고보다1개 늘어난 최종 목록이다. 개발1 빈 중복 실패, 개발2 신규 PC/모바일 조회 시간초과, 과거 A/B 원인 미확정 실패와 서식 검사 이력을 유지한다. 개발2 시간초과의 정확한 원인은 **여전히 미확정**이다. 개발3/이번 독립 통과는 비재현을 보여주지만 원인을 해결했다는 증거가 아니다. 테스트 삭제·필수 축소·전체 타임아웃 확대 없이 검증했고 이 독립 실행은 첫 시도에서 통과했다.

### 남은 범위·한계

로컬 가상 주문 참조 조회 자격만 검증했다. 실제 주문 생성·결제/배송/클레임과 인증 소비의 주문 거래 원자성은 P08-C 이후다. 비밀번호 변경·복구/복구 코드(P06-D), 기존 묶음 연결(P06-E)은 구현하지 않았다. 실제 SMS·실명 확인·운영/다중 인스턴스·실제 기기 및 사용자의 직접 확인/이해는 미검증이다. 기본 비활성 및 명시적 로컬 모드 한계를 유지한다. 기존 서버·DB·검증 DB·미디어·데모·실패 증거를 보존했으며 P06-D·커밋·배포·푸시는 진행하지 않았다.

## 계약·검사 매핑 문서 대조 (2026-10-26)

개발3 진행 중 P06-C.md의 C01~09 구체 메서드/assertion 표와 shop-design.md의 P06-C 경계 결정을 읽었다. 소비 전 준비 재전송/교체 허용·마지막 잠금 저장값 사용·소비 후409, 로그인 쿠키 응답 유실 후 명시적 재로그인, 로그아웃 전송/응답 유실 후 미확정 상태·멱등 재시도 정책이 현재 코드 및 추가 검사 의도와 일치한다. 기존 단위/DB/브라우저 검사와 연결돼 있으며 이 문서 대조에서 새 확정 결함은 발견하지 않았다.

GuestBuyer/조회 세션을 guest가 소유하고 identity.api.PasswordHashes로 Argon2id를 재사용한다는 결정은 초기 identity 소유안과의 차이를 명시했다. 공개 계약에는 String 입력/출력과 boolean만 있고 SharedPasswordHashes가 내부 ArgonPasswordHasher를 감싸므로 guest에 identity 내부 타입을 노출하지 않는다. 실제 ArchUnit 및 기존 회원 회귀 통과는 최종 전체 실행에서 확인한다.

desktop 신규 조회 시나리오2초 통과와 A2162/B405/C790개 실패 증거 보존은 개발 측 진행 보고다. 이 읽기 검토에서는 해당 실행·manifest를 재검증하지 않았다. 일부 브라우저 성공으로 전체 통과나 이전 timeout 원인 해결을 주장하지 않으며, 이전 원인 미확정 실패는 유지한다. 개발 전체 통과·소스 동결 후 독립 전체 실행과 실패 증거 대조가 남아 있다. 제품/검사 소스·기존 자원 변경 및 하네스 실행 없이 검토 문서만 갱신했다.

## 개발3 진행 중 보완 정적 확인 (2026-10-26)

개발3 실행 중 제품/검사 소스를 읽기만 했다. **R-C01~03의 주요 수정 및 추가 assertion은 코드상 확인했으나 실행 해결 판정은 보류한다.** 개발2의 저장된 result.json은 status=failed/preservation=passed로 직접 확인했다. 신규 PC/모바일 조회 시나리오 시간초과와 finally 정리로 원래 실패 지점이 가려졌다는 내용은 개발 측 보고이며 이 검토자가 원인을 확정한 것이 아니다. 기존44개 통과는 신규 조회 시나리오 통과를 대신하지 않는다.

- R-C01: GuestAccessStore.logout이 logoutPending을 설정하고 서버 LOGGED_OUT 확인 후에만 해제한다. GuestOrdersPage는 미확정 안내·재시도 버튼을 유지하고 주문 목록을 숨긴다. pending 중 focus/load로 목록을 다시 보이지 않도록 막는다. 실제 브라우저 시나리오에 전송 전 abort→재시도 버튼, 서버 성공 뒤 응답만 abort→이전 쿠키401·재시도 버튼 유지→정상 재시도 항목이 추가됐다. 해당 브라우저의 실행 성공은 아직 확인하지 못했다.
- R-C02: `guestSessionRotationFailureRollsBackAndLookupSerializesWithLogout`는 새 세션 INSERT를 CHECK로 거부하여 옛 자격 접근 유지·revoked_at NULL·세션1개를 확인한다. 실제 orders 거래가 잠금을 보유한 동안 logout을 시작하고 pg_stat_activity의 Lock을 관측한 뒤 해제하여 이후 orders401을 검사한다. 기존의 logout 이후 조회만 하는 race보다 요구한 직렬화 경로를 직접 검증한다. `guestLookupRechecksVersionAfterHashAndEnforcesLocalAndSecureBoundaries`는 hashes.matches 중 version 변경 후 로그인401·세션0개를 검사한다. 로그인 성공 응답만 유실한 뒤 재로그인하는 실제 브라우저 assertion도 추가됐다.
- R-C03: `guestPasswordPreparationReplacementsCannotChangeConsumedBuyer`는 동일 값 준비 재시도, 다른 값 준비, 늦은 해시 연산 중 createBuyer 소비 완료→늦은 prepare409와 소비된 buyer의 최종 해시 불변을 검사한다. disabled.prepare/login/orders503, Secure=true 응답 쿠키, 비fixture 컨텍스트의 fixture/전용 보안 bean 부재 검사가 있다. 준비를 주문 전 교체 가능하게 하고 소비 후 거부하는 정책은 최종 작업 문서에 명확히 남겨야 한다.
- 위 신규 DB 메서드3개는 required-tests.json에 등록된 것을 확인했다. 해당 등록과 코드 독해만으로 실제 실행 통과를 주장하지 않는다. 개발1의 빈 중복 실패를 수정한 SharedPasswordHashes 어댑터는 기존 회원 PasswordHasher 타입과 공개 PasswordHashes 역할을 분리한다. 기존 회원 회귀까지 전체 하네스로 검증해야 한다.

현재 브라우저 진단 코드는 단계별 진행 로그와 별도 context의10초 기본 제한을 추가했고 최종 정리에서 timeout 뒤 오류를 덮지 않도록 변경됐다. 이를 근거로 원래30초 실패가 해결됐다고 판정하지 않는다. 최종 개발 통과·소스 동결·독립 새 경로 전체 실행 전에는 완료 아님. 이 세션에서는 하네스나 서버/DB 조작, 소스 변경, P06-D 진행을 하지 않았다.

## 초기 구현 정적 검토 (2026-10-26)

개발 측 요청에 따라 변경 중 소스/API/UI/검사와 필수 목록을 읽었다. **R-C01의 오류 복구 경로 및 R-C02~03의 검사 공백을 보완하고 독립 전체 실행하기 전 완료 판정 보류.** 이 결과는 실행 재현이 아닌 코드/assertion 검토다. 제품·검사 소스 및 기존 자원을 변경하지 않았고 하네스는 실행하지 않았다.

| ID | 판정 | 코드 근거·영향·필요한 검증 |
| --- | --- | --- |
| R-C01 | P2: 로그아웃 실패 시 재시도 수단이 사라짐 | `GuestAccessStore.run` 38행은 모든 요청 시작 때 authenticated=false로 초기화하고 `logout` 50행도 같은 경로를 사용한다. `GuestOrdersPage` 12행의 로그아웃 버튼은 authenticated=true일 때만 표시된다. CSRF 조회 또는 logout 전송이 실패해 서버 세션/HttpOnly 쿠키가 살아 있어도 주문 화면과 로그아웃 버튼은 사라지고 일반 로그인 실패 안내와 로그인 폼만 남는다. 새로고침하면 살아 있는 세션으로 다시 주문이 보일 수 있다. 민감 목록은 즉시 숨기되 서버 폐기 미확정 상태를 구분하여 명시적인 로그아웃 재시도 수단을 유지해야 한다. 단위/브라우저에서 폐기 전 전송 실패→미확정 안내·재시도→캡처한 자격401을 확인하고, 서버 폐기 성공 후 응답만 유실된 경우도 안전하게 재시도 가능한지 검사한다. 현재 정상 logout 검사와 load 전송 실패 검사는 이 경로를 검증하지 않는다. |
| R-C02 | 필수 동시 상태·거래 assertion 공백 | login은 해시 검증 뒤 buyer 행을 잠그고 password_hash/password_version/verified_phone을 재검사하지만 현재 `guestLookupSessionsEnforceIdleMaximumVersionRotationAndLogout`는 이미 발급한 세션의 version 변경만 검사한다. 해시 검증 도중 또는 buyer 잠금 대기 중 상태가 바뀌었을 때 새 세션이 생기지 않는 assertion이 필요하다. 또 기존 토큰 폐기 후 새 세션 INSERT가 실패하는 상황에서 이전 토큰 폐기와 새 발급이 함께 rollback되는 검사, 로그인 응답 유실 시 복구/재로그인 계약 검사가 없다. `guestLookupLockWaitRechecksExpiryAndRevocation`의 마지막 race는 각 스레드가 logout을 끝낸 뒤 orders를 호출하므로 실제 조회와 logout이 경쟁하는 순서를 강제하지 않는다. 격리 DB에서 순서를 제어해 조회/폐기 직렬화와 폐기 후 접근 거부를 확인해야 한다. 비밀번호 변경 제품 기능 구현을 요구하는 것이 아니라 C의 현재 재검사/조회 세션 분기를 fixture 변경으로 검증하는 요청이다. |
| R-C03 | 모드·쿠키 및 준비 계약의 검사/기록 공백 | 읽기 도중 JdbcGuestAccess에 enabled 인자가 추가됐으나 새 조회/준비 API의 disabled 거부, Secure 기본값과 local HTTP 예외를 직접 검증하는 assertion은 현재 검사에서 확인하지 못했다. B의 provider-disabled 검사와 C의 secure=false 쿠키 검사만으로 대신할 수 없다. `prepare`는 같은 시도의 prepared_password_hash를 매번 덮어쓰므로 같은 값 재시도와 다른 값/동시 준비의 정책을 문서에 명시하고 그 최종 저장값·createBuyer와의 경합을 검사해야 한다. P06-D 비밀번호 변경과 구분되는 주문 전 준비 계약이다. fixture는 테스트 소스+c3-fixture에 한정된 것을 확인했지만 비fixture 실행에서 테스트 endpoint를 사용할 수 없다는 확인 근거도 최종 검토에 연결한다. |

현재 확인한 긍정적 구조와 검사 연결: 12~128 코드포인트/잘못된 surrogate 거부, 공백 보존·확인 일치, 기존 Argon2id 공개 계약 재사용 및 salt/접미부 차이, 준비 해시 연산 전후 현재 구매 인증 재검사, 내부 생성·소비 rollback 및 동시 한 승자, 같은 번호의 별도 GuestBuyer·주문 재배정 거부, 별도 난수 쿠키/DB 해시, 유휴/최대 만료와 version/폐기, 주문·출처 제한899/900초 검사가 있다. 실제 다른 browser context를 이용하는 가상 주문 조회·타 묶음404·회원401·로그아웃 후 이전 쿠키401·비밀번호 준비 응답 유실 브라우저 시나리오도 추가됐다. 이들은 코드상 assertion 확인이며 실행 통과 주장이 아니다.

새 GuestPasswordTest 및 guest DB6개·GuestAccessStore 단위·PC/모바일 guest-lookup 시나리오는 필수 목록에 등록돼 있다. 최종 소스가 바뀌면 새로 대조한다. createBuyer/registerOrder는 내부 계약이고 /__test/guest-order는 테스트 소스 fixture다. 실제 주문/결제/배송 기능 또는 P08-C 원자성 검증으로 확대하지 않는다. P06-D는 진행하지 않았다.

2026-10-26 사전 검토. **범위와 필수 assertion만 정리했으며 구현·실행 결과는 미검증, 완료 판정 보류다.** 제품/검사 소스 변경, 하네스 실행, 서버/DB 조작, 커밋·배포·푸시는 하지 않았다.

## 승인과 기준

개발 세션 `01a171ea-4e72-7563-8b5d-5643cb7888a2`의 최신 실제 userMessage를 read_thread로 확인했다. 사용자는 P06-C 진행·독립 검토와 기존 자원/실패 기록 보존을 요청했고 P06-D 진행을 금지했다. 과거 P06-C 보류 이후의 직접 승인임을 확인했다.

AGENTS.md, README.md, development-workflow.md, verification-harness.md, P06-B 마감 기록, implementation-plan §8, requirements §7, shop-design의 GuestBuyer/GuestAccessSession/VerificationAttempt 및 주문 생성 경계를 기준으로 한다. 현재 HEAD는 `7fcb6409c065c724604e8bf32f8e0351aaaf85c9`다. B 완료는 C의 검증을 대신하지 않는다.

## 범위와 세부 결정 필요 사항

- P06-C: 구매자가 직접 정한 조회 비밀번호, GuestBuyer 및 별도 조회 세션, 다른 브라우저에서 주문번호+등록 전화번호+비밀번호로 가상 주문 fixture 조회. F02와 AC02·AC04·AC12의 해당 부분만 검증한다.
- 비밀번호 기본안은 12~128자이며 공백·붙여넣기를 허용하고 확인 입력이 일치해야 한다. 임의 trim/정규화·해시 구현의 길이 절단으로 다른 비밀번호가 같아지는 일이 없어야 한다. 문자 수 산정·멀티바이트 길이와 라이브러리 한계를 계약으로 명시한다. 검증된 느린 해시 라이브러리를 사용하고 원문/인증번호/자격 토큰은 로그·URL·영구 브라우저 저장소에 남기지 않는다.
- GuestBuyer는 이름/전화번호만으로 고유 사용자나 기존 주문 묶음으로 취급하지 않는다. 새 자격 설정은 현재 구매 세션의 유효하고 결합된 전화 인증에 근거해야 한다. 이름/번호/인증 여부를 클라이언트가 임의 지정해 자격을 만들 수 없어야 한다.
- 조회 세션은 구매 세션·회원 세션과 구분하고 GuestBuyer·passwordVersion·토큰 해시·유휴/최대 만료·폐기 상태를 서버에서 확인한다. 기본안 유휴30분·최대12시간의 정확한 경계와 활동 갱신 조건을 확정한다. P06-D의 비밀번호 변경/복구 기능은 구현하지 않되, 현재 세션의 revoked/version 불일치 거부는 조회 세션 계약으로 검사한다.
- 현재 세션 로그아웃/폐기와 P06-D의 비밀번호 재설정 후 일괄 세션 폐기를 구분한다. 재설정·복구 코드 발급/회전·전화 재인증 복구는 이번 범위가 아니다.
- 가상 주문 fixture의 생성/연결 방식과 접근 가능한 모드를 명시한다. 실제 주문 생성·GuestBuyer 생성/연결·전화 인증 결과 소비의 주문 트랜잭션 원자성은 P08-C다. fixture 경로로 임의 실제 주문·다른 GuestBuyer에 연결되거나 운영 환경에 테스트 자격 생성 경로가 노출돼서는 안 된다.
- P06-E 재구매 연결과 묶음 통합은 제외한다. C에서도 같은 전화번호의 서로 다른 묶음 격리는 반드시 보장한다. 실제 SMS·실명 확인·운영 인증으로 완료 범위를 확대하지 않는다.

## 제약조건 → 요구할 자동 검사

아래는 구현 전 요구 목록이다. 존재하거나 통과한 테스트로 주장하지 않는다. 구현 후 실제 클래스/메서드·시나리오·실행 증거를 연결한다.

| ID | 제약 | 검사와 기대 assertion |
| --- | --- | --- |
| C01 | 비밀번호 입력·저장 | JUnit으로 11/12/128/129 경계, 공백/붙여넣기/확인 불일치/멀티바이트·접미부 차이를 검사. 느린 해시 검증 성공·잘못된 값 거부·같은 원문의 별도 salt 결과 및 DB 원문 비저장. 프런트 우회 입력도 서버에서 거부 |
| C02 | 구매 인증과 자격 설정 | 새 PostgreSQL/API 검사로 미인증·다른 세션/번호/용도·만료·소비된 증명 거부. 유효한 B 인증에서만 새 자격 생성. 동시 설정·중간 DB 오류·응답 유실에서도 중복 GuestBuyer/부분 자격/허위 성공이 없고 재시도 계약이 명확함. 실제 주문 원자성 완료로 확대하지 않음 |
| C03 | 조회 인증과 공통 오류 | 주문번호+등록 전화번호+비밀번호 모두 맞을 때만 성공. 없는 주문/다른 번호/틀린 비밀번호·잘못된 입력에 존재 여부를 드러내는 메시지·상태 차이가 없도록 HTTP 검사. 반복 시도 제한을 키/창/수치로 확정하고 정상·경계·동시 요청 및 새 세션/번호 표기 변경 우회 거부 검증 |
| C04 | 묶음 격리 | 같은 전화번호의 서로 다른 GuestBuyer와 서로 다른 주문 fixture를 생성. 주문번호·URL·요청 body의 ID 조작·세션 교차 사용으로 다른 묶음의 주문/결제/배송 등 노출된 자원에 접근 불가. 회원·구매 세션만으로 조회 권한 부여 불가. 번호만으로 병합 없음 |
| C05 | 조회 세션 수명·폐기 | DB/HTTP 검사로 토큰 무작위성·해시 저장·변조/누락 거부, idle30분/absolute12시간 직전·정확한 경계, 활동 연장에도 최대 수명 초과 불가. 잠금 대기 후 만료 재검사, revoked/passwordVersion 불일치 거부, 로그아웃 뒤 캡처한 이전 자격 재사용 거부. 동시 조회/폐기와 상태 갱신의 원자성 검증 |
| C06 | HTTP 경계·민감정보 | CSRF 없는 상태 변경 거부, 쿠키 HttpOnly/SameSite 및 모드에 맞는 Secure 정책, 민감 응답 no-store. 응답/오류/로그/URL/브라우저 저장소에 원문 비밀번호·조회 토큰·해시 노출 없음. fixture 활성 모드 허용 및 비허용 모드 거부 assertion |
| C07 | 화면·다른 브라우저 | Vitest로 비밀번호/확인 입력·중복 제출·늦은 응답·로딩/오류/재시도·로그아웃 시 민감 상태 초기화. 실제 PC/모바일 브라우저에서 자격 설정→독립 browser context 조회→새로고침→로그아웃/거부, 다른 묶음 거부·키보드/붙여넣기 확인. fixture 결과와 실제 주문 기능을 화면에서도 구분 |
| C08 | DB·모듈·회귀 | 새 DB 마이그레이션과 FK/고유/상태 제약의 거부 검사, 실제 트랜잭션 rollback/동시성. ArchUnit 정상 소스와 부정 fixture 및 기존 회원/B 인증 회귀 유지. 기존 데이터에 마이그레이션하지 않음 |
| C09 | 전체 증거·보존 | 신규 필수 Java/프런트/브라우저 검사 등록. 개발 통과·소스 동결 후 독립 새 경로에서 ./verify.ps1 -Offline 직접 실행. 실패/skip/누락0, result.status와 preservation passed, 제약별 assertion/결과/소스 식별 연결 |

## 현재 판정과 다음 진입 조건

C01~C09는 모두 아직 실행 미검증이다. 세부 결정(비밀번호 길이 정의/해시 방식, 자격 설정 재시도, 조회 세션 수명/갱신/폐기, 실패 제한, fixture 모드)을 작업 기록에서 확정해야 한다. 읽기만으로 제품 결함이나 통과를 확정하지 않는다.

개발 결과·실제 검사 매핑·소스 동결 정보가 전달되면 코드/assertion을 검토하고 별도 새 증거/DB/미디어 경로에서 독립 전체 하네스를 실행한다. 필요한 로컬 캐시/의존성이 없거나 필수 실패·skip·누락이면 원인과 차단 상태를 남기고 완료로 선언하지 않는다. 검사 삭제·필수 목록 축소·타임아웃 확대만으로 실패를 감추지 않는다.

기존 서버·DB·과거 검증 DB·미디어·데모·실패 증거를 보존한다. 과거 원인 미확정 실패와 서식 검사 실패 기록을 새 성공으로 소급 수정하지 않는다. 이번에는 보존 하네스를 실행하지 않았으므로 새 보존 통과를 주장하지 않는다. P06-D·배포·푸시는 진행하지 않는다.
