package com.example.marketplace.notification.api;

import java.util.UUID;

/** Implemented by the target's owner using the same policy as its detail page. */
public interface NotificationAccess {
    record Reader(UUID member,String session,Long version) { }
    boolean handles(NotificationEvents.Kind kind);
    /** A local route after checking the target's current policy; null hides the entire entry. */
    String destination(NotificationEvents.Kind kind,UUID target,Reader reader);
}
