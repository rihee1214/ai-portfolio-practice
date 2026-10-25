# P06-B 독립 검토

## 최종 독립 검증 판정 (2026-10-25)

**P06-B 로컬 구매 인증 범위의 독립 전체 검증 통과. R-B01~03 해결, 필수 잔여 차단 없음.** 아래의 보류·미검증 문구는 각 시점의 이력으로 보존한다. P06-C·실제 주문/운영 인증 완료를 뜻하지 않는다. 이 검토에서 커밋·배포·푸시는 하지 않았다.

이 세션에서 직접 실행한 명령: `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p06-b-review-final2-20261025`. 18:01:22~18:04:42 KST, 종료 코드0, result.status=passed, preservation=passed. Java269·브라우저44·프런트134·Node72 및 타입/lint/build/하네스 gate/필수 목록 대조 모두 통과했다. Java 사례는 전부 passed이며 필수 실패·skip·누락 없음. 제공자 브라우저 probe는 Java IT의 일부이고 일반 브라우저44에 중복 합산하지 않는다.

소스311개는 개발 최종 inputs와 동일하고 실행 후 현재 파일과 해시 불일치0. inputs.json SHA256 `96D4C186B4D32473E793A052E5C5B75AEB3CCE81EA75094AB14DCC51A0DFBAB1`, 기준 HEAD `5393fef6b62c96fe7232b321ea4ebc2056f2bb7f` 위의 미커밋 P06-B다. 문서와 증거만 이후 갱신했다. 결과·inputs·제약 coverage·Java 사례·프런트/브라우저 coverage·명령 로그·before/after·review-verification은 `evidence/P06-B-review-final-*`에 선별 보존했다. 전체 원본은 위 실행 경로에 남아 있다.

### 제약 및 지적별 실행 근거

| 조건 | 실제 검사와 판정 |
| --- | --- |
| B01 입력 | GuestInputTest 두 메서드, guestHttpRequiresCsrfAndBrowserBindingWithoutGrantingAccountAccess: 이름/번호/PURCHASE 및 서버 우회 거부. 통과 |
| B02 세션·자격 | guestBindingReuseAndOneTimeConsumption, guestProviderStagesWindowsAndRestartFailClosed, 실제 guest 브라우저: 다른 브라우저/변조 자격 거부, 원문 토큰 비저장, 새 키/옛 키 거부·재인증, 쿠키/CSRF. 통과 |
| B03 결합·재사용 | guestBindingReuseAndOneTimeConsumption와 실제 브라우저의 다른 번호/용도/브라우저409, 정상 확인 및 재확인/소비 거부. 통과 |
| B04 만료·제한 | guestBusinessExpiryBeforeAndAfterProviderAndSessionExpiry, guestExpiryIsRecheckedAfterDatabaseLockWait, guestResendFailureAndSourceLimitsPersistAcrossSessions, guestProviderStagesWindowsAndRestartFailClosed:179/180초, 실제 잠금 대기, 외부 호출 중 만료, 60초/3회/5회/출처20회/899·900초. 통과 |
| B05 거래·경합 | guestConcurrentVerificationHasOneWinnerAndNoExternalTransaction, guestDatabaseFailureLateProviderAndLostResponseRecoverWithoutFalseSuccess:한 승자·외부 호출 거래 비활성·최종쓰기 DB 실패/허위소비 거부·새 시도 복구·consume rollback·늦은 confirm 거부. 통과 |
| B06 제공자·모드 | GuestLocalPolicyTest, 기존 PhoneProviderTest/PhoneProviderEmulatorIT, 실제 SDK가 연결된 guest 브라우저 및 DB 모드 CHECK/disabled503. 통과 |
| B07 화면·복구 | GuestStore 단위6개와 guest 브라우저 PC/모바일 각2개:중복 제출/늦은 SDK 무시/만료/실패 안내/SENT reload/실제 응답 유실→reload VERIFIED/재확인409/영구 저장소 비노출. 통과 |
| B08 경계 | 기존 ArchUnit 실제 소스·부정 fixture 및 전체 회귀. 통과 |
| B09 전체·보존 | 전체 하네스 결과, 소스311개 동일, 필수 coverage, 보존 비교 및 실패 manifest 재확인. 통과 |
| R-B01 | SENT 상태와 본인 번호를 no-store로 복원, 실제 발급 직후 reload 동일 attemptId→동일 코드 확인 성공. 해결 |
| R-B02 | 세션 속성 쓰기 제거·동일 HMAC 자격, DB 생성 경쟁 및 같은 쿠키 동시 HTTP create200→조회/발급 성공. 해결 |
| R-B03 | 위 DB 실패/rollback/만료/제한/재시작과 실제 응답 유실·SDK 실패 복구 assertion 및 필수 등록을 실행 확인. 해결 |

### 보존·실패 이력·한계

새 DB는 `d2_it_8bddef6d7ed144ac849f5ce7df6d868f`, 새 미디어는 `.local-tools/p06-b-review-final2-20261025/db/media`다. 기존 DB166개 유지 후167개, 기존 미디어1035개 유지 후1053개. 기존 개발/테스트/데모 덤프·컨테이너·알려진 리스너·미디어 해시 보존 통과. 과거 검증 DB는 존재 목록 비교이며 전체 행 덤프 전수 검증은 아니다. 에뮬레이터는 `.local-tools/phone-emulator-c6a181d1f36340e1822c93f4cdab5557`이고 전체 명령 정상 종료를 확인했다.

기존 실패 manifest2162개와 P06-B 실패 manifest405개를 실행 전후 재확인해 누락/해시 불일치0. P06-B manifest 원본도 `evidence/P06-B-failure-manifest.json`에 복사했다. 개발1의 V22 기대개수 실패 및 이전 P06-A/P05-D 실패는 원래 결과로 보존한다.

첫 독립 시도는 `.local-tools/phone-emulator-ec920ec90f5d4553a186a11f839f30c8`의 hub loopback 5417 기동 timeout으로 실패했다(외부 명령 exit1, 에뮬레이터 exit2). 본 하네스 inputs/DB 검사 전이며 요청한 p06-b-review-final-20261025 결과 디렉터리는 생성되지 않았다. 로그를 `evidence/P06-B-review-first-emulator-failure.txt`에 보존했다. 필요한 실행 권한으로 새 경로에서 동일 소스를 재실행하여 성공했지만 첫 실패의 정확한 원인을 확정하지 않았다. 테스트 삭제·필수 목록 축소·타임아웃 확대 없이 재실행했다.

실제 SMS·실명 확인·운영 인증·공개 데모 인증·다중 인스턴스·사용자 직접 확인은 미검증/범위 밖이다. 로컬 키/제공자 메모리 재시작 및 세션 ID 변경 시 재인증이 필요하다. 인증 확인 성공은 구매/조회/주문 소유권 완성이 아니며 조회 비밀번호/GuestBuyer(P06-C), 실제 주문 생성과 인증 소비 원자성(P08-C)은 제외한다. 기존 서버·DB·검증 DB·미디어·데모·실패 증거는 유지했고 P06-C는 진행하지 않았다.

## 최종 개발 실행 중 읽기 재검토 (2026-10-25)

`.local-tools/p06-b-dev-final-20261025` 실행 중 소스·검사·필수 목록과 작업 문서를 읽었다. **R-B01~03의 지적 경로는 현재 코드와 assertion상 보완됐다. 최종 개발 결과 및 독립 전체 실행 전이므로 실행상 해결/완료 판정은 대기한다.** 이번 읽기에서 추가 확정 결함은 발견하지 않았다.

- R-B01: 실제 `guest.spec.ts`가 SENT 직후 reload와 동일 attemptId 유지, 기존 코드로 SDK→Java 성공까지 검사한다. 서버 SENT 반환 및 GuestStore 복원과 연결된다.
- R-B02: HttpSession 속성 쓰기가 제거됐고 같은 sessionId의 HMAC 자격을 사용한다. 동일 쿠키의 HTTP create 두 건 200→GET 200→실제 발급 성공과 DB create 경쟁 검사가 추가됐다. 원래의 속성 쓰기 순서 경합 경로가 없어진 코드 확인과 함께 판단한다.
- R-B03: `guestProviderStagesWindowsAndRestartFailClosed`에서 issue/exchange 호출 중 180초 경계, 번호 발급/실패 창 899초 거부·900초 재개, 서비스 키 교체 후 새 자격/옛 자격 모두 기존 checkout 접근 거부 및 새 인증 성공, purpose/provider/mode DB CHECK 거부를 검사한다. 출처 제한 검사에도 899/900초 assertion이 있다.
- 실제 브라우저에서 다른 브라우저/번호/용도 exchange 409 후 정상 인증, 실제 Java 확인 200 응답을 route.fetch로 받은 뒤 브라우저에는 abort하여 실패 UI→reload VERIFIED 복구→재확인409를 검사한다. SDK 예외 후 verify 미전송·오류 세부 비노출·명시적 새 인증 성공의 Vitest도 추가됐다. 가짜 공급자의 DB 실패 주입 검사와 실제 SDK/HTTP 응답 유실 검사를 구분한다.
- 신규 DB 메서드, PC/모바일 응답 유실 시나리오, 프런트 복원/SDK 실패 검사가 각각 required-tests.json, required-browser.json, required-frontend.json에 등록됐다. 선언 확인이며 실행 gate 통과 판정이 아니다.
- checkout은 현재 서비스 HMAC과 입력 자격 일치를 검사한다. 로컬 단일 프로세스·재시작/세션 ID 변경 시 재인증·동일 checkout reset 계약이 작업 문서에 반영됐다. consume은 guest_attempt에도 consumed_at을 기록하도록 보완돼 reset으로 checkout의 소비 시각을 지워도 시도 이력에 남는다. 실제 주문 소비 계약은 여전히 P08-C 범위다.

개발2의 저장된 result.json은 status=passed/preservation=passed, Java268·브라우저42·프런트133으로 직접 읽어 확인했다. 이후 추가된 검사들의 통과를 이 이전 결과에 소급 포함하지 않는다. 최종 개발 하네스 종료·소스 동일성 확인 후 별도 새 DB·미디어 경로의 독립 전체 실행이 남아 있다. 이번에는 검토 문서만 수정했으며 기존 자원·실패 이력은 변경하지 않았고 P06-C는 진행하지 않았다.

## HMAC 자격 대안의 중간 정적 확인 (2026-10-25)

개발 측 수정 중 파일을 다시 읽었다. 실행 검증은 하지 않았다. 문서 실제 경로는 `D:\Workspace\ai-portfolio-practice\docs\work-items\P06-B-review.md`다. 이번 추가 전 파일은 56줄/11,666바이트이고 첫 구현 정적 검토 R-B01~03가 상단에 존재했다. 38줄의 사전 검토만 있는 상태가 아님을 직접 확인했다.

- R-B01: status가 attempt.state를 반환하고 SENT에만 본인 번호를 no-store 응답에 포함하며, GuestStore.load가 SENT와 번호를 메모리로 복원하는 코드로 변경됐다. 발급 직후 새로고침 복원 방향은 타당하다. 운영 범위로 확대하거나 번호를 영구 브라우저 저장소에 보관할 필요는 없다. 단위 복원 검사는 추가됐으나 실제 전체 실행 결과 확인 전 해결 판정은 보류한다.
- R-B02: 현재 Controller에는 HttpSession 속성 쓰기가 없고 자격은 singleton 서비스의 무작위 256비트 키로 HMAC(sessionId)에서 도출한다. 같은 프로세스·같은 sessionId에서는 동일 자격이므로 이전의 늦은 setAttribute 경합 원인은 제거됐다. DB upsert가 current_attempt를 비워 이전 시도 ID를 거부하는 구조도 확인했다. 읽은 대안에서 새 인증 우회 결함은 확정하지 않았다. 다만 로컬 단일 인스턴스 계약이며 키가 바뀌는 재시작/인스턴스 간 재개 보장은 없다. 이 제약과 실제 HTTP 세션 ID 변경 시 재인증 필요를 문서에 명시해야 한다.
- 변경된 create는 새 checkout 행을 매번 만드는 방식이 아니라 같은 행을 재설정하며 CONSUMED/consumed_at도 지운다. 현재 P06-B의 이전 attempt 재사용 차단과는 별개로, 이 구조를 P08의 주문 소비 이력 저장 계약으로 그대로 설명해서는 안 된다. 작업 문서의 기존 '새 세션은 이전 세션 폐기' 및 '서버 전용 난수 속성' 설명을 최종 HMAC/reset 계약에 맞춰 갱신해야 한다.
- R-B03: 새 `guestDatabaseFailureLateProviderAndLostResponseRecoverWithoutFalseSuccess`는 최종 VERIFIED 쓰기 CHECK 실패, 허위 소비 거부, 새 시도 복구, consume 외부 거래 rollback, confirm 도중 reset 뒤 늦은 결과 거부, EXCHANGED 재교환 거부 및 VERIFIED status 복구를 assertion으로 추가했다. 이전 공백의 일부가 보완됐다. fixture는 실제 코드/증명을 소비하는 공급자가 아니므로 실제 SDK 응답 유실/실패 흐름과 구분한다.
- 읽은 시점에는 같은 서비스의 create 경쟁 검사는 있으나 같은 쿠키의 동시 HTTP create, 새 서비스 키로 도출한 자격의 이전 checkout 접근 거부/새 생성 복구, issue/exchange 외부 호출 중 3분 경계, 번호·출처의 899/900초 창 경계 검사를 확인하지 못했다. 개발 중 스냅샷에 대한 관찰이므로 최종 파일과 필수 목록에서 다시 확인한다. 공급자가 null인 503 검사는 키 교체에 따른 거부를 대신하지 않는다.

개발1의 마이그레이션 기대 수 4→5 실패와 브라우저42/신규 guest 검사 통과는 개발 측 보고다. 이 세션의 독립 실행 결과가 아니며 첫 실패는 보존해야 한다. V22 추가에 따른 정확한 기대값 갱신은 검사 축소와 구분하되, 새 실행에서 실제 적용 버전과 DB 제약 검사까지 확인해야 한다. R-B01~03 최종 해결 및 전체 완료 판정은 아직 보류한다.

## 첫 구현 정적 검토 (2026-10-25)

개발 측의 `.local-tools/p06-b-dev1-20261025` 실행 중 요청에 따라 소스와 assertion만 읽었다. 아래는 실행 재현 결과가 아닌 정적 검토 결과다. 이 세션에서 하네스나 테스트를 실행하지 않았고 제품/검사 소스를 수정하지 않았다. **R-B01~03 보완과 독립 전체 실행 전까지 완료 판정 보류.**

| ID | 판정 | 근거·영향·필요한 검사 |
| --- | --- | --- |
| R-B01 | P2: 발급 후 새로고침 시 유효한 시도를 이어갈 수 없음 | `JdbcGuestPurchases.status` 57행은 시도 SENT 여부와 무관하게 checkout의 OPEN을 반환한다. `GuestStore.load` 47행은 VERIFIED만 복원하고, 번호도 13행의 메모리에만 있어 페이지 재생성 시 사라진다. 인증번호 요청 직후 새로고침하면 이름/번호 입력 화면으로 돌아가며 이미 발급된 코드 입력 화면을 복구하지 못한다. 다시 제출하면 `issue`가 먼저 새 구매 세션을 만들어 기존 시도를 폐기하고 같은 번호 발급은 60초 제한에 걸린다. `guest.spec.ts`는 인증 완료 후 reload만 검사하므로 이를 찾지 못한다. 서버에 결합된 시도를 기준으로 민감정보를 영구 저장하지 않고 재개하거나, 계약에 맞는 명시적 복구 흐름을 제공하고 발급 직후 reload→동일 유효 코드 확인 및 재발송 제한 유지 브라우저 assertion을 추가해야 한다. |
| R-B02 | P2: 동일 브라우저의 동시 구매 세션 생성에서 폐기된 자격이 최종 세션 속성에 남을 수 있음 | `GuestController.create` 25행의 `purchases.create`와 `session.setAttribute`는 원자적이지 않다. A가 DB 생성 후 속성 설정 전 멈춤→B가 같은 브라우저로 새 checkout을 생성하며 A를 REVOKED 처리→B 속성 설정→A가 늦게 자신의 token을 설정하는 순서가 가능하다. DB advisory lock은 `JdbcGuestPurchases.create` 거래 안에만 있어 HTTP 세션 속성 갱신까지 보호하지 않는다. 결과적으로 활성 checkout B와 세션 속성 A가 어긋나 이후 GET/발급이 409가 될 수 있다. 현재 동시 검사는 verify/consume만 다루고 같은 쿠키의 create 경합은 없다. 세션 저장 방식까지 고려해 생성·자격 선택이 어긋나지 않도록 수정하고, 같은 쿠키의 동시 HTTP 생성 후 최종 세션으로 GET/발급 가능 및 이전 자격 거부를 결정적 순서 제어로 검증해야 한다. |
| R-B03 | 필수 실패 복구·경계 assertion 공백 | `PostgresJdbcIT`의 guest 6개 검사는 정상 결합, verify/consume 경합, verify 전후/잠금 대기 만료, 일부 횟수 제한, CSRF를 다루지만 DB 오류를 주입하지 않는다. exchange/confirm 외부 성공 후 DB 갱신 실패·응답 유실 시 EXCHANGING/CONFIRMING/EXCHANGED에서 어떻게 복구하는지, 오래 걸린 issue/exchange 후 만료·새 시도로 교체된 뒤 늦은 외부 결과를 거부하는지에 대한 assertion이 없다. 공급자가 한 번 소비한 증명은 DB rollback만으로 복원되지 않으므로 단순 verify 경합 검사로 대신할 수 없다. 새 격리 DB에서 실패 주입 후 허위 VERIFIED/중복 소비가 없는 것과 명시된 새 시도 또는 재시도 복구 성공을 검사해야 한다. 번호/출처 15분 창 정확한 재개 경계와 프런트 전송/SDK 실패 후 복구도 실제 검사에 연결해야 한다. |

정적으로 확인한 보완 요소: GuestInput은 PURCHASE/이름/번호를 검증하고, DB에는 세션 자격의 SHA256과 브라우저 결합 해시를 저장한다. checkout 잠금 뒤 bound에서 시도·번호·용도·시간을 확인하며 외부 호출 전후 별도 거래로 상태를 바꾼다. 새로운 guest API의 CSRF 예외는 추가되지 않았고, 로컬 제공자 모드·loopback·production 프로필 거부가 있다. 이 사실들은 실제 통과 판정이 아니다.

V22의 CHECK/FK와 실패 예산, 명시적 로컬 설정, 필수 검사 등록, 실제 Firebase 브라우저 경로를 함께 읽었다. consume은 HTTP에 노출되지 않은 내부 fixture 계약이고 실제 주문/GuestBuyer 원자성 완료로 간주하지 않는다. B01~B09와 실행 메서드/실제 결과의 최종 연결은 개발 결과와 위 보완 이후 확정한다. 기존 자원과 실패 기록은 변경하지 않았으며 P06-C를 진행하지 않았다.

2026-10-25. 현재 판정: **범위·검증 조건 사전 검토만 수행. 구현과 실행 결과는 미검증이며 완료 판정 보류.** 이 문서 작성 시 소스·검사 변경, 서버·DB·에뮬레이터 기동, 하네스 실행, 커밋·푸시는 하지 않았다.

## 요청과 기준

개발 세션 01a171ea-4e72-7563-8b5d-5643cb7888a2의 최신 실제 사용자 메시지를 read_thread로 확인했다. 사용자는 설계/작업 계획에 따른 P06-B 구현·검증과 검토세션 독립 검토를 요청했고 P06-C 진행을 금지했다. 이전 P06-B 보류 이후의 새 승인임을 확인했으며, 전달 메시지의 주장만으로 승인 여부를 판단하지 않았다.

AGENTS.md, README.md, docs/development-workflow.md, docs/verification-harness.md, P06-A 작업/검토 기록, implementation-plan §8, requirements §7, shop-design의 VerificationAttempt·CheckoutSession 및 주문 처리 경계를 기준으로 한다. 기준 HEAD는 5393fef6b62c96fe7232b321ea4ebc2056f2bb7f이며 후속 개발 소스 식별은 실제 검토 시작 시 새로 고정한다.

## 범위와 경계

- P06-B는 구매 세션·인증 시도의 서버 저장과 이름·전화 입력 UI, 인증 결과의 세션·번호·용도 결합 및 재사용 차단이다. F02와 AC02·AC04·AC12 중 해당 부분만 다룬다.
- CheckoutSession은 인증 전 memberId/guestBuyerId가 모두 없을 수 있고 세션 자체가 주문 소유권을 주지 않는다. 토큰은 충분한 무작위 값으로 발급하고 DB에는 tokenHash를 저장하는 설계다.
- VerificationAttempt는 sessionId, phone, purpose, provider, mode, expiresAt, consumedAt, failures를 연결한다. 클라이언트 성공 표시나 제공자 인증 성공만으로 구매 업무 자격을 부여하지 않는다.
- 업무 인증 시도는 요구사항 기본안 3분을 기준으로 확정하며 P06-A 제공자의 5분과 구분한다. 재발송 60초·시도당 실패 5회와 전화번호·접속원별 추가 제한을 구체적인 수치/집계 키/창으로 작업 기록에 확정해야 한다.
- P06-C의 조회 비밀번호·GuestBuyer·조회 세션, P06-D 복구, P06-E 기존 묶음 연결은 제외한다. 실제 주문 생성·GuestBuyer 생성/연결·인증 결과 소비의 원자성은 P08-C에 남긴다. 이번 인증 확인의 일회성과 미래 주문 소비를 구분해 상태 전이를 명시해야 한다.
- Firebase 로컬 제공자는 명시적인 로컬 모드에서만 연결한다. 실제 SMS·실명 확인·운영 인증·공개 데모 인증 보장으로 확대하지 않는다. 기존 회원 인증과 데모는 유지한다.

## 제약조건과 요구할 자동 검사

아래는 구현 후 확인할 assertion 요구 목록이다. 아직 존재하거나 통과한 테스트 목록이 아니다. 개발 기록에는 실제 클래스/메서드·시나리오 이름과 결과 경로를 연결해야 한다.

| ID | 조건 | 자동 검사와 기대 결과 |
| --- | --- | --- |
| B01 | 입력·정규화 | JUnit으로 이름의 빈 값/길이/허용 범위와 전화번호 정규화 경계 확인. 프런트 우회 HTTP도 서버가 거부하고 구매자 정보와 배송받는 사람 정보를 혼동하지 않음 |
| B02 | 세션 소유·만료 | 새 PostgreSQL HTTP 통합 검사로 무작위 세션 발급, DB 원문 토큰 비저장, 누락/변조/다른 브라우저/만료 세션 거부. 임의 sessionId 지정으로 다른 세션 조회·변경 불가. 세션 만료 정책과 쿠키 속성·CSRF를 실제 응답/거부 assertion으로 검증 |
| B03 | 세션·번호·용도 결합 | JUnit 및 실제 DB/API로 각각 하나만 다른 요청, 다른 제공자/모드, 다른 시도 증명의 교차 사용을 거부. 거부 후 성공 상태나 소비 상태가 생기지 않음. 클라이언트 verified 플래그는 자격 아님 |
| B04 | 만료와 제한 | 3분 직전/정확한 경계·외부 제공자 호출 또는 잠금 대기 중 만료를 검사. 60초 재발송 경계, 5회 실패, 재발송/번호 표기/새 시도로 제한 우회 불가. 전화번호·접속원별 제한도 확정 정책에 맞게 별도 검증. 제공자의 5분 유효만으로 업무 3분 경계를 통과하지 못함 |
| B05 | 일회성·동시성·거래 | 독립 PostgreSQL 연결/트랜잭션으로 동일 증명/시도 동시 확인은 최대 한 번 상태 전이. 성공 후 재사용, 재발송 후 이전 시도, 번호 변경 후 이전 증명 거부. DB 실패·rollback·응답 유실에서 허위 성공/중복 소비가 없고 재시도 또는 새 시도 복구 계약이 명확함 |
| B06 | 제공자 실제 연결·실패 | 새 로컬 에뮬레이터와 실제 Java/브라우저 SDK 경로로 업무 API까지 연결. 제공자 장애·잘못된 코드/토큰·기동 모드 오류 시 서버 성공 상태 없음. 로컬 허용과 비로컬 거부를 모두 실행 검증 |
| B07 | UI 상태와 정보 보호 | Vitest로 이름/번호/코드 검증·중복 제출·로딩/실패/재시도·늦은 응답 무시. 실제 브라우저로 정상 흐름·거부·재발송·새로고침/세션 변경과 키보드 조작 확인. 모의 인증 표시, 원문 인증번호/토큰의 URL·영구 저장소 유출 방지 및 안전한 오류 안내를 assertion으로 확인 |
| B08 | 모듈 경계·기존 회귀 | ArchUnit 정상 클래스 및 부정 fixture, 기존 회원/장바구니/제공자 검사 유지. 새로운 모듈 연결은 공개 계약을 사용하고 내부 구현/기술 타입 누출을 거부 |
| B09 | 전체 검증·보존 | 신규 필수 Java/프런트/브라우저 검사 등록 후 새 증거 경로에서 루트 ./verify.ps1 -Offline. JUnit·ArchUnit·새 DB·프런트·브라우저 및 보존 검사 모두 통과, 실패/skip/누락 없음. 소스 해시와 제약별 assertion 및 실제 결과 연결 |

## 현재 미검증 및 후속 진입 조건

현재 B01~B09는 실행 미검증이다. 구현 결함으로 확정한 항목은 없으나 이것이 구현 통과를 뜻하지 않는다. 세션 수명·이름 규칙·접속원 제한·인증/소비 상태·실패 후 복구 계약 등 세부 결정과 검사 연결이 필요하다. P06-A 전체 통과는 P06-B DB/업무/API/UI 검증을 대신하지 않는다.

개발 전체 하네스 결과, 제약별 실제 검사 매핑, 소스 동결 식별이 전달되면 독립 소스/assertion 검토 후 별도 새 경로에서 ./verify.ps1 -Offline을 직접 실행한다. 실행하지 못한 검사는 원인과 차단 상태로 기록한다. 테스트 삭제·필수 목록 축소·타임아웃 확대만으로 실패를 감추지 않는다.

기존 서버·DB·과거 검증 DB·미디어·데모·실패 기록을 유지하며 독립 실행에도 새 DB·미디어만 사용한다. 과거 검증 DB 존재 목록 비교와 전체 행 비교의 한계를 구분한다. P06-A 첫 에뮬레이터 기동 및 P05-D 비재현 실패의 원인 미확정 기록을 새 성공으로 덮지 않는다. 이번 사전 검토는 보존 하네스를 실행하지 않았으므로 새 보존 검사 통과를 주장하지 않는다. P06-C는 진행하지 않는다.
