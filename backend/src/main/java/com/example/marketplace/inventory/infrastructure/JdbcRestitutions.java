package com.example.marketplace.inventory.infrastructure;

import com.example.marketplace.inventory.api.Restitutions;
import com.example.marketplace.inventory.api.InventoryException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @Profile("db")
public final class JdbcRestitutions implements Restitutions {
 private final NamedParameterJdbcTemplate jdbc;public JdbcRestitutions(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
 @Override public Receipt restoreCancellation(Request request) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(request==null||request.causeId()==null||request.checkoutId()==null||request.units()==null||request.units().isEmpty()||request.units().size()>2000||request.units().stream().anyMatch(u->u==null||u.reservationId()==null||u.itemId()==null||u.skuId()==null||u.ordinal()<0)) { throw new InventoryException(400); }
  var units=request.units().stream().sorted(Comparator.comparing((Unit u)->u.itemId().toString()).thenComparingInt(Unit::ordinal)).toList();
  if(units.stream().map(u->u.itemId()+":"+u.ordinal()).distinct().count()!=units.size()) { throw new InventoryException(400); }
  String fingerprint=hash(request.checkoutId()+"|"+units);
  // All inventory balances precede all reservation locks, matching checkout completion.
  for(UUID sku:units.stream().map(Unit::skuId).distinct().sorted(Comparator.comparing(UUID::toString)).toList()) { if(jdbc.queryForList("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:id FOR UPDATE",Map.of("id",sku)).size()!=1) { throw new InventoryException(409); } }
  for(UUID id:units.stream().map(Unit::reservationId).distinct().sorted(Comparator.comparing(UUID::toString)).toList()) { if(jdbc.queryForList("SELECT id FROM marketplace.inventory_reservation WHERE id=:id FOR UPDATE",Map.of("id",id)).size()!=1) { throw new InventoryException(409); } }
  var prior=jdbc.queryForList("SELECT fingerprint FROM marketplace.inventory_restitution WHERE cause_id=:id FOR UPDATE",Map.of("id",request.causeId()),String.class);
  if(!prior.isEmpty()) { if(!fingerprint.equals(prior.getFirst())) { throw new InventoryException(409); }return new Receipt(request.causeId(),request.checkoutId(),units); }
  for(var u:units) {
   var r=jdbc.queryForMap("SELECT * FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",u.reservationId()));
   if(!request.checkoutId().equals(r.get("checkout_id"))||!u.itemId().equals(r.get("order_item_id"))||!u.skuId().equals(r.get("sku_id"))||!r.get("status").equals("COMMITTED")||u.ordinal()>=((Number)r.get("quantity")).longValue()||jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_restitution_unit WHERE reservation_id=:id AND ordinal=:o",Map.of("id",u.reservationId(),"o",u.ordinal()),Integer.class)!=0) { throw new InventoryException(409); }
  }
  jdbc.update("INSERT INTO marketplace.inventory_restitution(cause_id,checkout_id,fingerprint) VALUES(:id,:checkout,:hash)",Map.of("id",request.causeId(),"checkout",request.checkoutId(),"hash",fingerprint));
  for(var u:units) { jdbc.update("INSERT INTO marketplace.inventory_restitution_unit(cause_id,reservation_id,item_id,ordinal,sku_id,checkout_id) VALUES(:cause,:r,:i,:o,:sku,:checkout)",Map.of("cause",request.causeId(),"r",u.reservationId(),"i",u.itemId(),"o",u.ordinal(),"sku",u.skuId(),"checkout",request.checkoutId())); }
  for(UUID sku:units.stream().map(Unit::skuId).distinct().sorted(Comparator.comparing(UUID::toString)).toList()) {
   long quantity=units.stream().filter(u->u.skuId().equals(sku)).count();var before=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku));long onHand=((Number)before.get("on_hand")).longValue(),version=((Number)before.get("version")).longValue();long after=Math.addExact(onHand,quantity);
   if(after>999999999||version>=9007199254740991L) { throw new InventoryException(409); }
   jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=:after,version=version+1 WHERE sku_id=:sku",Map.of("after",after,"sku",sku));
   jdbc.update("INSERT INTO marketplace.inventory_restitution_movement(cause_id,sku_id,quantity,before_on_hand,after_on_hand,reserved,before_version,version) VALUES(:cause,:sku,:q,:before,:after,:reserved,:v,:v+1)",Map.of("cause",request.causeId(),"sku",sku,"q",quantity,"before",onHand,"after",after,"reserved",before.get("reserved"),"v",version));
  }
  return new Receipt(request.causeId(),request.checkoutId(),units);
 }
 private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
