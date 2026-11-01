# P09-C 신규 브라우저12 실제 결과·소스 delta·전체 용량 검토

현재 P09-C이다. 새 하네스/제품 변경/커밋 없이 이 고정 source와 보존 증거를 먼저 읽고 C-review.md에 검토 판정을 요청한다. 전체 개발·독립 실행은 아직 시작하지 않았다.

source414 SHA256 `6DD660A3453726ED5B3243EEC50B30C34C238D2F513522D8E8DA4F15CFA40B27`은 c08-review1 문맥 및 실제 auth-e2e 파일 전체다. 복사/current 불일치0, 마지막 focused2 inputs552/current0은 구현자가 확인했고 원 inputs SHA `EDB50F959E1E78A2B7F5F0A5EFF697657C4A7BFF8928035CDC6F45D01D6C2AA0`를 함께 고정했다.

## 동작·delta 검토

browser-focused1은12 중8pass/4failure/skip0/retry0이다. guest2는 다른 fixture 비밀번호를 사용해 로그인401로 본문 미실행, conflict2는 Panel mount 전에 count0을 읽어 열기를 생략해 준비 timeout이었다. 실패·새DB·원문은 보존했다. child75.312초는 실패 대기를 포함하므로 정상 용량 근거에서 제외한다.

focused2는 실제12pass/failure0/skip0/retry0, driver DB1/unit196 실패·오류·skip0이다. root는 다른 필수 검사 미선택 때문에 failed / preservation passed이며 전체 통과는 아니다. 기존118 시나리오는 이번에 실행하지 않았다.

신규6종×desktop/mobile은 member 원단위/응답유실 동일명령/DB hold·refund·입고1, genuine GAS quote/철회·타인권한, conflict409→GET실패→실제logout 잔류0, 실제 비어있지 않은 취소 진행의 늦은GET 폐기, seller0/5000·구매전표시·stale견적/원snapshot, 실제FAILED→SUPPORT새시도UNKNOWN 최신상태/모순완료거부다. API/DB 대조의 범위와 네트워크 오류 주입을 구분해 검토한다.

c08-review1 뒤 delta는 PostgresJdbcIT late fixture의 실제 APPROVED 취소 준비, order-cancellation.spec의 실제private late payload·정확GUEST_PASSWORD·Panelready 대기, 기존 purchase-complete.spec의 상품변경 PUT 필드를 쓰기계약에 맞춰 명시한 것과 C문서다. 마지막 기존118 준비 변경은 아직 해당 전체 시나리오 실행 전이며 원 이름/이력·금액·배송 assertion을 유지한다. 제품 동작 수정은 없다. 기존 Java421/frontend234/browser118 필수 이름 누락0 대조도 실행 ledger에 있다.

## 첫 전체 전 용량 판단 요청

현재 child 총예산260초를 **287초**로 늘리는 안만 기술 검토한다. 아직 변경하지 않았다. 근거는 기존 B118의 실제 child252,477ms와 이번 신규12의 실제 child18,648ms, 보수적 합271,125ms이다. 신규12의 개별duration 합15,915ms/나머지2,733ms는 timing-summary.json 및 원 browser JSON/child timing과 대조한다. 두 child 기동시간을 합하는 보수 추정이며 전체 실제시간을 측정한 것으로 주장하지 않는다.

제안은 검사 수 비례 `ceil(260×130/118)=287`초다. 기존30초·catalog60초 개별 제한, worker1/retry0, 전체130 기본선택·필수목록·누락/실패/skip 거부는 그대로 유지한다. 실제 신규12 성공 후 첫 전체 전에 늘어난 업무량을 수용하는 제안이며 실패한 전체를 timeout만 바꿔 통과 처리하는 것이 아니다. 자동 추가 확대도 하지 않는다. 동의되면 구현자가 한 곳의 aggregate를 수정하고 새 source 동결·입력으로 **선택없는 개발전체**를 실행한다. 초과·실패하면 원인과 원문을 남긴다.

이번 검토에 신규12 실제 assertion/delta 및 287초 용량 수용 여부를 구분해 기록한다. 원 R01~10 scoped ADDRESSED, C08 GAS/404 수용은 유지한다. 실제 전체130 회귀와 Java/phone/frontend/Node를 포함한 개발전체·동일소스 지정독립전체는 여전히 NOT RUN이다.
