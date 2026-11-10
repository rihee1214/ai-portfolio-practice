package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.UUID;

/** Current SUPPORT preserves the actual first EXCHANGE refund consent; it never acts as its buyer. */
public interface ExchangeRefundSupport {
 record ShippingState(UUID exchangeId,UUID sellerOrderId,UUID refundAttemptId,long revision,String state,String amount,int sequence,String resultState) { }
 ShippingState shipping(String number,OrderQueries.Authority operator,UUID exchangeId);
 record Receipt(UUID commandId,UUID exchangeId,UUID refundAttemptId,String purpose,String action,long revision,Instant acceptedAt,Exchanges.Progress progress) { }
 Receipt retryFailed(String number,OrderQueries.Authority operator,UUID exchangeId,String purpose,ClaimSupport.Command command);
 Receipt queryRefund(String number,OrderQueries.Authority operator,UUID exchangeId,String purpose,ClaimSupport.Command command);
}
