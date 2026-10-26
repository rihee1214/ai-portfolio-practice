package com.example.marketplace.promotion;
import com.example.marketplace.promotion.domain.QuoteCalculator;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class QuoteCalculatorTest {
    @Test void shippingThresholdAndDiscountOrder() {
        assertEquals(3000,QuoteCalculator.shipping(49999));assertEquals(0,QuoteCalculator.shipping(50000));
        assertEquals(40000,QuoteCalculator.storeTotal(50000,10000,0));
        assertEquals(76000,Math.addExact(QuoteCalculator.storeTotal(30000,0,0),QuoteCalculator.storeTotal(40000,0,0)));
        assertEquals(73000,Math.addExact(QuoteCalculator.storeTotal(30000,3000,0),QuoteCalculator.storeTotal(40000,0,0)));
        assertEquals(0,QuoteCalculator.storeTotal(1000,1000,3000));
        assertThrows(RuntimeException.class,()->QuoteCalculator.storeTotal(1,2,0));assertThrows(RuntimeException.class,()->QuoteCalculator.storeTotal(100,0,3001));
    }
    @Test void allocationConservesEveryWon() {
        UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002"),c=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        var weights=List.of(new QuoteCalculator.Weight(a,100),new QuoteCalculator.Weight(b,100),new QuoteCalculator.Weight(c,100));
        var split=QuoteCalculator.allocate(100,weights);assertEquals(34,split.get(a));assertEquals(33,split.get(b));assertEquals(33,split.get(c));assertEquals(split,QuoteCalculator.allocate(100,weights.reversed()));
        assertEquals(0,QuoteCalculator.allocate(0,List.of(new QuoteCalculator.Weight(a,0))).get(a));
        assertEquals(Long.MAX_VALUE,QuoteCalculator.allocate(Long.MAX_VALUE,List.of(new QuoteCalculator.Weight(a,Long.MAX_VALUE))).get(a));
        assertThrows(RuntimeException.class,()->QuoteCalculator.allocate(301,weights));
        var units=QuoteCalculator.units(101,1,99,99);assertEquals(99,units.size());assertEquals(9801,units.stream().mapToLong(u->u.net()).sum());assertEquals(99,units.stream().mapToLong(u->u.couponDiscount()).sum());
        assertEquals(0,QuoteCalculator.units(100,100,0,1).getFirst().net());
        assertEquals(0,QuoteCalculator.units(Long.MAX_VALUE,0,Long.MAX_VALUE,1).getFirst().net());
        assertThrows(RuntimeException.class,()->QuoteCalculator.units(Long.MAX_VALUE,0,0,99));
        assertThrows(UnsupportedOperationException.class,()->units.clear());
    }
}
