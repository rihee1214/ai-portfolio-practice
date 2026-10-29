package com.example.marketplace.order.domain;

import com.example.marketplace.promotion.api.Quotes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderRulesTest {
    @Test void snapshotAmountsConserve() {
        // Two stores, three units: 303 - 3 - 100 + 3000 + 200 = 3400.
        var units=List.of(new Quotes.Unit(0,101,1,34,66),new Quotes.Unit(1,101,1,33,67),new Quotes.Unit(2,101,1,33,67));
        var line=line(101,3,303,3,100,200,units);
        var first=store(line,303,3,100,3000,0,3200);
        var other=store(line(200,1,200,0,0,200,List.of(new Quotes.Unit(0,200,0,0,200))),200,0,0,0,0,200);
        assertEquals(3400,OrderRules.validateSnapshot(quote(List.of(first,other),3400)));
        assertThrows(RuntimeException.class,()->OrderRules.validateSnapshot(quote(List.of(first,other),3399)));
        var zero=store(line(100,1,100,100,0,0,List.of(new Quotes.Unit(0,100,100,0,0))),100,100,0,3000,3000,0);
        assertEquals(0,OrderRules.validateSnapshot(quote(List.of(zero),0)));
        var maximum=store(line(Long.MAX_VALUE,1,Long.MAX_VALUE,0,0,Long.MAX_VALUE,List.of(new Quotes.Unit(0,Long.MAX_VALUE,0,0,Long.MAX_VALUE))),Long.MAX_VALUE,0,0,0,0,Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE,OrderRules.validateSnapshot(quote(List.of(maximum),Long.MAX_VALUE)));
        assertThrows(RuntimeException.class,()->OrderRules.validateSnapshot(quote(List.of(maximum,other),199)));
    }
    @Test void snapshotStructureAndUnitAmountsAreValidated() {
        var valid=line(100,2,200,0,0,200,List.of(new Quotes.Unit(0,100,0,0,100),new Quotes.Unit(1,100,0,0,100)));
        var s=store(valid,200,0,0,0,0,200);
        assertThrows(RuntimeException.class,()->OrderRules.validateSnapshot(quote(List.of(s,s),400)));
        var secondStore=store(valid,200,0,0,0,0,200);
        assertThrows(IllegalArgumentException.class,()->OrderRules.validateSnapshot(quote(List.of(s,secondStore),400)));
        assertThrows(RuntimeException.class,()->OrderRules.validateSnapshot(quote(List.of(),0)));
        for(var units:List.of(List.of(new Quotes.Unit(0,100,0,0,100)),List.of(new Quotes.Unit(0,100,0,0,100),new Quotes.Unit(0,100,0,0,100)),List.of(new Quotes.Unit(0,100,0,0,99),new Quotes.Unit(1,100,0,0,101)))) {
            var invalid=line(100,2,200,0,0,200,units);
            assertThrows(RuntimeException.class,()->OrderRules.validateSnapshot(quote(List.of(store(invalid,200,0,0,0,0,200)),200)));
        }
        var overflow=line(Long.MAX_VALUE,2,Long.MAX_VALUE,0,0,Long.MAX_VALUE,List.of(new Quotes.Unit(0,Long.MAX_VALUE,0,0,Long.MAX_VALUE),new Quotes.Unit(1,Long.MAX_VALUE,0,0,Long.MAX_VALUE)));
        assertThrows(ArithmeticException.class,()->OrderRules.validateSnapshot(quote(List.of(store(overflow,Long.MAX_VALUE,0,0,0,0,Long.MAX_VALUE)),Long.MAX_VALUE)));
    }
    @Test void forbiddenTransitionsAreRejected() {
        var states=List.of("PENDING_PAYMENT","PAID","CLOSED","CANCELED","EXPIRED");
        for(var from:states) { for(var to:states) {
            boolean allowed=from.equals(to)||(from.equals("PENDING_PAYMENT")&&List.of("PAID","CANCELED","EXPIRED").contains(to))||(from.equals("PAID")&&List.of("CLOSED","CANCELED").contains(to));
            if(allowed) { OrderRules.transition(from,to); }
            else { assertThrows(RuntimeException.class,()->OrderRules.transition(from,to),from+" -> "+to); }
        } }
        assertThrows(RuntimeException.class,()->OrderRules.transition("PAID","SHIPPED"));
        assertThrows(RuntimeException.class,()->OrderRules.transition("UNKNOWN","UNKNOWN"));
    }
    private Quotes.Line line(long price,int quantity,long gross,long promotion,long coupon,long net,List<Quotes.Unit> units) {
        return new Quotes.Line(UUID.randomUUID(),UUID.randomUUID(),1,1,"상품",List.of("색상: 파랑"),quantity,price,gross,null,promotion,coupon,net,units);
    }
    private Quotes.Store store(Quotes.Line line,long gross,long promotion,long coupon,long shipping,long shippingDiscount,long total) {
        return new Quotes.Store(UUID.randomUUID(),"store","매대",1,List.of(line),gross,promotion,gross-promotion,null,coupon,shipping,null,shippingDiscount,total);
    }
    private Quotes.Quote quote(List<Quotes.Store> stores,long total) {
        return new Quotes.Quote(UUID.randomUUID(),"p07-v1",Instant.parse("2026-10-27T00:00:00Z"),stores,total,"snapshot");
    }
}
