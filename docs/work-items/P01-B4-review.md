# P01-B4 검토·수정·재검증

검토일: 2026-10-23 (Asia/Seoul)  
판정: **R1~R3 유지, R4 수정 및 개발·preview 회귀 검증 완료. 사용자 직접 확인·학습 완료는 미확인. B5 미착수.**

## 2026-10-23 R4 수정 후 최신 검증

지정된 검토 및 반영 세션에서 발견한 R4를 개발 세션에서 수정하고 검증했다. 아래 추가 검사 당시 실패 기록과 증거는 보존한다. 이번 수정에 대한 별도의 독립 재검토 완료를 뜻하지 않는다.

제품 변경은 `frontend/vite/apiProxy.ts`에 한정한다. upstream의 `aborted`·`error`·불완전 `close`를 downstream `destroy()`로 연결했다. 이미 헤더를 보낸 응답의 오류도 연결을 종료한다. 잘린 JSON을 `end()`로 정상 완료시키지 않으므로 브라우저 본문 읽기가 실패하고 기존 R3의 network 분류가 작동한다. 클라이언트가 먼저 취소하면 upstream도 닫는다. 헤더 전 JSON 502, 경로 차단 규칙은 유지한다.

`frontend/tests/proxy-stream.test.mjs`는 실제 Vite 개발·preview와 임시 HTTP 서버를 사용한다. 무응답, 부분 본문 정지, 강제 종료, 12초 연속 전송, 본문 수신 중 취소, 각 조건 후 정상 재시도를 검사한다. 수정 전 12개 중 6개 실패(부모 테스트 포함)를 확인한 뒤 수정했다. [실패 기록](evidence/P01-B4-R4-red.txt)과 [수정 후 전체 68개 통과 기록](evidence/P01-B4-R4-green.txt)을 분리했다. 타입 검사·린트·빌드는 각각 종료 코드 0이며 빌드 모듈은 102개다.

| 조건 | 개발 / preview 실제 HTTP | 내장 브라우저 최종 확인 |
| --- | --- | --- |
| 헤더 없는 무응답 | 10,031 / 10,029ms, JSON 502 | 양쪽 연결 오류, 재시도 정상 |
| 부분 JSON 후 정지 | 10,030 / 10,029ms, ECONNRESET | 양쪽 연결 오류, 재시도 정상 |
| 부분 JSON 후 강제 종료 | 326 / 326ms, ECONNRESET | 양쪽 연결 오류, 재시도 정상 |
| 250ms마다 전송, 12초 완료 | 12,035 / 12,035ms, 정상 UP | 양쪽 정상 연결 |
| 취소·새 요청·화면 이탈 | 자동 회귀 검사 통과 | 취소 표시, 늦은 요청 차단, 홈 복귀 초기 상태·재시도 정상 |

[HTTP 8건](evidence/P01-B4-R4-http.json), [UI 원시 관찰](evidence/P01-B4-R4-browser.json), [upstream 이벤트](evidence/P01-B4-R4-upstream.json), [검사 도구](evidence/P01-B4-R4-harness.txt)를 저장했다. UI 원시 관찰 38건 중 최종/복구 확인 32건은 기대 상태와 일치한다. 나머지 6건은 초기 diff 또는 timeout 전 중간 관찰로, 최종 실패 판정이 아니다. 브라우저 대기 도구가 지정한 16초 대신 약 3초에 종료되어 11초 대기 후 전체 상태로 재검사했다. 최종 `stall-final`, `no-headers-final`은 양쪽 모두 오류 종료를 확인한다. [오류 화면](evidence/P01-B4-R4-error.png)과 [복구 화면](evidence/P01-B4-R4-recovered.png)을 보관했다.

이번 검사는 임시 upstream 2467, 개발 2468, preview 2469에서 수행했다. 실제 Boot 장애 재시험은 아니다. 시작 시 기존 PID 44820·61828과 8080·5174 리스너는 없었고 종료 시에도 없었다. 이들을 재시작·중단·교체하지 않았다. 자체 harness PID 58900과 세 포트는 검사 후 종료를 확인했다.

남은 제한: `proxyTimeout: 10_000`은 무활동 제한이다. 총 처리 기한을 추가하지 않았으며 연속 전송은 10초를 넘길 수 있다. 무한 연속 전송의 종료는 보장하지 않는다. 공개 배포 프록시·DB·인증·업무 API는 범위 밖이다. 학습 확인으로 부분 본문 정지와 연속 전송을 비교하고, `upstream.complete`와 연결 종료가 왜 다른지 설명해 볼 수 있다. 사용자 설명·실습 수행은 미확인이다.

## 이전 검사 이력 읽기

이하의 “미해결”, “이번 작업에서는 수정하지 않았다”는 R4 발견 당시 판정이다. 최신 판정은 위 수정 후 결과를 따른다.

## 2026-10-23 추가 UI·timeout 검증 결과

사용자가 요청한 수정 후 UI 동작과 실제 timeout 만료를 검사했다. 제품 소스·설정·의존성은 수정하지 않았다. 아래의 기존 검토·수정 결과는 이전 검증 범위의 이력이며, 현재 완료 판정에는 이번 실패도 포함한다. **B5는 미착수**다.

### 실행 조건과 결과

현재 `apiProxy`와 React 소스를 별도 Vite 개발 서버 14110에서, 기존 빌드 산출물을 preview 14112에서 실행했다. 두 서버의 upstream만 임시 응답 서버 14109로 지정했다. `proxyTimeout: 10_000`은 줄이거나 덮어쓰지 않았다. Node 24.22.0·Vite 8.3.4의 기본 Node HTTP 경로이며 `FORCE_FETCH_PATH`는 설정되지 않았다. 장애 조건은 모의 응답으로 만들었으며 실제 Boot를 중단하거나 DOWN으로 바꾸지 않았다.

| 실제 HTTP 조건 | 개발 서버 / preview 측정 | 판정 |
| --- | --- | --- |
| 헤더도 보내지 않는 무응답 | 10,020 / 10,017ms 후 JSON 502, `UPSTREAM_UNAVAILABLE` | 통과 |
| 200 JSON 헤더와 `{"status":`만 전송 후 멈춤 | upstream은 약 10초에 닫혔으나 두 클라이언트 모두 15,008ms 검사 한도까지 미종료 | **실패, R4** |
| 같은 부분 본문 전송 후 300ms에 연결 강제 종료 | upstream은 311ms에 닫혔으나 두 클라이언트 모두 15,008ms 검사 한도까지 미종료 | **실패, R4** |
| 250ms마다 공백을 전송하고 12초에 `"UP"}`로 완료 | 양쪽 모두 12,023ms 후 HTTP 200·유효한 UP JSON | 관찰: 전체 10초 제한은 아님 |

HTTP 8건은 **2건 통과·4건 실패·2건 제한 관찰**이다. 15초는 제품 timeout이 아니라 검사 코드의 별도 안전 한도다. 한도 도달 후 검사 요청을 취소했으며 영구 대기를 직접 관찰했다고 표현하지 않는다.

내장 브라우저에서 UI 관찰 **27건: 23건 통과·3건 실패·1건 시간 관찰**을 기록했다.

- 통과: 초기 대기, 정상 UP, 503 DOWN, 잘못된 JSON·HTML, 무응답 중 로딩과 10초 만료 후 연결 실패 안내, 정상 응답으로 재시도, 헤더 전·본문 대기 중 취소, 진행 중 요청 교체, 이전 지연 시간 경과 후 최신 상태 유지, 화면 이탈·복귀, 홈 입력, 구매자 주문·판매자 상품·운영자 회원 화면의 이동·새로고침.
- 실패: 개발 화면의 본문 멈춤(15,301ms)·수신 중 연결 단절(15,293ms), preview의 본문 멈춤(15,321ms)에서 여전히 **“서버 연결을 확인하고 있습니다.”**가 표시됐다. 취소와 다시 확인으로는 복구할 수 있었다.
- 시간 관찰: 계속 조금씩 전송되는 응답은 개발 화면에서도 12,091ms 후 정상으로 표시됐다. preview의 무응답은 10,074ms 후 연결 실패로 표시됐다. 브라우저 시간은 버튼 조작부터 결과 관찰까지의 시간이고 정확한 통신 시간은 위 HTTP 기록을 따른다.
- 요청 교체 검사에서는 브라우저의 취소가 실제로 적용됐다. 취소를 무시하는 응답까지 브라우저에서 재현한 것은 아니며 그 조건은 기존 요청 순번 단위 검사 범위다.
- 마지막에는 기존 5174 화면 → 기존 8080 Boot 연결의 정상 안내를 확인했다. 사용자 기존 탭은 조작하지 않았고 검사 탭만 닫았다.

기존 `npm.ps1 test`도 다시 실행해 **56개 통과, 실패·건너뜀 0, 종료 코드 0**을 확인했다. 이 테스트 통과가 이번 실제 스트림 실패를 부정하지 않는다. 기존 테스트는 직접 만든 Response의 읽기 실패와 프록시의 연결 전 오류를 검사하지만, 실제 프록시가 부분 응답을 받은 뒤 끊기는 경로의 검증이 부족하다. 소스 변경이 없어 타입·린트·빌드는 이번에 재실행하지 않았으며 아래의 이전 결과와 구분한다.

### R4 · P2 · 부분 응답 이후 upstream 종료가 화면 요청을 끝내지 못함 — 미해결

재현 흐름은 **부분 JSON 수신 → upstream 중단/idle timeout → Vite의 downstream 응답 미종료 → 브라우저 `response.json()` 대기 → UI 로딩 유지**다. 기존 R3 수정은 본문 읽기가 예외를 던진 경우를 올바르게 분류한다. 이번 경로에서는 예외가 브라우저까지 전달되지 않아 그 분류 코드에 도달하지 않는다.

설치된 Vite의 `node_modules/vite/dist/node/chunks/node.js`에서 기본 HTTP 경로는 `proxyReq.setTimeout()`으로 upstream을 종료하고, 응답은 `proxyRes.pipe(res)`로 전달한다. 제품의 프록시 error 처리와 Vite의 기본 HTTP error 처리는 모두 헤더 전송 전 응답에만 502를 만든다. `apiProxy.ts`에는 `proxyRes`의 불완전 종료를 downstream 종료로 연결하는 처리가 없다. 실제 HTTP 기록은 upstream 종료와 downstream 미종료를 각각 확인한다.

수정 후 재검증 기준: 개발·preview에서 부분 본문 중단과 idle timeout을 회귀 검사로 고정하고, 이미 200 헤더를 전송한 경우 downstream을 정상 완료로 가장하지 않고 전송 실패로 끝내야 한다. UI가 연결 실패를 표시하고 재시도·취소가 유지되는지 확인한다. 헤더 전 오류의 JSON 502 계약도 보존해야 한다. **이번 작업에서는 수정하지 않았다.**

### T2 · timeout 의미 확인 — 전체 요청 시간은 제한하지 않음

현재 기본 HTTP 경로의 `proxyTimeout`은 소켓의 무활동 시간 기준이다. 데이터가 계속 오면 총 10초를 넘길 수 있다. 따라서 기존 설명의 “응답 대기는 10초 제한”을 전체 응답 완료 기한으로 이해하면 안 된다. UI·HTTP 모두 12초 응답 완료를 확인했으며 더 긴 무한 전송까지 실행하지 않았다.

health 확인에 별도 총 처리 기한이 필요한지는 후속 수정에서 명시하고 검사해야 한다. 공통 업무 API 전체에 같은 시간을 강제하는 결정은 하지 않았다. Java의 소켓 read timeout과 전체 작업 deadline이 다른 것처럼, **마지막 데이터 이후 대기 시간**과 **버튼을 누른 뒤 전체 경과 시간**을 구분한다.

### 증거·서버 보존·재현

- [HTTP 8건과 upstream 종료 시각](evidence/P01-B4-followup-http.json), [전체 임시 upstream 이벤트](evidence/P01-B4-followup-upstream.json)
- [UI 27건](evidence/P01-B4-followup-browser.json), [무응답 timeout 화면](evidence/P01-B4-followup-timeout.png), [본문 중단 후 로딩 유지 화면](evidence/P01-B4-followup-stalled.png), [기존 서버 정상 연결 화면](evidence/P01-B4-followup-healthy.png)
- [기존 56개 테스트 재실행](evidence/P01-B4-followup-tests.txt), [기존 검토 대상 16개 파일 해시 동일](evidence/P01-B4-followup-files.json), [서버 검사 전후](evidence/P01-B4-followup-servers.json)
- [사용한 검사 스크립트 원문](evidence/P01-B4-followup-harness.txt). 자동 합격 판정기가 아니라 조건별 응답·시간을 수집하는 검사 도구다. 실행 종료 코드 0을 제품 검사 전체 통과로 해석하지 않는다.

기존 8080(PID 44820)·5174(PID 61828)는 검사 전후 PID·프로세스 시작 시각이 같고 계속 LISTEN 상태다. 임시 14109·14110·14112만 종료·반환했다. 검증 도중 스크립트의 문법 오류와 서버 비교 코드의 날짜 타입 비교 오류는 검사 도구에서 바로잡았고 제품 결함으로 집계하지 않았다. 브라우저 선택자 대기 한도가 먼저 만료된 시도도 제품 timeout 결과로 집계하지 않고 실제 상태를 다시 관찰했다.

프로젝트 루트 PowerShell에서 다음과 같이 새 검사 폴더를 만들어 재현할 수 있다. 기존 Node·npm 패키지가 준비되어 있어야 한다. 이 명령은 서버를 새 임시 포트에 띄우고 결과를 기록하며, 기존 포트를 사용하지 않는다.

```powershell
$runPath = Join-Path (Get-Location) ('.local-tools/b4-timeout-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runPath | Out-Null
Copy-Item docs/work-items/evidence/P01-B4-followup-harness.txt (Join-Path $runPath 'harness.mjs')
Write-Output $runPath
& ./.local-tools/node-v24.22.0-win-x64/node.exe (Join-Path $runPath 'harness.mjs') --probe
```

약 15초 뒤 `http-before.json`에 결과가 남고 UI 검사를 위해 서버는 대기한다. 출력된 `dev`/`preview` 주소를 브라우저에서 열고 `mode.json`의 `mode`를 `up`, `down`, `malformed`, `html`, `no-headers`, `stall`, `reset`, `trickle`, `delayed-down` 중 하나로 바꾼 뒤 연결 확인을 누른다. 종료 시에는 다른 터미널에서 **이번에 출력된 검사 폴더**에 `stop` 파일을 만들면 된다. 예: `Set-Content '<검사 폴더 절대 경로>/stop' 'stop'`. 폴더·포트는 실행마다 달라지며 8080이나 5174를 종료할 필요가 없다.

작은 학습 예시: `no-headers`와 `trickle`의 예상 결과를 먼저 적고, 각각의 `elapsedMs`·`outcome`과 화면 문구를 비교한다. 이어 `stall`에서 취소를 눌러 요청 실패 처리와 사용자 취소의 차이를 설명한다. 사용자 직접 실습·이해 확인은 아직 **미확인**이다.

## 기준과 범위

[P01-B4 승인 계획과 개발 결과](P01-B4.md), [기술 설계](../shop-design.md), [ADR-001](../decisions/ADR-001-java-spring-react.md)을 실제 파일과 대조했다. Git 저장소가 없으므로 커밋 간 변경 검토가 아니라 현재 파일·계약 기준 검토다. 수정 전 소스·설정·검사 파일 15개를 `.local-tools/p01-b4-review-before`에 보관하고 SHA-256을 기록했다. 수정 후에는 `git diff --no-index`로 해당 보관본과 차이를 확인했다. Git 초기화·커밋·B5 이후 구현은 하지 않았다.

대상: `vite/apiProxy.ts`, `vite.config.ts`, `src/api/http.ts`·`health.ts`, `src/components/HealthPanel.tsx`, `HomePage.tsx`·`styles.css`, `tests/*.test.mjs`, `verify-proxy.ps1`, package/lock·TypeScript·ESLint 설정이다. 백엔드와 기존 8080·5174 개발 서버를 직접 수정·중단하지 않았다.

## 발견 사항과 반영

### R1 · P2 · API 접두사 검사 후 경로가 바뀌어 관리 경로로 전달됨 — 수정

기존 코드는 원래 문자열이 `/api/`로 시작하면 프록시했다. 하지만 프록시의 후속 URL 처리에서 상위 경로 이동 구간이 정리되므로 다음 요청이 허용 목록 밖으로 나갔다.

```text
요청: /api/../actuator/env
수정 전 임시 upstream 수신: /actuator/env

요청: /api/%2e%2e/actuator/health
수정 전 임시 upstream 수신: /actuator/health
```

Node의 `http.request`로 원래 요청 경로를 전송해 실제 Vite 개발 서버·미리보기에서 재현했다. 일반 fetch는 요청 전에 점 구간을 정리할 수 있어 이 재현에 사용하지 않았다. 현재 Boot는 env 등을 노출하지 않으므로 **실제 비공개 정보 유출을 확인한 것은 아니다.** 문제는 프록시의 정확한 경로 제한을 우회한다는 점이다.

`requestPathGuard`에서 프록시 전에 점 구간·역슬래시·연속 슬래시·세미콜론·제어 문자·경로의 인코딩된 슬래시·중복 디코딩 가능 표현을 거부했다. 모호한 경로를 다른 주소로 고쳐 보내지 않고 JSON 400으로 끝낸다. 직접 요청한 비허용 관리 경로는 기존 JSON 404를 유지한다.

### R2 · P2 · 인코딩된 API 주소가 화면 HTML로 처리됨 — 수정

`/%61pi/missing`, `/api%2fmissing`은 원문 프록시 정규식과 일치하지 않아 HTTP 200·SPA HTML을 반환했다. `/actu%61tor/%zz`처럼 유효하지 않은 인코딩이 섞이면 기존 guard가 원문으로 되돌아가 JSON 오류 응답 계약도 유지하지 못했다.

디코딩 실패는 JSON 400으로 종료하고, 디코딩한 경로가 API 공간인데 원래 접두사가 정확한 `/api`가 아니면 JSON 404로 끝내도록 했다. `/API/missing` 같은 대소문자 별칭도 화면 fallback으로 보내지 않는다. `/apiary`, `/actuator-help`, `/account/orders`는 API·관리 공간이 아니므로 기존 화면 처리를 유지한다.

R1·R2 수정은 개발·preview에 같은 guard를 사용한다. 현재 경로 계약에서는 세미콜론 경로 파라미터, 인코딩된 슬래시, 중복 인코딩·리터럴 `%`가 들어간 경로 식별자를 지원하지 않는다. 한국어 식별자 인코딩과 쿼리의 `a%2Fb`는 유지됨을 검사했다. 이런 특수 식별자가 업무 요구가 되면 경로 계약부터 재검토한다.

### R3 · P2 · JSON 본문 수신 중 연결 단절을 형식 오류로 분류 — 수정

`response.json()` 실패를 모두 JSON 파싱 오류처럼 처리했다. 정상 HTTP 헤더 이후 본문 스트림이 `TypeError`로 끊기는 경우도 `invalid-response`가 되어 연결 장애와 잘못된 JSON을 구분하지 못했다.

실제 `Response`·`ReadableStream`을 이용한 테스트에서 `invalid-response`가 반환되는 실패를 확인했다. 수정 후 성공 HTTP 응답의 `SyntaxError`는 형식 오류, 그 외 본문 읽기 실패는 `network`로 구분한다. 이미 받은 4xx/5xx의 HTTP 상태는 유지하며, 취소 신호·AbortError 판정을 우선한다. UI는 기존 `network → connection-error` 흐름을 그대로 사용하므로 화면 코드 변경은 필요 없었다.

### T1 · P3 · 본문 읽기 취소 테스트가 실제로는 요청 전 취소만 검사 — 보완

기존 테스트는 ReadableStream의 `start()`에서 즉시 취소했기 때문에 `requestJson()` 진입 전에 신호가 취소되었다. 테스트가 통과해도 fetch나 본문 읽기에 도달했다는 보장이 없었다.

수정한 테스트는 본문 읽기가 시작될 때까지 기다린 뒤 취소하고, fetch 실행 여부와 최종 `cancelled`를 확인한다. 204 빈 응답의 계약 오류와 잘못된 503 JSON의 HTTP 상태 보존 검사도 추가했다.

## 유지한 구현과 판단

- `requestJson`은 전송·응답 해석, `interpretHealth`는 health 계약, `createHealthCheck`는 최신 요청과 취소, HealthPanel은 한국어 표시를 맡는다. 전체 업무 API에 health 구조를 강제하지 않는다.
- 200·UP만 정상이다. 503·DOWN/OUT_OF_SERVICE와 200·UNKNOWN은 정상으로 처리하지 않는다. HTML 성공 응답·잘못된 JSON은 데이터로 쓰지 않는다.
- AbortController와 요청 순번을 함께 사용하므로 취소를 무시한 이전 응답도 최신 결과를 덮지 못한다. dispose는 완료 후 상태 전달을 막고 React effect 정리에서 호출된다.
- 요청은 사용자 버튼으로 시작한다. 자동 반복·자동 결제 재시도 같은 동작을 추가하지 않았다. 서버 오류 원문을 화면에 삽입하지 않는다.
- 502 응답은 정해진 오류 코드만 노출한다. API·관리 경로 오류를 화면으로 넘기지 않는 것이 이번 수정의 핵심이다. 로그인·서버 권한 검사의 대체 기능은 아니다.
- 추가 패키지 설치나 버전 변경은 없다. 기존 Node 테스트 러너·타입·린트 구성을 사용했다. 15개 보관 대상 중 변경은 제품 파일 2개와 기존 테스트 1개이며 나머지 12개는 해시가 같다.

## 기존 검토 당시의 검증 증거

| 검사 | 이번 검토에서 직접 확인한 결과 |
| --- | --- |
| 기존 테스트 기준선 | 제한된 환경에서 22 성공·통합 1개 EACCES 실패. 로컬 통신이 허용된 실행에서 기존 23개 모두 통과 |
| 경로 재현 → 수정 | 신규 경계 검사 30개 중 수정 전 6 성공·24 실패(부모 검사 2개 포함). 수정 후 30개 통과 |
| 본문 연결 단절 재현 → 수정 | 응답 검사 11개 중 수정 전 10 성공·1 실패. 수정 후 11개 통과 |
| 전체 프런트 테스트 | **56개 통과**, 실패·건너뜀 0, 종료 코드 0. Node 집계에는 경계 검사 부모 2개가 포함됨 |
| `run typecheck` | 최종 소스 기준 종료 코드 0 |
| `run lint` | 최종 소스 기준 종료 코드 0, 허용 경고 0 |
| `run build` | 최종 소스 기준 종료 코드 0, 102개 모듈, JS 272.11 kB / gzip 86.77 kB |
| 별도 실제 Boot + Vite | **48건 통과**: UP·DOWN·복구 각각 HTTP 11건 + 공통 요청/health 해석 1건 + 우회 경로 차단 3건 + 소유 Boot 중단 후 502/연결 오류 1건 |
| 서버 정리 | 최종 검사 포트 7683·7684 반환, 기존 8080·5174는 계속 LISTEN 상태 |

실제 Boot 검사는 제품 코드·설정을 바꾸지 않고 같은 실행 JAR를 별도 포트에 띄웠다. DOWN에는 `--management.health.diskspace.threshold=9223372036854775807B` 인수만 사용했으며 디스크를 채우지 않았다. 복구에서는 해당 인수를 제거했다. health의 status 단일 필드·JSON Content-Type·200/503·POST 405·미구현 API와 관리 경로 404를 확인했다.

증거 파일:

- [최종 전체 테스트 출력](evidence/P01-B4-review-tests.txt)
- [별도 실제 Boot·Vite 검사 48건](evidence/P01-B4-review-live.json)
- [수정 전후 파일 해시](evidence/P01-B4-review-files.json)

검증 과정에서 제어 문자 정규식이 린트 규칙에 걸려 문자 코드 비교로 바꿨고, 기존 `/actuator%2fenv`의 404를 유지하도록 guard 순서를 맞췄다. 이때의 실패는 최종 통과로 계산하지 않았다. 임시 통합 검사 스크립트가 이미 종료된 Java를 다시 기다리는 정리 오류도 수정해 전체 검사·종료 코드 0·포트 반환까지 다시 확인했다. 제품의 프로세스 종료 로직을 바꾼 것은 아니다.

재실행은 프로젝트 루트 PowerShell 7에서 한다.

```powershell
.\frontend\npm.ps1 test
.\frontend\npm.ps1 run typecheck
.\frontend\npm.ps1 run lint
.\frontend\npm.ps1 run build
```

프록시 테스트는 임시 로컬 포트를 직접 생성·반환한다. 소켓 접근이 차단된 환경에서는 EACCES를 기능 실패와 구분하고, 접근 가능한 환경에서 다시 실행해야 한다. 테스트를 생략해 통과로 표시하지 않는다.

## 미검증·후속 조건

- 이전 검토에서 미검증으로 남겼던 수정 후 UI·10초 timeout·연속 전송 조건은 위 추가 검증에서 실행했다. **R4 부분 응답 종료 결함은 미해결**이며 총 처리 기한은 보장하지 않는다. 수정 및 해당 회귀 검사는 후속이다.
- 다른 브라우저·실제 휴대전화·스크린리더는 이번에도 검증하지 않았다. 모바일 폭·키보드 검사를 추가로 반복한 결과는 없으며 이전 개발 세션 결과와 구분한다.
- React DOM 자동 E2E 스위트, 공개 배포 서버의 관리 경로 차단, 인증·CSRF·DB·주문·결제는 후속이다. 이 문서는 전체 보안 감사나 전체 쇼핑몰 완료 판정이 아니다.
- 사용자 직접 수정 실습·설명·확인은 **미확인**이다. P01-B 전체, 첫 구매, MSA 완료로 확대하지 않는다. 다음 구현 B5는 진행하지 않았다.

## 기존 검토의 변경 목록과 학습 확인

| 파일 | 변경 |
| --- | --- |
| `frontend/vite/apiProxy.ts` | 경로 거부 규칙·API 별칭 차단·JSON 400/404 |
| `frontend/src/api/http.ts` | 본문 수신 장애와 JSON 문법 오류 분리 |
| `frontend/tests/http.test.mjs` | 실제 읽기 중 취소, 빈 응답·오류 JSON·스트림 실패 검사 |
| `frontend/tests/proxy-boundary.test.mjs` | 신규. 실제 dev/preview의 원문 경로·upstream 수신·SPA 경계 회귀 |
| 작업·진행 문서와 evidence | 기존 검토 이력과 추가 UI·timeout 결과, R4 미해결 상태 연결 |

이번 추가 검증에서는 문서 7개를 수정하고 위 증거 파일 10개를 추가했다: 이 검토 문서, [P01-B4](P01-B4.md), [README](../../README.md), [구현 계획](../implementation-plan.md), [개발 흐름](../development-workflow.md), [기술 설계](../shop-design.md), [ADR-001](../decisions/ADR-001-java-spring-react.md). 각 문서의 B4 완료 표현을 현재 미해결 상태와 맞췄다. 제품 코드·기존 테스트·패키지·B5 설정은 변경하지 않았다. 검사 스크립트와 실행 중간 파일은 `.local-tools/p01-b4-followup/`에 남겼다. P01-A·P01-A2는 보존했다.

핵심 흐름은 **원문 경로 → 전달 전 검사 → Vite 프록시 → Boot → HTTP/본문 해석 → health 판정 → 화면 상태**다. URL 접두사가 맞는다는 사실만으로 전달할 최종 경로가 안전하다고 판단할 수 없다는 점을 이번 재현으로 확인했다.

작은 학습 예시: 경계 테스트의 `/api/../actuator/env`와 `/api/products/%ED%95%9C%EA%B8%80?name=a%2Fb`를 비교해 보자. 전자는 경로 구조를 바꾸므로 전달 전에 거부하고, 후자는 식별자·쿼리 값이므로 유지한다. 테스트에서 `seen.length`를 확인하는 이유는 오류 상태뿐 아니라 **upstream에 요청이 아예 전달되지 않았는지** 확인하기 위해서다. 두 경로의 예상 결과를 적고 테스트 실행 결과와 비교해 본다.
