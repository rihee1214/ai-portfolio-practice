package com.example.marketplace.order.domain;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
class ConfirmationRulesTest {
 @Test void sevenDayBoundaryUsesDeliveredUnits() {
  Instant delivered=Instant.parse("2026-10-20T01:02:03.123456789Z"),due=ConfirmationRules.due(delivered,604800);
  assertEquals(Instant.parse("2026-10-27T01:02:03.123456Z"),due);
  assertTrue(ConfirmationRules.claim(due.minusNanos(1000),due));assertFalse(ConfirmationRules.automatic(due.minusNanos(1000),due));
  assertFalse(ConfirmationRules.claim(due,due));assertTrue(ConfirmationRules.automatic(due,due));
  assertFalse(ConfirmationRules.claim(due.plusNanos(1000),due));assertTrue(ConfirmationRules.automatic(due.plusNanos(1000),due));
  assertFalse(ConfirmationRules.automatic(due.minusSeconds(60),due));assertEquals(due,ConfirmationRules.due(delivered,604800));
 }
}
