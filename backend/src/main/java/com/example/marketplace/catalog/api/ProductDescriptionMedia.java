package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ProductDescriptionMedia {
    record Upload(String filename, byte[] bytes) { }

    /** The caller holds the product write lock and transaction through attachment and retirement. */
    List<ProductDescription.Block> reconcile(UUID owner, UUID product, List<ProductDescription.Block> blocks,
            Map<Integer, Upload> uploads);
}
