package com.example.marketplace.claim.domain;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.example.marketplace.claim.domain.AftercareShippingRules.Kind;
import com.example.marketplace.claim.domain.AftercareShippingRules.State;
import com.example.marketplace.claim.domain.AftercareShippingRules.Unit;

class AftercareShippingRulesTest {
 private static Unit unit(int ordinal,Kind kind) { return new Unit(new UUID(0,1),ordinal,kind,kind==Kind.OPEN||kind==Kind.UNRESOLVED?null:new UUID(0,2)); }
 @Test void allCanceledOrGenuinelySellerReadyUnitsRequireTheOneOriginalSellerCharge() {
  for(var units:List.of(List.of(unit(0,Kind.CANCELED),unit(1,Kind.CANCELED)),List.of(unit(0,Kind.SELLER_RETURN_READY),unit(1,Kind.SELLER_RETURN_READY)),List.of(unit(0,Kind.CANCELED),unit(1,Kind.SELLER_RETURN_READY)))) {
   var result=AftercareShippingRules.evaluate(6000,units);assertEquals(State.REFUND_REQUIRED,result.state());assertEquals(6000,result.amount());assertEquals(units.stream().allMatch(u->u.kind()==Kind.CANCELED),result.allCanceled());
  }
 }
 @Test void buyerOrKeptConfirmedUnitsRetainTheChargeOnlyForATerminalWholeSeller() {
  for(var units:List.of(List.of(unit(0,Kind.BUYER_RETURN_READY)),List.of(unit(0,Kind.SELLER_RETURN_READY),unit(1,Kind.BUYER_RETURN_READY)),List.of(unit(0,Kind.CONFIRMED),unit(1,Kind.SELLER_RETURN_READY)))) {
   var result=AftercareShippingRules.evaluate(3000,units);assertEquals(State.RETAINED,result.state());assertEquals(0,result.amount());
  }
 }
 @Test void anyOpenOrUnresolvedOriginalUnitKeepsTheDecisionPending() {
  for(var kind:List.of(Kind.OPEN,Kind.UNRESOLVED)) {for(var terminal:List.of(Kind.BUYER_RETURN_READY,Kind.SELLER_RETURN_READY,Kind.CONFIRMED,Kind.CANCELED)) {var result=AftercareShippingRules.evaluate(3000,List.of(unit(0,kind),unit(1,terminal)));assertEquals(State.PENDING,result.state());assertEquals(0,result.amount());}}
 }
 @Test void zeroOriginalShippingStillRequiresAnExplicitZeroSettlementDecision() {
  var result=AftercareShippingRules.evaluate(0,List.of(unit(0,Kind.SELLER_RETURN_READY)));assertEquals(State.REFUND_REQUIRED,result.state());assertEquals(0,result.amount());
 }
 @Test void confirmedRemainderHasExplicitRetainedDecisionReason() throws Exception {
  var result=AftercareShippingRules.evaluate(3000,List.of(unit(0,Kind.CONFIRMED),unit(1,Kind.SELLER_RETURN_READY)));var reason=java.util.Arrays.stream(result.getClass().getMethods()).filter(m->m.getName().equals("reason")&&m.getParameterCount()==0).findFirst().orElse(null);org.junit.jupiter.api.Assertions.assertTrue(reason!=null,"B-last needs a retained decision reason, not a pending or unproved closure");assertEquals("CONFIRMED_UNITS_REMAIN",reason.invoke(result).toString());
 }
 @Test void incompleteDuplicateOrMalformedOriginalEvidenceIsRejected() {
  assertThrows(IllegalArgumentException.class,()->AftercareShippingRules.evaluate(-1,List.of(unit(0,Kind.CANCELED))));assertThrows(IllegalArgumentException.class,()->AftercareShippingRules.evaluate(3000,null));assertThrows(IllegalArgumentException.class,()->AftercareShippingRules.evaluate(3000,List.of()));assertThrows(IllegalArgumentException.class,()->AftercareShippingRules.evaluate(3000,List.of(unit(0,Kind.CANCELED),unit(0,Kind.SELLER_RETURN_READY))));
  for(var invalid:List.of(new Unit(null,0,Kind.CANCELED,new UUID(0,2)),new Unit(new UUID(0,1),-1,Kind.CANCELED,new UUID(0,2)),new Unit(new UUID(0,1),99,Kind.CANCELED,new UUID(0,2)),new Unit(new UUID(0,1),0,null,new UUID(0,2)),new Unit(new UUID(0,1),0,Kind.CANCELED,null))) {assertThrows(IllegalArgumentException.class,()->AftercareShippingRules.evaluate(3000,List.of(invalid)));}
 }
}
