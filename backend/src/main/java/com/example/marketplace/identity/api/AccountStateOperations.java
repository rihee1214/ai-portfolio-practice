package com.example.marketplace.identity.api;

import java.util.UUID;

public interface AccountStateOperations {
    default void suspend(UUID actor,UUID target,String reason){suspend(actor,target,reason,null);}
    default void restore(UUID actor,UUID target,String reason){restore(actor,target,reason,null);}
    void suspend(UUID actor,UUID target,String reason,Long expectedVersion);
    void restore(UUID actor,UUID target,String reason,Long expectedVersion);
    void withdraw(UUID member, String password, String confirmation);
}
