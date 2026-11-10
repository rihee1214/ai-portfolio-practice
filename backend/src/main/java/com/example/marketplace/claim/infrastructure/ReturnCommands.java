package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.Returns;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.ReturnApplications;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderUnitHolds;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Current actor commands preserve the original accepted application independently. */
@Service @Profile("db")
public final class ReturnCommands {
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final ReturnApplications applications;
 private final OrderUnitHolds holds;private final Outbox outbox;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 private final Clock clock;
 private final FinancialIntents intents;
 @org.springframework.beans.factory.annotation.Autowired public ReturnCommands(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnApplications applications,OrderUnitHolds holds,Outbox outbox,PlatformTransactionManager manager,FinancialIntents intents) {
  this(jdbc,queries,applications,holds,outbox,manager,Clock.systemUTC(),intents);
 }
 public ReturnCommands(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnApplications applications,OrderUnitHolds holds,Outbox outbox,PlatformTransactionManager manager,Clock clock,FinancialIntents intents) {
  this.jdbc=jdbc;this.queries=queries;this.applications=applications;this.holds=holds;this.outbox=outbox;this.tx=new TransactionTemplate(manager);
  this.clock=clock;
  this.intents=intents;
 }
 public Returns.Progress get(String number,UUID id,OrderQueries.Authority authority) {
  return tx.execute(t->{var own=queries.lockOwned(number,authority);var row=row(id);if(!own.id().equals(row.get("order_id"))) { throw new OrderRejected(404); }var result=progress(row);queries.lockOwned(number,authority);return result;});
 }
 public Returns.Progress execute(String number,UUID id,OrderQueries.Authority authority,Returns.Command command,String action) {
  if(command==null||command.key()==null||!command.key().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")||command.expectedRevision()==null||command.expectedRevision()<0||command.reason()==null||command.reason().isBlank()||command.reason().length()>500) { throw new OrderRejected(400); }
  String reason=command.reason().strip(),fingerprint=hash(action+"|"+command.expectedRevision()+"|"+reason);
  return tx.execute(t->{
   // An immutable routing read acquires no business lock before current actor authority.
   var route=row(id);boolean owner=action.equals("WITHDRAWN");
   if(owner) { if(!queries.lockOwned(number,authority).id().equals(route.get("order_id"))) { throw new OrderRejected(404); } }
   else { queries.lockSellerOrder((UUID)route.get("seller_order_id"),authority); }
   var prior=jdbc.queryForList("SELECT fingerprint,receipt::text FROM marketplace.claim_return_command WHERE return_id=:id AND request_key=:key",Map.of("id",id,"key",command.key()));
   if(!prior.isEmpty()) { if(!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }checkAuthority(number,route,authority,owner);return mapper.readValue(prior.getFirst().get("receipt").toString(),Returns.Progress.class); }
   var accepted=owner?applications.lockOwner(number,authority,id):applications.lockSeller(authority,id);
   var current=jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",id));
   validateClaim(current,accepted);
   if(!current.get("state").equals("REQUESTED")||((Number)current.get("version")).longValue()!=command.expectedRevision()) { throw new OrderRejected(409); }
   if(!action.equals("APPROVED")) { holds.release(accepted.orderId(),accepted.holdId(),accepted.causeId(),action); }
   long version=command.expectedRevision()+1;Instant at=clock.instant().truncatedTo(ChronoUnit.MICROS);
   var p=new MapSqlParameterSource("id",id).addValue("state",action).addValue("version",version).addValue("at",Timestamp.from(at)).addValue("reason",reason).addValue("actor",authority.member()==null?"GAS":"MEMBER:"+authority.member());
   jdbc.update("UPDATE marketplace.claim_return_request SET state=:state,version=:version WHERE id=:id",p);
   jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,:state,:version,:actor,:reason,:at)",p.addValue("event",UUID.randomUUID()));
   var result=progress(row(id));String key="return-command:"+id+":"+version;
   jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,:state,:version,CAST(:receipt AS jsonb))",p.addValue("key",command.key()).addValue("hash",fingerprint).addValue("receipt",mapper.writeValueAsString(result)));
   outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),new Jobs.Command("RETURN_RECORDED.v1",key,id.toString(),id.toString(),at)));
   checkAuthority(number,current,authority,owner);return result;
  });
 }
 private void validateClaim(Map<String,Object> row,ReturnApplications.Accepted accepted) {
  if(!accepted.id().equals(row.get("id"))||!accepted.orderId().equals(row.get("order_id"))||!accepted.sellerOrderId().equals(row.get("seller_order_id"))||!accepted.holdId().equals(row.get("hold_id"))||!accepted.causeId().equals(row.get("cause_id"))) { throw new OrderRejected(409); }
  var intent=intents.lock(accepted.id());var expected=accepted.source().units().stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).sorted(java.util.Comparator.comparing((com.example.marketplace.order.api.OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(com.example.marketplace.order.api.OrderConfirmations.Unit::ordinal)).toList();
  if(!intent.kind().equals("RETURN")||!intent.orderId().equals(accepted.orderId())||!intent.sellerOrderId().equals(accepted.sellerOrderId())||!intent.holdId().equals(accepted.holdId())||!intent.causeId().equals(accepted.causeId())||!intent.units().equals(expected)) { throw new OrderRejected(409); }
 }
 private void checkAuthority(String number,Map<String,Object> row,OrderQueries.Authority authority,boolean owner) {
  if(owner) { queries.lockOwned(number,authority); }else { queries.lockSellerOrder((UUID)row.get("seller_order_id"),authority); }
 }
 private Map<String,Object> row(UUID id) {
  if(id==null) { throw new OrderRejected(404); }var rows=jdbc.queryForList("SELECT * FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id));if(rows.size()!=1) { throw new OrderRejected(404); }return rows.getFirst();
 }
 Returns.Progress progress(Map<String,Object> row) {
  var history=jdbc.query("SELECT action,version,reason,occurred_at FROM marketplace.claim_return_history WHERE return_id=:id ORDER BY version",Map.of("id",row.get("id")),(r,n)->new Returns.History(r.getString(1),r.getLong(2),r.getString(3),r.getTimestamp(4).toInstant()));
  return new Returns.Progress(mapper.readValue(row.get("receipt").toString(),Returns.Receipt.class),row.get("state").toString(),((Number)row.get("version")).longValue(),history,ReturnPhysicalFacts.read(jdbc,(UUID)row.get("id")));
 }
 private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
