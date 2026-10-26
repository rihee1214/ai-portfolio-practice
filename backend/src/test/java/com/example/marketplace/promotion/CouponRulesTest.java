package com.example.marketplace.promotion;
import com.example.marketplace.promotion.domain.CouponRules;
import com.example.marketplace.promotion.api.Coupons;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class CouponRulesTest {
    @Test void couponEligibilityAndSnapshot() {
        Instant start=Instant.parse("2026-01-01T00:00:00Z"),end=start.plusSeconds(60);
        var terms=new Coupons.Terms("RATE",1000,100L,1000,start,end);
        assertEquals(0,CouponRules.discount(terms,999,start));
        assertEquals(100,CouponRules.discount(terms,1000,start));
        assertEquals(0,CouponRules.discount(terms,1000,start.minusNanos(1)));
        assertEquals(0,CouponRules.discount(terms,1000,end));
        assertEquals(100,CouponRules.discount(terms,Long.MAX_VALUE,end.minusNanos(1)));
        assertEquals(10,CouponRules.discount(new Coupons.Terms("FIXED",1000,null,0,start,end),10,start));
        assertThrows(RuntimeException.class,()->CouponRules.validate(new Coupons.Terms("FREE",0,null,0,start,end),start,end,1));
        assertThrows(RuntimeException.class,()->CouponRules.validate(terms,start,end,0));
        assertThrows(RuntimeException.class,()->CouponRules.validate(terms,end,start,1));
        assertThrows(RuntimeException.class,()->CouponRules.validate(new Coupons.Terms("FIXED",1,null,-1,start,end),start,end,1));
        assertEquals(1000,terms.minimum());assertEquals(end,terms.endsAt());
    }
}
