package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnInspections;
import com.example.marketplace.claim.api.Returns;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.inventory.api.Reservations;
import com.example.marketplace.inventory.api.Restitutions;
import com.example.marketplace.order.api.ReturnApplications;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class ReturnInspectionCommands implements ReturnInspections {
 @org.springframework.beans.factory.annotation.Autowired private com.example.marketplace.inventory.api.PhysicalStocks physicalStocks;
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final ReturnApplications applications;
 private final FinancialIntents intents;private final ReturnCommands commands;private final Orders orders;private final Reservations reservations;
 private final Restitutions restitutions;private final Outbox outbox;private final TransactionTemplate tx;private final Clock clock;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 @org.springframework.beans.factory.annotation.Autowired public ReturnInspectionCommands(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnApplications applications,FinancialIntents intents,ReturnCommands commands,Orders orders,Reservations reservations,Restitutions restitutions,Outbox outbox,PlatformTransactionManager manager) {
  this(jdbc,queries,applications,intents,commands,orders,reservations,restitutions,outbox,manager,Clock.systemUTC());
 }
 public ReturnInspectionCommands(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnApplications applications,FinancialIntents intents,ReturnCommands commands,Orders orders,Reservations reservations,Restitutions restitutions,Outbox outbox,PlatformTransactionManager manager,Clock clock) {
  this.jdbc=jdbc;this.queries=queries;this.applications=applications;this.intents=intents;this.commands=commands;this.orders=orders;this.reservations=reservations;this.restitutions=restitutions;this.outbox=outbox;this.tx=new TransactionTemplate(manager);this.clock=clock;
 }
 @Override public Returns.Progress inspect(UUID id,OrderQueries.Authority authority,Command command) {
  if(id==null||command==null||command.key()==null||!command.key().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")||command.expectedRevision()==null||command.expectedRevision()<0||command.reason()==null||command.reason().isBlank()||command.reason().length()>500||command.units()==null||command.units().isEmpty()||command.units().size()>1980||command.units().stream().anyMatch(u->u==null||u.itemId()==null||u.ordinal()<0||u.condition()==null||u.responsibility()==null)) { throw new OrderRejected(400); }
  var units=command.units().stream().sorted(Comparator.comparing((Unit u)->u.itemId().toString()).thenComparingInt(Unit::ordinal)).toList();if(units.stream().map(u->u.itemId()+":"+u.ordinal()).distinct().count()!=units.size()) { throw new OrderRejected(400); }
  String reason=command.reason().strip(),fingerprint=hash("INSPECT|"+command.expectedRevision()+"|"+units+"|"+reason);
  return tx.execute(t->{
   var route=jdbc.queryForList("SELECT seller_order_id FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id),UUID.class);if(route.size()!=1) { throw new OrderRejected(404); }var seller=queries.lockSellerOrder(route.getFirst(),authority);
   var prior=jdbc.queryForList("SELECT fingerprint,receipt::text FROM marketplace.claim_return_command WHERE return_id=:id AND request_key=:key",Map.of("id",id,"key",command.key()));
   if(!prior.isEmpty()) { if(!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }queries.lockSellerOrder(route.getFirst(),authority);return mapper.readValue(prior.getFirst().get("receipt").toString(),Returns.Progress.class); }
   var accepted=applications.lockSeller(authority,id);var row=jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",id));var intent=intents.lock(id);
   var expected=accepted.source().units().stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).sorted(Comparator.comparing((com.example.marketplace.order.api.OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(com.example.marketplace.order.api.OrderConfirmations.Unit::ordinal)).toList();
   if(!id.equals(row.get("id"))||!accepted.orderId().equals(row.get("order_id"))||!accepted.sellerOrderId().equals(row.get("seller_order_id"))||!accepted.holdId().equals(row.get("hold_id"))||!accepted.causeId().equals(row.get("cause_id"))||!intent.kind().equals("RETURN")||!intent.orderId().equals(accepted.orderId())||!intent.sellerOrderId().equals(accepted.sellerOrderId())||!intent.holdId().equals(accepted.holdId())||!intent.causeId().equals(accepted.causeId())||!intent.units().equals(expected)) { throw new OrderRejected(409); }
   if(!List.of("APPROVED","COLLECTING","REVIEW_REQUIRED").contains(row.get("state").toString())||((Number)row.get("version")).longValue()!=command.expectedRevision()) { throw new OrderRejected(409); }
   var facts=ReturnPhysicalFacts.read(jdbc,id);for(var unit:units) { var fact=facts.stream().filter(u->u.itemId().equals(unit.itemId())&&u.ordinal()==unit.ordinal()).findFirst().orElseThrow(()->new OrderRejected(409));if(!fact.collection().equals("RECEIVED")||!fact.inspection().equals("NOT_INSPECTED")) { throw new OrderRejected(409); } }
   UUID inspection=UUID.randomUUID();long version=command.expectedRevision()+1;Instant at=clock.instant().truncatedTo(ChronoUnit.MICROS);
   var p=new MapSqlParameterSource("inspection",inspection).addValue("id",id).addValue("order",accepted.orderId()).addValue("seller",accepted.sellerOrderId()).addValue("checkout",accepted.source().checkoutId()).addValue("version",version).addValue("reason",reason).addValue("actor","MEMBER:"+authority.member()).addValue("at",Timestamp.from(at));
   jdbc.update("INSERT INTO marketplace.claim_return_inspection(id,return_id,order_id,seller_order_id,checkout_id,version,reason,actor,occurred_at) VALUES(:inspection,:id,:order,:seller,:checkout,:version,:reason,:actor,:at)",p);
   for(var unit:units) { var fact=facts.stream().filter(u->u.itemId().equals(unit.itemId())&&u.ordinal()==unit.ordinal()).findFirst().orElseThrow();jdbc.update("INSERT INTO marketplace.claim_return_inspection_unit(inspection_id,return_id,item_id,ordinal,collection_id,condition,responsibility,restitution_id,physical_id) VALUES(:inspection,:id,:item,:ordinal,:collection,:condition,:responsibility,:restitution,:physical)",new MapSqlParameterSource("inspection",inspection).addValue("id",id).addValue("item",unit.itemId()).addValue("ordinal",unit.ordinal()).addValue("collection",fact.collectionId()).addValue("condition",unit.condition().name()).addValue("responsibility",unit.responsibility().name()).addValue("restitution",unit.condition()==Condition.SALEABLE?inspection:null).addValue("physical",accepted.source().units().stream().filter(u->u.itemId().equals(unit.itemId())&&u.ordinal()==unit.ordinal()).map(com.example.marketplace.order.api.ReturnSources.Unit::physical).filter(java.util.Objects::nonNull).map(com.example.marketplace.order.api.PhysicalOrders.Source::id).findFirst().orElse(null))); }
   var saleable=units.stream().filter(u->u.condition()==Condition.SALEABLE).toList();
   if(!saleable.isEmpty()&&accepted.source().units().stream().anyMatch(u->u.physical()!=null)) {
    var inbound=saleable.stream().map(u->{var source=accepted.source().units().stream().filter(a->a.itemId().equals(u.itemId())&&a.ordinal()==u.ordinal()).findFirst().orElseThrow(()->new OrderRejected(409));if(source.physical()==null) { throw new OrderRejected(409); }return new com.example.marketplace.inventory.api.PhysicalStocks.Unit(source.physical().id(),source.physical().stock().id());}).toList();physicalStocks.restore(new com.example.marketplace.inventory.api.PhysicalStocks.Inbound(inspection,id,accepted.source().checkoutId(),com.example.marketplace.inventory.api.PhysicalStocks.Purpose.RETURN,inbound));
   }else if(!saleable.isEmpty()) {
    var stocks=reservations.inspect(accepted.source().checkoutId(),orders.reservations(seller.orderNumber()).inventory());
    var inbound=saleable.stream().map(u->{var stock=stocks.stream().filter(s->s.orderItemId().equals(u.itemId())).findFirst().orElseThrow(()->new OrderRejected(409));var source=accepted.source().units().stream().filter(s->s.itemId().equals(u.itemId())&&s.ordinal()==u.ordinal()).findFirst().orElseThrow(()->new OrderRejected(409));if(!stock.status().equals("COMMITTED")||!stock.skuId().equals(source.skuId())) { throw new OrderRejected(409); }return new Restitutions.Unit(stock.id(),u.itemId(),u.ordinal(),source.skuId());}).toList();
    restitutions.restoreReturn(new Restitutions.ReturnRequest(inspection,id,accepted.source().checkoutId(),inbound));
   }
   var current=ReturnPhysicalFacts.read(jdbc,id);String state=current.stream().anyMatch(u->u.collection().equals("LOST_REPORTED"))?"REVIEW_REQUIRED":current.stream().allMatch(u->u.collection().equals("RECEIVED")&&!u.inspection().equals("NOT_INSPECTED"))?"AWAITING_CONSENT":"COLLECTING";
   jdbc.update("UPDATE marketplace.claim_return_request SET state=:state,version=:version WHERE id=:id",p.addValue("state",state));
   jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,:state,:version,:actor,:reason,:at)",p.addValue("event",UUID.randomUUID()));
   var result=commands.progress(jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id)));
   jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,:state,:version,CAST(:receipt AS jsonb))",p.addValue("key",command.key()).addValue("hash",fingerprint).addValue("receipt",mapper.writeValueAsString(result)));
   String key="return-command:"+id+":"+version;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),new Jobs.Command("RETURN_RECORDED.v1",key,id.toString(),id.toString(),at)));
   queries.lockSellerOrder(accepted.sellerOrderId(),authority);return result;
  });
 }
 private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
