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
 public JdbcRefunds(NamedParameterJdbcTemplate jdbc,RefundProvider provider,Outbox outbox,JobLeases leases,PlatformTransactionManager manager,Checkpoint checkpoint) { this.jdbc=jdbc;this.provider=provider;this.outbox=outbox;this.leases=leases;this.tx=new TransactionTemplate(manager);this.checkpoint=checkpoint;this.queryJobs=leases instanceof Jobs value?value:null; }
 private java.time.Clock queryClock;
 private Jobs queryJobs;private CurrentRefundSystem system;
 @Autowired void queryJobs(Jobs value){queryJobs=value;}
 @Autowired void systemAuthority(CurrentRefundSystem value){system=value;}
 private RefundQuerySchedule schedule(){return new RefundQuerySchedule(jdbc,outbox,leases);}
 private PaymentProviderConfiguration.Settings settings=new PaymentProviderConfiguration.Settings("","","","","","");
 @Autowired void refundSettings(PaymentProviderConfiguration.Settings value) { settings=value; }
 private void after(String stage) { try { checkpoint.after(stage); }catch(RuntimeException e) { throw e; }catch(Exception e) { throw new IllegalStateException("REFUND_CHECKPOINT",e); } }
 private static void transaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); } }
 private void order(UUID id) { transaction();if(jdbc.queryForList("SELECT id FROM marketplace.purchase_order WHERE id=:id FOR UPDATE",Map.of("id",id)).size()!=1) { throw new OrderRejected(409); } }
 @Override public Original original(UUID order) {
  order(order);var rows=jdbc.queryForList("SELECT id,amount,mode FROM marketplace.payment_attempt WHERE order_id=:order AND state='SUCCEEDED' AND compensation='NONE'",Map.of("order",order));if(rows.size()!=1) { throw new OrderRejected(409); }var row=rows.getFirst();return new Original((UUID)row.get("id"),order,((Number)row.get("amount")).longValue(),row.get("mode").toString());
 }
 @Override public Attempt reserve(Reserve c) {
  transaction();if(c==null||c.attemptId()==null||c.causeId()==null||c.paymentId()==null||c.orderId()==null||c.sellerOrderId()==null||c.claimId()==null||!java.util.List.of("ITEM","SHIPPING").contains(c.purpose()==null?"":c.purpose())||c.sequence()<1||c.amount()<0||c.originalReference()==null||c.originalReference().isBlank()||c.originalReference().length()>200) { throw new OrderRejected(400); }
  var original=original(c.orderId());if(!original.paymentId().equals(c.paymentId())||!java.util.List.of("DEMO","SANDBOX").contains(original.mode())||c.amount()>0&&!provider.supports(original.mode())) { throw new OrderRejected(409); }
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
 /** A verified payment notification is only a signal for the original bounded schedule. */
 boolean signal(UUID payment,String digest,String status) {
  transaction();var paid=jdbc.queryForList("SELECT order_id FROM marketplace.payment_attempt WHERE id=:id AND mode='SANDBOX' AND state='SUCCEEDED' AND compensation='NONE'",Map.of("id",payment));
  if(paid.size()!=1){return false;}order((UUID)paid.getFirst().get("order_id"));
  var p=Map.of("payment",payment,"digest",digest,"status",status);
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_signal WHERE payment_id=:payment AND payload_sha256=:digest",p,Integer.class)>0){return true;}
  jdbc.queryForList("SELECT payment_id FROM marketplace.payment_refund_budget WHERE payment_id=:payment FOR UPDATE",p);
  var attempts=jdbc.queryForList("SELECT id FROM marketplace.payment_refund_attempt WHERE payment_id=:payment AND amount>0 AND state IN('PROCESSING','UNKNOWN') AND started_at IS NOT NULL ORDER BY id FOR UPDATE",p);
  if(attempts.isEmpty()){return false;}
  for(var attempt:attempts){schedule().initialize((UUID)attempt.get("id"));}
  jdbc.update("INSERT INTO marketplace.payment_refund_signal(payment_id,payload_sha256,provider_status) VALUES(:payment,:digest,:status) ON CONFLICT DO NOTHING",p);
  return true;
 }
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
 private record Prepared(RefundProvider.Request request,boolean submit,UUID callId) { }
 public JobHandler.Result process(Jobs.Job job) {
  if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("REFUND_WORKER_REQUIRES_OUTSIDE_TRANSACTION"); }UUID id;int slot;try {var parts=job.command().payload().split("\\|",-1);id=UUID.fromString(parts[0]);slot=job.command().type().equals(RefundQuerySchedule.TYPE)&&parts.length==2?Integer.parseInt(parts[1]):0;if(parts.length!=(slot==0?1:2)||slot<0||slot>6){throw new IllegalArgumentException();}}catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  if(!id.toString().equals(job.command().causeKey())) { throw new OrderRejected(409); }
  Prepared prepared;try{prepared=tx.execute(t->{var row=locked(id);String state=row.get("state").toString();actualJob(job);requireOperation(job);if(slot==0&&((Number)row.get("amount")).longValue()>0&&java.util.List.of("PROCESSING","UNKNOWN").contains(state)&&!explicitQuery(id,job)){if(!job.command().dedupeKey().equals("refund-submit:"+id)){throw new OrderRejected(409);}schedule().initialize(id);leases.requireOwned(job.id(),job.ownerToken());return null;}if(slot==0&&state.equals("UNKNOWN")&&obsoleteOperation(job,((Number)row.get("version")).longValue())){return null;}if(slot>0&&state.equals("RESERVED")){throw new OrderRejected(409);}if(busy(id,job)){throw new QueryBusy();}if(slot>0&&!schedule().enter(id,slot,job,state,queryClock)){leases.requireOwned(job.id(),job.ownerToken());return null;}if(java.util.List.of("SUCCEEDED","FAILED","WITHDRAWN").contains(state)) { return null; }boolean submit=state.equals("RESERVED");
   jdbc.update("UPDATE marketplace.payment_refund_attempt SET state='PROCESSING',started_at=COALESCE(started_at,clock_timestamp()),fence_token=:token,version=version+1 WHERE id=:id",Map.of("token",job.ownerToken(),"id",id));var a=get(id);event(id,a.version(),submit?"PROCESSING":"QUERYING",null);schedule().initialize(id);jdbc.update("INSERT INTO marketplace.payment_refund_execution(attempt_id,job_id,owner_token) VALUES(:id,:job,:token) ON CONFLICT(attempt_id) DO UPDATE SET job_id=EXCLUDED.job_id,owner_token=EXCLUDED.owner_token",Map.of("id",id,"job",job.id(),"token",job.ownerToken()));after("PREPARE");var original=original(a.orderId());return prepareProvider(a,row.get("submit_key").toString(),original.mode(),submit,job);
  });}catch(QueryBusy busy){return JobHandler.Result.RETRY;}if(prepared==null) { return JobHandler.Result.COMPLETE; }after("PREPARED");
  RefundProvider.Outcome observed=prepared.request().amount()==0?new RefundProvider.Outcome("SUCCEEDED",id,0,"ZERO-REFUND-"+id):prepared.submit()?provider.submit(prepared.request()):provider.query(prepared.request());RefundProvider.Outcome outcome=prepared.callId()==null?observed:recordProvider(prepared,job,observed);if(prepared.callId()!=null){after("PROVIDER_RECEIPT");}after("PROVIDER");
  tx.executeWithoutResult(t->{var row=locked(id);requireOperation(job);leases.requireOwned(job.id(),job.ownerToken());if(!job.ownerToken().equals(row.get("fence_token"))) { throw new OrderRejected(409); }
   var a=projection(row);if(!a.state().equals("PROCESSING")) { throw new OrderRejected(409); }String state=outcome.state();if(!id.equals(outcome.refundAttemptId())||outcome.amount()!=a.amount()||!java.util.List.of("SUCCEEDED","FAILED","UNKNOWN").contains(state)||state.equals("SUCCEEDED")&&(outcome.reference()==null||outcome.reference().isBlank()||outcome.reference().length()>200)) { throw new OrderRejected(409); }
   var p=new MapSqlParameterSource("id",id).addValue("payment",a.paymentId()).addValue("amount",a.amount()).addValue("state",state).addValue("reference",state.equals("SUCCEEDED")?outcome.reference():null).addValue("at",state.equals("SUCCEEDED")?Timestamp.from(now()):null);
   if(!state.equals("UNKNOWN")) { jdbc.update("UPDATE marketplace.payment_refund_budget SET reserved=reserved-:amount,succeeded=succeeded+CASE WHEN :state='SUCCEEDED' THEN :amount ELSE 0 END WHERE payment_id=:payment",p); }
   jdbc.update("UPDATE marketplace.payment_refund_attempt SET state=:state,result_reference=:reference,result_at=:at,version=version+1 WHERE id=:id",p);after("RESULT");var saved=get(id);event(id,saved.version(),state,state.equals("FAILED")?"PROVIDER_DEFINITIVE_NO_EFFECT":null);after("HISTORY");
   String key="refund-result:"+id+":"+saved.version();String payload=id+"|"+saved.version();outbox.append(new Outbox.Event(named(key),new Jobs.Command("REFUND_RESULT_RECORDED.v1",key,id.toString(),payload,now())));after("RESULT_OUTBOX");requireOperation(job);leases.requireOwned(job.id(),job.ownerToken());
  });return JobHandler.Result.COMPLETE;
 }
 private static final class QueryBusy extends RuntimeException { private static final long serialVersionUID=1L; }
 private void actualJob(Jobs.Job job){if(queryJobs==null){throw new OrderRejected(409);}var actual=queryJobs.find(job.id()).orElseThrow(()->new OrderRejected(409));if(!actual.command().equals(job.command())||!java.util.Objects.equals(actual.ownerToken(),job.ownerToken())||!java.util.List.of("REFUND_SUBMIT.v1",RefundQuerySchedule.TYPE).contains(actual.command().type())){throw new OrderRejected(409);}leases.requireOwned(job.id(),job.ownerToken());}
 private boolean busy(UUID id,Jobs.Job job){var rows=jdbc.queryForList("SELECT job_id,owner_token FROM marketplace.payment_refund_execution WHERE attempt_id=:id",Map.of("id",id));if(rows.isEmpty()||rows.getFirst().get("job_id").equals(job.id())){return false;}var r=rows.getFirst();try{leases.requireOwned((UUID)r.get("job_id"),(UUID)r.get("owner_token"));return true;}catch(IllegalStateException lost){return false;}}
 private boolean explicitQuery(UUID attempt,Jobs.Job job){return jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_request WHERE attempt_id=:id AND job_id=:job",Map.of("id",attempt,"job",job.id()),Integer.class)==1;}
 private boolean obsoleteOperation(Jobs.Job job,long version){var original=jdbc.queryForList("SELECT original_version FROM marketplace.payment_refund_operation WHERE job_id=:id",Map.of("id",job.id()),Long.class);return !original.isEmpty()&&version>original.getFirst();}
 private void requireOperation(Jobs.Job job){var rows=jdbc.queryForList("SELECT actor,session_id,auth_version FROM marketplace.payment_refund_operation WHERE job_id=:id",Map.of("id",job.id()));if(!rows.isEmpty()){if(system==null||rows.size()!=1){throw new OrderRejected(403);}var r=rows.getFirst();system.require(new com.example.marketplace.payment.api.Payments.Authority(r.get("session_id").toString(),(UUID)r.get("actor"),((Number)r.get("auth_version")).longValue(),null));}}
 private Prepared prepareProvider(Attempt a,String key,String mode,boolean submit,Jobs.Job job) {
  if(!mode.equals("SANDBOX")||a.amount()==0){return new Prepared(new RefundProvider.Request(a.paymentId(),a.orderId(),a.id(),key,a.amount(),mode),submit,null);}
  var saved=jdbc.queryForList("SELECT * FROM marketplace.payment_refund_provider_request WHERE attempt_id=:id",Map.of("id",a.id()));
  if(saved.isEmpty()){
   if(!submit||!settings.ready()){throw new OrderRejected(409);}var binding=jdbc.queryForMap("SELECT provider_payment_key,provider_order_id,amount FROM marketplace.payment_toss_binding WHERE attempt_id=:id",Map.of("id",a.paymentId()));var origin=new TossRefundProvider.Binding(binding.get("provider_payment_key").toString(),binding.get("provider_order_id").toString(),((Number)binding.get("amount")).longValue());String marker="F-CUSTOMER-"+a.id();var request=new RefundProvider.Request(a.paymentId(),a.orderId(),a.id(),key,a.amount(),mode,marker,null,null);String fingerprint=TossRefundProvider.fingerprint(request,origin,settings.mid(),settings.apiVersion());
   var values=new MapSqlParameterSource("id",a.id()).addValue("payment",a.paymentId()).addValue("order",a.orderId()).addValue("amount",a.amount()).addValue("binding",TossRefundProvider.sha(origin.paymentKey())).addValue("providerOrder",origin.orderId()).addValue("merchant",settings.mid()).addValue("api",settings.apiVersion()).addValue("paid",origin.amount()).addValue("key",key).addValue("marker",marker).addValue("hash",fingerprint);
   jdbc.update("INSERT INTO marketplace.payment_refund_provider_request(attempt_id,payment_id,order_id,amount,provider,mode,binding_hash,provider_order_id,merchant_id,api_version,currency,original_amount,submit_key,marker,request_fingerprint,request_body,prepared_at) SELECT :id,:payment,:order,:amount,'TOSS','SANDBOX',:binding,:providerOrder,:merchant,:api,'KRW',:paid,:key,:marker,:hash,jsonb_build_object('cancelAmount',CAST(:amount AS bigint),'cancelReason',CAST(:marker AS text)),started_at FROM marketplace.payment_refund_attempt WHERE id=:id",values);
  }
  var row=jdbc.queryForMap("SELECT * FROM marketplace.payment_refund_provider_request WHERE attempt_id=:id",Map.of("id",a.id()));var receipts=jdbc.queryForList("SELECT transaction_key FROM marketplace.payment_refund_provider_receipt WHERE attempt_id=:id",Map.of("id",a.id()),String.class);String reference=receipts.isEmpty()?null:receipts.getFirst();UUID call=UUID.randomUUID();
  jdbc.update("INSERT INTO marketplace.payment_refund_provider_call(id,attempt_id,request_fingerprint,operation,job_id,owner_token,started_at) VALUES(:call,:attempt,:hash,:operation,:job,:owner,clock_timestamp())",Map.of("call",call,"attempt",a.id(),"hash",row.get("request_fingerprint"),"operation",submit?"POST":"GET","job",job.id(),"owner",job.ownerToken()));
  return new Prepared(new RefundProvider.Request(a.paymentId(),a.orderId(),a.id(),key,a.amount(),mode,row.get("marker").toString(),row.get("request_fingerprint").toString(),reference),submit,call);
 }
 private RefundProvider.Outcome recordProvider(Prepared prepared,Jobs.Job job,RefundProvider.Outcome observed) {
  return tx.execute(t->{var request=prepared.request();var row=locked(request.refundAttemptId());requireOperation(job);leases.requireOwned(job.id(),job.ownerToken());if(!job.ownerToken().equals(row.get("fence_token"))||!row.get("state").equals("PROCESSING")){throw new OrderRejected(409);}
   if(!request.refundAttemptId().equals(observed.refundAttemptId())||request.amount()!=observed.amount()){throw new OrderRejected(409);}var receipt=observed.receipt();boolean succeeded=observed.state().equals("SUCCEEDED")&&receipt!=null;
   if(succeeded){var origin=jdbc.queryForMap("SELECT * FROM marketplace.payment_refund_provider_request WHERE attempt_id=:id",Map.of("id",request.refundAttemptId()));if(!receipt.provider().equals("TOSS")||!receipt.mode().equals("SANDBOX")||!receipt.paymentId().equals(request.paymentId())||!receipt.orderId().equals(request.orderId())||receipt.amount()!=request.amount()||receipt.originalAmount()!=((Number)origin.get("original_amount")).longValue()||!receipt.merchantId().equals(origin.get("merchant_id"))||!receipt.currency().equals("KRW")||!receipt.requestFingerprint().equals(request.fingerprint())||!receipt.transactionKey().equals(observed.reference())){throw new OrderRejected(409);}if(!prepared.submit()&&!receipt.transactionKey().equals(request.transactionKey())){succeeded=false;}}
   var values=new MapSqlParameterSource("call",prepared.callId()).addValue("attempt",request.refundAttemptId()).addValue("hash",request.fingerprint()).addValue("state",succeeded?"SUCCEEDED":"UNKNOWN").addValue("reference",succeeded?receipt.transactionKey():null).addValue("response",succeeded?receipt.responseFingerprint():null).addValue("evidence",succeeded?receipt.evidence():observed.evidence()==null?"{}":observed.evidence());
   jdbc.update("INSERT INTO marketplace.payment_refund_provider_observation(call_id,attempt_id,request_fingerprint,state,transaction_key,response_fingerprint,evidence) VALUES(:call,:attempt,:hash,:state,:reference,:response,CAST(:evidence AS jsonb))",values);
   if(succeeded&&prepared.submit()){values.addValue("merchant",receipt.merchantId()).addValue("amount",receipt.amount());jdbc.update("INSERT INTO marketplace.payment_refund_provider_receipt(attempt_id,call_id,request_fingerprint,provider,mode,merchant_id,binding_hash,transaction_key,amount,response_fingerprint,evidence) SELECT :attempt,:call,:hash,'TOSS','SANDBOX',:merchant,binding_hash,:reference,:amount,:response,CAST(:evidence AS jsonb) FROM marketplace.payment_refund_provider_request WHERE attempt_id=:attempt",values);}
   leases.requireOwned(job.id(),job.ownerToken());return succeeded?observed:new RefundProvider.Outcome("UNKNOWN",request.refundAttemptId(),request.amount(),null,null,observed.evidence());
  });
 }
 private void event(UUID id,long version,String action,String reason) { jdbc.update("INSERT INTO marketplace.payment_refund_event(id,attempt_id,version,action,reason) VALUES(:id,:attempt,:version,:action,:reason)",new MapSqlParameterSource("id",UUID.randomUUID()).addValue("attempt",id).addValue("version",version).addValue("action",action).addValue("reason",reason)); }
 private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant(); }
 private static UUID named(String value) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
 private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}


