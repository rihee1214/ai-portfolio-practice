# P09-C R-C09/R-C10 검증 보강 재검토

현재 작업은 P09-C이며 이번 요청은 R-C09/R-C10 검증 공백 해결 판정이다. 새 제품 결함으로 단정하지 않았고 이번 변경은 assertion/fixture/필수 등록이다. 새 하네스 실행이나 제품 수정·커밋·배포는 요청하지 않는다. 구현 담당은 별도 C08 비회원 실제 HTTP/브라우저 작업을 계속한다.

고정 source375 manifest SHA256은 `41C501F184D473319BDE209CB7FA1057F54331E8C8F91D8A6007B914C7FC2930`이다. 이전 backend-contract-review1과 차이는 identity.json의7경로다. 해결 대상은 PostgresJdbcIT/LegacyReturnPolicyMigrationProbe/required-tests와 C 작업 문서이다. 같은 PostgresJdbcIT의 별도 C 브라우저 준비 fixture와 browser/frontend required 증가도 함께 저장돼 있으나 이번 R09/R10 해결 범위와 구분한다. 아직 브라우저 실행은 NOT RUN이다. backend main/V41~57 변경0. 복사 시 source/current SHA 불일치0. 마지막 focused3 inputs552/current0은 구현자가 확인했고 원 inputs를 사본으로 보존했다.

## 실제 결과

- backend-contract-focused1 DB5=4pass/1setup error/failure0/skip0, unit196통과. R09 SUPPORT 마지막 실제 attempt 대기 정상/만료와 owner403, R10 교차귀속/금액·원참조/비성공 apply, legacy 동의 변경·불변성은 통과했다. owner 검사 중 테스트 매대 slug가63자 제한을 넘겨 준비 Store400으로 중단했다. 원문/DB는 보존했다.
- focused2 ownerDB1 failure1, unit196통과. GAS quote 만료 기대404/실제200이었다. 이전 준비는 nanos Clock+1800만 주입해 저장 microsecond 만료를 실제 넘었는지 증명하지 못했다. 기대404와 전체 부작용0은 유지하고 실제 저장 deadline을 읽도록 수정했다.
- focused3 ownerDB1/unit196 실패·오류·skip0. member/GAS quote(bucket), accept+bind(existing budget), withdraw(attempt) 실제 별도 연결 대기와 정상/만료12대조가 통과했다. GAS는 실제 저장 deadline+1micro를 주입하고 초과 assertion 및 진단을 남긴다. 관측된 quote 예에서 이전 advance 03:00:52.096875500은 저장 deadline03:00:52.096876보다 앞이었다. 제품 코드를 수정하거나 만료 기대를 약화하지 않았다. 실제 벽시계 만료가 아니라 실제 권한 포트+제어 Clock 검사임을 명시한다.

모든 root는 선택된 검사만 실행했으므로 failed / preservation passed이며 전체 통과가 아니다. 원문은 `.local-tools/p09-c-backend-contract-focused{1,2,3}-20261101`에 있고 evidence에 root JSON/DB XML/Maven 및 최상위 fee/GAS 진단을 복사했다. 세부 원문은 실행 ledger의 manifest로 연결된다.

## 판정 요청

R-C09: member/GAS fee quote·accept/bind·withdraw와 SUPPORT retry의 실제 마지막 잠금 대기, 저장된 권한 만료 뒤 거부와 전체 participant 불변, 정상대조가 설계 요구를 검증하는지 확인한다.

R-C10: 유효한 다른 주문·판매자·hold/unit/attempt의 교차귀속, wrong amount/original reference 실제 reserve-bind rollback과 정상대조, 실제 FAILED/UNKNOWN resultVersion을 apply에 전달해409/A0/R유지/receipt0/hold유지를 확인하는 assertion을 검토한다. legacy 동의 true 동일키 재생, false/quoteID/fingerprint 변경 거부, 저장 consent 수정/삭제 및 quote 내용 변경 거부의 직접 assertion을 확인한다.

P09-C-review.md에 두 항목을 각각 판정하고 부족한 축이 있으면 구체적으로 남긴다. R01~08 scoped ADDRESSED는 유지하며 C08 실제 비회원 HTTP 후보·브라우저·최종 전체를 이번 승인으로 닫지 않는다. 최종 선택없는 개발 전체 및 별도 지정 독립 전체는 계속 NOT RUN이다.
