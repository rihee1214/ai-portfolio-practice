package com.example.marketplace.promotion.domain;
import com.example.marketplace.promotion.api.Quotes;
import com.example.marketplace.promotion.api.PromotionRejected;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
public final class QuoteCalculator {
    private QuoteCalculator() { }
    public record Weight(UUID id,long amount) { }
    public static long shipping(long afterPromotion) { if(afterPromotion<0) { throw new PromotionRejected(400); }return afterPromotion>=50000?0:3000; }
    public static long storeTotal(long afterPromotion,long coupon,long shippingDiscount) {
        long base=shipping(afterPromotion);if(coupon<0||coupon>afterPromotion||shippingDiscount<0||shippingDiscount>base) { throw new PromotionRejected(400); }
        return Math.addExact(afterPromotion-coupon,base-shippingDiscount);
    }
    public static Map<UUID,Long> allocate(long discount,List<Weight> weights) {
        if(discount<0||weights==null||weights.isEmpty()||weights.stream().anyMatch(w->w==null||w.id()==null||w.amount()<0)||weights.stream().map(Weight::id).distinct().count()!=weights.size()) { throw new PromotionRejected(400); }
        var ordered=weights.stream().sorted(java.util.Comparator.comparing(w->w.id().toString())).toList();long total=0;for(var w:ordered) { total=Math.addExact(total,w.amount()); }
        if(discount>total) { throw new PromotionRejected(400); }var result=new java.util.LinkedHashMap<UUID,Long>();long assigned=0;
        for(var w:ordered) { long amount=total==0?0:BigInteger.valueOf(discount).multiply(BigInteger.valueOf(w.amount())).divide(BigInteger.valueOf(total)).longValueExact();result.put(w.id(),amount);assigned=Math.addExact(assigned,amount); }
        long remainder=discount-assigned;for(var w:ordered) { if(remainder==0) { break; }if(result.get(w.id())<w.amount()) { result.put(w.id(),result.get(w.id())+1);remainder--; } }
        if(remainder!=0) { throw new IllegalStateException("ALLOCATION_REMAINDER"); }return java.util.Collections.unmodifiableMap(result);
    }
    public static List<Quotes.Unit> units(long unitPrice,long unitPromotion,long coupon,int quantity) {
        if(quantity<1||quantity>99||unitPrice<0||unitPromotion<0||unitPromotion>unitPrice||coupon<0) { throw new PromotionRejected(400); }
        Math.multiplyExact(unitPrice,quantity);long after=Math.multiplyExact(unitPrice-unitPromotion,quantity);if(coupon>after) { throw new PromotionRejected(400); }
        var rows=new java.util.ArrayList<Quotes.Unit>();for(int n=0;n<quantity;n++) { long share=coupon/quantity+(n<coupon%quantity?1:0);rows.add(new Quotes.Unit(n,unitPrice,unitPromotion,share,unitPrice-unitPromotion-share)); }return List.copyOf(rows);
    }
    // Length-delimited fields avoid delimiter ambiguity; evaluatedAt is deliberately excluded.
    public static String fingerprint(Quotes.Quote quote) {
        var b=new StringBuilder();add(b,quote.memberId());add(b,quote.policyVersion());add(b,quote.total());add(b,quote.stores().size());
        for(var s:quote.stores()) {
            add(b,s.storeId());add(b,s.slug());add(b,s.name());add(b,s.version());add(b,s.subtotal());add(b,s.productDiscount());add(b,s.afterPromotion());add(b,s.couponDiscount());add(b,s.shippingBase());add(b,s.shippingDiscount());add(b,s.total());benefit(b,s.shippingPromotion());
            if(s.coupon()==null) { add(b,null); }else{var c=s.coupon();add(b,c.id());add(b,c.version());add(b,c.templateVersion());add(b,c.title());var t=c.terms();add(b,t.kind());add(b,t.value());add(b,t.cap());add(b,t.minimum());add(b,t.startsAt());add(b,t.endsAt());}
            add(b,s.lines().size());for(var l:s.lines()) { add(b,l.skuId());add(b,l.productId());add(b,l.productVersion());add(b,l.skuVersion());add(b,l.title());add(b,l.options().size());l.options().forEach(o->add(b,o));add(b,l.quantity());add(b,l.unitPrice());add(b,l.gross());benefit(b,l.promotion());add(b,l.promotionDiscount());add(b,l.couponDiscount());add(b,l.net());add(b,l.units().size());for(var u:l.units()){add(b,u.ordinal());add(b,u.gross());add(b,u.promotionDiscount());add(b,u.couponDiscount());add(b,u.net());} }
        }
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void benefit(StringBuilder b,Quotes.Benefit p) { if(p==null){add(b,null);}else{add(b,p.id());add(b,p.version());add(b,p.title());add(b,p.kind());add(b,p.value());add(b,p.cap());} }
    private static void add(StringBuilder b,Object value) { if(value==null){b.append("-:");}else{String s=value.toString();b.append(s.length()).append(':').append(s);} }
}
