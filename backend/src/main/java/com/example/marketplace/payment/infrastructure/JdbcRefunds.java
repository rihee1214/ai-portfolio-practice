package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.payment.api.RefundProvider;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class JdbcRefunds implements Refunds {
 public interface Checkpoint { void after(String stage) throws Exception; }
 private final NamedParameterJdbcTemplate jdbc;private final RefundProvider provider;private final Outbox outbox;private final JobLeases leases;private final TransactionTemplate tx;private final Checkpoint checkpoint;
 @Autowired public JdbcRefunds(NamedParameterJdbcTemplate jdbc,RefundProvider provider,Outbox outbox,JobLeases leases,PlatformTransactionManager manager) { this(jdbc,provider,outbox,leases,manager,s->{ }); }
 public JdbcRefunds(NamedParameterJdbcTemplate jdbc,RefundProvider provider,Outbox outbox,JobLeases leases,PlatformTransactionManager manager,Checkpoint checkpoint) { this.jdbc=jdbc;this.provider=provider;this.outbox=outbox;this.leases=leases;this.tx=new TransactionTemplate(manager);this.checkpoint=checkpoint; }
 private void after(String stage) { try { checkpoint.after(stage); }catch(RuntimeException e) { throw e; }catch(Exception e) { throw new IllegalStateException("REFUND_CHECKPOINT",e); } }
 private static void transaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); } }
 private void order(UUID id) { transaction();if(jdbc.queryForList("SELECT id FROM marketplace.purchase_order WHERE id=:id FOR UPDATE",Map.of("id",id)).size()!=1) { throw new OrderRejected(409); } }
 @Override public Original original(UUID order) {
  order(order);var rows=jdbc.queryForList("SELECT id,amount,mode FROM marketplace.payment_attempt WHERE order_id=:order AND state='SUCCEEDED' AND compensation='NONE'",Map.of("order",order));if(rows.size()!=1) { throw new OrderRejected(409); }var row=rows.getFirst();return new Original((UUID)row.get("id"),order,((Number)row.get("amount")).longValue(),row.get("mode").toString());
 }
 @Override public Attempt reserve(Reserve c) {
  transaction();if(c==null||c.attemptId()==null||c.causeId()==null||c.paymentId()==null||c.orderId()==null||c.sellerOrderId()==null||c.claimId()==null||!java.util.List.of("ITEM","SHIPPING").contains(c.purpose()==null?"":c.purpose())||c.sequence()<1||c.amount()<0||c.originalReference()==null||c.originalReference().isBlank()||c.originalReference().length()>200) { throw new OrderRejected(400); }
  var original=original(c.orderId());if(!original.paymentId().equals(c.paymentId())||!original.mode().equals("DEMO")) { throw new OrderRejected(409); }
  var p=new MapSqlParameterSource("id",c.attemptId()).addValue("cause",c.causeId()).addValue("payment",c.paymentId()).addValue("order",c.orderId()).addValue("seller",c.sellerOrderId()).addValue("claim",c.claimId()).addValue("purpose",c.purpose()).addValue("seq",c.sequence()).addValue("amount",c.amount()).addValue("ref",c.originalReference()).addValue("hash",hash(c.toString())).addValue("key","customer-refund:"+c.attemptId()).addValue("paid",original.amount());
  jdbc.update("INSERT INTO marketplace.payment_refund_budget(payment_id,order_id,paid) VALUES(:payment,:order,:paid) ON CONFLICT DO NOTHING",p);jdbc.queryForList("SELECT payment_id FROM marketplace.payment_refund_budget WHERE payment_id=:payment FOR UPDATE",p);
  var prior=jdbc.queryForList("SELECT id,fingerprint FROM marketplace.payment_refund_attempt WHERE id=:id OR cause_id=:cause OR(payment_id=:payment AND claim_id=:claim AND purpose=:purpose AND sequence=:seq) FOR UPDATE",p);
  if(!prior.isEmpty()) { if(prior.size()!=1||!c.attemptId().equals(prior.getFirst().get("id"))||!hash(c.toString()).equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }return get(c.attemptId()); }
  if(jdbc.update("UPDATE marketplace.payment_refund_budget SET reserved=reserved+:amount WHERE payment_id=:payment AND :amount<=paid-succeeded-reserved",p)!=1) { throw new OrderRejected(409); }
  jdbc.update("INSERT INTO marketplace.payment_refund_attempt(id,cause_id,payment_id,order_id,seller_order_id,claim_id,purpose,sequence,amount,original_reference,fingerprint,state,submit_key) VALUES(:id,:cause,:payment,:order,:seller,:claim,:purpose,:seq,:amount,:ref,:hash,'RESERVED',:key)",p);event(c.attemptId(),0,"RESERVED",null);after("RESERVE");Instant at=now();String key="refund-submit:"+c.attemptId();outbox.append(new Outbox.Event(named(key),new Jobs.Command("REFUND_SUBMIT.v1",key,c.attemptId().toString(),c.attemptId().toString(),at)));after("RESERVE_OUTBOX");return get(c.attemptId());
 }
 private Map<String,Object> locked(UUID id) {
  transaction();var identity=jdbc.queryForList("SELECT order_id,payment_id FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",id));if(identity.size()!=1) { throw new OrderRejected(404); }var row=identity.getFirst();order((UUID)row.get("order_id"));jdbc.queryForList("SELECT payment_id FROM marketplace.payment_refund_budget WHERE payment_id=:id FOR UPDATE",Map.of("id",row.get("payment_id")));return jdbc.queryForMap("SELECT * FROM marketplace.payment_refund_attempt WHERE id=:id FOR UPDATE",Map.of("id",id));
 }
 @Override public Attempt get(UUID id) { return projection(locked(id)); }
 @Override public String originalReference(UUID id) { return locked(id).get("original_reference").toString(); }
 private Attempt projection(Map<String,Object> r) { return new Attempt((UUID)r.get("id"),(UUID)r.get("payment_id"),(UUID)r.get("order_id"),(UUID)r.get("seller_order_id"),(UUID)r.get("claim_id"),r.get("purpose").toString(),((Number)r.get("sequence")).intValue(),((Number)r.get("amount")).longValue(),r.get("state").toString(),((Number)r.get("version")).longValue(),(String)r.get("result_reference"),r.get("result_at")==null?null:((Timestamp)r.get("result_at")).toInstant()); }
 @Override public Attempt cancelUnsent(UUID id) {
  var row=locked(id);String state=row.get("state").toString();if(state.equals("WITHDRAWN")) { return projection(row); }if(state.equals("FAILED")) { return projection(row); }if(!state.equals("RESERVED")) { throw new OrderRejected(409); }
  jdbc.update("UPDATE marketplace.payment_refund_budget SET reserved=reserved-:amount WHERE payment_id=:id",Map.of("amount",row.get("amount"),"id",row.get("payment_id")));jdbc.update("UPDATE marketplace.payment_refund_attempt SET state='WITHDRAWN',version=version+1 WHERE id=:id",Map.of("id",id));event(id,((Number)row.get("version")).longValue()+1,"WITHDRAWN",null);return get(id);
 }
 @Override public Attempt retryFailed(UUID id,UUID next,UUID cause,String reason) {
  var row=locked(id);if(!row.get("state").equals("FAILED")||reason==null||reason.isBlank()||reason.length()>500) { throw new OrderRejected(409); }var old=projection(row);var result=reserve(new Reserve(next,cause,old.paymentId(),old.orderId(),old.sellerOrderId(),old.claimId(),old.purpose(),Math.incrementExact(old.sequence()),old.amount(),row.get("original_reference").toString()));event(next,0,"SUPPORT_RETRY",reason);return result;
 }
 @Override public Attempt requestQuery(UUID id,UUID cause,String reason) {
  if(id==null||cause==null||reason==null||reason.isBlank()||reason.length()>500||reason.codePoints().anyMatch(Character::isISOControl)) { throw new OrderRejected(400); }var row=locked(id);String fingerprint=hash(id+"|"+cause+"|"+reason);var prior=jdbc.queryForList("SELECT attempt_id,fingerprint FROM marketplace.payment_refund_query_request WHERE cause_id=:cause",Map.of("cause",cause));if(!prior.isEmpty()) { if(!id.equals(prior.getFirst().get("attempt_id"))||!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }return projection(row); }
  if(!java.util.List.of("PROCESSING","UNKNOWN").contains(row.get("state"))) { throw new OrderRejected(409); }String key="refund-query:"+id+":"+cause;UUID job=outbox.append(new Outbox.Event(named(key),new Jobs.Command("REFUND_SUBMIT.v1",key,id.toString(),id.toString(),now())));after("QUERY_OUTBOX");jdbc.update("INSERT INTO marketplace.payment_refund_query_request(cause_id,attempt_id,original_version,original_state,reason,fingerprint,job_id) VALUES(:cause,:attempt,:version,:state,:reason,:hash,:job)",Map.of("cause",cause,"attempt",id,"version",row.get("version"),"state",row.get("state"),"reason",reason,"hash",fingerprint,"job",job));after("QUERY_HISTORY");return projection(row);
 }
 private record Prepared(RefundProvider.Request request,boolean submit) { }
 public JobHandler.Result process(Jobs.Job job) {
  if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("REFUND_WORKER_REQUIRES_OUTSIDE_TRANSACTION"); }UUID id;try { id=UUID.fromString(job.command().payload()); }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  if(!id.toString().equals(job.command().causeKey())) { throw new OrderRejected(409); }
  Prepared prepared=tx.execute(t->{var row=locked(id);String state=row.get("state").toString();leases.requireOwned(job.id(),job.ownerToken());if(java.util.List.of("SUCCEEDED","FAILED","WITHDRAWN").contains(state)) { return null; }boolean submit=state.equals("RESERVED");
   jdbc.update("UPDATE marketplace.payment_refund_attempt SET state='PROCESSING',started_at=COALESCE(started_at,clock_timestamp()),fence_token=:token,version=version+1 WHERE id=:id",Map.of("token",job.ownerToken(),"id",id));var a=get(id);event(id,a.version(),submit?"PROCESSING":"QUERYING",null);after("PREPARE");return new Prepared(new RefundProvider.Request(a.paymentId(),a.orderId(),a.id(),row.get("submit_key").toString(),a.amount(),"DEMO"),submit);
  });if(prepared==null) { return JobHandler.Result.COMPLETE; }after("PREPARED");
  RefundProvider.Outcome outcome=prepared.request().amount()==0?new RefundProvider.Outcome("SUCCEEDED",id,0,"ZERO-REFUND-"+id):prepared.submit()?provider.submit(prepared.request()):provider.query(prepared.request());after("PROVIDER");
  tx.executeWithoutResult(t->{var row=locked(id);leases.requireOwned(job.id(),job.ownerToken());if(!job.ownerToken().equals(row.get("fence_token"))) { throw new OrderRejected(409); }
   var a=projection(row);if(!a.state().equals("PROCESSING")) { throw new OrderRejected(409); }String state=outcome.state();if(!id.equals(outcome.refundAttemptId())||outcome.amount()!=a.amount()||!java.util.List.of("SUCCEEDED","FAILED","UNKNOWN").contains(state)||state.equals("SUCCEEDED")&&(outcome.reference()==null||outcome.reference().isBlank()||outcome.reference().length()>200)) { throw new OrderRejected(409); }
   var p=new MapSqlParameterSource("id",id).addValue("payment",a.paymentId()).addValue("amount",a.amount()).addValue("state",state).addValue("reference",state.equals("SUCCEEDED")?outcome.reference():null).addValue("at",state.equals("SUCCEEDED")?Timestamp.from(now()):null);
   if(!state.equals("UNKNOWN")) { jdbc.update("UPDATE marketplace.payment_refund_budget SET reserved=reserved-:amount,succeeded=succeeded+CASE WHEN :state='SUCCEEDED' THEN :amount ELSE 0 END WHERE payment_id=:payment",p); }
   jdbc.update("UPDATE marketplace.payment_refund_attempt SET state=:state,result_reference=:reference,result_at=:at,version=version+1 WHERE id=:id",p);after("RESULT");var saved=get(id);event(id,saved.version(),state,state.equals("FAILED")?"PROVIDER_DEFINITIVE_NO_EFFECT":null);after("HISTORY");
   String key="refund-result:"+id+":"+saved.version();String payload=id+"|"+saved.version();outbox.append(new Outbox.Event(named(key),new Jobs.Command("REFUND_RESULT_RECORDED.v1",key,id.toString(),payload,now())));after("RESULT_OUTBOX");leases.requireOwned(job.id(),job.ownerToken());
  });return JobHandler.Result.COMPLETE;
 }
 private void event(UUID id,long version,String action,String reason) { jdbc.update("INSERT INTO marketplace.payment_refund_event(id,attempt_id,version,action,reason) VALUES(:id,:attempt,:version,:action,:reason)",new MapSqlParameterSource("id",UUID.randomUUID()).addValue("attempt",id).addValue("version",version).addValue("action",action).addValue("reason",reason)); }
 private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant(); }
 private static UUID named(String value) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
 private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}


