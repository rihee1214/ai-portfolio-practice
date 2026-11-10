package com.example.marketplace.dbtest;

import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Payments;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.payment.infrastructure.JdbcRefunds;
import com.example.marketplace.payment.infrastructure.RefundReconciliation;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import com.example.marketplace.platform.infrastructure.WorkerEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only DEMO provider observations and real durable workers. It never writes a claim/budget/completion. */
@RestController @Profile("c3-fixture")
public final class RefundBrowserFixture {
 private record Seed(Map<String,Object> data,UUID attempt,Payments.Authority operator){ }
 private static final Map<String,Seed> SEEDS=new ConcurrentHashMap<>(),ORDERS=new ConcurrentHashMap<>();
 static void register(String project,String scenario,Map<String,Object> data,UUID attempt,Payments.Authority operator){var seed=new Seed(Map.copyOf(data),attempt,operator);SEEDS.put(project+":"+scenario,seed);ORDERS.put(data.get("orderNumber").toString(),seed);}
 private final NamedParameterJdbcTemplate jdbc;private final DataSource source;private final PlatformTransactionManager manager;private final OrderQueries orders;private final Refunds refunds;private final JdbcRefunds workerApi;private final RefundReconciliation operations;private final Jobs jobs;private final JobLeases leases;private final List<JobHandler> handlers;
 public RefundBrowserFixture(NamedParameterJdbcTemplate jdbc,DataSource source,PlatformTransactionManager manager,OrderQueries orders,Refunds refunds,JdbcRefunds workerApi,RefundReconciliation operations,Jobs jobs,JobLeases leases,List<JobHandler> handlers){this.jdbc=jdbc;this.source=source;this.manager=manager;this.orders=orders;this.refunds=refunds;this.workerApi=workerApi;this.operations=operations;this.jobs=jobs;this.leases=leases;this.handlers=List.copyOf(handlers);}
 @org.springframework.web.bind.annotation.ExceptionHandler(OrderRejected.class) public org.springframework.http.ResponseEntity<?> denied(OrderRejected error){return org.springframework.http.ResponseEntity.status(error.status()).body(Map.of("code","FIXTURE_REJECTED"));}
 private OrderQueries.Authority authority(Principal p,HttpSession s,HttpServletRequest r,String token){Long version=(Long)r.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE);if(p instanceof org.springframework.security.core.Authentication a&&a.getPrincipal() instanceof com.example.marketplace.identity.infrastructure.SessionMember saved){version=saved.authVersion();}return new OrderQueries.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),version,token);}
 private UUID own(String number,OrderQueries.Authority a){return new TransactionTemplate(manager).execute(t->orders.lockOwned(number,a).id());}
 @GetMapping("/api/__test/refunds/source") public Object source(HttpServletRequest request){return Map.of("source",request.getRemoteAddr());}
 @GetMapping("/api/__test/refunds/seed/{project}/{scenario}") public Object seed(@PathVariable String project,@PathVariable String scenario){var seed=SEEDS.get(project+":"+scenario);if(seed==null){throw new OrderRejected(404);}return seed.data();}
 @GetMapping("/api/__test/refunds/{number}/database") public Object database(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest r,@CookieValue(name="GUEST_ACCESS",required=false) String token){var actor=authority(p,s,r,token);return new TransactionTemplate(manager).execute(t->{UUID order=orders.lockOwned(number,actor).id();var args=Map.of("id",order);var rows=new java.util.LinkedHashMap<String,Object>();rows.put("attempts",jdbc.queryForList("SELECT id,purpose,sequence,amount::text,state,version,result_reference FROM marketplace.payment_refund_attempt WHERE order_id=:id ORDER BY id",args));rows.put("budget",jdbc.queryForList("SELECT paid::text,reserved::text,succeeded::text FROM marketplace.payment_refund_budget WHERE order_id=:id",args));rows.put("provider",jdbc.queryForList("SELECT m.* FROM marketplace.payment_refund_mock m JOIN marketplace.payment_refund_attempt a ON a.id=m.attempt_id WHERE a.order_id=:id ORDER BY m.attempt_id",args));rows.put("consumptions",jdbc.queryForList("SELECT c.slot,c.decision,c.checked_at,c.consumed_at FROM marketplace.payment_refund_query_consumption c JOIN marketplace.payment_refund_attempt a ON a.id=c.attempt_id WHERE a.order_id=:id ORDER BY c.slot",args));rows.put("holds",jdbc.queryForList("SELECT state FROM marketplace.order_unit_hold WHERE order_id=:id ORDER BY id",args));rows.put("payment",jdbc.queryForList("SELECT state,compensation,amount::text FROM marketplace.payment_attempt WHERE order_id=:id",args));orders.lockOwned(number,actor);return rows;});}
 @PostMapping("/api/__test/refunds/{number}/work") public Object work(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest r,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody Map<String,String> body){
  if(!body.keySet().equals(java.util.Set.of("action"))||!List.of("AUTO","SUCCESS").contains(body.get("action"))){throw new OrderRejected(400);}var actor=authority(p,s,r,token);own(number,actor);var seed=ORDERS.get(number);if(seed==null){throw new OrderRejected(404);}UUID attempt=seed.attempt();
  if(body.get("action").equals("AUTO")){for(UUID id:jdbc.queryForList("SELECT s.job_id FROM marketplace.payment_refund_query_slot s WHERE s.attempt_id=:id AND s.slot<=5 ORDER BY s.slot",Map.of("id",attempt),UUID.class)){var saved=jobs.find(id).orElseThrow();Clock logical=Clock.fixed(saved.nextRunAt().plusSeconds(1),ZoneOffset.UTC);Object old=org.springframework.test.util.ReflectionTestUtils.getField(workerApi,"queryClock");org.springframework.test.util.ReflectionTestUtils.setField(workerApi,"queryClock",logical);try{run(id,logical);}finally{org.springframework.test.util.ReflectionTestUtils.setField(workerApi,"queryClock",old);}}}
  else{jdbc.update("UPDATE marketplace.payment_refund_mock SET state='SUCCEEDED',reference=:ref,effects=1 WHERE attempt_id=:id",Map.of("id",attempt,"ref","DEMO-REFUND-"+attempt));var current=new TransactionTemplate(manager).execute(t->refunds.get(attempt));var accepted=operations.command(attempt,seed.operator(),new RefundReconciliation.Command("browser_query_success",current.version(),"실제 DEMO 제공자 후속결과 조회",null,null),false);UUID id=jdbc.queryForObject("SELECT job_id FROM marketplace.payment_refund_operation WHERE id=:id",Map.of("id",accepted.operationId()),UUID.class);run(id,Clock.fixed(jobs.find(id).orElseThrow().nextRunAt().plusSeconds(1),ZoneOffset.UTC));var result=new TransactionTemplate(manager).execute(t->refunds.get(attempt));UUID effect=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key","refund-result:"+attempt+":"+result.version()),UUID.class);run(effect,Clock.fixed(jobs.find(effect).orElseThrow().nextRunAt().plusSeconds(1),ZoneOffset.UTC));}
  own(number,actor);return Map.of("actualJobs",true,"providerMode","DEMO","fabricatedFinancialCompletion",false);
 }
 void run(UUID id,Clock requestedPolicyClock){
  var saved=jobs.find(id).orElseThrow();Instant observed=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant(),host=Instant.now();
  // Slot/policy simulation must not create a lease already expired when the full UI suite reaches F.
  // This is the same stored-due, flowing test driver used by the existing real-job fixtures.
  Instant eligible=java.util.stream.Stream.of(saved.nextRunAt().plusNanos(1000),observed,host).max(Instant::compareTo).orElseThrow();
  Clock driver=Clock.offset(Clock.systemUTC(),Duration.between(host,eligible));var selected=new JdbcJobs(new OneJobJdbc(source,id),manager,driver);
  var checked=new java.util.ArrayList<JobHandler>();for(var delegate:handlers){checked.add(new JobHandler(){
   public String type(){return delegate.type();}
   public Result handle(Jobs.Job job) throws Exception {
    try{proof(job,"before",requestedPolicyClock.instant());var result=delegate.handle(job);proof(job,"after",requestedPolicyClock.instant());return result;}
    catch(Exception failure){write(job,"failure",Map.of("exception",failure.getClass().getName(),"message",String.valueOf(failure.getMessage())));throw failure;}
   }
  });}
  try(var engine=new WorkerEngine(selected,checked)){if(!engine.runOnce()){throw new OrderRejected(409);}}if(!selected.find(id).orElseThrow().status().equals("SUCCEEDED")){throw new OrderRejected(409);}
 }
 private void proof(Jobs.Job job,String phase,Instant requested){
  var values=new TransactionTemplate(manager).execute(t->{
   // Use the unchanged native platform lease verifier, not the test driver's adjusted clock.
   var fact=leases.requireDueClaim(job.id(),job.ownerToken());var actual=jobs.find(job.id()).orElseThrow();
   if(!fact.jobId().equals(job.id())||!fact.ownerToken().equals(job.ownerToken())||fact.attempt()!=job.attempts()||!fact.scheduledAt().equals(job.command().nextRunAt())||fact.claimedAt().isBefore(fact.scheduledAt())||!fact.claimedAt().plusSeconds(30).equals(job.leaseUntil())||!actual.status().equals("RUNNING")||!actual.ownerToken().equals(job.ownerToken())){throw new IllegalStateException("F_FIXTURE_EXACT_REAL_DUE_CLAIM_REQUIRED");}
   var raw=jdbc.queryForMap("WITH sample AS(SELECT clock_timestamp() AS at) SELECT at,at::text AS raw,extract(epoch FROM at)::text AS epoch,pg_backend_pid() AS backend,current_setting('marketplace.verification_instance',true) AS instance FROM sample",Map.of());Instant nativeDb=((java.sql.Timestamp)raw.get("at")).toInstant(),nativeHost=Instant.now();
   if(!nativeDb.isBefore(actual.leaseUntil())||!nativeHost.isBefore(actual.leaseUntil())){throw new IllegalStateException("F_FIXTURE_NATIVE_CURRENT_LEASE_REQUIRED");}
   var result=new java.util.LinkedHashMap<String,Object>();result.put("actualJob",job);result.put("currentJob",actual);result.put("dueFact",fact);result.put("nativeObservation",raw);result.put("nativeHost",nativeHost);result.put("requestedPolicyClock",requested);result.put("oldFixedLeaseWouldEnd",requested.plusSeconds(30));result.put("arrivedAfterOldFixedLease",nativeHost.isAfter(requested.plusSeconds(30)));result.put("exactDueFactAndNativeCurrentLeaseAsserted",true);result.put("productionAuthorityOrTimeGuardChanged",false);return result;
  });write(job,phase,values);
 }
 private static void write(Jobs.Job job,String phase,Map<String,?> values){
  try{var path=java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"f-browser-job-"+job.id()+"-"+job.attempts()+"-"+phase+".json");java.nio.file.Files.writeString(path,new tools.jackson.databind.ObjectMapper().writeValueAsString(values),java.nio.file.StandardOpenOption.CREATE_NEW);}catch(java.io.IOException failure){throw new IllegalStateException("F_FIXTURE_REAL_JOB_EVIDENCE",failure);}
 }
}
