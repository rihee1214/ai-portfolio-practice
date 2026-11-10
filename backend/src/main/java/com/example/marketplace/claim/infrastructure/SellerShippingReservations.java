package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.domain.AftercareShippingRules;
import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** One allocator for both original C and shared C/D shipping evaluation. */
final class SellerShippingReservations {
 private SellerShippingReservations() { }
 static void reserve(NamedParameterJdbcTemplate jdbc,FinancialIntents intents,Refunds refunds,AftercareSettlements.AftercareView view,List<AftercareShippingRules.Unit> evidence,UUID preferredCancellation) {
  if(AftercareShippingRules.evaluate(view.originalShipping(),evidence).state()!=AftercareShippingRules.State.REFUND_REQUIRED) { throw new OrderRejected(409); }
  var prior=jdbc.queryForList("SELECT order_id,claim_id,amount,refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:seller FOR UPDATE",Map.of("seller",view.sellerOrderId()));
  if(!prior.isEmpty()) { var row=prior.getFirst();if(!row.get("order_id").equals(view.orderId())||((Number)row.get("amount")).longValue()!=view.originalShipping()) { throw new OrderRejected(409); }var actual=refunds.get((UUID)row.get("refund_attempt_id"));if(!actual.purpose().equals("SHIPPING")||!actual.claimId().equals(row.get("claim_id"))||!actual.orderId().equals(view.orderId())||!actual.sellerOrderId().equals(view.sellerOrderId())||actual.amount()!=view.originalShipping()) { throw new OrderRejected(409); }return; }
  UUID origin;if(preferredCancellation!=null) {
   if(evidence.stream().noneMatch(u->u.kind()==AftercareShippingRules.Kind.CANCELED&&preferredCancellation.equals(u.factId()))) { throw new OrderRejected(409); }origin=preferredCancellation;
  }else { origin=evidence.stream().filter(u->u.kind()==AftercareShippingRules.Kind.SELLER_RETURN_READY||u.kind()==AftercareShippingRules.Kind.SELLER_EXCHANGE_REFUND_READY).map(AftercareShippingRules.Unit::factId).findFirst().orElseGet(()->evidence.stream().filter(u->u.kind()==AftercareShippingRules.Kind.CANCELED).map(AftercareShippingRules.Unit::factId).findFirst().orElseThrow(()->new OrderRejected(409))); }
  var intent=intents.lock(origin);if(!intent.orderId().equals(view.orderId())||!intent.sellerOrderId().equals(view.sellerOrderId())) { throw new OrderRejected(409); }
  var original=refunds.original(view.orderId());if(!original.orderId().equals(view.orderId())||original.amount()!=view.originalPaidAmount()||!original.mode().equals(view.mode())||view.recordedPaymentIds().stream().anyMatch(p->!p.equals(original.paymentId()))) { throw new OrderRejected(409); }
  UUID attempt=named("cancel-shipping-attempt:"+view.sellerOrderId()),cause=named("cancel-shipping-cause:"+view.sellerOrderId());
  jdbc.update("INSERT INTO marketplace.claim_shipping_obligation(seller_order_id,order_id,claim_id,amount,refund_attempt_id,state) VALUES(:seller,:order,:intent,:amount,:attempt,'RESERVED')",Map.of("seller",view.sellerOrderId(),"order",view.orderId(),"intent",intent.id(),"amount",view.originalShipping(),"attempt",attempt));
  refunds.reserve(new Refunds.Reserve(attempt,cause,original.paymentId(),view.orderId(),view.sellerOrderId(),intent.id(),"SHIPPING",1,view.originalShipping(),view.quoteFingerprint()));
  switch(intent.kind()) { case "RETURN" -> intents.bindReturnShipping(intent.id(),attempt);case "EXCHANGE_REFUND" -> intents.bindExchangeRefundShipping(intent.id(),attempt);case "CANCEL" -> intents.bindRefund(intent.id(),attempt);default -> throw new OrderRejected(409); }
 }
 private static UUID named(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)); }
}
