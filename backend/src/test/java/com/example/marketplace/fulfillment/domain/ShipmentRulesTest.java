package com.example.marketplace.fulfillment.domain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
class ShipmentRulesTest {
 @Test void splitQuantitiesStayWithinOriginal() throws Exception {
  var method=java.util.Arrays.stream(ShipmentRules.class.getMethods()).filter(m->m.getName().equals("splitQuantities")&&m.getParameterCount()==2).findFirst();
  assertTrue(method.isPresent(),"Split must validate a nonempty proper subset before mutating units");
  org.junit.jupiter.api.Assertions.assertEquals(true,method.orElseThrow().invoke(null,java.util.List.of(5),java.util.List.of(2)));
  for(int quantity:java.util.List.of(-1,0,5,6)) { org.junit.jupiter.api.Assertions.assertEquals(false,method.orElseThrow().invoke(null,java.util.List.of(5),java.util.List.of(quantity))); }
  org.junit.jupiter.api.Assertions.assertEquals(true,method.orElseThrow().invoke(null,java.util.List.of(2,3),java.util.List.of(2,0)));
 }
 @Test void basicTransitionsAndEvents() {
  assertTrue(ShipmentRules.allowed("PREPARING","SHIPPED","SELLER"));
  assertTrue(ShipmentRules.allowed("SHIPPED","IN_TRANSIT","MOCK_PROVIDER"));
  assertTrue(ShipmentRules.allowed("IN_TRANSIT","DELIVERED","MOCK_PROVIDER"));
  for(String current:java.util.List.of("PREPARING","SHIPPED","IN_TRANSIT","DELIVERED")) {
   assertFalse(ShipmentRules.allowed(current,"DELIVERED","SELLER"));
   assertFalse(ShipmentRules.allowed(current,current,"SELLER"));
   assertFalse(ShipmentRules.allowed(current,"PREPARING","MOCK_PROVIDER"));
  }
  assertFalse(ShipmentRules.allowed("SHIPPED","DELIVERED","MOCK_PROVIDER"));
  assertFalse(ShipmentRules.allowed("PREPARING","SHIPPED","MOCK_PROVIDER"));
  assertFalse(ShipmentRules.allowed("PREPARING","SHIPPED","CLIENT"));
 }
 @Test void trackingAndExceptionContracts() {
  assertTrue(ShipmentRules.tracking("A","등록"));assertTrue(ShipmentRules.tracking("A".repeat(64),"이".repeat(200)));
  for(String value:java.util.List.of("","A".repeat(65)," A","A ","https://a","한글","A\n")) { assertFalse(ShipmentRules.tracking(value,"등록")); }
  for(String reason:java.util.List.of(""," ","\n","이".repeat(201))) { assertFalse(ShipmentRules.tracking("MOCK-1",reason)); }
  for(String exception:java.util.List.of("DELAYED","FAILED","RESUME")) { assertFalse(ShipmentRules.allowed("SHIPPED",exception,"MOCK_PROVIDER"));assertFalse(ShipmentRules.allowed(exception,"DELIVERED","MOCK_PROVIDER")); }
 }
}
