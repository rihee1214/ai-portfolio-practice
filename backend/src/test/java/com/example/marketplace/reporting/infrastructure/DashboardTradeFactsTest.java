package com.example.marketplace.reporting.infrastructure;

import com.example.marketplace.order.api.DashboardOrders;
import com.example.marketplace.payment.api.DashboardPayments;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardTradeFactsTest {
    @Test void splitOrderCountsOnceGloballyAndRefundOnlyPeriodIsNegativeForItsOwnStore() {
        var order=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();
        var sa=UUID.randomUUID();var sb=UUID.randomUUID();var day=LocalDate.of(2024,2,29);var refundDay=day.plusDays(1);
        var orders=new DashboardOrders() {
            public List<Created> created() { return List.of(); }
            public List<Confirmed> confirmed() { return List.of(); }
            public List<Allocation> allocations() {
                return List.of(new Allocation(order,sa,a,10000,100,200,300,10000),new Allocation(order,sb,b,1000,0,0,0,1000));
            }
        };
        var payments=new DashboardPayments() {
            public List<Charge> charges() { return List.of(new Charge(order,day,11000)); }
            public List<Refund> refunds() { return List.of(new Refund(sa,"ITEM",refundDay,9700),new Refund(sa,"SHIPPING",refundDay,300)); }
            public List<Pending> unresolved() { return List.of(); }
        };
        var facts=new DashboardTradeFacts(orders,payments).collect();
        assertEquals(BigInteger.ONE,facts.get(new DashboardTradeFacts.Key("GLOBAL",null,day,"PAID_ORDERS")));
        for(var store:List.of(a,b)) { assertEquals(BigInteger.ONE,facts.get(new DashboardTradeFacts.Key("STORE",store,day,"PAID_ORDERS"))); }
        assertEquals(BigInteger.valueOf(11000),facts.get(new DashboardTradeFacts.Key("GLOBAL",null,day,"NET_TRADED")));
        assertEquals(BigInteger.valueOf(-10000),facts.get(new DashboardTradeFacts.Key("STORE",a,refundDay,"NET_TRADED")));
        assertEquals(BigInteger.valueOf(-10000),facts.get(new DashboardTradeFacts.Key("GLOBAL",null,refundDay,"NET_TRADED")));
        org.junit.jupiter.api.Assertions.assertNull(facts.get(new DashboardTradeFacts.Key("STORE",b,refundDay,"NET_TRADED")));
    }
}
