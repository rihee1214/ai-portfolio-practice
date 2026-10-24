package com.example.marketplace.catalog.web;

import com.example.marketplace.catalog.api.Categories;
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
public class CategoryController {
    private final Categories categories;
    public CategoryController(Categories categories) { this.categories=categories; }
    @GetMapping("/api/categories") public ResponseEntity<Categories.Page> published(@RequestParam(defaultValue="0") int page) { return response(200,categories.list(null,page)); }
    @GetMapping("/api/admin/content/categories") public ResponseEntity<Categories.Page> list(Principal p,@RequestParam(defaultValue="0") int page) { return response(200,categories.list(UUID.fromString(p.getName()),page)); }
    @PostMapping(path="/api/admin/content/categories",consumes="application/json") public ResponseEntity<Categories.Category> create(Principal p,@RequestBody Categories.Input input) { return response(201,categories.save(UUID.fromString(p.getName()),null,input)); }
    @PutMapping(path="/api/admin/content/categories/{id}",consumes="application/json") public ResponseEntity<Categories.Category> update(Principal p,@PathVariable UUID id,@RequestBody Categories.Input input) { return response(200,categories.save(UUID.fromString(p.getName()),id,input)); }
    private <T> ResponseEntity<T> response(int status,T value) { return ResponseEntity.status(status).header("Cache-Control","no-store").body(value); }
}
