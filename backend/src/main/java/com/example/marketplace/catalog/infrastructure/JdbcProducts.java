package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.catalog.api.ProductDescription;
import com.example.marketplace.catalog.api.ProductDescriptionMedia;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.seller.api.Stores;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

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
    private final ObjectProvider<ProductDescriptionMedia> descriptionMedia;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private com.example.marketplace.identity.api.PurchaseSessions sessions;
    public JdbcProducts(NamedParameterJdbcTemplate jdbc,AccessPolicy access,Stores stores,PlatformTransactionManager manager) {
        this(jdbc,access,stores,manager,null,new ObjectMapper());
    }
    @Autowired public JdbcProducts(NamedParameterJdbcTemplate jdbc,AccessPolicy access,Stores stores,PlatformTransactionManager manager,
            ObjectProvider<ProductDescriptionMedia> descriptionMedia,ObjectMapper mapper) {
        this.jdbc=jdbc; this.access=access; this.stores=stores; tx=new TransactionTemplate(manager);
        this.descriptionMedia=descriptionMedia; this.mapper=mapper;
    }
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
        return save(owner,id,input,()->{ });
    }
    @Override public Product saveForSession(UUID owner,String browser,Long authVersion,UUID id,Input input) {
        return save(owner,id,input,()->{if(!sessions.lockCurrent(browser,owner,authVersion)) { throw new CatalogException(401); }});
    }
    private Product save(UUID owner,UUID id,Input input,Runnable currentAuthority) {
        access.require(owner,AccessArea.SELLER);
        if(input==null||input.categoryId()==null||input.state()==null||!java.util.Set.of("DRAFT","ACTIVE","STOPPED","ARCHIVED").contains(input.state())
                ||input.slug()==null||!input.slug().matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new CatalogException(400); }
        CatalogValues.version(input.version());
        var blocks=input.descriptionBlocks();
        var uploads=input.descriptionUploads();
        if(uploads==null || blocks==null && !uploads.isEmpty()) { throw new CatalogException(400); }
        String plain=blocks==null?CatalogValues.description(input.description()):DescriptionBlocks.validate(blocks,uploads);
        Long fee=null;if(input.returnFee()!=null) { try { if(!input.returnFee().matches("0|[1-9][0-9]*")) { throw new NumberFormatException(); }fee=Long.parseLong(input.returnFee()); }catch(NumberFormatException invalid) { throw new CatalogException(400); } }Long suppliedFee=fee;
        var p=new MapSqlParameterSource().addValue("owner",owner).addValue("id",id==null?UUID.randomUUID():id)
                .addValue("category",input.categoryId()).addValue("slug",input.slug()).addValue("title",CatalogValues.text(input.title(),120,true))
                .addValue("description",plain).addValue("state",input.state());
        try {
            return tx.execute(status->{
                stores.requireCapability(owner,Stores.Capability.MANAGE_CATALOG);
                access.require(owner,AccessArea.SELLER);
                currentAuthority.run();
                // Resolve ownership before category/state errors; never disclose a foreign product.
                Product current=id==null?null:own(owner,id);
                var categories=jdbc.queryForList("SELECT active FROM marketplace.catalog_category WHERE id=:category FOR UPDATE",p,Boolean.class);
                if(categories.isEmpty()) { throw new CatalogException(400); }
                if("ACTIVE".equals(input.state())&&!categories.getFirst()) { throw new CatalogException(409); }
                if(current==null) {
                    if(input.version()!=0||!"DRAFT".equals(input.state())) { throw new CatalogException(400); }
                    p.addValue("store",stores.own(owner).id());
                    p.addValue("returnFee",suppliedFee==null?3000L:suppliedFee).addValue("policySource",suppliedFee==null?"PRODUCT_DEFAULT_3000":"SELLER_PRODUCT");currentAuthority.run();stores.requireCapability(owner,Stores.Capability.MANAGE_CATALOG);
                    jdbc.update("INSERT INTO marketplace.catalog_product(id,store_id,category_id,slug,title,description,state,return_fee,return_policy_source) VALUES(:id,:store,:category,:slug,:title,:description,:state,:returnFee,:policySource)",p);
                    jdbc.update("INSERT INTO marketplace.catalog_sku(id,product_id,code) VALUES(:id,:id,'sku-'||CAST(:id AS text))",p);
                    if(blocks!=null) {
                        p.addValue("descriptionBlocks",savedDescription(owner,p,null,input));
                        jdbc.update("UPDATE marketplace.catalog_product SET description_blocks=CAST(:descriptionBlocks AS jsonb) WHERE id=:id",p);
                    }
                } else {
                    current=jdbc.queryForObject("SELECT * FROM marketplace.catalog_product WHERE id=:id FOR UPDATE",p,this::map);
                    if(current.version()!=input.version()||!current.slug().equals(input.slug())||"ARCHIVED".equals(current.state())
                            ||!"DRAFT".equals(current.state())&&"DRAFT".equals(input.state())) { throw new CatalogException(409); }
                    p.addValue("returnFee",suppliedFee==null?current.returnPolicy().checkedAmount():suppliedFee).addValue("policySource",suppliedFee==null?current.returnPolicy().source():"SELLER_PRODUCT").addValue("policyRevision",suppliedFee==null?current.returnPolicy().revision():Math.incrementExact(current.returnPolicy().revision()));currentAuthority.run();stores.requireCapability(owner,Stores.Capability.MANAGE_CATALOG);
                    String richUpdate="";
                    if(blocks!=null) {
                        p.addValue("descriptionBlocks",savedDescription(owner,p,current.descriptionBlocks(),input));
                        richUpdate="description_blocks=CAST(:descriptionBlocks AS jsonb),";
                    } else if(current.description().equals(input.description())) {
                        p.addValue("description",current.description());
                    }
                    jdbc.update("UPDATE marketplace.catalog_product SET category_id=:category,title=:title,description=:description,"+richUpdate+"state=:state,version=version+1,return_fee=:returnFee,return_policy_source=:policySource,return_policy_revision=:policyRevision WHERE id=:id",p);
                }
                // Account/store locks precede session locks; include any INSERT/unique-key wait in expiry checking.
                stores.requireCapability(owner,Stores.Capability.MANAGE_CATALOG);
                currentAuthority.run();
                return own(owner,(UUID)p.getValue("id"));
            });
        } catch(DuplicateKeyException conflict) { throw new CatalogException(409); }
    }
    private String savedDescription(UUID owner,MapSqlParameterSource params,List<ProductDescription.Block> prior,Input input) {
        var blocks=input.descriptionBlocks();
        List<ProductDescription.Block> resolved=blocks;
        if(hasImages(prior) || hasImages(blocks)) {
            ProductDescriptionMedia media=descriptionMedia==null?null:descriptionMedia.getIfAvailable();
            if(media==null) { throw new CatalogException(409); }
            resolved=media.reconcile(owner,(UUID)params.getValue("id"),blocks,input.descriptionUploads());
        }
        return mapper.writeValueAsString(resolved);
    }
    private static boolean hasImages(List<ProductDescription.Block> blocks) {
        return blocks!=null && blocks.stream().anyMatch(block->"image".equals(block.kind()));
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
    static com.example.marketplace.catalog.api.ReturnPolicy policy(java.sql.ResultSet row) throws java.sql.SQLException { return com.example.marketplace.catalog.api.ReturnPolicy.product(row.getObject("id",UUID.class),row.getLong("return_policy_revision"),row.getString("return_policy_source"),row.getLong("return_fee")); }
    private Product map(java.sql.ResultSet row,int index) throws java.sql.SQLException { return new Product(row.getObject("id",UUID.class),row.getObject("category_id",UUID.class),row.getString("slug"),row.getString("title"),row.getString("description"),row.getString("state"),row.getLong("version"),row.getBoolean("is_hidden_by_admin"),policy(row),DescriptionBlocks.read(mapper,row)); }
    private PublicProduct publicMap(java.sql.ResultSet row,int index) throws java.sql.SQLException { return new PublicProduct(row.getString("slug"),row.getString("title"),row.getString("description"),row.getObject("category_id",UUID.class),row.getString("store_slug"),policy(row)); }
}
