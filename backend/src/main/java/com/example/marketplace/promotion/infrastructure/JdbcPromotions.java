package com.example.marketplace.promotion.infrastructure;
import com.example.marketplace.promotion.api.Promotions;
import com.example.marketplace.promotion.api.PromotionRejected;
import com.example.marketplace.promotion.domain.PromotionRules;
import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.seller.api.Stores;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccessArea;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service @Profile("db")
public final class JdbcPromotions implements Promotions {
    private final NamedParameterJdbcTemplate jdbc;
    private final Stores stores;
    private final Products products;
    private final AccessPolicy access;
    private final TransactionTemplate tx;
    public JdbcPromotions(NamedParameterJdbcTemplate jdbc,Stores stores,Products products,AccessPolicy access,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.stores=stores;this.products=products;this.access=access;tx=new TransactionTemplate(manager);
    }
    // Read-only public projection; no other module's tables are mutated.
    private static final String VISIBLE="""
        EXISTS(SELECT 1 FROM marketplace.seller_store s JOIN marketplace.identity_user u ON u.id=s.owner_id
          WHERE s.id=p.store_id AND s.state='OPEN' AND s.visible AND u.state='ACTIVE'
          AND EXISTS(SELECT 1 FROM marketplace.role_assignment r WHERE r.user_id=u.id AND r.role='SELLER'))
        """;
    private static final String TARGET="""
        SELECT t.product_id FROM marketplace.promotion_target t
        JOIN marketplace.catalog_product c ON c.id=t.product_id JOIN marketplace.catalog_category k ON k.id=c.category_id
        WHERE t.promotion_id=p.id AND c.store_id=p.store_id AND c.state='ACTIVE' AND NOT c.is_hidden_by_admin AND k.active
        """;
    private static final String STORE_COLUMNS="(SELECT s.slug FROM marketplace.seller_store s WHERE s.id=p.store_id) AS store_slug,(SELECT s.name FROM marketplace.seller_store s WHERE s.id=p.store_id) AS store_name,";
    private static String targetColumns(String query) { return "ARRAY("+query+" ORDER BY t.product_id) AS targets,ARRAY("+query.replace("SELECT t.product_id","SELECT c.slug")+" ORDER BY t.product_id) AS target_slugs,ARRAY("+query.replace("SELECT t.product_id","SELECT c.title")+" ORDER BY t.product_id) AS target_titles"; }
    private static final String PUBLIC="SELECT p.*,"+STORE_COLUMNS+targetColumns(TARGET)+" FROM marketplace.promotion p WHERE p.state='ACTIVE' AND p.starts_at<=:at AND p.ends_at>:at AND "+VISIBLE+" AND (p.scope='SHIPPING' OR EXISTS("+TARGET+"))";
    private static final String OWN="SELECT p.*,"+STORE_COLUMNS+targetColumns("SELECT t.product_id FROM marketplace.promotion_target t JOIN marketplace.catalog_product c ON c.id=t.product_id WHERE t.promotion_id=p.id")+" FROM marketplace.promotion p";
    private void page(int page) { if(page<0||page>10000) { throw new PromotionRejected(400); } }
    @Override public Promotion own(UUID actor,UUID id) {
        access.require(actor,AccessArea.SELLER);var store=stores.own(actor);
        if(store==null) { throw new PromotionRejected(404); }
        var rows=jdbc.query(OWN+" WHERE p.id=:id AND p.store_id=:store",Map.of("id",id,"store",store.id()),this::map);
        if(rows.isEmpty()) { throw new PromotionRejected(404); } return rows.getFirst();
    }
    @Override public Page list(UUID actor,int page) {
        page(page);access.require(actor,AccessArea.SELLER);var store=stores.own(actor);
        if(store==null) { return new Page(List.of(),page,false); }
        var rows=jdbc.query(OWN+" WHERE p.store_id=:store ORDER BY p.id LIMIT 21 OFFSET :offset",Map.of("store",store.id(),"offset",page*20),this::map);
        return new Page(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Promotion published(UUID id) {
        var rows=jdbc.query(PUBLIC+" AND p.id=:id",Map.of("id",id,"at",java.sql.Timestamp.from(Instant.now())),this::map);
        if(rows.isEmpty()) { throw new PromotionRejected(404); } return rows.getFirst();
    }
    @Override public Page published(int page) {
        page(page);var rows=jdbc.query(PUBLIC+" ORDER BY p.id LIMIT 21 OFFSET :offset",Map.of("at",java.sql.Timestamp.from(Instant.now()),"offset",page*20),this::map);
        return new Page(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public List<Promotion> candidates(UUID store,List<UUID> ids,Instant at) {
        if(store==null||ids==null||ids.size()>20||ids.stream().anyMatch(java.util.Objects::isNull)||at==null) { throw new PromotionRejected(400); }
        return jdbc.query(PUBLIC+" AND p.store_id=:store ORDER BY p.id",Map.of("at",java.sql.Timestamp.from(at),"store",store),this::map).stream()
            .filter(p->p.scope().equals("SHIPPING")||p.productIds().stream().anyMatch(ids::contains)).toList();
    }
    @Override public Promotion save(UUID actor,UUID id,Input i) {
        access.require(actor,AccessArea.SELLER);validate(i);
        return tx.execute(status->{
            stores.requireCapability(actor,Stores.Capability.MANAGE_CATALOG);access.require(actor,AccessArea.SELLER);
            UUID store=stores.own(actor).id();Promotion previous=id==null?null:own(actor,id);
            // Store capability holds the owner/store locks used by catalog writes.
            // Retiring unchanged targets checks ownership without editing archived products.
            boolean retiring=previous!=null && java.util.Set.of("STOPPED","ARCHIVED").contains(i.state())
                && new java.util.HashSet<>(previous.productIds()).equals(new java.util.HashSet<>(i.productIds()));
            for(UUID product:i.productIds().stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
                if(retiring) { products.own(actor,product); } else { products.lockForMedia(actor,product); }
            }
            if(previous==null) { if(!"DRAFT".equals(i.state())||i.version()!=0) { throw new PromotionRejected(400); } }
            else {
                jdbc.queryForObject("SELECT id FROM marketplace.promotion WHERE id=:id FOR UPDATE",Map.of("id",id),UUID.class);
                previous=own(actor,id);
                if(previous.version()!=i.version()||previous.state().equals("ARCHIVED")||!previous.state().equals("DRAFT")&&i.state().equals("DRAFT")) { throw new PromotionRejected(409); }
            }
            UUID key=id==null?UUID.randomUUID():id;long version=id==null?0:previous.version()+1;
            var p=new MapSqlParameterSource().addValue("id",key).addValue("store",store).addValue("title",i.title().trim()).addValue("scope",i.scope())
                .addValue("start",java.sql.Timestamp.from(i.startsAt())).addValue("end",java.sql.Timestamp.from(i.endsAt())).addValue("kind",i.kind()).addValue("value",i.value())
                .addValue("cap",i.cap()).addValue("state",i.state()).addValue("version",version).addValue("actor",actor);
            if(id==null) { jdbc.update("INSERT INTO marketplace.promotion(id,store_id,title,scope,starts_at,ends_at,kind,value,cap,state,version) VALUES(:id,:store,:title,:scope,:start,:end,:kind,:value,:cap,:state,:version)",p); }
            else { jdbc.update("UPDATE marketplace.promotion SET title=:title,scope=:scope,starts_at=:start,ends_at=:end,kind=:kind,value=:value,cap=:cap,state=:state,version=:version WHERE id=:id",p); }
            jdbc.update("DELETE FROM marketplace.promotion_target WHERE promotion_id=:id",p);
            for(UUID product:i.productIds()) { jdbc.update("INSERT INTO marketplace.promotion_target VALUES(:id,:product)",new MapSqlParameterSource("id",key).addValue("product",product)); }
            jdbc.update("INSERT INTO marketplace.promotion_event(id,promotion_id,actor_id,version,before_state,after_state,snapshot) VALUES(:event,:id,:actor,:version,:before,:state,:snapshot)",p.addValue("event",UUID.randomUUID()).addValue("before",previous==null?null:previous.state()).addValue("snapshot",i.toString()));
            return own(actor,key);
        });
    }
    private void validate(Input i) {
        if(i==null||i.title()==null||i.title().isBlank()||i.title().codePointCount(0,i.title().length())>120||i.scope()==null||!java.util.Set.of("PRODUCT","SHIPPING").contains(i.scope())
            ||i.state()==null||!java.util.Set.of("DRAFT","ACTIVE","STOPPED","ARCHIVED").contains(i.state())||i.version()==null||i.version()<0||i.version()>9007199254740990L
            ||i.value()==null||i.productIds()==null||i.productIds().stream().anyMatch(java.util.Objects::isNull)||i.productIds().size()>20||new java.util.HashSet<>(i.productIds()).size()!=i.productIds().size()) { throw new PromotionRejected(400); }
        PromotionRules.inputAmount(i.value(),i.cap());PromotionRules.period(i.startsAt(),i.endsAt());PromotionRules.discount(0,i.kind(),i.value(),i.cap());
        if(i.scope().equals("PRODUCT")&&(i.productIds().isEmpty()||"FREE".equals(i.kind()))||i.scope().equals("SHIPPING")&&(!i.productIds().isEmpty()||"RATE".equals(i.kind()))) { throw new PromotionRejected(400); }
    }
    private Promotion map(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        var a=(Object[])r.getArray("targets").getArray();var ids=java.util.Arrays.stream(a).map(x->UUID.fromString(x.toString())).toList();
        var slugs=(Object[])r.getArray("target_slugs").getArray();var titles=(Object[])r.getArray("target_titles").getArray();var targets=new java.util.ArrayList<Target>();
        for(int i=0;i<ids.size();i++) { targets.add(new Target(ids.get(i),slugs[i].toString(),titles[i].toString())); }
        return new Promotion(r.getObject("id",UUID.class),r.getObject("store_id",UUID.class),r.getString("title"),r.getString("scope"),ids,r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant(),r.getString("kind"),r.getLong("value"),r.getObject("cap",Long.class),r.getString("state"),r.getLong("version"),r.getString("store_slug"),r.getString("store_name"),List.copyOf(targets));
    }
}
