package com.example.marketplace.cart.api;
import com.example.marketplace.catalog.api.Browse;
import java.util.List;
import java.util.UUID;
public interface Wishlists {
    record Line(UUID productId,Browse.Detail detail) { }
    record View(List<Line> items) { }
    View get(UUID owner);
    View add(UUID owner,String slug);
    View remove(UUID owner,UUID product);
}
