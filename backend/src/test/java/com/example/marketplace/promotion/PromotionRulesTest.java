package com.example.marketplace.promotion;

import com.example.marketplace.promotion.domain.PromotionRules;
import com.example.marketplace.promotion.api.PromotionRejected;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromotionRulesTest {
    @Test void safeInputsAndDeterministicMaximum() {
        PromotionRules.inputAmount(9007199254740991L,null);
        assertThrows(PromotionRejected.class,()->PromotionRules.inputAmount(9007199254740992L,null));
        assertThrows(PromotionRejected.class,()->PromotionRules.inputAmount(1L,9007199254740993L));
        var now=Instant.parse("2026-01-01T00:00:00Z");var id=java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        var a=new com.example.marketplace.promotion.api.Promotions.Promotion(id,id,"a","SHIPPING",java.util.List.of(),now,now.plusSeconds(1),"FIXED",1000,null,"ACTIVE",0);
        var b=new com.example.marketplace.promotion.api.Promotions.Promotion(java.util.UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),id,"b","SHIPPING",java.util.List.of(),now,now.plusSeconds(1),"FIXED",1000,null,"ACTIVE",0);
        assertEquals(a,PromotionRules.best(java.util.List.of(b,a),3000));
        assertEquals(a,PromotionRules.best(java.util.List.of(a,b),3000));
        var c=new com.example.marketplace.promotion.api.Promotions.Promotion(id,id,"c","SHIPPING",java.util.List.of(),now,now.plusSeconds(1),"FREE",0,null,"ACTIVE",0);
        assertEquals(c,PromotionRules.best(java.util.List.of(a,c),3000));
    }

    @Test void freeShippingCannotPromisePartialDiscount() {
        assertEquals(3000,PromotionRules.discount(3000,"FREE",0,null));
        assertThrows(PromotionRejected.class,()->PromotionRules.discount(3000,"FREE",0,0L));
        assertThrows(PromotionRejected.class,()->PromotionRules.discount(3000,"FREE",0,1000L));
    }

    @Test void periodAndDiscountBoundaries() {
        Instant start=Instant.parse("2026-01-01T00:00:00Z"),end=start.plusSeconds(60);
        assertFalse(PromotionRules.active(start,end,start.minusNanos(1)));
        assertTrue(PromotionRules.active(start,end,start));
        assertFalse(PromotionRules.active(start,end,end));
        assertThrows(PromotionRejected.class,()->PromotionRules.period(end,start));
        assertEquals(1000,PromotionRules.discount(10001,"RATE",1000,null));
        assertEquals(100,PromotionRules.discount(100,"FIXED",200,null));
        assertEquals(70,PromotionRules.discount(100,"RATE",10000,70L));
        assertEquals(Long.MAX_VALUE,PromotionRules.discount(Long.MAX_VALUE,"RATE",10000,null));
        assertEquals(0,PromotionRules.discount(0,"FREE",0,null));
        assertThrows(PromotionRejected.class,()->PromotionRules.discount(-1,"FIXED",1,null));
        assertThrows(PromotionRejected.class,()->PromotionRules.discount(100,"RATE",10001,null));
        assertThrows(PromotionRejected.class,()->PromotionRules.discount(100,"FIXED",-1,null));
    }
}
