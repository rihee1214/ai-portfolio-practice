package com.example.marketplace.promotion.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface Coupons {
    record Terms(String kind,long value,Long cap,long minimum,Instant startsAt,Instant endsAt) { }
    record TemplateInput(String title,String kind,Long value,Long cap,Long minimum,Instant issueStart,Instant issueEnd,Instant startsAt,Instant endsAt,Long totalLimit,String state,Long version) { }
    record Template(UUID id,UUID storeId,String title,Terms terms,Instant issueStart,Instant issueEnd,long totalLimit,long issuedCount,String state,long version,String storeSlug,String storeName) { }
    record Issued(UUID id,UUID templateId,UUID storeId,String title,Terms terms,long templateVersion,String state,long version,String storeSlug,String storeName) { }
    record Page<T>(List<T> items,int page,boolean hasMore) { public Page { items=List.copyOf(items); } }
    record Eligibility(boolean eligible,String reason,long discount,Issued coupon) { }
    Template saveTemplate(UUID actor,UUID id,TemplateInput input);
    Template template(UUID actor,UUID id);
    Page<Template> templates(UUID actor,int page);
    Page<Template> available(UUID member,int page);
    Issued claim(UUID member,UUID templateId);
    Issued own(UUID member,UUID couponId);
    Page<Issued> owned(UUID member,int page);
    Eligibility eligible(UUID member,UUID couponId,UUID storeId,long afterPromotionSubtotal,Instant at);
}
