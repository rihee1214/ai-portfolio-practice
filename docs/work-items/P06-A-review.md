# P06-A 독립 검토

## 최종 독립 실행 판정

**2026-10-25: P06-A의 로컬 제공자 범위 독립 전체 검증 통과. R-A01·R-A02·R-A03 및 실행 차단 B-A04 해소, 필수 잔여 차단 없음.** 이후 아래의 미검증/차단 문구는 당시 이력으로 보존한다. 이 판정은 운영 전화 인증·P06-B 구매 인증 자격 완성을 뜻하지 않는다. 커밋·푸시·P06-B는 수행하지 않았다.

실행: `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p06-a-review-final2-20261025`. 이 검토 세션에서 직접 실행, 종료 코드 0, result.json의 status=passed/preservation=passed. 실행 시각 17:05:23~17:08:30 KST. 새 Auth Emulator 경로는 `.local-tools/phone-emulator-c16962275e9f4d29b8f87469b25883dd`이며 CLI 종료 성공도 확인했다.

- Java 258개: 기본149 + DB/제공자109. 제공자 정책10개 및 실제 SDK 연결IT1개 포함. 기존 XML Accept→406 검사도 통과했다.
- 기존 브라우저40개, 프런트 단위128개, Node72개(자원 정리 회귀4개 포함), 하네스 gate, 타입·lint·build 통과. 필수 목록 대조 통과, 실패/skip 없음. 제공자 브라우저 probe는 Java IT에 포함되며 기존 브라우저40에 중복 합산하지 않는다.
- 소스295개는 개발 full2 inputs와 완전히 동일하고 실행 후 현재 파일 해시 불일치0. inputs.json SHA256 `12B356447D616D7A1EA15648862E12E92F05C21849C5DDF6487E87A166B527CB`. 기준 HEAD `47867e962f620d345c937bf865e879571b565009` 위의 미커밋 변경이다.
- 기존 DB161개 유지, 새 검증 DB `d2_it_983e95a137bd4225a645a523fc789de7` 추가로162개. 기존 미디어945개 유지, 새 검증 미디어 포함963개. 개발/테스트/데모 덤프·기존 컨테이너·알려진 서버 리스너는 하네스 보존 비교 통과. 과거 검증 DB는 존재 목록 비교이며 전체 행 덤프 전수 검증은 아니다.
- 사전 수집한 P06-A 실패/관련 실행 및 JVM 충돌 증거2162개 해시 불일치0. `evidence/P06-A-review-failure-manifest.json`과 `P06-A-review-failure-preservation.json`에 기록했다. 기존 P05-D 원인 미확인 실패를 해결로 소급 판정하지 않는다.

### 제약별 실제 근거

| 조건 | 검사/assertion과 결과 |
| --- | --- |
| A01 실제 연결 | PhoneProviderEmulatorIT#browserSdkAndJavaVerifierEnforceProviderContract 및 db/phone-browser.txt: Java OTP 교환→React custom token 교환→Java 검증 성공, 잘못된 OTP·교환 재사용·확인 재사용 거부, 정상 번호/인증 시각 확인. 통과 |
| A02 증명 신뢰 | 실제 probe의 verified:true·다른 번호·다른 aud·만료 토큰400 후 정상 토큰200. PhoneProviderTest#rejectsWrongNumberOrStaleProof, #rejectsExpiryDuringProviderCallAndFutureProof. 통과 |
| A03 만료/횟수/경합 | #confirmationExpiresAtExactBoundaryAndConsumesSuccess, #resendInvalidatesOldRequestAndLimitsIssuanceAcrossFormats, #failedConfirmationLocksNumberAndResendCannotResetFailures, #failedCodeExchangesShareTheConfirmationFailureBudget, #concurrentConfirmationHasExactlyOneWinner, #issuanceFailureConsumesQuotaAndInvalidatesPreviousRequest. 경계 만료·구 요청 무효·공유 실패 예산·1승자·장애 시 발급 횟수 소비 통과 |
| A04 번호 정규화 | #normalizesOnlySupportedPhoneFormats의 국내/국제 동치와 잘못된 표기 거부, 재발송 검사의 표현 간 동일 예산. 통과 |
| A05 로컬 전용 | 정책 비로컬 거부, 실제 IT의 production/실제 프로젝트/외부 호스트 생성 거부, 프런트 DEV=false 및 잘못된 설정 초기화 전 거부. 통과 |
| A06 프런트/경계/정리 | phone-provider 프런트3개의 메모리 persistence·dispose·안전한 오류, 실제 probe localStorage 비저장, ArchUnit 실제 클래스/부정 fixture, Node 자원 정리4개. 통과. 제품 phone 소스에는 관리API OTP 조회가 없고 테스트 fixture에만 존재함을 정적으로 확인 |
| A07 전체 회귀/보존 | 전체 결과·필수 Java/브라우저/프런트 coverage·소스 해시·before/after 비교. 통과 |

선별 원본 증거는 `evidence/P06-A-review-final-*`에 저장했다(result, inputs, coverage, JUnit 사례, 프런트 XML, 명령 로그, before/after, DB 요약/대상, 제공자 probe, 브라우저 report). 문서 검토와 총 개수만으로 통과시킨 것이 아니라 위 assertion 및 실제 실행 결과를 대조했다.

### 이번 실행의 실패 및 남은 제한

첫 독립 시도는 `.local-tools/phone-emulator-320c218584124422a3e8a08e2092ba84`에서 loopback 포트3672 기동 timeout으로 종료1이었다. 본 하네스의 inputs/DB 검사 진입 전 실패다. 로그를 `evidence/P06-A-review-first-emulator-failure.txt`에도 보존했다. 필요한 실행 권한으로 새 경로의 동일 소스를 실행해 성공했으나 첫 기동 실패의 정확한 원인을 확정한 것은 아니다. 테스트 삭제·timeout 증가·필수 목록 변경 없이 재실행했다.

개발 중 메모리 부족, OTP 중복 교환 결함, full1 XML 응답 회귀 및 RED 실행들은 원래 결과로 유지한다. 성공 실행은 이전 실패를 없애지 않는다. 제공자 메모리 상태는 재시작 시 초기화되며 운영용·다중 인스턴스 보장은 없다. Firebase 에뮬레이터의 unsigned token과 가상 번호만 검증했으며 실제 SMS·실명 확인·운영 자격·공개 데모 인증을 검증하지 않았다. 테스트 HTTP bridge는 fixture이고 구매 세션/용도 결합·업무 인증 결과 소비는 P06-B 이후다. 실제 사용자 학습/직접 사용 확인도 미확인이다.

## 개발 full1 회귀와 XML 의존성 제외 확인

개발 전체 `.local-tools/p06-a-resume-full1-20261025/result.json`을 읽어 status=failed, preservation=passed를 확인했다. 개발 측은 기존 `ApiErrorHttpTest#unacceptableSuccessRepresentationStillHasJsonError`에서 XML Accept 요청에 기대한 406 대신 200을 반환했다고 보고했다. 이 테스트의 기존 계약은 유지돼 있으며 새 기능 때문에 XML 응답을 허용하도록 assertion을 완화하지 않았다.

현재 `backend/pom.xml`의 Firebase Admin 의존성에는 `com.fasterxml.jackson.dataformat:jackson-dataformat-xml` 하나의 exclusion이 추가됐다. 인증용 JSON 연결과 관계없이 MVC 응답 형식을 넓히는 간접 의존성을 제거한다는 수정 방향은 기존 계약과 부합한다. 다만 제외 후 해결됐다는 판정은 새 전체 실행에서 해당 406 회귀 검사와 Firebase 연결 검사가 함께 통과하는지 확인한 뒤 내린다. 개발 측 dependency resolve 분석 보고와 이 세션의 정적 판단은 구분하며, full1 실패를 성공으로 소급 변경하지 않는다.

개발 full2 실행 중이므로 이 세션에서는 별도 하네스·의존성 해석 명령을 실행하지 않았고 제품 소스를 수정하지 않았다. 독립 실행 및 최종 완료 판정은 계속 대기한다.

## 재개 후 정적 확인

2026-10-25 재부팅 후 변경된 현재 소스와 개발 측 저장 로그를 읽었다. 개발 전체 하네스가 진행 중이므로 이 세션에서는 테스트·서버를 실행하지 않았다. **R-A02·R-A03의 지적 사항은 코드/검사상 보완됐으며 독립 전체 실행 판정은 대기한다.** 아래의 미해결/메모리 차단 문구는 당시 이력이다. 메모리 회복은 개발 측 보고이며 이 검토에서 시스템 자원을 측정하지 않았다.

- R-A02: `PhoneProviderTest`에 동시 확인 1승자, 통신 중 만료/미래 시각 거부, 발급 실패의 횟수 소비/이전 요청 무효화 검사가 추가됐고 필수 목록에도 등록됐다. 실제 `phone-browser.mjs`에는 다른 번호 토큰·다른 aud·만료 토큰 거부 후 정상 토큰 성공 assertion이 있다. 로컬 unsigned 토큰 변형은 테스트 fixture에서만 수행한다.
- R-A03: `withPhoneResources`가 listen·launch·run을 try 안에 두고 중첩 finally로 browser.close 실패에도 server.close를 호출한다. `phone-resources.test.mjs` 4개는 listen/launch/run/browser-close 실패별 정리 순서를 확인하며 기존 npm test의 파일 패턴에 포함된다. 원래 launch 실패 정리 공백은 정적으로 해소됐다.
- 실제 연결 흐름: Java `exchange`가 OTP를 Firebase REST에 한 번 제출하고 Admin SDK로 결과를 검증한 다음 해당 UID의 custom token을 발급한다. React는 메모리 persistence로 custom token을 ID token으로 교환한다. Java `confirm`은 저장된 교환 결과의 UID·번호, 토큰 provider custom·만료와 Admin SDK 검증을 거친 뒤 원 전화 인증 시각을 반환한다. `PhoneProvider`가 요청 만료·번호·시각·일회성 성공을 검사하며 exchange/confirm 실패 횟수를 공유한다. 공유 예산 검사 `failedCodeExchangesShareTheConfirmationFailureBudget`도 필수 목록에 등록됐다.
- 제품 어댑터에서 verificationCodes 관리 API를 조회하는 코드는 읽은 현재 phone 소스에 없다. 해당 조회는 테스트 fixture의 코드 입력 준비에만 있다. 구매 세션·회원 권한·실제 SMS 처리는 추가되지 않았다. 이 결과는 로컬 테스트 제공자 계약이며 P06-B의 업무 세션/용도 결합 완료를 뜻하지 않는다.

읽기 확인한 개발 실행 증거: `.local-tools/p06-a-resume-exchange-20261025/java.txt`는 정책9+실제 연결IT1, 합계10개 실패0/오류0/skip0 및 BUILD SUCCESS다. 같은 경로 `phone-browser.txt`에 실제 연결 probe PASS가 있다. 이는 개발 측 실행의 저장 로그를 확인한 것이며 이 세션의 독립 실행이 아니다. 이후 추가된 공유 실패 예산 검사의 성공을 이 이전 10개 결과에 소급 포함하지 않는다.

잔여 조건: 현재 소스의 개발 전체 실행 완료·소스 고정 확인 후 별도 새 경로에서 독립 전체 하네스를 실행하고 제약별 결과·소스 식별·보존·과거 실패 유지 여부를 기록한다. 그 전까지 P06-A 완료/커밋 가능 판정은 하지 않는다. 이 검토는 검토 문서만 갱신했으며 P06-B를 진행하지 않았다.

## 필수 목록 등록 후 상태 갱신

개발 측 후속 변경을 파일 읽기로 확인했다. `required-tests.json`에 PhoneProviderTest의 현재 정책 6개 및 PhoneProviderEmulatorIT 실제 연결 1개, `required-frontend.json`에 phone-provider 프런트 단위 3개가 등록돼 있다. **R-A01의 등록 누락은 정적으로 해소됐다.** 다만 필수 목록과 실제 실행 결과를 대조하는 gate 및 전체 하네스는 아직 실행 미검증이다. 아래의 등록 누락 지적은 수정 전 이력으로 보존한다.

`P06-A.md`에는 RED/GREEN별 결과, Windows 메모리 차단, SDK→Java 연결·프런트·전체 하네스 미검증, 보존 스냅샷 미실행이 명시됐다. 문서 기록과 실제 독립 실행 성공은 구분한다. **R-A02 검사 공백, R-A03 기동 실패 정리 공백 및 B-A04 환경/전체 실행 차단은 유지한다.** R-A02·R-A03은 정적 코드 근거이며 실행 재현 결과가 아니다. 현재 완료 판정은 계속 보류하며 이번 확인에서도 제품/테스트를 실행하지 않았다.

## 메모리 차단 시점 정적 검토

읽기 확인한 환경 실패 근거: `.local-tools/p06-a-provider-green3-20261025/java.txt` 40~41행에 JVM의 insufficient memory 및 32,744바이트 native malloc 실패가 기록돼 있다. 이는 해당 실행의 메모리 할당 실패 근거이며 전체 시스템 부족의 발생 원인이나 현재 복구 여부를 입증하지 않는다.

2026-10-25 개발 측이 Windows commit/페이징 부족과 프로세스 생성 오류 1455를 보고하여 실행 검토를 중지했다. 이 세션에서는 소스·검사·하네스·작업 문서만 읽었으며 Java/Node/브라우저/에뮬레이터·전체 하네스를 실행하거나 기존 프로세스를 종료하지 않았다. 가벼운 파일 읽기가 성공한 것은 전체 검증을 실행할 메모리가 확보됐다는 근거가 아니다.

**판정: P06-A 완료 차단.** 제공자 단위 6개 통과·Java 발급 성공은 개발 측 보고이고, SDK→Java 전체 연결 및 새 전체 하네스·보존 성공은 미확인이다. 아래는 현재 소스의 정적 근거이며 실행으로 재현한 결함과 구분한다.

| ID | 차단/보완 사항 | 근거 및 필요한 조치 |
| --- | --- | --- |
| R-A01 | 필수 검사 등록 누락 | required-tests.json과 required-frontend.json에 새 PhoneProviderTest/PhoneProviderEmulatorIT 및 phone-provider 단위 검사가 없다. 파일 삭제·이름 변경이 조용히 빠지지 않도록 필수 등록 후 gate와 전체 실행으로 확인해야 한다. 제공자 브라우저 probe는 일반 Playwright report 밖에서 IT가 실행하므로 해당 IT 등록과 probe assertion 유지도 필요하다. |
| R-A02 | 제약별 거부/경계 검사 공백 | PhoneProviderTest 6개에는 문서가 예고한 동시 확인 검사가 없고, 실제 Firebase probe에는 다른 프로젝트/번호·만료 토큰 거부가 없다. 잘못된 문자열 토큰 거부는 이러한 검사를 대신하지 않는다. 통신 중 만료 재검사, 발급 실패의 횟수 소비, 미래 시각 거부도 구현 분기가 있으나 assertion 근거가 없다. 작업 문서의 검사 약속을 실제 메서드/사례에 연결하고 필요한 검사를 보완해야 한다. |
| R-A03 | 브라우저 기동 실패 정리 공백 | phone-browser.mjs에서 server.listen() 뒤 chromium.launch()가 try/finally 밖에 있다. launch가 실패하면 server.close()에 도달하지 않아 Vite 자원이 Node 종료까지 남을 수 있다. 서버 시작부터 정리 범위에 포함하고 부분 초기화 상태에서도 해당 테스트가 만든 자원만 정리하도록 보완한다. 현재 메모리 부족의 원인이 이 코드였다는 뜻은 아니다. |
| B-A04 | 환경 및 전체 실행 미검증 | 개발 측 보고의 1455/네이티브 메모리 실패를 해결하거나 실행 가능한 자원 여유를 확인한 뒤에만 새 경로에서 실행한다. 타임아웃 확대·검사 제거로 우회하지 않는다. 메모리 조치 전후와 최초 실패 증거를 남기고 개발/독립 전체 실행 및 보존 검사를 각각 확보한다. |

범위상 현재 PhoneProvider는 메모리의 제공자 정책이며 구매 세션/GuestBuyer를 생성하지 않는다. FirebasePhoneGateway는 Spring bean/API로 노출하지 않고 local-test·loopback·demo 프로젝트 설정을 요구한다. 프런트는 개발 모드와 메모리 persistence를 사용한다. 이 구조는 P06-A 경계와 맞지만 실제 런타임 거부 및 ArchUnit 통과까지 입증된 것은 아니다. 메모리 상태의 재시작 초기화와 서로 다른 번호에 대한 누적 상태 정리 한계도 로컬 제공자 제한으로 기록해야 한다.

개발 측이 지정한 .local-tools/p06-a-red*, p06-a-green1*, p06-a-provider-{red,green1,green2,green3}-20261025 및 backend/hs_err_pid*.log, replay_pid*.log는 삭제하지 않는다. 이 검토에서는 해당 증거의 전수 해시/내용 검증이나 기존 DB·미디어의 신규 보존 스냅샷을 수행하지 않았다. 보고된 메모리 장애와 SDK 연결의 기능적 성공 여부를 혼동하지 않으며 P06-B는 진행하지 않는다.

## 환경 조사 후 범위 확인

개발 측에서 Firebase CLI 15.34.1이 기존 Node 24.22.0에서 실행되고 demo 프로젝트의 Auth Emulator 기동에 성공했다고 전달했다. 이는 개발 측 보고이며 이 검토자가 해당 실행 증거를 독립 확인한 상태는 아니다. Firebase 경로를 우선 구현한다는 선택은 설계와 일치한다.

제공자 모듈·React SDK 어댑터·Java 토큰 검증 및 제공자 단계의 발급/확인/만료/재발송/실패 제한을 구현하고, 구매 세션·용도 결합·업무 인증 시도 영속화·이름/전화 사용자 화면을 제외한다는 경계에 현재 문서상 충돌은 없다. 다음 사항은 구현 검토에서 확인해야 한다.

- CLI 실행·에뮬레이터 기동만으로 SDK → 제공자 → Java 검증의 실제 연결을 통과로 표시하지 않는다.
- 만료·재발송·실패 제한이 제공자 자체 기능인지 앱 어댑터 정책인지 구분하고 적용 지점을 명시한다. 테스트 전용 래퍼에서만 제한하고 실제 연결 경로가 우회할 수 있다면 제약 충족으로 인정하지 않는다.
- 제공자 증명 검증은 구매 자격 발급과 구분한다. 번호·인증 시각 등의 검증된 결과를 반환하되 P06-B의 세션·용도 결합이나 일회성 업무 소비가 완료됐다고 표시하지 않는다.
- 에뮬레이터의 테스트 특성을 실제 SMS·운영 인증 보장으로 확대하지 않는다. 로컬 허용/비로컬 거부 검사는 여전히 필요하다.

이 추가 확인에서도 소스·서비스·데이터 변경이나 하네스 실행은 하지 않았다. 구현 완료 판정은 보류한다.

2026-10-25. 범위 사전 검토. 기준: AGENTS.md, README.md, development-workflow.md, verification-harness.md, implementation-plan.md §8 및 shop-design.md 전화 인증 경계.

**현재 판정: 범위 검토만 수행. 구현·실행 결과 미검증이며 완료 판정 보류.** 개발 측의 CLI 설치·환경 조사 진행 전달은 실행 증거로 인정하지 않는다. 이 검토에서 제품·검사 소스 변경, 제공자 실행, 전체 하네스 실행, 커밋·푸시는 수행하지 않았다.

## P06-A / P06-B 경계

- P06-A: 로컬 전화 인증 제공자 계약과 Firebase Auth Emulator 우선 환경 검증, React 브라우저 SDK → 제공자 → Java 검증 어댑터의 실제 연결. 발급·확인·만료·재발송·실패 제한을 검사한다. 실제 SMS를 보내지 않는다. 무료 도구가 환경에 맞지 않으면 Java 모의 제공자로 대체한 사유·차이·미검증 경로를 기록한다.
- P06-B: 실제 구매 세션·인증 시도 업무 상태와 이름·전화 입력 화면, 해당 세션의 번호·용도 대조 및 인증 결과 재사용 차단. 이번에는 구현하지 않는다. P06-A의 제공자 연결용 테스트 화면·fixture를 실제 구매 인증 완료 화면이나 업무 자격으로 취급하지 않는다.
- 이후 범위: GuestBuyer·조회 비밀번호/조회 세션(P06-C), 복구·세션 폐기(P06-D), 재구매 연결(P06-E), 실제 주문 생성과 인증 결과 소비의 원자성(P08-C). 모두 이번 범위 밖이다.

제공자 결과의 신뢰성 검증은 P06-A에서 필요하다. 반면 그 결과를 구매 세션에 결합·소비하는 업무 흐름은 P06-B 이후다. A의 성공을 비회원 구매 자격 완성으로 표시하면 안 된다. Firebase를 기존 회원 로그인 저장소로 바꾸지 않는다.

## 제약조건과 요구할 자동 검사

아래는 필요한 assertion과 증거의 사전 목록이다. 실제 구현 후 테스트 이름·파일·실행 결과에 연결해야 하며, 현재 통과한 검사 목록이 아니다.

| ID | 조건 | 요구할 검사·기대 결과 |
| --- | --- | --- |
| A01 | 환경·실제 연결 | 실제 Node/JDK/CLI/SDK/Java 어댑터 버전과 호환 근거, 새 로컬 제공자 인스턴스에서 브라우저 발급·확인 후 Java 검증 성공. CLI 설치 성공이나 모의 응답만으로 실제 연결 성공을 주장하지 않음 |
| A02 | 제공자 결과 신뢰 | JUnit으로 잘못된/누락된 증명·다른 번호·다른 프로젝트 등 구현 계약의 불일치 거부. 브라우저의 verified=true만으로 성공하지 않음. 검증된 번호·인증 시각이 후속 업무 계층에 전달되는 계약 확인 |
| A03 | 만료·재발송·실패 제한 | 명시한 유효 기간의 경계 및 직전/직후, 재발송 시 이전 코드·증명의 처리, 연속 실패 상한·이후 재시도 정책을 결정하고 JUnit/제공자 통합 assertion으로 검증. 에뮬레이터 자체가 보장하지 않는 제한은 구분하고 빠진 검사를 통과로 표시하지 않음 |
| A04 | 번호 정규화 | 국내 입력과 제공자 형식의 정규화가 한 곳에서 일관되며 허용/거부 경계값을 단위 검사. 문자열 표현 차이 때문에 다른 번호를 같은 번호로 잘못 인정하지 않음 |
| A05 | 로컬 전용 경계 | 실제 사용자 환경 설정에서 에뮬레이터·모의 제공자·서명 없는 토큰 사용 거부. 로컬 허용과 비로컬 거부를 각각 실행 가능한 설정/검증 검사로 확인. 공개 데모 및 기존 회원 인증에 연결하지 않음 |
| A06 | UI·오류 및 모듈 경계 | 프런트 단위/브라우저로 최소 제공자 연결 흐름과 실패 표시·재시도 확인. SDK 클라이언트 성공만으로 Java 검증을 우회하지 않음. ArchUnit 경계 유지, 토큰·코드·전화번호의 불필요한 로그/영구 브라우저 저장 방지 확인 |
| A07 | 전체 회귀·보존 | 새 증거 경로의 ./verify.ps1 -Offline에 새 필수 검사를 등록. JUnit·ArchUnit·새 DB 통합·프런트 단위·브라우저·기존 HTTP 검사 및 보존 검사 모두 성공, 필수 누락/skip/실패 없음. 소스 식별과 실제 증거 연결 |

발급·재발송·실패 제한을 위해 필요한 제공자 테스트 상태는 허용하되, 이를 구매 세션·GuestBuyer 영속 업무 모델로 확장하지 않는다. 정책 수치와 제공자/앱 각각의 책임은 구현 기록에 확정해야 한다. 구현이 DB 상태를 사용한다면 새 격리 DB의 실제 제약·동시성 검사가 필요하다.

## 다음 독립 검토의 진입 조건

개발 측이 구현 준비와 제약별 테스트 연결, 개발 전체 실행 결과, 소스 식별을 제공한 뒤 코드와 assertion을 검토한다. 이후 별도의 새 증거 경로에서 전체 하네스를 실행하고 결과·차단·미검증 사항을 이 문서에 추가한다. 설치 실패나 제공자 제약은 증거를 보존하고 대체 경로와 구분한다. 테스트 삭제·필수 목록 축소·타임아웃 확대만으로 실패를 감추지 않는다.

기존 서버·DB·과거 검증 DB·미디어·데모·실패 증거를 보존한다. P05-D의 원인 미확인 Node 실패 기록을 새 성공으로 덮지 않는다. 이번 범위 검토 자체는 기존 서비스나 데이터에 접근·변경하지 않았다. P06-B를 진행하지 않는다.

## 개발 세션의 커밋 직전 재검증 기록
개발 세션에서 사용자 요청에 따라 .local-tools/p06-a-commit-final-20261025의 새 전체 하네스를 실행했다. 종료코드0/status=passed/preservation=passed, Java258·브라우저40·프런트128·Node72. 소스295개는 이 문서의 독립 실행 inputs와 동일하고 실패/관련 증거2162개 해시도 유지했다. 이는 개발 세션의 추가 실행이며 검토 세션이 다시 실행한 것으로 표시하지 않는다. 원인 미확정 실패 및 운영상 제한은 유지한다. 본 검토 문서의 미커밋 변경과 선별 증거도 요청된 최종 로컬 커밋에 포함한다.
