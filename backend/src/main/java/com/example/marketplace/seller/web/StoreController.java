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
import org.springframework.web.bind.annotation.RequestParam;

@RestController @Profile("db")
public class StoreController {
    private final Stores stores;
    public StoreController(Stores stores) { this.stores=stores; }
    public record Own(Stores.Store store) { }
    @GetMapping("/api/admin/accounts/stores") public ResponseEntity<Stores.Page> queue(Principal p,@RequestParam(defaultValue="0") int page) {
        return response(200,stores.queue(UUID.fromString(p.getName()),page));
    }
    @GetMapping("/api/seller/capabilities") public ResponseEntity<Stores.Capabilities> capabilities(Principal p) { return response(200,stores.capabilities(UUID.fromString(p.getName()))); }
    @PostMapping(path="/api/admin/accounts/stores/{id}/{action}",consumes="application/json")
    public ResponseEntity<Stores.Store> operation(Principal p,@PathVariable UUID id,@PathVariable String action,@RequestBody Stores.Change change) {
        if(!"suspend".equals(action) && !"restore".equals(action)) { throw new StoreException(400); }
        return response(200,stores.transition(UUID.fromString(p.getName()),id,action,change));
    }
    @PostMapping(path="/api/seller/stores/{id}/close",consumes="application/json")
    public ResponseEntity<Stores.Store> close(Principal p,@PathVariable UUID id,@RequestBody Stores.Change change) { return response(200,stores.transition(UUID.fromString(p.getName()),id,"close",change)); }
    @GetMapping("/api/seller/stores/{id}/history") public ResponseEntity<java.util.List<Stores.Event>> ownHistory(Principal p,@PathVariable UUID id) { return response(200,stores.history(UUID.fromString(p.getName()),id,false)); }
    @GetMapping("/api/admin/accounts/stores/{id}/history") public ResponseEntity<java.util.List<Stores.Event>> history(Principal p,@PathVariable UUID id) { return response(200,stores.history(UUID.fromString(p.getName()),id,true)); }
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
