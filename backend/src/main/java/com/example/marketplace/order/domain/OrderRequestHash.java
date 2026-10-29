package com.example.marketplace.order.domain;

import com.example.marketplace.order.api.Orders;
import com.example.marketplace.promotion.api.Quotes;
import java.util.List;
import java.util.UUID;

/** No password, proof, recovery secret or session credential is accepted by this function. */
public final class OrderRequestHash {
    private OrderRequestHash() { }
    public static String calculate(List<Quotes.LineRequest> lines,List<Quotes.CouponSelection> coupons,String fingerprint,
            Orders.Contact contact,UUID attempt,Integer revision) {
        var pieces=new java.util.ArrayList<String>();
        lines.stream().sorted(java.util.Comparator.comparing(l->l.skuId().toString())).forEach(l->pieces.add(l.skuId()+":"+l.quantity()));
        pieces.add("COUPONS");coupons.stream().sorted(java.util.Comparator.comparing(c->c.storeId().toString())).forEach(c->pieces.add(c.storeId()+":"+c.couponId()));
        pieces.add(fingerprint);pieces.addAll(List.of(text(contact.buyerName()),phone(contact.buyerPhone()),text(contact.recipientName()),phone(contact.recipientPhone()),text(contact.postalCode()),text(contact.addressLine1()),text(contact.addressLine2())));
        pieces.add(String.valueOf(attempt));pieces.add(String.valueOf(revision));
        String canonical=pieces.stream().map(s->s.length()+":"+s).collect(java.util.stream.Collectors.joining());
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String text(String value) { return java.text.Normalizer.normalize(value.strip(),java.text.Normalizer.Form.NFC); }
    private static String phone(String value) { return com.example.marketplace.phone.api.PhoneVerifications.normalize(value); }
}
