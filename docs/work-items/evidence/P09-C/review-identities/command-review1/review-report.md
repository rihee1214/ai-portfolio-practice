# P09-C 취소·지원 명령 중간 검토 요청

이 고정 사본은 C 전체 완료 또는 독립 전체 실행 요청이 아니다. 제품 변경·새 검사·커밋·배포·D 구현 없이 기존 지정 검토세션에서 정적 검토와 보존된 증거 확인만 수행한다. 현재 작업공간은 이후 Sol 구현이 계속되므로 source 사본을 기준으로 판단한다.

## 식별

- HEAD: c9f924123b42e8d413f3ff70a095ffec06fd9593
- 완전 소스71, source-manifest.json SHA256: 81C01F65347F2058F2D482474954D0D9D1A459F6F60B5A8BB9B22492538C7D12.
- common-fee-fix-review1 source65 대비 신규6/변경7. manifest의 changed 항목으로 구분한다. 기존65에는 이전 R 재검토에서 제외했던 GET/withdraw 및 V53도 포함되므로 이번 명령 범위에서 함께 검토한다.
- 복사 직후 현재 source71 불일치0. 마지막 GREEN인 support-retry-focused1의 전체 inputs543과 현재파일 불일치0. 정확 식별은 identity.json 및 green-inputs.json에 기록했다.
- V41~56 적용 완료 파일은 불변이다. 제공된 문서 사본은 설명용이며 과거 검토의 범위를 확대하지 않는다.

## 검토 범위

1. 구매자 취소 진행 GET: 현재 본인/세션, 접수 원본과 현재 환불 진행 상태 분리, 타 사용자 정보 보호.
2. 구매자 취소 철회: 실제 환불 상태·수량 보류·불변 철회 영수증·이력/Outbox의 원자성, 같은 요청 재생과 출고 차단 해제 조건.
3. SUPPORT query: CurrentSupport 공개 자격(account→session), 지원 권한/사유/현재 자격, 동일 환불 시도 조회와 별도 불변 명령 이력. 기존 SYSTEM 대사 권한 확대 금지.
4. SUPPORT FAILED retry: 실제 확정 실패에서만 새 sequence 생성, 이전 실패와 접수 원본 불변, 같은 명령키 재생, 최신 시도 연결과 늦은 과거 결과 처리.
5. 공통 RETURN fee retry: 같은 assessment/consent/fee 예약을 유지하고 새 실제 환불 시도만 연결. 새 성공 후 적용1회, RETURN 업무 완료 효과0.
6. V53/V55/V56 귀속·완전성 및 변경된 기존 환불 결과 처리. R-C01~04 해결을 훼손하는 회귀 여부도 확인한다.

`ReturnFees.retryFailed`의 미구현 기본 메서드는 실제 JdbcReturnFees가 override한 상태이며 이번 GREEN과 동일하다. 이후 정리는 이 사본에 포함하지 않는다. 지원/반품의 미완성 화면 또는 아래 미실행 검사를 구현 완료라고 주장하지 않는다.

## 실제 실행 증거

모든 경로는 작업공간 .local-tools 아래이며 원문 XML·로그·result.json·실패 manifest를 보존했다. 선택 실행의 root는 필수 미선택으로 failed, preservation은 passed다. 아래 통과는 선택한 assertion만 의미한다.

- cancellation-command-red1-20261031: 실제 접수 후 owner GET 기대200/실제405, withdraw 기대200/실제404, DB2 failure/error0/skip0.
- fee-review-focused1-20261031: 정상 owner withdraw 통과를 포함하나 전체 DB11=8pass/1failure/2error. 익명 GET의 잘못된 기대404/실제401 및 상품 경합 준비 오류는 후속 검사로 보완했다.
- fee-review-focused2-20261031: DB6/단위·Arch196 모두 실패·오류·skip0. owner GET의 접수원본/현재진행, 익명401/실제 다른 로그인 사용자404 포함. R03/R04 재현 보완은 이미 별도 ADDRESSED 판정이다.
- support-query-red1-20261031: 실제 UNKNOWN/현재 ADMIN+SUPPORT의 HTTP 기대202/실제404, DB1 failure/error0/skip0.
- support-query-focused1-20261031: DB5/단위·Arch196 모두 실패·오류·skip0. SUPPORT202/동일키/no-store/attempt1/queryjob2, CSRF403/SYSTEM-only403/위조 성공400. 실제 제공자 후속 응답으로 같은 attempt query→T3/T4, submit1/query1/effect1/입고1. R01 실제 PREPARED child와 R02 FAILED/UNKNOWN·부분취소 회귀 포함.
- support-retry-red1-20261031: 실제 FAILED 취소의 새 route404 및 공통 RETURN retry 미구현409, DB2 failure/error0/skip0.
- support-retry-focused1-20261031: DB10/단위·Arch196 모두 실패·오류·skip0. CANCEL 새seq2/기존FAILED불변/늦은옛result ACK/새성공 상태와 revision불변/입고1; RETURN sameassessment/R/bucket불변→새seq성공→A1회 적용/hold유지. 이전 지원 query, R02 FAILED/UNKNOWN·실제 ACK child/역순결과/6rollback, 전체취소 SHIPPING, fee5rollback, RETURN3상태도 포함한다.

정확 선택과 실행 환경은 각 원문 inputs.json/db/maven.txt 및 implementation-progress.md의 당시 기록을 따른다. 직접 성공 상태를 SQL로 주입한 결과가 아니며 로컬 DEMO 제공자를 사용한다. 실제 Toss는 차단 상태다.

## 아직 남은 C 검증

UNKNOWN 재시도 금지·terminal 이후 명령 재생 추가 경계, 전체 권한/잠금/CSRF 표, 원금33001·할인26004·무료배송, 부모 한도/발송·확정·철회/fee bucket 동시성, 다중bucket rollback·직접 SQL 제약 전수, 실제 구형 JSON/legacy 동의·정책 변경 대 주문 경합, 프런트/브라우저, 전체 실패 보존 재감사, 새 unselected 개발 전체·지정 독립 전체는 아직 완료되지 않았다. 이 목록의 미실행 자체를 이번 완료주장으로 오해하지 않되, 위 구현 범위의 코드 결함은 구체적으로 지적한다.

최종 판정은 P09-C-review.md에 범위·심각도·재현 조건·수정 기준으로 추가한다. 원 source 및 artifact를 수정하지 않는다.
