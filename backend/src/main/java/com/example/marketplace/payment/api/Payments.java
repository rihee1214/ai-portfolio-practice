package com.example.marketplace.payment.api;
import java.util.UUID;
public interface Payments {
 record Authority(String browser,UUID member,Long version,String guestToken) { }
 record View(String orderNumber,String orderState,String paymentState,String compensation,boolean reviewRequired,String amount,String mode,String providerOrderId) { }
 View approve(String order,Authority authority);
 View get(String order,Authority authority);
 View reconcile(String order,Authority authority,String reason);
}
