package com.example.marketplace.catalog.web;

import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.catalog.api.ProductDescription;
import com.example.marketplace.catalog.api.ProductDescription.Block;
import com.example.marketplace.catalog.api.ProductDescription.Run;
import com.example.marketplace.catalog.api.ProductDescriptionMedia.Upload;
import com.example.marketplace.catalog.api.Products;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class ProductInput {
    private ProductInput() { }

    static Products.Input read(JsonNode body, Map<Integer, Upload> uploads) {
        fields(body, Set.of("categoryId","slug","title","description","state","version","returnFee","descriptionBlocks"));
        var version=body.path("version");
        if(!version.isIntegralNumber()||!version.canConvertToLong()) { throw new CatalogException(400); }
        try {
            return new Products.Input(UUID.fromString(text(body,"categoryId")),text(body,"slug"),text(body,"title"),
                    text(body,"description"),text(body,"state"),version.asLong(),body.has("returnFee")?text(body,"returnFee"):null,
                    body.has("descriptionBlocks")?blocks(body.get("descriptionBlocks")):null,uploads);
        } catch(IllegalArgumentException invalid) { throw new CatalogException(400); }
    }

    private static List<Block> blocks(JsonNode value) {
        if(!value.isArray()||value.size()>ProductDescription.MAX_BLOCKS) { throw new CatalogException(400); }
        var blocks=new ArrayList<Block>();
        for(var block:value) {
            String kind=text(block,"kind");
            switch(kind) {
                case "paragraph","heading" -> {
                    fields(block,Set.of("kind","runs"));
                    var content=block.path("runs");
                    if(!content.isArray()||content.size()>ProductDescription.MAX_RUNS) { throw new CatalogException(400); }
                    var runs=new ArrayList<Run>();
                    for(var run:content) {
                        fields(run,Set.of("text","bold","italic"));
                        if(!run.path("bold").isBoolean()||!run.path("italic").isBoolean()) { throw new CatalogException(400); }
                        runs.add(new Run(text(run,"text"),run.get("bold").asBoolean(),run.get("italic").asBoolean()));
                    }
                    blocks.add(new Block(kind,List.copyOf(runs),null,null,null));
                }
                case "image" -> {
                    fields(block,Set.of("kind","alt","assetId","uploadIndex"));
                    var upload=block.path("uploadIndex");
                    if(!upload.isMissingNode()&&(!upload.isIntegralNumber()||!upload.canConvertToInt())) {
                        throw new CatalogException(400);
                    }
                    String asset=block.has("assetId")?text(block,"assetId"):null;
                    if(asset!=null&&!asset.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
                        throw new CatalogException(400);
                    }
                    blocks.add(new Block(kind,null,asset==null?null:UUID.fromString(asset),text(block,"alt"),
                            upload.isMissingNode()?null:upload.asInt()));
                }
                default -> throw new CatalogException(400);
            }
        }
        return List.copyOf(blocks);
    }

    private static void fields(JsonNode object, Set<String> allowed) {
        if(!object.isObject()||object.properties().stream().anyMatch(entry->!allowed.contains(entry.getKey()))) {
            throw new CatalogException(400);
        }
    }

    private static String text(JsonNode object, String key) {
        var value=object.path(key);
        if(!value.isTextual()) { throw new CatalogException(400); }
        return value.asText();
    }
}
