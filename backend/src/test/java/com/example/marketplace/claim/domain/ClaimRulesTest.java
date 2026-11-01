package com.example.marketplace.claim.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaimRulesTest {
 private static final UUID SHIPMENT=new UUID(0,10),OTHER=new UUID(0,11),ITEM=new UUID(0,1),SECOND=new UUID(0,2);
 private ClaimRules.Target target(UUID shipment,UUID item,int ordinal,long net,long policy,boolean buyer) { return new ClaimRules.Target(shipment,item,ordinal,net,policy,buyer); }
 @Test void returnFeeQuoteNeverBecomesNegative() {
  var buyer=assertDoesNotThrow(()->ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,10001,3000,true)),Map.of()));assertEquals(7001,buyer.refund());assertEquals(3000,buyer.increment());assertEquals(0,buyer.additionalDue());
  var seller=ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,10001,5000,false)),Map.of());assertEquals(10001,seller.refund());assertEquals(0,seller.increment());
  var shortfall=ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,2000,3000,true)),Map.of());assertEquals(0,shortfall.refund());assertEquals(2000,shortfall.allocations().getFirst().deducted());assertEquals(1000,shortfall.additionalDue());
 }
 @Test void originalShipmentUsesReturnedTargetMaximumAndSubsequentDifference() {
  var same=assertDoesNotThrow(()->ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,10000,3000,true),target(SHIPMENT,ITEM,1,10000,3000,true)),Map.of()));assertEquals(3000,same.increment());assertEquals(17000,same.refund());
  var higher=ClaimRules.quote(List.of(target(SHIPMENT,SECOND,0,1000,5000,true)),Map.of(SHIPMENT,3000L));assertEquals(2000,higher.increment());assertEquals(1000,higher.additionalDue());assertEquals(5000,higher.buckets().getFirst().target());
  assertEquals(0,ClaimRules.quote(List.of(target(SHIPMENT,ITEM,1,10000,3000,true)),Map.of(SHIPMENT,5000L)).increment());assertEquals(0,ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,100,0,true)),Map.of()).increment());
 }
 @Test void differentOriginalShipmentsSumAndSellerResponsibilityIsExcluded() {
  var different=assertDoesNotThrow(()->ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,10000,3000,true),target(OTHER,SECOND,0,10000,5000,true)),Map.of()));assertEquals(8000,different.increment());
  var mixed=ClaimRules.quote(List.of(target(SHIPMENT,SECOND,0,10001,5000,false),target(SHIPMENT,ITEM,0,10001,3000,true)),Map.of());assertEquals(3000,mixed.increment());assertEquals(0,mixed.allocations().stream().filter(a->a.itemId().equals(SECOND)).findFirst().orElseThrow().deducted());
 }
 @Test void canonicalUnitAllocationPreservesOriginalResidualAndPriorOutstanding() {
  var quote=assertDoesNotThrow(()->ClaimRules.quote(List.of(target(SHIPMENT,SECOND,2,8002,3000,true),target(SHIPMENT,ITEM,0,8001,3000,true)),Map.of()));assertEquals(13003,quote.refund());assertEquals(3000,quote.allocations().getFirst().deducted());assertEquals(0,quote.allocations().getLast().deducted());
  var first=ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,2000,3000,true)),Map.of());var next=ClaimRules.quote(List.of(target(SHIPMENT,SECOND,0,1000,5000,true)),Map.of(SHIPMENT,first.buckets().getFirst().target()));assertEquals(3000,first.allocations().getFirst().deducted()+next.allocations().getFirst().deducted());assertEquals(2000,first.additionalDue()+next.additionalDue());assertEquals(5000,next.buckets().getFirst().target());
 }
 @Test void feesAndOriginalNetUseCheckedLongAndRejectDuplicateUnits() {
  assertThrows(ArithmeticException.class,()->ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,1,Long.MAX_VALUE,true),target(OTHER,SECOND,0,1,Long.MAX_VALUE,true)),Map.of()));assertThrows(ArithmeticException.class,()->ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,Long.MAX_VALUE,0,true),target(OTHER,SECOND,0,1,0,true)),Map.of()));
  assertThrows(IllegalArgumentException.class,()->ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,100,3000,true),target(OTHER,ITEM,0,100,5000,true)),Map.of()));assertThrows(IllegalArgumentException.class,()->ClaimRules.quote(List.of(target(SHIPMENT,ITEM,0,-1,3000,true)),Map.of()));
 }
}
