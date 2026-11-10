package com.example.marketplace.claim.domain;

import com.example.marketplace.claim.api.Returns;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReturnRulesTest {
 @Test void requestWindowUsesOriginalMicrosecondDeadline() {
  var delivered=Instant.parse("2026-11-01T01:02:03.123456Z");var due=delivered.plusSeconds(604800);
  assertTrue(ReturnRules.beforeVerifiedDeliveryDeadline(delivered,due.minusNanos(1000),604800));
  assertFalse(ReturnRules.beforeVerifiedDeliveryDeadline(delivered,due,604800));
  assertFalse(ReturnRules.beforeVerifiedDeliveryDeadline(delivered,due.plusNanos(1000),604800));
  // Actual-delivery absence is rejected by the real DB application test; raw observation order is not proof.
  assertTrue(ReturnRules.beforeVerifiedDeliveryDeadline(delivered,delivered.minusNanos(1000),604800));
  assertFalse(ReturnRules.beforeVerifiedDeliveryDeadline(null,due,604800));
  assertFalse(ReturnRules.beforeVerifiedDeliveryDeadline(Instant.MAX,Instant.MAX,604800));
 }
 @Test void requestCanonicalUnitsRejectDuplicatesAndMissingResponsibility() {
  UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002");
  var first=new Returns.Unit(a,0,Returns.Responsibility.BUYER);var second=new Returns.Unit(b,1,Returns.Responsibility.SELLER);
  assertEquals(List.of(first,second),ReturnRules.canonical(List.of(second,first)));
  assertThrows(IllegalArgumentException.class,()->ReturnRules.canonical(List.of(first,new Returns.Unit(a,0,Returns.Responsibility.SELLER))));
  assertThrows(IllegalArgumentException.class,()->ReturnRules.canonical(List.of(new Returns.Unit(a,-1,Returns.Responsibility.BUYER))));
  assertThrows(IllegalArgumentException.class,()->ReturnRules.canonical(List.of(new Returns.Unit(a,0,null))));
  assertThrows(IllegalArgumentException.class,()->ReturnRules.canonical(List.of()));
 }
}
