package com.example.marketplace.catalog.web;

import com.example.marketplace.catalog.api.Products;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public class ProductController {
    private final Products products;
    public ProductController(Products products) { this.products=products; }
    @GetMapping("/api/products") public ResponseEntity<Products.Page<Products.PublicProduct>> published(@RequestParam(defaultValue="0") int page) { return response(200,products.published(page)); }
    @GetMapping("/api/products/{slug}") public ResponseEntity<Products.PublicProduct> published(@PathVariable String slug) { return response(200,products.published(slug)); }
    @GetMapping("/api/seller/products") public ResponseEntity<Products.Page<Products.Product>> list(Principal p,@RequestParam(defaultValue="0") int page) { return response(200,products.list(UUID.fromString(p.getName()),page)); }
    @GetMapping("/api/seller/products/{id}") public ResponseEntity<Products.Product> own(Principal p,@PathVariable UUID id) { return response(200,products.own(UUID.fromString(p.getName()),id)); }
    @PostMapping(path="/api/seller/products",consumes="application/json") public ResponseEntity<Products.Product> create(Principal p,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) { return response(201,products.saveForSession(UUID.fromString(p.getName()),session.getId(),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),null,input(body))); }
    @PutMapping(path="/api/seller/products/{id}",consumes="application/json") public ResponseEntity<Products.Product> update(Principal p,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@PathVariable UUID id,@RequestBody tools.jackson.databind.JsonNode body) { return response(200,products.saveForSession(UUID.fromString(p.getName()),session.getId(),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),id,input(body))); }
    private Products.Input input(tools.jackson.databind.JsonNode body) {
        var allowed=java.util.Set.of("categoryId","slug","title","description","state","version","returnFee");
        if(!body.isObject()||body.properties().stream().anyMatch(e->!allowed.contains(e.getKey()))) { throw new com.example.marketplace.catalog.api.CatalogException(400); }
        for(String field:java.util.List.of("categoryId","slug","title","description","state")) { if(!body.has(field)||!body.get(field).isTextual()) { throw new com.example.marketplace.catalog.api.CatalogException(400); } }
        if(!body.has("version")||!body.get("version").isIntegralNumber()||!body.get("version").canConvertToLong()||body.has("returnFee")&&!body.get("returnFee").isTextual()) { throw new com.example.marketplace.catalog.api.CatalogException(400); }
        try { return new Products.Input(UUID.fromString(body.get("categoryId").asText()),body.get("slug").asText(),body.get("title").asText(),body.get("description").asText(),body.get("state").asText(),body.get("version").asLong(),body.has("returnFee")?body.get("returnFee").asText():null); }catch(IllegalArgumentException invalid) { throw new com.example.marketplace.catalog.api.CatalogException(400); }
    }
    private <T> ResponseEntity<T> response(int status,T value) { return ResponseEntity.status(status).header("Cache-Control","no-store").body(value); }
}
