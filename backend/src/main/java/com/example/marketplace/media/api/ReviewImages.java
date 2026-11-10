package com.example.marketplace.media.api;

import java.util.List;
import java.util.UUID;

/** For the content module after it authorizes and locks the review. */
public interface ReviewImages {
    record Image(int slot, UUID id) { }
    List<Image> list(UUID review, long edition);
    UUID replace(UUID member, UUID review, long edition, int slot, String filename, byte[] bytes);
    void remove(UUID review, long edition, int slot);
    void retireAll(UUID review, long edition);
    byte[] read(UUID review, long edition, UUID asset);
}
