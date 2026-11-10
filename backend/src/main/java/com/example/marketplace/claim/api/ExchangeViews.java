package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Current actor-bound projection; immutable accepted and operation receipts keep their original shapes. */
public interface ExchangeViews {
 record Eligibility(String storeState,boolean canApprove,boolean canReceive,boolean canInspect,boolean canReship,boolean canExtend,boolean canRefundQuote,String reason) { }
 record Extension(int sequence,Instant previousExpiresAt,Instant extendedExpiresAt,Instant extendedAt,String reason) { }
 record Refund(UUID attemptId,String purpose,int sequence,long resultVersion,String state,String amount,String reference,Instant resultAt,List<String> supportActions) { public Refund { supportActions=List.copyOf(supportActions); } }
 record Financial(UUID consentId,UUID assessmentId,String assessmentState,String net,String fee,String refund,String additionalDue,Refund item,boolean itemReady,boolean completed) { }
 record Origin(String kind,UUID id,String supportContract,String revisionKind,long commandRevision) { }
 record Shipping(String decision,String reason,Long operationRevision,String state,String amount,Origin origin,Refund attempt) { }
 record Subject(UUID itemId,int ordinal,String title,String originalUnitPrice,String originalNet,com.example.marketplace.catalog.api.ReturnPolicy returnPolicy) { }
 record Current(Exchanges.Progress progress,String actor,String orderState,List<Subject> subjects,Eligibility eligibility,Extension extension,Financial financial,Shipping shipping,boolean replacementDelivered) { public Current { subjects=List.copyOf(subjects); } }
 record Page(long orderRevision,List<Exchanges.Progress> exchanges,List<Exchanges.Choice> choices,String nextCursor) { public Page { exchanges=List.copyOf(exchanges);choices=List.copyOf(choices); } }
 Current owner(String number,UUID id,OrderQueries.Authority authority);
 Current seller(UUID id,OrderQueries.Authority authority);
 Current support(String number,UUID id,OrderQueries.Authority authority);
 Page support(String number,OrderQueries.Authority authority,int size,String cursor);
}
