package com.example.marketplace.inventory.infrastructure;
import com.example.marketplace.inventory.api.ExchangeReservations;
import com.example.marketplace.inventory.api.InventoryException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service @Profile("db")
public final class JdbcExchangeReservations implements ExchangeReservations {
 private final NamedParameterJdbcTemplate jdbc;private final java.time.Clock clock;
 @org.springframework.beans.factory.annotation.Autowired public JdbcExchangeReservations(NamedParameterJdbcTemplate jdbc) { this(jdbc,null); }
 public JdbcExchangeReservations(NamedParameterJdbcTemplate jdbc,java.time.Clock clock) { this.jdbc=jdbc;this.clock=clock; }
 private java.time.Instant now() { return clock==null?jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant():clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
 @Override public Purchased purchased(UUID checkout,UUID item,int ordinal,UUID sku) {
  if(checkout==null||item==null||sku==null||ordinal<0) { throw new InventoryException(400); }
  var ids=jdbc.queryForList("SELECT id FROM marketplace.inventory_reservation WHERE checkout_id=:checkout AND order_item_id=:item AND sku_id=:sku AND quantity>:ordinal AND status='COMMITTED'",Map.of("checkout",checkout,"item",item,"sku",sku,"ordinal",ordinal),UUID.class);
  if(ids.size()!=1) { throw new InventoryException(409); }return new Purchased(ids.getFirst(),checkout,item,ordinal,sku);
 }
 @Override public long available(UUID sku) { var rows=jdbc.queryForList("SELECT on_hand-reserved FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",sku),Long.class);return rows.isEmpty()?0:rows.getFirst(); }
 @Override public List<Reservation> get(UUID id) { return jdbc.query("SELECT r.*,COALESCE(e.extended_expires_at,r.expires_at) AS effective_expires_at FROM marketplace.inventory_exchange_reservation r LEFT JOIN marketplace.inventory_exchange_extension_unit e ON e.reservation_id=r.id WHERE r.exchange_id=:id ORDER BY r.physical_id::text,r.sequence",Map.of("id",id),(r,n)->new Reservation(r.getObject("id",UUID.class),id,r.getObject("physical_id",UUID.class),r.getObject("target_sku_id",UUID.class),r.getLong("quantity"),r.getInt("sequence"),r.getString("status"),r.getTimestamp("approved_at").toInstant(),r.getTimestamp("effective_expires_at").toInstant())); }
 @Override public List<Reservation> lockCurrent(UUID exchange) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }if(exchange==null) { throw new InventoryException(400); }
  var all=get(exchange);if(all.isEmpty()) { throw new InventoryException(409); }int sequence=all.stream().mapToInt(Reservation::sequence).max().orElseThrow();var current=all.stream().filter(r->r.sequence()==sequence).toList();
  for(UUID sku:current.stream().map(Reservation::targetSkuId).distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) { jdbc.queryForMap("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:sku FOR UPDATE",Map.of("sku",sku)); }
  for(UUID id:current.stream().map(Reservation::id).sorted(java.util.Comparator.comparing(UUID::toString)).toList()) { jdbc.queryForMap("SELECT id FROM marketplace.inventory_exchange_reservation WHERE id=:id FOR UPDATE",Map.of("id",id)); }
  return get(exchange).stream().filter(r->r.sequence()==sequence).toList();
 }
 @Override public List<Reservation> extend(Extension command) {
  if(command==null||command.id()==null||command.exchangeId()==null||command.sequence()<1||command.previousExpiresAt()==null||command.extendedExpiresAt()==null||command.extendedAt()==null||!command.extendedExpiresAt().equals(command.previousExpiresAt().plusSeconds(604800))||!command.extendedAt().isBefore(command.previousExpiresAt())) { throw new InventoryException(400); }
  var current=lockCurrent(command.exchangeId());var p=new MapSqlParameterSource("id",command.id()).addValue("exchange",command.exchangeId()).addValue("seq",command.sequence()).addValue("previous",Timestamp.from(command.previousExpiresAt())).addValue("extended",Timestamp.from(command.extendedExpiresAt())).addValue("at",Timestamp.from(command.extendedAt()));
  var old=jdbc.queryForList("SELECT * FROM marketplace.inventory_exchange_extension WHERE id=:id OR exchange_id=:exchange",p);
  if(!old.isEmpty()) { var row=old.getFirst();if(old.size()!=1||!row.get("id").equals(command.id())||!row.get("exchange_id").equals(command.exchangeId())||((Number)row.get("sequence")).intValue()!=command.sequence()||!((Timestamp)row.get("previous_expires_at")).toInstant().equals(command.previousExpiresAt())||!((Timestamp)row.get("extended_expires_at")).toInstant().equals(command.extendedExpiresAt())||!((Timestamp)row.get("extended_at")).toInstant().equals(command.extendedAt())) { throw new InventoryException(409); }return current; }
  if(current.stream().anyMatch(r->r.sequence()!=command.sequence()||!r.status().equals("RESERVED")||!r.expiresAt().equals(command.previousExpiresAt()))) { throw new InventoryException(409); }
  jdbc.update("INSERT INTO marketplace.inventory_exchange_extension(id,exchange_id,sequence,previous_expires_at,extended_expires_at,extended_at) VALUES(:id,:exchange,:seq,:previous,:extended,:at)",p);
  for(var r:current) { jdbc.update("INSERT INTO marketplace.inventory_exchange_extension_unit(extension_id,exchange_id,reservation_id,previous_expires_at,extended_expires_at,extended_at) VALUES(:id,:exchange,:reservation,:previous,:extended,:at)",p.addValue("reservation",r.id())); }return get(command.exchangeId()).stream().filter(r->r.sequence()==current.getFirst().sequence()).toList();
 }
 @Override public List<Reservation> release(Release command) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }if(command==null||command.id()==null||command.exchangeId()==null||command.sequence()<1||command.reason()==null||command.releasedAt()==null) { throw new InventoryException(400); }
  var selected=get(command.exchangeId()).stream().filter(r->r.sequence()==command.sequence()).toList();if(selected.isEmpty()) { throw new InventoryException(409); }
  for(UUID sku:selected.stream().map(Reservation::targetSkuId).distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) { jdbc.queryForMap("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:sku FOR UPDATE",Map.of("sku",sku)); }
  for(UUID id:selected.stream().map(Reservation::id).sorted(java.util.Comparator.comparing(UUID::toString)).toList()) { jdbc.queryForMap("SELECT id FROM marketplace.inventory_exchange_reservation WHERE id=:id FOR UPDATE",Map.of("id",id)); }
  selected=get(command.exchangeId()).stream().filter(r->r.sequence()==command.sequence()).toList();String state=command.reason()==ReleaseReason.EXPIRED?"EXPIRED":"RELEASED";String cause=command.reason()==ReleaseReason.EXPIRED?"EXPIRE":"RELEASE";
  var p=new MapSqlParameterSource("id",command.id()).addValue("exchange",command.exchangeId()).addValue("seq",command.sequence()).addValue("reason",command.reason().name()).addValue("at",Timestamp.from(command.releasedAt())).addValue("state",state).addValue("cause",cause);
  var prior=jdbc.queryForList("SELECT * FROM marketplace.inventory_exchange_release WHERE id=:id OR(exchange_id=:exchange AND sequence=:seq)",p);
  if(!prior.isEmpty()) { var row=prior.getFirst();if(prior.size()!=1||!row.get("id").equals(command.id())||!row.get("exchange_id").equals(command.exchangeId())||((Number)row.get("sequence")).intValue()!=command.sequence()||!row.get("reason").equals(command.reason().name())||!((Timestamp)row.get("released_at")).toInstant().equals(command.releasedAt())||selected.stream().anyMatch(r->!r.status().equals(state))) { throw new InventoryException(409); }return selected; }
  if(selected.stream().anyMatch(r->!r.status().equals("RESERVED")||command.reason()==ReleaseReason.EXPIRED&&command.releasedAt().isBefore(r.expiresAt()))) {
   System.getLogger(JdbcExchangeReservations.class.getName()).log(System.Logger.Level.WARNING,"EXCHANGE_RELEASE_PRECONDITION_REJECTED exchange="+command.exchangeId()+" sequence="+command.sequence()+" reason="+command.reason()+" releasedAt="+command.releasedAt()+" stock="+selected);
   throw new InventoryException(409);
  }
  jdbc.update("INSERT INTO marketplace.inventory_exchange_release(id,exchange_id,sequence,reason,released_at) VALUES(:id,:exchange,:seq,:reason,:at)",p);
  for(var r:selected) { p.addValue("reservation",r.id()).addValue("sku",r.targetSkuId());var balance=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:sku",p);long reserved=((Number)balance.get("reserved")).longValue(),version=((Number)balance.get("version")).longValue();if(reserved<r.quantity()||version>=9007199254740991L) { throw new InventoryException(409); }UUID event=UUID.randomUUID();p.addValue("event",event).addValue("before",reserved).addValue("after",Math.subtractExact(reserved,r.quantity())).addValue("version",version);
   jdbc.update("UPDATE marketplace.inventory_balance SET reserved=:after,version=version+1 WHERE sku_id=:sku",p);jdbc.update("UPDATE marketplace.inventory_exchange_reservation SET status=:state WHERE id=:reservation",p);jdbc.update("INSERT INTO marketplace.inventory_exchange_event(id,reservation_id,cause,before_reserved,after_reserved,before_version,version,occurred_at) VALUES(:event,:reservation,:cause,:before,:after,:version,:version+1,:at)",p);jdbc.update("INSERT INTO marketplace.inventory_exchange_release_unit(release_id,exchange_id,sequence,reservation_id,event_id,cause) VALUES(:id,:exchange,:seq,:reservation,:event,:cause)",p);
  }return get(command.exchangeId()).stream().filter(r->r.sequence()==command.sequence()).toList();
 }
 @Override public Released released(UUID exchange,int sequence) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }if(exchange==null||sequence<1) { throw new InventoryException(400); }
  var rows=jdbc.queryForList("SELECT * FROM marketplace.inventory_exchange_release WHERE exchange_id=:id AND sequence=:seq",Map.of("id",exchange,"seq",sequence));if(rows.size()!=1) { throw new InventoryException(409); }var row=rows.getFirst();var reason=ReleaseReason.valueOf(row.get("reason").toString());var list=get(exchange).stream().filter(r->r.sequence()==sequence).toList();String status=reason==ReleaseReason.EXPIRED?"EXPIRED":"RELEASED";if(list.isEmpty()||list.stream().anyMatch(r->!r.status().equals(status))) { throw new InventoryException(409); }return new Released((UUID)row.get("id"),exchange,sequence,reason,((Timestamp)row.get("released_at")).toInstant(),list);
 }
 @Override public void bindSources(UUID exchange,List<PhysicalBinding> bindings) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(exchange==null||bindings==null||bindings.isEmpty()||bindings.stream().anyMatch(b->b==null||b.reservationId()==null||b.physicalId()==null||b.committedSourceId()==null)||bindings.stream().map(PhysicalBinding::reservationId).distinct().count()!=bindings.size()) { throw new InventoryException(400); }
  for(var b:bindings) { var rows=jdbc.queryForList("SELECT id FROM marketplace.inventory_exchange_reservation WHERE id=:id AND exchange_id=:exchange",Map.of("id",b.reservationId(),"exchange",exchange),UUID.class);if(rows.size()!=1) { throw new InventoryException(409); }var p=Map.of("id",b.reservationId(),"physical",b.physicalId(),"source",b.committedSourceId());jdbc.update("INSERT INTO marketplace.inventory_exchange_reservation_physical_source(reservation_id,physical_id,source_id) VALUES(:id,:physical,:source) ON CONFLICT(reservation_id) DO NOTHING",p);var stored=jdbc.queryForMap("SELECT physical_id,source_id FROM marketplace.inventory_exchange_reservation_physical_source WHERE reservation_id=:id",p);if(!stored.get("physical_id").equals(b.physicalId())||!stored.get("source_id").equals(b.committedSourceId())) { throw new InventoryException(409); } }
 }
 @Override public List<Reservation> commit(UUID exchange,UUID dispatch) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }if(exchange==null||dispatch==null) { throw new InventoryException(400); }
  var all=lockCurrent(exchange);if(all.isEmpty()) { throw new InventoryException(409); }
  for(var sku:all.stream().map(Reservation::targetSkuId).distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) { jdbc.queryForMap("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:sku FOR UPDATE",Map.of("sku",sku)); }
  for(var r:all) { var p=new MapSqlParameterSource("id",r.id()).addValue("dispatch",dispatch).addValue("sku",r.targetSkuId());var row=jdbc.queryForMap("SELECT * FROM marketplace.inventory_exchange_reservation WHERE id=:id FOR UPDATE",p);
   var old=jdbc.queryForList("SELECT dispatch_id FROM marketplace.inventory_exchange_commit WHERE reservation_id=:id",p,UUID.class);if(!old.isEmpty()) { if(!old.getFirst().equals(dispatch)||!row.get("status").equals("COMMITTED")) { throw new InventoryException(409); }continue; }
   var at=now();if(!row.get("status").equals("RESERVED")||!at.isBefore(jdbc.queryForObject("SELECT COALESCE((SELECT extended_expires_at FROM marketplace.inventory_exchange_extension_unit WHERE reservation_id=:id),expires_at) FROM marketplace.inventory_exchange_reservation WHERE id=:id",p,Timestamp.class).toInstant())) { throw new InventoryException(409); }
   var b=jdbc.queryForMap("SELECT * FROM marketplace.inventory_balance WHERE sku_id=:sku",p);long hand=((Number)b.get("on_hand")).longValue(),reserved=((Number)b.get("reserved")).longValue(),version=((Number)b.get("version")).longValue();if(hand<1||reserved<1||version>=9007199254740991L) { throw new InventoryException(409); }
   p.addValue("hand",hand).addValue("reserved",reserved).addValue("version",version).addValue("at",Timestamp.from(at));jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=on_hand-1,reserved=reserved-1,version=version+1 WHERE sku_id=:sku",p);jdbc.update("UPDATE marketplace.inventory_exchange_reservation SET status='COMMITTED' WHERE id=:id",p);
   jdbc.update("INSERT INTO marketplace.inventory_exchange_commit(reservation_id,dispatch_id,before_on_hand,after_on_hand,before_reserved,after_reserved,before_version,version,committed_at) VALUES(:id,:dispatch,:hand,:hand-1,:reserved,:reserved-1,:version,:version+1,:at)",p);
  }return get(exchange).stream().filter(r->r.sequence()==all.getFirst().sequence()).toList();
 }
 @Override public List<Reservation> reserve(Request request) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(request==null||request.exchangeId()==null||request.orderId()==null||request.sellerOrderId()==null||request.checkoutId()==null||request.sequence()<1||request.approvedAt()==null||request.expiresAt()==null||!request.expiresAt().equals(request.approvedAt().plusSeconds(604800))||request.units().isEmpty()||request.units().size()>1980) { throw new InventoryException(400); }
  var units=request.units();if(units.stream().anyMatch(u->u==null||u.physicalId()==null||u.itemId()==null||u.ordinal()<0||u.sourceReservationId()==null||u.sourceSkuId()==null||u.targetSkuId()==null)||units.stream().map(Unit::physicalId).distinct().count()!=units.size()) { throw new InventoryException(400); }
  var keys=units.stream().map(u->"inventory/exchange/"+request.exchangeId()+"/"+u.physicalId()+"/"+request.sequence()).toList();
  var locks=jdbc.queryForList("SELECT DISTINCT hashtextextended(value,0) AS lock_id FROM unnest(CAST(ARRAY[:keys] AS text[])) AS keys(value) ORDER BY lock_id",Map.of("keys",keys),Long.class);
  for(long key:locks) { jdbc.query("SELECT pg_advisory_xact_lock(:key)",Map.of("key",key),(r,n)->r.getObject(1)); }
  for(UUID sku:units.stream().map(Unit::targetSkuId).distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
   if(jdbc.queryForList("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:sku FOR UPDATE",Map.of("sku",sku),UUID.class).size()!=1) { throw new InventoryException(409); }
  }
  var now=now();
  if(!now.isBefore(request.expiresAt())) { throw new InventoryException(409); }
  var approval=new MapSqlParameterSource("exchange",request.exchangeId()).addValue("seq",request.sequence()).addValue("order",request.orderId()).addValue("seller",request.sellerOrderId()).addValue("checkout",request.checkoutId()).addValue("at",Timestamp.from(request.approvedAt())).addValue("expires",Timestamp.from(request.expiresAt()));
  jdbc.update("INSERT INTO marketplace.inventory_exchange_approval(exchange_id,sequence,order_id,seller_order_id,checkout_id,approved_at,expires_at) VALUES(:exchange,:seq,:order,:seller,:checkout,:at,:expires) ON CONFLICT DO NOTHING",approval);var approved=jdbc.queryForMap("SELECT * FROM marketplace.inventory_exchange_approval WHERE exchange_id=:exchange AND sequence=:seq",approval);if(!approved.get("order_id").equals(request.orderId())||!approved.get("seller_order_id").equals(request.sellerOrderId())||!approved.get("checkout_id").equals(request.checkoutId())||!((Timestamp)approved.get("approved_at")).toInstant().equals(request.approvedAt())||!((Timestamp)approved.get("expires_at")).toInstant().equals(request.expiresAt())) { throw new InventoryException(409); }
  for(var u:units.stream().sorted(java.util.Comparator.comparing(v->v.physicalId().toString())).toList()) {
   var source=purchased(request.checkoutId(),u.itemId(),u.ordinal(),u.sourceSkuId());if(!source.reservationId().equals(u.sourceReservationId())) { throw new InventoryException(409); }
   var p=new MapSqlParameterSource("exchange",request.exchangeId()).addValue("physical",u.physicalId()).addValue("seq",request.sequence()).addValue("order",request.orderId()).addValue("seller",request.sellerOrderId()).addValue("checkout",request.checkoutId()).addValue("item",u.itemId()).addValue("ordinal",u.ordinal()).addValue("source",u.sourceReservationId()).addValue("sourceSku",u.sourceSkuId()).addValue("sku",u.targetSkuId()).addValue("at",Timestamp.from(request.approvedAt())).addValue("expires",Timestamp.from(request.expiresAt()));
   var previous=jdbc.queryForList("SELECT * FROM marketplace.inventory_exchange_reservation WHERE exchange_id=:exchange AND physical_id=:physical AND sequence=:seq FOR UPDATE",p);
   if(!previous.isEmpty()) { var r=previous.getFirst();if(!r.get("target_sku_id").equals(u.targetSkuId())||!((Timestamp)r.get("approved_at")).toInstant().equals(request.approvedAt())||!((Timestamp)r.get("expires_at")).toInstant().equals(request.expiresAt())) { throw new InventoryException(409); }continue; }
   var b=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:sku",p);long hand=((Number)b.get("on_hand")).longValue(),reserved=((Number)b.get("reserved")).longValue(),version=((Number)b.get("version")).longValue();
   if(hand-reserved<1||version>=9007199254740991L) { throw new InventoryException(409); }
   UUID id=UUID.randomUUID();p.addValue("id",id).addValue("before",reserved).addValue("after",Math.addExact(reserved,1)).addValue("version",version);
   jdbc.update("INSERT INTO marketplace.inventory_exchange_reservation(id,exchange_id,order_id,seller_order_id,checkout_id,item_id,ordinal,physical_id,source_reservation_id,source_sku_id,target_sku_id,quantity,sequence,status,approved_at,expires_at) VALUES(:id,:exchange,:order,:seller,:checkout,:item,:ordinal,:physical,:source,:sourceSku,:sku,1,:seq,'RESERVED',:at,:expires)",p);
   if(jdbc.update("UPDATE marketplace.inventory_balance SET reserved=:after,version=version+1 WHERE sku_id=:sku AND reserved=:before AND version=:version AND on_hand>=:after",p)!=1) { throw new InventoryException(409); }
   jdbc.update("INSERT INTO marketplace.inventory_exchange_event(id,reservation_id,cause,before_reserved,after_reserved,before_version,version,occurred_at) VALUES(:id,:id,'RESERVE',:before,:after,:version,:version+1,:at)",p);
  }
  return get(request.exchangeId()).stream().filter(r->r.sequence()==request.sequence()).toList();
 }
}
