package com.example.marketplace.promotion.infrastructure;
import com.example.marketplace.promotion.api.Quotes;
import com.example.marketplace.promotion.api.Coupons;
import com.example.marketplace.promotion.api.Promotions;
import com.example.marketplace.promotion.api.PromotionRejected;
import com.example.marketplace.promotion.domain.PromotionRules;
import com.example.marketplace.promotion.domain.QuoteCalculator;
import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccessArea;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
@Service @Profile("db")
public final class JdbcQuotes implements Quotes {
    private final Browse browse;
    private final Promotions promotions;
    private final Coupons coupons;
    private final AccessPolicy access;
    private final TransactionTemplate tx;
    public JdbcQuotes(Browse browse,Promotions promotions,Coupons coupons,AccessPolicy access,PlatformTransactionManager manager) {
        this.browse=browse;this.promotions=promotions;this.coupons=coupons;this.access=access;tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private record Source(Browse.QuoteOffer offer,int quantity) { }
    @Override public Quote calculate(UUID member,List<LineRequest> requests,List<CouponSelection> selections) {
        if(requests==null||requests.isEmpty()||requests.size()>20||requests.stream().anyMatch(r->r==null||r.skuId()==null||r.quantity()==null||r.quantity()<1||r.quantity()>99)||requests.stream().map(LineRequest::skuId).distinct().count()!=requests.size()
            ||selections==null||selections.size()>20||selections.stream().anyMatch(s->s==null||s.storeId()==null||s.couponId()==null)||selections.stream().map(CouponSelection::storeId).distinct().count()!=selections.size()||selections.stream().map(CouponSelection::couponId).distinct().count()!=selections.size()) { throw new PromotionRejected(400); }
        if(member==null&&!selections.isEmpty()) { throw new PromotionRejected(401); }
        try { return tx.execute(status->calculateSnapshot(member,requests,selections)); }
        catch(ArithmeticException ex) { throw new PromotionRejected(400); }
    }
    private Quote calculateSnapshot(UUID member,List<LineRequest> requests,List<CouponSelection> selections) {
        if(member!=null) { access.require(member,AccessArea.BUYER); }Instant at=Instant.now();
        var groups=new java.util.TreeMap<UUID,List<Source>>(java.util.Comparator.comparing(UUID::toString));
        for(var r:requests.stream().sorted(java.util.Comparator.comparing(x->x.skuId().toString())).toList()) {
            var o=browse.quoteOffer(r.skuId());if(o==null||o.available()<r.quantity()) { throw new PromotionRejected(409); }
            groups.computeIfAbsent(o.storeId(),key->new java.util.ArrayList<>()).add(new Source(o,r.quantity()));
        }
        if(selections.stream().anyMatch(s->!groups.containsKey(s.storeId()))) { throw new PromotionRejected(400); }
        Map<UUID,UUID> chosen=selections.stream().collect(java.util.stream.Collectors.toMap(CouponSelection::storeId,CouponSelection::couponId));var stores=new java.util.ArrayList<Store>();long total=0;
        for(var group:groups.entrySet()) {
            UUID storeId=group.getKey();var source=group.getValue();var display=source.getFirst().offer();
            var candidates=promotions.candidates(storeId,source.stream().map(s->s.offer().productId()).distinct().toList(),at);var lines=new java.util.ArrayList<Line>();long subtotal=0,productDiscount=0;
            for(var item:source) {
                var o=item.offer();var promotion=PromotionRules.best(candidates.stream().filter(p->p.scope().equals("PRODUCT")&&p.productIds().contains(o.productId())).toList(),o.price());
                long unitDiscount=promotion==null?0:PromotionRules.discount(o.price(),promotion.kind(),promotion.value(),promotion.cap());long gross=Math.multiplyExact(o.price(),item.quantity()),discount=Math.multiplyExact(unitDiscount,item.quantity());
                subtotal=Math.addExact(subtotal,gross);productDiscount=Math.addExact(productDiscount,discount);
                lines.add(new Line(o.skuId(),o.productId(),o.productVersion(),o.skuVersion(),o.title(),o.options().stream().map(x->x.name()+": "+x.value()).toList(),item.quantity(),o.price(),gross,benefit(promotion),discount,0,gross-discount,List.of()));
            }
            long after=subtotal-productDiscount,couponDiscount=0;CouponUse use=null;
            if(chosen.containsKey(storeId)) {
                var eligible=coupons.eligible(member,chosen.get(storeId),storeId,after,at);if(!eligible.eligible()) { throw new PromotionRejected(409); }couponDiscount=eligible.discount();var c=eligible.coupon();use=new CouponUse(c.id(),c.version(),c.templateVersion(),c.title(),c.terms());
            }
            var shares=QuoteCalculator.allocate(couponDiscount,lines.stream().map(l->new QuoteCalculator.Weight(l.skuId(),l.net())).toList());var finalLines=new java.util.ArrayList<Line>();
            for(var l:lines) { long share=shares.get(l.skuId());finalLines.add(new Line(l.skuId(),l.productId(),l.productVersion(),l.skuVersion(),l.title(),l.options(),l.quantity(),l.unitPrice(),l.gross(),l.promotion(),l.promotionDiscount(),share,l.net()-share,QuoteCalculator.units(l.unitPrice(),l.promotionDiscount()/l.quantity(),share,l.quantity()))); }
            long shipping=QuoteCalculator.shipping(after);var shippingPromotion=PromotionRules.best(candidates.stream().filter(p->p.scope().equals("SHIPPING")).toList(),shipping);long shippingDiscount=shippingPromotion==null?0:PromotionRules.discount(shipping,shippingPromotion.kind(),shippingPromotion.value(),shippingPromotion.cap());long storeTotal=QuoteCalculator.storeTotal(after,couponDiscount,shippingDiscount);total=Math.addExact(total,storeTotal);
            stores.add(new Store(storeId,display.storeSlug(),display.storeName(),display.storeVersion(),finalLines,subtotal,productDiscount,after,use,couponDiscount,shipping,benefit(shippingPromotion),shippingDiscount,storeTotal));
        }
        var quote=new Quote(member,"P07-C-1",at,stores,total,"");return new Quote(member,quote.policyVersion(),at,stores,total,QuoteCalculator.fingerprint(quote));
    }
    private Benefit benefit(Promotions.Promotion p) { return p==null?null:new Benefit(p.id(),p.version(),p.title(),p.kind(),p.value(),p.cap()); }
}
