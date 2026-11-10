package com.example.marketplace.order.api;

import com.example.marketplace.catalog.api.ReturnPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable original delivery/policy evidence, owned by order. No return workflow completion is implied. */
public interface ReturnSources {
 record Unit(UUID itemId,int ordinal,UUID skuId,UUID originalShipmentId,UUID deliveryEventId,Instant deliveredAt,long net,String policyStatus,ReturnPolicy returnPolicy,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) PhysicalOrders.Source physical) {
  public Unit(UUID itemId,int ordinal,UUID skuId,UUID originalShipmentId,UUID deliveryEventId,Instant deliveredAt,long net,String policyStatus,ReturnPolicy returnPolicy) { this(itemId,ordinal,skuId,originalShipmentId,deliveryEventId,deliveredAt,net,policyStatus,returnPolicy,null); }
 }
 record Selection(UUID orderId,UUID sellerOrderId,UUID checkoutId,String mode,long revision,List<Unit> units) { public Selection { units=List.copyOf(units); } }
 /** Null hold selects currently OPEN units for a quote; otherwise requires the actual active RETURN hold. */
 Selection lock(String number,OrderQueries.Authority authority,List<OrderConfirmations.Unit> units,UUID holdId);
}
