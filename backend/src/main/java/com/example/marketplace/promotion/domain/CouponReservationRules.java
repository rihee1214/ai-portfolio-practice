package com.example.marketplace.promotion.domain;
import com.example.marketplace.promotion.api.PromotionRejected;
import java.time.Instant;
public final class CouponReservationRules {
 private CouponReservationRules(){}
 public static String transition(String state,String target,Instant now,Instant expires){
  if(state==null||target==null||!java.util.Set.of("COMMITTED","RELEASED","EXPIRED").contains(target)||now==null||expires==null){throw new PromotionRejected(400);}
  if(state.equals(target)){return state;}
  if(!state.equals("RESERVED")||target.equals("COMMITTED")&&!now.isBefore(expires)||target.equals("EXPIRED")&&now.isBefore(expires)){throw new PromotionRejected(409);}
  return target;
 }
 public static boolean restorable(String state,boolean full,boolean confirmed){if(full&&confirmed&&!java.util.Set.of("COMMITTED","RESTORED").contains(state)){throw new PromotionRejected(409);}return state.equals("COMMITTED")&&full&&confirmed;}
 public static boolean reusable(Instant now,Instant ends){return now.isBefore(ends);}
}
