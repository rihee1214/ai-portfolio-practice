# 통합 자동 검증 하네스

2026-10-25 사용자 지시: 하네스를 먼저 준비한 뒤 제약조건을 검토한다. 단위 테스트(JUnit 등)와 아키텍처 테스트(ArchUnit 등)를 포함한다.

## 실행

저장소 루트의 PowerShell 7에서 실행한다. 기존 로컬 JDK·Node·Maven 캐시·Docker PostgreSQL·`.env`가 필요하다.

```powershell
./verify.ps1 -Offline
```

실행마다 `.local-tools/harness-<고유값>`에 새 결과를 만든다. 경로를 지정하려면 `-EvidenceDirectory .local-tools/<새 이름>`을 사용한다. 기존 경로 재사용은 거부한다. `-Offline`은 Maven 다운로드를 막으며 빠른 검사나 검사 생략 옵션이 아니다.

하네스는 다음을 순서대로 실행한다.

1. 소스·검사·하네스 SHA256, 현재 커밋과 미커밋 상태, 기존 환경 보존 기준을 저장한다.
2. 하네스가 누락·실패·skip을 거부하는 자체 회귀 검사를 실행한다.
3. 기존 `backend/verify-db.ps1 -RunBrowser`로 Checkstyle, JUnit Jupiter, ArchUnit, 새 PostgreSQL DB의 통합 검사와 실제 브라우저 검사를 실행한다.
4. JUnit XML에서 각 테스트 실행 결과와 필수 목록을 대조한다. 프로세스 종료 코드만으로 통과시키지 않는다.
5. 프런트 Node 검사, 타입, 린트, Vitest 단위 검사, 빌드를 실행한다.
6. 소스 변경 여부와 기존 DB·컨테이너·리스너·미디어 보존을 비교한다. 실패해도 보존 비교와 최종 결과 기록을 시도한다.

`result.json`의 범위에 맞는 status(`passed` 또는 `selected-passed`), `preservation=passed`와 명령의 정상 종료를 함께 확인한다. `inputs.json`, `selection.json`, `coverage.json`, `java-cases.json`, JUnit XML, 브라우저 JSON, 각 명령 로그가 근거다. 실패 실행의 새 DB와 미디어도 삭제하지 않는다. 결과 경로는 Git에서 제외되므로 완료 기록에는 실행 경로와 요약·필요한 검토 증거를 명시한다. 증거를 외부로 옮기기 전 개인정보와 비밀값을 확인한다.

## 변경 영향·위험과 완료 검증의 구분

2026-11-03 이후 분할·검토·재실행 절차와 양식은 [개발 흐름의 영향·위험 기준](development-workflow.md#변경-영향과-위험에-따른-검증-범위)을 따른다. 과거 실행 판정은 소급 수정하지 않는다.

| 목적 | 진입·필요 증거 | 허용되는 판정 |
| --- | --- | --- |
| 집중 피드백 | 업무 제약의 RED/수정 가설·예상 결과를 정하고 관련 assertion 실행 | 대상 제약의 결과만 기록. 선택 실행의 전체 필수 목록 미충족은 전체 통과가 아님 |
| 중간 독립 검토 | 연결된 업무 묶음 또는 재작업 위험 경계, 대상 소스·관련 증거·새 쟁점·남은 범위 | 경계/지적의 판단. 매 내부 수정에 전체 실행을 요구하지 않으며 단계 완료를 뜻하지 않음 |
| 범위가 명확한 작업 완료 | 변경 도메인+등록된 소비 계약, 필수 assertion, 같은 소스의 독립 영향/선택 적합성 검토와 필요한 검증 | 적절한 범위의 selected-passed·보존 통과·정상 종료와 필수 지적 해결로 해당 작업 완료 가능. 전체 PASS는 아님 |
| 전체가 필요한 변경·마감 | 인증·공통 금융·작업 실행기·공유 스키마·공통 fixture·하네스 등 넓은 실행 동작 변경, 영향 불명확/영향표 누락, 통합 마일스톤 마감·배포 전 | 최종 소스의 새 경로 무선택 Offline 전체 근거 필수. 같은 소스·조건에서 이미 확보한 유효한 전체 근거를 독립 검토나 로컬 커밋 때문에 자동 반복하지 않음 |
| 문서·서식만의 작업 완료 | 실행 동작 영향 없음 확인, 링크·형식·차이와 관련 정책 일치 검사 | 필요한 정적 확인으로 완료, 하네스 미실행 명시. 전체/선택 실행 PASS를 주장하지 않음 |

수정 뒤 실패 검사와 수정 영향 범위를 재검증하고 소스·실행 조건이 유효한 증거만 재사용한다. 독립 검토자는 같은 소스에서 영향·선택 적합성을 확인하고 필요한 범위를 검증하며 개발·독립 전체를 무조건 반복하지 않는다. 소스 변경·새 실패·새 위험 근거가 없으면 이미 통과한 검사를 반복하지 않는다. 단순 로컬 커밋은 전체 실행 조건이 아니다. 필수 assertion/검사 목록 축소·skip 허용·타임아웃 확대만으로 실패를 감추지 않는다. 캐시·의존성 부족은 원인과 미실행을 기록한다.

### 결과 필드와 작업 완료의 구분

| 실제 결과 | 의미·소비 기준 |
| --- | --- |
| mode=full, status=passed, certificationEligible=true | 무선택 전체 실행의 인증 가능 결과. 실제 정상 종료·필수 assertion/coverage·보존 통과까지 확인해야 전체 PASS라고 기록 |
| mode=selected, status=selected-passed, certificationEligible=false | 선택 범위만 통과, **전체 검증 인증 불가**. 영향·선택 적합성과 필요한 독립 검토를 충족하면 해당 범위의 작업 완료 가능. 작업 완료 불가 플래그가 아님 |
| status=failed 또는 선택 범위의 실패/skip·필수 assertion 누락·미해결 필수 결함 | 작업 완료 불가. 미선택 범위를 실패/skip으로 수집한 것과 구분하며 원 실패를 유지 |
| ResourceOnlyDiagnostic=true | 보존 범위를 줄인 제한 진단. false 플래그만 보고 정상 선택 통과로 해석하지 않으며 완료 근거를 대신하지 않음 |

실제 소비 지점: `verification/DbGroups.ps1`의 Resolve-DbGroups가 명시 옵션 유무로 mode/selected/excluded/reasons/certificationEligible을 만든다. `verify.ps1`은 이를 result에 기록하고 선택한 IT의 필수 목록을 투영해 검사한 뒤 정상 선택을 selected-passed로 출력한다. 단위·ArchUnit·프런트 필수 검사는 선택 실행에도 유지된다. `Test-DbGroups.ps1`은 선택 결과가 전체 인증으로 바뀌지 않는지 검증한다. `DatabaseTarget.ps1`의 Assert-FullVerificationAllowed는 이름과 달리 선택 플래그를 검사하지 않고 ResourceOnlyDiagnostic을 차단한다. 현재 저장소 실행 코드에는 certificationEligible=false를 이유로 작업 완료·로컬 커밋을 막는 소비자가 없다. 작업 완료는 문서의 영향/위험·독립 검토 판정이며 새 완료 자동화 필드를 도입하지 않는다.

전체 필요 조건에서 `-ChangedContracts 'schema'`/`'unknown'`처럼 명시 옵션으로 전부 선택하면 여전히 selected-passed다. 전체가 필요하면 처음부터 선택 옵션 없이 실행한다. 등록된 계약의 영향 행 부재는 전체 선택으로 확대하고, 알 수 없는 ID/오타와 선언된 소비 계약 누락은 거부한다. 불명확한 신규 계약을 좁은 선택으로 우회하지 말고 등록을 보완하거나 무선택 전체로 검증한다. 영향표는 수동 선언이며 실제 소비 계약 검토를 대신하지 않는다.

완료 기록에는 소스 식별, 변경 계약·위험, 선택·제외 범위와 이유, 실제 명령·증거 경로·결과, 동일 소스 독립 검토 결과와 재사용 근거, 미검증 범위·실패 이력을 남긴다. 제외한 검사는 통과로 표시하지 않는다. 과거 selected/failed/full 결과는 새 정책으로 재분류하지 않는다.

## 제약조건과 실행 근거

`verification/required-tests.json`은 P04-D까지 기존 JUnit 실행의 클래스·메서드 필수 목록과 P04-E 예약 계약의 신규9개 메서드와 P05-A 공개 탐색의 신규4개 메서드, P05-B 장바구니 규칙2개·DB3개와 P05-C 찜 DB2개 메서드를 포함한다. `required-browser.json`은 브라우저 종류·파일·시나리오 이름, `required-frontend.json`은 프런트 단위 검사 파일·이름을 고정한다. 기존 테스트 삭제·이름 변경으로 검증이 조용히 빠지는 것을 차단한다. 목록은 회귀 검사 목록이며, 그것만으로 모든 요구사항의 커버리지를 입증하지는 않는다. 작업별 제약조건은 아래 형식으로 구체적인 assertion과 연결한다.

| 제약조건 | 자동 검사 근거 |
| --- | --- |
| 다른 모듈 내부 참조 금지, 공개 API를 통한 호출 | `ModuleArchitectureTest#productionClassesRespectModuleBoundaries`, `ModuleRulesTest#otherModuleInternalsAreRejected` |
| platform의 업무 모듈 의존 금지 | `ModuleRulesTest#platformCannotDependOnBusinessEvenThroughApi` 및 실제 클래스 검사 |
| 공개 API 내부 타입·기술 타입 누출 금지 | `ModuleRulesTest#publicApiCannotExposeOwnInternalType`, `jdbcTypesAreRejectedInContractsAndDomain`, `genericJdbcTypeIsNotHiddenByJavaList`, `securityPrincipalCannotLeakIntoApi` |
| 모듈 순환·domain에서 web 의존 금지 | `ModuleRulesTest#cyclesThroughPublicApisAreRejected`, `domainCannotDependOnItsWebLayer` 및 실제 클래스 검사 |
| 상품 소유권·상태·공개 노출 조건 | `PostgresJdbcIT#catalogProductHttpOwnershipLifecycleAndPublicFilters` |
| SKU 중복·최대 개수·동시 변경 충돌 | `PostgresJdbcIT#skuConcurrentChangesDuplicatesAndCapacityHaveOneWinner` |
| 기본 SKU 전환과 활성화의 원자성 | `PostgresJdbcIT#skuDefaultTransitionIsAtomicAndSerializedWithActivation` |
| 이미지 소유권·CSRF·공개 조건 | `PostgresJdbcIT#mediaStoreOwnershipCsrfAndPublicProjectionAreEnforced` |
| 이미지 rollback·삭제 재시도 | `PostgresJdbcIT#mediaRollbackAndCleanupKeepCurrentReferencesAndRetryPartialFailure` |
| 재고 음수·예약량 하한·권한 | `PostgresJdbcIT#inventoryBoundariesOwnershipAndReservedFloorAreEnforced` |
| 재고 동시 요청·멱등성·감사 실패 rollback | `PostgresJdbcIT#inventoryConcurrentKeysHaveOneEffectAndAuditFailureRollsBack`, `inventoryGlobalRequestKeyCollisionRollsBackOtherOwnersBalance` |
| 잠금 대기 후 권한·상태 재검사 | `PostgresJdbcIT#inventoryAdministratorPermissionIsRecheckedAfterProductLockWait`, `inventoryWritesRecheckSuspensionAndArchiveAfterActualLockWait` |

ArchUnit 규칙은 정상 소스 검사뿐 아니라 잘못된 의존을 가진 fixture가 거부되는 검사도 필수다. 업무 규칙은 JUnit/실제 DB 검사, React 상태는 Vitest, 브라우저 연결은 Playwright로 검사한다. 화면 캡처와 육안 확인은 보조 근거이며 권한·동시성·rollback·아키텍처 제약의 증명이 아니다.

## 완료 판정 규칙

- 구현 또는 검토 시작 전에 제약조건 ID·정상/경계/거부/동시성 조건·검사 메서드·기대 결과를 작업 기록에 작성한다. 새 조건은 먼저 실패 재현 검사를 추가한다.
- 검사 구현이 없거나 실행하지 못한 조건은 **미검증/차단**이다. 코드를 읽었거나 화면이 보인다는 이유로 통과시키지 않는다.
- 필수 목록에서 검사를 빼서 통과시키지 않는다. 의도적 계약 변경에 따른 교체는 작업 기록에 이유·대체 assertion·실행 증거를 남긴다.
- 선택/전체 하네스 통과와 별도로 검토자가 변경 영향과 각 제약조건의 assertion 적합성을 확인한다. 총 통과 수가 충분하다는 이유로 요구사항 충족을 선언하지 않는다.
- 실행 동작 변경은 제약조건/검사 매핑과 적절한 선택/전체 결과가 있어야 완료된다. 최종 독립 검토는 같은 소스의 영향·선택·증거를 확인하고 필요한 범위를 검증한다. 문서·서식만의 변경은 위 정적 확인 기준을 따른다.
- 과거 기록은 당시 실행한 범위의 증거로 보존한다. 새 규칙을 소급해 과거에 이 하네스를 실행했다고 기재하지 않는다. P01~P04-D 전체 요구사항의 완전한 재감사와 현재 회귀 검사 재실행을 구분한다.

## 보존과 한계

기존 개발/테스트 DB는 정규화 덤프 해시, 실행용 `app_<32자리 hex>` 데모 DB는 정규화 덤프 해시와 개발 DB 존재 목록, 과거 검증 DB는 존재 목록, 기존 프로젝트 컨테이너는 ID, 알려진 서버 포트(8080/4174/5174/15432/15433)는 주소·포트·PID, `.local-tools/*/media`와 새 하네스의 `.local-tools/*/db/media` 파일은 SHA256을 비교한다. 과거 검증 DB 전체 행과 프로젝트 밖의 임의 미디어 경로를 전수 비교하는 것은 아니다. 실행 중 사용자가 기존 DB/미디어를 변경하면 보존 검사는 실패한다.

브라우저와 프런트 단위 검사에는 필수 시나리오 이름, 현재 기준 최소 개수와 skip/실패 금지 조건을 적용한다. 브라우저의 예상 실패와 재시도 후 통과도 정상 통과로 인정하지 않는다. JUnit 파라미터 검사 목록은 메서드 단위이므로 개별 파라미터의 삭제까지 자동으로 보증하지 않는다. 실제 기기·상용 부하·전체 접근성·실제 주문/결제 연동은 이 하네스의 통과 범위가 아니다. P04-E 예약 모듈 자체의 제약조건·실행 결과·독립 검토 상태는 [작업 기록](work-items/P04-E.md)을 따른다. P05-A 공개 탐색은 [작업 기록](work-items/P05-A.md)과 [독립 검토](work-items/P05-A-review.md)를 따른다. P05-B 장바구니는 [작업 기록](work-items/P05-B.md)과 [독립 검토](work-items/P05-B-review.md)에 별도 실행 증거를 기록한다. P05-C 찜·주문서 준비는 [작업 기록](work-items/P05-C.md)과 [독립 검토](work-items/P05-C-review.md)에 별도 증거를 기록한다. P05-D 입력 훅·키보드 흐름은 [작업 기록](work-items/P05-D.md)과 [독립 검토](work-items/P05-D-review.md)를 따른다. 수량/주소 단위2개 및 375/768/1440px와 로딩/재시도 브라우저8개를 기존 필수 목록에 추가한다. P06은 진행하지 않는다.

## P06-A 제공자 검증 추가 (2026-10-25)
루트 verify.ps1은 verification/Run-PhoneEmulator.ps1을 통해 새 demo 프로젝트와 동적 loopback 포트의 Firebase Auth Emulator를 기동한 뒤 전체 하네스를 수행한다. CLI15.34.1 캐시는 .local-tools/p06-a-provider-probe-20261025/node_modules에 있어야 한다. 캐시가 없으면 명시적으로 실패하며 검사를 생략하지 않는다. 새 에뮬레이터 실행 정보는 .local-tools/phone-emulator-<UUID>에 보존한다.
기존 JUnit/ArchUnit/새 PostgreSQL·미디어/브라우저/프런트/Node 검사는 유지한다. 추가된 PhoneProviderTest10개와 PhoneProviderEmulatorIT1개는 required-tests.json 필수 목록에 있으며, 실제 제공자 브라우저 probe는 IT가 실행해 db/phone-browser.txt에 남긴다. 일반 Playwright report의40개와 별도이며 중복 합산하지 않는다. 프런트 제공자 단위3개는 required-frontend.json, 자원 정리4개는 기존 Node test 파일 패턴에 포함된다.
SDK13.1.0은 브라우저 메모리 persistence, Admin Java9.11.0은 공식 검증/custom token을 사용한다. Firebase의 XML 간접 의존성은 JSON 전용 API 회귀 방지를 위해 제외했다. 실제 문자·구매 세션·GuestBuyer·P06-B는 실행하지 않는다. 상세 제약/실패/결과는 work-items/P06-A.md와 P06-A-review.md를 따른다. 위의 이전 'P06 미진행' 문장은 이전 단계 시점의 기록이다.

## P06-B 구매 업무 검증 추가 (2026-10-25)

후속 사용자 요청으로 구매 세션·인증 시도·이름/전화 화면을 추가했다. 기존 P06-A 절의 '구매 세션/P06-B 미진행'은 당시 범위 기록이다. 새 제약과 결과는 [P06-B 작업 기록](work-items/P06-B.md), [독립 검토](work-items/P06-B-review.md)를 따른다. 현재 하네스는 명시적인 로컬 전화 제공자를 격리 테스트 서버에 연결하여 DB·실제 SDK·업무 API·브라우저를 검사한다. 모든 실행은 새 DB/미디어/증거 경로를 사용한다. 구매 인증은 실제 SMS·실명 확인·주문 소유권이 아니며 P06-C는 진행하지 않는다.

## P06-C 조회 자격 검증 추가 (2026-10-26)

후속 요청으로 직접 정한 조회 비밀번호 준비와 GuestBuyer/조회 세션 계약을 구현했다. 이전 절의 C 미진행은 B 당시 범위 기록이다. 신규 JUnit2·DB9·프런트5·브라우저2를 기존 필수 목록에 더했다. 준비/생성 rollback·경합, 다른 묶음 격리, 세션 만료·version·회전·폐기, 로그인·로그아웃 응답 유실의 assertion 매핑은 [작업 기록](work-items/P06-C.md)을 따른다. 테스트 소스의 c3-fixture로 가상 주문만 생성하며 실제 주문 거래는 P08-C, 변경/복구는 P06-D다. 독립 실행 결과는 [검토 문서](work-items/P06-C-review.md)에 별도 기록한다.

## P06-D 비밀번호 변경·복구 검증 추가 (2026-10-26)

후속 요청으로 조회 세션과 현재 비밀번호를 요구하는 변경/코드 재발급, 별도 RECOVERY 인증·5분 일회성 재설정 권한·전체 세션 폐기를 구현했다. JUnit1·PostgreSQL12·프런트4·PC/모바일 브라우저6을 기존 필수 목록에 추가했다. 실제 SDK probe에는 동일 전화의 이전 교환 token 거부를 추가했다. 제약별 assertion, 실패 재현과 수정, 개발 최종·독립 실행은 [P06-D 작업 기록](work-items/P06-D.md)과 [독립 검토](work-items/P06-D-review.md)를 따른다. 최초 주문 생성은 테스트 전용 fixture 및 내부 계약이며 실제 주문 거래는 P08-C에 남는다. 이전 절의 후속 단계 미진행 문장은 당시 범위 기록이다. P06-E는 수행하지 않는다.

## P06-E 묶음 연결 검증 추가 (2026-10-26)

NEW/EXISTING 명시 선택·기존 조회+현재 구매 인증·동일 번호 검사·기존 자격 불변·revision 충돌·느린 hash 중 변경·조회/구매 잠금 대기 만료·신규/기존 rollback·응답 유실 검사를 추가한다. 기존 필수 목록을 유지하고 JUnit1·PostgreSQL12·프런트5·PC/모바일 브라우저6을 등록했다. 테스트 fixture의 커밋 후503은 성공 응답을 잃는 상태의 모의 검사이며 실제 네트워크 유실과 구분한다. [작업 기록](work-items/P06-E.md)의 실제 실행 결과와 [독립 검토](work-items/P06-E-review.md)를 함께 확인한다. P07-A는 진행하지 않는다.

## P08-E Toss 로컬 계약 검증 추가 (2026-10-28)

기존 필수 목록을 보존하고 JUnit9·새 PostgreSQL5·프런트4·PC/모바일 브라우저4를 추가했다. 기본 Offline 루트는 HTTP stub과 테스트 소스의 가짜 SDK 인증으로 주문/금액/시도 결합·응답 유실 후 서버조회·서명/재조회/중복 웹훅·CSRF·현재 세션·전체예약 원자성을 검사한다. 실제 Toss SANDBOX 실행은 이 결과에 포함되지 않으며, 접근키/MID/API버전/웹훅 보안키가 없으면 [P08-E](work-items/P08-E.md)에 외부 미검증·차단을 남기고 E 전체 완료를 선언하지 않는다. 운영키/실제과금은 사용하지 않는다.

프로덕션 구성은 공식 HTTPS API 주소만 허용하고, 격리 검사 소스의 별도 Settings만 loopback HTTP 제공자 fixture를 주입한다. 실제 제공자 비밀키는 서버 환경 변수, 공개 test clientKey는 권한 있는 준비 응답으로 분리한다. PostgreSQL 로그의 비밀 검사는 새 실행 시간 구간의 합성 fixture 키를 메모리에서만 대조하고 원문 로그/키 대신 구간·개수·통과 여부만 보존한다. 이전 실행 기록은 당시 범위로 유지한다.

## P08-F 실제 주문·결제 이력 조회 검증 추가 (2026-10-28)

P08-E 실제 Toss SANDBOX의 외부 차단은 유지하면서 승인된 DEMO 후속 범위 F를 검증했다. 기존 필수 이름을 보존하고 PostgreSQL8·프런트8·PC/mobile 브라우저6을 추가했다. 회원 서버세션/version과 별도 GuestAccessSession을 사용하는 실제 주문 조회, order행 잠금 대기 뒤 idle/max/current-session 재검사, 양쪽 판매자의 최소 projection/EXISTING_TRADES, 동일시각 커서·원래 금액/단위 스냅샷, 실제 비회원 NEW/EXISTING·reset·탈퇴/동일email재가입의 소유권 격리, 최종 UI publish 경계의 stale 응답 폐기를 검사한다. 결제 이력의 운영 확인 파생 상태는 provider/쓰기 없이 검증한다.

P06의 fixture 소유권 참조 읽기는 `/api/guest/access/references[/{number}]`로 명확히 분리했다. 기존 P06 assertion은 이 경로에서 유지하고 `/api/guest/orders`는 실제 주문 페이지로 사용한다. strict `/api/payments/{number}/history` 조회는 새 브라우저의 조회 자격을 받지만 기존 D 구매브라우저 status/approval 권한을 확장하지 않는다. 실제 배송은 F1/F2 범위다.

개발 최종 `.local-tools/p08-f-dev1-20261028`은 선택 없는 Offline 루트로 Java429·브라우저90·프런트187·Node73 및 보존을 통과했다. 소스453개·실패49431파일의 해시/존재 대조와 실패 이력, 제약별 실제 assertion은 [P08-F](work-items/P08-F.md)에 기록했다. 독립 전체 검토와 최종 F 완료 판정은 부모 검토세션에 남으며 실제 Toss 제공자/실기기/배송 완료를 이 로컬 결과로 주장하지 않는다.
