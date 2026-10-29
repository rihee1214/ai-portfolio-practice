package com.example.marketplace.identity.api;
import java.util.UUID;
/** Current readable member identity, independent of purchase capability. Caller transaction required. */
public interface HistorySessions {
    boolean lockReadable(String sessionId,UUID memberId,Long authVersion);
}
