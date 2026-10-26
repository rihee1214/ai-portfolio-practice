package com.example.marketplace.promotion.web;
import com.example.marketplace.promotion.api.Coupons;
import java.security.Principal;
import java.time.Instant;
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
public class CouponController {
    private final Coupons api;
    public CouponController(Coupons api) { this.api=api; }
    record Claim(UUID templateId) { }
    @GetMapping("/api/seller/coupons") public ResponseEntity<?> templates(Principal p,@RequestParam(defaultValue="0") int page) { return response(200,api.templates(member(p),page)); }
    @GetMapping("/api/seller/coupons/{id}") public ResponseEntity<?> template(Principal p,@PathVariable UUID id) { return response(200,api.template(member(p),id)); }
    @PostMapping("/api/seller/coupons") public ResponseEntity<?> create(Principal p,@RequestBody Coupons.TemplateInput input) { return response(201,api.saveTemplate(member(p),null,input)); }
    @PutMapping("/api/seller/coupons/{id}") public ResponseEntity<?> update(Principal p,@PathVariable UUID id,@RequestBody Coupons.TemplateInput input) { return response(200,api.saveTemplate(member(p),id,input)); }
    @GetMapping("/api/account/coupons") public ResponseEntity<?> owned(Principal p,@RequestParam(defaultValue="0") int page) { return response(200,api.owned(member(p),page)); }
    @GetMapping("/api/account/coupons/available") public ResponseEntity<?> available(Principal p,@RequestParam(defaultValue="0") int page) { return response(200,api.available(member(p),page)); }
    @GetMapping("/api/account/coupons/{id}") public ResponseEntity<?> own(Principal p,@PathVariable UUID id) { return response(200,api.own(member(p),id)); }
    @GetMapping("/api/account/coupons/{id}/eligibility") public ResponseEntity<?> eligible(Principal p,@PathVariable UUID id,@RequestParam UUID storeId,@RequestParam long subtotal) { if(subtotal<0||subtotal>9007199254740991L) { throw new com.example.marketplace.promotion.api.PromotionRejected(400); } return response(200,api.eligible(member(p),id,storeId,subtotal,Instant.now())); }
    @PostMapping("/api/account/coupons/claim") public ResponseEntity<?> claim(Principal p,@RequestBody Claim input) { return response(200,api.claim(member(p),input.templateId())); }
    private UUID member(Principal p) { return p==null?null:UUID.fromString(p.getName()); }
    private ResponseEntity<?> response(int status,Object value) { return ResponseEntity.status(status).header("Cache-Control","no-store").body(value); }
}
