# P09-C common fee implemented slice — independent intermediate review request

This is a fixed local source copy, not final C completion or full verification. Parent alone requests review in the existing designated session. No commits/push/deploy/D implementation. Review source/ contains all 63 complete changed/context files, including new source absent from tracked-diff.patch. docs/ contains read-only copies of the current C contract/review/work item and parent-owned D handoff. Do not edit this artifact.

Identity: HEAD c9f924123b42e8d413f3ff70a095ffec06fd9593; source63 SHA256 861CDDD801CFE4675C533CF5A33195758F216C88351FB086DFE8CC50380B6E7C; copy/current mismatch0 at capture. See identity.json/source-manifest.json. git diff --check passed. Successful V41~52 history/checksums are copied read-only from the newest isolated test DB; applied migrations are immutable. Original B source496 and preserved-failure85643 references remain in preflight; this intermediate artifact does not claim a fresh all-file85643 audit.

## In scope: currently implemented behavior

- Product-level seller return fee, default3000/explicit0/omittedupdate retains0; public/quote/order policy snapshots and policy fingerprint. Original immutable order policy vs current catalog.
- Common CANCEL/RETURN financial intent with real B hold/exact original units, public payment refund reservation/binding and deferred complete identity. No fake CANCEL or unowned UUID.
- RETURN result router: actual Refunds.get current version/state/reference, durable result/history/Outbox, same-result no-op/replay and final lease. Public FinancialIntents.results retains future coordinator resumption. Existing CANCEL result delegation stays unchanged.
- ReturnFees.quote: genuine delivered original units/product snapshot/original shipment, returned-target cumulative-max math, seller responsibility excluded, no hold/payment/fee reservation.
- Caller-transaction accept/bind: immutable consent/quote/allocation, active bucket slot and R, original source/policy/net proofs, actual payment amount/order/seller/intent/reference binding, final current authority, deferred completeness.
- apply: accepted command/current actual SUCCEEDED plus saved immutable RETURN result, once-only A/R/deduction/outstanding/receipt/history/Outbox, optional actual final worker fence. No live browser credential required for accepted work. No B completeRefund, RETURN stock/coupon/parent closure.
- withdraw: current owner, actual RESERVED(cancelUnsent same transaction) or definitiveFAILED only; fee R/slot and B hold release/receipt/history/Outbox atomic; UNKNOWN/PROCESSING/SUCCEEDED rejected in implementation. B persisted release state RELEASED/outcomeWITHDRAWN. Same withdrawal receipt replay.
- DB declarations/guards V45~52, original core/restitution/refund dependencies. Review major new breakage in touched shared paths; this does not imply all C assertions ran.

## Exact real evidence (all raw runs retained under workspace .local-tools)

Harness command format: set MAVEN_ARGS to the listed selection, then ./verify.ps1 -Offline -EvidenceDirectory .local-tools/<run>.
Unit selection in these focused runs: -Dtest=ClaimRulesTest,ModuleArchitectureTest,ModuleRulesTest (actual31).
Integration selection prefix: -Dit.test=PostgresJdbcIT# followed by plus-separated method names.

1. p09-c-fee-accept-red1-20261031: returnFeeAcceptBindsActualRefundAndReservesOneOriginalShipment — genuine quote/Bhold/intent then accept unsupported409, actual failure1/error0/skip0. Capability absence RED.
2. p09-c-fee-accept-focused1-20261031: V49 CASE parser42601, context14errors, no business assertions. Full rollback/no history49; failed original V49 copy/hash in execution/v49-setup-failure.
3. p09-c-fee-accept-focused2-20261031: V49 appliedsuccess then target variable/column42702 at deferred quote commit, existing quote1failure/accept prerequisite1error; V49 preserved, followupV50 only.
4. p09-c-fee-accept-focused3-20261031: returnFeeQuoteUsesGenuineProductSnapshotAndOriginalShipmentMaximum+returnFeeAcceptBindsActualRefundAndReservesOneOriginalShipment — DB2/unit31 passed, failure/error/skip0.
5. p09-c-return-result-red1-20261031: returnRefundResultIsRetainedWithoutCancellationEffects — actual providerSUCCEEDED then result job expectedSUCCEEDED/actualREADY, failure1/error0/skip0.
6. p09-c-return-result-focused1-20261031: new uppercase-email fixture exact mail mismatch before business test, setup error1; six existing regressions passed. Corrected lowercase test identity only.
7. p09-c-return-result-focused2-20261031: returnRefundResultIsRetainedWithoutCancellationEffects+returnFeeQuoteUsesGenuineProductSnapshotAndOriginalShipmentMaximum+returnFeeAcceptBindsActualRefundAndReservesOneOriginalShipment+cancellationReservesBeforeRefundAndRestoresOriginalUnitOnce+fullCancellationRefundsOriginalShippingAndClosesOnlyAfterEverySeller+refundFailedResultRecordsOneTransitionAndReplayIsNoOp+refundUnknownResultRecordsOneTransitionAndReplayIsNoOp — actual DB7/unit31 passed, failure/error/skip0.
8. p09-c-fee-effect-red1-20261031: returnFeeApplicationUsesCumulativeDebtAndNeverCompletesPhysicalReturn+returnFeeWithdrawalUsesActualRefundStateAndReleasesOnlyOwnHold — two actual unsupported409 capability failures after genuine prepared workflows, error0/skip0.
9. p09-c-fee-effect-focused1-20261031: returnFeeApplicationUsesCumulativeDebtAndNeverCompletesPhysicalReturn+returnFeeWithdrawalUsesActualRefundStateAndReleasesOnlyOwnHold+returnRefundResultIsRetainedWithoutCancellationEffects+returnFeeQuoteUsesGenuineProductSnapshotAndOriginalShipmentMaximum+returnFeeAcceptBindsActualRefundAndReservesOneOriginalShipment+fullCancellationRefundsOriginalShippingAndClosesOnlyAfterEverySeller — actual DB6/unit31 passed, failure/error/skip0.
10. After GREEN, two overridden unsupported default interface methods apply/withdraw were made abstract (implementation bodies unchanged); compile20 Checkstyle/main/testcompile BUILD SUCCESS16.779s. See compile20-maven.txt. No new harness claimed for signature-only cleanup.

Every focused root status is failed due missing unselected mandatory tests (or the documented actual failure), preservation passed. These are not full-suite passes. Copies of result/input/summary/target and run+emulator manifests are under evidence/. Original raw logs/XML remain in exact named root. Failed emulator/DB/media not deleted.

## Assertion boundaries actually exercised

- Genuine product3000/5000 -> paid/delivered original shipment; same selected shipment max5000 rather than8000; seller-responsible unit excluded; quote hold/payment/R/slot/A0; saved money JSON string and quote immutability.
- Actual B hold + common RETURN intent + consent + R3000/A0 + real0amount refund binding committed together; original accepted receipt replay unchanged.
- Actual nonzero refund provider SUCCEEDED/FAILED/UNKNOWN -> result ACK, public immutable result, replay result/event/Outbox1; return holdACTIVE/lifeHELD, stock unchanged/orderPAID/noCANCEL, fee stillBOUND/A0.
- First original unit net2000 fee3000 -> actual0refund/provider0 + stored result -> A3000/deduct2000/outstanding1000; same shipment different free unit net1000 fee5000 -> only newdelta2000, netdeduct1000/newoutstanding1000; final A5000/deduct3000/outstanding2000/collected0/R0/slotNULL. Prior outstanding was not automatically offset. Same application receipt replay. Original RETURN holds2ACTIVE/lifeHELD, inventory restitution0, no parent closure.
- RESERVED withdraw -> actual paymentWITHDRAWN; FAILED remainsFAILED; feeR0 and B RELEASED/outcomeWITHDRAWN/original unitOPEN; stable receipt replay. UNKNOWN withdrawal409 and bucket/hold unchanged.
- Existing CANCEL partial/full and FAILED/UNKNOWN result replay regression assertions remain in selected6/7. R-C01/R-C02 immutable core-fix-review1 already independently ADDRESSED; new router must not undo them.

## Explicitly not implemented or not yet evidenced

No final C approval/full run/independent full. Original mandatory421 retained with additions in required-preservation.json, no skip/timeout/workers changes. Frontend234/browser118/Node75 unchanged and not rerun for this slice.

Remaining C01~11 assertions include genuine discounted33001/26004/free-shipping money fixtures, comprehensive current member/GAS/SUPPORT/CSRF and after-final-lock expiry waits, cancel-vs-dispatch/fee bucket and parent budget races, multi-bucket rollback, full directSQL bypass checks, fee acceptance/application/withdrawal history/Outbox failure injections/final-lease rollback/child-crash evidence, explicit UNKNOWN/FAILED apply denial and positiveFAILEDretry sameassessment/R preservation, RETURN genuine coupon-reservation no-effect evidence, actual old-format JSON/legacy migration+consent, product boundary/auth/quote-vs-order race, cancellation get/withdraw/SUPPORT APIs, frontend/browser, final sourcefreeze/preservation85643 audit/unselected full and designated independent full.

No real return endpoint/approval/collection/inspection/stock/coupon/order completion or extra fee collection/waiver. D remains unstarted. This common fee test coordinator integration is not user return E2E.
