package com.example.marketplace.guest.api;
import java.util.UUID;
/** Lookup authority only. Caller transaction retains the buyer/session locks through its read. */
public interface GuestAccessSessions {
    UUID requireCurrent(String token);
}
