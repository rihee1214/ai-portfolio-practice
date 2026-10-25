package com.example.marketplace.cart.web;
import com.example.marketplace.cart.api.Wishlists;
import com.example.marketplace.cart.api.CartException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
@RestController @Profile("db")
public class WishlistController {
    private final Wishlists lists;
    public WishlistController(Wishlists lists) { this.lists=lists; }
    private UUID owner(Principal principal,UUID expected) {
        UUID actual=UUID.fromString(principal.getName());
        if(!actual.equals(expected)) { throw new CartException(409); }
        return actual;
    }
    @GetMapping("/api/wishlist") public ResponseEntity<Wishlists.View> get(Principal p,@RequestParam UUID owner) { return response(lists.get(owner(p,owner))); }
    @PutMapping("/api/wishlist/{slug}") public ResponseEntity<Wishlists.View> add(Principal p,@RequestParam UUID owner,@PathVariable String slug) { return response(lists.add(owner(p,owner),slug)); }
    @DeleteMapping("/api/wishlist/{product}") public ResponseEntity<Wishlists.View> remove(Principal p,@RequestParam UUID owner,@PathVariable UUID product) { return response(lists.remove(owner(p,owner),product)); }
    private ResponseEntity<Wishlists.View> response(Wishlists.View view) { return ResponseEntity.ok().header("Cache-Control","no-store").body(view); }
}
