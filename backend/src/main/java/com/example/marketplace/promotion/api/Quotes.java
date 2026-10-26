package com.example.marketplace.promotion.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface Quotes {
    record LineRequest(UUID skuId,Integer quantity) { }
    record CouponSelection(UUID storeId,UUID couponId) { }
    record Benefit(UUID id,long version,String title,String kind,long value,Long cap) { }
    record CouponUse(UUID id,long version,long templateVersion,String title,Coupons.Terms terms) { }
    record Unit(int ordinal,long gross,long promotionDiscount,long couponDiscount,long net) { }
    record Line(UUID skuId,UUID productId,long productVersion,long skuVersion,String title,List<String> options,int quantity,long unitPrice,long gross,Benefit promotion,long promotionDiscount,long couponDiscount,long net,List<Unit> units) { public Line { options=List.copyOf(options);units=List.copyOf(units); } }
    record Store(UUID storeId,String slug,String name,long version,List<Line> lines,long subtotal,long productDiscount,long afterPromotion,CouponUse coupon,long couponDiscount,long shippingBase,Benefit shippingPromotion,long shippingDiscount,long total) { public Store { lines=List.copyOf(lines); } }
    record Quote(UUID memberId,String policyVersion,Instant evaluatedAt,List<Store> stores,long total,String fingerprint) { public Quote { stores=List.copyOf(stores); } }
    Quote calculate(UUID memberOrNull,List<LineRequest> lines,List<CouponSelection> coupons);
}
