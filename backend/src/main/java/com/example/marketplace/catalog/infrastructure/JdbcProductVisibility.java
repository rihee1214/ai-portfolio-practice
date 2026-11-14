package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.catalog.api.ProductVisibility;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public class JdbcProductVisibility implements ProductVisibility {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcProductVisibility(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public State lock(UUID id){
        var rows=jdbc.query("SELECT version,is_hidden_by_admin FROM marketplace.catalog_product WHERE id=:id FOR UPDATE",
                Map.of("id",id),(r,n)->new State(r.getLong(1),r.getBoolean(2)));
        if(rows.isEmpty()){throw new CatalogException(404);}
        return rows.getFirst();
    }
    @Override public UUID storeId(UUID product) {
        return jdbc.queryForObject("SELECT store_id FROM marketplace.catalog_product WHERE id=:id",Map.of("id",product),UUID.class);
    }
    public void change(UUID id,boolean hidden,long version){
        if(jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=:hidden,version=version+1 WHERE id=:id AND version=:version",
                Map.of("id",id,"hidden",hidden,"version",version))!=1){throw new CatalogException(409);}
    }
}
