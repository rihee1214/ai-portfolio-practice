package com.example.marketplace.catalog.web;

import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.Skus;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.example.marketplace.catalog.api.CatalogException;
import tools.jackson.databind.JsonNode;

@RestController @Profile("db")
public class SkuController {
    private final Skus skus;
    public SkuController(Skus skus) { this.skus=skus; }
    @PostMapping(path="/api/seller/products/{id}/skus",consumes="application/json") public ResponseEntity<Skus.Sku> create(Principal p,@PathVariable UUID id,@RequestBody JsonNode body) { return ResponseEntity.status(201).header("Cache-Control","no-store").body(skus.save(UUID.fromString(p.getName()),id,null,input(body))); }
    @PutMapping(path="/api/seller/products/{id}/skus/{skuId}",consumes="application/json") public ResponseEntity<Skus.Sku> update(Principal p,@PathVariable UUID id,@PathVariable UUID skuId,@RequestBody JsonNode body) { return response(skus.save(UUID.fromString(p.getName()),id,skuId,input(body))); }
    @GetMapping("/api/seller/products/{id}/skus") public ResponseEntity<Products.Page<Skus.Sku>> list(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="0") int page) { return response(skus.list(UUID.fromString(p.getName()),id,page)); }
    @GetMapping("/api/products/{slug}/skus") public ResponseEntity<Products.Page<Skus.Sku>> published(@PathVariable String slug,@RequestParam(defaultValue="0") int page) { return response(skus.published(slug,page)); }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().header("Cache-Control","no-store").body(value); }
    private Skus.Input input(JsonNode body) {
        if(body==null||!body.isObject()||!body.path("price").isIntegralNumber()||!body.path("price").canConvertToLong()
                ||!body.path("active").isBoolean()||!body.path("version").isIntegralNumber()||!body.path("version").canConvertToLong()
                ||!body.path("options").isArray()||body.path("options").size()>3) { throw new CatalogException(400); }
        var options=new java.util.ArrayList<Skus.Option>();
        for(var option:body.path("options")) {
            if(!option.isObject()||!option.path("name").isString()||!option.path("value").isString()) { throw new CatalogException(400); }
            options.add(new Skus.Option(option.path("name").asString(),option.path("value").asString()));
        }
        return new Skus.Input(options,body.path("price").asLong(),body.path("active").asBoolean(),body.path("version").asLong());
    }
}
