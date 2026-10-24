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
    @PostMapping(path="/api/seller/products",consumes="application/json") public ResponseEntity<Products.Product> create(Principal p,@RequestBody Products.Input input) { return response(201,products.save(UUID.fromString(p.getName()),null,input)); }
    @PutMapping(path="/api/seller/products/{id}",consumes="application/json") public ResponseEntity<Products.Product> update(Principal p,@PathVariable UUID id,@RequestBody Products.Input input) { return response(200,products.save(UUID.fromString(p.getName()),id,input)); }
    private <T> ResponseEntity<T> response(int status,T value) { return ResponseEntity.status(status).header("Cache-Control","no-store").body(value); }
}
