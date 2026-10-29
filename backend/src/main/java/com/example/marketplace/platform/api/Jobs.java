package com.example.marketplace.platform.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable, at-least-once jobs. Handlers must deduplicate effects using causeKey. */
public interface Jobs {
    record Command(String type, String dedupeKey, String causeKey, String payload, Instant nextRunAt) { }
    record Job(UUID id, Command command, String status, int attempts, Instant nextRunAt, UUID ownerToken, Instant leaseUntil, String lastError) { }
    UUID enqueue(Command command);
    Optional<Job> claim(Set<String> supportedTypes);
    boolean heartbeat(UUID id, UUID ownerToken);
    boolean complete(UUID id, UUID ownerToken);
    boolean fail(UUID id, UUID ownerToken, boolean terminal, String errorCode);
    Optional<Job> find(UUID id);
    List<Job> failed(int limit);
}
