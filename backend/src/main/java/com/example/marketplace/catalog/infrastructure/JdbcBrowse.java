package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.Skus;
import com.example.marketplace.catalog.api.CatalogException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.ObjectMapper;

@Service @Profile("db")
public final class JdbcBrowse implements Browse {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcBrowse(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    private static final String PROJECTION = "WITH visible AS ("+JdbcProducts.PUBLIC+") " + """
        SELECT v.*,c.name category_name,s.name store_name,prices.min_price,
        NOT coalesce(prices.in_stock,false) sold_out
        FROM visible v JOIN marketplace.catalog_category c ON c.id=v.category_id
        JOIN marketplace.seller_store s ON s.id=v.store_id
        LEFT JOIN LATERAL (SELECT min(k.price) min_price,
            bool_or(coalesce(b.on_hand-b.reserved,0)>0) in_stock
            FROM marketplace.catalog_sku k LEFT JOIN marketplace.inventory_balance b ON b.sku_id=k.id
            WHERE k.product_id=v.id AND k.active) prices ON true
        """;
    @Override public Products.Page<Item> search(Query q) {
        if(q==null||q.page()<0||q.page()>10000||q.sort()==null||!Set.of("newest","price_asc","price_desc","name").contains(q.sort())
            ||q.minPrice()!=null&&(q.minPrice()<0||q.minPrice()>999999999)
            ||q.maxPrice()!=null&&(q.maxPrice()<0||q.maxPrice()>999999999)
            ||q.minPrice()!=null&&q.maxPrice()!=null&&q.minPrice()>q.maxPrice()
            ||q.store()!=null&&!q.store().matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new CatalogException(400); }
        String text=java.text.Normalizer.normalize(q.q()==null?"":q.q(),java.text.Normalizer.Form.NFC);
        if(text.codePointCount(0,text.length())>80||text.codePoints().anyMatch(Character::isISOControl)) { throw new CatalogException(400); }
        text=text.strip();
        var args=new MapSqlParameterSource().addValue("q","%"+text.replace("!","!!").replace("%","!%").replace("_","!_")+"%")
            .addValue("offset",q.page()*20);
        var sql=new StringBuilder(PROJECTION).append(" WHERE v.title ILIKE :q ESCAPE '!'");
        if(q.category()!=null) { sql.append(" AND v.category_id=:category"); args.addValue("category",q.category()); }
        if(q.store()!=null) { sql.append(" AND v.store_slug=:store"); args.addValue("store",q.store()); }
        if(q.minPrice()!=null) { sql.append(" AND prices.min_price>=:min"); args.addValue("min",q.minPrice()); }
        if(q.maxPrice()!=null) { sql.append(" AND prices.min_price<=:max"); args.addValue("max",q.maxPrice()); }
        if(q.inStock()) { sql.append(" AND prices.in_stock"); }
        String order=switch(q.sort()) {
            case "price_asc" -> "min_price ASC NULLS LAST";
            case "price_desc" -> "min_price DESC NULLS LAST";
            case "name" -> "title ASC";
            default -> "created_at DESC";
        }+",id";
        sql.append(" ORDER BY ").append(order).append(" LIMIT 21 OFFSET :offset");
        // Project one current product asset per visible page row, in the same snapshot as the page.
        String withThumbnails="""
            SELECT page.slug,page.title,page.description,page.category_id,page.category_name,page.store_slug,page.store_name,
                page.min_price,page.sold_out,page.id,page.return_policy_revision,page.return_policy_source,page.return_fee,
                photo.asset_id,photo.alt FROM (%s) page
            LEFT JOIN LATERAL (
                SELECT a.id asset_id,m.alt FROM marketplace.media_slot m
                JOIN marketplace.media_asset a ON a.id=m.asset_id AND a.retired_at IS NULL
                WHERE m.kind='products' AND m.target_id=page.id
                ORDER BY m.slot LIMIT 1
            ) photo ON true
            ORDER BY %s
            """.formatted(sql,order);
        var rows=jdbc.query(withThumbnails,args,(r,n)-> {
            UUID asset=r.getObject("asset_id",UUID.class);
            return item(r,asset==null?null:new Thumbnail(asset,r.getString("alt")));
        });
        return new Products.Page<>(rows.stream().limit(20).toList(),q.page(),rows.size()>20);
    }
    @Override public Detail detail(String slug) {
        return detail(slug,true);
    }
    private Detail detail(String slug,boolean includeDescription) {
        if(slug==null||!slug.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new CatalogException(404); }
        // Item eligibility and offers are evaluated within one statement snapshot.
        String productColumns=includeDescription?"p.*":"""
            p.slug,p.title,p.description,p.category_id,p.category_name,p.store_slug,p.store_name,
            p.min_price,p.sold_out,p.id,p.return_policy_revision,p.return_policy_source,p.return_fee
            """;
        var rows=jdbc.query("SELECT "+productColumns+",k.id offer_id,k.options,k.price,coalesce(b.on_hand-b.reserved,0) available FROM ("+PROJECTION+") p "
            +"LEFT JOIN marketplace.catalog_sku k ON k.product_id=p.id AND k.active LEFT JOIN marketplace.inventory_balance b ON b.sku_id=k.id WHERE p.slug=:slug ORDER BY k.id",
            Map.of("slug",slug),(r,n)-> {
                Offer offer=null;
                if(r.getObject("offer_id")!=null) {
                    try { offer=new Offer(r.getObject("offer_id",UUID.class),mapper.readValue(r.getString("options"),new tools.jackson.core.type.TypeReference<List<Skus.Option>>() {}),r.getLong("price"),r.getLong("available")); }
                    catch(tools.jackson.core.JacksonException e) { throw new java.sql.SQLException("Invalid persisted options",e); }
                }
                return new Detail(item(r,null),offer==null?List.of():List.of(offer),includeDescription&&n==0?DescriptionBlocks.read(mapper,r):null);
            });
        if(rows.isEmpty()) { throw new CatalogException(404); }
        return new Detail(rows.getFirst().product(),rows.stream().flatMap(r->r.offers().stream()).toList(),rows.getFirst().descriptionBlocks());
    }
    @Override public QuoteOffer quoteOffer(UUID sku) {
        if(sku==null) { return null; }
        var rows=jdbc.query("SELECT v.id product_id,v.store_id,v.store_slug,v.title,v.version product_version,v.return_fee,v.return_policy_revision,v.return_policy_source,k.id sku_id,k.options,k.price,k.version sku_version,coalesce(b.on_hand-b.reserved,0) available,s.name store_name,s.version store_version FROM ("+JdbcProducts.PUBLIC+") v JOIN marketplace.catalog_sku k ON k.product_id=v.id AND k.active JOIN marketplace.seller_store s ON s.id=v.store_id LEFT JOIN marketplace.inventory_balance b ON b.sku_id=k.id WHERE k.id=:id",Map.of("id",sku),(r,n)-> {
            try { return new QuoteOffer(r.getObject("sku_id",UUID.class),r.getObject("product_id",UUID.class),r.getObject("store_id",UUID.class),r.getString("store_slug"),r.getString("store_name"),r.getString("title"),mapper.readValue(r.getString("options"),new tools.jackson.core.type.TypeReference<List<Skus.Option>>() {}),r.getLong("price"),r.getLong("available"),r.getLong("product_version"),r.getLong("sku_version"),r.getLong("store_version"),com.example.marketplace.catalog.api.ReturnPolicy.product(r.getObject("product_id",UUID.class),r.getLong("return_policy_revision"),r.getString("return_policy_source"),r.getLong("return_fee"))); }
            catch(tools.jackson.core.JacksonException ex) { throw new java.sql.SQLException("Invalid persisted options",ex); }
        });return rows.isEmpty()?null:rows.getFirst();
    }
    @Override public Detail forSku(UUID sku) {
        var slugs=jdbc.queryForList("SELECT p.slug FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku),String.class);
        if(slugs.isEmpty()) { return null; }
        try {
            var value=detail(slugs.getFirst(),false);
            var offers=value.offers().stream().filter(o->o.id().equals(sku)).toList();
            return offers.isEmpty()?null:new Detail(value.product(),offers,value.descriptionBlocks());
        } catch(CatalogException e) { if(e.status()==404) { return null; } throw e; }
    }
    @Override public Detail forProduct(UUID product) {
        var slugs=jdbc.queryForList("SELECT slug FROM marketplace.catalog_product WHERE id=:id",Map.of("id",product),String.class);
        if(slugs.isEmpty()) { return null; }
        try { return detail(slugs.getFirst(),false); }
        catch(CatalogException e) { if(e.status()==404) { return null; } throw e; }
    }
    @Override public UUID identify(String slug) {
        if(slug==null||!slug.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { return null; }
        try { detail(slug,false); }
        catch(CatalogException e) { if(e.status()==404) { return null; } throw e; }
        var ids=jdbc.queryForList("SELECT id FROM marketplace.catalog_product WHERE slug=:slug",Map.of("slug",slug),UUID.class);
        return ids.isEmpty()?null:ids.getFirst();
    }
    private Item item(java.sql.ResultSet r,Thumbnail thumbnail) throws java.sql.SQLException {
        return new Item(r.getString("slug"),r.getString("title"),r.getString("description"),r.getObject("category_id",UUID.class),r.getString("category_name"),r.getString("store_slug"),r.getString("store_name"),(Long)r.getObject("min_price"),r.getBoolean("sold_out"),JdbcProducts.policy(r),thumbnail);
    }
}
