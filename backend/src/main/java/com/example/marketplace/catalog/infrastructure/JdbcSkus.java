package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.Skus;
import com.example.marketplace.catalog.api.CatalogException;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Service @Profile("db")
public final class JdbcSkus implements Skus {
    private final NamedParameterJdbcTemplate jdbc;
    private final Products products;
    private final com.example.marketplace.identity.api.AccessPolicy access;
    private final com.example.marketplace.seller.api.Stores stores;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
    public JdbcSkus(NamedParameterJdbcTemplate jdbc,Products products,com.example.marketplace.seller.api.Stores stores,org.springframework.transaction.PlatformTransactionManager manager,com.example.marketplace.identity.api.AccessPolicy access) { this.access=access; this.jdbc=jdbc; this.products=products; this.stores=stores; tx=new org.springframework.transaction.support.TransactionTemplate(manager); }
    @Override public Sku save(UUID owner,UUID product,UUID id,Input input) {
        products.own(owner,product);
        if(input==null||input.price()==null||input.price()<0||input.price()>999999999||input.active()==null) { throw new CatalogException(400); }
        CatalogValues.version(input.version());
        var options=normalize(input.options());
        var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("product",product).addValue("id",id==null?UUID.randomUUID():id)
                .addValue("options",mapper.writeValueAsString(options)).addValue("price",input.price()).addValue("active",input.active());
        try {
            return tx.execute(status->{
                stores.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.MANAGE_CATALOG);
                products.own(owner,product);
                var state=jdbc.queryForObject("SELECT state FROM marketplace.catalog_product WHERE id=:product FOR UPDATE",p,String.class);
                if("ARCHIVED".equals(state)) { throw new CatalogException(409); }
                if(id==null) {
                    if(input.version()!=0) { throw new CatalogException(400); }
                    if(options.isEmpty()||jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_sku WHERE product_id=:product",p,Integer.class)>=100) { throw new CatalogException(409); }
                    jdbc.update("UPDATE marketplace.catalog_sku SET active=false,version=version+1 WHERE product_id=:product AND options='[]' AND active",p);
                    p.addValue("code","sku-"+p.getValue("id"));
                    jdbc.update("INSERT INTO marketplace.catalog_sku(id,product_id,code,options,price,active) VALUES(:id,:product,:code,CAST(:options AS jsonb),:price,:active)",p);
                } else {
                    var rows=jdbc.query("SELECT * FROM marketplace.catalog_sku WHERE product_id=:product AND id=:id",p,this::map);
                    if(rows.isEmpty()) { throw new CatalogException(404); }
                    var current=rows.getFirst();
                    if(current.version()!=input.version()||!current.options().equals(options)) { throw new CatalogException(409); }
                    if(options.isEmpty()&&input.active()&&jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_sku WHERE product_id=:product AND options<>'[]'",p,Integer.class)>0) { throw new CatalogException(409); }
                    jdbc.update("UPDATE marketplace.catalog_sku SET price=:price,active=:active,version=version+1 WHERE id=:id",p);
                }
                return jdbc.queryForObject("SELECT * FROM marketplace.catalog_sku WHERE id=:id",p,this::map);
            });
        } catch(org.springframework.dao.DuplicateKeyException conflict) { throw new CatalogException(409); }
    }
    @Override public void inventoryAccess(UUID actor,UUID product,UUID sku,boolean administrator,boolean write) {
        if(administrator) { access.require(actor,com.example.marketplace.identity.api.AccessArea.CONTENT); }
        else { products.own(actor,product); }
        var owners=jdbc.queryForList("SELECT s.owner_id FROM marketplace.catalog_product p JOIN marketplace.seller_store s ON s.id=p.store_id WHERE p.id=:product",Map.of("product",product),UUID.class);
        if(owners.isEmpty()) { throw new CatalogException(404); }
        if(sku!=null&&jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_sku WHERE product_id=:product AND id=:sku",Map.of("product",product,"sku",sku),Integer.class)==0) { throw new CatalogException(404); }
        if(write) {
            products.lockForMedia(owners.getFirst(),product);
            if(administrator) { access.require(actor,com.example.marketplace.identity.api.AccessArea.CONTENT); }
        }
    }
    private java.util.List<Option> normalize(java.util.List<Option> options) {
        if(options==null||options.size()>3) { throw new CatalogException(400); }
        var sorted=new java.util.TreeMap<String,String>();
        for(var option:options) {
            if(option==null||option.name()==null||option.value()==null) { throw new CatalogException(400); }
            String name=CatalogValues.text(java.text.Normalizer.normalize(option.name(),java.text.Normalizer.Form.NFC),40,true);
            String value=CatalogValues.text(java.text.Normalizer.normalize(option.value(),java.text.Normalizer.Form.NFC),40,true);
            if(sorted.put(name,value)!=null) { throw new CatalogException(400); }
        }
        return sorted.entrySet().stream().map(e->new Option(e.getKey(),e.getValue())).toList();
    }
    @Override public Products.Page<Sku> list(UUID owner,UUID product,int page) {
        products.own(owner,product); CatalogValues.page(page);
        var rows=jdbc.query("SELECT * FROM marketplace.catalog_sku WHERE product_id=:product ORDER BY id LIMIT 21 OFFSET :offset",Map.of("product",product,"offset",page*20),this::map);
        return new Products.Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Products.Page<Sku> published(String slug,int page) {
        CatalogValues.page(page);
        var rows=jdbc.query("SELECT k.* FROM ("+JdbcProducts.PUBLIC+" AND p.slug=:slug) p LEFT JOIN LATERAL (SELECT * FROM marketplace.catalog_sku WHERE product_id=p.id AND active ORDER BY id LIMIT 21 OFFSET :offset) k ON true ORDER BY k.id",Map.of("slug",slug,"offset",page*20),
                (row,index)->row.getObject("id")==null?null:map(row,index));
        if(rows.isEmpty()) { throw new CatalogException(404); }
        return new Products.Page<>(rows.stream().filter(java.util.Objects::nonNull).limit(20).toList(),page,rows.size()>20);
    }
    private Sku map(java.sql.ResultSet row,int index) throws java.sql.SQLException {
        try { return new Sku(row.getObject("id",UUID.class),row.getString("code"),mapper.readValue(row.getString("options"),new tools.jackson.core.type.TypeReference<java.util.List<Option>>() { }),row.getLong("price"),row.getBoolean("active"),row.getLong("version")); }
        catch(tools.jackson.core.JacksonException invalid) { throw new java.sql.SQLException("Invalid persisted SKU options",invalid); }
    }
}
