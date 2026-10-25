package com.example.marketplace.cart.infrastructure;
import com.example.marketplace.cart.api.Carts;
import com.example.marketplace.cart.api.CartException;
import com.example.marketplace.cart.domain.CartRules;
import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessRejected;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class JdbcCarts implements Carts {
    private final NamedParameterJdbcTemplate jdbc;
    private final Browse browse;
    private final AccessPolicy access;
    private final TransactionTemplate tx;
    public JdbcCarts(NamedParameterJdbcTemplate jdbc,Browse browse,AccessPolicy access,PlatformTransactionManager manager) { this.jdbc=jdbc;this.browse=browse;this.access=access;tx=new TransactionTemplate(manager); }
    private void lockOwner(UUID owner) {
        if(owner==null||jdbc.queryForList("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class).isEmpty()) { throw new AccessRejected(); }
        access.require(owner,AccessArea.BUYER);
    }
    private long lockCart(UUID owner) {
        jdbc.update("INSERT INTO marketplace.cart(user_id) VALUES(:id) ON CONFLICT DO NOTHING",Map.of("id",owner));
        return jdbc.queryForObject("SELECT version FROM marketplace.cart WHERE user_id=:id FOR UPDATE",Map.of("id",owner),Long.class);
    }
    private void version(long expected,long current) {
        if(expected<0||expected>9007199254740991L) { throw new CartException(400); }
        if(expected!=current||current==9007199254740991L) { throw new CartException(409); }
    }
    private void bump(UUID owner) { jdbc.update("UPDATE marketplace.cart SET version=version+1 WHERE user_id=:id",Map.of("id",owner)); }
    private List<Input> stored(UUID owner) { return jdbc.query("SELECT sku_id,quantity FROM marketplace.cart_item WHERE user_id=:id ORDER BY sku_id",Map.of("id",owner),(r,n)->new Input(r.getObject(1,UUID.class),r.getInt(2))); }
    private View view(UUID owner) {
        var versions=jdbc.queryForList("SELECT version FROM marketplace.cart WHERE user_id=:id",Map.of("id",owner),Long.class);
        return new View(versions.isEmpty()?0:versions.getFirst(),lines(stored(owner)));
    }
    private List<Line> lines(List<Input> items) { return items.stream().map(i->new Line(i.skuId(),i.quantity(),browse.forSku(i.skuId()))).toList(); }
    @Override public View preview(List<Input> items) { return new View(0,lines(CartRules.normalize(items))); }
    @Override public View get(UUID owner) { return tx.execute(status->{lockOwner(owner);return view(owner);}); }
    @Override public View set(UUID owner,UUID sku,int quantity,long expected) {
        if(sku==null||quantity<0||quantity>99) { throw new CartException(400); }
        return tx.execute(status->{
            lockOwner(owner);version(expected,lockCart(owner));
            var items=stored(owner);boolean exists=items.stream().anyMatch(i->i.skuId().equals(sku));
            if(quantity>0&&!exists&&(items.size()>=100||browse.forSku(sku)==null)) { throw new CartException(409); }
            if(quantity==0) { jdbc.update("DELETE FROM marketplace.cart_item WHERE user_id=:owner AND sku_id=:sku",Map.of("owner",owner,"sku",sku)); }
            else { put(owner,new Input(sku,quantity)); }
            bump(owner);return view(owner);
        });
    }
    private void put(UUID owner,Input item) {
        jdbc.update("INSERT INTO marketplace.cart_item(user_id,sku_id,quantity) VALUES(:owner,:sku,:quantity) ON CONFLICT(user_id,sku_id) DO UPDATE SET quantity=excluded.quantity",Map.of("owner",owner,"sku",item.skuId(),"quantity",item.quantity()));
    }
    @Override public View clear(UUID owner,long expected) {
        return tx.execute(status->{lockOwner(owner);version(expected,lockCart(owner));jdbc.update("DELETE FROM marketplace.cart_item WHERE user_id=:id",Map.of("id",owner));bump(owner);return view(owner);});
    }
    @Override public View merge(UUID owner,UUID requestId,List<Input> items) {
        if(requestId==null) { throw new CartException(400); }
        var normalized=CartRules.normalize(items);
        String payload=normalized.stream().map(i->i.skuId()+":"+i.quantity()).collect(java.util.stream.Collectors.joining(","));
        return tx.execute(status->{
            lockOwner(owner);long current=lockCart(owner);
            int inserted=jdbc.update("INSERT INTO marketplace.cart_merge(request_id,user_id,payload) VALUES(:request,:owner,:payload) ON CONFLICT DO NOTHING",Map.of("request",requestId,"owner",owner,"payload",payload));
            if(inserted==0) {
                var previous=jdbc.queryForMap("SELECT user_id,payload FROM marketplace.cart_merge WHERE request_id=:id",Map.of("id",requestId));
                if(!owner.equals(previous.get("user_id"))||!payload.equals(previous.get("payload"))) { throw new CartException(409); }
                return view(owner);
            }
            version(current,current);
            var combined=new java.util.TreeMap<UUID,Integer>(java.util.Comparator.comparing(UUID::toString));
            stored(owner).forEach(i->combined.put(i.skuId(),i.quantity()));normalized.forEach(i->combined.merge(i.skuId(),i.quantity(),(a,b)->Math.min(99,a+b)));
            if(combined.size()>100) { throw new CartException(409); }
            combined.forEach((sku,quantity)->put(owner,new Input(sku,quantity)));
            if(!normalized.isEmpty()) { bump(owner); }
            return view(owner);
        });
    }
}
