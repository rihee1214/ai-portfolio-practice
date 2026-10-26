package com.example.marketplace.promotion.web;
import com.example.marketplace.promotion.api.Promotions;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public class PromotionController {
    private final Promotions api;
    public PromotionController(Promotions api) { this.api=api; }
    @GetMapping("/api/events") public ResponseEntity<?> list(@RequestParam(defaultValue="0") int page) { return response(200,api.published(page)); }
    @GetMapping("/api/events/{id}") public ResponseEntity<?> published(@PathVariable UUID id) { return response(200,api.published(id)); }
    @GetMapping("/api/seller/promotions") public ResponseEntity<?> list(Principal p,@RequestParam(defaultValue="0") int page) { return response(200,api.list(UUID.fromString(p.getName()),page)); }
    @GetMapping("/api/seller/promotions/{id}") public ResponseEntity<?> own(Principal p,@PathVariable UUID id) { return response(200,api.own(UUID.fromString(p.getName()),id)); }
    @PostMapping("/api/seller/promotions") public ResponseEntity<?> create(Principal p,@RequestBody Promotions.Input i) { return response(201,api.save(UUID.fromString(p.getName()),null,i)); }
    @PutMapping("/api/seller/promotions/{id}") public ResponseEntity<?> update(Principal p,@PathVariable UUID id,@RequestBody Promotions.Input i) { return response(200,api.save(UUID.fromString(p.getName()),id,i)); }
    private ResponseEntity<?> response(int status,Object data) { return ResponseEntity.status(status).header("Cache-Control","no-store").body(data); }
}
