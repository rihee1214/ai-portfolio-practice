# P09-C C08 실제 비회원 HTTP 및 브라우저 준비 정적 검토

현재 P09-C다. 과거 B 마감은 재검토하지 않는다. 이 보고서부터 읽고 고정 source/evidence를 검토한다. 이번 요청은 새로운 실제 C08 결함 수정과 준비 소스의 정적 검토이며, 브라우저나 전체 통과 요청이 아니다. 구현자는 신규 브라우저12 실행·측정을 별도로 진행한다. 새 하네스/제품 변경/커밋/배포 없이 P09-C-review.md에 판정한다.

source379 SHA256 `855B29D1EE115D6C50CB98526ECA684921EC0B54280EE80AC7A564504CF463A6`, 복사 시 현재와 불일치0. 마지막 gas-http-focused2 inputs552/current0은 구현자 확인 및 원문 사본으로 연결한다. HEAD c9f9241 불변. backend-contract-fix-review1의 main/test/하네스·문서 문맥에 QuotePanel/QuoteStore.test/order-cancellation.spec/playwright.config를 추가했다.

## 실제 RED와 수정

gas-http-red1 DB1failure/error0/skip0/unit196pass. 실제 CSRF 발급·GAS 로그인200 뒤 account 로그인 없는 GET cancellations가 기대200/실제401이었다. 기본 계정 인증 경로가 현재 Claims의 GAS 소유권 검사를 막았다. 이는 실제 결함이며 준비 실패가 아니다.

AuthConfiguration은 정확 GET1/POST3 취소 경로만 기존 비회원 confirm과 같이 현재 Claims의 소유권/권한 검사까지 연결한다. CSRF와 실제 GAS/소유권 검사를 유지한다. 테스트 전용 c3-fixture와 브라우저 fixture의 endpoint는 제품 endpoint가 아니며 test profile/설정 안에만 존재해야 한다.

gas-http-focused1 DB4=3pass/1failure/unit196pass. 신규 실제 GAS GET/no-store/quote/cancel/exactreplay/withdraw·금액예약해제/provider0 및 익명·타인404/CSRF403/logout404·participant불변과 기존 member quote/withdraw는 통과했다. 기존 private GET 검사의 익명 기대401은 generic account gate를 전제했으나 현재 소유권 숨김404로 바뀌어 실패했다. 이름·다른 assertion은 유지하고 이 기대만404로 바꾼 focused2 DB1/unit196은 실패·오류·skip0이다. 이 응답 변경이 기존 현재 소유권/GAS 계약과 양립하며 정보 노출/접근 권한 약화를 만들지 않는지 별도로 판단한다. 실패를 통과로 소급하지 않는다.

모든 위 root는 선택 실행이라 failed / preservation passed이며 전체 통과가 아니다. 원문은 각 `.local-tools/p09-c-gas-http-*-20261101`이며 evidence에 JSON/XML/Maven 로그를 복사했다.

## 별도 UI/브라우저 준비

QuotePanel의 상품별 회수비 안내를 닫힌 배분 details 밖으로 이동했다. 실제 hidden RED 이후 unit256/required256/typecheck/lint/build 통과이며 구현 ledger가 원문 경로를 가리킨다. 이 소스는 standalone 전체 입력의 사전동등성 증명으로 확대하지 않고 최종 전체에서 다시 수집한다.

브라우저 신규6시나리오×desktop/mobile=12, 기존118와 합130을 실제 수집하고 타입/린트를 통과했다. 이 snapshot 시점 동작·시간은 NOT RUN이다. 실제 생성/결제·GAS·FAILED→실제SUPPORTretry→UNKNOWN 준비, 개인 소유 fixture 제약, test 전용 조작 경로, 원 성공 금융 데이터를 SQL로 주입하지 않는지 검토한다. P09_C_BROWSER_ONLY는 진단용 명시 선택이며 기본 전체130/필수목록의 누락 탐지를 약화하지 않아야 한다. 기존 개별 timeout/retry0/worker1 및 총260초는 현재 유지한다. 실제 추가 시간 측정 후 별도로 용량 검토한다.

R-C01~10은 기존 범위 ADDRESSED 유지. 이번 새 변경의 문제 또는 승인 범위를 구체적으로 P09-C-review.md에 추가한다. C 전체·선택없는 개발전체·지정 독립전체·실제 Toss·사용자 직접 확인은 여전히 미완료/미검증이다.
