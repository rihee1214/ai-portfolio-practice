package com.example.marketplace.dbtest;

import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import com.example.marketplace.platform.infrastructure.WorkerEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.time.Clock;
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

/** Isolated browser setup/observations and actual DEMO jobs. No fabricated receipt, clock or result writes. */
@RestController @Profile("c3-fixture")
public final class ExchangeBrowserFixture {
 private static final Map<String,Map<String,Object>> SEEDS=new ConcurrentHashMap<>();
 static void register(String project,String scenario,Map<String,Object> seed) { SEEDS.put(project+":"+scenario,Map.copyOf(seed)); }
 private final NamedParameterJdbcTemplate jdbc;private final DataSource source;private final PlatformTransactionManager manager;
 private final OrderQueries queries;private final List<JobHandler> handlers;
 public ExchangeBrowserFixture(NamedParameterJdbcTemplate jdbc,DataSource source,PlatformTransactionManager manager,OrderQueries queries,List<JobHandler> handlers) { this.jdbc=jdbc;this.source=source;this.manager=manager;this.queries=queries;this.handlers=List.copyOf(handlers); }
 @org.springframework.web.bind.annotation.ExceptionHandler(OrderRejected.class)
 public org.springframework.http.ResponseEntity<?> rejected(OrderRejected denied) { return org.springframework.http.ResponseEntity.status(denied.status()).header("Cache-Control","no-store").body(Map.of("code","E_FIXTURE_ORDER_REJECTED")); }
 private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request,String token) {
  Long version=(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE);
  if(principal instanceof org.springframework.security.core.Authentication authentication&&authentication.getPrincipal() instanceof com.example.marketplace.identity.infrastructure.SessionMember saved) { version=saved.authVersion(); }
  return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),version,token);
 }
 private OrderQueries.Owned own(String number,OrderQueries.Authority actor) { return new TransactionTemplate(manager).execute(t->queries.requireOwned(number,actor)); }
 @GetMapping("/api/__test/exchanges/seed/{project}/{scenario}") public Object seed(@PathVariable String project,@PathVariable String scenario) { var value=SEEDS.get(project+":"+scenario);if(value==null){throw new OrderRejected(404);}return value; }
 @GetMapping("/api/__test/exchanges/source") public Object source(HttpServletRequest request) { return Map.of("source",request.getRemoteAddr()); }
 @GetMapping("/api/__test/exchanges/{number}/database")
 public Object database(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
  var actor=authority(principal,session,request,token);return new TransactionTemplate(manager).execute(t->{
   var own=queries.requireOwned(number,actor);var p=Map.of("order",own.id());var result=new java.util.LinkedHashMap<String,Object>();
   result.put("order",jdbc.queryForMap("SELECT id,state,amount,quote_snapshot::text AS quote FROM marketplace.purchase_order WHERE id=:order",p));
   for(String table:List.of("claim_exchange_request","inventory_exchange_reservation","order_physical_source","order_delivery_bucket","fulfillment_exchange_shipment","order_unit_lifecycle","order_unit_hold","claim_fee_assessment","claim_shipping_obligation","payment_refund_attempt","payment_refund_budget","order_aftercare_shipping","order_aftercare_closure","claim_return_request")){result.put(table,jdbc.queryForList("SELECT * FROM marketplace."+table+" WHERE order_id=:order ORDER BY 1",p));}
   for(String table:List.of("claim_exchange_history","claim_exchange_collection","claim_exchange_inspection","claim_exchange_completion","claim_exchange_refund_consent","claim_exchange_refund_item_ready","claim_exchange_refund_completion")){result.put(table,jdbc.queryForList("SELECT x.* FROM marketplace."+table+" x JOIN marketplace.claim_exchange_request e ON e.id=x.exchange_id WHERE e.order_id=:order ORDER BY 1",p));}
   result.put("purchaseReservations",jdbc.queryForList("SELECT r.* FROM marketplace.inventory_reservation r JOIN marketplace.order_reservation x ON x.reservation_id=r.id WHERE x.order_id=:order ORDER BY r.id",p));
   result.put("inventory",jdbc.queryForList("SELECT b.* FROM marketplace.inventory_balance b WHERE b.sku_id IN(SELECT source_sku_id FROM marketplace.inventory_exchange_reservation WHERE order_id=:order UNION SELECT target_sku_id FROM marketplace.inventory_exchange_reservation WHERE order_id=:order UNION SELECT sku_id FROM marketplace.order_item WHERE order_id=:order) ORDER BY b.sku_id",p));
   result.put("provider",jdbc.queryForList("SELECT m.* FROM marketplace.payment_refund_mock m JOIN marketplace.payment_refund_attempt a ON a.id=m.attempt_id WHERE a.order_id=:order ORDER BY m.attempt_id",p));
   result.put("restitutions",jdbc.queryForList("SELECT x.* FROM marketplace.inventory_source_restitution x JOIN marketplace.order_physical_source s ON s.committed_source_id=x.source_id WHERE s.order_id=:order ORDER BY x.source_id",p));
   result.put("coupons",jdbc.queryForList("SELECT c.* FROM marketplace.coupon_restoration c JOIN marketplace.seller_order s ON s.id=c.seller_order_ref WHERE s.order_id=:order ORDER BY c.cause_id",p));
   queries.requireOwned(number,actor);return result;
  });
 }
 @PostMapping("/api/__test/exchanges/{number}/mock")
 public Object mock(@PathVariable String number,@RequestBody Map<String,String> input,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
  var actor=authority(principal,session,request,token);return new TransactionTemplate(manager).execute(t->{
   var own=queries.requireOwned(number,actor);UUID id=UUID.fromString(input.get("exchangeId"));String purpose=input.get("purpose"),behavior=input.get("behavior");
   if(!List.of("ITEM","SHIPPING").contains(purpose)||!List.of("SUCCESS","FAILED","UNKNOWN","LOST_RESPONSE").contains(behavior)||jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_financial_intent WHERE id=:id AND order_id=:order AND kind='EXCHANGE_REFUND'",Map.of("id",id,"order",own.id()),Integer.class)!=1){throw new OrderRejected(404);}
   jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,:purpose,:behavior) ON CONFLICT(claim_id,purpose) DO UPDATE SET behavior=excluded.behavior",Map.of("id",id,"purpose",purpose,"behavior",behavior));queries.requireOwned(number,actor);return Map.of("configured",true);
  });
 }
 @PostMapping("/api/__test/exchanges/{number}/work")
 public Object work(@PathVariable String number,@RequestBody Map<String,String> input,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
  var actor=authority(principal,session,request,token);var own=own(number,actor);UUID id=UUID.fromString(input.get("exchangeId"));var rows=jdbc.queryForList("SELECT seller_order_id FROM marketplace.claim_exchange_request WHERE id=:id AND order_id=:order",Map.of("id",id,"order",own.id()));if(rows.size()!=1){throw new OrderRejected(404);}UUID seller=(UUID)rows.getFirst().get("seller_order_id");String action=input.get("action");
  if("DISPATCH".equals(action)){runAll("EXCHANGE_RESHIP.v1",id);}
  else if("DELIVER".equals(action)){
   UUID shipment=jdbc.queryForObject("SELECT id FROM marketplace.fulfillment_exchange_shipment WHERE exchange_id=:id",Map.of("id",id),UUID.class);
   for(UUID job:jdbc.queryForList("SELECT s.job_id FROM marketplace.fulfillment_exchange_progress_schedule s JOIN marketplace.job j ON j.id=s.job_id WHERE s.shipment_id=:id AND j.status='READY' ORDER BY s.expected_version",Map.of("id",shipment),UUID.class)){run(job);}
   runAll("EXCHANGE_DELIVERED.v1",id);
  }else if(List.of("ITEM","SHIPPING").contains(action)){
   UUID attempt=action.equals("ITEM")?jdbc.queryForObject("SELECT b.attempt_id FROM marketplace.claim_fee_binding b JOIN marketplace.claim_exchange_refund_consent c ON c.assessment_id=b.assessment_id WHERE c.exchange_id=:id",Map.of("id",id),UUID.class):jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE claim_id=:id",Map.of("id",id),UUID.class);
   runAll("REFUND_SUBMIT.v1",attempt);runAll("REFUND_RESULT_RECORDED.v1",attempt);runAll("EXCHANGE_REFUND_RESUME.v1",id);runAll("SELLER_AFTERCARE_REEVALUATE.v1",seller);
  }else {throw new OrderRejected(400);}
  own(number,actor);return Map.of("actualJobs",true,"action",action);
 }
 private void runAll(String type,UUID cause){for(int i=0;i<32;i++){var ids=jdbc.queryForList("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status='READY' ORDER BY scheduled_at,id LIMIT 1",Map.of("type",type,"cause",cause.toString()),UUID.class);if(ids.isEmpty()){return;}run(ids.getFirst());}throw new IllegalStateException("E_BROWSER_JOB_BOUND");}
 private void run(UUID id){Instant stored=jdbc.queryForObject("SELECT next_run_at FROM marketplace.job WHERE id=:id",Map.of("id",id),java.sql.Timestamp.class).toInstant(),now=Instant.now(),at=stored.plusNanos(1000).isAfter(now)?stored.plusNanos(1000):now;var jobs=new JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(at,ZoneOffset.UTC));try(var worker=new WorkerEngine(jobs,handlers)){if(!worker.runOnce()||!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")){throw new IllegalStateException("E_BROWSER_ACTUAL_JOB_FAILED:"+jobs.find(id).orElseThrow().lastError());}}}
}
