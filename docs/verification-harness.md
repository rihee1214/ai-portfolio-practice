# 통합 자동 검증 하네스

## 성능 후속 실행 계약

사용자 승인 후속 구현은 `./verify.ps1 -Offline`의 DB 묶음을 기본 `-GroupConcurrency 2`로 실행한다. 진단·비교용 `1`을 지원하고 다른 값·소수·2초과는 생성 전에 거부한다. 사전 검사·phone emulator 재진입·backend에 같은 값을 전달하고 준비 근거에 결합한다. 기존42개 묶음·정확한 필수 이름·매개변수 호출 수·새 DB/media·권한/금액/복구 assertion과 업무별 공유10초는 유지한다. 선행 재사용·동시2 구현은 전체2회로 수용됐으며, 추가 우선순위 개선의 실행·수용·성능 판정은 [최신 작업 기록](work-items/verification-performance.md)을 따른다. 과거 측정은 새 소스의 통과 근거로 바꾸지 않는다.

`db-groups.json.executionResources`와 각 묶음의 `execution`은 영향표와 별개인 실행 분류다. `public-demo-runtime → release-recovery`의 성공 검증 의존성과 root `package-runtime → container-reproduction`을 유지한다. 실제 Docker/전역 관측·과거 릴리스의 `release-recovery`·`package-runtime`·`release-upgrade`는 전역 단독이다. 지정된6개 실제 fault 묶음은 `fault-injection` 자원을 공유하며 다른 fault와 겹치지 않는다. 일반 browser와 site2는 `browser-preview-24176`, browser/phone-provider는 `firebase-auth-project`를 공유한다. 분류 누락·중복·알 수 없는 자원은 생성 전에 거부한다.

부모가 공통 Java build·JUnit/ArchUnit을 한 번 끝낸 뒤 현재 production bundle을 한 번 생산한다. 각 PowerShell7.6.9 worker는 비밀을 request 파일에 쓰지 않고 별도 환경으로 전달받으며, UUID DB/media/evidence·java tmp·report·Maven build/Failsafe fork scratch를 분리한다. 측정된 compiled tree 복제 비용(선택2의 약2.9초/묶음)을 줄이기 위해 classes/test-classes/JAR는 같은 실행의 공통 산출물을 읽기 전용으로 소비한다. 로컬 Failsafe3.5.6 descriptor에서 확인한 `classesDirectory`·`testClassesDirectory`만 명시하고 private build/tmp/report는 유지한다. `CompiledBuild.cjs`가 source/tool과 정확한 전체 compiled inventory·해시를 생산 직후, **매 worker 입장 전**, 실패를 포함한 최종 `finally`에서 대조한다. worker는 manifest/JAR 해시와 실제 JUnit classpath의 공통 경로·private compiled copy 부재를 확인한다. 초기 관리자 명령의 출력 파일도 실제 `A3_EVIDENCE/java-tmp` 내부임을 assertion으로 확인한다. 이전 선택2의 복제 산출물과 결과는 원 이력으로 보존한다.

부모는 엄격한 최종 JUnit·필수 ID·호출 수·DB 개수/중복·교차 browser 격리를 다시 대조한다. 종료 코드0이나 폴더 존재만으로 producer를 통과시키지 않는다. 실패한 producer의 소비자는 `blocked`, 미실행/취소는 명시 비통과로 남기고 독립 묶음의 결과를 보존한다. worker는 native 작업 전에 새 UUID Windows JobObject에 자신을 연결하고 실제 membership/request 증거를 남긴 뒤 부모 admission을 기다린다. handle은 비상속이며 `KILL_ON_JOB_CLOSE`만 설정하고 breakaway를 허용하지 않는다. 정상 종료 원근거를 쓴 뒤 OS 종료까지 handle을 유지해 비정상 부모 종료에도 자손 소유가 유실되지 않게 한다. 부모 정리는 유일한 소유 job의 kernel membership과 유한 종료/관측으로 확인하며, PID 추측·호스트 전체 kill을 사용하지 않는다. 정리 거부/정체 시에도 유한 시간 안에 실패 원근거·`unresolvedOwnership`·`unreleasedClaims`를 남기고 새 admission을 중단한다. attachment 충돌은 native 업무 전에 거부하고 기존 job을 종료하지 않는다. 전체·시나리오 총 timeout은 추가하지 않는다. 공통 compiled hash 준비/입장/최종 비용, worker binding 및 job attachment 비용도 결과에 별도 측정한다.

실제 Failsafe classpath의 빈 component는 Java의 현재 작업 디렉터리를 뜻한다. reader는 실제 `user.dir`와 빈 component 수를 기록하고 명시된 두 공통 compiled 디렉터리·private copy 부재만 assertion으로 대조한다. 전체 classpath 동일성을 주장하거나 빈 문자열을 `GetFullPath`에 넘기지 않는다. 선택3에서 보존된 원 XML13/8/37개는 Java 검사 자체는 통과했고, 당시 worker 후처리는 trailing separator의 빈 component 처리 오류로 실패했다. 수정된 reader의 새 fixture와 원 XML 읽기 전용 재처리는 해당 후처리 계약의 근거이며, 당시 실패 root를 통과로 바꾸지 않는다.

production byte 재사용은 같은 실행의 source/config/lock/tool/mode/환경·파일 해시를 확인한 fresh copy에만 적용한다. package/release/root frontend는 각자 새 경로를 소비하며 development bundle·역사 산출물·세션/DB·과거 검사 결과를 재사용하지 않는다. root 프런트 검사는 검증된 `frontend-production` 복사본을 preview에 전달하고 검사 뒤 다시 해시를 대조한다. production 준비 비용도 root 측정 구간 안에 포함한다.

기존99개 Node 필수 이름에 scheduler7·production bundle5·compiled sharing/classpath5·worker job lifecycle5를 더한 선행121개를 사전 수집한다. 이전115개를 보존하고 공통 compiled source/tool/바이트·누락/추가·manifest 변조/다른 실행 경로 거부, worker 생성 전 변경 거부와 실패 원근거 보존을 추가 확인한다. 실제 dummy7.6.9 두 worker의 중첩·환경/경로/DB 식별/포트 분리, 실패/위조 producer 거부, 소유 자손 취소와 무관 sentinel 보존, 미실행 필수 목록 보존을 검증한다. 새 lifecycle 검사는 실제 비정상 부모 종료 후 열린 kernel job handle로 소유가 유지된 child/grandchild 정리, 기존 충돌 job 보존, 정리 거부/정체의 유한 실패와 미해제 소유 기록을 확인한다. `DatabaseRuntimeIT#batchedParticipantSnapshotsPreserveEveryTableAndValue`의 invocation1도 기존 DB 묶음에 추가하며 기존 필수 ID를 줄이지 않는다. 실행 이벤트는 `db/scheduler-events.ndjson`, 작은 현재 상태는 `db/scheduler-progress.json`, 최종 결과는 `db/scheduler-result.json`에 남긴다.

추가 성능 개선의 PERF2-IR1 보완은 위121개를 유지하고 `Test-FrontendNodeCoverage.cjs`의 필수7개를 더해128개를 등록한다. 별도의 frontend Node 실행은 기존 `tests/*.test.mjs` glob을 한 번만 실행하면서 spec/JUnit 보고서를 함께 남긴다. Node 인수는 glob 앞에 두며 `FrontendNodeCoverage.ps1`이 `required-frontend-node.json`의 정확한5개 파일/이름/1회 실행과 전체 보고서의 실패·오류·skip/TODO 부재를 검사한다. 보고서 누락·빈 내용·잘못된 XML·중복·다른 파일 귀속은 실패다. `frontend-node-tests.xml`은 Vitest의 `TEST-*.xml` 스캔에서 제외되어 기존 프런트427 집계를 늘리지 않는다. 기존101개 Node 검사는 그대로 실행하며 새 필수 매핑5개만 실행하도록 축소하지 않는다. 후속 포트 관측 guard7을 더한 현재 필수 등록은135개이며 기존128개를 유지한다. 실제135/101 전체 실행 판정은 최신 작업의 전체 근거를 따른다.

릴리스 검사의 서버 포트는 Windows 호스트뿐 아니라 Docker Linux 호스트 네임스페이스의 동적 클라이언트 포트 범위와도 분리한다. `release-recovery`가 선택된 preflight는 필요한 시스템 도구와 이미 실행 중인 Docker Desktop VM·`nsenter`의 가용성을 먼저 확인한다. 실제 IPv4/IPv6·dockerd 네임스페이스의 범위는 릴리스 검사 시작 때 한 번 관측하며, preflight의 가용성 결과나 과거 `.local-tools` 관측을 현재 범위 증거로 재사용하지 않는다. 잘못된·모호한 관측이나 빈 후보는 실패다. 기존20000~65535 후보에서 관측된 범위의 합집합을 제외하고, 이미 할당한 포트와 실제 bind로 점유를 거른다. 각 대상의 포트는 R0/R1 교체 동안 고정하며100회 시도 제한을 유지한다.

이 보완은 테스트 환경의 할당 위험 완화다. final1의 과거 바인딩 충돌 소유자는 미확정으로 보존한다. 운영 Activate/quiescence·실패 시 닫힘·업무 공유10초를 바꾸거나 timeout 확대·충돌 명령 재시도를 하지 않는다. 도구 설치·VM 명시 시작·네트워크 설정 변경·기존 자원 종료·기본 범위 추정은 하지 않는다. running 확인과 WSL exec 사이의 TOCTOU를 원자적으로 제거했다고 주장하지 않는다. 현재 Windows/Docker Desktop 전제의 가용성·실제 범위·유한한 준비/자식 명령·실행 증거는 최신 작업 기록에서 별도로 확인한다.

기준 root3785.0445586초와 launch→exit3841.4221501초는 서로 다른 측정 경계다. [읽기 전용 기준 분석](../.local-tools/perf-baseline-4015e6e3-fea3-4bfb-81d7-37ce97f99386/analysis.md)은42묶음3450.071초·공통 build79.893초·보존23.342초를 구분한다. 이상적인2-slot만으로도 root 하한2060.009초이므로 절반 목표1892.522초 달성을 미리 주장하지 않는다. 실제 실행의 준비·산출물 생산·자원 압력과 모든 실패를 측정하며, 목표 미달을 숨기려고 assertion/기한/격리를 완화하지 않는다. 아래 P12-D/E 기록은 당시 수용 이력이다.

## P12-D/E 실행 준비 후속 — 현재 구현 계약

루트는 명시된 `C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe` 안정7.6.9의 실제 실행 경로·버전·Core edition과 Windows 호환 세션0을 검사한다. 전역 PATH/설정 변경이나 이전 PowerShell 자동 대체는 하지 않는다. `powershell.json`, `powershell-processes/`와 E의 실제 네트워크 자식 기록을 증거에 남긴다.

`verification/Preflight.ps1`은 emulator·통합 DB·Docker 자원 생성 전에 새 `<EvidenceDirectory>-preflight/`를 만든다. 현재 PowerShell/CJS/MJS 구문, 명시 도구·캐시·과거 산출물 존재, 프런트 타입/lint, 선택된 실제 Playwright `--list`의 프로젝트·파일·제목 ID와 기존 필수 JSON을 대조한다. 수집용 loopback/합성 계약은 서버를 시작하거나 검증할 업무를 실행하지 않는다. E가 선택되면 기존 cache guard와 서브넷 allocator로 로컬 고정 이미지 및 Docker IPAM/호스트 경로의 비중첩 가용 주소도 생성 전에 확인한다. 자원 관측은 기존 `snapshotDockerResources`의 안전한 projection·정확한 ID 대조·최대20개 inspect batch를 재사용하며 필수 ID를 빠뜨리지 않는다. 실제 E 단계는 생성 직전에 다시 관측해 환경 변화에 대응한다. 현재 소스·선택·도구 경로/명령·각 결과는 preflight manifest에 묶으며 자식 루트 재진입 시 같은 통과 소스만 재사용한다. `Offline`·역순 실행·진단 옵션·해결된 PG endpoint와 선택된 과거 JAR/proof/dist의 명시 경로·파일 해시·정확한 dist 파일 목록도 시작/종료/재진입에서 대조한다. emulator가 새로 초기화한 Firebase/GCLOUD 값이나 비밀 내용은 이 불변 준비 계약에 넣지 않는다. 네이티브 명령의 stdout이 비어도 새 UTF-8 로그를 먼저 생성해 루트 복사 소비가 실패하지 않게 하며, 원 실패 exit와 stderr는 보존한다. 실패 경로는 보존하고 재시도는 새 경로를 쓴다. 타입/lint의 동일 소스 근거를 DB 실행 뒤 중복 실행하지 않는다.

Node guard 실행 파일과 정확한 필수 이름은 `required-container-reproduction-guards.json`의 `files`·`tests`에 함께 등록하며 실행과 XML assertion이 같은 등록을 소비한다. 기존77개를 보존하고 runtime8·preflight9·E runtime/source mutation5를 더한 현재99개를 필수 대조한다. runtime8은 실제 PowerShell→자식→leaf의 산출물 빌드 제한·부분 UTF-8 출력·소유 자손 종료도 포함한다. preflight9는 무출력 성공 로그의 루트 복사 소비, 실패 exit/UTF-8 보존, 소스가 같아도 실행 설정·과거 산출물 변경 거부, 47개 네트워크의20/20/7 batch와 정확한 전체 ID·비밀 제외, 과거 런타임 의존 파일의 생성 전 누락 거부·내용 변경 시 근거 재사용 거부를 포함한다. 누락·중복·실패·skip은 생성 전 차단한다. 이 절은 구현 계약이며 통과·최종 수용은 [후속 작업 기록](work-items/P12-DE-execution-hardening.md)의 실제 소스와 근거를 따른다. 아래 기존77개 설명과 당시 결과는 과거 이력이다.

`release-recovery`를 선택하면 `ReleaseRecoveryIT`와 동일하게 R0 JAR의 상위3단계에서 과거 sourceRoot를 구한다. 수정된 `infra/p12-d/payload.cjs`가 내보내는 `runtimeFiles`의 과거7파일을 읽기 전용으로 확인하고 경로·SHA256·크기를 준비 근거에 포함한다. 현재 컨트롤러의 PowerShell helper를 과거 소스에 요구하거나 복사하지 않는다. 필수 파일 누락은 preflight 명령 실행 이전에 거부하고, 같은 현재 소스/JAR/dist/proof라도 과거7파일이 바뀌면 재진입 근거 재사용을 거부한다. 과거 런타임7과 현재 컨트롤러18의 소유 구분 및 실제 payload 검사는 별도 `required-release-operations.json`의18개 필수 이름으로 검증한다.

`verification/Get-VerificationProgress.ps1 -EvidenceDirectory <경로>`는 루트/DB 묶음 결과와 E의 마지막 진행 이벤트만 읽어 현재 phase·완료/실패를 요약한다. 로그 전체나 과거 증거를 재귀로 반복 탐색하지 않는다. 업무별 공유10초 및 기존 유한 준비/정리 제한은 유지하고 전체·시나리오 총 제한은 추가하지 않는다.

P12-E 1차 필수 보완 후 현재 Node 필수 목록은 **77개**(root proof42·기존 준비물 guard5·보존 guard22·cache guard8)다. 과거56개 실행 근거는 당시 소스로 보존한다. `cached-images.test.cjs`를 기존3파일과 함께 루트 JUnit에 수집해 기존56개를 유지한 정확한 이름·실패/skip을 대조한다. 새 gate는 동일 신청 ID/신청자의 APPROVED DB 상태와 신청자 PENDING/운영자 APPROVED audit·정확한 사유, 고정 이미지의 로컬 digest 존재·불변 FROM ID·빌드 후 base layer를 확인한다. 실제 Docker 변경 전 cache 누락 시 build/pull/resource callback이 실행되지 않는 자동 guard가 있다. 현재 E 소스 복제는 새 helper/test를 포함한13파일이며 과거 reproduction14의11파일·화면 출처는 변경하지 않는다. 수정 전 전체와 수정 후 선택·독립 판정은 [P12-E 기록](work-items/P12-E.md)의 각 소스/시점으로 구분한다.

P12-E는 `container-reproduction`을 **루트 전용 계약 phase**로 등록한다. 기존42개 JVM/IT 묶음과 그 DB 개수는 유지한다. 이 phase는 같은 루트 실행의 `package-runtime`이 생산한 JAR·production dist·출처만 명시 입력으로 소비한다. 무선택 전체와 `package-runtime`을 포함하는 선택 실행에 필수이며, `-ChangedContracts 'container-reproduction'`은 producer를 선택한다. 다른 정확한 선택은 `selection.json.rootPhases.excluded`에 제외를 기록한다. 제공/소비 계약과 영향표는 `verification/db-groups.json.rootPhases`에 있으며 미등록 phase·중복·producer 누락·소비 영향 누락은 자원 생성 전에 거부한다. 공유 하네스 실행 변경이므로 이번 변경의 최종 근거에는 새 무선택 전체가 필요하다.

`container-reproduction/`은 새 Compose 프로젝트, PostgreSQL 컨테이너의 새 앱 DB1개, 새 DB/media 볼륨과 전용 네트워크2개를 보존한다. 이 DB는 backend IT DB 총계에 합산하지 않고 `result.json.rootPhases.container-reproduction`의 DB 이름·컨테이너·볼륨 ID로 구분한다. migration131개/latest132/실패0, before에 없던 컨테이너/볼륨, API/worker 동일 JAR 이미지·worker mount 없음, 기존 Docker 자원 동일성을 관측 자료로 대조한다. 단순 `existingDatabaseUsed=false` 선언이나 health만으로 통과하지 않는다.

새 브라우저 필수 이름은 `required-browser-container-reproduction.json`의 desktop/mobile **정확한2개**다. 루트는 실제 Playwright 결과의 실패/skip/retry와 이름·프로젝트·중복, 기존 공유10초 업무 deadline 원장을 대조하고 `browser-coverage-container-reproduction.json`에 기록한다. 같은 주문 ID의 UI→API→PG→worker/판매자 배송→구매자 재조회, 금액13000원·PAID·COMMITTED·SHIPPED와 같은 결제 attempt의 승인 효과1회/성공 event1회를 두 프로젝트에서 각각 확인한다. 결제 관측은 이4개 필드의 정확한 전체 객체를 대조한다. 종료 후에는 모든 소유 컨테이너의 exited/running=false와 paused/restarting/OOM/dead=false를 확인하고, 서비스의 정상/SIGTERM 종료0 또는143·준비 helper의 종료0을 대조한다. 정리 오류 배열만으로 종료를 수용하지 않는다. `container-reproduction-proof.json`은 같은 실행 artifact/source 해시·관측 DB/볼륨·보존 원근거의 교차 검증이다. 원 화면·명령·실패와 기존 자원을 지우지 않으며 E의 공개/실결제 제한은 [P12-E 기록](work-items/P12-E.md)을 따른다.

1차 보완 전 root guard는 `Test-ContainerReproduction.ps1`과 `required-container-reproduction-guards.json`의 Node56개였다. 당시 Node는 root proof29개와 `infra/p12-e/guards.test.cjs`5개·`preservation.test.cjs`22개를 같은 필수 JUnit 보고서로 수집해 이름·개수·실패/skip을 대조했다. 현재77개는 이56개를 모두 유지한다. 누락/중복 phase·잘못된 producer/영향표, 한 viewport 누락/skip·deadline 누락, stale artifact/source·누락/추가 dist, 잘못된 DB/migration/금액/주문, worker media mount·기존 volume·이미지 불일치·기존 자원 변조를 거부한다. 명시 준비물/새 경로·주소 충돌 회피·비밀을 담지 않는 관측, bounded inspect batch·불완전/중복 관측 거부와 기존 자원 변조/삭제·미소유 추가 거부도 필수다. 실행 결과는 `container-reproduction-gate-tests.txt`, `container-reproduction-guard-tests.xml`, `container-reproduction-guard-coverage.json`에 남긴다. 이 등록 설명은 실행 통과 선언이 아니며 실제 새 전체·실패 이력과 독립 판정은 작업 기록에서 확인한다.

P12-B는 전역 배송 정책을 변경하는 유지보수 시나리오를 `site-maintenance-desktop`·`site-maintenance-mobile` 두 묶음으로 등록한다. 각 묶음은 별도 IT/JVM·새 DB/media·API·브라우저를 사용하고 migration 초기 정책에서 시작해 변경 후 상태를 보존한다. 기존 `browser-api`의178개와 초기 정책/seed 계약은 유지하며 새4개는 `verification/required-browser-site-maintenance.json`과 명시 단일 project로 대조한다. 두 묶음은 무선택 전체에 필수 포함되고 등록된 소비 영향표를 따른다. 선택 예시는 `-DbGroups 'site-maintenance-desktop,site-maintenance-mobile'`이며 선택 통과를 전체 인증으로 표시하지 않는다. 소스별 실행·독립 판정은 [P12-B 기록](work-items/P12-B.md)을 따른다.

브라우저 묶음별 `db/groups/<id>/` 아래 bundle·report·deadline 원장·자원 식별을 보존한다. 기존 루트 `browser-coverage.json`은 유지하고 새 묶음은 `browser-coverage-site-maintenance-desktop.json`·`browser-coverage-site-maintenance-mobile.json`에 별도 필수4개 중 해당project 결과를 기록한다. 새 시나리오도 아래 업무별 공유10초와 유한 준비·정리·watchdog을 소비하며 총 제한이나 실행 병렬화를 추가하지 않는다. 아래 과거 시점의 필수 개수와 실행 결과는 당시 이력이다.

2026-11-14 사용자 승인 후속 변경 **로컬 수용·인계 완료**: 브라우저 child390초/시나리오30·60초 총 제한을 업무별 공유10초 deadline과 유한 infrastructure 단계 감시로 대체한다. 준비 완료는 실제 API/DB/화면 조건으로 확인하며, 검증대상 명령은 준비로 제외하지 않는다. 같은 업무의 클릭·응답·재조회·폴링·최종 assertion은 하나의 기한을 공유하고 기존 더 짧은 개별 계약은 유지한다. 준비60초·정리10초·서버기동30초·연결/요청 제한과 외부 Java watchdog을 결합한다. 아래390초 설명은 이전 실행 정책이며 과거 판정은 바꾸지 않는다. 새 무선택 전체와 소비 준비 경계 보완 후 선택 재검증의 결합을 독립 수용했으며 최종 manifest 자체의 무선택 전체 PASS는 아니다. 최종 근거·정확한 범위는 [P12-A 후속 기록](work-items/P12-A.md)을 따른다.

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

단일 worker와 desktop/mobile 필수 이름 전체는 유지한다. P10 당시에는 새 번들 빌드·서버 준비를 포함한 child 총390초와 시나리오 총 제한을 적용했다(이전 정책). P12-A 후속 승인부터는 이 총 제한을 제거하고 준비 완료 뒤 각 업무의 명령·응답·재조회·최종 assertion이 하나의10초 deadline을 공유한다. 준비·기동·연결·요청·정리는 별도의 유한 제한과 외부 watchdog으로 감독한다. `browser-app-build.json`에 빌드 모드·시간·엔트리와 파일 크기/SHA256을 남긴다. DB 하네스가 Vite manifest와 실제 출력 파일 집합·해시를 대조하며, Node 회귀는 출력 재사용 거부 및 원본 수정 후 새 빌드 반영을 검사한다. 제품 배포 빌드는 프런트 검증의 기존 `build` 단계에서 별도로 수행한다.

변경 이유와 이전 390초 timeout·진단 한계·실제 브라우저 결과는 [P10-A 실행 기록](work-items/P10-A.md)을 따른다. 브라우저 하네스 동작 변경은 최종 무선택 전체 근거를 요구한다.

### P12-D 릴리스·복구 검증 — 격리 로컬 수용

`release-upgrade`는 고정 P11-D H129 산출물을 실제 실행하여 V129→V132 이행과 과거 API 소비를 검사한다. `P12_D_H129_JAR`, `P12_D_HISTORY_PROOF`는 보존된 과거 산출물과 commit/JAR 출처를 가리켜야 한다. 현재 API 또는 SQL 결과만으로 대체하지 않으며 산출물 부재는 필수 차단이다. `release-recovery`는 `P12_D_R0_JAR`·`P12_D_R0_DIST`와 현재 후보를 실제 교체하고 새 DB/media 복원을 검사한다. R1은 제품 API가 같을 수 있는 운영 패키지 릴리스이며 실제 고정 payload 소비·프로세스·pointer·업무 결과를 확인한다. ZIP timestamp 차이는 버전 변경 근거가 아니다.

L의 local-full/local-test 비회원 성공 경로와 P의 초대 제한·개별 로그인 public-demo 경로를 구분한다. P의 비회원503·DB 무효과는 실제 P 경로에서 관측하고 제공자 무호출은 같은 제품 클래스에 결합된 `public-demo-runtime` 근거와 대조한다. 해당 연결 선택 검사에는 두 묶음을 함께 지정한다. 각 소유 DB와 실패 대상까지 필수 보조 DB 수에 등록하며, 의도한 거부도 정확한 명령·원인·무효과 assertion이 있어야 한다. 임의 실패 무시는 허용하지 않는다.

운영 phase와 브라우저 사용자 업무 시간을 구분한다. 사용자 명령/응답/재조회/최종 assertion은 기존 공유10초를 따르고, 준비·기동·연결·종료는 유한 phase와 소유 자식 정리로 제한한다. 전체 또는 시나리오 총 제한은 추가하지 않는다. 이 설명은 실행 통과 판정이 아니며 최신 소스·명령·선택/전체·실패·독립 판정은 [P12-D 기록](work-items/P12-D.md)을 따른다. 인증·공통 실행·복구 통합 변경의 새 무선택 full3 전체 근거를 확보했다. 이후 테스트 전용 관측4파일만 변경하고 browser-api 선택 검증을 통과해 동일소스 독립 영향 수용으로 결합했다. 최종 manifest 자체의 무선택 전체 PASS와 구분하며 구체적인 입력 해시·근거·외부 미검증은 P12-D 인계를 따른다.

### P12-C 실제 패키지 검증 — 로컬·모의 수용

P12-C는 기존 브라우저 묶음을 유지하고 `package-runtime`과 `public-demo-runtime`을 추가한다. 전자는 같은 실행 JAR의 API/non-web worker, 새 PostgreSQL DB와 media, 새 production dist, 실제 TLS NGINX를 연결한다. 이전 P12-B의 보존 번들을 별도 새 경로로 복제해 새 API와의 세션·오류 호환성도 검사한다. 구형 번들은 당시 development build이고 현재 production build와 구분한다. 테스트용 읽기 관측 서버는 별도 loopback 프로세스이며 제품 JAR나 공개 프록시의 API가 아니다. 단독 proxy 경계 검사는 실제 NGINX와 제어된 upstream을 사용하므로 제품 DB 장애 실행으로 확대 해석하지 않는다.

`public-demo-runtime`은 공개 모드의 실제 세션·CSRF·미지원 발급/SANDBOX 거부와 DB/명시 제공자 무효과를 검사한다. 하네스의 로컬 전화 에뮬레이터 환경은 이 묶음의 자식 JVM에서만 제외하고 이후 원복한다. public-demo의 금지 설정 가드를 완화하지 않는다. 공통 Java 임시 파일은 실행별 evidence의 새 `java-tmp` 경로를 사용하며 과거 실패 파일은 삭제하지 않는다.

새 필수 브라우저 이름·준비/업무 deadline 계측은 기존 게이트에 등록했다. 업무별 공유10초, 유한 준비·기동·요청·정리, 재시도 deadline 불변 정책을 유지하며 시나리오/전체 총 제한을 복원하지 않는다. 루트 필수 Run-PackageProxy 경로는 공유 절대기한과 부모 phase 감시, child close 후 출력 복구, 잘못된 progress 거부, 정리 실패 시 원실패·결과 보존을 검사한다. 음성5개·주소 선택5 assertion·실제 NGINX8업무를 실행하고 기존 네트워크/호스트 경로와 겹치지 않는 새 사설 /28을 명시한다. 기존 네트워크 삭제나 Docker 전역 변경은 하지 않는다.

최종1003입력의 무선택 full2 40묶음·Java852·브라우저192·프런트392·Node95 및 보존 통과와 동일 소스 독립 수용을 확보했다. 실제 실행/실패·소스 일치·모델 제약·외부 미검증은 [P12-C 기록](work-items/P12-C.md)을 따른다. 공개 배포·전체 Compose 기동·다른 환경 검증으로 확대하지 않는다.

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

실행 우선순위는 등록부의 명시적 `prerequisites`를 지키는 안정 위상 정렬이다. `executionPriority`의 `measured-duration-descending` 정책은 고정된 측정 시간 힌트가 긴 묶음을 우선하며, 같은 값·누락값0의 동률은 기존 등록 순서로 결정한다. `-ReverseGroups`는 이 동률·fallback 순서만 역순으로 하며 긴 작업 우선을 짧은 작업 우선으로 바꾸지 않는다. 메타데이터가 없는 등록부는 기존 정순·역순 동작을 유지한다. `source`의 기준 증거·입력 해시·worker 시작→검증된 종료 경계는 출처 설명이며, 실행 때 과거 `.local-tools` 증거를 읽거나 결과를 재사용하지 않는다. 힌트는 timeout이 아니고 자동 학습하지 않는다. 선택되지 않은 항목까지 알 수 없는·중복 ID, 비수치·null·bool·문자열·음수·비유한 힌트를 자원 취득 전에 거부한다.

`public-demo-runtime`의 같은 실행 증거를 소비하는 `release-recovery`는 우선순위나 역순 옵션과 무관하게 생산자의 성공 검증 뒤에 입장한다. 알 수 없는·자기 자신·중복 선행 조건, 누락된 선택 선행 묶음과 순환 그래프는 자원 취득 전에 거부한다. 선택 묶음을 자동 추가하거나 소비 검사를 생략하지 않으며 `provides`/`consumes` 영향표에서 실행 선행 관계를 추론하지 않는다. 선택·제외·인증 가능 여부는 그대로 유지한다. `summary.json.executionOrder`와 scheduler 요청에는 실제 반환한 우선순위를 기록하며 scheduler 내부에서 숨겨진 재정렬을 하지 않는다. 최대2개 worker의 실제 입장·종료·자원 배제·중첩 구간은 `scheduler-events.ndjson`에 기록한다. 기존 Node ready 판단의 선행 통과·cap·공유 자원 배타·ready-exclusive drain은 그대로 유지한다.

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

아래는 초기 묶음 등록의 요약 표이며 이후 추가된 현재 전체42개 목록과 정확한 필수 메서드/실행 수는 `db-groups.json`을 기준으로 한다. `database-runtime`은 이번 성능 후속의 새 assertion1개를 반영해 현재13/13으로 보완했다. 과거12/12 실행·수용 원근거는 당시 이력으로 유지한다.

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
| database-runtime | shared-contract | 13 / 13 |
| phone-provider | shared-contract | 1 / 1 |

변경 영향 행에는 준비 업무가 소비하는 계약도 포함한다. `provides`/`consumes` 연결에 선언된 소비 묶음이 영향표에서 빠지면 자동 검사가 실패한다. 아직 완전한 영향 행이 없는 등록 묶음을 `-ChangedContracts`로 지정하면 전체로 확대한다. 이는 복잡한 파일 분석 없이 명시 표의 누락을 거부하기 위한 보수적 규칙이다. 거래 계열 변경은 정상 선행 주문/결제와 교차 금액·복구 계약 때문에 비교적 넓게 선택되며, 가설이 좁고 소비 계약을 이미 검토한 경우 정확한 `-DbGroups` 집중 실행을 사용한다.
에뮬레이터 인증 저장소는 루트 안에서 공유된다. 현재 제공자 probe는 OTP를 정확한 sessionInfo로 찾고 성공 교환에서 소비하지만, 일부 일반 브라우저 helper는 phoneNumber 첫 일치 OTP를 찾는다. 같은 번호의 미소비 OTP를 겹치게 하는 새 검사, 병렬 실행, 전체 사용자 초기화, 기존 사용자 부재/개수 전제를 추가할 때는 정확한 session 식별 또는 번호/namespace 분리가 필요하다. 현재 수용 조건과 정/역순 실행 근거는 작업 기록에 남긴다.

### 보조 DB 소유권 검사 보완

각 묶음의 `expectedDatabaseCount`도 등록한다. 일반 DB 묶음은1, phone-provider는0, 현재 legacy는 주DB1+기존 이행 비교DB13=14, inventory는 주DB1+V17 업그레이드 보존 비교DB1=2다. 보조 DB는 해당 묶음만 소유하며 모두 새 UUID로 생성·보존한다. 주DB 생성 정확1회, 전체 생성 수, 이름 허용식, 중복 없음과 실행 identity를 자동 대조한다. `result.json.databaseProof`의 created/auxiliary가 실제 생성 목록이다. 검사에 새 이행 DB가 필요하면 소스의 생성 근거와 예상 수를 함께 갱신한다. 실패를 숨기기 위해 기대 수를 낮추거나 기존 DB를 재사용하지 않는다.


계측 해석: 실제 브라우저 실행시간은 `browser-report.json.stats.duration`이다. `runAuthBrowsersIfRequested`의 inclusive 시간에는 준비·자식 프로세스 대기가 들어가며, 그 내부 `process-wait`는 exclusive 집계에서 별도 분류된다. 따라서 `browser` exclusive 값만 브라우저 총시간으로 보고하지 않는다. 실제 프로세스 대기는 JVM 기동/child 업무 실행도 포함하므로 순수 sleep 시간이 아니다. 초기화 값은 성공한 DB/context refresh 구간이며 별도 child 초기화는 child 실행/대기에 남을 수 있다. 증거 쓰기 시간은 부모 구간과 겹치는 진단값이고 root before/after 보존 시간과 별개다. 스레드 간 중첩이 있을 수 있어 모든 phase 합을 루트 경과시간으로 대체하지 않는다.

현재 등록된 묶음은 각각 한 실행 클래스다. 이번 성능 후속은 이 경계를 유지하며 독립 worker를 최대2개로 제한하고 명시된 자원·의존 조건을 적용한다. 새 도메인도 데이터·트랜잭션·복구 계약에 맞는 실행 범위를 먼저 정하고 등록한다. 여러 클래스를 한 묶음에 합치거나 동시 실행 범위를 넓힐 때는 fixture의 AfterAll 종료, Spring context와 static 상태 수명을 다시 검증해야 한다. 기존 순차·단일 클래스 묶음의 수용 결과만으로 새 동시 실행의 안전성을 주장하지 않으며 같은 소스의 실제 worker 격리·자원 배제·전체 검사 근거를 확인한다.
