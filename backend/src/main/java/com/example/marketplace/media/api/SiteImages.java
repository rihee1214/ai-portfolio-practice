package com.example.marketplace.media.api;

import java.util.UUID;

/** The caller owns the publication lock, current visibility and exact image binding. */
public interface SiteImages {
    UUID save(UUID member,String filename,byte[] bytes);
    byte[] read(UUID asset);
}
