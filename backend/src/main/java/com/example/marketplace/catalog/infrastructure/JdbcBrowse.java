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
        sql.append(" ORDER BY ").append(switch(q.sort()) {
            case "price_asc" -> "prices.min_price ASC NULLS LAST";
            case "price_desc" -> "prices.min_price DESC NULLS LAST";
            case "name" -> "v.title ASC";
            default -> "v.created_at DESC";
        }).append(",v.id LIMIT 21 OFFSET :offset");
        var rows=jdbc.query(sql.toString(),args,(r,n)->item(r));
        return new Products.Page<>(rows.stream().limit(20).toList(),q.page(),rows.size()>20);
    }
    @Override public Detail detail(String slug) {
        if(slug==null||!slug.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) { throw new CatalogException(404); }
        // Item eligibility and offers are evaluated within one statement snapshot.
        var rows=jdbc.query("SELECT p.*,k.id offer_id,k.options,k.price,coalesce(b.on_hand-b.reserved,0) available FROM ("+PROJECTION+") p "
            +"LEFT JOIN marketplace.catalog_sku k ON k.product_id=p.id AND k.active LEFT JOIN marketplace.inventory_balance b ON b.sku_id=k.id WHERE p.slug=:slug ORDER BY k.id",
            Map.of("slug",slug),(r,n)-> {
                Offer offer=null;
                if(r.getObject("offer_id")!=null) {
                    try { offer=new Offer(r.getObject("offer_id",UUID.class),mapper.readValue(r.getString("options"),new tools.jackson.core.type.TypeReference<List<Skus.Option>>() {}),r.getLong("price"),r.getLong("available")); }
                    catch(tools.jackson.core.JacksonException e) { throw new java.sql.SQLException("Invalid persisted options",e); }
                }
                return new Detail(item(r),offer==null?List.of():List.of(offer));
            });
        if(rows.isEmpty()) { throw new CatalogException(404); }
        return new Detail(rows.getFirst().product(),rows.stream().flatMap(r->r.offers().stream()).toList());
    }
    private Item item(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Item(r.getString("slug"),r.getString("title"),r.getString("description"),r.getObject("category_id",UUID.class),r.getString("category_name"),r.getString("store_slug"),r.getString("store_name"),(Long)r.getObject("min_price"),r.getBoolean("sold_out"));
    }
}
