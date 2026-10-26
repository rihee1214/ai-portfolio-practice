# P06-E 독립 검토

최종 판정(2026-10-26): 개발과 동일한 동결 소스의 독립 전체 검증 및 보존 검사 통과. R-E01~04 해결 확인, 로컬 P06-E 범위의 필수 잔여 차단 없음. 아래 미실행·미해결 표시는 당시 이력이며 최종 근거는 마지막 절을 따른다. 커밋·푸시·배포·P07-A는 진행하지 않았다.

## 사전 계약 검토 — 2026-10-26

상태: 사전 검토. 구현·자동 검사 통과 또는 완료 판정이 아니다. 개발 세션 실제 사용자 메시지(turn `01a177f6-e0ac-74d2-bbf1-2129a060e73a`)에서 P06-E 구현·새 격리 검사·독립 검토와 P07-A 미진행 지시를 확인했다. 기준 HEAD는 `a4f8068ecab16508ca21a7942c3e873181ee64cc`다.

README, development-workflow, verification-harness, requirements §7, implementation-plan §8, P06-D 완료 기록과 P06-E 초안을 읽고 기존 구매 인증·조회 자격 계약과 대조했다. 기존 조회 인증과 현재 PURCHASE 인증을 함께 요구하고 NEW를 명시적으로 분리하는 범위는 설계에 부합한다. P06-E 초안 상단의 '문서 작성 요청/구현 미승인'은 이전 작성 당시 이력이며 이번 실제 사용자 구현 요청과 구분해야 한다.

이 세션은 이 검토 문서만 소유한다. 제품·검사 소스와 개발 작업 기록은 개발 세션 소유다. 현재 하네스 실행 없음. 개발 전체 통과·소스 동결 이후 별도 새 경로에서 독립 전체 검증한다. P07-A·커밋·배포·푸시는 범위 밖이다.

## 구현 전 고정할 계약

1. **기존 묶음 연결의 세 가지 조건.** 현재 유효한 조회 세션이 특정 buyer를 가리키고, 현재 browser/checkout의 PURCHASE attempt가 인증 완료이며, 정규화한 전화번호가 buyer의 등록 번호와 같아야 한다. 준비와 소비 모두 검사한다. 클라이언트 buyer ID나 '로그인됨' 플래그는 권한 근거가 아니다. 조회 세션만, 전화 인증만, RECOVERY attempt/grant만으로는 연결할 수 없다.
2. **선택과 자격의 결합.** 준비 당시 buyer/version/조회 자격과 현재 구매 attempt를 서버에 결합한다. 준비 후 같은 브라우저에서 다른 buyer로 로그인했을 때 과거 준비 대상이 새 쿠키의 buyer로 조용히 바뀌면 안 된다. 같은 전화의 두 buyer를 사용해 검증한다. 같은 buyer의 세션 교체를 허용할지 기존 준비를 무효화할지 명시하고 검사한다. 만료/폐기된 자격으로 준비된 EXISTING을 NEW로 자동 전환하지 않는다.
3. **NEW/EXISTING 전환과 비밀번호 준비의 원자성.** NEW는 조회 쿠키가 있어도 새 buyer다. EXISTING은 준비된 새 비밀번호를 사용하지 않고 새 입력도 거부한다. NEW password 해시를 거래 밖에서 계산하는 동안 EXISTING으로 바뀌면 오래된 해시 결과가 모드나 준비 상태를 되돌릴 수 없어야 한다. EXISTING→NEW 시 기존 대상 연결이 제거되고 NEW의 유효한 비밀번호 준비가 다시 요구돼야 한다. 모드 전환 재시도의 멱등성과 오래된 두 탭 요청의 충돌 처리도 명시한다.
4. **기존 자격 불변.** 연결 전후 기존 이름·등록 번호·password hash/version·recovery hash가 동일해야 한다. 현재 구매의 다른 이름이나 새 비밀번호가 기존 값을 덮어쓰지 않는다. 다른 번호는 명시적 NEW 흐름으로만 진행하며 과거 주문 참조는 이동시키지 않는다. 코드 재발급만으로 조회 세션을 폐기하지 않는 P06-D 정책도 유지한다.
5. **거래와 잠금.** 기존 코드의 buyer→session과 구매 checkout→attempt 잠금을 함께 검토해 전 경로에서 순서를 통일한다. 기존 prepare/createBuyer뿐 아니라 재인증·새 attempt·모드 변경·reset·logout·login 회전까지 포함한다. 모든 필요한 잠금을 얻은 뒤 현재 시각/상태/번호/version을 다시 확인한다. 늦은 password 해시는 먼저 읽은 상태만 믿지 않는다.
6. **소비와 응답 유실.** buyer 선택/NEW 생성, 구매 인증 소비, 주문 참조 등록을 호출자의 동일 거래에 넣는다. 후반 등록 실패 시 새 buyer 및 소비 상태가 되돌아가고 재시도할 수 있어야 한다. 소비 성공 후 재요청은 중복 생성/연결 없이 명시적으로 거부하거나 동일 결과를 제공하는 정책을 정한다. 준비 상태 재조회는 주문 완료 증명이 아니다. 제품 주문 생성 endpoint와 실제 주문 성공 UI는 만들지 않는다.
7. **상태 복원도 권한 검사.** 새로고침/응답 유실 뒤 상태 응답은 서버의 현재 모드·준비 여부를 반영한다. 폐기된 조회 세션에 대해 이전 EXISTING 준비를 유효하다고 표시하지 않는다. 비밀 원문·내부 buyer 식별자·다른 묶음 주문은 노출하지 않는다. 구매 세션 재생성/번호 변경/새 attempt 뒤 이전 준비가 남지 않아야 한다.

## 제약 → 자동 검사와 기대 결과

아래 이름은 개발 초안의 예정 이름이며, 최종 단계에는 실제 메서드·assertion·실행 경로로 연결한다. 모든 실행 결과는 현재 미실행이다.

| ID | 검사 계약 | 현재 결과 |
| --- | --- | --- |
| E01 | JUnit GuestLinkPolicyTest 및 새 PostgreSQL: NEW/EXISTING 명시값·잘못된 모드 거부, 정규화 번호 비교, 유효 조회+현재 PURCHASE+같은 번호만 연결, 어느 한 조건이 없으면 거부 | 미실행 |
| E02 | guestNewPurchaseNeverMergesByPhone: 같은 이름/전화/조회 쿠키 존재에도 NEW buyer ID는 다르고 새 주문만 조회 가능. 원래 묶음의 주문/자격 불변 | 미실행 |
| E03 | guestRepurchaseCannotOverwriteCredentials: 이름/번호/hash/version/recovery hash 전후 정확 비교, buyer ID/새 password 조작 거부, 다른 번호 연결 거부 및 명시적 NEW 성공 | 미실행 |
| E04 | guestLinkPreparationIsBoundToCurrentAttempt: 다른 browser/attempt/checkout, RECOVERY ID/grant, 만료/소비된 PURCHASE 거부. 번호·새 시도·모드 변경으로 준비 무효화. 다른 buyer 재로그인·두 탭 선택 충돌·늦은 password 해시가 잘못된 모드/대상을 복원하지 않음 | 미실행 |
| E05 | guestRepurchaseRechecksAccessAfterLockWait: 실제 DB 잠금 대기를 latch/pg_stat_activity로 확인한 뒤 조회 또는 구매 자격을 정각 만료시키면 거부. logout/reset/version 변경 후 준비/소비 거부. 코드만 재발급한 경우 D의 세션 유지 정책대로 동작. reset/login 회전과 연결 경합 최종 권한 확인 | 미실행 |
| E06 | guestRepurchaseConsumptionIsAtomic: 주문 참조 등록 등 거래 후반 실패 주입 뒤 소비·buyer 생성·연결 상태 복원과 재시도 성공. 같은 인증 동시 소비 한 승자, 서로 다른 목적지로 이동/중복 연결 없음. 기존 order reference 충돌 시 이전 소유권 불변 | 미실행 |
| E07 | HTTP CSRF403·성공/실패 no-store·disabled503·다른 묶음 비노출·fixture 비활성, 기존 쿠키/Secure 정책과 ArchUnit 회귀. 잘못된 준비 상태도 GET 응답에서 권한을 우회하지 않음 | 미실행 |
| E08 | Vitest 중복/늦은 응답/모드 전환/비밀 삭제/서버 상태 복원. PC·모바일 실제 SDK: EXISTING/NEW/다른 번호, 만료·두 탭 충돌, 준비 커밋 후 응답 유실→재조회. fixture 소비 응답 유실 재시도도 중복 없음. 실제 주문 완료로 오인하지 않는 화면 문구 assertion | 미실행 |
| E09 | 기존 필수 목록 유지+신규 등록, 개발 통과 후 동결 소스의 독립 새 경로 ./verify.ps1 -Offline. 종료0·status/preservation passed·필수 누락/실패/skip0·소스 해시 일치·기존 자원/실패 증거 보존 | 미실행 |

기존 C/D 검사를 재사용하는 제약은 정확한 기존 메서드와 assertion을 연결한다. 모든 조합을 새 테스트 이름 하나씩 만들 필요는 없지만 총개수나 화면 확인으로 위 조건을 대체하지 않는다. 경합 최종 상태 assertion과 실제 잠금 대기 관찰은 서로 구분한다.

## 실행·기록 경계

기존 서버·DB·과거 검증 DB·미디어·데모·실패 기록을 유지한다. 새 검사 DB/미디어만 사용하며 실패 실행도 삭제하지 않는다. P06-D 커밋 전 DB 서비스 중단 실패를 포함해 최신 실패 manifest를 기준으로 해시 보존을 확인하고, 과거 원인 미확정 실패를 새 성공으로 소급 변경하지 않는다.

최종 완료 기록에는 HEAD+inputs 해시, 개발/독립 실행 경로, 실제 제약별 결과, before/after 보존 비교, 실패 이력·미검증 범위를 남긴다. 실제 주문·재고·쿠폰·결제와 통합된 거래 원자성은 P08-C에 남는다. 지금은 사전 계약 검토만 완료했으며 P06-E 구현 완료 판정은 보류한다.

## 개발1 소스 동결 중 정적 검토

대상: JdbcGuestAccess/GuestAccessController, V25, GuestStore/GuestPage, 신규 DB9개와 guest-repurchase 브라우저 시나리오. 개발1 실행과 병행한 읽기 전용 검토이며 독립 하네스는 실행하지 않았다. 아래는 정적 발견이며 개발1 실제 결과와 구분한다.

### R-E01 / P2 / 미해결 — 내부 prepare의 revision 생략이 해시 중 선택 변경을 놓침

`JdbcGuestAccess.prepare(browser,attempt,password)`는 null revision으로 위임하고 `checkRevision`은 null이면 비교를 생략한다. 첫 거래에서 NEW를 확인한 뒤 해시 계산 중 NEW→EXISTING→NEW로 전환되면 두 번째 거래도 NEW만 확인하여 오래된 해시를 저장한다. 새 선택 뒤 반드시 다시 준비해야 한다는 계약과 다르다. 제품 HTTP는 누락 revision을0으로 바꿔 이 경로를 방어하지만 내부 계약은 여전히 취약하다.

개발 세션이 이미 관심점으로 알려준 `guestLinkRevisionAndSlowPasswordCannotRestoreOldChoice`가 정확히 이 null 경로를 검사한다. 아직 실행 결과는 확인하지 않았다. 명시 revision의 처음 대조뿐 아니라 첫 거래에서 실제 revision을 캡처하여 두 번째 거래에서 반드시 동일함을 확인하거나, 내부 호출도 revision을 명시하도록 계약을 고정해야 한다. 무조건0으로 바꾸어 모드 변경 후 정상 NEW 준비까지 막는 해결은 피한다. 정상 변경 후 현재 revision 준비 성공, 오래된 명시 revision 거부, 내부 생략 호출의 해시 중 변경 거부를 함께 확인한다.

### R-E02 / P2 / 미해결 — 상태 재조회 실패 시 서버와 다른 NEW 선택을 표시

`GuestStore.load`는 checkout 응답을 `accept`한 뒤 link 상태를 요청한다. `accept`는 linkMode=NEW/linkRevision=0으로 초기화하고, `refreshLink` 실패는 '첫 방문에는 세션 없음이 정상'이라는 catch에서 모두 숨긴다. 서버가 EXISTING을 선택한 상태에서 link GET만 실패하면 기존 선택이 NEW로 표시되고 새 비밀번호 입력이 노출된다. 사용자가 명시적으로 상태 확인을 눌렀는데 오류도 나타나지 않는다. OPEN/만료 응답이나 checkout GET 실패 때에도 기존 ready 표시를 확실히 무효화하지 않아 오래된 준비 완료 화면이 남을 수 있다.

상태 복원을 하나의 확인 단위로 처리하고 링크 상태를 확인하지 못하면 준비 여부를 거짓으로 표시하며 명시적인 오류/재시도 경로를 제공한다. 실패를 NEW 선택으로 해석하지 않는다. 기존 선택을 유지하되 미확인으로 표시하거나 UNKNOWN 상태를 사용하는 등 표현은 구현에서 정한다. 첫 방문과 명시적 재조회 실패를 구분한다.

필수 검사: 서버 EXISTING 상태에서 link GET만 네트워크 실패/401/409 → NEW로 자동 전환하거나 비밀번호 준비 완료를 표시하지 않음 → 재시도 성공 시 원래 EXISTING 복원. 다른 탭의 checkout reset 후 OPEN 응답, 만료/소비 응답에서도 이전 ready가 사라지는지 단위와 브라우저 assertion을 추가한다.

### R-E03 / P2 / 미해결 — 신규 거래·권한 조합 검사 일부 누락

현재 DB 검사는 EXISTING 참조 충돌 rollback/같은 인증 한 승자, lookup 만료의 실제 buyer 잠금 대기, 비밀번호 변경과 연결 경합, 자격 불변·명시 NEW를 포함한다. 다음 사전 계약은 아직 구체 assertion이 필요하다.

- NEW buyer 생성 뒤 참조 등록 실패 시 새 buyer 수와 소비 상태 모두 복원되고 같은 인증으로 재시도 성공. 현재 `guestRepurchaseConsumptionIsAtomic`는 EXISTING만 검사한다.
- 같은 전화의 다른 buyer로 조회 로그인 교체 후 이전 준비를 재사용하지 못함. 현재 검사는 같은 buyer의 새 토큰으로 재선택해야 함을 확인하지만 다른 buyer 대체 조합은 없다.
- 구매 인증이 buyer/session 잠금 대기 중 정각 만료되는 경우 소비/준비 거부. 현재 잠금 검사에서는 조회 세션만2초 만료로 바꾼다.
- RECOVERY attempt/grant를 PURCHASE 연결에 넣는 직접 거부 검사와 기존 모듈 회귀의 정확한 연결.
- fixture 소비 커밋 후 응답 유실·재시도에서 참조와 NEW buyer 중복 없음. 현재 브라우저는 준비 선택의 응답 유실과 정상 소비 후409 재호출을 검사하며 소비 응답 유실은 직접 주입하지 않는다.

기존 테스트가 이미 위 조건을 입증하면 실제 메서드/해당 assertion을 연결한다. 새 이름을 늘리는 것 자체가 목적은 아니다. 제품 주문 endpoint 추가나 실제 결제까지 확장하지 않는다.

### 정적으로 확인한 구조

조회 토큰 해시에 연결해 같은 buyer의 세션 교체도 재선택을 요구한다. EXISTING은 authorize 및 번호 비교 후 PURCHASE consume을 다시 검사하고, NEW/EXISTING 전환은 비밀번호 해시를 비우며 revision을 증가시킨다. V25는 EXISTING에 세션 결합이 있고 새 password hash가 없다는 조건을 강제한다. attachOrder 거래는 consume/registerOrder를 함께 감싸고 D 경로는 checkout을 사용하지 않으므로 현재 읽은 코드에서 직접적인 checkout 역순 잠금은 발견하지 않았다. 실제 경합·원자성 통과 판정은 실행 결과와 함께 확정한다.

현재 R-E01~03 미해결이며 완료 판정은 보류한다. 이 세션은 검토 문서만 수정했고 제품/검사 소스·기존 자원 변경이나 하네스 실행을 하지 않았다.

## 개발2 소스 동결 중 후속 정적 검토

R-E01~03의 수정과 추가 assertion을 대조했다. 개발2 하네스를 함께 실행하지 않았고 이 문서만 수정했다. 현재 추가 확정 결함은 발견하지 않았다. 아래는 정적 확인이며 최종 실행 통과 판정은 아니다.

- **R-E01 수정 확인:** prepare 첫 거래가 실제 link_revision을 `preparedRevision`으로 반환하고 두 번째 거래에서 반드시 대조한다. 내부 null 호출도 해시 중 전환을 놓치지 않으며 명시 revision의 초기 검사도 유지된다. 개발1 `db/maven.txt:1165` 부근에서 `guestLinkRevisionAndSlowPasswordCannotRestoreOldChoice`의 'Expected GuestRejected ... but nothing was thrown' 재현을 직접 확인했다. 개발1 result는 failed/preservation passed이며 원본을 유지한다.
- **R-E02 수정 확인:** load 시작에 ready 두 값을 false로 만들고 linkKnown=false로 표시한다. link 조회 실패 시 이전 모드를 유지하되 미확인·오류를 표시하며, OPEN/EXPIRED/CONSUMED는 이전 준비 상태를 제거한다. selectLink도 요청 시작에 linkKnown=false로 만들고 정상 응답에서만 true로 복원한다. 화면은 미확인 상태에서 새 비밀번호 폼을 숨기고 preparePassword도 linkKnown을 요구한다. 신규 단위2개는 link GET409 뒤 상태 유지/재시도, OPEN 및 checkout401/EXPIRED/CONSUMED 후 ready 제거를 검사한다. 브라우저에는 link GET 네트워크 실패 뒤 기존 준비 완료·새 비밀번호 폼 부재 및 정상 재조회 복원이 추가됐다.
- **R-E03 검사 보완 확인:** `guestNewLinkFailureRollsBackBuyerAndConsumption`은 NEW 생성 후 참조 충돌 때 buyer 수/VERIFIED 복원, 재시도1회 생성, 추가 소비 거부를 검사한다. `guestLinkRejectsOtherBuyerAndRecoveryProofs`는 같은 전화의 다른 buyer로 로그인 후 이전 준비 거부, RECOVERY attempt/grant 거부, 명시 재선택 후 다른 buyer에만 연결됨을 검사한다. `guestRepurchaseRechecksPurchaseAfterLockWait`는 실제 buyer 잠금 대기 중 PURCHASE attempt를 정각 만료시켜409와 consumed_at NULL·새 참조0을 확인한다.

EXISTING/NEW 브라우저 흐름은 테스트 fixture의 `loseResponse`로 거래 커밋 후503을 반환하고, 같은 attempt 재요청409 및 후속 조회의 참조 개수를 확인한다. 이는 서버 커밋 후 성공 본문을 받지 못하는 상태의 모의 검사이며 실제 네트워크 패킷 유실과는 구분한다. 준비 POST/상태 GET 실패는 별도의 route.abort 검사다. fixture 기능과 예외 매핑은 테스트 컨트롤러에 한정되며 제품 주문 생성 endpoint로 간주하지 않는다.

현재 상태: R-E01~03 수정 및 검사 연결 확인, 개발2 전체 결과와 동결 소스의 독립 전체 실행 대기. 기존 실패 이력과 사전/초기 검토 내용은 당시 기록으로 유지한다. P06-E 완료 판정은 아직 보류한다.

## 개발3 이후 로그인 출처 격리 정적 검토

개발3 result.json의 failed/preservation passed를 직접 확인했다. 개발 세션은 브라우저56통과/모바일2실패 및 새 DB의 공유 source 로그인 할당량20 도달을 보고했다. 이 절에서는 실패 DB를 직접 조회하지 않았으므로 횟수의 직접 확인과 개발 보고를 구분한다.

`guest-repurchase.spec.ts`의 E 로그인만 별도 loopback source `127.0.0.3`에서 실제 Java 서버로 전달하는 테스트 격리 방향은 허용 범위다. 기존 quota 설정/타임아웃을 변경하지 않으며 실제 요청 body를 Java로 보내 상태·헤더·본문을 그대로 브라우저에 전달한다. 다만 E 로그인은 Vite 프록시 경로를 통과하지 않으므로 이를 원래 프록시까지 포함한 E2E 근거라고 표시하지 않는다. 기존 직접 브라우저/프록시 및 제한 회귀는 유지해야 한다.

### R-E04 / P2 / 미해결 — 중계 요청에서 쿠키를 보존하지 않는 headers() 사용

위치: `frontend/auth-e2e/guest-repurchase.spec.ts`, isolateLoginSource의 httpRequest headers.

현재 `route.request().headers()`를 복사한다. 설치된 `frontend/node_modules/playwright-core/types/types.d.ts:22656`의 API 설명은 이 메서드가 cookie를 포함한 보안 관련 헤더를 반환하지 않으며 `request.allHeaders()`를 사용해야 한다고 명시한다. 따라서 주석과 보고의 '쿠키·CSRF 그대로 보존'은 현재 코드로 보장되지 않는다. SESSION 쿠키 누락 시 CSRF token이 다른 세션에 결합되어403이 날 수 있고, GUEST_ACCESS가 누락되면 이전 조회 세션 회전 조건도 달라진다.

수정 조건: 비동기 `await route.request().allHeaders()`로 원래 전체 헤더를 확보한 뒤 필요한 Host만 목적지에 맞게 바꾼다. 원래 body와 CSRF를 유지하고 인증 쿠키를 새로 만들거나 위조하지 않는다. 실제 응답 Set-Cookie 전달 후 브라우저의 인증 조회 및 기존 자격 회전 동작을 assertion한다. 민감 헤더 원문은 로그에 남기지 않는다. 테스트 격리 구현을 수정한 뒤 새 실행에서 상태200·후속 실제 조회를 확인해야 하며, 이 지적을 완료 전에 닫는다.

이 세션은 여전히 하네스를 실행하지 않았고 검토 문서만 갱신했다. 독립 전체 실행은 개발 최종 통과·동결 후 별도 요청을 기다린다.

## R-E04 후속 정적 확인 — 개발 최종 실행 중

`isolateLoginSource`가 `await route.request().allHeaders()`를 사용하도록 수정됐다. cookie/x-csrf-token 존재 여부를 boolean으로 검사하고 전체 헤더 중 Host만 목적지에 맞게 바꾼다. body는 원래 요청에서 읽고 실제 Java 상태·헤더·본문으로 fulfill한다. 쿠키 원문을 assertion 실패 메시지에 넣지 않도록 값의 동일 여부를 boolean으로 비교한다.

각 historical 로그인 뒤 실제 브라우저 fetch 재로그인200, GUEST_ACCESS 교체, 이전 쿠키 조회401, 현재 쿠키 조회200 assertion이 추가됐다. 따라서 헤더 전달과 실제 세션 회전까지 검사하도록 연결된 것을 확인했다. R-E04 수정은 정적으로 확인했으며 실행 판정은 이 소스를 사용하는 개발 최종/독립 전체 결과를 따른다.

개발4 result 원본에서 status/preservation passed, Java306·브라우저58·프런트148을 확인했다. 이 실행은 allHeaders 및 쿠키 회전 assertion 추가 전이며 최종 소스의 증거로 소급하지 않는다. 개발4에서 실패가 재현되지 않았다는 사실과 Playwright API 계약에 따른 보완은 구분해 남긴다. 추가 확정 정적 결함은 없고, 현재 검토 문서만 갱신했다.

## 독립 전체 검증 최종 결과 — 2026-10-26

개발 최종 실행 종료·소스 동결 후 루트에서 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p06-e-review-final-20261026`를 별도로 실행했다.

- 실행: 21:05:08.7527919~21:09:17.3663092 KST. 프로세스 종료0, result status=passed, preservation=passed.
- Java306 전부 passed, 브라우저58, 프런트 단위148, Node72 통과. 필수 coverage 전부 passed, 누락·실패·skip 없음. JUnit·ArchUnit·Checkstyle·새 PostgreSQL·실제 SDK·브라우저·타입/lint/build·하네스 gate 포함.
- 기준 HEAD `a4f8068ecab16508ca21a7942c3e873181ee64cc` 위 미커밋 P06-E 소스337개. 개발 최종 inputs와 경로/해시 차이0, 실행 후 현재 소스 불일치0.
- inputs.json SHA256 `5624AF038FBFDE8F09E198F19F6926260C466A2DEDEEA8E31F499F5581736853`.
- 새 DB `d2_it_de2d1fadf5634a638c017b7549876e20`, 새 미디어 `.local-tools/p06-e-review-final-20261026/db/media`, 에뮬레이터 `.local-tools/phone-emulator-f0d8e96be0b54ee8852837289fd3c34b`.
- 원본은 실행 경로에 보존. 요약/inputs/coverage/Java cases/보존/명령 로그/SDK probe/브라우저 보고서 등19개 사본은 `docs/work-items/evidence/P06-E-review-final-*`. 별도 소스·실패 해시 대조는 `review-verification.json`과 그 사본에 기록했다.

### 제약별 실제 실행 결과

DB 메서드는 PostgresJdbcIT이며 상세 assertion은 앞의 검토와 작업 문서 매핑을 따른다. 아래 메서드 및 관련 기존 회귀는 이번 java-cases/coverage에서 passed다.

| 제약 | 실행 근거 | 결과 |
| --- | --- | --- |
| E01 두 자격·번호·명시 모드 | GuestLinkPolicyTest, guestRepurchaseRequiresBothCredentials | 통과 |
| E02 NEW 분리 | guestNewPurchaseNeverMergesByPhone, PC/모바일 동일 번호 NEW 로그인 후 새 참조만 조회·이전 참조404 | 통과 |
| E03 기존 자격 불변 | guestRepurchaseCannotOverwriteCredentials의 buyer 전후 전체 비교·새 비밀번호 거부, 다른 번호 브라우저 흐름 | 통과 |
| E04 현재 시도·revision·자격 결합 | guestLinkPreparationIsBoundToCurrentAttempt, guestLinkRevisionAndSlowPasswordCannotRestoreOldChoice, guestLinkRejectsOtherBuyerAndRecoveryProofs | 통과 |
| E05 시간·잠금·폐기 | guestRepurchaseRechecksAccessAfterLockWait, guestRepurchaseRechecksPurchaseAfterLockWait, guestRepurchaseRacesPasswordChangeWithoutDeadlock 및 기존 C/D 회귀 | 통과 |
| E06 거래·중복 방지 | guestRepurchaseConsumptionIsAtomic, guestNewLinkFailureRollsBackBuyerAndConsumption, EXISTING/NEW fixture 커밋 후503→같은 attempt409→조회 참조 개수 | 통과 |
| E07 HTTP·모듈·비노출 | guestLinkHttpCsrfAndDisabledBoundaries, 기존 cookie/Secure/fixture 비활성·ArchUnit 회귀, 프런트/브라우저 비밀 비노출 및 실제 쿠키 회전 | 통과 |
| E08 UI·응답 유실·충돌 | GuestLinkStore 단위5개, guest-repurchase.spec.ts 3시나리오×PC/모바일, 기존 GuestStore 회귀. POST응답 유실·GET실패·두 탭 충돌·준비 무효화·명시 재조회 | 통과 |
| E09 하네스·소스·보존 | result, Java/frontend/browser coverage, before/after, review-verification | 통과 |

R-E01은 실제 revision 캡처 및 해시 중 전환 거부로 해결, R-E02는 linkKnown·준비 무효화·GET 실패 복원 검사로 해결, R-E03은 NEW rollback/다른 buyer·RECOVERY/구매 만료/소비 결과 유실 검사로 해결했다. R-E04는 allHeaders 계약 준수 및 실제 재로그인200·쿠키 교체·이전 쿠키401/현재200 assertion으로 해결을 확인했다. 개발 최종과 독립 실행이 동일 소스로 통과하여 필수 잔여 차단 없음.

### 보존·실패 이력

하네스 보존 passed. DB 목록184→185는 이번 새 DB 추가이며 이전 목록 유지. 알려진 미디어1342→1360, 기존 파일 해시 유지. 개발/데모 DB 정규화 내용, 알려진 서버 포트/PID, 컨테이너 ID, 과거 검증 DB 존재 목록을 비교했다. 과거 검증 DB 모든 행이나 프로젝트 밖 전체 파일의 전수 검사는 아니다.

실행 전후 A2162+B405+C791+D882+D커밋5+E1375=5,620개 실패 증거 해시 불일치0. E 개발1의 revision 결함 및 초기 실패, R-E02 단위 재현, 개발2 fixture boolean 누락400, 개발3 공유 source 로그인 할당량 실패를 원본/manifest와 함께 보존한다. 개발4 성공은 R-E04 최종 보완 전 이력으로 유지한다. A/B 에뮬레이터 시작 실패·C 시간초과 등 원인 미확정 이력을 이번 성공으로 소급 해결하지 않는다.

### 완료 범위와 한계

로컬 전화 에뮬레이터·가상 주문 참조의 기존 묶음 연결과 NEW 분리 계약을 완료했다. 실제 주문·재고/쿠폰·결제 거래 원자성은 P08-C 범위이며 실제 SMS·운영 부하·실제 기기/사용자 직접 확인은 미검증이다.

E 로그인 브라우저 검사는 별도 loopback 출처에서 실제 Java로 전달하므로 서버 인증/쿠키/CSRF는 검증하지만 그 요청의 Vite 프록시 통과를 입증하지 않는다. 기존 프록시 회귀는 유지했다. 소비 응답 유실은 테스트 fixture의 커밋 후503 모의 검사이며 실제 네트워크 패킷 유실과 구분한다. 준비 요청/상태 조회 유실은 route.abort 검사다. 공통 시작 latch 경합과 pg_stat_activity로 관찰한 실제 잠금 대기는 앞의 검사 설명대로 구분한다.

이 세션은 제품/검사 소스를 변경하지 않았으며 독립 하네스와 검토 문서·증거 사본만 작성했다. 기존 자원과 실패 기록을 보존했고 P07-A·커밋·푸시·배포는 진행하지 않았다.
