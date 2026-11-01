# P09-C R-C05/R-C06 수정 재검토 — 2026-11-01

현재 작업은 P09-C의 두 검토 지적 해결 확인이다. 이 고정 source만 정적 검토하고 보존된 실행 증거를 확인한다. 제품 수정·새 하네스 실행·커밋·배포·D 작업은 요청하지 않는다. 구현 담당은 별도 현재 작업공간에서 남은 C 작업을 계속한다.

## 고정 소스와 범위

- HEAD c9f924123b42e8d413f3ff70a095ffec06fd9593, source72.
- source-manifest.json SHA256: 17E3DE04CC4562C27C0C24B472A49EFD992012CE47CB9C09EAF5213D2515D20F.
- 원 command-review1/source71 대비 변경5·신규1이며 identity.json에 정확 경로를 기록했다.
- R05/R06: JdbcClaims.java, 신규 V57__cancellation_withdrawal_terminal_proofs.sql, PostgresJdbcIT.java와 필수 등록.
- 별도 보완: JdbcCancellationOrders.java는 배송비 반환 전에 원 단위별 같은 원인의 금융 완료 근거를 요구한다. 이 변경은 R05/R06과 분리해 검토·판정한다.
- ReturnFees.java의 이미 override된 retryFailed 기본 메서드를 abstract로 정리했다. 이 사본은 정리 후 실제 GREEN과 일치한다.
- V41~56을 수정하지 않았으며 V57도 성공 적용 후 불변이다.
- 복사 당시 source72/current 불일치0, 마지막 GREEN의 inputs544/current 불일치0. 원 inputs.json SHA256은 8A4CC2A95557C66CB9A4EC01854EA86183CF8A65F50CA24F95C1C718E33C6D47이다.

## 재현과 수정

R-C05: 실제 FAILED T3→구매자 철회→늦은 T4에서 작업READY/claimWITHDRAWN/resultReceipt0을 재현했다. 수정은 실제 철회 영수증·현재 시도·FAILED/미제출 상태·원인/원단위·해제된 hold 근거를 검증하고, 철회 상태/revision을 변경하지 않은 채 결과 영수증·감사/Outbox·최종 lease를 원자 처리한다. 이미 처리한 FAILED 결과의 철회 후 재생도 유지한다.

R-C06: 실제 RESERVED/FAILED 각각에서 환불 취소와 hold 해제 후 철회 영수증만 저장하는 거래가 허용되고 API replay가 WITHDRAWN으로 성공하는 것을 재현했다. 신규 V57은 receipt INSERT에도 deferred terminal/환불/hold/영수증 양방향 검사를 연결한다. replay는 실제 상태와 귀속이 일치하지 않으면 성공을 반환하지 않는다. receipt-only commit 거부, 동일 거래 내 API409 및 모든 participant rollback, 정상 철회와 재생을 검사했다.

별도 배송비 근거: B 공개 포트로 수량을 REFUNDED로 만든 상태만으로 실제 결제 환불 완료를 대신할 수 없다는 음성 검사다. 실제 일부 단위만 환불했는데 SHIPPING 시도1이 생기는 RED를 보존했다. order 소유 settlement_unit의 같은 원인 근거를 각 원단위에 요구하도록 최소 수정했다. 외부 모듈의 내부 테이블을 조회하거나 새 반품 배송비 정책을 정하지 않는다.

## 실행 증거

모든 원문은 작업공간 .local-tools에 보존돼 있다. 아래 선택 실행은 모두 root failed / preservation passed이며 전체 통과가 아니다.

- p09-c-command-review-red1-20261031: R05와 R06 RESERVED/FAILED의 DB3 failure3/error0/skip0. 실제 업무 상태에서 재현했으며 성공 상태를 SQL로 주입하지 않았다.
- p09-c-shipping-proof-red1-20261031: DB5 중4pass/1failure/error0/skip0. 금융증거 없는 원단위 상태를 둔 음성 검사에서 SHIPPING 기대0/실제1. UNKNOWN 유효 revision의 retry 거부와 terminal 동일 명령 재생 추가4개는 통과했다.
- p09-c-command-review-focused1-20261031: DB14 중13pass/1failure/error0/skip0, 단위·Arch196 통과. LEASE 준비 코드가 production requireOwned의 job FOR UPDATE를 잡은 거래 안에서 다른 연결의 owner_token UPDATE를 기다리는 자기 대기를 만들었다. 확인된 새 검증 DB의 그 UPDATE만 취소해 종료했고 기존 증거를 보존했다. 이 결과를 GREEN 또는 제품 lease 결함으로 재해석하지 않는다.
- p09-c-command-review-focused2-20261101: DB14·단위/Arch196 모두 failure/error/skip0. 준비 코드만 실제 JdbcJobs와 mutable Clock으로 바꿔 저장된 leaseUntil을 WITHDRAWN_OUTBOX 시점에서 넘겼다. 실제 최종 requireOwned 검사는 변경하거나 모의 실패시키지 않았고 timeout도 늘리지 않았다. HISTORY/OUTBOX/실제 lease 만료의 rollback 후 같은 결과 복구를 검증했다. 실제 child kill 검사라고 표현하지 않는다.

GREEN의 DB 검사 목록:

1. withdrawnFailedRefundResultAcknowledgesWithoutReopeningCancellation
2. alreadyRecordedFailedResultAfterWithdrawalIsNoOp
3. withdrawnFailedResultHistoryOutboxAndLostLeaseRollBackAtomically
4. cancellationReservedWithdrawalReceiptOnlyRollsBackEveryParticipant
5. cancellationFailedWithdrawalReceiptOnlyRollsBackEveryParticipant
6. cancellationWithdrawalUsesCurrentOwnerAndActualRefundNoEffect
7. cancellationGetSeparatesAcceptedReceiptFromCurrentPrivateProgress
8. cancellationShippingRequiresEveryOriginalUnitFinancialSettlement
9. fullCancellationRefundsOriginalShippingAndClosesOnlyAfterEverySeller
10. supportQueryKeepsSameUnknownRefundAttemptAndRequiresSupport
11. supportFailedRetryPreservesOldAttemptAndLateResultCannotRewindSuccess
12. returnFeeFailedRetryPreservesAssessmentAndReservedIncrement
13. refundFailedResultRecordsOneTransitionAndReplayIsNoOp
14. refundUnknownResultRecordsOneTransitionAndReplayIsNoOp

명령은 위 목록을 `-Dit.test=PostgresJdbcIT#`의 `+` 구분 선택으로 지정하고 단위 검사 선택 없이 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-c-command-review-focused2-20261101`을 실행했다. 정확 원문 XML·result.json·inputs.json·db/maven.txt 및 실행 ledger를 함께 확인한다. 소스 동결과 새 DB/미디어 사용을 유지했다.

## 판정 요청

R-C05/R-C06 각각 해결 여부, 새 수정으로 생긴 문제, 별도 배송비 금융 근거 보완의 적합성을 P09-C-review.md에 구분해 추가한다. R-C01~04의 기존 범위 ADDRESSED는 유지하며 C 전체 완료로 확대하지 않는다. 원금/할인/무료배송 전체 fixture, 권한·경합·legacy·프런트/브라우저, 새 unselected 개발 전체 및 지정 독립 전체는 아직 완료되지 않았다.
