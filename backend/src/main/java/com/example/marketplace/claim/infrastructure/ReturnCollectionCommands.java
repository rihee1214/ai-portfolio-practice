package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnCollections;
import com.example.marketplace.claim.api.Returns;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.ReturnApplications;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
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
public final class ReturnCollectionCommands implements ReturnCollections {
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final ReturnApplications applications;
 private final FinancialIntents intents;private final ReturnCommands commands;private final Outbox outbox;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 private final Clock clock;
 @org.springframework.beans.factory.annotation.Autowired public ReturnCollectionCommands(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnApplications applications,FinancialIntents intents,ReturnCommands commands,Outbox outbox,PlatformTransactionManager manager) {
  this(jdbc,queries,applications,intents,commands,outbox,manager,Clock.systemUTC());
 }
 public ReturnCollectionCommands(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnApplications applications,FinancialIntents intents,ReturnCommands commands,Outbox outbox,PlatformTransactionManager manager,Clock clock) {
  this.jdbc=jdbc;this.queries=queries;this.applications=applications;this.intents=intents;this.commands=commands;this.outbox=outbox;this.tx=new TransactionTemplate(manager);
  this.clock=clock;
 }
 @Override public Returns.Progress receive(UUID id,OrderQueries.Authority authority,Command command) {
  if(id==null||command==null||command.key()==null||!command.key().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")||command.expectedRevision()==null||command.expectedRevision()<0||command.reason()==null||command.reason().isBlank()||command.reason().length()>500||command.units()==null||command.units().isEmpty()||command.units().size()>1980||command.units().stream().anyMatch(u->u==null||u.itemId()==null||u.ordinal()<0||u.status()==null)) { throw new OrderRejected(400); }
  var units=command.units().stream().sorted(Comparator.comparing((Unit u)->u.itemId().toString()).thenComparingInt(Unit::ordinal)).toList();
  if(units.stream().map(u->u.itemId()+":"+u.ordinal()).distinct().count()!=units.size()) { throw new OrderRejected(400); }
  String reason=command.reason().strip(),fingerprint=hash("RECEIVE|"+command.expectedRevision()+"|"+units+"|"+reason);
  return tx.execute(t->{
   var route=jdbc.queryForList("SELECT seller_order_id FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id),UUID.class);if(route.size()!=1) { throw new OrderRejected(404); }
   queries.lockSellerOrder(route.getFirst(),authority);
   var prior=jdbc.queryForList("SELECT fingerprint,receipt::text FROM marketplace.claim_return_command WHERE return_id=:id AND request_key=:key",Map.of("id",id,"key",command.key()));
   if(!prior.isEmpty()) { if(!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }queries.lockSellerOrder(route.getFirst(),authority);return mapper.readValue(prior.getFirst().get("receipt").toString(),Returns.Progress.class); }
   var accepted=applications.lockSeller(authority,id);var row=jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",id));
   var intent=intents.lock(id);if(!id.equals(row.get("id"))||!accepted.orderId().equals(row.get("order_id"))||!accepted.sellerOrderId().equals(row.get("seller_order_id"))||!accepted.holdId().equals(row.get("hold_id"))||!accepted.causeId().equals(row.get("cause_id"))||!intent.kind().equals("RETURN")||!intent.orderId().equals(accepted.orderId())||!intent.sellerOrderId().equals(accepted.sellerOrderId())||!intent.holdId().equals(accepted.holdId())||!intent.causeId().equals(accepted.causeId())) { throw new OrderRejected(409); }
   var expected=accepted.source().units().stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).sorted(Comparator.comparing((com.example.marketplace.order.api.OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(com.example.marketplace.order.api.OrderConfirmations.Unit::ordinal)).toList();if(!intent.units().equals(expected)) { throw new OrderRejected(409); }
   if(!List.of("APPROVED","COLLECTING","REVIEW_REQUIRED").contains(row.get("state").toString())||((Number)row.get("version")).longValue()!=command.expectedRevision()) { throw new OrderRejected(409); }
   var facts=ReturnPhysicalFacts.read(jdbc,id);long version=command.expectedRevision()+1;Instant at=clock.instant().truncatedTo(ChronoUnit.MICROS);
   for(var unit:units) {
    var original=facts.stream().filter(u->u.itemId().equals(unit.itemId())&&u.ordinal()==unit.ordinal()).findFirst().orElseThrow(()->new OrderRejected(409));
    if(!original.inspection().equals("NOT_INSPECTED")||original.collection().equals("RECEIVED")||original.collection().equals(unit.status().name())) { throw new OrderRejected(409); }
    jdbc.update("INSERT INTO marketplace.claim_return_collection(id,return_id,item_id,ordinal,status,version,occurred_at,physical_id) VALUES(:event,:id,:item,:ordinal,:status,:version,:at,:physical)",new MapSqlParameterSource("event",UUID.randomUUID()).addValue("id",id).addValue("item",unit.itemId()).addValue("ordinal",unit.ordinal()).addValue("status",unit.status().name()).addValue("version",version).addValue("at",Timestamp.from(at)).addValue("physical",accepted.source().units().stream().filter(u->u.itemId().equals(unit.itemId())&&u.ordinal()==unit.ordinal()).map(com.example.marketplace.order.api.ReturnSources.Unit::physical).filter(java.util.Objects::nonNull).map(com.example.marketplace.order.api.PhysicalOrders.Source::id).findFirst().orElse(null)));
   }
   String state=ReturnPhysicalFacts.read(jdbc,id).stream().anyMatch(u->u.collection().equals("LOST_REPORTED"))?"REVIEW_REQUIRED":"COLLECTING";
   var p=new MapSqlParameterSource("id",id).addValue("state",state).addValue("version",version).addValue("reason",reason).addValue("actor","MEMBER:"+authority.member()).addValue("at",Timestamp.from(at));
   jdbc.update("UPDATE marketplace.claim_return_request SET state=:state,version=:version WHERE id=:id",p);
   jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,:state,:version,:actor,:reason,:at)",p.addValue("event",UUID.randomUUID()));
   var result=commands.progress(jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id)));
   jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,:state,:version,CAST(:receipt AS jsonb))",p.addValue("key",command.key()).addValue("hash",fingerprint).addValue("receipt",mapper.writeValueAsString(result)));
   String key="return-command:"+id+":"+version;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),new Jobs.Command("RETURN_RECORDED.v1",key,id.toString(),id.toString(),at)));
   queries.lockSellerOrder(accepted.sellerOrderId(),authority);return result;
  });
 }
 private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
