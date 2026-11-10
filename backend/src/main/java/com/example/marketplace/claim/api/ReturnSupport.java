package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.UUID;

/** Actual current SUPPORT commands retain the accepted buyer terms and original financial obligation. */
public interface ReturnSupport {
 record ShippingState(UUID returnId,UUID sellerOrderId,UUID refundAttemptId,long revision,String state,String amount,int sequence,String resultState) { }
 ShippingState shipping(String number,OrderQueries.Authority operator,UUID returnId);
 record Receipt(UUID commandId,UUID returnId,UUID refundAttemptId,String purpose,String action,long revision,Instant acceptedAt,Returns.Progress progress) { }
 Receipt retryFailed(String number,OrderQueries.Authority operator,UUID returnId,String purpose,ClaimSupport.Command command);
 Receipt queryRefund(String number,OrderQueries.Authority operator,UUID returnId,String purpose,ClaimSupport.Command command);
}
