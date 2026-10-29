package com.example.marketplace.platform.api;

import java.util.UUID;

/** Joins the caller transaction, atomically persisting the event and its delivery job. */
public interface Outbox {
    record Event(UUID id, Jobs.Command command) { }
    UUID append(Event event);
}
