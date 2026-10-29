package com.example.marketplace.fulfillment.domain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
class ShipmentRulesTest {
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
}
