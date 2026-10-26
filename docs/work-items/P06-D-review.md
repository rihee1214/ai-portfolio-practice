# P06-D 독립 검토

최종 판정(2026-10-26): 동결 소스의 독립 전체 하네스와 보존 검사 통과. R-D01~03 해결 확인, P06-D 로컬 가상 주문·전화 에뮬레이터 범위의 필수 잔여 차단 없음. 아래 사전/중간의 미해결·미실행 표시는 당시 이력이며 최종 근거는 문서 마지막 절을 따른다. 커밋·푸시·배포·P06-E는 진행하지 않았다.

## 사전 계약 검토 — 2026-10-26

상태: 사전 검토만 수행. 구현 완료·검사 통과 판정이 아니다. 개발 세션의 실제 사용자 메시지 `P06-D를 진행해줘`(turn 01a17708-14e3-7772-bc76-423c63e6cc87)를 확인했다. 기준 HEAD는 `6eef13dbbfd4b6dee556d42578b229113c25f858`. 개발 중 변경이 존재하며 이 검토에서는 제품 소스·검사를 수정하지 않았다.

README, 개발 흐름, 검증 하네스, requirements §7, shop-design의 비회원 데이터·복구 정책, implementation-plan P06-D, P06-C 완료 기록 및 P06-D 초안을 대조했다. 범위는 기존 설계에 부합한다. 최초 복구 코드, 인증된 비밀번호 변경·재발급, 주문번호·등록 전화·복구 코드·별도 전화 재인증, 5분 일회성 재설정 권한, 코드 회전·전체 조회 세션 폐기를 검증한다. 실제 주문은 가상 fixture 및 내부 계약까지만 포함한다. P06-E·실제 SMS·배포·푸시·커밋은 포함하지 않는다.

## 구현 시 반드시 닫을 경계

아래는 아직 구현 결함 판정이 아니라 초안의 검증 조건을 구체화한 것이다. 구체 테스트 메서드와 실제 결과가 연결되기 전에는 미검증으로 남긴다.

1. **RECOVERY 증명의 독립성.** 테이블/API/purpose 분리만으로 제공자 토큰 재사용 차단을 입증할 수 없다. `PhoneVerifications.Proof`는 phone/authenticatedAt만 반환하고 purpose 결합은 호출자 책임이다. 구매 때의 attempt·request ID·OTP 교환 결과·최종 제공자 토큰을 복구 API에 각각 넣어 거부되는지 검증한다. 동일 번호라도 새 RECOVERY request에 대한 교환과 확인을 거쳐야 한다. 서로 다른 복구 시도의 결과도 바꿔 쓸 수 없어야 한다. 인증 시각의 초 단위 정밀도만으로 request 결합을 대신하지 않는다.
2. **잠금 순서.** 기준 코드의 `JdbcGuestAccess.login`은 previous session → buyer, `authorize`는 session → buyer 순서다. reset이 buyer → 전체 session이면 서로 대기할 수 있다. 조회·로그인·로그아웃·변경·재발급·복구를 함께 검토하여 순서를 통일한다. 이전 쿠키가 다른 buyer를 가리키는 로그인도 포함하며 여러 buyer를 잠그면 결정적인 순서를 사용한다. 선조회로 잠금 대상을 알아냈다면 잠금 후 관계·버전·폐기·만료를 다시 확인한다.
3. **전체 원자성.** password hash/version, recovery hash, 모든 기존 세션 폐기, grant 소비는 한 거래다. 실패를 각 갱신 뒤에 주입해 기존 상태가 모두 복구되는지 확인한다. 재발급도 이전 코드 및 이미 발급된 grant를 무효화해야 한다. 재발급 자체가 조회 세션까지 폐기하는지는 작업 문서에 별도로 확정하여 비밀번호 변경 정책과 혼동하지 않는다.
4. **응답 유실.** 최초 발급, 변경, 재설정, 재발급 각각 서버 커밋 뒤 응답을 버리는 검사를 둔다. 원문 재조회·소비된 grant 재사용 없이 현재 유효한 비밀번호로 로그인하고 재발급하여 복구한다. 전송 전 실패와 커밋 후 응답 유실을 구분할 수 없을 때 화면이 성공을 단정하거나 계속 이전 코드 사용을 유도하지 않아야 한다. 전화 인증 성공 후 grant 응답 유실의 재시작 경로도 필요하다.
5. **추측·반복 공격의 거래 경계.** 존재하지 않는 주문/전화·잘못된 코드에도 공통 오류와 제한을 적용한다. 실패 횟수는 예외로 롤백되지 않아야 하고 새 브라우저·새 attempt로 전화/출처 제한이 초기화되지 않아야 한다. 공급자 호출 실패 시 어떤 할당량을 소비하는지도 명시한다.

## 제약 → 자동 검사·기대 결과

| ID | 검사 방식과 필수 assertion | 현재 결과 |
| --- | --- | --- |
| D01 | JUnit: 32바이트 난수의 43자 URL-safe 표현, 잘못된 입력 거부, 해시 비교. PostgreSQL: 최초 발급 한 번·동시 최초 발급 한 승자, 저장값은 해시만, 일반 조회에서 원문 없음, 이전 코드 회전 후 거부. 단순 문자열 포함 검사를 원문 비저장의 유일 근거로 삼지 않는다. | 미실행 |
| D02 | PostgreSQL/HTTP: 유효 조회 세션과 현재 비밀번호 둘 다 필요. 다른 buyer·만료·폐기 세션 거부. 비밀번호 12~128 코드포인트·공백/붙여넣기 계약 유지. 느린 해시는 거래 밖에서 수행하고 그 사이 만료·logout·version 변경 시 갱신 거부. 변경 후 전체 이전 세션 401, 새 비밀번호만 로그인 가능. | 미실행 |
| D03 | PostgreSQL/실제 SDK 브라우저: 주문·등록 전화·복구 코드·browser·RECOVERY request 결합. 전화 단독·구매 증명·다른 시도의 증명·다른 browser·다른 buyer 조합 모두 거부. 동일 전화의 다른 묶음 자격/주문에는 변화 없음. | 미실행 |
| D04 | 제어 시계 PostgreSQL: attempt 3분/grant 5분 직전 허용·정각 거부, 잠금 대기/해시 연산 후 만료 재검사. grant는 해시만 저장하며 1회 소비. 같은 grant 동시 reset 및 서로 다른 grant의 같은 코드 reset 모두 한 승자, 나머지는 새 version/코드 기준 거부. | 미실행 |
| D05 | 실제 격리 PostgreSQL: 강제 DB 실패 후 password/version/code/session/grant 원상 유지. reset↔lookup/login/logout/change/reissue 및 다른 buyer 이전 쿠키 로그인 경합. latch·DB 대기 상태로 실제 겹침을 증명하며 정상 종료와 최종 권한 assertion을 둔다. 단순 반복 실행이나 타임아웃 확대는 대체 근거가 아니다. | 미실행 |
| D06 | PostgreSQL: 주문 5/출처 20/전화 3회, 재발송 59/60초, 15분 제한 899/900초, OTP 실패 5회 경계. 실패 카운터 영속, 새 browser/attempt 우회 불가, 동시 마지막 허용 슬롯 한 승자. 잘못된 주문·전화·코드의 공통 오류 검증. | 미실행 |
| D07 | HTTP/ArchUnit: CSRF, no-store(성공/실패), 기존 HttpOnly/Secure 쿠키 정책, disabled 503, fixture 비활성 시 최초 생성 경로 노출 없음. 로그·URL·일반 응답·브라우저 영구 저장소에 code/grant/password 원문 없음. guest는 identity/phone 공개 API만 참조. | 미실행 |
| D08 | Vitest: 중복 전송 차단·늦은 응답 무시·입력 비밀 삭제·실패 재시도·일회 코드 표시/화면 이탈. PC/모바일 실제 SDK: 최초 코드→변경/재발급→별도 전화 재인증→reset→이전 세션 401→새 로그인, 각 커밋 후 응답 유실 복구, 코드 분실 시 지원 안내와 자동 우회 없음. grant 응답 유실도 재시작 가능. | 미실행 |
| D09 | 기존 필수 목록 유지 및 신규 이름 등록. 개발 전체 통과·소스 동결 후 독립 새 경로 `./verify.ps1 -Offline`. status/preservation passed, 종료 0, 필수 누락·실패·skip 0, 소스 해시 일치. 새 DB/미디어, 기존 서버·DB·검증 DB·데모·미디어·실패 증거 보존. | 미실행 |

## 후속 검토와 증거

개발 소스가 준비되면 위 경계를 정적으로 검토하고 발견사항을 추가한다. 개발 전체 검증 통과 및 동결 확인 후 독립 전체 실행을 별도 수행한다. 이번 사전 검토에서는 하네스·서버·DB 명령을 실행하지 않았다. 이전 P06-C의 Java280/브라우저46/프런트139/Node72 통과는 P06-D의 근거가 아니다.

최종 기록에는 구체 메서드·기대값·실제 실행 결과, HEAD와 inputs 해시, 새 실행 경로 및 DB/미디어, 보존 비교, 실패 이력과 미검증 범위를 연결한다. 기존 원인 미확정 에뮬레이터/브라우저 실패를 새 성공으로 소급 해결 처리하지 않는다. 현재 P06-D 완료 판정은 보류한다.

## 초기 구현 정적 검토 — 개발1 실행 중

검토 시점: 개발 세션이 `.local-tools/p06-d-dev1-20261026` 실행 및 소스 동결을 알린 뒤. 이 세션은 하네스를 실행하지 않았고 이 문서만 수정했다. 아래 결과는 코드 독해이며 실행 재현/최종 완료 판정과 구분한다.

### R-D01 / P2 / 미해결 — 제공자 ID 증명이 개별 교환에 결합되지 않음

위치: `phone/infrastructure/FirebasePhoneGateway.java:66,82,88`, `guest/infrastructure/JdbcGuestRecovery.java:63` 부근.

`exchange`는 같은 전화의 Firebase UID로 추가 결합 정보 없이 custom token을 만든다. `confirm`은 제출 토큰의 custom provider·UID·전화·만료를 검사하지만 해당 exchange의 토큰인지 검사하지 않는다. 이후 반환하는 authenticatedAt도 제출 토큰의 시각이 아니라 현재 exchange에 저장한 시각이다. 따라서 같은 전화로 구매 때 받은 유효 ID token을 보관한 뒤 새 RECOVERY OTP를 교환하고, 복구 verify에 그 이전 구매 token을 넣으면 현재 조건을 만족할 수 있다. 다른 복구 시도의 token 대체도 같은 구조다.

새 복구 OTP 교환은 여전히 필수이므로 이것을 전화 재인증 자체를 생략하는 우회라고 판단하지 않는다. 그러나 요청된 구매 증명 재사용 금지·개별 request 결합 조건은 충족하지 못한다. 교환별 예측 불가능한 식별자를 custom token claim 등에 결합하고 확인 시 해당 교환과 정확히 대조하는 등, 실제 제출 증명이 그 교환에서 나온 것임을 검사해야 한다. 시각 비교만 추가하면 같은 초 내 교환을 구별하지 못한다.

필수 검증: 실제 Firebase SDK/에뮬레이터에서 같은 번호의 구매 ID token을 보관 → 새 RECOVERY OTP 정상 교환 → 이전 구매 ID token 제출 거부. 서로 다른 RECOVERY 교환 token 대체도 거부하고 해당 교환의 정상 token은 성공해야 한다. 기존 구매 인증 회귀를 함께 유지한다. 현재 DB fixture의 고정 `valid-proof`는 이 결합을 입증하지 않는다.

### R-D02 / P2 / 미해결 — 거래 후반 롤백·경합 및 경계 assertion 미완성

현재 신규 DB 메서드는 `guestRecoveryCodesChangePasswordsAndRevokeAllSessions`, `guestRecoveryRequiresEveryFactorAndConsumesGrantOnce`, `guestRecoveryResetRollbackExpiryAndRotationKeepBoundaries`다. 기본 회전, 같은 grant 한 승자, 정각 만료, 재발급 후 grant 거부는 포함한다. 다만 rollback 검사는 password_version CHECK로 첫 password UPDATE를 실패시키므로 이후 session 폐기·code 회전·grant 소비의 롤백을 검증하지 않는다.

사전 표 D02~D07의 남은 항목을 구체 메서드로 연결해야 한다: 거래 후반 실패 주입과 모든 상태 복원, 실제 잠금 대기 후 만료, 해시 중 logout/version/code 변경, 공급자 issue/exchange/confirm 지연·실패 후 상태 재검사, reset 대 조회/로그인/변경/재발급 경합, 서로 다른 grant의 한 승자, 한도·쿨다운의 직전/정각·동시 마지막 슬롯·실패 카운터 영속, disabled/fixture 비활성/오류 no-store. 개발 세션이 이미 추가 예정이라고 알린 항목이며, 검사 개수나 개발1 성공만으로 해결 처리하지 않는다.

### R-D03 / P2 / 미해결 — 응답 유실 검사 범위 부족

현재 브라우저 검사는 reset 커밋 후 응답 유실 → 새 비밀번호 로그인 → 재발급을 포함한다. 그러나 최초 코드 응답 유실, 관리 화면 비밀번호 변경 응답 유실, 재발급 응답 유실, verify 성공 뒤 grant 응답 유실의 복구 경로는 검사하지 않는다. 단위 검사 `does not claim success after a lost response and permits explicit retry`는 manage 요청 실패 한 번과 busy/error만 검사하며 실제 재시도 성공을 assertion하지 않는다.

각 유실 지점에서 원문 재조회 없이 현재 유효 자격으로 다시 진행 가능한지 확인하고, 이전 코드/grant 거부 및 재발급 결과를 assertion한다. 특히 verify 응답 유실 후 화면은 SENT에 남지만 서버는 READY이므로 동일 exchange 재시도는 성공할 수 없다. 현재 제공되는 '복구를 처음부터 시작' 링크를 이용한 새 인증 경로와 제한 안내를 실제 브라우저에서 검증한다.

### 실행 결과와 대조할 관찰

- `JdbcGuestAccess.login`은 candidate/previous buyer를 ID 정렬로 먼저 잠그고, authorize도 buyer→session 재조회로 변경했다. reset과의 잠금 순서 정렬 의도는 코드에 반영됐다. logout은 session만 갱신하므로 그 자체에 반대 buyer 잠금은 없다. 실제 경합 assertion 전에는 deadlock 부재를 통과로 표시하지 않는다.
- 현재 브라우저 시나리오는 같은 번호로 구매 OTP 확인 직후 곧바로 RECOVERY start를 시도한다. 실제 `PhoneProvider`는 purpose와 무관하게 번호별 lastSent 60초 제한을 공유하고 system clock을 쓴다. 60초 미만이면 recovery DB 한도는 허용해도 provider.issue가 거부하고 API는 409가 된다. 이는 정적 예상이며 개발1 실패 원인으로 확정하지 않는다. 실행 로그와 대조하고 쿨다운을 존중하는 검사 준비/사용자 재시도 경로로 보완해야 한다. 제한 제거나 전체 타임아웃 확대만으로 통과시키지 않는다.
- 최초 코드는 제품 HTTP endpoint가 아닌 내부 `initialCode`와 테스트 주문 fixture에서 발급한다. 코드 재발급은 password version/세션을 변경하지 않고 recovery hash만 회전하여 기존 grant를 무효화한다. 이 정책을 작업 기록에 명확히 남긴다.

독립 전체 검증은 아직 미실행. R-D01~03과 사전 제약의 미검증 항목이 남아 있으므로 P06-D 완료를 승인하지 않는다.

## 개발3 소스 동결 중 후속 정적 검토

이 절은 위 초기 발견사항의 후속 상태다. 하네스 동시 실행·소스 수정 없이 gateway, 신규 DB10개 메서드, 브라우저3개 시나리오(PC/모바일 6실행), 프런트 단위 변경을 읽었다. 개발3 전체 결과와 독립 실행은 아직 이 절의 근거에 포함하지 않는다.

### R-D01: 수정 확인, 최종 실행 대기

`FirebasePhoneGateway`가 exchange마다 UUID binding을 만들고 custom token의 `phone_exchange` claim에 넣으며 confirm에서 저장된 binding과 정확히 비교한다. 기존 UID/전화/만료 검사도 유지한다. `verification/phone-browser.mjs`는 같은 전화의 이전 token을 다음 두 교환에 대입해 각각 400을 기대하고 해당 교환 token의 200을 확인한다. 이 검사는 실제 SDK/gateway 결합 검사이며 제품 복구 API 전체 검사는 아니다.

수정 전 재현은 `.local-tools/p06-d-dev2-binding-red-20261026/db/phone-browser.txt`에서 직접 확인했다: `earlier same-phone purchase/recovery proof must not satisfy a new exchange`, 실제 200/기대 400. 실행 결과가 확인될 때까지 최종 해결 판정은 보류한다.

### R-D02: 주요 보완 확인, 남은 assertion

- `guestRecoveryFinalWriteRollbackAndDifferentGrantsHaveOneWinner`: 마지막 CONSUMED 갱신을 CHECK로 실패시켜 version/code/session/READY 복원을 확인하고, 제약 제거 후 다른 두 grant 경쟁의 한 승자를 검사한다.
- `guestRecoveryLateFailureRollsBackAndSerializesWithLookup`: 세션 폐기 실패 롤백, 실제 orders 거래가 잡은 잠금에 reset이 대기하는지 pg_stat_activity로 확인, 해제 후 reset 성공과 이전 세션 거부를 검사한다.
- `guestRecoveryRechecksGrantAfterBuyerLockAndHashSideEffects`: buyer 잠금 대기 후 5분 만료 거부, 현재 비밀번호 확인 중 logout, 새 비밀번호 해시 중 코드 회전에 따른 거부를 검사한다.
- `guestRecoveryRechecksExternalWorkExpiryVersionAndDisabledMode`: issue/exchange/confirm 지연 및 해시 연산 후 만료, disabled 서비스 503을 검사한다.
- `guestRecoveryLimitsSurviveFailuresAndExpireAtWindowBoundary`, `guestRecoveryLostGrantRestartsWithoutReusingPurchaseProof`, `guestRecoveryHttpRejectsCsrfAndReturnsUniformPrivateErrors`: 횟수·시간·재시작·구매 attempt 거부·공통 오류/no-store 검사 추가를 확인했다.

남은 필수 연결: (1) 변경한 login의 서로 다른 buyer 이전 쿠키 잠금과 reset/회전의 실제 경합, (2) 같은 grant가 아닌 첫 코드 동시 발급 한 승자, (3) 마지막 허용 할당량을 여러 요청이 동시에 사용하는 경계. 현재 코드는 직렬화 의도가 있으나 기존 사전 계약 D01/D05/D06에 대응하는 신규 assertion은 찾지 못했다. 이미 다른 검사에 있으면 정확한 메서드·assertion을 연결하고, 없으면 보완한다. 일반 `race`만으로 실제 잠금 대기를 증명했다고 표현하지 않는다.

### R-D03: 유실 복구 보완 확인, 제한 안내는 남음

최초 주문 응답 body를 사용하지 않고 로그인으로 재발급하는 검사, 재발급/비밀번호 변경 커밋 후 route.abort 및 재시도, grant 응답 유실 후 처음부터 시작 경로가 추가됐다. 단위 검사에도 실패 뒤 실제 재시도 성공과 COMPLETE/code assertion이 추가됐다. 60초 이후 재시작 성공은 `guestRecoveryLostGrantRestartsWithoutReusingPurchaseProof`의 DB 가상 시계 근거이며 실제 브라우저가 60초를 기다려 성공한 증거로 표시하지 않는다.

남은 UI 문제: `GuestRecoveryStore.call`은 429를 다른 실패와 함께 `Error('request')`로 바꾸고 run은 일반적인 입력/유효시간/새 비밀번호 안내만 표시한다. 새로 시작한 INPUT 화면에는 SENT 화면의 '60초 뒤' 설명도 없다. 따라서 grant 응답 유실 후 '처음부터'→즉시 start의 제한에서 무엇을 기다려야 하는지 알 수 없다. 새 브라우저 검사는 alert 존재만 확인하므로 이름의 '재요청 제한을 안내한다'를 입증하지 않는다. 제한 응답을 구분해 쿨다운·횟수 제한에 맞는 재시도 안내를 제공하고 해당 문구와 입력 단계 유지 assertion을 추가한다. 429가 항상 60초 제한인 것은 아니므로 모든 429에 60초 뒤 성공을 보장하면 안 된다.

기존 구매 직후의 쿨다운 충돌은 historical test-only fixture로 복구 시나리오의 준비 조건을 분리했다. 실제 구매 검사는 기존 C 경로에 유지되고 제공자 제한은 제거되지 않았다. 이 준비 방식 자체는 범위에 부합한다.

현재 판정: 새 증명 결합 수정 및 주요 검사 보완은 확인. R-D02 잔여 assertion, R-D03 제한 안내, 개발/독립 전체 결과 연결 전까지 완료 보류.

## 개발 최종 실행 중 정적 대조

추가된 두 DB 메서드와 필수 목록 등록을 확인했다.

- `guestRecoveryInitialLimitAndCredentialRacesAreSerialized`: 최초 코드 발급의 성공/거부 한 쌍, 주문 한도 4회 실패 뒤 동시 요청의 성공/429 한 쌍, reset/manage의 한 승자와 최종 version=2·이전 세션 거부를 assertion한다.
- `guestRecoveryCrossBuyerLoginAndVersionChangesCannotRestoreRevokedState`: A reset과 previous=A 쿠키를 사용하는 B login이 종료되고 B 주문만 조회되며 A 이전 세션이 거부되는지 검사한다. manage의 비밀번호 확인 중 version 변경 후 갱신 거부와 기존 password 유지도 검사한다.

두 메서드는 공통 latch로 두 작업을 시작하는 경합 검사다. 개별 SQL이 실제로 동시에 잠금 대기했다고 증명하는 검사는 아니며, 앞서 추가한 조회/reset 및 만료 검사의 pg_stat_activity 대기 증거와 구분한다. 정적 buyer ID 정렬·buyer→session 순서 확인 및 이 경합 assertion을 함께 판단 근거로 사용한다.

429는 store에서 별도로 분류하며 화면에 '재발송은 최소 60초', '반복 제한에 걸리면 15분'을 안내한다. 모든 요청이 60초 후 성공한다고 보장하지 않는다. 신규 단위 `explains the resend and window limits after a 429 without exposing protected data`는 두 문구와 INPUT/빈 code/busy=false를 검사하고 필수 목록에 등록됐다. grant 유실 브라우저 검사도 두 문구와 인증번호 입력 부재를 assertion한다. R-D03의 남은 제한 안내 수정은 정적으로 확인했다.

개발3 원본 `result.json`에서 status/preservation passed, Java291·브라우저52·프런트142를 직접 확인했다. `db/phone-browser.txt`의 SDK probe PASS도 확인했다. 이는 추가 DB2개·429 단위가 반영되기 전 결과이며 최종 소스 통과로 소급하지 않는다.

현재 추가 확정 결함은 발견하지 않았다. R-D01~03 수정 및 검사 연결을 정적으로 확인했으며, 최종 해결/완료 판정은 `.local-tools/p06-d-dev-final-20261026` 통과와 동결 소스의 독립 전체 검증 후 확정한다. 이 검토에서도 문서만 수정했고 하네스·제품 소스·기존 자원은 변경하지 않았다.

## 독립 전체 검증 최종 결과 — 2026-10-26

개발 최종 실행 종료와 소스 동결 통보 후 루트에서 다음을 별도로 실행했다.

`./verify.ps1 -Offline -EvidenceDirectory .local-tools/p06-d-review-final-20261026`

- 실행 시간: 16:40:40.3788689~16:44:22.9242671 KST. 프로세스 종료 0, result status=passed, preservation=passed.
- Java293(전부 passed), 실제 브라우저52, 프런트 단위143, Node72 통과. 필수 coverage 전체 passed, 실패·누락·skip 없음. ArchUnit/Checkstyle/타입/lint/build/하네스 gate 포함.
- 기준 HEAD `6eef13dbbfd4b6dee556d42578b229113c25f858` 위 미커밋 P06-D 소스332개. 개발 최종 inputs와 경로·해시 차이0, 실행 후 현재 소스 불일치0.
- inputs.json SHA256: `C23695F38C45B29442F8DB61DAB61EF04DBBEFDD326075A220D26AC925EAFFFE`.
- 새 DB `d2_it_bf20b81272124e3ea04b1e87b78b7f37`, 미디어 `.local-tools/p06-d-review-final-20261026/db/media`, 에뮬레이터 `.local-tools/phone-emulator-2aefb5a5ef404b1387826fc39b7844a2`.
- 원본 증거는 실행 경로에 보존. Git 관리용 요약/소스/coverage/Java cases/보존/명령 로그/실제 SDK probe/브라우저 보고서 등 19개 사본은 `docs/work-items/evidence/P06-D-review-final-*`. 추가 대조 결과는 `review-verification.json` 및 그 사본에 기록했다.

### 제약별 실제 결과

아래 DB 메서드는 `PostgresJdbcIT`이며 이번 `java-cases.json`에서 각각 passed를 확인했다. 실제 assertion 상세는 앞 절 및 개발 작업 기록의 제약 매핑을 함께 따른다.

| 제약 | 이번 실행의 핵심 근거 | 판정 |
| --- | --- | --- |
| D01 난수·비저장·최초/회전 | RecoverySecretsTest#usesRandomBoundedSecretsAndConstantHashComparison, guestRecoveryCodesChangePasswordsAndRevokeAllSessions, guestRecoveryInitialLimitAndCredentialRacesAreSerialized | 통과 |
| D02 현재 자격·변경·전체 폐기 | guestRecoveryCodesChangePasswordsAndRevokeAllSessions, guestRecoveryRechecksGrantAfterBuyerLockAndHashSideEffects, guestRecoveryCrossBuyerLoginAndVersionChangesCannotRestoreRevokedState, 기존 GuestPassword/조회 수명 회귀 | 통과 |
| D03 별도 복구 인증·증명 결합 | guestRecoveryRequiresEveryFactorAndConsumesGrantOnce, guestRecoveryLostGrantRestartsWithoutReusingPurchaseProof, PhoneProviderEmulatorIT#browserSdkAndJavaVerifierEnforceProviderContract 및 phone-browser.txt PASS | 통과 |
| D04 만료·일회성·재검사 | guestRecoveryResetRollbackExpiryAndRotationKeepBoundaries, guestRecoveryRechecksExternalWorkExpiryVersionAndDisabledMode, guestRecoveryRechecksGrantAfterBuyerLockAndHashSideEffects, guestRecoveryFinalWriteRollbackAndDifferentGrantsHaveOneWinner | 통과 |
| D05 거래 롤백·동시성 | guestRecoveryLateFailureRollsBackAndSerializesWithLookup, guestRecoveryFinalWriteRollbackAndDifferentGrantsHaveOneWinner, guestRecoveryInitialLimitAndCredentialRacesAreSerialized, guestRecoveryCrossBuyerLoginAndVersionChangesCannotRestoreRevokedState | 통과 |
| D06 횟수·시간 제한 | guestRecoveryLimitsSurviveFailuresAndExpireAtWindowBoundary, guestRecoveryLostGrantRestartsWithoutReusingPurchaseProof, guestRecoveryInitialLimitAndCredentialRacesAreSerialized | 통과 |
| D07 HTTP/모듈/비노출 | guestRecoveryHttpRejectsCsrfAndReturnsUniformPrivateErrors, disabled 서비스 검사, 기존 HTTP 쿠키/Secure·fixture 비활성 회귀와 ArchUnit, 실제 브라우저 storage/URL 및 secret 입력 삭제 assertion | 통과 |
| D08 화면·유실·재시도 | GuestRecoveryStore 단위4개 및 guest-recovery.spec.ts 3시나리오×PC/모바일. reset/변경/재발급/grant 유실, 최초 응답 body 미사용, 재발급·429 문구 assertion | 통과 |
| D09 전체 하네스·소스·보존 | result/coverage/frontend-coverage/browser-coverage, before/after 및 review-verification | 통과 |

R-D01은 수정 전 실제 SDK 200≠400 실패 증거와 수정 후 개발/독립 SDK PASS를 연결해 해결로 판정한다. R-D02는 후반/최종 갱신 롤백·시간·경합 검사, R-D03은 응답 유실 및 실제 재시도·제한 문구 검사로 해결을 확인했다. 필수 잔여 차단 없음.

### 보존·실패 이력·한계

기존 환경 보존 결과 passed. DB 존재 목록177→178은 이번 새 검증 DB 추가이며 이전 항목은 유지됐다. 알려진 미디어1216→1234, 기존 파일 해시 유지. 기존 개발/데모 DB 정규화 내용, 컨테이너 ID, 알려진 서버 포트/PID를 하네스로 비교했다. 이는 과거 검증 DB의 모든 행이나 프로젝트 밖 모든 파일을 전수 비교했다는 뜻은 아니다.

실행 전·후 A2162/B405/C791/D882, 총4,240개 실패 증거 해시 불일치0. D의 최초 import/미구현 컴파일·프런트 미구현 실패, 개발1 구매 직후 공유 쿨다운 충돌, 개발2 증명 대입 재현을 보존한다. 원본 경로·파일 목록은 `P06-D-failure-manifest.json`을 따른다. A/B 에뮬레이터 시작 실패와 C 개발2 시간초과의 원인 미확정 기록은 그대로 유지하며 이번 성공으로 소급 해결하지 않는다.

완료 범위는 로컬 에뮬레이터·가상 주문 참조의 자격 변경/복구다. 실제 SMS·운영 인증·실제 기기·다중 인스턴스/운영 부하·실제 지원 접수는 미검증이다. 실제 최초 주문 거래 연결은 P08-C 범위다. grant 응답 유실 뒤 60초 경과 재시작 성공은 DB 가상 시계로 입증했으며 브라우저 실시간 대기 성공으로 표시하지 않는다. 일반 경합 검사는 공통 시작 latch의 최종 상태 검사이고 SQL 잠금 대기 관찰은 별도 조회/reset·만료 검사에 한정된다.

이 세션에서는 제품 소스를 수정하지 않았고 지정된 독립 하네스와 검토 문서·증거 사본만 작성했다. 기존 자원·실패 기록을 보존했고 커밋·푸시·배포·P06-E를 진행하지 않았다.
