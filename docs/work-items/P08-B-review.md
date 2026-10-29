# P08-B 독립 사전 검토

2026-10-27. A 독립 마감 후 B 계획, 설계의 worker/Outbox 정책, 현재 platform과 실제 ModuleRules/ModuleRulesTest를 읽었다. **B 구현 완료 검토·검사 실행·독립 전체 하네스는 미실행**이다. 이 문서는 계약·assertion 사전 검토이며 통과/완료 증거가 아니다. 제품/검사/하네스와 기존 자원을 변경하지 않았다.

## R-B01 / P2 — 새 platform API와 기존 ArchUnit의 실제 충돌

ModuleRules의 `layer(origin,"api") && (!targetApi || targetPlatform)`는 platform.api가 자기 platform.api DTO를 참조해도 거부한다. 현재 platform에는 API가 없으므로 새 Jobs/Outbox/JobHandler에서 처음 드러난다. 같은 platform.api 내부의 공개 DTO 참조를 최소한 허용하고 정상 fixture를 추가해야 한다. 기존 업무 application→platform 허용, platform→업무 금지, API→내부/기술 타입 금지는 유지한다. 특히 기존 `publicApiCannotDependOnPlatformEvenIfItsPackageIsNamedApi`는 업무 API의 플랫폼 결합 금지를 명시하므로 B를 이유로 없애거나 무조건 완화할 필요가 없다.

## R-B02 / P2 — Outbox 전달 자체의 원자성·충돌 계약 필요

append의 호출자 거래 참여뿐 아니라 event→job 전달과 전달완료 표식의 거래 경계를 확정해야 한다. 중간 실패로 이벤트만 delivered가 되거나 job만 여러 개 생겨서는 안 된다. event ID/dedupeKey가 같고 type/payload가 다른 경우 기존 성공을 무조건 반환하지 말고 충돌 정책을 명시한다.

필요 assertion: 업무행+event+job의 외부 거래 rollback/commit, commit 전 다른 연결에서 미관측, dispatch의 enqueue 전후 실패주입과 재시작 후 job1개, 같은내용 동시 중복1개, 다른내용 같은키 거부·기존행 불변. 새 작업이 즉시 enqueue되는 설계라면 별도 전달 단계가 없음을 계약에 명시하고 원자적 저장을 검사한다.

## R-B03 / P2 — token만으로 fencing을 충분하다고 간주하지 않기

ack/retry/heartbeat는 상태·현재token·유효 lease를 함께 검사해야 한다. 아직 B가 재claim하지 않았더라도 이미 만료된 A가 heartbeat로 부활하거나 ack하는 정책은 명시적으로 거부하는 편이 일관된다. 시간을 Java에서 먼저 확인하고 DB잠금 대기 후 오래된 판정을 쓰지 않아야 한다. 느린 handler와 heartbeat는 같은 단일 스레드에 묶이면 heartbeat가 실행되지 않으므로 실행 구조를 구분한다.

필요 assertion: 만료직전/정각, 잠금대기 중 만료, B 재claim 후 A의 ack/retry/heartbeat 모두 변화0, B의 token/lease/attempts 유지, 정상 heartbeat 후 긴 handler의 조기 재claim 방지. lease 갱신 자체가 업무 부작용을 exactly-once로 만들지는 않는다.

## R-B04 / P2 — crash가 재시도 상한을 우회하지 않기

attempts를 handler 실패 응답에서만 증가시키면 매번 프로세스가 죽는 작업은 영원히 claim될 수 있다. claim 시 실행 횟수를 내구적으로 증가시키고, 마지막 회차에서 crash한 작업도 lease 종료 후 terminal로 수렴하는 계약이 필요하다. 만료 reclaim·일반 retry·알수없는 handler/영구오류가 어떤 정책을 쓰는지 고정한다.

필요 assertion: 반복 claim→미ack→lease만료5회 후 추가실행0·FAILED 조회가능, 정상 실패의5/15/60/300초 대기·정각 재실행, terminal 작업 heartbeat/ack/재claim 불가. 일반 FAILED를 주문 실패/예약해제로 해석하지 않는 D 인계를 유지한다.

## R-B05 / P2 — 실제 kill-after-effect-before-ack 증거

B03은 단순 예외·메서드 재호출·Clock 전진으로 대체할 수 없다. 새 전용 worker 프로세스와 같은 격리 DB를 사용하고, 업무효과가 실제 commit됐음을 다른 연결로 확인한 뒤 ack 직전의 결정적인 barrier에서 해당 PID만 종료해야 한다. 종료완료를 관측하고 새 PID worker를 시작해 같은 원인 키의 효과1회·job 완료·attempt 증가를 검증한다. 전체 프로세스 목록이나 기존 서버를 종료해서는 안 된다.

실패 재시작도 로그·DB를 보존하고 자식 프로세스 cleanup은 자신이 시작한 PID에 한정한다. 테스트용 효과/중단 handler는 비검사 실행에서 등록0을 assertion한다. 핸들러는 업무효과 dedupe를 원인키와 같은 거래로 저장해야 한다. 외부 호출이 있다면 동일 원인키를 제공자까지 전달하는 계약이 별도로 필요하다.

## R-B06 / P2 — 실행 모드·handler 호출 거래 경계

설계는 같은 JAR의 별도 worker 실행, 웹 기본 polling 비활성, 명시 결합 모드를 요구한다. 설정 파일 존재만으로 실제 분리를 입증하지 않는다. 기본 웹 context의 poller0, worker에서 실행 가능·중복 handler type 거부·미등록 type 정책을 검사한다. DB claim을 commit한 뒤 handler를 거래 밖에서 호출하고 ack/retry는 짧은 별도 거래로 수행하는지 확인한다. D 외부 제공자 호출이 claim 거래/행잠금을 오래 잡는 구조가 되지 않아야 한다.

## 현재 판정

위 내용은 B 구현 전 명확히 해야 할 계약/필수 assertion이며 제품 결함이 이미 재현됐다는 의미는 아니다. 구현자 집중 검사와 개발 최종 전체 통과·소스 동결 이후 독립 검토/새 전체 하네스를 별도로 수행한다. 기존14,158+A4,721 실패파일과 후속 B 실패증거를 유지한다. C·커밋·배포·푸시는 수행하지 않았다.

## B 개발1 실행 중 동결 소스 정적 재검토

2026-10-27. 개발 전체 `.local-tools/p08-b-dev1-20261027`과 동시에 하네스를 실행하지 않았다. 플랫폼 API/규칙/JDBC/worker/V32, B 실제 검사9개, 단위·ArchUnit 보완, WorkerProcessProbe와 B 착수·인계 계약을 읽었다. 제품/검사 소스 수정은 없다.

| 사전 지적 | 구체적인 보완·assertion 확인 | 정적 판정 |
| --- | --- | --- |
| R-B01 | origin platform.api일 때 자기 공개 API 참조만 예외. 업무api→platformapi 금지 fixture 유지, 자기API 정상/내부금지 fixture 추가 | 보완 확인 |
| R-B02 | Outbox.append와 Jobs.enqueue는 REQUIRED 거래. event와 job 동시저장으로 별도 dispatcher 없음. 업무 주문+event+job rollback/commit, 별도 연결 commit전 job0, 동시 같은내용1/다른내용 한승자, event trigger실패 후 job0 | 보완 확인 |
| R-B03 | owned는 행잠금 후 Clock을 읽고 RUNNING/token/lease를 확인. pg_stat_activity의 실제 lock wait 후 시간전진으로 heartbeat/ack/fail 모두 거부. 새 owner 이후 stale3종 거부. 별도 heartbeat thread가 handler 대기 중10초 갱신하고 원기한 이후 재claim0 | 보완 확인 |
| R-B04 | claim에서 attempts 증가.5회 normal fail의5/15/60/300초 정각·직전,5회 lease crash 후 FAILED/추가claim0·실패조회. 알수없는 type은 소유 실행자를 기다리고 미claim. 안전 오류코드만 저장 | 보완 확인 |
| R-B05 | 테스트 전용 새 child JVM: handler가 원인키 효과 거래 commit→marker. 부모가 독립 연결 effect1/RUNNING/attempt1 확인→해당 PID만 kill·종료확인. 새PID는 Clock31초 후 재개하여 effect1/SUCCEEDED/attempt2 확인 | 보완 확인 |
| R-B06 | 기본 웹 context WorkerEngine/JobHandler0. child는 production MarketplaceApplication+worker profile/nonweb/engine1. handler 거래밖·중복type거부·worker 호출자거래 거부, RETRY/TERMINAL/EXCEPTION 내구결과 검사 | 보완 확인 |

프로세스 재개 검사는 실제 JVM 종료·재시작이며 메서드 재호출로 대신하지 않는다. 테스트 classpath에서 production MarketplaceApplication과 실제 worker 설정을 부팅하고 test-only handler/고정 Clock을 주입한다. 배포된 JAR 자체를 실행한 smoke라고 주장하지 않는다. 실제30초 sleep 대신 새 child Clock을31초 전진해 lease 재획득 경계를 검증하며 PID 종료와 업무효과 commit은 실제로 관측한다.

현재 정적 필수 차단은 발견하지 않았다. 이는 실행 성공 판정이 아니며 개발 전체 결과 및 별도 독립 전체·소스·보존 대조가 남았다. B의 외부 부작용 exactly-once는 보장 범위가 아니고 C/D/F1 handler는 causeKey를 업무/제공자 멱등성에 연결해야 한다. READY/FAILED 조회를 주문 실패나 예약해제 의미로 사용하지 않는 인계를 유지한다.

## 독립 전체 review1 실패 — R-B07 / P2 검사 캐시 격리

이 검토자가 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-b-review1-20261027`을 실행했다. 2026-10-27 16:44:11.9360963~16:48:53.5571177 KST, **종료1/status failed/preservation passed**, 오류는 `Frontend test failed`다. Java373·브라우저68·Vitest162는 통과했으나 Node72 중71통과/1실패여서 B 완료 판정을 보류한다. 실패 후 프런트 필수 coverage 기록 단계에는 도달하지 않았다. 앞의 정적 보완 확인은 이 실패를 숨기거나 독립 전체 통과를 뜻하지 않는다.

실패 원문: `test.txt`의 `real Vite proxy preserves health/API errors, blocks other management paths before SPA fallback`이 시작83ms에 `EPERM: operation not permitted, rmdir 'D:\Workspace\ai-portfolio-practice\frontend\node_modules\.vite\deps'`로 실패했다. HTTP 응답 assertion 실패가 아니라 Vite 캐시 정리 오류다. package.json은 Node 테스트 파일을 실행하며 `proxy.test.mjs:19`와 `proxy-stream.test.mjs:31` 모두 같은 frontend root에 cacheDir를 지정하지 않는다. 따라서 공유 `.vite/deps` 사용은 확인됐고, 오류 순간 실제 파일잠금 소유 프로세스까지는 특정하지 않았다. `proxy-boundary.test.mjs`는 mkdtemp root라 이미 분리돼 있다.

수정 권고는 두 테스트의 각 새 Vite 인스턴스에 고유 cacheDir를 제공하는 것이다. 새 실행 증거 아래에 캐시를 보존하고 기존 공유 캐시를 삭제하거나 기존 서버를 종료하지 않는다. 병렬성·시간제한·기존 assertion은 유지하고 서로 다른 인스턴스의 경로 격리를 검증한다. 부모가 원 구현자에게 이 보완을 맡겼으며 이 검토자는 제품/검사 소스를 수정하거나 무작정 재실행하지 않았다.

개발1/독립1 입력394개의 경로·해시 차이0, 종료 직후 16:50:10 소스 해시 불일치0이다. 실행 전 P06/P07 14,158+A4,721+B2,894 실패증거 모두 해시 불일치0이었다. review1 원본 및 해당 `.local-tools/phone-emulator-11e9a9c5bf9848609f4199bfaff4a81d` 원본576파일을 `evidence/P08-B-review1-failure-manifest.json`에 추가 보존했다. 기존 B manifest와 실패 원본은 덮어쓰지 않았다. 결과/coverage/Node실패/Vitest XML/보존/소스 대조 사본은 `P08-B-review1-failed-*`다.

실제 worker 증거는 review1/db/worker-ae72ea13-0148-4cef-ade6-9d1546123312에 남아 있다. 첫PID46572를 종료하고 새PID34480의 nonweb=true/SUCCEEDED를 확인했다. 이 B03 통과와 전체 하네스 실패는 구분한다. 보완 후 새 개발 전체·독립 전체와 전후 보존/소스 일치가 필요하며 C는 시작하지 않는다.

실제 프런트 순서는 typecheck→lint→test:unit→build→Node test다. 타입/lint/Vitest162/build는 수행·통과했고 마지막 Node 실패 때문에 frontend-coverage.json 최종 필수대조만 미실행이다. 실패 후 누적22,349파일(기존21,773+독립실패576)을 다시 해시 대조해 불일치0을 확인했으며 `P08-B-review1-failed-preservation-verification.json`에 저장했다. 새 DB는 `d2_it_c0ef8d4d375446b294b39d0675c551e1`, 미디어는 review1/db/media다.

## R-B07 캐시 격리 보완 정적 재검토

dev2 전체 실행 중 읽기 전용으로 보완4파일을 검토했다. proxyViteConfig.mjs는 프로젝트 `.local-tools` 아래 mkdtemp로 호출마다 새 cacheDir를 생성하고 삭제하지 않는다. proxy.test와 proxy-stream의 각 서버가 helper를 호출하며 실제 vite.config.cacheDir와 요청값 동일을 assertion하고 경로를 로그에 남긴다. root/기존 프록시 플러그인/HTTP assertion/병렬성/timeout은 유지한다.

새 proxy-cache.test는 동시에 생성한 두 구성의 Vite resolveConfig 유효 cacheDir가 서로 다르고 공유 개발 cache와 다름을 검사한다. 실제 두 서버를 동시에 기동해 각 유효 cacheDir 및 SPA응답200/root marker까지 검증하고 소유 서버만 닫는다. 기존 공유 cache나 기존 서버를 정리하는 코드가 없다. 새 helper와 새 검사파일은 dev2 inputs.json에 포함됨을 확인했다.

개발의 cache-red 원문은 유효 Vite 캐시가 공유 경로로 동일하다는 assertion 실패이며, 집중 green14개 통과·최종 cache 단독1개 통과와 공유 cache339파일 변화0 대조를 읽었다. 이들은 개발 집중 증거이고 독립 전체 성공을 뜻하지 않는다. R-B07의 원인 경로를 제거하는 범위 적합성을 확인했고 새 정적 차단은 발견하지 않았다. dev2 종료 후 동일 동결 소스의 새 독립 전체 실행·실패 보존 대조가 남는다.

## 독립 전체 review2 최종 결과 — B 완료

이 검토자가 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-b-review2-20261027`을 직접 실행했다. 2026-10-27 17:02:32.47102~17:07:14.714745 KST, 종료0/status=passed/preservation=passed. Java373(단위170+PostgreSQL202+전화제공자1), PC/모바일 브라우저68, Vitest162, Node73 및 타입/lint/build·하네스 자체검사 통과다. JUnit 개별결과373건 모두 passed, 전체 coverage와 프런트/브라우저 필수 coverage passed, 실패/오류/skip/필수 누락0을 직접 확인했다.

개발2/독립2 inputs396개 경로·SHA 차이0, 실행 후 현재 소스 불일치0. inputs SHA256은 `85F3C6E15DBAFAA45F30AE5E6F2F139F0B0DF61F3996786BA3F8918552869774`, 기준 HEAD는 `460aa8bb0bb22f5138849eb00c050abd18f6fb53` 위 미커밋 B 변경이다. Maven 선택 실행 옵션은 없었다. 실행 이후 이 검토자는 문서·증거만 작성했다.

### 제약과 실제 assertion 판정

| 제약/지적 | 독립2 실행 근거·확인 결과 |
| --- | --- |
| B01 / R-B02 | outboxJoinsBusinessTransaction, outboxConcurrentDedupeAndAtomicInsertFailure 통과. 업무+event+job commit/rollback, 독립연결 미commit 미관측, 동시dedupe·내용충돌, event삽입실패 job rollback |
| B02 / R-B03 | jobLeaseFencesStaleWorkers, jobWritesRecheckLeaseAfterRowLockWait, workerHeartbeatProtectsLongHandler 통과. 한claim승자, 정각만료, 기존token 3종쓰기 거부, 실제 행잠금 대기 후 시간 재검사, 긴 handler 중 heartbeat/조기 재claim0 |
| B03 / R-B05 | workerRestartKeepsDurableEffects 통과. 실제 첫 child의 commit효과1 확인→자신의 PID 종료→새PID 재개, 최종 효과1/attempt2/SUCCEEDED/nonweb |
| B04 / R-B04 | retryAndLeaseBoundaries, jobRetryAndDueBoundaries 통과. 미래/정각·재시도간격,5회 실패·5회 crash후 terminal·6회미실행·실패조회 |
| R-B01·06 | 신규 ArchUnit 정상/거부3개와 기존 경계 전체, workerDispatchesOutsideTransactionsAndRejectsDuplicateTypes, workerFailureResultsAreDurableAndSanitized 통과. 기본web 실행기0·handler 거래밖·중복타입거부·안전코드 저장 |
| R-B07 | Node73 통과. 이전 실패 프록시 검사가 고유 cacheDir를 사용하고 새 실제 동시 두서버의 cache 분리/SPA응답 검사가 통과. 기존 timeout/병렬성/응답 assertion 유지 |

R-B01~07과 B01~04의 필수 잔여 차단 없음. **B Job/Outbox/worker 범위의 구현·독립 검증 완료**로 판정한다. review1 실패는 그대로 남으며 review2 성공으로 과거 결과를 바꾸지 않는다.

### 보존·실패 이력·실행 범위

새 DB `d2_it_7a86cf51e9b84d68ae13400428de0caa`, media `.local-tools/p08-b-review2-20261027/db/media`, emulator `.local-tools/phone-emulator-1422b7811c024fcba74aaabf4c830045`를 사용했다. 기존 서버·DB·과거 검증DB·미디어 보존은 하네스 before/after passed다. 과거 검증DB 전체 행 전수비교를 뜻하지 않는다.

실행 전후 P06/P07 14,158+A4,721+B2,894+review1 576+cache-red1=22,350개 실패파일 누락/해시 불일치0이다. review1 및 에뮬레이터 원본576개와 cache-red1개를 별도 manifest로 보존했고 모든 이전 manifest를 유지했다. 초기 단위/저장/worker RED와 probe설정 격리실패, 선택 실행의 coverage 실패, review1 캐시 EPERM을 각각 실제 원인/범위로 유지한다.

공유 frontend/node_modules/.vite는 실행 전후339파일이다. 문제 대상 의존성 캐시를 포함한338개는 해시 동일하다. 남은1개 `vitest/da39a3ee5e6b4b0d3255bfef95601890afd80709/results.json`은 17:06:40의 정상 Vitest 실행 결과(duration/lastRun)로 갱신됐다. 전체339개 불변이라고 주장하지 않는다. Node 프록시별 새 캐시는 `.local-tools/vite-proxy-test-*`에 보존되고 실제 경로는 test.txt에 남았다.

`evidence/P08-B-review2-final-*`에 최종 result/inputs/coverage/개별결과/명령로그/DB요약·대상/브라우저report/제공자probe/before·after/소스·실패·공유캐시 대조/실제 worker PID·효과·완료 및 로그/요약 사본29개를 저장했다. 원본 실행 경로와 실패 원본은 삭제하지 않았다.

프로세스 검사는 production MarketplaceApplication·worker 설정을 테스트 classpath에서 부팅한 실제 JVM 종료/재시작 검사다. 배포 JAR smoke·실서비스 부하·외부 제공자 exactly-once·업무handler·운영 재처리 UI는 미검증/범위밖이다. C/D/F1은 이 일반 작업의 causeKey를 실제 업무효과/제공자 멱등성으로 연결하고 UNKNOWN을 일반 FAILED/예약해제로 번역하지 않아야 한다. 이 검토자는 C·커밋·배포·푸시를 실행하지 않았다.
