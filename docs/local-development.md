# 로컬 실행과 검사 — 현재 진입점

2026-11-16 P12-E 수용 이력과 후속 실행 환경 보완 안내다. 현재 소스 기준은 P12-D 커밋 `dbaef2ddedfa7f2393e93bea6b648cf832c5cbf9`이며, P12-E의 문서·독립 컨테이너 실행 결과는 [현재 작업 기록](work-items/P12-E.md)을 따른다. 아래 명령은 **저장소 루트의 Windows x64·명시 PowerShell 7.6.9**에서 시작한다. Linux/macOS 호스트에서 이 Windows 하네스가 동작한다고 주장하지 않는다.

현재 기능은 DB 기반 가입·세션·판매·주문·모의 결제·후속 처리·정산·운영까지 포함한다. 실제 Toss F07/G08, 실송금, 공개 배포, 실제 사용자 직접 수행은 별도 미검증/차단이다. `health=UP`은 기동 확인이며 거래 성공 근거는 같은 주문 ID의 API/DB/화면 assertion이다. [실제 업무 흐름](portfolio/business-demo.md), [유지보수 사례](portfolio/maintenance-cases.md), [하네스 실행 계약](verification-harness.md), [릴리스·복구 명령](../infra/p12-d/README.md)을 함께 읽는다.

P12-E 독립 재현은 Docker의 legacy local-image-ID builder 지원도 확인해야 한다. 실행 도구가 고정 base/nginx/postgres 이미지의 로컬 digest를 먼저 검사하고, 검증된 base image ID를 FROM으로 사용해 자식 빌드에만 `DOCKER_BUILDKIT=0`을 적용한다. 전역 설정 변경이나 다운로드 fallback은 없으며 캐시/지원이 없으면 실패한다. 현재 환경에서 실제 기동·새 DB 이행·업무까지 검증한 조건이며 다른 Docker 버전의 지원을 추측하지 않는다.

## 1. 실행 목적 선택

| 목적 | 실행 진입점·결과 | 범위·부작용 |
| --- | --- | --- |
| Java/React 수정과 빠른 피드백 | 아래 빌드·단위 명령, 개발 서버 | Java 기본 검사는 실제 PostgreSQL 업무 검사가 아니다. Vite 개발/preview는 공개 서버가 아니다 |
| 실제 DB·브라우저 업무 확인 | 루트 `verify.ps1`의 전체 또는 명시 계약 선택 | 새 UUID DB·media·API/worker·로컬 전화 에뮬레이터와 증거 생성. 실패 대상도 보존 |
| 새 독립 컨테이너 재현(AC16) | [P12-E 실행 기록](work-items/P12-E.md)의 명시 준비물·새 project/volume·실제 실행 명령 | 같은 PC 허용. 기존 C/D 증거와 새 부족 범위 결과를 함께 판정. 파일 존재/health만으로 완료하지 않음 |
| 교체·백업·복원·앱 복귀(AC18) | `infra/p12-d/Invoke-ReleaseOperation.ps1 -Request <절대 JSON 경로>` | 명시 새 소유 대상만 조작. `Prepare/Activate/Checkpoint/Restore/Rollback/Inspect`별 입력·출력·실패 경계는 [운영 안내](../infra/p12-d/README.md) |

## 2. 명시적으로 준비할 도구·캐시·산출물

저장소 checkout만으로 도구·과거 산출물·비밀 설정이 생기지 않는다. 바이너리/캐시/DB/media/원증거는 Git 대상이 아니다. 공식 배포물과 checksum 준비 이력은 [P01-B1](work-items/P01-B1.md), [P01-B2](work-items/P01-B2.md), [P01-D4](work-items/P01-D4.md), [P06-A](work-items/P06-A.md)를 따른다. 임의 호스트의 개인 캐시를 암묵적으로 준비물로 삼지 않는다.

| 준비물 | 현재 소스가 요구하는 위치·버전/조건 |
| --- | --- |
| PowerShell·Git·Docker | 사용자 지정 안정 PowerShell 7.6.9 실행 파일만 사용. Git·Docker CLI/Compose 접근 가능. 원 검증 OS는 Windows x64. 컨테이너 엔진 및 이미지 캐시는 별도 준비 |
| JDK | `.local-tools/jdk-21.0.12.1+1`의 Temurin 21.0.12.1+1. Java 목표21은 [pom](../backend/pom.xml), 하네스 고정 경로는 [backend launcher](../backend/verify-db.ps1) |
| Maven | Wrapper3.3.4·Maven3.9.16/[고정 배포 checksum](../backend/.mvn/wrapper/maven-wrapper.properties). Wrapper 캐시 `.local-tools/m2`, 의존 저장소 `.local-tools/m2/repository` 모두 필요. `-Offline`은 누락 의존을 다운로드하지 않음 |
| Node/npm | `.local-tools/node-v24.22.0-win-x64`의 Node24.22.0/npm11.19.1. [package.json](../frontend/package.json)은 Node `>=24.22.0 <25`, npm11.19.1을 명시. [npm.ps1](../frontend/npm.ps1)의 npm cache는 `.local-tools/npm-cache` |
| 프런트 의존·Chromium | 잠금 파일로 설치한 `frontend/node_modules`, Playwright1.64.1의 브라우저 캐시 `.local-tools/playwright`. DB 브라우저도 실제 Chromium이 필요 |
| 전화 에뮬레이터 | [launcher](../verification/Run-PhoneEmulator.ps1)가 `.local-tools/p06-a-provider-probe-20261025/node_modules/.bin/firebase.cmd`를 요구. 원 준비 버전 Firebase CLI15.34.1. 매 실행 새 `demo-*` project/loopback 포트를 생성하며 실제 SMS는 발송하지 않음 |
| PostgreSQL·프록시 이미지 | 기본 개발/검사 [compose.yaml](../compose.yaml)은 PostgreSQL18.6. 패키지는 [고정 PostgreSQL/NGINX digest](../infra/p12-c/compose.yaml)와 같은 API/worker immutable app image를 사용. package의 `pull_policy: never` 때문에 이미지가 없으면 먼저 별도 준비해야 함 |
| DB 비밀값 | 기본 하네스는 루트 `.env`의 `TEST_ADMIN_PASSWORD/TEST_DB_PASSWORD`를 읽음. [예제](../.env.example)의 개발/검사 네 암호를 서로 다르게 준비하고 출력·커밋하지 않음. 기존 `.env` 덮어쓰기 금지. 패키지 비밀 파일은 별도 `PACKAGE_DB_*` 세 항목이고 앱/관리자 암호가 다름 |
| 과거 호환·이행 검사 입력 | `P12_D_H129_JAR`, `P12_D_R0_JAR`, `P12_D_R0_DIST`, `P12_D_HISTORY_PROOF`에 검증된 과거 절대 경로를 명시. 고정 H129 JAR/출처, P12-C R0 JAR/dist/출처가 필요. [C 패키지 안내](../infra/p12-c/README.md)의 이전 P12-B 번들도 필요. 부재를 현재 빌드로 대체하거나 skip 성공 처리하지 않음 |
| 독립 패키지 입력 | 검증된 JAR·production dist·전체 migration 목록·고정 이미지 ID·렌더한 proxy 설정·private TLS/개별 reviewer 파일·명시 PowerShell 실행 파일을 [릴리스 manifest](../infra/p12-d/README.md)에 제공. 새 대상·미사용 포트·UID10001 writable media 필요 |

**현재 실행 환경 계약:** 이 후속 실행은 사용자 지정 `C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe`의 안정 7.6.9만 허용한다. [공통 검사](../verification/PowerShellRuntime.ps1)는 실제 프로세스 경로·버전·Core edition을 검사하고 부모와 Node/Java가 시작한 자식의 증거를 기록한다. 다른 경로/버전은 설치나 PATH 대체 없이 차단한다. D manifest의 `runtime.powershellPath`도 같은 경로를 사용한다. 네트워크 cmdlet은 `NetTCPIP -SkipEditionCheck`로 직접 로드하며 `WinPSCompatSession`이 있으면 거부한다. 이 Windows 호스트 계약을 임의 PC의 이식성으로 해석하지 않는다. 과거 P12-E 수용 증거는 [당시 기록](work-items/P12-E.md)에 보존한다.

## 3. Java/React 빌드와 최소 기동

현재 셸에만 도구를 연결한다. 첫 준비에서는 의존성이 실제로 준비되어야 하며 아래 offline 빌드는 설치 명령이 아니다. `npm ci`는 lockfile을 따라 `node_modules`를 재설치하므로 자신이 실행한 Vite가 사용 중이면 먼저 그 서버를 종료한다.

```powershell
& {
    $ErrorActionPreference = 'Stop'
    $env:JAVA_HOME = Join-Path $PWD '.local-tools/jdk-21.0.12.1+1'
    $env:MAVEN_USER_HOME = Join-Path $PWD '.local-tools/m2'
    $env:PATH = (Join-Path $PWD '.local-tools/node-v24.22.0-win-x64') + ';' + $env:PATH
    ./frontend/npm.ps1 ci --offline
    Push-Location backend
    try {
        ./mvnw.cmd -o -B -ntp '-Dmaven.repo.local=../.local-tools/m2/repository' verify
        if ($LASTEXITCODE -ne 0) { throw 'Java build failed' }
    } finally { Pop-Location }
    ./frontend/npm.ps1 run typecheck
    ./frontend/npm.ps1 run lint
    ./frontend/npm.ps1 run test:unit
    ./frontend/npm.ps1 run build
    ./frontend/npm.ps1 test
}
```

Java 산출물은 `backend/target/marketplace-api-0.0.1-SNAPSHOT.jar`, 프런트는 `frontend/dist/`다. Checkstyle·JUnit·ArchUnit의 실제 범위는 pom/원 XML을 따른다. Maven 기본 `verify`만으로 새 DB의 Flyway·트랜잭션·업무를 검증했다고 하지 않는다. 중간 명령 실패 시 다음 단계 진행을 멈추고 원 로그를 남긴다.

DB 없는 최소 API 기동은 다음과 같다. `local` 예제는 주소/포트만 제공하는 **P01 설정**이며 지금의 DB 업무 프로필을 대신하지 않는다.

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar backend/target/marketplace-api-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=8080
```

다른 셸에서 `Invoke-RestMethod http://127.0.0.1:8080/actuator/health`로 기동을 확인한다. React는 또 다른 루트 셸에서 `./frontend/npm.ps1 run dev '--' --port 5173 --strictPort`, 빌드 preview는 `./frontend/npm.ps1 run preview '--' --port 4174 --strictPort`다. 기본 API 프록시는8080이다. 포트가 점유돼 있으면 기존 프로세스를 종료하지 말고 자신의 구성/미사용 포트를 확인한다. 종료는 자신이 시작한 서버의 Ctrl+C를 사용한다.

## 4. DB 업무 프로필과 모드

| 실행 | 실제 프로필·설정 | 허용 범위 |
| --- | --- | --- |
| 로컬 HTTP API | `db,local-http` (`--spring.profiles.active=db,local-http`) | 새 전용 DB의 `DB_URL/DB_USERNAME/DB_PASSWORD`, 새 `MEDIA_ROOT` 필요. HTTP에서 세션 Secure 설정을 조정하는 local-http는 loopback 전용 |
| 로컬 메일 확인 | 로컬 API에 `local-mailbox` 추가 | 합성 메일/토큰 소비 도구. 실제 외부 메일 발송 증거가 아님 |
| worker | `db,worker` | API와 같은 JAR/DB, non-web. media 비활성·mount 없음. API만 이미지 root/lock·정리를 소유 |
| 로컬 전체 패키지 L | `local-full`·`local-test` 전화, host JAR API/worker + 격리 DB/TLS proxy | 명시 에뮬레이터 host·demo project 필요. 회원/비회원 성공 경로; 실제 PG는 아님 |
| 초대 패키지 P | `public-demo`·`invited-only`, API `db`·worker `db,worker`, Compose | 개별 초대 로그인과 앱 로그인은 별개. 전화 제공자 disabled, 비회원 발급503·SANDBOX 거부. loopback TLS rehearsal이며 공개 운영 완료가 아님 |

DB 프로필은 시작 시 Flyway를 수행하므로 기존 서버/DB에 위 명령을 시험 적용하지 않는다. 새 DB/media 생성은 아래 하네스 또는 명시 새 대상의 릴리스 `Prepare`를 사용한다. DB namespace `.database`와 `.lock`을 지우거나 다른 DB의 media를 재사용하지 않는다. 이미지 root를 정적 서버에 노출하지 않으며 실제 파일은 권한을 검사하는 API로만 제공한다. 프런트 `VITE_*`에 비밀값·DB 주소를 넣지 않는다. [실제 Spring 설정](../backend/src/main/resources/application-db.properties)과 [worker 설정](../backend/src/main/resources/application-worker.properties)이 기준이다.

## 5. 새 DB·브라우저 검증과 결과 판정

준비된 전용 PostgreSQL과 `.env`, 위 캐시/과거 입력을 확인한 뒤 루트 PowerShell7에서 실행한다. 기존 검사 DB에 덮어쓰지 않는다. 첫 예시는 **현재 소스의 명령 형식**이며 이 문서 작성 시 새 실행한 결과를 뜻하지 않는다.

```powershell
$evidence = '.local-tools/harness-' + [guid]::NewGuid().ToString('N')
./verify.ps1 -Offline -EvidenceDirectory $evidence
```

영향·등록 소비 계약을 정한 집중 실행 예시는 `./verify.ps1 -Offline -DbGroups 'site-maintenance-desktop,site-maintenance-mobile' -EvidenceDirectory <새 .local-tools 경로>`다. `-ChangedContracts` 선택은 [영향표·등록 기준](verification-harness.md)을 따른다. 선택 옵션 없는 실행은 전체이며 루트는 DB 브라우저도 포함한다. **루트에는 `-RunBrowser` 인수가 없다.** 그 인수는 하위 `backend/verify-db.ps1` 계약이고 루트가 전달한다.

별도 검증 서버는 `-TestPostgresPort <새 전용 포트> -TestPostgresInstance <서버 UUID> -TestPostgresContainer <컨테이너 이름>`을 함께 지정한다. 기본은127.0.0.1:15433이며 개발15432를 별도 검사 서버로 지정할 수 없다. 이름/포트/instance 확인 누락 시 기본 서버로 우회하지 않는다. `.env`와 서버의 실제 자격도 일치해야 한다. `-PhoneEmulatorReady`는 내부 전달 경로이며 에뮬레이터 준비를 생략하는 사용자 shortcut이 아니다.

정상 종료와 새 경로의 `result.json`을 함께 확인한다. `status=passed`는 무선택 전체, `selected-passed/certificationEligible=false`는 명시 선택 통과다. 둘 다 `preservation=passed`, 필수 assertion 충족·실패/skip0이 필요하다. 선택 범위의 작업 완료 적합성은 같은 소스 독립 검토로 판단하며 선택 통과를 전체 인증으로 바꾸지 않는다. 인증/공통 금융/worker/공유 schema/fixture/하네스 실행 변경 또는 통합 마감에는 [전체 필요 조건](development-workflow.md)을 적용한다.

`inputs.json`은 실행 소스, `selection.json`은 선택·제외, `coverage.json`/XML/브라우저 report·deadline·명령 로그는 실제 assertion, `before.json/after.json`은 기존 환경 보존 근거다. `-Offline`은 빠른 검사나 skip 옵션이 아니다. 캐시 누락·Docker 권한·과거 입력 부재·준비 실패는 원 로그와 **미검증 범위**를 남긴다. `-ResourceOnlyDiagnostic`은 정상 전체/선택 성공으로 마감할 수 없다. 증거·실패 DB/media를 삭제하거나 전체 Docker 정리를 실행하지 않는다.

## 6. 독립 Compose 실행 — AC16 명시 입력

[E 재현 driver](../infra/p12-e/reproduce.cjs)는 산출물·도구·증거 경로를 **명시 입력 JSON**으로 받으며 PowerShell 자식은 위 고정 7.6.9 계약을 따른다. 저장소 루트에서 준비된 Node24 실행 파일로 호출한다. Docker Compose2.24 이상(`!override` 지원), PowerShell 7.6.9의 네이티브 `Get-NetRoute`, Docker 접근/고정 이미지 캐시, lockfile로 설치된 `frontend/node_modules`가 필요하다. driver는 의존 다운로드나 이미지 pull을 하지 않는다.

| JSON 필드 | 전달할 절대 경로 |
| --- | --- |
| `evidence` | 아직 존재하지 않는 이번 실행 결과 경로 |
| `jar`, `dist` | 검증된 API JAR과 production dist. dist에는 index와 모든 해시 일치 파일 필요 |
| `jdk`, `node`, `browsers` | `bin/jar.exe`·`javac.exe`가 있는 JDK root, Node 실행 파일, Playwright Chromium cache root |
| `jarProvenance` | JAR의 `apiJarSha256/workerJarSha256`을 확인할 package-proof.json |
| `distProvenance` | build mode와 `files[{file,sha256}]`을 확인할 package-dist.json |

각 값을 자신의 준비물로 채운 `.local-tools/p12-e-request-new.json`을 먼저 만든다. [실제 request2 입력](../.local-tools/p12-e-request2.json)은 D full3의 검증 JAR·package-runtime production bundle/provenance를 사용한 사례이며, 그 증거 경로를 다른 checkout에도 존재한다고 가정하지 않는다. 명령 형식은 다음과 같다. 과거 evidence 경로 재사용은 거부된다.

과거 D 산출물이 없다면 [명시 빌드 준비 도구](../infra/p12-e/prepare-artifacts.cjs)를 사용할 수 있다. JSON에 `output`(존재하지 않는 새 경로), `jdk`(JDK root), `node`(실행 파일), `npmCli`(설치된 npm의 `npm-cli.js`), `mavenHome`, `mavenUserHome`(그 아래 `repository` 캐시)의 절대 경로를 명시하고 저장소 루트에서 `node infra/p12-e/prepare-artifacts.cjs <입력 JSON 경로>`를 실행한다. 준비된 프런트 잠금 파일 의존성과 Maven offline cache가 필요하며 자동 설치·다운로드는 하지 않는다. 일반 backend/target·frontend/dist 빌드 결과를 새 output에 복사하고 JAR·production dist·출처 JSON을 생성한다. 사용 중인 자기 개발 빌드와 동시에 실행하지 않는다. 이 단계의 `testExecution=false`는 패키지 준비이지 테스트 통과가 아니다. 생성된 `marketplace.jar`, `dist`, `package-proof.json`, `package-dist.json`을 위 재현 입력에 연결한다. 사용자별 경로를 명시하므로 과거 개인 경로나 D의 비공개 원증거가 필수 입력이 아니다.

```powershell
$requestPath = (Resolve-Path '.local-tools/p12-e-request-new.json').Path
& ./.local-tools/node-v24.22.0-win-x64/node.exe infra/p12-e/reproduce.cjs $requestPath
if ($LASTEXITCODE -ne 0) { throw 'Independent reproduction failed; retain its evidence' }
```

driver는 입력 artifact를 새 결과 경로에 staging하고 JAR의 class/library에 대해 **계정 준비 전용 helper**를 컴파일한다. 그 helper는 주문/결제/정산의 기대 결과를 만들지 않는다. 새 project·기존 경로와 겹치지 않는 private /28 network·DB/media volume에서 C의 public-demo Compose를 실행하고 desktop/mobile 업무를 실제 API/화면/DB/worker assertion으로 확인한다. 종료 시 자신이 만든 project를 정지하되 컨테이너·volume·evidence를 보존한다. `down -v`나 기존 서비스 정리는 하지 않는다. 명령 종료 코드와 결과/proof, 문서에 연결된 업무 assertion·기존 자원 보존 및 같은 소스 독립 판정을 함께 확인한다. **실제 실행 결과·실패·AC16 최종 판정은 [P12-E](work-items/P12-E.md)를 따른다.** 이 명령 예제 자체는 통과 근거가 아니다.

## 7. 읽을 수 있는 결과와 남은 한계

최신 P12-D 수용은 [full3 전체 결과](../.local-tools/p12-d-full3-df330177d05144dfa7d9bf1182ce161e/result.json)와 [DIAG1 최종 영향 선택](../.local-tools/p12-d-diag1-final-4223da6208ed49949301a1be5f6728b6/result.json)을 [독립 검토](../.local-tools/p12-d-implementation-review-20261116/final-review.md)가 결합 수용한 것이다. 최종1063입력 manifest 자체의 무선택 전체 PASS가 아니다. 과거 실패와 public-demo 제한을 유지한다. AC16의 변경 전 다른 PC/OS 조건 및 당시 미검증 설명은 역사이며, 현재 독립 컨테이너 조건의 판정은 [P12-E](work-items/P12-E.md)를 따른다.

실행 가이드는 명령을 읽고 따라갈 수 있게 제공한 문서다. 사용자 본인의 직접 실행·학습 확인과 자동 assertion 결과는 별도로 기록한다. 화면을 본 사실·검사 총 개수만으로 금액·권한·복구 검증을 대신하지 않는다.

## 역사 부록 — 기존 P01 종합 안내와 후속 단계별 기록

아래 본문은 P12-E 정비 전 안내를 그대로 보존했다. **당시 도구·검사 수·미구현 범위·개인 호스트 경로·명령 옵션은 역사이며 현재 실행 절차는 위 안내가 우선한다.** P01/P02 등의 원 작업 기록과 원 실패/통과 사실을 현재 숫자나 정책으로 소급 고치지 않는다.

### 로컬 실행과 검사 — P01 종합 기준(보존 원문)

P02-A2 인증 API는 `db` 웹 프로필에서 활성화된다. 로컬 HTTP에서만 `local-http`를 추가한다. SESSION 쿠키의 기본 Secure=true 때문에 HTTP에서 이 프로필을 빼면 로그인 유지가 되지 않을 수 있다. 가입 테스트 도구까지 필요하면 `local-mailbox`를 추가한다. 기존 DB를 자동 갱신하지 말고 새 격리 DB를 쓰며, 이번 단계의 재현 명령은 `./backend/verify-db.ps1 -EvidenceDirectory .local-tools/a2-새로운이름`이다. 이 명령이 임시 서버와 새 DB를 구성한다. [A2 계약·검증·한계](work-items/P02-A2.md)를 먼저 확인한다. React 가입·확인·로그인 화면은 P02-A3에서 구현했다. 실제 외부 메일은 발송하지 않는다. 인증 브라우저 검증은 ./backend/verify-db.ps1 -EvidenceDirectory .local-tools/a3-새로운이름 -RunBrowser로 별도 실행한다. 최신 P03-C 기준 PC·모바일18개를 실행하며 기본130개·DB통합58개도 함께 검증한다. [매대 운영 작업 기록](work-items/P03-C.md)을 확인한다. [판매 신청·심사 계약](work-items/P03-A.md)을 확인한다. [R1 최신 검증](work-items/P02-R1.md)과 [D 범위](work-items/P02-D.md)를 확인한다. 운영 권한과 별도 로컬 초기 설정 명령은 [C 기록](work-items/P02-C.md)을 따른다. 복구·프로필·배송지 계약과 새 DB 실행은 [B 기록](work-items/P02-B.md)을 따른다. 자세한 메일 확인·재현 방법은 [A3 기록](work-items/P02-A3.md)을 따른다.

현재 검증 호스트는 Windows x64·PowerShell 7이며 루트는 `D:\Workspace\ai-portfolio-practice`다. 아래 명령은 저장소 루트에서 시작한다. 시스템 PATH·JAVA_HOME을 영구 변경하지 않는다.

## 준비된 도구

| 도구 | 버전 | 이 호스트의 프로젝트 상대 경로 |
| --- | --- | --- |
| JDK | Temurin 21.0.12.1+1 | `.local-tools/jdk-21.0.12.1+1` |
| Maven / Wrapper | 3.9.16 / 3.3.4 | `backend/mvnw.cmd`, `.local-tools/m2/wrapper` 캐시 |
| Node / npm | 24.22.0 / 11.19.1 | `.local-tools/node-v24.22.0-win-x64` |
| Git | 2.45.1.windows.1 | `D:\Tools\Git\cmd\git.exe` |

도구 바이너리·캐시·빌드 결과는 Git에 넣지 않는다. 새 PC는 [JDK·Maven 준비](work-items/P01-B1.md), [Node 준비](work-items/P01-B2.md)의 공식 다운로드·체크섬 절차를 따른다. 준비된 도구를 복제한 것으로 간주하지 않는다. 다른 OS에서 `npm.ps1`의 Windows ZIP 경로를 그대로 쓸 수 없으며 Node/npm을 준비한 뒤 frontend에서 npm 명령을 실행한다. Maven은 Java 21 JAVA_HOME 지정 후 `./mvnw`로 실행한다. Linux/macOS 실행은 이번에 검증하지 않았다.

## Java 빌드와 실행

```powershell
$env:JAVA_HOME = Join-Path $PWD '.local-tools/jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME = Join-Path $PWD '.local-tools/m2'
Push-Location backend
try {
    ./mvnw.cmd -B -ntp '-Dmaven.repo.local=../.local-tools/m2/repository' clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Java build failed' }
} finally { Pop-Location }
```

Checkstyle은 validate에서 먼저 실행되며 위반 시 빌드를 멈춘다. 기본 Java 검사는 130개이며 실제 DB 통합 검사는 포함하지 않는다. 플러그인 3.6.0(현재 의존 엔진 Checkstyle 9.3)과 최소 규칙은 [pom](../backend/pom.xml), [규칙](../backend/config/checkstyle.xml)에 있다. 역할은 [Maven 공식 설명](https://maven.apache.org/plugins/maven-checkstyle-plugin/usage.html)을 참고한다. 자동 포맷터는 도입하지 않았다.

설정이 필요한 경우 예제를 `backend/application-local.properties`로 복사하고 값을 편집한다. 기존 로컬 파일은 덮어쓰지 않는다. `local` 프로필은 기본 활성화되지 않는다. 아래 실행은 **backend 폴더를 작업 디렉터리로 사용**해야 외부 파일을 찾는다.

```powershell
Push-Location backend
try {
    if (!(Test-Path application-local.properties)) {
        Copy-Item application-local.properties.example application-local.properties
    }
    & "$env:JAVA_HOME/bin/java.exe" -jar target/marketplace-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
} finally { Pop-Location }
```

서버는 Ctrl+C로 종료한다. 기본 예제는 127.0.0.1:8080이며 이미 사용 중이면 기존 프로세스를 임의 종료하지 않는다. 다른 포트를 쓰면 현재 Vite 프록시 대상도 별도 변경해야 한다. `Invoke-RestMethod http://127.0.0.1:8080/actuator/health`의 예상 응답은 status UP이다. DB·인증·업무 기능의 정상 판정은 아니다.

## React 빌드와 실행

```powershell
./frontend/npm.ps1 ci
./frontend/npm.ps1 run typecheck
./frontend/npm.ps1 run lint
./frontend/npm.ps1 run build
./frontend/npm.ps1 test
./frontend/npm.ps1 run dev '--' --port 5173 --strictPort
```

명령은 순서대로 성공한 뒤 다음을 실행한다. npm.ps1은 실패하면 예외를 던진다. ci는 잠금 파일에 따라 node_modules를 재설치한다. 실행 중 서버가 사용한다면 먼저 자신이 시작한 서버를 종료한 뒤 수행한다. 빌드 미리보기는 `./frontend/npm.ps1 run preview '--' --port 4174 --strictPort`다. 개발과 preview는 8080으로 프록시하며 공개 운영 서버가 아니다. 테스트는 임시 포트와 모의 upstream을 생성·종료한다. 샌드박스 EACCES는 앱 오류와 구별하고 허용된 실행 환경에서 재검증한다.

현재 사용자 정의 환경 변수는 필요 없다. [frontend/.env.example](../frontend/.env.example)은 보안 안내다. VITE_*는 브라우저에 공개될 수 있으므로 암호·토큰·DB 주소를 넣지 않는다. 백엔드는 Spring 프로필, 프런트는 빌드 도구 환경이라는 차이가 있다. 실제 DB·비밀값 설정은 [C1](work-items/P01-C1.md), JDBC·Flyway는 [C2](work-items/P01-C2.md), 격리 DB 통합 실행은 [D2](work-items/P01-D2.md)를 따른다. D2 실행은 새 전용 DB와 데이터를 생성하므로 기본 검사와 구분한다.

## Git과 직접 확인

로컬 `main`에서 `git status --short`, `git diff`, `git log --oneline`으로 변경을 확인한다. 원격 저장소·push는 이번 범위가 아니다. `.gitignore`는 도구·캐시·빌드·IDE·로컬 설정·키 파일을 제외한다. 이것만으로 모든 비밀값을 탐지하지는 못하므로 커밋 전 `git diff --cached`와 파일 목록을 점검한다. 공유 가능한 설정 예제와 package-lock·Maven Wrapper는 추적한다.

학습 실습: Java에 쓰지 않는 import를 잠시 추가하면 validate가 실패하는지 확인하고 원복한다. React에는 사용하지 않는 변수를 추가해 lint 오류를 비교한다. 자동 검사 결과와 사용자의 직접 실습 완료는 별개다.

## 단위·브라우저 검증

저장소 루트에서 아래 순서로 실행한다. JAVA_HOME과 MAVEN_USER_HOME은 위 Java 준비와 같다. MAVEN_USER_HOME은 Wrapper 캐시이며 의존성 저장소는 -Dmaven.repo.local로 별도 지정한다.

```powershell
./frontend/npm.ps1 run test:unit
./frontend/npm.ps1 test
./backend/mvnw.cmd -f backend/pom.xml "-Dmarketplace.build.directory=$PWD/.local-tools/d4-build" verify
./frontend/npm.ps1 run build
./frontend/npm.ps1 run test:e2e
```

각 명령의 성공을 확인한 뒤 다음을 실행한다. 현재 Vitest 73개·Playwright 16개다. Node 68개는 B에서 통과한 기록이며 C·D에서는 전송 코드 변경이 없어 재실행하지 않았다. Playwright는 최신 JAR와 dist를 자동 재빌드하지 않으므로 위 빌드를 생략하지 않는다. Chromium 준비 방법은 [D4](work-items/P01-D4.md)를 따른다. 18080·24174·24175는 전용 검사 포트이며 점유 시 기존 서버를 종료하지 말고 원인을 확인한다. 실제 DB는 사용하지 않는다. Windows 이외 환경·새 PC 전체 재현은 미검증이다.


## P04-C 이미지 저장소

DB 프로필 서버에는 `MEDIA_ROOT`를 별도로 설정한다. 예: 저장소 밖 정적 서비스와 연결되지 않은 `D:/Workspace/ai-portfolio-practice/.local-tools/media-dev`. 최초 사용 시 빈 디렉터리여야 하며, 다른 DB 또는 실행 중인 서버와 공유할 수 없다. DB namespace 마커 `.database`와 프로세스 `.lock`을 임의로 지우거나 기존 이미지 폴더를 새 DB에 재사용하지 않는다. DB와 이미지 폴더를 함께 보존한다. 기존 서버의 설정이나 데이터에 이번 변경을 적용하지 않았다.

검증은 `./backend/verify-db.ps1 -EvidenceDirectory .local-tools/p04-c-새이름 -Offline -RunBrowser`로 실행한다. 매번 새 무작위 DB와 해당 evidence 하위 `media` 디렉터리를 만들고 실패 결과도 남긴다. 기존 경로를 재사용하지 않는다. 초기 운영자 CLI는 이미지 저장소를 사용하지 않는 명령이므로 media 구성을 비활성화한다.

이미지는 `/api/media/{assetId}/content`의 권한 검사 경로로만 제공한다. 루트를 웹 서버 정적 디렉터리로 매핑하지 않는다. 교체/삭제는 즉시 이전 URL을 막고, 실제 파일 정리는24시간 유예 뒤 시간별 작업으로 재시도한다.

## P06-C 비회원 조회 자격 확인

조회 화면은 `/guest/orders/login`과 `/guest/orders`다. 주문번호·등록 전화번호·직접 정한 비밀번호가 모두 일치해야 해당 구매자 묶음의 주문 참조를 볼 수 있다. 구매 인증 화면 `/checkout/guest`에서 비밀번호를 준비해도 실제 주문이나 조회 권한이 바로 생성되지는 않는다.

전체 재현은 저장소 루트의 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/<새 이름>`으로 한다. 하네스가 새 로컬 제공자·격리 DB·미디어를 만들고, 테스트 소스에만 있는 가상 주문 fixture를 통해 다른 브라우저의 조회·만료·로그아웃을 검증한다. 기존 서버/DB에 마이그레이션하거나 데모 주문을 추가하지 않는다. 준비/조회는 명시적인 로컬 제공자 설정이 없으면503으로 닫히며, 일반 실행에는 가상 주문 생성 경로가 없다. 실제 주문 생성과 변경·복구 기능은 아직 범위 밖이다. [검사·실패 기록](work-items/P06-C.md)을 따른다.

## P06-D 변경·복구 확인

`/guest/security`에서 조회 로그인과 현재 비밀번호로 비밀번호 변경 또는 복구 코드 재발급을 한다. `/guest/recovery`는 주문번호·등록전화·복구코드를 확인하고 별도 모의 전화 재인증을 거친 뒤 새 비밀번호를 정한다. 코드와 재설정 권한은 저장소/URL에 보관하지 않으며 새 코드는 응답에서만 보여준다. 변경/재설정 후 모든 이전 조회세션을 폐기하므로 새비밀번호로 다시 로그인해야 한다.

가상 과거주문과 최초코드는 하네스의 테스트소스fixture에서만 제공한다. 기존 데모DB에 주문을 추가하지 않는다. 구매와 복구는 같은 제공자의 전화재발송제한을 공유하므로 방금 구매인증한 번호로 즉시 복구인증을 시작하면 제한될 수 있다. 응답유실·재시작·코드분실지원안내와 실제SMS/운영 미검증은 [P06-D 기록](work-items/P06-D.md)을 따른다. 검증은 루트 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/<새 이름>`으로 새 격리 환경에서 실행한다.

## P06-E 재구매 준비

`/guest/orders/login`에서 기존 주문 조회 인증 후 `/checkout/guest`에서 같은 번호의 현재 구매 전화 인증을 마친다. '기존 묶음 연결'은 기존 비밀번호를 유지하고, '새 묶음 선택'은 같은 번호라도 별도 조회 비밀번호를 준비한다. 다른 번호는 새 묶음으로만 준비할 수 있다. 준비 결과는 실제 주문 생성/결제 성공을 뜻하지 않는다.

응답 유실이나 다른 탭의 선택 충돌 때 '서버 준비 상태 다시 확인'으로 복원한다. 서버 확인 실패 중에는 준비 완료나 새 비밀번호 폼을 표시하지 않는다. 연결 거부가 발생하면 기존 조회 자격·번호·현재 인증의 유효시간을 확인하고 다시 인증한다. 로컬 모의 인증과 기존60초 재발송 제한을 유지한다. 현재 주문 생성은 테스트 전용 fixture이며 제품 호출자는 P08-C에서 연결한다. 상세 검사·실패 이력은 `docs/work-items/P06-E.md`를 따른다.
