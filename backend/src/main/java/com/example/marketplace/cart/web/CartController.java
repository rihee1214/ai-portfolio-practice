package com.example.marketplace.cart.web;
import com.example.marketplace.cart.api.Carts;
import com.example.marketplace.cart.api.CartException;
import java.security.Principal;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
@RestController @Profile("db")
public class CartController {
    public record Change(UUID owner,BigDecimal quantity,BigDecimal version) { }
    public record Merge(UUID owner,UUID requestId,List<Item> items) { }
    public record Item(UUID skuId,BigDecimal quantity) { }
    public record Preview(List<Item> items) { }
    public record Reconcile(UUID owner,UUID orderId,List<Carts.Submitted> items) { }
    private final Carts carts;
    public CartController(Carts carts) { this.carts=carts; }
    private UUID owner(Principal principal,UUID expected) {
        UUID actual=UUID.fromString(principal.getName());
        if(!actual.equals(expected)) { throw new CartException(409); }
        return actual;
    }
    @GetMapping("/api/cart") public ResponseEntity<Carts.View> get(Principal p,@RequestParam UUID owner) { return response(carts.get(owner(p,owner))); }
    @PutMapping("/api/cart/items/{sku}") public ResponseEntity<Carts.View> set(Principal p,@PathVariable UUID sku,@RequestBody Change input) {
        if(input==null||input.quantity()==null||input.version()==null) { throw new CartException(400); }
        return response(carts.set(owner(p,input.owner()),sku,input.quantity().intValueExact(),input.version().longValueExact()));
    }
    @DeleteMapping("/api/cart") public ResponseEntity<Carts.View> clear(Principal p,@RequestParam UUID owner,@RequestParam long version) { return response(carts.clear(owner(p,owner),version)); }
    @PostMapping("/api/cart/merge") public ResponseEntity<Carts.View> merge(Principal p,@RequestBody Merge input) {
        if(input==null) { throw new CartException(400); }
        return response(carts.merge(owner(p,input.owner()),input.requestId(),items(input.items())));
    }
    @PostMapping("/api/cart/preview") public ResponseEntity<Carts.View> preview(@RequestBody Preview input) {
        if(input==null) { throw new CartException(400); }
        return response(carts.preview(items(input.items())));
    }
    @PostMapping("/api/cart/reconcile") public ResponseEntity<Carts.View> reconcile(Principal p,@RequestBody Reconcile input) {
        if(input==null) { throw new CartException(400); }
        return response(carts.reconcile(owner(p,input.owner()),input.orderId(),input.items()));
    }
    private List<Carts.Input> items(List<Item> values) {
        if(values==null||values.size()>100) { throw new CartException(400); }
        return values.stream().map(value -> {
            if(value==null||value.quantity()==null) { throw new CartException(400); }
            return new Carts.Input(value.skuId(),value.quantity().intValueExact());
        }).toList();
    }
    private ResponseEntity<Carts.View> response(Carts.View value) { return ResponseEntity.ok().header("Cache-Control","no-store").body(value); }
}
