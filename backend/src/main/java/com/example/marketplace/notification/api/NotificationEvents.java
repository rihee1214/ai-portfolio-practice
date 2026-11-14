package com.example.marketplace.notification.api;

import java.util.UUID;

/** The producer supplies the committed business fact and its authorized recipient in the same transaction. */
public interface NotificationEvents {
    enum Kind {
        PAYMENT_BUYER, PAYMENT_SELLER, PURCHASE_CONFIRMED, SHIPPED, EXCHANGE_SHIPPED,
        PRODUCT_ANSWER, CASE_REPLY, MODERATION_ACTION, MODERATION_RESTORED, REPORT_CLOSED
    }

    void record(Kind kind, UUID cause, UUID recipient, UUID target);
}
