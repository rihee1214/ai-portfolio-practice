package com.example.marketplace.media.api;

import java.util.List;
import java.util.UUID;

/** The caller retains the authorized message lock through the transaction. */
public interface MessageImages {
    enum Kind { PRODUCT_QUESTION, PRODUCT_ANSWER, CASE_MESSAGE }
    record Target(Kind kind, UUID message) { }
    record Attachment(int slot, UUID asset) { }
    record View(long version, List<Attachment> images) { }
    record Receipt(long version, UUID asset) { }
    View forMessage(Target target);
    Receipt changeMessageImage(Target target, UUID member, UUID guest, UUID key, long version,
            int slot, String filename, byte[] bytes);
    byte[] readMessageImage(Target target, UUID asset);
}
