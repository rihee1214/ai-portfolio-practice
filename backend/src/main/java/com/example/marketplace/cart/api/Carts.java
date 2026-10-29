package com.example.marketplace.cart.api;
import java.util.List;
import java.util.UUID;
import com.example.marketplace.catalog.api.Browse;
public interface Carts {
    record Input(UUID skuId,int quantity) { }
    record Line(UUID skuId,int quantity,Browse.Detail detail,long revision) { }
    record Submitted(UUID skuId,int quantity,long revision) { }
    record View(long version,List<Line> items) { }
    View get(UUID owner);
    View preview(List<Input> items);
    View set(UUID owner,UUID sku,int quantity,long version);
    View clear(UUID owner,long version);
    View merge(UUID owner,UUID requestId,List<Input> items);
    View reconcile(UUID owner,UUID orderId,List<Submitted> submitted);
}
