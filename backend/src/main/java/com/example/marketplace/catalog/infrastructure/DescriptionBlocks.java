package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.catalog.api.ProductDescription;
import com.example.marketplace.catalog.api.ProductDescription.Block;
import com.example.marketplace.catalog.api.ProductDescriptionMedia.Upload;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

final class DescriptionBlocks {
    private static final TypeReference<List<Block>> TYPE = new TypeReference<>() { };

    private DescriptionBlocks() { }

    static List<Block> read(ObjectMapper mapper, ResultSet row) throws SQLException {
        int column;
        try { column=row.findColumn("description_blocks"); }
        catch(SQLException absent) {
            // Historical migration probes run the current plain catalog path on an older schema.
            if("42703".equals(absent.getSQLState())) { return null; }
            throw absent;
        }
        String stored=row.getString(column);
        return stored == null ? null : mapper.readValue(stored, TYPE);
    }

    static String validate(List<Block> blocks, Map<Integer, Upload> uploads) {
        if (blocks.size() > ProductDescription.MAX_BLOCKS || uploads.size() > ProductDescription.MAX_IMAGES) {
            throw new CatalogException(400);
        }
        int images = 0;
        int runs = 0;
        int bytes = 0;
        var existing = new HashSet<UUID>();
        var incoming = new HashSet<Integer>();
        for (Block block : blocks) {
            if (block == null || block.kind() == null) { throw new CatalogException(400); }
            switch (block.kind()) {
                case "image" -> {
                    if (++images > ProductDescription.MAX_IMAGES || block.runs() != null
                            || (block.assetId() == null) == (block.uploadIndex() == null)
                            || !CatalogValues.text(block.alt(), 120, true).equals(block.alt())) {
                        throw new CatalogException(400);
                    }
                    if (block.assetId() != null && !existing.add(block.assetId())) {
                        throw new CatalogException(400);
                    }
                    if (block.uploadIndex() != null) {
                        Upload upload = uploads.get(block.uploadIndex());
                        if (upload == null || upload.bytes() == null || !incoming.add(block.uploadIndex())
                                || block.uploadIndex() < 0 || block.uploadIndex() >= ProductDescription.MAX_IMAGES
                                || upload.bytes().length > ProductDescription.MAX_IMAGE_BYTES) {
                            throw new CatalogException(400);
                        }
                        bytes += upload.bytes().length;
                    }
                }
                case "paragraph", "heading" -> {
                    if (block.assetId() != null || block.uploadIndex() != null || block.alt() != null || block.runs() == null
                            || block.runs().size() > ProductDescription.MAX_RUNS - runs) {
                        throw new CatalogException(400);
                    }
                    runs += block.runs().size();
                    for (var run : block.runs()) {
                        if (run == null || run.text() == null || !run.text().equals(run.text().replace('\r', '\n'))
                                || run.text().codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'
                                || c >= 0xD800 && c <= 0xDFFF)) {
                            throw new CatalogException(400);
                        }
                    }
                }
                default -> throw new CatalogException(400);
            }
        }
        if (bytes > ProductDescription.MAX_IMAGE_BYTES || incoming.size() != uploads.size()) {
            throw new CatalogException(400);
        }
        String plain = blocks.stream().filter(b -> b.runs() != null)
                .map(b -> b.runs().stream().map(ProductDescription.Run::text).collect(Collectors.joining()))
                .collect(Collectors.joining("\n"));
        if (plain.codePointCount(0, plain.length()) > 4000) { throw new CatalogException(400); }
        return CatalogValues.description(plain);
    }
}
