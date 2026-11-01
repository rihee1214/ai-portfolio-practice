# P09-C dev2 브라우저 경계 검사 보완 재검토

현재 P09-C이다. 이 고정 보고서와 source/evidence부터 읽고, 새 검사·제품 수정·커밋 없이 C-review.md에 판정한다. dev1/dev2 실패는 그대로 보존하며 이번 수용 이후 새 unselected dev3를 실행할 예정이다. 아직 최종 독립전체를 실행하지 않는다.

source414 SHA `EB3B60AB97F9FCD5B42A499FD566DB4C79177A1738DF6E569A4C31FB993FEB34`, copy/current 불일치0. dev1-fixture-review1 대비 runtime test3파일과 C작업/검토문서만 달라졌다. 마지막 focused 입력552 SHA `2BCE564B1EE31166FCCD5F0AB9B22F7E455FEB98CB66A4BE05D92CE6BEE6425C`이고 구현자가 current0을 확인했다. 제품·SQL·required·287초·개별30/catalog60/worker1/retry0은 변경하지 않았다.

## dev2 실제 결과·정정

unselected dev2 rootfailed/preservationpassed/source552현재불일치0. Java553 중 unit196/phone1 통과, PG356은355업무통과/브라우저driver1failure·error0/skip0이다. dev1의 메일함·GAS·구형이행 실패군은 실제 전체에서 통과했다. frontend/Node는 DB 중단으로 NOT RUN이다.

브라우저 child287021ms timeout, JSON은 강제종료로 생성되지 않았다. 콘솔 직접 집계는127ok+개별2failure+마지막coupon1미완료다. 앞선128 통과 보고는 마지막 진행번호를 통과 수로 읽은 오류이며 부모·작업 기록에서 정정했다. 단순 용량 초과로 재분류하지 않는다. 두 error-context 원문과 console/timing을 보존했다.

1. 신규 mobile latestUnknown은 실제 두 ITEM tuple이 seq1FAILED/seq2UNKNOWN의 역순으로 반환돼 deepEqual이 실패했다. JdbcClaims.get은 purpose/attempt_id로 정렬하며 sequence 순서를 보장하지 않는다. 검사만 응답 복사본을 purpose/sequence로 정렬해 같은 개수·차수·상태를 검증한다. 최신 UNKNOWN 철회 차단·모순 완료 거부·DB 무효과 assertion은 유지한다.
2. 기존 mobile guest-lookup 첫 재시도 click은 버튼 disabled→detached로 실패했다. 기존 검사는 retry visible만 보고 첫 abort route를 해제했다. Store는 CSRF 요청 전 logoutPending을 켜므로 아직 첫 logout 요청이 abort callback에 도달하기 전에 route를 바꿀 수 있는 준비 경합 후보다. dev2 trace가 off이므로 당시 실제 네트워크 순서를 확정했다고 주장하지 않는다. 수정은 첫 abort callback 완료 count1, retry enabled, 실제 현재GAS200을 확인한 후 route를 바꾼다. 다음 실제 logout commit+응답유실, 재시도·401·최종 로그인 화면 수렴/소유권 검사는 그대로 유지한다. 제품 코드를 변경하거나 클릭 제한을 늘리지 않았다.

## 보완 실행

기존 P09_C_BROWSER_ONLY 진단 selector에 명시 retry-boundaries 값만 추가했다. unset은 전체130, 기존1은신규12, 새 값은 두정확제목×desktop/mobile4다. 세 모드의 실제 CLI collection을 확인했고 typecheck/lint 통과. 정식 required130은 변하지 않으며 전체에서는 이 환경변수를 명시적으로 비운다.

browser-retry-focused1은 실제4pass/failure0/skip0/flaky0/retry0, child8103ms다. driverDB1/unit196도 실패·오류·skip0. root는 필수 미선택 failed/preservationpassed이며 전체 용량 또는130통과 증거가 아니다. 원783/emulator3 manifest와 source552현재0은 ledger를 따른다.

두 준비 보완의 assertion 보존·계약 적합성과 진단 selector의 기본 전체/누락 탐지 불변을 판정한다. 최종 전체 통과로 확대하지 않는다. 기존 R01~10 및 앞선 수용은 해당 범위에서 유지한다. 전체287 제한은 그대로이고 자동 확대는 없다. 필요하면 구체적인 새 지적을 남긴다.
