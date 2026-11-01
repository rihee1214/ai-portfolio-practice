# R-C01/R-C02 fix report — static re-review package

Scope: only the two findings and regressions introduced by their fixes. C overall, fee expansion, future common ports and missing later C behavior are not presented as complete. Original core-review1 artifact is unchanged. Current 27-file contextual source copies are immutable and full-body; R patch selects six changed/new paths, including complete tests and helper. See identity.json/manifest.json/source-verification.json. HEAD c9f924123b42e8d413f3ff70a095ffec06fd9593. This is a static review artifact, not a standalone entire application checkout.

R patch SHA256: 701C5C6D4DDA73CC1B2E31D3666F106188ABA2155FA60B3147FECAE6ADE1EE9E.

## Actual RED and preparation failures (all preserved)

- review-red1: DB3 failures3/error0/skip0. FAILED/UNKNOWN each actual expected history1/actual0 = R-C02 product RED2. R-C01 child bootstrap succeeded but TARGET_NOT_CLAIMED before PREPARED = preparation failure, not a reproduced race.
- review-red2: R-C01 actual two nonweb children, PREPARED A paused -> stored schedule Clock+31s lease takeover B -> query/T3 -> A resumed. observed.txt was state=FAILED;reserved=0;providerEffects=1, expected UNKNOWN, actual failure1/error0/skip0.
- review-focused1 after fix: R-C01 observed UNKNOWN/reserved9999/providerEffects1, but common helper selected wall-clock-ineligible/previous SUCCEEDED jobs, causing5 preparation failures among7. Not claimed as verification of all fixes. Helper now selects READY/RUNNING and injects explicit stored due+1 or expired lease+1 Clock with unchanged production claim/lease SQL. No timeout increase.

## Fix and assertions

- R-C01: missing provider row returns UNKNOWN, never definitive failure. Same permanent attempt remains reserved/HELD while late stale submit executes, and stale T3 is fenced. Current query of the same attempt converges to success, submit_calls1/effects1, inventory original-unit1. UNKNOWN cancelUnsent and retryFailed both409 with no new attempt/budget release. Provider calls remain outside business transactions.
- R-C02: new V46 immutable (attemptId,current durable result version) receipt. State/revision/history/Outbox and receipt share one transaction, final lease guard follows the final effect/Outbox wait. Same result replay does not increment revision or append effects. Current durable success is used for old UNKNOWN delivery; successful completion is not reversed.
- refundFailedResultRecordsOneTransitionAndReplayIsNoOp and refundUnknownResultRecordsOneTransitionAndReplayIsNoOp: REVIEW_REQUIRED, event1, Outbox1, replay exact claim row unchanged.
- refundReviewAckCrashAndOlderUnknownCannotRewindSuccess: for both FAILED and UNKNOWN, actual child commits result then is killed before ACK, second child takes over and replays with claim row/event/Outbox/receipt unchanged. UNKNOWN scripted provider later succeeds, same attempt queried (no SQL payment-success update), current completion then old UNKNOWN delivery leaves completed row and stock effect unchanged.
- refundReviewHistoryOutboxAndFinalLeaseRollbackAtomically: FAILED/UNKNOWN x history/Outbox/final-lease expiration =6 subcases. Every injected failure rolls claim revision/state/receipt/event/outbox back, preserves active hold, and next real worker succeeds. Expiration is moved after REVIEW_OUTBOX while business/job locks remain held; final guard rejects the stale transaction.

## Exact commands and observed results

Set MAVEN_ARGS only in the child invocation; root always received -Offline and a fresh evidence directory.

review-focused2:
MAVEN_ARGS=-Dit.test=PostgresJdbcIT#refundPreparedChildTakeoverNeverReleasesBudgetBeforeLateSubmit+refundFailedResultRecordsOneTransitionAndReplayIsNoOp+refundUnknownResultRecordsOneTransitionAndReplayIsNoOp+refundReviewAckCrashAndOlderUnknownCannotRewindSuccess+refundReviewHistoryOutboxAndFinalLeaseRollbackAtomically+productReturnFeeDefaultsZeroAndOrderSnapshotAreStable+cancellationQuoteUsesOriginalUnitsAndShipping+cancellationReservesBeforeRefundAndRestoresOriginalUnitOnce+fullCancellationRefundsOriginalShippingAndClosesOnlyAfterEverySeller
./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-c-review-focused2-20261031
Actual DB9 passed, failure/error/skip0, unit/Arch191 passed. Includes previous quote/partial/full shipping+closure/product snapshot regressions. Root failed because original mandatory tests were intentionally not selected; preservation passed.

review-focused3 (explicit UNKNOWN retryFailed409 added):
MAVEN_ARGS=-Dit.test=PostgresJdbcIT#refundPreparedChildTakeoverNeverReleasesBudgetBeforeLateSubmit
./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-c-review-focused3-20261031
Actual DB1 passed, failure/error/skip0, unit/Arch191 passed. Root failed due unselected mandatory tests; preservation passed. Runtime inputs.json SHA256 6EEF259AD3E32DA651F8062F4BAD8CE0B6BD7D3678AE4682B97D681FFBE12AB2. Source27 copied/current mismatch0 checked separately.

Original XML: each run db/build/failsafe-reports/TEST-com.example.marketplace.dbtest.PostgresJdbcIT.xml. Child per-phase args/PID/log/schedule/media, PREPARED observed.txt and ACK/restart markers are under db/refund-*. Raw failures/emulators have SHA manifests in the parent execution directory. No sources changed during runs. V41~V46 were executed and are immutable; any later strengthening uses new migrations. C unselected full and independent full remain NOT RUN. No commit/push/deploy/D.
