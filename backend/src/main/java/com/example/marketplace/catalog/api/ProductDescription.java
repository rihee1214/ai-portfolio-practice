package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.UUID;

public final class ProductDescription {
    public static final int MAX_BLOCKS = 100;
    public static final int MAX_RUNS = 256;
    public static final int MAX_IMAGES = 5;
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    private ProductDescription() { }

    public record Run(String text, boolean bold, boolean italic) { }

    public record Block(String kind, List<Run> runs, UUID assetId, String alt, Integer uploadIndex) {
        public Block savedImage(UUID id) {
            return new Block("image", null, id, alt, null);
        }
    }
}
