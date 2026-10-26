package com.example.marketplace.promotion.domain;
import com.example.marketplace.promotion.api.Coupons.Terms;
import com.example.marketplace.promotion.api.PromotionRejected;
import java.time.Instant;
public final class CouponRules {
    private CouponRules() { }
    public static void validate(Terms t,Instant issueStart,Instant issueEnd,long limit) {
        if(t==null||!java.util.Set.of("FIXED","RATE").contains(t.kind()==null?"":t.kind())) { throw new PromotionRejected(400); }
        PromotionRules.period(t.startsAt(),t.endsAt());PromotionRules.period(issueStart,issueEnd);
        PromotionRules.inputAmount(t.value(),t.cap());PromotionRules.inputAmount(t.minimum(),null);
        PromotionRules.discount(0,t.kind(),t.value(),t.cap());
        if(limit<1||limit>9007199254740991L||issueEnd.isAfter(t.endsAt())) { throw new PromotionRejected(400); }
    }
    public static boolean inUsePeriod(Terms t,Instant at) { return at!=null&&PromotionRules.active(t.startsAt(),t.endsAt(),at); }
    public static long discount(Terms t,long subtotal,Instant at) {
        if(subtotal<0) { throw new PromotionRejected(400); }
        return inUsePeriod(t,at)&&subtotal>=t.minimum()?PromotionRules.discount(subtotal,t.kind(),t.value(),t.cap()):0;
    }
}
