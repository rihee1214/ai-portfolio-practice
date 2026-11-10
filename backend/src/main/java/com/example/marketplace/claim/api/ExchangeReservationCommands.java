package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.util.UUID;

/** Explicit reservation lifecycle commands do not imply a refund or a released claim hold. */
public interface ExchangeReservationCommands {
 Exchanges.Progress extend(UUID exchangeId,OrderQueries.Authority seller,Exchanges.Command command);
}
