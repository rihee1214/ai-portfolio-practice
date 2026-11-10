package com.example.marketplace.operations.api;

import java.util.List;
import java.util.UUID;

public interface SupportAssignments {
    record Actor(UUID member, String session, Long version) { }
    record Entry(UUID targetId, String kind, boolean assigned, long version) { }
    record Change(UUID key, long expectedVersion, UUID operator) { }
    void register(UUID target, String kind);
    boolean assigned(UUID target, Actor actor);
    List<Entry> queue(Actor actor);
    Entry assign(UUID target, Actor actor, Change change);
}
