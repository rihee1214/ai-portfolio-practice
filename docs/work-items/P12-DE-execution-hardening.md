# P12-D/E 실행 환경·검사 준비·진행 기록 개선

## 최신 상태

- 범위: 사용자 승인된 D/E 후속 하네스 개선의 구현·검증·독립 검토 완료, **로컬 실행 범위 수용**. P13 이후 기능 개발이 아니며 결과는 미커밋으로 인계한다.
- 기준: HEAD `dbaef2ddedfa7f2393e93bea6b648cf832c5cbf9`, 빈 index. [진입 감사](../../.local-tools/pwsh769-entry-6ca36b97-9108-4727-a79b-02d884cc41a0/entry.json): P12-E 수용1082입력·최종 문서 불일치0, 기존 미커밋53파일 원본/소유 보존. `deliverables/`는 사용자 작업 묶음이며 임의 수정하지 않는다.
- 승인: [작업 지시서](../../deliverables/pwsh769-work-kit/WORK-ORDER.md). 명시 PowerShell7.6.9 부모/자식, 사전 검사, 기존 helper 재사용, 실패·진행 기록을 구현하고 작은 회귀→새 무선택 전체→별도 Astra 독립 검토→필수 수정·영향 재검증까지 수행한다. 커밋/푸시/공개/외부 거래/다음 단계 금지.
- 현재 근거: 지정 실행 파일 직접 호출 시 실제 경로와7.6.9 확인. `shell` 도구 옵션만으로는5.1이 실행된 사례가 있어 명시 실행 파일+UTF-8 script file로 호출한다. 작업 묶음의 사전 실행만으로 기존 프로젝트 이행 완료라고 하지 않는다.
- 최신 개발 근거: [full3 전체 통과](../../.local-tools/pwsh769-full3-1e9bfbe0bcd94ecea17068e56412200a/result.json), JUnit935·브라우저196·프런트427,42DB그룹·컨테이너 재현·보존 통과. 무선택/제외0, `certificationEligible=true`,3785.045초. [1090입력 manifest](../../.local-tools/pwsh769-full3-1e9bfbe0bcd94ecea17068e56412200a/inputs.json) SHA256 `9ba7ccd6faa04fb16fb767ce05529826f1f5e88bd1cec9b7ccdbb8cf5219aeaa`.
- 최종 판정: [별도 Astra 독립 수용](../../.local-tools/pwsh769-independent-review/final-review.md), [판정·문서 해시](../../.local-tools/pwsh769-independent-review/final-review.json). 필수 보완2차/4건 해결·잔여0,1090입력 재대조0불일치. full1 빈 로그와 full2 역사 의존성 오류는 해결 근거와 함께 실패 이력을 보존한다. 실제 Toss·공개 배포·실송금·실기기, 기존 원인 미확정 실패 및 초기 회귀 소스 해시 미수집 한계는 별도 유지한다.

## 완료표·변경 계약과 소비 영향

| 결과 단위 | 생산→소비·필수 assertion | 계층/검증 | 상태 |
|---|---|---|---|
| 정확한 실행 환경 | 명시exe→실제process path/version/Core→PS/Node/Java 자식,5.1/7.6.8·다른path 거부, 암묵WinPSCompat 없음 | PS/Node 단위·실제readonly network/child·full3 evidence | 독립 수용 |
| 안전한 인수/관측 | UTF8·인수 배열·기존 Java@argfile·Docker safe JSON/batch·소유 process cleanup | 기존 긴classpath 성공/의도거부 회귀 재사용, 변경 helper 작은회귀+full3 | 독립 수용 |
| 생성 전 준비 | tools/cache/pinnedimages/artifact·역사runtime closure, 기존 subnet/hostroute 중복검사→준비 결과→새 DB/container | 준비 거부 음성검사, 명시API/DB/화면 준비; 기존 E image/subnet helper 재사용 | 독립 수용 |
| 비싼 실행 전 검사 | PS/JS구문·type/lint·실제 browser collection→기존 정확한 필수ID 및 공통 guard file registry→실행 | 수집 누락/중복/잘못된ID 거부,99guard/18release guard/196browserID; 재진입 동일source/조건 확인 | 독립 수용 |
| 전체 근거 | 위 공통실행 변경→모든 등록 DB/브라우저/프런트 소비 | 새 무선택 `./verify.ps1 -Offline -ReverseGroups`; JUnit/ArchUnit/격리PG/front/browser 기존전체 유지 | full3 전체 통과 |
| 보존·진행·인계 | 실행원장/기존이벤트→의미있는 변화 보고→동일source 독립검토 | 실패/변경/재실행목적/범위/증거 식별, 소스·문서·기존자원 보존 | 독립 수용·미커밋 인계 |

제품의 UI/API/DB 업무·권한·금액·상태 전이 계약은 바꾸지 않는다. 이 작업의 생산자는 실행 진입점/검사 registry이고 소비자는 준비 단계와 기존 통합 검사다. 기존 사용자 명령부터 후속 worker/최종 assertion까지 공유10초를 유지하며, 준비로 실제 명령을 이동하지 않는다. 준비·기동·요청·자식·정리는 유한 제한을 유지하고 전체/시나리오 총 제한은 추가하지 않는다.

## 재사용과 모델 담당

P12-D는 커밋 및 전체+선택 수용, P12-E는 미커밋1082입력의 전체+영향 선택 수용이 선행 상태다. E의 원 화면/원증거·13회 재현 실패·IR1의 두 보완·당시 final-confirmation은 그대로 보존한다. 이미 수용된 Java@argfile, Docker20개 batch/safe projection, 새 subnet 선택, cached image ID build, PG 동일ID assertion을 다시 구현하지 않는다. 이번 실제 diff가 영향을 주는 소비만 작은 검사로 보완한 뒤 공통 실행 조건에 따른 전체 근거를 확보한다.

실제 `gpt-6.1-sol` high 별도 담당 두 명이 실행 helper/Node·Java 호출과 root 사전검사/등록을 분담하고 부모는 감사·문서·통합 실행·인계를 맡는다. 별도 `gpt-6-astra` medium이 개발 근거 이후 독립 검토한다. 부모 모델이 역할 지정으로 자동 전환됐다고 주장하지 않는다. 내부 helper별 승인/검토 게이트는 만들지 않는다.

## 실패·재실행 원장 규칙과 측정

각 실행은 새 evidence 경로에 명령·source/환경 식별·예상결과·실제결과·원인(확정/가설/미확정)·수정/환경변화·재실행목적·영향범위를 연결한다. 계획 RED와 우발 실패, 준비 오류와 업무 assertion 실패를 구분한다. 동일source의 재현조사는 가설/목적/조건을 먼저 남기며 원인 미확정은 재통과만으로 해결 처리하지 않는다. 기존 D full2·E reproduction5 원인 한계를 유지한다.

진입 준비 관측: 기본 셸/도구 shell 옵션의 실제5.1 관측, 부모 문서 읽기 도구 호출의 출력 잘림2회, 중첩 inline quoting의 읽기 명령 오류1건. 제품 테스트 실패가 아니며 이후 명시exe/UTF8 script file/범위 제한 읽기로 보완한다. 별도 담당의 같은 도구 한계 관측을 제품 실패로 중복 합산하지 않는다.

진행 중 원인 연결: 실제 child 인수 전달 회귀에서 인용한 parameter token이 위치 인수로 처리되는 결함을 발견해 parameter token 보존으로 수정했다. D09 guard2건은 sandbox CIM 조회/소유 taskkill 권한 때문에 실패했으며 같은 원환경을 통과로 덮어쓰지 않고 새 증거의 확장 실행으로 필요한 범위만 재검증한다. 사전 수집 검사에서는 site 필수 JSON의 객체 형식을 배열로 가정한 오류1건이 비싼 DB 실행 전에 발견됐다. 기존178개 브라우저 수집·type/lint 근거는 보존하고 형식 처리와 남은 수집 범위를 보완한다. 각 담당의 원장과 최종 횟수는 실제 검사 종료 뒤 아래 인계에 연결한다.

## 통합 실행과 독립 검토 진행 원장

| 실행/판정 | 소스·조건·결과와 재실행 사유 |
|---|---|
| 작은 회귀/수집 | [실행 담당 원장](../../.local-tools/pwsh769-execution-ledger.json), [사전 검사 원장](../../.local-tools/pwsh769-preflight-handoff.json), [최종95개 등록 대조](../../.local-tools/pwsh769-registry-final-check.json). 기존77개와 새조건 검사를 보존. runtime/native network/D guards/자손정리 및196개 실제 브라우저ID 확인 |
| 사전검사 소스 변경 거부 | `.local-tools/pwsh769-preflight-diagnostic-27801d7f4e594f5ab9adf23d602b018a/result.json`: 검사 자체 통과 뒤2파일 동시 변경을 정확히 거부. 조정 실패1회, 제품 assertion 실패와 구분. 재실행은 최종 소스 결합 목적 |
| full1 최초 전체 진입 | [명령/조건](../../.local-tools/pwsh769-launch-9da9671d75974e0094ba91223ae06ecc/command.json), [원결과](../../.local-tools/pwsh769-full1-9da9671d75974e0094ba91223ae06ecc/result.json): 사전95guard/196browserID/구문·타입·lint·환경 통과 뒤 로그 복사 실패. Node JUnit reporter는 stdout이 없으므로 PS pipeline `Set-Content`가 빈파일을 생성하지 않은 원인. DB검사 미실행·보존 통과이며 전체 PASS가 아님 |
| 독립1차 | 별도 실제 `gpt-6-astra` medium의 `/root/pwsh769_independent`. IR1 위 생산/소비 오류를 독립 확인, IR2 source+selection 외 준비환경/과거산출물 조건 결합 누락, IR3 새 network-ipam의 단일 대량 argv를 지적. **필수 요청1차/3건**, 아직 수정/재검증 중. full1 자동실패1회와 동일 원인의 IR1을 별도 제품 실패2회로 합산하지 않음 |
| IR1~3 수정·집중 재검증 | [수정/해시/실패 원장](../../.local-tools/pwsh769-ir1-3-handoff.json), [집중8검사](../../.local-tools/pwsh769-preflight-ir1-3-focused.xml), [별도 독립 재대조](../../.local-tools/pwsh769-independent-review/round1-fix-review.md). 무출력 로그/실패 보존, 조건·역사 artifact 경로/바이트/dist목록 변경 거부,47networks의3batch 완전성 확인. 기존95개를 보존해 필수98개 등록. 신규 필수 지적0, 전체 판정 전 |
| 실제 별도 산출물 생산 | [새 producer 관측](../../.local-tools/pwsh769-artifact-observation-e5b09f920aac4284a2ac8cb6c0c4e60b/result.json): 부모/실제Maven 자식7.6.9/Core·호환세션0,766source 전후불일치0, JAR/production dist 해시 통과,23.761초. 전체 하네스가 호출하지 않는 변경된 prepare-artifacts 소비를 실제검증하기 위해1회 실행. `testExecution=false`이며 검사통과로 합산하지 않음 |
| full2 수정 후 전체 진입 | [정확한 명령/재실행 사유](../../.local-tools/pwsh769-launch-6085c499f4884fb29c2f0dad0b5e9c41/command.json). 새 `.local-tools/pwsh769-full2-6085c499f4884fb29c2f0dad0b5e9c41`에서 무선택 전체 진행 중. full1은 DB 이전 실패라 통합 근거가 없고 IR1~3 소스변경 후 실행이므로 관성적 반복이나 동일source 재통과 목적이 아니다. 원 실패·기존 수용 범위는 보존 |

### full2 종료 후 보완

full2 최종 [원결과](../../.local-tools/pwsh769-full2-6085c499f4884fb29c2f0dad0b5e9c41/result.json)는 `failed`이며 보존은 통과했다. 앞선 표의 진행 중 상태는 실행 당시 기록이다. `release-recovery/payload-prepare-R0.log`의 `Payload file missing`, JUnit1실패/skip0로 종료됐다. 기존41그룹 통과를 전체 PASS로 바꾸지 않는다. 실행 담당이 현재 helper를 과거 R0의 필수 runtimeFiles에 잘못 추가한 의존성 소유 결함을 확인했다. 기존7파일은 존재하고 새 helper만 없으므로 역사 산출물을 수정하지 않고 현재 controller closure에만 포함한다. 실패한 실제 definition을 새 경로에서 재현·수정 검증한다.

별도 [독립 영향 검토](../../.local-tools/pwsh769-independent-review/full2-payload-assessment.md)는 역사 runtime closure 존재·해시를 DB 전 사전 검사와 재진입 조건에 연결해야 한다는 **2차 필수 요청1건(IR4)**을 추가했다. 자동 실행에서 찾은 payload 결함과 별도 사전검사 누락 지적을 구분한다. 기존3건 수정은 유지한다. 수정 후 전체 실행은 기존 `-ReverseGroups` 옵션으로 복구를 먼저 수행하며 선택 범위를 줄이지 않는다. full1/2 모두 전체 성공 근거가 없고 새 수정이 있어 다시 전체 근거가 필요하다.

payload 수정은 [수정 인계](../../.local-tools/pwsh769-payload-fix-handoff.json), [실제 R0/R1 준비 결과](../../.local-tools/pwsh769-historical-payload-green-f89f76cf34d045df92df778a60417483/result.json)에서 확인한다. 릴리스 가드18/18, 역사 소스2114파일 변경0. IR4 집중9검사 및 실제 역사7파일 읽기 대조를 통과했고 [독립2차 재대조](../../.local-tools/pwsh769-independent-review/round2.md)에서 필수 보완 해결·신규 필수0으로 판단했다. 현재 필수 guard 등록99개이며 기존 목록은 유지한다. 최종 수용은 새 전체와 최종 문서 대조 전까지 보류한다.

full3는 [명령·조건](../../.local-tools/pwsh769-launch-1e9bfbe0bcd94ecea17068e56412200a/command.json)의 무선택 `./verify.ps1 -Offline -ReverseGroups`로 새 DB·미디어·증거에서 실행한다. IR4 [수정 인계](../../.local-tools/pwsh769-ir4-handoff.json) 후 소스를 고정했다. 역순은 기존 하네스 기능이며 전체 검사 대상은 그대로다. 릴리스 복구 그룹이 먼저 통과한 사실은 그 그룹의 근거이며 전체 최종 판정을 대신하지 않는다.

### 실패 집계 정의

- 현재 변경의 비의도 자동 검사 실패 실행은 현재까지4회: 초기 인수 전달 회귀1, site 수집 형식 진단1, full1 빈 로그1, full2 역사 payload1. 같은 원인의 집중 재현·독립 지적을 추가 결함 횟수로 중복하지 않는다.
- 별도 환경 실패 실행1회(D09 assertion2개: sandbox CIM/소유 프로세스 종료 권한), 소스 동결 조정 실패1회(2파일 변경을 준비 결합이 정상 거부). 권한 조건 변경 및 최종 소스 고정 후 영향 범위로 재검증했다.
- 계획된 RED/음성 거부(미구현 사전 모듈, 빈 로그 재현, 잘못된 역사 closure 재현, IR4 미구현 거부)는 우발 실패와 구분하며 각각 원증거를 유지한다.
- 독립 필수 보완은2차/총4건(IR1~3, IR4). payload 소유 결함 자체는 full2 자동 검사에서 발견됐고 독립 추가 지적은 사전 탐지 누락이다.
- 초기 runtime 회귀의 당시 helper/test 불변 해시와 전체 원콘솔 파일은 미수집이며 원 도구 대화·보존 probe만 있다. 이후 통과로 이 한계를 해소됐다고 하지 않는다. 자세한 경로는 실행 담당 원장에 고정했다.

문서 보완 중 apply_patch 문맥 불일치3회는 무변경 편집 준비 실패이며 자동 제품/검증 실패에 합산하지 않는다. 긴 브라우저 단계 관측 중 폴더명 출력 잘림1회를 추가 관측해 부모 읽기 출력 잘림은 총3회이며 이후 정확한 progress 파일의 마지막2줄만 읽었다.

## 최종 구현·assertion 연결

| 변경 계약 | 구현/자동 assertion | 최신 증거·범위 |
|---|---|---|
| 부모·자식 실제7.6.9 고정 | `verification/PowerShellRuntime.ps1`·`.cjs`, `Test-PowerShellRuntime.cjs`; 실제path/version/Core/Compat 거부·UTF8 인수·자손 종료 | full3 `powershell.json`, `powershell-processes/`, 사전 guard XML; [정상·음성 구분 관측](../../.local-tools/pwsh769-full3-runtime-audit-partial-676b4aa9d4404d3687b7e1ea75bccee0.json)은 부분 스냅샷145정상기록/120PID·기대 거부4건, 최종 수용 자체가 아님 |
| 실제 소비 호출 | `backend/verify-db.ps1`, `Run-PhoneEmulator.ps1`, C/D PS 진입점·`host-runtime.cjs`, E `prepare-artifacts.cjs`/재현 호출; 기존 Java@argfile 유지 | full3 실제 Java TLS/패키지·Node release network·E경로, 별도 산출물 producer23.761초는 build-only이며 테스트 통과와 구분 |
| 생성 전 준비·재사용 | `Preflight.ps1`·`.cjs`, `Test-Preflight.cjs`, `Test-PreflightLogging.ps1`; 실제 수집ID와 registry, 빈 로그·exit·UTF8, source+실행조건+역사산출물+7파일 closure 결합 | full3 sibling `-preflight/result.json`,99필수 guard·196브라우저 수집, IR1~3 집중8·IR4 집중9; Docker47networks→20개 단위3batch completeness |
| 역사/current payload 호환 | `infra/p12-d/payload.cjs`, `Test-ReleaseOperations.cjs`, `required-release-operations.json`; 역사7파일 무주입·불변, 현재18파일에 양쪽 helper 필수 | 실제 실패했던 R0/R1 definition 새 준비,18/18guard·2114역사파일불변, full3 release-recovery 실제 교체/복원 통과 |
| 상태·실패 보존 | `Get-VerificationProgress.ps1`, 개발 흐름·하네스 문서; bounded current summary와 실패원인/환경/소스/목적/범위/새경로 | [집계 정의·관측값](../../.local-tools/pwsh769-orchestration.json), full3 observer65회 제한 조회/30변화 요약. 부모 전체 진입3회·계수한 실행 상태 조회104회. 모델 호출/토큰/압축은 미노출, 절감률 미산출 |

실제 `powershell.exe`를 실행 대상으로 하던 프로젝트 호출을 지정exe와 실제 식별 검사로 바꿨다. 역사 설명·거부 검사에는 이전 이름을 유지한다. 도구 바깥 bootstrap 셸의5.1 관측은 남으며 전역 기본셸이7.6.9로 바뀌었다고 주장하지 않는다. NetTCPIP은 native import를 사용하고 암묵 WinPSCompat을 허용하지 않는다. 준비/기동/요청/자식/정리 제한과 사용자 업무의 공유10초는 유지했다. 사용자 업무를 준비로 옮기거나 병렬화 범위를 확대하지 않았다.

full3 전체 소스와 현재소스의 [최종 정적 감사](../../.local-tools/pwsh769-final-audit.json)는1090입력 불일치0, 기존 수용 증거 불일치0, 과거34224증거파일 불일치0, 사용자 작업묶음13파일 불일치0,652로컬 링크 유효, `git diff --check` 통과다. HEAD는 기준과 같고 index는 비어 있으며 `.codex`/`.agents` 변경0이다. 미커밋79파일은 진입 시 이미 있던 수용 작업40파일(이번 후속 수정이 겹치는 파일 포함), 이번 후속에서 추가 변경한26파일, 보존된 사용자 `deliverables/`13파일로 분류한다. 경로별 소유·해시는 감사의 `files`에 있다. `.local-tools` 원증거·DB·미디어·도구는 커밋 대상에 넣지 않았고 커밋/푸시/공개/다음 단계는 수행하지 않았다.

별도 실제 `gpt-6-astra` medium은 전체 결과·42그룹·99+18필수 guard·935Java개별case 실패/skip0·현재1090입력 및 assertion 원증거를 대조해 수용했다. root+preflight+E의 완료 시점 정상 런타임154기록/125PID는 지정7.6.9/Core/Compat0이며 기대 음성4파일과 구분해 확인했다. 최종 검토 MD SHA256 `4ad22f821362b32a929258e11327a9a8d63079592e9b9b5c636ba93ae4d51804`, JSON SHA256 `de2f9dd185fd0061162129c3b7e0c20c7692b0f037ab59141fa03492ecd060f3`. 수용 표시·링크만 보완한 문서의 마지막 해시는 `.local-tools/pwsh769-independent-review/final-confirmation.json`에 기록한다.

문서만 변경한 최종 인계 때문에 전체 검증을 반복하지 않는다. 실제 외부 Toss·실송금·실기기·공개 배포는 미검증이며 기존 원인 미확정 실패를 재통과만으로 해결 처리하지 않았다. AC16은 승인된 동일PC의 독립 컨테이너 재현 범위이고 다른OS 실행 증거로 확대하지 않는다. 다음 제품 단계는 별도 승인 대상이다.

부모의 실제 통합 실행/관측 횟수는 `.local-tools/pwsh769-orchestration.json`과 각 launch의 `observer-metrics.json`으로 연결한다. 관측기는60초마다 최상위 현재 결과만 제한 조회하고 변화만 출력하며, 실행 소유 프로세스 종료/최종exit 기록에 따라 종료한다. 최상위 검증에 총 제한을 추가하지 않는다. 자세한 과거 로그 재귀 재독은 하지 않는다.

모델 호출의 전체 횟수·비캐시 입력/출력 토큰·자동 압축은 현재 도구에서 노출되지 않아 `측정 불가`다. 실제 서브 에이전트 배정과 명시 통합 실행/상태 조회는 이 작업에서 관측한 범위만 남긴다. 이전 작업과 비교 가능한 동일 정의 데이터가 없으므로 절감률을 산출하지 않는다. 긴 실행의 상세목록 반복 조회 대신 기존 progress 이벤트와 종료/실패/결정 필요/의미있는 phase 변화를 모아 보고한다.
