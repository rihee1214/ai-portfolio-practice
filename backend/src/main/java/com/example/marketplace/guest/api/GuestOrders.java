package com.example.marketplace.guest.api;

import java.util.UUID;

/** Trusted order transaction port; passwords are prepared outside the order transaction. */
public interface GuestOrders {
    record Prepared(UUID attemptId,int revision,String name,String phone) { }
    Prepared lock(String browser,UUID attempt,int revision,String accessToken);
    UUID consume(String browser,Prepared prepared,String accessToken);
    void reference(UUID buyer,String orderNumber);
    void authorizeRetry(String browser,UUID attempt,UUID buyer,String accessToken);
}
