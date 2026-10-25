package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.seller.api.Stores;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

@Service @Profile("db")
public final class JdbcProducts implements Products {
    // Read projection only: one snapshot applies all public eligibility conditions.
    static final String PUBLIC = """
            SELECT p.*,s.slug AS store_slug FROM marketplace.catalog_product p
            JOIN marketplace.catalog_category c ON c.id=p.category_id
            JOIN marketplace.seller_store s ON s.id=p.store_id
            JOIN marketplace.identity_user u ON u.id=s.owner_id
            WHERE p.state='ACTIVE' AND NOT p.is_hidden_by_admin AND c.active
            AND s.state='OPEN' AND s.visible AND u.state='ACTIVE'
            AND EXISTS(SELECT 1 FROM marketplace.role_assignment r WHERE r.user_id=u.id AND r.role='SELLER')
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final Stores stores;
    private final TransactionTemplate tx;
    public JdbcProducts(NamedParameterJdbcTemplate jdbc,AccessPolicy access,Stores stores,PlatformTransactionManager manager) { this.jdbc=jdbc; this.access=access; this.stores=stores; tx=new TransactionTemplate(manager); }
    @Override public Product own(UUID owner,UUID id) {
        access.require(owner,AccessArea.SELLER);
        var rows=jdbc.query("SELECT p.* FROM marketplace.catalog_product p JOIN marketplace.seller_store s ON s.id=p.store_id WHERE s.owner_id=:owner AND p.id=:id",Map.of("owner",owner,"id",id),this::map);
        if(rows.isEmpty()) { throw new CatalogException(404); }
        return rows.getFirst();
    }
    @Override public void lockForMedia(UUID owner,UUID id) {
        stores.requireCapability(owner,Stores.Capability.MANAGE_CATALOG);
        own(owner,id);
        String state=jdbc.queryForObject("SELECT state FROM marketplace.catalog_product WHERE id=:id FOR UPDATE",Map.of("id",id),String.class);
        if("ARCHIVED".equals(state)) { throw new CatalogException(409); }
    }
    @Override public Page<Product> list(UUID owner,int page) {
        access.require(owner,AccessArea.SELLER); CatalogValues.page(page);
        var rows=jdbc.query("SELECT p.* FROM marketplace.catalog_product p JOIN marketplace.seller_store s ON s.id=p.store_id WHERE s.owner_id=:owner ORDER BY p.id LIMIT 21 OFFSET :offset",Map.of("owner",owner,"offset",page*20),this::map);
        return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Product save(UUID owner,UUID id,Input input) {
        access.require(owner,AccessArea.SELLER);
        if(input==null||input.categoryId()==null||input.state()==null||!java.util.Set.of("DRAFT","ACTIVE","STOPPED","ARCHIVED").contains(input.state())
                ||input.slug()==null||!input.slug().matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new CatalogException(400); }
        CatalogValues.version(input.version());
        var p=new MapSqlParameterSource().addValue("owner",owner).addValue("id",id==null?UUID.randomUUID():id)
                .addValue("category",input.categoryId()).addValue("slug",input.slug()).addValue("title",CatalogValues.text(input.title(),120,true))
                .addValue("description",CatalogValues.description(input.description())).addValue("state",input.state());
        try {
            return tx.execute(status->{
                stores.requireCapability(owner,Stores.Capability.MANAGE_CATALOG);
                access.require(owner,AccessArea.SELLER);
                // Resolve ownership before category/state errors; never disclose a foreign product.
                Product current=id==null?null:own(owner,id);
                var categories=jdbc.queryForList("SELECT active FROM marketplace.catalog_category WHERE id=:category FOR UPDATE",p,Boolean.class);
                if(categories.isEmpty()) { throw new CatalogException(400); }
                if("ACTIVE".equals(input.state())&&!categories.getFirst()) { throw new CatalogException(409); }
                if(current==null) {
                    if(input.version()!=0||!"DRAFT".equals(input.state())) { throw new CatalogException(400); }
                    p.addValue("store",stores.own(owner).id());
                    jdbc.update("INSERT INTO marketplace.catalog_product(id,store_id,category_id,slug,title,description,state) VALUES(:id,:store,:category,:slug,:title,:description,:state)",p);
                    jdbc.update("INSERT INTO marketplace.catalog_sku(id,product_id,code) VALUES(:id,:id,'sku-'||CAST(:id AS text))",p);
                } else {
                    current=jdbc.queryForObject("SELECT * FROM marketplace.catalog_product WHERE id=:id FOR UPDATE",p,this::map);
                    if(current.version()!=input.version()||!current.slug().equals(input.slug())||"ARCHIVED".equals(current.state())
                            ||!"DRAFT".equals(current.state())&&"DRAFT".equals(input.state())) { throw new CatalogException(409); }
                    jdbc.update("UPDATE marketplace.catalog_product SET category_id=:category,title=:title,description=:description,state=:state,version=version+1 WHERE id=:id",p);
                }
                return own(owner,(UUID)p.getValue("id"));
            });
        } catch(DuplicateKeyException conflict) { throw new CatalogException(409); }
    }
    @Override public PublicProduct published(String slug) {
        if(slug==null||!slug.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new CatalogException(404); }
        var rows=jdbc.query(PUBLIC+" AND p.slug=:slug",Map.of("slug",slug),this::publicMap);
        if(rows.isEmpty()) { throw new CatalogException(404); }
        return rows.getFirst();
    }
    @Override public Page<PublicProduct> published(int page) {
        CatalogValues.page(page);
        var rows=jdbc.query(PUBLIC+" ORDER BY p.id LIMIT 21 OFFSET :offset",Map.of("offset",page*20),this::publicMap);
        return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    private Product map(java.sql.ResultSet row,int index) throws java.sql.SQLException { return new Product(row.getObject("id",UUID.class),row.getObject("category_id",UUID.class),row.getString("slug"),row.getString("title"),row.getString("description"),row.getString("state"),row.getLong("version"),row.getBoolean("is_hidden_by_admin")); }
    private PublicProduct publicMap(java.sql.ResultSet row,int index) throws java.sql.SQLException { return new PublicProduct(row.getString("slug"),row.getString("title"),row.getString("description"),row.getObject("category_id",UUID.class),row.getString("store_slug")); }
}
