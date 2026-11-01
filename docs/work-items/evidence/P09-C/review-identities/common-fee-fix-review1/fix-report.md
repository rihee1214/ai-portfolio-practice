# R-C03/R-C04 fixed-source review request (not C completion)

Baseline immutable package: ../common-fee-review1, source63 canonical SHA861CDDD801CFE4675C533CF5A33195758F216C88351FB086DFE8CC50380B6E7C.
Current source65 canonical SHAD724DD39D5676E8DC100DE6F59D8ECB22B0985DF9A2B6187E1B265173A9F5A48; HEADc9f924123b42e8d413f3ff70a095ffec06fd9593. Current source mismatch0. Latest focused inputs537 mismatch0; inputs.json file SHA323B33CAFC8637F73597CC831A521BED1A0B35A6E95EABDDD4357121E37B5CAB. Identity/source-delta/source-manifest contain exact paths and hashes. V41~52 unchanged against prior immutable source63, V53/V54 applied successfully and immutable. No runtime or assertion change after focused2.

## Scope separation

Review R-C03/R-C04 fixes and breakage caused by these fixes. Runtime fix paths: catalog/infrastructure/JdbcProducts.java, claim/infrastructure/JdbcReturnFees.java, migrationV54. R-only-test-excerpts has the full five test methods plus genuine fixtures/helper context. review-fix-delta.patch includes those three paths plus shared PostgresJdbcIT/required list delta. Shared test/registration delta also contains the separately identified owner command tests; exclude those additions from R-fix approval. New files are included as complete unified sections and all full source is available. Both patches passed read-only git apply --stat parsing; no patch was applied.

Separately concurrent C feature: Claims.java/JdbcClaims.java/ClaimController.java/V53 add owner private GET and withdraw. concurrent-owner-command-delta.patch and concurrent-owner-test-excerpts isolate those paths/assertions. These commands are future whole-C review scope and are not approved merely by closing R-C03/R-C04. They were preserved when review feedback arrived. No new reviewer or agent was created.

## R-C03 real RED

Command: MAVEN_ARGS=-Dtest=ClaimRulesTest,ModuleArchitectureTest,ModuleRulesTest -Dit.test=PostgresJdbcIT#catalogSessionSaveAndAccountRevocationKeepCanonicalLockOrder+feeApplicationReceiptOnlyCannotCommitOrPretendApplied+feeWithdrawalReceiptOnlyRollsBackActualRefundAndHoldRelease
./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-c-fee-review-red1-20261031

Actual DB3 failures/error0/skip0, unit/Arch31 passed. Observed first product-update/save-first/suspend race: saveOK, revoke=PessimisticLockingFailureException; maven log contains actual PostgreSQL deadlock in DELETE spring_session. Two production connections and latches: real public session SHARE paused, real account-state operation obtained account lock then waited session, original save resumed and waited account. No simulated database failure.

Fix: acquire public Stores.requireCapability account/store locks first, then session. Preserve final current-session validation after product/category/write/unique waits. No catalog SQL access to identity internals, no removal of authorization, no retry masking.

Actual final assertions: create/update x suspend/self-withdraw x save/revoke first =8 observed lines, all revokeOK/deadlock0. Save-first writes policy5000 and exact one product version (or one created product) before revocation. Revoke-first returns current-account rejection and no product/policy/version side effect. Both revoke paths use real AccountStateOperations. Natural expiry test blocks actual catalog_category row, lets real wall-clock session deadline pass, then verifies401 and no create/update effect. Fresh stale authVersion is also401/no write. Session wrapper only adds a barrier around the real PurchaseSessions call; success/failure is not stubbed.

## R-C04 real RED

Same red1 run, two independent methods:
- feeApplicationReceiptOnlyCannotCommitOrPretendApplied: genuine paid/delivered/accepted fee, actual0 refund and durable SUCCEEDED result, valid raw application receipt alone commits. Observed rejected=false/assessmentBOUND/debt0/receipts1.
- feeWithdrawalReceiptOnlyRollsBackActualRefundAndHoldRelease: actual Refunds.cancelUnsent plus B.release and valid raw withdrawal receipt, without assessment/bucket changes, commits. Observed rejected=false/refundWITHDRAWN/holdRELEASED.

Fix: V52 unchanged. FollowupV54 adds deferred terminal/state/bucket verification to receipt INSERT as well as assessment writes; receipt->terminal and terminal->receipt match. Existing inconsistent evidence is rejected during migration validation, not promoted or rewritten. JdbcReturnFees prior receipt replay requires matching terminal state and checks current financial bucket proof before returning success.

Final assertions: both receipt-only transactions fail commit. Before commit, calling the public apply/withdraw with the inconsistent visible receipt is409 rather than false completion. Rejection rolls back application/withdrawal receipt, payment cancel, hold release and participant changes. Observed application rejected=true/BOUND/debt0/receipts0; withdrawal rejected=true/refundRESERVED/holdACTIVE. Normal apply/replay and withdraw/replay remain passed in focused1.

feeTerminalHistoryOutboxAndLostLeaseRollBackEveryParticipant executes five cases: APPLY HISTORY, APPLY OUTBOX, APPLY actual lost job owner token, WITHDRAW HISTORY, WITHDRAW OUTBOX. It compares actual fee assessment/bucket/receipts/event, payment attempt/budget, B hold/lifecycle, Outbox participants, then recovers with the same actual stored payment result. Positive refund provider effects1/submit1. Lost lease uses a real claimed production job and a second physical connection changing its stored owner token after business/Outbox writes; production JobLeases final guard rejects and business transaction rolls back. This is actual fencing evidence, not a claim of an additional child-process kill run. Existing R01/R02 child evidence is preserved.

## Execution and preparation failures

compile22 failed for missing assertion import (no database run), compile23 passed17.057s before actual RED. compile24 failed for missing Set qualification (no database run), compile25 passed17.385s. compile26 passed17.390s after fixture-only corrections. Logs included.

fee-review-focused1 exact integration selection:
catalogSessionSaveAndAccountRevocationKeepCanonicalLockOrder+catalogSessionExpiryAfterCategoryWaitAndStaleVersionLeaveNoPolicyWrite+feeApplicationReceiptOnlyCannotCommitOrPretendApplied+feeWithdrawalReceiptOnlyRollsBackActualRefundAndHoldRelease+feeTerminalHistoryOutboxAndLostLeaseRollBackEveryParticipant+returnFeeApplicationUsesCumulativeDebtAndNeverCompletesPhysicalReturn+returnFeeWithdrawalUsesActualRefundStateAndReleasesOnlyOwnHold+returnRefundResultIsRetainedWithoutCancellationEffects+cancellationGetSeparatesAcceptedReceiptFromCurrentPrivateProgress+cancellationWithdrawalUsesCurrentOwnerAndActualRefundNoEffect+fullCancellationRefundsOriginalShippingAndClosesOnlyAfterEverySeller

MAVEN_ARGS=-Dit.test=PostgresJdbcIT#<selection> (all unit/Arch, no -Dtest). ./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-c-fee-review-focused1-20261031.
Actual unit/Arch196 passed; DB11:8pass/1failure/2errors/skip0. R04 receipt/rollback and normal fee/CANCEL full/ownerwithdraw passed. R03 errors were fixture Products.own404 before barrier: a multistore quote's first seller was incorrectly assumed to be the fixture member. The actual original deadlock RED had selected an owned product and remains valid. Fixture now selects matching actual Stores.own owner store. New ownerGET's anonymous404 expectation was actual401 from existing authentication gate; corrected to anonymous401 plus an actual different authenticated owner404 (both enforced).

Final fee-review-focused2 exact integration selection:
catalogSessionSaveAndAccountRevocationKeepCanonicalLockOrder+catalogSessionExpiryAfterCategoryWaitAndStaleVersionLeaveNoPolicyWrite+feeApplicationReceiptOnlyCannotCommitOrPretendApplied+feeWithdrawalReceiptOnlyRollsBackActualRefundAndHoldRelease+feeTerminalHistoryOutboxAndLostLeaseRollBackEveryParticipant+cancellationGetSeparatesAcceptedReceiptFromCurrentPrivateProgress

MAVEN_ARGS=-Dit.test=PostgresJdbcIT#<selection>. ./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-c-fee-review-focused2-20261031.
Actual unit/Arch196 +DB6 failure/error/skip0. These six methods contain the eight R03 races, expiry/stale checks, both R04 bypass/replay cases and five rollback cases, plus private owner GET. Every focused root is failed for missing unselected mandatory tests (or original documented failures), preservation passed. Never a full-suite pass.

Raw run+emulator manifests and observed files preserved; report copies exact root inputs/result/summary/target/maven and observations. Original raw XML is in corresponding run/db/build/failsafe-reports and surefire-reports. No old DB/media/evidence deleted or edited. Final source inputs537 mismatch0. Required original421 retained/current450/missing0; no timeout/workers/skip changes.

## Remaining unverified C

R03/R04 independent status still pending reviewer. R01/R02 ADDRESSED preserved, not rerun as child probes in focused2. No whole-C completion/full run/independent full. SUPPORT failed retry/query, broader cancellation/fee budget/locks/auth races and failure/child tests, product purchase races/long boundaries, real old-format legacy migrations/consent, genuine coupon RETURN no-effect, frontend/browser and final preservation audit/sourcefreeze remain. Frontend234/browser118/Node75 unchanged and not run for this fix. D unstarted; commit/push/deploy prohibited.
