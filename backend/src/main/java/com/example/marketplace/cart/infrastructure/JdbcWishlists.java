package com.example.marketplace.cart.infrastructure;
import com.example.marketplace.cart.api.Wishlists;
import com.example.marketplace.cart.api.CartException;
import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessRejected;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service @Profile("db")
public final class JdbcWishlists implements Wishlists {
    private final NamedParameterJdbcTemplate jdbc;
    private final Browse browse;
    private final AccessPolicy access;
    private final TransactionTemplate tx;
    public JdbcWishlists(NamedParameterJdbcTemplate jdbc,Browse browse,AccessPolicy access,PlatformTransactionManager manager) { this.jdbc=jdbc;this.browse=browse;this.access=access;tx=new TransactionTemplate(manager); }
    private void lock(UUID owner) {
        if(owner==null||jdbc.queryForList("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class).isEmpty()) { throw new AccessRejected(); }
        access.require(owner,AccessArea.BUYER);
    }
    private View view(UUID owner) {
        return new View(jdbc.queryForList("SELECT product_id FROM marketplace.wishlist WHERE user_id=:id ORDER BY product_id",Map.of("id",owner),UUID.class).stream().map(id->new Line(id,browse.forProduct(id))).toList());
    }
    @Override public View get(UUID owner) { return tx.execute(status->{lock(owner);return view(owner);}); }
    @Override public View add(UUID owner,String slug) {
        return tx.execute(status->{
            lock(owner);UUID product=browse.identify(slug);
            if(product==null) { throw new CartException(404); }
            var ids=jdbc.queryForList("SELECT product_id FROM marketplace.wishlist WHERE user_id=:id",Map.of("id",owner),UUID.class);
            if(!ids.contains(product)&&ids.size()>=100) { throw new CartException(409); }
            jdbc.update("INSERT INTO marketplace.wishlist(user_id,product_id) VALUES(:owner,:product) ON CONFLICT DO NOTHING",Map.of("owner",owner,"product",product));
            return view(owner);
        });
    }
    @Override public View remove(UUID owner,UUID product) {
        if(product==null) { throw new CartException(400); }
        return tx.execute(status->{lock(owner);jdbc.update("DELETE FROM marketplace.wishlist WHERE user_id=:owner AND product_id=:product",Map.of("owner",owner,"product",product));return view(owner);});
    }
}
