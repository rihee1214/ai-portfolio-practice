package com.example.marketplace.promotion.web;
import com.example.marketplace.promotion.api.Quotes;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public class QuoteController {
    private final Quotes api;
    public QuoteController(Quotes api) { this.api=api; }
    record Input(List<Quotes.LineRequest> lines,List<Quotes.CouponSelection> coupons) { }
    @PostMapping("/api/quotes") public ResponseEntity<?> quote(Principal p,@RequestBody Input input) {
        var q=api.calculate(p==null?null:UUID.fromString(p.getName()),input.lines(),input.coupons());return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("policyVersion",q.policyVersion(),"evaluatedAt",q.evaluatedAt(),"fingerprint",q.fingerprint(),"total",Long.toString(q.total()),"stores",q.stores().stream().map(this::store).toList()));
    }
    private Map<String,Object> store(Quotes.Store s) {
        var m=new java.util.LinkedHashMap<String,Object>();m.put("storeId",s.storeId());m.put("slug",s.slug());m.put("name",s.name());m.put("version",s.version());m.put("lines",s.lines().stream().map(this::line).toList());m.put("subtotal",Long.toString(s.subtotal()));m.put("productDiscount",Long.toString(s.productDiscount()));m.put("afterPromotion",Long.toString(s.afterPromotion()));m.put("coupon",s.coupon()==null?null:Map.of("id",s.coupon().id(),"version",s.coupon().version(),"templateVersion",s.coupon().templateVersion(),"title",s.coupon().title()));m.put("couponDiscount",Long.toString(s.couponDiscount()));m.put("shippingBase",Long.toString(s.shippingBase()));m.put("shippingPromotion",benefit(s.shippingPromotion()));m.put("shippingDiscount",Long.toString(s.shippingDiscount()));m.put("total",Long.toString(s.total()));return m;
    }
    private Map<String,Object> line(Quotes.Line l) {
        var m=new java.util.LinkedHashMap<String,Object>();m.put("skuId",l.skuId());m.put("productId",l.productId());m.put("productVersion",l.productVersion());m.put("skuVersion",l.skuVersion());m.put("title",l.title());m.put("options",l.options());m.put("quantity",l.quantity());m.put("unitPrice",Long.toString(l.unitPrice()));m.put("gross",Long.toString(l.gross()));m.put("promotion",benefit(l.promotion()));m.put("promotionDiscount",Long.toString(l.promotionDiscount()));m.put("couponDiscount",Long.toString(l.couponDiscount()));m.put("net",Long.toString(l.net()));m.put("returnPolicy",l.returnPolicy());m.put("units",l.units().stream().map(u->Map.of("ordinal",u.ordinal(),"gross",Long.toString(u.gross()),"promotionDiscount",Long.toString(u.promotionDiscount()),"couponDiscount",Long.toString(u.couponDiscount()),"net",Long.toString(u.net()))).toList());return m;
    }
    private Object benefit(Quotes.Benefit b) { return b==null?null:Map.of("id",b.id(),"version",b.version(),"title",b.title(),"selectionReason","MAX_DISCOUNT_ID_TIE"); }
}
