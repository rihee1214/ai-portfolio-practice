package com.example.marketplace.reporting.infrastructure;

import com.example.marketplace.order.api.DashboardOrders;
import com.example.marketplace.payment.api.DashboardPayments;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public class DashboardTradeFacts {
    record Key(String scope,UUID owner,LocalDate day,String metric) { }
    private final DashboardOrders orders;
    private final DashboardPayments payments;
    public DashboardTradeFacts(DashboardOrders orders,DashboardPayments payments) {
        this.orders=orders;this.payments=payments;
    }
    private static void add(Map<Key,BigInteger> facts,UUID store,LocalDate day,String metric,BigInteger amount) {
        facts.merge(new Key(store==null?"GLOBAL":"STORE",store,day,metric),amount,BigInteger::add);
    }
    private static void both(Map<Key,BigInteger> facts,UUID store,LocalDate day,String metric,BigInteger amount) {
        add(facts,null,day,metric,amount);
        add(facts,store,day,metric,amount);
    }
    Map<Key,BigInteger> collect() {
        Map<Key,BigInteger> facts=new HashMap<>();
        for(var created:orders.created()) {
            add(facts,created.storeId(),created.day(),"ORDERS_CREATED",BigInteger.valueOf(created.count()));
        }
        Map<UUID,List<DashboardOrders.Allocation>> byOrder=new HashMap<>();
        Map<UUID,UUID> storeBySellerOrder=new HashMap<>();
        for(var allocation:orders.allocations()) {
            byOrder.computeIfAbsent(allocation.orderId(),ignored->new ArrayList<>()).add(allocation);
            storeBySellerOrder.put(allocation.sellerOrderId(),allocation.storeId());
        }
        for(var charge:payments.charges()) {
            var paid=BigInteger.valueOf(charge.amount());
            var allocations=byOrder.get(charge.orderId());
            if(allocations==null || !paid.equals(allocations.stream().map(a->BigInteger.valueOf(a.total())).reduce(BigInteger.ZERO,BigInteger::add))) {
                throw new IllegalStateException("DASHBOARD_CHARGE_ALLOCATION");
            }
            add(facts,null,charge.day(),"PAID_ORDERS",BigInteger.ONE);
            for(var a:allocations) {
                add(facts,a.storeId(),charge.day(),"PAID_ORDERS",BigInteger.ONE);
                both(facts,a.storeId(),charge.day(),"PAID_AMOUNT",BigInteger.valueOf(a.total()));
                both(facts,a.storeId(),charge.day(),"NET_TRADED",BigInteger.valueOf(a.total()));
                both(facts,a.storeId(),charge.day(),"PRODUCT_GROSS",BigInteger.valueOf(a.subtotal()));
                both(facts,a.storeId(),charge.day(),"PRODUCT_DISCOUNT",BigInteger.valueOf(a.productDiscount()));
                both(facts,a.storeId(),charge.day(),"COUPON_DISCOUNT",BigInteger.valueOf(a.couponDiscount()));
                both(facts,a.storeId(),charge.day(),"SHIPPING_CHARGED",BigInteger.valueOf(a.chargedShipping()));
            }
        }
        for(var refund:payments.refunds()) {
            var store=java.util.Objects.requireNonNull(storeBySellerOrder.get(refund.sellerOrderId()));
            var amount=BigInteger.valueOf(refund.amount());
            both(facts,store,refund.day(),"REFUNDED_"+refund.purpose(),amount);
            both(facts,store,refund.day(),"NET_TRADED",amount.negate());
        }
        for(var c:orders.confirmed()) { both(facts,c.storeId(),c.day(),"CONFIRMED_PRODUCT_NET",c.net()); }
        for(var p:payments.unresolved()) {
            String metric="PAYMENT_"+p.state();
            add(facts,null,null,metric,BigInteger.ONE);
            for(var a:byOrder.get(p.orderId())) { add(facts,a.storeId(),null,metric,BigInteger.ONE); }
        }
        return facts;
    }
}
