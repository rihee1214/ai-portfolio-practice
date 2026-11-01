package com.example.marketplace.catalog.api;

import java.util.UUID;

/** Product policy identity is captured with the purchase quote; amounts remain decimal strings over HTTP. */
public record ReturnPolicy(UUID productId,long revision,String source,String amount,String version,String basis,String aggregation,String repeat) {
 public static ReturnPolicy product(UUID product,long revision,String source,long amount) { return new ReturnPolicy(product,revision,source,Long.toString(amount),"P09-RETURN-PRODUCT-MAX-1","ORIGINAL_SHIPMENT","RETURNED_TARGET_CUMULATIVE_MAX","DELTA_ONLY"); }
 public long checkedAmount() {
  if(productId==null||revision<0||amount==null||!amount.matches("0|[1-9][0-9]*")||!"P09-RETURN-PRODUCT-MAX-1".equals(version)||!"ORIGINAL_SHIPMENT".equals(basis)||!"RETURNED_TARGET_CUMULATIVE_MAX".equals(aggregation)||!"DELTA_ONLY".equals(repeat)||!java.util.Set.of("MIGRATED_DEFAULT_3000","PRODUCT_DEFAULT_3000","SELLER_PRODUCT").contains(source==null?"":source)) { throw new IllegalArgumentException("INVALID_RETURN_POLICY"); }
  try { return Long.parseLong(amount); }catch(NumberFormatException invalid) { throw new IllegalArgumentException("INVALID_RETURN_POLICY",invalid); }
 }
}
