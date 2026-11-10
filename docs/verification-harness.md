# 통합 자동 검증 하네스

2026-10-25 사용자 지시: 하네스를 먼저 준비한 뒤 제약조건을 검토한다. 단위 테스트(JUnit 등)와 아키텍처 테스트(ArchUnit 등)를 포함한다.

## 실행

저장소 루트의 PowerShell 7에서 실행한다. 기존 로컬 JDK·Node·Maven 캐시·Docker PostgreSQL·`.env`가 필요하다.

```powershell
./verify.ps1 -Offline
```

실행마다 `.local-tools/harness-<고유값>`에 새 결과를 만든다. 경로를 지정하려면 `-EvidenceDirectory .local-tools/<새 이름>`을 사용한다. 기존 경로 재사용은 거부한다. `-Offline`은 Maven 다운로드를 막으며 빠른 검사나 검사 생략 옵션이 아니다.

별도 검증 PostgreSQL 서버를 선택할 때는 `-TestPostgresPort`, `-TestPostgresInstance`(서버의 검증용 UUID), `-TestPostgresContainer`를 함께 지정한다. 호스트는 `127.0.0.1`로 고정하며, 기본 검증 포트15433과 개발 포트15432를 별도 서버로 지정할 수 없다. 컨테이너·포트·인스턴스 식별을 확인하고 자식 검사에도 전달하며, 식별 누락 시 기본 서버로 우회하지 않는다. 서버 선택만으로 검사나 보존 범위가 축소되지는 않는다. 일반 전체 실행은 기존 자원과 선택한 서버를 함께 보존 검사한다.

`-ResourceOnlyDiagnostic`은 원래 DB 덤프 보존을 제외하는 제한 진단용이다. 결과의 `certificationEligible=false`와 제한 보존 범위를 그대로 기록한다. 현재 루트는 마지막 `Assert-FullVerificationAllowed`에서 이 옵션을 거부해 정상 선택/전체 성공으로 마감하지 않는다. 선택 작업 완료를 허용하는 정책을 이 진단 옵션의 성공 허용으로 확대하지 않는다. 도입 근거·실제 서버 식별·실행별 제한은 [P09-E 작업 기록](work-items/P09-E.md)에 보존한다.

하네스는 다음을 순서대로 실행한다.

1. 소스·검사·하네스 SHA256, 현재 커밋과 미커밋 상태, 기존 환경 보존 기준을 저장한다.
2. 하네스가 누락·실패·skip을 거부하는 자체 회귀 검사를 실행한다.
3. `backend/verify-db.ps1`로 Checkstyle·JUnit Jupiter·ArchUnit·공통 build를 한 번 실행하고, 등록된 계약 묶음마다 새 JVM·DB에서 통합 검사를 실행한다. `browser-api` 묶음은 실제 브라우저를 포함한다. 선택 옵션이 없으면 모든 묶음이 필수다.
4. JUnit XML에서 각 테스트 실행 결과와 필수 목록을 대조한다. 프로세스 종료 코드만으로 통과시키지 않는다.
5. 프런트 Node 검사, 타입, 린트, Vitest 단위 검사, 빌드를 실행한다.
6. 소스 변경 여부와 기존 DB·컨테이너·리스너·미디어 보존을 비교한다. 실패해도 보존 비교와 최종 결과 기록을 시도한다.

`result.json`의 범위에 맞는 status(`passed` 또는 `selected-passed`), `preservation=passed`와 명령의 정상 종료를 함께 확인한다. `inputs.json`, `selection.json`, `coverage.json`, `java-cases.json`, JUnit XML, 브라우저 JSON, 각 명령 로그가 근거다. 실패 실행의 새 DB와 미디어도 삭제하지 않는다. 결과 경로는 Git에서 제외되므로 완료 기록에는 실행 경로와 요약·필요한 검토 증거를 명시한다. 증거를 외부로 옮기기 전 개인정보와 비밀값을 확인한다.

### 브라우저 앱 빌드와 실행 — 2026-11-06

P10-A부터 Playwright는 `frontend/auth-e2e/serve-current-bundle.mjs`로 현재 프런트 소스를 개발 모드에서 빌드한다. 실행별 `db/groups/browser-api/browser-app/`을 새로 확보하고 같은 이름의 출력이 있으면 실패한다. Vite preview는 이 디렉터리만 서비스하며 기존 테스트용 `vite.config.ts`의 loopback API 프록시와 요청 경로 검사를 적용한다. 개발 전용 오류 화면·로컬 전화 에뮬레이터·React 개발 모드를 유지한다. 소스 모듈별 전송·HMR은 이 통합 브라우저 실행의 검증 대상에서 제외한다.

단일 worker, desktop/mobile 필수 이름 전체, 개별 timeout과 child 총 390초는 기존 기준을 따른다. 새 번들 빌드와 서버 준비도 child 시간에 포함한다. `browser-app-build.json`에 빌드 모드·시간·엔트리와 파일 크기/SHA256을 남긴다. DB 하네스가 Vite manifest와 실제 출력 파일 집합·해시를 대조하며, Node 회귀는 출력 재사용 거부 및 원본 수정 후 새 빌드 반영을 검사한다. 제품 배포 빌드는 프런트 검증의 기존 `build` 단계에서 별도로 수행한다.

변경 이유와 이전 390초 timeout·진단 한계·실제 브라우저 결과는 [P10-A 실행 기록](work-items/P10-A.md)을 따른다. 브라우저 하네스 동작 변경은 최종 무선택 전체 근거를 요구한다.

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

### 변경 범위의 준비 조건과 UI 집중 검증

2026-11-04 P09-G와 이후 작업에서 새로 작성하거나 수정한 fixture를 점검한다. 준비 시점의 고정 시각을 실제 worker 실행 시점의 현재 시각이나 lease로 재사용하지 않는다. 실제 저장된 due, 현재 token·lease를 검증하는 기존 패턴을 재사용하고, 지연 진입은 임의 sleep이나 timeout 확대 없이 해당 사실과 업무 결과를 assertion으로 확인한다. UUID 정렬·실행 순서·우연히 짧은 문자열에 따라 중요한 조건이 사라지지 않도록 대상 데이터와 선택 조건을 명시한다. P09-F에서 수정·검증한 구현과 유효한 증거는 재사용한다.

UI 변경의 집중 검증은 해당 화면의 긴 상품명·옵션·오류 문구와 좁은 화면에서도 내용과 조작 기능이 유지되는 조건을 포함한다. 필요한 화면은 요청 viewport 폭과 clientWidth·scrollWidth를 같은 관측에서 확인한다. overflow 숨김, 문구·옵션·업무 assertion 삭제로 통과시키지 않는다. 관측하지 못한 과거 실패의 원인을 현재 관측으로 소급 확정하지 않는다.

전체 진입 확인, 실패 분류·가설·영향 재검증과 시간 기록은 [개발 흐름 3절](development-workflow.md#3-한-작업의-반복-흐름)에 통합한다. 새 게이트나 모든 기존 검사의 집중 재실행 의무를 추가하지 않으며, 필수 검사·독립 검토·위험별 최종 전체와 기존 증거·DB·미디어 보존 조건을 유지한다.

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
| 상품 소유권·상태·공개 노출 조건 | `CatalogIT#catalogProductHttpOwnershipLifecycleAndPublicFilters` |
| SKU 중복·최대 개수·동시 변경 충돌 | `CatalogIT#skuConcurrentChangesDuplicatesAndCapacityHaveOneWinner` |
| 기본 SKU 전환과 활성화의 원자성 | `CatalogIT#skuDefaultTransitionIsAtomicAndSerializedWithActivation` |
| 이미지 소유권·CSRF·공개 조건 | `MediaIT#mediaStoreOwnershipCsrfAndPublicProjectionAreEnforced` |
| 이미지 rollback·삭제 재시도 | `MediaIT#mediaRollbackAndCleanupKeepCurrentReferencesAndRetryPartialFailure` |
| 재고 음수·예약량 하한·권한 | `InventoryIT#inventoryBoundariesOwnershipAndReservedFloorAreEnforced` |
| 재고 동시 요청·멱등성·감사 실패 rollback | `InventoryIT#inventoryConcurrentKeysHaveOneEffectAndAuditFailureRollsBack`, `inventoryGlobalRequestKeyCollisionRollsBackOtherOwnersBalance` |
| 잠금 대기 후 권한·상태 재검사 | `InventoryIT#inventoryAdministratorPermissionIsRecheckedAfterProductLockWait`, `inventoryWritesRecheckSuspensionAndArchiveAfterActualLockWait` |

ArchUnit 규칙은 정상 소스 검사뿐 아니라 잘못된 의존을 가진 fixture가 거부되는 검사도 필수다. 업무 규칙은 JUnit/실제 DB 검사, React 상태는 Vitest, 브라우저 연결은 Playwright로 검사한다. 화면 캡처와 육안 확인은 보조 근거이며 권한·동시성·rollback·아키텍처 제약의 증명이 아니다.

## 완료 판정 규칙

- 구현 또는 검토 시작 전에 제약조건 ID·정상/경계/거부/동시성 조건·검사 메서드·기대 결과를 작업 기록에 작성한다. 새 업무 조건은 먼저 실패 재현 검사에 연결한다. 같은 업무 RED가 내부 의존 구현을 포괄하면 구성요소별 중복 RED는 요구하지 않으며 서로 다른 제약의 assertion은 유지한다.
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

## DB 계약 묶음과 선택 실행 (2026-11-03)

이 절은 위 단일 `PostgresJdbcIT` 실행 방식과 현재 경로 안내를 대체한다. 과거 실행의 경로·결과는 변경하지 않는다. [P09-E 후속 개선 기록](work-items/P09-E.md#db-통합-검사-분할선택-실행-개선--2026-11-03)에 이관·측정·실패·검토·인계를 통합한다.

```powershell
# 전체 필요 조건: 선택 옵션을 생략하면 모든 등록 검사와 프런트·브라우저를 실행한다.
./verify.ps1 -Offline
# 개발: 교환 변경의 제공/소비 계약을 영향표로 확대한다.
./verify.ps1 -Offline -ChangedContracts 'exchange'
# 이미 영향 범위를 확인한 집중 피드백: 정확한 묶음만 지정한다.
./verify.ps1 -Offline -DbGroups 'exchange-recovery'
./verify.ps1 -Offline -DbGroups 'database-runtime,job-contracts' -ReverseGroups
# 영향 불명확 시 선택 범위는 전부로 확대되지만 결과는 여전히 selected-passed다.
# 전체 인증이 필요한 마감에는 위의 무선택 명령을 사용한다.
./verify.ps1 -Offline -ChangedContracts 'unknown'
```

쉼표 목록은 PowerShell에서 **따옴표로 감싼 문자열**로 전달한다. `-DbGroups`와 `-ChangedContracts`는 함께 사용할 수 없다. 오타·빈 값·빈 목록 원소·중복은 실행 전에 실패한다. 명시 옵션으로 모든 묶음을 골라도 판정은 `selected-passed`, `certificationEligible=false`다. 전체 인증은 무선택 실행의 `passed`·보존 통과·정상 종료로만 주장한다. 적절한 선택 범위의 작업 완료 가능 여부는 위 결과 필드 기준을 따른다. 선택 실행도 Java 단위·ArchUnit·프런트 단위/Node/type/lint/build를 실행하며, 일반 브라우저154건은 `browser-api`, 실제 phone SDK probe는 `phone-provider`를 선택한 경우에 실행한다. 미선택 검사를 skip으로 수집하지 않는다. 작업에 브라우저/전화 소비 영향이 있으면 해당 묶음을 포함하거나 영향 불명확으로 전체를 선택한다.

### 영향표와 검사 소유권

`verification/db-groups.json`은 묶음 ID·정확한 클래스/메서드·기존 위치·파라미터 실행 수·제공/소비 영향표의 실행 가능한 등록부다. 아래 표의 영향은 파일 경로 자동 추론이 아니다. 변경자가 데이터 소유권, 공개 포트, 트랜잭션과 소비 계약을 확인해 `-ChangedContracts`를 선택한다. 여러 변경 계약은 합집합으로 실행한다. 등록된 직접 영향 행은 후속 소비·복구/브라우저까지 명시한 범위이며 자동 재귀 추론은 하지 않는다.

- 교환은 재고·반품·환불·구매확정·교차 종결·복구·legacy·브라우저를 포함한다.
- 재고·주문·결제·배송·쿠폰 변경은 해당 소비 계약과 교차 업무를 포함한다. 정확한 선택/제외 ID는 매 실행 `selection.json`에서 확인한다.
- 인증(`authentication` 또는 `identity`), 공통 작업 실행기(`job-runner` 또는 `job-contracts`), 공통 금융(`financial-common`), 공유 DB/스키마(`schema`), 공통 검사 도구(`test-tools`), 영향 불명확(`unknown`)은 모든 묶음으로 확대한다.
- 삭제·이름 변경·새 파일도 같은 기준이다. 파일명만으로 검사 생략을 판단하지 않는다. 새 모듈·새 공개 계약은 영향표 등록 전 `unknown`을 사용한다. `-DbGroups`는 영향 분석을 대신하지 않는다.

### 격리·자원 수명

공통 Checkstyle·컴파일·단위/ArchUnit·패키징은 루트 실행당 한 번이다. 각 DB 계약 묶음은 별도 Maven Failsafe JVM, 새 UUID DB, 새 media/증거/보고서 경로를 가진다. 한 묶음의 테스트 클래스가 Spring context와 동적 loopback HTTP/stub 서버를 소유하며 마지막에 종료한다. 공통 `PostgresContractFixture`에는 실행 가능한 테스트가 없고 instance 상태는 JUnit의 메서드별 인스턴스에 남는다. registry/static 외부 모의 제공자 상태는 JVM 밖으로 공유하지 않는다.

경합 상대·blocker·child worker는 같은 묶음의 DB와 작업 상태를 사용한다. child 재시작 때 DB를 새로 바꾸지 않는다. 공통 fixture는 원래 정상 업무 경로를 그대로 호출하며, 실제 COMMIT/rollback·현재 권한·lease/재시작 assertion을 유지한다. 테이블 전체 불변 비교를 대상 주문만의 비교로 축소하지 않았다. 묶음 간 데이터 누적을 제거해 비교 비용을 줄인다.

phone emulator는 루트 실행별 새 demo project·동적 포트다. `browser-api`의 실제 구매 인증과 `phone-provider` SDK probe는 같은 루트 에뮬레이터를 순차 소비한다. 각 시나리오는 새 인증 세션·교환 token을 발급하고 자신의 전화/세션에 한정해 검증하며, 이전 묶음의 발급 결과나 사용자 부재를 준비 조건으로 쓰지 않는다. 같은 전화의 과거 token을 새 교환 증거로 수용하지 않는 assertion도 유지한다. 이 공유의 안전 조건은 순차 실행·새 challenge와 메서드별 귀속이며, 반대 순서 전체 실행의 결과는 작업 기록을 따른다. 서로 다른 루트 실행은 별도 에뮬레이터를 사용한다. 병렬 실행 옵션은 제공하지 않는다. 동시에 실행할 때 DB뿐 아니라 프런트 공유 build 경로·보존 snapshot·제공자 상태의 안전성도 확인해야 하며, DB 격리만으로 루트 전체 병렬 안전성을 주장하지 않는다.

### 새 도메인·검사 등록과 결과 확인

새 `*IT.java`/테스트 메서드는 `db-groups.json`의 단일 소유 묶음, `required-tests.json`, 제공/소비 영향표에 등록한다. 파라미터 검사는 예상 실행 수를 등록한다. 기존 테스트 이동은 `old`→`new` 대응을 유지하며 `db-test-baseline.json`의 기존 필수 ID를 잃으면 실패한다. 공통 fixture에 테스트를 넣거나 미등록 실행 클래스/메서드, 중복 귀속, 누락 필수 검사, 잘못된 실행 수를 허용하지 않는다. 새 업무 제약의 assertion 매핑은 작업 문서에 별도로 기록한다.

실행마다 새 `.local-tools/harness-*` 또는 지정한 새 `-EvidenceDirectory`를 사용한다. `inputs.json`은 미커밋/untracked 소스 포함 SHA256, `result.json`은 전체/선택 판정과 보존·시작/종료, `selection.json`은 선택·제외·근거다. `db/summary.json`은 공통 build 시간과 묶음별 종료/DB/경로/경과시간을 집계한다. 각 `db/groups/<id>/`의 `result.json`, `coverage.json`, `java-cases.json`, `failsafe-reports/TEST-*.xml`, `maven.txt`가 원근거다. 브라우저 결과는 `db/groups/browser-api/browser-report.json`이다. 실패 묶음도 보고서를 남기고 나머지를 실행한 뒤 최종 실패로 집계한다. 이전 결과 XML을 재사용하지 않는다.

`contract-timing.ndjson`은 monotonic 시계로 준비·상태 조회/비교·실행·명시적 대기 helper·프로세스 기동·브라우저를 측정한다. `exclusiveNanos`는 같은 스레드의 중첩 helper 시간을 제외하고 `inclusiveNanos`는 포함한다. 자식 프로세스 본문과 직접 대기 등 미계측 비용은 execution/잔여시간에 남으므로 pure 업무 CPU 시간으로 해석하지 않는다. 클래스 XML 시간과 메서드 시간 합, Maven/JVM·초기화 잔여, 루트 전체 경과, 보존 시간을 구분한다. 서로 다른 조건/반복 수의 결과로 확정적인 속도 배수를 주장하지 않는다.

### 실제 검사 묶음 목록

| ID | 책임 분류 | 기존 메서드 수 / 예상 실행 수 |
| --- | --- | --- |
| payment | cross-domain-contract | 20 / 25 |
| process-recovery | recovery | 12 / 12 |
| browser-api | browser-api | 5 / 5 |
| order-history | domain | 9 / 9 |
| confirmation | domain | 13 / 13 |
| refund | cross-domain-contract | 54 / 54 |
| return-physical | domain | 31 / 31 |
| return-finance | cross-domain-contract | 37 / 37 |
| return-recovery | recovery | 11 / 11 |
| aftercare-contracts | cross-domain-contract | 19 / 19 |
| legacy | migration | 9 / 9 |
| fulfillment | cross-domain-contract | 20 / 20 |
| checkout | cross-domain-contract | 22 / 22 |
| job-contracts | shared-contract | 8 / 8 |
| promotion | domain | 34 / 34 |
| guest | shared-contract | 41 / 41 |
| shopping | domain | 5 / 5 |
| exchange | domain | 35 / 35 |
| exchange-finance | cross-domain-contract | 17 / 17 |
| exchange-recovery | recovery | 5 / 5 |
| catalog | domain | 23 / 23 |
| inventory | domain | 15 / 15 |
| media | domain | 8 / 8 |
| seller-store | domain | 9 / 9 |
| identity | shared-contract | 36 / 36 |
| database-runtime | shared-contract | 12 / 12 |
| phone-provider | shared-contract | 1 / 1 |

변경 영향 행에는 준비 업무가 소비하는 계약도 포함한다. `provides`/`consumes` 연결에 선언된 소비 묶음이 영향표에서 빠지면 자동 검사가 실패한다. 아직 완전한 영향 행이 없는 등록 묶음을 `-ChangedContracts`로 지정하면 전체로 확대한다. 이는 복잡한 파일 분석 없이 명시 표의 누락을 거부하기 위한 보수적 규칙이다. 거래 계열 변경은 정상 선행 주문/결제와 교차 금액·복구 계약 때문에 비교적 넓게 선택되며, 가설이 좁고 소비 계약을 이미 검토한 경우 정확한 `-DbGroups` 집중 실행을 사용한다.
에뮬레이터 인증 저장소는 루트 안에서 공유된다. 현재 제공자 probe는 OTP를 정확한 sessionInfo로 찾고 성공 교환에서 소비하지만, 일부 일반 브라우저 helper는 phoneNumber 첫 일치 OTP를 찾는다. 같은 번호의 미소비 OTP를 겹치게 하는 새 검사, 병렬 실행, 전체 사용자 초기화, 기존 사용자 부재/개수 전제를 추가할 때는 정확한 session 식별 또는 번호/namespace 분리가 필요하다. 현재 수용 조건과 정/역순 실행 근거는 작업 기록에 남긴다.

### 보조 DB 소유권 검사 보완

각 묶음의 `expectedDatabaseCount`도 등록한다. 일반 DB 묶음은1, phone-provider는0, 현재 legacy는 주DB1+기존 이행 비교DB13=14, inventory는 주DB1+V17 업그레이드 보존 비교DB1=2다. 보조 DB는 해당 묶음만 소유하며 모두 새 UUID로 생성·보존한다. 주DB 생성 정확1회, 전체 생성 수, 이름 허용식, 중복 없음과 실행 identity를 자동 대조한다. `result.json.databaseProof`의 created/auxiliary가 실제 생성 목록이다. 검사에 새 이행 DB가 필요하면 소스의 생성 근거와 예상 수를 함께 갱신한다. 실패를 숨기기 위해 기대 수를 낮추거나 기존 DB를 재사용하지 않는다.


계측 해석: 실제 브라우저 실행시간은 `browser-report.json.stats.duration`이다. `runAuthBrowsersIfRequested`의 inclusive 시간에는 준비·자식 프로세스 대기가 들어가며, 그 내부 `process-wait`는 exclusive 집계에서 별도 분류된다. 따라서 `browser` exclusive 값만 브라우저 총시간으로 보고하지 않는다. 실제 프로세스 대기는 JVM 기동/child 업무 실행도 포함하므로 순수 sleep 시간이 아니다. 초기화 값은 성공한 DB/context refresh 구간이며 별도 child 초기화는 child 실행/대기에 남을 수 있다. 증거 쓰기 시간은 부모 구간과 겹치는 진단값이고 root before/after 보존 시간과 별개다. 스레드 간 중첩이 있을 수 있어 모든 phase 합을 루트 경과시간으로 대체하지 않는다.

현재 등록된 묶음은 각각 한 실행 클래스다. 새 도메인도 데이터·트랜잭션·복구 계약에 맞는 실행 범위를 먼저 정하고 등록한다. 여러 클래스를 한 묶음에 합치거나 병렬로 바꿀 때는 fixture의 AfterAll 종료, Spring context와 static 상태 수명을 다시 검증해야 한다. 현재 순차·단일 클래스 묶음의 결과를 그 변경의 안전성 증거로 사용하지 않는다.
