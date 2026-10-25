package com.example.marketplace.media.api;

import java.util.List;
import java.util.UUID;

public interface Media {
    record Slot(int slot,UUID assetId,String alt,long version,int width,int height) { }
    List<Slot> list(UUID owner,String kind,UUID target);
    List<Slot> published(String kind,String slug);
    Slot upload(UUID owner,String kind,UUID target,int slot,long version,String alt,String filename,byte[] bytes);
    Slot delete(UUID owner,String kind,UUID target,int slot,long version);
    byte[] content(UUID viewer,UUID asset);
}
