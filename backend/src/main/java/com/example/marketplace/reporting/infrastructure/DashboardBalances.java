package com.example.marketplace.reporting.infrastructure;

import com.example.marketplace.catalog.api.DashboardProducts;
import com.example.marketplace.order.api.DashboardOrders;
import com.example.marketplace.fulfillment.api.DashboardShipments;
import com.example.marketplace.claim.api.DashboardClaims;
import com.example.marketplace.seller.api.DashboardSellers;
import com.example.marketplace.inventory.api.DashboardStocks;
import com.example.marketplace.content.api.DashboardContent;
import com.example.marketplace.operations.api.DashboardOperations;
import com.example.marketplace.settlement.api.DashboardSettlements;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public class DashboardBalances {
    private final DashboardOrders orders;
    private final DashboardProducts catalog;
    private final DashboardShipments fulfillment;
    private final DashboardClaims claim;
    private final DashboardSellers seller;
    private final DashboardStocks inventory;
    private final DashboardContent content;
    private final DashboardOperations operations;
    private final DashboardSettlements settlement;
    public DashboardBalances(DashboardOrders orders,DashboardProducts catalog,
            DashboardShipments fulfillment,
            DashboardClaims claim,
            DashboardSellers seller,
            DashboardStocks inventory,
            DashboardContent content,
            DashboardOperations operations,
            DashboardSettlements settlement) {
        this.orders=orders;this.catalog=catalog;
        this.fulfillment=fulfillment;
        this.claim=claim;
        this.seller=seller;
        this.inventory=inventory;
        this.content=content;
        this.operations=operations;
        this.settlement=settlement;
    }
    void collect(Map<DashboardTradeFacts.Key,BigInteger> values) {
        var stores=new HashMap<UUID,UUID>();
        var products=new HashMap<UUID,UUID>();
        var skus=new HashMap<UUID,UUID>();
        orders.allocations().forEach(a->stores.put(a.sellerOrderId(),a.storeId()));
        catalog.skus().forEach(s->skus.put(s.id(),s.store()));
        for(var p:catalog.products()) {
            products.put(p.id(),p.store());
            stored(values,p.store(),null,"PRODUCTS_REGISTERED",BigInteger.ONE);
            if(p.published()) { stored(values,p.store(),null,"PRODUCTS_PUBLIC",BigInteger.ONE); }
            if(p.hidden()) { stored(values,p.store(),null,"PRODUCTS_HIDDEN",BigInteger.ONE); }
            if("STOPPED".equals(p.state())) { stored(values,p.store(),null,"PRODUCTS_STOPPED",BigInteger.ONE); }
        }
        var parents=Map.of("SELLER_ORDER",stores,"PRODUCT",products,"SKU",skus);
        fulfillment.measure().forEach(f->include(values,parents,f.scope(),f.owner(),f.day(),f.metric(),f.amount()));
        claim.measure().forEach(f->include(values,parents,f.scope(),f.owner(),f.day(),f.metric(),f.amount()));
        seller.measure().forEach(f->include(values,parents,f.scope(),f.owner(),f.day(),f.metric(),f.amount()));
        inventory.measure().forEach(f->include(values,parents,f.scope(),f.owner(),f.day(),f.metric(),f.amount()));
        content.measure().forEach(f->include(values,parents,f.scope(),f.owner(),f.day(),f.metric(),f.amount()));
        operations.measure().forEach(f->include(values,parents,f.scope(),f.owner(),f.day(),f.metric(),f.amount()));
        settlement.measure().forEach(f->include(values,parents,f.scope(),f.owner(),f.day(),f.metric(),f.amount()));
    }
    private static void stored(Map<DashboardTradeFacts.Key,BigInteger> values,UUID store,LocalDate day,String metric,BigInteger amount) {
        java.util.function.BinaryOperator<BigInteger> combine =
                DashboardMetric.REPLACEMENT_OLDEST_SECONDS.name().equals(metric) ? BigInteger::max : BigInteger::add;
        values.merge(new DashboardTradeFacts.Key("STORE",store,day,metric),amount,combine);
        values.merge(new DashboardTradeFacts.Key("GLOBAL",null,day,metric),amount,combine);
    }
    private static void include(Map<DashboardTradeFacts.Key,BigInteger> values,Map<String,HashMap<UUID,UUID>> parents,
                                String scope,UUID id,LocalDate day,String metric,BigInteger amount) {
        DashboardMetric.valueOf(metric);
        if("GLOBAL".equals(scope)||"ASSIGNEE".equals(scope)) {
            values.merge(new DashboardTradeFacts.Key(scope,id,day,metric),amount,BigInteger::add);
        }else {
            UUID store="STORE".equals(scope)?id:Objects.requireNonNull(parents.get(scope).get(id),"DASHBOARD_SCOPE_MISSING");
            stored(values,store,day,metric,amount);
        }
    }
}
