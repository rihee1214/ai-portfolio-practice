package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.ReturnApplications;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Actor-bound current read projections; accepted receipts remain separate and immutable. */
public interface ReturnQueries {
 record Summary(UUID returnId,String state,long version,Instant requestedAt,List<Returns.Unit> units) { public Summary { units=List.copyOf(units); } }
 record Page(List<Summary> returns,String nextCursor,long orderRevision,ReturnApplications.Eligibility eligibility,List<Shipping> shipping) { public Page { returns=List.copyOf(returns);shipping=List.copyOf(shipping); } }
 record Item(String purpose,String state,UUID assessmentId,UUID refundAttemptId,int sequence,long resultVersion,String amount,String feeIncrement,String additionalDue,String reference,Instant resultAt,String assessmentState) { }
 record Shipping(String purpose,String decision,String reason,long decisionRevision,long revision,String state,UUID refundAttemptId,int sequence,long resultVersion,String amount,String reference,Instant resultAt,String resultState,UUID sellerOrderId,String originKind,UUID originIntentId,String supportContract,String commandRevisionKind,long commandRevision,List<String> supportActions) { public Shipping { supportActions=List.copyOf(supportActions); } }
 record OriginalUnit(UUID sellerOrderId,UUID itemId,int ordinal,String title,String net,com.example.marketplace.catalog.api.ReturnPolicy returnPolicy) { }
 record Detail(Returns.Progress progress,long orderRevision,String orderState,List<OriginalUnit> originalUnits,Item item,Shipping shipping,List<String> allowedActions) { public Detail { originalUnits=List.copyOf(originalUnits);allowedActions=List.copyOf(allowedActions); } }
 Page owner(String number,OrderQueries.Authority authority,int size,String cursor);
 Detail owner(String number,UUID id,OrderQueries.Authority authority);
 Page seller(UUID sellerOrderId,OrderQueries.Authority authority,int size,String cursor);
 Detail seller(UUID sellerOrderId,UUID id,OrderQueries.Authority authority);
 Page support(String number,OrderQueries.Authority authority,int size,String cursor);
 Detail support(String number,UUID id,OrderQueries.Authority authority);
}
