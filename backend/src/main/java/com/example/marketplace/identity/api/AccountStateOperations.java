package com.example.marketplace.identity.api;

import java.util.UUID;

public interface AccountStateOperations {
    void suspend(UUID actor, UUID target, String reason);
    void restore(UUID actor, UUID target, String reason);
    void withdraw(UUID member, String password, String confirmation);
}
