package com.example.marketplace.dbtest;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.identity.infrastructure.SessionMember;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Payments;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.payment.infrastructure.RefundReconciliation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only exact jobs over genuine order/operation graphs; never creates a financial result. */
@RestController @Profile("c3-fixture")
public final class PostPurchaseBrowserFixture {
 private static final Map<String,Map<String,Object>> SEEDS=new ConcurrentHashMap<>();
 static void register(String project,Map<String,Object> seed){SEEDS.put(project,Map.copyOf(seed));}
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries orders;private final Refunds refunds;private final RefundReconciliation operations;private final RefundBrowserFixture worker;private final TransactionTemplate tx;
 @PostMapping("/api/__test/notifications/questions/{answer}/deliver")
 public Map<String,Object> notifyAnswer(@PathVariable UUID answer,Principal principal) {
  if(principal==null) { throw new OrderRejected(401); }
  var rows=jdbc.queryForList("""
          SELECT o.job_id FROM marketplace.notification_event e JOIN marketplace.outbox_event o ON o.id=e.id
          WHERE e.kind='PRODUCT_ANSWER' AND e.cause_id=:answer AND e.recipient_id=:member
          """,Map.of("answer",answer,"member",UUID.fromString(principal.getName())),UUID.class);
  if(rows.size()!=1) { throw new OrderRejected(404); }
  worker.run(rows.getFirst(),java.time.Clock.systemUTC());
  return Map.of("delivered",true);
 }
 public PostPurchaseBrowserFixture(NamedParameterJdbcTemplate jdbc,OrderQueries orders,Refunds refunds,RefundReconciliation operations,RefundBrowserFixture worker,PlatformTransactionManager manager){this.jdbc=jdbc;this.orders=orders;this.refunds=refunds;this.operations=operations;this.worker=worker;tx=new TransactionTemplate(manager);}
 @ExceptionHandler(OrderRejected.class) public ResponseEntity<?> denied(OrderRejected e){return ResponseEntity.status(e.status()).body(Map.of("code","G_FIXTURE_REJECTED"));}
 private Long version(Principal p,HttpServletRequest request){return p instanceof org.springframework.security.core.Authentication a&&a.getPrincipal() instanceof SessionMember saved?saved.authVersion():(Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE);}
 private Payments.Authority system(Principal p,HttpSession s,HttpServletRequest request){return new Payments.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),version(p,request),null);}
 private void own(String number,Principal p,HttpSession s,HttpServletRequest request,String token){tx.executeWithoutResult(t->orders.requireOwned(number,new OrderQueries.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),version(p,request),token)));}
 private void attempt(String number,UUID id){if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt a JOIN marketplace.purchase_order o ON o.id=a.order_id WHERE a.id=:id AND o.order_number=:n",Map.of("id",id,"n",number),Integer.class)!=1){throw new OrderRejected(404);}}
 @GetMapping("/api/__test/refunds/g-seed/{project}") public Object seed(@PathVariable String project){var seed=SEEDS.get(project);if(seed==null){throw new OrderRejected(404);}return seed;}

 @PostMapping("/api/__test/cancellations/{number}/current-worker") public Object refund(@PathVariable String number,@RequestBody Map<String,String> input,Principal p,HttpSession s,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token){
  if(!input.keySet().equals(Set.of("attempt","action"))){throw new OrderRejected(400);}own(number,p,s,request,token);UUID id=UUID.fromString(input.get("attempt"));attempt(number,id);String type=switch(input.get("action")){case "submit"->"REFUND_SUBMIT.v1";case "result"->"REFUND_RESULT_RECORDED.v1";default->throw new OrderRejected(400);};
  var current=tx.execute(t->refunds.get(id));String key=type.equals("REFUND_RESULT_RECORDED.v1")?"refund-result:"+id+":"+current.version():null;
  UUID job=key==null?jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status='READY'",Map.of("type",type,"cause",id.toString()),UUID.class):jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",key),UUID.class);
  worker.run(job,Clock.systemUTC());own(number,p,s,request,token);return Map.of("actualJobs",true,"jobId",job,"financialRowsFabricated",false);
 }

 @PostMapping("/api/__test/shipments/{number}/current-worker") public Object shipment(@PathVariable String number,@RequestBody Map<String,String> input,Principal p,HttpSession s,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token){
  own(number,p,s,request,token);String key;
  if("prepare".equals(input.get("action"))&&input.keySet().equals(Set.of("action"))){key=jdbc.queryForObject("SELECT dedupe_key FROM marketplace.job WHERE type='PAYMENT_SETTLED' AND payload LIKE :prefix",Map.of("prefix",number+":%"),String.class);}
  else {if(!input.keySet().equals(Set.of("action","shipment","target"))||!"progress".equals(input.get("action"))||!Set.of("IN_TRANSIT","DELIVERED").contains(input.get("target"))){throw new OrderRejected(400);}UUID id=UUID.fromString(input.get("shipment"));var row=jdbc.queryForMap("SELECT progress_epoch FROM marketplace.shipment WHERE id=:id AND order_number=:n",Map.of("id",id,"n",number));long epoch=((Number)row.get("progress_epoch")).longValue();key=epoch==0?"progress:"+id+":"+input.get("target"):"progress:v2:"+id+":"+epoch+":"+input.get("target");}
  UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",key),UUID.class);worker.run(id,Clock.systemUTC());own(number,p,s,request,token);return Map.of("actualJobs",true,"jobId",id,"financialRowsFabricated",false);
 }

 @PostMapping("/api/__test/refunds/{number}/operation-worker") public Object operation(@PathVariable String number,@RequestBody Map<String,String> input,Principal p,HttpSession s,HttpServletRequest request){
  if(!input.keySet().equals(Set.of("attempt","operationId"))){throw new OrderRejected(400);}UUID id=UUID.fromString(input.get("attempt")),operation=UUID.fromString(input.get("operationId"));var actor=system(p,s,request);operations.get(id,actor);attempt(number,id);
  UUID job=jdbc.queryForObject("SELECT job_id FROM marketplace.payment_refund_operation WHERE id=:op AND attempt_id=:id AND actor=:actor AND session_id=:session",Map.of("op",operation,"id",id,"actor",actor.member(),"session",actor.browser()),UUID.class);
  worker.run(job,Clock.systemUTC());var current=tx.execute(t->refunds.get(id));var effects=jdbc.queryForList("SELECT id FROM marketplace.job WHERE dedupe_key=:key AND status='READY'",Map.of("key","refund-result:"+id+":"+current.version()),UUID.class);if(effects.size()>1){throw new IllegalStateException("EXACT_G_REFUND_RESULT_REQUIRED");}for(UUID effect:effects){worker.run(effect,Clock.systemUTC());}operations.get(id,actor);return Map.of("actualJobs",true,"operationId",operation,"jobId",job,"financialRowsFabricated",false);
 }

 @PostMapping("/api/__test/returns/{number}/current-worker") public Object returns(@PathVariable String number,@RequestBody Map<String,String> input,Principal p,HttpSession s,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token){
  own(number,p,s,request,token);UUID attempt,seller,intent;
  if("ITEM".equals(input.get("action"))&&input.keySet().equals(Set.of("action","returnId"))){intent=UUID.fromString(input.get("returnId"));var row=jdbc.queryForMap("SELECT b.attempt_id,c.seller_order_id FROM marketplace.claim_return_request c JOIN marketplace.claim_return_financial_consent f ON f.return_id=c.id JOIN marketplace.claim_fee_binding b ON b.assessment_id=f.assessment_id JOIN marketplace.purchase_order o ON o.id=c.order_id WHERE c.id=:id AND o.order_number=:n",Map.of("id",intent,"n",number));attempt=(UUID)row.get("attempt_id");seller=(UUID)row.get("seller_order_id");}
  else if("SHIPPING".equals(input.get("action"))&&input.keySet().equals(Set.of("action","sellerOrderId"))){seller=UUID.fromString(input.get("sellerOrderId"));var row=jdbc.queryForMap("SELECT c.claim_id,c.refund_attempt_id FROM marketplace.claim_shipping_obligation c JOIN marketplace.purchase_order o ON o.id=c.order_id WHERE c.seller_order_id=:id AND o.order_number=:n",Map.of("id",seller,"n",number));intent=(UUID)row.get("claim_id");attempt=(UUID)row.get("refund_attempt_id");}
  else{throw new OrderRejected(400);}
  actualJobs("REFUND_SUBMIT.v1",attempt,true);actualJobs("REFUND_RESULT_RECORDED.v1",attempt,false);actualJobs("RETURN_FINANCE_RESUME.v1",intent,false);actualJobs("SELLER_AFTERCARE_REEVALUATE.v1",seller,false);own(number,p,s,request,token);return Map.of("actualWorkersCompleted",true,"actualAttempt",attempt,"actualIntent",intent,"financialRowsFabricated",false);
 }
 private void actualJobs(String type,UUID cause,boolean required){
  int count=0;for(;count<32;count++){var rows=jdbc.queryForList("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status='READY' ORDER BY next_run_at,id LIMIT 1",Map.of("type",type,"cause",cause.toString()),UUID.class);if(rows.isEmpty()){if(required&&count==0){throw new IllegalStateException("G_EXACT_ACTUAL_JOB_MISSING:"+type);}return;}worker.run(rows.getFirst(),Clock.systemUTC());}throw new IllegalStateException("G_ACTUAL_JOB_BOUND_EXCEEDED:"+type);
 }

 @GetMapping("/api/__test/refunds/{number}/g-database") public Object database(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token){
  own(number,p,s,request,token);var base=(Map<?,?>)worker.database(number,p,s,request,token);var result=new java.util.LinkedHashMap<String,Object>();base.forEach((key,value)->result.put(key.toString(),value));var args=Map.of("n",number);
  result.put("observations",jdbc.queryForList("SELECT c.operation,o.state,o.transaction_key,o.response_fingerprint FROM marketplace.payment_refund_provider_call c JOIN marketplace.payment_refund_provider_observation o ON o.call_id=c.id JOIN marketplace.payment_refund_attempt a ON a.id=c.attempt_id JOIN marketplace.purchase_order p ON p.id=a.order_id WHERE p.order_number=:n ORDER BY c.started_at,c.id",args));
  result.put("operations",jdbc.queryForList("SELECT r.id,r.attempt_id,r.actor,r.original_version,r.action,r.command_key,r.requested_transaction FROM marketplace.payment_refund_operation r JOIN marketplace.payment_refund_attempt a ON a.id=r.attempt_id JOIN marketplace.purchase_order p ON p.id=a.order_id WHERE p.order_number=:n ORDER BY r.created_at,r.id",args));
  var bindings=jdbc.queryForList("SELECT b.provider_payment_key FROM marketplace.payment_toss_binding b JOIN marketplace.payment_attempt payment ON payment.id=b.attempt_id JOIN marketplace.purchase_order p ON p.id=payment.order_id WHERE p.order_number=:n",args,String.class);if(bindings.size()!=1){throw new OrderRejected(409);}String key=bindings.getFirst();var calls=PostgresContractFixture.TOSS_CALLS;var external=new java.util.LinkedHashMap<String,Integer>();for(String method:Set.of("POST:cancel","GET")){var counter=calls.get(key+":"+method);external.put(method,counter==null?0:counter.get());}result.put("externalCalls",external);
  result.put("restored",jdbc.queryForList("SELECT r.* FROM marketplace.inventory_restitution_unit r JOIN marketplace.order_item i ON i.id=r.item_id JOIN marketplace.purchase_order p ON p.id=i.order_id WHERE p.order_number=:n ORDER BY r.item_id,r.ordinal",args));result.put("coupons",jdbc.queryForList("SELECT c.* FROM marketplace.coupon_restoration c JOIN marketplace.seller_order s ON s.id=c.seller_order_ref JOIN marketplace.purchase_order p ON p.id=s.order_id WHERE p.order_number=:n ORDER BY c.cause_id",args));own(number,p,s,request,token);return result;
 }
}
