package com.example.marketplace.order.domain;

import com.example.marketplace.promotion.api.Quotes;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class OrderRules {
    private OrderRules() { }
    public static long validateSnapshot(Quotes.Quote quote) {
        require(quote!=null && quote.policyVersion()!=null && !quote.policyVersion().isBlank()
                && quote.evaluatedAt()!=null && quote.fingerprint()!=null && !quote.fingerprint().isBlank());
        require(!quote.stores().isEmpty() && quote.stores().size()<=20);
        var stores=new HashSet<UUID>(); var skus=new HashSet<UUID>(); long total=0;
        for(var store:quote.stores()) {
            require(store.storeId()!=null && stores.add(store.storeId()) && !store.lines().isEmpty());
            long gross=0, promotion=0, coupon=0, net=0;
            for(var line:store.lines()) {
                require(line.skuId()!=null && skus.add(line.skuId()) && skus.size()<=20 && line.productId()!=null
                        && line.title()!=null && !line.title().isBlank() && line.quantity()>=1 && line.quantity()<=99);
                nonnegative(line.unitPrice(),line.gross(),line.promotionDiscount(),line.couponDiscount(),line.net());
                require(Math.multiplyExact(line.unitPrice(),line.quantity())==line.gross());
                require(line.units().size()==line.quantity());
                long unitGross=0,unitPromotion=0,unitCoupon=0,unitNet=0;
                for(int i=0;i<line.units().size();i++) {
                    var unit=line.units().get(i);
                    nonnegative(unit.gross(),unit.promotionDiscount(),unit.couponDiscount(),unit.net());
                    require(unit.ordinal()==i && unit.gross()==line.unitPrice());
                    require(Math.subtractExact(Math.subtractExact(unit.gross(),unit.promotionDiscount()),unit.couponDiscount())==unit.net());
                    unitGross=Math.addExact(unitGross,unit.gross());unitPromotion=Math.addExact(unitPromotion,unit.promotionDiscount());
                    unitCoupon=Math.addExact(unitCoupon,unit.couponDiscount());unitNet=Math.addExact(unitNet,unit.net());
                }
                require(unitGross==line.gross() && unitPromotion==line.promotionDiscount()
                        && unitCoupon==line.couponDiscount() && unitNet==line.net());
                gross=Math.addExact(gross,line.gross());promotion=Math.addExact(promotion,line.promotionDiscount());
                coupon=Math.addExact(coupon,line.couponDiscount());net=Math.addExact(net,line.net());
            }
            nonnegative(store.subtotal(),store.productDiscount(),store.afterPromotion(),store.couponDiscount(),
                    store.shippingBase(),store.shippingDiscount(),store.total());
            require(gross==store.subtotal() && promotion==store.productDiscount() && coupon==store.couponDiscount()
                    && Math.subtractExact(gross,promotion)==store.afterPromotion() && store.shippingDiscount()<=store.shippingBase());
            require(Math.addExact(net,store.shippingBase()-store.shippingDiscount())==store.total());
            total=Math.addExact(total,store.total());
        }
        require(total==quote.total()); return total;
    }
    public static void transition(String from, String to) {
        var states=List.of("PENDING_PAYMENT","PAID","CLOSED","CANCELED","EXPIRED");
        require(from!=null && to!=null && states.contains(from) && states.contains(to));
        require(from.equals(to)||(from.equals("PENDING_PAYMENT")&&List.of("PAID","CANCELED","EXPIRED").contains(to))
                ||(from.equals("PAID")&&List.of("CLOSED","CANCELED").contains(to)));
    }
    private static void nonnegative(long... values) { for(long value:values) { require(value>=0); } }
    private static void require(boolean condition) { if(!condition) { throw new IllegalArgumentException("INVALID_ORDER_SNAPSHOT_OR_STATE"); } }
}
