package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Claims {
 record QuoteCommand(List<OrderConfirmations.Unit> units,String reason,Long expectedRevision) { }
 record Unit(UUID itemId,int ordinal,String net) { }
 record Quote(String fingerprint,String policyVersion,Instant quotedAt,long revision,List<Unit> units,String itemAmount,String conditionalShippingMaximum,String shippingCondition) {
  public Quote { units=List.copyOf(units); }
 }
 Quote quote(String number,OrderQueries.Authority authority,QuoteCommand command);
 record CancelCommand(String key,List<OrderConfirmations.Unit> units,String reason,Long expectedRevision,String quoteFingerprint) { }
 record Receipt(UUID claimId,UUID refundAttemptId,String policyVersion,long revision,List<Unit> units,String itemAmount,String state) { public Receipt { units=List.copyOf(units); } }
 Receipt cancel(String number,OrderQueries.Authority authority,CancelCommand command);
 record RefundProgress(UUID attemptId,String purpose,int sequence,String amount,String state,long version,String reference,Instant resultAt) { }
 record History(String action,long version,Instant occurredAt) { }
 record Progress(Receipt accepted,String state,long version,List<RefundProgress> refunds,List<History> history) { public Progress { refunds=List.copyOf(refunds);history=List.copyOf(history); } }
 List<Progress> get(String number,OrderQueries.Authority authority);
 record WithdrawCommand(String key,Long expectedRevision,String reason) { }
 record Withdrawal(UUID claimId,UUID refundAttemptId,String state,long version,Instant withdrawnAt) { }
 Withdrawal withdraw(String number,OrderQueries.Authority authority,UUID claimId,WithdrawCommand command);
}
