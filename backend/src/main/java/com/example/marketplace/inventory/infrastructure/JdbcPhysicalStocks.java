package com.example.marketplace.inventory.infrastructure;
import com.example.marketplace.inventory.api.PhysicalStocks;
import com.example.marketplace.inventory.api.InventoryException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service @Profile("db")
public final class JdbcPhysicalStocks implements PhysicalStocks {
 private final NamedParameterJdbcTemplate jdbc;public JdbcPhysicalStocks(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
 private static void transaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); } }
 @Override public Source purchased(UUID checkout,UUID item,int ordinal,UUID sku) {
  transaction();if(checkout==null||item==null||sku==null||ordinal<0) { throw new InventoryException(400); }
  var ids=jdbc.queryForList("SELECT id FROM marketplace.inventory_reservation WHERE checkout_id=:checkout AND order_item_id=:item AND sku_id=:sku AND status='COMMITTED' AND quantity>:ordinal",Map.of("checkout",checkout,"item",item,"sku",sku,"ordinal",ordinal),UUID.class);if(ids.size()!=1) { throw new InventoryException(409); }
  var id=jdbc.queryForObject("SELECT marketplace.inventory_ensure_purchase_source(:id,:ordinal)",Map.of("id",ids.getFirst(),"ordinal",ordinal),UUID.class);return source(id);
 }
 @Override public Source exchanged(UUID reservation) {
  transaction();var rows=jdbc.queryForList("SELECT * FROM marketplace.inventory_exchange_reservation WHERE id=:id",Map.of("id",reservation));if(rows.size()!=1||!rows.getFirst().get("status").equals("COMMITTED")) { throw new InventoryException(409); }var r=rows.getFirst();
  UUID id=UUID.nameUUIDFromBytes(("inventory/EXCHANGE/"+reservation+"/0").getBytes(StandardCharsets.UTF_8));
  jdbc.update("INSERT INTO marketplace.inventory_committed_unit(id,kind,checkout_id,item_id,ordinal,sku_id) VALUES(:id,'EXCHANGE',:checkout,:item,:ordinal,:sku) ON CONFLICT DO NOTHING",Map.of("id",id,"checkout",r.get("checkout_id"),"item",r.get("item_id"),"ordinal",r.get("ordinal"),"sku",r.get("target_sku_id")));
  jdbc.update("INSERT INTO marketplace.inventory_exchange_source(source_id,reservation_id) VALUES(:id,:reservation) ON CONFLICT DO NOTHING",Map.of("id",id,"reservation",reservation));return source(id);
 }
 @Override public Source source(UUID id) {
  transaction();if(id==null) { throw new InventoryException(400); }
  var rows=jdbc.queryForList("SELECT s.*,p.reservation_id purchase_id,p.reservation_unit purchase_unit,e.reservation_id exchange_id,e.reservation_unit exchange_unit FROM marketplace.inventory_committed_unit s LEFT JOIN marketplace.inventory_purchase_source p ON p.source_id=s.id LEFT JOIN marketplace.inventory_exchange_source e ON e.source_id=s.id WHERE s.id=:id",Map.of("id",id));if(rows.size()!=1) { throw new InventoryException(409); }var s=rows.getFirst();var kind=Kind.valueOf(s.get("kind").toString());UUID reservation=(UUID)s.get(kind==Kind.PURCHASE?"purchase_id":"exchange_id");var unit=(Number)s.get(kind==Kind.PURCHASE?"purchase_unit":"exchange_unit");
  if(reservation==null||unit==null||s.get(kind==Kind.PURCHASE?"exchange_id":"purchase_id")!=null) { throw new InventoryException(409); }
  var result=new Source(id,kind,reservation,unit.intValue(),(UUID)s.get("checkout_id"),(UUID)s.get("item_id"),((Number)s.get("ordinal")).intValue(),(UUID)s.get("sku_id"));
  if(kind==Kind.PURCHASE) { var r=jdbc.queryForMap("SELECT * FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",reservation));if(!r.get("status").equals("COMMITTED")||!r.get("checkout_id").equals(result.checkoutId())||!r.get("order_item_id").equals(result.itemId())||!r.get("sku_id").equals(result.skuId())||unit.intValue()!=result.ordinal()||unit.intValue()<0||unit.longValue()>=((Number)r.get("quantity")).longValue()) { throw new InventoryException(409); } }
  else { var r=jdbc.queryForMap("SELECT * FROM marketplace.inventory_exchange_reservation WHERE id=:id",Map.of("id",reservation));if(!r.get("status").equals("COMMITTED")||unit.intValue()!=0||!r.get("checkout_id").equals(result.checkoutId())||!r.get("item_id").equals(result.itemId())||!r.get("target_sku_id").equals(result.skuId())||((Number)r.get("ordinal")).intValue()!=result.ordinal()) { throw new InventoryException(409); } }
  return result;
 }
 @Override public void lockBalances(List<UUID> skus) { transaction();if(skus==null||skus.isEmpty()||skus.stream().anyMatch(java.util.Objects::isNull)) { throw new InventoryException(400); }for(var id:skus.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) { if(jdbc.queryForList("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:id FOR UPDATE",Map.of("id",id)).size()!=1) { throw new InventoryException(409); } } }
 @Override public Receipt restore(Inbound input) {
  transaction();if(input==null||input.inspectionId()==null||input.claimId()==null||input.checkoutId()==null||input.purpose()==null||input.units().isEmpty()||input.units().size()>1980||input.units().stream().anyMatch(u->u==null||u.sourceId()==null||u.physicalId()==null)||input.units().stream().map(Unit::sourceId).distinct().count()!=input.units().size()||input.units().stream().map(Unit::physicalId).distinct().count()!=input.units().size()) { throw new InventoryException(400); }
  var units=input.units().stream().sorted(java.util.Comparator.comparing(u->u.sourceId().toString())).toList();var sources=units.stream().map(u->source(u.sourceId())).toList();lockBalances(sources.stream().map(Source::skuId).toList());
  String fingerprint=hash(input.claimId()+"|"+input.checkoutId()+"|"+input.purpose()+"|"+units);var previous=jdbc.queryForList("SELECT fingerprint,kind FROM marketplace.inventory_restitution WHERE cause_id=:id FOR UPDATE",Map.of("id",input.inspectionId()));
  if(!previous.isEmpty()) { if(!fingerprint.equals(previous.getFirst().get("fingerprint"))||!input.purpose().name().equals(previous.getFirst().get("kind"))) { throw new InventoryException(409); }return new Receipt(input.inspectionId(),input.claimId(),input.purpose(),units); }
  for(var s:sources) { if(!input.checkoutId().equals(s.checkoutId())||jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_source_restitution WHERE source_id=:id",Map.of("id",s.id()),Integer.class)!=0) { throw new InventoryException(409); } }
  var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",input.inspectionId()).addValue("claim",input.claimId()).addValue("checkout",input.checkoutId()).addValue("kind",input.purpose().name()).addValue("hash",fingerprint);
  jdbc.update("INSERT INTO marketplace.inventory_restitution(cause_id,checkout_id,fingerprint,kind) VALUES(:id,:checkout,:hash,:kind)",p);
  jdbc.update("INSERT INTO marketplace.inventory_physical_inbound(inspection_id,cause_id,claim_id,checkout_id,kind) VALUES(:id,:id,:claim,:checkout,:kind)",p);
  if(input.purpose()==Purpose.RETURN) { jdbc.update("INSERT INTO marketplace.inventory_return_physical_restitution(inspection_id,return_id,checkout_id,kind) VALUES(:id,:claim,:checkout,'RETURN')",p); }
  for(int index=0;index<units.size();index++) { var u=units.get(index);var s=sources.get(index);p.addValue("source",s.id()).addValue("physical",u.physicalId()).addValue("reservation",s.reservationId()).addValue("item",s.itemId()).addValue("ordinal",s.reservationUnit()).addValue("sku",s.skuId());
   if(s.kind()==Kind.PURCHASE) { jdbc.update("INSERT INTO marketplace.inventory_restitution_unit(cause_id,reservation_id,item_id,ordinal,sku_id,checkout_id) VALUES(:id,:reservation,:item,:ordinal,:sku,:checkout)",p); }
   else { jdbc.update("INSERT INTO marketplace.inventory_source_restitution(source_id,cause_id) VALUES(:source,:id)",p); }
   jdbc.update("INSERT INTO marketplace.inventory_physical_inbound_unit(inspection_id,physical_id,source_id) VALUES(:id,:physical,:source)",p);
  }
  for(var sku:sources.stream().map(Source::skuId).distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) { long quantity=sources.stream().filter(s->s.skuId().equals(sku)).count();var before=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku));long hand=((Number)before.get("on_hand")).longValue(),version=((Number)before.get("version")).longValue(),after=Math.addExact(hand,quantity);if(after>999999999||version>=9007199254740991L) { throw new InventoryException(409); }
   jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=:hand,version=version+1 WHERE sku_id=:sku",Map.of("hand",after,"sku",sku));jdbc.update("INSERT INTO marketplace.inventory_restitution_movement(cause_id,sku_id,quantity,before_on_hand,after_on_hand,reserved,before_version,version) VALUES(:id,:sku,:quantity,:before,:after,:reserved,:version,:version+1)",Map.of("id",input.inspectionId(),"sku",sku,"quantity",quantity,"before",hand,"after",after,"reserved",before.get("reserved"),"version",version));
  }
  return new Receipt(input.inspectionId(),input.claimId(),input.purpose(),units);
 }
 private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
