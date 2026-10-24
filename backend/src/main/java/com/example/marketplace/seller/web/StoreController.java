package com.example.marketplace.seller.web;

import com.example.marketplace.seller.api.Stores;
import com.example.marketplace.seller.api.StoreException;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public class StoreController {
    private final Stores stores;
    public StoreController(Stores stores) { this.stores=stores; }
    public record Own(Stores.Store store) { }
    @GetMapping("/api/seller/stores") public ResponseEntity<Own> own(Principal p) { return response(200,new Own(stores.own(UUID.fromString(p.getName())))); }
    @PostMapping(path="/api/seller/stores",consumes="application/json") public ResponseEntity<Stores.Store> create(Principal p,@RequestBody Stores.Input input) {
        return response(201,stores.save(UUID.fromString(p.getName()),null,input));
    }
    @PutMapping(path="/api/seller/stores/{id}",consumes="application/json") public ResponseEntity<Stores.Store> update(Principal p,@PathVariable UUID id,@RequestBody Stores.Input input) {
        return response(200,stores.save(UUID.fromString(p.getName()),id,input));
    }
    @GetMapping("/api/stores/{slug}") public ResponseEntity<Stores.PublicStore> published(@PathVariable String slug) { return response(200,stores.published(slug)); }
    @ExceptionHandler({StoreException.class,AccessRejected.class,AccountException.class})
    public ResponseEntity<ApiErrorResponse> rejected(RuntimeException ex,HttpServletRequest request) {
        int status=ex instanceof StoreException s?s.status():ex instanceof AccessRejected?403:409;
        return response(status,ApiErrorResponse.forStatus(status,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
    private <T> ResponseEntity<T> response(int status,T value) { return ResponseEntity.status(status).header("Cache-Control","no-store").body(value); }
}
