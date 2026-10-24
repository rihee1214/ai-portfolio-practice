package com.example.marketplace.seller.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SellerApplications {
    record Input(String displayName, String description) { }
    record Event(UUID actorId, String status, String reason, Instant occurredAt) { }
    record Application(UUID id, UUID applicantId, String displayName, String description, String status, Instant createdAt, List<Event> events) { }
    record Page(List<Application> items, int page, boolean hasMore) { }
    Application submit(UUID applicant, Input input);
    Page own(UUID applicant, int page);
    Page reviewQueue(UUID operator, int page);
    Application decide(UUID operator, UUID application, String decision, String reason);
}
