package com.example.marketplace.dbtest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.promotion.api.CouponReservations;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.api.AccountOperations;
import com.example.marketplace.identity.api.AccountOperations.AddressInput;
import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.application.PasswordRecoveryService;
import com.example.marketplace.identity.infrastructure.JdbcPasswordRecoveryStore;
import com.example.marketplace.identity.infrastructure.LocalPasswordResetMailbox;
import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.application.SignupService;
import com.example.marketplace.identity.domain.IdentityStore;
import com.example.marketplace.identity.domain.PasswordHasher;
import com.example.marketplace.identity.domain.VerificationMailbox;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import com.example.marketplace.jdbcfixture.AccountRepository;
import com.example.marketplace.jdbcfixture.BatchService;
import com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget;
import com.example.marketplace.jdbcfixture.TransferService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"db", "c3-fixture", "local-mailbox", "local-http"})
@ExtendWith({OutputCaptureExtension.class, ContractTiming.class})
@ContextConfiguration(initializers = DedicatedPostgresInitializer.class)
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/", "spring.sql.init.mode=never", "spring.flyway.clean-disabled=true",
            "server.address=127.0.0.1", "marketplace.phone.mode=local-test", "spring.jmx.enabled=true", "spring.jmx.unique-names=true",
            "management.endpoints.jmx.domain=com.example.marketplace.dbfollowup",
            "spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import({PostgresContractFixture.TestMailboxEndpoint.class,PostgresContractFixture.GuestOrderFixture.class,PostgresContractFixture.GuestFixtureSecurity.class,PostgresContractFixture.CouponFixture.class,PostgresContractFixture.PaymentFixtureEndpoint.class,PostgresContractFixture.ShipmentBrowserFixture.class,PostgresContractFixture.TossTestConfiguration.class})
class PostgresContractFixture {
    @org.junit.jupiter.api.BeforeEach
    void recordExecutionScope() {
        ContractTiming.identity(port, context.getEnvironment().getRequiredProperty("spring.datasource.url"));
    }
    protected static final Map<UUID,LocalTestMailbox> FIXTURE_ACCOUNT_MAILBOXES=new java.util.concurrent.ConcurrentHashMap<>();
    protected static final java.util.concurrent.ConcurrentHashMap<String,Map<String,Object>> TOSS_FIXTURES=new java.util.concurrent.ConcurrentHashMap<>();
    protected static final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger> TOSS_CALLS=new java.util.concurrent.ConcurrentHashMap<>();
    protected static final String TOSS_WEBHOOK_FIXTURE="fixture-only-security-key-not-real";
    protected static final com.sun.net.httpserver.HttpServer TOSS_STUB=startTossStub();
    protected static com.sun.net.httpserver.HttpServer startTossStub() {
        try {
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/payments",exchange->{
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();var mapper=new tools.jackson.databind.ObjectMapper();
                var input=method.equals("POST")?mapper.readTree(exchange.getRequestBody().readAllBytes()):null;
                String key=path.endsWith("/confirm")?input.path("paymentKey").asText():path.substring("/v1/payments/".length()).replace("/cancel","");
                TOSS_CALLS.computeIfAbsent(key+":"+method+(path.endsWith("/cancel")?":cancel":""),ignored->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                assertEquals("Basic "+java.util.Base64.getEncoder().encodeToString("test_sk_fixture-integration-only:".getBytes(java.nio.charset.StandardCharsets.UTF_8)),exchange.getRequestHeaders().getFirst("Authorization"));
                var data=TOSS_FIXTURES.get(key);
                if(data==null) { exchange.sendResponseHeaders(404,-1);exchange.close();return; }
                if(method.equals("POST")) {
                    String attempt=exchange.getRequestHeaders().getFirst("Idempotency-Key");boolean customer=path.endsWith("/cancel")&&input.has("cancelAmount");assertTrue(attempt!=null&&attempt.matches(customer?"customer-refund:[0-9a-f-]{36}":"[0-9a-f-]{36}"));
                    if(customer){
                        UUID refund=UUID.fromString(attempt.substring("customer-refund:".length()));assertEquals("F-CUSTOMER-"+refund,input.path("cancelReason").asText());assertTrue(input.path("cancelAmount").isIntegralNumber());long amount=input.path("cancelAmount").asLong();assertTrue(amount>0);String transaction="fixture-customer-"+refund;
                        @SuppressWarnings("unchecked") var cancels=(java.util.List<Map<String,Object>>)data.computeIfAbsent("cancels",ignored->new java.util.concurrent.CopyOnWriteArrayList<Map<String,Object>>());
                        synchronized(data){if(cancels.stream().noneMatch(c->transaction.equals(c.get("transactionKey")))){long balance=((Number)data.get("balanceAmount")).longValue();assertTrue(amount<=balance);cancels.add(Map.of("transactionKey",transaction,"cancelAmount",amount,"cancelReason",input.path("cancelReason").asText(),"cancelStatus","DONE"));data.put("balanceAmount",balance-amount);data.put("status",balance==amount?"CANCELED":"PARTIAL_CANCELED");TOSS_CALLS.computeIfAbsent(key+":EFFECT:"+attempt,ignored->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();}data.put("lastTransactionKey",transaction);}
                        if(data.remove("refundLose")!=null){exchange.close();return;}
                    }
                    if(path.endsWith("/confirm")) { assertEquals(data.get("orderId"),input.path("orderId").asText());assertEquals(data.get("totalAmount"),input.path("amount").asLong()); }
                    if(path.endsWith("/cancel")&&!customer) { data.put("status","CANCELED");data.put("balanceAmount",0L); }
                    else if(data.remove("lose")!=null) { data.put("status","DONE");exchange.close();return; }
                }
                byte[] bytes=mapper.writeValueAsBytes(data);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();return server;
        }catch(java.io.IOException failure) { throw new IllegalStateException("TOSS_FIXTURE_SETUP",failure); }
    }
    @org.springframework.test.context.DynamicPropertySource
    static void tossStubProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("marketplace.toss.client-key",()->"test_ck_fixture-integration-only");registry.add("marketplace.toss.secret-key",()->"test_sk_fixture-integration-only");registry.add("marketplace.toss.mid",()->"fixture-mid");registry.add("marketplace.toss.api-version",()->"2024-06-01");registry.add("marketplace.toss.webhook-security-key",()->TOSS_WEBHOOK_FIXTURE);
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class TossTestConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        com.example.marketplace.payment.infrastructure.PaymentProviderConfiguration.Settings stubTossSettings() { return new com.example.marketplace.payment.infrastructure.PaymentProviderConfiguration.Settings("test_ck_fixture-integration-only","test_sk_fixture-integration-only","fixture-mid","2024-06-01",TOSS_WEBHOOK_FIXTURE,"http://127.0.0.1:"+TOSS_STUB.getAddress().getPort()); }
    }
    @org.junit.jupiter.api.AfterAll static void stopTossStub() { TOSS_STUB.stop(0); ContractTiming.finishEvidence(); }
    protected PaymentFixture sandboxFixture(String suffix,boolean zero) throws Exception { var f=paymentFixture("sandbox-"+suffix,zero);jdbc.update("UPDATE marketplace.purchase_order SET mode='SANDBOX' WHERE id=:id",Map.of("id",f.order().id()));return new PaymentFixture(orders().find(f.order().orderNumber()),f.browser(),f.authority()); }
    protected Map<String,Object> tossFixture(PaymentFixture f,String key,String state) {
        var data=new java.util.concurrent.ConcurrentHashMap<String,Object>();data.putAll(Map.of("paymentKey",key,"orderId",f.order().id().toString(),"totalAmount",f.order().quote().total(),"balanceAmount",f.order().quote().total(),"status",state,"currency","KRW","method","카드","mId","fixture-mid","version","2024-06-01"));TOSS_FIXTURES.put(key,data);return data;
    }
    protected Map<String,String> tossConfirm(PaymentFixture f,String key) { return Map.of("paymentKey",key,"orderId",f.order().id().toString(),"amount",Long.toString(f.order().quote().total())); }
    protected int tossCalls(String key,String method) { return TOSS_CALLS.getOrDefault(key+":"+method,new java.util.concurrent.atomic.AtomicInteger()).get(); }



    protected static String tossSignature(String body,String time) throws Exception { var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(TOSS_WEBHOOK_FIXTURE.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));return "v1:"+java.util.Base64.getEncoder().encodeToString(mac.doFinal((body+":"+time).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    protected HttpResponse<String> webhook(String path,String body,String time,String signature) throws Exception {
        return webhook(path,body,time,signature,UUID.randomUUID().toString());
    }
    protected HttpResponse<String> webhook(String path,String body,String time,String signature,String transmission) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));if(transmission!=null){builder.header("tosspayments-webhook-transmission-id",transmission);}if(time!=null) { builder.header("tosspayments-webhook-transmission-time",time); }if(signature!=null) { builder.header("tosspayments-webhook-signature",signature); }return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }







    protected Process zeroWorkerProcess(java.nio.file.Path folder,String phase,Instant now,String type,UUID job,PaymentFixture f,boolean block,String point) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("process-start", "zeroWorkerProcess")) {
        var args=folder.resolve(phase+".args");var marker=folder.resolve(phase+"-effect.txt");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');
        java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.PaymentApprovalProbe\n"+now+"\n"+type+"\n\""+marker.toString().replace((char)92,'/')+"\"\n"+block+"\n"+job+"\n"+f.order().orderNumber()+"\n"+point+"\n"+f.authority().browser()+"\n"+f.authority().member()+"\n"+f.authority().version()+"\n");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile()).start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    
        }
    }




    @org.springframework.web.bind.annotation.RestController @org.springframework.context.annotation.Profile("c3-fixture")
    static class PaymentFixtureEndpoint {
        private final NamedParameterJdbcTemplate jdbc;private final com.example.marketplace.payment.infrastructure.PaymentCoordinator payments;
        PaymentFixtureEndpoint(NamedParameterJdbcTemplate jdbc,com.example.marketplace.payment.infrastructure.PaymentCoordinator payments) { this.jdbc=jdbc;this.payments=payments; }
        @org.springframework.web.bind.annotation.GetMapping("/api/__test/payment") Object own(java.security.Principal principal) {
            return jdbc.queryForList("SELECT o.order_number,o.id AS provider_order_id,o.amount,o.state,coalesce(m.approval_calls,0) AS approval_calls,coalesce(m.approval_effects,0) AS approval_effects,(SELECT count(*) FROM marketplace.order_reservation r JOIN marketplace.inventory_reservation i ON i.id=r.reservation_id WHERE r.order_id=o.id AND i.status='COMMITTED') AS inventory_committed,(SELECT count(*) FROM marketplace.order_reservation r JOIN marketplace.coupon_reservation c ON c.id=r.reservation_id WHERE r.order_id=o.id AND c.state='COMMITTED') AS coupon_committed FROM marketplace.purchase_order o LEFT JOIN marketplace.payment_attempt a ON a.order_id=o.id LEFT JOIN marketplace.payment_mock m ON m.attempt_id=a.id WHERE o.member_id=:id ORDER BY o.created_at",Map.of("id",UUID.fromString(principal.getName())));
        }
        @org.springframework.web.bind.annotation.PostMapping("/api/__test/payment/query") Object query(java.security.Principal principal,@org.springframework.web.bind.annotation.RequestBody Map<String,String> body) {
            var rows=jdbc.queryForList("SELECT a.* FROM marketplace.payment_attempt a JOIN marketplace.purchase_order o ON o.id=a.order_id WHERE o.member_id=:id AND o.order_number=:n",Map.of("id",UUID.fromString(principal.getName()),"n",body.get("number")));if(rows.size()!=1) { throw new com.example.marketplace.order.api.OrderRejected(404); }var a=rows.getFirst();payments.query(body.get("number"),((Number)a.get("round")).intValue(),((Number)a.get("query_count")).intValue(),false);return Map.of("done",true);
        }
    }


    protected Process paymentWorkerProcess(java.nio.file.Path folder,String phase,Instant now,String type,UUID job,PaymentFixture f,boolean cancel,boolean block,String point) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("process-start", "paymentWorkerProcess")) {
        var args=folder.resolve(phase+".args");var marker=folder.resolve(phase+"-effect.txt");var a=paymentAttempt(f);String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');
        java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.PaymentWorkerProbe\n"+now+"\n"+type+"\n\""+marker.toString().replace((char)92,'/')+"\"\n"+block+"\n"+job+"\n"+f.order().orderNumber()+"\n"+a.get("round")+"\n"+a.get("query_count")+"\n"+cancel+"\n"+point+"\n");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile()).start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    
        }
    }




    protected String paymentProviderState() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "paymentProviderState")) { return jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace.payment_mock t",String.class); 
        }
    }
    protected com.example.marketplace.payment.infrastructure.PaymentCoordinator paymentAuthorityFixture(com.example.marketplace.identity.api.PurchaseSessions sessions,com.example.marketplace.guest.api.GuestOrders guest) {
        var saved=new com.example.marketplace.order.infrastructure.JdbcOrders(jdbc,authMapper,transactionManager);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(saved,"authorities",context.getBean(com.example.marketplace.identity.api.SellerMembership.class),sessions,guest);
        var api=new com.example.marketplace.payment.infrastructure.PaymentCoordinator(jdbc,saved,reservations(),couponReservations(),context.getBean(com.example.marketplace.payment.api.PaymentProvider.class),context.getBean(com.example.marketplace.platform.api.Jobs.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),sessions,Clock.systemUTC(),stage->{ });
        org.springframework.test.util.ReflectionTestUtils.setField(api,"sandboxSettings",context.getBean(com.example.marketplace.payment.infrastructure.PaymentProviderConfiguration.Settings.class));return api;
    }


    protected HttpResponse<String> paymentPost(HttpClient browser,String number,String body,String csrf) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/payments/"+number+"/approve")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));if(csrf!=null) { builder.header("X-CSRF-TOKEN",csrf); }return browser.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }




    protected static final Map<String,Map<String,Object>> C_BROWSER_SEEDS=new java.util.concurrent.ConcurrentHashMap<>();
    @org.springframework.web.bind.annotation.RestController @org.springframework.context.annotation.Profile("c3-fixture")
    static class ShipmentBrowserFixture {
        private final NamedParameterJdbcTemplate jdbc;private final DataSource source;private final PlatformTransactionManager manager;private final com.example.marketplace.order.api.OrderQueries queries;private final List<com.example.marketplace.platform.api.JobHandler> handlers;private final com.example.marketplace.fulfillment.infrastructure.JdbcShipments shipmentApi;
        ShipmentBrowserFixture(NamedParameterJdbcTemplate jdbc,DataSource source,PlatformTransactionManager manager,com.example.marketplace.order.api.OrderQueries queries,List<com.example.marketplace.platform.api.JobHandler> handlers,com.example.marketplace.fulfillment.infrastructure.JdbcShipments shipmentApi) { this.jdbc=jdbc;this.source=source;this.manager=manager;this.queries=queries;this.handlers=handlers;this.shipmentApi=shipmentApi; }
        private void own(String number,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,String token) { new TransactionTemplate(manager).executeWithoutResult(t->queries.requireOwned(number,new com.example.marketplace.order.api.OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),principal instanceof org.springframework.security.core.Authentication authentication && authentication.getPrincipal() instanceof com.example.marketplace.identity.infrastructure.SessionMember saved?saved.authVersion():null,token))); }
        @org.springframework.web.bind.annotation.GetMapping("/api/__test/cancellations/seed/{project}/{scenario}")
        Object cancellationSeed(@org.springframework.web.bind.annotation.PathVariable String project,@org.springframework.web.bind.annotation.PathVariable String scenario) {
            var seed=C_BROWSER_SEEDS.get(project+":"+scenario);if(seed==null){throw new com.example.marketplace.order.api.OrderRejected(404);}return seed;
        }
        @org.springframework.web.bind.annotation.GetMapping("/api/__test/cancellations/{number}/database")
        Object cancellationDatabase(@org.springframework.web.bind.annotation.PathVariable String number,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token) {
            own(number,principal,session,request,token);UUID order=jdbc.queryForObject("SELECT id FROM marketplace.purchase_order WHERE order_number=:n",Map.of("n",number),UUID.class);var p=Map.of("id",order);var result=new java.util.LinkedHashMap<String,Object>();result.put("order",jdbc.queryForMap("SELECT id,state,amount,quote_snapshot::text AS quote FROM marketplace.purchase_order WHERE id=:id",p));for(String table:List.of("claim","claim_unit","claim_event","claim_refund_effect","claim_refund_result_receipt")){result.put(table,jdbc.queryForList("SELECT c.* FROM marketplace."+table+" c JOIN marketplace.claim parent ON parent.id="+(table.equals("claim")?"c.id":"c.claim_id")+" WHERE parent.order_id=:id ORDER BY 1,2",p));}for(String table:List.of("payment_refund_attempt","payment_refund_budget","order_unit_lifecycle","order_unit_hold","order_aftercare_closure")){result.put(table,jdbc.queryForList("SELECT * FROM marketplace."+table+" WHERE order_id=:id ORDER BY 1,2",p));}result.put("units",jdbc.queryForList("SELECT u.* FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.order_id=:id ORDER BY u.item_id,u.ordinal",p));result.put("provider",jdbc.queryForList("SELECT m.* FROM marketplace.payment_refund_mock m JOIN marketplace.payment_refund_attempt a ON a.id=m.attempt_id WHERE a.order_id=:id ORDER BY m.attempt_id",p));result.put("restored",jdbc.queryForList("SELECT r.* FROM marketplace.inventory_restitution_unit r JOIN marketplace.order_item i ON i.id=r.item_id WHERE i.order_id=:id ORDER BY r.item_id,r.ordinal",p));result.put("inventory",jdbc.queryForList("SELECT r.id,r.status,r.quantity,b.on_hand,b.reserved FROM marketplace.inventory_reservation r JOIN marketplace.order_reservation x ON x.reservation_id=r.id JOIN marketplace.inventory_balance b ON b.sku_id=r.sku_id WHERE x.order_id=:id ORDER BY r.id",p));result.put("coupons",jdbc.queryForList("SELECT c.* FROM marketplace.coupon_restoration c JOIN marketplace.seller_order s ON s.id=c.seller_order_ref WHERE s.order_id=:id ORDER BY c.cause_id",p));return result;
        }
        @org.springframework.web.bind.annotation.PostMapping("/api/__test/cancellations/{number}/worker")
        Object cancellationWorker(@org.springframework.web.bind.annotation.PathVariable String number,@org.springframework.web.bind.annotation.RequestBody Map<String,String> input,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token) {
            own(number,principal,session,request,token);UUID attempt=UUID.fromString(input.get("attempt"));if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt a JOIN marketplace.purchase_order o ON o.id=a.order_id WHERE a.id=:id AND o.order_number=:n",Map.of("id",attempt,"n",number),Integer.class)!=1){throw new com.example.marketplace.order.api.OrderRejected(404);}String type=switch(input.get("action")){case "submit"->"REFUND_SUBMIT.v1";case "result"->"REFUND_RESULT_RECORDED.v1";default->throw new com.example.marketplace.order.api.OrderRejected(400);};UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status IN ('READY','RUNNING') ORDER BY scheduled_at DESC,id LIMIT 1",Map.of("type",type,"cause",attempt.toString()),UUID.class);var row=jdbc.queryForMap("SELECT status,next_run_at,lease_until FROM marketplace.job WHERE id=:id",Map.of("id",id));Instant at=((java.sql.Timestamp)row.get(row.get("status").equals("RUNNING")?"lease_until":"next_run_at")).toInstant().plusSeconds(1);var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(at,ZoneOffset.UTC));try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,handlers)){if(!worker.runOnce()||!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")){throw new IllegalStateException("CANCELLATION_FIXTURE_WORKER_FAILED");}}return Map.of("done",true);
        }
        @org.springframework.web.bind.annotation.PostMapping("/api/__test/shipments/{number}/worker")
        Object worker(@org.springframework.web.bind.annotation.PathVariable String number,@org.springframework.web.bind.annotation.RequestBody Map<String,String> input,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token) {
            own(number,principal,session,request,token);String key;
            if("prepare".equals(input.get("action"))) { key=jdbc.queryForObject("SELECT dedupe_key FROM marketplace.job WHERE type='PAYMENT_SETTLED' AND payload LIKE :p",Map.of("p",number+":%"),String.class); }
            else { UUID shipment=UUID.fromString(input.get("shipment"));if(jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment WHERE id=:id AND order_number=:n",Map.of("id",shipment,"n",number),Integer.class)!=1) { throw new com.example.marketplace.order.api.OrderRejected(404); }long epoch=input.containsKey("epoch")?Long.parseLong(input.get("epoch")):jdbc.queryForObject("SELECT progress_epoch FROM marketplace.shipment WHERE id=:id",Map.of("id",shipment),Long.class);key=epoch==0?"progress:"+shipment+":"+input.get("target"):"progress:v2:"+shipment+":"+epoch+":"+input.get("target"); }
            UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",key),UUID.class);Instant at=jdbc.queryForObject("SELECT next_run_at FROM marketplace.job WHERE id=:id",Map.of("id",id),java.sql.Timestamp.class).toInstant();
            var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(at,ZoneOffset.UTC));try(var engine=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,handlers)) { if(!engine.runOnce()||!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")) { throw new IllegalStateException("FIXTURE_WORKER_FAILED"); } }return Map.of("done",true);
        }
        @org.springframework.web.bind.annotation.PostMapping("/api/__test/shipments/{number}/exception")
        Object exception(@org.springframework.web.bind.annotation.PathVariable String number,@org.springframework.web.bind.annotation.RequestBody Map<String,String> input,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token) {
            own(number,principal,session,request,token);UUID id=UUID.fromString(input.get("shipment"));if(jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment WHERE id=:id AND order_number=:n",Map.of("id",id,"n",number),Integer.class)!=1) { throw new com.example.marketplace.order.api.OrderRejected(404); }return shipmentApi.exception(id,input.get("key"),input.get("target"),"브라우저 모의 예외",Instant.parse(input.get("at")));
        }
        @org.springframework.web.bind.annotation.GetMapping("/api/__test/purchase/{number}/database")
        Object purchaseDatabase(@org.springframework.web.bind.annotation.PathVariable String number,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token) {
            own(number,principal,session,request,token);var args=Map.of("n",number);var result=new java.util.LinkedHashMap<String,Object>();
            result.put("order",jdbc.queryForMap("SELECT id,state,amount FROM marketplace.purchase_order WHERE order_number=:n",args));
            result.put("inventory",jdbc.queryForList("SELECT i.sku_id,i.quantity,i.status FROM marketplace.order_reservation r JOIN marketplace.purchase_order o ON o.id=r.order_id JOIN marketplace.inventory_reservation i ON i.id=r.reservation_id WHERE o.order_number=:n ORDER BY i.sku_id",args));
            result.put("coupons",jdbc.queryForList("SELECT c.state FROM marketplace.order_reservation r JOIN marketplace.purchase_order o ON o.id=r.order_id JOIN marketplace.coupon_reservation c ON c.id=r.reservation_id WHERE o.order_number=:n",args));
            result.put("attempts",jdbc.queryForList("SELECT a.state,a.compensation,coalesce(m.approval_calls,0) AS approval_calls,coalesce(m.approval_effects,0) AS approval_effects,coalesce(m.cancel_effects,0) AS cancel_effects,(SELECT count(*) FROM marketplace.payment_event e WHERE e.attempt_id=a.id AND e.action='SUCCEEDED') AS success_events FROM marketplace.payment_attempt a JOIN marketplace.purchase_order o ON o.id=a.order_id LEFT JOIN marketplace.payment_mock m ON m.attempt_id=a.id WHERE o.order_number=:n",args));
            return result;
        }
        @org.springframework.web.bind.annotation.GetMapping("/api/__test/shipments/{number}/database")
        Object database(@org.springframework.web.bind.annotation.PathVariable String number,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token) {
            own(number,principal,session,request,token);return Map.of("shipments",jdbc.queryForList("SELECT id,seller_order_id,state,version FROM marketplace.shipment WHERE order_number=:n ORDER BY id",Map.of("n",number)),"items",jdbc.queryForList("SELECT i.shipment_id,i.order_item_id,i.quantity FROM marketplace.shipment_item i JOIN marketplace.shipment s ON s.id=i.shipment_id WHERE s.order_number=:n ORDER BY i.shipment_id,i.order_item_id",Map.of("n",number)),"events",jdbc.queryForList("SELECT e.id,e.shipment_id,e.source,e.actor,e.occurred_at,e.processed_at,e.previous_state,e.next_state,e.version,e.applied FROM marketplace.shipment_event e JOIN marketplace.shipment s ON s.id=e.shipment_id WHERE s.order_number=:n ORDER BY e.event_sequence",Map.of("n",number)),"units",jdbc.queryForList("SELECT u.shipment_id,u.order_item_id,u.ordinal FROM marketplace.shipment_unit u JOIN marketplace.shipment s ON s.id=u.shipment_id WHERE s.order_number=:n ORDER BY u.order_item_id::text,u.ordinal",Map.of("n",number)),"facts",jdbc.queryForList("SELECT f.* FROM marketplace.order_initial_delivery f JOIN marketplace.purchase_order o ON o.id=f.order_id WHERE o.order_number=:n ORDER BY f.event_id",Map.of("n",number)),"tracking",jdbc.queryForList("SELECT id,tracking,exception_status,progress_epoch FROM marketplace.shipment WHERE order_number=:n ORDER BY id",Map.of("n",number)),"trackingHistory",jdbc.queryForList("SELECT h.* FROM marketplace.shipment_tracking_history h JOIN marketplace.shipment s ON s.id=h.shipment_id JOIN marketplace.shipment_event e ON e.id=h.event_id WHERE s.order_number=:n ORDER BY e.event_sequence",Map.of("n",number)));
        }
        @org.springframework.web.bind.annotation.GetMapping("/api/__test/confirmations/{number}/database")
        Object confirmationsDatabase(@org.springframework.web.bind.annotation.PathVariable String number,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token) {
            own(number,principal,session,request,token);var p=Map.of("n",number);var result=new java.util.LinkedHashMap<String,Object>();result.put("order",jdbc.queryForMap("SELECT id,state,amount FROM marketplace.purchase_order WHERE order_number=:n",p));for(String table:List.of("order_unit_lifecycle","order_confirmation","order_confirmation_unit","order_unit_hold","order_lifecycle_event","order_confirmation_schedule","order_aftercare_policy","order_dispatch")) {result.put(table,jdbc.queryForList("SELECT t.* FROM marketplace."+table+" t JOIN marketplace.purchase_order o ON o.id=t.order_id WHERE o.order_number=:n ORDER BY 1,2",p));}result.put("closedEvents",jdbc.queryForList("SELECT e.* FROM marketplace.order_event e JOIN marketplace.purchase_order o ON o.id=e.order_id WHERE o.order_number=:n AND e.action='CLOSED'",p));return result;
        }
        @org.springframework.web.bind.annotation.GetMapping("/api/__test/confirmations/guest-seed/{project}")
        Object confirmationGuestSeed(@org.springframework.web.bind.annotation.PathVariable String project) {
            if(!List.of("desktop","mobile").contains(project)) {throw new com.example.marketplace.order.api.OrderRejected(400);}var row=jdbc.queryForMap("SELECT o.order_number,c.buyer_phone FROM marketplace.purchase_order o JOIN marketplace.order_checkout_attempt a ON a.id=o.checkout_id JOIN marketplace.order_contact c ON c.order_id=o.id WHERE a.request_key=:key AND o.guest_buyer_id IS NOT NULL",Map.of("key","p09-b-browser-confirmationguest-"+project));return Map.of("orderNumber",row.get("order_number"),"phone",row.get("buyer_phone"));
        }
        @org.springframework.web.bind.annotation.GetMapping("/api/__test/shipments/prepared/{project}/{scenario}")
        Object pendingShipmentSeed(@org.springframework.web.bind.annotation.PathVariable String project,@org.springframework.web.bind.annotation.PathVariable String scenario,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request) {
            if(!List.of("desktop","mobile").contains(project)||!List.of("shipmentsplit","shipmenteditfailure","shipmentkeyboard").contains(scenario)) {throw new com.example.marketplace.order.api.OrderRejected(400);}String number=jdbc.queryForObject("SELECT o.order_number FROM marketplace.purchase_order o JOIN marketplace.order_checkout_attempt a ON a.id=o.checkout_id WHERE a.request_key=:key",Map.of("key","p09-b-pending-"+scenario+"-"+project),String.class);own(number,principal,session,request,null);return Map.of("orderNumber",number);
        }
    }









    protected void awaitHistoryLock(String table) {
        try (var contractTimingScope = ContractTiming.measure("wait", "awaitHistoryLock")) { long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline) { jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :table AND query LIKE '%FOR SHARE%')",Map.of("table","%"+table+"%"),Boolean.class))) { return; }try { Thread.sleep(20); }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); } }throw new AssertionError("Actual history FOR SHARE wait on "+table); 
        }
    }




    protected com.example.marketplace.fulfillment.infrastructure.JdbcShipments shipments() { return context.getBean(com.example.marketplace.fulfillment.infrastructure.JdbcShipments.class); }
    protected com.example.marketplace.order.api.OrderQueries.Authority shipmentAuthority(PaymentFixture f) { return new com.example.marketplace.order.api.OrderQueries.Authority(f.authority().browser(),f.authority().member(),f.authority().version(),null); }
    protected UUID ownSeller(PaymentFixture f) { UUID store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(f.authority().member()).id();return f.order().sellers().stream().filter(s->s.storeId().equals(store)).findFirst().orElseThrow().id(); }
    protected UUID settlementJob(PaymentFixture f) { return jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='PAYMENT_SETTLED' AND payload LIKE :p",Map.of("p",f.order().orderNumber()+":%"),UUID.class); }
    protected void shipmentJob(UUID id,Instant now) {
        var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(now,ZoneOffset.UTC));
        try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.copyOf(context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values()))) { assertTrue(worker.runOnce()); }
        assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status(),jobs.find(id).orElseThrow().lastError());
    }
    protected com.example.marketplace.fulfillment.api.Shipments.View paidShipment(PaymentFixture f) {
        try (var contractTimingScope = ContractTiming.measure("preparation", "paidShipment")) { payments().approve(f.order().orderNumber(),f.authority());shipmentJob(settlementJob(f),Instant.now());return shipments().seller(ownSeller(f),shipmentAuthority(f)).getFirst(); 
        }
    }
    protected com.example.marketplace.fulfillment.api.Shipments.Command shipCommand(com.example.marketplace.fulfillment.api.Shipments.View v,String key) { return new com.example.marketplace.fulfillment.api.Shipments.Command(key,v.version(),v.items()); }
    protected Map<String,Object> shipBody(com.example.marketplace.fulfillment.api.Shipments.View v,String key) { return Map.of("key",key,"version",v.version(),"items",v.items()); }
    protected Map<String,List<Map<String,Object>>> shipmentState() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "shipmentState")) { var state=new java.util.LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("shipment","shipment_item","shipment_unit","shipment_preparation","shipment_command","shipment_tracking_history","shipment_event","shipment_audit","order_initial_delivery","order_initial_delivery_unit","job","outbox_event")) { state.put(table,jdbc.queryForList("SELECT * FROM marketplace."+table+" ORDER BY 1,2",Map.of())); }return state; 
        }
    }

    protected com.example.marketplace.order.infrastructure.JdbcOrderConfirmations confirmations() { return context.getBean(com.example.marketplace.order.infrastructure.JdbcOrderConfirmations.class); }
    protected com.example.marketplace.order.infrastructure.JdbcOrderConfirmations confirmationsWith(Clock clock,com.example.marketplace.order.infrastructure.JdbcOrderConfirmations.Checkpoint checkpoint) { return confirmationsWith(clock,checkpoint,context.getBean(com.example.marketplace.order.api.OrderQueries.class)); }
    protected com.example.marketplace.order.infrastructure.JdbcOrderConfirmations confirmationsWith(Clock clock,com.example.marketplace.order.infrastructure.JdbcOrderConfirmations.Checkpoint checkpoint,com.example.marketplace.order.api.OrderQueries queries) { return new com.example.marketplace.order.infrastructure.JdbcOrderConfirmations(jdbc,queries,orders(),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),jobs(clock),transactionManager,clock,checkpoint); }
    protected void completeShipment(PaymentFixture f,com.example.marketplace.fulfillment.api.Shipments.View v,Instant at) { at=at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);shipments().ship(v.id(),shipmentAuthority(f),shipCommand(v,"ship-"+v.id()));shipments().progress(v.id(),"transit-"+v.id(),"IN_TRANSIT",1,at.minusSeconds(1));shipments().progress(v.id(),"done-"+v.id(),"DELIVERED",2,at); }
    protected String browserSession(HttpClient browser) {return new String(java.util.Base64.getDecoder().decode(cookieValue(browser)),java.nio.charset.StandardCharsets.UTF_8);}
    /** Actual current application ports seed only duplicate checkout/shipping setup; browser still owns confirmation HTTP/UI assertions. */
    protected void seedConfirmationBrowserOrders(String project) throws Exception {
        for(String scenario:List.of("confirmationmember","confirmationguest","confirmationfailure","confirmationlate","reviewmember","questionmember")) {boolean guest=scenario.equals("confirmationguest");var client=authBrowser();csrfToken(client);UUID member=guest?null:jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE email=:email",Map.of("email","order-"+scenario+"-"+project+"@example.com"),UUID.class);Long version=null;if(member!=null) {assertEquals(200,authRequest(client,"/api/auth/login",loginForm("order-"+scenario+"-"+project+"@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",member),Long.class);}String browser=browserSession(client);UUID attempt=null;com.example.marketplace.guest.api.GuestOrders guestPort=null;if(guest) {var f=new GuestFixture(browser,Instant.now());attempt=guestVerified(f);var access=guestAccess(f);access.prepare(browser,attempt,new com.example.marketplace.guest.api.GuestPassword("f2 actual guest password","f2 actual guest password"));guestPort=access;}
            var lines=new java.util.ArrayList<com.example.marketplace.promotion.api.Quotes.LineRequest>();for(String side:List.of("a","b")) {UUID sku=jdbc.queryForObject("SELECT k.id FROM marketplace.catalog_sku k JOIN marketplace.catalog_product p ON p.id=k.product_id WHERE p.slug=:slug",Map.of("slug","p4b-browse-order-"+scenario+"-"+project+"-"+side),UUID.class);lines.add(quoteLine(sku,side.equals("a")?5:1));}var q=quotes().calculate(member,lines,List.of());var contact=new com.example.marketplace.order.api.Orders.Contact("F2 구매자","01012345678","F2 수령인","01087654321","01234","서울시 B 배송 검증 주소","101호");var input=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("p09-b-browser-"+scenario+"-"+project,lines,List.of(),q.fingerprint(),contact,attempt,guest?0:null);var saved=guest?creationWith(guestPort,stage->{ }).create(browser,null,null,input):creation().createForSession(browser,member,version,null,input);var authority=new com.example.marketplace.payment.api.Payments.Authority(browser,member,version,null);assertEquals("PAID",payments().approve(saved.orderNumber(),authority).orderState());var fixture=new PaymentFixture(saved,client,authority);shipmentJob(settlementJob(fixture),Instant.now());
            UUID seller=jdbc.queryForObject("SELECT so.id FROM marketplace.seller_order so JOIN marketplace.seller_store s ON s.id=so.store_id JOIN marketplace.identity_user u ON u.id=s.owner_id WHERE so.order_id=:id AND u.email=:email",Map.of("id",saved.id(),"email","p4b-browse-order-"+scenario+"-"+project+"-a@example.com"),UUID.class);var actor=asSeller(fixture,seller);var source=shipments().seller(seller,shipmentAuthority(actor)).getFirst();var split=shipments().split(source.id(),shipmentAuthority(actor),splitCommand(source,"browser-two",2));var target=shipments().seller(seller,shipmentAuthority(actor)).stream().filter(s->s.id().equals(split.target().id())).findFirst().orElseThrow();shipments().ship(target.id(),shipmentAuthority(actor),shipCommand(target,"browser-target"));for(String stage:List.of("IN_TRANSIT","DELIVERED")) {UUID job=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key","progress:"+target.id()+":"+stage),UUID.class);Instant at=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(job).orElseThrow().command().nextRunAt();shipmentJob(job,at);}assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_initial_delivery_unit WHERE order_id=:id",Map.of("id",saved.id()),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_confirmation WHERE order_id=:id",Map.of("id",saved.id()),Integer.class));
        }
    }
    protected PhysicalReturn browserPhysicalUnits(ReturnParties parties,List<com.example.marketplace.fulfillment.api.Shipments.Unit> selected,String key) {
        var api=context.getBean(com.example.marketplace.claim.api.Returns.class);var units=selected.stream().map(u->new com.example.marketplace.claim.api.Returns.Unit(u.orderItemId(),u.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)).toList();var accepted=api.request(parties.buyer().order().orderNumber(),shipmentAuthority(parties.buyer()),new com.example.marketplace.claim.api.Returns.Request(key,confirmations().get(parties.buyer().order().orderNumber(),shipmentAuthority(parties.buyer())).revision(),units,"브라우저 독립 시작 상태의 실제 접수"));api.approve(accepted.returnId(),shipmentAuthority(parties.seller()),new com.example.marketplace.claim.api.Returns.Command("browser-seed-approve",0L,"실제 판매자 준비 승인"));var received=context.getBean(com.example.marketplace.claim.api.ReturnCollections.class).receive(accepted.returnId(),shipmentAuthority(parties.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("browser-seed-receive",1L,units.stream().map(u->new com.example.marketplace.claim.api.ReturnCollections.Unit(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)).toList(),"실제 원단위 준비 수령"));return new PhysicalReturn(parties,accepted,received);
    }
    protected void registerReturnBrowserSeed(String project,String scenario,ReturnParties parties,String supportEmail,String foreignSellerEmail,Map<String,Object> extra) {
        var seed=new java.util.LinkedHashMap<String,Object>();seed.put("orderNumber",parties.buyer().order().orderNumber());seed.put("sellerOrderId",parties.shipment().sellerOrderId());seed.put("guest",parties.buyer().authority().member()==null);seed.put("buyerEmail",parties.buyer().authority().member()==null?"":jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",parties.buyer().authority().member()),String.class));seed.put("sellerEmail",jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",parties.seller().authority().member()),String.class));seed.put("supportEmail",supportEmail);seed.put("foreignSellerEmail",foreignSellerEmail);seed.putAll(extra);ReturnBrowserFixture.register(project,scenario,seed,shipmentAuthority(parties.seller()));
    }
    protected void registerExchangeBrowserSeed(String project,String scenario,ReturnParties f,String supportEmail,UUID target,Map<String,Object> extra) {
        var seed=new java.util.LinkedHashMap<String,Object>();seed.put("orderNumber",f.buyer().order().orderNumber());seed.put("sellerOrderId",f.shipment().sellerOrderId());seed.put("guest",f.buyer().authority().member()==null);seed.put("buyerEmail",f.buyer().authority().member()==null?"":jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",f.buyer().authority().member()),String.class));seed.put("sellerEmail",jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",f.seller().authority().member()),String.class));seed.put("supportEmail",supportEmail);seed.put("targetSkuId",target);seed.putAll(extra);ExchangeBrowserFixture.register(project,scenario,seed);
    }
    protected void seedRefundBrowserOrders(String project) throws Exception {
        String previous=fixtureClientSource.get();String local=project.equals("desktop")?"127.0.0.11":"127.0.0.12";fixtureClientSource.set(local);
        try {String suffix=project.substring(0,1);var operator=supportFixture("fb-op-"+suffix,"SYSTEM");var system=new com.example.marketplace.payment.api.Payments.Authority(operator.authority().browser(),operator.authority().member(),operator.authority().version(),null);
            var member=paymentFixture("fb-"+suffix,false);seedRefundBrowserOrder(project,"member",member,"",system);
            var guest=cancellationGuest("fb-"+suffix);var browser=authBrowser();assertEquals(200,accountRequest(browser,"POST","/api/guest/access/login",Map.of("orderNumber",guest.order().orderNumber(),"phone",guest.fixture().phone,"password",GUEST_PASSWORD),csrfToken(browser)).statusCode());String token=((java.net.CookieManager)browser.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c->c.getName().equals("GUEST_ACCESS")).findFirst().orElseThrow().getValue();var paid=new PaymentFixture(guest.order(),browser,new com.example.marketplace.payment.api.Payments.Authority(browserSession(browser),null,null,token));seedRefundBrowserOrder(project,"guest",paid,guest.fixture().phone,system);
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"f-browser-seed-"+project+".json"),authMapper.writeValueAsString(Map.of("scenarios",2,"actualSignupQuotesPaidGuestQualificationAndCancellation",true,"preparationSource",local,"fabricatedBusinessReceipt",false,"providerMode","DEMO","original154Preserved",true)));
        }finally{fixtureClientSource.set(previous);}
    }
    private void seedRefundBrowserOrder(String project,String scenario,PaymentFixture fixture,String phone,com.example.marketplace.payment.api.Payments.Authority system) throws Exception {
        var shipment=fixture.authority().member()==null?shipments().seller(fixture.order().sellers().getFirst().id(),shipmentAuthority(asSeller(fixture,fixture.order().sellers().getFirst().id()))).getFirst():paidShipment(fixture);var selected=shipment.units().stream().map(u->Map.of("itemId",u.orderItemId(),"ordinal",u.ordinal())).toList();var current=new com.example.marketplace.order.api.OrderQueries.Authority(fixture.authority().browser(),fixture.authority().member(),fixture.authority().version(),fixture.authority().guestToken());if(fixture.authority().member()==null){assertTrue(current.guestToken()!=null&&current.guestToken().length()==43,"prepared GAS token shape without exposing its value");}assertEquals(200,accountRequest(fixture.browser(),"GET","/api/orders/"+fixture.order().orderNumber()+"/refunds",null,null).statusCode(),"actual current owner HTTP must be valid before cancellation and browser preparation");var receipt=acceptCancellation(fixture,selected,"f-browser-"+scenario,current);UUID attempt=UUID.fromString(receipt.path("refundAttemptId").asText()),claim=UUID.fromString(receipt.path("claimId").asText());jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','UNKNOWN')",Map.of("id",claim));runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);String email=fixture.authority().member()==null?"":jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",fixture.authority().member()),String.class);String amount=jdbc.queryForObject("SELECT amount::text FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",attempt),String.class);RefundBrowserFixture.register(project,scenario,Map.of("orderNumber",fixture.order().orderNumber(),"attemptId",attempt,"buyerEmail",email,"phone",phone,"guest",fixture.authority().member()==null,"amount",amount),attempt,system);
    }

    protected void seedPostPurchaseBrowserOrders(String project) throws Exception {
        String old=fixtureClientSource.get();fixtureClientSource.set(project.equals("desktop")?"127.0.0.15":"127.0.0.16");
        try {String p=project.substring(0,1);var operator=supportFixture("gs-op-"+p,"SYSTEM");var support=supportFixture("gs-sp-"+p,"SUPPORT");var f=sandboxFixture("gs-"+p,false);String key="fixture-g-system-"+project+"-"+UUID.randomUUID();var provider=tossFixture(f,key,"DONE");assertEquals(200,accountRequest(f.browser(),"POST","/api/payments/"+f.order().orderNumber()+"/confirm",tossConfirm(f,key),csrfToken(f.browser())).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");
            var seller=f.order().sellers().stream().filter(value->value.id().equals(ownSeller(f))).findFirst().orElseThrow();var receipt=acceptCancellation(f,List.of(Map.of("itemId",seller.items().getFirst().id(),"ordinal",0)),"g_system_"+p);UUID attempt=UUID.fromString(receipt.path("refundAttemptId").asText());provider.put("refundLose",true);var driver=context.getBean(RefundBrowserFixture.class);UUID submit=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key","refund-submit:"+attempt),UUID.class);driver.run(submit,Clock.systemUTC());var result=context.getBean(com.example.marketplace.payment.api.Refunds.class);long version=new TransactionTemplate(transactionManager).execute(t->result.get(attempt).version());UUID effect=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key","refund-result:"+attempt+":"+version),UUID.class);driver.run(effect,Clock.systemUTC());
            String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",f.authority().member()),String.class),op=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",operator.authority().member()),String.class),sp=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",support.authority().member()),String.class),amount=jdbc.queryForObject("SELECT amount::text FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",attempt),String.class);
            var data=Map.<String,Object>of("orderNumber",f.order().orderNumber(),"attemptId",attempt,"buyerEmail",email,"operatorEmail",op,"supportEmail",sp,"amount",amount,"mode","SANDBOX");PostPurchaseBrowserFixture.register(project,data);RefundBrowserFixture.register(project,"g-system",data,attempt,new com.example.marketplace.payment.api.Payments.Authority(operator.authority().browser(),operator.authority().member(),operator.authority().version(),null));
            assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",attempt),String.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_provider_observation WHERE attempt_id=:id AND state='UNKNOWN' AND response_fingerprint IS NULL",Map.of("id",attempt),Integer.class));
        } finally {fixtureClientSource.set(old);}
    }

    protected void seedExchangeBrowserOrders(String project) throws Exception {
        String previousSource=fixtureClientSource.get();fixtureClientSource.set("127.0.0.9");try {
        long started=System.nanoTime();String suffix=project.substring(0,1);var operator=supportFixture("eb-op-"+suffix,"SUPPORT");var observedSource=accountRequest(operator.browser(),"GET","/api/__test/exchanges/source",null,null);assertEquals(200,observedSource.statusCode());assertEquals("127.0.0.9",authMapper.readTree(observedSource.body()).path("source").asText());String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",operator.authority().member()),String.class);
        for(String scenario:List.of("member","refund")){var f=returnParties("eb-"+suffix+"-"+scenario.substring(0,1),true,1,"5000");UUID target=actualExchangeTarget(f,"eb-"+suffix+"-"+scenario.substring(0,1));registerExchangeBrowserSeed(project,scenario,f,email,target,Map.of());}
        var guest=cancellationGuest("ebg-"+suffix);var paid=new PaymentFixture(guest.order(),authBrowser(),new com.example.marketplace.payment.api.Payments.Authority(guest.fixture().browser,null,null,guest.token()));var seller=asSeller(paid,guest.order().sellers().getFirst().id());var shipment=shipments().seller(guest.order().sellers().getFirst().id(),shipmentAuthority(seller)).getFirst();completeShipment(seller,shipment,Instant.now());var guestParties=new ReturnParties(paid,seller,shipment);UUID target=actualExchangeTarget(guestParties,"ebg-"+suffix),store=guest.order().sellers().getFirst().storeId();var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);var currentStore=stores.own(seller.authority().member());stores.transition(seller.authority().member(),currentStore.id(),"close",new com.example.marketplace.seller.api.Stores.Change(currentStore.version(),"actual guest existing-trade browser exception"));registerExchangeBrowserSeed(project,"guest",guestParties,email,target,Map.of("phone",guest.fixture().phone,"storeId",store));
        var financial=exchangeRefundAccepted("eb-s-"+suffix,true);UUID id=financial.physical().accepted().exchangeId(),sellerId=financial.physical().parties().shipment().sellerOrderId();runCancellationJob("REFUND_SUBMIT.v1",financial.conversion().refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",financial.conversion().refundAttemptId());runCancellationJob("EXCHANGE_REFUND_RESUME.v1",id);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",sellerId);UUID attempt=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE claim_id=:id",Map.of("id",id),UUID.class);jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'SHIPPING','FAILED')",Map.of("id",id));runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",sellerId);registerExchangeBrowserSeed(project,"shipping",financial.physical().parties(),email,financial.physical().targetSku(),Map.of("exchangeId",id));
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-browser-seeds-"+project+".json"),authMapper.writeValueAsString(Map.of("scenarios",4,"elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"actualSignupQuotePaymentDeliveryAndEPorts",true,"fabricatedBusinessReceipts",false,"existing146Preserved",true,"actualClientSource","127.0.0.9","authPosts",authPostObservations.stream().filter(v->v.get("source").equals("127.0.0.9")).toList())));
        }finally{fixtureClientSource.set(previousSource);java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-browser-auth-source-observations.json"),authMapper.writeValueAsString(authPostObservations));}
    }
    protected void seedReturnBrowserOrders(String project) throws Exception {
        long started=System.nanoTime();String p=project.substring(0,1);var operator=supportFixture("d-br-"+p,"SUPPORT");String supportEmail=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",operator.authority().member()),String.class);var foreign=returnParties("df-"+p);String foreignSellerEmail=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",foreign.seller().authority().member()),String.class);
        for(String scenario:List.of("ghappy","glate")) {
            var g=cancellationGuest("d-g"+(scenario.equals("ghappy")?"h":"l")+"-"+p);var paid=new PaymentFixture(g.order(),authBrowser(),new com.example.marketplace.payment.api.Payments.Authority(g.fixture().browser,null,null,g.token()));var seller=asSeller(paid,g.order().sellers().getFirst().id());var shipment=shipments().seller(g.order().sellers().getFirst().id(),shipmentAuthority(seller)).getFirst();completeShipment(seller,shipment,Instant.now());var parties=new ReturnParties(paid,seller,shipment);var extra=new java.util.LinkedHashMap<String,Object>();extra.put("phone",g.fixture().phone);
            if(scenario.equals("glate")){var owner=new com.example.marketplace.order.api.OrderQueries.Authority("d-browser-current-gas",null,null,g.token());var unit=shipment.units().getFirst();var accepted=context.getBean(com.example.marketplace.claim.api.Returns.class).request(g.order().orderNumber(),owner,new com.example.marketplace.claim.api.Returns.Request("d-browser-private",confirmations().get(g.order().orderNumber(),owner).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(unit.orderItemId(),unit.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"비회원 현재 private 반품 이력"));extra.put("returnId",accepted.returnId());}
            registerReturnBrowserSeed(project,scenario,parties,supportEmail,foreignSellerEmail,extra);
        }
        var states=returnFeeFixture("ds-"+p,true);var stateShipment=paidShipment(states);completeShipment(states,stateShipment,Instant.now());registerReturnBrowserSeed(project,"states",new ReturnParties(states,states,stateShipment),supportEmail,foreignSellerEmail,Map.of());
        var retry=returnParties("dr-"+p);var competing=recoveryPhysicalUnit(retry,retry.shipment().units().get(1),"d-browser-competing");var unit=competing.accepted().units().getFirst();context.getBean(com.example.marketplace.claim.api.ReturnInspections.class).inspect(competing.accepted().returnId(),shipmentAuthority(retry.seller()),new com.example.marketplace.claim.api.ReturnInspections.Command("d-browser-competing-inspect",2L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"실제 별도 원단위의 동의 전 견적 준비"));registerReturnBrowserSeed(project,"retry",retry,supportEmail,foreignSellerEmail,Map.of("competingReturnId",competing.accepted().returnId()));
        var actors=returnParties("da-"+p);var ids=new java.util.ArrayList<UUID>();var api=context.getBean(com.example.marketplace.claim.api.Returns.class);for(int index=0;index<3;index++){var selected=actors.shipment().units().get(index);var accepted=api.request(actors.buyer().order().orderNumber(),shipmentAuthority(actors.buyer()),new com.example.marketplace.claim.api.Returns.Request("d-browser-history-"+index,confirmations().get(actors.buyer().order().orderNumber(),shipmentAuthority(actors.buyer())).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(selected.orderItemId(),selected.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"역할별 현재 명령의 실제 시작 접수"));ids.add(accepted.returnId());}registerReturnBrowserSeed(project,"actors",actors,supportEmail,foreignSellerEmail,Map.of("withdrawId",ids.get(0),"approveId",ids.get(1),"rejectId",ids.get(2)));
        for(String outcome:List.of("FAILED","LOST_RESPONSE")){var item=returnParties("di-"+p+"-"+(outcome.equals("FAILED")?"f":"u"));var physical=recoveryPhysicalUnit(item,item.shipment().units().getFirst(),"d-browser-item");var consent=consentSellerReturnItem(physical);jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM',:outcome)",Map.of("id",physical.accepted().returnId(),"outcome",outcome));runCancellationJob("REFUND_SUBMIT.v1",consent.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",consent.refundAttemptId());runCancellationJob("RETURN_FINANCE_RESUME.v1",physical.accepted().returnId());registerReturnBrowserSeed(project,outcome.equals("FAILED")?"item":"itemunknown",item,supportEmail,foreignSellerEmail,Map.of(outcome.equals("FAILED")?"failedReturnId":"unknownReturnId",physical.accepted().returnId()));}
        var shippingPaid=cancellationMoneyFixture("dx-"+p,false,false);var shippingView=paidShipment(shippingPaid);completeShipment(shippingPaid,shippingView,Instant.now());var shippingParties=new ReturnParties(shippingPaid,shippingPaid,shippingView);var first=browserPhysicalUnits(shippingParties,shippingView.units().subList(0,1),"d-browser-shipping-one");settleSellerReturnItem(first);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",shippingView.sellerOrderId());assertEquals("COMPLETED",jdbc.queryForObject("SELECT state FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",first.accepted().returnId()),String.class));var last=browserPhysicalUnits(shippingParties,shippingView.units().subList(1,3),"d-browser-shipping-last");settleSellerReturnItem(last);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",shippingView.sellerOrderId());UUID origin=jdbc.queryForObject("SELECT claim_id FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",Map.of("id",shippingView.sellerOrderId()),UUID.class),attempt=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",Map.of("id",shippingView.sellerOrderId()),UUID.class);assertEquals("RETURN",new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.claim.api.FinancialIntents.class).lock(origin)).kind());jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'SHIPPING','FAILED')",Map.of("id",origin));runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",shippingView.sellerOrderId());UUID otherView=origin.equals(first.accepted().returnId())?last.accepted().returnId():first.accepted().returnId();registerReturnBrowserSeed(project,"shipping",shippingParties,supportEmail,foreignSellerEmail,Map.of("originReturnId",origin,"viewReturnId",otherView,"oldCompletedReturnId",first.accepted().returnId()));
        var canceled=cancellationMoneyFixture("dc-"+p,false,false);var canceledShipment=paidShipment(canceled);var cancellation=acceptCancellation(canceled,canceledShipment.units().stream().map(u->Map.<String,Object>of("itemId",u.orderItemId(),"ordinal",u.ordinal())).toList(),"d-browser-pure-c");UUID claim=UUID.fromString(cancellation.path("claimId").asText()),itemAttempt=UUID.fromString(cancellation.path("refundAttemptId").asText());runCancellationJob("REFUND_SUBMIT.v1",itemAttempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",itemAttempt);UUID sAttempt=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE claim_id=:id",Map.of("id",claim),UUID.class);jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'SHIPPING','FAILED')",Map.of("id",claim));runCancellationJob("REFUND_SUBMIT.v1",sAttempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",sAttempt);registerReturnBrowserSeed(project,"cshipping",new ReturnParties(canceled,canceled,canceledShipment),supportEmail,foreignSellerEmail,Map.of("originClaimId",claim));
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"d-browser-seeds-"+project+".json"),authMapper.writeValueAsString(Map.of("project",project,"scenarioTitles",8,"isolatedOrders",9,"elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"actualSignupQuotePaymentDeliveryAndDPorts",true,"sqlFabricatedPhysicalOrRefundOutcome",false,"existing130Preserved",true)));
    }
    /** Only C setup uses actual creation/payment/fulfillment ports; browser owns all C GET/POST/UI/DB assertions. */
    protected void seedCancellationBrowserOrders(String project) throws Exception {
        for(String scenario:List.of("member","failure","late","policy","latest")) {
            var f=paymentFixture("a-c-browser-"+scenario+"-"+project,false);var shipment=paidShipment(f);var seed=new java.util.LinkedHashMap<String,Object>();seed.put("orderNumber",f.order().orderNumber());seed.put("email",jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",f.authority().member()),String.class));var line=f.order().quote().stores().stream().flatMap(s->s.lines().stream()).filter(l->l.skuId().equals(f.order().sellers().stream().filter(s->s.id().equals(shipment.sellerOrderId())).findFirst().orElseThrow().items().getFirst().skuId())).findFirst().orElseThrow();seed.put("productId",line.productId());seed.put("skuId",line.skuId());seed.put("title",line.title());seed.put("slug",jdbc.queryForObject("SELECT slug FROM marketplace.catalog_product WHERE id=:id",Map.of("id",line.productId()),String.class));seed.put("sellerOrderId",shipment.sellerOrderId());
            if(scenario.equals("late")) {var unit=shipment.units().getFirst();acceptCancellation(f,List.of(Map.of("itemId",unit.orderItemId(),"ordinal",unit.ordinal())),"browser-held-private-progress");}
            if(scenario.equals("latest")) {
                var unit=shipment.units().getFirst();var accepted=acceptCancellation(f,List.of(Map.of("itemId",unit.orderItemId(),"ordinal",unit.ordinal())),"browser-old-failure");UUID claim=UUID.fromString(accepted.get("claimId").asText()),attempt=UUID.fromString(accepted.get("refundAttemptId").asText());jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','FAILED')",Map.of("id",claim));runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);var operator=supportFixture("browser-latest-"+project,"SUPPORT");long revision=jdbc.queryForObject("SELECT version FROM marketplace.claim WHERE id=:id",Map.of("id",claim),Long.class);var retry=context.getBean(com.example.marketplace.claim.api.ClaimSupport.class).retryFailed(f.order().orderNumber(),operator.authority(),claim,"ITEM",new com.example.marketplace.claim.api.ClaimSupport.Command("browser-next",revision,"확정 실패 다음 시도 확인"));jdbc.update("UPDATE marketplace.payment_refund_mock_setting SET behavior='UNKNOWN' WHERE claim_id=:id AND purpose='ITEM'",Map.of("id",claim));runCancellationJob("REFUND_SUBMIT.v1",retry.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",retry.refundAttemptId());seed.put("claimId",claim);
            }
            C_BROWSER_SEEDS.put(project+":"+scenario,Map.copyOf(seed));
        }
        var guest=cancellationGuest("browser-"+project);C_BROWSER_SEEDS.put(project+":guest",Map.of("orderNumber",guest.order().orderNumber(),"phone",guest.fixture().phone,"sellerOrderId",guest.order().sellers().getFirst().id()));
    }
    protected Map<String,String> aftercareState() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "aftercareState")) { var state=new java.util.TreeMap<>(paymentParticipantState());for(String table:List.of("order_aftercare_policy","order_policy_migration","order_confirmation_state","order_unit_lifecycle","order_confirmation","order_confirmation_unit","order_unit_hold","order_hold_unit","order_lifecycle_event","order_dispatch","order_dispatch_unit","order_confirmation_schedule","order_confirmation_scan","order_initial_delivery","order_initial_delivery_unit","shipment","shipment_item","shipment_unit","shipment_event","shipment_audit","shipment_preparation","shipment_command","shipment_tracking_history")) { state.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class)); }return state; 
        }
    }
    protected void seedPendingShipmentBrowserOrders(String project) throws Exception {
        long started=System.nanoTime();for(String scenario:List.of("shipmentsplit","shipmenteditfailure","shipmentkeyboard")) {
            var client=authBrowser();assertEquals(200,authRequest(client,"/api/auth/login",loginForm("order-"+scenario+"-"+project+"@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());UUID member=jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE email=:email",Map.of("email","order-"+scenario+"-"+project+"@example.com"),UUID.class);long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",member),Long.class);var lines=new java.util.ArrayList<com.example.marketplace.promotion.api.Quotes.LineRequest>();for(String side:List.of("a","b")) {UUID sku=jdbc.queryForObject("SELECT k.id FROM marketplace.catalog_sku k JOIN marketplace.catalog_product p ON p.id=k.product_id WHERE p.slug=:slug",Map.of("slug","p4b-browse-order-"+scenario+"-"+project+"-"+side),UUID.class);lines.add(quoteLine(sku,side.equals("a")&&!scenario.equals("shipmentkeyboard")?5:1));}var q=quotes().calculate(member,lines,List.of());var contact=new com.example.marketplace.order.api.Orders.Contact("F2 구매자","01012345678","F2 수령인","01087654321","01234","서울시 배송 검증 주소 "+"긴주소".repeat(18),"101호");var saved=creation().createForSession(browserSession(client),member,version,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("p09-b-pending-"+scenario+"-"+project,lines,List.of(),q.fingerprint(),contact,null,null));assertEquals("PENDING_PAYMENT",saved.state());assertEquals(2,saved.sellers().size());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment WHERE order_id=:id",Map.of("id",saved.id()),Integer.class));
        }java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"pending-shipment-seed-"+project+".json"),authMapper.writeValueAsString(Map.of("project",project,"orders",3,"elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"finalState","PENDING_PAYMENT","shipments",0)));
    }
    protected UUID confirmationJob(com.example.marketplace.order.api.OrderConfirmations.Unit u,long generation) { return jdbc.queryForObject("SELECT j.id FROM marketplace.order_confirmation_schedule s JOIN marketplace.job j ON j.cause_key=s.id::text WHERE s.item_id=:i AND s.ordinal=:o AND s.generation=:g",Map.of("i",u.itemId(),"o",u.ordinal(),"g",generation),UUID.class); }
    protected void runConfirmation(UUID id,Clock clock) { runConfirmation(id,clock,clock); }
    protected void runConfirmation(UUID id,Clock claimClock,Clock businessClock) { var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,claimClock);var api=confirmationsWith(businessClock,stage->{ });String type=jobs.find(id).orElseThrow().command().type();var handler=new com.example.marketplace.platform.api.JobHandler() { public String type() { return type; }public Result handle(com.example.marketplace.platform.api.Jobs.Job job) { return api.automatic(job); } };try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler))) { assertTrue(worker.runOnce()); } }






    protected PaymentFixture cancellationMoneyFixture(String suffix,boolean discounted,boolean free) throws Exception {
        var d=orderDraft("c-money-"+suffix);var original=d.quote().stores().getFirst().lines().getFirst();var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);var sku=skus.list(d.memberId(),original.productId(),0).items().getFirst();skus.save(d.memberId(),original.productId(),sku.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),free?20000L:10001L,true,sku.version()));
        var selections=new java.util.ArrayList<com.example.marketplace.promotion.api.Quotes.CouponSelection>();
        if(!free) {
            var template=coupons().saveTemplate(d.memberId(),null,couponInput("원금 배분 쿠폰",discounted?2999:2,5,"DRAFT",0));template=coupons().saveTemplate(d.memberId(),template.id(),couponInput("원금 배분 쿠폰",discounted?2999:2,5,"ACTIVE",template.version()));var issued=coupons().claim(d.memberId(),template.id());selections.add(new com.example.marketplace.promotion.api.Quotes.CouponSelection(template.storeId(),issued.id()));
        }
        if(discounted) {
            var now=Instant.now();for(String scope:List.of("PRODUCT","SHIPPING")) {
                var targets=scope.equals("PRODUCT")?List.of(original.productId()):List.<UUID>of();var input=new com.example.marketplace.promotion.api.Promotions.Input("원래 "+scope+" 할인",scope,targets,now.minusSeconds(10),now.plusSeconds(3600),"FIXED",1000L,null,"DRAFT",0L);var promo=promotions().save(d.memberId(),null,input);promotions().save(d.memberId(),promo.id(),new com.example.marketplace.promotion.api.Promotions.Input(input.title(),scope,targets,input.startsAt(),input.endsAt(),"FIXED",1000L,null,"ACTIVE",promo.version()));
            }
        }
        var lines=List.of(quoteLine(sku.id(),3));var quote=quotes().calculate(d.memberId(),lines,selections);var saved=creation().create(d.purchaseSessionId().toString(),d.memberId(),null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("money-"+suffix,lines,selections,quote.fingerprint(),d.contact(),null,0));
        var browser=authBrowser();String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id",Map.of("id",d.memberId().toString()),String.class);long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),Long.class);return new PaymentFixture(saved,browser,new com.example.marketplace.payment.api.Payments.Authority(session,d.memberId(),version,null));
    }
    protected void cancellationMoneyConserved(PaymentFixture f,List<Long> expectedUnits,long shipping,long total) throws Exception {
        paidShipment(f);var detail=context.getBean(com.example.marketplace.order.api.OrderQueries.class).detail(f.order().orderNumber(),shipmentAuthority(f));assertEquals(1,detail.stores().size());var seller=detail.stores().getFirst();var item=seller.items().getFirst();assertEquals(expectedUnits,item.units().stream().map(u->Long.parseLong(u.net())).toList());assertEquals(Long.toString(total),detail.total());assertEquals(shipping,Long.parseLong(seller.shippingBase())-Long.parseLong(seller.shippingDiscount()));
        String snapshot=jdbc.queryForObject("SELECT quote_snapshot::text FROM marketplace.purchase_order WHERE id=:id",Map.of("id",f.order().id()),String.class);var reservations=context.getBean(com.example.marketplace.inventory.api.Reservations.class);var ids=orders().reservations(f.order().orderNumber()).inventory();var committed=reservations.inspect(f.order().checkoutId(),ids);assertTrue(committed.stream().allMatch(r->r.status().equals("COMMITTED")));long beforeStock=jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",item.skuId()),Long.class);
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);var original=f.order().quote().stores().getFirst().lines().getFirst();var product=products.own(f.authority().member(),original.productId());products.save(f.authority().member(),product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),product.description(),product.state(),product.version(),"5000"));var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);var sku=skus.list(f.authority().member(),product.id(),0).items().getFirst();skus.save(f.authority().member(),product.id(),sku.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),999999L,true,sku.version()));
        var first=acceptCancellation(f,List.of(Map.of("itemId",item.id(),"ordinal",2)),"money-first");assertEquals(Long.toString(expectedUnits.get(2)),first.get("itemAmount").asText());UUID firstAttempt=UUID.fromString(first.get("refundAttemptId").asText());assertEquals(beforeStock,jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",item.skuId()),Long.class));runCancellationJob("REFUND_SUBMIT.v1",firstAttempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",firstAttempt);assertEquals(beforeStock+1,jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",item.skuId()),Long.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE seller_order_ref=:id",Map.of("id",seller.sellerOrderId()),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_obligation WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));assertEquals("PAID",orders().find(f.order().orderNumber()).state());
        var last=acceptCancellation(f,List.of(Map.of("itemId",item.id(),"ordinal",1),Map.of("itemId",item.id(),"ordinal",0)),"money-last");long remaining=Math.addExact(expectedUnits.get(0),expectedUnits.get(1));assertEquals(Long.toString(remaining),last.get("itemAmount").asText());UUID lastAttempt=UUID.fromString(last.get("refundAttemptId").asText());runCancellationJob("REFUND_SUBMIT.v1",lastAttempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",lastAttempt);UUID shippingAttempt=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE order_id=:id",Map.of("id",f.order().id()),UUID.class);assertEquals(shipping,jdbc.queryForObject("SELECT amount FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",shippingAttempt),Long.class));assertEquals("PAID",orders().find(f.order().orderNumber()).state());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE seller_order_ref=:id",Map.of("id",seller.sellerOrderId()),Integer.class));
        runCancellationJob("REFUND_SUBMIT.v1",shippingAttempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",shippingAttempt);assertEquals(total,Math.addExact(Math.addExact(expectedUnits.get(2),remaining),shipping));assertEquals(total,jdbc.queryForObject("SELECT succeeded FROM marketplace.payment_refund_budget WHERE order_id=:id",Map.of("id",f.order().id()),Long.class));assertEquals(0L,jdbc.queryForObject("SELECT reserved FROM marketplace.payment_refund_budget WHERE order_id=:id",Map.of("id",f.order().id()),Long.class));assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_restitution_unit WHERE item_id=:id",Map.of("id",item.id()),Integer.class));assertEquals(beforeStock+3,jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",item.skuId()),Long.class));assertEquals(committed,reservations.inspect(f.order().checkoutId(),ids));assertEquals(snapshot,jdbc.queryForObject("SELECT quote_snapshot::text FROM marketplace.purchase_order WHERE id=:id",Map.of("id",f.order().id()),String.class));assertEquals("CANCELED",orders().find(f.order().orderNumber()).state());assertEquals(f.order().quote().stores().getFirst().coupon()==null?0:1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE seller_order_ref=:id AND restored",Map.of("id",seller.sellerOrderId()),Integer.class));
        if(shipping==0) { assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",shippingAttempt),Integer.class),"zero original shipping is durably successful without provider call"); }
        var before=aftercareState();payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);assertEquals(before,aftercareState());assertEquals("NONE",paymentAttempt(f).get("compensation"));java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("original-money-"+total+".txt"),"units="+expectedUnits+";first="+expectedUnits.get(2)+";remainingItems="+remaining+";shipping="+shipping+";total="+total+";stockRestored=3;originalReservationUnchanged=true;snapshotUnchanged=true");
    }


    protected Map<String,String> cancellationParticipants() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "cancellationParticipants")) {
        var state=new java.util.TreeMap<>(aftercareState());state.putAll(cancellationWithdrawalState(false));for(String table:List.of("claim_financial_intent","claim_financial_intent_unit","claim_financial_refund_binding","claim_support_command","payment_refund_query_request","claim_shipping_obligation","order_cancellation_settlement","order_cancellation_settlement_unit","order_cancellation_shipping","order_aftercare_closure")) {state.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));}return state;
    
        }
    }


    protected com.example.marketplace.claim.infrastructure.JdbcClaims cancellationWithQueries(com.example.marketplace.order.api.OrderQueries queries) {
        return new com.example.marketplace.claim.infrastructure.JdbcClaims(new com.example.marketplace.order.infrastructure.JdbcCancellationOrders(queries,jdbc),queries,orders(),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),reservations(),context.getBean(com.example.marketplace.inventory.api.Restitutions.class),couponReservations(),context.getBean(com.example.marketplace.order.api.AftercareSettlements.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),jdbc,context.getBean(com.example.marketplace.platform.api.Outbox.class),context.getBean(com.example.marketplace.platform.api.JobLeases.class),transactionManager);
    }
    protected void seedReleasedCancellationBudget(com.example.marketplace.claim.api.Claims api,String number,com.example.marketplace.order.api.OrderQueries.Authority authority,List<com.example.marketplace.order.api.OrderConfirmations.Unit> units) {
        long revision=jdbc.queryForObject("SELECT c.revision FROM marketplace.order_confirmation_state c JOIN marketplace.purchase_order o ON o.id=c.order_id WHERE o.order_number=:n",Map.of("n",number),Long.class);var q=api.quote(number,authority,new com.example.marketplace.claim.api.Claims.QuoteCommand(units,"잠금 검증 준비",revision));var accepted=api.cancel(number,authority,new com.example.marketplace.claim.api.Claims.CancelCommand("released-budget-seed",units,"잠금 검증 준비",revision,q.fingerprint()));var progress=api.get(number,authority).stream().filter(p->p.accepted().claimId().equals(accepted.claimId())).findFirst().orElseThrow();api.withdraw(number,authority,accepted.claimId(),new com.example.marketplace.claim.api.Claims.WithdrawCommand("release-budget-seed",progress.version(),"제출 전 준비 철회"));assertEquals(0L,jdbc.queryForObject("SELECT b.reserved FROM marketplace.payment_refund_budget b JOIN marketplace.purchase_order o ON o.id=b.order_id WHERE o.order_number=:n",Map.of("n",number),Long.class));
    }
    protected ConfirmationGuest cancellationGuest(String suffix) throws Exception {
        var browser=authBrowser();csrfToken(browser);var f=new GuestFixture(browserSession(browser),Instant.now());var access=guestAccess(f);var product=browseFixture("c-cancel-guest-"+suffix.replace('_','-'),10000,10);var lines=List.of(quoteLine((UUID)product.get("sku"),2));var quote=quotes().calculate(null,lines,List.of());UUID attempt=guestVerified(f);access.prepare(f.browser,attempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));var contact=new com.example.marketplace.order.api.Orders.Contact("ignored",f.phone,"수령인",f.phone,"01234","주소","");var order=creationWith(access,stage->{ }).create(f.browser,null,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("once",lines,List.of(),quote.fingerprint(),contact,attempt,0));String token=access.login(order.orderNumber(),f.phone,GUEST_PASSWORD,f.source,null);var api=paymentAuthorityFixture(context.getBean(com.example.marketplace.identity.api.PurchaseSessions.class),access);api.approve(order.orderNumber(),new com.example.marketplace.payment.api.Payments.Authority(f.browser,null,null,token));shipmentJob(jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='PAYMENT_SETTLED' AND payload LIKE :p",Map.of("p",order.orderNumber()+":%"),UUID.class),Instant.now());return new ConfirmationGuest(f,access,orders().find(order.orderNumber()),token);
    }
    protected void expireCancellationGasAtStoredDeadline(ConfirmationGuest guest,String label) throws Exception {
        Instant deadline=jdbc.queryForObject("SELECT idle_expires_at FROM marketplace.guest_access_session WHERE guest_buyer_id=:id AND token_hash=:token AND revoked_at IS NULL",Map.of("id",guest.order().guestBuyerId(),"token",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(guest.token())),java.sql.Timestamp.class).toInstant();Instant before=guest.fixture().clock.instant();guest.fixture().clock.now=deadline.plusNanos(1000);assertTrue(guest.fixture().clock.instant().isAfter(deadline),"actual C05 GAS clock must cross its saved idle deadline: "+label);java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"c05-gas-deadline-"+label+".json"),authMapper.writeValueAsString(Map.of("case",label,"beforeClock",before.toString(),"savedDeadline",deadline.toString(),"old1800Advance",before.plusSeconds(1800).toString(),"oldAdvanceExpired",!before.plusSeconds(1800).isBefore(deadline),"injectedClock",guest.fixture().clock.instant().toString(),"actualDeadlineCrossed",true)));
    }




    protected AcceptedReturnFee acceptReturnQuote(PaymentFixture f,com.example.marketplace.claim.api.ReturnFees.Quote quote,String key,com.example.marketplace.claim.api.ReturnFees fees) {
        UUID intent=UUID.randomUUID(),hold=UUID.randomUUID(),cause=UUID.randomUUID(),attempt=UUID.randomUUID();var units=quote.units().stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).toList();return new TransactionTemplate(transactionManager).execute(t->{context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class).acquire(f.order().id(),hold,cause,"RETURN",units);context.getBean(com.example.marketplace.claim.api.FinancialIntents.class).registerReturn(f.order().orderNumber(),shipmentAuthority(f),intent,hold,cause,units);var accepted=fees.accept(f.order().orderNumber(),shipmentAuthority(f),quote.id(),quote.fingerprint(),key,intent,quote.legacyConsentRequired());var refunds=context.getBean(com.example.marketplace.payment.api.Refunds.class);var payment=refunds.original(f.order().id());refunds.reserve(new com.example.marketplace.payment.api.Refunds.Reserve(attempt,UUID.randomUUID(),payment.paymentId(),f.order().id(),quote.sellerOrderId(),intent,"ITEM",1,Long.parseLong(accepted.refund()),quote.fingerprint()));fees.bindRefund(f.order().orderNumber(),shipmentAuthority(f),accepted.id(),attempt);return new AcceptedReturnFee(quote,accepted,attempt,hold,cause);});
    }
    protected com.example.marketplace.claim.infrastructure.JdbcReturnFees returnFeesWith(com.example.marketplace.claim.infrastructure.JdbcReturnFees.Checkpoint point) {
        return new com.example.marketplace.claim.infrastructure.JdbcReturnFees(jdbc,context.getBean(com.example.marketplace.order.api.ReturnSources.class),context.getBean(com.example.marketplace.order.api.OrderQueries.class),orders(),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.platform.api.JobLeases.class),context.getBean(com.example.marketplace.claim.infrastructure.CurrentSupport.class),transactionManager,context.getBean(com.example.marketplace.claim.infrastructure.InspectedReturnQuotes.class),point);
    }
    protected Map<String,String> returnFinancialParticipants() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "returnFinancialParticipants")) {
        var result=new java.util.TreeMap<>(cancellationParticipants());for(String table:List.of("claim_fee_quote","claim_fee_bucket","claim_fee_assessment","claim_fee_assessment_bucket","claim_fee_assessment_unit","claim_fee_consent","claim_fee_binding_history","claim_fee_binding","claim_fee_event","claim_fee_application","claim_fee_withdrawal","claim_return_refund_result","claim_return_result_event","claim_fee_support_command","claim_delivery_fee_bucket","claim_basic_fee_bucket","claim_exchange_fee_bucket","claim_fee_quote_physical_unit","claim_exchange_fee_assessment_bucket","claim_exchange_fee_assessment_unit")){result.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));}return result;
    
        }
    }



    protected void recordPolicyOrderWait(boolean saveFirst,UUID buyer,UUID seller) {
        var waiting=jdbc.queryForList("SELECT pid,pg_blocking_pids(pid)::text AS blockers,query FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%identity_user%FOR NO KEY UPDATE%'",Map.of());assertEquals(1,waiting.size());assertFalse(waiting.getFirst().get("blockers").equals("{}"));try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("policy-order-wait-"+saveFirst+".txt"),"buyer="+buyer+";seller="+seller+";distinctActors=true;saveFirst="+saveFirst+";canonicalSharedSellerAccount="+waiting);}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }

    protected record FeeAuthorityCase(PaymentFixture fixture,com.example.marketplace.order.api.OrderQueries.Authority authority,com.example.marketplace.claim.api.ReturnFees fees,com.example.marketplace.claim.api.FinancialIntents intents,com.example.marketplace.order.api.OrderConfirmations.Unit unit,GuestFixture guest,JobClock clock,long expiry) { }
    protected FeeAuthorityCase feeAuthorityCase(String suffix,boolean guest) throws Exception {
        String fixtureKey=(guest?"g-":"m-")+Integer.toUnsignedString(suffix.hashCode(),36);ConfirmationGuest g=guest?cancellationGuest("fee-auth-"+fixtureKey):null;var f=guest?new PaymentFixture(g.order(),authBrowser(),new com.example.marketplace.payment.api.Payments.Authority(g.fixture().browser,null,null,g.token())):paymentFixture("a-c-fee-auth-"+fixtureKey,false);if(!guest){paidShipment(f);}var clock=new JobClock();clock.now=Instant.now();long expiry=clock.millis()+60000;if(!guest){jdbc.update("UPDATE marketplace.spring_session SET expiry_time=:at WHERE session_id=:id",Map.of("at",expiry,"id",f.authority().browser()));}
        var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),guest?g.access():context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class),new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);var authority=guest?new com.example.marketplace.order.api.OrderQueries.Authority("independent-return-owner",null,null,g.token()):shipmentAuthority(f);UUID seller=guest?f.order().sellers().getFirst().id():ownSeller(f);var item=f.order().sellers().stream().filter(s->s.id().equals(seller)).findFirst().orElseThrow().items().getFirst();seedReleasedCancellationBudget(cancellationWithQueries(queries),f.order().orderNumber(),authority,List.of(new com.example.marketplace.order.api.OrderConfirmations.Unit(item.id(),1)));var actor=asSeller(f,seller);var shipment=shipments().seller(seller,shipmentAuthority(actor)).getFirst();completeShipment(actor,shipment,Instant.now());
        var sources=new com.example.marketplace.order.infrastructure.JdbcReturnSources(jdbc,queries,orders(),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class));var intents=new com.example.marketplace.claim.infrastructure.JdbcFinancialIntents(jdbc,context.getBean(com.example.marketplace.order.api.CancellationOrders.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),queries,sources);var fees=feeServiceWithAuthority(queries,sources,intents,context.getBean(com.example.marketplace.claim.infrastructure.CurrentSupport.class));return new FeeAuthorityCase(f,authority,fees,intents,new com.example.marketplace.order.api.OrderConfirmations.Unit(item.id(),0),guest?g.fixture():null,clock,expiry);
    }
    protected com.example.marketplace.claim.infrastructure.JdbcReturnFees feeServiceWithAuthority(com.example.marketplace.order.api.OrderQueries queries,com.example.marketplace.order.api.ReturnSources sources,com.example.marketplace.claim.api.FinancialIntents intents,com.example.marketplace.claim.infrastructure.CurrentSupport support) {
        return new com.example.marketplace.claim.infrastructure.JdbcReturnFees(jdbc,sources,queries,orders(),intents,context.getBean(com.example.marketplace.payment.api.Refunds.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.platform.api.JobLeases.class),support,transactionManager,context.getBean(com.example.marketplace.claim.infrastructure.InspectedReturnQuotes.class));
    }
    protected AcceptedReturnFee bindFeeAuthority(FeeAuthorityCase c,com.example.marketplace.claim.api.ReturnFees.Quote quote,UUID intent,UUID hold,UUID cause,String key) {
        var f=c.fixture();var accepted=c.fees().accept(f.order().orderNumber(),c.authority(),quote.id(),quote.fingerprint(),key,intent,false);UUID attempt=UUID.randomUUID(),payment=jdbc.queryForObject("SELECT id FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),UUID.class);context.getBean(com.example.marketplace.payment.api.Refunds.class).reserve(new com.example.marketplace.payment.api.Refunds.Reserve(attempt,cause,payment,f.order().id(),quote.sellerOrderId(),intent,"ITEM",1,Long.parseLong(accepted.refund()),quote.fingerprint()));c.fees().bindRefund(f.order().orderNumber(),c.authority(),accepted.id(),attempt);return new AcceptedReturnFee(quote,accepted,attempt,hold,cause);
    }






    protected Map<String,String> cancellationEffectParticipants() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "cancellationEffectParticipants")) {
        var result=new java.util.TreeMap<>(cancellationParticipants());result.remove("job");for(String table:List.of("inventory_restitution_unit","inventory_restitution_movement")){result.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));}return result;
    
        }
    }

    protected com.example.marketplace.claim.infrastructure.JdbcClaims cancellationWithCoupons(CouponReservations coupons) {
        return new com.example.marketplace.claim.infrastructure.JdbcClaims(context.getBean(com.example.marketplace.order.api.CancellationOrders.class),context.getBean(com.example.marketplace.order.api.OrderQueries.class),orders(),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),reservations(),context.getBean(com.example.marketplace.inventory.api.Restitutions.class),coupons,context.getBean(com.example.marketplace.order.api.AftercareSettlements.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),jdbc,context.getBean(com.example.marketplace.platform.api.Outbox.class),context.getBean(com.example.marketplace.platform.api.JobLeases.class),transactionManager);
    }
    protected void runCancellationResultWith(UUID attempt,com.example.marketplace.claim.infrastructure.JdbcClaims api) {
        UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='REFUND_RESULT_RECORDED.v1' AND cause_key=:cause AND status='READY' ORDER BY scheduled_at DESC,id LIMIT 1",Map.of("cause",attempt.toString()),UUID.class);var row=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(row.nextRunAt().plusSeconds(1),ZoneOffset.UTC));var handler=new com.example.marketplace.platform.api.JobHandler(){public String type(){return "REFUND_RESULT_RECORDED.v1";}public Result handle(com.example.marketplace.platform.api.Jobs.Job job){return api.result(job);}};try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler))){assertTrue(worker.runOnce());}assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());
    }









    protected com.example.marketplace.claim.infrastructure.JdbcClaims claimsWith(com.example.marketplace.platform.api.JobLeases leases,com.example.marketplace.claim.infrastructure.JdbcClaims.Checkpoint checkpoint) {
        return new com.example.marketplace.claim.infrastructure.JdbcClaims(context.getBean(com.example.marketplace.order.api.CancellationOrders.class),context.getBean(com.example.marketplace.order.api.OrderQueries.class),orders(),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),reservations(),context.getBean(com.example.marketplace.inventory.api.Restitutions.class),couponReservations(),context.getBean(com.example.marketplace.order.api.AftercareSettlements.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),jdbc,context.getBean(com.example.marketplace.platform.api.Outbox.class),leases,transactionManager,checkpoint);
    }
    protected void refundReviewResult(String state) throws Exception {
        var f=paymentFixture("a-c-result-"+state.toLowerCase(java.util.Locale.ROOT),false);var v=paidShipment(f);var u=v.units().getFirst();var receipt=acceptCancellation(f,List.of(Map.of("itemId",u.orderItemId(),"ordinal",u.ordinal())),"review-result");UUID claim=UUID.fromString(receipt.get("claimId").asText()),attempt=UUID.fromString(receipt.get("refundAttemptId").asText());jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM',:state)",Map.of("id",claim,"state",state));runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);assertEquals("REVIEW_REQUIRED",jdbc.queryForObject("SELECT state FROM marketplace.claim WHERE id=:id",Map.of("id",claim),String.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_event WHERE claim_id=:id AND action=:action",Map.of("id",claim,"action","REFUND_"+state),Integer.class),"first actual uncertain/failed result needs durable business history");assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event e JOIN marketplace.job j ON j.id=e.job_id WHERE j.dedupe_key LIKE :prefix",Map.of("prefix","claim-review-result:"+attempt+":%"),Integer.class));var before=jdbc.queryForMap("SELECT * FROM marketplace.claim WHERE id=:id",Map.of("id",claim));
        String payload=jdbc.queryForObject("SELECT payload FROM marketplace.job WHERE type='REFUND_RESULT_RECORDED.v1' AND cause_key=:cause",Map.of("cause",attempt.toString()),String.class);context.getBean(com.example.marketplace.platform.api.Jobs.class).enqueue(new com.example.marketplace.platform.api.Jobs.Command("REFUND_RESULT_RECORDED.v1","result-replay:"+attempt,attempt.toString(),payload,Instant.now()));runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);assertEquals(before,jdbc.queryForMap("SELECT * FROM marketplace.claim WHERE id=:id",Map.of("id",claim)),"same payment result cannot increment claim revision again");assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_event WHERE claim_id=:id AND action=:action",Map.of("id",claim,"action","REFUND_"+state),Integer.class));
    }
    protected Process refundProcess(java.nio.file.Path folder,String phase,Instant now,UUID id,String mode) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("process-start", "refundProcess")) {
        var args=folder.resolve(phase+".args");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.RefundWorkerProbe\n"+now+"\n"+id+"\n\""+folder.resolve(phase+"-effect").toString().replace((char)92,'/')+"\"\n"+mode+"\n");var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile());builder.environment().remove("D2_ADMIN_PASSWORD");var child=builder.start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(child.pid()));return child;
    
        }
    }








    protected long runReturnOutcome(AcceptedReturnFee a) { runCancellationJob("REFUND_SUBMIT.v1",a.attempt());runCancellationJob("REFUND_RESULT_RECORDED.v1",a.attempt());return new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.claim.api.FinancialIntents.class).results(a.accepted().intentId())).getLast().version(); }
    protected record AcceptedReturnFee(com.example.marketplace.claim.api.ReturnFees.Quote quote,com.example.marketplace.claim.api.ReturnFees.Accepted accepted,UUID attempt,UUID hold,UUID cause) { }
    protected AcceptedReturnFee acceptReturnFee(PaymentFixture f,List<com.example.marketplace.claim.api.ReturnFees.Target> targets,String key) {
        var fees=context.getBean(com.example.marketplace.claim.api.ReturnFees.class);var quote=fees.quote(f.order().orderNumber(),shipmentAuthority(f),targets);UUID intent=UUID.randomUUID(),hold=UUID.randomUUID(),cause=UUID.randomUUID(),attempt=UUID.randomUUID();var units=targets.stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).toList();return new TransactionTemplate(transactionManager).execute(t->{context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class).acquire(f.order().id(),hold,cause,"RETURN",units);context.getBean(com.example.marketplace.claim.api.FinancialIntents.class).registerReturn(f.order().orderNumber(),shipmentAuthority(f),intent,hold,cause,units);var accepted=fees.accept(f.order().orderNumber(),shipmentAuthority(f),quote.id(),quote.fingerprint(),key,intent,quote.legacyConsentRequired());var refunds=context.getBean(com.example.marketplace.payment.api.Refunds.class);var payment=refunds.original(f.order().id());refunds.reserve(new com.example.marketplace.payment.api.Refunds.Reserve(attempt,UUID.randomUUID(),payment.paymentId(),f.order().id(),quote.sellerOrderId(),intent,"ITEM",1,Long.parseLong(accepted.refund()),quote.fingerprint()));fees.bindRefund(f.order().orderNumber(),shipmentAuthority(f),accepted.id(),attempt);return new AcceptedReturnFee(quote,accepted,attempt,hold,cause);});
    }
    protected PaymentFixture returnFeeFixture(String suffix,boolean shortfall) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "returnFeeFixture")) {
        var d=orderDraft("c-fee-"+suffix);var original=d.quote().stores().getFirst().lines().getFirst();var products=context.getBean(com.example.marketplace.catalog.api.Products.class);var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);var first=skus.list(d.memberId(),original.productId(),0).items().getFirst();skus.save(d.memberId(),original.productId(),first.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),shortfall?2000L:10000L,true,first.version()));var product=products.own(d.memberId(),original.productId());var second=products.save(d.memberId(),null,new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),"c-fee-second-"+suffix,"다른 회수비 상품","","DRAFT",0L,"5000"));var option=skus.list(d.memberId(),second.id(),0).items().getFirst();skus.save(d.memberId(),second.id(),option.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),shortfall?1000L:10000L,true,option.version()));context.getBean(com.example.marketplace.inventory.api.Inventory.class).adjust(d.memberId(),second.id(),option.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(10L,0L,"회수비 실제 상품 fixture 재고",UUID.randomUUID()));products.save(d.memberId(),second.id(),new com.example.marketplace.catalog.api.Products.Input(second.categoryId(),second.slug(),second.title(),"","ACTIVE",second.version()));var lines=List.of(quoteLine(first.id(),2),quoteLine(option.id(),2));var q=quotes().calculate(d.memberId(),lines,List.of());var saved=creation().create(d.purchaseSessionId().toString(),d.memberId(),null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("fee-"+suffix,lines,List.of(),q.fingerprint(),d.contact(),null,0));
        var browser=authBrowser();String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id",Map.of("id",d.memberId().toString()),String.class);Long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),Long.class);return new PaymentFixture(saved,browser,new com.example.marketplace.payment.api.Payments.Authority(session,d.memberId(),version,null));
    
        }
    }
    protected record ReturnParties(PaymentFixture buyer,PaymentFixture seller,com.example.marketplace.fulfillment.api.Shipments.View shipment) { }
    protected ReturnParties returnParties(String suffix) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "returnParties")) {
        return returnParties(suffix,true);
    
        }
    }
    protected ReturnParties returnParties(String suffix,boolean deliver) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "returnParties")) {
        return returnParties(suffix,deliver,0);
    
        }
    }
    protected ReturnParties returnParties(String suffix,boolean deliver,int quantity) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "returnParties")) {
        return returnParties(suffix,deliver,quantity,null);
    
        }
    }
    protected ReturnParties returnParties(String suffix,boolean deliver,int quantity,String originalPolicyFee) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "returnParties")) {
        return returnParties(suffix,deliver,quantity,originalPolicyFee,0);
    
        }
    }
    protected ReturnParties returnParties(String suffix,boolean deliver,int quantity,String originalPolicyFee,int copiesPerLine) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "returnParties")) {
        return returnParties(suffix,deliver,quantity,originalPolicyFee,copiesPerLine,false);
    
        }
    }
    protected ReturnParties returnParties(String suffix,boolean deliver,int quantity,String originalPolicyFee,int copiesPerLine,boolean shortfall) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "returnParties")) {
        var seller=returnFeeFixture("d-s-"+suffix,shortfall);var draft=orderDraft("d-b-"+suffix);
        var lines=seller.order().quote().stores().getFirst().lines().stream().map(line->quoteLine(line.skuId(),line.quantity())).toList();
        if(quantity>0){var line=seller.order().quote().stores().getFirst().lines().stream().filter(candidate->originalPolicyFee==null||originalPolicyFee.equals(candidate.returnPolicy().amount())).findFirst().orElseThrow();var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);var balance=inventory.list(seller.authority().member(),line.productId(),false,0).items().stream().filter(b->b.id().equals(line.skuId())).findFirst().orElseThrow();inventory.adjust(seller.authority().member(),line.productId(),line.skuId(),false,new com.example.marketplace.inventory.api.Inventory.Change(99L,balance.version(),"실제 bounded 복구 원 주문 재고",UUID.randomUUID()));lines=List.of(quoteLine(line.skuId(),quantity));}
        if(copiesPerLine>0){lines=seller.order().quote().stores().getFirst().lines().stream().map(line->quoteLine(line.skuId(),copiesPerLine)).toList();}
        var quote=quotes().calculate(draft.memberId(),lines,List.of());
        var saved=creation().create(draft.purchaseSessionId().toString(),draft.memberId(),null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("d-parties-"+suffix,lines,List.of(),quote.fingerprint(),draft.contact(),null,0));
        var browser=authBrowser();String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",draft.memberId()),String.class);
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
        String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id",Map.of("id",draft.memberId().toString()),String.class);
        Long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",draft.memberId()),Long.class);
        var buyer=new PaymentFixture(saved,browser,new com.example.marketplace.payment.api.Payments.Authority(session,draft.memberId(),version,null));
        assertFalse(buyer.authority().member().equals(seller.authority().member()),"actual buyer and own-store seller must be different actors");
        payments().approve(saved.orderNumber(),buyer.authority());shipmentJob(settlementJob(buyer),Instant.now());
        var sellerOrder=new PaymentFixture(saved,seller.browser(),seller.authority());
        var shipment=shipments().seller(saved.sellers().getFirst().id(),shipmentAuthority(sellerOrder)).getFirst();if(deliver){completeShipment(sellerOrder,shipment,Instant.now());}
        return new ReturnParties(buyer,sellerOrder,shipment);
    
        }
    }





    protected com.example.marketplace.claim.api.Returns.Progress executeReturnCommand(com.example.marketplace.claim.api.Returns api,ReturnParties f,UUID id,com.example.marketplace.claim.api.Returns.Command command,String action) {
        return action.equals("WITHDRAWN")?api.withdraw(f.buyer().order().orderNumber(),id,shipmentAuthority(f.buyer()),command):action.equals("REJECTED")?api.reject(id,shipmentAuthority(f.seller()),command):api.approve(id,shipmentAuthority(f.seller()),command);
    }












    protected void assertReturnGraphCommitRejected(String label,Map<String,String> before,Runnable attack) throws Exception {
        RuntimeException failure=null;try { new TransactionTemplate(transactionManager).executeWithoutResult(t->attack.run()); }catch(RuntimeException rejected) { failure=rejected; }
        var after=returnApplicationParticipants();var changed=after.entrySet().stream().filter(e->!e.getValue().equals(before.get(e.getKey()))).map(Map.Entry::getKey).toList();Throwable root=failure;if(root!=null) { while(root.getCause()!=null) { root=root.getCause(); } }
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-graph-"+label+".json"),authMapper.writeValueAsString(Map.of("case",label,"commitRejected",failure!=null,"sqlState",root instanceof java.sql.SQLException sql?sql.getSQLState():"NONE","constraintMessage",root==null?"NONE":root.getMessage(),"changedParticipants",changed)));
        assertTrue(failure!=null,"actual invalid attribution commit was accepted: "+label+" changed="+changed);assertTrue(root instanceof java.sql.SQLException,root.toString());assertEquals("23514",((java.sql.SQLException)root).getSQLState());assertEquals(before,after,"every physical/inventory/original reservation/history/outbox participant must roll back");
        String expected=switch(label) { case "fictional-inspection"->"ACTUAL_RETURN_INSPECTION_REQUIRED";case "inspection-without-inbound"->"ACTUAL_SALEABLE_INBOUND_RECEIPT_REQUIRED";case "defective-inbound"->"DEFECTIVE_INSPECTION_CANNOT_RESTOCK";case "inbound-without-balance"->"RETURN_STOCK_EFFECT_NOT_RECORDED";default->throw new IllegalArgumentException(label); };assertTrue(root.getMessage().contains(expected),"actual intended graph checkpoint must reject: "+root);
    }
    protected record PhysicalReturn(ReturnParties parties,com.example.marketplace.claim.api.Returns.Receipt accepted,com.example.marketplace.claim.api.Returns.Progress collected) { }
    protected PhysicalReturn physicalReturn(String suffix,int quantity,boolean collect) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "physicalReturn")) {
        return physicalReturn(returnParties(suffix),quantity,collect);
    
        }
    }
    protected PhysicalReturn physicalReturn(ReturnParties f,int quantity,boolean collect) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "physicalReturn")) {
        var api=context.getBean(com.example.marketplace.claim.api.Returns.class);var units=f.shipment().units().stream().limit(quantity).map(u->new com.example.marketplace.claim.api.Returns.Unit(u.orderItemId(),u.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)).toList();assertEquals(quantity,units.size());
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),new com.example.marketplace.claim.api.Returns.Request("physical-request",confirmations().get(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer())).revision(),units,"실제 물리 경계 준비"));api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("physical-approve",0L,"현재 판매자 실제 승인"));
        var received=collect?context.getBean(com.example.marketplace.claim.api.ReturnCollections.class).receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("physical-receive",1L,units.stream().map(u->new com.example.marketplace.claim.api.ReturnCollections.Unit(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)).toList(),"실제 신청 원단위 전량 수령")):null;
        return new PhysicalReturn(f,accepted,received);
    
        }
    }





    protected PhysicalReturn finalQuoteReturn(String suffix,boolean buyerResponsible) throws Exception {
        var f=physicalReturn(suffix,1,true);var unit=f.accepted().units().getFirst();var inspected=context.getBean(com.example.marketplace.claim.api.ReturnInspections.class).inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),new com.example.marketplace.claim.api.ReturnInspections.Command("final-quote-inspect",2L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,buyerResponsible?com.example.marketplace.claim.api.Returns.Responsibility.BUYER:com.example.marketplace.claim.api.Returns.Responsibility.SELLER)),"최종 견적 전 실제 검수/책임 제안"));assertEquals("AWAITING_CONSENT",inspected.state());assertEquals(3,inspected.version());return f;
    }








    protected record FinancialReturn(PhysicalReturn physical,com.example.marketplace.claim.api.ReturnFinances.Accepted consent,com.example.marketplace.payment.api.Refunds.Attempt outcome,com.example.marketplace.platform.api.Jobs.Job job,com.example.marketplace.platform.infrastructure.JdbcJobs jobs,JobClock clock) { }
    protected FinancialReturn financialReturn(String suffix,String outcome) throws Exception {
        var f=finalQuoteReturn(suffix,true);var api=context.getBean(com.example.marketplace.claim.api.ReturnFinances.class);String number=f.parties().buyer().order().orderNumber();var owner=shipmentAuthority(f.parties().buyer());var quote=api.quote(number,f.accepted().returnId(),owner,3).feeQuote();var consent=api.consent(number,f.accepted().returnId(),owner,new com.example.marketplace.claim.api.ReturnFinances.Consent("financial-boundary",3L,quote.id(),quote.fingerprint(),true,false));
        if(!outcome.equals("SUCCEEDED")) { jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM',:behavior)",Map.of("id",f.accepted().returnId(),"behavior",outcome)); }
        runCancellationJob("REFUND_SUBMIT.v1",consent.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",consent.refundAttemptId());var actual=new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.payment.api.Refunds.class).get(consent.refundAttemptId()));assertEquals(outcome.equals("LOST_RESPONSE")?"UNKNOWN":outcome,actual.state());UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='RETURN_FINANCE_RESUME.v1' AND cause_key=:id AND payload=:payload",Map.of("id",f.accepted().returnId().toString(),"payload",f.accepted().returnId()+"|"+actual.id()+"|"+actual.version()),UUID.class);var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();var clock=new JobClock();clock.now=saved.nextRunAt().plusSeconds(1);var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,clock);var job=jobs.claim(java.util.Set.of("RETURN_FINANCE_RESUME.v1")).orElseThrow();return new FinancialReturn(f,consent,actual,job,jobs,clock);
    }
    protected com.example.marketplace.claim.infrastructure.ReturnFinancialWorker financialWorker(FinancialReturn f) {
        return new com.example.marketplace.claim.infrastructure.ReturnFinancialWorker(jdbc,context.getBean(com.example.marketplace.order.api.ReturnApplications.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),context.getBean(com.example.marketplace.claim.api.ReturnFees.class),context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class),f.jobs(),f.jobs(),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager);
    }









    protected void assertReturnSqlStageRollback(String table,String operation,String label,String condition,Map<String,String> before,Runnable invoke) {
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.d_business_sql_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "+condition+"RAISE EXCEPTION 'ACTUAL_D_SQL_"+label+"' USING ERRCODE='23514';END $$");jdbc.getJdbcTemplate().execute("CREATE TRIGGER d_business_sql_reject BEFORE "+operation+" ON marketplace."+table+" FOR EACH ROW EXECUTE FUNCTION marketplace.d_business_sql_failure()");
        try {var error=assertThrows(RuntimeException.class,invoke::run);Throwable root=error;while(root.getCause()!=null){root=root.getCause();}assertTrue(root instanceof java.sql.SQLException);assertEquals("23514",((java.sql.SQLException)root).getSQLState());assertTrue(root.getMessage().contains("ACTUAL_D_SQL_"+label),root.toString());assertEquals(before,returnApplicationParticipants(),"every participant rolls back at actual "+label);}
        finally {jdbc.getJdbcTemplate().execute("DROP TRIGGER d_business_sql_reject ON marketplace."+table);jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.d_business_sql_failure()");}
    }





    protected com.example.marketplace.claim.api.ReturnFinances.Accepted consentSellerReturnItem(PhysicalReturn f) throws Exception {
        UUID id=f.accepted().returnId();var inspected=context.getBean(com.example.marketplace.claim.api.ReturnInspections.class).inspect(id,shipmentAuthority(f.parties().seller()),new com.example.marketplace.claim.api.ReturnInspections.Command("last-inspect",2L,f.accepted().units().stream().map(u->new com.example.marketplace.claim.api.ReturnInspections.Unit(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER)).toList(),"실제 판매자 책임 검수"));assertEquals("AWAITING_CONSENT",inspected.state());var api=context.getBean(com.example.marketplace.claim.api.ReturnFinances.class);String number=f.parties().buyer().order().orderNumber();var q=api.quote(number,id,shipmentAuthority(f.parties().buyer()),3).feeQuote();return api.consent(number,id,shipmentAuthority(f.parties().buyer()),new com.example.marketplace.claim.api.ReturnFinances.Consent("last-consent",3L,q.id(),q.fingerprint(),true,false));
    }
    protected com.example.marketplace.claim.api.ReturnFinances.Accepted settleSellerReturnItem(PhysicalReturn f) throws Exception {
        var accepted=consentSellerReturnItem(f);runCancellationJob("REFUND_SUBMIT.v1",accepted.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",accepted.refundAttemptId());runCancellationJob("RETURN_FINANCE_RESUME.v1",f.accepted().returnId());return accepted;
    }
    protected UUID currentRecoveryRun;
    protected void publishRecoveryStartup() {
        var before=new java.util.HashSet<>(jdbc.queryForList("SELECT id FROM marketplace.claim_return_recovery_run",Map.of(),UUID.class));
        var configured=(org.springframework.context.ConfigurableApplicationContext)context;var environment=configured.getEnvironment();String key="d-recovery-test-startup";environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(key,Map.<String,Object>of("marketplace.worker.enabled","true")));try{configured.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(new org.springframework.boot.SpringApplication(MarketplaceApplication.class),new String[0],configured,java.time.Duration.ZERO));}finally{environment.getPropertySources().remove(key);}
        var added=jdbc.queryForList("SELECT id FROM marketplace.claim_return_recovery_run",Map.of(),UUID.class).stream().filter(id->!before.contains(id)).toList();assertEquals(1,added.size(),"trusted bootstrap must create exactly one actual run");currentRecoveryRun=added.getFirst();
    }
    protected void drainReturnRecoveryWork() { drainReturnRecoveryWork(true); }
    protected void drainReturnRecoveryWork(boolean effects) {
        var types=effects?java.util.Set.of("RETURN_RECOVERY_SCAN.v1","RETURN_FINANCE_RESUME.v1","REFUND_RESULT_RECORDED.v1","SELLER_AFTERCARE_REEVALUATE.v1"):java.util.Set.of("RETURN_RECOVERY_SCAN.v1");var handlers=context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values().stream().filter(h->types.contains(h.type())).toList();
        String owned="(j.type='RETURN_RECOVERY_SCAN.v1' AND j.cause_key=:runText OR EXISTS(SELECT 1 FROM marketplace.claim_return_recovery_delivery d WHERE d.run_id=:run AND d.job_id=j.id) OR j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND EXISTS(SELECT 1 FROM marketplace.claim_return_recovery_candidate c JOIN marketplace.claim_return_request r ON r.id=c.return_id WHERE c.run_id=:run AND j.cause_key=r.seller_order_id::text))";
        var parameters=Map.of("types",types,"run",currentRecoveryRun,"runText",currentRecoveryRun.toString());int count=0;
        while(true){
            var pending=jdbc.queryForList("SELECT j.id,j.next_run_at FROM marketplace.job j WHERE j.type IN(:types) AND j.status='READY' AND j.attempts=0 AND "+owned+" ORDER BY j.next_run_at,j.id LIMIT 1",parameters);if(pending.isEmpty()){break;}
            var row=pending.getFirst();UUID id=(UUID)row.get("id");Instant stored=((java.sql.Timestamp)row.get("next_run_at")).toInstant();Instant observed=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant();Instant due=java.util.stream.Stream.of(stored.plusNanos(1000),observed,Instant.now()).max(Instant::compareTo).orElseThrow();assertTrue(due.isAfter(stored));
            var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(due,ZoneOffset.UTC));
            try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,handlers)){assertTrue(worker.runOnce(),"fresh real job must be claimed at its stored nextRunAt");}
            var actual=jobs.find(id).orElseThrow();assertEquals("SUCCEEDED",actual.status(),"actual recovery handler failed: "+actual);assertNull(actual.lastError());assertTrue(++count<500,"bounded recovery work must finish");
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_recovery_stage WHERE run_id=:run AND NOT completed",parameters,Integer.class),"every stage must consume its frozen source candidates");
        if(effects){assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_recovery_delivery d JOIN marketplace.job j ON j.id=d.job_id WHERE d.run_id=:run AND j.status<>'SUCCEEDED'",parameters,Integer.class),"all manifest deliveries must actually complete without silently leaving a failed retry");}
    }
    protected Map<String,String> recoveryBusinessState() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "recoveryBusinessState")) {
        var result=new java.util.LinkedHashMap<>(returnApplicationParticipants());result.keySet().removeIf(k->k.equals("job")||k.equals("outbox_event")||k.startsWith("claim_return_recovery_"));return result;
    
        }
    }


    protected Map<UUID,String> ackRecoveryDeliveries(UUID run) {
        var result=new java.util.LinkedHashMap<UUID,String>();
        for(UUID id:jdbc.queryForList("SELECT job_id FROM marketplace.claim_return_recovery_delivery WHERE run_id=:run ORDER BY id",Map.of("run",run),UUID.class)){
            var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(Instant.now().isAfter(saved.nextRunAt())?Instant.now():saved.nextRunAt().plusNanos(1000),ZoneOffset.UTC));var claimed=jobs.claim(java.util.Set.of(saved.command().type())).orElseThrow();assertTrue(jobs.complete(id,claimed.ownerToken()));result.put(id,jdbc.queryForObject("SELECT row_to_json(j)::text FROM marketplace.job j WHERE id=:id",Map.of("id",id),String.class));
        }
        return result;
    }
    protected void assertRecoveryNextPageRollsBackAfterDurableCandidates(UUID run) throws Exception {
        UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='RETURN_RECOVERY_SCAN.v1' AND payload=:payload",Map.of("payload",run+"|ITEM_EFFECT|START"),UUID.class);var owned=claimActualReturnJob(id);var scanner=context.getBean(com.example.marketplace.claim.infrastructure.ReturnRecoveryScanner.class);Map<String,String> durable=null;var business=recoveryBusinessState();var outcomes=new java.util.ArrayList<Map<String,String>>();String route="j.type='RETURN_RECOVERY_SCAN.v1' AND j.cause_key='"+run+"' AND j.payload LIKE '"+run+"|ITEM_EFFECT|%' AND j.payload<>'"+run+"|ITEM_EFFECT|START'";
        for(String table:List.of("job","outbox_event","claim_return_recovery_page_complete","claim_return_recovery_stage")){
            assertTrue(owned.jobs().heartbeat(id,owned.job().ownerToken()));String condition=table.equals("job")?route.replace("j.","NEW."):table.equals("outbox_event")?"EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=NEW.job_id AND "+route+")":table.equals("claim_return_recovery_page_complete")?"NEW.scan_job_id='"+id+"'::uuid":"NEW.run_id='"+run+"'::uuid AND NEW.stage='ITEM_EFFECT'";recoveryInjection(table,table.equals("claim_return_recovery_stage")?"UPDATE":"INSERT","IF "+condition+" THEN RAISE EXCEPTION 'ACTUAL_RECOVERY_NEXT_"+table+"' USING ERRCODE='23514';END IF;");
            try{var failure=recoverySqlFailure(scanner,owned);assertEquals("23514",failure.getSQLState());assertTrue(failure.getMessage().contains("ACTUAL_RECOVERY_NEXT_"+table));assertEquals(50,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_recovery_candidate WHERE run_id=:run AND stage='ITEM_EFFECT'",Map.of("run",run),Integer.class));assertEquals(50,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_recovery_delivery WHERE run_id=:run AND stage='ITEM_EFFECT'",Map.of("run",run),Integer.class));var actual=immutableRecoveryEvidence(run);if(durable==null){durable=actual;}else{assertEquals(durable,actual,"previously committed page candidates and actual delivery evidence must survive every finish failure unchanged");}assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_recovery_page_complete WHERE scan_job_id=:id",Map.of("id",id),Integer.class));assertNull(jdbc.queryForObject("SELECT cursor_id FROM marketplace.claim_return_recovery_stage WHERE run_id=:run AND stage='ITEM_EFFECT'",Map.of("run",run),UUID.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.job j WHERE "+route,Map.of(),Integer.class));assertEquals(business,recoveryBusinessState(),"scan and failed next-page publication never apply any money/stock/coupon/closure");outcomes.add(Map.of("table",table,"sqlState",failure.getSQLState(),"guard",failure.getMessage()));}finally{removeRecoveryInjection(table);}
        }
        assertTrue(owned.jobs().heartbeat(id,owned.job().ownerToken()));assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,scanner.scan(owned.job()));assertEquals(durable,immutableRecoveryEvidence(run));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_recovery_page_complete WHERE scan_job_id=:id",Map.of("id",id),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job j WHERE "+route,Map.of(),Integer.class));var stable=recoveryCandidateParticipants();assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,scanner.scan(owned.job()));assertEquals(stable,recoveryCandidateParticipants());assertTrue(owned.jobs().complete(id,owned.job().ownerToken()));java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"recovery-next-page-rollback-actual.json"),authMapper.writeValueAsString(Map.of("run",run,"actualFailures",outcomes,"durableCandidates",50,"nextPageAfterResume",1,"cursorReplayAdditionalEffects",0)));
    }

    protected PhysicalReturn recoveryPhysicalUnit(ReturnParties parties,com.example.marketplace.fulfillment.api.Shipments.Unit unit,String key) {
        try (var contractTimingScope = ContractTiming.measure("preparation", "recoveryPhysicalUnit")) {
        var api=context.getBean(com.example.marketplace.claim.api.Returns.class);var accepted=api.request(parties.buyer().order().orderNumber(),shipmentAuthority(parties.buyer()),new com.example.marketplace.claim.api.Returns.Request(key,confirmations().get(parties.buyer().order().orderNumber(),shipmentAuthority(parties.buyer())).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(unit.orderItemId(),unit.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"실제 늦은 금융 사실 경계"));api.approve(accepted.returnId(),shipmentAuthority(parties.seller()),new com.example.marketplace.claim.api.Returns.Command("recovery-approve",0L,"실제 승인"));var received=context.getBean(com.example.marketplace.claim.api.ReturnCollections.class).receive(accepted.returnId(),shipmentAuthority(parties.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("recovery-receive",1L,List.of(new com.example.marketplace.claim.api.ReturnCollections.Unit(unit.orderItemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)),"원단위 실제 회수"));return new PhysicalReturn(parties,accepted,received);
    
        }
    }
    protected record RecoverySeed(PhysicalReturn physical,com.example.marketplace.claim.api.ReturnFinances.Accepted consent,UUID primary,String primaryJson) { }
    protected RecoverySeed retainedRecoveryItem(PhysicalReturn physical) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "retainedRecoveryItem")) { return retainedRecoveryItem(physical,true); 
        }
    }
    protected RecoverySeed retainedRecoveryItem(PhysicalReturn physical,boolean acknowledge) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "retainedRecoveryItem")) {
        var consent=consentSellerReturnItem(physical);runCancellationJob("REFUND_SUBMIT.v1",consent.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",consent.refundAttemptId());var primary=ownReturnJob("RETURN_FINANCE_RESUME.v1",physical.accepted().returnId());if(acknowledge){assertTrue(primary.jobs().complete(primary.job().id(),primary.job().ownerToken()));}return new RecoverySeed(physical,consent,primary.job().id(),jdbc.queryForObject("SELECT row_to_json(j)::text FROM marketplace.job j WHERE id=:id",Map.of("id",primary.job().id()),String.class));
    
        }
    }

    protected OwnedReturnJob claimActualReturnJob(UUID id) {
        var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();Instant due=java.util.stream.Stream.of(saved.nextRunAt().plusNanos(1000),Instant.now(),jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant()).max(Instant::compareTo).orElseThrow();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.offset(Clock.systemUTC(),Duration.between(Instant.now(),due)));return new OwnedReturnJob(jobs,jobs.claim(java.util.Set.of(saved.command().type())).orElseThrow());
    }

    /** Only newly added F fixtures use the existing stored-due driver; authority/business clocks stay native. */
    protected OwnedReturnJob claimFRefundJob(UUID id) {
        var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();assertEquals("READY",saved.status());assertEquals("REFUND_SUBMIT.v1",saved.command().type());Instant host=Instant.now();var raw=jdbc.queryForMap("WITH sample AS(SELECT clock_timestamp() AS at) SELECT at,at::text AS raw,extract(epoch FROM at)::text AS epoch,pg_backend_pid() AS backend,current_setting('marketplace.verification_instance') AS instance FROM sample",Map.of());
        var owned=claimActualReturnJob(id);var proof=new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.platform.api.JobLeases.class).requireDueClaim(id,owned.job().ownerToken()));assertEquals(id,proof.jobId());assertEquals(owned.job().ownerToken(),proof.ownerToken());assertEquals(owned.job().attempts(),proof.attempt());assertEquals(saved.command().nextRunAt(),proof.scheduledAt());assertFalse(proof.claimedAt().isBefore(proof.scheduledAt()));assertEquals(proof.claimedAt().plusSeconds(30),owned.job().leaseUntil());
        try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"f-stored-due-claim-"+id+"-"+proof.attempt()+".json"),authMapper.writeValueAsString(Map.of("stored",saved,"nativeObservationBefore",raw,"hostBefore",host,"hostAfter",Instant.now(),"actualJob",owned.job(),"actualDueFact",proof,"testOnlyStoredDueDriver",true,"businessAndAuthorityClockChanged",false)),java.nio.file.StandardOpenOption.CREATE_NEW);}catch(java.io.IOException error){throw new IllegalStateException(error);}return owned;
    }
    protected Map<String,String> recoveryCandidateParticipants() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "recoveryCandidateParticipants")) {
        var result=new java.util.LinkedHashMap<>(returnApplicationParticipants());for(String table:List.of("claim_return_recovery_run","claim_return_recovery_stage","claim_return_recovery_delivery","claim_return_recovery_candidate","claim_return_recovery_page_complete")){result.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));}return result;
    
        }
    }

    protected void recoveryInjection(String table,String operation,String body) {
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.d_recovery_injection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "+body+" RETURN NEW;END $$");jdbc.getJdbcTemplate().execute("CREATE TRIGGER d_recovery_injection BEFORE "+operation+" ON marketplace."+table+" FOR EACH ROW EXECUTE FUNCTION marketplace.d_recovery_injection()");
    }
    protected void removeRecoveryInjection(String table) {
        jdbc.getJdbcTemplate().execute("DROP TRIGGER d_recovery_injection ON marketplace."+table);jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.d_recovery_injection()");
    }
    protected java.sql.SQLException recoverySqlFailure(com.example.marketplace.claim.infrastructure.ReturnRecoveryScanner scanner,OwnedReturnJob owned) {
        var failure=assertThrows(RuntimeException.class,()->scanner.scan(owned.job()));Throwable root=failure;while(root.getCause()!=null){root=root.getCause();}assertTrue(root instanceof java.sql.SQLException,root.toString());return (java.sql.SQLException)root;
    }

    protected Map<String,String> immutableRecoveryEvidence(UUID run) {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "immutableRecoveryEvidence")) {
        var result=new java.util.LinkedHashMap<String,String>();for(String table:List.of("claim_return_recovery_run","claim_return_recovery_page","claim_return_recovery_delivery","claim_return_recovery_candidate")){String where=table.equals("claim_return_recovery_run")?"id=:run":"run_id=:run";result.put(table,String.join("\n",jdbc.queryForList("SELECT row_to_json(t)::text FROM marketplace."+table+" t WHERE "+where+" ORDER BY row_to_json(t)::text",Map.of("run",run),String.class)));}for(String table:List.of("job","outbox_event")){String where=table.equals("job")?"id IN(SELECT job_id FROM marketplace.claim_return_recovery_delivery WHERE run_id=:run)":"job_id IN(SELECT job_id FROM marketplace.claim_return_recovery_delivery WHERE run_id=:run)";result.put(table,String.join("\n",jdbc.queryForList("SELECT row_to_json(t)::text FROM marketplace."+table+" t WHERE "+where+" ORDER BY row_to_json(t)::text",Map.of("run",run),String.class)));}return result;
    
        }
    }
    protected void runExactActualJob(UUID id) {
        var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();Instant now=java.util.stream.Stream.of(Instant.now(),saved.nextRunAt().plusNanos(1000),jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant()).max(Instant::compareTo).orElseThrow();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(now,ZoneOffset.UTC));try(var engine=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.copyOf(context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values()))){assertTrue(engine.runOnce());}assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status(),"actual exact job handler must finish");
    }

    protected com.example.marketplace.claim.infrastructure.ReturnRecoveryScanner recoveryScannerWith(com.example.marketplace.platform.infrastructure.JdbcJobs jobs) {
        return new com.example.marketplace.claim.infrastructure.ReturnRecoveryScanner(jdbc,context.getBean(com.example.marketplace.order.api.AftercareSettlements.class),context.getBean(com.example.marketplace.order.api.ReturnApplications.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),jobs,jobs,context.getBean(com.example.marketplace.platform.api.Outbox.class),context.getEnvironment(),transactionManager);
    }
    protected com.example.marketplace.claim.infrastructure.ReturnFinancialWorker recoveryFinancialWith(com.example.marketplace.platform.infrastructure.JdbcJobs jobs) {
        return new com.example.marketplace.claim.infrastructure.ReturnFinancialWorker(jdbc,context.getBean(com.example.marketplace.order.api.ReturnApplications.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),context.getBean(com.example.marketplace.claim.api.ReturnFees.class),context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class),jobs,jobs,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager);
    }

    protected String recoveryFutureState(java.util.concurrent.Future<?> future) {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "recoveryFutureState")) {
        if(!future.isDone()){return "PENDING";}try{return "RESULT:"+future.get();}catch(Exception failure){Throwable root=failure;while(root.getCause()!=null){root=root.getCause();}return "ERROR:"+root.getClass().getName()+":"+root.getMessage();}
    
        }
    }

    protected com.example.marketplace.platform.api.JobHandler.Result invokeReturnHandler(com.example.marketplace.platform.api.JobHandler handler,com.example.marketplace.platform.api.Jobs.Job job) {
        try{return handler.handle(job);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException(failure);}
    }
    protected void returnTerminalSqlFaults(String stage,List<String> tables,java.util.Set<String> updates,String jobType,OwnedReturnJob owned,java.util.List<String> reached) {
        var handlers=context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values().stream().filter(h->h.type().equals(owned.job().command().type())).toList();assertEquals(1,handlers.size());
        for(String table:tables){assertTrue(owned.jobs().heartbeat(owned.job().id(),owned.job().ownerToken()));var before=returnApplicationParticipants();String condition=table.equals("job")?"IF NEW.type<>'"+jobType+"' THEN RETURN NEW;END IF;":table.equals("outbox_event")?"IF NOT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=NEW.job_id AND j.type='"+jobType+"') THEN RETURN NEW;END IF;":"";String label=stage+"_"+table;assertReturnSqlStageRollback(table,updates.contains(table)?"UPDATE":"INSERT",label,condition,before,()->assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,invokeReturnHandler(handlers.getFirst(),owned.job())));reached.add(label);}
        assertTrue(owned.jobs().heartbeat(owned.job().id(),owned.job().ownerToken()));assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,invokeReturnHandler(handlers.getFirst(),owned.job()));var stable=returnApplicationParticipants();assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,invokeReturnHandler(handlers.getFirst(),owned.job()));assertEquals(stable,returnApplicationParticipants());assertTrue(owned.jobs().complete(owned.job().id(),owned.job().ownerToken()));
    }


    protected Map<String,String> completedReturnOwnEffects(UUID id) {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "completedReturnOwnEffects")) {
        var filters=new java.util.LinkedHashMap<String,String>();for(String table:List.of("claim_return_history","claim_return_command","claim_return_financial_consent","claim_return_financial_result","claim_return_item_ready","claim_return_ready_unit","claim_return_completion","claim_return_unit","claim_return_inspection","claim_return_inspection_unit","claim_return_collection","order_return_completion","order_return_completion_unit","inventory_return_restitution")){filters.put(table,"return_id=:id");}for(String table:List.of("claim_return_request","order_return_application","claim_financial_intent")){filters.put(table,"id=:id");}filters.put("order_unit_hold","id=(SELECT hold_id FROM marketplace.claim_return_request WHERE id=:id)");filters.put("claim_financial_intent_unit","intent_id=:id");filters.put("claim_fee_assessment","intent_id=:id");filters.put("claim_fee_application","assessment_id=(SELECT assessment_id FROM marketplace.claim_return_financial_consent WHERE return_id=:id)");filters.put("payment_refund_attempt","claim_id=:id AND purpose='ITEM'");filters.put("claim_financial_refund_binding","intent_id=:id AND purpose='ITEM'");for(String table:List.of("inventory_restitution","inventory_restitution_unit","inventory_restitution_movement")){filters.put(table,"cause_id IN(SELECT inspection_id FROM marketplace.claim_return_inspection_unit WHERE return_id=:id)");}var result=new java.util.LinkedHashMap<String,String>();filters.forEach((table,where)->result.put(table,String.join("\n",jdbc.queryForList("SELECT row_to_json(t)::text FROM marketplace."+table+" t WHERE "+where+" ORDER BY row_to_json(t)::text",Map.of("id",id),String.class))));return result;
    
        }
    }



    protected record ShippingFixture(PhysicalReturn physical,UUID attempt) { }
    protected ShippingFixture prepareActualShipping(PhysicalReturn physical,String behavior) throws Exception {
        settleSellerReturnItem(physical);UUID id=physical.accepted().returnId(),seller=physical.parties().shipment().sellerOrderId();runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",seller);UUID attempt=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",Map.of("id",seller),UUID.class);if(behavior!=null){jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'SHIPPING',:behavior)",Map.of("id",id,"behavior",behavior));}runCancellationJob("REFUND_SUBMIT.v1",attempt);return new ShippingFixture(physical,attempt);
    }

    protected HttpResponse<String> concurrentTypedShippingSupport(HttpClient browser,String path,Map<String,?> command,UUID order,String scenario) throws Exception {
        String csrf=csrfToken(browser);try(var pool=Executors.newFixedThreadPool(2);var connection=context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);int blocker;try(var query=connection.createStatement();var row=query.executeQuery("SELECT pg_backend_pid()")){assertTrue(row.next());blocker=row.getInt(1);}try(var lock=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")){lock.setObject(1,order);lock.executeQuery().close();}var go=new CountDownLatch(1);var left=pool.submit(()->{go.await();return accountRequest(browser,"POST",path,command,csrf);});var right=pool.submit(()->{go.await();return accountRequest(browser,"POST",path,command,csrf);});go.countDown();var observed=new java.util.concurrent.atomic.AtomicReference<List<Map<String,Object>>>();awaitJobCondition(()->{jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");var rows=jdbc.queryForList("WITH RECURSIVE blocked(pid) AS(SELECT CAST(:pid AS integer) UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid=ANY(pg_blocking_pids(a.pid)) WHERE a.datname=current_database()) SELECT DISTINCT a.pid,a.query,a.wait_event_type,pg_blocking_pids(a.pid)::text AS blockers FROM pg_stat_activity a JOIN blocked b ON b.pid=a.pid WHERE a.datname=current_database() AND a.pid<>:pid",Map.of("pid",blocker));observed.set(rows);try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"typed-origin-two-view-wait-"+scenario+".ndjson"),authMapper.writeValueAsString(Map.of("blocker",blocker,"actualBlockingChain",rows,"left",recoveryFutureState(left),"right",recoveryFutureState(right)))+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}return rows.size()>=2||left.isDone()||right.isDone();},"both actual SUPPORT HTTP requests must wait through the canonical parent chain");assertFalse(left.isDone(),recoveryFutureState(left));assertFalse(right.isDone(),recoveryFutureState(right));assertTrue(observed.get().size()>=2);connection.commit();var first=left.get(10,TimeUnit.SECONDS);var second=right.get(10,TimeUnit.SECONDS);assertEquals(202,first.statusCode());assertEquals(202,second.statusCode());assertEquals(first.body(),second.body());return first;
        }
    }
    protected com.example.marketplace.claim.api.ReturnSupport shippingSupportWith(com.example.marketplace.claim.infrastructure.CurrentSupport current) {
        var applications=context.getBean(com.example.marketplace.order.api.ReturnApplications.class);var intents=context.getBean(com.example.marketplace.claim.api.FinancialIntents.class);var refunds=context.getBean(com.example.marketplace.payment.api.Refunds.class);var commands=context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class);var outbox=context.getBean(com.example.marketplace.platform.api.Outbox.class);var shipping=new com.example.marketplace.claim.infrastructure.ReturnShippingSupport(jdbc,current,orders(),context.getBean(com.example.marketplace.order.api.AftercareSettlements.class),applications,intents,refunds,commands,outbox,transactionManager);return new com.example.marketplace.claim.infrastructure.JdbcReturnSupport(jdbc,current,applications,intents,orders(),refunds,context.getBean(com.example.marketplace.claim.api.ReturnFees.class),commands,shipping,outbox,transactionManager);
    }



    protected NamedParameterJdbcTemplate claimStorageRecorder(List<String> statements) {
        return claimStorageRecorder(statements,sql->{ });
    }
    protected NamedParameterJdbcTemplate claimStorageRecorder(List<String> statements,java.util.function.Consumer<String> observer) {
        var transactional=new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(context.getBean(DataSource.class));return new NamedParameterJdbcTemplate(new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public java.sql.Connection getConnection(String username,String password) throws java.sql.SQLException{throw new java.sql.SQLFeatureNotSupportedException();}
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException{var actual=transactional.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,m,args)->{if(m.getName().equals("prepareStatement")&&args!=null&&args.length>0&&args[0] instanceof String sql){statements.add(sql);observer.accept(sql);}try{return m.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}});}
        });
    }
    protected record OwnedReturnJob(com.example.marketplace.platform.infrastructure.JdbcJobs jobs,com.example.marketplace.platform.api.Jobs.Job job) { }
    protected OwnedReturnJob ownReturnJob(String type,UUID cause) {
        UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status='READY' ORDER BY scheduled_at DESC,id LIMIT 1",Map.of("type",type,"cause",cause.toString()),UUID.class);var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(saved.nextRunAt().plusSeconds(1),ZoneOffset.UTC));return new OwnedReturnJob(jobs,jobs.claim(java.util.Set.of(type)).orElseThrow());
    }







    protected record BuyerReturnMoney(com.example.marketplace.claim.api.ReturnFees.Quote quote,com.example.marketplace.claim.api.ReturnFinances.Accepted accepted) { }
    protected BuyerReturnMoney inspectAndConsentBuyerReturn(PhysicalReturn f,String key) {
        var units=f.accepted().units().stream().map(u->new com.example.marketplace.claim.api.ReturnInspections.Unit(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.BUYER)).toList();assertEquals("AWAITING_CONSENT",context.getBean(com.example.marketplace.claim.api.ReturnInspections.class).inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),new com.example.marketplace.claim.api.ReturnInspections.Command(key+"-inspect",2L,units,"실제 구매자 책임 검수")).state());var api=context.getBean(com.example.marketplace.claim.api.ReturnFinances.class);String number=f.parties().buyer().order().orderNumber();var quote=api.quote(number,f.accepted().returnId(),shipmentAuthority(f.parties().buyer()),3).feeQuote();return new BuyerReturnMoney(quote,api.consent(number,f.accepted().returnId(),shipmentAuthority(f.parties().buyer()),new com.example.marketplace.claim.api.ReturnFinances.Consent(key+"-consent",3L,quote.id(),quote.fingerprint(),true,false)));
    }
    protected void completeActualReturnMoney(PhysicalReturn f,com.example.marketplace.claim.api.ReturnFinances.Accepted accepted) {
        if(!accepted.progress().state().equals("REFUND_PENDING")){throw new IllegalStateException("actual return consent state");}runCancellationJob("REFUND_SUBMIT.v1",accepted.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",accepted.refundAttemptId());runCancellationJob("RETURN_FINANCE_RESUME.v1",f.accepted().returnId());
    }


    protected com.example.marketplace.claim.infrastructure.SellerAftercareWorker sellerAftercareWithCoupons(OwnedReturnJob owned,CouponReservations coupons) {
        return new com.example.marketplace.claim.infrastructure.SellerAftercareWorker(jdbc,context.getBean(com.example.marketplace.order.api.AftercareSettlements.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),owned.jobs(),owned.jobs(),context.getBean(com.example.marketplace.order.api.ReturnApplications.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class),orders(),coupons,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,context.getBean(com.example.marketplace.claim.infrastructure.ExchangeRefundCompleter.class));
    }



    static void prepareIsolatedCFinancialGraph(org.springframework.context.ConfigurableApplicationContext configured,java.nio.file.Path folder,String targetDatabase) throws Exception {
        var fixture=new PostgresContractFixture();try{fixture.context=configured;fixture.jdbc=configured.getBean(NamedParameterJdbcTemplate.class);fixture.source=configured.getBean(DataSource.class);fixture.transactionManager=configured.getBean(PlatformTransactionManager.class);fixture.identityStore=configured.getBean(IdentityStore.class);fixture.passwordHasher=configured.getBean(PasswordHasher.class);fixture.mailbox=configured.getBean(LocalTestMailbox.class);fixture.signup=configured.getBean(SignupOperations.class);fixture.port=configured.getEnvironment().getRequiredProperty("local.server.port",Integer.class);try(var connection=fixture.source.getConnection()){DedicatedDatabaseTarget.verifyConnection(connection,System.getenv("D2_DB_NAME"));}
            var f=fixture.returnFeeFixture("d-v57-isolated",false);var shipment=fixture.paidShipment(f);fixture.completeShipment(f,shipment,Instant.now());var policyLines=f.order().quote().stores().getFirst().lines();assertEquals(java.util.Set.of("3000","5000"),policyLines.stream().map(line->line.returnPolicy().amount()).collect(java.util.stream.Collectors.toSet()));var selected=policyLines.stream().filter(line->line.returnPolicy().amount().equals("3000")).findFirst().orElseThrow();var raw=shipment.units().stream().filter(unit->f.order().sellers().getFirst().items().stream().anyMatch(item->item.id().equals(unit.orderItemId())&&item.skuId().equals(selected.skuId()))).findFirst().orElseThrow();var fees=configured.getBean(com.example.marketplace.claim.api.ReturnFees.class);var quote=fees.quote(f.order().orderNumber(),fixture.shipmentAuthority(f),List.of(new com.example.marketplace.claim.api.ReturnFees.Target(raw.orderItemId(),raw.ordinal(),com.example.marketplace.claim.api.ReturnFees.Responsibility.BUYER)));assertEquals("3000",quote.increment());var accepted=fixture.acceptReturnQuote(f,quote,"actual-c-before-d",fees);assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_request",Map.of(),Integer.class));var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(DedicatedDatabaseTarget.databaseUrl(targetDatabase),System.getenv("D2_DB_USERNAME"),System.getenv("D2_DB_PASSWORD"));try(var connection=ds.getConnection()){DedicatedDatabaseTarget.verifyConnection(connection,targetDatabase);}var graph=fixture.copyActualCGraphToV57(new NamedParameterJdbcTemplate(ds),ds);java.nio.file.Files.writeString(folder.resolve("target-columns.json"),fixture.authMapper.writeValueAsString(graph));java.nio.file.Files.writeString(folder.resolve("preparation-result.json"),fixture.authMapper.writeValueAsString(Map.of("sourceDatabase",System.getenv("D2_DB_NAME"),"targetDatabase",targetDatabase,"sourceActualPorts","ReturnFees.quote/accept; OrderUnitHolds.acquire; FinancialIntents.registerReturn; Refunds.reserve/bindRefund","intent",accepted.accepted().intentId(),"assessment",accepted.accepted().id(),"attempt",accepted.attempt(),"targetVersion",57,"dbLocalAndMetadataExcluded",List.of("claim_fee_assessment.created_xid(target transaction default)","media_namespace","order_policy_migration","admin_bootstrap","flyway_schema_history"),"sourceDOnlyColumnsExcluded",List.of("claim_fee_quote.purpose"),"previousBinaryExecuted",false)));
        }finally{TOSS_STUB.stop(0);}
    }

    static void prepareLegacyRefundQueryFixture(org.springframework.context.ConfigurableApplicationContext configured,java.nio.file.Path folder,boolean prepare) throws Exception {
        var f=new PostgresContractFixture();f.context=configured;f.jdbc=configured.getBean(NamedParameterJdbcTemplate.class);f.source=configured.getBean(DataSource.class);f.transactionManager=configured.getBean(PlatformTransactionManager.class);f.identityStore=configured.getBean(IdentityStore.class);f.passwordHasher=configured.getBean(PasswordHasher.class);f.mailbox=configured.getBean(LocalTestMailbox.class);f.signup=configured.getBean(SignupOperations.class);f.port=configured.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
        try{
            if(prepare){
                assertNull(f.jdbc.queryForObject("SELECT to_regclass('marketplace.payment_refund_query_policy')",Map.of(),String.class));var payment=f.paymentFixture("f-pre-query",false);var unit=f.paidShipment(payment).units().getFirst();var accepted=f.acceptCancellation(payment,List.of(Map.of("itemId",unit.orderItemId(),"ordinal",unit.ordinal())),"f_pre_query");UUID attempt=UUID.fromString(accepted.path("refundAttemptId").asText()),claim=UUID.fromString(accepted.path("claimId").asText());f.jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','UNKNOWN')",Map.of("id",claim));UUID id=f.jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key","refund-submit:"+attempt),UUID.class);var preparedClaim=f.claimFRefundJob(id);var jobs=preparedClaim.jobs();var job=preparedClaim.job();var tx=new TransactionTemplate(f.transactionManager);var refunds=configured.getBean(com.example.marketplace.payment.api.Refunds.class);var leases=configured.getBean(com.example.marketplace.platform.api.JobLeases.class);var outbox=configured.getBean(com.example.marketplace.platform.api.Outbox.class);
                // Test-only previous T2/T3 protocol, validated by the actual V102 constraints. No old binary, copied graph, disabled trigger, or invented success.
                var request=tx.execute(t->{var current=refunds.get(attempt);leases.requireOwned(job.id(),job.ownerToken());f.jdbc.update("UPDATE marketplace.payment_refund_attempt SET state='PROCESSING',started_at=clock_timestamp(),fence_token=:token,version=version+1 WHERE id=:id",Map.of("id",attempt,"token",job.ownerToken()));f.jdbc.update("INSERT INTO marketplace.payment_refund_event(id,attempt_id,version,action) VALUES(:event,:id,1,'PROCESSING')",Map.of("event",UUID.randomUUID(),"id",attempt));leases.requireOwned(job.id(),job.ownerToken());return new com.example.marketplace.payment.api.RefundProvider.Request(current.paymentId(),current.orderId(),attempt,"customer-refund:"+attempt,current.amount(),"DEMO");});
                var result=configured.getBean(com.example.marketplace.payment.api.RefundProvider.class).submit(request);assertEquals("UNKNOWN",result.state());assertEquals(attempt,result.refundAttemptId());assertEquals(request.amount(),result.amount());
                tx.executeWithoutResult(t->{refunds.get(attempt);leases.requireOwned(job.id(),job.ownerToken());f.jdbc.update("UPDATE marketplace.payment_refund_attempt SET state='UNKNOWN',version=version+1 WHERE id=:id AND state='PROCESSING' AND fence_token=:token",Map.of("id",attempt,"token",job.ownerToken()));f.jdbc.update("INSERT INTO marketplace.payment_refund_event(id,attempt_id,version,action) VALUES(:event,:id,2,'UNKNOWN')",Map.of("event",UUID.randomUUID(),"id",attempt));String key="refund-result:"+attempt+":2";outbox.append(new com.example.marketplace.platform.api.Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new com.example.marketplace.platform.api.Jobs.Command("REFUND_RESULT_RECORDED.v1",key,attempt.toString(),attempt+"|2",Instant.now())));leases.requireOwned(job.id(),job.ownerToken());});assertTrue(jobs.complete(job.id(),job.ownerToken()));assertEquals("UNKNOWN",tx.execute(t->refunds.get(attempt)).state());
                java.nio.file.Files.writeString(folder.resolve("legacy-prepared.json"),f.authMapper.writeValueAsString(Map.of("attempt",attempt,"claim",claim,"order",payment.order().id(),"job",id,"schema",102,"previousBinaryExecuted",false,"actualPorts","current C reserve + actual DEMO provider + test-only V102 T2/T3 protocol under all original constraints")));
            }else{
                var prepared=f.authMapper.readTree(java.nio.file.Files.readString(folder.resolve("legacy-prepared.json")));UUID attempt=UUID.fromString(prepared.path("attempt").asText());var before=f.jdbc.queryForMap("SELECT * FROM marketplace.payment_refund_query_policy WHERE attempt_id=:id",Map.of("id",attempt));f.runScheduledRefundQuery(attempt);assertEquals(1,f.jdbc.queryForObject("SELECT submit_calls FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",attempt),Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT query_calls FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",attempt),Integer.class));assertEquals("UNKNOWN",f.jdbc.queryForObject("SELECT state FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",attempt),String.class));assertEquals(f.jdbc.queryForObject("SELECT amount FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",attempt),Long.class),f.jdbc.queryForObject("SELECT reserved FROM marketplace.payment_refund_budget WHERE order_id=:id",Map.of("id",UUID.fromString(prepared.path("order").asText())),Long.class));assertEquals(0L,f.jdbc.queryForObject("SELECT succeeded FROM marketplace.payment_refund_budget WHERE order_id=:id",Map.of("id",UUID.fromString(prepared.path("order").asText())),Long.class));assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT state FROM marketplace.order_unit_hold WHERE cause_id=:id",Map.of("id",UUID.fromString(prepared.path("claim").asText())),String.class));assertEquals(before,f.jdbc.queryForMap("SELECT * FROM marketplace.payment_refund_query_policy WHERE attempt_id=:id",Map.of("id",attempt)));assertEquals("SUCCEEDED",f.jdbc.queryForObject("SELECT status FROM marketplace.job WHERE id=:id",Map.of("id",UUID.fromString(prepared.path("job").asText())),String.class));java.nio.file.Files.writeString(folder.resolve("legacy-resumed.json"),f.authMapper.writeValueAsString(Map.of("attempt",attempt,"submit",1,"query",1,"state","UNKNOWN","samePreparedAt",true)));
            }
        }finally{TOSS_STUB.stop(0);}
    }

    protected void runLegacyFinancialChild(java.nio.file.Path folder,String phase,String database,String type,List<String> values) throws Exception {
        var argument=folder.resolve(phase+".args");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');String parameters=values.stream().map(value->"\""+value.replace((char)92,'/')+"\"").collect(java.util.stream.Collectors.joining("\n"));java.nio.file.Files.writeString(argument,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest."+type+"\n"+parameters+"\n");var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+argument).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile());builder.environment().remove("D2_ADMIN_PASSWORD");builder.environment().put("D2_DB_NAME",database);builder.environment().put("D2_DB_URL",DedicatedDatabaseTarget.databaseUrl(database));var child=builder.start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(child.pid()));try{assertTrue(ContractTiming.awaitProcess(child,40,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),"inspect preserved V57 "+phase+" runtime log");}finally{if(child.isAlive()){child.destroyForcibly();ContractTiming.awaitProcess(child,10,TimeUnit.SECONDS);}}
    }
    protected Map<String,String> copyActualCGraphToV57(NamedParameterJdbcTemplate target,DataSource source) {
        var excluded=java.util.Set.of("flyway_schema_history","media_namespace","order_policy_migration","admin_bootstrap");var tables=target.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='marketplace' AND table_type='BASE TABLE' ORDER BY table_name",Map.of(),String.class).stream().filter(t->!excluded.contains(t)&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace."+t+")",Map.of(),Boolean.class)).toList();var graph=new java.util.LinkedHashMap<String,String>();
        for(String table:tables){var columns=target.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='marketplace' AND table_name=:table AND is_generated='NEVER' AND column_name<>'created_xid' ORDER BY ordinal_position",Map.of("table",table),String.class);graph.put(table,columns.stream().map(c->"\""+c+"\"").collect(java.util.stream.Collectors.joining(",")));}
        try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"v57-original-foreign-key-definitions.json"),authMapper.writeValueAsString(target.queryForList("SELECT c.conname,child.relname AS child,parent.relname AS parent,c.condeferrable,c.condeferred,pg_get_constraintdef(c.oid) AS definition FROM pg_constraint c JOIN pg_class child ON child.oid=c.conrelid JOIN pg_class parent ON parent.oid=c.confrelid JOIN pg_namespace n ON n.oid=child.relnamespace WHERE n.nspname='marketplace' AND c.contype='f' ORDER BY child.relname,c.conname",Map.of())));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        var dependencies=target.queryForList("SELECT child.relname AS child,parent.relname AS parent,(SELECT string_agg(quote_ident(a.attname),'|' ORDER BY u.ordinality) FROM unnest(c.conkey) WITH ORDINALITY u(attnum,ordinality) JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=u.attnum) AS columns FROM pg_constraint c JOIN pg_class child ON child.oid=c.conrelid JOIN pg_class parent ON parent.oid=c.confrelid JOIN pg_namespace n ON n.oid=child.relnamespace WHERE n.nspname='marketplace' AND c.contype='f' AND NOT c.condeferrable AND child.oid<>parent.oid",Map.of()).stream().filter(d->tables.contains(d.get("child"))&&tables.contains(d.get("parent"))).filter(d->{String where=java.util.Arrays.stream(d.get("columns").toString().split("\\|" )).map(c->c+" IS NOT NULL").collect(java.util.stream.Collectors.joining(" AND "));return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace."+d.get("child")+" WHERE "+where+")",Map.of(),Boolean.class);}).toList();try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"v57-actual-nonnull-source-fk-dependencies.json"),authMapper.writeValueAsString(Map.of("populatedActualSourceTables",tables,"nonNullSourceRowDependencies",dependencies)));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}var remaining=new java.util.LinkedHashSet<>(tables);var order=new java.util.ArrayList<String>();while(!remaining.isEmpty()){var ready=remaining.stream().filter(t->dependencies.stream().noneMatch(d->t.equals(d.get("child"))&&remaining.contains(d.get("parent")))).toList();assertFalse(ready.isEmpty(),"nondeferred source dependency cycle: "+remaining);order.addAll(ready);remaining.removeAll(ready);}
        new TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(source)).executeWithoutResult(t->{target.getJdbcTemplate().execute("SET CONSTRAINTS ALL DEFERRED");for(String table:order){String columns=graph.get(table);String sort="";for(var row:jdbc.queryForList("SELECT "+columns+" FROM marketplace."+table+sort,Map.of())){var values=new MapSqlParameterSource();var slots=new java.util.ArrayList<String>();int i=0;for(var value:row.values()){values.addValue("v"+i,value);slots.add(":v"+i++);}target.update("INSERT INTO marketplace."+table+"("+columns+") OVERRIDING SYSTEM VALUE VALUES("+String.join(",",slots)+")",values);}}});
        for(var entry:graph.entrySet()){String sql="SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM(SELECT "+entry.getValue()+" FROM marketplace."+entry.getKey()+") t";assertEquals(jdbc.getJdbcTemplate().queryForObject(sql,String.class),target.getJdbcTemplate().queryForObject(sql,String.class),entry.getKey()+" target V57 columns/constraints preserve actual C graph");}return graph;
    }


    protected void assertNotEmptyCursor(String cursor){assertTrue(cursor!=null&&!cursor.isEmpty());}
    protected record ReadActorFixture(ReturnParties parties,com.example.marketplace.claim.api.Returns.Receipt accepted,com.example.marketplace.order.api.OrderQueries.Authority actor,com.example.marketplace.guest.api.GuestAccessSessions gas,JobClock clock,GuestFixture guest) { }
    protected ReadActorFixture returnReadActorFixture(String actor) throws Exception {
        GuestFixture guest=null;com.example.marketplace.guest.api.GuestAccessSessions gas=context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class);ReturnParties parties;com.example.marketplace.order.api.OrderQueries.Authority authority;var clock=new JobClock();clock.now=Instant.now();
        if(actor.equals("SUPPORT")){var shipping=prepareActualShipping(physicalReturn("d-rs",4,true),"FAILED");runCancellationJob("REFUND_RESULT_RECORDED.v1",shipping.attempt());var operator=supportFixture("read-final-support","SUPPORT");clock.now=Instant.now();return new ReadActorFixture(shipping.physical().parties(),shipping.physical().accepted(),operator.authority(),gas,clock,null);}
        if(actor.equals("GAS")){var source=feeAuthorityCase("d-read-final-gas",true);guest=source.guest();gas=guestAccess(guest);var seller=asSeller(source.fixture(),source.fixture().order().sellers().getFirst().id());var shipment=shipments().seller(seller.order().sellers().getFirst().id(),shipmentAuthority(seller)).getFirst();parties=new ReturnParties(source.fixture(),seller,shipment);authority=source.authority();}
        else{parties=returnParties("read-final-"+actor.toLowerCase(java.util.Locale.ROOT));authority=actor.equals("SELLER")?shipmentAuthority(parties.seller()):actor.equals("SUPPORT")?supportFixture("read-final-support","SUPPORT").authority():shipmentAuthority(parties.buyer());}
        var owner=guest==null?shipmentAuthority(parties.buyer()):authority;var raw=parties.shipment().units().getFirst();var request=new com.example.marketplace.claim.api.Returns.Request("read-final-request",confirmations().get(parties.buyer().order().orderNumber(),owner).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(raw.orderItemId(),raw.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"실제 조회 최종권한 준비");var accepted=context.getBean(com.example.marketplace.claim.api.Returns.class).request(parties.buyer().order().orderNumber(),owner,request);clock.now=Instant.now();if(guest!=null){guest.clock.now=clock.instant();}return new ReadActorFixture(parties,accepted,authority,gas,clock,guest);
    }
    protected com.example.marketplace.claim.api.ReturnQueries returnReadsWith(NamedParameterJdbcTemplate storage,com.example.marketplace.order.api.OrderQueries queries,com.example.marketplace.order.api.ReturnApplications applications,com.example.marketplace.claim.infrastructure.CurrentSupport support) {
        return new com.example.marketplace.claim.infrastructure.JdbcReturnQueries(storage,queries,orders(),applications,support,context.getBean(com.example.marketplace.payment.api.Refunds.class),context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class),context.getBean(com.example.marketplace.order.api.AftercareSettlements.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),transactionManager);
    }
    protected record ReadOutcome(int status,Object body) { }


    protected void assertGasReadHasOnlySlidingCredentialEffect(Map<String,String> before,Map<String,String> after,String currentTokenHash,boolean logout) {
        var expected=new java.util.TreeMap<>(before);var actual=new java.util.TreeMap<>(after);String oldRaw=expected.remove("guest_access_session"),newRaw=actual.remove("guest_access_session");assertEquals(expected,actual,"all non-credential claim/payment/order/inventory/coupon/job participants remain byte-identical");var oldRows=authMapper.readValue(oldRaw,new tools.jackson.core.type.TypeReference<List<Map<String,Object>>>() { });var newRows=authMapper.readValue(newRaw,new tools.jackson.core.type.TypeReference<List<Map<String,Object>>>() { });assertEquals(oldRows.size(),newRows.size());boolean currentFound=false;
        for(var old:oldRows){var next=new java.util.LinkedHashMap<>(newRows.stream().filter(row->row.get("token_hash").equals(old.get("token_hash"))).findFirst().orElseThrow());var original=new java.util.LinkedHashMap<>(old);if(!old.get("token_hash").equals(currentTokenHash)){assertEquals(original,next,"other GAS credentials are unchanged");continue;}currentFound=true;Instant oldIdle=java.time.OffsetDateTime.parse(original.remove("idle_expires_at").toString()).toInstant(),newIdle=java.time.OffsetDateTime.parse(next.remove("idle_expires_at").toString()).toInstant(),maximum=java.time.OffsetDateTime.parse(next.get("expires_at").toString()).toInstant();assertFalse(newIdle.isBefore(oldIdle),"legitimate current GET cannot shorten its stored idle deadline");assertFalse(newIdle.isAfter(maximum));if(logout){assertNull(original.remove("revoked_at"));assertTrue(next.remove("revoked_at")!=null,"the explicit logout revokes this exact cookie credential");}assertEquals(original,next,"only this current credential sliding idle field and explicit logout revocation may change");}assertTrue(currentFound);
    }
    protected com.example.marketplace.claim.api.Returns.Request returnRequest(PaymentFixture f,com.example.marketplace.fulfillment.api.Shipments.View shipment,String key) {
        var selected=shipment.units().getFirst();
        return new com.example.marketplace.claim.api.Returns.Request(key,confirmations().get(f.order().orderNumber(),shipmentAuthority(f)).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(selected.orderItemId(),selected.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"실제 반품 접수");
    }



    protected com.example.marketplace.claim.api.Returns returnsAtClock(java.time.Clock clock) {
        var queries=context.getBean(com.example.marketplace.order.api.OrderQueries.class);
        var applications=new com.example.marketplace.order.infrastructure.JdbcReturnApplications(jdbc,queries,context.getBean(com.example.marketplace.order.api.ReturnSources.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),clock);
        var commands=new com.example.marketplace.claim.infrastructure.ReturnCommands(jdbc,queries,applications,context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,clock,context.getBean(com.example.marketplace.claim.api.FinancialIntents.class));
        return new com.example.marketplace.claim.infrastructure.JdbcReturns(queries,confirmations(),applications,context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),jdbc,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,commands);
    }


    protected Map<String,String> returnApplicationParticipants() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "returnApplicationParticipants")) {
        var result=new java.util.LinkedHashMap<String,String>(returnFinancialParticipants());
        for(String table:List.of("order_return_application","claim_return_request","claim_return_unit","claim_return_history","claim_return_command","claim_return_collection","claim_return_inspection","claim_return_inspection_unit","inventory_return_restitution","inventory_restitution_unit","inventory_restitution_movement","claim_return_fee_quote_context","claim_return_financial_consent","claim_return_financial_result","claim_return_support_command","order_return_physical_unit","claim_return_physical_source","inventory_return_physical_restitution","claim_return_item_ready","claim_return_ready_unit","claim_seller_shipping_decision","claim_seller_shipping_current","claim_return_shipping_binding","order_return_completion","order_return_completion_unit","order_shipping_decision","order_shipping_current","order_aftercare_shipping","claim_shipping_result","claim_return_completion","claim_shipping_support_command")) {
            result.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));
        }
        return result;
    
        }
    }




    protected com.example.marketplace.catalog.api.Products.Product ownedPaymentProduct(PaymentFixture f) {
        UUID store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(f.authority().member()).id();var product=f.order().quote().stores().stream().filter(s->s.storeId().equals(store)).findFirst().orElseThrow().lines().getFirst().productId();return context.getBean(com.example.marketplace.catalog.api.Products.class).own(f.authority().member(),product);
    }



    protected Map<String,Object> feeParticipantState(PaymentFixture f,AcceptedReturnFee a) {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "feeParticipantState")) {
        var result=new java.util.LinkedHashMap<String,Object>();var p=Map.of("assessment",a.accepted().id(),"order",f.order().id(),"attempt",a.attempt(),"hold",a.hold());for(var entry:Map.of("assessment","SELECT to_jsonb(t)::text FROM marketplace.claim_fee_assessment t WHERE id=:assessment","bucket","SELECT to_jsonb(t)::text FROM marketplace.claim_fee_bucket t WHERE order_id=:order ORDER BY id","application","SELECT to_jsonb(t)::text FROM marketplace.claim_fee_application t WHERE assessment_id=:assessment","withdrawal","SELECT to_jsonb(t)::text FROM marketplace.claim_fee_withdrawal t WHERE assessment_id=:assessment","event","SELECT to_jsonb(t)::text FROM marketplace.claim_fee_event t WHERE assessment_id=:assessment ORDER BY version,action","payment","SELECT to_jsonb(t)::text FROM marketplace.payment_refund_attempt t WHERE id=:attempt","budget","SELECT to_jsonb(t)::text FROM marketplace.payment_refund_budget t WHERE order_id=:order","hold","SELECT to_jsonb(t)::text FROM marketplace.order_unit_hold t WHERE id=:hold","unit","SELECT to_jsonb(t)::text FROM marketplace.order_unit_lifecycle t WHERE order_id=:order ORDER BY item_id,ordinal").entrySet()) { result.put(entry.getKey(),jdbc.queryForList(entry.getValue(),p,String.class)); }result.put("outbox",jdbc.queryForList("SELECT e.id::text||':'||j.dedupe_key FROM marketplace.outbox_event e JOIN marketplace.job j ON j.id=e.job_id WHERE j.dedupe_key LIKE :key ORDER BY e.id",Map.of("key","return-fee:"+a.accepted().id()+":%"),String.class));return Map.copyOf(result);
    
        }
    }









    protected void cancellationWithdrawalReceiptOnly(String suffix,boolean failed) throws Exception {
        var c=cancellationAtRefundState(suffix,failed);var before=cancellationWithdrawalState(true);long revision=jdbc.queryForObject("SELECT version FROM marketplace.claim WHERE id=:id",Map.of("id",c.claim()),Long.class);String reason="철회 영수증 단독 저장 검증",key="receipt-only";String fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((c.claim()+"|"+key+"|"+revision+"|"+reason).getBytes(java.nio.charset.StandardCharsets.UTF_8)));var receipt=new com.example.marketplace.claim.api.Claims.Withdrawal(c.claim(),c.attempt(),"WITHDRAWN",revision+1,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));boolean rejected=false;
        try { new TransactionTemplate(transactionManager).executeWithoutResult(t->insertCancellationWithdrawalOnly(c,failed,key,fingerprint,reason,receipt)); }catch(DataIntegrityViolationException expected) { rejected=true; }
        String replay="NOT_NEEDED";if(!rejected) { replay=context.getBean(com.example.marketplace.claim.api.Claims.class).withdraw(c.fixture().order().orderNumber(),shipmentAuthority(c.fixture()),c.claim(),new com.example.marketplace.claim.api.Claims.WithdrawCommand(key,revision,reason)).state(); }java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"cancel-withdraw-receipt-only-"+(failed?"failed":"reserved")+"-observed.txt"),"rejected="+rejected+";claim="+jdbc.queryForObject("SELECT state FROM marketplace.claim WHERE id=:id",Map.of("id",c.claim()),String.class)+";refund="+jdbc.queryForObject("SELECT state FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",c.attempt()),String.class)+";apiReplay="+replay);assertTrue(rejected,"receipt-only withdrawal must reject commit and roll back payment/budget/hold/lifecycle/claim/receipt for "+(failed?"FAILED":"RESERVED"));assertEquals(before,cancellationWithdrawalState(true));new TransactionTemplate(transactionManager).executeWithoutResult(t->{insertCancellationWithdrawalOnly(c,failed,key,fingerprint,reason,receipt);assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->context.getBean(com.example.marketplace.claim.api.Claims.class).withdraw(c.fixture().order().orderNumber(),shipmentAuthority(c.fixture()),c.claim(),new com.example.marketplace.claim.api.Claims.WithdrawCommand(key,revision,reason))).status(),"precommit inconsistent withdrawal receipt must not replay as success");t.setRollbackOnly();});assertEquals(before,cancellationWithdrawalState(true));var command=new com.example.marketplace.claim.api.Claims.WithdrawCommand("normal-after-rollback",revision,"정상 철회");var api=context.getBean(com.example.marketplace.claim.api.Claims.class);var normal=api.withdraw(c.fixture().order().orderNumber(),shipmentAuthority(c.fixture()),c.claim(),command);assertEquals(normal,api.withdraw(c.fixture().order().orderNumber(),shipmentAuthority(c.fixture()),c.claim(),command));assertEquals("WITHDRAWN",normal.state());java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"cancel-withdraw-receipt-only-"+(failed?"failed":"reserved")+"-observed.txt"),";apiPrecommitReplay=409;normalReplay=true",java.nio.file.StandardOpenOption.APPEND);
    }
    protected void insertCancellationWithdrawalOnly(CancellationCase c,boolean failed,String key,String fingerprint,String reason,com.example.marketplace.claim.api.Claims.Withdrawal receipt) {
        context.getBean(com.example.marketplace.payment.api.Refunds.class).cancelUnsent(c.attempt());context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class).release(c.fixture().order().id(),c.hold(),c.claim(),"WITHDRAWN");jdbc.update("INSERT INTO marketplace.claim_withdrawal(claim_id,attempt_id,original_state,request_key,fingerprint,reason,receipt,withdrawn_at) VALUES(:claim,:attempt,:state,:key,:hash,:reason,CAST(:receipt AS jsonb),:at)",Map.of("claim",c.claim(),"attempt",c.attempt(),"state",failed?"FAILED":"RESERVED","key",key,"hash",fingerprint,"reason",reason,"receipt",authMapper.writeValueAsString(receipt),"at",java.sql.Timestamp.from(receipt.withdrawnAt())));
    }


    protected record CancellationCase(PaymentFixture fixture,UUID claim,UUID attempt,UUID hold) { }
    protected CancellationCase cancellationAtRefundState(String suffix,boolean failed) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "cancellationAtRefundState")) {
        var f=paymentFixture("a-"+suffix,false);var shipment=paidShipment(f);var u=shipment.units().getFirst();var accepted=acceptCancellation(f,List.of(Map.of("itemId",u.orderItemId().toString(),"ordinal",u.ordinal())),"case-once");UUID claim=UUID.fromString(accepted.get("claimId").asText()),attempt=UUID.fromString(accepted.get("refundAttemptId").asText()),hold=jdbc.queryForObject("SELECT hold_id FROM marketplace.claim WHERE id=:id",Map.of("id",claim),UUID.class);if(failed) { jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','FAILED')",Map.of("id",claim));runCancellationJob("REFUND_SUBMIT.v1",attempt); }return new CancellationCase(f,claim,attempt,hold);
    
        }
    }
    protected CancellationCase failedCancellationWithdrawnThroughHttp(String suffix) throws Exception {
        var c=cancellationAtRefundState(suffix,true);withdrawCancellationThroughHttp(c);return c;
    }
    protected void withdrawCancellationThroughHttp(CancellationCase c) throws Exception {
        long revision=jdbc.queryForObject("SELECT version FROM marketplace.claim WHERE id=:id",Map.of("id",c.claim()),Long.class);var response=accountRequest(c.fixture().browser(),"POST","/api/orders/"+c.fixture().order().orderNumber()+"/cancellations/"+c.claim()+"/withdraw",Map.of("key","withdraw-failed","expectedRevision",revision,"reason","확정 실패 후 구매자 철회"),csrfToken(c.fixture().browser()));assertEquals(200,response.statusCode());assertEquals("WITHDRAWN",jdbc.queryForObject("SELECT state FROM marketplace.claim WHERE id=:id",Map.of("id",c.claim()),String.class));
    }
    protected void assertWithdrawnFailedResultOnce(CancellationCase c) {
        assertEquals("RELEASED",jdbc.queryForObject("SELECT state FROM marketplace.order_unit_hold WHERE id=:id",Map.of("id",c.hold()),String.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE active_hold_id=:id",Map.of("id",c.hold()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_refund_result_receipt WHERE attempt_id=:id",Map.of("id",c.attempt()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_event WHERE claim_id=:id AND action LIKE 'WITHDRAWN_%_FAILED_%'",Map.of("id",c.claim()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event e JOIN marketplace.job j ON j.id=e.job_id WHERE j.dedupe_key LIKE :key",Map.of("key","claim-withdrawn-result:"+c.attempt()+":%"),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_restitution WHERE cause_id=:id",Map.of("id",c.claim()),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT effects FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",c.attempt()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",c.claim()),Integer.class));assertEquals("PAID",orders().find(c.fixture().order().orderNumber()).state());
    }
    protected Map<String,String> cancellationWithdrawalState(boolean includeJobs) {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "cancellationWithdrawalState")) {
        var state=new java.util.TreeMap<String,String>();var tables=new java.util.ArrayList<>(List.of("claim","claim_unit","claim_withdrawal","claim_refund_result_receipt","claim_event","claim_refund_effect","payment_refund_attempt","payment_refund_event","payment_refund_budget","order_unit_hold","order_unit_lifecycle","order_lifecycle_event","order_confirmation_schedule","outbox_event","inventory_restitution","coupon_restoration"));if(includeJobs) { tables.add("job"); }for(String table:tables) { state.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class)); }return state;
    
        }
    }
    protected record SupportFixture(HttpClient browser,com.example.marketplace.order.api.OrderQueries.Authority authority) { }
    protected SupportFixture supportFixture(String suffix,String permission) throws Exception {
        String email="c-support-"+suffix+"@example.com";UUID member=activeMember(email);jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'ADMIN')",Map.of("id",member));jdbc.update("INSERT INTO marketplace.admin_permission(user_id,permission) VALUES(:id,:permission)",Map.of("id",member,"permission",permission));var browser=authBrowser();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email,SIGNUP_PASSWORD),csrfToken(browser)).statusCode());String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id",Map.of("id",member.toString()),String.class);long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",member),Long.class);return new SupportFixture(browser,new com.example.marketplace.order.api.OrderQueries.Authority(session,member,version,null));
    }
    protected tools.jackson.databind.JsonNode acceptCancellation(PaymentFixture f,List<? extends Map<String,?>> units,String key) throws Exception {
        return acceptCancellation(f,units,key,shipmentAuthority(f));
    }
    protected tools.jackson.databind.JsonNode acceptCancellation(PaymentFixture f,List<? extends Map<String,?>> units,String key,com.example.marketplace.order.api.OrderQueries.Authority current) throws Exception {
        long revision=confirmations().get(f.order().orderNumber(),current).revision();String path="/api/orders/"+f.order().orderNumber()+"/cancellations";var quote=accountRequest(f.browser(),"POST",path+"/quote",Map.of("units",units,"reason","전체취소","expectedRevision",revision),csrfToken(f.browser()));assertEquals(200,quote.statusCode());var q=authMapper.readTree(quote.body());var response=accountRequest(f.browser(),"POST",path,Map.of("key",key,"units",units,"reason","전체취소","expectedRevision",revision,"quoteFingerprint",q.get("fingerprint").asText()),csrfToken(f.browser()));assertEquals(202,response.statusCode());return authMapper.readTree(response.body());
    }
    /** F automatic recovery consumes a real persisted due slot; it never fabricates a submit job. */
    protected void runScheduledRefundQuery(UUID attempt) {
        UUID id=jdbc.queryForObject("SELECT s.job_id FROM marketplace.payment_refund_query_slot s JOIN marketplace.job j ON j.id=s.job_id WHERE s.attempt_id=:id AND s.slot<=5 AND j.status='READY' AND NOT EXISTS(SELECT 1 FROM marketplace.payment_refund_query_consumption c WHERE c.attempt_id=s.attempt_id AND c.slot=s.slot) ORDER BY s.slot LIMIT 1",Map.of("id",attempt),UUID.class);var actual=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();var clock=Clock.fixed(actual.nextRunAt().plusSeconds(1),ZoneOffset.UTC);var api=context.getBean(com.example.marketplace.payment.infrastructure.JdbcRefunds.class);Object previous=org.springframework.test.util.ReflectionTestUtils.getField(api,"queryClock");org.springframework.test.util.ReflectionTestUtils.setField(api,"queryClock",clock);
        try {var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,clock);try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.copyOf(context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values()))){assertTrue(worker.runOnce());}assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());assertEquals("CALL",jdbc.queryForObject("SELECT decision FROM marketplace.payment_refund_query_consumption WHERE job_id=:id",Map.of("id",id),String.class));}
        finally {org.springframework.test.util.ReflectionTestUtils.setField(api,"queryClock",previous);}
    }

    protected void runCancellationJob(String type,UUID cause) {
        UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status IN ('READY','RUNNING') ORDER BY scheduled_at DESC,id LIMIT 1",Map.of("type",type,"cause",cause.toString()),UUID.class);var selected=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();Instant due=selected.status().equals("RUNNING")?selected.leaseUntil():selected.nextRunAt();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(due.plusSeconds(1),ZoneOffset.UTC));var handlers=context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values().stream().filter(h->h.type().equals(type)).toList();assertEquals(1,handlers.size());try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,handlers)) { assertTrue(worker.runOnce()); }assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());
    }









    protected PaymentFixture asSeller(PaymentFixture f,UUID seller) throws Exception { UUID owner=jdbc.queryForObject("SELECT ss.owner_id FROM marketplace.seller_order so JOIN marketplace.seller_store ss ON ss.id=so.store_id WHERE so.id=:id",Map.of("id",seller),UUID.class);var browser=authBrowser();String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner),String.class);assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id ORDER BY expiry_time DESC LIMIT 1",Map.of("id",owner.toString()),String.class);long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner),Long.class);return new PaymentFixture(f.order(),browser,new com.example.marketplace.payment.api.Payments.Authority(session,owner,version,null)); }


    protected Process confirmationProcess(java.nio.file.Path folder,String phase,Instant now,UUID id,String mode) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("process-start", "confirmationProcess")) {
        var args=folder.resolve(phase+".args");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.ConfirmationWorkerProbe\n"+now+"\n"+id+"\n\""+folder.resolve(phase+"-effect").toString().replace((char)92,'/')+"\"\n"+mode+"\n");var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile());builder.environment().remove("D2_ADMIN_PASSWORD");var process=builder.start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    
        }
    }
    protected record ConfirmationGuest(GuestFixture fixture,com.example.marketplace.guest.infrastructure.JdbcGuestAccess access,com.example.marketplace.order.api.Orders.Snapshot order,String token) { }
    protected ConfirmationGuest confirmationGuest(String suffix) throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var product=browseFixture("b-confirm-guest-"+suffix.replace((char)95,(char)45),10000,10);var lines=List.of(quoteLine((UUID)product.get("sku"),2));var q=quotes().calculate(null,lines,List.of());UUID attempt=guestVerified(f);access.prepare(f.browser,attempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));var contact=new com.example.marketplace.order.api.Orders.Contact("ignored",f.phone,"수령인",f.phone,"01234","주소","");var order=creationWith(access,stage->{ }).create(f.browser,null,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("once",lines,List.of(),q.fingerprint(),contact,attempt,0));new TransactionTemplate(transactionManager).executeWithoutResult(t->{orders().transition(order.orderNumber(),"PAID");var seller=order.sellers().getFirst();var units=java.util.stream.IntStream.range(0,2).mapToObj(i->new com.example.marketplace.order.api.FulfillmentOrders.Unit(seller.items().getFirst().id(),i)).toList();context.getBean(com.example.marketplace.order.api.FulfillmentOrders.class).recordInitialDelivery(new com.example.marketplace.order.api.FulfillmentOrders.InitialDelivery(1,order.id(),seller.id(),UUID.randomUUID(),UUID.randomUUID(),Instant.now(),units));});String token=access.login(order.orderNumber(),f.phone,GUEST_PASSWORD,f.source,null);return new ConfirmationGuest(f,access,order,token);
    }









    protected com.example.marketplace.fulfillment.infrastructure.JdbcShipments shipmentsWith(com.example.marketplace.order.api.OrderQueries queries,com.example.marketplace.fulfillment.infrastructure.JdbcShipments.Checkpoint checkpoint) { return new com.example.marketplace.fulfillment.infrastructure.JdbcShipments(jdbc,queries,context.getBean(com.example.marketplace.order.api.FulfillmentOrders.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,Clock.systemUTC(),checkpoint); }





    protected Process shipmentWorkerProcess(java.nio.file.Path folder,String phase,Instant now,UUID job,boolean block) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("process-start", "shipmentWorkerProcess")) {
        var args=folder.resolve(phase+".args");var marker=folder.resolve(phase+"-effect.txt");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');
        java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.ShipmentWorkerProbe\n"+now+"\n"+job+"\n\""+marker.toString().replace((char)92,'/')+"\"\n"+block+"\n");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile()).start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    
        }
    }







    protected record PaymentFixture(com.example.marketplace.order.api.Orders.Snapshot order,HttpClient browser,com.example.marketplace.payment.api.Payments.Authority authority) { }


    protected int deliveryCount(PaymentFixture f) { return jdbc.queryForObject("SELECT count(*) FROM marketplace.order_initial_delivery WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class); }
    protected com.example.marketplace.fulfillment.api.Shipments.Command splitCommand(com.example.marketplace.fulfillment.api.Shipments.View v,String key,int quantity) { return new com.example.marketplace.fulfillment.api.Shipments.Command(key,v.version(),List.of(new com.example.marketplace.fulfillment.api.Shipments.Item(v.items().getFirst().orderItemId(),quantity))); }









    protected void copyLegacyRows(NamedParameterJdbcTemplate target,String table,String query,Map<String,?> parameters) {
        var targetColumns=target.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='marketplace' AND table_name=:table ORDER BY ordinal_position",Map.of("table",table),String.class);
        for(var row:jdbc.queryForList(query,parameters)) { if(table.equals("job")&&!targetColumns.contains("claim_checked_at")){assertTrue(row.containsKey("claim_checked_at"));row.remove("claim_checked_at");}assertTrue(targetColumns.containsAll(row.keySet()),"all explicitly selected legacy columns must exist in target "+table);if(table.equals("purchase_order")){row.put("quote_snapshot",legacyJsonWithoutReturnPolicy(row.get("quote_snapshot"),true));}if(table.equals("order_item")){row.put("line_snapshot",legacyJsonWithoutReturnPolicy(row.get("line_snapshot"),false));}var columns=new java.util.ArrayList<>(row.keySet());var values=new MapSqlParameterSource();var placeholders=new java.util.ArrayList<String>();for(int i=0;i<columns.size();i++) { values.addValue("v"+i,row.get(columns.get(i)));placeholders.add(":v"+i); }target.update("INSERT INTO marketplace."+table+"("+String.join(",",columns)+")"+(table.equals("shipment_event")?" OVERRIDING SYSTEM VALUE":"")+" VALUES("+String.join(",",placeholders)+")",values); }
    }
    protected org.springframework.jdbc.datasource.DriverManagerDataSource freshLegacyDatabase(String scope) {
        String database="d2_it_"+UUID.randomUUID().toString().replace("-",""),url=DedicatedDatabaseTarget.databaseUrl(database);com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.validate(url,"marketplace_test",database);try(var admin=java.sql.DriverManager.getConnection(DedicatedDatabaseTarget.maintenanceUrl(),"postgres",System.getenv("D2_ADMIN_PASSWORD"));var sql=admin.createStatement()){DedicatedDatabaseTarget.verifyAdminConnection(admin);sql.executeUpdate("CREATE DATABASE \""+database+"\" OWNER marketplace_test");}catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}System.out.println("D2_CREATED_DATABASE="+database);var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"marketplace_test",System.getenv("D2_DB_PASSWORD"));try(var connection=ds.getConnection()){com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyConnection(connection,database);var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve(scope+"-"+UUID.randomUUID()));java.nio.file.Files.writeString(folder.resolve("database.txt"),database);}catch(java.sql.SQLException|java.io.IOException failure){throw new IllegalStateException(failure);}return ds;
    }
    protected void copyLegacyCatalogForOrders(NamedParameterJdbcTemplate target,Map<String,?> parameters) {
        String selected="SELECT i.sku_id FROM marketplace.order_item i WHERE i.order_id IN (:ids)";copyLegacyRows(target,"catalog_category","SELECT c.* FROM marketplace.catalog_category c WHERE c.id IN (SELECT p.category_id FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id IN ("+selected+"))",parameters);copyLegacyRows(target,"catalog_product","SELECT id,store_id,category_id,slug,title,description,state,is_hidden_by_admin,version,created_at FROM marketplace.catalog_product WHERE id IN (SELECT product_id FROM marketplace.catalog_sku WHERE id IN ("+selected+"))",parameters);copyLegacyRows(target,"catalog_sku","SELECT * FROM marketplace.catalog_sku WHERE id IN ("+selected+")",parameters);copyLegacyRows(target,"inventory_balance","SELECT * FROM marketplace.inventory_balance WHERE sku_id IN ("+selected+")",parameters);
    }
    protected void copyLegacyShipmentSet(NamedParameterJdbcTemplate target,List<UUID> ids,UUID job) {
        for(String table:List.of("identity_user","seller_store")){copyLegacyRows(target,table,"SELECT * FROM marketplace."+table,Map.of());}var p=Map.of("ids",ids);copyLegacyRows(target,"order_checkout_attempt","SELECT c.* FROM marketplace.order_checkout_attempt c JOIN marketplace.purchase_order o ON o.checkout_id=c.id WHERE o.id IN (:ids)",p);copyLegacyRows(target,"purchase_order","SELECT * FROM marketplace.purchase_order WHERE id IN (:ids)",p);for(String table:List.of("seller_order","order_item","order_contact")){copyLegacyRows(target,table,"SELECT * FROM marketplace."+table+" WHERE order_id IN (:ids)",p);}copyLegacyRows(target,"order_unit","SELECT u.* FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.order_id IN (:ids)",p);copyLegacyRows(target,"shipment","SELECT id,order_id,seller_order_id,order_number,purpose_key,state,version,created_at FROM marketplace.shipment WHERE order_id IN (:ids)",p);for(String table:List.of("shipment_item","shipment_event","shipment_audit")){copyLegacyRows(target,table,"SELECT e.* FROM marketplace."+table+" e JOIN marketplace.shipment s ON s.id=e.shipment_id WHERE s.order_id IN (:ids)",p);}copyLegacyRows(target,"job","SELECT * FROM marketplace.job WHERE id=:id",Map.of("id",job));
    }
    protected com.example.marketplace.promotion.api.Quotes.Quote legacyQuoteWithoutReturnPolicy(com.example.marketplace.promotion.api.Quotes.Quote q) {
        var stores=q.stores().stream().map(store->new com.example.marketplace.promotion.api.Quotes.Store(store.storeId(),store.slug(),store.name(),store.version(),store.lines().stream().map(l->new com.example.marketplace.promotion.api.Quotes.Line(l.skuId(),l.productId(),l.productVersion(),l.skuVersion(),l.title(),l.options(),l.quantity(),l.unitPrice(),l.gross(),l.promotion(),l.promotionDiscount(),l.couponDiscount(),l.net(),l.units())).toList(),store.subtotal(),store.productDiscount(),store.afterPromotion(),store.coupon(),store.couponDiscount(),store.shippingBase(),store.shippingPromotion(),store.shippingDiscount(),store.total())).toList();var prior=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt(),stores,q.total(),q.fingerprint());return new com.example.marketplace.promotion.api.Quotes.Quote(prior.memberId(),prior.policyVersion(),prior.evaluatedAt(),prior.stores(),prior.total(),com.example.marketplace.promotion.domain.QuoteCalculator.fingerprint(prior));
    }
    protected org.postgresql.util.PGobject legacyJsonWithoutReturnPolicy(Object value,boolean quote) {
        tools.jackson.databind.JsonNode tree;if(quote){tree=authMapper.valueToTree(legacyQuoteWithoutReturnPolicy(authMapper.readValue(value.toString(),com.example.marketplace.promotion.api.Quotes.Quote.class)));for(var store:tree.get("stores")){for(var line:store.get("lines")){((tools.jackson.databind.node.ObjectNode)line).remove("returnPolicy");}}}else{tree=authMapper.readTree(value.toString());((tools.jackson.databind.node.ObjectNode)tree).remove("returnPolicy");}var result=new org.postgresql.util.PGobject();result.setType("jsonb");try{result.setValue(tree.toString());}catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}return result;
    }
    protected List<Map<String,Object>> alignCopiedLegacySequenceMetadata(NamedParameterJdbcTemplate target,String database,java.util.Set<String> copiedTables) {
        assertTrue(database.matches("d2_it_[a-f0-9]{32}"));assertEquals(database,target.getJdbcTemplate().queryForObject("SELECT current_database()",String.class));
        var columns=target.queryForList("SELECT c.relname AS table_name,a.attname AS column_name,a.attidentity AS identity_kind,pg_get_serial_sequence(format('%I.%I',n.nspname,c.relname),a.attname) AS sequence_name FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid WHERE n.nspname='marketplace' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped AND pg_get_serial_sequence(format('%I.%I',n.nspname,c.relname),a.attname) IS NOT NULL ORDER BY c.relname,a.attnum",Map.of());
        var audit=new java.util.ArrayList<Map<String,Object>>();for(var column:columns) {
            String table=column.get("table_name").toString(),field=column.get("column_name").toString(),sequence=column.get("sequence_name").toString();assertTrue(table.matches("[a-z_][a-z_0-9]*"));assertTrue(field.matches("[a-z_][a-z_0-9]*"));var metadata=target.queryForMap("SELECT seqstart,seqincrement FROM pg_sequence WHERE seqrelid=CAST(:name AS regclass)",Map.of("name",sequence));assertTrue(((Number)metadata.get("seqincrement")).longValue()>0);String relation="marketplace.\""+table+"\"",quoted="\""+field+"\"";var rows=target.queryForList("SELECT row_to_json(t)::text FROM "+relation+" t ORDER BY row_to_json(t)::text",Map.of(),String.class);Long maximum=target.getJdbcTemplate().queryForObject("SELECT max("+quoted+") FROM "+relation,Long.class);boolean imported=copiedTables.contains(table);if(imported) {long value=maximum==null?((Number)metadata.get("seqstart")).longValue():maximum;target.queryForObject("SELECT setval(CAST(:name AS regclass),:value,:called)",Map.of("name",sequence,"value",value,"called",maximum!=null),Long.class);assertEquals(rows,target.queryForList("SELECT row_to_json(t)::text FROM "+relation+" t ORDER BY row_to_json(t)::text",Map.of(),String.class));}var row=new java.util.LinkedHashMap<String,Object>(column);row.put("explicitlyCopiedTable",imported);row.put("rows",rows.size());row.put("copiedMaximum",maximum);row.put("metadata",metadata);row.put("businessRowsUnchanged",true);row.put("targetOnlySequenceAligned",imported);audit.add(row);
        }assertTrue(audit.stream().anyMatch(row->row.get("table_name").equals("shipment_event")&&Boolean.TRUE.equals(row.get("explicitlyCopiedTable"))));return audit;
    }


    protected PaymentFixture paymentFixture(String suffix,boolean zero) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "paymentFixture")) {
        suffix=suffix.toLowerCase(java.util.Locale.ROOT).replace('_','-');var d=orderDraft("d-"+suffix);var input=creationInput(d);
        var other=browseFixture("d-other-"+suffix,10000,10);UUID owner=(UUID)other.get("owner"),sku=(UUID)other.get("sku");
        var t=couponTemplate(owner,"두번째 쿠폰",5);var c=coupons().claim(d.memberId(),t.id());
        var lines=new java.util.ArrayList<>(input.lines());lines.add(quoteLine(sku,1));if(suffix.startsWith("a-")) { lines.set(0,quoteLine(lines.getFirst().skuId(),5)); }if(suffix.equals("g-totals")) { lines.set(0,quoteLine(lines.getFirst().skuId(),3));lines.set(1,quoteLine(sku,2)); }var selections=new java.util.ArrayList<>(input.coupons());selections.add(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),c.id()));
        if(suffix.startsWith("a-multi")) { UUID category=jdbc.queryForObject("SELECT p.category_id FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",lines.getFirst().skuId()),UUID.class);var products=context.getBean(com.example.marketplace.catalog.api.Products.class);var product=products.save(d.memberId(),null,new com.example.marketplace.catalog.api.Products.Input(category,"p09-"+suffix,"같은 매대 두 번째 상품","","DRAFT",0L));var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);var option=skus.list(d.memberId(),product.id(),0).items().getFirst();skus.save(d.memberId(),product.id(),option.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),10000L,true,option.version()));jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand) VALUES(:id,10)",Map.of("id",option.id()));products.save(d.memberId(),product.id(),new com.example.marketplace.catalog.api.Products.Input(category,product.slug(),product.title(),"","ACTIVE",0L));lines.add(quoteLine(option.id(),3)); }
        if(zero) {
            // Fixture creates genuine free shipping + full-value coupons before the trusted quote/order flow.
            for(var selection:selections) { jdbc.update("UPDATE marketplace.issued_coupon SET value=10000 WHERE id=:id",Map.of("id",selection.couponId())); }
            for(UUID seller:List.of(d.memberId(),owner)) {
                var now=Instant.now();var pi=new com.example.marketplace.promotion.api.Promotions.Input("무료 배송","SHIPPING",List.of(),now.minusSeconds(10),now.plusSeconds(3600),"FREE",0L,null,"DRAFT",0L);
                var p=promotions().save(seller,null,pi);promotions().save(seller,p.id(),new com.example.marketplace.promotion.api.Promotions.Input("무료 배송","SHIPPING",List.of(),pi.startsAt(),pi.endsAt(),"FREE",0L,null,"ACTIVE",0L));
            }
        }
        if(suffix.startsWith("a-")) { jdbc.update("UPDATE marketplace.issued_coupon SET value=1 WHERE id=:id",Map.of("id",selections.getFirst().couponId())); }
        var q=quotes().calculate(d.memberId(),lines,selections);if(zero) { assertEquals(0,q.total()); }
        var saved=creation().create(d.purchaseSessionId().toString(),d.memberId(),null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input(input.requestKey(),lines,selections,q.fingerprint(),input.contact(),null,0));
        var browser=authBrowser();String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
        String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id",Map.of("id",d.memberId().toString()),String.class);
        Long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),Long.class);
        return new PaymentFixture(saved,browser,new com.example.marketplace.payment.api.Payments.Authority(session,d.memberId(),version,null));
    
        }
    }
    protected com.example.marketplace.payment.infrastructure.PaymentCoordinator payments() { return context.getBean(com.example.marketplace.payment.infrastructure.PaymentCoordinator.class); }
    protected com.example.marketplace.payment.infrastructure.PaymentCoordinator paymentWith(Clock clock,com.example.marketplace.payment.infrastructure.PaymentCoordinator.Checkpoint checkpoint) {
        return new com.example.marketplace.payment.infrastructure.PaymentCoordinator(jdbc,orders(),reservations(),couponReservations(),context.getBean(com.example.marketplace.payment.api.PaymentProvider.class),context.getBean(com.example.marketplace.platform.api.Jobs.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.identity.api.PurchaseSessions.class),clock,checkpoint);
    }
    protected void mockPayment(PaymentFixture f,String approval,String cancellation) { jdbc.update("INSERT INTO marketplace.payment_mock_setting(order_id,approval,cancellation) VALUES(:id,:a,:c)",Map.of("id",f.order().id(),"a",approval,"c",cancellation)); }
    protected Map<String,Object> paymentAttempt(PaymentFixture f) { return jdbc.queryForMap("SELECT * FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id())); }
    protected void paymentQuery(PaymentFixture f,boolean compensation) { var a=paymentAttempt(f);payments().query(f.order().orderNumber(),((Number)a.get("round")).intValue(),((Number)a.get("query_count")).intValue(),compensation); }
    protected void paymentStates(PaymentFixture f,String order,String inventory,String coupon) {
        assertEquals(order,orders().find(f.order().orderNumber()).state());var ids=orders().reservations(f.order().orderNumber());assertEquals(2,ids.inventory().size());assertEquals(2,ids.coupons().size());
        assertTrue(reservations().inspect(f.order().checkoutId(),ids.inventory()).stream().allMatch(r->r.status().equals(inventory)));
        assertTrue(couponReservations().inspect(f.order().checkoutId(),ids.coupons()).stream().allMatch(r->r.state().equals(coupon)));
    }
    protected Map<String,String> paymentParticipantState() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "paymentParticipantState")) { var state=new java.util.TreeMap<>(orderParticipantState());for(String table:List.of("payment_attempt","payment_event","payment_toss_binding","payment_webhook_receipt")) { state.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class)); }return state; 
        }
    }












    protected com.example.marketplace.order.infrastructure.OrderCreationService creation() { return context.getBean(com.example.marketplace.order.infrastructure.OrderCreationService.class); }
    protected com.example.marketplace.order.infrastructure.OrderCreationService.Input creationInput(com.example.marketplace.order.api.Orders.Draft d) {
        return new com.example.marketplace.order.infrastructure.OrderCreationService.Input(d.requestKey(),d.quote().stores().stream().flatMap(s->s.lines().stream()).map(l->quoteLine(l.skuId(),l.quantity())).toList(),d.quote().stores().stream().filter(s->s.coupon()!=null).map(s->new com.example.marketplace.promotion.api.Quotes.CouponSelection(s.storeId(),s.coupon().id())).toList(),d.quote().fingerprint(),d.contact(),d.guestAttemptId(),0);
    }


    protected com.example.marketplace.order.infrastructure.OrderCreationService creationWith(com.example.marketplace.guest.api.GuestOrders guest,com.example.marketplace.order.infrastructure.OrderCreationService.Checkpoint probe) {
        return new com.example.marketplace.order.infrastructure.OrderCreationService(jdbc,orders(),quotes(),context.getBean(com.example.marketplace.catalog.api.PurchaseOffers.class),guest,context.getBean(com.example.marketplace.identity.api.SellerMembership.class),couponReservations(),context.getBean(com.example.marketplace.inventory.api.Reservations.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,"DEMO",probe);
    }
    protected Map<String,String> orderParticipantState() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "orderParticipantState")) {
        var result=new java.util.TreeMap<String,String>();
        for(String table:List.of("guest_checkout","guest_attempt","guest_buyer","guest_access_session","guest_order_reference","order_checkout_attempt","purchase_order","seller_order","order_item","order_unit","order_contact","order_reservation","order_event","inventory_balance","inventory_reservation","inventory_reservation_event","coupon_reservation_batch","issued_coupon","coupon_reservation","coupon_reservation_event","job","outbox_event")) {
            result.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));
        }
        return result;
    
        }
    }




    protected com.example.marketplace.order.infrastructure.OrderCreationService.Input orderInput(UUID buyer,List<com.example.marketplace.promotion.api.Quotes.LineRequest> lines,List<com.example.marketplace.promotion.api.Quotes.CouponSelection> selected,String key) {
        var q=quotes().calculate(buyer,lines,selected);
        return new com.example.marketplace.order.infrastructure.OrderCreationService.Input(key,lines,selected,q.fingerprint(),new com.example.marketplace.order.api.Orders.Contact("구매자","01012345678","수령인","01087654321","01234","서울","101호"),null,null);
    }












    protected static Process workerProcess(java.nio.file.Path folder,String phase,Instant now,String type,UUID id,boolean block) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("process-start", "workerProcess")) {
        var args=folder.resolve(phase+".args");var marker=folder.resolve(phase+"-effect.txt");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace('\\','/');
        java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.WorkerProcessProbe\n"+now+"\n"+type+"\n\""+marker.toString().replace('\\','/')+"\"\n"+block+"\n"+id+"\n");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile()).start();
        java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    
        }
    }
    protected static void awaitJobCondition(java.util.function.BooleanSupplier condition,String message) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("wait", "awaitJobCondition")) {
        long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(System.nanoTime()<end&&!condition.getAsBoolean()) { Thread.sleep(25); }assertTrue(condition.getAsBoolean(),message);
    
        }
    }


    static final class JobClock extends Clock {
        volatile Instant now=Instant.parse("2030-01-01T00:00:00Z");
        void advance(long seconds) { now=now.plusSeconds(seconds); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    protected com.example.marketplace.platform.infrastructure.JdbcJobs jobs(Clock clock) { return new com.example.marketplace.platform.infrastructure.JdbcJobs(jdbc,transactionManager,clock); }
    protected com.example.marketplace.platform.api.Jobs.Command jobCommand(String type,Instant due) { return new com.example.marketplace.platform.api.Jobs.Command(type,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"{}",due); }





    protected com.example.marketplace.order.api.Orders orders() { return context.getBean(com.example.marketplace.order.api.Orders.class); }
    protected com.example.marketplace.order.api.Orders.Draft orderDraft(String suffix) {
        var r=reservationRequest("order-"+suffix);var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),"key","b".repeat(64),r.memberId(),null,null,"DEMO",r.quoteSnapshot(),new com.example.marketplace.order.api.Orders.Contact("구매자","01012345678","수령인","01087654321","01234","서울시 테스트로","101호"),now,now.plusSeconds(600));
    }










    protected void couponBatchInsertLockOrder(boolean cross) throws Exception {
        String suffix=cross?"fk-cross":"fk-same";var a=reservationRequest(suffix+"-a");var b=reservationRequest(suffix+"-b");UUID left=cross?a.memberId():activeMember(suffix+"@example.com"),right=cross?b.memberId():left;var requests=new java.util.ArrayList<CouponReservations.Request>();
        for(var pair:List.of(Map.entry(left,b),Map.entry(right,a))){var source=pair.getValue();var c=coupons().claim(pair.getKey(),coupons().own(source.memberId(),source.issuedCouponId()).templateId());var q=quotes().calculate(pair.getKey(),List.of(quoteLine(source.quoteSnapshot().stores().getFirst().lines().getFirst().skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(c.storeId(),c.id())));requests.add(new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),pair.getKey(),c.storeId(),c.id(),q,source.expiresAt()));}
        var barrier=new java.util.concurrent.CyclicBarrier(2);var probe=new NamedParameterJdbcTemplate(jdbc.getJdbcTemplate()){@Override public int update(String sql,Map<String,?> params){int result=super.update(sql,params);if(sql.startsWith("INSERT INTO marketplace.coupon_reservation_batch(")){try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}}return result;}};var api=new com.example.marketplace.promotion.infrastructure.JdbcCouponReservations(probe,quotes(),coupons(),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.seller.api.Stores.class),authMapper,transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)){var futures=requests.stream().map(r->pool.submit(()->api.reserve(List.of(r)))).toList();for(var future:futures){assertEquals("RESERVED",future.get(10,TimeUnit.SECONDS).getFirst().state());}}
        for(var r:requests){assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_event WHERE reservation_id=:id",Map.of("id",r.reservationId()),Integer.class));assertEquals(1,coupons().own(r.memberId(),r.issuedCouponId()).version());}
    }





    protected List<CouponReservations.Request> reservationBatch(String suffix){
        var a=reservationRequest(suffix+"-a");var b=reservationRequest(suffix+"-b");var second=coupons().claim(a.memberId(),coupons().own(b.memberId(),b.issuedCouponId()).templateId());var lines=java.util.stream.Stream.of(a,b).flatMap(r->r.quoteSnapshot().stores().stream()).flatMap(s->s.lines().stream()).map(l->quoteLine(l.skuId(),l.quantity())).toList();var q=quotes().calculate(a.memberId(),lines,List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(a.storeId(),a.issuedCouponId()),new com.example.marketplace.promotion.api.Quotes.CouponSelection(b.storeId(),second.id())));
        return List.of(new CouponReservations.Request(a.reservationId(),a.checkoutId(),a.sellerOrderRef(),a.memberId(),a.storeId(),a.issuedCouponId(),q,a.expiresAt()),new CouponReservations.Request(b.reservationId(),a.checkoutId(),b.sellerOrderRef(),a.memberId(),b.storeId(),second.id(),q,a.expiresAt()));
    }
    protected List<String> reservationState(){
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "reservationState")) {var rows=new java.util.ArrayList<String>();for(String table:List.of("issued_coupon","coupon_reservation_batch","coupon_reservation","coupon_reservation_event","coupon_restoration")){rows.addAll(jdbc.getJdbcTemplate().queryForList("SELECT row_to_json(t)::text FROM marketplace."+table+" t ORDER BY row_to_json(t)::text",String.class));}return rows;
        }
    }



    @org.springframework.web.bind.annotation.RestController @org.springframework.context.annotation.Profile("c3-fixture")
    static class CouponFixture {
        private final CouponReservations reservations;private final com.example.marketplace.promotion.api.Quotes quotes;private final com.example.marketplace.promotion.api.Coupons coupons;private final NamedParameterJdbcTemplate jdbc;
        CouponFixture(CouponReservations reservations,com.example.marketplace.promotion.api.Quotes quotes,com.example.marketplace.promotion.api.Coupons coupons,NamedParameterJdbcTemplate jdbc){this.reservations=reservations;this.quotes=quotes;this.coupons=coupons;this.jdbc=jdbc;}
        record Input(String action,UUID couponId,UUID skuId,UUID reservationId,UUID causeId){}
        @org.springframework.web.bind.annotation.ExceptionHandler(com.example.marketplace.promotion.api.PromotionRejected.class)
        org.springframework.http.ResponseEntity<?> rejected(com.example.marketplace.promotion.api.PromotionRejected error){return org.springframework.http.ResponseEntity.status(error.status()).header("Cache-Control","no-store").body(Map.of("error","FIXTURE_REJECTED"));}
        @org.springframework.web.bind.annotation.PostMapping("/api/__test/coupon-reservations")
        Object change(java.security.Principal principal,@org.springframework.web.bind.annotation.RequestBody Input input){
            UUID member=UUID.fromString(principal.getName());
            if("reserve".equals(input.action())){var c=coupons.own(member,input.couponId());var q=quotes.calculate(member,List.of(new com.example.marketplace.promotion.api.Quotes.LineRequest(input.skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(c.storeId(),c.id())));return reservations.reserve(List.of(new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),member,c.storeId(),c.id(),q,Instant.now().plusSeconds(600)))).getFirst();}
            if(input.reservationId()==null){throw new com.example.marketplace.promotion.api.PromotionRejected(400);}var rows=jdbc.queryForList("SELECT checkout_id,seller_order_ref,coupon_id FROM marketplace.coupon_reservation WHERE id=:id AND member_id=:member",Map.of("id",input.reservationId(),"member",member));if(rows.isEmpty()){throw new com.example.marketplace.promotion.api.PromotionRejected(404);}var row=rows.getFirst();coupons.own(member,(UUID)row.get("coupon_id"));var ids=List.of(input.reservationId());UUID checkout=(UUID)row.get("checkout_id");
            return switch(input.action()){case "commit"->reservations.commit(checkout,ids).getFirst();case "release"->reservations.release(checkout,ids).getFirst();case "partial","full"->reservations.restore(new CouponReservations.RestoreRequest(input.causeId(),(UUID)row.get("seller_order_ref"),input.reservationId(),new CouponReservations.RefundCompletion(input.action().equals("full"),true)));default->throw new com.example.marketplace.promotion.api.PromotionRejected(400);};
        }
    }


    protected CouponReservations reservationClock(Clock clock){return new com.example.marketplace.promotion.infrastructure.JdbcCouponReservations(jdbc,quotes(),coupons(),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.seller.api.Stores.class),authMapper,transactionManager,clock);}
    protected void awaitCouponLock(String table){
        try (var contractTimingScope = ContractTiming.measure("wait", "awaitCouponLock")) {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE :q OR query LIKE :q2))",Map.of("q","%"+table+"%FOR UPDATE%","q2","%"+table+"%FOR NO KEY UPDATE%"),Boolean.class));if(waiting){break;}try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}assertTrue(waiting,"actual lock wait on "+table);
        }
    }




    protected com.example.marketplace.promotion.api.CouponReservations couponReservations(){return context.getBean(com.example.marketplace.promotion.api.CouponReservations.class);}
    protected com.example.marketplace.promotion.api.CouponReservations.Request reservationRequest(String suffix){
        var f=browseFixture("reserve-"+suffix,10000,10);UUID owner=(UUID)f.get("owner"),sku=(UUID)f.get("sku");var t=couponTemplate(owner,"예약 쿠폰",5);var c=coupons().claim(owner,t.id());var q=quotes().calculate(owner,List.of(quoteLine(sku,1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),c.id())));
        return new com.example.marketplace.promotion.api.CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),owner,t.storeId(),c.id(),q,Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }



    protected List<String> quoteReadOnlyState(){
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "quoteReadOnlyState")) {var result=new java.util.ArrayList<String>();for(String table:List.of("coupon_template","issued_coupon","coupon_event","inventory_balance","inventory_reservation","guest_checkout","guest_attempt")){result.addAll(jdbc.getJdbcTemplate().queryForList("SELECT row_to_json(t)::text FROM marketplace."+table+" t ORDER BY row_to_json(t)::text",String.class));}return result;
        }
    }



    protected com.example.marketplace.promotion.api.Quotes quotes() { return context.getBean(com.example.marketplace.promotion.api.Quotes.class); }
    protected com.example.marketplace.promotion.api.Quotes.LineRequest quoteLine(UUID sku,int quantity) { return new com.example.marketplace.promotion.api.Quotes.LineRequest(sku,quantity); }




    protected boolean couponAvailable(UUID member,UUID template) {
        for(int page=0;page<=10000;page++){var rows=coupons().available(member,page);if(rows.items().stream().anyMatch(t->t.id().equals(template))){return true;}if(!rows.hasMore()){return false;}}throw new IllegalStateException("PAGING_BOUND");
    }








    protected com.example.marketplace.promotion.api.Coupons coupons() { return context.getBean(com.example.marketplace.promotion.api.Coupons.class); }
    protected com.example.marketplace.promotion.api.Coupons.TemplateInput couponInput(String title,long value,long limit,String state,long version) {
        Instant start=Instant.now().minusSeconds(60),end=start.plusSeconds(7200);
        return new com.example.marketplace.promotion.api.Coupons.TemplateInput(title,"FIXED",value,null,100L,start,end,start,end,limit,state,version);
    }
    protected com.example.marketplace.promotion.api.Coupons.Template couponTemplate(UUID owner,String title,long limit) {
        var d=coupons().saveTemplate(owner,null,couponInput(title,100,limit,"DRAFT",0));return coupons().saveTemplate(owner,d.id(),couponInput(title,100,limit,"ACTIVE",0));
    }









    protected com.example.marketplace.promotion.api.Promotions promotions() { return context.getBean(com.example.marketplace.promotion.api.Promotions.class); }
    protected com.example.marketplace.promotion.api.Promotions.Input promotionInput(UUID product,String state,long version) {
        return new com.example.marketplace.promotion.api.Promotions.Input("행사", "PRODUCT",List.of(product),Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),"RATE",1000L,null,state,version);
    }


















    protected UUID guestRepeat(GuestFixture f) { f.clock.advance(60);f.service.create(f.browser);return guestVerified(f); }













    protected com.example.marketplace.guest.infrastructure.JdbcGuestRecovery recovery(GuestFixture f) {
        return new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,new com.example.marketplace.identity.infrastructure.SharedPasswordHashes(),f.provider,f.clock);
    }
    protected String resetGrant(GuestFixture f,com.example.marketplace.guest.infrastructure.JdbcGuestRecovery recovery,String order,String code) {
        UUID id=recovery.start(f.browser,order,f.phone,code,f.source).attemptId();
        assertEquals("custom-proof",recovery.exchange(f.browser,id,"123456"));return recovery.verify(f.browser,id,"valid-proof");
    }









    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("c3-fixture")
    static class GuestFixtureSecurity {
        @org.springframework.context.annotation.Bean @org.springframework.core.annotation.Order(0)
        org.springframework.security.web.SecurityFilterChain guestFixtureChain(org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.securityMatcher("/__test/guest-order","/__test/guest-recovery-order","/__test/guest-linked-order","/api/__test/shipments/**","/api/__test/purchase/**","/api/__test/confirmations/**","/api/__test/cancellations/**","/api/__test/returns/**","/api/__test/exchanges/**","/api/__test/refunds/**").csrf(csrf->csrf.csrfTokenRepository(new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository()))
                    .authorizeHttpRequests(auth->auth.anyRequest().permitAll()).build();
        }
    }
    @org.springframework.web.bind.annotation.RestController @org.springframework.context.annotation.Profile("c3-fixture")
    static class GuestOrderFixture {
        private final com.example.marketplace.guest.infrastructure.JdbcGuestAccess access;
        private final TransactionTemplate tx;
        private final NamedParameterJdbcTemplate fixtureJdbc;
        @Autowired private com.example.marketplace.guest.infrastructure.JdbcGuestRecovery recovery;
        GuestOrderFixture(com.example.marketplace.guest.infrastructure.JdbcGuestAccess access,PlatformTransactionManager manager,NamedParameterJdbcTemplate jdbc){this.access=access;this.tx=new TransactionTemplate(manager);this.fixtureJdbc=jdbc;}
        @org.springframework.web.bind.annotation.ExceptionHandler(com.example.marketplace.guest.api.GuestRejected.class)
        org.springframework.http.ResponseEntity<?> rejected(com.example.marketplace.guest.api.GuestRejected error){return org.springframework.http.ResponseEntity.status(error.status()).header("Cache-Control","no-store").body(Map.of("error","FIXTURE_REJECTED"));}
        record Historical(String phone,String password,String orderNumber) { }
        @org.springframework.web.bind.annotation.PostMapping("/__test/guest-recovery-order")
        Map<String,String> historical(@org.springframework.web.bind.annotation.RequestBody Historical input){
            String phone=com.example.marketplace.phone.api.PhoneVerifications.normalize(input.phone());
            new com.example.marketplace.guest.api.GuestPassword(input.password(),input.password());
            String hash=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes().encode(input.password());
            return tx.execute(status->{UUID buyer=UUID.randomUUID();fixtureJdbc.update("INSERT INTO marketplace.guest_buyer(id,buyer_name,verified_phone,password_hash,created_at) VALUES(:b,'Historical fixture',:p,:h,now()-interval '1 day')",Map.of("b",buyer,"p",phone,"h",hash));access.registerOrder(buyer,input.orderNumber());return Map.of("orderNumber",input.orderNumber(),"recoveryCode",recovery.initialCode(buyer));});
        }
        record LinkedInput(UUID attemptId,String orderNumber,boolean loseResponse) { }
        @org.springframework.web.bind.annotation.PostMapping("/__test/guest-linked-order")
        Map<String,String> linked(jakarta.servlet.http.HttpSession session,@org.springframework.web.bind.annotation.RequestBody LinkedInput input,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token){
            Map<String,String> result=tx.execute(status->{String order=input.orderNumber()==null?"FIXTURE-"+UUID.randomUUID():input.orderNumber();boolean fresh="NEW".equals(access.linkStatus(session.getId(),input.attemptId(),token).mode());UUID buyer=access.attachOrder(session.getId(),input.attemptId(),token,order);return fresh?Map.of("orderNumber",order,"recoveryCode",recovery.initialCode(buyer)):Map.of("orderNumber",order);});
            if(input.loseResponse()){throw new com.example.marketplace.guest.api.GuestRejected(503);}return result;
        }
        record Input(UUID attemptId) { }
        @org.springframework.web.bind.annotation.PostMapping("/__test/guest-order")
        Map<String,String> create(jakarta.servlet.http.HttpSession session,@org.springframework.web.bind.annotation.RequestBody Input input){
            return tx.execute(status->{UUID buyer=access.createBuyer(session.getId(),input.attemptId());String order="FIXTURE-"+UUID.randomUUID();access.registerOrder(buyer,order); UUID other=UUID.randomUUID(); fixtureJdbc.update("INSERT INTO marketplace.guest_buyer(id,buyer_name,verified_phone,password_hash,created_at) SELECT :other,buyer_name,verified_phone,password_hash,created_at FROM marketplace.guest_buyer WHERE id=:id",Map.of("other",other,"id",buyer)); String otherOrder="FIXTURE-"+UUID.randomUUID();access.registerOrder(other,otherOrder);return Map.of("orderNumber",order,"otherOrderNumber",otherOrder,"recoveryCode",recovery.initialCode(buyer));});
        }
    }

    protected static final String GUEST_PASSWORD="  guest lookup 密碼  ";
    protected com.example.marketplace.guest.infrastructure.JdbcGuestAccess guestAccess(GuestFixture f) {
        return new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,new com.example.marketplace.identity.infrastructure.SharedPasswordHashes(),f.clock,true);
    }
    protected UUID guestVerified(GuestFixture f) { UUID id=f.issue(); f.exchange(id); f.verify(id); return id; }
    protected UUID guestBuyer(GuestFixture f,com.example.marketplace.guest.infrastructure.JdbcGuestAccess access,String order) {
        UUID id=guestVerified(f); access.prepare(f.browser,id,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        return new TransactionTemplate(transactionManager).execute(status->{UUID buyer=access.createBuyer(f.browser,id);access.registerOrder(buyer,order);return buyer;});
    }









    protected static final class GuestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-25T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    protected static final class GuestProvider implements com.example.marketplace.phone.api.PhoneVerifications {
        private final GuestClock clock;
        private final java.util.concurrent.ConcurrentHashMap<String,String> phones = new java.util.concurrent.ConcurrentHashMap<>();
        Runnable hook = () -> { };
        Runnable issueHook = () -> { }, exchangeHook = () -> { };
        boolean broken;
        GuestProvider(GuestClock clock) { this.clock = clock; }
        @Override public Request issue(String phone) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            if (broken) { throw new IllegalStateException("provider offline"); }
            issueHook.run(); String id = UUID.randomUUID().toString(); phones.put(id,phone);
            return new Request(id,"private-session",clock.instant().plusSeconds(300));
        }
        @Override public String exchange(String id,String code) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            if (!"123456".equals(code)) { throw new IllegalStateException("wrong code"); }
            exchangeHook.run(); return "custom-proof";
        }
        @Override public Proof confirm(String id,String token) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            hook.run();
            if (!"valid-proof".equals(token)) { throw new IllegalStateException("wrong token"); }
            return new Proof(phones.get(id),clock.instant());
        }
    }
    protected final class GuestFixture {
        final GuestClock clock = new GuestClock();
        final GuestProvider provider = new GuestProvider(clock);
        final com.example.marketplace.guest.infrastructure.JdbcGuestPurchases service = new com.example.marketplace.guest.infrastructure.JdbcGuestPurchases(jdbc,transactionManager,clock,provider);
        final String browser, source = UUID.randomUUID().toString();
        final String phone = String.format("010%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),100000000));
        final String token;
        final com.example.marketplace.guest.api.GuestInput input = new com.example.marketplace.guest.api.GuestInput("가상 구매자",phone,"PURCHASE");
        GuestFixture() { this(UUID.randomUUID().toString(),null); }
        GuestFixture(String browser,Instant current) { this.browser=browser;if(current!=null) {clock.now=current;}this.token=service.create(browser); }
        UUID issue() { return service.issue(browser,token,input,source).attemptId(); }
        void exchange(UUID id) { assertEquals("custom-proof",service.exchange(browser,token,id,phone,"PURCHASE","123456")); }
        void verify(UUID id) { service.verify(browser,token,id,phone,"PURCHASE","valid-proof"); }
    }








    protected com.example.marketplace.cart.api.Carts carts() {
        assertEquals(1,context.getBeansOfType(com.example.marketplace.cart.api.Carts.class).size(),"Cart implementation required");
        return context.getBean(com.example.marketplace.cart.api.Carts.class);
    }





    protected Map<String,Object> browseFixture(String suffix,long price,long stock) {
        var f=skuFixture("browse-"+suffix);UUID owner=(UUID)f.get("owner");var p=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,p.id(),0).items().getFirst().id();
        jdbc.update("UPDATE marketplace.catalog_product SET state='ACTIVE',title=:title WHERE id=:id",Map.of("id",p.id(),"title","P05 "+suffix));
        jdbc.update("UPDATE marketplace.catalog_sku SET active=true,price=:price WHERE id=:id",Map.of("id",sku,"price",price));
        jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand) VALUES(:sku,:stock)",Map.of("sku",sku,"stock",stock));
        return Map.of("owner",owner,"product",p,"sku",sku);
    }


    protected com.example.marketplace.claim.api.Exchanges.Receipt exchangeRequest(ReturnParties f,String key,List<com.example.marketplace.fulfillment.api.Shipments.Unit> selected) {
        var detail=context.getBean(com.example.marketplace.order.api.OrderQueries.class).detail(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()));var units=selected.stream().map(u->new com.example.marketplace.claim.api.Exchanges.Unit(u.orderItemId(),u.ordinal(),detail.stores().stream().flatMap(store->store.items().stream()).filter(i->i.id().equals(u.orderItemId())).findFirst().orElseThrow().skuId(),com.example.marketplace.claim.api.Exchanges.Responsibility.BUYER)).toList();return context.getBean(com.example.marketplace.claim.api.Exchanges.class).request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),new com.example.marketplace.claim.api.Exchanges.Request(key,confirmations().get(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer())).revision(),units,"actual same-store exchange request"));
    }
    protected Map<String,List<String>> exchangeParticipants() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "exchangeParticipants")) {
        var result=new java.util.LinkedHashMap<String,List<String>>();for(String table:List.of("order_physical_unit","order_exchange_application","order_exchange_unit","order_physical_source","order_physical_current","order_exchange_physical_unit","order_delivery_bucket","order_basic_delivery_bucket","order_physical_delivery","claim_exchange_request","claim_exchange_unit","claim_exchange_history","claim_exchange_command","claim_exchange_collection","claim_exchange_inspection","claim_exchange_inspection_unit","inventory_exchange_reservation","inventory_exchange_event","inventory_exchange_reservation_physical_source","inventory_committed_unit","inventory_purchase_source","inventory_exchange_source","inventory_source_restitution","inventory_physical_inbound","inventory_physical_inbound_unit","inventory_restitution","inventory_restitution_unit","inventory_restitution_movement","inventory_balance","order_unit_lifecycle","order_unit_hold","order_hold_unit","order_lifecycle_event","order_confirmation_state","claim_exchange_dispatch_request","inventory_exchange_commit","fulfillment_exchange_shipment","fulfillment_exchange_event","fulfillment_exchange_allocation","fulfillment_exchange_progress_schedule","fulfillment_exchange_progress_effect","claim_exchange_dispatch_effect","order_exchange_dispatch","order_exchange_delivery_bucket","order_exchange_completion","order_exchange_completion_unit","claim_exchange_completion","order_physical_confirmation_schedule","claim_exchange_extension","inventory_exchange_extension","inventory_exchange_extension_unit","claim_exchange_approval_generation","inventory_exchange_approval","claim_exchange_reservation_schedule","claim_exchange_expiry_delivery","inventory_exchange_release","inventory_exchange_release_unit","outbox_event","job","job_claim_fact")){result.put(table,jdbc.queryForList("SELECT to_jsonb(r)::text FROM marketplace."+table+" r ORDER BY to_jsonb(r)::text",Map.of(),String.class));}return result;
    
        }
    }









    protected UUID actualExchangeTarget(ReturnParties f,String suffix) {
        UUID owner=f.seller().authority().member();var line=f.buyer().order().quote().stores().getFirst().lines().getFirst();var products=context.getBean(com.example.marketplace.catalog.api.Products.class);var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);var original=products.own(owner,line.productId());var target=products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(original.categoryId(),"e-target-"+suffix,"actual replacement "+suffix,"actual seller configured same gross price target","DRAFT",0L));var sku=skus.list(owner,target.id(),0).items().getFirst();skus.save(owner,target.id(),sku.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),line.unitPrice(),true,sku.version()));var current=products.own(owner,target.id());products.save(owner,target.id(),new com.example.marketplace.catalog.api.Products.Input(current.categoryId(),current.slug(),current.title(),current.description(),"ACTIVE",current.version()));var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);var balance=inventory.list(owner,target.id(),false,0).items().getFirst();inventory.adjust(owner,target.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(5L,balance.version(),"actual stock for replacement dispatch",UUID.randomUUID()));return sku.id();
    }


    protected record DeliveredExchangeFixture(ReturnParties parties,com.example.marketplace.claim.api.Exchanges.Request request,com.example.marketplace.claim.api.Exchanges.Receipt accepted,com.example.marketplace.order.api.PhysicalOrders.Source replacement) { }
    protected com.example.marketplace.order.api.PhysicalOrders.Source exchangeCurrent(UUID item,int ordinal) {
        return new TransactionTemplate(transactionManager).execute(t->{UUID id=jdbc.queryForObject("SELECT physical_id FROM marketplace.order_physical_current WHERE item_id=:item AND ordinal=:ordinal",Map.of("item",item,"ordinal",ordinal),UUID.class);return context.getBean(com.example.marketplace.order.api.PhysicalOrders.class).recorded(id);});
    }
    protected DeliveredExchangeFixture deliveredExchange(String suffix) throws Exception {
        return deliveredExchange(suffix,null);
    }
    protected DeliveredExchangeFixture deliveredExchange(String suffix,String originalPolicyFee) throws Exception {
        var f=returnParties(suffix,true,1,originalPolicyFee);var raw=f.shipment().units().getFirst();var buyer=shipmentAuthority(f.buyer());String number=f.buyer().order().orderNumber();UUID target=actualExchangeTarget(f,suffix);var request=new com.example.marketplace.claim.api.Exchanges.Request("delivered-request-"+suffix,confirmations().get(number,buyer).revision(),List.of(new com.example.marketplace.claim.api.Exchanges.Unit(raw.orderItemId(),raw.ordinal(),target,com.example.marketplace.claim.api.Exchanges.Responsibility.BUYER)),"actual initial exchange before replacement-source boundary");var accepted=context.getBean(com.example.marketplace.claim.api.Exchanges.class).request(number,buyer,request);finishActualExchange(f,accepted);return new DeliveredExchangeFixture(f,request,accepted,exchangeCurrent(raw.orderItemId(),raw.ordinal()));
    }
    protected void finishActualExchange(ReturnParties f,com.example.marketplace.claim.api.Exchanges.Receipt accepted) throws Exception {
        var seller=shipmentAuthority(f.seller());var api=context.getBean(com.example.marketplace.claim.api.Exchanges.class);var physical=context.getBean(com.example.marketplace.claim.api.ExchangePhysical.class);api.approve(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.Exchanges.Command("finish-approved",0L,"actual original gross price and stock approval"));physical.receive(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.CollectionCommand("finish-received",1L,accepted.units().stream().map(u->new com.example.marketplace.claim.api.ExchangePhysical.Received(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Status.RECEIVED)).toList(),"actual predecessor received"));physical.inspect(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.InspectionCommand("finish-inspected",2L,accepted.units().stream().map(u->new com.example.marketplace.claim.api.ExchangePhysical.Inspected(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Condition.SALEABLE,com.example.marketplace.claim.api.Exchanges.Responsibility.BUYER)).toList(),"actual predecessor saleable inspection"));context.getBean(com.example.marketplace.claim.api.ExchangeDispatches.class).request(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.Exchanges.Command("finish-dispatch",3L,"actual outgoing dedicated stock"));UUID job=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='EXCHANGE_RESHIP.v1' AND cause_key=:id",Map.of("id",accepted.exchangeId().toString()),UUID.class);runExchangeActualJob(job);UUID shipment=jdbc.queryForObject("SELECT id FROM marketplace.fulfillment_exchange_shipment WHERE exchange_id=:id",Map.of("id",accepted.exchangeId()),UUID.class);for(int version=2;version<=3;version++){runExchangeActualJob(jdbc.queryForObject("SELECT job_id FROM marketplace.fulfillment_exchange_progress_schedule WHERE shipment_id=:id AND expected_version=:version",Map.of("id",shipment,"version",version),UUID.class));}runExchangeActualJob(jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='EXCHANGE_DELIVERED.v1' AND cause_key=:id",Map.of("id",accepted.exchangeId().toString()),UUID.class));assertEquals("COMPLETED",api.get(f.buyer().order().orderNumber(),accepted.exchangeId(),shipmentAuthority(f.buyer())).state());
    }



    protected record PendingExchangeFixture(ReturnParties parties,com.example.marketplace.claim.api.Exchanges.Receipt accepted,UUID jobId,UUID targetSku) { }
    protected PendingExchangeFixture pendingActualExchange(String suffix) throws Exception {
        return pendingActualExchange(suffix,com.example.marketplace.claim.api.Exchanges.Responsibility.BUYER);
    }
    protected PendingExchangeFixture pendingActualExchange(String suffix,com.example.marketplace.claim.api.Exchanges.Responsibility responsibility) throws Exception {
        var f=returnParties(suffix,true,1);var raw=f.shipment().units().getFirst();var buyer=shipmentAuthority(f.buyer());UUID target=actualExchangeTarget(f,suffix);String number=f.buyer().order().orderNumber();var api=context.getBean(com.example.marketplace.claim.api.Exchanges.class);var accepted=api.request(number,buyer,new com.example.marketplace.claim.api.Exchanges.Request("pending-request",confirmations().get(number,buyer).revision(),List.of(new com.example.marketplace.claim.api.Exchanges.Unit(raw.orderItemId(),raw.ordinal(),target,responsibility)),"actual pending replacement for job safety boundary"));var seller=shipmentAuthority(f.seller());observeActualExchangeApproval(api,accepted.exchangeId(),seller,new com.example.marketplace.claim.api.Exchanges.Command("pending-approved",0L,"actual seller price and stock approval"));var physical=context.getBean(com.example.marketplace.claim.api.ExchangePhysical.class);physical.receive(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.CollectionCommand("pending-received",1L,List.of(new com.example.marketplace.claim.api.ExchangePhysical.Received(raw.orderItemId(),raw.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Status.RECEIVED)),"actual predecessor received"));physical.inspect(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.InspectionCommand("pending-inspected",2L,List.of(new com.example.marketplace.claim.api.ExchangePhysical.Inspected(raw.orderItemId(),raw.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Condition.SALEABLE,responsibility)),"actual predecessor inspected"));context.getBean(com.example.marketplace.claim.api.ExchangeDispatches.class).request(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.Exchanges.Command("pending-reship",3L,"actual replacement dispatch requested"));UUID job=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='EXCHANGE_RESHIP.v1' AND cause_key=:id",Map.of("id",accepted.exchangeId().toString()),UUID.class);return new PendingExchangeFixture(f,accepted,job,target);
    }
    protected record OwnedExchangeJob(com.example.marketplace.platform.infrastructure.JdbcJobs jobs,com.example.marketplace.platform.api.Jobs.Job job) { }
    protected OwnedExchangeJob claimActualExchangeJob(UUID id,Instant at) {
        var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();var actualAt=java.util.stream.Stream.of(at,saved.nextRunAt().plusNanos(1000),jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant()).max(Instant::compareTo).orElseThrow();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(actualAt,ZoneOffset.UTC));var job=jobs.claim(java.util.Set.of(saved.command().type())).orElseThrow();assertEquals(id,job.id());return new OwnedExchangeJob(jobs,job);
    }
    protected void deliverPreparedExchange(PendingExchangeFixture f) throws Exception {
        UUID shipment=jdbc.queryForObject("SELECT id FROM marketplace.fulfillment_exchange_shipment WHERE exchange_id=:id",Map.of("id",f.accepted().exchangeId()),UUID.class);for(int version=2;version<=3;version++){runExchangeActualJob(jdbc.queryForObject("SELECT job_id FROM marketplace.fulfillment_exchange_progress_schedule WHERE shipment_id=:id AND expected_version=:version",Map.of("id",shipment,"version",version),UUID.class));}runExchangeActualJob(jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='EXCHANGE_DELIVERED.v1' AND cause_key=:id",Map.of("id",f.accepted().exchangeId().toString()),UUID.class));
    }
    protected void assertReshipCommitBeforeAckAfterCompletion(boolean hidden) throws Exception {
        var f=pendingActualExchange(hidden?"e-r04h":"e-r04c");var worker=context.getBean(com.example.marketplace.claim.infrastructure.JdbcExchangeDispatches.class);var original=claimActualExchangeJob(f.jobId(),Instant.now().plusSeconds(1));assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,worker.dispatch(original.job()));assertEquals("RUNNING",original.jobs().find(f.jobId()).orElseThrow().status(),"dispatch effect committed while ACK is deliberately omitted");deliverPreparedExchange(f);var parties=f.parties();String number=parties.buyer().order().orderNumber();var buyer=shipmentAuthority(parties.buyer());var unit=f.accepted().units().getFirst();confirmations().confirm(number,buyer,new com.example.marketplace.order.api.OrderConfirmations.Command("r04_closed",confirmations().get(number,buyer).revision(),List.of(new com.example.marketplace.order.api.OrderConfirmations.Unit(unit.itemId(),unit.ordinal()))));assertEquals("CLOSED",context.getBean(com.example.marketplace.order.api.OrderQueries.class).detail(number,buyer).state());
        if(hidden){var products=context.getBean(com.example.marketplace.catalog.api.Products.class);UUID product=jdbc.queryForObject("SELECT product_id FROM marketplace.catalog_sku WHERE id=:id",Map.of("id",f.targetSku()),UUID.class);UUID owner=parties.seller().authority().member();var prior=products.own(owner,product);products.save(owner,product,new com.example.marketplace.catalog.api.Products.Input(prior.categoryId(),prior.slug(),prior.title(),prior.description(),"ARCHIVED",prior.version()));var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);var store=stores.own(owner);stores.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(store.version(),"actual store close after committed exchange dispatch"));}
        var takeover=claimActualExchangeJob(f.jobId(),original.job().leaseUntil().plusNanos(1000));assertNotEquals(original.job().ownerToken(),takeover.job().ownerToken());var before=exchangeParticipants();Throwable failure=null;com.example.marketplace.platform.api.JobHandler.Result result=null;try{result=worker.dispatch(takeover.job());}catch(Throwable rejected){failure=rejected;}var after=exchangeParticipants();java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-r04-commit-before-ack-"+hidden+".json"),authMapper.writeValueAsString(Map.of("original",original.job(),"takeover",takeover.job(),"actualRejected",failure!=null,"actualError",failure==null?"NONE":failure.toString(),"before",before,"after",after)));assertNull(failure,"actual committed dispatch must replay after downstream completion/CLOSED/catalog change: "+failure);assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,result);assertEquals(before,after);assertTrue(takeover.jobs().complete(f.jobId(),takeover.job().ownerToken()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_dispatch_effect WHERE exchange_id=:id",Map.of("id",f.accepted().exchangeId()),Integer.class));assertEquals("SUCCEEDED",takeover.jobs().find(f.jobId()).orElseThrow().status());
    }


    protected void assertExchangeProgressForgery(String mode) throws Exception {
        var f=pendingActualExchange("e-r06"+mode.toLowerCase(java.util.Locale.ROOT));runExchangeActualJob(f.jobId());var shipment=jdbc.queryForMap("SELECT * FROM marketplace.fulfillment_exchange_shipment WHERE exchange_id=:id",Map.of("id",f.accepted().exchangeId()));UUID shipmentId=(UUID)shipment.get("id");var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("shipment",shipmentId).addValue("event",UUID.randomUUID()).addValue("at",java.sql.Timestamp.from(Instant.now()));
        if(!mode.equals("EVENT")){long actualVersion=mode.equals("JOB")?3:2;UUID job=jdbc.queryForObject("SELECT job_id FROM marketplace.fulfillment_exchange_progress_schedule WHERE shipment_id=:id AND expected_version=:version",Map.of("id",shipmentId,"version",actualVersion),UUID.class);var owned=claimActualExchangeJob(job,Instant.now().plusSeconds(1));p.addValue("job",job).addValue("owner",owned.job().ownerToken());}
        assertExchangeSqlMutationRejected("e-r06-"+mode,()->new TransactionTemplate(transactionManager).executeWithoutResult(t->{if(mode.equals("EFFECT")){UUID original=jdbc.queryForObject("SELECT id FROM marketplace.fulfillment_exchange_event WHERE shipment_id=:shipment AND version=1",p,UUID.class);p.addValue("event",original);}else{String state=mode.equals("STATE")?"DELIVERED":"IN_TRANSIT";p.addValue("state",state);jdbc.update("INSERT INTO marketplace.fulfillment_exchange_event(id,shipment_id,version,state,occurred_at) VALUES(:event,:shipment,2,:state,:at)",p);if(!mode.equals("EVENT")){jdbc.update("UPDATE marketplace.fulfillment_exchange_shipment SET state=:state,version=2,delivered_at=CASE WHEN :state='DELIVERED' THEN CAST(:at AS timestamptz) ELSE NULL END WHERE id=:shipment",p);}}if(!mode.equals("EVENT")){jdbc.update("INSERT INTO marketplace.fulfillment_exchange_progress_effect(shipment_id,version,event_id,job_id,owner_token) VALUES(:shipment,2,:event,:job,:owner)",p);}}),"23514","23503");
    }





    protected void assertNewReshipAuthorityAfterFinalInventoryWait(String mode) throws Exception {
        var f=pendingActualExchange("e-r05"+mode.toLowerCase(java.util.Locale.ROOT));UUID owner=f.parties().seller().authority().member();UUID admin=activeMember("e-r05-admin-"+mode.toLowerCase(java.util.Locale.ROOT)+"@example.com");jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'ADMIN')",Map.of("id",admin));jdbc.update("INSERT INTO marketplace.admin_permission(user_id,permission) VALUES(:id,'ACCOUNTS')",Map.of("id",admin));var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);var store=stores.own(owner);var original=new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.order.api.PhysicalOrders.class).application(f.accepted().exchangeId())).getFirst();UUID last=java.util.stream.Stream.of(original.stock().skuId(),f.targetSku()).sorted(java.util.Comparator.comparing(UUID::toString)).toList().getLast();var owned=claimActualExchangeJob(f.jobId(),Instant.now().plusSeconds(1));var worker=context.getBean(com.example.marketplace.claim.infrastructure.JdbcExchangeDispatches.class);var baseline=exchangeParticipants();var evidence=new java.util.LinkedHashMap<String,Object>();
        try(var pool=Executors.newFixedThreadPool(2);var connection=context.getBean(DataSource.class).getConnection()) { connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=? FOR UPDATE")){statement.setObject(1,last);statement.executeQuery().close();}var dispatched=pool.submit(()->{try{worker.dispatch(owned.job());return (Throwable)null;}catch(Throwable failure){return failure;}});awaitExchangeBusinessLock("inventory_balance","e-r05-"+mode);var changed=pool.submit(()->{if(mode.equals("ACCOUNT")){context.getBean(com.example.marketplace.identity.api.AccountStateOperations.class).suspend(admin,owner,"actual account suspension during new reship stock wait");}else{stores.transition(mode.equals("CLOSE")?owner:admin,store.id(),mode.equals("CLOSE")?"close":"suspend",new com.example.marketplace.seller.api.Stores.Change(store.version(),"actual store authority change during reship stock wait"));}return true;});List<Map<String,Object>> waits=List.of();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until&&!changed.isDone()){jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");waits=jdbc.queryForList("SELECT pid,query,pg_blocking_pids(pid)::text AS blockers FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock'",Map.of());if(waits.stream().anyMatch(r->r.get("query").toString().contains("marketplace.identity_user")||r.get("query").toString().contains("marketplace.seller_store"))){break;}Thread.sleep(20);}boolean authorityCommittedFirst=changed.isDone();if(authorityCommittedFirst){assertTrue(changed.get(10,TimeUnit.SECONDS));}else{assertTrue(waits.stream().anyMatch(r->r.get("query").toString().contains("marketplace.identity_user")||r.get("query").toString().contains("marketplace.seller_store")),"real seller/store mutation must either finish before reship or be observed blocked by its transaction");}evidence.put("authorityCommittedBeforeInventoryRelease",authorityCommittedFirst);evidence.put("actualAuthorityWaits",waits);evidence.put("stateBeforeRelease",jdbc.queryForObject(mode.equals("ACCOUNT")?"SELECT state FROM marketplace.identity_user WHERE id=:id":"SELECT state FROM marketplace.seller_store WHERE id=:id",Map.of("id",mode.equals("ACCOUNT")?owner:store.id()),String.class));connection.commit();var failure=dispatched.get(10,TimeUnit.SECONDS);assertTrue(changed.get(10,TimeUnit.SECONDS));var after=exchangeParticipants();evidence.put("actualWorkerError",failure==null?"NONE":failure.toString());evidence.put("before",baseline);evidence.put("after",after);java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-r05-authority-"+mode+".json"),authMapper.writeValueAsString(evidence));if(authorityCommittedFirst){assertNotNull(failure,"new reship must reject authority lost during the actual final inventory wait");assertEquals(baseline,after);}else{assertNull(failure,"protected original OPEN reship must commit before the serialized authority change");assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_dispatch_effect WHERE exchange_id=:id",Map.of("id",f.accepted().exchangeId()),Integer.class));assertEquals("COMMITTED",jdbc.queryForObject("SELECT status FROM marketplace.inventory_exchange_reservation WHERE exchange_id=:id",Map.of("id",f.accepted().exchangeId()),String.class));}
        }
    }








    protected Map<String,String> actualReturnFeeParticipants() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "actualReturnFeeParticipants")) {
        var result=new java.util.TreeMap<>(returnApplicationParticipants());exchangeParticipants().forEach((key,rows)->result.put(key,rows.toString()));return result;
    
        }
    }
    protected PhysicalReturn inspectedActualFeeReturn(ReturnParties f,List<com.example.marketplace.fulfillment.api.Shipments.Unit> selected,String key) throws Exception {
        String number=f.buyer().order().orderNumber();var buyer=shipmentAuthority(f.buyer());var seller=shipmentAuthority(f.seller());var physical=context.getBean(com.example.marketplace.order.api.PhysicalOrders.class);var deliveries=new TransactionTemplate(transactionManager).execute(t->selected.stream().map(u->physical.current(f.buyer().order().id(),f.buyer().order().sellers().getFirst().id(),new com.example.marketplace.order.api.OrderConfirmations.Unit(u.orderItemId(),u.ordinal())).delivery().deliveredAt()).toList());var latest=deliveries.stream().max(Instant::compareTo).orElseThrow();assertNotNull(latest,"genuine saved delivery source remains mandatory; no wall-clock catch-up gate");
        var api=context.getBean(com.example.marketplace.claim.api.Returns.class);var accepted=api.request(number,buyer,new com.example.marketplace.claim.api.Returns.Request(key,confirmations().get(number,buyer).revision(),selected.stream().map(u->new com.example.marketplace.claim.api.Returns.Unit(u.orderItemId(),u.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)).toList(),"actual returned units in their real delivery group"));api.approve(accepted.returnId(),seller,new com.example.marketplace.claim.api.Returns.Command("approve-"+key,0L,"actual seller accepts return"));var received=context.getBean(com.example.marketplace.claim.api.ReturnCollections.class).receive(accepted.returnId(),seller,new com.example.marketplace.claim.api.ReturnCollections.Command("receive-"+key,1L,accepted.units().stream().map(u->new com.example.marketplace.claim.api.ReturnCollections.Unit(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)).toList(),"actual goods received"));var inspected=context.getBean(com.example.marketplace.claim.api.ReturnInspections.class).inspect(accepted.returnId(),seller,new com.example.marketplace.claim.api.ReturnInspections.Command("inspect-"+key,2L,accepted.units().stream().map(u->new com.example.marketplace.claim.api.ReturnInspections.Unit(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.BUYER)).toList(),"actual saleable buyer-responsible inspected goods"));assertEquals("AWAITING_CONSENT",inspected.state());return new PhysicalReturn(f,accepted,received);
    }
    protected com.example.marketplace.claim.api.ReturnFees.Quote actualFeeQuote(PhysicalReturn f) {
        return context.getBean(com.example.marketplace.claim.api.ReturnFinances.class).quote(f.parties().buyer().order().orderNumber(),f.accepted().returnId(),shipmentAuthority(f.parties().buyer()),3).feeQuote();
    }
    protected com.example.marketplace.claim.api.ReturnFinances.Accepted actualFeeConsent(PhysicalReturn f,com.example.marketplace.claim.api.ReturnFees.Quote quote,String key) {
        return context.getBean(com.example.marketplace.claim.api.ReturnFinances.class).consent(f.parties().buyer().order().orderNumber(),f.accepted().returnId(),shipmentAuthority(f.parties().buyer()),new com.example.marketplace.claim.api.ReturnFinances.Consent(key,3L,quote.id(),quote.fingerprint(),true,quote.legacyConsentRequired()));
    }
    protected void applyActualFee(PhysicalReturn f,com.example.marketplace.claim.api.ReturnFinances.Accepted consent) throws Exception {
        runCancellationJob("REFUND_SUBMIT.v1",consent.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",consent.refundAttemptId());runCancellationJob("RETURN_FINANCE_RESUME.v1",f.accepted().returnId());assertEquals("APPLIED",jdbc.queryForObject("SELECT state FROM marketplace.claim_fee_assessment WHERE id=:id",Map.of("id",consent.assessmentId()),String.class));
    }


    protected void exchangeActualRefundConversion(boolean sellerResponsible) throws Exception {
        var f=pendingActualExchange(sellerResponsible?"e-fcs":"e-fc",sellerResponsible?com.example.marketplace.claim.api.Exchanges.Responsibility.SELLER:com.example.marketplace.claim.api.Exchanges.Responsibility.BUYER);var parties=f.parties();var id=f.accepted().exchangeId();var buyer=parties.buyer();var authority=shipmentAuthority(buyer);var unit=f.accepted().units().getFirst();var api=context.getBean(com.example.marketplace.claim.api.Exchanges.class);String path="/api/orders/"+buyer.order().orderNumber()+"/exchanges/"+id;
        long net=jdbc.queryForObject("SELECT net FROM marketplace.order_unit WHERE item_id=:id AND ordinal=:ordinal",Map.of("id",unit.itemId(),"ordinal",unit.ordinal()),Long.class),reserved=jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",f.targetSku()),Long.class);var purchase=jdbc.queryForList("SELECT to_jsonb(r)::text FROM marketplace.inventory_reservation r WHERE checkout_id=:id ORDER BY id",Map.of("id",buyer.order().checkoutId()),String.class);var physical=jdbc.queryForList("SELECT to_jsonb(u)::text FROM marketplace.claim_exchange_inspection_unit u WHERE exchange_id=:id ORDER BY item_id,ordinal",Map.of("id",id),String.class);var balances=jdbc.queryForList("SELECT sku_id,on_hand FROM marketplace.inventory_balance WHERE sku_id IN (:ids) ORDER BY sku_id",Map.of("ids",List.of(unit.targetSkuId(),api.get(buyer.order().orderNumber(),id,authority).physical().getFirst().actualSkuId())));
        var quoted=accountRequest(buyer.browser(),"POST",path+"/refund-quote",Map.of("expectedRevision",4),csrfToken(buyer.browser()));assertEquals(200,quoted.statusCode(),"actual inspected EXCHANGE buyer refund quote must exist without forging a RETURN");var quote=authMapper.readTree(quoted.body()).get("quote");assertEquals(Long.toString(net),quote.get("net").asText());assertTrue(quote.get("refund").isTextual());assertTrue(quote.get("additionalDue").isTextual());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE order_id=:id",Map.of("id",buyer.order().id()),Integer.class));assertEquals(reserved,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",f.targetSku()),Long.class));
        var command=Map.of("key","explicit-refund-conversion","expectedRevision",4,"quoteId",quote.get("id").asText(),"quoteFingerprint",quote.get("fingerprint").asText(),"consent",true,"legacyConsent",quote.get("legacyConsentRequired").asBoolean(),"reason","buyer explicitly chooses refund after actual received and inspected exchange");var accepted=accountRequest(buyer.browser(),"POST",path+"/refund-consent",command,csrfToken(buyer.browser()));assertEquals(202,accepted.statusCode(),"actual exchange refund consent failed: "+accepted.body());var receipt=authMapper.readTree(accepted.body());UUID attempt=UUID.fromString(receipt.get("refundAttemptId").asText());assertEquals("REFUND_PENDING",receipt.get("progress").get("state").asText());assertEquals(5,receipt.get("progress").get("version").asLong());assertEquals("EXCHANGE_REFUND",jdbc.queryForObject("SELECT kind FROM marketplace.claim_financial_intent WHERE id=:id",Map.of("id",id),String.class));assertEquals(reserved-1,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",f.targetSku()),Long.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_exchange_release WHERE exchange_id=:id AND reason='REFUND_CONVERSION'",Map.of("id",id),Integer.class));assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM marketplace.order_unit_hold WHERE id=:id",Map.of("id",f.accepted().holdId()),String.class));
        var stable=exchangeRefundParticipants();assertEquals(receipt,authMapper.readTree(accountRequest(buyer.browser(),"POST",path+"/refund-consent",command,csrfToken(buyer.browser())).body()));assertEquals(stable,exchangeRefundParticipants());var old=claimActualExchangeJob(f.jobId(),Instant.now().plusSeconds(1));assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,exchangeDispatcherWith(old.jobs()).dispatch(old.job()));assertTrue(old.jobs().complete(old.job().id(),old.job().ownerToken()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.fulfillment_exchange_shipment WHERE exchange_id=:id",Map.of("id",id),Integer.class));
        runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);runCancellationJob("EXCHANGE_REFUND_RESUME.v1",id);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",buyer.order().sellers().getFirst().id());
        UUID sellerOrder=buyer.order().sellers().getFirst().id();
        if(sellerResponsible) {
            assertEquals("REFUND_PENDING",api.get(buyer.order().orderNumber(),id,authority).state(),"last seller-responsible conversion waits for actual original S settlement");assertEquals("PAID",context.getBean(com.example.marketplace.order.api.OrderQueries.class).detail(buyer.order().orderNumber(),authority).state());
            UUID shipping=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id AND claim_id=:origin",Map.of("id",sellerOrder,"origin",id),UUID.class);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_shipping_binding WHERE attempt_id=:id AND intent_id=:origin",Map.of("id",shipping,"origin",id),Integer.class));
            runCancellationJob("REFUND_SUBMIT.v1",shipping);runCancellationJob("REFUND_RESULT_RECORDED.v1",shipping);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",sellerOrder);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_aftercare_shipping WHERE seller_order_id=:id AND refund_attempt_id=:attempt AND cause_id=:origin",Map.of("id",sellerOrder,"attempt",shipping,"origin",id),Integer.class));
        }else { assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",Map.of("id",sellerOrder),Integer.class)); }
        assertEquals("CLOSED",context.getBean(com.example.marketplace.order.api.OrderQueries.class).detail(buyer.order().orderNumber(),authority).state());assertEquals("ALL_EXCHANGE_REFUNDED",jdbc.queryForObject("SELECT reason FROM marketplace.order_aftercare_closure WHERE order_id=:id",Map.of("id",buyer.order().id()),String.class));
        assertEquals("SUCCEEDED",new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.payment.api.Refunds.class).get(attempt).state()));assertEquals("APPLIED",jdbc.queryForObject("SELECT state FROM marketplace.claim_fee_assessment WHERE id=:id",Map.of("id",UUID.fromString(receipt.get("assessmentId").asText())),String.class));assertEquals("0",quote.get("additionalDue").asText(),"normal fixture must fully fund its stored fee");assertEquals("REFUNDED",api.get(buyer.order().orderNumber(),id,authority).state());assertEquals("REFUNDED",jdbc.queryForObject("SELECT state FROM marketplace.order_unit_hold WHERE id=:id",Map.of("id",f.accepted().holdId()),String.class));
        assertEquals(purchase,jdbc.queryForList("SELECT to_jsonb(r)::text FROM marketplace.inventory_reservation r WHERE checkout_id=:id ORDER BY id",Map.of("id",buyer.order().checkoutId()),String.class));assertEquals(physical,jdbc.queryForList("SELECT to_jsonb(u)::text FROM marketplace.claim_exchange_inspection_unit u WHERE exchange_id=:id ORDER BY item_id,ordinal",Map.of("id",id),String.class));assertEquals(balances,jdbc.queryForList("SELECT sku_id,on_hand FROM marketplace.inventory_balance WHERE sku_id IN (:ids) ORDER BY sku_id",Map.of("ids",List.of(unit.targetSkuId(),api.get(buyer.order().orderNumber(),id,authority).physical().getFirst().actualSkuId()))));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_request WHERE order_id=:id",Map.of("id",buyer.order().id()),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim WHERE order_id=:id",Map.of("id",buyer.order().id()),Integer.class));
    }

    protected Map<String,String> exchangeRefundParticipants() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "exchangeRefundParticipants")) {
        var rows=new java.util.TreeMap<>(actualReturnFeeParticipants());for(String table:List.of("claim_exchange_refund_fee_quote_context","claim_exchange_refund_consent","claim_exchange_refund_result","claim_exchange_refund_result_event","claim_financial_result","claim_exchange_refund_effect","claim_exchange_refund_item_ready","claim_exchange_refund_ready_unit","order_exchange_refund_completion","order_exchange_refund_completion_unit","claim_exchange_refund_completion","claim_exchange_refund_shipping_binding","claim_typed_shipping_binding","claim_exchange_refund_support_command","claim_exchange_shipping_support_command")){rows.put(table,jdbc.queryForList("SELECT to_jsonb(r)::text FROM marketplace."+table+" r ORDER BY to_jsonb(r)::text",Map.of(),String.class).toString());}return rows;
    
        }
    }


    protected void exchangeRefundSupportBoundary(String outcome) throws Exception {
        var f=pendingActualExchange(outcome.equals("FAILED")?"e-rff":"e-rfu");var buyer=f.parties().buyer();var authority=shipmentAuthority(buyer);var id=f.accepted().exchangeId();String number=buyer.order().orderNumber();var api=context.getBean(com.example.marketplace.claim.api.ExchangeRefunds.class);var quote=api.quote(number,id,authority,4).quote();var conversion=api.consent(number,id,authority,new com.example.marketplace.claim.api.ExchangeRefunds.Consent("first-buyer-consent",4L,quote.id(),quote.fingerprint(),true,quote.legacyConsentRequired(),"actual inspected original item, explicit buyer refund choice"));
        var originalConsent=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM marketplace.claim_exchange_refund_consent c WHERE exchange_id=:id",Map.of("id",id),String.class);jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM',:outcome)",Map.of("id",id,"outcome",outcome));
        runCancellationJob("REFUND_SUBMIT.v1",conversion.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",conversion.refundAttemptId());runCancellationJob("EXCHANGE_REFUND_RESUME.v1",id);
        var original=new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.payment.api.Refunds.class).get(conversion.refundAttemptId()));assertEquals(outcome,original.state());assertEquals("REVIEW_REQUIRED",context.getBean(com.example.marketplace.claim.api.Exchanges.class).get(number,id,authority).state());assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM marketplace.order_unit_hold WHERE id=:id",Map.of("id",f.accepted().holdId()),String.class));assertEquals(outcome.equals("FAILED")?0L:original.amount(),jdbc.queryForObject("SELECT reserved FROM marketplace.payment_refund_budget WHERE order_id=:id",Map.of("id",buyer.order().id()),Long.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_item_ready WHERE exchange_id=:id",Map.of("id",id),Integer.class));assertEquals("BOUND",jdbc.queryForObject("SELECT state FROM marketplace.claim_fee_assessment WHERE id=:id",Map.of("id",conversion.assessmentId()),String.class));
        var operator=supportFixture(outcome.equals("FAILED")?"e-rff-op":"e-rfu-op","SUPPORT");String base="/api/admin/support/orders/"+number+"/exchanges/"+id+"/refunds/ITEM/",action=outcome.equals("FAILED")?"retry":"query";var body=Map.of("key","explicit-support-command","expectedRevision",6,"reason","current support examines definitive provider evidence and recorded buyer consent");
        var response=accountRequest(operator.browser(),"POST",base+action,body,csrfToken(operator.browser()));assertEquals(202,response.statusCode(),"typed E support command must use actual E intent without pretending RETURN: "+response.body());var receipt=authMapper.readTree(response.body());UUID attempt=UUID.fromString(receipt.path("refundAttemptId").asText());assertEquals(outcome.equals("FAILED")?"RETRY_FAILED":"QUERY_UNKNOWN",receipt.path("action").asText());assertEquals(7,receipt.path("progress").path("version").asLong());assertEquals(outcome.equals("FAILED")?"REFUND_PENDING":"REVIEW_REQUIRED",receipt.path("progress").path("state").asText());assertEquals(outcome.equals("UNKNOWN"),attempt.equals(original.id()));
        var stable=exchangeRefundParticipants();assertEquals(response.body(),accountRequest(operator.browser(),"POST",base+action,body,csrfToken(operator.browser())).body());assertEquals(stable,exchangeRefundParticipants());var changed=new java.util.HashMap<String,Object>(body);changed.put("reason","same key changed reason");assertEquals(409,accountRequest(operator.browser(),"POST",base+action,changed,csrfToken(operator.browser())).statusCode());assertEquals(stable,exchangeRefundParticipants());assertEquals(403,accountRequest(buyer.browser(),"POST",base+action,body,csrfToken(buyer.browser())).statusCode());assertEquals(stable,exchangeRefundParticipants());assertEquals(403,accountRequest(operator.browser(),"POST",base+action,body,null).statusCode());assertEquals(stable,exchangeRefundParticipants());
        if(outcome.equals("UNKNOWN")) { var denied=new java.util.HashMap<String,Object>(body);denied.put("key","unknown-no-new-attempt");denied.put("expectedRevision",7);assertEquals(409,accountRequest(operator.browser(),"POST",base+"retry",denied,csrfToken(operator.browser())).statusCode());assertEquals(stable,exchangeRefundParticipants());jdbc.update("UPDATE marketplace.payment_refund_mock SET state='SUCCEEDED',reference=:ref,effects=1 WHERE attempt_id=:id",Map.of("id",attempt,"ref","DEMO-REFUND-"+attempt)); }
        else { assertFalse(attempt.equals(original.id()));jdbc.update("UPDATE marketplace.payment_refund_mock_setting SET behavior='SUCCESS' WHERE claim_id=:id AND purpose='ITEM'",Map.of("id",id)); }
        runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);runCancellationJob("EXCHANGE_REFUND_RESUME.v1",id);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",buyer.order().sellers().getFirst().id());assertEquals("REFUNDED",context.getBean(com.example.marketplace.claim.api.Exchanges.class).get(number,id,authority).state());assertEquals("CLOSED",context.getBean(com.example.marketplace.order.api.OrderQueries.class).detail(number,authority).state());assertEquals(originalConsent,jdbc.queryForObject("SELECT to_jsonb(c)::text FROM marketplace.claim_exchange_refund_consent c WHERE exchange_id=:id",Map.of("id",id),String.class));assertEquals(1L,jdbc.queryForObject("SELECT sum(effects) FROM marketplace.payment_refund_mock WHERE attempt_id IN(SELECT id FROM marketplace.payment_refund_attempt WHERE claim_id=:id)",Map.of("id",id),Long.class));stable=exchangeRefundParticipants();assertEquals(response.body(),accountRequest(operator.browser(),"POST",base+action,body,csrfToken(operator.browser())).body());assertEquals(stable,exchangeRefundParticipants());
    }

    protected void exchangeRefundAwaitParentChain(int pid,int count,List<? extends java.util.concurrent.Future<?>> futures,String label) throws Exception {
        awaitJobCondition(()->{jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");var waits=jdbc.queryForList("WITH RECURSIVE blocked(pid) AS(SELECT CAST(:pid AS integer) UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid=ANY(pg_blocking_pids(a.pid)) WHERE a.datname=current_database()) SELECT DISTINCT a.pid,a.query,a.wait_event_type,pg_blocking_pids(a.pid)::text AS blockers FROM pg_stat_activity a JOIN blocked b ON b.pid=a.pid WHERE a.datname=current_database() AND a.pid<>:pid",Map.of("pid",pid));try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),label+".ndjson"),authMapper.writeValueAsString(Map.of("blocker",pid,"actualWaitChain",waits,"futures",futures.stream().map(this::recoveryFutureState).toList()))+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}catch(java.io.IOException error){throw new IllegalStateException(error);}return waits.size()>=count||futures.stream().anyMatch(java.util.concurrent.Future::isDone);},"actual exchange refund/dispatch commands must reach their canonical parent lock chain");for(var future:futures){assertFalse(future.isDone(),recoveryFutureState(future));}
    }
    protected record ExchangeFinancialCase(PendingExchangeFixture physical,com.example.marketplace.claim.api.ExchangeRefunds.Accepted conversion) { }
    protected ExchangeFinancialCase exchangeRefundAccepted(String suffix,boolean seller) throws Exception {
        var f=pendingActualExchange(suffix,seller?com.example.marketplace.claim.api.Exchanges.Responsibility.SELLER:com.example.marketplace.claim.api.Exchanges.Responsibility.BUYER);var buyer=f.parties().buyer();var api=context.getBean(com.example.marketplace.claim.api.ExchangeRefunds.class);var quote=api.quote(buyer.order().orderNumber(),f.accepted().exchangeId(),shipmentAuthority(buyer),4).quote();return new ExchangeFinancialCase(f,api.consent(buyer.order().orderNumber(),f.accepted().exchangeId(),shipmentAuthority(buyer),new com.example.marketplace.claim.api.ExchangeRefunds.Consent("first-financial-consent",4L,quote.id(),quote.fingerprint(),true,quote.legacyConsentRequired(),"actual physical receipt and inspection, current buyer financial consent")));
    }




    protected void exchangeShippingAssertDirectHalfCommits(OwnedExchangeJob owned,UUID intent,UUID seller) throws Exception {
        var refund=new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.payment.api.Refunds.class).get(UUID.fromString(owned.job().command().causeKey())));assertEquals("SUCCEEDED",refund.state());assertNotNull(refund.resultReference());assertEquals("RUNNING",owned.jobs().find(owned.job().id()).orElseThrow().status());String fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(refund.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for(String side:List.of("CLAIM_RESULT","ORDER_RECEIPT")){
            var before=exchangeRefundParticipants();var failure=assertThrows(RuntimeException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(t->{
                var order=context.getBean(com.example.marketplace.order.api.AftercareSettlements.class);order.lockAftercare(seller);context.getBean(com.example.marketplace.platform.api.JobLeases.class).requireOwned(owned.job().id(),owned.job().ownerToken());
                if(side.equals("ORDER_RECEIPT")){order.recordShippingSettlement(new com.example.marketplace.order.api.AftercareSettlements.Shipping(refund.orderId(),seller,intent,refund.paymentId(),refund.id(),refund.amount(),refund.resultReference()));}
                else {long revision=jdbc.queryForObject("SELECT version FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id FOR UPDATE",Map.of("id",seller),Long.class)+1;var p=new MapSqlParameterSource("attempt",refund.id()).addValue("version",refund.version()).addValue("seller",seller).addValue("intent",intent).addValue("reference",refund.resultReference()).addValue("resultAt",java.sql.Timestamp.from(refund.resultAt())).addValue("hash",fingerprint).addValue("job",owned.job().id()).addValue("owner",owned.job().ownerToken()).addValue("at",jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class)).addValue("revision",revision);jdbc.update("INSERT INTO marketplace.claim_shipping_result(attempt_id,result_version,seller_order_id,intent_id,result_state,reference,result_at,fingerprint,job_id,owner_token,occurred_at,workflow_revision) VALUES(:attempt,:version,:seller,:intent,'SUCCEEDED',:reference,:resultAt,:hash,:job,:owner,:at,:revision)",p);jdbc.update("UPDATE marketplace.claim_shipping_obligation SET state='SUCCEEDED',version=:revision WHERE seller_order_id=:seller",p);}
            }));Throwable root=failure;while(root.getCause()!=null){root=root.getCause();}assertTrue(root instanceof java.sql.SQLException,root.toString());assertEquals("23514",((java.sql.SQLException)root).getSQLState());assertTrue(root.getMessage().contains("ACTUAL_SHIPPING_RESULT_GRAPH_REQUIRED")||root.getMessage().contains("ACTUAL_SHIPPING_RESULT_REQUIRED"),root.toString());var after=exchangeRefundParticipants();java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-shipping-direct-half-commit-"+side+".json"),authMapper.writeValueAsString(Map.of("side",side,"actualRunningJob",owned.job().id(),"actualSucceededAttempt",refund.id(),"sqlState","23514","guard",root.getMessage(),"before",before,"after",after)));assertEquals(before,after,"direct caller COMMIT must not persist a one-sided E SHIPPING settlement: "+side);
        }
    }
    /** Corrupt or suppress a real INSERT; unlike the older SQL11 helper this injects no exception. */
    protected void exchangeRefundAssertGraphIntervention(String table,String label,String assignment,boolean suppress,Runnable invoke) {
        exchangeRefundAssertGraphIntervention(table,label,assignment,suppress,List.of("23514","23503"),invoke);
    }
    protected void exchangeRefundAssertGraphIntervention(String table,String label,String assignment,boolean suppress,List<String> allowedStates,Runnable invoke) {
        var before=exchangeRefundParticipants();jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.e_financial_graph_intervention() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE LOG 'E_FINANCIAL_GRAPH_"+label+"'; "+assignment+(suppress?"RETURN NULL;":"RETURN NEW;")+" END $$");jdbc.getJdbcTemplate().execute("CREATE TRIGGER aaa_e_financial_graph_intervention BEFORE INSERT ON marketplace."+table+" FOR EACH ROW EXECUTE FUNCTION marketplace.e_financial_graph_intervention()");
        try{var failure=assertThrows(RuntimeException.class,invoke::run,"a real one-sided/foreign financial graph must never commit: "+label);Throwable root=failure;while(root.getCause()!=null){root=root.getCause();}String sqlState=root instanceof java.sql.SQLException sql?sql.getSQLState():"NONE",rejection=root instanceof com.example.marketplace.order.api.OrderRejected denied&&denied.status()==409?"PORT_409":sqlState;var after=exchangeRefundParticipants();java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-financial-direct-graph-"+label+".json"),authMapper.writeValueAsString(Map.of("table",table,"assignment",assignment,"suppressed",suppress,"injectedException",false,"sqlState",sqlState,"rejection",rejection,"failure",root.toString(),"before",before,"after",after)));assertTrue(root instanceof java.sql.SQLException||rejection.equals("PORT_409")&&allowedStates.contains("PORT_409"),"must observe an actual SQL guard or explicitly allowed current typed-port409: "+root);assertTrue(allowedStates.contains(rejection),rejection);assertEquals(before,after,"all financial/hold/stock/history/job/outbox participants remain unchanged: "+label);}
        catch(java.io.IOException error){throw new IllegalStateException(error);}finally{jdbc.getJdbcTemplate().execute("DROP TRIGGER aaa_e_financial_graph_intervention ON marketplace."+table);jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.e_financial_graph_intervention()");}
    }
    protected void exchangeRefundAssertSqlFailure(String table,String label,String condition,Map<String,String> before,Runnable invoke) {
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.e_financial_sql_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "+condition+"RAISE EXCEPTION 'ACTUAL_E_FINANCIAL_"+label+"' USING ERRCODE='23514';END $$");jdbc.getJdbcTemplate().execute("CREATE TRIGGER e_financial_sql_reject BEFORE INSERT ON marketplace."+table+" FOR EACH ROW EXECUTE FUNCTION marketplace.e_financial_sql_failure()");
        try{var error=assertThrows(RuntimeException.class,invoke::run);Throwable root=error;while(root.getCause()!=null){root=root.getCause();}assertTrue(root instanceof java.sql.SQLException,root.toString());assertEquals("23514",((java.sql.SQLException)root).getSQLState());assertTrue(root.getMessage().contains("ACTUAL_E_FINANCIAL_"+label),root.toString());var after=exchangeRefundParticipants();java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-financial-rollback-"+label+".json"),authMapper.writeValueAsString(Map.of("table",table,"sqlState","23514","before",before,"after",after)));assertEquals(before,after,"all payment/fee/stock/hold/ready/completion/coupon/closure/history/outbox participants roll back at "+label);}
        catch(java.io.IOException error){throw new IllegalStateException(error);}finally{jdbc.getJdbcTemplate().execute("DROP TRIGGER e_financial_sql_reject ON marketplace."+table);jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.e_financial_sql_failure()");}
    }



    protected void exchangeOriginalShippingSupport(String outcome) throws Exception {
        var f=exchangeRefundAccepted(outcome.equals("FAILED")?"e-srf":"e-sru",true);var buyer=f.physical().parties().buyer();var id=f.physical().accepted().exchangeId();UUID seller=buyer.order().sellers().getFirst().id();String number=buyer.order().orderNumber();runCancellationJob("REFUND_SUBMIT.v1",f.conversion().refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",f.conversion().refundAttemptId());runCancellationJob("EXCHANGE_REFUND_RESUME.v1",id);runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",seller);UUID first=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id AND claim_id=:origin",Map.of("id",seller,"origin",id),UUID.class);assertTrue(new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.payment.api.Refunds.class).get(first).amount())>0);
        jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'SHIPPING',:outcome)",Map.of("id",id,"outcome",outcome));runCancellationJob("REFUND_SUBMIT.v1",first);long firstVersion=new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.payment.api.Refunds.class).get(first).version());UUID firstResultId=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='REFUND_RESULT_RECORDED.v1' AND dedupe_key=:key",Map.of("key","refund-result:"+first+":"+firstVersion),UUID.class);var firstResultJob=claimActualExchangeJob(firstResultId,Instant.now().plusSeconds(1));var collector=context.getBean(com.example.marketplace.claim.infrastructure.JdbcFinancialResults.class);assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,collector.result(firstResultJob.job()));assertEquals("RUNNING",firstResultJob.jobs().find(firstResultId).orElseThrow().status(),"original failure result commit succeeded with ACK intentionally omitted");runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",seller);assertEquals("PAID",orders().find(number).state());assertEquals("REFUND_PENDING",context.getBean(com.example.marketplace.claim.api.Exchanges.class).get(number,id,shipmentAuthority(buyer)).state());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_completion WHERE exchange_id=:id",Map.of("id",id),Integer.class));
        var initialItem=jdbc.queryForObject("SELECT to_jsonb(r)::text FROM marketplace.claim_exchange_refund_item_ready r WHERE exchange_id=:id",Map.of("id",id),String.class);var initialConsent=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM marketplace.claim_exchange_refund_consent c WHERE exchange_id=:id",Map.of("id",id),String.class);var initialStock=exchangeParticipants().get("inventory_exchange_reservation");var operator=supportFixture(outcome.equals("FAILED")?"e-srf-op":"e-sru-op","SUPPORT");String base="/api/admin/support/orders/"+number+"/exchanges/"+id+"/refunds/SHIPPING";var status=accountRequest(operator.browser(),"GET",base,null,null);assertEquals(200,status.statusCode(),"real E original shipping has its own support read/progress, no RETURN id: "+status.body());var projected=authMapper.readTree(status.body());assertEquals(id.toString(),projected.path("exchangeId").asText());assertEquals(1,projected.path("revision").asLong());assertEquals(outcome,projected.path("state").asText());assertTrue(projected.path("amount").isTextual());
        String action=outcome.equals("FAILED")?"retry":"query";var body=Map.of("key","independent-shipping-support","expectedRevision",1,"reason","current support examines only the original seller shipping obligation");var response=accountRequest(operator.browser(),"POST",base+"/"+action,body,csrfToken(operator.browser()));assertEquals(202,response.statusCode(),"explicit typed E original shipping support command: "+response.body());var receipt=authMapper.readTree(response.body());UUID attempt=UUID.fromString(receipt.path("refundAttemptId").asText());assertEquals(2,receipt.path("revision").asLong());assertEquals(6,receipt.path("progress").path("version").asLong(),"independent shipping support must not bump or reopen the E ITEM workflow");assertEquals(outcome.equals("UNKNOWN"),attempt.equals(first));var stable=exchangeRefundParticipants();assertEquals(response.body(),accountRequest(operator.browser(),"POST",base+"/"+action,body,csrfToken(operator.browser())).body());assertEquals(stable,exchangeRefundParticipants());assertEquals(403,accountRequest(buyer.browser(),"POST",base+"/"+action,body,csrfToken(buyer.browser())).statusCode());assertEquals(stable,exchangeRefundParticipants());
        if(outcome.equals("FAILED")){jdbc.update("UPDATE marketplace.payment_refund_mock_setting SET behavior='SUCCESS' WHERE claim_id=:id AND purpose='SHIPPING'",Map.of("id",id));}
        else {var invalid=new java.util.HashMap<String,Object>(body);invalid.put("key","unknown-cannot-reserve-new-shipping");invalid.put("expectedRevision",2);assertEquals(409,accountRequest(operator.browser(),"POST",base+"/retry",invalid,csrfToken(operator.browser())).statusCode());assertEquals(stable,exchangeRefundParticipants());jdbc.update("UPDATE marketplace.payment_refund_mock SET state='SUCCEEDED',reference=:ref,effects=1 WHERE attempt_id=:id",Map.of("id",attempt,"ref","DEMO-REFUND-"+attempt));}
        runCancellationJob("REFUND_SUBMIT.v1",attempt);long nextVersion=new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.payment.api.Refunds.class).get(attempt).version());runExchangeActualJob(jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='REFUND_RESULT_RECORDED.v1' AND dedupe_key=:key",Map.of("key","refund-result:"+attempt+":"+nextVersion),UUID.class));runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",seller);assertEquals("CLOSED",orders().find(number).state());var complete=context.getBean(com.example.marketplace.claim.api.Exchanges.class).get(number,id,shipmentAuthority(buyer));assertEquals("REFUNDED",complete.state());assertEquals(7,complete.version());assertEquals(initialItem,jdbc.queryForObject("SELECT to_jsonb(r)::text FROM marketplace.claim_exchange_refund_item_ready r WHERE exchange_id=:id",Map.of("id",id),String.class));assertEquals(initialConsent,jdbc.queryForObject("SELECT to_jsonb(c)::text FROM marketplace.claim_exchange_refund_consent c WHERE exchange_id=:id",Map.of("id",id),String.class));assertEquals(initialStock,exchangeParticipants().get("inventory_exchange_reservation"));var finalRead=accountRequest(operator.browser(),"GET",base,null,null);assertEquals(200,finalRead.statusCode());assertEquals(3,authMapper.readTree(finalRead.body()).path("revision").asLong());assertEquals("SUCCEEDED",authMapper.readTree(finalRead.body()).path("state").asText());stable=exchangeRefundParticipants();assertEquals(response.body(),accountRequest(operator.browser(),"POST",base+"/"+action,body,csrfToken(operator.browser())).body());assertEquals(stable,exchangeRefundParticipants());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_aftercare_shipping WHERE seller_order_id=:id",Map.of("id",seller),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_completion WHERE exchange_id=:id",Map.of("id",id),Integer.class));var beforeOldAck=exchangeRefundParticipants();assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,collector.result(firstResultJob.job()),"old committed FAILED/UNKNOWN result acknowledges after newer supported success without reversing original-S or terminal E");assertEquals(beforeOldAck,exchangeRefundParticipants());assertTrue(firstResultJob.jobs().complete(firstResultId,firstResultJob.job().ownerToken()));
    }
    protected record PartialExchangeRefund(ReturnParties parties,com.example.marketplace.claim.api.Exchanges.Receipt accepted) { }
    protected PartialExchangeRefund inspectedPartialExchange(ReturnParties f,String key) {
        var raw=f.shipment().units().getFirst();String number=f.buyer().order().orderNumber();var authority=new com.example.marketplace.order.api.OrderQueries.Authority(f.buyer().authority().browser(),f.buyer().authority().member(),f.buyer().authority().version(),f.buyer().authority().guestToken());var api=context.getBean(com.example.marketplace.claim.api.Exchanges.class);UUID target=f.buyer().order().sellers().stream().flatMap(seller->seller.items().stream()).filter(item->item.id().equals(raw.orderItemId())).findFirst().orElseThrow().skuId();var accepted=api.request(number,authority,new com.example.marketplace.claim.api.Exchanges.Request(key,confirmations().get(number,authority).revision(),List.of(new com.example.marketplace.claim.api.Exchanges.Unit(raw.orderItemId(),raw.ordinal(),target,com.example.marketplace.claim.api.Exchanges.Responsibility.SELLER)),"actual seller-responsible physical item, same current eligible original SKU"));var seller=shipmentAuthority(f.seller());observeActualExchangeApproval(api,accepted.exchangeId(),seller,new com.example.marketplace.claim.api.Exchanges.Command("approve-"+key,0L,"actual same-price stock approval"));var physical=context.getBean(com.example.marketplace.claim.api.ExchangePhysical.class);physical.receive(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.CollectionCommand("receive-"+key,1L,List.of(new com.example.marketplace.claim.api.ExchangePhysical.Received(raw.orderItemId(),raw.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Status.RECEIVED)),"actual original goods received"));physical.inspect(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.InspectionCommand("inspect-"+key,2L,List.of(new com.example.marketplace.claim.api.ExchangePhysical.Inspected(raw.orderItemId(),raw.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Condition.SALEABLE,com.example.marketplace.claim.api.Exchanges.Responsibility.SELLER)),"actual received goods inspected seller responsibility"));return new PartialExchangeRefund(f,accepted);
    }
    protected void settlePartialExchangeRefund(PartialExchangeRefund f) {
        String number=f.parties().buyer().order().orderNumber();var authority=shipmentAuthority(f.parties().buyer());var api=context.getBean(com.example.marketplace.claim.api.ExchangeRefunds.class);var quote=api.quote(number,f.accepted().exchangeId(),authority,3).quote();var receipt=api.consent(number,f.accepted().exchangeId(),authority,new com.example.marketplace.claim.api.ExchangeRefunds.Consent("explicit-partial-refund",3L,quote.id(),quote.fingerprint(),true,quote.legacyConsentRequired(),"current buyer explicitly accepts inspected partial exchange refund"));runCancellationJob("REFUND_SUBMIT.v1",receipt.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",receipt.refundAttemptId());runCancellationJob("EXCHANGE_REFUND_RESUME.v1",f.accepted().exchangeId());
    }
    protected Map<String,List<String>> completedExchangeRefundOwnEffects(UUID id) {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "completedExchangeRefundOwnEffects")) {
        var rows=new java.util.TreeMap<String,List<String>>();for(String table:List.of("claim_exchange_request","claim_exchange_history","claim_exchange_command","claim_exchange_refund_consent","claim_exchange_refund_result","claim_exchange_refund_effect","claim_exchange_refund_item_ready","claim_exchange_refund_ready_unit","claim_exchange_refund_completion","order_exchange_refund_completion","order_exchange_refund_completion_unit","inventory_exchange_reservation","inventory_exchange_release","claim_fee_assessment","claim_financial_result")){String column=table.equals("claim_exchange_request")?"id":table.equals("claim_exchange_refund_result")||table.equals("claim_fee_assessment")||table.equals("claim_financial_result")?"intent_id":"exchange_id";rows.put(table,jdbc.queryForList("SELECT to_jsonb(r)::text FROM marketplace."+table+" r WHERE "+column+"=:id ORDER BY to_jsonb(r)::text",Map.of("id",id),String.class));}rows.put("original_hold",jdbc.queryForList("SELECT to_jsonb(h)::text FROM marketplace.order_unit_hold h JOIN marketplace.claim_exchange_request e ON e.hold_id=h.id WHERE e.id=:id",Map.of("id",id),String.class));rows.put("actual_original_inspection",jdbc.queryForList("SELECT to_jsonb(i)::text FROM marketplace.claim_exchange_inspection_unit i WHERE exchange_id=:id ORDER BY to_jsonb(i)::text",Map.of("id",id),String.class));return rows;
    
        }
    }



    protected com.example.marketplace.claim.api.Exchanges.Progress observeActualExchangeApproval(com.example.marketplace.claim.api.Exchanges api,UUID id,com.example.marketplace.order.api.OrderQueries.Authority authority,com.example.marketplace.claim.api.Exchanges.Command command) {
        return new TransactionTemplate(transactionManager).execute(t->{var approved=api.approve(id,authority,command);var observation=new java.util.LinkedHashMap<String,Object>();observation.put("actualProgress",approved);observation.put("databaseObservedNow",jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant());observation.put("jvmObservedNow",Instant.now());for(String table:List.of("claim_exchange_request","claim_exchange_approval_generation","inventory_exchange_approval","inventory_exchange_reservation","claim_exchange_command","claim_exchange_history","claim_exchange_reservation_schedule")){observation.put(table,jdbc.queryForList("SELECT to_jsonb(r)::text FROM marketplace."+table+" r WHERE "+(table.equals("claim_exchange_request")?"id":"exchange_id")+"=:id ORDER BY to_jsonb(r)::text",Map.of("id",id),String.class));}observation.put("approvalGenerationGuardDefinitions",List.of("g.exchange_id IS NULL","a.exchange_id IS NULL","c.id IS NULL","g.revision<>g.original_revision+1 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history predecessor WHERE predecessor.exchange_id=p_id AND predecessor.version=g.original_revision)","n=0","c.current_sequence<p_sequence","c.current_sequence<>(SELECT max(sequence) FROM claim_exchange_approval_generation WHERE exchange_id=p_id)","c.current_sequence<>(SELECT count(*) FROM claim_exchange_approval_generation WHERE exchange_id=p_id)","(a.order_id,a.seller_order_id,a.approved_at,a.expires_at) IS DISTINCT FROM(c.order_id,c.seller_order_id,g.approved_at,g.expires_at)","n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=p_id AND sequence=p_sequence)","NOT EXISTS(SELECT 1 FROM claim_exchange_command command JOIN claim_exchange_history history ON history.exchange_id=command.exchange_id AND history.version=command.version WHERE command.exchange_id=p_id AND command.command_key=g.command_key AND command.action='APPROVED' AND command.version=g.revision AND command.receipt=g.receipt AND history.action='APPROVED' AND history.occurred_at=g.approved_at)","NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key=(CASE WHEN p_sequence=1 THEN 'exchange-approved:' ELSE 'exchange-approved-'||p_sequence::text||':' END)||p_id::text AND j.cause_key=p_id::text AND j.payload=p_id::text)","(g.receipt->>'version')::bigint IS DISTINCT FROM g.revision","g.receipt->'accepted' IS DISTINCT FROM c.receipt","n<>(SELECT count(*) FROM jsonb_array_elements(g.receipt->'reservations') saved WHERE(saved->>'sequence')::integer=p_sequence)","EXISTS(SELECT 1 FROM inventory_exchange_reservation r JOIN claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id WHERE r.exchange_id=p_id AND r.sequence=p_sequence AND((r.order_id,r.seller_order_id,r.checkout_id,r.item_id,r.ordinal,r.target_sku_id,r.approved_at,r.expires_at) IS DISTINCT FROM(a.order_id,a.seller_order_id,a.checkout_id,u.item_id,u.ordinal,u.target_sku_id,a.approved_at,a.expires_at)\n  OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(g.receipt->'reservations') saved WHERE saved->>'id'=r.id::text AND saved->>'exchangeId'=p_id::text AND saved->>'physicalId'=r.physical_id::text AND saved->>'targetSkuId'=r.target_sku_id::text AND(saved->>'sequence')::integer=r.sequence AND(saved->>'approvedAt')::timestamptz=r.approved_at AND(saved->>'expiresAt')::timestamptz=r.expires_at AND saved->>'status'='RESERVED')\n  OR NOT EXISTS(SELECT 1 FROM inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=r.approved_at)))"));observation.put("actualApprovalGuardConjuncts",jdbc.queryForObject("SELECT jsonb_build_object('clause1',(g.exchange_id IS NULL),'clause2',(a.exchange_id IS NULL),'clause3',(c.id IS NULL),'clause4',(g.revision<>g.original_revision+1 OR NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_history predecessor WHERE predecessor.exchange_id=:id AND predecessor.version=g.original_revision)),'clause5',(unit_count.n=0),'clause6',(c.current_sequence<:sequence),'clause7',(c.current_sequence<>(SELECT max(sequence) FROM marketplace.claim_exchange_approval_generation WHERE exchange_id=:id)),'clause8',(c.current_sequence<>(SELECT count(*) FROM marketplace.claim_exchange_approval_generation WHERE exchange_id=:id)),'clause9',((a.order_id,a.seller_order_id,a.approved_at,a.expires_at) IS DISTINCT FROM(c.order_id,c.seller_order_id,g.approved_at,g.expires_at)),'clause10',(unit_count.n<>(SELECT count(*) FROM marketplace.inventory_exchange_reservation WHERE exchange_id=:id AND sequence=:sequence)),'clause11',(NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_command command JOIN marketplace.claim_exchange_history history ON history.exchange_id=command.exchange_id AND history.version=command.version WHERE command.exchange_id=:id AND command.command_key=g.command_key AND command.action='APPROVED' AND command.version=g.revision AND command.receipt=g.receipt AND history.action='APPROVED' AND history.occurred_at=g.approved_at)),'clause12',(NOT EXISTS(SELECT 1 FROM marketplace.outbox_event o JOIN marketplace.job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key=(CASE WHEN :sequence=1 THEN 'exchange-approved:' ELSE 'exchange-approved-'||:sequence::text||':' END)||:id::text AND j.cause_key=:id::text AND j.payload=:id::text)),'clause13',((g.receipt->>'version')::bigint IS DISTINCT FROM g.revision),'clause14',(g.receipt->'accepted' IS DISTINCT FROM c.receipt),'clause15',(unit_count.n<>(SELECT count(*) FROM jsonb_array_elements(g.receipt->'reservations') saved WHERE(saved->>'sequence')::integer=:sequence)),'clause16',(EXISTS(SELECT 1 FROM marketplace.inventory_exchange_reservation r JOIN marketplace.claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id WHERE r.exchange_id=:id AND r.sequence=:sequence AND((r.order_id,r.seller_order_id,r.checkout_id,r.item_id,r.ordinal,r.target_sku_id,r.approved_at,r.expires_at) IS DISTINCT FROM(a.order_id,a.seller_order_id,a.checkout_id,u.item_id,u.ordinal,u.target_sku_id,a.approved_at,a.expires_at)\n  OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(g.receipt->'reservations') saved WHERE saved->>'id'=r.id::text AND saved->>'exchangeId'=:id::text AND saved->>'physicalId'=r.physical_id::text AND saved->>'targetSkuId'=r.target_sku_id::text AND(saved->>'sequence')::integer=r.sequence AND(saved->>'approvedAt')::timestamptz=r.approved_at AND(saved->>'expiresAt')::timestamptz=r.expires_at AND saved->>'status'='RESERVED')\n  OR NOT EXISTS(SELECT 1 FROM marketplace.inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=r.approved_at)))))::text FROM marketplace.claim_exchange_request c JOIN marketplace.claim_exchange_approval_generation g ON g.exchange_id=c.id AND g.sequence=:sequence JOIN marketplace.inventory_exchange_approval a ON a.exchange_id=c.id AND a.sequence=g.sequence CROSS JOIN LATERAL(SELECT count(*) AS n FROM marketplace.claim_exchange_unit WHERE exchange_id=c.id) unit_count WHERE c.id=:id",Map.of("id",id,"sequence",approved.reservations().getFirst().sequence()),String.class));observation.put("inventoryEvents",jdbc.queryForList("SELECT to_jsonb(e)::text FROM marketplace.inventory_exchange_event e JOIN marketplace.inventory_exchange_reservation r ON r.id=e.reservation_id WHERE r.exchange_id=:id ORDER BY e.id",Map.of("id",id),String.class));observation.put("actualJobsAndOutbox",jdbc.queryForList("SELECT jsonb_build_object('job',to_jsonb(j),'outbox',to_jsonb(o))::text FROM marketplace.job j JOIN marketplace.outbox_event o ON o.job_id=j.id WHERE j.cause_key=:id ORDER BY j.id",Map.of("id",id.toString()),String.class));var configuredClock=org.springframework.test.util.ReflectionTestUtils.getField(context.getBean(com.example.marketplace.claim.infrastructure.JdbcExchanges.class),"clock");observation.put("configuredSpringExchangeClock",configuredClock==null?"actualDatabaseClock":configuredClock.getClass().getName());observation.put("beforeDeferredGuardCommit",true);try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-actual-approval-before-commit-"+id+".json"),authMapper.writeValueAsString(observation));}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}return approved;});
    }
    protected UUID currentExchangeRefundRecoveryRun;
    protected void publishExchangeRefundRecoveryStartup() {
        boolean exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('marketplace.claim_exchange_refund_recovery_run') IS NOT NULL",Map.of(),Boolean.class));var before=exists?new java.util.HashSet<>(jdbc.queryForList("SELECT id FROM marketplace.claim_exchange_refund_recovery_run",Map.of(),UUID.class)):java.util.Set.<UUID>of();publishRecoveryStartup();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('marketplace.claim_exchange_refund_recovery_run') IS NOT NULL",Map.of(),Boolean.class))){currentExchangeRefundRecoveryRun=null;return;}var added=jdbc.queryForList("SELECT id FROM marketplace.claim_exchange_refund_recovery_run",Map.of(),UUID.class).stream().filter(id->!before.contains(id)).toList();assertEquals(1,added.size(),"actual trusted startup issues one distinct typed E recovery run");currentExchangeRefundRecoveryRun=added.getFirst();
    }
    protected void drainExchangeRefundRecoveryWork() throws Exception {
        if(currentExchangeRefundRecoveryRun==null){return;}var p=Map.of("run",currentExchangeRefundRecoveryRun,"runText",currentExchangeRefundRecoveryRun.toString());String own="(j.type='EXCHANGE_REFUND_RECOVERY_SCAN.v1' AND j.cause_key=:runText OR EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_delivery d WHERE d.run_id=:run AND d.job_id=j.id) OR j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_candidate c JOIN marketplace.claim_exchange_request e ON e.id=c.exchange_id WHERE c.run_id=:run AND j.cause_key=e.seller_order_id::text))";int count=0;
        while(true){var ids=jdbc.queryForList("SELECT j.id FROM marketplace.job j WHERE j.status='READY' AND j.attempts=0 AND "+own+" ORDER BY j.next_run_at,j.id LIMIT 1",p,UUID.class);if(ids.isEmpty()){break;}runExchangeActualJob(ids.getFirst());assertTrue(++count<500,"bounded typed E recovery must finish without silently skipping a failed handler");}
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=:run AND NOT completed",p,Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_delivery d JOIN marketplace.job j ON j.id=d.job_id WHERE d.run_id=:run AND j.status<>'SUCCEEDED'",p,Integer.class));
    }
    protected Map<String,String> exchangeRefundRecoveryBusinessState() {
        try (var contractTimingScope = ContractTiming.measure("state-comparison", "exchangeRefundRecoveryBusinessState")) {
        var state=new java.util.TreeMap<>(exchangeRefundParticipants());state.keySet().removeIf(k->k.equals("job")||k.equals("job_claim_fact")||k.equals("outbox_event")||k.startsWith("claim_return_recovery_")||k.startsWith("claim_exchange_refund_recovery_"));return state;
    
        }
    }

    protected record ExchangeRecoverySeed(PartialExchangeRefund physical,com.example.marketplace.claim.api.ExchangeRefunds.Accepted consent,com.example.marketplace.claim.api.ReturnFees.Quote quote) { }
    protected ExchangeRecoverySeed pendingExchangeRecoverySeed(ReturnParties f,String key) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "pendingExchangeRecoverySeed")) { return pendingExchangeRecoverySeed(f,key,f.shipment().units().getFirst()); 
        }
    }
    protected ExchangeRecoverySeed pendingExchangeRecoverySeed(ReturnParties f,String key,com.example.marketplace.fulfillment.api.Shipments.Unit unit) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "pendingExchangeRecoverySeed")) {
        var owner=shipmentAuthority(f.buyer());var seller=shipmentAuthority(f.seller());String number=f.buyer().order().orderNumber();UUID sku=f.buyer().order().sellers().stream().flatMap(v->v.items().stream()).filter(v->v.id().equals(unit.orderItemId())).findFirst().orElseThrow().skuId();var accepted=context.getBean(com.example.marketplace.claim.api.Exchanges.class).request(number,owner,new com.example.marketplace.claim.api.Exchanges.Request(key,confirmations().get(number,owner).revision(),List.of(new com.example.marketplace.claim.api.Exchanges.Unit(unit.orderItemId(),unit.ordinal(),sku,com.example.marketplace.claim.api.Exchanges.Responsibility.SELLER)),"복구에 필요한 실제 환불원사실을 위한 교환 접수"));var physical=context.getBean(com.example.marketplace.claim.api.ExchangePhysical.class);physical.receive(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.CollectionCommand("receive-"+key,0L,List.of(new com.example.marketplace.claim.api.ExchangePhysical.Received(unit.orderItemId(),unit.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Status.RECEIVED)),"승인 전 실제 수령"));physical.inspect(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.InspectionCommand("inspect-"+key,1L,List.of(new com.example.marketplace.claim.api.ExchangePhysical.Inspected(unit.orderItemId(),unit.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Condition.SALEABLE,com.example.marketplace.claim.api.Exchanges.Responsibility.SELLER)),"실제 판매자 책임 검수·원출처 입고"));var inspected=new PartialExchangeRefund(f,accepted);var api=context.getBean(com.example.marketplace.claim.api.ExchangeRefunds.class);var quote=api.quote(number,accepted.exchangeId(),owner,2).quote();assertEquals("0",quote.increment());var consent=api.consent(number,accepted.exchangeId(),owner,new com.example.marketplace.claim.api.ExchangeRefunds.Consent("recovery-explicit-consent",2L,quote.id(),quote.fingerprint(),true,quote.legacyConsentRequired(),"실제 검수된 판매자책임 원금의 명시 환불 전환"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_exchange_reservation WHERE exchange_id=:id",Map.of("id",accepted.exchangeId()),Integer.class));return new ExchangeRecoverySeed(inspected,consent,quote);
    
        }
    }
    protected void retainExchangeRecoveryItem(ExchangeRecoverySeed seed,Map<UUID,String> originals) throws Exception {
        UUID attempt=seed.consent().refundAttemptId(),id=seed.physical().accepted().exchangeId();for(String type:List.of("REFUND_SUBMIT.v1","REFUND_RESULT_RECORDED.v1")){UUID actualJob=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status='READY' ORDER BY scheduled_at DESC,id LIMIT 1",Map.of("type",type,"cause",attempt.toString()),UUID.class);runExchangeActualJob(actualJob);}UUID primary=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='EXCHANGE_REFUND_RESUME.v1' AND cause_key=:id AND status='READY'",Map.of("id",id.toString()),UUID.class);var owned=claimActualExchangeJob(primary,Instant.now());assertTrue(owned.jobs().complete(primary,owned.job().ownerToken()));originals.put(primary,jdbc.queryForObject("SELECT to_jsonb(j)::text FROM marketplace.job j WHERE id=:id",Map.of("id",primary),String.class));
    }
    protected void scanExchangeRecoveryOnly(UUID run) throws Exception {
        int count=0;while(true){var next=jdbc.queryForList("SELECT id FROM marketplace.job WHERE type='EXCHANGE_REFUND_RECOVERY_SCAN.v1' AND cause_key=:run AND status='READY' AND attempts=0 ORDER BY next_run_at,id LIMIT 1",Map.of("run",run.toString()),UUID.class);if(next.isEmpty()){break;}runExchangeActualJob(next.getFirst());assertTrue(++count<20,"a 51-source fixed run must finish within its bounded page jobs");}assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=:run AND NOT completed",Map.of("run",run),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE type='EXCHANGE_REFUND_RECOVERY_SCAN.v1' AND cause_key=:run AND status<>'SUCCEEDED'",Map.of("run",run.toString()),Integer.class));
    }
    protected Map<UUID,String> ackExchangeRecoveryDeliveries(UUID run) throws Exception {
        var result=new java.util.LinkedHashMap<UUID,String>();for(UUID id:jdbc.queryForList("SELECT d.job_id FROM marketplace.claim_exchange_refund_recovery_delivery d JOIN marketplace.job j ON j.id=d.job_id WHERE d.run_id=:run AND j.status='READY' ORDER BY d.job_id",Map.of("run",run),UUID.class)){var owned=claimActualExchangeJob(id,Instant.now());assertTrue(owned.jobs().complete(id,owned.job().ownerToken()));result.put(id,jdbc.queryForObject("SELECT to_jsonb(j)::text FROM marketplace.job j WHERE id=:id",Map.of("id",id),String.class));}return result;
    }
    protected List<Integer> exchangeRecoveryPageSizes(UUID run,String stage) {
        return jdbc.queryForList("SELECT jsonb_array_length(locator_ids) FROM marketplace.claim_exchange_refund_recovery_page WHERE run_id=:run AND stage=:stage ORDER BY CASE WHEN cursor_key='START' THEN 0 ELSE 1 END,cursor_key",Map.of("run",run,"stage",stage),Integer.class);
    }
    protected void exchangeRecoveryNextPageFailureRetainsFiftyCandidatesAndResumesExactCursor(UUID run) throws Exception {
        UUID job=jdbc.queryForObject("SELECT first_job_id FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=:run AND stage='ITEM_EFFECT'",Map.of("run",run),UUID.class);var owned=claimActualExchangeJob(job,Instant.now());var scanner=context.getBean(com.example.marketplace.claim.infrastructure.ExchangeRefundRecoveryScanner.class);var original=jdbc.queryForMap("SELECT * FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=:run AND stage='ITEM_EFFECT'",Map.of("run",run));
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.e_recovery_finish_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'E_RECOVERY_PAGE_COMPLETE_FAILURE' USING ERRCODE='23514'; END $$");jdbc.getJdbcTemplate().execute("CREATE TRIGGER e_recovery_finish_failure BEFORE INSERT ON marketplace.claim_exchange_refund_recovery_page_complete FOR EACH ROW EXECUTE FUNCTION marketplace.e_recovery_finish_failure()");
        try{var failure=assertThrows(RuntimeException.class,()->scanner.scan(owned.job()));Throwable sqlRoot=failure;while(sqlRoot.getCause()!=null){sqlRoot=sqlRoot.getCause();}assertTrue(sqlRoot instanceof java.sql.SQLException,sqlRoot.toString());assertEquals("23514",((java.sql.SQLException)sqlRoot).getSQLState());assertEquals(original,jdbc.queryForMap("SELECT * FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=:run AND stage='ITEM_EFFECT'",Map.of("run",run)));assertEquals(50,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_candidate WHERE run_id=:run AND stage='ITEM_EFFECT'",Map.of("run",run),Integer.class));assertEquals(50,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_delivery WHERE run_id=:run AND stage='ITEM_EFFECT'",Map.of("run",run),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_page_complete c JOIN marketplace.claim_exchange_refund_recovery_page p ON p.id=c.page_id WHERE p.run_id=:run AND p.stage='ITEM_EFFECT'",Map.of("run",run),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE type='EXCHANGE_REFUND_RECOVERY_SCAN.v1' AND cause_key=:run AND dedupe_key LIKE '%:ITEM_EFFECT:%'",Map.of("run",run.toString()),Integer.class));}finally{jdbc.getJdbcTemplate().execute("DROP TRIGGER e_recovery_finish_failure ON marketplace.claim_exchange_refund_recovery_page_complete");jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.e_recovery_finish_failure()");}
        var deliveries=jdbc.queryForList("SELECT to_jsonb(d)::text FROM marketplace.claim_exchange_refund_recovery_delivery d WHERE run_id=:run AND stage='ITEM_EFFECT' ORDER BY id",Map.of("run",run),String.class);assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,scanner.scan(owned.job()));assertEquals(deliveries,jdbc.queryForList("SELECT to_jsonb(d)::text FROM marketplace.claim_exchange_refund_recovery_delivery d WHERE run_id=:run AND stage='ITEM_EFFECT' ORDER BY id",Map.of("run",run),String.class));assertTrue(owned.jobs().complete(job,owned.job().ownerToken()));java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-recovery-page-cursor-rollback.json"),authMapper.writeValueAsString(Map.of("run",run,"sqlState","23514","durableCandidates",50,"cursorAndNextJobRollback",true,"candidateReplayDuplicateDelivery",0)));
    }

    protected Map<String,String> exchangeRecoveryMetadata() {
        var rows=new java.util.TreeMap<String,String>();for(String table:List.of("claim_exchange_refund_recovery_run","claim_exchange_refund_recovery_stage","claim_exchange_refund_recovery_page","claim_exchange_refund_recovery_delivery","claim_exchange_refund_recovery_candidate","claim_exchange_refund_recovery_page_complete")){rows.put(table,jdbc.queryForList("SELECT to_jsonb(r)::text FROM marketplace."+table+" r ORDER BY to_jsonb(r)::text",Map.of(),String.class).toString());}return rows;
    }
    protected void exchangeRecoveryGraphIntervention(String table,String label,String assignment,boolean suppress,Runnable invoke,UUID rejectedExchange) throws Exception {
        var metadata=exchangeRecoveryMetadata();exchangeRefundAssertGraphIntervention(table,"recovery-"+label,assignment,suppress,invoke);var after=exchangeRecoveryMetadata();var originalCandidates=authMapper.readValue(metadata.get("claim_exchange_refund_recovery_candidate"),new tools.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});var candidates=authMapper.readValue(after.get("claim_exchange_refund_recovery_candidate"),new tools.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});assertTrue(candidates.containsAll(originalCandidates),"every earlier durable candidate remains byte-for-byte unchanged");var added=candidates.stream().filter(c->!originalCandidates.contains(c)).toList();
        for(var skip:added){assertEquals("SKIP",skip.get("outcome"));assertEquals("CURRENT_SELLER_FACTS_ALREADY_EVALUATED",skip.get("reason"));assertEquals("SELLER_REEVALUATE",skip.get("stage"));assertNull(skip.get("delivery_id"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_candidate c JOIN marketplace.claim_exchange_refund_recovery_page p ON p.id=c.page_id AND p.run_id=c.run_id AND p.stage=c.stage JOIN marketplace.job j ON j.id=c.scan_job_id AND j.owner_token=c.owner_token JOIN marketplace.claim_exchange_request x ON x.id=c.exchange_id WHERE c.page_id=:page AND c.exchange_id=:exchange AND c.outcome='SKIP' AND c.reason='CURRENT_SELLER_FACTS_ALREADY_EVALUATED' AND p.locator_ids @>jsonb_build_array(c.exchange_id::text) AND j.status='RUNNING' AND x.id<>:rejected",Map.of("page",UUID.fromString(skip.get("page_id").toString()),"exchange",UUID.fromString(skip.get("exchange_id").toString()),"rejected",rejectedExchange),Integer.class));}
        var expected=new java.util.TreeMap<>(metadata);var actual=new java.util.TreeMap<>(after);expected.remove("claim_exchange_refund_recovery_candidate");actual.remove("claim_exchange_refund_recovery_candidate");assertEquals(expected,actual,"rejected candidate leaves every delivery/page/cursor/run receipt unchanged; earlier independent SKIP receipts are explicitly verified");assertTrue(candidates.stream().filter(c->c.get("exchange_id").equals(rejectedExchange.toString())).allMatch(originalCandidates::contains),"the rejected candidate cannot gain a receipt");java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-recovery-metadata-"+label+".json"),authMapper.writeValueAsString(Map.of("before",metadata,"after",after,"independentVerifiedSkips",added,"rejectedExchange",rejectedExchange)));
    }
    protected ExchangeRecoverySeed retainedExchangeRecoveryStage(String stage,String key) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("preparation", "retainedExchangeRecoveryStage")) {
        var seed=pendingExchangeRecoverySeed(returnParties(key,true,1,"5000"),key);UUID id=seed.physical().accepted().exchangeId(),attempt=seed.consent().refundAttemptId(),seller=seed.physical().parties().shipment().sellerOrderId();runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);String type="EXCHANGE_REFUND_RESUME.v1";UUID cause=id;
        if(!stage.equals("ITEM_EFFECT")){runCancellationJob(type,cause);type="SELLER_AFTERCARE_REEVALUATE.v1";cause=seller;}if(stage.equals("SHIPPING_RESULT")){runCancellationJob(type,cause);attempt=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",Map.of("id",seller),UUID.class);runCancellationJob("REFUND_SUBMIT.v1",attempt);type="REFUND_RESULT_RECORDED.v1";cause=attempt;}
        UUID job=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status='READY'",Map.of("type",type,"cause",cause.toString()),UUID.class);var owned=claimActualExchangeJob(job,Instant.now());assertTrue(owned.jobs().complete(job,owned.job().ownerToken()));return seed;
    
        }
    }

    protected com.example.marketplace.claim.infrastructure.ExchangeRefundRecoveryScanner exchangeScannerWith(com.example.marketplace.platform.infrastructure.JdbcJobs jobs) {
        return new com.example.marketplace.claim.infrastructure.ExchangeRefundRecoveryScanner(jdbc,context.getBean(com.example.marketplace.order.api.AftercareSettlements.class),context.getBean(com.example.marketplace.order.api.ExchangeApplications.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),jobs,jobs,context.getBean(com.example.marketplace.platform.api.Outbox.class),context.getEnvironment(),transactionManager,context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class));
    }
    protected com.example.marketplace.claim.infrastructure.ExchangeRefundFinancialWorker exchangeFinancialWith(com.example.marketplace.platform.infrastructure.JdbcJobs jobs) {
        return new com.example.marketplace.claim.infrastructure.ExchangeRefundFinancialWorker(jdbc,context.getBean(com.example.marketplace.claim.infrastructure.JdbcExchanges.class),context.getBean(com.example.marketplace.order.api.ExchangeApplications.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),context.getBean(com.example.marketplace.claim.api.ReturnFees.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),jobs,jobs,transactionManager);
    }


    protected void settleExchangeRecoverySeed(ExchangeRecoverySeed seed) {
        runCancellationJob("REFUND_SUBMIT.v1",seed.consent().refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",seed.consent().refundAttemptId());runCancellationJob("EXCHANGE_REFUND_RESUME.v1",seed.physical().accepted().exchangeId());
    }
    protected void reevaluateSellerWithExpiredOriginalCoupon(UUID seller,PaymentFixture paid) {
        var coupon=paid.order().quote().stores().getFirst().coupon();var job=ownReturnJob("SELLER_AFTERCARE_REEVALUATE.v1",seller);var api=coupon==null?context.getBean(com.example.marketplace.claim.infrastructure.SellerAftercareWorker.class):sellerAftercareWithCoupons(job,reservationClock(Clock.fixed(coupon.terms().endsAt().plusNanos(1000),ZoneOffset.UTC)));assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,api.reevaluate(job.job()));var stable=exchangeRefundParticipants();assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,api.reevaluate(job.job()));assertEquals(stable,exchangeRefundParticipants());assertTrue(job.jobs().complete(job.job().id(),job.job().ownerToken()));
    }


    protected com.example.marketplace.claim.infrastructure.JdbcExchanges exchangeBusinessObservationAt(Instant raw) {
        return new com.example.marketplace.claim.infrastructure.JdbcExchanges(jdbc,context.getBean(com.example.marketplace.order.api.OrderQueries.class),confirmations(),context.getBean(com.example.marketplace.order.api.ExchangeApplications.class),context.getBean(com.example.marketplace.catalog.api.ExchangeOffers.class),context.getBean(com.example.marketplace.inventory.api.ExchangeReservations.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,context.getBean(com.example.marketplace.order.api.PhysicalOrders.class),Clock.fixed(raw,ZoneOffset.UTC));
    }
    protected record ClockCausalExchange(ReturnParties parties,com.example.marketplace.claim.api.Exchanges.Receipt accepted,UUID target) { }
    protected ClockCausalExchange clockCausalRequested(String key) throws Exception {
        var f=returnParties(key,true,1,"5000");UUID target=actualExchangeTarget(f,key);var u=f.shipment().units().getFirst();var receipt=context.getBean(com.example.marketplace.claim.api.Exchanges.class).request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),new com.example.marketplace.claim.api.Exchanges.Request("causal-request",confirmations().get(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer())).revision(),List.of(new com.example.marketplace.claim.api.Exchanges.Unit(u.orderItemId(),u.ordinal(),target,com.example.marketplace.claim.api.Exchanges.Responsibility.SELLER)),"실제 현재 배송원단위의 후속 교환 요청"));return new ClockCausalExchange(f,receipt,target);
    }
    protected NamedParameterJdbcTemplate exchangeObservedJdbcAt(Instant observed,java.util.concurrent.atomic.AtomicInteger reads) {
        return new NamedParameterJdbcTemplate(context.getBean(DataSource.class)) {
            @Override public <T> T queryForObject(String sql,Map<String,?> parameters,org.springframework.jdbc.core.RowMapper<T> rowMapper) {
                if(sql.startsWith("WITH observed AS MATERIALIZED(SELECT pg_catalog.clock_timestamp() AS observed_at)")){reads.incrementAndGet();sql=sql.replace("pg_catalog.clock_timestamp()","CAST('"+observed+"' AS timestamptz)");}return super.queryForObject(sql,parameters,rowMapper);
            }
        };
    }
    protected com.example.marketplace.claim.api.ExchangeRefunds exchangeRefundObservationAt(Instant raw,java.util.concurrent.atomic.AtomicInteger reads) {
        return new com.example.marketplace.claim.infrastructure.JdbcExchangeRefunds(exchangeObservedJdbcAt(raw,reads),context.getBean(com.example.marketplace.order.api.OrderQueries.class),context.getBean(com.example.marketplace.claim.infrastructure.JdbcExchanges.class),context.getBean(com.example.marketplace.claim.infrastructure.InspectedExchangeRefundQuotes.class),context.getBean(com.example.marketplace.claim.api.ReturnFees.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),context.getBean(com.example.marketplace.inventory.api.ExchangeReservations.class),transactionManager,context.getBean(com.example.marketplace.order.api.ExchangeApplications.class));
    }
    protected void recordClockCausalAttempt(String boundary,UUID id,Instant predecessor,Instant supplied,RuntimeException failure,Map<String,String> before) throws Exception {
        var values=new java.util.LinkedHashMap<String,Object>();values.put("boundary",boundary);values.put("exchange",id);values.put("predecessorRaw",predecessor);values.put("observedRaw",supplied);values.put("observedDeltaMicros",java.time.Duration.between(predecessor,supplied).toNanos()/1000);values.put("testOnlyObservationInjection",true);values.put("authorityDeadlineAndJobClocksUnchanged",true);values.put("serverClockChanged",false);values.put("actualFailure",failure==null?"NONE":failure.toString());values.put("before",before);values.put("after",exchangeRefundParticipants());java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-clock-causal-"+boundary+".json"),authMapper.writeValueAsString(values));if(failure!=null){assertEquals(before,exchangeRefundParticipants(),"a rejected actual operation must retain every business participant");}assertTrue(failure==null,"a valid locked causal operation must not fail only because its raw observation moved backwards: "+failure);
    }


    protected ReturnParties exchangeExceptionParties(String suffix,String state,boolean noStock) throws Exception {
        var f=returnParties(suffix,true,1,"5000");var raw=f.shipment().units().getFirst();var item=f.buyer().order().sellers().getFirst().items().stream().filter(i->i.id().equals(raw.orderItemId())).findFirst().orElseThrow();var line=f.buyer().order().quote().stores().getFirst().lines().stream().filter(i->i.skuId().equals(item.skuId())).findFirst().orElseThrow();
        if(noStock){var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);var b=inventory.list(f.seller().authority().member(),line.productId(),false,0).items().stream().filter(v->v.id().equals(item.skuId())).findFirst().orElseThrow();long available=b.onHand()-b.reserved();assertTrue(available>0);inventory.adjust(f.seller().authority().member(),line.productId(),item.skuId(),false,new com.example.marketplace.inventory.api.Inventory.Change(b.reserved(),b.version(),"actual no suitable replacement stock before store exception",UUID.randomUUID()));}
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);var store=stores.own(f.seller().authority().member());UUID actor=state.equals("CLOSED")?f.seller().authority().member():supportFixture(suffix+"-ops","ACCOUNTS").authority().member();var changed=stores.transition(actor,store.id(),state.equals("CLOSED")?"close":"suspend",new com.example.marketplace.seller.api.Stores.Change(store.version(),"actual existing delivered trade exception boundary"));assertEquals(state,changed.state());assertFalse(changed.visible());assertFalse(stores.capabilities(f.seller().authority().member()).newSales());assertTrue(stores.capabilities(f.seller().authority().member()).existingTrades());var before=exchangeRefundParticipants();assertNull(context.getBean(com.example.marketplace.catalog.api.Browse.class).quoteOffer(item.skuId()),"normal public new-sale query must hide the suspended/closed offer");assertEquals(before,exchangeRefundParticipants(),"existing-trade exception never makes this suspended/closed target a public new-sale offer");return f;
    }
    protected com.example.marketplace.claim.api.Exchanges.Receipt requestExceptionExchange(ReturnParties f) {
        var raw=f.shipment().units().getFirst();var item=f.buyer().order().sellers().getFirst().items().stream().filter(i->i.id().equals(raw.orderItemId())).findFirst().orElseThrow();var authority=shipmentAuthority(f.buyer());String number=f.buyer().order().orderNumber();return assertDoesNotThrow(()->context.getBean(com.example.marketplace.claim.api.Exchanges.class).request(number,authority,new com.example.marketplace.claim.api.Exchanges.Request("existing-trade-request",confirmations().get(number,authority).revision(),List.of(new com.example.marketplace.claim.api.Exchanges.Unit(raw.orderItemId(),raw.ordinal(),item.skuId(),com.example.marketplace.claim.api.Exchanges.Responsibility.SELLER)),"current buyer requests existing-trade exchange; seller responsibility is not yet verified")),"suspended/closed existing trade may request and hold, while approval and stock must wait for actual seller inspection");
    }
    protected void inspectUnapprovedException(ReturnParties f,com.example.marketplace.claim.api.Exchanges.Receipt accepted,com.example.marketplace.claim.api.ExchangePhysical.Condition condition,com.example.marketplace.claim.api.Exchanges.Responsibility responsibility) {
        var unit=accepted.units().getFirst();var physical=context.getBean(com.example.marketplace.claim.api.ExchangePhysical.class);var seller=shipmentAuthority(f.seller());var received=physical.receive(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.CollectionCommand("exception-received",0L,List.of(new com.example.marketplace.claim.api.ExchangePhysical.Received(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Status.RECEIVED)),"actual accepted original item received before exceptional stock approval"));assertEquals(1,received.version());var inspected=physical.inspect(accepted.exchangeId(),seller,new com.example.marketplace.claim.api.ExchangePhysical.InspectionCommand("exception-inspected",1L,List.of(new com.example.marketplace.claim.api.ExchangePhysical.Inspected(unit.itemId(),unit.ordinal(),condition,responsibility)),"seller records actual inspected condition and responsibility, not buyer assertion"));assertEquals(2,inspected.version());assertEquals(responsibility,inspected.physical().getFirst().responsibility());assertEquals(0,inspected.reservations().size());
    }





    protected record ExchangeOwnerCase(ReturnParties parties,UUID id,long revision,com.example.marketplace.order.api.OrderQueries.Authority owner,ConfirmationGuest gas) { }
    protected ExchangeOwnerCase inspectedExchangeOwner(String suffix,boolean guest) throws Exception {
        if(!guest){var f=pendingActualExchange(suffix);return new ExchangeOwnerCase(f.parties(),f.accepted().exchangeId(),4,shipmentAuthority(f.parties().buyer()),null);}
        var gas=cancellationGuest(suffix);var paid=new PaymentFixture(gas.order(),authBrowser(),new com.example.marketplace.payment.api.Payments.Authority(gas.fixture().browser,null,null,gas.token()));var seller=asSeller(paid,gas.order().sellers().getFirst().id());var shipment=shipments().seller(seller.order().sellers().getFirst().id(),shipmentAuthority(seller)).getFirst();completeShipment(seller,shipment,Instant.now());var parties=new ReturnParties(paid,seller,shipments().seller(shipment.sellerOrderId(),shipmentAuthority(seller)).getFirst());var inspected=inspectedPartialExchange(parties,"guest-physical");return new ExchangeOwnerCase(parties,inspected.accepted().exchangeId(),3,new com.example.marketplace.order.api.OrderQueries.Authority(paid.authority().browser(),null,null,gas.token()),gas);
    }
    protected com.example.marketplace.claim.api.ExchangeRefunds exchangeRefundsWithQueries(com.example.marketplace.order.api.OrderQueries queries) {
        var holds=context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class);var stocks=context.getBean(com.example.marketplace.inventory.api.ExchangeReservations.class);var applications=new com.example.marketplace.order.infrastructure.JdbcExchangeApplications(jdbc,queries,orders(),holds,context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),stocks,context.getBean(com.example.marketplace.order.api.PhysicalOrders.class));var exchanges=context.getBean(com.example.marketplace.claim.infrastructure.JdbcExchanges.class);var inspected=new com.example.marketplace.claim.infrastructure.InspectedExchangeRefundQuotes(jdbc,queries,applications,exchanges,stocks,orders(),transactionManager);
        var fees=new com.example.marketplace.claim.infrastructure.JdbcReturnFees(jdbc,context.getBean(com.example.marketplace.order.api.ReturnSources.class),queries,orders(),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),holds,context.getBean(com.example.marketplace.platform.api.JobLeases.class),context.getBean(com.example.marketplace.claim.infrastructure.CurrentSupport.class),transactionManager,context.getBean(com.example.marketplace.claim.infrastructure.InspectedReturnQuotes.class));org.springframework.test.util.ReflectionTestUtils.setField(fees,"exchangeQuotes",inspected);
        return new com.example.marketplace.claim.infrastructure.JdbcExchangeRefunds(jdbc,queries,exchanges,inspected,fees,context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.payment.api.Refunds.class),stocks,transactionManager,applications);
    }







    protected record ExchangeExpiryOwned(JobClock clock,com.example.marketplace.platform.infrastructure.JdbcJobs jobs,com.example.marketplace.platform.api.Jobs.Job job,com.example.marketplace.claim.infrastructure.ExchangeReservationExpiry handler) { }
    protected ExchangeExpiryOwned ownExchangeExpiry(UUID id,Instant at) {
        var clock=new JobClock();clock.now=at;var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,clock);var handler=new com.example.marketplace.claim.infrastructure.ExchangeReservationExpiry(jdbc,context.getBean(com.example.marketplace.claim.infrastructure.JdbcExchanges.class),context.getBean(com.example.marketplace.order.api.ExchangeApplications.class),context.getBean(com.example.marketplace.inventory.api.ExchangeReservations.class),jobs,jobs,transactionManager,clock);var job=jobs.claim(java.util.Set.of("EXCHANGE_RESERVATION_DUE.v1")).orElseThrow();assertEquals(id,job.id());return new ExchangeExpiryOwned(clock,jobs,job,handler);
    }
    protected com.example.marketplace.claim.infrastructure.JdbcExchanges exchangesAt(JobClock clock) {
        var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class),new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);var applications=new com.example.marketplace.order.infrastructure.JdbcExchangeApplications(jdbc,queries,orders(),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),context.getBean(com.example.marketplace.inventory.api.ExchangeReservations.class),context.getBean(com.example.marketplace.order.api.PhysicalOrders.class));return new com.example.marketplace.claim.infrastructure.JdbcExchanges(jdbc,queries,confirmations(),applications,context.getBean(com.example.marketplace.catalog.api.ExchangeOffers.class),context.getBean(com.example.marketplace.inventory.api.ExchangeReservations.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,context.getBean(com.example.marketplace.order.api.PhysicalOrders.class),clock);
    }
    protected com.example.marketplace.claim.infrastructure.JdbcExchangeDispatches exchangeDispatcherWith(com.example.marketplace.platform.infrastructure.JdbcJobs jobs) {
        return new com.example.marketplace.claim.infrastructure.JdbcExchangeDispatches(jdbc,context.getBean(com.example.marketplace.order.api.ExchangeApplications.class),context.getBean(com.example.marketplace.order.api.PhysicalOrders.class),context.getBean(com.example.marketplace.inventory.api.ExchangeReservations.class),context.getBean(com.example.marketplace.inventory.api.PhysicalStocks.class),context.getBean(com.example.marketplace.fulfillment.api.ExchangeShipments.class),context.getBean(com.example.marketplace.catalog.api.ExchangeOffers.class),context.getBean(com.example.marketplace.claim.infrastructure.JdbcExchanges.class),jobs,jobs,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager);
    }











    protected void invokeCausalProgress(com.example.marketplace.platform.api.JobHandler handler,com.example.marketplace.platform.api.Jobs.Job job) {
        try { handler.handle(job); }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException(failure);}
    }

    protected void heartbeatFinancialProbe(OwnedExchangeJob owned) {
        var actual=new com.example.marketplace.platform.infrastructure.JdbcJobs(jdbc,transactionManager,Clock.systemUTC());var before=actual.find(owned.job().id()).orElseThrow();assertEquals("RUNNING",before.status());assertEquals(owned.job().ownerToken(),before.ownerToken());assertEquals(owned.job().attempts(),before.attempts());var proof=jdbc.queryForList("SELECT to_jsonb(f)::text FROM marketplace.job_claim_fact f WHERE job_id=:id ORDER BY attempt",Map.of("id",before.id()),String.class);assertTrue(actual.heartbeat(before.id(),before.ownerToken()),"independent mutation probe requires a still-live actual owner");var renewed=actual.find(before.id()).orElseThrow();assertEquals(before.ownerToken(),renewed.ownerToken());assertEquals(before.attempts(),renewed.attempts());assertEquals(proof,jdbc.queryForList("SELECT to_jsonb(f)::text FROM marketplace.job_claim_fact f WHERE job_id=:id ORDER BY attempt",Map.of("id",before.id()),String.class));
    }
    protected void assertChildTakeoverClaims(List<String> previous,com.example.marketplace.platform.api.Jobs.Job old,java.nio.file.Path folder,String phase) throws Exception {
        var current=jdbc.queryForList("SELECT to_jsonb(f)::text FROM marketplace.job_claim_fact f ORDER BY job_id,attempt",Map.of(),String.class);assertTrue(current.containsAll(previous),"all prior immutable claim facts survive child takeover");var added=current.stream().filter(row->!previous.contains(row)).toList();assertEquals(1,added.size());var fact=authMapper.readTree(added.getFirst());assertEquals(old.id().toString(),fact.get("job_id").asText());assertEquals(old.attempts()+1,fact.get("attempt").asInt());assertEquals(old.ownerToken().toString(),fact.get("previous_owner").asText());assertEquals("TAKEOVER",fact.get("eligibility_kind").asText());var row=jdbc.queryForMap("SELECT * FROM marketplace.job_claim_fact WHERE job_id=:id AND attempt=:attempt",Map.of("id",old.id(),"attempt",old.attempts()+1));Instant at=old.leaseUntil().plusNanos(1000);assertEquals(old.leaseUntil(),((java.sql.Timestamp)row.get("required_at")).toInstant());assertEquals(at,((java.sql.Timestamp)row.get("claimed_at")).toInstant());assertEquals(at.plusSeconds(30),((java.sql.Timestamp)row.get("initial_lease_until")).toInstant());assertEquals(old.command().nextRunAt(),((java.sql.Timestamp)row.get("scheduled_at_snapshot")).toInstant());assertNotEquals(old.ownerToken(),row.get("owner_token"));String actual=java.nio.file.Files.readString(folder.resolve(phase+"-effect-claim"));assertTrue(actual.contains("id="+old.id()));assertTrue(actual.contains("attempts="+(old.attempts()+1)));assertTrue(actual.contains("ownerToken="+row.get("owner_token")));assertTrue(actual.contains("leaseUntil="+at.plusSeconds(30)));
    }
    protected void assertActualRecoveryClaims(List<String> previous,List<UUID> expectedJobs) throws Exception {
        var current=jdbc.queryForList("SELECT to_jsonb(f)::text FROM marketplace.job_claim_fact f ORDER BY job_id,attempt",Map.of(),String.class);assertTrue(current.containsAll(previous),"old claim facts remain byte-for-byte unchanged");var added=current.stream().filter(c->!previous.contains(c)).toList();assertEquals(expectedJobs.size(),added.size());var seen=new java.util.HashSet<UUID>();
        for(String row:added){var f=authMapper.readTree(row);UUID id=UUID.fromString(f.get("job_id").asText());assertTrue(expectedJobs.contains(id));assertTrue(seen.add(id));int attempt=f.get("attempt").asInt();var actual=authMapper.readValue(java.nio.file.Files.readString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"exchange-actual-worker-"+id+"-claim-"+attempt+".json")),com.example.marketplace.platform.api.Jobs.Job.class);var db=jdbc.queryForMap("SELECT * FROM marketplace.job_claim_fact WHERE job_id=:id AND attempt=:attempt",Map.of("id",id,"attempt",attempt));assertEquals(id,actual.id());assertEquals(attempt,actual.attempts());assertEquals(actual.ownerToken(),db.get("owner_token"));assertEquals(actual.command().nextRunAt(),((java.sql.Timestamp)db.get("scheduled_at_snapshot")).toInstant());Instant claimed=((java.sql.Timestamp)db.get("claimed_at")).toInstant(),required=((java.sql.Timestamp)db.get("required_at")).toInstant();assertFalse(claimed.isBefore(required));assertEquals(claimed.plusSeconds(30),actual.leaseUntil());assertEquals(actual.leaseUntil(),((java.sql.Timestamp)db.get("initial_lease_until")).toInstant());assertEquals(attempt-1,((Number)db.get("previous_attempt")).intValue());if(db.get("eligibility_kind").equals("READY")){assertNull(db.get("previous_owner"));assertFalse(claimed.isBefore(actual.command().nextRunAt()));}else{assertNotNull(db.get("previous_owner"));assertNotEquals(actual.ownerToken(),db.get("previous_owner"));}}
    }

    protected void assertPlannedCurrentUpgrade(Flyway migration,int oldVersion) {
        final int plannedCurrent=116;var expectedVersions=java.util.stream.IntStream.rangeClosed(oldVersion+1,plannedCurrent).mapToObj(Integer::toString).toList();var pending=java.util.Arrays.stream(migration.info().pending()).toList();assertEquals(expectedVersions,pending.stream().map(i->i.getVersion().toString()).toList(),"fixed source planned versions, never actual result as expectation");var expectedChecksums=new java.util.LinkedHashMap<String,Integer>();for(var info:pending){assertNotNull(info.getChecksum());expectedChecksums.put(info.getVersion().toString(),info.getChecksum());}
        try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"planned-upgrade-"+oldVersion+"-"+UUID.randomUUID()+".json"),authMapper.writeValueAsString(Map.of("from",oldVersion,"plannedCurrent",plannedCurrent,"expectedVersions",expectedVersions,"sourceChecksumsBeforeApply",expectedChecksums)));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        assertEquals(plannedCurrent-oldVersion,migration.migrate().migrationsExecuted);assertEquals(0,migration.migrate().migrationsExecuted);migration.validate();var target=new NamedParameterJdbcTemplate(migration.getConfiguration().getDataSource());var actualChecksums=new java.util.LinkedHashMap<String,Integer>();target.query("SELECT version,checksum FROM marketplace.flyway_schema_history WHERE success AND version IS NOT NULL AND version::integer>:old ORDER BY version::integer",Map.of("old",oldVersion),(r,n)->{actualChecksums.put(r.getString("version"),r.getInt("checksum"));return 0;});assertEquals(expectedChecksums,actualChecksums);assertEquals(0,target.queryForObject("SELECT count(*) FROM marketplace.job WHERE claim_checked_at IS NOT NULL",Map.of(),Integer.class),"legacy observed decisions remain absent");assertEquals(0,target.queryForObject("SELECT count(*) FROM marketplace.job_claim_fact",Map.of(),Integer.class),"historical due facts are never invented");
    }
    protected List<Map<String,Object>> legacyJobObservationProjection(String table,List<Map<String,Object>> actual) {
        if(!table.equals("job")){return actual;}return actual.stream().map(row->{assertTrue(row.containsKey("claim_checked_at"));assertNull(row.get("claim_checked_at"));var old=new java.util.LinkedHashMap<>(row);old.remove("claim_checked_at");return (Map<String,Object>)old;}).toList();
    }


    protected com.example.marketplace.claim.api.Exchanges exchangesWithRequestObservation(Instant raw) {
        var observed=exchangeObservedJdbcAt(raw,new java.util.concurrent.atomic.AtomicInteger());var queries=context.getBean(com.example.marketplace.order.api.OrderQueries.class);var stocks=context.getBean(com.example.marketplace.inventory.api.ExchangeReservations.class);var physical=context.getBean(com.example.marketplace.order.api.PhysicalOrders.class);var applications=new com.example.marketplace.order.infrastructure.JdbcExchangeApplications(observed,queries,orders(),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),stocks,physical);return new com.example.marketplace.claim.infrastructure.JdbcExchanges(observed,queries,confirmations(),applications,context.getBean(com.example.marketplace.catalog.api.ExchangeOffers.class),stocks,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,physical);
    }
    protected record TimedExchangeRuntime(com.example.marketplace.claim.infrastructure.JdbcExchangeDispatches dispatch,com.example.marketplace.order.api.ExchangeApplications applications,com.example.marketplace.order.api.PhysicalOrders physical,com.example.marketplace.fulfillment.api.ExchangeShipments shipments,com.example.marketplace.claim.infrastructure.JdbcExchanges exchanges) { }
    protected TimedExchangeRuntime timedExchangeRuntime(JobClock clock,com.example.marketplace.platform.infrastructure.JdbcJobs jobs) {
        var physical=new com.example.marketplace.order.infrastructure.JdbcPhysicalOrders(jdbc,context.getBean(com.example.marketplace.inventory.api.PhysicalStocks.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),clock);var stock=new com.example.marketplace.inventory.infrastructure.JdbcExchangeReservations(jdbc,clock);var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class),new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);var applications=new com.example.marketplace.order.infrastructure.JdbcExchangeApplications(jdbc,queries,orders(),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),stock,physical);var shipments=new com.example.marketplace.fulfillment.infrastructure.JdbcExchangeShipments(jdbc,context.getBean(com.example.marketplace.platform.api.Outbox.class),clock);var exchanges=exchangesAt(clock);var dispatch=new com.example.marketplace.claim.infrastructure.JdbcExchangeDispatches(jdbc,applications,physical,stock,context.getBean(com.example.marketplace.inventory.api.PhysicalStocks.class),shipments,context.getBean(com.example.marketplace.catalog.api.ExchangeOffers.class),exchanges,jobs,jobs,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,clock);return new TimedExchangeRuntime(dispatch,applications,physical,shipments,exchanges);
    }
    protected OwnedExchangeJob ownTimedExchangeJob(UUID id,JobClock clock) {
        var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();if(!clock.instant().isAfter(saved.nextRunAt())){clock.now=saved.nextRunAt().plusNanos(1000);}var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,clock);var actual=jobs.claim(java.util.Set.of(saved.command().type())).orElseThrow();assertEquals(id,actual.id());return new OwnedExchangeJob(jobs,actual);
    }






    protected PhysicalReturn observedReplacementReturn(ReturnParties f) throws Exception {
        var actual=context.getBean(com.example.marketplace.order.api.ReturnApplications.class);var observed=new com.example.marketplace.order.api.ReturnApplications(){
            public Current current(UUID id){return actual.current(id);}public Eligibility eligibility(String number,com.example.marketplace.order.api.OrderQueries.Authority authority){return actual.eligibility(number,authority);}
            public Accepted begin(String number,com.example.marketplace.order.api.OrderQueries.Authority authority,UUID id,UUID hold,UUID cause,List<com.example.marketplace.order.api.OrderConfirmations.Unit> units){var accepted=actual.begin(number,authority,id,hold,cause,units);try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-replacement-return-real-application-clock.json"),authMapper.writeValueAsString(Map.of("actualAccepted",accepted,"observedJvmNow",Instant.now(),"observedDatabaseNow",jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant(),"actualClockClass",org.springframework.test.util.ReflectionTestUtils.getField(actual,"clock").getClass().getName(),"withinActualWindow",accepted.source().units().stream().map(u->com.example.marketplace.claim.domain.ReturnRules.beforeVerifiedDeliveryDeadline(u.physical()==null?u.deliveredAt():u.physical().delivery().deliveredAt(),accepted.requestedAt(),accepted.windowSeconds())).toList())));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}return accepted;}
            public Accepted lockOwner(String number,com.example.marketplace.order.api.OrderQueries.Authority authority,UUID id){return actual.lockOwner(number,authority,id);}public Accepted lockSeller(com.example.marketplace.order.api.OrderQueries.Authority authority,UUID id){return actual.lockSeller(authority,id);}public Recorded lockRecorded(UUID id){return actual.lockRecorded(id);}
        };var api=new com.example.marketplace.claim.infrastructure.JdbcReturns(context.getBean(com.example.marketplace.order.api.OrderQueries.class),confirmations(),observed,context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),jdbc,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class));var raw=f.shipment().units().getFirst();var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),new com.example.marketplace.claim.api.Returns.Request("physical-request",confirmations().get(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer())).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(raw.orderItemId(),raw.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"actual replacement source RETURN with observed application clock"));api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("physical-approve",0L,"actual replacement seller approval"));var received=context.getBean(com.example.marketplace.claim.api.ReturnCollections.class).receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("physical-receive",1L,List.of(new com.example.marketplace.claim.api.ReturnCollections.Unit(raw.orderItemId(),raw.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)),"actual replacement B received"));return new PhysicalReturn(f,accepted,received);
    }

    protected void runExchangeActualJob(UUID id) throws Exception {
        var saved=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(id).orElseThrow();Instant now=java.util.stream.Stream.of(Instant.now(),saved.nextRunAt().plusNanos(1000),jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant()).max(Instant::compareTo).orElseThrow();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(now,ZoneOffset.UTC));var handlers=context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values().stream().map(handler->new com.example.marketplace.platform.api.JobHandler() {
            public String type() { return handler.type(); }
            public Result handle(com.example.marketplace.platform.api.Jobs.Job job) throws Exception { try { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"exchange-actual-worker-"+job.id()+"-claim-"+job.attempts()+".json"),authMapper.writeValueAsString(job));return handler.handle(job); }catch(Exception failure) { var text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"exchange-actual-worker-"+job.id()+"-exception.txt"),text.toString());throw failure; } }
        }).map(com.example.marketplace.platform.api.JobHandler.class::cast).toList();try(var engine=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,handlers)){assertTrue(engine.runOnce());}var actual=jobs.find(id).orElseThrow();java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"exchange-actual-worker-"+id+".json"),authMapper.writeValueAsString(actual));assertEquals("SUCCEEDED",actual.status(),"actual exact exchange job must finish; preserved state/exception: "+actual.lastError());
    }

    protected record ExchangeReviewFixture(ReturnParties parties,com.example.marketplace.claim.api.Exchanges.Receipt accepted) { }
    protected ExchangeReviewFixture exchangeReviewFixture(String suffix,int count,boolean received) throws Exception {
        var f=returnParties(suffix);var selected=f.shipment().units().stream().limit(count).toList();assertEquals(count,selected.size());var accepted=exchangeRequest(f,"review-request-"+suffix,selected);context.getBean(com.example.marketplace.claim.api.Exchanges.class).approve(accepted.exchangeId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Exchanges.Command("review-approve-"+suffix,0L,"actual review source and target approval"));if(received){context.getBean(com.example.marketplace.claim.api.ExchangePhysical.class).receive(accepted.exchangeId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ExchangePhysical.CollectionCommand("review-receive-"+suffix,1L,selected.stream().map(u->new com.example.marketplace.claim.api.ExchangePhysical.Received(u.orderItemId(),u.ordinal(),com.example.marketplace.claim.api.ExchangePhysical.Status.RECEIVED)).toList(),"actual originals received for review boundary"));}return new ExchangeReviewFixture(f,accepted);
    }
    protected void assertExchangeSqlMutationRejected(String label,Runnable work,String... states) throws Exception {
        var before=exchangeParticipants();RuntimeException error=null;try{work.run();}catch(RuntimeException failure){error=failure;}var after=exchangeParticipants();Throwable root=error;while(root!=null&&root.getCause()!=null){root=root.getCause();}String state=root instanceof java.sql.SQLException sql?sql.getSQLState():"NOT_SQL";var evidence=new java.util.LinkedHashMap<String,Object>();evidence.put("label",label);evidence.put("actualCommitted",error==null);evidence.put("actualSqlState",state);evidence.put("actualRoot",root==null?"NONE":root.toString());evidence.put("before",before);evidence.put("after",after);java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),label+".json"),authMapper.writeValueAsString(evidence));assertNotNull(error,"forged real graph must reject; original before/after evidence preserved: "+label);assertTrue(root instanceof java.sql.SQLException,root.toString());assertTrue(java.util.Arrays.asList(states).contains(state),state+" "+root);assertEquals(before,after,"every participant and actual balance must roll back: "+label);
    }





    protected void awaitExchangeBusinessLock(String table,String label) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("wait", "awaitExchangeBusinessLock")) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);List<Map<String,Object>> waits=List.of();while(System.nanoTime()<end){jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");waits=jdbc.queryForList("SELECT pid,query,pg_blocking_pids(pid)::text AS blockers FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock'",Map.of());if(waits.stream().anyMatch(r->r.get("query").toString().contains("marketplace."+table))){break;}Thread.sleep(20);}java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),label+"-lock-waits.json"),authMapper.writeValueAsString(waits));assertTrue(waits.stream().anyMatch(r->r.get("query").toString().contains("marketplace."+table)),"actual final business wait must be observed before changing the authority clock: "+table);
    
        }
    }









    protected com.example.marketplace.inventory.api.Reservations reservations() {
        return context.getBean(com.example.marketplace.inventory.api.Reservations.class);
    }
    protected UUID stockedSku(String suffix,long quantity) {
        var f=skuFixture(suffix); UUID owner=(UUID)f.get("owner");
        var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst().id();
        context.getBean(com.example.marketplace.inventory.api.Inventory.class).adjust(owner,product.id(),sku,false,
            new com.example.marketplace.inventory.api.Inventory.Change(quantity,0L,"예약 검사 입고",UUID.randomUUID()));
        return sku;
    }
    protected com.example.marketplace.inventory.api.Reservations.Request reservationRequest(UUID sku,long quantity) {
        return new com.example.marketplace.inventory.api.Reservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),sku,quantity,
            Instant.now().plusSeconds(900).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    protected void stockEquals(UUID sku,long onHand,long reserved,long version) {
        var row=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",sku));
        assertEquals(onHand,((Number)row.get("on_hand")).longValue());
        assertEquals(reserved,((Number)row.get("reserved")).longValue());
        assertEquals(version,((Number)row.get("version")).longValue());
    }
    protected String reservationAttempt(Runnable action) {
        try { action.run();return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException rejected) { return ""+rejected.status(); }
    }


    protected void reservationCrossKeys(boolean orderItems) throws Exception {
        String suffix=orderItems?"items":"ids";UUID a=stockedSku("cross-"+suffix+"-a",10),b=stockedSku("cross-"+suffix+"-b",10);
        UUID x=UUID.randomUUID(),y=UUID.randomUUID(),checkout=UUID.randomUUID();Instant expiry=Instant.now().plusSeconds(900);
        var left=List.of(new com.example.marketplace.inventory.api.Reservations.Request(orderItems?UUID.randomUUID():x,checkout,orderItems?x:UUID.randomUUID(),a,1,expiry),
            new com.example.marketplace.inventory.api.Reservations.Request(orderItems?UUID.randomUUID():y,checkout,orderItems?y:UUID.randomUUID(),a,1,expiry));
        var right=List.of(new com.example.marketplace.inventory.api.Reservations.Request(orderItems?UUID.randomUUID():y,checkout,orderItems?y:UUID.randomUUID(),b,1,expiry),
            new com.example.marketplace.inventory.api.Reservations.Request(orderItems?UUID.randomUUID():x,checkout,orderItems?x:UUID.randomUUID(),b,1,expiry));
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.p4e_insert_barrier() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock_shared(187041); RETURN NEW; END $$");
        jdbc.getJdbcTemplate().execute("CREATE TRIGGER p4e_insert_barrier AFTER INSERT ON marketplace.inventory_reservation FOR EACH ROW EXECUTE FUNCTION marketplace.p4e_insert_barrier()");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.query("SELECT pg_advisory_xact_lock(187041)",Map.of(),(r,i)->r.getObject(1));
                futures.add(pool.submit(()->reservationAttempt(()->reservations().reserve(left))));
                futures.add(pool.submit(()->reservationAttempt(()->reservations().reserve(right))));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waiting=0;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event='advisory'",Map.of(),Integer.class);
                    if(waiting==2) { break; } try { Thread.sleep(20); } catch(InterruptedException ex) { throw new IllegalStateException(ex); }
                }
                assertEquals(2,waiting,"both transactions must reach the key/insert barrier before it opens");
            });
            assertThat(List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("ok","409");
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE checkout_id=:id",Map.of("id",checkout),Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE sku_id IN (:a,:b)",Map.of("a",a,"b",b),Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT sum(reserved) FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b)",Map.of("a",a,"b",b),Long.class));
            assertEquals(List.of(1L,3L),jdbc.queryForList("SELECT version FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b) ORDER BY version",Map.of("a",a,"b",b),Long.class));
            assertEquals(20,jdbc.queryForObject("SELECT sum(on_hand) FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b)",Map.of("a",a,"b",b),Long.class));
        } finally {
            jdbc.getJdbcTemplate().execute("DROP TRIGGER p4e_insert_barrier ON marketplace.inventory_reservation");
            jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.p4e_insert_barrier()");
        }
    }













    protected byte[] mediaPng() throws Exception {
        var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out); return out.toByteArray();
    }







    protected HttpResponse<String> mediaUpload(HttpClient client,String path,byte[] bytes,String filename,long version,String alt,boolean csrf) throws Exception {
        String boundary="p04c-boundary"; var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n"+version+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"alt\"\r\n\r\n"+alt+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","multipart/form-data; boundary="+boundary).PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if(csrf) { request.header("X-CSRF-TOKEN",csrfToken(client)); }
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }









    protected Map<String,Object> skuFixture(String suffix) {
        UUID owner=activeMember("p4b-"+suffix+"@example.com"), category=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p4b-"+suffix,"P04B","intro","address","","","",true,0L));
        jdbc.update("INSERT INTO marketplace.catalog_category(id,name) VALUES(:id,:name)",Map.of("id",category,"name","P04B "+suffix));
        var product=context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,null,new com.example.marketplace.catalog.api.Products.Input(category,"p4b-"+suffix,"SKU 상품","","DRAFT",0L));
        return Map.of("owner",owner,"product",product);
    }

































    protected int runBootstrapCommand(UUID user, String database, String confirmation, boolean forbiddenArgument) throws Exception {
        try (var contractTimingScope = ContractTiming.measure("process-start", "runBootstrapCommand")) {
        var classes = java.nio.file.Path.of(MarketplaceApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var build = classes.getParent();
        var jar = build.resolve("marketplace-api-0.0.1-SNAPSHOT.jar");
        var command = new java.util.ArrayList<>(List.of(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dloader.main=com.example.marketplace.identity.infrastructure.InitialAdminCommand", "-cp", jar.toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
        if (forbiddenArgument) { command.add("--spring.flyway.enabled=true"); }
        var output = java.nio.file.Files.createTempFile(build, "bootstrap-", ".txt");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("DB_URL", System.getenv("D2_DB_URL"));
        builder.environment().put("DB_USERNAME", System.getenv("D2_DB_USERNAME"));
        builder.environment().put("DB_PASSWORD", System.getenv("D2_DB_PASSWORD"));
        builder.environment().put("BOOTSTRAP_USER_ID", user.toString());
        builder.environment().put("BOOTSTRAP_EXPECTED_DATABASE", database);
        builder.environment().put("BOOTSTRAP_CONFIRM", confirmation);
        var process = builder.start();
        if (!ContractTiming.awaitProcess(process,45,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Bootstrap command timed out"); }
        String log = java.nio.file.Files.readString(output);
        assertFalse(log.contains("Tomcat started"));
        assertFalse(log.contains(System.getenv("D2_DB_PASSWORD")));
        if (process.exitValue() == 0) { assertTrue(log.contains("INITIAL_ADMIN_COMPLETED")); }
        return process.exitValue();
    
        }
    }





    protected String resetMail(String email) {
        return resetMailbox.messages().stream().filter(item -> item.recipient().equals(email))
                .reduce((first, second) -> second).orElseThrow().token();
    }

    protected java.net.http.HttpResponse<String> upload(java.net.http.HttpClient client,String path,String filename,
            byte[] bytes,String csrf) throws Exception {
        String boundary="review"+UUID.randomUUID().toString().replace("-","");
        var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+
                "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        body.write(bytes);
        body.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","multipart/form-data; boundary="+boundary);
        if (csrf!=null) {request.header("X-CSRF-TOKEN",csrf);}
        return client.send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    protected HttpResponse<String> accountRequest(HttpClient browser, String method, String path, Object body, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(body)));
        if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }



    protected AddressInput addressInput(boolean isDefault) {
        return new AddressInput("집", "구매자", "010-1234-5678", "01234", "서울 주소", "101호", isDefault);
    }











    protected PasswordRecoveryService recoveryAt(Instant time, VerificationMailbox targetMailbox) {
        return new PasswordRecoveryService(new JdbcPasswordRecoveryStore(jdbc), passwordHasher, targetMailbox,
                Clock.fixed(time, ZoneOffset.UTC), transactionManager);
    }









    protected HttpResponse<String> authJsonPost(HttpClient browser, String path, Map<String, String> body, String csrf)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(body)));
        if (csrf != null) { builder.header("X-CSRF-TOKEN", csrf); }
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }


    @org.springframework.web.bind.annotation.RestController
    @org.springframework.context.annotation.Profile("c3-fixture")
    static class TestMailboxEndpoint {
        private final LocalTestMailbox mailbox;
        private final LocalPasswordResetMailbox resetMailbox;
        TestMailboxEndpoint(LocalTestMailbox mailbox, LocalPasswordResetMailbox resetMailbox) {
            this.mailbox = mailbox; this.resetMailbox = resetMailbox;
        }
        @org.springframework.web.bind.annotation.GetMapping("/__test/reset-mail")
        Map<String, String> resetToken(@org.springframework.web.bind.annotation.RequestParam String email,
                jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control", "no-store");
            return Map.of("token", resetMailbox.messages().stream().filter(item -> item.recipient().equals(email))
                    .reduce((first, second) -> second).orElseThrow().token());
        }
        @org.springframework.web.bind.annotation.GetMapping("/__test/mail")
        Map<String, String> token(@org.springframework.web.bind.annotation.RequestParam String email,
                jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control", "no-store");
            return Map.of("token", mailbox.messages().stream().filter(item -> item.recipient().equals(email))
                    .findFirst().orElseThrow().token());
        }
    }

    protected void runAuthBrowsersIfRequested() throws Exception {
        try (var contractTimingScope = ContractTiming.measure("browser", "runAuthBrowsersIfRequested")) {
        if (!"true".equals(System.getenv("A3_RUN_BROWSER"))) { return; }
        var browseSeed=browseFixture("browser",1000,0);
        var browseProduct=(com.example.marketplace.catalog.api.Products.Product)browseSeed.get("product");

        for(int i=0;i<22;i++) {
            UUID id=i==0?browseProduct.id():UUID.randomUUID();
            if(i>0) {
                jdbc.update("INSERT INTO marketplace.catalog_product(id,store_id,category_id,slug,title,description,state) SELECT :id,store_id,category_id,:slug,:title,'browse fixture','ACTIVE' FROM marketplace.catalog_product WHERE id=:original",Map.of("id",id,"slug","p05-browser-"+i,"title","P05 browser "+String.format("%02d",i),"original",browseProduct.id()));
                jdbc.update("INSERT INTO marketplace.catalog_sku(id,product_id,code,price,active) VALUES(:id,:id,:code,:price,true)",Map.of("id",id,"code","p05-browser-"+i,"price",1000+i*100));
                jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand) VALUES(:id,5)",Map.of("id",id));
            } else { jdbc.update("UPDATE marketplace.catalog_product SET slug='p05-browser-0',title='P05 browser 00' WHERE id=:id",Map.of("id",id)); }
        }
        var purchaseSeed=browseFixture("purchase-second",2400,10);
        UUID purchaseProduct=((com.example.marketplace.catalog.api.Products.Product)purchaseSeed.get("product")).id();
        jdbc.update("UPDATE marketplace.catalog_product SET slug='p05-c-second',title='P05-C second' WHERE id=:id",Map.of("id",purchaseProduct));
        jdbc.update("UPDATE marketplace.seller_store SET name='P05-C second store' WHERE id=(SELECT store_id FROM marketplace.catalog_product WHERE id=:id)",Map.of("id",purchaseProduct));
        var inputSeed=browseFixture("input-flow",1500,10);
        UUID inputProduct=((com.example.marketplace.catalog.api.Products.Product)inputSeed.get("product")).id();
        jdbc.update("UPDATE marketplace.catalog_product SET slug='p05-d-input',title='P05-D input' WHERE id=:id",Map.of("id",inputProduct));
        var inputSkus=context.getBean(com.example.marketplace.catalog.api.Skus.class);
        for(int amount:List.of(1500,2500)) {
            var inputOption=inputSkus.save((UUID)inputSeed.get("owner"),inputProduct,null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("크기",amount==1500?"작은 것":"큰 것")),(long)amount,true,0L));
            jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand) VALUES(:id,10)",Map.of("id",inputOption.id()));
        }
        for(String project:List.of("desktop","mobile")) {
            for(String scenario:List.of("create","guest","conflict","historyguest","shipmentmember","shipmentguest","shipmentkeyboard","shipmentsplit","shipmenteditfailure","confirmationmember","confirmationguest","confirmationfailure","confirmationlate","reviewmember","questionmember","gmember","gguest","ghistory","gpost","gpostguest")) {
                UUID buyer=(scenario.equals("guest")||scenario.equals("historyguest")||scenario.equals("shipmentguest")||scenario.equals("confirmationguest")||scenario.equals("gguest")||scenario.equals("gpostguest"))?null:activeMember("order-"+scenario+"-"+project+"@example.com");
                for(String side:List.of("a","b","c")) {
                    var fixture=browseFixture("order-"+scenario+"-"+project+"-"+side,side.equals("a")?10000:20000,10);
                    if((scenario.equals("gguest")||scenario.equals("ghistory"))&&!side.equals("c")) { var now=Instant.now();var pi=new com.example.marketplace.promotion.api.Promotions.Input("G 공개 행사 "+side,"PRODUCT",List.of(((com.example.marketplace.catalog.api.Products.Product)fixture.get("product")).id()),now.minusSeconds(10),now.plusSeconds(3600),"FIXED",1000L,null,"DRAFT",0L);var promo=promotions().save((UUID)fixture.get("owner"),null,pi);promotions().save((UUID)fixture.get("owner"),promo.id(),new com.example.marketplace.promotion.api.Promotions.Input(pi.title(),pi.scope(),pi.productIds(),pi.startsAt(),pi.endsAt(),pi.kind(),pi.value(),pi.cap(),"ACTIVE",0L)); }
                    if((scenario.equals("create")||scenario.equals("gmember")||scenario.equals("ghistory")||scenario.equals("gpost"))&&!side.equals("c")) {var template=couponTemplate((UUID)fixture.get("owner"),"주문 할인 "+side,5);coupons().claim(buyer,template.id());}
                }
            }
            seedConfirmationBrowserOrders(project);
            supportFixture("b-ui-"+project,"SUPPORT");
            seedPendingShipmentBrowserOrders(project);
            seedCancellationBrowserOrders(project);
            seedReturnBrowserOrders(project);
            seedExchangeBrowserOrders(project);
            seedRefundBrowserOrders(project);
            seedPostPurchaseBrowserOrders(project);
            for(int width:List.of(375,768,1440)) { activeMember("input-"+project+"-"+width+"@example.com"); }
        }
        var cMembers = new java.util.ArrayList<UUID>();
        // Keep the first operator page full so browser journeys must follow pagination,
        // regardless of random store UUIDs or the order of other integration tests.
        for (int index = 1; index <= 20; index++) {
            UUID owner = activeMember("browser-page-fixture-" + index + "@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", owner));
            cMembers.add(owner);
            jdbc.update("INSERT INTO marketplace.seller_store(id,owner_id,slug,name,introduction,address,notice,image_key,image_alt) "
                    + "VALUES(:id,:owner,:slug,'Pagination fixture','Test only','Test address','','','')",
                    Map.of("id", new UUID(0, index), "owner", owner, "slug", "browser-page-fixture-" + index));
        }
        for(String project:List.of("desktop","mobile")) { for(String mode:List.of("unknown","failed","lost","zero")) { var f=paymentFixture("g-browser-"+project+"-"+mode,mode.equals("zero"));if(!mode.equals("zero")) { mockPayment(f,mode.equals("unknown")?"QUERY_FAILURE":mode.equals("failed")?"REJECT":"LOST_RESPONSE","SUCCESS"); } } }
        for (String project : List.of("desktop", "mobile")) { for(String fMode:List.of("success","failed","unknown")) { var f=paymentFixture("f-browser-"+project+"-"+fMode,false);mockPayment(f,fMode.equals("failed")?"REJECT":fMode.equals("unknown")?"QUERY_FAILURE":"SUCCESS","SUCCESS");payments().approve(f.order().orderNumber(),f.authority()); } for(String eMode:List.of("success","lost")) { var e=sandboxFixture("browser-"+project+"-"+eMode,false);var data=tossFixture(e,"fixture-e-browser-"+project+"-"+eMode,"DONE");if(eMode.equals("lost")) { data.put("lose",true); } } for(String mode:List.of("zero","lost","session")) { var pf=paymentFixture("browser-"+project+"-"+mode,mode.equals("zero"));if(mode.equals("lost")) { mockPayment(pf,"LOST_RESPONSE","SUCCESS"); } }
            var rf=browseFixture("reservation-browser-"+project,10000,10);UUID rm=activeMember("reservation-member-"+project+"@example.com");activeMember("reservation-other-"+project+"@example.com");var rt=couponTemplate((UUID)rf.get("owner"),"예약 확인 쿠폰",5);coupons().claim(rm,rt.id());
            var qa=browseFixture("quote-browser-"+project+"-a",30000,10);browseFixture("quote-browser-"+project+"-b",40000,10);UUID qm=activeMember("quote-member-"+project+"@example.com"),qo=(UUID)qa.get("owner");var qt=coupons().saveTemplate(qo,null,couponInput("견적 3000원",3000,5,"DRAFT",0));qt=coupons().saveTemplate(qo,qt.id(),couponInput("견적 3000원",3000,5,"ACTIVE",0));coupons().claim(qm,qt.id());
            browseFixture("promotion-browser-"+project,10001,10);
            var couponSeller=browseFixture("coupon-browser-"+project,10001,10);
            UUID couponMember=activeMember("coupon-member-"+project+"@example.com");var expiredTemplate=couponTemplate((UUID)couponSeller.get("owner"),"만료 안내 "+project,2);var expiredCoupon=coupons().claim(couponMember,expiredTemplate.id());
            jdbc.update("UPDATE marketplace.issued_coupon SET ends_at=clock_timestamp()-interval '1 second' WHERE id=:id",Map.of("id",expiredCoupon.id()));
            for(int side=0;side<2;side++){var sellerFixture=browseFixture("coupon-twin-"+project+"-"+side,100,1);UUID sellerId=(UUID)sellerFixture.get("owner");var twin=couponTemplate(sellerId,"같은 이름 "+project,10);coupons().claim(couponMember,twin.id());if(side==1){jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE id=:id",Map.of("id",twin.storeId()));}}

            activeMember("wish-browser-"+project+"@example.com");
            UUID cartBuyer=activeMember("cart-browser-"+project+"@example.com");
            UUID cartSku=jdbc.queryForObject("SELECT k.id FROM marketplace.catalog_sku k JOIN marketplace.catalog_product p ON p.id=k.product_id WHERE p.slug=:slug",Map.of("slug","p05-browser-1"),UUID.class);
            carts().set(cartBuyer,cartSku,98,0);
            UUID catalogSeller=activeMember("p4-seller-"+project+"@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",catalogSeller));
            context.getBean(com.example.marketplace.seller.api.Stores.class).save(catalogSeller,null,new com.example.marketplace.seller.api.Stores.Input("p4-browser-"+project,"P04 browser","intro","address","","","",true,0L));
            cMembers.add(catalogSeller);
            UUID seller = activeMember("c-seller-" + project + "@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", seller));
            UUID admin = activeMember("c-admin-" + project + "@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", admin));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')", Map.of("id", admin));
            cMembers.add(seller); cMembers.add(admin);
            UUID lifecycleSeller=activeMember("p3c-seller-"+project+"@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",lifecycleSeller));
            cMembers.add(lifecycleSeller);
            UUID reviewer = activeMember("p3-admin-" + project + "@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", reviewer));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", reviewer));
            cMembers.add(reviewer);
        }
        try {
        var root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        var node = root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe");
        var builder = new ProcessBuilder(node.toString(), "node_modules/@playwright/test/cli.js", "test",
                "--config=auth-e2e/playwright.config.ts").directory(root.resolve("frontend").toFile());
        if("1".equals(System.getenv("P09_F_BROWSER_ONLY"))) { builder.command().add("refund-reconciliation.spec.ts"); }
        if("1".equals(System.getenv("P10_B_BROWSER_ONLY"))) { builder.command().add("questions-support.spec.ts"); }
        if("1".equals(System.getenv("P10_A_BROWSER_ONLY"))) { builder.command().add("content.spec.ts"); }
        String gSelection=System.getenv("P09_G_BROWSER_ONLY");
        if(gSelection!=null&&!gSelection.isBlank()) { if(!List.of("1","member-guest","member").contains(gSelection)) { throw new IllegalStateException("Unknown G browser diagnostic selection"); }builder.command().add("post-purchase-complete.spec.ts");if(gSelection.equals("member-guest")) { builder.command().add("--grep=G (member actual purchase partial refund and remaining multi-seller delivery confirmation preserve snapshot money and one-time effects|fresh guest browser actual return seller inspection consent and remaining confirmation retain GAS and financial boundaries)$"); }if(gSelection.equals("member")) { builder.command().add("--grep=G member actual purchase partial refund and remaining multi-seller delivery confirmation preserve snapshot money and one-time effects$"); } }
        if("1".equals(System.getenv("P09_C_BROWSER_ONLY"))) { builder.command().add("order-cancellation.spec.ts"); }
        if("1".equals(System.getenv("P09_D_BROWSER_ONLY"))) { builder.command().add("order-return.spec.ts"); }
        if("return-layout".equals(System.getenv("P09_D_BROWSER_ONLY"))) { builder.command().addAll(List.of("order-return.spec.ts","--grep","currentBuyerWithdrawalOwnSellerDecisionsForeignSellerDenialAndKeyboardRequestKeepTerminalHistory$")); }
        if("1".equals(System.getenv("P09_E_BROWSER_ONLY"))) { builder.command().add("order-exchanges.spec.ts"); }
        if("retry-boundaries".equals(System.getenv("P09_C_BROWSER_ONLY"))) { builder.command().addAll(List.of("guest-lookup.spec.ts","order-cancellation.spec.ts","--grep","직접 정한 비밀번호로 다른 브라우저에서 해당 주문 묶음만 조회한다|latestUnknownAfterDefinitiveFailureBlocksWithdrawalAndContradictoryCompletionIsRejected")); }
        builder.environment().put("A3_API_PORT", Integer.toString(port));
        builder.environment().put("PATH", node.getParent() + java.io.File.pathSeparator + System.getenv("PATH"));
        builder.redirectErrorStream(true).redirectOutput(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"), "browser.txt").toFile());
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"e-browser-auth-preparation-final.json"),authMapper.writeValueAsString(authPostObservations));
        long browserStarted=System.nanoTime();var process = builder.start();recordBrowserTiming("child:started",browserStarted,process.pid());
        if (!ContractTiming.awaitProcess(process,390,TimeUnit.SECONDS)) {
            recordBrowserTiming("child:timeout",browserStarted,process.pid());
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Auth browser verification timed out");
        }
        recordBrowserTiming("child:exited",browserStarted,process.exitValue());
        assertEquals(0, process.exitValue(), "See isolated evidence browser.txt");
        } finally {
            for (UUID id : cMembers) {
                jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", id));
                jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role IN ('ADMIN','SELLER')", Map.of("id", id));
            }
        }
    
        }
    }




    protected final tools.jackson.databind.json.JsonMapper authMapper = tools.jackson.databind.json.JsonMapper.builder().build();

    protected static void recordBrowserTiming(String phase,long started,long value) throws java.io.IOException {
        String line=new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("phase",phase,"at",Instant.now().toString(),"elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"value",value));java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"browser-child-timing.ndjson"),line+System.lineSeparator(),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
    }
    protected final ThreadLocal<String> fixtureClientSource=ThreadLocal.withInitial(()->"127.0.0.2");
    protected final java.util.List<Map<String,Object>> authPostObservations=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    protected HttpClient authBrowser() {
        // Isolate Java contract traffic from Playwright's 127.0.0.1 rate window without relaxing production limits.
        return HttpClient.newBuilder().localAddress(new java.net.InetSocketAddress(fixtureClientSource.get(), 0).getAddress()).cookieHandler(new java.net.CookieManager(
                null, java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    }

    protected HttpResponse<String> authRequest(HttpClient browser, String path, String form, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (form == null) { request.GET(); } else {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
        }
        if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        var response=browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if(form!=null&&path.startsWith("/api/auth/")){authPostObservations.add(Map.of("source",fixtureClientSource.get(),"path",path,"status",response.statusCode(),"at",Instant.now().toString()));}
        return response;
    }

    protected String csrfToken(HttpClient browser) throws Exception {
        var response = authRequest(browser, "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode());
        return authMapper.readTree(response.body()).get("token").asText();
    }

    protected String loginForm(String email, String password) {
        return "email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                + "&password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
    }

    protected record FixtureSignup(UUID id,SignupService service,LocalTestMailbox inbox) { }
    protected FixtureSignup prepareFixtureSignup(String email) {
        try (var contractTimingScope = ContractTiming.measure("preparation", "prepareFixtureSignup")) {
        var inbox=new LocalTestMailbox(Clock.systemUTC());var service=new SignupService(identityStore,passwordHasher,inbox,Clock.systemUTC(),transactionManager);UUID id=service.register(email,SIGNUP_PASSWORD);FIXTURE_ACCOUNT_MAILBOXES.put(id,inbox);return new FixtureSignup(id,service,inbox);
    
        }
    }

    protected UUID activeMember(String email) {
        try (var contractTimingScope = ContractTiming.measure("preparation", "activeMember")) {
        var prepared=prepareFixtureSignup(email);
        prepared.service().verifyEmail(prepared.inbox().messages().getFirst().token());
        return prepared.id();
    
        }
    }

    protected String cookieValue(HttpClient browser) {
        return ((java.net.CookieManager) browser.cookieHandler().orElseThrow()).getCookieStore().getCookies()
                .stream().filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
    }














    protected static final String SIGNUP_PASSWORD = " A1-private-passphrase-가나다 ";
    @Autowired protected PasswordRecoveryOperations recovery;
    @Autowired protected LocalPasswordResetMailbox resetMailbox;
    @Autowired protected AccountOperations accountOperations;
    @Autowired
    protected SignupOperations signup;
    @Autowired
    protected IdentityStore identityStore;
    @Autowired
    protected PasswordHasher passwordHasher;
    @Autowired
    protected LocalTestMailbox mailbox;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @LocalServerPort
    protected int port;
    @Autowired
    protected ApplicationContext context;
    @Autowired
    protected MBeanServer mBeanServer;
    @Autowired
    protected DataSource source;
    @Autowired
    protected NamedParameterJdbcTemplate jdbc;
    @Autowired
    protected AccountRepository accounts;
    @Autowired
    protected TransferService transfers;
    @Autowired
    protected BatchService batches;
    @Autowired
    protected Flyway flyway;

    protected LocalTestMailbox.Message mail(String email) {
        var shared=mailbox.messages().stream().filter(item -> item.recipient().equals(email)).reduce((first,last)->last);
        return shared.orElseGet(()->FIXTURE_ACCOUNT_MAILBOXES.values().stream().flatMap(inbox->inbox.messages().stream()).filter(item->item.recipient().equals(email)).reduce((first,last)->last).orElseThrow());
    }

    protected SignupService serviceAt(Instant now, VerificationMailbox targetMailbox) {
        return new SignupService(identityStore, passwordHasher, targetMailbox,
                Clock.fixed(now, ZoneOffset.UTC), transactionManager);
    }





    protected List<String> race(Callable<String> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                start.await();
                return action.call();
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }











    protected void verifyExpiryAfterLockWait(String table) throws Exception {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        String email = "r1-" + table + "@example.com";
        UUID member = serviceAt(issuedAt, mailbox).register(email, SIGNUP_PASSWORD);
        String token = mail(email).token();
        var current = new java.util.concurrent.atomic.AtomicReference<>(issuedAt.plusSeconds(1799));
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var service = new SignupService(identityStore, passwordHasher, mailbox, moving, transactionManager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<String> result;
            try (var blocker = source.getConnection()) {
                blocker.setAutoCommit(false);
                String column = table.equals("identity_user") ? "id" : "user_id";
                try (var statement = blocker.prepareStatement("SELECT " + column + " FROM marketplace." + table + " WHERE " + column + "=? FOR UPDATE")) {
                    statement.setObject(1, member);
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                }
                result = pool.submit(() -> {
                    try { service.verifyEmail(token); return "ok"; }
                    catch (SignupException expected) { return expected.getMessage(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT count(*) > 0 FROM pg_stat_activity WHERE datname=current_database()
                            AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query
                            """, Map.of("query", "%marketplace." + table + "%"), Boolean.class);
                    if (waiting) { break; }
                    Thread.sleep(10);
                }
                current.set(issuedAt.plusSeconds(1800));
                blocker.commit();
                assertTrue(waiting, "Verification must actually wait for " + table + " before clock advances");
            }
            assertEquals("INVALID_EMAIL_TOKEN", result.get(10, TimeUnit.SECONDS));
        }
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", member), String.class));
        assertNull(jdbc.queryForObject("SELECT email_verified_at FROM marketplace.identity_user WHERE id=:id", Map.of("id", member), java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", member), java.sql.Timestamp.class));
    }









    protected HttpResponse<String> request(String method, String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }









    protected void seed(long id, String label, String note) {
        jdbc.update("INSERT INTO c3_fixture.account VALUES (:id, :label, :balance, :note)",
                new MapSqlParameterSource().addValue("id", id).addValue("label", label)
                        .addValue("balance", new BigDecimal("100.50")).addValue("note", note));
    }

    /** Bypasses the Spring connection pool and transaction context to observe committed state. */
    protected String observe(String sql, Object parameter) throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("D2_DB_URL"),
                System.getenv("D2_DB_USERNAME"), System.getenv("D2_DB_PASSWORD"));
                var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    protected void balance(long id, String expected) throws Exception {
        assertEquals(expected, observe("SELECT balance FROM c3_fixture.account WHERE id = ?", id));
    }

    protected void entryCount(String reference, String expected) throws Exception {
        assertEquals(expected, observe("SELECT count(*) FROM c3_fixture.transfer_entry WHERE reference = ?", reference));
    }
















}
