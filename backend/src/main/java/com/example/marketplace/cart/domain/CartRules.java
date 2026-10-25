package com.example.marketplace.cart.domain;
import com.example.marketplace.cart.api.Carts;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
public final class CartRules {
    private CartRules() { }
    public static List<Carts.Input> normalize(List<Carts.Input> items) {
        if(items==null||items.size()>100) { throw new IllegalArgumentException("Invalid cart items"); }
        var quantities=new TreeMap<UUID,Integer>(java.util.Comparator.comparing(UUID::toString));
        for(var item:items) {
            if(item==null||item.skuId()==null||item.quantity()<1||item.quantity()>99) { throw new IllegalArgumentException("Invalid cart quantity"); }
            quantities.merge(item.skuId(),item.quantity(),(a,b)->Math.min(99,a+b));
        }
        return quantities.entrySet().stream().map(e->new Carts.Input(e.getKey(),e.getValue())).toList();
    }
}
