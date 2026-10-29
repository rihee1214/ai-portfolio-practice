package com.example.marketplace.order.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface OrderQueries {
    record Authority(String browser,UUID member,Long version,String guestToken) { }
    record Owned(UUID id,String state,String amount,String mode) { }
    record Summary(String orderNumber,String state,String total,String mode,Instant createdAt) { }
    record Page(List<Summary> orders,String nextCursor) { public Page { orders=List.copyOf(orders); } }
    record Unit(int ordinal,String gross,String promotionDiscount,String couponDiscount,String net) { }
    record Item(UUID id,UUID skuId,String title,List<String> options,int quantity,String unitPrice,String gross,String promotionTitle,String promotionDiscount,String couponDiscount,String net,List<Unit> units) { public Item { options=List.copyOf(options);units=List.copyOf(units); } }
    record Store(UUID sellerOrderId,UUID storeId,String name,String subtotal,String productDiscount,String couponTitle,String couponDiscount,String shippingBase,String shippingPromotionTitle,String shippingDiscount,String total,List<Item> items) { public Store { items=List.copyOf(items); } }
    record Detail(String orderNumber,String state,String total,String mode,Instant createdAt,String policyVersion,Orders.Contact contact,List<Store> stores) { public Detail { stores=List.copyOf(stores); } }
    record Recipient(String name,String phone,String postalCode,String addressLine1,String addressLine2) { }
    record SellerOrder(UUID sellerOrderId,String orderNumber,String orderState,String total,Recipient recipient,List<Item> items) { public SellerOrder { items=List.copyOf(items); } }
    record SellerSummary(UUID sellerOrderId,String orderNumber,String orderState,String total,Instant createdAt) { }
    record SellerPage(List<SellerSummary> orders,String nextCursor) { public SellerPage { orders=List.copyOf(orders); } }
    SellerPage sellerOrders(Authority authority,int size,String cursor);
    Page memberOrders(Authority authority,int size,String cursor);
    Page guestOrders(String token,int size,String cursor);
    Detail detail(String number,Authority authority);
    /** Caller transaction must hold returned order lock while reading its payment projection. */
    Owned requireOwned(String number,Authority authority);
    SellerOrder sellerOrder(UUID id,Authority authority);
    /** Later fulfillment may consume only a PAID own-store projection; no shipment is created here. */
    SellerOrder paidSellerOrder(UUID id,Authority authority);
    /** Seller command authority; caller transaction retains exclusive order lock through shipment writes. */
    SellerOrder writePaidSellerOrder(UUID id,Authority authority);
}
