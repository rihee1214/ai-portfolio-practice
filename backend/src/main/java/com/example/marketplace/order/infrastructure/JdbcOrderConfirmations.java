package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderUnitHolds;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.domain.ConfirmationRules;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class JdbcOrderConfirmations implements OrderConfirmations,OrderUnitHolds {
 public interface Checkpoint { void after(String stage) throws Exception; }
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final Orders orders;private final AftercareLifecycle lifecycle;private final Outbox outbox;private final JobLeases leases;private final TransactionTemplate tx;private final Clock clock;private final Checkpoint checkpoint;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 @Autowired public JdbcOrderConfirmations(NamedParameterJdbcTemplate jdbc,OrderQueries queries,Orders orders,AftercareLifecycle lifecycle,Outbox outbox,JobLeases leases,PlatformTransactionManager manager) { this(jdbc,queries,orders,lifecycle,outbox,leases,manager,Clock.systemUTC(),stage->{ }); }
 public JdbcOrderConfirmations(NamedParameterJdbcTemplate jdbc,OrderQueries queries,Orders orders,AftercareLifecycle lifecycle,Outbox outbox,JobLeases leases,PlatformTransactionManager manager,Clock clock,Checkpoint checkpoint) { this.jdbc=jdbc;this.queries=queries;this.orders=orders;this.lifecycle=lifecycle;this.outbox=outbox;this.leases=leases;this.tx=new TransactionTemplate(manager);this.clock=clock;this.checkpoint=checkpoint; }
 private void after(String stage) { try { checkpoint.after(stage); }catch(RuntimeException failure) { throw failure; }catch(Exception failure) { throw new IllegalStateException("CONFIRMATION_CHECKPOINT",failure); } }
 private Instant now() { return ConfirmationRules.normalized(clock.instant()); }
 private static boolean key(String value) { return value!=null&&value.matches("[A-Za-z0-9_-]{1,100}"); }
 private List<Unit> all(UUID order) { return jdbc.query("SELECT item_id,ordinal FROM marketplace.order_unit_lifecycle WHERE order_id=:id ORDER BY item_id::text,ordinal",Map.of("id",order),(r,n)->new Unit(r.getObject(1,UUID.class),r.getInt(2))); }
 @Override public View get(String number,OrderQueries.Authority authority) {
  return tx.execute(t->{var own=queries.requireOwned(number,authority);var policy=lifecycle.policy(own.id());var units=all(own.id()).stream().map(u->{var row=jdbc.queryForMap("SELECT state,revision FROM marketplace.order_unit_lifecycle WHERE item_id=:i AND ordinal=:o",Map.of("i",u.itemId(),"o",u.ordinal()));Instant delivered=lifecycle.delivered(u),due=lifecycle.due(own.id(),u,false);boolean eligible=own.state().equals("PAID")&&row.get("state").equals("OPEN")&&delivered!=null;String reason=eligible?"ELIGIBLE":!own.state().equals("PAID")?"ORDER_NOT_PAID":!row.get("state").equals("OPEN")?row.get("state").toString():"NOT_DELIVERED";return new Eligibility(u,row.get("state").toString(),((Number)row.get("revision")).longValue(),delivered,due,policy.get("status").toString(),eligible,reason);}).toList();return new View(number,own.state(),lifecycle.revision(own.id()),policy.get("status").toString(),units);});
 }
 @Override public Result confirm(String number,OrderQueries.Authority authority,Command command) {
  if(command==null||!key(command.key())||command.expectedRevision()==null||command.expectedRevision()<0) { throw new OrderRejected(400); }var units=AftercareLifecycle.canonical(command.units());String hash=AftercareLifecycle.hash(command.expectedRevision()+"|"+AftercareLifecycle.unitsText(units));
  return tx.execute(t->{var own=queries.lockOwned(number,authority);for(var u:units) { lifecycle.lockUnit(own.id(),u); }queries.lockOwned(number,authority);var prior=receipt(own.id(),command.key(),hash);if(prior!=null) { return prior; }
   if(!own.state().equals("PAID")||lifecycle.revision(own.id())!=command.expectedRevision()) { throw new OrderRejected(409); }lifecycle.policy(own.id());for(var u:units) { if(!lifecycle.lockUnit(own.id(),u).get("state").equals("OPEN")||lifecycle.delivered(u)==null) { throw new OrderRejected(409); } }
   return write(own.id(),command.key(),hash,units,"DIRECT",authority.member()==null?"GUEST":authority.member().toString(),now());
  });
 }
 private Result receipt(UUID order,String key,String fingerprint) { var rows=jdbc.queryForList("SELECT fingerprint,result::text FROM marketplace.order_confirmation WHERE order_id=:id AND request_key=:key",Map.of("id",order,"key",key));if(rows.isEmpty()) { return null; }if(!rows.getFirst().get("fingerprint").equals(fingerprint)) { throw new OrderRejected(409); }return mapper.readValue((String)rows.getFirst().get("result"),Result.class); }
 private Result write(UUID order,String key,String hash,List<Unit> units,String cause,String actor,Instant at) {
  UUID id=UUID.randomUUID();for(var u:units) { jdbc.update("UPDATE marketplace.order_unit_lifecycle SET state='CONFIRMED',revision=revision+1,confirmation_id=:id WHERE item_id=:i AND ordinal=:o",Map.of("id",id,"i",u.itemId(),"o",u.ordinal())); }after("UNITS");long revision=lifecycle.bump(order);var result=new Result(id,units,at,cause,revision);
  jdbc.update("INSERT INTO marketplace.order_confirmation(id,order_id,request_key,fingerprint,cause,actor,confirmed_at,result) VALUES(:id,:order,:key,:hash,:cause,:actor,:at,CAST(:result AS jsonb))",new MapSqlParameterSource("id",id).addValue("order",order).addValue("key",key).addValue("hash",hash).addValue("cause",cause).addValue("actor",actor).addValue("at",Timestamp.from(at)).addValue("result",mapper.writeValueAsString(result)));for(var u:units) {jdbc.update("INSERT INTO marketplace.order_confirmation_unit(item_id,ordinal,order_id,confirmation_id) VALUES(:i,:o,:order,:id)",Map.of("i",u.itemId(),"o",u.ordinal(),"order",order,"id",id));}after("RECEIPT");event(order,id,"CONFIRMED",at);after("HISTORY");
  after("SELLER_CLOSURE");if(jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE order_id=:id AND state<>'CONFIRMED'",Map.of("id",order),Integer.class)==0) { String number=jdbc.queryForObject("SELECT order_number FROM marketplace.purchase_order WHERE id=:id",Map.of("id",order),String.class);orders.transition(number,"CLOSED"); }after("PARENT_CLOSED");
  outbox.append(new Outbox.Event(id,new Jobs.Command("ORDER_CONFIRMATION_RECORDED","confirmation-recorded:"+id,id.toString(),id.toString(),at)));after("OUTBOX");return result;
 }
 private void event(UUID order,UUID cause,String action,Instant at) { jdbc.update("INSERT INTO marketplace.order_lifecycle_event(id,order_id,cause_id,action,occurred_at) VALUES(:id,:order,:cause,:action,:at)",Map.of("id",UUID.randomUUID(),"order",order,"cause",cause,"action",action,"at",Timestamp.from(at))); }
 private void lifecycleOutbox(UUID cause,String action,Instant at) { String key="order-unit-lifecycle:"+cause+":"+action;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("ORDER_UNIT_LIFECYCLE_RECORDED",key,cause.toString(),action,at))); }
 @Override public Hold acquire(UUID order,UUID hold,UUID cause,String kind,List<Unit> input) {
  AftercareLifecycle.transaction();if(order==null||hold==null||cause==null||!List.of("CANCEL","RETURN","EXCHANGE").contains(kind==null?"":kind)) { throw new OrderRejected(400); }var units=AftercareLifecycle.canonical(input);String fingerprint=AftercareLifecycle.hash(order+"|"+hold+"|"+cause+"|"+kind+"|"+AftercareLifecycle.unitsText(units));String state=lifecycle.lockOrder(order);for(var u:units) { lifecycle.lockUnit(order,u); }
  var prior=jdbc.queryForList("SELECT * FROM marketplace.order_unit_hold WHERE id=:hold OR cause_id=:cause FOR UPDATE",Map.of("hold",hold,"cause",cause));if(!prior.isEmpty()) { if(prior.size()!=1||!prior.getFirst().get("fingerprint").equals(fingerprint)) { throw new OrderRejected(409); }return new Hold(hold,order,cause,kind,units,"ACTIVE"); }
  if(!state.equals("PAID")) { throw new OrderRejected(409); }Instant at=now();for(var u:units) { if(!lifecycle.lockUnit(order,u).get("state").equals("OPEN")) { throw new OrderRejected(409); }
   if(kind.equals("CANCEL")) { if(jdbc.queryForObject("SELECT count(*) FROM marketplace.order_dispatch_unit WHERE item_id=:i AND ordinal=:o",Map.of("i",u.itemId(),"o",u.ordinal()),Integer.class)!=0) { throw new OrderRejected(409); } }
   else { Instant due=lifecycle.due(order,u,true);if(due==null||!ConfirmationRules.claim(at,due)) { throw new OrderRejected(409); } }
  }
  jdbc.update("INSERT INTO marketplace.order_unit_hold(id,order_id,cause_id,kind,fingerprint,state,acquired_at) VALUES(:id,:order,:cause,:kind,:hash,'ACTIVE',:at)",Map.of("id",hold,"order",order,"cause",cause,"kind",kind,"hash",fingerprint,"at",Timestamp.from(at)));after("HOLD");
  for(var u:units) { jdbc.update("INSERT INTO marketplace.order_hold_unit(hold_id,item_id,ordinal) VALUES(:h,:i,:o)",Map.of("h",hold,"i",u.itemId(),"o",u.ordinal()));jdbc.update("UPDATE marketplace.order_unit_lifecycle SET state='HELD',active_hold_id=:h,revision=revision+1 WHERE item_id=:i AND ordinal=:o",Map.of("h",hold,"i",u.itemId(),"o",u.ordinal())); }after("UNITS");lifecycle.bump(order);event(order,hold,"HELD",at);after("HISTORY");lifecycleOutbox(hold,"HELD",at);after("OUTBOX");return new Hold(hold,order,cause,kind,units,"ACTIVE");
 }
 @Override public Hold release(UUID order,UUID hold,UUID cause,String outcome) { if(!List.of("REJECTED","WITHDRAWN").contains(outcome==null?"":outcome)) { throw new OrderRejected(409); }return finishHold(order,hold,cause,outcome,false); }
 @Override public Hold completeRefund(UUID order,UUID hold,UUID cause,String reference) { if(reference==null||reference.isBlank()||reference.length()>200) { throw new OrderRejected(400); }return finishHold(order,hold,cause,reference,true); }
 private Hold finishHold(UUID order,UUID hold,UUID cause,String outcome,boolean refund) {
  AftercareLifecycle.transaction();if(order==null||hold==null||cause==null) { throw new OrderRejected(400); }lifecycle.lockOrder(order);var units=jdbc.query("SELECT item_id,ordinal FROM marketplace.order_hold_unit WHERE hold_id=:id ORDER BY item_id::text,ordinal",Map.of("id",hold),(r,n)->new Unit(r.getObject(1,UUID.class),r.getInt(2)));for(var u:units) { lifecycle.lockUnit(order,u); }
  var rows=jdbc.queryForList("SELECT * FROM marketplace.order_unit_hold WHERE id=:id FOR UPDATE",Map.of("id",hold));if(rows.size()!=1||!rows.getFirst().get("order_id").equals(order)||!rows.getFirst().get("cause_id").equals(cause)) { throw new OrderRejected(409); }var h=rows.getFirst();String target=refund?"REFUNDED":"RELEASED",hash=AftercareLifecycle.hash(order+"|"+hold+"|"+cause+"|"+target+"|"+outcome);if(!h.get("state").equals("ACTIVE")) { if(!h.get("state").equals(target)||!hash.equals(h.get("outcome_fingerprint"))) { throw new OrderRejected(409); }return new Hold(hold,order,cause,h.get("kind").toString(),units,target); }
  var at=now();for(var u:units) { var row=lifecycle.lockUnit(order,u);if(!row.get("state").equals("HELD")||!hold.equals(row.get("active_hold_id"))) { throw new OrderRejected(409); }jdbc.update("UPDATE marketplace.order_unit_lifecycle SET state=:state,active_hold_id=NULL,refund_cause_id=:refund,revision=revision+1 WHERE item_id=:i AND ordinal=:o",new MapSqlParameterSource("state",refund?"REFUNDED":"OPEN").addValue("refund",refund?cause:null).addValue("i",u.itemId()).addValue("o",u.ordinal())); }after("UNITS");
  jdbc.update("UPDATE marketplace.order_unit_hold SET state=:state,outcome=:outcome,outcome_fingerprint=:hash WHERE id=:id",Map.of("state",target,"outcome",outcome,"hash",hash,"id",hold));after("HOLD");lifecycle.bump(order);event(order,hold,target,at);after("HISTORY");if(!refund) { for(var u:units) { lifecycle.schedule(order,u,at); } }after("JOB");lifecycleOutbox(hold,target,at);after("OUTBOX");return new Hold(hold,order,cause,h.get("kind").toString(),units,target);
 }
 public JobHandler.Result automatic(Jobs.Job job) {
  UUID schedule;try { schedule=UUID.fromString(job.command().payload()); }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  return tx.execute(t->{var rows=jdbc.queryForList("SELECT * FROM marketplace.order_confirmation_schedule WHERE id=:id",Map.of("id",schedule));if(rows.size()!=1) { throw new OrderRejected(409); }var s=rows.getFirst();UUID order=(UUID)s.get("order_id");String state=lifecycle.lockOrder(order);var unit=new Unit((UUID)s.get("item_id"),((Number)s.get("ordinal")).intValue());var row=lifecycle.lockUnit(order,unit);jdbc.queryForList("SELECT id FROM marketplace.order_confirmation_schedule WHERE id=:id FOR UPDATE",Map.of("id",schedule));leases.requireOwned(job.id(),job.ownerToken());
   if(!job.command().type().equals(s.get("job_type"))||!job.command().causeKey().equals(schedule.toString())||!job.command().nextRunAt().equals(((Timestamp)s.get("scheduled_at")).toInstant())) { throw new OrderRejected(409); }
   if(!state.equals("PAID")||!row.get("state").equals("OPEN")||((Number)row.get("revision")).longValue()!=((Number)s.get("generation")).longValue()) { return JobHandler.Result.COMPLETE; }
   var policy=lifecycle.policy(order);var due=lifecycle.due(order,unit,false);if(due==null||!policy.get("policy_version").equals(s.get("policy_version"))||!due.equals(((Timestamp)s.get("due_at")).toInstant())) { throw new OrderRejected(409); }
   var at=now();if(at.isBefore(((Timestamp)policy.get("effective_at")).toInstant())||!ConfirmationRules.automatic(at,due)) { return JobHandler.Result.RETRY; }
   write(order,"auto:"+schedule,AftercareLifecycle.hash(schedule.toString()),List.of(unit),"AUTO","order-worker",at);return JobHandler.Result.COMPLETE;
  });
 }
}
