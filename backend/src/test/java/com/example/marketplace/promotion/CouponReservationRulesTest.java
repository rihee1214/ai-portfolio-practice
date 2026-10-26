package com.example.marketplace.promotion;
import com.example.marketplace.promotion.domain.CouponReservationRules;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class CouponReservationRulesTest {
 @Test void transitionsAndRestoration(){
  Instant end=Instant.parse("2026-01-01T00:15:00Z");
  assertEquals("COMMITTED",CouponReservationRules.transition("RESERVED","COMMITTED",end.minusNanos(1),end));
  assertThrows(RuntimeException.class,()->CouponReservationRules.transition("RESERVED","COMMITTED",end,end));
  assertEquals("EXPIRED",CouponReservationRules.transition("RESERVED","EXPIRED",end,end));
  assertThrows(RuntimeException.class,()->CouponReservationRules.transition("RESERVED","EXPIRED",end.minusNanos(1),end));
  assertEquals("RELEASED",CouponReservationRules.transition("RESERVED","RELEASED",end,end));
  for(String state:java.util.List.of("RELEASED","EXPIRED","RESTORED")){assertThrows(RuntimeException.class,()->CouponReservationRules.transition(state,"COMMITTED",end.minusSeconds(1),end));}
  assertEquals("COMMITTED",CouponReservationRules.transition("COMMITTED","COMMITTED",end.plusSeconds(1),end));
  assertFalse(CouponReservationRules.restorable("COMMITTED",false,true));assertFalse(CouponReservationRules.restorable("COMMITTED",true,false));assertTrue(CouponReservationRules.restorable("COMMITTED",true,true));assertFalse(CouponReservationRules.restorable("RESTORED",true,true));
  assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->CouponReservationRules.transition(null,"COMMITTED",end,end));
  assertFalse(CouponReservationRules.reusable(end,end));assertTrue(CouponReservationRules.reusable(end.minusNanos(1),end));
 }
}
