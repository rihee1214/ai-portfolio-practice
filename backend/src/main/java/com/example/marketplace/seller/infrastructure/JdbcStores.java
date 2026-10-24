package com.example.marketplace.seller.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.seller.api.Stores;
import com.example.marketplace.seller.api.StoreException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

public final class JdbcStores implements Stores {
    private final NamedParameterJdbcTemplate jdbc;
    private final SellerMembership members;
    private final AccessPolicy access;
    private final TransactionTemplate tx;
    public JdbcStores(NamedParameterJdbcTemplate jdbc, SellerMembership members, AccessPolicy access, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.members=members; this.access=access; this.tx=new TransactionTemplate(manager);
    }
    @Override public Store own(UUID owner) {
        access.require(owner, AccessArea.SELLER);
        var rows=jdbc.query("SELECT * FROM marketplace.seller_store WHERE owner_id=:owner",Map.of("owner",owner),this::map);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Override public Store save(UUID owner, UUID id, Input input) {
        access.require(owner, AccessArea.SELLER);
        if(input==null || input.visible()==null || input.version()==null || input.version()<0 || input.version()>9007199254740990L) { throw new StoreException(400); }
        String slug=input.slug();
        if(slug==null || !slug.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new StoreException(400); }
        String key=text(input.imageKey(),100,false), alt=text(input.imageAlt(),120,false);
        if(key.isEmpty()!=alt.isEmpty() || !key.isEmpty()&&!key.matches("[A-Za-z0-9_-]+")) { throw new StoreException(400); }
        var p=new MapSqlParameterSource().addValue("owner",owner).addValue("slug",slug)
                .addValue("name",text(input.name(),80,true)).addValue("intro",text(input.introduction(),1000,true))
                .addValue("address",text(input.address(),200,true)).addValue("notice",text(input.notice(),1000,false))
                .addValue("image",key).addValue("alt",alt).addValue("visible",input.visible()).addValue("version",input.version());
        try {
            return tx.execute(status -> {
                if(!members.lockActiveBuyer(owner)) { throw new StoreException(403); }
                if(id==null) {
                    if(input.version()!=0) { throw new StoreException(400); }
                    p.addValue("id",UUID.randomUUID());
                    jdbc.update("INSERT INTO marketplace.seller_store(id,owner_id,slug,name,introduction,address,notice,image_key,image_alt,visible) VALUES(:id,:owner,:slug,:name,:intro,:address,:notice,:image,:alt,:visible)",p);
                } else {
                    p.addValue("id",id);
                    var rows=jdbc.query("SELECT * FROM marketplace.seller_store WHERE id=:id AND owner_id=:owner FOR UPDATE",p,this::map);
                    if(rows.isEmpty()) { throw new StoreException(404); }
                    if(!rows.getFirst().slug().equals(slug) || rows.getFirst().version()!=input.version()) { throw new StoreException(409); }
                    jdbc.update("UPDATE marketplace.seller_store SET name=:name,introduction=:intro,address=:address,notice=:notice,image_key=:image,image_alt=:alt,visible=:visible,version=version+1 WHERE id=:id AND owner_id=:owner",p);
                }
                return own(owner);
            });
        } catch(DuplicateKeyException conflict) { throw new StoreException(409); }
    }
    @Override public PublicStore published(String slug) {
        if(slug==null || !slug.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new StoreException(404); }
        return jdbc.query("SELECT * FROM marketplace.seller_store WHERE slug=:slug AND visible=true",Map.of("slug",slug),row -> {
            if(!row.next() || !access.allowed(row.getObject("owner_id",UUID.class),AccessArea.SELLER)) { throw new StoreException(404); }
            Store s=map(row,0);
            return new PublicStore(s.slug(),s.name(),s.introduction(),s.address(),s.notice(),s.imageKey(),s.imageAlt());
        });
    }
    private Store map(java.sql.ResultSet row,int index) throws java.sql.SQLException {
        return new Store(row.getObject("id",UUID.class),row.getString("slug"),row.getString("name"),row.getString("introduction"),row.getString("address"),row.getString("notice"),row.getString("image_key"),row.getString("image_alt"),row.getBoolean("visible"),row.getLong("version"));
    }
    private String text(String value,int limit,boolean required) {
        if(value==null) { throw new StoreException(400); }
        String s=value.strip();
        if(required&&s.isEmpty() || s.codePointCount(0,s.length())>limit || s.codePoints().anyMatch(c -> Character.isISOControl(c) || c>=0xD800&&c<=0xDFFF)) { throw new StoreException(400); }
        return s;
    }
}
