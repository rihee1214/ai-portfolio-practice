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

`result.json`의 `status=passed`, `preservation=passed`와 명령의 정상 종료를 함께 확인한다. `inputs.json`, `coverage.json`, `java-cases.json`, JUnit XML, 브라우저 JSON, 각 명령 로그가 근거다. 실패 실행의 새 DB와 미디어도 삭제하지 않는다. 결과 경로는 Git에서 제외되므로 완료 기록에는 실행 경로와 요약·필요한 검토 증거를 명시한다. 증거를 외부로 옮기기 전 개인정보와 비밀값을 확인한다.

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
- 전체 하네스 통과와 별도로 검토자가 각 제약조건의 assertion 적합성을 확인한다. 총 통과 수가 충분하다는 이유로 전체 요구사항 충족을 선언하지 않는다.
- 새 작업은 제약조건/검사 매핑과 하네스 실행 결과를 포함해야 완료된다. 독립 검토도 별도 새 결과 경로에서 동일 하네스를 실행한다.
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
