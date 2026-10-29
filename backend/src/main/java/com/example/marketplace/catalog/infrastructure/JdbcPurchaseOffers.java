package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.PurchaseOffers;
import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.seller.api.Stores;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @Profile("db")
public final class JdbcPurchaseOffers implements PurchaseOffers {
    private final NamedParameterJdbcTemplate jdbc;
    private final SellerMembership members;
    private final Stores stores;
    private final Browse browse;
    public JdbcPurchaseOffers(NamedParameterJdbcTemplate jdbc,SellerMembership members,Stores stores,Browse browse) {
        this.jdbc=jdbc;this.members=members;this.stores=stores;this.browse=browse;
    }
    @Override public void lock(UUID buyer,List<UUID> skus) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        if(skus==null||skus.isEmpty()||skus.size()>20||skus.stream().anyMatch(java.util.Objects::isNull)||skus.stream().distinct().count()!=skus.size()) { throw new CatalogException(400); }
        var sources=jdbc.queryForList("SELECT k.id,p.id product_id,s.owner_id FROM marketplace.catalog_sku k JOIN marketplace.catalog_product p ON p.id=k.product_id JOIN marketplace.seller_store s ON s.id=p.store_id WHERE k.id IN (:ids)",Map.of("ids",skus));
        if(sources.size()!=skus.size()) { throw new CatalogException(409); }
        var owners=new java.util.TreeSet<UUID>(java.util.Comparator.comparing(UUID::toString));
        sources.forEach(row->owners.add((UUID)row.get("owner_id")));
        var accounts=new java.util.TreeSet<UUID>(java.util.Comparator.comparing(UUID::toString));accounts.addAll(owners);if(buyer!=null) { accounts.add(buyer); }
        accounts.forEach(members::lockActiveBuyer);
        owners.forEach(owner->stores.requireCapability(owner,Stores.Capability.NEW_SALES));
        // Store locks stabilize product/category assignments and promotion writers; resolve categories afterwards.
        var products=sources.stream().map(row->(UUID)row.get("product_id")).distinct().toList();
        var categories=jdbc.queryForList("SELECT DISTINCT category_id FROM marketplace.catalog_product WHERE id IN (:ids)",Map.of("ids",products),UUID.class);
        lockRows("catalog_category",categories);lockRows("catalog_product",products);lockRows("catalog_sku",skus);
        for(UUID sku:skus) { if(browse.quoteOffer(sku)==null) { throw new CatalogException(409); } }
    }
    private void lockRows(String table,List<UUID> ids) {
        for(UUID id:ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
            jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",id),UUID.class);
        }
    }
}
