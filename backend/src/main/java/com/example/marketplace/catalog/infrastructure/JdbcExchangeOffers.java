package com.example.marketplace.catalog.infrastructure;
import com.example.marketplace.catalog.api.ExchangeOffers;
import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.seller.api.Stores;
import com.example.marketplace.identity.api.SellerMembership;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service @Profile("db")
public final class JdbcExchangeOffers implements ExchangeOffers {
 private final NamedParameterJdbcTemplate jdbc;private final Browse browse;private final Stores stores;private final SellerMembership members;
 public JdbcExchangeOffers(NamedParameterJdbcTemplate jdbc,Browse browse,Stores stores,SellerMembership members) { this.jdbc=jdbc;this.browse=browse;this.stores=stores;this.members=members; }
 @Override public Offer published(UUID store,UUID sku) {
  if(store==null||sku==null) { throw new CatalogException(404); }var q=browse.quoteOffer(sku);
  if(q==null||!q.storeId().equals(store)) { throw new CatalogException(409); }
  return new Offer(q.skuId(),q.productId(),q.storeId(),q.title(),q.options(),Long.toString(q.price()),q.productVersion(),q.skuVersion());
 }
 @Override public List<Offer> candidates(UUID store,UUID after,int size) {
  if(store==null||size<1||size>50) { throw new CatalogException(400); }
  var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("store",store).addValue("after",after).addValue("size",size);
  var ids=jdbc.queryForList("SELECT k.id FROM ("+JdbcProducts.PUBLIC+") p JOIN marketplace.catalog_sku k ON k.product_id=p.id AND k.active WHERE p.store_id=:store AND (CAST(:after AS uuid) IS NULL OR k.id>CAST(:after AS uuid)) ORDER BY k.id LIMIT :size",p,UUID.class);
  return ids.stream().map(id->published(store,id)).toList();
 }
 @Override public List<Offer> lockOpen(UUID seller,UUID store,List<UUID> input) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(seller==null||input==null||input.isEmpty()||input.stream().anyMatch(java.util.Objects::isNull)) { throw new CatalogException(400); }
  var ids=input.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
  members.lockActiveBuyer(seller);stores.requireCapability(seller,Stores.Capability.NEW_SALES);
  var own=stores.own(seller);if(!own.id().equals(store)) { throw new CatalogException(404); }
  return lockPublished(store,ids);
 }
 @Override public List<Offer> lockPublished(UUID store,List<UUID> input) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(store==null||input==null||input.isEmpty()||input.stream().anyMatch(java.util.Objects::isNull)) { throw new CatalogException(400); }var ids=input.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
  var trade=stores.lockExistingTrade(store);if(!trade.state().equals("OPEN")||!trade.visible()) { throw new CatalogException(409); }
  var products=jdbc.queryForList("SELECT DISTINCT p.id FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE p.store_id=:store AND k.id IN (:ids)",Map.of("store",store,"ids",ids),UUID.class);
  if(products.isEmpty()) { throw new CatalogException(409); }
  var categories=jdbc.queryForList("SELECT DISTINCT category_id FROM marketplace.catalog_product WHERE id IN (:ids)",Map.of("ids",products),UUID.class);
  lock("catalog_category",categories);lock("catalog_product",products);lock("catalog_sku",ids);
  return ids.stream().map(id->published(store,id)).toList();
 }
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 private static boolean tradeState(Stores.Store store) { return store.state().equals("OPEN")?store.visible():List.of("SUSPENDED","CLOSED").contains(store.state()); }
 @Override public Offer existingPublished(UUID store,UUID sku) {
  if(store==null||sku==null){throw new CatalogException(404);}if(!tradeState(stores.existingTrade(store))){throw new CatalogException(409);}var rows=jdbc.query("SELECT p.id,p.store_id,p.title,p.version AS product_version,k.id AS sku_id,k.options,k.price,k.version AS sku_version FROM marketplace.catalog_product p JOIN marketplace.catalog_category c ON c.id=p.category_id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE p.store_id=:store AND k.id=:sku AND p.state='ACTIVE' AND NOT p.is_hidden_by_admin AND c.active AND k.active",Map.of("store",store,"sku",sku),(r,n)->{
   try{return new Offer(r.getObject("sku_id",UUID.class),r.getObject("id",UUID.class),r.getObject("store_id",UUID.class),r.getString("title"),mapper.readValue(r.getString("options"),new tools.jackson.core.type.TypeReference<List<com.example.marketplace.catalog.api.Skus.Option>>() {}),Long.toString(r.getLong("price")),r.getLong("product_version"),r.getLong("sku_version"));}catch(tools.jackson.core.JacksonException invalid){throw new java.sql.SQLException("Invalid actual exchange options",invalid);}
  });if(rows.size()!=1){throw new CatalogException(409);}return rows.getFirst();
 }
 @Override public List<Offer> existingCandidates(UUID store,UUID after,int size) {
  if(store==null||size<1||size>50){throw new CatalogException(400);}try{if(!tradeState(stores.existingTrade(store))){return List.of();}}catch(com.example.marketplace.seller.api.StoreException unavailable){return List.of();}
  var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("store",store).addValue("after",after).addValue("size",size);var ids=jdbc.queryForList("SELECT k.id FROM marketplace.catalog_product p JOIN marketplace.catalog_category c ON c.id=p.category_id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE p.store_id=:store AND p.state='ACTIVE' AND NOT p.is_hidden_by_admin AND c.active AND k.active AND(CAST(:after AS uuid) IS NULL OR k.id>CAST(:after AS uuid)) ORDER BY k.id LIMIT :size",p,UUID.class);return ids.stream().map(id->existingPublished(store,id)).toList();
 }
 @Override public TradeTargets lockExistingForSeller(UUID seller,UUID store,List<UUID> ids) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()){throw new IllegalStateException("TRANSACTION_REQUIRED");}if(seller==null){throw new CatalogException(400);}members.lockActiveBuyer(seller);stores.requireCapability(seller,Stores.Capability.EXISTING_TRADES);var own=stores.own(seller);if(own==null||!own.id().equals(store)){throw new CatalogException(404);}return lockExistingTrade(store,ids);
 }
 @Override public TradeTargets lockExistingTrade(UUID store,List<UUID> input) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()){throw new IllegalStateException("TRANSACTION_REQUIRED");}if(store==null||input==null||input.isEmpty()||input.stream().anyMatch(java.util.Objects::isNull)){throw new CatalogException(400);}var ids=input.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList();var trade=stores.lockExistingTrade(store);if(!tradeState(trade)){throw new CatalogException(409);}var products=jdbc.queryForList("SELECT DISTINCT p.id FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE p.store_id=:store AND k.id IN(:ids)",Map.of("store",store,"ids",ids),UUID.class);if(products.isEmpty()){throw new CatalogException(409);}var categories=jdbc.queryForList("SELECT DISTINCT category_id FROM marketplace.catalog_product WHERE id IN(:ids)",Map.of("ids",products),UUID.class);lock("catalog_category",categories);lock("catalog_product",products);lock("catalog_sku",ids);var values=ids.stream().map(id->existingPublished(store,id)).toList();return new TradeTargets(store,trade.state(),trade.visible(),values);
 }
 private void lock(String table,List<UUID> ids) { for(var id:ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) { if(jdbc.queryForList("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",id),UUID.class).size()!=1) { throw new CatalogException(409); } } }
}
