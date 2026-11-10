package com.example.marketplace.dbtest;

import com.example.marketplace.claim.api.ReturnCollections;
import com.example.marketplace.claim.api.ReturnFinances;
import com.example.marketplace.claim.api.ReturnInspections;
import com.example.marketplace.claim.api.Returns;
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

/** Isolated browser prerequisites and observations, never a production API or a substitute for D commands. */
@RestController @Profile("c3-fixture")
public final class ReturnBrowserFixture {
    @org.springframework.web.bind.annotation.ExceptionHandler(OrderRejected.class)
    public org.springframework.http.ResponseEntity<?> rejected(OrderRejected denied) {
        return org.springframework.http.ResponseEntity.status(denied.status()).header("Cache-Control","no-store").body(Map.of("code","FIXTURE_ORDER_REJECTED"));
    }
    private record Entry(Map<String,Object> publicSeed,OrderQueries.Authority seller) { }
    private static final Map<String,Entry> SEEDS=new ConcurrentHashMap<>(),ORDERS=new ConcurrentHashMap<>();
    static void register(String project,String scenario,Map<String,Object> seed,OrderQueries.Authority seller) {
        var entry=new Entry(Map.copyOf(seed),seller);SEEDS.put(project+":"+scenario,entry);ORDERS.put(seed.get("orderNumber").toString(),entry);
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource source;
    private final PlatformTransactionManager manager;
    private final OrderQueries queries;
    private final Returns returns;
    private final ReturnCollections collections;
    private final ReturnInspections inspections;
    private final ReturnFinances finances;
    private final List<JobHandler> handlers;
    public ReturnBrowserFixture(NamedParameterJdbcTemplate jdbc,DataSource source,PlatformTransactionManager manager,OrderQueries queries,Returns returns,ReturnCollections collections,ReturnInspections inspections,ReturnFinances finances,List<JobHandler> handlers) {
        this.jdbc=jdbc;this.source=source;this.manager=manager;this.queries=queries;this.returns=returns;this.collections=collections;this.inspections=inspections;this.finances=finances;this.handlers=List.copyOf(handlers);
    }
    private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request,String token) {
        Long version=(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE);
        if(principal instanceof org.springframework.security.core.Authentication authentication&&authentication.getPrincipal() instanceof com.example.marketplace.identity.infrastructure.SessionMember saved) { version=saved.authVersion(); }
        return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),version,token);
    }
    private UUID own(String number,OrderQueries.Authority actor) { return new TransactionTemplate(manager).execute(t->queries.requireOwned(number,actor).id()); }
    @GetMapping("/api/__test/returns/seed/{project}/{scenario}")
    public Object seed(@PathVariable String project,@PathVariable String scenario) {
        var seed=SEEDS.get(project+":"+scenario);if(seed==null) { throw new OrderRejected(404); }return seed.publicSeed();
    }
    @GetMapping("/api/__test/returns/{number}/database")
    public Object database(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
        var actor=authority(principal,session,request,token);return new TransactionTemplate(manager).execute(t->{
            UUID order=own(number,actor);var p=Map.of("order",order);var result=new java.util.LinkedHashMap<String,Object>();
            result.put("order",jdbc.queryForMap("SELECT id,state,amount,quote_snapshot::text AS quote FROM marketplace.purchase_order WHERE id=:order",p));
            for(String table:List.of("order_unit_lifecycle","order_unit_hold","order_aftercare_closure","order_shipping_decision","order_aftercare_shipping","order_return_application","order_return_completion","claim_return_request","claim_seller_shipping_decision","claim_shipping_obligation","payment_refund_attempt","payment_refund_budget")) { result.put(table,jdbc.queryForList("SELECT * FROM marketplace."+table+" WHERE order_id=:order ORDER BY 1",p)); }
            for(String table:List.of("claim_return_unit","claim_return_history","claim_return_command","claim_return_collection","claim_return_inspection","claim_return_inspection_unit","claim_return_financial_consent","claim_return_financial_result","claim_return_item_ready","claim_return_ready_unit","claim_return_completion")) { result.put(table,jdbc.queryForList("SELECT child.* FROM marketplace."+table+" child JOIN marketplace.claim_return_request parent ON parent.id=child.return_id WHERE parent.order_id=:order ORDER BY 1,2",p)); }
            for(String table:List.of("claim_fee_assessment","claim_fee_bucket","claim_financial_intent","claim")) { result.put(table,jdbc.queryForList("SELECT * FROM marketplace."+table+" WHERE order_id=:order ORDER BY 1",p)); }
            result.put("order_shipping_current",jdbc.queryForList("SELECT c.* FROM marketplace.order_shipping_current c JOIN marketplace.seller_order s ON s.id=c.seller_order_id WHERE s.order_id=:order ORDER BY c.seller_order_id",p));
            result.put("units",jdbc.queryForList("SELECT u.* FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.order_id=:order ORDER BY u.item_id,u.ordinal",p));
            result.put("provider",jdbc.queryForList("SELECT m.* FROM marketplace.payment_refund_mock m JOIN marketplace.payment_refund_attempt a ON a.id=m.attempt_id WHERE a.order_id=:order ORDER BY m.attempt_id",p));
            result.put("inventory",jdbc.queryForList("SELECT r.id,r.status,r.quantity,b.on_hand,b.reserved FROM marketplace.inventory_reservation r JOIN marketplace.order_reservation x ON x.reservation_id=r.id JOIN marketplace.inventory_balance b ON b.sku_id=r.sku_id WHERE x.order_id=:order ORDER BY r.id",p));
            result.put("restored",jdbc.queryForList("SELECT r.* FROM marketplace.inventory_restitution_unit r JOIN marketplace.order_item i ON i.id=r.item_id WHERE i.order_id=:order ORDER BY r.item_id,r.ordinal",p));
            result.put("coupons",jdbc.queryForList("SELECT c.* FROM marketplace.coupon_restoration c JOIN marketplace.seller_order s ON s.id=c.seller_order_ref WHERE s.order_id=:order ORDER BY c.cause_id",p));
            own(number,actor);return result;
        });
    }
    @PostMapping("/api/__test/returns/{number}/prepare")
    public Object prepare(@PathVariable String number,@RequestBody Map<String,String> input,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
        var actor=authority(principal,session,request,token);own(number,actor);var entry=ORDERS.get(number);if(entry==null) { throw new OrderRejected(404); }
        UUID id=UUID.fromString(input.get("returnId"));var current=returns.get(number,id,actor);
        if(input.get("phase").equals("INSPECT")) {
            if(current.state().equals("REQUESTED")) { current=returns.approve(id,entry.seller(),new Returns.Command("browser-prerequisite-approve",current.version(),"실제 판매자 준비 승인")); }
            if(current.physical().stream().anyMatch(u->!u.collection().equals("RECEIVED"))) { current=collections.receive(id,entry.seller(),new ReturnCollections.Command("browser-prerequisite-receive",current.version(),current.physical().stream().filter(u->!u.collection().equals("RECEIVED")).map(u->new ReturnCollections.Unit(u.itemId(),u.ordinal(),ReturnCollections.Status.RECEIVED)).toList(),"실제 원단위 회수 준비")); }
            var pending=current.physical().stream().filter(u->u.inspection().equals("NOT_INSPECTED")).map(u->new ReturnInspections.Unit(u.itemId(),u.ordinal(),ReturnInspections.Condition.valueOf(input.getOrDefault("condition","SALEABLE")),Returns.Responsibility.valueOf(input.getOrDefault("responsibility","BUYER")))).toList();
            if(!pending.isEmpty()) { current=inspections.inspect(id,entry.seller(),new ReturnInspections.Command("browser-prerequisite-inspect",current.version(),pending,"실제 판매자 검수 준비")); }return current;
        }
        if(input.get("phase").equals("CONSENT")) { var quote=finances.quote(number,id,actor,current.version()).feeQuote();return finances.consent(number,id,actor,new ReturnFinances.Consent("browser-prerequisite-consent",current.version(),quote.id(),quote.fingerprint(),true,Boolean.parseBoolean(input.getOrDefault("legacyConsent","false")))); }
        throw new OrderRejected(400);
    }
    @PostMapping("/api/__test/returns/{number}/mock")
    public Object mock(@PathVariable String number,@RequestBody Map<String,String> input,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
        var actor=authority(principal,session,request,token);return new TransactionTemplate(manager).execute(t->{UUID order=own(number,actor);UUID intent=UUID.fromString(input.get("intentId"));String purpose=input.get("purpose"),behavior=input.get("behavior");if(!List.of("ITEM","SHIPPING").contains(purpose)||!List.of("SUCCESS","FAILED","UNKNOWN","LOST_RESPONSE").contains(behavior)||jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_financial_intent WHERE id=:intent AND order_id=:order",Map.of("intent",intent,"order",order),Integer.class)!=1) { throw new OrderRejected(404); }jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,:purpose,:behavior) ON CONFLICT(claim_id,purpose) DO UPDATE SET behavior=excluded.behavior",Map.of("id",intent,"purpose",purpose,"behavior",behavior));own(number,actor);return Map.of("configured",true);});
    }
    @PostMapping("/api/__test/returns/{number}/work")
    public Object work(@PathVariable String number,@RequestBody Map<String,String> input,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
        UUID order=own(number,authority(principal,session,request,token));UUID attempt,seller,intent;
        if(input.get("action").equals("ITEM")) {
            intent=UUID.fromString(input.get("returnId"));var rows=jdbc.queryForList("SELECT b.attempt_id,c.seller_order_id FROM marketplace.claim_return_request c JOIN marketplace.claim_return_financial_consent f ON f.return_id=c.id JOIN marketplace.claim_fee_binding b ON b.assessment_id=f.assessment_id WHERE c.id=:id AND c.order_id=:order",Map.of("id",intent,"order",order));if(rows.size()!=1) { throw new OrderRejected(404); }attempt=(UUID)rows.getFirst().get("attempt_id");seller=(UUID)rows.getFirst().get("seller_order_id");
        }else if(input.get("action").equals("SHIPPING")) {
            seller=UUID.fromString(input.get("sellerOrderId"));var rows=jdbc.queryForList("SELECT claim_id,refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE order_id=:order AND seller_order_id=:seller",Map.of("order",order,"seller",seller));if(rows.size()!=1) { throw new OrderRejected(404); }intent=(UUID)rows.getFirst().get("claim_id");attempt=(UUID)rows.getFirst().get("refund_attempt_id");
        }else { throw new OrderRejected(400); }
        runOne("REFUND_SUBMIT.v1",attempt,true);runAll("REFUND_RESULT_RECORDED.v1",attempt);runAll("RETURN_FINANCE_RESUME.v1",intent);runAll("SELLER_AFTERCARE_REEVALUATE.v1",seller);return Map.of("actualAttempt",attempt,"actualIntent",intent,"actualWorkersCompleted",true);
    }
    private void runAll(String type,UUID cause) { for(int count=0;count<32;count++) { if(!runOne(type,cause,false)) { return; } }throw new IllegalStateException("D_BROWSER_ACTUAL_JOB_BOUND_EXCEEDED"); }
    private boolean runOne(String type,UUID cause,boolean required) {
        var ids=jdbc.queryForList("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status='READY' ORDER BY scheduled_at DESC,id LIMIT 1",Map.of("type",type,"cause",cause.toString()),UUID.class);if(ids.isEmpty()) { if(required) { throw new IllegalStateException("D_BROWSER_ACTUAL_JOB_MISSING:"+type); }return false; }
        UUID id=ids.getFirst();Instant stored=jdbc.queryForObject("SELECT next_run_at FROM marketplace.job WHERE id=:id",Map.of("id",id),java.sql.Timestamp.class).toInstant(),observed=Instant.now(),next=stored.plusSeconds(1),at=next.isAfter(observed)?next:observed;System.err.println("D_BROWSER_ACTUAL_JOB_TIMING type="+type+" job="+id+" storedNextRunAt="+stored+" observed="+observed+" claimClock="+at);var jobs=new JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(at,ZoneOffset.UTC));try(var worker=new WorkerEngine(jobs,handlers.stream().<JobHandler>map(handler->new JobHandler(){ public String type() { return handler.type(); } public Result handle(com.example.marketplace.platform.api.Jobs.Job actual) throws Exception { try { return handler.handle(actual); }catch(Exception failure) { System.err.println("D_BROWSER_ACTUAL_HANDLER_FAILURE type="+type()+" job="+actual.id()+" cause="+actual.command().causeKey());failure.printStackTrace(System.err);throw failure; } } }).toList())) { if(!worker.runOnce()||!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")) { throw new IllegalStateException("D_BROWSER_ACTUAL_WORKER_FAILED:"+jobs.find(id).orElseThrow().lastError()); } }return true;
    }
}
