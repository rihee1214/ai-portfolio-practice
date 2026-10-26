package com.example.marketplace.promotion.domain;
import com.example.marketplace.promotion.api.PromotionRejected;
import java.math.BigInteger;
import java.time.Instant;
public final class PromotionRules {
    private PromotionRules() { }
    public static void inputAmount(long value,Long cap) {
        if(value<0||value>9007199254740991L||cap!=null&&(cap<0||cap>9007199254740991L)) { throw new PromotionRejected(400); }
    }
    public static com.example.marketplace.promotion.api.Promotions.Promotion best(java.util.List<com.example.marketplace.promotion.api.Promotions.Promotion> candidates,long base) {
        return candidates.stream().sorted(java.util.Comparator.<com.example.marketplace.promotion.api.Promotions.Promotion>comparingLong(p->discount(base,p.kind(),p.value(),p.cap())).reversed().thenComparing(p->p.id().toString())).findFirst().orElse(null);
    }
    public static void period(Instant start,Instant end) {
        if(start==null||end==null||!start.isBefore(end)) { throw new PromotionRejected(400); }
    }
    public static boolean active(Instant start,Instant end,Instant at) { return !at.isBefore(start)&&at.isBefore(end); }
    public static long discount(long base,String kind,long value,Long cap) {
        if(base<0||kind==null||value<0||cap!=null&&cap<0) { throw new PromotionRejected(400); }
        long result=switch(kind) {
            case "FIXED" -> { if(value==0) { throw new PromotionRejected(400); } yield Math.min(base,value); }
            case "RATE" -> { if(value<1||value>10000) { throw new PromotionRejected(400); } yield BigInteger.valueOf(base).multiply(BigInteger.valueOf(value)).divide(BigInteger.valueOf(10000)).longValueExact(); }
            case "FREE" -> { if(value!=0||cap!=null) { throw new PromotionRejected(400); } yield base; }
            default -> throw new PromotionRejected(400);
        };
        return cap==null?result:Math.min(result,cap);
    }
}
