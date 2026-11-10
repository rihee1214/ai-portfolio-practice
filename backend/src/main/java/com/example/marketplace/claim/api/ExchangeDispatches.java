package com.example.marketplace.claim.api;
import com.example.marketplace.order.api.OrderQueries;
import java.util.UUID;
public interface ExchangeDispatches {
 Exchanges.Progress request(UUID id,OrderQueries.Authority authority,Exchanges.Command command);
}
