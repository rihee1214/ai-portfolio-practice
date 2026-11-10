package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Payments;
import com.example.marketplace.payment.api.Refunds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local financial projection and audited query-only operations; never assigns an unproved transaction. */
@Service @Profile("db")
public final class RefundReconciliation {
 public record Status(UUID attemptId,UUID sellerOrderId,UUID claimId,String purpose,int sequence,String amount,String state,long version,String mode,int automaticQueries,boolean reviewRequired,Instant nextDue,Instant deadline,boolean providerReceipt,String providerReference,Instant resultAt){ }
 public record Command(String key,long expectedVersion,String reason,String evidenceReference,String transactionKey){ }
 public record Accepted(UUID operationId,UUID attemptId,long acceptedVersion,String action,String state){ }
 private final NamedParameterJdbcTemplate jdbc;private final Refunds refunds;private final OrderQueries orders;private final CurrentRefundSystem system;private final TransactionTemplate tx;
 public RefundReconciliation(NamedParameterJdbcTemplate jdbc,Refunds refunds,OrderQueries orders,CurrentRefundSystem system,PlatformTransactionManager manager){this.jdbc=jdbc;this.refunds=refunds;this.orders=orders;this.system=system;tx=new TransactionTemplate(manager);}
 public List<Status> queue(Payments.Authority a){system.preliminary(a);var ids=jdbc.queryForList("SELECT id FROM marketplace.payment_refund_attempt WHERE state IN('PROCESSING','UNKNOWN') ORDER BY order_id,payment_id,id LIMIT 50",Map.of(),UUID.class);if(ids.isEmpty()){tx.executeWithoutResult(t->system.require(a));}return ids.stream().map(id->get(id,a)).toList();}
 public Status get(UUID id,Payments.Authority a){system.preliminary(a);return tx.execute(t->{var attempt=refunds.get(id);system.require(a);return view(attempt);});}
 public List<Map<String,Object>> evidence(UUID id,Payments.Authority a){system.preliminary(a);return tx.execute(t->{refunds.get(id);system.require(a);return jdbc.queryForList("SELECT c.operation,o.state,o.transaction_key,o.response_fingerprint,o.evidence::text AS evidence,o.observed_at FROM marketplace.payment_refund_provider_call c JOIN marketplace.payment_refund_provider_observation o ON o.call_id=c.id WHERE c.attempt_id=:id ORDER BY o.observed_at,c.id",Map.of("id",id));});}
 public List<Status> owned(String number,OrderQueries.Authority a){return tx.execute(t->{var owned=orders.lockOwned(number,a);var result=jdbc.queryForList("SELECT id FROM marketplace.payment_refund_attempt WHERE order_id=:id ORDER BY seller_order_id,claim_id,purpose,sequence",Map.of("id",owned.id()),UUID.class).stream().map(id->view(refunds.get(id))).toList();orders.lockOwned(number,a);return result;});}
 public Accepted command(UUID id,Payments.Authority a,Command c,boolean reconcile){
  if(c==null||c.key()==null||!c.key().matches("[A-Za-z0-9_-]{1,100}")||c.expectedVersion()<0||!text(c.reason(),500)||reconcile&&(!text(c.evidenceReference(),2000)||!text(c.transactionKey(),200))||!reconcile&&(c.evidenceReference()!=null||c.transactionKey()!=null)){throw new OrderRejected(400);}system.preliminary(a);
  return tx.execute(t->{var attempt=refunds.get(id);system.require(a);String action=reconcile?"RECONCILE":"QUERY";String fingerprint=hash(id+"|"+action+"|"+c.key()+"|"+c.expectedVersion()+"|"+field(c.reason())+field(c.evidenceReference())+field(c.transactionKey()));var p=new MapSqlParameterSource("actor",a.member()).addValue("key",c.key()).addValue("attempt",id);
   var old=jdbc.queryForList("SELECT * FROM marketplace.payment_refund_operation WHERE actor=:actor AND command_key=:key",p);if(!old.isEmpty()){var r=old.getFirst();if(!id.equals(r.get("attempt_id"))||!fingerprint.equals(r.get("fingerprint"))){throw new OrderRejected(409);}return accepted(r);}
   if(attempt.version()!=c.expectedVersion()||!List.of("PROCESSING","UNKNOWN").contains(attempt.state())){throw new OrderRejected(409);}UUID operation=UUID.randomUUID();refunds.requestQuery(id,operation,c.reason());UUID job=jdbc.queryForObject("SELECT job_id FROM marketplace.payment_refund_query_request WHERE cause_id=:id",Map.of("id",operation),UUID.class);
   p.addValue("id",operation).addValue("version",attempt.version()).addValue("action",action).addValue("reason",c.reason()).addValue("reference",c.evidenceReference()).addValue("transaction",c.transactionKey()).addValue("hash",fingerprint).addValue("job",job).addValue("session",a.browser()).addValue("auth",a.version());
   jdbc.update("INSERT INTO marketplace.payment_refund_operation(id,attempt_id,actor,session_id,auth_version,command_key,original_version,action,reason,evidence_reference,requested_transaction,fingerprint,job_id) VALUES(:id,:attempt,:actor,:session,:auth,:key,:version,:action,:reason,:reference,:transaction,:hash,:job)",p);system.require(a);
   return new Accepted(operation,id,attempt.version(),action,reconcile?"EVIDENCE_RECORDED_QUERY_ONLY":"QUERY_REQUESTED");
  });
 }
 private Accepted accepted(Map<String,Object> r){return new Accepted((UUID)r.get("id"),(UUID)r.get("attempt_id"),((Number)r.get("original_version")).longValue(),r.get("action").toString(),r.get("action").equals("RECONCILE")?"EVIDENCE_RECORDED_QUERY_ONLY":"QUERY_REQUESTED");}
 private Status view(Refunds.Attempt a){
  int count=jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_consumption WHERE attempt_id=:id AND decision='CALL'",Map.of("id",a.id()),Integer.class);var policies=jdbc.queryForList("SELECT deadline FROM marketplace.payment_refund_query_policy WHERE attempt_id=:id",Map.of("id",a.id()));Instant deadline=policies.isEmpty()?null:((Timestamp)policies.getFirst().get("deadline")).toInstant();
  var due=jdbc.queryForList("SELECT min(s.due_at) AS due FROM marketplace.payment_refund_query_slot s WHERE s.attempt_id=:id AND s.slot<=5 AND NOT EXISTS(SELECT 1 FROM marketplace.payment_refund_query_consumption c WHERE c.attempt_id=s.attempt_id AND c.slot=s.slot)",Map.of("id",a.id()));Instant next=due.isEmpty()||due.getFirst().get("due")==null?null:((Timestamp)due.getFirst().get("due")).toInstant();boolean unresolved=List.of("PROCESSING","UNKNOWN").contains(a.state());boolean review=unresolved&&(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_consumption WHERE attempt_id=:id AND decision='CALL' AND consumption_basis='CLAIM_ONLY_LEGACY'",Map.of("id",a.id()),Integer.class)>0||count>=5||deadline!=null&&!jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant().isBefore(deadline)||jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_consumption WHERE attempt_id=:id AND decision='REVIEW'",Map.of("id",a.id()),Integer.class)>0);
  return new Status(a.id(),a.sellerOrderId(),a.claimId(),a.purpose(),a.sequence(),Long.toString(a.amount()),a.state(),a.version(),jdbc.queryForObject("SELECT mode FROM marketplace.payment_attempt WHERE id=:id",Map.of("id",a.paymentId()),String.class),count,review,unresolved&&!review?next:null,deadline,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_provider_receipt WHERE attempt_id=:id",Map.of("id",a.id()),Integer.class)==1,a.resultReference(),a.resultAt());
 }
 private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max&&value.codePoints().noneMatch(Character::isISOControl);}
 private static String field(String value){return value==null?"-1:":value.length()+":"+value;}
 private static String hash(String value){return TossRefundProvider.sha(value);}
}
