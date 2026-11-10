package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.domain.AftercareShippingRules;
import com.example.marketplace.order.api.AftercareSettlements;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Claim-owned physical/financial facts combined with the public order snapshot. */
final class ReturnAftercareEvidence {
 private ReturnAftercareEvidence() { }
 static List<AftercareShippingRules.Unit> units(NamedParameterJdbcTemplate jdbc,AftercareSettlements.AftercareView view) {
  var result=new ArrayList<AftercareShippingRules.Unit>();
  for(var unit:view.units()) {
   var ready=jdbc.queryForList("SELECT u.return_id,u.responsibility,r.hold_id FROM marketplace.claim_return_ready_unit u JOIN marketplace.claim_return_request r ON r.id=u.return_id JOIN marketplace.claim_return_item_ready x ON x.return_id=r.id WHERE u.item_id=:item AND u.ordinal=:ordinal AND r.order_id=:order AND r.seller_order_id=:seller",Map.of("item",unit.itemId(),"ordinal",unit.ordinal(),"order",view.orderId(),"seller",view.sellerOrderId()));
   var exchange=jdbc.queryForList("SELECT u.exchange_id,u.responsibility,r.hold_id FROM marketplace.claim_exchange_refund_ready_unit u JOIN marketplace.claim_exchange_refund_item_ready r ON r.exchange_id=u.exchange_id WHERE u.item_id=:item AND u.ordinal=:ordinal AND r.order_id=:order AND r.seller_order_id=:seller",Map.of("item",unit.itemId(),"ordinal",unit.ordinal(),"order",view.orderId(),"seller",view.sellerOrderId()));
   if(exchange.size()>1||!exchange.isEmpty()&&!ready.isEmpty()) { throw new com.example.marketplace.order.api.OrderRejected(409); }
   AftercareShippingRules.Kind kind;UUID fact=null;
   if(unit.state().equals("CONFIRMED")&&unit.confirmationId()!=null) { kind=AftercareShippingRules.Kind.CONFIRMED;fact=unit.confirmationId(); }
   else if(unit.state().equals("REFUNDED")&&unit.cancellationCauseId()!=null&&!unit.dispatched()) { kind=AftercareShippingRules.Kind.CANCELED;fact=unit.cancellationCauseId(); }
   else if(exchange.size()==1&&unit.dispatched()&&(unit.state().equals("HELD")&&exchange.getFirst().get("hold_id").equals(unit.activeHoldId())||unit.state().equals("REFUNDED")&&exchange.getFirst().get("exchange_id").equals(unit.exchangeRefundCompletionId()))) { var e=exchange.getFirst();kind=e.get("responsibility").equals("SELLER")?AftercareShippingRules.Kind.SELLER_EXCHANGE_REFUND_READY:AftercareShippingRules.Kind.BUYER_EXCHANGE_REFUND_READY;fact=(UUID)e.get("exchange_id"); }
   else if(ready.size()==1&&unit.dispatched()&&(unit.state().equals("HELD")&&ready.getFirst().get("hold_id").equals(unit.activeHoldId())||unit.state().equals("REFUNDED")&&unit.returnCompletionId()!=null)) {
    var r=ready.getFirst();kind=r.get("responsibility").equals("SELLER")?AftercareShippingRules.Kind.SELLER_RETURN_READY:AftercareShippingRules.Kind.BUYER_RETURN_READY;fact=(UUID)r.get("return_id");
   }else { kind=unit.state().equals("OPEN")?AftercareShippingRules.Kind.OPEN:AftercareShippingRules.Kind.UNRESOLVED; }
   result.add(new AftercareShippingRules.Unit(unit.itemId(),unit.ordinal(),kind,fact));
  }
  return List.copyOf(result);
 }

 static String fingerprint(AftercareSettlements.AftercareView view,List<AftercareShippingRules.Unit> units,AftercareShippingRules.Evaluation evaluation) {
  return ReturnFeeQuoting.hash(view.orderId()+"|"+view.sellerOrderId()+"|"+view.originalShipping()+"|"+units+"|"+evaluation);
 }
}
