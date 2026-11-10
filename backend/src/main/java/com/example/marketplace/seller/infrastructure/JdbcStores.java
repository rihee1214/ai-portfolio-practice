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
    @Override public Page queue(UUID operator,int page) {
        access.require(operator,AccessArea.ACCOUNTS);
        if(page<0 || page>10000) { throw new StoreException(400); }
        var rows=jdbc.query("SELECT * FROM marketplace.seller_store ORDER BY id LIMIT 21 OFFSET :offset",Map.of("offset",page*20),this::map);
        return new Page(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Store transition(UUID actor,UUID id,String action,Change change) {
        boolean closing="close".equals(action);
        access.require(actor,closing?AccessArea.SELLER:AccessArea.ACCOUNTS);
        if(!closing && !"suspend".equals(action) && !"restore".equals(action)) { throw new StoreException(400); }
        if(change==null || change.version()==null || change.version()<0 || change.version()>9007199254740990L) { throw new StoreException(400); }
        String reason=text(change.reason(),300,true);
        return tx.execute(status -> {
            var owners=jdbc.queryForList("SELECT owner_id FROM marketplace.seller_store WHERE id=:id",Map.of("id",id),UUID.class);
            if(owners.isEmpty()) { throw new StoreException(404); }
            UUID owner=owners.getFirst();
            if(closing && !owner.equals(actor)) { throw new StoreException(404); }
            if(!closing && owner.equals(actor)) { throw new StoreException(403); }
            if(!members.lockActiveBuyer(owner)) { throw new StoreException(409); }
            var current=jdbc.queryForObject("SELECT * FROM marketplace.seller_store WHERE id=:id FOR UPDATE",Map.of("id",id),this::map);
            access.require(actor,closing?AccessArea.SELLER:AccessArea.ACCOUNTS);
            if(current.version()!=change.version() || "CLOSED".equals(current.state())) { throw new StoreException(409); }
            String next=closing?"CLOSED":"suspend".equals(action)?"SUSPENDED":"OPEN";
            if(!closing && !("suspend".equals(action)?"OPEN":"SUSPENDED").equals(current.state())) { throw new StoreException(409); }
            jdbc.update("UPDATE marketplace.seller_store SET state=:state,visible=false,version=version+1 WHERE id=:id",Map.of("state",next,"id",id));
            jdbc.update("INSERT INTO marketplace.store_operation_event(id,store_id,actor_id,action,before_state,after_state,reason,store_version) VALUES(:event,:store,:actor,:action,:before,:after,:reason,:version)",
                    Map.of("event",UUID.randomUUID(),"store",id,"actor",actor,"action",action,"before",current.state(),"after",next,"reason",reason,"version",current.version()+1));
            return jdbc.queryForObject("SELECT * FROM marketplace.seller_store WHERE id=:id",Map.of("id",id),this::map);
        });
    }
    @Override public java.util.List<Event> history(UUID actor,UUID id,boolean administrator) {
        access.require(actor,administrator?AccessArea.ACCOUNTS:AccessArea.SELLER);
        var owners=jdbc.queryForList("SELECT owner_id FROM marketplace.seller_store WHERE id=:id",Map.of("id",id),UUID.class);
        if(owners.isEmpty() || !administrator&&!owners.getFirst().equals(actor)) { throw new StoreException(404); }
        return jdbc.query("SELECT * FROM marketplace.store_operation_event WHERE store_id=:id ORDER BY store_version DESC NULLS LAST,occurred_at DESC,id DESC LIMIT 20",Map.of("id",id),
                (row,index)->new Event(row.getObject("actor_id",UUID.class),row.getString("action"),row.getString("before_state"),row.getString("after_state"),row.getString("reason"),row.getTimestamp("occurred_at").toInstant()));
    }
    @Override public Store existingTrade(UUID id) {
        record Existing(UUID owner,Store value) { }
        var rows=jdbc.query("SELECT * FROM marketplace.seller_store WHERE id=:id",Map.of("id",id),(r,n)->new Existing(r.getObject("owner_id",UUID.class),map(r,n)));
        if(rows.size()!=1||!access.allowed(rows.getFirst().owner(),AccessArea.SELLER)){throw new StoreException(404);}return rows.getFirst().value();
    }
    @Override public Store lockExistingTrade(UUID id) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        var owners=jdbc.queryForList("SELECT owner_id FROM marketplace.seller_store WHERE id=:id",Map.of("id",id),UUID.class);if(owners.size()!=1) { throw new StoreException(404); }UUID owner=owners.getFirst();members.lockSeller(owner);
        var rows=jdbc.queryForList("SELECT owner_id FROM marketplace.seller_store WHERE id=:id FOR UPDATE",Map.of("id",id),UUID.class);if(rows.size()!=1||!rows.getFirst().equals(owner)) { throw new StoreException(409); }access.require(owner,AccessArea.SELLER);return jdbc.queryForObject("SELECT * FROM marketplace.seller_store WHERE id=:id",Map.of("id",id),this::map);
    }
    private Capabilities allowed(Store store) {
        boolean open=store!=null&&"OPEN".equals(store.state());
        return new Capabilities(open,open&&store.visible(),store!=null);
    }
    @Override public Capabilities capabilities(UUID owner) { return allowed(own(owner)); }
    @Override public void requireCapability(UUID owner,Capability capability) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        if(!members.lockActiveBuyer(owner)) { throw new StoreException(403); }
        var rows=jdbc.query("SELECT * FROM marketplace.seller_store WHERE owner_id=:owner FOR UPDATE",Map.of("owner",owner),this::map);
        var flags=allowed(rows.isEmpty()?null:rows.getFirst());
        if(capability==null) { throw new StoreException(400); }
        boolean permitted=switch(capability) { case MANAGE_CATALOG -> flags.manageCatalog(); case NEW_SALES -> flags.newSales(); case EXISTING_TRADES -> flags.existingTrades(); };
        if(!permitted) { throw new StoreException(409); }
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
                    if(!"OPEN".equals(rows.getFirst().state())) { throw new StoreException(409); }
                    if(!rows.getFirst().slug().equals(slug) || rows.getFirst().version()!=input.version()) { throw new StoreException(409); }
                    jdbc.update("UPDATE marketplace.seller_store SET name=:name,introduction=:intro,address=:address,notice=:notice,image_key=:image,image_alt=:alt,visible=:visible,version=version+1 WHERE id=:id AND owner_id=:owner",p);
                }
                return own(owner);
            });
        } catch(DuplicateKeyException conflict) { throw new StoreException(409); }
    }
    @Override public PublicStore published(String slug) {
        if(slug==null || !slug.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new StoreException(404); }
        record Candidate(Store store,UUID owner) { }
        var rows=jdbc.query("SELECT * FROM marketplace.seller_store WHERE slug=:slug AND visible=true AND state='OPEN'",Map.of("slug",slug),
                (row,index)->new Candidate(map(row,index),row.getObject("owner_id",UUID.class)));
        // Release the query connection before the identity API performs its own query.
        if(rows.isEmpty() || !access.allowed(rows.getFirst().owner(),AccessArea.SELLER)) { throw new StoreException(404); }
        Store s=rows.getFirst().store();
        return new PublicStore(s.slug(),s.name(),s.introduction(),s.address(),s.notice(),s.imageKey(),s.imageAlt());
    }
    private Store map(java.sql.ResultSet row,int index) throws java.sql.SQLException {
        return new Store(row.getObject("id",UUID.class),row.getString("slug"),row.getString("name"),row.getString("introduction"),row.getString("address"),row.getString("notice"),row.getString("image_key"),row.getString("image_alt"),row.getBoolean("visible"),row.getLong("version"),row.getString("state"));
    }
    private String text(String value,int limit,boolean required) {
        if(value==null) { throw new StoreException(400); }
        String s=value.strip();
        if(required&&s.isEmpty() || s.codePointCount(0,s.length())>limit || s.codePoints().anyMatch(c -> Character.isISOControl(c) || c>=0xD800&&c<=0xDFFF)) { throw new StoreException(400); }
        return s;
    }
}
