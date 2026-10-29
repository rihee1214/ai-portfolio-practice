package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.promotion.api.CouponReservations;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.api.AccountOperations;
import com.example.marketplace.identity.api.AccountOperations.AddressInput;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.application.PasswordRecoveryService;
import com.example.marketplace.identity.infrastructure.JdbcPasswordRecoveryStore;
import com.example.marketplace.identity.infrastructure.LocalPasswordResetMailbox;
import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.application.SignupService;
import com.example.marketplace.identity.application.TokenSecrets;
import com.example.marketplace.identity.domain.IdentityStore;
import com.example.marketplace.identity.domain.PasswordHasher;
import com.example.marketplace.identity.domain.VerificationMailbox;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import com.example.marketplace.jdbcfixture.AccountRepository;
import com.example.marketplace.jdbcfixture.BatchService;
import com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget;
import com.example.marketplace.jdbcfixture.TransferRejectedException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"db", "c3-fixture", "local-mailbox", "local-http"})
@ExtendWith(OutputCaptureExtension.class)
@ContextConfiguration(initializers = DedicatedPostgresInitializer.class)
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/", "spring.sql.init.mode=never", "spring.flyway.clean-disabled=true",
            "server.address=127.0.0.1", "marketplace.phone.mode=local-test", "spring.jmx.enabled=true", "spring.jmx.unique-names=true",
            "management.endpoints.jmx.domain=com.example.marketplace.dbfollowup",
            "spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import({PostgresJdbcIT.TestMailboxEndpoint.class,PostgresJdbcIT.GuestOrderFixture.class,PostgresJdbcIT.GuestFixtureSecurity.class,PostgresJdbcIT.CouponFixture.class,PostgresJdbcIT.PaymentFixtureEndpoint.class,PostgresJdbcIT.ShipmentBrowserFixture.class,PostgresJdbcIT.TossTestConfiguration.class})
class PostgresJdbcIT {
    private static final java.util.concurrent.ConcurrentHashMap<String,Map<String,Object>> TOSS_FIXTURES=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger> TOSS_CALLS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final String TOSS_WEBHOOK_FIXTURE="fixture-only-security-key-not-real";
    private static final com.sun.net.httpserver.HttpServer TOSS_STUB=startTossStub();
    private static com.sun.net.httpserver.HttpServer startTossStub() {
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
                    String attempt=exchange.getRequestHeaders().getFirst("Idempotency-Key");assertTrue(attempt!=null&&attempt.matches("[0-9a-f-]{36}"));
                    if(path.endsWith("/confirm")) { assertEquals(data.get("orderId"),input.path("orderId").asText());assertEquals(data.get("totalAmount"),input.path("amount").asLong()); }
                    if(path.endsWith("/cancel")) { data.put("status","CANCELED");data.put("balanceAmount",0L); }
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
    @org.junit.jupiter.api.AfterAll static void stopTossStub() { TOSS_STUB.stop(0); }
    private PaymentFixture sandboxFixture(String suffix,boolean zero) throws Exception { var f=paymentFixture("sandbox-"+suffix,zero);jdbc.update("UPDATE marketplace.purchase_order SET mode='SANDBOX' WHERE id=:id",Map.of("id",f.order().id()));return new PaymentFixture(orders().find(f.order().orderNumber()),f.browser(),f.authority()); }
    private Map<String,Object> tossFixture(PaymentFixture f,String key,String state) {
        var data=new java.util.concurrent.ConcurrentHashMap<String,Object>();data.putAll(Map.of("paymentKey",key,"orderId",f.order().id().toString(),"totalAmount",f.order().quote().total(),"balanceAmount",f.order().quote().total(),"status",state,"currency","KRW","method","카드","mId","fixture-mid","version","2024-06-01"));TOSS_FIXTURES.put(key,data);return data;
    }
    private Map<String,String> tossConfirm(PaymentFixture f,String key) { return Map.of("paymentKey",key,"orderId",f.order().id().toString(),"amount",Long.toString(f.order().quote().total())); }
    private int tossCalls(String key,String method) { return TOSS_CALLS.getOrDefault(key+":"+method,new java.util.concurrent.atomic.AtomicInteger()).get(); }
    @Test void sandboxConcurrentConfirmationsAndReusedProviderKeyHaveOneEffect(CapturedOutput output) throws Exception {
        var f=sandboxFixture("concurrent",false);String key="fixture-e-concurrent-"+UUID.randomUUID(),otherKey="fixture-e-concurrent-other-"+UUID.randomUUID();tossFixture(f,key,"DONE");tossFixture(f,otherKey,"DONE");String csrf=csrfToken(f.browser());var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) { var results=List.of(key,otherKey).stream().map(k->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return accountRequest(f.browser(),"POST","/api/payments/"+f.order().orderNumber()+"/confirm",tossConfirm(f,k),csrf).statusCode();})).toList();var statuses=List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));assertThat(statuses).containsExactlyInAnyOrder(200,400); }
        paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,tossCalls(key,"POST")+tossCalls(otherKey,"POST"));String bound=tossCalls(key,"POST")==1?key:otherKey;var other=sandboxFixture("key-reuse",false);int before=tossCalls(bound,"POST");assertEquals(400,accountRequest(other.browser(),"POST","/api/payments/"+other.order().orderNumber()+"/confirm",tossConfirm(other,bound),csrfToken(other.browser())).statusCode());paymentStates(other,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",other.order().id()),Integer.class));assertEquals(before,tossCalls(bound,"POST"));assertFalse(output.getAll().contains(bound));
    }
    @Test void sandboxConfirmationWaitRechecksCurrentSession() throws Exception {
        var f=sandboxFixture("session-wait",false);String key="fixture-e-session-"+UUID.randomUUID();tossFixture(f,key,"DONE");String csrf=csrfToken(f.browser());
        try(var connection=context.getBean(DataSource.class).getConnection();var pool=Executors.newSingleThreadExecutor()) { connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { sql.setObject(1,f.authority().member());sql.executeQuery().close(); }var response=pool.submit(()->accountRequest(f.browser(),"POST","/api/payments/"+f.order().orderNumber()+"/confirm",tossConfirm(f,key),csrf));awaitCouponLock("identity_user");jdbc.update("DELETE FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",f.authority().browser()));connection.commit();assertEquals(401,response.get(10,TimeUnit.SECONDS).statusCode()); }
        assertEquals(0,tossCalls(key,"POST"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
    }
    @Test void sandboxConfirmationBindsStoredOrderAndCurrentAuthority() throws Exception {
        var f=sandboxFixture("server-binding",false);String path="/api/payments/"+f.order().orderNumber();
        assertEquals(200,accountRequest(f.browser(),"POST",path+"/sandbox",Map.of(),csrfToken(f.browser())).statusCode());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));
        assertEquals(403,accountRequest(f.browser(),"POST",path+"/sandbox",Map.of(),null).statusCode());String key="fixture-e-bind-"+UUID.randomUUID();tossFixture(f,key,"DONE");var input=tossConfirm(f,key);
        assertEquals(403,accountRequest(f.browser(),"POST",path+"/confirm",input,null).statusCode());var other=paymentFixture("sandbox-other",false);assertEquals(401,accountRequest(other.browser(),"POST",path+"/confirm",input,csrfToken(other.browser())).statusCode());
        for(var forged:List.of(Map.of("paymentKey",key,"orderId",UUID.randomUUID().toString(),"amount",input.get("amount")),Map.of("paymentKey",key,"orderId",input.get("orderId"),"amount","1"),Map.of("paymentKey",key,"orderId",input.get("orderId"),"amount",input.get("amount"),"mode","DEMO"))) { assertEquals(400,accountRequest(f.browser(),"POST",path+"/confirm",forged,csrfToken(f.browser())).statusCode()); }
        assertEquals(0,tossCalls(key,"POST"));assertEquals(200,accountRequest(f.browser(),"POST",path+"/confirm",input,csrfToken(f.browser())).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(200,accountRequest(f.browser(),"POST",path+"/confirm",input,csrfToken(f.browser())).statusCode());assertEquals(1,tossCalls(key,"POST"));
        assertEquals(400,accountRequest(f.browser(),"POST",path+"/confirm",tossConfirm(f,"another-key"),csrfToken(f.browser())).statusCode());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_toss_binding WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
        var demo=paymentFixture("sandbox-demo-reject",false);assertEquals(409,accountRequest(demo.browser(),"POST","/api/payments/"+demo.order().orderNumber()+"/confirm",tossConfirm(demo,key),csrfToken(demo.browser())).statusCode());
    }
    private static String tossSignature(String body,String time) throws Exception { var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(TOSS_WEBHOOK_FIXTURE.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));return "v1:"+java.util.Base64.getEncoder().encodeToString(mac.doFinal((body+":"+time).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    private HttpResponse<String> webhook(String path,String body,String time,String signature) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header("tosspayments-webhook-transmission-id",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(body));if(time!=null) { builder.header("tosspayments-webhook-transmission-time",time); }if(signature!=null) { builder.header("tosspayments-webhook-signature",signature); }return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void paymentWebhookRequiresProviderEvidence() throws Exception {
        var f=sandboxFixture("webhook",false);String key="fixture-e-webhook-"+UUID.randomUUID();var data=tossFixture(f,key,"IN_PROGRESS");data.put("lose",true);String path="/api/payments/"+f.order().orderNumber();assertEquals(200,accountRequest(f.browser(),"POST",path+"/confirm",tossConfirm(f,key),csrfToken(f.browser())).statusCode());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
        data.put("status","DONE");String body=authMapper.writeValueAsString(Map.of("eventType","PAYMENT_STATUS_CHANGED","createdAt","2020-01-01T00:00:00","data",data));String time=Instant.now().toString(),sig=tossSignature(body,time);var before=paymentParticipantState();
        assertEquals(403,webhook("/api/payments/webhooks/toss",body,null,null).statusCode());assertEquals(403,webhook("/api/payments/webhooks/toss",body,time,"v1:Zm9yZ2Vk").statusCode());assertEquals(403,webhook("/api/payments/webhooks/toss",body+" ",time,sig).statusCode());String old=Instant.now().minusSeconds(301).toString();assertEquals(403,webhook("/api/payments/webhooks/toss",body,old,tossSignature(body,old)).statusCode());assertEquals(before,paymentParticipantState());assertEquals(0,tossCalls(key,"GET"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_webhook_receipt",Map.of(),Integer.class));
        assertEquals(503,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->paymentWith(Clock.systemUTC(),stage->{ }).webhook(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),time,sig,"fixture-disabled")).status());assertEquals(before,paymentParticipantState());assertEquals(0,tossCalls(key,"GET"));
        for(String invalid:List.of(body.replace("\"totalAmount\":"+f.order().quote().total(),"\"totalAmount\":184467440737095516160000"),body.replace(f.order().id().toString(),UUID.randomUUID().toString()),body.replace(key,"fixture-unbound-key"),body.replace("fixture-mid","foreign-mid"))) { assertEquals(409,webhook("/api/payments/webhooks/toss",invalid,time,tossSignature(invalid,time)).statusCode());assertEquals(0,tossCalls(key,"GET"));assertEquals(before,paymentParticipantState()); }
        for(String wrongPath:List.of("/api/payments/webhooks/other","/api/payments/webhooks/toss/extra","/api/payments/webhooks/toss/")) { assertEquals(403,webhook(wrongPath,body,time,sig).statusCode()); }
        data.put("totalAmount",1L);assertEquals(409,webhook("/api/payments/webhooks/toss",body,time,sig).statusCode());assertEquals(before,paymentParticipantState());data.put("totalAmount",f.order().quote().total());
        assertEquals(200,webhook("/api/payments/webhooks/toss",body,time,"v1:invalid,"+sig).statusCode());assertEquals(200,webhook("/api/payments/webhooks/toss",body,time,sig).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_webhook_receipt WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='SUCCEEDED'",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
        String reverse=body.replace("DONE","ABORTED");assertEquals(409,webhook("/api/payments/webhooks/toss",reverse,time,tossSignature(reverse,time)).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,tossCalls(key,"POST"));
    }
    @Test void sandboxLostResponseAndLateSuccessUseSameAttempt(CapturedOutput output) throws Exception {
        var f=sandboxFixture("lost",false);String key="fixture-e-lost-"+UUID.randomUUID();var data=tossFixture(f,key,"DONE");data.put("lose",true);assertEquals(200,accountRequest(f.browser(),"POST","/api/payments/"+f.order().orderNumber()+"/confirm",tossConfirm(f,key),csrfToken(f.browser())).statusCode());assertEquals("UNKNOWN",paymentAttempt(f).get("state"));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");paymentQuery(f,false);paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,tossCalls(key,"POST"));assertEquals(1,tossCalls(key,"GET"));
        var late=sandboxFixture("late",false);String lateKey="fixture-e-late-"+UUID.randomUUID();var lateData=tossFixture(late,lateKey,"IN_PROGRESS");accountRequest(late.browser(),"POST","/api/payments/"+late.order().orderNumber()+"/confirm",tossConfirm(late,lateKey),csrfToken(late.browser()));var attempt=paymentAttempt(late);lateData.put("status","DONE");var timed=paymentWith(Clock.fixed(late.order().expiresAt(),ZoneOffset.UTC),stage->{ });timed.query(late.order().orderNumber(),0,0,false);assertEquals("PENDING",paymentAttempt(late).get("compensation"));paymentQuery(late,true);paymentQuery(late,true);paymentStates(late,"CANCELED","RELEASED","RELEASED");assertEquals(attempt.get("id"),paymentAttempt(late).get("id"));assertEquals(1,tossCalls(lateKey,"POST:cancel"));assertEquals(1,tossCalls(lateKey,"POST"));assertFalse(output.getAll().contains(key));assertFalse(output.getAll().contains(lateKey));assertFalse(output.getAll().contains("test_sk_fixture-integration-only"));
        var zero=sandboxFixture("zero",true);int operations=TOSS_CALLS.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();assertEquals(200,paymentPost(zero.browser(),zero.order().orderNumber(),"{}",csrfToken(zero.browser())).statusCode());paymentStates(zero,"PAID","COMMITTED","COMMITTED");assertEquals(operations,TOSS_CALLS.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_toss_binding WHERE attempt_id=:id",Map.of("id",paymentAttempt(zero).get("id")),Integer.class));
    }
    @Test void paymentZeroInitialDeadlineNeverSchedulesCompensation() throws Exception {
        var f=paymentFixture("zero-initial-deadline",true);var clock=new JobClock();clock.now=Instant.now();var before=paymentParticipantState();var api=paymentWith(clock,stage->{if(stage.equals("PREPARE")) { clock.now=f.order().expiresAt(); }});
        assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->api.approve(f.order().orderNumber(),f.authority()));assertEquals(before,paymentParticipantState());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
    }

    @Test void paymentApprovalProcessRecoveryBeforeAndAfterProvider() throws Exception {
        for(String point:List.of("PREPARED","PROVIDER")) {
            var f=paymentFixture("approval-crash-"+point,false);var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("approval-worker-"+UUID.randomUUID()));var clock=new JobClock();clock.now=Instant.now();var jobs=jobs(clock);String type="approval-process-"+UUID.randomUUID();UUID id=jobs.enqueue(jobCommand(type,clock.instant()));
            Process first=zeroWorkerProcess(folder,"first",clock.instant(),type,id,f,true,point),second=null;
            try {
                awaitJobCondition(()->java.nio.file.Files.exists(folder.resolve("first-effect.txt"))||!first.isAlive(),"approval worker never reached checkpoint");assertTrue(java.nio.file.Files.exists(folder.resolve("first-effect.txt")));first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals("PROCESSING",paymentAttempt(f).get("state"));clock.advance(31);
                second=zeroWorkerProcess(folder,"second",clock.instant(),type,id,f,false,point);assertTrue(second.waitFor(40,TimeUnit.SECONDS));assertEquals(0,second.exitValue());paymentStates(f,point.equals("PREPARED")?"CANCELED":"PAID",point.equals("PREPARED")?"RELEASED":"COMMITTED",point.equals("PREPARED")?"RELEASED":"COMMITTED");assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));
                assertEquals(point.equals("PREPARED")?0:1,jdbc.queryForObject("SELECT coalesce(sum(approval_calls),0) FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
            }finally { if(first.isAlive()) { first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS); }if(second!=null&&second.isAlive()) { second.destroyForcibly();second.waitFor(10,TimeUnit.SECONDS); } }
        }
    }
    @Test void paymentRepeatedWorkerCrashExhaustionNeedsReviewWithoutRelease() throws Exception {
        var f=paymentFixture("five-crashes",false);mockPayment(f,"QUERY_FAILURE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("exhausted-worker-"+UUID.randomUUID()));var clock=new JobClock();clock.now=Instant.now();var jobs=jobs(clock);String type="exhausted-process-"+UUID.randomUUID();UUID id=jobs.enqueue(jobCommand(type,clock.instant()));jdbc.update("UPDATE marketplace.payment_attempt SET next_job=:job WHERE order_id=:id",Map.of("job",id,"id",f.order().id()));
        for(int i=0;i<5;i++) {
            String phase="crash-"+i;Process child=paymentWorkerProcess(folder,phase,clock.instant(),type,id,f,false,true,"QUERY_PROVIDER");try { awaitJobCondition(()->java.nio.file.Files.exists(folder.resolve(phase+"-effect.txt"))||!child.isAlive(),"owned worker did not reach query");assertTrue(java.nio.file.Files.exists(folder.resolve(phase+"-effect.txt")));child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS)); }finally { if(child.isAlive()) { child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS); } }clock.advance(31);
        }
        assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());assertEquals("FAILED",jobs.find(id).orElseThrow().status());assertEquals("LEASE_EXPIRED",jobs.find(id).orElseThrow().lastError());assertTrue(payments().get(f.order().orderNumber(),f.authority()).reviewRequired());assertEquals(0,paymentAttempt(f).get("query_count"));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
    }

    private Process zeroWorkerProcess(java.nio.file.Path folder,String phase,Instant now,String type,UUID job,PaymentFixture f,boolean block,String point) throws Exception {
        var args=folder.resolve(phase+".args");var marker=folder.resolve(phase+"-effect.txt");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');
        java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.PaymentApprovalProbe\n"+now+"\n"+type+"\n\""+marker.toString().replace((char)92,'/')+"\"\n"+block+"\n"+job+"\n"+f.order().orderNumber()+"\n"+point+"\n"+f.authority().browser()+"\n"+f.authority().member()+"\n"+f.authority().version()+"\n");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile()).start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    }
    @Test void paymentZeroCrashRecoveryNeverCallsAnyProviderOperation() throws Exception {
        for(String point:List.of("COUPON","INVENTORY","PREPARED")) {
            var f=paymentFixture("zero-crash-"+point,true);var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("zero-worker-"+UUID.randomUUID()));var clock=new JobClock();clock.now=Instant.now();var jobs=jobs(clock);String type="zero-process-"+UUID.randomUUID();UUID id=jobs.enqueue(jobCommand(type,clock.instant()));var before=paymentParticipantState();before.remove("job");
            Process first=zeroWorkerProcess(folder,"first",clock.instant(),type,id,f,true,point),second=null;
            try {
                awaitJobCondition(()->java.nio.file.Files.exists(folder.resolve("first-effect.txt"))||!first.isAlive(),"zero worker never reached checkpoint");assertTrue(java.nio.file.Files.exists(folder.resolve("first-effect.txt")),"inspect preserved zero worker log");first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));
                if(!point.equals("PREPARED")) { var after=paymentParticipantState();after.remove("job");assertEquals(before,after,"uncommitted zero transaction must roll back every participant");paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED"); }else { paymentStates(f,"PAID","COMMITTED","COMMITTED"); }
                clock.advance(31);second=zeroWorkerProcess(folder,"second",clock.instant(),type,id,f,false,point);assertTrue(second.waitFor(40,TimeUnit.SECONDS));assertEquals(0,second.exitValue());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());assertFalse(java.nio.file.Files.exists(folder.resolve("forbidden-provider.txt")));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='SUCCEEDED'",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
            }finally { if(first.isAlive()) { first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS); }if(second!=null&&second.isAlive()) { second.destroyForcibly();second.waitFor(10,TimeUnit.SECONDS); } }
        }
        var f=paymentFixture("zero-boundary",true);var before=paymentParticipantState();var api=paymentWith(Clock.systemUTC(),stage->{if(stage.equals("COUPON")) { jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp()-interval '1 second' WHERE checkout_id=:id",Map.of("id",f.order().checkoutId())); }});
        assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.approve(f.order().orderNumber(),f.authority()));assertEquals(before,paymentParticipantState());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_mock WHERE attempt_id IN (SELECT id FROM marketplace.payment_attempt WHERE order_id=:id)",Map.of("id",f.order().id()),Integer.class));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
    }
    @Test void paymentApprovalExpiryContendOnTheSameOrderLock() throws Exception {
        for(boolean approveFirst:List.of(false,true)) {
            var f=paymentFixture("order-lock-"+approveFirst,false);mockPayment(f,"QUERY_FAILURE","SUCCESS");var due=paymentWith(Clock.fixed(f.order().expiresAt(),ZoneOffset.UTC),stage->{ });
            try(var connection=context.getBean(DataSource.class).getConnection();var pool=Executors.newFixedThreadPool(2)) {
                connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { sql.setObject(1,f.order().id());sql.executeQuery().close(); }
                Callable<String> approval=()->{try { return payments().approve(f.order().orderNumber(),f.authority()).paymentState(); }catch(com.example.marketplace.order.api.OrderRejected denied) { return "DENIED"; }};
                Callable<String> expiry=()->{due.expire(f.order().orderNumber());return "EXPIRED";};var first=pool.submit(approveFirst?approval:expiry);
                awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%purchase_order%FOR UPDATE%'",Integer.class)>=1,"first order waiter missing");var second=pool.submit(approveFirst?expiry:approval);
                awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%purchase_order%FOR UPDATE%'",Integer.class)>=2,"both order paths must actually contend");connection.commit();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            }
            if(approveFirst) { paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals("UNKNOWN",paymentAttempt(f).get("state"));assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class)); }
            else { paymentStates(f,"EXPIRED","RELEASED","RELEASED");assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class)); }
        }
    }
    @Test void paymentRejectsMislinkedOrIncompleteReservationHandoffs() throws Exception {
        for(String mode:List.of("missing","coupon","deadline","member")) {
            var f=paymentFixture("handoff-"+mode,false);var ids=orders().reservations(f.order().orderNumber());
            if(mode.equals("missing")) { jdbc.update("DELETE FROM marketplace.order_reservation WHERE reservation_id=:id",Map.of("id",ids.inventory().getFirst())); }
            if(mode.equals("deadline")) { jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=expires_at-interval '1 second' WHERE id=:id",Map.of("id",ids.inventory().getFirst())); }
            if(mode.equals("coupon")) { UUID old=f.order().quote().stores().getFirst().coupon().id();var stored=coupons().own(f.authority().member(),old);var other=couponTemplate(f.authority().member(),"다른 쿠폰",3);var issued=coupons().claim(f.authority().member(),other.id());UUID rid=jdbc.queryForObject("SELECT id FROM marketplace.coupon_reservation WHERE coupon_id=:id",Map.of("id",stored.id()),UUID.class);jdbc.update("UPDATE marketplace.coupon_reservation SET coupon_id=:coupon WHERE id=:id",Map.of("coupon",issued.id(),"id",rid)); }
            if(mode.equals("member")) { UUID other=activeMember("payment-handoff-other@example.com");var intact=paymentParticipantState();assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.coupon_reservation SET member_id=:member WHERE id=:id",Map.of("member",other,"id",ids.coupons().getFirst())));assertEquals(intact,paymentParticipantState());continue; }
            var before=paymentParticipantState();assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->payments().approve(f.order().orderNumber(),f.authority()));assertEquals(before,paymentParticipantState());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));
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
    @Test void paymentMockFixtureIsNotProductionApi() {
        try(var isolated=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) { isolated.register(PaymentFixtureEndpoint.class);isolated.refresh();assertTrue(isolated.getBeansOfType(PaymentFixtureEndpoint.class).isEmpty()); }
        assertTrue(PaymentFixtureEndpoint.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("test-classes"));
    }

    private Process paymentWorkerProcess(java.nio.file.Path folder,String phase,Instant now,String type,UUID job,PaymentFixture f,boolean cancel,boolean block,String point) throws Exception {
        var args=folder.resolve(phase+".args");var marker=folder.resolve(phase+"-effect.txt");var a=paymentAttempt(f);String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');
        java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.PaymentWorkerProbe\n"+now+"\n"+type+"\n\""+marker.toString().replace((char)92,'/')+"\"\n"+block+"\n"+job+"\n"+f.order().orderNumber()+"\n"+a.get("round")+"\n"+a.get("query_count")+"\n"+cancel+"\n"+point+"\n");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile()).start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    }
    @Test void paymentWorkerRestartKeepsEffects() throws Exception {
        for(boolean cancel:List.of(false,true)) {
            var f=paymentFixture("worker-"+cancel,false);mockPayment(f,"DELAYED_SUCCESS","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());
            if(cancel) { var a=paymentAttempt(f);context.getBean(com.example.marketplace.payment.api.PaymentProvider.class).query(new com.example.marketplace.payment.api.PaymentProvider.Request((UUID)a.get("id"),f.order().id(),f.order().quote().total(),"DEMO"));paymentWith(Clock.fixed(f.order().expiresAt(),ZoneOffset.UTC),stage->{ }).apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED); }
            var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("payment-worker-"+UUID.randomUUID()));var clock=new JobClock();clock.now=Instant.now();var jobs=jobs(clock);String type="payment-process-"+UUID.randomUUID();UUID id=jobs.enqueue(jobCommand(type,clock.instant()));
            Process first=paymentWorkerProcess(folder,"first",clock.instant(),type,id,f,cancel,true,cancel?"CANCEL_PROVIDER":"QUERY_PROVIDER"),second=null;
            try {
                awaitJobCondition(()->java.nio.file.Files.exists(folder.resolve("first-effect.txt"))||!first.isAlive(),"payment worker never reached provider effect");assertTrue(java.nio.file.Files.exists(folder.resolve("first-effect.txt")),"inspect preserved owned worker log");assertEquals("RUNNING",jobs.find(id).orElseThrow().status());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
                first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));clock.advance(31);second=paymentWorkerProcess(folder,"second",clock.instant(),type,id,f,cancel,false,"none");assertTrue(second.waitFor(40,TimeUnit.SECONDS));assertEquals(0,second.exitValue(),"inspect payment worker second.log");assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());assertEquals(2,jobs.find(id).orElseThrow().attempts());
                paymentStates(f,cancel?"CANCELED":"PAID",cancel?"RELEASED":"COMMITTED",cancel?"RELEASED":"COMMITTED");var a=paymentAttempt(f);assertEquals(1,jdbc.queryForObject("SELECT approval_effects FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));assertEquals(cancel?1:0,jdbc.queryForObject("SELECT cancel_effects FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));
            }finally { if(first.isAlive()) { first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS); }if(second!=null&&second.isAlive()) { second.destroyForcibly();second.waitFor(10,TimeUnit.SECONDS); } }
        }
    }

    @Test void paymentReviewRequiresAuthorizedReconcile() throws Exception {
        var f=paymentFixture("ops",false);mockPayment(f,"QUERY_FAILURE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());for(int i=0;i<5;i++) { paymentQuery(f,false); }
        var before=paymentParticipantState();assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->payments().reconcile(f.order().orderNumber(),f.authority(),"provider checked"));assertEquals(before,paymentParticipantState());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",f.authority().member()));assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->payments().reconcile(f.order().orderNumber(),f.authority(),"provider checked"));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'SYSTEM')",Map.of("id",f.authority().member()));assertTrue(payments().reviewQueue(f.authority()).stream().anyMatch(v->v.orderNumber().equals(f.order().orderNumber())));
        payments().reconcile(f.order().orderNumber(),f.authority(),"provider checked");var a=paymentAttempt(f);assertEquals(1,a.get("round"));assertEquals(0,a.get("query_count"));assertEquals(false,a.get("review_required"));
        assertEquals("provider checked",jdbc.queryForObject("SELECT reason FROM marketplace.payment_event WHERE attempt_id=:id AND action='RECONCILE_REQUESTED' AND actor=:actor",Map.of("id",a.get("id"),"actor",f.authority().member()),String.class));
        jdbc.update("UPDATE marketplace.payment_mock SET approval='SUCCESS',state='SUCCEEDED',approval_effects=1 WHERE attempt_id=:id",Map.of("id",a.get("id")));paymentQuery(f,false);paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"demo-coupon","demo-inventory","zero","sandbox","confirm"})
    void paymentPreparationRechecksExpiryAfterReservationWait(String shape) throws Exception {
        var f=shape.equals("sandbox")||shape.equals("confirm")?sandboxFixture("final-"+shape,false):paymentFixture("final-"+shape,shape.equals("zero"));
        var clock=new JobClock();clock.now=Instant.now();long expiry=clock.millis()+60000;
        jdbc.update("UPDATE marketplace.spring_session SET expiry_time=:expiry WHERE session_id=:s",Map.of("expiry",expiry,"s",f.authority().browser()));
        var sessions=new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock);
        var api=paymentAuthorityFixture(sessions,context.getBean(com.example.marketplace.guest.api.GuestOrders.class));
        String key="fixture-final-"+UUID.randomUUID();if(shape.equals("confirm")) { tossFixture(f,key,"DONE"); }
        boolean coupon=shape.equals("demo-coupon");String table=coupon?"issued_coupon":"inventory_balance",column=coupon?"id":"sku_id";
        UUID locked=coupon?f.order().quote().stores().getFirst().coupon().id():f.order().sellers().getFirst().items().getFirst().skuId();
        var before=paymentParticipantState();String providerBefore=paymentProviderState();
        try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT "+column+" FROM marketplace."+table+" WHERE "+column+"=? FOR UPDATE")) { sql.setObject(1,locked);sql.executeQuery().close(); }
            var response=pool.submit(()->{try { if(shape.equals("sandbox")) { api.sandbox(f.order().orderNumber(),f.authority()); }else if(shape.equals("confirm")) { api.confirm(f.order().orderNumber(),f.authority(),new com.example.marketplace.payment.infrastructure.PaymentCoordinator.Confirmation(key,f.order().id().toString(),Long.toString(f.order().quote().total()))); }else { api.approve(f.order().orderNumber(),f.authority()); }return 200; }catch(com.example.marketplace.order.api.OrderRejected denied) { return denied.status(); }});
            awaitCouponLock(table);clock.now=Instant.ofEpochMilli(expiry);assertTrue(clock.instant().isBefore(f.order().expiresAt()));connection.commit();assertEquals(401,response.get(10,TimeUnit.SECONDS),shape);
        }
        assertEquals(before,paymentParticipantState());assertEquals(providerBefore,paymentProviderState());assertEquals(0,tossCalls(key,"POST"));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
    }
    private String paymentProviderState() { return jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace.payment_mock t",String.class); }
    private com.example.marketplace.payment.infrastructure.PaymentCoordinator paymentAuthorityFixture(com.example.marketplace.identity.api.PurchaseSessions sessions,com.example.marketplace.guest.api.GuestOrders guest) {
        var saved=new com.example.marketplace.order.infrastructure.JdbcOrders(jdbc,authMapper,transactionManager);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(saved,"authorities",context.getBean(com.example.marketplace.identity.api.SellerMembership.class),sessions,guest);
        var api=new com.example.marketplace.payment.infrastructure.PaymentCoordinator(jdbc,saved,reservations(),couponReservations(),context.getBean(com.example.marketplace.payment.api.PaymentProvider.class),context.getBean(com.example.marketplace.platform.api.Jobs.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),sessions,Clock.systemUTC(),stage->{ });
        org.springframework.test.util.ReflectionTestUtils.setField(api,"sandboxSettings",context.getBean(com.example.marketplace.payment.infrastructure.PaymentProviderConfiguration.Settings.class));return api;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"status","approve"})
    void paymentExistingGuestRechecksMaximumAfterOrderWait(String shape) throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var product=browseFixture("final-guest-"+shape,10000,10);var lines=List.of(quoteLine((UUID)product.get("sku"),1));var quote=quotes().calculate(null,lines,List.of());var contact=new com.example.marketplace.order.api.Orders.Contact("ignored",f.phone,"수령인",f.phone,"01234","주소","");
        UUID first=guestVerified(f);access.prepare(f.browser,first,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var original=creationWith(access,stage->{ }).create(f.browser,null,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("first",lines,List.of(),quote.fingerprint(),contact,first,0));
        String token=access.login(original.orderNumber(),f.phone,GUEST_PASSWORD,f.source,null);
        Instant maximum=jdbc.queryForObject("SELECT expires_at FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(token)),java.sql.Timestamp.class).toInstant();
        // Genuine activity keeps idle authority valid. Create the new purchase one second before the immutable maximum.
        while(f.clock.instant().isBefore(maximum.minusSeconds(61))) {
            f.clock.advance(Math.min(1500,Duration.between(f.clock.instant(),maximum.minusSeconds(61)).getSeconds()));
            new TransactionTemplate(transactionManager).executeWithoutResult(t->assertEquals(original.guestBuyerId(),access.requireCurrent(token)));
        }
        UUID next=guestRepeat(f);var link=access.selectLink(f.browser,next,"EXISTING",token,0);
        var order=creationWith(access,stage->{ }).create(f.browser,null,token,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("linked",lines,List.of(),quote.fingerprint(),contact,next,link.revision()));
        long now=System.currentTimeMillis();jdbc.update("INSERT INTO marketplace.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time) VALUES(:id,:s,:now,:now,3600,:expiry)",Map.of("id",UUID.randomUUID().toString(),"s",f.browser,"now",now,"expiry",now+3600000));
        var sessions=context.getBean(com.example.marketplace.identity.api.PurchaseSessions.class);var api=paymentAuthorityFixture(sessions,access);var authority=new com.example.marketplace.payment.api.Payments.Authority(f.browser,null,null,token);
        assertEquals(maximum.minusSeconds(1),f.clock.instant());
        assertEquals(maximum,jdbc.queryForObject("SELECT idle_expires_at FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(token)),java.sql.Timestamp.class).toInstant());
        var before=paymentParticipantState();String providerBefore=paymentProviderState();
        try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { sql.setObject(1,order.id());sql.executeQuery().close(); }
            var response=pool.submit(()->{try { if(shape.equals("status")) { api.get(order.orderNumber(),authority); }else { api.approve(order.orderNumber(),authority); }return 200; }catch(com.example.marketplace.order.api.OrderRejected denied) { return denied.status(); }catch(com.example.marketplace.guest.api.GuestRejected denied) { return denied.status(); }});
            awaitCouponLock("purchase_order");f.clock.now=maximum;assertTrue(Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(t->sessions.lockCurrent(f.browser,null,null))));connection.commit();assertEquals(401,response.get(10,TimeUnit.SECONDS),shape);
        }
        assertEquals(before,paymentParticipantState());assertEquals(providerBefore,paymentProviderState());assertEquals("PENDING_PAYMENT",orders().find(order.orderNumber()).state());
    }
    @Test void paymentHttpWaitRechecksSessionAndWorkerSurvivesSuspension() throws Exception {
        var f=paymentFixture("http-wait",false);String csrf=csrfToken(f.browser());
        try(var connection=context.getBean(DataSource.class).getConnection();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { sql.setObject(1,f.authority().member());sql.executeQuery().close(); }
            var response=pool.submit(()->paymentPost(f.browser(),f.order().orderNumber(),"{}",csrf));awaitCouponLock("identity_user");jdbc.update("DELETE FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",f.authority().browser()));connection.commit();assertEquals(401,response.get(10,TimeUnit.SECONDS).statusCode());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
        var suspended=paymentFixture("suspended-after-provider",false);mockPayment(suspended,"LOST_RESPONSE","SUCCESS");payments().approve(suspended.order().orderNumber(),suspended.authority());jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",suspended.authority().member()));paymentQuery(suspended,false);paymentStates(suspended,"PAID","COMMITTED","COMMITTED");
    }
    private HttpResponse<String> paymentPost(HttpClient browser,String number,String body,String csrf) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/payments/"+number+"/approve")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));if(csrf!=null) { builder.header("X-CSRF-TOKEN",csrf); }return browser.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void paymentHttpRejectsOwnershipAmountModeAndMissingCsrf() throws Exception {
        var f=paymentFixture("http-denials",false);String token=csrfToken(f.browser());assertEquals(403,paymentPost(f.browser(),f.order().orderNumber(),"{}",null).statusCode());
        for(String body:List.of("{\"amount\":\"0\"}","{\"mode\":\"SANDBOX\"}","{\"reservationIds\":[]}","{\"result\":\"SUCCESS\"}")) { assertEquals(400,paymentPost(f.browser(),f.order().orderNumber(),body,token).statusCode()); }
        var other=paymentFixture("http-other",false);assertEquals(401,paymentPost(other.browser(),f.order().orderNumber(),"{}",csrfToken(other.browser())).statusCode());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));
        assertEquals(200,paymentPost(f.browser(),f.order().orderNumber(),"{}",token).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(200,paymentPost(f.browser(),f.order().orderNumber(),"{}",token).statusCode());assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
    }

    @Test void realOrdersRequireCurrentOwner() throws Exception {
        var f=paymentFixture("f-owner",false);String number=f.order().orderNumber();
        var detail=accountRequest(f.browser(),"GET","/api/orders/"+number,null,null);assertEquals(200,detail.statusCode());assertTrue(detail.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var stranger=authBrowser();for(String n:List.of(number,"Ononexistent")) { for(String path:List.of("/api/orders/"+n,"/api/payments/"+n+"/history")) {var denied=accountRequest(stranger,"GET",path,null,null);assertEquals(404,denied.statusCode());assertTrue(denied.headers().firstValue("Cache-Control").orElse("").contains("no-store"));} }
        var other=paymentFixture("f-other",false);assertEquals(404,accountRequest(other.browser(),"GET","/api/orders/"+number,null,null).statusCode());
        assertEquals(200,accountRequest(f.browser(),"GET","/api/payments/"+number+"/history",null,null).statusCode());jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='BUYER'",Map.of("id",f.authority().member()));assertEquals(200,accountRequest(f.browser(),"GET","/api/orders/"+number,null,null).statusCode());
        jdbc.update("DELETE FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",f.authority().browser()));assertEquals(404,accountRequest(f.browser(),"GET","/api/orders/"+number,null,null).statusCode());
    }
    @Test void realGuestOrdersUseIndependentAccessSessions() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var api=creationWith(access,stage->{ });var product=browseFixture("f-guest-real",10000,20);var lines=List.of(quoteLine((UUID)product.get("sku"),1));var quote=quotes().calculate(null,lines,List.of());var contact=new com.example.marketplace.order.api.Orders.Contact("ignored",f.phone,"수령인",f.phone,"01234","실제 주문 주소","");
        UUID a=guestVerified(f);access.prepare(f.browser,a,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var first=api.create(f.browser,null,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("f-first",lines,List.of(),quote.fingerprint(),contact,a,0));
        UUID b=guestRepeat(f);access.prepare(f.browser,b,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var second=api.create(f.browser,null,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("f-second",lines,List.of(),quote.fingerprint(),contact,b,0));assertThat(second.guestBuyerId()).isNotEqualTo(first.guestBuyerId());
        String old=access.login(first.orderNumber(),f.phone,GUEST_PASSWORD,f.source,null);UUID c=guestRepeat(f);var link=access.selectLink(f.browser,c,"EXISTING",old,0);
        var third=api.create(f.browser,null,old,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("f-third",lines,List.of(),quote.fingerprint(),contact,c,link.revision()));assertEquals(first.guestBuyerId(),third.guestBuyerId());
        var browser=authBrowser();String csrf=csrfToken(browser);assertEquals(200,accountRequest(browser,"POST","/api/guest/access/login",Map.of("orderNumber",first.orderNumber(),"phone",f.phone,"password",GUEST_PASSWORD),csrf).statusCode());
        var listing=authMapper.readTree(accountRequest(browser,"GET","/api/guest/orders",null,null).body()).get("orders");assertEquals(2,listing.size());var numbers=new java.util.HashSet<String>();listing.forEach(r->numbers.add(r.get("orderNumber").asText()));assertEquals(java.util.Set.of(first.orderNumber(),third.orderNumber()),numbers);
        for(String path:List.of("/api/orders/"+first.orderNumber(),"/api/payments/"+first.orderNumber()+"/history","/api/orders/"+first.orderNumber()+"/shipments")) { assertEquals(200,accountRequest(browser,"GET",path,null,null).statusCode()); }
        assertEquals(404,accountRequest(browser,"GET","/api/orders/"+second.orderNumber(),null,null).statusCode());assertEquals(404,accountRequest(browser,"GET","/api/orders/"+second.orderNumber()+"/shipments",null,null).statusCode());assertEquals(401,paymentPost(browser,first.orderNumber(),"{}",csrfToken(browser)).statusCode());
        assertEquals(200,accountRequest(browser,"POST","/api/guest/access/logout",Map.of(),csrfToken(browser)).statusCode());assertEquals(404,accountRequest(browser,"GET","/api/orders/"+first.orderNumber(),null,null).statusCode());
        for(String expiry:List.of("idle_expires_at","expires_at","password_version")) {
            assertEquals(200,accountRequest(browser,"POST","/api/guest/access/login",Map.of("orderNumber",first.orderNumber(),"phone",f.phone,"password",GUEST_PASSWORD),csrfToken(browser)).statusCode());
            if(expiry.equals("password_version")) { jdbc.update("UPDATE marketplace.guest_buyer SET password_version=password_version+1 WHERE id=:id",Map.of("id",first.guestBuyerId())); }
            else { jdbc.update("UPDATE marketplace.guest_access_session SET idle_expires_at=LEAST(idle_expires_at,now()-interval '1 second')"+(expiry.equals("expires_at")?",expires_at=LEAST(expires_at,now()-interval '1 second')":"")+" WHERE guest_buyer_id=:id",Map.of("id",first.guestBuyerId())); }
            assertEquals(404,accountRequest(browser,"GET","/api/orders/"+first.orderNumber(),null,null).statusCode());assertEquals(404,accountRequest(browser,"GET","/api/payments/"+first.orderNumber()+"/history",null,null).statusCode());assertEquals(404,accountRequest(browser,"GET","/api/orders/"+first.orderNumber()+"/shipments",null,null).statusCode());
        }
        var secondBrowser=authBrowser();for(var client:List.of(browser,secondBrowser)) { assertEquals(200,accountRequest(client,"POST","/api/guest/access/login",Map.of("orderNumber",third.orderNumber(),"phone",f.phone,"password",GUEST_PASSWORD),csrfToken(client)).statusCode());assertEquals(200,accountRequest(client,"GET","/api/orders/"+first.orderNumber(),null,null).statusCode()); }
        var recovery=recovery(f);String code=recovery.initialCode(first.guestBuyerId());String grant=resetGrant(f,recovery,first.orderNumber(),code);String replacement="f actual reset password";recovery.reset(f.browser,grant,new com.example.marketplace.guest.api.GuestPassword(replacement,replacement));
        for(var client:List.of(browser,secondBrowser)) { assertEquals(404,accountRequest(client,"GET","/api/orders/"+first.orderNumber(),null,null).statusCode());assertEquals(404,accountRequest(client,"GET","/api/payments/"+first.orderNumber()+"/history",null,null).statusCode());assertEquals(404,accountRequest(client,"GET","/api/orders/"+first.orderNumber()+"/shipments",null,null).statusCode()); }
        assertEquals(401,accountRequest(browser,"POST","/api/guest/access/login",Map.of("orderNumber",third.orderNumber(),"phone",f.phone,"password",GUEST_PASSWORD),csrfToken(browser)).statusCode());assertEquals(200,accountRequest(browser,"POST","/api/guest/access/login",Map.of("orderNumber",third.orderNumber(),"phone",f.phone,"password",replacement),csrfToken(browser)).statusCode());assertEquals(200,accountRequest(browser,"GET","/api/orders/"+first.orderNumber(),null,null).statusCode());
    }
    @org.springframework.web.bind.annotation.RestController @org.springframework.context.annotation.Profile("c3-fixture")
    static class ShipmentBrowserFixture {
        private final NamedParameterJdbcTemplate jdbc;private final DataSource source;private final PlatformTransactionManager manager;private final com.example.marketplace.order.api.OrderQueries queries;private final List<com.example.marketplace.platform.api.JobHandler> handlers;
        ShipmentBrowserFixture(NamedParameterJdbcTemplate jdbc,DataSource source,PlatformTransactionManager manager,com.example.marketplace.order.api.OrderQueries queries,List<com.example.marketplace.platform.api.JobHandler> handlers) { this.jdbc=jdbc;this.source=source;this.manager=manager;this.queries=queries;this.handlers=handlers; }
        private void own(String number,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,String token) { new TransactionTemplate(manager).executeWithoutResult(t->queries.requireOwned(number,new com.example.marketplace.order.api.OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),principal instanceof org.springframework.security.core.Authentication authentication && authentication.getPrincipal() instanceof com.example.marketplace.identity.infrastructure.SessionMember saved?saved.authVersion():null,token))); }
        @org.springframework.web.bind.annotation.PostMapping("/api/__test/shipments/{number}/worker")
        Object worker(@org.springframework.web.bind.annotation.PathVariable String number,@org.springframework.web.bind.annotation.RequestBody Map<String,String> input,java.security.Principal principal,jakarta.servlet.http.HttpSession session,jakarta.servlet.http.HttpServletRequest request,@org.springframework.web.bind.annotation.CookieValue(name="GUEST_ACCESS",required=false) String token) {
            own(number,principal,session,request,token);String key;
            if("prepare".equals(input.get("action"))) { key=jdbc.queryForObject("SELECT dedupe_key FROM marketplace.job WHERE type='PAYMENT_SETTLED' AND payload LIKE :p",Map.of("p",number+":%"),String.class); }
            else { UUID shipment=UUID.fromString(input.get("shipment"));if(jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment WHERE id=:id AND order_number=:n",Map.of("id",shipment,"n",number),Integer.class)!=1) { throw new com.example.marketplace.order.api.OrderRejected(404); }key="progress:"+shipment+":"+input.get("target"); }
            UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",key),UUID.class);Instant at=jdbc.queryForObject("SELECT next_run_at FROM marketplace.job WHERE id=:id",Map.of("id",id),java.sql.Timestamp.class).toInstant();
            var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(at,ZoneOffset.UTC));try(var engine=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,handlers)) { if(!engine.runOnce()||!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")) { throw new IllegalStateException("FIXTURE_WORKER_FAILED"); } }return Map.of("done",true);
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
            own(number,principal,session,request,token);return Map.of("shipments",jdbc.queryForList("SELECT id,seller_order_id,state,version FROM marketplace.shipment WHERE order_number=:n ORDER BY id",Map.of("n",number)),"items",jdbc.queryForList("SELECT i.shipment_id,i.order_item_id,i.quantity FROM marketplace.shipment_item i JOIN marketplace.shipment s ON s.id=i.shipment_id WHERE s.order_number=:n ORDER BY i.shipment_id,i.order_item_id",Map.of("n",number)),"events",jdbc.queryForList("SELECT e.id,e.shipment_id,e.source,e.actor,e.occurred_at,e.processed_at,e.previous_state,e.next_state,e.version,e.applied FROM marketplace.shipment_event e JOIN marketplace.shipment s ON s.id=e.shipment_id WHERE s.order_number=:n ORDER BY e.event_sequence",Map.of("n",number)));
        }
    }
    @Test void shipmentBrowserFixtureIsNotProductionApi() { try(var isolated=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) { isolated.register(ShipmentBrowserFixture.class);isolated.refresh();assertTrue(isolated.getBeansOfType(ShipmentBrowserFixture.class).isEmpty()); } }
    @Test void sellerOrderListIsBoundedScopedAndCurrent() throws Exception {
        var f=paymentFixture("f2-list",false);var original=f.order();UUID own=ownSeller(f);var expected=new java.util.HashSet<String>();expected.add(own.toString());
        // Public order persistence creates real seller/item snapshots; no synthetic shipment/payment rows.
        for(int i=0;i<22;i++) { var saved=orders().save(new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),"f2-list-"+i,"a".repeat(64),original.memberId(),null,null,"DEMO",original.quote(),original.contact(),original.createdAt(),original.expiresAt()));expected.add(saved.sellers().stream().filter(s->s.storeId().equals(original.sellers().stream().filter(x->x.id().equals(own)).findFirst().orElseThrow().storeId())).findFirst().orElseThrow().id().toString()); }
        var response=accountRequest(f.browser(),"GET","/api/seller/orders",null,null);assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));var page=authMapper.readTree(response.body());assertEquals(20,page.get("orders").size());var seen=new java.util.HashSet<String>();
        for(var row:page.get("orders")) { var keys=new java.util.HashSet<String>();row.properties().forEach(e->keys.add(e.getKey()));assertEquals(java.util.Set.of("sellerOrderId","orderNumber","orderState","total","createdAt"),keys);assertTrue(seen.add(row.get("sellerOrderId").asText())); }
        String cursor=page.get("nextCursor").asText();var next=authMapper.readTree(accountRequest(f.browser(),"GET","/api/seller/orders?cursor="+cursor,null,null).body());assertEquals(3,next.get("orders").size());next.get("orders").forEach(r->assertTrue(seen.add(r.get("sellerOrderId").asText())));assertEquals(expected,seen);assertTrue(next.get("nextCursor").isNull());
        for(int size:List.of(0,-1,51)) { assertEquals(400,accountRequest(f.browser(),"GET","/api/seller/orders?size="+size,null,null).statusCode()); }assertEquals(400,accountRequest(f.browser(),"GET","/api/seller/orders?cursor=invalid",null,null).statusCode());assertEquals(23,authMapper.readTree(accountRequest(f.browser(),"GET","/api/seller/orders?size=50",null,null).body()).get("orders").size());
        var other=paymentFixture("f2-other-list",false);var foreign=authMapper.readTree(accountRequest(other.browser(),"GET","/api/seller/orders?cursor="+cursor,null,null).body());for(var row:foreign.get("orders")) { assertFalse(expected.contains(row.get("sellerOrderId").asText())); }
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);var store=stores.own(f.authority().member());jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE id=:id",Map.of("id",store.id()));assertEquals(200,accountRequest(f.browser(),"GET","/api/seller/orders",null,null).statusCode());stores.transition(f.authority().member(),store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(store.version(),"기존 주문 목록 보존"));assertEquals(200,accountRequest(f.browser(),"GET","/api/seller/orders",null,null).statusCode());
        jdbc.update("UPDATE marketplace.spring_session SET last_access_time=:at,expiry_time=:expiry WHERE session_id=:id",Map.of("at",Instant.now().minusSeconds(1801).toEpochMilli(),"expiry",Instant.now().minusSeconds(1).toEpochMilli(),"id",other.authority().browser()));
        assertEquals(404,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->context.getBean(com.example.marketplace.order.api.OrderQueries.class).sellerOrders(shipmentAuthority(other),20,null)).status());
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",f.authority().member()));assertEquals(401,accountRequest(f.browser(),"GET","/api/seller/orders",null,null).statusCode());
    }
    @Test void sellerOrderProjectionIsScoped() throws Exception {
        var f=paymentFixture("f-seller",false);var own=f.order().sellers().stream().filter(s->s.storeId().equals(f.order().quote().stores().stream().filter(qs->qs.slug().contains("reserve")).findFirst().orElseThrow().storeId())).findFirst().orElseThrow();var initialAuthority=new com.example.marketplace.order.api.OrderQueries.Authority(f.authority().browser(),f.authority().member(),f.authority().version(),null);assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->context.getBean(com.example.marketplace.order.api.OrderQueries.class).paidSellerOrder(own.id(),initialAuthority)).status());payments().approve(f.order().orderNumber(),f.authority());
        var result=accountRequest(f.browser(),"GET","/api/seller/orders/"+own.id(),null,null);assertEquals(200,result.statusCode());assertTrue(result.headers().firstValue("Cache-Control").orElse("").contains("no-store"));var data=authMapper.readTree(result.body());
        var keys=new java.util.HashSet<String>();data.properties().forEach(entry->keys.add(entry.getKey()));assertEquals(java.util.Set.of("sellerOrderId","orderNumber","orderState","total","recipient","items"),keys);assertEquals(own.id().toString(),data.get("sellerOrderId").asText());assertEquals("PAID",data.get("orderState").asText());assertEquals(1,data.get("items").size());assertEquals("수령인",data.get("recipient").get("name").asText());
        for(String forbidden:List.of("buyerName","buyerPhone","memberId","guestBuyerId","password","recovery","paymentKey","paymentState","attempts","stores")) { assertFalse(result.body().contains("\""+forbidden+"\""),forbidden); }
        var other=f.order().sellers().stream().filter(s->!s.id().equals(own.id())).findFirst().orElseThrow();assertFalse(result.body().contains(other.storeId().toString()));assertEquals(404,accountRequest(f.browser(),"GET","/api/seller/orders/"+other.id(),null,null).statusCode());assertEquals(404,accountRequest(f.browser(),"GET","/api/seller/orders/"+UUID.randomUUID(),null,null).statusCode());
        for(var seller:f.order().sellers()) {
            UUID owner=jdbc.queryForObject("SELECT owner_id FROM marketplace.seller_store WHERE id=:id",Map.of("id",seller.storeId()),UUID.class);String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner),String.class);var client=authBrowser();assertEquals(200,authRequest(client,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(client)).statusCode());
            var projected=authMapper.readTree(accountRequest(client,"GET","/api/seller/orders/"+seller.id(),null,null).body());var snapshot=f.order().quote().stores().stream().filter(q->q.storeId().equals(seller.storeId())).findFirst().orElseThrow();assertEquals(Long.toString(snapshot.total()),projected.get("total").asText());assertEquals(snapshot.lines().getFirst().quantity(),projected.get("items").get(0).get("quantity").asInt());assertEquals(Long.toString(snapshot.lines().getFirst().units().getFirst().net()),projected.get("items").get(0).get("units").get(0).get("net").asText());
            var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);var current=stores.own(owner);jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE id=:id",Map.of("id",seller.storeId()));assertEquals(200,accountRequest(client,"GET","/api/seller/orders/"+seller.id(),null,null).statusCode());stores.transition(owner,seller.storeId(),"close",new com.example.marketplace.seller.api.Stores.Change(current.version(),"거래 조회 보존"));assertEquals(200,accountRequest(client,"GET","/api/seller/orders/"+seller.id(),null,null).statusCode());
            String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id ORDER BY creation_time DESC LIMIT 1",Map.of("id",owner.toString()),String.class);Long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner),Long.class);var authority=new com.example.marketplace.order.api.OrderQueries.Authority(session,owner,version,null);assertEquals(seller.id(),context.getBean(com.example.marketplace.order.api.OrderQueries.class).paidSellerOrder(seller.id(),authority).sellerOrderId());
        }
    }
    @Test void orderHistoryPreservesSnapshotsAndPagination() throws Exception {
        var f=paymentFixture("f-pages",false);var original=f.order();var ids=new java.util.HashSet<String>();ids.add(original.orderNumber());
        for(int i=0;i<22;i++) { var draft=new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),"history-"+i,"a".repeat(64),original.memberId(),null,null,"DEMO",original.quote(),original.contact(),original.createdAt(),original.expiresAt());ids.add(orders().save(draft).orderNumber()); }
        var firstPage=accountRequest(f.browser(),"GET","/api/account/orders",null,null);assertEquals(200,firstPage.statusCode());var page=authMapper.readTree(firstPage.body());assertEquals(20,page.get("orders").size());String cursor=page.get("nextCursor").asText();assertFalse(cursor.isBlank());
        var seen=new java.util.HashSet<String>();page.get("orders").forEach(r->assertTrue(seen.add(r.get("orderNumber").asText())));
        var newer=orders().save(new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),"insert-between-pages","a".repeat(64),original.memberId(),null,null,"DEMO",original.quote(),original.contact(),original.createdAt().plusSeconds(1),original.expiresAt().plusSeconds(1)));new TransactionTemplate(transactionManager).executeWithoutResult(t->orders().transition(original.orderNumber(),"PAID"));
        var second=authMapper.readTree(accountRequest(f.browser(),"GET","/api/account/orders?cursor="+cursor,null,null).body());assertEquals(3,second.get("orders").size());assertTrue(second.get("nextCursor").isNull());second.get("orders").forEach(r->assertTrue(seen.add(r.get("orderNumber").asText())));assertEquals(ids,seen);
        assertEquals(24,authMapper.readTree(accountRequest(f.browser(),"GET","/api/account/orders?size=50",null,null).body()).get("orders").size());assertFalse(seen.contains(newer.orderNumber()));for(int invalid:List.of(0,-1,51)) { assertEquals(400,accountRequest(f.browser(),"GET","/api/account/orders?size="+invalid,null,null).statusCode()); }assertEquals(400,accountRequest(f.browser(),"GET","/api/account/orders?cursor=invalid",null,null).statusCode());
        String before=accountRequest(f.browser(),"GET","/api/orders/"+original.orderNumber(),null,null).body();var line=original.quote().stores().getFirst().lines().getFirst();
        jdbc.update("UPDATE marketplace.catalog_product SET title='변경 후 비공개',is_hidden_by_admin=true WHERE id=:id",Map.of("id",line.productId()));jdbc.update("UPDATE marketplace.catalog_sku SET price=7 WHERE id=:id",Map.of("id",line.skuId()));jdbc.update("UPDATE marketplace.issued_coupon SET title='변경된 혜택' WHERE id=:id",Map.of("id",original.quote().stores().getFirst().coupon().id()));
        assertEquals(before,accountRequest(f.browser(),"GET","/api/orders/"+original.orderNumber(),null,null).body());var detail=authMapper.readTree(before);assertTrue(detail.get("stores").get(0).has("couponTitle"));assertEquals(original.quote().stores().getFirst().coupon().title(),detail.get("stores").get(0).get("couponTitle").asText());assertEquals(Long.toString(original.quote().total()),detail.get("total").asText());assertEquals(Long.toString(line.units().getFirst().net()),detail.get("stores").get(0).get("items").get(0).get("units").get(0).get("net").asText());assertFalse(before.contains("\"memberId\""));assertFalse(before.contains("\"guestBuyerId\""));
        var retained=orders().find(original.orderNumber());String oldEmail=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",original.memberId()),String.class);context.getBean(com.example.marketplace.identity.api.AccountStateOperations.class).withdraw(original.memberId()," A1-private-passphrase-가나다 ","WITHDRAW");assertEquals(retained,orders().find(original.orderNumber()));assertEquals(24,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",original.memberId()),Integer.class));
        UUID replacement=activeMember(oldEmail);assertThat(replacement).isNotEqualTo(original.memberId());var replacementBrowser=authBrowser();assertEquals(200,authRequest(replacementBrowser,"/api/auth/login",loginForm(oldEmail," A1-private-passphrase-가나다 "),csrfToken(replacementBrowser)).statusCode());assertEquals(404,accountRequest(replacementBrowser,"GET","/api/orders/"+original.orderNumber(),null,null).statusCode());assertEquals(0,authMapper.readTree(accountRequest(replacementBrowser,"GET","/api/account/orders",null,null).body()).get("orders").size());
    }

    @Test void paymentHistoryShowsExhaustionWithoutMutation() throws Exception {
        for(String reason:List.of("COUNT","TIME","JOB")) {
            var f=paymentFixture("f-review-"+reason.toLowerCase(java.util.Locale.ROOT),false);mockPayment(f,"QUERY_FAILURE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());var attempt=paymentAttempt(f);
            if(reason.equals("COUNT")) { jdbc.update("UPDATE marketplace.payment_attempt SET query_count=5 WHERE id=:id",Map.of("id",attempt.get("id"))); }
            if(reason.equals("TIME")) { jdbc.update("UPDATE marketplace.payment_attempt SET round_started_at=now()-interval '901 seconds' WHERE id=:id",Map.of("id",attempt.get("id"))); }
            if(reason.equals("JOB")) { jdbc.update("UPDATE marketplace.job SET status='FAILED',last_error='DELIVERY_EXHAUSTED' WHERE id=:id",Map.of("id",attempt.get("next_job"))); }
            var before=paymentParticipantState();var mockBefore=jdbc.queryForMap("SELECT * FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",attempt.get("id")));
            var response=accountRequest(f.browser(),"GET","/api/payments/"+f.order().orderNumber()+"/history",null,null);assertEquals(200,response.statusCode());assertTrue(authMapper.readTree(response.body()).get("reviewRequired").asBoolean(),reason);assertEquals(before,paymentParticipantState());assertEquals(mockBefore,jdbc.queryForMap("SELECT * FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",attempt.get("id"))));
            var historyKeys=new java.util.HashSet<String>();authMapper.readTree(response.body()).properties().forEach(e->historyKeys.add(e.getKey()));assertEquals(java.util.Set.of("orderNumber","orderState","paymentState","compensation","reviewRequired","amount","mode","events"),historyKeys);
            for(String compensation:List.of("PENDING","UNKNOWN")) { jdbc.update("UPDATE marketplace.payment_attempt SET state='FAILED',compensation=:c,query_count=5 WHERE id=:id",Map.of("c",compensation,"id",attempt.get("id")));var unchanged=paymentParticipantState();var value=accountRequest(f.browser(),"GET","/api/payments/"+f.order().orderNumber()+"/history",null,null);assertTrue(authMapper.readTree(value.body()).get("reviewRequired").asBoolean());assertEquals(unchanged,paymentParticipantState()); }
            jdbc.update("UPDATE marketplace.payment_attempt SET state='SUCCEEDED',compensation='CANCELED',review_required=false WHERE id=:id",Map.of("id",attempt.get("id")));var terminal=paymentParticipantState();assertFalse(authMapper.readTree(accountRequest(f.browser(),"GET","/api/payments/"+f.order().orderNumber()+"/history",null,null).body()).get("reviewRequired").asBoolean());assertEquals(terminal,paymentParticipantState());assertEquals(mockBefore,jdbc.queryForMap("SELECT * FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",attempt.get("id"))));
        }
    }
    @Test void orderHistoryRechecksAuthorityAfterActualWait() throws Exception {
        var f=paymentFixture("f-read-wait",false);var queries=context.getBean(com.example.marketplace.order.api.OrderQueries.class);var authority=new com.example.marketplace.order.api.OrderQueries.Authority(f.authority().browser(),f.authority().member(),f.authority().version(),null);
        try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { sql.setObject(1,f.authority().member());sql.executeQuery().close(); }
            var reader=pool.submit(()->assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->queries.detail(f.order().orderNumber(),authority)).status());awaitHistoryLock("identity_user");jdbc.update("DELETE FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",f.authority().browser()));connection.commit();assertEquals(404,reader.get(10,TimeUnit.SECONDS));
        }
    }

    private void awaitHistoryLock(String table) { long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline) { jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :table AND query LIKE '%FOR SHARE%')",Map.of("table","%"+table+"%"),Boolean.class))) { return; }try { Thread.sleep(20); }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); } }throw new AssertionError("Actual history FOR SHARE wait on "+table); }
    @Test void guestHistoryRechecksExpiryAfterOrderWait() throws Exception {
        for(long seconds:List.of(1800L,43200L)) {
            var f=new GuestFixture();var access=guestAccess(f);var fixture=browseFixture("f-guest-wait-"+seconds,10000,10);var lines=List.of(quoteLine((UUID)fixture.get("sku"),1));var quote=quotes().calculate(null,lines,List.of());UUID attempt=guestVerified(f);access.prepare(f.browser,attempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));var contact=new com.example.marketplace.order.api.Orders.Contact("ignored",f.phone,"수령인",f.phone,"01234","주소","");
            var order=creationWith(access,stage->{ }).create(f.browser,null,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("wait",lines,List.of(),quote.fingerprint(),contact,attempt,0));String token=access.login(order.orderNumber(),f.phone,GUEST_PASSWORD,f.source,null);var authority=new com.example.marketplace.order.api.OrderQueries.Authority("independent-browser",null,null,token);
            var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),access,context.getBean(com.example.marketplace.identity.api.HistorySessions.class),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);
            try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { sql.setObject(1,order.id());sql.executeQuery().close(); }
                var reader=pool.submit(()->{try { queries.detail(order.orderNumber(),authority);return 200; }catch(com.example.marketplace.order.api.OrderRejected denied) { return denied.status(); }});awaitHistoryLock("purchase_order");f.clock.advance(seconds);connection.commit();assertEquals(404,reader.get(10,TimeUnit.SECONDS));
            }
        }
    }

    @Test void memberAndSellerHistoryRecheckExpiryAfterOrderWait() throws Exception {
        for(String shape:List.of("BUYER","PAYMENT","SELLER")) {
            var f=paymentFixture("f-session-clock-"+shape.toLowerCase(java.util.Locale.ROOT),false);var current=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());Clock clock=new Clock() { @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; } @Override public Clock withZone(java.time.ZoneId zone) { return this; } @Override public Instant instant() { return current.get(); } };
            var sessions=new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock);var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class),sessions,context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);var history=new com.example.marketplace.payment.infrastructure.JdbcPaymentQueries(jdbc,queries,context.getBean(com.example.marketplace.platform.api.Jobs.class),transactionManager);var authority=new com.example.marketplace.order.api.OrderQueries.Authority(f.authority().browser(),f.authority().member(),f.authority().version(),null);UUID ownStore=context.getBean(com.example.marketplace.seller.api.Stores.class).own(f.authority().member()).id();UUID seller=f.order().sellers().stream().filter(s->s.storeId().equals(ownStore)).findFirst().orElseThrow().id();long expires=jdbc.queryForObject("SELECT expiry_time FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",authority.browser()),Long.class);
            try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { sql.setObject(1,f.order().id());sql.executeQuery().close(); }
                var reader=pool.submit(()->{try { if(shape.equals("SELLER")) { queries.sellerOrder(seller,authority); }else if(shape.equals("PAYMENT")) { history.history(f.order().orderNumber(),authority); }else { queries.detail(f.order().orderNumber(),authority); }return 200; }catch(com.example.marketplace.order.api.OrderRejected denied) { return denied.status(); }});awaitHistoryLock("purchase_order");current.set(Instant.ofEpochMilli(expires));connection.commit();assertEquals(404,reader.get(10,TimeUnit.SECONDS),shape);
            }
        }
        var f=paymentFixture("f-seller-role-wait",false);var queries=context.getBean(com.example.marketplace.order.api.OrderQueries.class);var authority=new com.example.marketplace.order.api.OrderQueries.Authority(f.authority().browser(),f.authority().member(),f.authority().version(),null);UUID store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(authority.member()).id();UUID seller=f.order().sellers().stream().filter(s->s.storeId().equals(store)).findFirst().orElseThrow().id();
        try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { sql.setObject(1,authority.member());sql.executeQuery().close(); }
            var reader=pool.submit(()->assertThrows(com.example.marketplace.seller.api.StoreException.class,()->queries.sellerOrder(seller,authority)).status());awaitCouponLock("identity_user");jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",authority.member()));connection.commit();assertEquals(403,reader.get(10,TimeUnit.SECONDS));
        }
    }

    private com.example.marketplace.fulfillment.infrastructure.JdbcShipments shipments() { return context.getBean(com.example.marketplace.fulfillment.infrastructure.JdbcShipments.class); }
    private com.example.marketplace.order.api.OrderQueries.Authority shipmentAuthority(PaymentFixture f) { return new com.example.marketplace.order.api.OrderQueries.Authority(f.authority().browser(),f.authority().member(),f.authority().version(),null); }
    private UUID ownSeller(PaymentFixture f) { UUID store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(f.authority().member()).id();return f.order().sellers().stream().filter(s->s.storeId().equals(store)).findFirst().orElseThrow().id(); }
    private UUID settlementJob(PaymentFixture f) { return jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='PAYMENT_SETTLED' AND payload LIKE :p",Map.of("p",f.order().orderNumber()+":%"),UUID.class); }
    private void shipmentJob(UUID id,Instant now) {
        var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),id),transactionManager,Clock.fixed(now,ZoneOffset.UTC));
        try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.copyOf(context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values()))) { assertTrue(worker.runOnce()); }
        assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status(),jobs.find(id).orElseThrow().lastError());
    }
    private com.example.marketplace.fulfillment.api.Shipments.View paidShipment(PaymentFixture f) { payments().approve(f.order().orderNumber(),f.authority());shipmentJob(settlementJob(f),Instant.now());return shipments().seller(ownSeller(f),shipmentAuthority(f)).getFirst(); }
    private com.example.marketplace.fulfillment.api.Shipments.Command shipCommand(com.example.marketplace.fulfillment.api.Shipments.View v,String key) { return new com.example.marketplace.fulfillment.api.Shipments.Command(key,v.version(),v.items()); }
    private Map<String,Object> shipBody(com.example.marketplace.fulfillment.api.Shipments.View v,String key) { return Map.of("key",key,"version",v.version(),"items",v.items()); }
    private Map<String,List<Map<String,Object>>> shipmentState() { var state=new java.util.LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("shipment","shipment_item","shipment_event","shipment_audit","job","outbox_event")) { state.put(table,jdbc.queryForList("SELECT * FROM marketplace."+table+" ORDER BY 1,2",Map.of())); }return state; }
    @Test void basicShipmentRequiresPaidSellerOrder() throws Exception {
        var f=paymentFixture("f1-basic",false);UUID own=ownSeller(f);
        var response=accountRequest(f.browser(),"GET","/api/seller/orders/"+own+"/shipments",null,null);assertEquals(200,response.statusCode());assertEquals(0,authMapper.readTree(response.body()).size());
        var before=shipmentState();shipments().prepare(f.order().orderNumber());assertEquals(before,shipmentState());
        var v=paidShipment(f);assertEquals("PREPARING",v.state());assertEquals(0,v.version());assertEquals("MOCK",v.provider());assertEquals(2,shipments().buyer(f.order().orderNumber(),shipmentAuthority(f)).size());
        for(var seller:f.order().sellers()) {
            var rows=jdbc.queryForList("SELECT si.order_item_id,si.quantity FROM marketplace.shipment s JOIN marketplace.shipment_item si ON si.shipment_id=s.id WHERE s.seller_order_id=:id",Map.of("id",seller.id()));assertEquals(seller.items().size(),rows.size());for(var item:seller.items()) { assertTrue(rows.stream().anyMatch(r->item.id().equals(r.get("order_item_id"))&&item.quantity()==((Number)r.get("quantity")).intValue())); }
        }
        var unchanged=shipmentState();shipments().prepare(f.order().orderNumber());assertEquals(unchanged,shipmentState());
        for(String mode:List.of("REJECT","QUERY_FAILURE")) { var denied=paymentFixture("f1-nonpaid-"+mode,false);mockPayment(denied,mode,"SUCCESS");payments().approve(denied.order().orderNumber(),denied.authority());if(mode.equals("REJECT")) { shipmentJob(settlementJob(denied),Instant.now()); }else { shipments().prepare(denied.order().orderNumber()); }assertEquals(0,shipments().buyer(denied.order().orderNumber(),shipmentAuthority(denied)).size()); }
        var other=f.order().sellers().stream().filter(row->!row.id().equals(own)).findFirst().orElseThrow().items().getFirst();
        for(var invalid:List.of(new com.example.marketplace.fulfillment.api.Shipments.Item(other.id(),other.quantity()),new com.example.marketplace.fulfillment.api.Shipments.Item(v.items().getFirst().orderItemId(),v.items().getFirst().quantity()+1))) { var state=shipmentState();assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().ship(v.id(),shipmentAuthority(f),new com.example.marketplace.fulfillment.api.Shipments.Command("wrong",0L,List.of(invalid)))).status());assertEquals(state,shipmentState()); }
        assertEquals(404,accountRequest(authBrowser(),"GET","/api/orders/"+f.order().orderNumber()+"/shipments",null,null).statusCode());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }
    @Test void shipmentWriteRechecksSellerAuthority() throws Exception {
        var f=paymentFixture("f1-authority",false);var v=paidShipment(f);String path="/api/seller/shipments/"+v.id()+"/ship";var body=shipBody(v,"once");var other=paymentFixture("f1-other",false);var before=shipmentState();
        assertEquals(403,accountRequest(f.browser(),"POST",path,body,null).statusCode());
        assertEquals(404,accountRequest(other.browser(),"POST",path,body,csrfToken(other.browser())).statusCode());
        for(String field:List.of("state","actor","source")) { var forged=new java.util.LinkedHashMap<>(body);forged.put(field,"DELIVERED");assertEquals(400,accountRequest(f.browser(),"POST",path,forged,csrfToken(f.browser())).statusCode()); }
        assertEquals(404,accountRequest(f.browser(),"POST","/api/seller/shipments/"+v.id()+"/delivered",body,csrfToken(f.browser())).statusCode());assertEquals(before,shipmentState());
        jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE id=:id",Map.of("id",context.getBean(com.example.marketplace.seller.api.Stores.class).own(f.authority().member()).id()));
        assertEquals(200,accountRequest(f.browser(),"POST",path,body,csrfToken(f.browser())).statusCode());var saved=shipmentState();String existingCsrf=csrfToken(f.browser());jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",f.authority().member()));assertEquals(401,accountRequest(f.browser(),"POST",path,body,existingCsrf).statusCode());assertEquals(saved,shipmentState());
        for(String invalidation:List.of("ROLE","SESSION","SUSPEND")) {
            var waiting=paymentFixture("f1-wait-"+invalidation,false);var target=paidShipment(waiting);var initial=shipmentState();
            try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { sql.setObject(1,waiting.authority().member());sql.executeQuery().close(); }
                var write=pool.submit(()->{try { shipments().ship(target.id(),shipmentAuthority(waiting),shipCommand(target,"wait"));return 200; }catch(com.example.marketplace.order.api.OrderRejected e) { return e.status(); }catch(com.example.marketplace.seller.api.StoreException e) { return e.status(); }catch(com.example.marketplace.identity.api.AccountException e) { return 409; }});awaitCouponLock("identity_user");
                if(invalidation.equals("ROLE")) { jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",waiting.authority().member())); }else if(invalidation.equals("SESSION")) { jdbc.update("DELETE FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",waiting.authority().browser())); }else { try(var update=connection.prepareStatement("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=?")) { update.setObject(1,waiting.authority().member());update.executeUpdate(); } }connection.commit();assertTrue(List.of(403,404,409).contains(write.get(10,TimeUnit.SECONDS)),invalidation);
            }assertEquals(initial,shipmentState());
        }
    }
    @Test void shipmentEventsAreIdempotent() throws Exception {
        var f=paymentFixture("f1-events",false);var v=paidShipment(f);var command=shipCommand(v,"ship");var result=shipments().ship(v.id(),shipmentAuthority(f),command);assertEquals("SHIPPED",result.state());assertEquals(1,result.version());var before=shipmentState();assertEquals(result,shipments().ship(v.id(),shipmentAuthority(f),command));assertEquals(before,shipmentState());
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().ship(v.id(),shipmentAuthority(f),new com.example.marketplace.fulfillment.api.Shipments.Command("ship",1L,v.items()))).status());assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().ship(v.id(),shipmentAuthority(f),shipCommand(v,"another"))).status());
        Instant at=Instant.parse("2026-10-28T00:00:00Z");assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().progress(v.id(),"early","DELIVERED",1,at)).status());
        UUID transit=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key","progress:"+v.id()+":IN_TRANSIT"),UUID.class);shipmentJob(transit,Instant.now().plusSeconds(30));UUID delivered=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key","progress:"+v.id()+":DELIVERED"),UUID.class);shipmentJob(delivered,Instant.now().plusSeconds(30));
        var done=shipments().seller(ownSeller(f),shipmentAuthority(f)).getFirst();assertEquals("DELIVERED",done.state());assertEquals(3,done.version());assertEquals(v.items(),done.items());assertEquals(4,done.events().size());
        var original=context.getBean(com.example.marketplace.platform.api.Jobs.class).find(transit).orElseThrow();var stable=shipmentState();var prior=shipments().progress(v.id(),original.command().dedupeKey(),"IN_TRANSIT",1,original.command().nextRunAt());assertEquals("IN_TRANSIT",prior.state());assertEquals(2,prior.version());assertEquals(stable,shipmentState());
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().progress(v.id(),original.command().dedupeKey(),"DELIVERED",1,original.command().nextRunAt())).status());
        var ignored=shipments().progress(v.id(),"late-transit","IN_TRANSIT",1,at);assertFalse(ignored.applied());assertEquals("DELIVERED",ignored.state());assertEquals(3,ignored.version());var lateState=shipmentState();assertEquals(ignored,shipments().progress(v.id(),"late-transit","IN_TRANSIT",1,at));assertEquals(lateState,shipmentState());
        var events=shipments().seller(ownSeller(f),shipmentAuthority(f)).getFirst().events();assertEquals("SYSTEM",events.getFirst().source());assertTrue(events.stream().anyMatch(e->e.source().equals("SELLER")&&e.actor().equals(f.authority().member().toString())&&e.previousState().equals("PREPARING")&&e.nextState().equals("SHIPPED")&&e.version()==1));assertTrue(events.stream().anyMatch(e->e.key().equals("late-transit")&&e.occurredAt().equals(at)&&e.actor().equals("mock-shipment-worker")&&!e.applied()));assertTrue(events.stream().allMatch(e->e.processedAt()!=null));assertEquals(events.size(),jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment_audit WHERE shipment_id=:id",Map.of("id",v.id()),Integer.class));
    }

    private com.example.marketplace.fulfillment.infrastructure.JdbcShipments shipmentsWith(com.example.marketplace.order.api.OrderQueries queries,com.example.marketplace.fulfillment.infrastructure.JdbcShipments.Checkpoint checkpoint) { return new com.example.marketplace.fulfillment.infrastructure.JdbcShipments(jdbc,queries,context.getBean(com.example.marketplace.order.api.FulfillmentOrders.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,Clock.systemUTC(),checkpoint); }
    @Test void shipmentExpiryRecheckedAfterOrderAndShipmentWaits() throws Exception {
        for(String table:List.of("purchase_order","shipment")) {
            var f=paymentFixture("f1-expiry-"+table,false);var v=paidShipment(f);var current=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());Clock clock=new Clock() { @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }@Override public Clock withZone(java.time.ZoneId zone) { return this; }@Override public Instant instant() { return current.get(); } };
            var sessions=new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock);var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class),sessions,context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);var api=shipmentsWith(queries,stage->{ });long expiry=jdbc.queryForObject("SELECT expiry_time FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",f.authority().browser()),Long.class);var before=shipmentState();
            try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM marketplace."+table+" WHERE id=? FOR UPDATE")) { statement.setObject(1,table.equals("shipment")?v.id():f.order().id());statement.executeQuery().close(); }
                var future=pool.submit(()->assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->api.ship(v.id(),shipmentAuthority(f),shipCommand(v,"expiry"))).status());awaitCouponLock(table);current.set(Instant.ofEpochMilli(expiry));connection.commit();assertEquals(404,future.get(10,TimeUnit.SECONDS));
            }assertEquals(before,shipmentState());
        }
    }
    @Test void shipmentAtomicRollbackAndCancellationRace() throws Exception {
        for(String operation:List.of("PREPARE","SHIP")) { for(String point:operation.equals("PREPARE")?List.of("ITEMS","EVENT","AUDIT","OUTBOX"):List.of("STATE","EVENT","AUDIT","OUTBOX")) { for(boolean checked:List.of(false,true)) {
            var f=paymentFixture("f1-rollback-"+operation+point+checked,false);payments().approve(f.order().orderNumber(),f.authority());if(operation.equals("SHIP")) { shipmentJob(settlementJob(f),Instant.now()); }var before=shipmentState();var api=shipmentsWith(context.getBean(com.example.marketplace.order.api.OrderQueries.class),stage->{if(stage.equals(point)) { if(checked) { throw new java.io.IOException("injected"); }throw new IllegalStateException("injected"); }});
            if(operation.equals("PREPARE")) { assertThrows(IllegalStateException.class,()->api.prepare(f.order().orderNumber())); }else { var v=shipments().seller(ownSeller(f),shipmentAuthority(f)).getFirst();assertThrows(IllegalStateException.class,()->api.ship(v.id(),shipmentAuthority(f),shipCommand(v,"rollback"))); }assertEquals(before,shipmentState(),operation+point+checked);
        }}}
        // The existing D compensation path converges from PENDING, never makes a late success shippable.
        var late=paymentFixture("f1-compensation",false);mockPayment(late,"DELAYED_SUCCESS","SUCCESS");payments().approve(late.order().orderNumber(),late.authority());var timed=paymentWith(Clock.fixed(late.order().expiresAt(),ZoneOffset.UTC),stage->{ });timed.apply(late.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);shipmentJob(context.getBean(com.example.marketplace.platform.api.Jobs.class).enqueue(new com.example.marketplace.platform.api.Jobs.Command("FULFILLMENT_PREPARE","f1-late-"+late.order().id(),late.order().id().toString(),late.order().orderNumber(),Instant.EPOCH)),Instant.now());assertTrue(shipments().buyer(late.order().orderNumber(),shipmentAuthority(late)).isEmpty());timed.query(late.order().orderNumber(),((Number)paymentAttempt(late).get("round")).intValue(),0,true);shipmentJob(settlementJob(late),late.order().expiresAt().plusSeconds(1));assertTrue(shipments().buyer(late.order().orderNumber(),shipmentAuthority(late)).isEmpty());
        for(boolean alreadyPrepared:List.of(false,true)) {
            var f=paymentFixture("f1-cancel-wins-"+alreadyPrepared,false);payments().approve(f.order().orderNumber(),f.authority());if(alreadyPrepared) { shipmentJob(settlementJob(f),Instant.now()); }var target=alreadyPrepared?shipments().seller(ownSeller(f),shipmentAuthority(f)).getFirst():null;var before=shipmentState();
            try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { sql.setObject(1,f.order().id());sql.executeQuery().close(); }
                var write=pool.submit(()->{if(alreadyPrepared) { return assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().ship(target.id(),shipmentAuthority(f),shipCommand(target,"canceled"))).status(); }shipments().prepare(f.order().orderNumber());return 200;});awaitCouponLock("purchase_order");try(var sql=connection.prepareStatement("UPDATE marketplace.purchase_order SET state='CANCELED',version=version+1 WHERE id=?")) { sql.setObject(1,f.order().id());sql.executeUpdate(); }connection.commit();assertEquals(alreadyPrepared?409:200,write.get(10,TimeUnit.SECONDS));
            }assertEquals(before,shipmentState());
        }
        var first=paymentFixture("f1-prepare-wins",false);payments().approve(first.order().orderNumber(),first.authority());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var api=shipmentsWith(context.getBean(com.example.marketplace.order.api.OrderQueries.class),stage->{if(stage.equals("ITEMS")) { entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS)); }});
        try(var pool=Executors.newFixedThreadPool(2)) { var preparing=pool.submit(()->api.prepare(first.order().orderNumber()));assertTrue(entered.await(10,TimeUnit.SECONDS));var cancel=pool.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(t->{orders().lock(first.order().orderNumber());orders().transition(first.order().orderNumber(),"CANCELED");}));try { awaitCouponLock("purchase_order"); }finally { release.countDown(); }preparing.get(10,TimeUnit.SECONDS);cancel.get(10,TimeUnit.SECONDS); }
        var prepared=shipments().seller(ownSeller(first),shipmentAuthority(first)).getFirst();assertEquals("PREPARING",prepared.state());assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().ship(prepared.id(),shipmentAuthority(first),shipCommand(prepared,"denied"))).status());
    }
    @Test void shipmentConcurrentPurposeAndVersionHaveOneEffect() throws Exception {
        var f=paymentFixture("f1-concurrent",false);payments().approve(f.order().orderNumber(),f.authority());var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) { var a=pool.submit(()->{gate.await();shipments().prepare(f.order().orderNumber());return true;});var b=pool.submit(()->{gate.await();shipments().prepare(f.order().orderNumber());return true;});gate.countDown();assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS)); }
        assertEquals(2,shipments().buyer(f.order().orderNumber(),shipmentAuthority(f)).size());var v=shipments().seller(ownSeller(f),shipmentAuthority(f)).getFirst();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) { var writes=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();for(String key:List.of("one","two")) { writes.add(pool.submit(()->{start.await();try { shipments().ship(v.id(),shipmentAuthority(f),shipCommand(v,key));return 200; }catch(com.example.marketplace.order.api.OrderRejected denied) { return denied.status(); }})); }start.countDown();var statuses=List.of(writes.get(0).get(10,TimeUnit.SECONDS),writes.get(1).get(10,TimeUnit.SECONDS));assertTrue(statuses.contains(200));assertTrue(statuses.contains(409)); }
        var current=shipments().seller(ownSeller(f),shipmentAuthority(f)).getFirst();assertEquals(1,current.version());assertEquals(2,current.events().size());assertEquals(v.items(),current.items());
        for(int invalid:List.of(0,-1,100)) { assertEquals(400,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().ship(v.id(),shipmentAuthority(f),new com.example.marketplace.fulfillment.api.Shipments.Command("quantity",1L,List.of(new com.example.marketplace.fulfillment.api.Shipments.Item(v.items().getFirst().orderItemId(),invalid))))).status()); }
        assertEquals(400,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().ship(v.id(),shipmentAuthority(f),new com.example.marketplace.fulfillment.api.Shipments.Command("duplicate",1L,List.of(v.items().getFirst(),v.items().getFirst())))).status());
    }

    @Test void shipmentHistoryUsesCausalOrder() throws Exception {
        var f=paymentFixture("f1-causal",false);payments().approve(f.order().orderNumber(),f.authority());var now=new JobClock();now.now=Instant.parse("2026-10-28T01:00:00Z");var api=new com.example.marketplace.fulfillment.infrastructure.JdbcShipments(jdbc,context.getBean(com.example.marketplace.order.api.OrderQueries.class),context.getBean(com.example.marketplace.order.api.FulfillmentOrders.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,now,stage->{ });api.prepare(f.order().orderNumber());var v=api.seller(ownSeller(f),shipmentAuthority(f)).getFirst();now.now=now.now.minusSeconds(10);api.ship(v.id(),shipmentAuthority(f),shipCommand(v,"causal"));api.progress(v.id(),"same-time","IN_TRANSIT",1,now.instant());api.progress(v.id(),"ignored-same-time","IN_TRANSIT",1,now.instant());api.progress(v.id(),"delivered-same-time","DELIVERED",2,now.instant());
        assertEquals(List.of("basic","seller:causal","same-time","ignored-same-time","delivered-same-time"),api.seller(ownSeller(f),shipmentAuthority(f)).getFirst().events().stream().map(com.example.marketplace.fulfillment.api.Shipments.Event::key).toList());
    }
    private Process shipmentWorkerProcess(java.nio.file.Path folder,String phase,Instant now,UUID job,boolean block) throws Exception {
        var args=folder.resolve(phase+".args");var marker=folder.resolve(phase+"-effect.txt");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace((char)92,'/');
        java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.ShipmentWorkerProbe\n"+now+"\n"+job+"\n\""+marker.toString().replace((char)92,'/')+"\"\n"+block+"\n");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile()).start();java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    }
    @Test void shipmentOutboxAndWorkerRecovery() throws Exception {
        var f=paymentFixture("f1-crash",false);payments().approve(f.order().orderNumber(),f.authority());UUID id=settlementJob(f);var jobs=context.getBean(com.example.marketplace.platform.api.Jobs.class);var command=jobs.find(id).orElseThrow().command();var unrelated=jdbc.queryForList("SELECT * FROM marketplace.job WHERE id<>:id ORDER BY id",Map.of("id",id));var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("shipment-worker-"+UUID.randomUUID()));Instant now=Instant.now();Process first=shipmentWorkerProcess(folder,"first",now,id,true),second=null;
        try {
            awaitJobCondition(()->java.nio.file.Files.exists(folder.resolve("first-effect.txt"))||!first.isAlive(),"shipment worker did not reach committed effect");assertTrue(java.nio.file.Files.exists(folder.resolve("first-effect.txt")),"inspect preserved child log");assertEquals("RUNNING",jobs.find(id).orElseThrow().status());var committed=shipments().buyer(f.order().orderNumber(),shipmentAuthority(f));assertEquals(2,committed.size());assertTrue(committed.stream().allMatch(v->v.events().size()==1&&v.state().equals("PREPARING")));
            first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));second=shipmentWorkerProcess(folder,"second",now.plusSeconds(31),id,false);assertTrue(second.waitFor(40,TimeUnit.SECONDS));assertEquals(0,second.exitValue(),"inspect second.log");assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());assertEquals(2,jobs.find(id).orElseThrow().attempts());assertEquals(command,jobs.find(id).orElseThrow().command());assertEquals(committed,shipments().buyer(f.order().orderNumber(),shipmentAuthority(f)));assertTrue(java.nio.file.Files.readString(folder.resolve("completed.txt")).contains("nonweb=true"));
            for(var row:unrelated) { assertEquals(row,jdbc.queryForMap("SELECT * FROM marketplace.job WHERE id=:id",Map.of("id",row.get("id")))); }
        }finally { if(first.isAlive()) { first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS); }if(second!=null&&second.isAlive()) { second.destroyForcibly();second.waitFor(10,TimeUnit.SECONDS); } }
    }

    @Test void shipmentDatabaseItemScopeAndMalformedInput() throws Exception {
        var f=paymentFixture("f1-db-scope",false);var v=paidShipment(f);var foreign=f.order().sellers().stream().filter(row->!row.id().equals(v.sellerOrderId())).findFirst().orElseThrow().items().getFirst();var before=shipmentState();
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.shipment_item(shipment_id,seller_order_id,order_item_id,quantity) VALUES(:shipment,:seller,:item,1)",Map.of("shipment",v.id(),"seller",v.sellerOrderId(),"item",foreign.id())));assertEquals(before,shipmentState());
        assertEquals(400,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().ship(v.id(),shipmentAuthority(f),new com.example.marketplace.fulfillment.api.Shipments.Command("null-item",0L,List.of(v.items().getFirst(),new com.example.marketplace.fulfillment.api.Shipments.Item(null,1))))).status());
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{UUID future=UUID.randomUUID();jdbc.update("INSERT INTO marketplace.shipment(id,order_id,seller_order_id,order_number,purpose_key,state,version,created_at) VALUES(:id,:order,:seller,:number,'FUTURE_TEST','PREPARING',0,now())",Map.of("id",future,"order",f.order().id(),"seller",v.sellerOrderId(),"number",f.order().orderNumber()));jdbc.update("INSERT INTO marketplace.shipment_item(shipment_id,seller_order_id,order_item_id,quantity) VALUES(:id,:seller,:item,1)",Map.of("id",future,"seller",v.sellerOrderId(),"item",v.items().getFirst().orderItemId()));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment WHERE seller_order_id=:id",Map.of("id",v.sellerOrderId()),Integer.class));t.setRollbackOnly();});assertEquals(before,shipmentState());
        jdbc.update("UPDATE marketplace.shipment_item SET quantity=2 WHERE shipment_id=:id",Map.of("id",v.id()));var corrupt=shipmentState();assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->shipments().prepare(f.order().orderNumber())).status());assertEquals(corrupt,shipmentState());

    }
    @Test void shipmentBackfillResumesAndSharesLivePurpose() throws Exception {
        var old=paymentFixture("f1-legacy",false);payments().approve(old.order().orderNumber(),old.authority());UUID oldJob=settlementJob(old);var jobs=context.getBean(com.example.marketplace.platform.api.Jobs.class);var legacyJobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(DataSource.class),oldJob),transactionManager,Clock.systemUTC());var claimed=legacyJobs.claim(java.util.Set.of("PAYMENT_SETTLED")).orElseThrow();assertTrue(legacyJobs.complete(oldJob,claimed.ownerToken()));var oldRecord=jobs.find(oldJob).orElseThrow();assertTrue(shipments().buyer(old.order().orderNumber(),shipmentAuthority(old)).isEmpty());
        var legacyNumbers=new java.util.ArrayList<String>();legacyNumbers.add(old.order().orderNumber());for(int i=0;i<51;i++) { var o=old.order();var copy=orders().save(new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),"legacy-"+i,"b".repeat(64),o.memberId(),null,null,"DEMO",o.quote(),o.contact(),o.createdAt(),o.expiresAt()));new TransactionTemplate(transactionManager).executeWithoutResult(t->orders().transition(copy.orderNumber(),"PAID"));legacyNumbers.add(copy.orderNumber()); }
        var live=paymentFixture("f1-backfill-live",false);payments().approve(live.order().orderNumber(),live.authority());var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("shipment-backfill-worker-"+UUID.randomUUID()));UUID sentinel=jobs.enqueue(new com.example.marketplace.platform.api.Jobs.Command("SHIPMENT_CREATED","startup-probe-"+UUID.randomUUID(),"startup-probe","none",Instant.EPOCH));var bootstrap=shipmentWorkerProcess(folder,"bootstrap",Instant.now(),sentinel,false);
        try { assertTrue(bootstrap.waitFor(40,TimeUnit.SECONDS));assertEquals(0,bootstrap.exitValue()); }finally { if(bootstrap.isAlive()) { bootstrap.destroyForcibly();bootstrap.waitFor(10,TimeUnit.SECONDS); } }
        UUID scan=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key='fulfillment-backfill-v1:START'",Map.of(),UUID.class);assertEquals("READY",jobs.find(scan).orElseThrow().status());
        // A mid-batch durable enqueue failure must not leave either preceding work or the next cursor.
        var candidates=new TransactionTemplate(transactionManager).execute(t->context.getBean(com.example.marketplace.order.api.FulfillmentOrders.class).paidCandidates(null,50));String blocked=candidates.get(1).number();var initial=shipmentState();
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.f1_fail_backfill() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type='FULFILLMENT_PREPARE' AND NEW.payload='"+blocked+"' THEN RAISE EXCEPTION 'injected backfill enqueue failure'; END IF; RETURN NEW; END $$");jdbc.getJdbcTemplate().execute("CREATE TRIGGER f1_fail_backfill BEFORE INSERT ON marketplace.job FOR EACH ROW EXECUTE FUNCTION marketplace.f1_fail_backfill()");
        try { var handler=context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values().stream().filter(h->h.type().equals("FULFILLMENT_BACKFILL")).findFirst().orElseThrow();assertThrows(RuntimeException.class,()->handler.handle(jobs.find(scan).orElseThrow()));assertEquals(initial,shipmentState()); }finally { jdbc.getJdbcTemplate().execute("DROP TRIGGER f1_fail_backfill ON marketplace.job");jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.f1_fail_backfill()"); }
        Instant now=Instant.now();Process first=shipmentWorkerProcess(folder,"first",now,scan,true),second=null;
        try { awaitJobCondition(()->java.nio.file.Files.exists(folder.resolve("first-effect.txt"))||!first.isAlive(),"backfill did not reach batch commit");assertTrue(java.nio.file.Files.exists(folder.resolve("first-effect.txt")));var batch=jdbc.queryForList("SELECT * FROM marketplace.job WHERE type='FULFILLMENT_PREPARE' AND dedupe_key LIKE 'fulfillment-backfill-v1:order:%' ORDER BY id",Map.of());assertEquals(50,batch.size());first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));second=shipmentWorkerProcess(folder,"second",now.plusSeconds(31),scan,false);assertTrue(second.waitFor(40,TimeUnit.SECONDS));assertEquals(0,second.exitValue());assertEquals(2,jobs.find(scan).orElseThrow().attempts());assertEquals(batch,jdbc.queryForList("SELECT * FROM marketplace.job WHERE type='FULFILLMENT_PREPARE' AND dedupe_key LIKE 'fulfillment-backfill-v1:order:%' ORDER BY id",Map.of()));
        }finally { if(first.isAlive()) { first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS); }if(second!=null&&second.isAlive()) { second.destroyForcibly();second.waitFor(10,TimeUnit.SECONDS); } }
        for(int i=0;i<100;i++) { var pending=jdbc.queryForList("SELECT id FROM marketplace.job WHERE type='FULFILLMENT_BACKFILL' AND status='READY' ORDER BY id",Map.of(),UUID.class);if(pending.isEmpty()) { break; }for(UUID id:pending) { shipmentJob(id,Instant.now()); } }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE type='FULFILLMENT_BACKFILL' AND status<>'SUCCEEDED'",Map.of(),Integer.class));
        for(String number:legacyNumbers) { UUID prepare=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='FULFILLMENT_PREPARE' AND payload=:n",Map.of("n",number),UUID.class);shipmentJob(prepare,Instant.now());assertEquals(2,shipments().buyer(number,shipmentAuthority(old)).size()); }
        UUID liveBackfill=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='FULFILLMENT_PREPARE' AND payload=:n",Map.of("n",live.order().orderNumber()),UUID.class);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) { var a=pool.submit(()->{gate.await();shipmentJob(liveBackfill,Instant.now());return true;});var b=pool.submit(()->{gate.await();shipmentJob(settlementJob(live),Instant.now());return true;});gate.countDown();assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS)); }
        assertEquals(2,shipments().buyer(live.order().orderNumber(),shipmentAuthority(live)).size());assertTrue(shipments().buyer(live.order().orderNumber(),shipmentAuthority(live)).stream().allMatch(v->v.events().size()==1));assertEquals(oldRecord,jobs.find(oldJob).orElseThrow());
    }

    @Test void shipmentDatabaseParentOwnershipIsLinked() throws Exception {
        var first=paymentFixture("f1-parent-a",false);var second=paymentFixture("f1-parent-b",false);var before=shipmentState();
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.shipment(id,order_id,seller_order_id,order_number,purpose_key,state,version,created_at) VALUES(:id,:order,:seller,:number,'BASIC','PREPARING',0,now())",Map.of("id",UUID.randomUUID(),"order",first.order().id(),"seller",second.order().sellers().getFirst().id(),"number",first.order().orderNumber())));assertEquals(before,shipmentState());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.shipment(id,order_id,seller_order_id,order_number,purpose_key,state,version,created_at) VALUES(:id,:order,:seller,:number,'BASIC','PREPARING',0,now())",Map.of("id",UUID.randomUUID(),"order",first.order().id(),"seller",first.order().sellers().getFirst().id(),"number",second.order().orderNumber())));assertEquals(before,shipmentState());
    }

    private record PaymentFixture(com.example.marketplace.order.api.Orders.Snapshot order,HttpClient browser,com.example.marketplace.payment.api.Payments.Authority authority) { }
    private PaymentFixture paymentFixture(String suffix,boolean zero) throws Exception {
        suffix=suffix.toLowerCase(java.util.Locale.ROOT).replace('_','-');var d=orderDraft("d-"+suffix);var input=creationInput(d);
        var other=browseFixture("d-other-"+suffix,10000,10);UUID owner=(UUID)other.get("owner"),sku=(UUID)other.get("sku");
        var t=couponTemplate(owner,"두번째 쿠폰",5);var c=coupons().claim(d.memberId(),t.id());
        var lines=new java.util.ArrayList<>(input.lines());lines.add(quoteLine(sku,1));if(suffix.equals("g-totals")) { lines.set(0,quoteLine(lines.getFirst().skuId(),3));lines.set(1,quoteLine(sku,2)); }var selections=new java.util.ArrayList<>(input.coupons());selections.add(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),c.id()));
        if(zero) {
            // Fixture creates genuine free shipping + full-value coupons before the trusted quote/order flow.
            for(var selection:selections) { jdbc.update("UPDATE marketplace.issued_coupon SET value=10000 WHERE id=:id",Map.of("id",selection.couponId())); }
            for(UUID seller:List.of(d.memberId(),owner)) {
                var now=Instant.now();var pi=new com.example.marketplace.promotion.api.Promotions.Input("무료 배송","SHIPPING",List.of(),now.minusSeconds(10),now.plusSeconds(3600),"FREE",0L,null,"DRAFT",0L);
                var p=promotions().save(seller,null,pi);promotions().save(seller,p.id(),new com.example.marketplace.promotion.api.Promotions.Input("무료 배송","SHIPPING",List.of(),pi.startsAt(),pi.endsAt(),"FREE",0L,null,"ACTIVE",0L));
            }
        }
        var q=quotes().calculate(d.memberId(),lines,selections);if(zero) { assertEquals(0,q.total()); }
        var saved=creation().create(d.purchaseSessionId().toString(),d.memberId(),null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input(input.requestKey(),lines,selections,q.fingerprint(),input.contact(),null,0));
        var browser=authBrowser();String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
        String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id",Map.of("id",d.memberId().toString()),String.class);
        Long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),Long.class);
        return new PaymentFixture(saved,browser,new com.example.marketplace.payment.api.Payments.Authority(session,d.memberId(),version,null));
    }
    private com.example.marketplace.payment.infrastructure.PaymentCoordinator payments() { return context.getBean(com.example.marketplace.payment.infrastructure.PaymentCoordinator.class); }
    private com.example.marketplace.payment.infrastructure.PaymentCoordinator paymentWith(Clock clock,com.example.marketplace.payment.infrastructure.PaymentCoordinator.Checkpoint checkpoint) {
        return new com.example.marketplace.payment.infrastructure.PaymentCoordinator(jdbc,orders(),reservations(),couponReservations(),context.getBean(com.example.marketplace.payment.api.PaymentProvider.class),context.getBean(com.example.marketplace.platform.api.Jobs.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.identity.api.PurchaseSessions.class),clock,checkpoint);
    }
    private void mockPayment(PaymentFixture f,String approval,String cancellation) { jdbc.update("INSERT INTO marketplace.payment_mock_setting(order_id,approval,cancellation) VALUES(:id,:a,:c)",Map.of("id",f.order().id(),"a",approval,"c",cancellation)); }
    private Map<String,Object> paymentAttempt(PaymentFixture f) { return jdbc.queryForMap("SELECT * FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id())); }
    private void paymentQuery(PaymentFixture f,boolean compensation) { var a=paymentAttempt(f);payments().query(f.order().orderNumber(),((Number)a.get("round")).intValue(),((Number)a.get("query_count")).intValue(),compensation); }
    private void paymentStates(PaymentFixture f,String order,String inventory,String coupon) {
        assertEquals(order,orders().find(f.order().orderNumber()).state());var ids=orders().reservations(f.order().orderNumber());assertEquals(2,ids.inventory().size());assertEquals(2,ids.coupons().size());
        assertTrue(reservations().inspect(f.order().checkoutId(),ids.inventory()).stream().allMatch(r->r.status().equals(inventory)));
        assertTrue(couponReservations().inspect(f.order().checkoutId(),ids.coupons()).stream().allMatch(r->r.state().equals(coupon)));
    }
    private Map<String,String> paymentParticipantState() { var state=new java.util.TreeMap<>(orderParticipantState());for(String table:List.of("payment_attempt","payment_event","payment_toss_binding","payment_webhook_receipt")) { state.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class)); }return state; }
    @Test void purchaseTotalsAndReservationsConverge() throws Exception {
        var f=paymentFixture("g-totals",false);var original=f.order();paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
        assertEquals(2,original.quote().stores().size());assertEquals(55800,original.quote().total());
        var balance=new java.util.HashMap<UUID,Integer>();for(var store:original.quote().stores()) { for(var line:store.lines()) { balance.put(line.skuId(),jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",line.skuId()),Integer.class)); } }
        assertEquals(200,paymentPost(f.browser(),original.orderNumber(),"{}",csrfToken(f.browser())).statusCode());
        assertEquals(200,paymentPost(f.browser(),original.orderNumber(),"{}",csrfToken(f.browser())).statusCode());
        payments().apply(original.orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);paymentStates(f,"PAID","COMMITTED","COMMITTED");
        var detail=authMapper.readTree(accountRequest(f.browser(),"GET","/api/orders/"+original.orderNumber(),null,null).body());long parent=0;
        for(var store:detail.get("stores")) { long gross=0,promotion=0,coupon=0,net=0;for(var item:store.get("items")) { long ug=0,up=0,uc=0,un=0;assertEquals(item.get("quantity").asInt(),item.get("units").size());for(var unit:item.get("units")) { ug+=unit.get("gross").asLong();up+=unit.get("promotionDiscount").asLong();uc+=unit.get("couponDiscount").asLong();un+=unit.get("net").asLong();assertEquals(unit.get("gross").asLong()-unit.get("promotionDiscount").asLong()-unit.get("couponDiscount").asLong(),unit.get("net").asLong()); }assertEquals(ug,item.get("gross").asLong());assertEquals(up,item.get("promotionDiscount").asLong());assertEquals(uc,item.get("couponDiscount").asLong());assertEquals(un,item.get("net").asLong());gross+=ug;promotion+=up;coupon+=uc;net+=un;
                UUID sku=UUID.fromString(item.get("skuId").asText());assertEquals(balance.get(sku)-item.get("quantity").asInt(),jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku),Integer.class));
            }assertEquals(gross,store.get("subtotal").asLong());assertEquals(promotion,store.get("productDiscount").asLong());assertEquals(coupon,store.get("couponDiscount").asLong());assertEquals(net+store.get("shippingBase").asLong()-store.get("shippingDiscount").asLong(),store.get("total").asLong());parent+=store.get("total").asLong(); }
        assertEquals(55800,parent);assertEquals(parent,detail.get("total").asLong());assertEquals(parent,jdbc.queryForObject("SELECT amount FROM marketplace.purchase_order WHERE id=:id",Map.of("id",original.id()),Long.class));
        var attempt=paymentAttempt(f);assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",attempt.get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT approval_effects FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",attempt.get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='SUCCEEDED'",Map.of("id",attempt.get("id")),Integer.class));
        shipmentJob(settlementJob(f),Instant.now());var rows=shipments().buyer(original.orderNumber(),shipmentAuthority(f));assertEquals(2,rows.size());for(var seller:original.sellers()) { var row=rows.stream().filter(r->r.sellerOrderId().equals(seller.id())).findFirst().orElseThrow();assertEquals("PREPARING",row.state());assertEquals(seller.items().stream().map(i->new com.example.marketplace.fulfillment.api.Shipments.Item(i.id(),i.quantity())).toList(),row.items());assertEquals(1,row.events().size()); }
        shipments().prepare(original.orderNumber());assertEquals(rows,shipments().buyer(original.orderNumber(),shipmentAuthority(f)));
    }
    @Test void zeroPaymentCommitsAllOnce() throws Exception {
        var f=paymentFixture("zero",true);assertEquals("PAID",payments().approve(f.order().orderNumber(),f.authority()).orderState());payments().approve(f.order().orderNumber(),f.authority());payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);
        paymentStates(f,"PAID","COMMITTED","COMMITTED");var a=paymentAttempt(f);assertEquals("SUCCEEDED",a.get("state"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='SUCCEEDED'",Map.of("id",a.get("id")),Integer.class));
    }
    @Test void paymentApprovalAndExpiryHaveOneOutcome() throws Exception {
        var f=paymentFixture("race",false);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var api=paymentWith(Clock.systemUTC(),stage->{if(stage.equals("PREPARED")) { entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS)); }});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var approval=pool.submit(()->api.approve(f.order().orderNumber(),f.authority()));assertTrue(entered.await(10,TimeUnit.SECONDS));
            var due=paymentWith(Clock.fixed(f.order().expiresAt(),ZoneOffset.UTC),stage->{ });var expiry=pool.submit(()->due.expire(f.order().orderNumber()));expiry.get(10,TimeUnit.SECONDS);paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals("PROCESSING",paymentAttempt(f).get("state"));
            payments().approve(f.order().orderNumber(),f.authority());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));release.countDown();assertEquals("PAID",approval.get(10,TimeUnit.SECONDS).orderState());paymentStates(f,"PAID","COMMITTED","COMMITTED");
        }finally { release.countDown(); }
        var expired=paymentFixture("expiry-first",false);paymentWith(Clock.fixed(expired.order().expiresAt(),ZoneOffset.UTC),stage->{ }).expire(expired.order().orderNumber());paymentStates(expired,"EXPIRED","RELEASED","RELEASED");assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->payments().approve(expired.order().orderNumber(),expired.authority()));
    }
    @Test void paymentUnknownAndLateSuccessConverge() throws Exception {
        var f=paymentFixture("lost",false);mockPayment(f,"LOST_RESPONSE","SUCCESS");assertEquals("UNKNOWN",payments().approve(f.order().orderNumber(),f.authority()).paymentState());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");paymentQuery(f,false);paymentStates(f,"PAID","COMMITTED","COMMITTED");payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.FAILED);paymentStates(f,"PAID","COMMITTED","COMMITTED");
        for(String cancel:List.of("SUCCESS","LOST_RESPONSE","REJECT","QUERY_FAILURE")) {
            var late=paymentFixture("late-"+cancel,false);mockPayment(late,"DELAYED_SUCCESS",cancel);payments().approve(late.order().orderNumber(),late.authority());var clock=new JobClock();clock.now=late.order().expiresAt();var timed=paymentWith(clock,stage->{ });
            // Provider success after the actual order deadline: no reservation deadline is extended or bypassed.
            var a=paymentAttempt(late);var provider=context.getBean(com.example.marketplace.payment.api.PaymentProvider.class);provider.query(new com.example.marketplace.payment.api.PaymentProvider.Request((UUID)a.get("id"),late.order().id(),late.order().quote().total(),"DEMO"));
            timed.apply(late.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);assertEquals("PENDING",paymentAttempt(late).get("compensation"));paymentStates(late,"PENDING_PAYMENT","RESERVED","RESERVED");
            paymentQuery(late,true);if(cancel.equals("LOST_RESPONSE")) { paymentQuery(late,true); }
            if(cancel.equals("SUCCESS")||cancel.equals("LOST_RESPONSE")) { paymentStates(late,"CANCELED","RELEASED","RELEASED");assertEquals("CANCELED",paymentAttempt(late).get("compensation")); }
            else if(cancel.equals("REJECT")) { assertEquals(true,paymentAttempt(late).get("review_required"));paymentStates(late,"PENDING_PAYMENT","RESERVED","RESERVED"); }
            else { for(int i=1;i<5;i++) { paymentQuery(late,true); }assertEquals(true,paymentAttempt(late).get("review_required"));paymentStates(late,"PENDING_PAYMENT","RESERVED","RESERVED"); }
            timed.apply(late.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='COMPENSATION_PENDING'",Map.of("id",a.get("id")),Integer.class));
        }
        var released=paymentFixture("released-late",false);mockPayment(released,"REJECT","SUCCESS");payments().approve(released.order().orderNumber(),released.authority());paymentStates(released,"CANCELED","RELEASED","RELEASED");var a=paymentAttempt(released);jdbc.update("UPDATE marketplace.payment_mock SET state='SUCCEEDED',approval_effects=1 WHERE attempt_id=:id",Map.of("id",a.get("id")));payments().apply(released.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);paymentQuery(released,true);paymentStates(released,"CANCELED","RELEASED","RELEASED");assertEquals(1,jdbc.queryForObject("SELECT cancel_effects FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));
    }
    @Test void paymentResultRollbackAndDuplicateEvents() throws Exception {
        for(boolean checked:List.of(false,true)) { for(String point:List.of("COUPON","INVENTORY","ORDER","AUDIT","OUTBOX")) {
            var f=paymentFixture("rollback-"+point+checked,false);mockPayment(f,"LOST_RESPONSE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());var before=paymentParticipantState();var api=paymentWith(Clock.systemUTC(),stage->{if(stage.equals(point)) { if(checked) { throw new java.io.IOException("checked"); }throw new IllegalStateException("runtime"); }});
            assertThrows(IllegalStateException.class,()->api.apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED));assertEquals(before,paymentParticipantState(),point+checked);paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");paymentQuery(f,false);paymentStates(f,"PAID","COMMITTED","COMMITTED");payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.UNKNOWN);paymentStates(f,"PAID","COMMITTED","COMMITTED");
        } }
        var f=paymentFixture("boundary-after-coupon",false);mockPayment(f,"LOST_RESPONSE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());UUID stock=orders().reservations(f.order().orderNumber()).inventory().getLast();
        // The real inventory clock sees an expired row; coupon commit occurred first inside the result transaction.
        jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id",Map.of("id",stock));payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals("PENDING",paymentAttempt(f).get("compensation"));paymentQuery(f,true);paymentStates(f,"CANCELED","RELEASED","RELEASED");
    }
    @Test void paymentUnknownHoldsReservationsAndExhaustedJobsAreVisible() throws Exception {
        for(String mode:List.of("queries","time","delivery")) {
            var f=paymentFixture("review-"+mode,false);mockPayment(f,"QUERY_FAILURE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());
            if(mode.equals("queries")) { for(int i=0;i<5;i++) { paymentQuery(f,false); } }
            else if(mode.equals("time")) { var a=paymentAttempt(f);paymentWith(Clock.fixed(((java.sql.Timestamp)a.get("round_started_at")).toInstant().plusSeconds(900),ZoneOffset.UTC),stage->{ }).get(f.order().orderNumber(),f.authority()); }
            else { var a=paymentAttempt(f);jdbc.update("UPDATE marketplace.job SET attempts=5,status='RUNNING',owner_token=:token,lease_until=clock_timestamp()-interval '1 second' WHERE id=:id",Map.of("id",a.get("next_job"),"token",UUID.randomUUID()));context.getBean(com.example.marketplace.platform.api.Jobs.class).claim(java.util.Set.of("PAYMENT_QUERY"));payments().get(f.order().orderNumber(),f.authority()); }
            assertEquals(true,paymentAttempt(f).get("review_required"));payments().approve(f.order().orderNumber(),f.authority());paymentWith(Clock.fixed(f.order().expiresAt(),ZoneOffset.UTC),stage->{ }).expire(f.order().orderNumber());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
        }
    }


    @Test void paymentHttpOwnershipAndTampering() throws Exception {
        var d=orderDraft("d-http");var saved=creation().create(d.purchaseSessionId().toString(),d.memberId(),null,creationInput(d));var browser=authBrowser();
        String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
        var result=browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/payments/"+saved.orderNumber())).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,result.statusCode());assertTrue(result.body().contains("PENDING_PAYMENT"));
    }



    private com.example.marketplace.order.infrastructure.OrderCreationService creation() { return context.getBean(com.example.marketplace.order.infrastructure.OrderCreationService.class); }
    private com.example.marketplace.order.infrastructure.OrderCreationService.Input creationInput(com.example.marketplace.order.api.Orders.Draft d) {
        return new com.example.marketplace.order.infrastructure.OrderCreationService.Input(d.requestKey(),d.quote().stores().stream().flatMap(s->s.lines().stream()).map(l->quoteLine(l.skuId(),l.quantity())).toList(),d.quote().stores().stream().filter(s->s.coupon()!=null).map(s->new com.example.marketplace.promotion.api.Quotes.CouponSelection(s.storeId(),s.coupon().id())).toList(),d.quote().fingerprint(),d.contact(),d.guestAttemptId(),0);
    }
    @Test void orderCreationLockOrderAndAuthorityRaces() throws Exception {
        var d=orderDraft("c-lock");var input=creationInput(d);
        try(var connection=context.getBean(DataSource.class).getConnection();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { statement.setObject(1,d.memberId());statement.executeQuery().close(); }
            var future=pool.submit(()->{try { creation().create(d.purchaseSessionId().toString(),d.memberId(),null,input);return true; }catch(RuntimeException rejected) { return false; }});
            try { awaitCouponLock("identity_user");try(var statement=connection.prepareStatement("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=?")) { statement.setObject(1,d.memberId());statement.executeUpdate(); }connection.commit();assertFalse(future.get(10,TimeUnit.SECONDS)); }
            finally { connection.rollback(); }
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",d.memberId()),Integer.class));
    }
    @Test void orderRetryReturnsSameResult() throws Exception {
        var d=orderDraft("c-retry");var input=creationInput(d);var line=d.quote().stores().getFirst().lines().getFirst();
        jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=1 WHERE sku_id=:id",Map.of("id",line.skuId()));
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(0,1).stream().map(index->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return creation().create(d.purchaseSessionId().toString(),d.memberId(),null,input);})).toList();
            var first=tasks.get(0).get(15,TimeUnit.SECONDS);assertEquals(first,tasks.get(1).get(15,TimeUnit.SECONDS));
            assertEquals(first,creation().create(d.purchaseSessionId().toString(),d.memberId(),null,input));
            assertEquals("PENDING_PAYMENT",first.state());assertEquals(1,first.sellers().size());assertEquals(1,orders().reservations(first.orderNumber()).inventory().size());assertEquals(1,orders().reservations(first.orderNumber()).coupons().size());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_reservation WHERE order_id=:id",Map.of("id",first.id()),Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",line.skuId()),Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_event WHERE order_id=:id",Map.of("id",first.id()),Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event WHERE id=:id",Map.of("id",first.id()),Integer.class));
            assertEquals(first.expiresAt(),jdbc.queryForObject("SELECT scheduled_at FROM marketplace.job WHERE cause_key=:id",Map.of("id",first.id().toString()),java.sql.Timestamp.class).toInstant());
            var changed=new com.example.marketplace.order.infrastructure.OrderCreationService.Input(input.requestKey(),input.lines(),input.coupons(),"c".repeat(64),input.contact(),null,0);
            assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->creation().create(d.purchaseSessionId().toString(),d.memberId(),null,changed)).status());
            UUID other=activeMember("c-retry-other@example.com");
            assertEquals(401,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->creation().create(d.purchaseSessionId().toString(),other,null,input)).status());
        }
    }
    private com.example.marketplace.order.infrastructure.OrderCreationService creationWith(com.example.marketplace.guest.api.GuestOrders guest,com.example.marketplace.order.infrastructure.OrderCreationService.Checkpoint probe) {
        return new com.example.marketplace.order.infrastructure.OrderCreationService(jdbc,orders(),quotes(),context.getBean(com.example.marketplace.catalog.api.PurchaseOffers.class),guest,context.getBean(com.example.marketplace.identity.api.SellerMembership.class),couponReservations(),context.getBean(com.example.marketplace.inventory.api.Reservations.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,"DEMO",probe);
    }
    private Map<String,String> orderParticipantState() {
        var result=new java.util.TreeMap<String,String>();
        for(String table:List.of("guest_checkout","guest_attempt","guest_buyer","guest_access_session","guest_order_reference","order_checkout_attempt","purchase_order","seller_order","order_item","order_unit","order_contact","order_reservation","order_event","inventory_balance","inventory_reservation","inventory_reservation_event","coupon_reservation_batch","issued_coupon","coupon_reservation","coupon_reservation_event","job","outbox_event")) {
            result.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));
        }
        return result;
    }
    @Test void orderCreationRollsBackAllParticipants() {
        var d=orderDraft("c-rollback");var memberInput=creationInput(d);
        var f=new GuestFixture();var access=guestAccess(f);UUID attempt=guestVerified(f);access.prepare(f.browser,attempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var guestQuote=quotes().calculate(null,memberInput.lines(),List.of());
        var guestInput=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("guest-rollback",memberInput.lines(),List.of(),guestQuote.fingerprint(),d.contact(),attempt,0);
        for(boolean guest:List.of(false,true)) { for(boolean checked:List.of(false,true)) { for(String point:List.of("GUEST","ORDER","COUPON","INVENTORY","REFERENCE","AUDIT","OUTBOX")) {
            var api=creationWith(access,stage->{if(stage.equals(point)) { if(checked) { throw new java.io.IOException("checked injection"); }throw new IllegalStateException("runtime injection"); }});
            var before=orderParticipantState();
            var failure=assertThrows(IllegalStateException.class,()->api.create(guest?f.browser:d.purchaseSessionId().toString(),guest?null:d.memberId(),null,guest?guestInput:memberInput));
            if(checked) { assertTrue(failure.getCause() instanceof java.io.IOException); }
            assertEquals(before,orderParticipantState(),"all rows restored after "+point+" guest="+guest+" checked="+checked);
        } } }
    }
    @Test void guestOrderConsumesVerificationAtomically() {
        var f=new GuestFixture();var access=guestAccess(f);var api=creationWith(access,stage->{ });UUID firstAttempt=guestVerified(f);access.prepare(f.browser,firstAttempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var fixture=browseFixture("c-guest",10000,10);var lines=List.of(quoteLine((UUID)fixture.get("sku"),1));
        var contact=new com.example.marketplace.order.api.Orders.Contact("ignored","01012345678","수령인","01012345678","01234","주소","상세");
        var q=quotes().calculate(null,lines,List.of());
        var input=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("first",lines,List.of(),q.fingerprint(),contact,firstAttempt,0);
        var first=api.create(f.browser,null,null,input);assertEquals(first,api.create(f.browser,null,null,input));assertEquals("가상 구매자",first.contact().buyerName());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE order_number=:n AND guest_buyer_id=:id",Map.of("n",first.orderNumber(),"id",first.guestBuyerId()),Integer.class));
        assertEquals(first,api.create(f.browser,null,null,input));
        UUID next=guestRepeat(f);access.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var secondInput=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("second",lines,List.of(),q.fingerprint(),contact,next,0);
        var second=api.create(f.browser,null,null,secondInput);assertThat(second.guestBuyerId()).isNotEqualTo(first.guestBuyerId());
        assertEquals(first,api.create(f.browser,null,null,input));
        String token=access.login(first.orderNumber(),f.phone,GUEST_PASSWORD,f.source,null);UUID third=guestRepeat(f);var link=access.selectLink(f.browser,third,"EXISTING",token,0);
        var thirdInput=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("third",lines,List.of(),q.fingerprint(),contact,third,link.revision());
        var before=orderParticipantState();assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->api.create(f.browser,null,null,thirdInput));assertEquals(before,orderParticipantState());
        var linked=api.create(f.browser,null,token,thirdInput);assertEquals(first.guestBuyerId(),linked.guestBuyerId());assertEquals(linked,api.create(f.browser,null,token,thirdInput));
        access.logout(token);assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->api.create(f.browser,null,token,thirdInput));
        f.clock.advance(901);UUID fourth=guestRepeat(f);access.prepare(f.browser,fourth,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var stale=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("stale",lines,List.of(),q.fingerprint(),contact,fourth,0);
        access.selectLink(f.browser,fourth,"NEW",null,0);before=orderParticipantState();assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->api.create(f.browser,null,null,stale));assertEquals(before,orderParticipantState());
    }
    @Test void orderHttpCsrfAndCurrentSessionOwnership() throws Exception {
        var d=orderDraft("c-http");var browser=authBrowser();
        String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
        var input=creationInput(d);String body=authMapper.writeValueAsString(input);
        var denied=browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/orders")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,denied.statusCode());
        String csrf=csrfToken(browser);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/orders")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var success=browser.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,success.statusCode());assertTrue(success.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(success.body(),browser.send(request,HttpResponse.BodyHandlers.ofString()).body());assertFalse(success.body().contains("buyerPhone"));assertEquals("PENDING_PAYMENT",authMapper.readTree(success.body()).get("state").asText());
    }
    @Test void orderCartReconciliationPreservesChangedAndAddedItems() {
        UUID buyer=activeMember("c-cleanup@example.com");var a=browseFixture("c-cart-a",100,10);var b=browseFixture("c-cart-b",100,10);var c=browseFixture("c-cart-c",100,10);
        var view=carts().set(buyer,(UUID)a.get("sku"),1,0);view=carts().set(buyer,(UUID)b.get("sku"),2,view.version());
        var submitted=view.items().stream().map(l->new com.example.marketplace.cart.api.Carts.Submitted(l.skuId(),l.quantity(),l.revision())).toList();
        view=carts().set(buyer,(UUID)b.get("sku"),3,view.version());view=carts().set(buyer,(UUID)b.get("sku"),2,view.version());view=carts().set(buyer,(UUID)c.get("sku"),1,view.version());
        UUID order=UUID.randomUUID();var cleaned=org.junit.jupiter.api.Assertions.assertDoesNotThrow(()->carts().reconcile(buyer,order,submitted));
        assertThat(cleaned.items().stream().map(l->l.skuId()).toList()).containsExactlyInAnyOrder((UUID)b.get("sku"),(UUID)c.get("sku"));
        var added=carts().set(buyer,(UUID)a.get("sku"),1,cleaned.version());assertEquals(added,carts().reconcile(buyer,order,submitted));
        assertThrows(com.example.marketplace.cart.api.CartException.class,()->carts().reconcile(buyer,order,List.of()));
    }
    private com.example.marketplace.order.infrastructure.OrderCreationService.Input orderInput(UUID buyer,List<com.example.marketplace.promotion.api.Quotes.LineRequest> lines,List<com.example.marketplace.promotion.api.Quotes.CouponSelection> selected,String key) {
        var q=quotes().calculate(buyer,lines,selected);
        return new com.example.marketplace.order.infrastructure.OrderCreationService.Input(key,lines,selected,q.fingerprint(),new com.example.marketplace.order.api.Orders.Contact("구매자","01012345678","수령인","01087654321","01234","서울","101호"),null,null);
    }
    @Test void orderAuthorityChangesAfterActualRowWait() throws Exception {
        for(String mode:List.of("seller","store","product","category","price","coupon","expiry")) {
            var d=orderDraft("c-authority-"+mode);var input=creationInput(d);var store=d.quote().stores().getFirst();var line=store.lines().getFirst();
            String table=switch(mode) {case "seller"->"identity_user";case "store"->"seller_store";case "product"->"catalog_product";case "category"->"catalog_category";case "price"->"catalog_sku";default->"issued_coupon";};
            UUID id=switch(mode) {case "seller"->d.memberId();case "store"->store.storeId();case "product"->line.productId();case "category"->jdbc.queryForObject("SELECT category_id FROM marketplace.catalog_product WHERE id=:id",Map.of("id",line.productId()),UUID.class);case "price"->line.skuId();default->store.coupon().id();};
            try(var pool=Executors.newSingleThreadExecutor()) {
                var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",id),UUID.class);
                    pending.set(pool.submit(()->{try {creation().create(d.purchaseSessionId().toString(),d.memberId(),null,input);return true;}catch(RuntimeException rejected){return false;}}));
                    awaitCouponLock(table);
                    String change=switch(mode) {case "seller"->"state='SUSPENDED'";case "store"->"visible=false";case "product"->"is_hidden_by_admin=true";case "category"->"active=false";case "price"->"price=price+1,version=version+1";case "coupon"->"state='USED',version=version+1";default->"ends_at=clock_timestamp()-interval '1 second'";};
                    jdbc.update("UPDATE marketplace."+table+" SET "+change+" WHERE id=:id",Map.of("id",id));
                });
                assertFalse(pending.get().get(15,TimeUnit.SECONDS),mode);
            }
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",d.memberId()),Integer.class),mode);
            assertEquals(0,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",line.skuId()),Integer.class),mode);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE coupon_id=:id",Map.of("id",store.coupon().id()),Integer.class),mode);
        }
    }
    @Test void orderAdministratorAuditLocksDoNotCycle() throws Exception {
        for(boolean category:List.of(true,false)) {
            var a=browseFixture("c-admin-a-"+category,10000,10);var b=browseFixture("c-admin-b-"+category,10000,10);
            var left=((UUID)a.get("owner")).toString().compareTo(((UUID)b.get("owner")).toString())<0?a:b;var right=left==a?b:a;
            UUID actor=(UUID)left.get("owner"),seller=(UUID)right.get("owner");var product=(com.example.marketplace.catalog.api.Products.Product)right.get("product");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",actor));jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT'),(:id,'ACCOUNTS')",Map.of("id",actor));
            var input=orderInput(actor,List.of(quoteLine((UUID)right.get("sku"),1)),List.of(),"admin");String browser=UUID.randomUUID().toString();
            try(var pool=Executors.newSingleThreadExecutor()) {
                var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    String table=category?"catalog_category":"identity_user";UUID locked=category?product.categoryId():seller;
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",locked),UUID.class);
                    pending.set(pool.submit(()->{try {creation().create(browser,actor,null,input);return true;}catch(RuntimeException rejected){return false;}}));
                    awaitCouponLock(table);
                    // Real administrator command writes an actor FK while C owns actor's purchase lock.
                    if(category) {var row=jdbc.queryForMap("SELECT name,version FROM marketplace.catalog_category WHERE id=:id",Map.of("id",locked));context.getBean(com.example.marketplace.catalog.api.Categories.class).save(actor,locked,new com.example.marketplace.catalog.api.Categories.Input((String)row.get("name"),false,((Number)row.get("version")).longValue()));}
                    else {context.getBean(com.example.marketplace.identity.api.AccountStateOperations.class).suspend(actor,seller,"order audit race");}
                });
                assertFalse(pending.get().get(15,TimeUnit.SECONDS));
            }
            String audit=category?"category_event":"account_state_audit";assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace."+audit+" WHERE actor_id=:id",Map.of("id",actor),Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",actor),Integer.class));
        }
    }
    @Test void orderCrossSellerAndSameBuyerBatchesSerializeBeforeForeignKeys() throws Exception {
        for(boolean cross:List.of(true,false)) {
            var a=reservationRequest("c-cross-a-"+cross);var b=reservationRequest("c-cross-b-"+cross);
            UUID first=cross?a.memberId():activeMember("c-samebuyer@example.com"),second=cross?b.memberId():first;
            var buyers=List.of(first,second);var sources=List.of(b,a);var inputs=new java.util.ArrayList<com.example.marketplace.order.infrastructure.OrderCreationService.Input>();
            for(int i=0;i<2;i++) {var source=sources.get(i);var coupon=coupons().claim(buyers.get(i),coupons().own(source.memberId(),source.issuedCouponId()).templateId());inputs.add(orderInput(buyers.get(i),List.of(quoteLine(source.quoteSnapshot().stores().getFirst().lines().getFirst().skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(coupon.storeId(),coupon.id())),"cross-"+i));}
            UUID locked=cross?java.util.stream.Stream.of(first,second).min(java.util.Comparator.comparing(UUID::toString)).orElseThrow():first;
            try(var pool=Executors.newFixedThreadPool(2);var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")){statement.setObject(1,locked);statement.executeQuery().close();}
                var tasks=List.of(0,1).stream().map(i->pool.submit(()->creation().create(UUID.randomUUID().toString(),buyers.get(i),null,inputs.get(i)))).toList();
                try {awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%identity_user%FOR NO KEY UPDATE%'",Integer.class)>=2,"both purchases actually wait before FK insertion");connection.commit();for(var task:tasks){var saved=task.get(15,TimeUnit.SECONDS);assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_reservation WHERE order_id=:id",Map.of("id",saved.id()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_event WHERE order_id=:id",Map.of("id",saved.id()),Integer.class));}}
                finally {connection.rollback();}
            }
        }
    }
    @Test void orderLastStockHasOneWinnerAndNoPartialCoupon() throws Exception {
        var seller=browseFixture("c-last-stock",10000,1);UUID owner=(UUID)seller.get("owner"),sku=(UUID)seller.get("sku");var template=couponTemplate(owner,"last stock",5);
        var buyers=List.of(activeMember("c-stock-first@example.com"),activeMember("c-stock-second@example.com"));var inputs=new java.util.ArrayList<com.example.marketplace.order.infrastructure.OrderCreationService.Input>();var issued=new java.util.ArrayList<UUID>();
        for(UUID buyer:buyers){var coupon=coupons().claim(buyer,template.id());issued.add(coupon.id());inputs.add(orderInput(buyer,List.of(quoteLine(sku,1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(coupon.storeId(),coupon.id())),"last"));}
        var barrier=new java.util.concurrent.CyclicBarrier(2);int winners=0;
        try(var pool=Executors.newFixedThreadPool(2)){var tasks=List.of(0,1).stream().map(i->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);try{return creation().create(UUID.randomUUID().toString(),buyers.get(i),null,inputs.get(i));}catch(com.example.marketplace.promotion.api.PromotionRejected rejected){assertEquals(409,rejected.status());return null;}})).toList();for(var task:tasks){if(task.get(15,TimeUnit.SECONDS)!=null){winners++;}}}
        assertEquals(1,winners);assertEquals(1,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.issued_coupon WHERE id IN (:ids) AND state='RESERVED'",Map.of("ids",issued),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.issued_coupon WHERE id IN (:ids) AND state='AVAILABLE'",Map.of("ids",issued),Integer.class));
    }
    @Test void guestOrderRejectsStaleQuoteAndCurrentCredentialChanges(CapturedOutput output) throws Exception {
        for(String mode:List.of("price","expired","replaced","revoked","coupons","checkout-wait")) {
            var f=new GuestFixture();var access=guestAccess(f);UUID attempt=guestVerified(f);access.prepare(f.browser,attempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
            var fixture=browseFixture("c-guest-reject-"+mode,10000,10);UUID sku=(UUID)fixture.get("sku");var lines=List.of(quoteLine(sku,1));var q=quotes().calculate(null,lines,List.of());var contact=new com.example.marketplace.order.api.Orders.Contact("ignored","01012345678","수령인","01012345678","01234","주소","");
            var selected=mode.equals("coupons")?List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(UUID.randomUUID(),UUID.randomUUID())):List.<com.example.marketplace.promotion.api.Quotes.CouponSelection>of();
            var input=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("reject",lines,selected,q.fingerprint(),contact,attempt,0);var api=creationWith(access,stage->{ });
            switch(mode) {
                case "price"->jdbc.update("UPDATE marketplace.catalog_sku SET price=price+1000,version=version+1 WHERE id=:id",Map.of("id",sku));
                case "expired"->f.clock.advance(181);
                case "replaced"->{f.clock.advance(60);f.service.issue(f.browser,f.token,new com.example.marketplace.guest.api.GuestInput("changed","01099998888","PURCHASE"),f.source);}
                case "revoked"->jdbc.update("UPDATE marketplace.guest_checkout SET state='REVOKED' WHERE current_attempt=:id",Map.of("id",attempt));
                default->{ }
            }
            var before=orderParticipantState();
            if(mode.equals("checkout-wait")) {
                try(var pool=Executors.newSingleThreadExecutor()) {
                    var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
                    new TransactionTemplate(transactionManager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM marketplace.guest_checkout WHERE current_attempt=:id FOR UPDATE",Map.of("id",attempt),UUID.class);pending.set(pool.submit(()->{try{api.create(f.browser,null,null,input);return true;}catch(RuntimeException denied){return false;}}));awaitCouponLock("guest_checkout");f.clock.advance(181);});assertFalse(pending.get().get(10,TimeUnit.SECONDS));
                }
            } else {
                var rejected=assertThrows(RuntimeException.class,()->api.create(f.browser,null,null,input));
                if(mode.equals("price")) {var changed=(com.example.marketplace.order.api.OrderRejected)rejected;assertEquals(409,changed.status());assertThat(changed.quote()).isNotNull();assertThat(changed.quote().fingerprint()).isNotEqualTo(q.fingerprint());}
            }
            assertEquals(before,orderParticipantState(),mode);
            assertFalse(String.join("",before.values()).contains(GUEST_PASSWORD));assertFalse(output.getAll().contains(GUEST_PASSWORD));assertFalse(output.getAll().contains("valid-proof"));
        }
    }
    @Test void purchaseAccountLockAllowsAuditForeignKeysButExcludesMutations() {
        UUID member=activeMember("c-lock-strength@example.com");var membership=context.getBean(com.example.marketplace.identity.api.SellerMembership.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            membership.lockActiveBuyer(member);
            try(var other=context.getBean(DataSource.class).getConnection()) {
                other.setAutoCommit(false);
                try(var statement=other.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR KEY SHARE NOWAIT")){statement.setObject(1,member);assertTrue(statement.executeQuery().next());}other.rollback();
                try(var statement=other.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE NOWAIT")){statement.setObject(1,member);assertEquals("55P03",assertThrows(java.sql.SQLException.class,statement::executeQuery).getSQLState());}other.rollback();
                try(var statement=other.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR NO KEY UPDATE NOWAIT")){statement.setObject(1,member);assertEquals("55P03",assertThrows(java.sql.SQLException.class,statement::executeQuery).getSQLState());}other.rollback();
            }catch(java.sql.SQLException e){throw new IllegalStateException(e);}
        });
    }
    @Test void orderManualStockAdjustmentSerializesAndRechecksAvailability() throws Exception {
        var fixture=browseFixture("c-manual-stock",10000,1);UUID owner=(UUID)fixture.get("owner"),sku=(UUID)fixture.get("sku");var product=(com.example.marketplace.catalog.api.Products.Product)fixture.get("product");var input=orderInput(owner,List.of(quoteLine(sku,1)),List.of(),"manual");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                context.getBean(com.example.marketplace.identity.api.SellerMembership.class).lockActiveBuyer(owner);
                pending.set(pool.submit(()->{try{creation().create(UUID.randomUUID().toString(),owner,null,input);return true;}catch(RuntimeException denied){return false;}}));awaitCouponLock("identity_user");
                context.getBean(com.example.marketplace.inventory.api.Inventory.class).adjust(owner,product.id(),sku,false,new com.example.marketplace.inventory.api.Inventory.Change(0L,0L,"concurrent reduction",UUID.randomUUID()));
            });assertFalse(pending.get().get(10,TimeUnit.SECONDS));
        }
        assertEquals(0,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",owner),Integer.class));
    }
    @Test void orderHttpRechecksSessionAfterActualAccountWait() throws Exception {
        for(boolean retry:List.of(false,true)) { for(String mode:List.of("logout","expired","password")) {
            var d=orderDraft("c-session-"+mode+retry);var browser=authBrowser();String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);
            assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
            String csrf=csrfToken(browser);var input=creationInput(d);var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/orders")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(input))).build();
            var recovery=context.getBean(com.example.marketplace.identity.api.PasswordRecoveryOperations.class);String token=null;
            if(mode.equals("password")) {recovery.request(email);token=context.getBean(LocalPasswordResetMailbox.class).messages().stream().filter(message->message.recipient().equals(email)).findFirst().orElseThrow().token();}
            String recoveryToken=token;
            if(retry) { assertEquals(200,browser.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()); }
            var participants=orderParticipantState();
            try(var pool=Executors.newSingleThreadExecutor()) {
                var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",d.memberId()),UUID.class);
                    pending.set(pool.submit(()->browser.send(request,HttpResponse.BodyHandlers.ofString())));awaitCouponLock("identity_user");
                    if(mode.equals("password")){recovery.reset(recoveryToken,"replacement session password");}
                    else if(mode.equals("expired")){jdbc.update("UPDATE marketplace.spring_session SET expiry_time=0 WHERE principal_name=:id",Map.of("id",d.memberId().toString()));}
                    else {try{assertEquals(200,authRequest(browser,"/api/auth/logout","",csrf).statusCode());}catch(Exception e){throw new IllegalStateException(e);}}
                });
                assertEquals(401,pending.get().get(15,TimeUnit.SECONDS).statusCode(),mode);
            }
            assertEquals(retry?1:0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",d.memberId()),Integer.class),mode);
            assertEquals(participants,orderParticipantState(),mode+" retry="+retry);
        } }
    }
    @Test void jobWritesRecheckLeaseAfterRowLockWait() throws Exception {
        for(String operation:List.of("heartbeat","complete","fail")) {
            var clock=new JobClock();var jobs=jobs(clock);var type="wait-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant()));var job=jobs.claim(java.util.Set.of(type)).orElseThrow();
            try(var lock=context.getBean(DataSource.class).getConnection()) {
                lock.setAutoCommit(false);try(var statement=lock.prepareStatement("SELECT id FROM marketplace.job WHERE id=? FOR UPDATE")) { statement.setObject(1,id);statement.executeQuery().close(); }
                var pool=Executors.newSingleThreadExecutor();
                try {
                    var result=pool.submit(()->switch(operation) { case "heartbeat" -> jobs.heartbeat(id,job.ownerToken());case "complete" -> jobs.complete(id,job.ownerToken());default -> jobs.fail(id,job.ownerToken(),false,"WAITED"); });
                    awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%marketplace.job WHERE id=%'",Integer.class)>0,"owner write must actually wait for row lock");
                    clock.advance(30);lock.commit();assertFalse(result.get(10,TimeUnit.SECONDS));assertEquals("RUNNING",jobs.find(id).orElseThrow().status());assertEquals(job.ownerToken(),jobs.find(id).orElseThrow().ownerToken());
                } finally { lock.rollback();pool.shutdownNow(); }
            }
        }
    }
    @Test void workerHeartbeatProtectsLongHandler() throws Exception {
        var clock=new JobClock();var jobs=jobs(clock);String type="heartbeat-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant()));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var handler=new com.example.marketplace.platform.api.JobHandler() {
            public String type() { return type; }
            public Result handle(com.example.marketplace.platform.api.Jobs.Job job) throws Exception { entered.countDown();if(!release.await(25,TimeUnit.SECONDS)) { throw new IllegalStateException("test release absent"); }return Result.COMPLETE; }
        };
        try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler))) {
            worker.start();try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));clock.advance(15);
                awaitJobCondition(()->jobs.find(id).orElseThrow().leaseUntil().equals(clock.instant().plusSeconds(30)),"independent heartbeat must renew during handler");
                clock.advance(16);assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());release.countDown();awaitJobCondition(()->jobs.find(id).orElseThrow().status().equals("SUCCEEDED"),"long handler should complete with renewed lease");
            } finally { release.countDown(); }
        }
    }
    @Test void outboxConcurrentDedupeAndAtomicInsertFailure() throws Exception {
        var clock=new JobClock();var jobs=jobs(clock);var outbox=new com.example.marketplace.platform.infrastructure.JdbcOutbox(jdbc,transactionManager,jobs);var command=jobCommand("dedupe",clock.instant());var event=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),command);var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(0,1).stream().map(index->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return outbox.append(event);})).toList();assertEquals(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",command.dedupeKey()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event WHERE id=:id",Map.of("id",event.id()),Integer.class));
        var conflict=jobCommand("conflict",clock.instant());var changed=new com.example.marketplace.platform.api.Jobs.Command(conflict.type(),conflict.dedupeKey(),conflict.causeKey(),"different",conflict.nextRunAt());var simultaneous=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(conflict,changed).stream().map(c->pool.submit(()->{simultaneous.await(10,TimeUnit.SECONDS);try { return jobs.enqueue(c); }catch(IllegalArgumentException rejected) { assertEquals("JOB_KEY_CONFLICT",rejected.getMessage());return null; }})).toList();
            int winners=0;for(var future:futures) { if(future.get(10,TimeUnit.SECONDS)!=null) { winners++; } }assertEquals(1,winners);
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",conflict.dedupeKey()),Integer.class));
        var invisible=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),jobCommand("visibility",clock.instant()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            var id=outbox.append(invisible);
            try(var other=context.getBean(DataSource.class).getConnection();var query=other.prepareStatement("SELECT count(*) FROM marketplace.job WHERE id=?")) { query.setObject(1,id);try(var rs=query.executeQuery()) { rs.next();assertEquals(0,rs.getInt(1)); } }catch(java.sql.SQLException ex) { throw new IllegalStateException(ex); }
        });
        var broken=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),jobCommand("injected",clock.instant()));
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.reject_probe_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"+broken.id()+"'::uuid THEN RAISE EXCEPTION 'test only insertion failure'; END IF; RETURN NEW; END $$");
        jdbc.getJdbcTemplate().execute("CREATE TRIGGER probe_outbox_failure BEFORE INSERT ON marketplace.outbox_event FOR EACH ROW EXECUTE FUNCTION marketplace.reject_probe_outbox()");
        assertThrows(org.springframework.dao.DataAccessException.class,()->outbox.append(broken));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",broken.command().dedupeKey()),Integer.class));
        UUID id=jobs.enqueue(jobCommand("constraints",clock.instant()));
        for(String update:List.of("attempts=6","status='RUNNING'","owner_token='"+UUID.randomUUID()+"'","status='UNKNOWN'")) { assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.job SET "+update+" WHERE id=:id",Map.of("id",id))); }
    }
    @Test void workerFailureResultsAreDurableAndSanitized() {
        var clock=new JobClock();var jobs=jobs(clock);
        for(String mode:List.of("RETRY","TERMINAL","EXCEPTION")) {
            String type="result-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant()));var handler=new com.example.marketplace.platform.api.JobHandler() {
                public String type() { return type; }
                public Result handle(com.example.marketplace.platform.api.Jobs.Job job) { if(mode.equals("EXCEPTION")) { throw new IllegalArgumentException("private provider credentials"); }return Result.valueOf(mode); }
            };
            try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler))) { assertTrue(worker.runOnce());assertEquals(mode.equals("TERMINAL")?"FAILED":"READY",jobs.find(id).orElseThrow().status());assertEquals("HANDLER_"+mode,jobs.find(id).orElseThrow().lastError());assertFalse(worker.runOnce());assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->worker.runOnce())); }
        }
    }
    private static Process workerProcess(java.nio.file.Path folder,String phase,Instant now,String type,UUID id,boolean block) throws Exception {
        var args=folder.resolve(phase+".args");var marker=folder.resolve(phase+"-effect.txt");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace('\\','/');
        java.nio.file.Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.WorkerProcessProbe\n"+now+"\n"+type+"\n\""+marker.toString().replace('\\','/')+"\"\n"+block+"\n"+id+"\n");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(folder.resolve(phase+".log").toFile()).start();
        java.nio.file.Files.writeString(folder.resolve(phase+"-pid.txt"),Long.toString(process.pid()));return process;
    }
    private static void awaitJobCondition(java.util.function.BooleanSupplier condition,String message) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(System.nanoTime()<end&&!condition.getAsBoolean()) { Thread.sleep(25); }assertTrue(condition.getAsBoolean(),message);
    }
    @Test void workerRestartKeepsDurableEffects() throws Exception {
        var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("worker-"+UUID.randomUUID()));
        jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.worker_probe_effect(cause_key TEXT PRIMARY KEY,effects INTEGER NOT NULL)");
        var clock=new JobClock();var jobs=jobs(clock);var type="process-"+UUID.randomUUID();var command=jobCommand(type,clock.instant());var id=jobs.enqueue(command);
        Process first=workerProcess(folder,"first",clock.instant(),type,id,true);Process second=null;
        try {
            awaitJobCondition(()->java.nio.file.Files.exists(folder.resolve("first-effect.txt"))||!first.isAlive(),"owned worker never reached effect barrier");
            assertTrue(java.nio.file.Files.exists(folder.resolve("first-effect.txt")),"child must commit effect before kill; inspect preserved first.log");
            assertEquals(1,jdbc.queryForObject("SELECT effects FROM marketplace.worker_probe_effect WHERE cause_key=:key",Map.of("key",command.causeKey()),Integer.class));
            assertEquals("RUNNING",jobs.find(id).orElseThrow().status());assertEquals(1,jobs.find(id).orElseThrow().attempts());
            first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));assertFalse(first.isAlive());clock.advance(31);
            second=workerProcess(folder,"second",clock.instant(),type,id,false);assertThat(second.pid()).isNotEqualTo(first.pid());assertTrue(second.waitFor(40,TimeUnit.SECONDS));assertEquals(0,second.exitValue(),"inspect preserved second.log");
            assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());assertEquals(2,jobs.find(id).orElseThrow().attempts());assertEquals(1,jdbc.queryForObject("SELECT effects FROM marketplace.worker_probe_effect WHERE cause_key=:key",Map.of("key",command.causeKey()),Integer.class));assertTrue(java.nio.file.Files.readString(folder.resolve("completed.txt")).contains("nonweb=true"));
        } finally { if(first.isAlive()) { first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS); }if(second!=null&&second.isAlive()) { second.destroyForcibly();second.waitFor(10,TimeUnit.SECONDS); } }
    }
    @Test void workerDispatchesOutsideTransactionsAndRejectsDuplicateTypes() {
        var clock=new JobClock();var jobs=jobs(clock);var type="dispatch-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant()));var calls=new java.util.concurrent.atomic.AtomicInteger();
        var handler=new com.example.marketplace.platform.api.JobHandler() {
            public String type() { return type; }
            public Result handle(com.example.marketplace.platform.api.Jobs.Job job) {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals("RUNNING",jobs.find(id).orElseThrow().status());assertEquals(id,job.id());assertEquals(1,calls.incrementAndGet());return Result.COMPLETE;
            }
        };
        try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler))) { assertTrue(worker.runOnce());assertFalse(worker.runOnce()); }
        assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());assertEquals(1,calls.get());
        assertThrows(IllegalArgumentException.class,()->new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler,handler)));
        assertTrue(context.getBeansOfType(com.example.marketplace.platform.infrastructure.WorkerEngine.class).isEmpty());assertEquals(java.util.Set.of("ORDER_EXPIRY","PAYMENT_QUERY","PAYMENT_COMPENSATE","PAYMENT_SETTLED","FULFILLMENT_PREPARE","FULFILLMENT_BACKFILL","SHIPMENT_CREATED","SHIPMENT_PROGRESS"),context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values().stream().map(com.example.marketplace.platform.api.JobHandler::type).collect(java.util.stream.Collectors.toSet()));
    }
    static final class JobClock extends Clock {
        private volatile Instant now=Instant.parse("2030-01-01T00:00:00Z");
        void advance(long seconds) { now=now.plusSeconds(seconds); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private com.example.marketplace.platform.infrastructure.JdbcJobs jobs(Clock clock) { return new com.example.marketplace.platform.infrastructure.JdbcJobs(jdbc,transactionManager,clock); }
    private com.example.marketplace.platform.api.Jobs.Command jobCommand(String type,Instant due) { return new com.example.marketplace.platform.api.Jobs.Command(type,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"{}",due); }
    @Test void outboxJoinsBusinessTransaction() {
        var clock=new JobClock();var jobs=jobs(clock);var outbox=new com.example.marketplace.platform.infrastructure.JdbcOutbox(jdbc,transactionManager,jobs);
        var event=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),jobCommand("transaction",clock.instant()));
        UUID id=outbox.append(event);assertThat(id).isNotNull();assertEquals(id,outbox.append(event));assertEquals(id,jobs.enqueue(event.command()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE id=:id",Map.of("id",id),Integer.class));
        var other=new com.example.marketplace.platform.api.Jobs.Command("changed",event.command().dedupeKey(),event.command().causeKey(),"{}",clock.instant());
        assertThrows(IllegalArgumentException.class,()->jobs.enqueue(other));
        assertThrows(IllegalArgumentException.class,()->outbox.append(new com.example.marketplace.platform.api.Outbox.Event(event.id(),jobCommand("different",clock.instant()))));
        var rollback=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),jobCommand("rollback",clock.instant()));var draft=orderDraft("outbox-rollback");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{orders().save(draft);outbox.append(rollback);throw new IllegalStateException("rollback");}));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event WHERE id=:id",Map.of("id",rollback.id()),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",rollback.command().dedupeKey()),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",draft.purchaseSessionId()),Integer.class));
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{orders().save(draft);outbox.append(rollback);});
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event WHERE id=:id",Map.of("id",rollback.id()),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",rollback.command().dedupeKey()),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",draft.purchaseSessionId()),Integer.class));
    }
    @Test void jobLeaseFencesStaleWorkers() throws Exception {
        var clock=new JobClock();String type="lease-"+UUID.randomUUID();var first=jobs(clock);var second=jobs(clock);UUID id=first.enqueue(jobCommand(type,clock.instant()));assertThat(id).isNotNull();var barrier=new java.util.concurrent.CyclicBarrier(2);
        com.example.marketplace.platform.api.Jobs.Job acquired;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(first,second).stream().map(api->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return api.claim(java.util.Set.of(type));})).toList();
            var results=List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(java.util.Optional::isPresent).count());acquired=results.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
        }
        assertEquals(1,acquired.attempts());clock.advance(29);assertTrue(second.claim(java.util.Set.of(type)).isEmpty());assertTrue(first.heartbeat(id,acquired.ownerToken()));assertEquals(clock.instant().plusSeconds(30),first.find(id).orElseThrow().leaseUntil());
        clock.advance(29);assertTrue(second.claim(java.util.Set.of(type)).isEmpty());clock.advance(1);assertFalse(first.heartbeat(id,acquired.ownerToken()));assertFalse(first.complete(id,acquired.ownerToken()));assertFalse(first.fail(id,acquired.ownerToken(),true,"STALE"));
        var replacement=second.claim(java.util.Set.of(type)).orElseThrow();assertThat(replacement.ownerToken()).isNotEqualTo(acquired.ownerToken());assertEquals(2,replacement.attempts());
        assertFalse(first.complete(id,acquired.ownerToken()));assertFalse(first.heartbeat(id,acquired.ownerToken()));assertFalse(first.fail(id,acquired.ownerToken(),true,"STALE"));assertTrue(second.complete(id,replacement.ownerToken()));assertEquals("SUCCEEDED",second.find(id).orElseThrow().status());assertFalse(second.complete(id,replacement.ownerToken()));
    }
    @Test void jobRetryAndDueBoundaries() {
        var clock=new JobClock();var jobs=jobs(clock);String type="retry-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant().plusSeconds(1)));assertThat(id).isNotNull();assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());clock.advance(1);assertTrue(jobs.claim(java.util.Set.of("unsupported")).isEmpty());
        long[] delays={5,15,60,300};for(int attempt=1;attempt<=5;attempt++) {
            var job=jobs.claim(java.util.Set.of(type)).orElseThrow();assertEquals(attempt,job.attempts());assertTrue(jobs.fail(id,job.ownerToken(),false,"TRANSIENT"));
            if(attempt<5) { assertEquals(clock.instant().plusSeconds(delays[attempt-1]),jobs.find(id).orElseThrow().nextRunAt());clock.advance(delays[attempt-1]-1);assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());clock.advance(1); }
        }
        assertEquals("FAILED",jobs.find(id).orElseThrow().status());assertTrue(jobs.failed(100).stream().anyMatch(j->j.id().equals(id)&&j.attempts()==5&&"TRANSIENT".equals(j.lastError())));clock.advance(10000);assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());
        var permanent=jobs.enqueue(jobCommand(type,clock.instant()));var p=jobs.claim(java.util.Set.of(type)).orElseThrow();assertTrue(jobs.fail(permanent,p.ownerToken(),true,"INVALID"));assertEquals(1,jobs.find(permanent).orElseThrow().attempts());
        var crashes=jobs.enqueue(jobCommand(type,clock.instant()));for(int attempt=1;attempt<=5;attempt++) { assertEquals(attempt,jobs.claim(java.util.Set.of(type)).orElseThrow().attempts());clock.advance(30); }
        assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());assertEquals("FAILED",jobs.find(crashes).orElseThrow().status());assertEquals("LEASE_EXPIRED",jobs.find(crashes).orElseThrow().lastError());
    }

    @Test void couponBatchConcurrentIdenticalInsertsRespectAllUniqueKeys() throws Exception {
        for(int round=0;round<8;round++) {
            var request=reservationRequest("all-unique-"+round);var barrier=new java.util.concurrent.CyclicBarrier(2);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var futures=List.of(0,1).stream().map(index->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return couponReservations().reserve(List.of(request));})).toList();
                var first=futures.get(0).get(15,TimeUnit.SECONDS);assertEquals(first,futures.get(1).get(15,TimeUnit.SECONDS));assertEquals("RESERVED",first.getFirst().state());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_batch WHERE checkout_id=:id",Map.of("id",request.checkoutId()),Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_event WHERE reservation_id=:id",Map.of("id",request.reservationId()),Integer.class));
            }
        }
    }
    private com.example.marketplace.order.api.Orders orders() { return context.getBean(com.example.marketplace.order.api.Orders.class); }
    private com.example.marketplace.order.api.Orders.Draft orderDraft(String suffix) {
        var r=reservationRequest("order-"+suffix);var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),"key","b".repeat(64),r.memberId(),null,null,"DEMO",r.quoteSnapshot(),new com.example.marketplace.order.api.Orders.Contact("구매자","01012345678","수령인","01087654321","01234","서울시 테스트로","101호"),now,now.plusSeconds(600));
    }
    @Test void orderSnapshotsSurviveSourceChanges() {
        var original=orderDraft("snapshot");UUID productId=original.quote().stores().getFirst().lines().getFirst().productId();
        var promo=promotions().save(original.memberId(),null,promotionInput(productId,"DRAFT",0));promotions().save(original.memberId(),promo.id(),promotionInput(productId,"ACTIVE",0));
        var quote=quotes().calculate(original.memberId(),List.of(quoteLine(original.quote().stores().getFirst().lines().getFirst().skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(original.quote().stores().getFirst().storeId(),original.quote().stores().getFirst().coupon().id())));
        var draft=new com.example.marketplace.order.api.Orders.Draft(original.purchaseSessionId(),original.requestKey(),original.requestHash(),original.memberId(),null,null,original.mode(),quote,original.contact(),original.createdAt(),original.expiresAt());
        var saved=orders().save(draft);assertThat(saved).isNotNull();
        assertEquals(draft.quote(),saved.quote());assertEquals(draft.contact(),saved.contact());
        var line=draft.quote().stores().getFirst().lines().getFirst();var coupon=draft.quote().stores().getFirst().coupon();
        assertThat(line.promotion()).isNotNull();promotions().save(draft.memberId(),promo.id(),promotionInput(productId,"STOPPED",1));
        jdbc.update("UPDATE marketplace.catalog_product SET title='나중에 수정',is_hidden_by_admin=true WHERE id=:id",Map.of("id",line.productId()));
        jdbc.update("UPDATE marketplace.catalog_sku SET price=123 WHERE id=:id",Map.of("id",line.skuId()));
        jdbc.update("UPDATE marketplace.issued_coupon SET title='수정된 쿠폰' WHERE id=:id",Map.of("id",coupon.id()));
        assertEquals(saved,orders().find(saved.orderNumber()));
        assertEquals(saved.quote().total(),jdbc.queryForObject("SELECT sum(total) FROM marketplace.seller_order WHERE order_id=:id",Map.of("id",saved.id()),Long.class));
        assertEquals(line.net(),jdbc.queryForObject("SELECT sum(u.net) FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.order_id=:id",Map.of("id",saved.id()),Long.class));
        var failed=orderDraft("rollback");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(s->{orders().save(failed);throw new IllegalStateException("caller");}));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",failed.purchaseSessionId()),Integer.class));
    }
    @Test void checkoutAttemptsAndOrderNumbersAreDistinct() {
        var d=orderDraft("attempt");var first=orders().save(d);assertThat(first).isNotNull();assertEquals(first,orders().save(d));
        var other=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),"second",d.requestHash(),d.memberId(),null,null,"SANDBOX",d.quote(),d.contact(),d.createdAt(),d.expiresAt());
        var second=orders().save(other);assertThat(second.checkoutId()).isNotEqualTo(first.checkoutId());assertThat(second.orderNumber()).isNotEqualTo(first.orderNumber());
        assertEquals("DEMO",orders().find(first.orderNumber()).mode());assertEquals("SANDBOX",orders().find(second.orderNumber()).mode());
        var changed=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),"c".repeat(64),d.memberId(),null,null,d.mode(),d.quote(),d.contact(),d.createdAt(),d.expiresAt());
        assertThrows(RuntimeException.class,()->orders().save(changed));
        for(String mode:List.of("LIVE","")) { assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET mode=:mode WHERE id=:id",Map.of("mode",mode,"id",first.id()))); }
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET mode=NULL WHERE id=:id",Map.of("id",first.id())));
        assertEquals(first,orders().find(first.orderNumber()));
    }
    @Test void orderNumberCollisionsRetryWithoutOrphans() {
        var d=orderDraft("collision");var first=orders().save(d);UUID collision=UUID.fromString(first.orderNumber().substring(1).replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})","$1-$2-$3-$4-$5"));
        var sequence=new java.util.concurrent.atomic.AtomicInteger();var api=new com.example.marketplace.order.infrastructure.JdbcOrders(jdbc,authMapper,transactionManager,()->sequence.getAndIncrement()==0?collision:UUID.randomUUID());
        var another=orderDraft("collision-second");var second=api.save(another);assertEquals(2,sequence.get());assertThat(second.orderNumber()).isNotEqualTo(first.orderNumber());assertEquals(second,api.find(second.orderNumber()));
        var stuck=new com.example.marketplace.order.infrastructure.JdbcOrders(jdbc,authMapper,transactionManager,()->collision);var rejected=orderDraft("collision-exhausted");
        assertThrows(IllegalStateException.class,()->stuck.save(rejected));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",rejected.purchaseSessionId()),Integer.class));
    }
    @Test void orderDeadlineAndMalformedSnapshotsLeaveNoRows() {
        var d=orderDraft("invalid");
        for(Instant expires:List.of(d.createdAt(),d.createdAt().minusSeconds(1),d.createdAt().plusSeconds(901),d.createdAt().plusNanos(1))) {
            var bad=new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),d.quote(),d.contact(),d.createdAt(),expires);
            assertThrows(IllegalArgumentException.class,()->orders().save(bad));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",bad.purchaseSessionId()),Integer.class));
        }
        var q=d.quote();var altered=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt(),q.stores(),q.total()+1,q.fingerprint());
        var bad=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),altered,d.contact(),d.createdAt(),d.expiresAt());
        assertThrows(IllegalArgumentException.class,()->orders().save(bad));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",bad.purchaseSessionId()),Integer.class));
        var atLimit=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),q,d.contact(),d.createdAt(),d.createdAt().plusSeconds(900));
        assertEquals(atLimit.expiresAt(),orders().save(atLimit).expiresAt());
        var couponEnd=q.stores().getFirst().coupon().terms().endsAt();var created=couponEnd.minusSeconds(600);
        var boundary=new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),q,d.contact(),created,couponEnd);
        assertEquals(couponEnd,orders().save(boundary).expiresAt());
        var over=new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),q,d.contact(),created,couponEnd.plusNanos(1000));
        assertThrows(IllegalArgumentException.class,()->orders().save(over));
    }
    @Test void orderMultiStoreUnitsAndDatabaseBoundaries() {
        var d=orderDraft("multi");var a=d.quote().stores().getFirst().lines().getFirst();var other=browseFixture("order-other",101,20);
        var extra=browseFixture("order-extra",203,20);UUID extraProduct=((com.example.marketplace.catalog.api.Products.Product)extra.get("product")).id();
        jdbc.update("UPDATE marketplace.catalog_product SET store_id=:store WHERE id=:id",Map.of("store",d.quote().stores().getFirst().storeId(),"id",extraProduct));
        var productPromo=promotions().save(d.memberId(),null,promotionInput(a.productId(),"DRAFT",0));promotions().save(d.memberId(),productPromo.id(),promotionInput(a.productId(),"ACTIVE",0));
        var shippingInput=new com.example.marketplace.promotion.api.Promotions.Input("배송 700원 할인","SHIPPING",List.of(),d.createdAt().minusSeconds(60),d.createdAt().plusSeconds(3600),"FIXED",700L,null,"DRAFT",0L);
        var shippingPromo=promotions().save(d.memberId(),null,shippingInput);promotions().save(d.memberId(),shippingPromo.id(),new com.example.marketplace.promotion.api.Promotions.Input(shippingInput.title(),shippingInput.scope(),shippingInput.productIds(),shippingInput.startsAt(),shippingInput.endsAt(),shippingInput.kind(),shippingInput.value(),shippingInput.cap(),"ACTIVE",0L));
        var q=quotes().calculate(d.memberId(),List.of(quoteLine(a.skuId(),3),quoteLine((UUID)other.get("sku"),3),quoteLine((UUID)extra.get("sku"),2)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(d.quote().stores().getFirst().storeId(),d.quote().stores().getFirst().coupon().id())));
        var draft=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),q,d.contact(),d.createdAt(),d.expiresAt());
        var saved=orders().save(draft);assertEquals(q,saved.quote());assertEquals(2,saved.sellers().size());
        assertEquals(q.total(),jdbc.queryForObject("SELECT amount FROM marketplace.purchase_order WHERE id=:id",Map.of("id",saved.id()),Long.class));
        var discounted=q.stores().stream().filter(s->s.coupon()!=null).findFirst().orElseThrow();assertTrue(discounted.productDiscount()>0);assertTrue(discounted.couponDiscount()>0);assertEquals(3000,discounted.shippingBase());assertEquals(700,discounted.shippingDiscount());
        assertEquals(8,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.order_id=:id",Map.of("id",saved.id()),Integer.class));
        for(var store:q.stores()) {
            UUID seller=saved.sellers().stream().filter(s->s.storeId().equals(store.storeId())).findFirst().orElseThrow().id();
            assertEquals(store.total(),jdbc.queryForObject("SELECT total FROM marketplace.seller_order WHERE id=:id",Map.of("id",seller),Long.class));
            assertEquals(List.of(store.subtotal(),store.productDiscount(),store.couponDiscount(),store.shippingBase(),store.shippingDiscount(),store.total()),jdbc.queryForObject("SELECT subtotal,product_discount,coupon_discount,shipping_base,shipping_discount,total FROM marketplace.seller_order WHERE id=:id",Map.of("id",seller),(rs,n)->List.of(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6))));
            for(var line:store.lines()) {
                assertEquals(List.of(line.unitPrice(),line.gross(),line.promotionDiscount(),line.couponDiscount(),line.net()),jdbc.queryForObject("SELECT unit_price,gross,promotion_discount,coupon_discount,net FROM marketplace.order_item WHERE seller_order_id=:seller AND sku_id=:sku",Map.of("seller",seller,"sku",line.skuId()),(rs,n)->List.of(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5))));
                assertEquals(line,authMapper.readValue(jdbc.queryForObject("SELECT line_snapshot::text FROM marketplace.order_item WHERE seller_order_id=:seller AND sku_id=:sku",Map.of("seller",seller,"sku",line.skuId()),String.class),com.example.marketplace.promotion.api.Quotes.Line.class));
                var actual=jdbc.query("SELECT u.ordinal,u.gross,u.promotion_discount,u.coupon_discount,u.net FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.seller_order_id=:seller AND i.sku_id=:sku ORDER BY u.ordinal",Map.of("seller",seller,"sku",line.skuId()),(rs,n)->new com.example.marketplace.promotion.api.Quotes.Unit(rs.getInt(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)));
                assertEquals(line.units(),actual);
            }
        }
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET member_id=NULL WHERE id=:id",Map.of("id",saved.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET amount=-1 WHERE id=:id",Map.of("id",saved.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET state='SHIPPED' WHERE id=:id",Map.of("id",saved.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.seller_order SET store_id=:store WHERE order_id=:id",Map.of("id",saved.id(),"store",q.stores().getFirst().storeId())));
        assertEquals(saved,orders().find(saved.orderNumber()));
    }
    @Test void orderConcurrentKeysHaveOneDurableResult() throws Exception {
        for(boolean conflict:List.of(false,true)) {
            var d=orderDraft("concurrent-"+conflict);var other=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),conflict?"d".repeat(64):d.requestHash(),d.memberId(),null,null,d.mode(),d.quote(),d.contact(),d.createdAt(),d.expiresAt());
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var futures=List.of(d,other).stream().map(draft->pool.submit(()->{
                    try { return new TransactionTemplate(transactionManager).execute(status->{try { barrier.await(10,TimeUnit.SECONDS); } catch(Exception ex) { throw new IllegalStateException(ex); } return orders().save(draft);}); }
                    catch(IllegalArgumentException ex) { assertEquals("ORDER_REQUEST_CONFLICT",ex.getMessage());return null; }
                })).toList();
                var results=new java.util.ArrayList<com.example.marketplace.order.api.Orders.Snapshot>();for(var future:futures) { var result=future.get(15,TimeUnit.SECONDS);if(result!=null) { results.add(result); } }
                assertEquals(conflict?1:2,results.size());if(!conflict) { assertEquals(results.get(0),results.get(1)); }
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",d.purchaseSessionId()),Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE checkout_id=:id",Map.of("id",results.getFirst().checkoutId()),Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_contact WHERE order_id=:id",Map.of("id",results.getFirst().id()),Integer.class));
            }
        }
    }
    @Test void orderGuestAttemptBindingSurvivesCheckoutRestart() throws Exception {
        var d=orderDraft("guest");UUID checkout=UUID.randomUUID(),attempt=UUID.randomUUID(),buyer=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.guest_checkout(id,token_hash,browser_hash,purpose,expires_at,state,created_at) VALUES(:id,:token,:browser,'PURCHASE',clock_timestamp()+interval '30 minutes','VERIFIED',clock_timestamp())",Map.of("id",checkout,"token",UUID.randomUUID().toString(),"browser",UUID.randomUUID().toString()));
        jdbc.update("INSERT INTO marketplace.guest_attempt(id,checkout_id,buyer_name,phone,purpose,state,issued_at,expires_at) VALUES(:id,:checkout,'가상 구매자','01012345678','PURCHASE','VERIFIED',clock_timestamp(),clock_timestamp()+interval '3 minutes')",Map.of("id",attempt,"checkout",checkout));
        jdbc.update("UPDATE marketplace.guest_checkout SET current_attempt=:attempt WHERE id=:id",Map.of("id",checkout,"attempt",attempt));
        jdbc.update("INSERT INTO marketplace.guest_buyer(id,buyer_name,verified_phone,password_hash,created_at) VALUES(:id,'가상 구매자','01012345678','{argon2id}test-fixture',clock_timestamp())",Map.of("id",buyer));
        var q=quotes().calculate(null,List.of(quoteLine(d.quote().stores().getFirst().lines().getFirst().skuId(),2)),List.of());
        var guest=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),d.requestHash(),null,buyer,attempt,d.mode(),q,d.contact(),d.createdAt(),d.expiresAt());
        com.example.marketplace.order.api.Orders.Snapshot saved;
        var start=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(0,1).stream().map(index->pool.submit(()->new TransactionTemplate(transactionManager).execute(status->{try { start.await(10,TimeUnit.SECONDS); } catch(Exception ex) { throw new IllegalStateException(ex); } return orders().save(guest); }))).toList();
            saved=futures.get(0).get(15,TimeUnit.SECONDS);assertEquals(saved,futures.get(1).get(15,TimeUnit.SECONDS));
        }
        assertEquals(buyer,saved.guestBuyerId());assertNull(saved.memberId());
        jdbc.update("UPDATE marketplace.guest_checkout SET current_attempt=NULL,state='OPEN' WHERE id=:id",Map.of("id",checkout));
        assertEquals(saved,orders().save(guest));assertEquals(attempt,jdbc.queryForObject("SELECT guest_attempt_id FROM marketplace.order_checkout_attempt WHERE id=:id",Map.of("id",saved.checkoutId()),UUID.class));
        var reused=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),"another-key",d.requestHash(),null,buyer,attempt,d.mode(),q,d.contact(),d.createdAt(),d.expiresAt());
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->orders().save(reused)).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE guest_attempt_id=:id",Map.of("id",attempt),Integer.class));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET member_id=:member WHERE id=:id",Map.of("member",d.memberId(),"id",saved.id())));
        assertEquals(saved,orders().find(saved.orderNumber()));
    }
    @Test void orderOwnerAndAttemptConstraints() {
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT to_regclass('marketplace.purchase_order')::text",String.class)).isNotNull();
        UUID member=activeMember("p08-owner@example.com"),session=UUID.randomUUID();
        UUID attempt=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.order_checkout_attempt(id,purchase_session_id,request_key,request_hash,created_at,expires_at) VALUES(:id,:session,'first',:hash,clock_timestamp(),clock_timestamp()+interval '10 minutes')",Map.of("id",attempt,"session",session,"hash","a".repeat(64)));
        for(String mode:List.of("DEMO","SANDBOX")) {
            UUID id=UUID.randomUUID();
            assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.purchase_order(id,checkout_id,order_number,mode,state,amount,policy_version,quote_snapshot,created_at,expires_at) VALUES(:id,:checkout,:number,:mode,'PENDING_PAYMENT',0,'p08','{}',clock_timestamp(),clock_timestamp()+interval '10 minutes')",Map.of("id",id,"checkout",attempt,"number",UUID.randomUUID().toString(),"mode",mode)));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE id=:id",Map.of("id",id),Integer.class));
        }
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.order_checkout_attempt(id,purchase_session_id,request_key,request_hash,created_at,expires_at) VALUES(:id,:session,'first',:hash,clock_timestamp(),clock_timestamp()+interval '10 minutes')",Map.of("id",UUID.randomUUID(),"session",session,"hash","a".repeat(64))));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",session),Integer.class));
        assertThat(member).isNotNull();
    }
    @Test void couponBatchInsertPreservesCrossSellerLockOrder() throws Exception {couponBatchInsertLockOrder(true);}
    @Test void couponBatchInsertPreservesSameBuyerLockOrder() throws Exception {couponBatchInsertLockOrder(false);}
    private void couponBatchInsertLockOrder(boolean cross) throws Exception {
        String suffix=cross?"fk-cross":"fk-same";var a=reservationRequest(suffix+"-a");var b=reservationRequest(suffix+"-b");UUID left=cross?a.memberId():activeMember(suffix+"@example.com"),right=cross?b.memberId():left;var requests=new java.util.ArrayList<CouponReservations.Request>();
        for(var pair:List.of(Map.entry(left,b),Map.entry(right,a))){var source=pair.getValue();var c=coupons().claim(pair.getKey(),coupons().own(source.memberId(),source.issuedCouponId()).templateId());var q=quotes().calculate(pair.getKey(),List.of(quoteLine(source.quoteSnapshot().stores().getFirst().lines().getFirst().skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(c.storeId(),c.id())));requests.add(new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),pair.getKey(),c.storeId(),c.id(),q,source.expiresAt()));}
        var barrier=new java.util.concurrent.CyclicBarrier(2);var probe=new NamedParameterJdbcTemplate(jdbc.getJdbcTemplate()){@Override public int update(String sql,Map<String,?> params){int result=super.update(sql,params);if(sql.startsWith("INSERT INTO marketplace.coupon_reservation_batch(")){try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}}return result;}};var api=new com.example.marketplace.promotion.infrastructure.JdbcCouponReservations(probe,quotes(),coupons(),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.seller.api.Stores.class),authMapper,transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)){var futures=requests.stream().map(r->pool.submit(()->api.reserve(List.of(r)))).toList();for(var future:futures){assertEquals("RESERVED",future.get(10,TimeUnit.SECONDS).getFirst().state());}}
        for(var r:requests){assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_event WHERE reservation_id=:id",Map.of("id",r.reservationId()),Integer.class));assertEquals(1,coupons().own(r.memberId(),r.issuedCouponId()).version());}
    }
    @Test void couponBatchMigrationPreservesPriorDataAndRejectsConflicts(){
        var a=reservationRequest("upgrade-a");var b=reservationRequest("upgrade-b");couponReservations().reserve(List.of(a));couponReservations().reserve(List.of(b));var ds=context.getBean(DataSource.class);var before=reservationState();
        for(boolean conflict:List.of(false,true)){String schema=conflict?"p07_d_conflict":"p07_d_upgrade";Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("28").cleanDisabled(true).load().migrate();for(String table:List.of("identity_user","seller_store","coupon_template","issued_coupon","coupon_reservation","coupon_reservation_event")){jdbc.getJdbcTemplate().execute("INSERT INTO "+schema+"."+table+" SELECT * FROM marketplace."+table);}if(conflict){jdbc.update("UPDATE "+schema+".coupon_reservation SET checkout_id=:checkout WHERE id=:id",Map.of("checkout",a.checkoutId(),"id",b.reservationId()));}var upgrade=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();if(conflict){assertThrows(org.flywaydb.core.api.FlywayException.class,upgrade::migrate);assertNull(jdbc.getJdbcTemplate().queryForObject("SELECT to_regclass('"+schema+".coupon_reservation_batch')",String.class));}else{assertEquals(8,upgrade.migrate().migrationsExecuted);assertEquals(0,upgrade.migrate().migrationsExecuted);assertEquals(0,jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM "+schema+".coupon_reservation r JOIN "+schema+".coupon_reservation_batch b ON b.checkout_id=r.checkout_id WHERE b.member_id<>r.member_id OR b.request_hash<>r.request_hash",Integer.class));assertEquals(jdbc.getJdbcTemplate().queryForObject("SELECT count(DISTINCT checkout_id) FROM "+schema+".coupon_reservation",Integer.class),jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM "+schema+".coupon_reservation_batch",Integer.class));}assertEquals(before,reservationState());}
    }
    @Test void couponReservationRechecksDistinctBuyerSellerChanges() throws Exception {
        for(String mode:List.of("seller","hidden","store","role","price","promotion","coupon")){var original=reservationRequest("distinct-"+mode);UUID buyer=activeMember("reservation-distinct-"+mode+"@example.com");var c=coupons().claim(buyer,coupons().own(original.memberId(),original.issuedCouponId()).templateId());var line=original.quoteSnapshot().stores().getFirst().lines().getFirst();UUID promotion=null;
            if(mode.equals("promotion")){var p=promotions().save(original.memberId(),null,promotionInput(line.productId(),"DRAFT",0));promotion=promotions().save(original.memberId(),p.id(),promotionInput(line.productId(),"ACTIVE",0)).id();}
            var q=quotes().calculate(buyer,List.of(quoteLine(line.skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(c.storeId(),c.id())));var r=new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),buyer,c.storeId(),c.id(),q,original.expiresAt());UUID lock=java.util.stream.Stream.of(buyer,original.memberId()).min(java.util.Comparator.comparing(UUID::toString)).orElseThrow(),event=promotion;
            try(var pool=Executors.newSingleThreadExecutor()){var result=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();new TransactionTemplate(transactionManager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR NO KEY UPDATE",Map.of("id",lock),UUID.class);result.set(pool.submit(()->{try{couponReservations().reserve(List.of(r));return true;}catch(RuntimeException e){return false;}}));awaitCouponLock("identity_user");switch(mode){case "seller"->jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",original.memberId()));case "hidden"->jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE id=:id",Map.of("id",c.storeId()));case "store"->jdbc.update("UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE id=:id",Map.of("id",c.storeId()));case "role"->jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",original.memberId()));case "price"->jdbc.update("UPDATE marketplace.catalog_sku SET price=20000,version=version+1 WHERE id=:id",Map.of("id",line.skuId()));case "promotion"->jdbc.update("UPDATE marketplace.promotion SET state='STOPPED',version=version+1 WHERE id=:id",Map.of("id",event));case "coupon"->couponReservations().reserve(List.of(new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),buyer,c.storeId(),c.id(),q,r.expiresAt())));default->throw new IllegalStateException();}});assertFalse(result.get().get(10,TimeUnit.SECONDS));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE id=:id",Map.of("id",r.reservationId()),Integer.class));assertEquals(mode.equals("coupon")?"RESERVED":"AVAILABLE",coupons().own(buyer,c.id()).state());}
        }
    }
    @Test void couponTransitionsAndRestoreAuditFailuresRollback(){
        for(String action:List.of("COMMITTED","RELEASED","EXPIRED","RESTORED")){var batch=reservationBatch("audit-"+action.toLowerCase());var api=couponReservations();api.reserve(batch);var ids=batch.stream().map(CouponReservations.Request::reservationId).sorted(java.util.Comparator.comparing(UUID::toString)).toList();if(action.equals("RESTORED")){api.commit(batch.getFirst().checkoutId(),ids);}UUID failure=ids.getLast();var request=batch.stream().filter(r->r.reservationId().equals(failure)).findFirst().orElseThrow();var full=new CouponReservations.RestoreRequest(UUID.randomUUID(),request.sellerOrderRef(),failure,new CouponReservations.RefundCompletion(true,true));var before=reservationState();jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_reservation_event ADD CONSTRAINT p07_d_transition_fail CHECK(reservation_id<>'"+failure+"' OR action<>'"+action+"')");
            try{assertThrows(RuntimeException.class,()->{switch(action){case "COMMITTED"->api.commit(request.checkoutId(),ids);case "RELEASED"->api.release(request.checkoutId(),ids);case "EXPIRED"->reservationClock(Clock.fixed(request.expiresAt(),ZoneOffset.UTC)).expire(request.checkoutId(),ids);case "RESTORED"->api.restore(full);default->throw new IllegalStateException();}});assertEquals(before,reservationState());}finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_reservation_event DROP CONSTRAINT p07_d_transition_fail");}
        }
    }
    @Test void couponDifferentRestoreCausesCannotAffectNextUse() throws Exception {
        var r=reservationRequest("different-causes");var api=couponReservations();api.reserve(List.of(r));api.commit(r.checkoutId(),List.of(r.reservationId()));long version=coupons().own(r.memberId(),r.issuedCouponId()).version();var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var futures=java.util.stream.IntStream.range(0,2).mapToObj(n->pool.submit(()->{go.await();return api.restore(new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true)));})).toList();go.countDown();assertTrue(futures.get(0).get(10,TimeUnit.SECONDS).restored()^futures.get(1).get(10,TimeUnit.SECONDS).restored());}assertEquals(version+1,coupons().own(r.memberId(),r.issuedCouponId()).version());
        var line=r.quoteSnapshot().stores().getFirst().lines().getFirst();var q=quotes().calculate(r.memberId(),List.of(quoteLine(line.skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(r.storeId(),r.issuedCouponId())));var next=new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),r.memberId(),r.storeId(),r.issuedCouponId(),q,r.expiresAt());api.reserve(List.of(next));var before=coupons().own(r.memberId(),r.issuedCouponId());var late=api.restore(new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true)));assertFalse(late.restored());assertEquals("ALREADY_RESTORED",late.reason());assertEquals(before,coupons().own(r.memberId(),r.issuedCouponId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE reservation_id=:id AND restored",Map.of("id",r.reservationId()),Integer.class));
    }
    @Test void couponReservationDeadlineBoundariesAndRetry(){
        var r=reservationRequest("deadline-boundaries");Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var clock=new GuestClock();clock.now=now;var api=reservationClock(clock);var before=reservationState();for(Instant end:List.of(now.minusSeconds(1),now,now.plusSeconds(900).plusNanos(1000))){assertThrows(RuntimeException.class,()->api.reserve(List.of(new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),end))));assertEquals(before,reservationState());}
        Instant couponEnd=coupons().own(r.memberId(),r.issuedCouponId()).terms().endsAt();clock.now=couponEnd.minusSeconds(100);assertThrows(RuntimeException.class,()->api.reserve(List.of(new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),couponEnd.plusSeconds(1)))));clock.now=now;var exact=new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),now.plusSeconds(900).plusNanos(789));var saved=api.reserve(List.of(exact));assertEquals(now.plusSeconds(900),saved.getFirst().expiresAt());var normalized=new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),now.plusSeconds(900));assertEquals(saved,api.reserve(List.of(normalized)));clock.now=now.plusSeconds(1000);jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",r.memberId()));assertEquals(saved,api.reserve(List.of(exact)));
    }
    private List<CouponReservations.Request> reservationBatch(String suffix){
        var a=reservationRequest(suffix+"-a");var b=reservationRequest(suffix+"-b");var second=coupons().claim(a.memberId(),coupons().own(b.memberId(),b.issuedCouponId()).templateId());var lines=java.util.stream.Stream.of(a,b).flatMap(r->r.quoteSnapshot().stores().stream()).flatMap(s->s.lines().stream()).map(l->quoteLine(l.skuId(),l.quantity())).toList();var q=quotes().calculate(a.memberId(),lines,List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(a.storeId(),a.issuedCouponId()),new com.example.marketplace.promotion.api.Quotes.CouponSelection(b.storeId(),second.id())));
        return List.of(new CouponReservations.Request(a.reservationId(),a.checkoutId(),a.sellerOrderRef(),a.memberId(),a.storeId(),a.issuedCouponId(),q,a.expiresAt()),new CouponReservations.Request(b.reservationId(),a.checkoutId(),b.sellerOrderRef(),a.memberId(),b.storeId(),second.id(),q,a.expiresAt()));
    }
    private List<String> reservationState(){var rows=new java.util.ArrayList<String>();for(String table:List.of("issued_coupon","coupon_reservation_batch","coupon_reservation","coupon_reservation_event","coupon_restoration")){rows.addAll(jdbc.getJdbcTemplate().queryForList("SELECT row_to_json(t)::text FROM marketplace."+table+" t ORDER BY row_to_json(t)::text",String.class));}return rows;}
    @Test void couponCheckoutCannotAcceptAnotherBatch() throws Exception {
        var a=reservationRequest("checkout-a");var b=reservationRequest("checkout-b");var api=couponReservations();api.reserve(List.of(a));var appended=new CouponReservations.Request(b.reservationId(),a.checkoutId(),b.sellerOrderRef(),b.memberId(),b.storeId(),b.issuedCouponId(),b.quoteSnapshot(),b.expiresAt());var before=reservationState();assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->api.reserve(List.of(appended)));assertEquals(before,reservationState());
        var extra=coupons().claim(a.memberId(),coupons().own(b.memberId(),b.issuedCouponId()).templateId());var sku=b.quoteSnapshot().stores().getFirst().lines().getFirst().skuId();var q=quotes().calculate(a.memberId(),List.of(quoteLine(sku,1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(b.storeId(),extra.id())));var sameMember=new CouponReservations.Request(UUID.randomUUID(),a.checkoutId(),UUID.randomUUID(),a.memberId(),b.storeId(),extra.id(),q,a.expiresAt());var sameBefore=reservationState();assertThrows(RuntimeException.class,()->api.reserve(List.of(sameMember)));assertEquals(sameBefore,reservationState());assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.coupon_reservation SET member_id=:member WHERE id=:id",Map.of("member",b.memberId(),"id",a.reservationId())));assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.coupon_reservation SET request_hash=:hash WHERE id=:id",Map.of("hash","f".repeat(64),"id",a.reservationId())));assertEquals(sameBefore,reservationState());
        var c=reservationRequest("checkout-c");var d=reservationRequest("checkout-d");var sameCheckout=new CouponReservations.Request(d.reservationId(),c.checkoutId(),d.sellerOrderRef(),d.memberId(),d.storeId(),d.issuedCouponId(),d.quoteSnapshot(),d.expiresAt());var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var futures=List.of(c,sameCheckout).stream().map(r->pool.submit(()->{go.await();try{api.reserve(List.of(r));return true;}catch(com.example.marketplace.promotion.api.PromotionRejected e){return false;}})).toList();go.countDown();assertTrue(futures.get(0).get(10,TimeUnit.SECONDS)^futures.get(1).get(10,TimeUnit.SECONDS));}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE checkout_id=:id",Map.of("id",c.checkoutId()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.issued_coupon WHERE id IN (:a,:b) AND state='AVAILABLE'",Map.of("a",c.issuedCouponId(),"b",d.issuedCouponId()),Integer.class));
    }
    @Test void couponBatchRetryHasOneReadSnapshot() throws Exception {
        var batch=reservationBatch("retry-snapshot");var api=couponReservations();api.reserve(batch);var read=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        var probe=new NamedParameterJdbcTemplate(jdbc.getJdbcTemplate()){@Override public <T> List<T> query(String sql,Map<String,?> params,org.springframework.jdbc.core.RowMapper<T> mapper){var result=super.query(sql,params,mapper);if(sql.startsWith("SELECT * FROM marketplace.coupon_reservation WHERE")&&once.compareAndSet(false,true)){read.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return result;}};
        var snapshot=new com.example.marketplace.promotion.infrastructure.JdbcCouponReservations(probe,quotes(),coupons(),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.seller.api.Stores.class),authMapper,transactionManager);
        try(var pool=Executors.newSingleThreadExecutor()){var future=pool.submit(()->snapshot.reserve(batch));try{assertTrue(read.await(10,TimeUnit.SECONDS));api.commit(batch.getFirst().checkoutId(),batch.stream().map(CouponReservations.Request::reservationId).toList());}finally{release.countDown();}var result=future.get(10,TimeUnit.SECONDS);assertEquals(List.of("RESERVED","RESERVED"),result.stream().map(CouponReservations.Reservation::state).toList());assertTrue(api.reserve(batch).stream().allMatch(r->r.state().equals("COMMITTED")));}
    }
    @Test void couponFullRestoreRejectsUnusedStates(){
        for(String state:List.of("RESERVED","RELEASED","EXPIRED")){var r=reservationRequest("unused-"+state.toLowerCase());var api=couponReservations();api.reserve(List.of(r));if(state.equals("RELEASED")){api.release(r.checkoutId(),List.of(r.reservationId()));}if(state.equals("EXPIRED")){reservationClock(Clock.fixed(r.expiresAt(),ZoneOffset.UTC)).expire(r.checkoutId(),List.of(r.reservationId()));}var before=reservationState();var full=new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true));assertEquals(409,assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->api.restore(full)).status());assertEquals(before,reservationState());if(state.equals("RESERVED")){api.commit(r.checkoutId(),List.of(r.reservationId()));assertTrue(api.restore(full).restored());}}
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
    @Test void couponFixtureIsDisabledOutsideTests() throws Exception {
        try(var isolated=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){isolated.register(CouponFixture.class);isolated.refresh();assertTrue(isolated.getBeansOfType(CouponFixture.class).isEmpty());}
        var browser=authBrowser();assertEquals(401,accountRequest(browser,"POST","/api/__test/coupon-reservations",Map.of("action","reserve","couponId",UUID.randomUUID(),"skuId",UUID.randomUUID()),csrfToken(browser)).statusCode());assertEquals(403,accountRequest(browser,"POST","/api/__test/coupon-reservations",Map.of("action","reserve"),null).statusCode());
        assertTrue(CouponFixture.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("test-classes"));
    }
    @Test void couponReservationCrossSellerAndConcurrentRetries() throws Exception {
        var a=reservationRequest("cross-a");var b=reservationRequest("cross-b");var forA=coupons().claim(a.memberId(),coupons().own(b.memberId(),b.issuedCouponId()).templateId());var forB=coupons().claim(b.memberId(),coupons().own(a.memberId(),a.issuedCouponId()).templateId());var requests=new java.util.ArrayList<CouponReservations.Request>();
        for(var pair:List.of(Map.entry(a,forA),Map.entry(b,forB))){var owner=pair.getKey();var c=pair.getValue();var sku=(c.storeId().equals(a.storeId())?a:b).quoteSnapshot().stores().getFirst().lines().getFirst().skuId();var q=quotes().calculate(owner.memberId(),List.of(quoteLine(sku,1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(c.storeId(),c.id())));requests.add(new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),owner.memberId(),c.storeId(),c.id(),q,owner.expiresAt()));}
        var api=couponReservations();var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var futures=requests.stream().map(r->pool.submit(()->{go.await();return api.reserve(List.of(r));})).toList();go.countDown();for(var future:futures){assertEquals("RESERVED",future.get(10,TimeUnit.SECONDS).getFirst().state());}}
        var r=reservationRequest("same-retry");var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(()->{start.await();return api.reserve(List.of(r));});var y=pool.submit(()->{start.await();return api.reserve(List.of(r));});start.countDown();assertEquals(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));}api.commit(r.checkoutId(),List.of(r.reservationId()));var restore=new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true));var restoreGo=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(()->{restoreGo.await();return api.restore(restore);});var y=pool.submit(()->{restoreGo.await();return api.restore(restore);});restoreGo.countDown();assertEquals(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE reservation_id=:id",Map.of("id",r.reservationId()),Integer.class));
    }
    private CouponReservations reservationClock(Clock clock){return new com.example.marketplace.promotion.infrastructure.JdbcCouponReservations(jdbc,quotes(),coupons(),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.seller.api.Stores.class),authMapper,transactionManager,clock);}
    private void awaitCouponLock(String table){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE :q OR query LIKE :q2))",Map.of("q","%"+table+"%FOR UPDATE%","q2","%"+table+"%FOR NO KEY UPDATE%"),Boolean.class));if(waiting){break;}try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}assertTrue(waiting,"actual lock wait on "+table);}
    @Test void couponCommitReleaseAndExpiryRace() throws Exception {
        var api=couponReservations();var r=reservationRequest("race");api.reserve(List.of(r));var go=new CountDownLatch(1);var ids=List.of(r.reservationId());
        try(var pool=Executors.newFixedThreadPool(2)){var commit=pool.submit(()->{go.await();try{api.commit(r.checkoutId(),ids);return true;}catch(com.example.marketplace.promotion.api.PromotionRejected e){return false;}});var release=pool.submit(()->{go.await();try{api.release(r.checkoutId(),ids);return true;}catch(com.example.marketplace.promotion.api.PromotionRejected e){return false;}});go.countDown();assertTrue(commit.get(10,TimeUnit.SECONDS)^release.get(10,TimeUnit.SECONDS));}
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_event WHERE reservation_id=:id",Map.of("id",r.reservationId()),Integer.class));
        var exp=reservationRequest("expiry");var clock=new GuestClock();clock.now=Instant.now();var timed=reservationClock(clock);timed.reserve(List.of(exp));var expIds=List.of(exp.reservationId());
        try(var pool=Executors.newFixedThreadPool(2)){var commit=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();var expiry=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<List<CouponReservations.Reservation>>>();new TransactionTemplate(transactionManager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM marketplace.issued_coupon WHERE id=:id FOR UPDATE",Map.of("id",exp.issuedCouponId()),UUID.class);commit.set(pool.submit(()->{try{timed.commit(exp.checkoutId(),expIds);return true;}catch(com.example.marketplace.promotion.api.PromotionRejected e){return false;}}));awaitCouponLock("issued_coupon");clock.now=exp.expiresAt();expiry.set(pool.submit(()->timed.expire(exp.checkoutId(),expIds)));});assertFalse(commit.get().get(10,TimeUnit.SECONDS));var result=expiry.get().get(10,TimeUnit.SECONDS);assertEquals("EXPIRED",result.getFirst().state());assertEquals(result,timed.expire(exp.checkoutId(),expIds));assertEquals("AVAILABLE",coupons().own(exp.memberId(),exp.issuedCouponId()).state());}
    }
    @Test void couponReservationRechecksWaitsAndQuoteContent() throws Exception {
        for(boolean deadline:List.of(false,true)){var r=reservationRequest(deadline?"wait-time":"wait-member");var clock=new GuestClock();clock.now=Instant.now();var api=reservationClock(clock);try(var pool=Executors.newSingleThreadExecutor()){var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();new TransactionTemplate(transactionManager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR NO KEY UPDATE",Map.of("id",r.memberId()),UUID.class);future.set(pool.submit(()->{try{api.reserve(List.of(r));return true;}catch(RuntimeException e){return false;}}));awaitCouponLock("identity_user");if(deadline){clock.now=r.expiresAt();}else{jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",r.memberId()));}});assertFalse(future.get().get(10,TimeUnit.SECONDS));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE id=:id",Map.of("id",r.reservationId()),Integer.class));assertEquals("AVAILABLE",jdbc.queryForObject("SELECT state FROM marketplace.issued_coupon WHERE id=:id",Map.of("id",r.issuedCouponId()),String.class));}}
        var r=reservationRequest("forged");var q=r.quoteSnapshot();var forged=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt(),q.stores(),1,q.fingerprint());var bad=new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),forged,r.expiresAt());assertThrows(RuntimeException.class,()->couponReservations().reserve(List.of(bad)));
        var changed=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt(),q.stores(),1,com.example.marketplace.promotion.domain.QuoteCalculator.fingerprint(forged));assertThrows(RuntimeException.class,()->couponReservations().reserve(List.of(new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),changed,r.expiresAt()))));
        jdbc.update("UPDATE marketplace.catalog_sku SET price=20000,version=version+1 WHERE id=:id",Map.of("id",q.stores().getFirst().lines().getFirst().skuId()));assertThrows(RuntimeException.class,()->couponReservations().reserve(List.of(r)));assertEquals("AVAILABLE",coupons().own(r.memberId(),r.issuedCouponId()).state());
    }
    @Test void couponReservationBatchAndAuditRollback(){
        var a=reservationRequest("batch-a");var b=reservationRequest("batch-b");var second=coupons().claim(a.memberId(),coupons().own(b.memberId(),b.issuedCouponId()).templateId());var lines=java.util.stream.Stream.of(a,b).flatMap(r->r.quoteSnapshot().stores().stream()).flatMap(s->s.lines().stream()).map(l->quoteLine(l.skuId(),l.quantity())).toList();var q=quotes().calculate(a.memberId(),lines,List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(a.storeId(),a.issuedCouponId()),new com.example.marketplace.promotion.api.Quotes.CouponSelection(b.storeId(),second.id())));
        var ra=new CouponReservations.Request(a.reservationId(),a.checkoutId(),a.sellerOrderRef(),a.memberId(),a.storeId(),a.issuedCouponId(),q,a.expiresAt());var rb=new CouponReservations.Request(b.reservationId(),a.checkoutId(),b.sellerOrderRef(),a.memberId(),b.storeId(),second.id(),q,a.expiresAt());var batch=List.of(ra,rb);var api=couponReservations();
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_reservation_event ADD CONSTRAINT p07_d_audit_fail CHECK(reservation_id<>'"+rb.reservationId()+"')");try{assertThrows(RuntimeException.class,()->api.reserve(batch));assertEquals("AVAILABLE",coupons().own(a.memberId(),a.issuedCouponId()).state());assertEquals("AVAILABLE",coupons().own(a.memberId(),second.id()).state());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE checkout_id=:id",Map.of("id",a.checkoutId()),Integer.class));}finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_reservation_event DROP CONSTRAINT p07_d_audit_fail");}
        var reserved=api.reserve(batch);assertEquals(reserved,api.reserve(List.of(rb,ra)));assertThrows(RuntimeException.class,()->api.reserve(List.of(ra)));var ids=List.of(ra.reservationId(),rb.reservationId());assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(s->{api.commit(a.checkoutId(),ids);throw new IllegalStateException("after commit rollback");}));assertEquals("RESERVED",coupons().own(a.memberId(),second.id()).state());var released=api.release(a.checkoutId(),ids);assertEquals(released,api.release(a.checkoutId(),ids));assertThrows(RuntimeException.class,()->api.commit(a.checkoutId(),ids));
    }
    @Test void couponRestorationPreservesExpiryAndCause(){
        var r=reservationRequest("restore-expired");var api=couponReservations();api.reserve(List.of(r));api.commit(r.checkoutId(),List.of(r.reservationId()));var unconfirmed=new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,false));assertFalse(api.restore(unconfirmed).restored());assertEquals(api.restore(unconfirmed),api.restore(unconfirmed));assertThrows(RuntimeException.class,()->api.restore(new CouponReservations.RestoreRequest(unconfirmed.causeId(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true))));
        var clock=new GuestClock();var ends=coupons().own(r.memberId(),r.issuedCouponId()).terms().endsAt();clock.now=ends;var timed=reservationClock(clock);var full=new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true));var before=coupons().own(r.memberId(),r.issuedCouponId());assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(s->{timed.restore(full);throw new IllegalStateException("restore caller rollback");}));assertEquals(before,coupons().own(r.memberId(),r.issuedCouponId()));var restored=timed.restore(full);assertTrue(restored.restored());assertFalse(restored.reusable());assertEquals(ends,coupons().own(r.memberId(),r.issuedCouponId()).terms().endsAt());assertFalse(coupons().eligible(r.memberId(),r.issuedCouponId(),r.storeId(),10000,ends).eligible());assertEquals(restored,timed.restore(full));
    }
    private com.example.marketplace.promotion.api.CouponReservations couponReservations(){return context.getBean(com.example.marketplace.promotion.api.CouponReservations.class);}
    private com.example.marketplace.promotion.api.CouponReservations.Request reservationRequest(String suffix){
        var f=browseFixture("reserve-"+suffix,10000,10);UUID owner=(UUID)f.get("owner"),sku=(UUID)f.get("sku");var t=couponTemplate(owner,"예약 쿠폰",5);var c=coupons().claim(owner,t.id());var q=quotes().calculate(owner,List.of(quoteLine(sku,1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),c.id())));
        return new com.example.marketplace.promotion.api.CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),owner,t.storeId(),c.id(),q,Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    @Test void couponReservationIsAtomicAndIdempotent() throws Exception {
        var r=reservationRequest("atomic");var api=couponReservations();var before=coupons().own(r.memberId(),r.issuedCouponId());
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(s->{api.reserve(List.of(r));throw new IllegalStateException("caller rollback");}));assertEquals(before,coupons().own(r.memberId(),r.issuedCouponId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE id=:id",Map.of("id",r.reservationId()),Integer.class));
        var other=new com.example.marketplace.promotion.api.CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),r.expiresAt());var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){var futures=List.of(r,other).stream().map(request->pool.submit(()->{go.await();try{return api.reserve(List.of(request)).getFirst();}catch(com.example.marketplace.promotion.api.PromotionRejected e){return null;}})).toList();go.countDown();var results=new java.util.ArrayList<com.example.marketplace.promotion.api.CouponReservations.Reservation>();for(var future:futures){var result=future.get(10,TimeUnit.SECONDS);if(result!=null){results.add(result);}}assertEquals(1,results.size());var winner=results.getFirst().id().equals(r.reservationId())?r:other;assertEquals(results,api.reserve(List.of(winner)));assertEquals("RESERVED",coupons().own(r.memberId(),r.issuedCouponId()).state());
            var changed=new com.example.marketplace.promotion.api.CouponReservations.Request(winner.reservationId(),winner.checkoutId(),UUID.randomUUID(),winner.memberId(),winner.storeId(),winner.issuedCouponId(),winner.quoteSnapshot(),winner.expiresAt());assertThrows(RuntimeException.class,()->api.reserve(List.of(changed)));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE coupon_id=:id",Map.of("id",r.issuedCouponId()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_event WHERE reservation_id=:id",Map.of("id",winner.reservationId()),Integer.class));
        }
    }
    @Test void couponRestoreCannotAffectNewReservation(){
        var r=reservationRequest("restore");var api=couponReservations();api.reserve(List.of(r));var ids=List.of(r.reservationId());var committed=api.commit(r.checkoutId(),ids);assertEquals(committed,api.commit(r.checkoutId(),ids));assertEquals("USED",coupons().own(r.memberId(),r.issuedCouponId()).state());
        var partial=new com.example.marketplace.promotion.api.CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new com.example.marketplace.promotion.api.CouponReservations.RefundCompletion(false,true));assertFalse(api.restore(partial).restored());assertEquals("USED",coupons().own(r.memberId(),r.issuedCouponId()).state());var full=new com.example.marketplace.promotion.api.CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new com.example.marketplace.promotion.api.CouponReservations.RefundCompletion(true,true));var restored=api.restore(full);assertTrue(restored.restored());assertTrue(restored.reusable());assertEquals(restored,api.restore(full));
        var q=quotes().calculate(r.memberId(),r.quoteSnapshot().stores().stream().flatMap(s->s.lines().stream()).map(l->quoteLine(l.skuId(),l.quantity())).toList(),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(r.storeId(),r.issuedCouponId())));var next=new com.example.marketplace.promotion.api.CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),r.memberId(),r.storeId(),r.issuedCouponId(),q,r.expiresAt());api.reserve(List.of(next));var current=coupons().own(r.memberId(),r.issuedCouponId());assertEquals(restored,api.restore(full));assertThrows(RuntimeException.class,()->api.release(r.checkoutId(),ids));assertEquals(current,coupons().own(r.memberId(),r.issuedCouponId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE reservation_id=:id AND restored",Map.of("id",r.reservationId()),Integer.class));
    }
    @Test void quoteMultipleLinesFingerprintAndHttpContract() throws Exception {
        var a=browseFixture("quote-multi-a",100,10);var b=browseFixture("quote-multi-b",100,10);UUID owner=(UUID)a.get("owner"),member=activeMember("quote-multi@example.com"),product=((com.example.marketplace.catalog.api.Products.Product)a.get("product")).id();var ids=new java.util.ArrayList<UUID>();
        for(int n=0;n<3;n++){var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).save(owner,product,null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("종류","선택"+n)),100L,true,0L));ids.add(sku.id());jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand) VALUES(:id,10)",Map.of("id",sku.id()));}ids.sort(java.util.Comparator.comparing(UUID::toString));
        var ta=couponTemplate(owner,"배분100",5);var tb=couponTemplate((UUID)b.get("owner"),"둘째100",5);var ca=coupons().claim(member,ta.id());var cb=coupons().claim(member,tb.id());var selections=new java.util.ArrayList<>(List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(ta.storeId(),ca.id()),new com.example.marketplace.promotion.api.Quotes.CouponSelection(tb.storeId(),cb.id())));var lines=new java.util.ArrayList<>(ids.stream().map(id->quoteLine(id,1)).toList());lines.add(quoteLine((UUID)b.get("sku"),1));
        var before=quoteReadOnlyState();var q=quotes().calculate(member,lines,selections);var store=q.stores().stream().filter(s->s.storeId().equals(ta.storeId())).findFirst().orElseThrow();assertEquals(List.of(34L,33L,33L),store.lines().stream().map(l->l.couponDiscount()).toList());assertEquals(3200,store.total());assertEquals(6200,q.total());store.lines().forEach(l->assertEquals(l.net(),l.units().stream().mapToLong(u->u.net()).sum()));java.util.Collections.reverse(lines);java.util.Collections.reverse(selections);var reverse=quotes().calculate(member,lines,selections);assertEquals(q.stores(),reverse.stores());assertEquals(q.fingerprint(),reverse.fingerprint());assertEquals(before,quoteReadOnlyState());
        var browser=authBrowser();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("quote-multi@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());var response=accountRequest(browser,"POST","/api/quotes",Map.of("lines",lines,"coupons",selections),csrfToken(browser));assertEquals(200,response.statusCode());var tree=authMapper.readTree(response.body());assertEquals(java.util.Set.of("policyVersion","evaluatedAt","fingerprint","total","stores"),authMapper.readValue(response.body(),Map.class).keySet());
        for(var s:tree.get("stores")){for(String key:List.of("subtotal","productDiscount","afterPromotion","couponDiscount","shippingBase","shippingDiscount","total")){assertTrue(s.get(key).isString());assertTrue(s.get(key).asText().matches("0|[1-9][0-9]*"));}assertTrue(s.get("coupon").get("version").isIntegralNumber());assertTrue(s.get("coupon").get("templateVersion").isIntegralNumber());for(var l:s.get("lines")){for(String key:List.of("unitPrice","gross","promotionDiscount","couponDiscount","net")){assertTrue(l.get(key).isString());}for(var u:l.get("units")){for(String key:List.of("gross","promotionDiscount","couponDiscount","net")){assertTrue(u.get(key).isString());}}}}
    }
    private List<String> quoteReadOnlyState(){var result=new java.util.ArrayList<String>();for(String table:List.of("coupon_template","issued_coupon","coupon_event","inventory_balance","inventory_reservation","guest_checkout","guest_attempt")){result.addAll(jdbc.getJdbcTemplate().queryForList("SELECT row_to_json(t)::text FROM marketplace."+table+" t ORDER BY row_to_json(t)::text",String.class));}return result;}
    @Test void quoteRejectsEveryUnavailableProjection() {
        var f=browseFixture("quote-unavailable",100,10);UUID sku=(UUID)f.get("sku"),owner=(UUID)f.get("owner");var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");var values=Map.of("sku",sku,"product",product.id(),"owner",owner);var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        for(String sql:List.of("UPDATE marketplace.catalog_sku SET active=false WHERE id=:sku","UPDATE marketplace.catalog_product SET state='STOPPED' WHERE id=:product","UPDATE marketplace.catalog_category SET active=false WHERE id=(SELECT category_id FROM marketplace.catalog_product WHERE id=:product)","UPDATE marketplace.seller_store SET visible=false WHERE owner_id=:owner","UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE owner_id=:owner","UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:owner","DELETE FROM marketplace.role_assignment WHERE user_id=:owner AND role='SELLER'","UPDATE marketplace.inventory_balance SET reserved=on_hand WHERE sku_id=:sku")){
            tx.executeWithoutResult(status->{try{jdbc.update(sql,values);assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->quotes().calculate(null,List.of(quoteLine(sku,1)),List.of()));}finally{status.setRollbackOnly();}});assertEquals(3100,quotes().calculate(null,List.of(quoteLine(sku,1)),List.of()).total());
        }
    }
    @Test void quoteReadSnapshotSurvivesConcurrentPrices() throws Exception {
        var a=browseFixture("quote-consistent-a",30000,10);var b=browseFixture("quote-consistent-b",40000,10);var read=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        var delegate=context.getBean(com.example.marketplace.catalog.api.Browse.class);
        var probe=new com.example.marketplace.catalog.api.Browse(){
            public com.example.marketplace.catalog.api.Products.Page<Item> search(Query q){return delegate.search(q);}public Detail detail(String s){return delegate.detail(s);}public Detail forSku(UUID s){return delegate.forSku(s);}public Detail forProduct(UUID p){return delegate.forProduct(p);}public UUID identify(String s){return delegate.identify(s);}
            public QuoteOffer quoteOffer(UUID id){var result=delegate.quoteOffer(id);if(once.compareAndSet(false,true)){assertEquals("repeatable read",jdbc.getJdbcTemplate().queryForObject("SHOW transaction_isolation",String.class));read.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return result;}
        };
        var api=new com.example.marketplace.promotion.infrastructure.JdbcQuotes(probe,promotions(),coupons(),context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),context.getBean(PlatformTransactionManager.class));var lines=List.of(quoteLine((UUID)a.get("sku"),1),quoteLine((UUID)b.get("sku"),1));
        try(var pool=Executors.newSingleThreadExecutor()) {var future=pool.submit(()->api.calculate(null,lines,List.of()));try{assertTrue(read.await(10,TimeUnit.SECONDS));jdbc.update("UPDATE marketplace.catalog_sku SET price=50000,version=version+1 WHERE id IN (:a,:b)",Map.of("a",a.get("sku"),"b",b.get("sku")));}finally{release.countDown();}assertEquals(76000,future.get(10,TimeUnit.SECONDS).total());assertEquals(100000,quotes().calculate(null,lines,List.of()).total());}
    }
    @Test void quoteShippingAndZeroTotalsUsePromotionOrder() throws Exception {
        var f=browseFixture("quote-zero",50000,99);UUID owner=(UUID)f.get("owner"),sku=(UUID)f.get("sku"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();var lines=List.of(quoteLine(sku,1));
        var t=coupons().saveTemplate(owner,null,couponInput("전액 쿠폰",50000,5,"DRAFT",0));t=coupons().saveTemplate(owner,t.id(),couponInput("전액 쿠폰",50000,5,"ACTIVE",0));var c=coupons().claim(owner,t.id());var selected=List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),c.id()));assertEquals(0,quotes().calculate(owner,lines,selected).total());
        var p=promotions().save(owner,null,promotionInput(product,"DRAFT",0));promotions().save(owner,p.id(),promotionInput(product,"ACTIVE",0));var q=quotes().calculate(owner,lines,selected);assertEquals(45000,q.stores().getFirst().afterPromotion());assertEquals(3000,q.total());
        var starts=Instant.now().minusSeconds(60);var ends=starts.plusSeconds(3600);var ids=new java.util.ArrayList<UUID>();
        for(int n=0;n<2;n++){var input=new com.example.marketplace.promotion.api.Promotions.Input("무료 배송", "SHIPPING",List.of(),starts,ends,"FREE",0L,null,"DRAFT",0L);var draft=promotions().save(owner,null,input);var active=promotions().save(owner,draft.id(),new com.example.marketplace.promotion.api.Promotions.Input(input.title(),input.scope(),input.productIds(),starts,ends,"FREE",0L,null,"ACTIVE",0L));ids.add(active.id());}
        var zero=quotes().calculate(owner,lines,selected);assertEquals(0,zero.total());assertEquals(3000,zero.stores().getFirst().shippingDiscount());assertEquals(ids.stream().min(java.util.Comparator.comparing(UUID::toString)).orElseThrow(),zero.stores().getFirst().shippingPromotion().id());assertEquals(0,zero.stores().getFirst().lines().getFirst().units().getFirst().net());
        var browser=authBrowser();var response=accountRequest(browser,"POST","/api/quotes",Map.of("lines",lines,"coupons",List.of()),csrfToken(browser));assertEquals(200,response.statusCode());var web=authMapper.readTree(response.body()).get("stores").get(0);assertEquals(zero.stores().getFirst().shippingPromotion().id().toString(),web.get("shippingPromotion").get("id").asText());assertEquals(1,web.get("shippingPromotion").get("version").asLong());assertEquals(p.id().toString(),web.get("lines").get(0).get("promotion").get("id").asText());assertEquals("MAX_DISCOUNT_ID_TIE",web.get("lines").get(0).get("promotion").get("selectionReason").asText());
    }
    private com.example.marketplace.promotion.api.Quotes quotes() { return context.getBean(com.example.marketplace.promotion.api.Quotes.class); }
    private com.example.marketplace.promotion.api.Quotes.LineRequest quoteLine(UUID sku,int quantity) { return new com.example.marketplace.promotion.api.Quotes.LineRequest(sku,quantity); }
    @Test void quotesUseCurrentAuthorizedInputs() throws Exception {
        var a=browseFixture("quote-a",30000,10);var b=browseFixture("quote-b",40000,10);UUID owner=(UUID)a.get("owner"),skuA=(UUID)a.get("sku"),skuB=(UUID)b.get("sku"),member=activeMember("quote-member@example.com"),other=activeMember("quote-other@example.com");var api=quotes();var lines=List.of(quoteLine(skuA,1),quoteLine(skuB,1));
        var guest=api.calculate(null,lines,List.of());assertEquals(76000,guest.total());assertEquals(2,guest.stores().size());assertEquals(guest.stores(),api.calculate(member,lines,List.of()).stores());
        var d=coupons().saveTemplate(owner,null,couponInput("견적 쿠폰",3000,10,"DRAFT",0));var t=coupons().saveTemplate(owner,d.id(),couponInput("견적 쿠폰",3000,10,"ACTIVE",0));var coupon=coupons().claim(member,t.id());var selected=List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),coupon.id()));
        long events=jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event",Map.of(),Long.class),reservations=jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation",Map.of(),Long.class);var q=api.calculate(member,lines,selected);assertEquals(73000,q.total());assertEquals(3000,q.stores().stream().mapToLong(x->x.couponDiscount()).sum());assertEquals(coupon,coupons().own(member,coupon.id()));assertEquals(events,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event",Map.of(),Long.class));assertEquals(reservations,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation",Map.of(),Long.class));
        assertThrows(RuntimeException.class,()->api.calculate(null,lines,selected));assertThrows(RuntimeException.class,()->api.calculate(other,lines,selected));assertThrows(RuntimeException.class,()->api.calculate(member,lines,List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(UUID.randomUUID(),coupon.id()))));
        assertThrows(RuntimeException.class,()->api.calculate(member,lines,List.of(selected.getFirst(),selected.getFirst())));assertThrows(RuntimeException.class,()->api.calculate(null,List.of(quoteLine(skuA,1),quoteLine(skuA,2)),List.of()));
        for(int quantity:new int[]{0,11,100}){assertThrows(RuntimeException.class,()->api.calculate(null,List.of(quoteLine(skuA,quantity)),List.of()));}
        assertThrows(RuntimeException.class,()->api.calculate(null,java.util.stream.IntStream.range(0,21).mapToObj(n->quoteLine(UUID.randomUUID(),1)).toList(),List.of()));
        var browser=authBrowser();var body=Map.of("lines",List.of(Map.of("skuId",skuA,"quantity",1,"price",1),Map.of("skuId",skuB,"quantity",1)),"coupons",List.of(),"total","1");assertEquals(403,accountRequest(browser,"POST","/api/quotes",body,null).statusCode());var response=accountRequest(browser,"POST","/api/quotes",body,csrfToken(browser));assertEquals(200,response.statusCode());var tree=authMapper.readTree(response.body());assertTrue(tree.get("total").isString());assertEquals("76000",tree.get("total").asText());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertFalse(tree.has("memberId"));
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",((com.example.marketplace.catalog.api.Products.Product)a.get("product")).id()));assertThrows(RuntimeException.class,()->api.calculate(null,lines,List.of()));
    }
    @Test void quoteSnapshotsRemainStable() {
        var f=browseFixture("quote-snapshot",10001,99);UUID owner=(UUID)f.get("owner"),sku=(UUID)f.get("sku"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();var p=promotions().save(owner,null,promotionInput(product,"DRAFT",0));promotions().save(owner,p.id(),promotionInput(product,"ACTIVE",0));
        var t=couponTemplate(owner,"스냅샷 쿠폰",10);var c=coupons().claim(owner,t.id());var selection=List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),c.id()));var lines=List.of(quoteLine(sku,3));var q=quotes().calculate(owner,lines,selection);var store=q.stores().getFirst();var line=store.lines().getFirst();
        assertEquals(30003,line.gross());assertEquals(3000,line.promotionDiscount());assertEquals(100,line.couponDiscount());assertEquals(26903,line.net());assertEquals(29903,q.total());assertEquals(34,line.units().getFirst().couponDiscount());assertEquals(line.net(),line.units().stream().mapToLong(u->u.net()).sum());
        assertEquals(q.fingerprint(),quotes().calculate(owner,lines,selection).fingerprint());assertThrows(UnsupportedOperationException.class,()->q.stores().clear());assertThrows(UnsupportedOperationException.class,()->line.units().clear());long refund=line.units().get(0).net()+line.units().get(1).net();
        jdbc.update("UPDATE marketplace.catalog_sku SET price=20000,version=version+1 WHERE id=:id",Map.of("id",sku));promotions().save(owner,p.id(),promotionInput(product,"STOPPED",1));var next=quotes().calculate(owner,lines,selection);assertEquals(59900,next.total());assertFalse(q.fingerprint().equals(next.fingerprint()));assertEquals(29903,q.total());assertEquals(refund,line.units().get(0).net()+line.units().get(1).net());
        var altered=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt().plusSeconds(100),q.stores(),q.total(),q.fingerprint());assertEquals(q.fingerprint(),com.example.marketplace.promotion.domain.QuoteCalculator.fingerprint(altered));
    }


    private boolean couponAvailable(UUID member,UUID template) {
        for(int page=0;page<=10000;page++){var rows=coupons().available(member,page);if(rows.items().stream().anyMatch(t->t.id().equals(template))){return true;}if(!rows.hasMore()){return false;}}throw new IllegalStateException("PAGING_BOUND");
    }

    @Test void couponCrossClaimsAndManagementTransactions() throws Exception {
        UUID a=(UUID)browseFixture("coupon-cross-a",100,1).get("owner"),b=(UUID)browseFixture("coupon-cross-b",100,1).get("owner");var api=coupons();var ta=couponTemplate(a,"같은 이름",5);var tb=couponTemplate(b,"같은 이름",5);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->{go.await();return api.claim(a,tb.id());});var y=pool.submit(()->{go.await();return api.claim(b,ta.id());});go.countDown();assertEquals(tb.storeId(),x.get(10,TimeUnit.SECONDS).storeId());assertEquals(ta.storeId(),y.get(10,TimeUnit.SECONDS).storeId());
        }
        var original=api.template(a,ta.id());var issued=api.claim(a,ta.id());long events=jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event WHERE template_id=:id",Map.of("id",ta.id()),Long.class);original=api.template(a,ta.id());
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_event ADD CONSTRAINT p07_template_fail CHECK(template_id<>'"+ta.id()+"' OR action<>'TEMPLATE' OR version<2)");
        try{assertThrows(RuntimeException.class,()->api.saveTemplate(a,ta.id(),couponInput("바뀌면 안 됨",50,10,"STOPPED",1)));assertEquals(original,api.template(a,ta.id()));assertEquals(issued,api.own(a,issued.id()));assertEquals(events,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event WHERE template_id=:id",Map.of("id",ta.id()),Long.class));}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_event DROP CONSTRAINT p07_template_fail");}
        var ready=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){Callable<Boolean> save=()->{ready.await();try{api.saveTemplate(a,ta.id(),couponInput("한 승자",80,5,"ACTIVE",1));return true;}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return false;}};var x=pool.submit(save);var y=pool.submit(save);ready.countDown();assertEquals(1,(x.get(10,TimeUnit.SECONDS)?1:0)+(y.get(10,TimeUnit.SECONDS)?1:0));}
        var race=couponTemplate(a,"하향 경쟁",2);api.claim(a,race.id());var raceStart=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var x=pool.submit(()->{raceStart.await();try{api.claim(b,race.id());return true;}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return false;}});
            var y=pool.submit(()->{raceStart.await();try{api.saveTemplate(a,race.id(),couponInput("하향 경쟁",100,1,"ACTIVE",1));return true;}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return false;}});raceStart.countDown();assertEquals(1,(x.get(10,TimeUnit.SECONDS)?1:0)+(y.get(10,TimeUnit.SECONDS)?1:0));
        }
        var result=api.template(a,race.id());assertTrue(result.issuedCount()<=result.totalLimit());
    }
    @Test void couponAvailabilityRetryAndPagingBoundaries() {
        UUID owner=(UUID)browseFixture("coupon-visibility",100,1).get("owner"),member=activeMember("coupon-visibility-member@example.com"),other=activeMember("coupon-visibility-other@example.com");var api=coupons();var t=couponTemplate(owner,"보유",100);var c=api.claim(member,t.id());
        String[] off={"UPDATE marketplace.seller_store SET visible=false WHERE id=:store","UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE id=:store","DELETE FROM marketplace.role_assignment WHERE user_id=:owner AND role='SELLER'"};
        String[] on={"UPDATE marketplace.seller_store SET visible=true WHERE id=:store","UPDATE marketplace.seller_store SET state='OPEN',visible=true WHERE id=:store","INSERT INTO marketplace.role_assignment VALUES(:owner,'SELLER')"};var p=Map.of("store",t.storeId(),"owner",owner);
        for(int n=0;n<off.length;n++){assertTrue(couponAvailable(other,t.id()));jdbc.update(off[n],p);assertThrows(RuntimeException.class,()->api.claim(other,t.id()));assertFalse(couponAvailable(other,t.id()));assertFalse(api.eligible(member,c.id(),t.storeId(),100,Instant.now()).eligible());assertEquals(c,api.claim(member,t.id()));assertTrue(api.owned(member,0).items().contains(c));jdbc.update(on[n],p);}
        jdbc.update("UPDATE marketplace.coupon_template SET issue_end=:end WHERE id=:id",Map.of("id",t.id(),"end",java.sql.Timestamp.from(Instant.now().minusSeconds(1))));assertEquals(c,api.claim(member,t.id()));assertThrows(RuntimeException.class,()->api.claim(other,t.id()));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",member));assertThrows(RuntimeException.class,()->api.claim(member,t.id()));jdbc.update("UPDATE marketplace.identity_user SET state='ACTIVE' WHERE id=:id",Map.of("id",member));
        for(int n=0;n<21;n++){var next=couponTemplate(owner,"동일 쿠폰",100);api.claim(member,next.id());}
        var first=api.owned(member,0);var second=api.owned(member,1);assertEquals(20,first.items().size());assertTrue(first.hasMore());assertEquals(2,second.items().size());assertFalse(second.hasMore());var ids=new java.util.HashSet<UUID>();first.items().forEach(x->assertTrue(ids.add(x.id())));second.items().forEach(x->assertTrue(ids.add(x.id())));assertEquals(0,api.owned(other,0).items().size());
        assertTrue(api.available(other,0).hasMore());assertEquals(20,api.available(other,0).items().size());assertTrue(api.available(member,0).items().stream().noneMatch(x->x.storeId().equals(t.storeId())));
    }


    @Test void couponHttpAmountsAndStoreIdentity() throws Exception {
        UUID seller=(UUID)browseFixture("coupon-http",100,1).get("owner"),member=activeMember("coupon-http-member@example.com");var api=coupons();Instant start=Instant.now().minusSeconds(60),end=start.plusSeconds(7200);
        var input=new com.example.marketplace.promotion.api.Coupons.TemplateInput("같은 이름","RATE",10000L,null,0L,start,end,start,end,50L,"DRAFT",0L);
        var draft=api.saveTemplate(seller,null,input);var active=api.saveTemplate(seller,draft.id(),new com.example.marketplace.promotion.api.Coupons.TemplateInput("같은 이름","RATE",10000L,null,0L,start,end,start,end,50L,"ACTIVE",0L));var c=api.claim(member,active.id());
        var browser=authBrowser();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("coupon-http-member@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        for(long amount:new long[]{9007199254740991L,9007199254740992L,9007199254740993L,-1}) {
            var response=accountRequest(browser,"GET","/api/account/coupons/"+c.id()+"/eligibility?storeId="+active.storeId()+"&subtotal="+amount,null,null);assertEquals(amount>=0&&amount<=9007199254740991L?200:400,response.statusCode(),"subtotal="+amount);
            if(response.statusCode()==200){assertEquals(amount,authMapper.readTree(response.body()).get("discount").asLong());}
        }
        assertEquals(Long.MAX_VALUE,api.eligible(member,c.id(),active.storeId(),Long.MAX_VALUE,Instant.now()).discount());
        var response=accountRequest(browser,"GET","/api/account/coupons/"+c.id(),null,null);var tree=authMapper.readTree(response.body());assertEquals(context.getBean(com.example.marketplace.seller.api.Stores.class).own(seller).slug(),tree.get("storeSlug").asText());
        assertEquals(java.util.Set.of("id","templateId","storeId","title","terms","templateVersion","state","version","storeSlug","storeName"),authMapper.readValue(response.body(),Map.class).keySet());
        assertEquals(java.util.Set.of("kind","value","cap","minimum","startsAt","endsAt"),authMapper.readValue(tree.get("terms").toString(),Map.class).keySet());
        var admin=authBrowser();assertEquals(200,authRequest(admin,"/api/auth/login",loginForm("p4b-browse-coupon-http@example.com",SIGNUP_PASSWORD),csrfToken(admin)).statusCode());String csrf=csrfToken(admin);
        var base=new java.util.HashMap<String,Object>();base.put("title","경계");base.put("kind","FIXED");base.put("value",1L);base.put("cap",1L);base.put("minimum",0L);base.put("totalLimit",1L);base.put("issueStart",start.toString());base.put("issueEnd",end.toString());base.put("startsAt",start.toString());base.put("endsAt",end.toString());base.put("state","DRAFT");base.put("version",0L);
        for(String field:List.of("value","cap","minimum","totalLimit")){for(long number:new long[]{9007199254740991L,9007199254740992L,9007199254740993L,-1}){var values=new java.util.HashMap<>(base);values.put(field,number);var saved=accountRequest(admin,"POST","/api/seller/coupons",values,csrf);assertEquals(number>=0&&number<=9007199254740991L?201:400,saved.statusCode(),field+"="+number);}}
        var seller2=(UUID)browseFixture("coupon-http-other",100,1).get("owner");var duplicate=couponTemplate(seller2,"같은 이름",50);var second=api.claim(member,duplicate.id());assertFalse(c.storeSlug().equals(second.storeSlug()));assertEquals("같은 이름",second.title());

    }


    private com.example.marketplace.promotion.api.Coupons coupons() { return context.getBean(com.example.marketplace.promotion.api.Coupons.class); }
    private com.example.marketplace.promotion.api.Coupons.TemplateInput couponInput(String title,long value,long limit,String state,long version) {
        Instant start=Instant.now().minusSeconds(60),end=start.plusSeconds(7200);
        return new com.example.marketplace.promotion.api.Coupons.TemplateInput(title,"FIXED",value,null,100L,start,end,start,end,limit,state,version);
    }
    private com.example.marketplace.promotion.api.Coupons.Template couponTemplate(UUID owner,String title,long limit) {
        var d=coupons().saveTemplate(owner,null,couponInput(title,100,limit,"DRAFT",0));return coupons().saveTemplate(owner,d.id(),couponInput(title,100,limit,"ACTIVE",0));
    }
    @Test void couponIssuanceIsBoundedAndIdempotent() throws Exception {
        UUID seller=(UUID)browseFixture("coupon-limit",100,1).get("owner");var api=coupons();var t=couponTemplate(seller,"한도",1);
        UUID a=activeMember("coupon-limit-a@example.com"),b=activeMember("coupon-limit-b@example.com");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{start.await();try{return api.claim(a,t.id());}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return null;}});
            var two=pool.submit(()->{start.await();try{return api.claim(b,t.id());}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return null;}});
            start.countDown();var x=one.get(10,TimeUnit.SECONDS);var y=two.get(10,TimeUnit.SECONDS);assertTrue((x==null)!=(y==null));UUID winner=x==null?b:a;
            assertEquals(x==null?y:x,api.claim(winner,t.id()));assertEquals(1,api.template(seller,t.id()).issuedCount());
        }
        var same=couponTemplate(seller,"재시도",10);var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){Callable<com.example.marketplace.promotion.api.Coupons.Issued> request=()->{ready.await();return api.claim(a,same.id());};var x=pool.submit(request);var y=pool.submit(request);ready.countDown();assertEquals(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));}
        assertEquals(1,api.template(seller,same.id()).issuedCount());
        var rollback=couponTemplate(seller,"감사 실패",10);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_event ADD CONSTRAINT p07_b_fail CHECK(template_id<>'"+rollback.id()+"' OR action<>'CLAIM')");
        try{assertThrows(RuntimeException.class,()->api.claim(a,rollback.id()));assertEquals(0,api.template(seller,rollback.id()).issuedCount());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.issued_coupon WHERE template_id=:id",Map.of("id",rollback.id()),Integer.class));}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_event DROP CONSTRAINT p07_b_fail");}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event WHERE template_id=:id AND action='CLAIM'",Map.of("id",same.id()),Integer.class));
    }
    @Test void couponOwnershipAndTemplateSnapshot() throws Exception {
        UUID seller=(UUID)browseFixture("coupon-snapshot",100,1).get("owner");var api=coupons();var t=couponTemplate(seller,"원래 조건",10);UUID buyer=activeMember("coupon-snapshot-buyer@example.com"),other=activeMember("coupon-snapshot-other@example.com");
        var issued=api.claim(buyer,t.id());assertThrows(RuntimeException.class,()->api.own(other,issued.id()));assertThrows(RuntimeException.class,()->api.template(other,t.id()));
        assertTrue(api.eligible(buyer,issued.id(),t.storeId(),100,Instant.now()).eligible());assertEquals(100,api.eligible(buyer,issued.id(),t.storeId(),100,Instant.now()).discount());assertFalse(api.eligible(buyer,issued.id(),t.storeId(),99,Instant.now()).eligible());assertFalse(api.eligible(buyer,issued.id(),UUID.randomUUID(),100,Instant.now()).eligible());assertFalse(api.eligible(buyer,issued.id(),t.storeId(),100,issued.terms().endsAt()).eligible());
        api.saveTemplate(seller,t.id(),couponInput("바뀐 조건",50,10,"ACTIVE",1));assertEquals(issued,api.own(buyer,issued.id()));var next=api.claim(other,t.id());assertEquals(50,next.terms().value());assertEquals(2,next.templateVersion());
        assertThrows(RuntimeException.class,()->api.saveTemplate(seller,t.id(),couponInput("낮은 한도",50,1,"ACTIVE",2)));
        api.saveTemplate(seller,t.id(),couponInput("발급 중지",50,10,"STOPPED",2));assertEquals(issued,api.claim(buyer,t.id()));assertTrue(api.eligible(buyer,issued.id(),t.storeId(),100,Instant.now()).eligible());assertThrows(RuntimeException.class,()->api.claim(seller,t.id()));
        var ownSeller=couponTemplate(seller,"판매자도 구매자",2);assertEquals(ownSeller.id(),api.claim(seller,ownSeller.id()).templateId());
        var guest=authBrowser();assertEquals(401,accountRequest(guest,"GET","/api/account/coupons",null,null).statusCode());
        var member=authBrowser();assertEquals(200,authRequest(member,"/api/auth/login",loginForm("coupon-snapshot-buyer@example.com",SIGNUP_PASSWORD),csrfToken(member)).statusCode());
        assertEquals(403,accountRequest(member,"POST","/api/account/coupons/claim",Map.of("templateId",ownSeller.id()),null).statusCode());
        assertEquals(404,accountRequest(member,"GET","/api/account/coupons/"+next.id(),null,null).statusCode());
        var response=accountRequest(member,"GET","/api/account/coupons/"+issued.id(),null,null);assertEquals(200,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertFalse(response.body().contains("memberId"));
    }
    @Test void couponIssuanceRechecksAuthority() throws Exception {
        for(String change:List.of("buyer","seller","time")) {
            UUID seller=(UUID)browseFixture("coupon-wait-"+change,100,1).get("owner"),buyer=activeMember("coupon-wait-"+change+"@example.com");var api=coupons();var t=couponTemplate(seller,"대기",5);
            UUID lock=change.equals("buyer")?buyer:seller;var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var holder=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",lock),UUID.class);locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}
                    if(change.equals("time")){System.out.println("P07_CLOCK java="+Instant.now()+" database="+jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.time.OffsetDateTime.class));jdbc.update("UPDATE marketplace.coupon_template SET issue_end=:end WHERE id=:id",Map.of("id",t.id(),"end",java.sql.Timestamp.from(Instant.now().minusSeconds(1))));}else{jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",lock));}return true;}));
                assertTrue(locked.await(5,TimeUnit.SECONDS));var writer=pool.submit(()->assertThrows(RuntimeException.class,()->api.claim(buyer,t.id()),"wait scenario="+change));
                try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%')",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);}finally{release.countDown();}
                holder.get(5,TimeUnit.SECONDS);writer.get(5,TimeUnit.SECONDS);assertEquals(0,jdbc.queryForObject("SELECT issued_count FROM marketplace.coupon_template WHERE id=:id",Map.of("id",t.id()),Long.class));
            }
        }
    }


    @Test void promotionArchivedTargetsCanBeRetiredWithoutBypassingOwnership() throws Exception {
        var f=browseFixture("promo-retire",100,1);UUID owner=(UUID)f.get("owner");var p=(com.example.marketplace.catalog.api.Products.Product)f.get("product");var api=promotions();
        var d=api.save(owner,null,promotionInput(p.id(),"DRAFT",0));api.save(owner,d.id(),promotionInput(p.id(),"ACTIVE",0));
        jdbc.update("UPDATE marketplace.catalog_product SET state='ARCHIVED' WHERE id=:id",Map.of("id",p.id()));
        var stopped=api.save(owner,d.id(),promotionInput(p.id(),"STOPPED",1));assertEquals(2,stopped.version());assertEquals(List.of(p.id()),stopped.productIds());
        assertThrows(RuntimeException.class,()->api.save(owner,d.id(),promotionInput(p.id(),"ARCHIVED",1)));
        assertThrows(RuntimeException.class,()->api.save(owner,d.id(),promotionInput(UUID.randomUUID(),"ARCHIVED",2)));
        var other=browseFixture("promo-retire-other",100,1);assertThrows(RuntimeException.class,()->api.save((UUID)other.get("owner"),d.id(),promotionInput(p.id(),"ARCHIVED",2)));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.promotion_event ADD CONSTRAINT p07_retire_fail CHECK(promotion_id<>'"+d.id()+"' OR version<3)");
        try{assertThrows(RuntimeException.class,()->api.save(owner,d.id(),promotionInput(p.id(),"ARCHIVED",2)));assertEquals(stopped,api.own(owner,d.id()));}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.promotion_event DROP CONSTRAINT p07_retire_fail");}
        assertEquals("ARCHIVED",api.save(owner,d.id(),promotionInput(p.id(),"ARCHIVED",2)).state());assertThrows(RuntimeException.class,()->api.published(d.id()));
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM marketplace.promotion_event WHERE promotion_id=:id",Map.of("id",d.id()),Integer.class));
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        var f2=browseFixture("promo-retire-race",100,1);UUID owner2=(UUID)f2.get("owner");var p2=products.own(owner2,((com.example.marketplace.catalog.api.Products.Product)f2.get("product")).id());
        var p3=products.save(owner2,null,new com.example.marketplace.catalog.api.Products.Input(p2.categoryId(),"promo-retire-mixed","다른 대상","","DRAFT",0L));
        var ids=List.of(p2.id(),p3.id());Instant start=Instant.now().minusSeconds(60),end=start.plusSeconds(3600);
        var mixed=api.save(owner2,null,new com.example.marketplace.promotion.api.Promotions.Input("복합","PRODUCT",ids,start,end,"FIXED",10L,null,"DRAFT",0L));
        api.save(owner2,mixed.id(),new com.example.marketplace.promotion.api.Promotions.Input("복합","PRODUCT",ids,start,end,"FIXED",10L,null,"ACTIVE",0L));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
                products.save(owner2,p2.id(),new com.example.marketplace.catalog.api.Products.Input(p2.categoryId(),p2.slug(),p2.title(),p2.description(),"ARCHIVED",p2.version()));
                locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}return true;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));var writer=pool.submit(()->api.save(owner2,mixed.id(),new com.example.marketplace.promotion.api.Promotions.Input("복합","PRODUCT",ids,start,end,"FIXED",10L,null,"STOPPED",1L)));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%')",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);}finally{release.countDown();}
            holder.get(5,TimeUnit.SECONDS);var retired=writer.get(5,TimeUnit.SECONDS);assertEquals("STOPPED",retired.state());assertEquals(2,retired.version());assertEquals(new java.util.HashSet<>(ids),new java.util.HashSet<>(retired.productIds()));
        }
    }


    @Test void promotionPublicationMatrixAndNumericContract() throws Exception {
        var f=browseFixture("promo-matrix",100,1);UUID owner=(UUID)f.get("owner");var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");var api=promotions();
        var store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(owner);var d=api.save(owner,null,promotionInput(product.id(),"DRAFT",0));var live=api.save(owner,d.id(),promotionInput(product.id(),"ACTIVE",0));
        String[] off={"UPDATE marketplace.seller_store SET visible=false WHERE id=:store","UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE id=:store","UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:owner","DELETE FROM marketplace.role_assignment WHERE user_id=:owner AND role='SELLER'","UPDATE marketplace.catalog_category SET active=false WHERE id=:category","UPDATE marketplace.catalog_product SET state='STOPPED' WHERE id=:product"};
        String[] on={"UPDATE marketplace.seller_store SET visible=true WHERE id=:store","UPDATE marketplace.seller_store SET state='OPEN',visible=true WHERE id=:store","UPDATE marketplace.identity_user SET state='ACTIVE' WHERE id=:owner","INSERT INTO marketplace.role_assignment VALUES(:owner,'SELLER')","UPDATE marketplace.catalog_category SET active=true WHERE id=:category","UPDATE marketplace.catalog_product SET state='ACTIVE' WHERE id=:product"};
        var params=Map.of("store",store.id(),"owner",owner,"category",product.categoryId(),"product",product.id());
        for(int i=0;i<off.length;i++){jdbc.update(off[i],params);assertTrue(api.candidates(store.id(),List.of(product.id()),Instant.now()).isEmpty(),off[i]);jdbc.update(on[i],params);assertEquals(live.id(),api.published(live.id()).id());}
        var second=context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,null,new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),"promo-matrix-second","숨김 대상","","DRAFT",0L));
        var multi=api.save(owner,live.id(),new com.example.marketplace.promotion.api.Promotions.Input("복합","PRODUCT",List.of(product.id(),second.id()),live.startsAt(),live.endsAt(),"FIXED",100L,null,"ACTIVE",1L));assertEquals(List.of(product.id()),api.published(multi.id()).productIds());
        var shipping=new com.example.marketplace.promotion.api.Promotions.Input("배송","SHIPPING",List.of(),live.startsAt(),live.endsAt(),"FREE",0L,null,"DRAFT",0L);
        var ship=api.save(owner,null,shipping);api.save(owner,ship.id(),new com.example.marketplace.promotion.api.Promotions.Input("배송","SHIPPING",List.of(),live.startsAt(),live.endsAt(),"FREE",0L,null,"ACTIVE",0L));assertEquals("SHIPPING",api.published(ship.id()).scope());assertTrue(api.candidates(store.id(),List.of(),live.endsAt()).isEmpty());
        for(Long cap:List.of(0L,1000L)){assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->api.save(owner,null,new com.example.marketplace.promotion.api.Promotions.Input("배송","SHIPPING",List.of(),live.startsAt(),live.endsAt(),"FREE",0L,cap,"DRAFT",0L)));}
        var browser=authBrowser();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("p4b-browse-promo-matrix@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());String csrf=csrfToken(browser);
        for(long[] pair:new long[][]{{9007199254740991L,1L},{9007199254740992L,1L},{9007199254740993L,1L},{1L,9007199254740991L},{1L,9007199254740992L},{1L,9007199254740993L}}) {
            long amount=pair[0],cap=pair[1];boolean allowed=amount<=9007199254740991L&&cap<=9007199254740991L;
            var input=new com.example.marketplace.promotion.api.Promotions.Input("경계","SHIPPING",List.of(),live.startsAt(),live.endsAt(),"FIXED",amount,cap,"DRAFT",0L);
            var response=accountRequest(browser,"POST","/api/seller/promotions",input,csrf);assertEquals(allowed?201:400,response.statusCode());
            if(response.statusCode()==201){var tree=authMapper.readTree(response.body());assertEquals(amount,tree.get("value").asLong());assertEquals(cap,tree.get("cap").asLong());}
        }
        var publicResult=accountRequest(authBrowser(),"GET","/api/events/"+live.id(),null,null);assertEquals("no-store",publicResult.headers().firstValue("Cache-Control").orElseThrow());
        var tree=authMapper.readTree(publicResult.body());assertEquals(java.util.Set.of("id","storeId","title","scope","productIds","startsAt","endsAt","kind","value","cap","state","version","storeSlug","storeName","targets"),authMapper.readValue(publicResult.body(),Map.class).keySet());assertFalse(publicResult.body().contains("actorId"));assertEquals(store.slug(),tree.get("storeSlug").asText());assertEquals(1,tree.get("targets").size());assertEquals(java.util.Set.of("id","slug","title"),authMapper.readValue(tree.get("targets").get(0).toString(),Map.class).keySet());
    }
    private com.example.marketplace.promotion.api.Promotions promotions() { return context.getBean(com.example.marketplace.promotion.api.Promotions.class); }
    private com.example.marketplace.promotion.api.Promotions.Input promotionInput(UUID product,String state,long version) {
        return new com.example.marketplace.promotion.api.Promotions.Input("행사", "PRODUCT",List.of(product),Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),"RATE",1000L,null,state,version);
    }
    @Test void promotionOwnershipVersionAndAudit() throws Exception {
        var f=browseFixture("promotion-own",10001,10);UUID owner=(UUID)f.get("owner"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();
        var other=browseFixture("promotion-other",100,1);UUID foreign=(UUID)other.get("owner");var api=promotions();
        assertThrows(RuntimeException.class,()->api.save(foreign,null,promotionInput(product,"DRAFT",0)));
        var draft=api.save(owner,null,promotionInput(product,"DRAFT",0));
        assertThrows(RuntimeException.class,()->api.own(foreign,draft.id()));
        var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> change=()->{start.await();try{api.save(owner,draft.id(),promotionInput(product,"ACTIVE",0));return true;}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return false;}};
            var a=pool.submit(change);var b=pool.submit(change);start.countDown();assertEquals(1,(a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0));
        }
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.promotion_event WHERE promotion_id=:id",Map.of("id",draft.id()),Integer.class));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.promotion_event ADD CONSTRAINT p07_a_fail CHECK(promotion_id<>'"+draft.id()+"' OR version<2)");
        try{var changed=new com.example.marketplace.promotion.api.Promotions.Input("changed","SHIPPING",List.of(),Instant.now(),Instant.now().plusSeconds(50),"FIXED",2L,null,"STOPPED",1L);
        assertThrows(RuntimeException.class,()->api.save(owner,draft.id(),changed));assertEquals(List.of(product),api.own(owner,draft.id()).productIds());assertEquals("행사",api.own(owner,draft.id()).title());assertEquals(1000,api.own(owner,draft.id()).value());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.promotion_event WHERE promotion_id=:id",Map.of("id",draft.id()),Integer.class));assertEquals("ACTIVE",api.own(owner,draft.id()).state());assertEquals(1,api.own(owner,draft.id()).version());}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.promotion_event DROP CONSTRAINT p07_a_fail");}
        api.save(owner,draft.id(),promotionInput(product,"ARCHIVED",1));assertThrows(RuntimeException.class,()->api.save(owner,draft.id(),promotionInput(product,"ACTIVE",2)));
    }
    @Test void promotionPublicationAndTime() throws Exception {
        var f=browseFixture("promotion-public",10001,10);UUID owner=(UUID)f.get("owner"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();var api=promotions();
        var draft=api.save(owner,null,promotionInput(product,"DRAFT",0));var live=api.save(owner,draft.id(),promotionInput(product,"ACTIVE",0));
        UUID store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(owner).id();
        assertTrue(api.candidates(store,List.of(product),live.startsAt()).stream().anyMatch(x->x.id().equals(live.id())));
        assertTrue(api.candidates(store,List.of(product),live.endsAt()).isEmpty());
        var anonymous=authBrowser();assertEquals(200,accountRequest(anonymous,"GET","/api/events/"+live.id(),null,null).statusCode());
        var member=authBrowser();assertEquals(200,authRequest(member,"/api/auth/login",loginForm("p4b-browse-promotion-public@example.com",SIGNUP_PASSWORD),csrfToken(member)).statusCode());
        assertEquals(accountRequest(anonymous,"GET","/api/events/"+live.id(),null,null).body(),accountRequest(member,"GET","/api/events/"+live.id(),null,null).body());
        assertEquals(403,accountRequest(member,"POST","/api/seller/promotions","{}",null).statusCode());
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",product));
        assertTrue(api.candidates(store,List.of(product),Instant.now()).isEmpty());assertEquals(404,accountRequest(anonymous,"GET","/api/events/"+live.id(),null,null).statusCode());
    }
    @Test void promotionAccessAfterLockWait() throws Exception {
        var f=browseFixture("promotion-lock",100,1);UUID owner=(UUID)f.get("owner"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();var api=promotions();
        var d=api.save(owner,null,promotionInput(product,"DRAFT",0));var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class);locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));return true;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));var writer=pool.submit(()->assertThrows(RuntimeException.class,()->api.save(owner,d.id(),promotionInput(product,"ACTIVE",0))));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%')",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);}finally{release.countDown();}
            holder.get(5,TimeUnit.SECONDS);writer.get(5,TimeUnit.SECONDS);assertEquals("DRAFT",jdbc.queryForObject("SELECT state FROM marketplace.promotion WHERE id=:id",Map.of("id",d.id()),String.class));
        }
    }

    @Test void guestRepurchaseRequiresBothCredentials() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);
        String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,next,"EXISTING",null));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink("wrong",next,"EXISTING",session));
        assertEquals("EXISTING",a.selectLink(f.browser,next,"EXISTING",session).mode());
        assertTrue(a.linkStatus(f.browser,next,session).ready());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,null,"E-NO-"+f.browser));
        assertEquals(buyer,a.attachOrder(f.browser,next,session,"E-YES-"+f.browser));
        assertEquals(2,a.orders(session).size());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,session,"E-DUP-"+f.browser));
    }
    @Test void guestNewPurchaseNeverMergesByPhone() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);
        UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",session);a.selectLink(f.browser,next,"NEW",session);
        assertFalse(a.linkStatus(f.browser,next,session).ready());
        a.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        UUID created=a.attachOrder(f.browser,next,session,"E-NEW-"+f.browser);assertFalse(buyer.equals(created));assertEquals(1,a.orders(session).size());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.order(session,"E-NEW-"+f.browser));
    }
    @Test void guestRepurchaseCannotOverwriteCredentials() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);recovery(f).initialCode(buyer);
        String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);var before=jdbc.queryForMap("SELECT * FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer));UUID next=guestRepeat(f);
        a.selectLink(f.browser,next,"EXISTING",session);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword("other password","other password")));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.createBuyer(f.browser,next));
        a.attachOrder(f.browser,next,session,"E-KEEP-"+f.browser);assertEquals(before,jdbc.queryForMap("SELECT * FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer)));
        var other=new GuestFixture();var otherAccess=guestAccess(other);UUID different=guestVerified(other);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->otherAccess.selectLink(other.browser,different,"EXISTING",session));
        assertEquals("NEW",otherAccess.linkStatus(other.browser,different,null).mode());
    }
    @Test void guestLinkPreparationIsBoundToCurrentAttempt() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;guestBuyer(f,a,old);String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);
        a.selectLink(f.browser,next,"EXISTING",session);a.logout(session);
        assertFalse(a.linkStatus(f.browser,next,session).ready());assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,session,"E-BAD-"+f.browser));
        String replacement=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);
        assertFalse(a.linkStatus(f.browser,next,replacement).ready());a.selectLink(f.browser,next,"EXISTING",replacement);
        recovery(f).manage(replacement,GUEST_PASSWORD,null,f.source);assertTrue(a.linkStatus(f.browser,next,replacement).ready());
        recovery(f).manage(replacement,GUEST_PASSWORD,new com.example.marketplace.guest.api.GuestPassword("changed password","changed password"),f.source);
        assertFalse(a.linkStatus(f.browser,next,replacement).ready());
        f.service.create(f.browser);assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,next,"NEW",null));
    }
    @Test void guestRepurchaseConsumptionIsAtomic() throws Exception {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",session);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->a.attachOrder(f.browser,next,session,old));
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());assertEquals(1,a.orders(session).size());
        assertThat(race(()->{try{a.attachOrder(f.browser,next,session,"E-RACE-"+UUID.randomUUID());return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        assertEquals(2,a.orders(session).size());assertEquals(buyer,jdbc.queryForObject("SELECT guest_buyer_id FROM marketplace.guest_order_reference WHERE order_number=:o",Map.of("o",old),UUID.class));
    }
    @Test void guestLinkRevisionAndSlowPasswordCannotRestoreOldChoice() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;guestBuyer(f,a,old);String token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);
        assertEquals(1,a.selectLink(f.browser,next,"EXISTING",token,0).revision());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,next,"NEW",token,0));
        a.selectLink(f.browser,next,"NEW",token,1);
        var real=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var slow=new com.example.marketplace.identity.api.PasswordHashes(){public boolean matches(String p,String h){return real.matches(p,h);}public String encode(String p){if(!p.equals("non-account-dummy-password")){a.selectLink(f.browser,next,"EXISTING",token);a.selectLink(f.browser,next,"NEW",token);}return real.encode(p);}};
        var racing=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,slow,f.clock,true);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->racing.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD)));
        assertFalse(a.linkStatus(f.browser,next,token).ready());assertEquals(4,a.linkStatus(f.browser,next,token).revision());
    }
    @Test void guestRepurchaseRechecksAccessAfterLockWait() throws Exception {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",token);
        jdbc.update("UPDATE marketplace.guest_access_session SET expires_at=:e,idle_expires_at=:e WHERE token_hash=:t",Map.of("e",java.sql.Timestamp.from(f.clock.instant().plusSeconds(2)),"t",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(token)));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM marketplace.guest_buyer WHERE id=:b FOR UPDATE",Map.of("b",buyer),UUID.class);locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));assertTrue(locked.await(5,TimeUnit.SECONDS));
            var consume=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,token,"E-EXPIRED-"+f.browser)));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_buyer%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);f.clock.advance(2);}finally{release.countDown();}
            holder.get(5,TimeUnit.SECONDS);assertEquals(401,consume.get(5,TimeUnit.SECONDS).status());
        }
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE order_number=:o",Map.of("o","E-EXPIRED-"+f.browser),Integer.class));
    }
    @Test void guestRepurchaseRechecksPurchaseAfterLockWait() throws Exception {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",token);
        jdbc.update("UPDATE marketplace.guest_attempt SET expires_at=:e WHERE id=:id",Map.of("e",java.sql.Timestamp.from(f.clock.instant().plusSeconds(2)),"id",next));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM marketplace.guest_buyer WHERE id=:b FOR UPDATE",Map.of("b",buyer),UUID.class);locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));assertTrue(locked.await(5,TimeUnit.SECONDS));
            var consume=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,token,"E-EXPIRED-"+f.browser)));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_buyer%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);f.clock.advance(2);}finally{release.countDown();}
            holder.get(5,TimeUnit.SECONDS);assertEquals(409,consume.get(5,TimeUnit.SECONDS).status());
        }
        assertEquals("EXPIRED",f.service.status(f.browser,f.token).state());assertEquals(null,jdbc.queryForObject("SELECT consumed_at FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",next),java.sql.Timestamp.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE order_number=:o",Map.of("o","E-EXPIRED-"+f.browser),Integer.class));
    }
    @Test void guestRepurchaseRacesPasswordChangeWithoutDeadlock() throws Exception {
        var f=new GuestFixture();var a=guestAccess(f);var r=recovery(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);r.initialCode(buyer);String token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",token);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var link=executor.submit(()->{start.await();try{a.attachOrder(f.browser,next,token,"E-R-"+f.browser);return true;}catch(com.example.marketplace.guest.api.GuestRejected e){return false;}});
            var change=executor.submit(()->{start.await();return r.manage(token,GUEST_PASSWORD,new com.example.marketplace.guest.api.GuestPassword("new valid password","new valid password"),f.source);});start.countDown();boolean linked=link.get(10,TimeUnit.SECONDS);assertEquals(43,change.get(10,TimeUnit.SECONDS).length());
            assertEquals(linked?2:1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE guest_buyer_id=:b",Map.of("b",buyer),Integer.class));
        }
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.orders(token));
    }
    @Test void guestLinkHttpCsrfAndDisabledBoundaries() throws Exception {
        var client=authBrowser();String csrf=csrfToken(client);var bad=Map.of("attemptId",UUID.randomUUID().toString(),"mode","EXISTING","revision",0);
        assertEquals(403,accountRequest(client,"POST","/api/guest/checkout/link",bad,null).statusCode());
        var rejected=accountRequest(client,"POST","/api/guest/checkout/link",bad,csrf);assertEquals(409,rejected.statusCode());assertEquals("no-store",rejected.headers().firstValue("Cache-Control").orElseThrow());
        var f=new GuestFixture();var disabled=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,new com.example.marketplace.identity.infrastructure.SharedPasswordHashes(),f.clock,false);
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.selectLink(f.browser,UUID.randomUUID(),"NEW",null)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.linkStatus(f.browser,UUID.randomUUID(),null)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.attachOrder(f.browser,UUID.randomUUID(),null,"NO")).status());
    }

    @Test void guestNewLinkFailureRollsBackBuyerAndConsumption() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;guestBuyer(f,a,old);UUID next=guestRepeat(f);a.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        int count=jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer",Map.of(),Integer.class);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->a.attachOrder(f.browser,next,null,old));
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer",Map.of(),Integer.class));assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());
        a.attachOrder(f.browser,next,null,"E-NEW-"+f.browser);assertEquals(count+1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer",Map.of(),Integer.class));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,null,"E-NEW2-"+f.browser));assertEquals(count+1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer",Map.of(),Integer.class));
    }
    @Test void guestLinkRejectsOtherBuyerAndRecoveryProofs() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String code=recovery(f).initialCode(buyer),token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",token);
        UUID other=UUID.randomUUID();jdbc.update("INSERT INTO marketplace.guest_buyer(id,buyer_name,verified_phone,password_hash,created_at) SELECT :b,buyer_name,verified_phone,password_hash,created_at FROM marketplace.guest_buyer WHERE id=:old",Map.of("b",other,"old",buyer));String otherOrder="E-OTHER-"+f.browser;a.registerOrder(other,otherOrder);
        String otherToken=a.login(otherOrder,f.phone,GUEST_PASSWORD,f.source,token);
        assertFalse(a.linkStatus(f.browser,next,otherToken).ready());assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,otherToken,"E-NO-"+f.browser));
        var r=recovery(f);UUID recoveryId=r.start(f.browser,old,f.phone,code,f.source).attemptId();r.exchange(f.browser,recoveryId,"123456");String grant=r.verify(f.browser,recoveryId,"valid-proof");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,recoveryId,"EXISTING",otherToken));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,next,"EXISTING",grant));
        a.selectLink(f.browser,next,"EXISTING",otherToken);assertEquals(other,a.attachOrder(f.browser,next,otherToken,"E-OTHER-NEW-"+f.browser));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE guest_buyer_id=:b",Map.of("b",buyer),Integer.class));
    }

    private UUID guestRepeat(GuestFixture f) { f.clock.advance(60);f.service.create(f.browser);return guestVerified(f); }

    @Test void guestRecoveryInitialLimitAndCredentialRacesAreSerialized() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);
        var code=new java.util.concurrent.atomic.AtomicReference<String>();
        assertThat(race(()->{try{code.set(r.initialCode(buyer));return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        for(int i=0;i<4;i++){assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,"wrong",UUID.randomUUID().toString()));}
        assertThat(race(()->{try{r.start(UUID.randomUUID().toString(),order,f.phone,code.get(),UUID.randomUUID().toString());return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return ""+e.status();}})).containsExactlyInAnyOrder("ok","429");
        var second=new GuestFixture();var a=guestAccess(second);var recovery=recovery(second);String o="D-"+second.browser;UUID b=guestBuyer(second,a,o);String c=recovery.initialCode(b),grant=resetGrant(second,recovery,o,c),session=a.login(o,second.phone,GUEST_PASSWORD,second.source,null);
        var password=new com.example.marketplace.guest.api.GuestPassword("racing password D","racing password D");var counter=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(race(()->{try{if(counter.getAndIncrement()==0){recovery.reset(second.browser,grant,password);}else{recovery.manage(session,GUEST_PASSWORD,password,second.source);}return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        assertEquals(2L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",b),Long.class));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.orders(session));
    }
    @Test void guestRecoveryCrossBuyerLoginAndVersionChangesCannotRestoreRevokedState() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer),grant=resetGrant(f,r,order,code),old=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        var other=new GuestFixture();String otherOrder="D-"+other.browser;guestBuyer(other,guestAccess(other),otherOrder);
        var password=new com.example.marketplace.guest.api.GuestPassword("cross buyer password","cross buyer password");var counter=new java.util.concurrent.atomic.AtomicInteger();var rotated=new java.util.concurrent.atomic.AtomicReference<String>();
        assertThat(race(()->{if(counter.getAndIncrement()==0){r.reset(f.browser,grant,password);}else{rotated.set(access.login(otherOrder,other.phone,GUEST_PASSWORD,f.source,old));}return "ok";})).containsOnly("ok");
        assertEquals(List.of(new com.example.marketplace.guest.infrastructure.JdbcGuestAccess.Order(otherOrder)),access.orders(rotated.get()));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(old));
        String current=access.login(order,f.phone,password.value(),f.source,null);var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var mutation=new com.example.marketplace.identity.api.PasswordHashes(){public String encode(String value){return actual.encode(value);}public boolean matches(String value,String hash){boolean result=actual.matches(value,hash);jdbc.update("UPDATE marketplace.guest_buyer SET password_version=password_version+1 WHERE id=:b",Map.of("b",buyer));return result;}};
        var service=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,mutation,f.provider,f.clock);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->service.manage(current,password.value(),new com.example.marketplace.guest.api.GuestPassword("must not persist password","must not persist password"),f.source));
        assertTrue(actual.matches(password.value(),jdbc.queryForObject("SELECT password_hash FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),String.class)));
    }

    @Test void guestRecoveryFinalWriteRollbackAndDifferentGrantsHaveOneWinner() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        String first=resetGrant(f,r,order,code);f.clock.advance(60);String second=resetGrant(f,r,order,code);String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        UUID id=jdbc.queryForObject("SELECT id FROM marketplace.guest_recovery_attempt WHERE grant_hash=:g",Map.of("g",com.example.marketplace.guest.api.RecoverySecrets.hash(first)),UUID.class);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_recovery_attempt ADD CONSTRAINT recovery_final_failure CHECK(id <> '"+id+"'::uuid OR state <> 'CONSUMED')");
        var password=new com.example.marketplace.guest.api.GuestPassword("final transaction password","final transaction password");
        assertThrows(DataIntegrityViolationException.class,()->r.reset(f.browser,first,password));
        assertEquals(1,access.orders(session).size());assertEquals(1L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),Long.class));
        assertTrue(com.example.marketplace.guest.api.RecoverySecrets.matches(code,jdbc.queryForObject("SELECT recovery_code_hash FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),String.class)));
        assertEquals("READY",jdbc.queryForObject("SELECT state FROM marketplace.guest_recovery_attempt WHERE id=:id",Map.of("id",id),String.class));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_recovery_attempt DROP CONSTRAINT recovery_final_failure");
        var counter=new java.util.concurrent.atomic.AtomicInteger();assertThat(race(()->{try{r.reset(f.browser,counter.getAndIncrement()==0?first:second,password);return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source));
    }
    @Test void guestRecoveryRechecksGrantAfterBuyerLockAndHashSideEffects() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer),grant=resetGrant(f,r,order,code);
        var password=new com.example.marketplace.guest.api.GuestPassword("lock password D","lock password D");
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM marketplace.guest_buyer WHERE id=:id FOR UPDATE",Map.of("id",buyer),UUID.class);locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));assertTrue(locked.await(5,TimeUnit.SECONDS));
            var reset=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.reset(f.browser,grant,password)));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_buyer%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);f.clock.advance(300);}finally{release.countDown();}holder.get(5,TimeUnit.SECONDS);reset.get(5,TimeUnit.SECONDS);
        }
        var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        var revoke=new com.example.marketplace.identity.api.PasswordHashes(){public String encode(String v){return actual.encode(v);}public boolean matches(String v,String h){boolean match=actual.matches(v,h);access.logout(session);return match;}};
        var during=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,revoke,f.provider,f.clock);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->during.manage(session,GUEST_PASSWORD,password,f.source));
        String fresh=access.login(order,f.phone,GUEST_PASSWORD,f.source,null),nextGrant=resetGrant(f,r,order,code);
        var rotate=new com.example.marketplace.identity.api.PasswordHashes(){public String encode(String v){r.manage(fresh,GUEST_PASSWORD,null,f.source);return actual.encode(v);}public boolean matches(String v,String h){return actual.matches(v,h);}};
        var changing=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,rotate,f.provider,f.clock);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->changing.reset(f.browser,nextGrant,password));
        assertEquals(1L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),Long.class));
    }
    @Test void guestRecoveryLostGrantRestartsWithoutReusingPurchaseProof() {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        UUID purchase=jdbc.queryForObject("SELECT id FROM marketplace.guest_attempt WHERE phone=:p",Map.of("p",f.input.phone()),UUID.class);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(f.browser,purchase,"123456"));
        String lost=resetGrant(f,r,order,code);assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());
        f.clock.advance(59);assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());f.clock.advance(1);
        String fresh=resetGrant(f,r,order,code);f.clock.advance(299);
        assertEquals(43,r.reset(f.browser,fresh,new com.example.marketplace.guest.api.GuestPassword("restored password D","restored password D")).length());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.reset(f.browser,lost,new com.example.marketplace.guest.api.GuestPassword("old grant password","old grant password")));
    }
    @Test void guestRecoveryHttpRejectsCsrfAndReturnsUniformPrivateErrors() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        var client=authBrowser();String csrf=csrfToken(client);
        for(String path:List.of("/api/guest/access/password","/api/guest/access/recovery-code","/api/guest/recovery/start","/api/guest/recovery/reset")){
            assertEquals(403,accountRequest(client,"POST",path,Map.of(),null).statusCode());
        }
        for(var input:List.of(Map.of("orderNumber","missing","phone",f.phone,"recoveryCode",code),Map.of("orderNumber",order,"phone","01000000000","recoveryCode",code),Map.of("orderNumber",order,"phone",f.phone,"recoveryCode","wrong"))){
            var response=accountRequest(client,"POST","/api/guest/recovery/start",input,csrf);assertEquals(401,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertEquals("AUTHENTICATION_REQUIRED",authMapper.readTree(response.body()).get("code").asText());assertFalse(response.body().contains(code));
        }
        assertEquals(401,accountRequest(client,"POST","/api/guest/access/recovery-code",Map.of("currentPassword",GUEST_PASSWORD),csrf).statusCode());
        accountRequest(client,"POST","/api/guest/access/login",Map.of("orderNumber",order,"phone",f.phone,"password",GUEST_PASSWORD),csrf);
        var result=accountRequest(client,"POST","/api/guest/access/recovery-code",Map.of("currentPassword",GUEST_PASSWORD),csrf);assertEquals(200,result.statusCode());assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());
        String rotated=authMapper.readTree(result.body()).get("recoveryCode").asText();assertFalse(rotated.equals(code));var listing=accountRequest(client,"GET","/api/guest/access/references",null,null);assertFalse(listing.body().contains(rotated));
    }

    @Test void guestRecoveryLimitsSurviveFailuresAndExpireAtWindowBoundary() {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        for(int i=0;i<5;i++){assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(UUID.randomUUID().toString(),order,f.phone,"wrong",UUID.randomUUID().toString())).status());}
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());f.clock.advance(899);
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());f.clock.advance(1);
        UUID id=r.start(f.browser,order,f.phone,code,f.source).attemptId();
        for(int i=0;i<5;i++){assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(f.browser,id,"000000"));}
        assertEquals(5,jdbc.queryForObject("SELECT failures FROM marketplace.guest_recovery_attempt WHERE id=:id",Map.of("id",id),Integer.class));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(f.browser,id,"123456"));
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());
        f.clock.advance(60);r.start(f.browser,order,f.phone,code,f.source);f.clock.advance(60);r.start(f.browser,order,f.phone,code,f.source);f.clock.advance(60);
        String alternate=order+"-x";access.registerOrder(buyer,alternate);
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,alternate,f.phone,code,f.source)).status());
        String source=UUID.randomUUID().toString();for(int i=0;i<20;i++){assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,UUID.randomUUID().toString(),f.phone,"wrong",source)).status());}
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,"missing",f.phone,"wrong",source)).status());f.clock.advance(899);
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,"missing",f.phone,"wrong",source)).status());f.clock.advance(1);
        assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,"missing",f.phone,"wrong",source)).status());
    }
    @Test void guestRecoveryRechecksExternalWorkExpiryVersionAndDisabledMode() {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        f.provider.issueHook=()->f.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source));f.provider.issueHook=()->{};
        UUID exchanged=r.start(f.browser,order,f.phone,code,f.source).attemptId();f.provider.exchangeHook=()->f.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(f.browser,exchanged,"123456"));f.provider.exchangeHook=()->{};
        UUID verified=r.start(f.browser,order,f.phone,code,f.source).attemptId();r.exchange(f.browser,verified,"123456");f.provider.hook=()->f.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.verify(f.browser,verified,"valid-proof"));f.provider.hook=()->{};f.clock.advance(900);
        String grant=resetGrant(f,r,order,code);var password=new com.example.marketplace.guest.api.GuestPassword("after expiry password","after expiry password");
        var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();var moving=new com.example.marketplace.identity.api.PasswordHashes(){
            public String encode(String value){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());f.clock.advance(300);return actual.encode(value);}
            public boolean matches(String value,String hash){return actual.matches(value,hash);}
        };
        var late=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,moving,f.provider,f.clock);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->late.reset(f.browser,grant,password));
        assertEquals(1L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),Long.class));
        String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        f.clock.advance(1500);assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->late.manage(session,GUEST_PASSWORD,password,f.source));
        var disabled=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,actual,null,f.clock);
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.initialCode(buyer)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.start(f.browser,order,f.phone,code,f.source)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.manage(session,GUEST_PASSWORD,password,f.source)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.reset(f.browser,grant,password)).status());
    }
    @Test void guestRecoveryLateFailureRollsBackAndSerializesWithLookup() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        String grant=resetGrant(f,r,order,code),session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);var password=new com.example.marketplace.guest.api.GuestPassword("transaction password D","transaction password D");
        String tokenHash=com.example.marketplace.guest.api.RecoverySecrets.hash(session);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_access_session ADD CONSTRAINT recovery_late_failure CHECK(token_hash <> '"+tokenHash+"' OR revoked_at IS NULL)");
        assertThrows(DataIntegrityViolationException.class,()->r.reset(f.browser,grant,password));
        assertEquals(1L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),Long.class));
        assertTrue(com.example.marketplace.guest.api.RecoverySecrets.matches(code,jdbc.queryForObject("SELECT recovery_code_hash FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),String.class)));
        assertEquals(1,access.orders(session).size());
        // Remove only this test's constraint in this newly isolated DB, then retry the still-unconsumed grant.
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_access_session DROP CONSTRAINT recovery_late_failure");
        var read=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var reader=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{access.orders(session);read.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));assertTrue(read.await(5,TimeUnit.SECONDS));
            var reset=executor.submit(()->r.reset(f.browser,grant,password));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_buyer%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);}finally{release.countDown();}
            reader.get(5,TimeUnit.SECONDS);assertEquals(43,reset.get(5,TimeUnit.SECONDS).length());
        }
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(session));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.reset(f.browser,grant,password));
    }

    private com.example.marketplace.guest.infrastructure.JdbcGuestRecovery recovery(GuestFixture f) {
        return new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,new com.example.marketplace.identity.infrastructure.SharedPasswordHashes(),f.provider,f.clock);
    }
    private String resetGrant(GuestFixture f,com.example.marketplace.guest.infrastructure.JdbcGuestRecovery recovery,String order,String code) {
        UUID id=recovery.start(f.browser,order,f.phone,code,f.source).attemptId();
        assertEquals("custom-proof",recovery.exchange(f.browser,id,"123456"));return recovery.verify(f.browser,id,"valid-proof");
    }
    @Test void guestRecoveryCodesChangePasswordsAndRevokeAllSessions() {
        var f=new GuestFixture();var access=guestAccess(f);var recovery=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);
        String code=recovery.initialCode(buyer);assertEquals(43,code.length());assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.initialCode(buyer));
        assertFalse(jdbc.queryForObject("SELECT recovery_code_hash FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),String.class).contains(code));
        String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null),other=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.manage(session,"wrong",null,f.source));
        String rotated=recovery.manage(session,GUEST_PASSWORD,null,f.source);assertFalse(rotated.equals(code));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.start(f.browser,order,f.phone,code,f.source));
        String next="new guest password D";String changed=recovery.manage(session,GUEST_PASSWORD,new com.example.marketplace.guest.api.GuestPassword(next,next),f.source);
        assertFalse(changed.equals(rotated));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(session));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(other));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session WHERE guest_buyer_id=:b AND revoked_at IS NOT NULL",Map.of("b",buyer),Integer.class));
        assertEquals(1,access.orders(access.login(order,f.phone,next,f.source,null)).size());
    }
    @Test void guestRecoveryRequiresEveryFactorAndConsumesGrantOnce() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var recovery=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=recovery.initialCode(buyer);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.start(f.browser,order,"01000000000",code,f.source));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.start(f.browser,"missing",f.phone,code,f.source));
        UUID id=recovery.start(f.browser,order,f.phone,code,f.source).attemptId();
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.exchange("other",id,"123456"));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.verify(f.browser,id,"valid-proof"));
        recovery.exchange(f.browser,id,"123456");String grant=recovery.verify(f.browser,id,"valid-proof");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.verify(f.browser,id,"valid-proof"));
        String next="reset guest password D";var password=new com.example.marketplace.guest.api.GuestPassword(next,next);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.reset("other",grant,password));
        String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        assertThat(race(()->{try{recovery.reset(f.browser,grant,password);return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(session));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(order,f.phone,GUEST_PASSWORD,f.source,null));
        assertEquals(1,access.orders(access.login(order,f.phone,next,f.source,null)).size());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_recovery_attempt WHERE grant_hash=:g OR recovery_hash=:g",Map.of("g",grant),Integer.class));
    }
    @Test void guestRecoveryResetRollbackExpiryAndRotationKeepBoundaries() {
        var f=new GuestFixture();var access=guestAccess(f);var recovery=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=recovery.initialCode(buyer);
        String grant=resetGrant(f,recovery,order,code),session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);var password=new com.example.marketplace.guest.api.GuestPassword("next password D","next password D");
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_buyer ADD CONSTRAINT recovery_rollback CHECK(id <> '"+buyer+"'::uuid OR password_version=1)");
        assertThrows(DataIntegrityViolationException.class,()->recovery.reset(f.browser,grant,password));assertEquals(1,access.orders(session).size());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_recovery_attempt WHERE buyer_id=:b AND state='CONSUMED'",Map.of("b",buyer),Integer.class));
        f.clock.advance(300);assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.reset(f.browser,grant,password));
        var second=new GuestFixture();var secondAccess=guestAccess(second);var r=recovery(second);String secondOrder="D-"+second.browser;UUID secondBuyer=guestBuyer(second,secondAccess,secondOrder);String secondCode=r.initialCode(secondBuyer);
        UUID id=r.start(second.browser,secondOrder,second.phone,secondCode,second.source).attemptId();second.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(second.browser,id,"123456"));
        String pending=resetGrant(second,r,secondOrder,secondCode);String s=secondAccess.login(secondOrder,second.phone,GUEST_PASSWORD,second.source,null);r.manage(s,GUEST_PASSWORD,null,second.source);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.reset(second.browser,pending,password));
    }

    @Test void guestSessionRotationFailureRollsBackAndLookupSerializesWithLogout() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);String order="C-"+f.browser;UUID buyer=guestBuyer(f,access,order);
        String old=access.login(order,f.phone,GUEST_PASSWORD,f.source,null),hash=com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(old);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_access_session ADD CONSTRAINT guest_c_rotation_failure CHECK (guest_buyer_id <> '"+buyer+"'::uuid OR token_hash='"+hash+"')");
        assertThrows(DataIntegrityViolationException.class,()->access.login(order,f.phone,GUEST_PASSWORD,f.source,old));
        assertEquals(1,access.orders(old).size());
        assertNull(jdbc.queryForObject("SELECT revoked_at FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",hash),java.sql.Timestamp.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session WHERE guest_buyer_id=:b",Map.of("b",buyer),Integer.class));
        var read=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var reader=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                assertEquals(1,access.orders(old).size());read.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
            }));assertTrue(read.await(5,TimeUnit.SECONDS));
            var logout=executor.submit(()->access.logout(old));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_access_session%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}
                assertTrue(waiting,"Logout must wait behind an authorized read transaction");
            }finally{release.countDown();}reader.get(5,TimeUnit.SECONDS);logout.get(5,TimeUnit.SECONDS);
        }
        assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(old)).status());
    }
    @Test void guestPasswordPreparationReplacementsCannotChangeConsumedBuyer() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);UUID id=guestVerified(f);
        var first=new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD);
        access.prepare(f.browser,id,first);access.prepare(f.browser,id,first);
        String replacement="replacement password C";
        access.prepare(f.browser,id,new com.example.marketplace.guest.api.GuestPassword(replacement,replacement));
        var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var hashing=new CountDownLatch(1);var release=new CountDownLatch(1);var active=new AtomicBoolean(false);
        var delayed=new com.example.marketplace.identity.api.PasswordHashes(){
            public String encode(String value){if(active.get()){hashing.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}return actual.encode(value);}
            public boolean matches(String value,String hash){return actual.matches(value,hash);}
        };
        var racing=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,delayed,f.clock,true);active.set(true);
        UUID buyer;
        try(var executor=Executors.newSingleThreadExecutor()){
            var writer=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->racing.prepare(f.browser,id,new com.example.marketplace.guest.api.GuestPassword("late password C","late password C"))));
            try{assertTrue(hashing.await(5,TimeUnit.SECONDS));buyer=access.createBuyer(f.browser,id);}finally{release.countDown();}
            assertEquals(409,writer.get(5,TimeUnit.SECONDS).status());
        }
        String saved=jdbc.queryForObject("SELECT password_hash FROM marketplace.guest_buyer WHERE id=:id",Map.of("id",buyer),String.class);
        assertTrue(actual.matches(replacement,saved));assertFalse(actual.matches(GUEST_PASSWORD,saved));assertFalse(actual.matches("late password C",saved));
        assertEquals(saved,jdbc.queryForObject("SELECT prepared_password_hash FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",id),String.class));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.prepare(f.browser,id,first));
    }

    @Test void guestLookupRechecksVersionAfterHashAndEnforcesLocalAndSecureBoundaries() {
        var f=new GuestFixture();var original=guestAccess(f);String order="C-"+f.browser;UUID buyer=guestBuyer(f,original,order);
        var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var changing=new com.example.marketplace.identity.api.PasswordHashes(){
            public String encode(String password){return actual.encode(password);}
            public boolean matches(String password,String hash){
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                boolean result=actual.matches(password,hash);jdbc.update("UPDATE marketplace.guest_buyer SET password_version=password_version+1 WHERE id=:id",Map.of("id",buyer));return result;
            }
        };
        var access=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,changing,f.clock,true);
        assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(order,f.phone,GUEST_PASSWORD,f.source,null)).status());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session WHERE guest_buyer_id=:b",Map.of("b",buyer),Integer.class));
        var disabled=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,actual,f.clock,false);
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.login(order,f.phone,GUEST_PASSWORD,f.source,null)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.orders("x".repeat(43))).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.prepare(f.browser,UUID.randomUUID(),new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD))).status());
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setRemoteAddr(UUID.randomUUID().toString());
        var controller=new com.example.marketplace.guest.web.GuestAccessController(original,true);
        var response=controller.login(new com.example.marketplace.guest.web.GuestAccessController.Login(order,f.phone,GUEST_PASSWORD),request,null);
        assertTrue(response.getHeaders().getFirst("Set-Cookie").contains("Secure"));
        assertFalse(response.getBody().toString().contains(GUEST_PASSWORD));
        try(var fixtureContext=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            fixtureContext.register(GuestOrderFixture.class,GuestFixtureSecurity.class);fixtureContext.refresh();
            assertTrue(fixtureContext.getBeansOfType(GuestOrderFixture.class).isEmpty());
            assertTrue(fixtureContext.getBeansOfType(GuestFixtureSecurity.class).isEmpty());
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("c3-fixture")
    static class GuestFixtureSecurity {
        @org.springframework.context.annotation.Bean @org.springframework.core.annotation.Order(0)
        org.springframework.security.web.SecurityFilterChain guestFixtureChain(org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.securityMatcher("/__test/guest-order","/__test/guest-recovery-order","/__test/guest-linked-order","/api/__test/shipments/**","/api/__test/purchase/**").csrf(csrf->csrf.csrfTokenRepository(new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository()))
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

    private static final String GUEST_PASSWORD="  guest lookup 密碼  ";
    private com.example.marketplace.guest.infrastructure.JdbcGuestAccess guestAccess(GuestFixture f) {
        return new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,new com.example.marketplace.identity.infrastructure.SharedPasswordHashes(),f.clock,true);
    }
    private UUID guestVerified(GuestFixture f) { UUID id=f.issue(); f.exchange(id); f.verify(id); return id; }
    private UUID guestBuyer(GuestFixture f,com.example.marketplace.guest.infrastructure.JdbcGuestAccess access,String order) {
        UUID id=guestVerified(f); access.prepare(f.browser,id,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        return new TransactionTemplate(transactionManager).execute(status->{UUID buyer=access.createBuyer(f.browser,id);access.registerOrder(buyer,order);return buyer;});
    }
    @Test void guestPasswordHashPreparationAndCreationAreBoundAtomicAndNeverMerge() throws Exception {
        var f=new GuestFixture(); var access=guestAccess(f); UUID id=f.issue();
        var password=new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.prepare(f.browser,id,password));
        f.exchange(id); f.verify(id);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.prepare("other",id,password));
        access.prepare(f.browser,id,password);
        String stored=jdbc.queryForObject("SELECT prepared_password_hash FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",id),String.class);
        var hasher=new com.example.marketplace.identity.infrastructure.ArgonPasswordHasher();
        assertTrue(stored.startsWith("{argon2id}")); assertFalse(stored.contains(GUEST_PASSWORD)); assertTrue(hasher.matches(GUEST_PASSWORD,stored));
        assertFalse(hasher.matches(GUEST_PASSWORD.strip(),stored)); assertFalse(hasher.matches(GUEST_PASSWORD+"x",stored)); assertFalse(stored.equals(hasher.encode(GUEST_PASSWORD)));
        assertTrue(hasher.matches("😀".repeat(128),hasher.encode("😀".repeat(128))));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{access.createBuyer(f.browser,id);throw new IllegalStateException("fixture rollback");}));
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer WHERE verified_phone=:p",Map.of("p",f.input.phone()),Integer.class));
        assertThat(race(()->{try{access.createBuyer(f.browser,id);return "ok";}catch(com.example.marketplace.guest.api.GuestRejected rejected){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        UUID first=jdbc.queryForObject("SELECT id FROM marketplace.guest_buyer WHERE verified_phone=:p",Map.of("p",f.input.phone()),UUID.class);
        access.registerOrder(first,"C-"+first);
        f.clock.advance(60); f.service.create(f.browser);
        UUID second=guestBuyer(f,access,"C-"+f.browser);
        assertFalse(first.equals(second));
        assertThrows(DataIntegrityViolationException.class,()->access.registerOrder(second,"C-"+first));
        String token=access.login("C-"+first,f.phone,GUEST_PASSWORD,f.source,null);
        assertEquals(List.of(new com.example.marketplace.guest.infrastructure.JdbcGuestAccess.Order("C-"+first)),access.orders(token));
        assertEquals(404,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.order(token,"C-"+f.browser)).status());
    }
    @Test void guestPasswordHashingRechecksExpiryOutsideTransactions() {
        var f=new GuestFixture(); UUID id=guestVerified(f); var move=new AtomicBoolean(false);
        var actual=new com.example.marketplace.identity.infrastructure.ArgonPasswordHasher();
        var hasher=new com.example.marketplace.identity.api.PasswordHashes(){
            public String encode(String password){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());if(move.get()){f.clock.advance(180);}return actual.encode(password);}
            public boolean matches(String password,String hash){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return actual.matches(password,hash);}
        };
        var access=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,hasher,f.clock,true); move.set(true);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.prepare(f.browser,id,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD)));
        assertNull(jdbc.queryForObject("SELECT prepared_password_hash FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",id),String.class));
    }
    @Test void guestLookupSessionsEnforceIdleMaximumVersionRotationAndLogout() {
        var f=new GuestFixture();var access=guestAccess(f);String order="C-"+f.browser;UUID buyer=guestBuyer(f,access,order);
        String token=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        assertEquals(43,token.length());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",token),Integer.class));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders("x".repeat(43)));
        f.clock.advance(1799);assertEquals(1,access.orders(token).size());f.clock.advance(1800);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(token));
        String maximum=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        for(int i=0;i<24;i++){f.clock.advance(1799);assertEquals(1,access.orders(maximum).size());}
        f.clock.advance(23);assertEquals(1,access.orders(maximum).size());f.clock.advance(1);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(maximum));
        String current=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        String rotated=access.login(order,f.phone,GUEST_PASSWORD,f.source,current);assertFalse(current.equals(rotated));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(current));
        jdbc.update("UPDATE marketplace.guest_buyer SET password_version=password_version+1 WHERE id=:id",Map.of("id",buyer));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(rotated));
        String logout=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);access.logout(logout);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(logout));
    }
    @Test void guestLookupLimitsAndCredentialFailuresAreUniformAndDurable() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);String order="C-"+f.browser;guestBuyer(f,access,order);
        for(int i=0;i<4;i++){assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(order,f.phone,"wrong",UUID.randomUUID().toString(),null)).status());}
        assertThat(race(()->{try{access.login(order,f.phone,GUEST_PASSWORD,UUID.randomUUID().toString(),null);return "ok";}catch(com.example.marketplace.guest.api.GuestRejected rejected){return ""+rejected.status();}})).containsExactlyInAnyOrder("ok","429");
        f.clock.advance(899);assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(order,f.phone,GUEST_PASSWORD,f.source,null)).status());
        f.clock.advance(1);assertEquals(1,access.orders(access.login(order,f.phone,GUEST_PASSWORD,f.source,null)).size());
        String source=UUID.randomUUID().toString();
        for(int i=0;i<20;i++){String missing="missing-"+UUID.randomUUID();assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(missing,f.phone,GUEST_PASSWORD,source,null)).status());}
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login("missing",f.phone,GUEST_PASSWORD,source,null)).status());
        f.clock.advance(899);assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login("missing",f.phone,GUEST_PASSWORD,source,null)).status());
        f.clock.advance(1);assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login("missing",f.phone,GUEST_PASSWORD,source,null)).status());
    }
    @Test void guestLookupLockWaitRechecksExpiryAndRevocation() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);String order="C-"+f.browser;guestBuyer(f,access,order);String token=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT token_hash FROM marketplace.guest_access_session WHERE token_hash=:t FOR UPDATE",Map.of("t",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(token)),String.class);
                locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
            }));assertTrue(locked.await(5,TimeUnit.SECONDS));
            var waiter=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(token)));
            try {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_access_session%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}
                assertTrue(waiting);f.clock.advance(1800);
            }finally{release.countDown();}holder.get(5,TimeUnit.SECONDS);waiter.get(5,TimeUnit.SECONDS);
        }
        String next=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        var outcomes=race(()->{access.logout(next);try{access.orders(next);return "unexpected";}catch(com.example.marketplace.guest.api.GuestRejected rejected){return "revoked";}});
        assertEquals(List.of("revoked","revoked"),outcomes);
    }
    @Test void guestLookupHttpChecksCsrfCookieUniformErrorsAndMemberSeparation() throws Exception {
        var f=new GuestFixture();var fixture=guestAccess(f);String order="C-"+f.browser;guestBuyer(f,fixture,order);
        var client=authBrowser();var input=Map.of("orderNumber",order,"phone",f.phone,"password",GUEST_PASSWORD);
        assertEquals(403,accountRequest(client,"POST","/api/guest/access/login",input,null).statusCode());String csrf=csrfToken(client);
        for(var bad:List.of(Map.of("orderNumber","missing","phone",f.phone,"password",GUEST_PASSWORD),Map.of("orderNumber",order,"phone","01000000000","password",GUEST_PASSWORD),Map.of("orderNumber",order,"phone",f.phone,"password","wrong"))){
            var result=accountRequest(client,"POST","/api/guest/access/login",bad,csrf);assertEquals(401,result.statusCode());assertEquals("AUTHENTICATION_REQUIRED",authMapper.readTree(result.body()).get("code").asText());assertFalse(result.body().contains(GUEST_PASSWORD));
        }
        var result=accountRequest(client,"POST","/api/guest/access/login",input,csrf);assertEquals(200,result.statusCode());
        String cookie=result.headers().firstValue("Set-Cookie").orElseThrow();assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Lax"));assertFalse(cookie.contains("Secure"));
        assertFalse(result.body().contains("token"));assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(200,accountRequest(client,"GET","/api/guest/access/references",null,null).statusCode());assertEquals(401,accountRequest(client,"GET","/api/account/profile",null,null).statusCode());
        assertEquals(403,accountRequest(client,"POST","/api/guest/access/logout",Map.of(),null).statusCode());
        assertEquals(200,accountRequest(client,"POST","/api/guest/access/logout",Map.of(),csrf).statusCode());assertEquals(401,accountRequest(client,"GET","/api/guest/access/references",null,null).statusCode());
    }

    @Test void guestProviderStagesWindowsAndRestartFailClosed() {
        var issuing=new GuestFixture(); issuing.provider.issueHook=()->issuing.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,issuing::issue);
        assertEquals("EXPIRED",issuing.service.status(issuing.browser,issuing.token).state());
        var exchanging=new GuestFixture(); UUID id=exchanging.issue(); exchanging.provider.exchangeHook=()->exchanging.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->exchanging.exchange(id));
        assertEquals("EXPIRED",exchanging.service.status(exchanging.browser,exchanging.token).state());
        var window=new GuestFixture(); window.issue(); window.clock.advance(60); window.issue(); window.clock.advance(60); window.issue();
        window.clock.advance(779); assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,window::issue).status());
        window.clock.advance(1); UUID reopened=window.issue(); window.exchange(reopened); window.verify(reopened);
        var failed=new GuestFixture(); UUID failing=failed.issue();
        for(int i=0;i<5;i++) { assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->failed.service.exchange(failed.browser,failed.token,failing,failed.phone,"PURCHASE","000000")); }
        failed.clock.advance(899); assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,failed::issue).status());
        failed.clock.advance(1); failed.exchange(failed.issue());
        var restart=new GuestFixture(); UUID previous=restart.issue(); restart.exchange(previous); restart.verify(previous);
        var restarted=new com.example.marketplace.guest.infrastructure.JdbcGuestPurchases(jdbc,transactionManager,restart.clock,restart.provider);
        String nextCredential=restarted.credential(restart.browser);
        assertFalse(nextCredential.equals(restart.token));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->restarted.status(restart.browser,nextCredential));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->restarted.status(restart.browser,restart.token));
        restarted.create(restart.browser);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->restart.service.status(restart.browser,restart.token));
        restart.clock.advance(60);
        UUID fresh=restarted.issue(restart.browser,nextCredential,restart.input,restart.source).attemptId();
        restarted.exchange(restart.browser,nextCredential,fresh,restart.phone,"PURCHASE","123456");
        assertEquals("VERIFIED",restarted.verify(restart.browser,nextCredential,fresh,restart.phone,"PURCHASE","valid-proof").state());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.guest_attempt SET purpose='RECOVERY' WHERE id=:id",Map.of("id",fresh)));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.guest_attempt SET provider='OTHER' WHERE id=:id",Map.of("id",fresh)));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.guest_attempt SET mode='production' WHERE id=:id",Map.of("id",fresh)));
    }

    private static final class GuestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-25T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    private static final class GuestProvider implements com.example.marketplace.phone.api.PhoneVerifications {
        private final GuestClock clock;
        private final java.util.concurrent.ConcurrentHashMap<String,String> phones = new java.util.concurrent.ConcurrentHashMap<>();
        private Runnable hook = () -> { };
        private Runnable issueHook = () -> { }, exchangeHook = () -> { };
        private boolean broken;
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
    private final class GuestFixture {
        final GuestClock clock = new GuestClock();
        final GuestProvider provider = new GuestProvider(clock);
        final com.example.marketplace.guest.infrastructure.JdbcGuestPurchases service = new com.example.marketplace.guest.infrastructure.JdbcGuestPurchases(jdbc,transactionManager,clock,provider);
        final String browser = UUID.randomUUID().toString(), source = UUID.randomUUID().toString();
        final String phone = String.format("010%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),100000000));
        final String token = service.create(browser);
        final com.example.marketplace.guest.api.GuestInput input = new com.example.marketplace.guest.api.GuestInput("가상 구매자",phone,"PURCHASE");
        UUID issue() { return service.issue(browser,token,input,source).attemptId(); }
        void exchange(UUID id) { assertEquals("custom-proof",service.exchange(browser,token,id,phone,"PURCHASE","123456")); }
        void verify(UUID id) { service.verify(browser,token,id,phone,"PURCHASE","valid-proof"); }
    }
    @Test void guestBindingReuseAndOneTimeConsumption() throws Exception {
        var f=new GuestFixture(); UUID id=f.issue();
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange("other-browser",f.token,id,f.phone,"PURCHASE","123456"));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange(f.browser,"tampered",id,f.phone,"PURCHASE","123456"));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange(f.browser,f.token,id,"01000000000","PURCHASE","123456"));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange(f.browser,f.token,id,f.phone,"RECOVERY","123456"));
        f.exchange(id); f.verify(id);
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.verify(id));
        var results=race(()-> { try { var value=f.service.consume(f.browser,f.token,id,f.phone,"PURCHASE"); assertEquals("가상 구매자",value.name()); return "ok"; }
            catch(com.example.marketplace.guest.api.GuestRejected rejected) { return "rejected"; } });
        assertThat(results).containsExactlyInAnyOrder("ok","rejected");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.status(f.browser,f.token));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_checkout WHERE token_hash=:token",Map.of("token",f.token),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_checkout WHERE token_hash=:token AND state='CONSUMED'",Map.of("token",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(f.token)),Integer.class));
    }
    @Test void guestConcurrentVerificationHasOneWinnerAndNoExternalTransaction() throws Exception {
        var f=new GuestFixture(); UUID id=f.issue(); f.exchange(id);
        assertThat(race(()-> { try { f.verify(id); return "ok"; } catch(com.example.marketplace.guest.api.GuestRejected rejected) { return "rejected"; } })).containsExactlyInAnyOrder("ok","rejected");
        String replacement=f.service.create(f.browser);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.consume(f.browser,f.token,id,f.phone,"PURCHASE"));
        assertEquals("OPEN",f.service.status(f.browser,replacement).state());
        assertThat(race(()->f.service.create(f.browser))).containsOnly(replacement);
        assertEquals("OPEN",f.service.status(f.browser,replacement).state());
    }
    @Test void guestDatabaseFailureLateProviderAndLostResponseRecoverWithoutFalseSuccess() {
        var f=new GuestFixture(); UUID id=f.issue(); f.exchange(id);
        // A deterministic database rejection after the provider accepted its proof.
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_attempt ADD CONSTRAINT guest_test_final_write CHECK (id <> '"+id+"'::uuid OR state <> 'VERIFIED')");
        assertThrows(DataIntegrityViolationException.class,()->f.verify(id));
        assertEquals("CONFIRMING",f.service.status(f.browser,f.token).state());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.consume(f.browser,f.token,id,f.phone,"PURCHASE"));
        f.clock.advance(60); UUID replacement=f.issue(); f.exchange(replacement); f.verify(replacement);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            f.service.consume(f.browser,f.token,replacement,f.phone,"PURCHASE"); throw new IllegalStateException("fixture rollback");
        }));
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());
        assertEquals("가상 구매자",f.service.consume(f.browser,f.token,replacement,f.phone,"PURCHASE").name());
        var late=new GuestFixture(); UUID stale=late.issue(); late.exchange(stale);
        late.provider.hook=()->late.service.create(late.browser);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->late.verify(stale));
        assertEquals("OPEN",late.service.status(late.browser,late.token).state());
        var lost=new GuestFixture(); UUID pending=lost.issue(); lost.exchange(pending);
        assertEquals("EXCHANGED",lost.service.status(lost.browser,lost.token).state());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->lost.exchange(pending));
        lost.clock.advance(60); UUID fresh=lost.issue(); lost.exchange(fresh); lost.verify(fresh);
        // The caller lost the successful response: status recovers it; replay still fails.
        assertEquals("VERIFIED",lost.service.status(lost.browser,lost.token).state());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->lost.verify(fresh));
        String stored=jdbc.queryForObject("SELECT row_to_json(a)::text FROM marketplace.guest_attempt a WHERE id=:id",Map.of("id",fresh),String.class);
        assertFalse(stored.contains("valid-proof")); assertFalse(stored.contains("custom-proof")); assertFalse(stored.contains("private-session"));
        var disabled=new com.example.marketplace.guest.infrastructure.JdbcGuestPurchases(jdbc,transactionManager,lost.clock,null);
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.status(lost.browser,lost.token)).status());
    }
    @Test void guestBusinessExpiryBeforeAndAfterProviderAndSessionExpiry() {
        var before=new GuestFixture(); UUID good=before.issue(); before.exchange(good); before.clock.advance(179); before.verify(good);
        var exact=new GuestFixture(); UUID expired=exact.issue(); exact.exchange(expired); exact.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->exact.verify(expired));
        assertEquals("EXPIRED",exact.service.status(exact.browser,exact.token).state());
        var during=new GuestFixture(); UUID id=during.issue(); during.exchange(id); during.provider.hook=()->during.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->during.verify(id));
        assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM marketplace.guest_checkout WHERE current_attempt=:id",Map.of("id",id),String.class));
        during.clock.advance(1620);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->during.service.status(during.browser,during.token));
    }
    @Test void guestExpiryIsRecheckedAfterDatabaseLockWait() throws Exception {
        var f=new GuestFixture(); UUID id=f.issue(); f.exchange(id);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.guest_checkout WHERE current_attempt=:id FOR UPDATE",Map.of("id",id),UUID.class);
                locked.countDown(); try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var waiter=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.verify(id)));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_checkout%'",Map.of(),Boolean.class);
                    if(waiting) { break; } Thread.sleep(20);
                }
                assertTrue(waiting,"Must actually wait for database lock before advancing clock"); f.clock.advance(180);
            } finally { release.countDown(); }
            holder.get(5,TimeUnit.SECONDS); waiter.get(5,TimeUnit.SECONDS);
        }
        assertEquals("EXCHANGED",jdbc.queryForObject("SELECT state FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",id),String.class));
    }
    @Test void guestResendFailureAndSourceLimitsPersistAcrossSessions() {
        var f=new GuestFixture(); UUID old=f.issue();
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,f::issue).status());
        f.clock.advance(60); UUID current=f.issue();
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.exchange(old));
        for(int i=0;i<5;i++) { assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange(f.browser,f.token,current,f.phone,"PURCHASE","999999")); }
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.exchange(current));
        f.clock.advance(60);
        String replacement=f.service.create(f.browser);
        var normalized=new com.example.marketplace.guest.api.GuestInput("가상",f.input.phone(),"PURCHASE");
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.issue(f.browser,replacement,normalized,f.source)).status());
        var outage=new GuestFixture(); outage.provider.broken=true;
        for(int i=0;i<3;i++) { assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,outage::issue).status()); outage.clock.advance(60); }
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,outage::issue).status());
        String source=UUID.randomUUID().toString();
        for(int i=0;i<20;i++) { var next=new GuestFixture(); next.service.issue(next.browser,next.token,next.input,source); }
        var blocked=new GuestFixture();
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->blocked.service.issue(blocked.browser,blocked.token,blocked.input,source)).status());
        blocked.clock.advance(899);
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->blocked.service.issue(blocked.browser,blocked.token,blocked.input,source)).status());
        blocked.clock.advance(1); assertEquals("SENT",blocked.service.issue(blocked.browser,blocked.token,blocked.input,source).state());
    }
    @Test void guestHttpRequiresCsrfAndBrowserBindingWithoutGrantingAccountAccess() throws Exception {
        var first=authBrowser(); var second=authBrowser();
        assertEquals(403,accountRequest(first,"POST","/api/guest/checkout",Map.of(),null).statusCode());
        String csrf=csrfToken(first);
        var created=accountRequest(first,"POST","/api/guest/checkout",Map.of(),csrf);
        assertEquals(200,created.statusCode()); assertEquals("no-store",created.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(created.body().contains("token"));
        assertEquals(409,accountRequest(second,"GET","/api/guest/checkout",null,null).statusCode());
        assertEquals(400,accountRequest(first,"POST","/api/guest/verifications",Map.of("name","", "phone","01012345678","purpose","PURCHASE"),csrf).statusCode());
        assertEquals(400,accountRequest(first,"POST","/api/guest/verifications",Map.of("name","가상", "phone","01012345678","purpose","RECOVERY"),csrf).statusCode());
        assertEquals(401,accountRequest(first,"GET","/api/account/profile",null,null).statusCode());
        assertEquals(401,accountRequest(first,"POST","/api/guest/lookup",Map.of(),csrf).statusCode());
    }

    private com.example.marketplace.cart.api.Carts carts() {
        assertEquals(1,context.getBeansOfType(com.example.marketplace.cart.api.Carts.class).size(),"Cart implementation required");
        return context.getBean(com.example.marketplace.cart.api.Carts.class);
    }
    @Test void wishlistOwnershipVisibilityAndIdempotency() throws Exception {
        var f=browseFixture("wish-http",1700,5);
        var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID owner=activeMember("wish-owner@example.com"),other=activeMember("wish-other@example.com");
        var browser=authBrowser();
        assertEquals(401,accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).statusCode());
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("wish-owner@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        String csrf=csrfToken(browser),path="/api/wishlist/"+product.slug()+"?owner="+owner;
        assertEquals(200,accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).statusCode());
        assertEquals(403,accountRequest(browser,"PUT",path,Map.of(),null).statusCode());
        assertEquals(409,accountRequest(browser,"GET","/api/wishlist?owner="+other,null,null).statusCode());
        assertEquals(409,accountRequest(browser,"PUT","/api/wishlist/"+product.slug()+"?owner="+other,Map.of(),csrf).statusCode());
        for(int i=0;i<2;i++) { assertEquals(200,accountRequest(browser,"PUT",path,Map.of(),csrf).statusCode()); }
        var list=authMapper.readTree(accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).body());
        assertEquals(1,list.get("items").size());assertEquals(product.id().toString(),list.get("items").get(0).get("productId").asText());
        assertEquals("P05 wish-http",list.get("items").get(0).get("detail").get("product").get("title").asText());
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",product.id()));
        list=authMapper.readTree(accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).body());
        assertTrue(list.get("items").get(0).get("detail").isNull());
        assertEquals(404,accountRequest(browser,"PUT",path,Map.of(),csrf).statusCode());
        assertEquals(409,accountRequest(browser,"DELETE","/api/wishlist/"+product.id()+"?owner="+other,null,csrf).statusCode());
        for(int i=0;i<2;i++) { assertEquals(200,accountRequest(browser,"DELETE","/api/wishlist/"+product.id()+"?owner="+owner,null,csrf).statusCode()); }
        assertEquals(0,authMapper.readTree(accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).body()).get("items").size());
        stockEquals((UUID)f.get("sku"),5,0,0);
    }
    @Test void wishlistCapacityConcurrencyAndSuspension() throws Exception {
        var api=context.getBean(com.example.marketplace.cart.api.Wishlists.class);
        UUID owner=activeMember("wish-race@example.com");var f=browseFixture("wish-race",100,5);
        var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        assertEquals(List.of("ok","ok"),race(()->{api.add(owner,product.slug());return "ok";}));
        assertEquals(1,api.get(owner).items().size());
        for(int i=0;i<99;i++) { jdbc.update("INSERT INTO marketplace.wishlist(user_id,product_id) VALUES(:owner,:id)",Map.of("owner",owner,"id",UUID.randomUUID())); }
        assertEquals(100,api.add(owner,product.slug()).items().size());
        var second=(com.example.marketplace.catalog.api.Products.Product)browseFixture("wish-over",100,5).get("product");
        assertEquals(409,assertThrows(com.example.marketplace.cart.api.CartException.class,()->api.add(owner,second.slug())).status());
        assertEquals(100,api.get(owner).items().size());
        UUID capOwner=activeMember("wish-cap-race@example.com");
        for(int i=0;i<99;i++) { jdbc.update("INSERT INTO marketplace.wishlist(user_id,product_id) VALUES(:owner,:id)",Map.of("owner",capOwner,"id",UUID.randomUUID())); }
        var index=new java.util.concurrent.atomic.AtomicInteger();
        var accepted=new java.util.concurrent.ConcurrentLinkedQueue<UUID>();
        var rejected=new java.util.concurrent.ConcurrentLinkedQueue<UUID>();
        var contenders=List.of(product,second);
        var capResults=race(()->{
            var candidate=contenders.get(index.getAndIncrement());
            try { api.add(capOwner,candidate.slug());accepted.add(candidate.id());return "ok"; }
            catch(com.example.marketplace.cart.api.CartException e) { rejected.add(candidate.id());return ""+e.status(); }
        });
        assertEquals(1,capResults.stream().filter("ok"::equals).count());
        assertEquals(1,capResults.stream().filter("409"::equals).count());
        var capIds=api.get(capOwner).items().stream().map(com.example.marketplace.cart.api.Wishlists.Line::productId).toList();
        assertEquals(100,capIds.size());assertTrue(capIds.containsAll(accepted));
        assertFalse(capIds.contains(rejected.element()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.wishlist(user_id,product_id) VALUES(:owner,:id)",Map.of("owner",owner,"id",product.id())));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class);
                future.set(executor.submit(()->{try{api.remove(owner,product.id());return 200;}catch(com.example.marketplace.identity.api.AccessRejected e){return 403;}}));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%'))",Map.of(),Boolean.class));
                    if(waiting) { break; }
                    try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertTrue(waiting,"Wishlist removal must wait on member row");
                jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
            });
            assertEquals(403,future.get().get(10,TimeUnit.SECONDS));
        }
        assertEquals(100,jdbc.queryForObject("SELECT count(*) FROM marketplace.wishlist WHERE user_id=:id",Map.of("id",owner),Integer.class));
    }
    @Test void cartOwnershipCrudAndPublicAvailability() throws Exception {
        var f=browseFixture("cart-http",1700,5);UUID sku=(UUID)f.get("sku");var p=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID owner=activeMember("cart-owner@example.com"),other=activeMember("cart-other@example.com");var browser=authBrowser();
        assertEquals(401,accountRequest(browser,"GET","/api/cart",null,null).statusCode());
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("cart-owner@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());String csrf=csrfToken(browser);
        assertEquals(200,accountRequest(browser,"GET","/api/cart?owner="+owner,null,null).statusCode());
        assertEquals(409,accountRequest(browser,"GET","/api/cart?owner="+other,null,null).statusCode());
        var body=Map.of("owner",owner,"quantity",2,"version",0);
        assertEquals(403,accountRequest(browser,"PUT","/api/cart/items/"+sku,body,null).statusCode());
        assertEquals(409,accountRequest(browser,"PUT","/api/cart/items/"+sku,Map.of("owner",other,"quantity",2,"version",0),csrf).statusCode());
        var saved=accountRequest(browser,"PUT","/api/cart/items/"+sku,body,csrf);assertEquals(200,saved.statusCode(),saved.body());
        var api=carts();assertEquals(2,api.get(owner).items().getFirst().quantity());assertTrue(api.get(other).items().isEmpty());
        assertEquals(409,accountRequest(browser,"PUT","/api/cart/items/"+sku,body,csrf).statusCode());
        for(Object q:List.of(-1,100,1.5)) { assertEquals(400,accountRequest(browser,"PUT","/api/cart/items/"+sku,Map.of("owner",owner,"quantity",q,"version",1),csrf).statusCode()); }
        jdbc.update("UPDATE marketplace.catalog_sku SET price=2200 WHERE id=:id",Map.of("id",sku));
        assertEquals(2200,api.get(owner).items().getFirst().detail().offers().getFirst().price());
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",p.id()));
        assertNull(api.get(owner).items().getFirst().detail());
        assertEquals(400,accountRequest(browser,"PUT","/api/cart/items/"+sku,Map.of("owner",owner,"quantity",2,"version",1.5),csrf).statusCode());
        for(String path:List.of("/api/cart/preview","/api/cart/merge")) {
            assertEquals(400,accountRequest(browser,"POST",path,Map.of("owner",owner,"requestId",UUID.randomUUID(),"items",List.of(Map.of("skuId",sku,"quantity",1.5))),csrf).statusCode());
        }
        var anon=authBrowser();assertEquals(200,accountRequest(anon,"POST","/api/cart/preview",Map.of("items",List.of(Map.of("skuId",sku,"quantity",1))),csrfToken(anon)).statusCode());
        assertEquals(403,accountRequest(anon,"POST","/api/cart/preview",Map.of("items",List.of()),null).statusCode());
        assertEquals(200,accountRequest(browser,"PUT","/api/cart/items/"+sku,Map.of("owner",owner,"quantity",0,"version",1),csrf).statusCode());
        assertTrue(api.get(owner).items().isEmpty());stockEquals(sku,5,0,0);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE sku_id=:id",Map.of("id",sku),Integer.class));
    }
    @Test void cartMergeReplayAndConcurrentUpdates() throws Exception {
        var api=carts();UUID owner=activeMember("cart-merge@example.com"),other=activeMember("cart-merge-other@example.com");var f=browseFixture("cart-merge",100,10);UUID sku=(UUID)f.get("sku");
        var first=api.set(owner,sku,70,0);UUID request=UUID.randomUUID();var input=List.of(new com.example.marketplace.cart.api.Carts.Input(sku,60));
        assertEquals(List.of("ok","ok"),race(()->{api.merge(owner,request,input);return "ok";}));
        var merged=api.get(owner);assertEquals(99,merged.items().getFirst().quantity());assertEquals(first.version()+1,merged.version());
        api.merge(owner,request,input);assertEquals(merged.version(),api.get(owner).version());
        assertEquals(409,assertThrows(com.example.marketplace.cart.api.CartException.class,()->api.merge(other,request,input)).status());
        assertEquals(409,assertThrows(com.example.marketplace.cart.api.CartException.class,()->api.merge(owner,request,List.of(new com.example.marketplace.cart.api.Carts.Input(sku,1)))).status());
        var results=race(()->{try{api.set(owner,sku,4,merged.version());return "ok";}catch(com.example.marketplace.cart.api.CartException e){return ""+e.status();}});
        assertEquals(1,results.stream().filter("ok"::equals).count());assertEquals(1,results.stream().filter("409"::equals).count());
        var current=api.get(owner);api.clear(owner,current.version());api.merge(owner,request,input);assertTrue(api.get(owner).items().isEmpty());
    }
    @Test void cartCapacityRollbackAndSuspension() throws Exception {
        var api=carts();UUID owner=activeMember("cart-capacity@example.com");
        var items=java.util.stream.IntStream.range(0,100).mapToObj(i->new com.example.marketplace.cart.api.Carts.Input(UUID.randomUUID(),1)).toList();
        api.merge(owner,UUID.randomUUID(),items);UUID failed=UUID.randomUUID();
        assertEquals(409,assertThrows(com.example.marketplace.cart.api.CartException.class,()->api.merge(owner,failed,List.of(new com.example.marketplace.cart.api.Carts.Input(UUID.randomUUID(),1)))).status());
        assertEquals(100,api.get(owner).items().size());assertEquals(1,api.get(owner).version());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.cart_merge WHERE request_id=:id",Map.of("id",failed),Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.cart_item SET quantity=100 WHERE user_id=:id",Map.of("id",owner)));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class);
                future.set(executor.submit(()->{try{api.clear(owner,1);return 200;}catch(com.example.marketplace.identity.api.AccessRejected rejected){return 403;}}));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%'))",Map.of(),Boolean.class));
                    if(waiting) {break;}
                    try{Thread.sleep(20);}catch(InterruptedException e){throw new IllegalStateException(e);}
                }
                assertTrue(waiting,"Cart mutation must wait on member row");
                jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
            });
            assertEquals(403,future.get().get(10,TimeUnit.SECONDS));
        }
        assertEquals(100,jdbc.queryForObject("SELECT count(*) FROM marketplace.cart_item WHERE user_id=:id",Map.of("id",owner),Integer.class));
    }
    private Map<String,Object> browseFixture(String suffix,long price,long stock) {
        var f=skuFixture("browse-"+suffix);UUID owner=(UUID)f.get("owner");var p=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,p.id(),0).items().getFirst().id();
        jdbc.update("UPDATE marketplace.catalog_product SET state='ACTIVE',title=:title WHERE id=:id",Map.of("id",p.id(),"title","P05 "+suffix));
        jdbc.update("UPDATE marketplace.catalog_sku SET active=true,price=:price WHERE id=:id",Map.of("id",sku,"price",price));
        jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand) VALUES(:sku,:stock)",Map.of("sku",sku,"stock",stock));
        return Map.of("owner",owner,"product",p,"sku",sku);
    }
    @Test
    void browseSearchFiltersSortAndPages() throws Exception {
        var f=browseFixture("pages",100,10);var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");UUID owner=(UUID)f.get("owner");
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);
        for(int i=1;i<=21;i++) {
            var p=products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),"p05-page-"+i,"P05 pages "+i,"page fixture","DRAFT",0L));
            products.save(owner,p.id(),new com.example.marketplace.catalog.api.Products.Input(p.categoryId(),p.slug(),p.title(),p.description(),"ACTIVE",0L));
            var sku=skus.list(owner,p.id(),0).items().getFirst();skus.save(owner,p.id(),sku.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),100L,true,0L));
        }
        var client=authBrowser();String base="/api/browse/products?q=P05+pages&sort=price_asc";
        var response=accountRequest(client,"GET",base,null,null);assertEquals(200,response.statusCode(),response.body());
        var first=authMapper.readTree(response.body());var next=authMapper.readTree(accountRequest(client,"GET",base+"&page=1",null,null).body());
        assertEquals(20,first.get("items").size());assertTrue(first.get("hasMore").asBoolean());assertEquals(2,next.get("items").size());assertFalse(next.get("hasMore").asBoolean());
        var slugs=new java.util.HashSet<String>();first.get("items").forEach(v->assertTrue(slugs.add(v.get("slug").asText())));next.get("items").forEach(v->assertTrue(slugs.add(v.get("slug").asText())));
        assertEquals(response.body(),accountRequest(client,"GET",base,null,null).body());
        String store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(owner).slug();
        var filtered=authMapper.readTree(accountRequest(client,"GET",base+"&category="+product.categoryId()+"&store="+store+"&inStock=true&minPrice=100&maxPrice=100",null,null).body());
        assertEquals(1,filtered.get("items").size());assertEquals(product.slug(),filtered.get("items").get(0).get("slug").asText());
        assertEquals(0,authMapper.readTree(accountRequest(client,"GET",base+"&minPrice=101",null,null).body()).get("items").size());
        jdbc.update("UPDATE marketplace.catalog_sku SET price=200 WHERE id=:id",Map.of("id",f.get("sku")));
        jdbc.update("UPDATE marketplace.catalog_product SET created_at='2099-01-01T00:00:00Z',title='P05 pages 000' WHERE id=:id",Map.of("id",product.id()));
        for(String sort:List.of("price_desc","newest","name")) {
            var sorted=authMapper.readTree(accountRequest(client,"GET","/api/browse/products?q=P05+pages&sort="+sort,null,null).body());
            assertEquals(product.slug(),sorted.get("items").get(0).get("slug").asText(),sort);
        }
        assertFalse(authMapper.readTree(accountRequest(client,"GET",base,null,null).body()).get("items").get(0).get("slug").asText().equals(product.slug()));
        jdbc.update("UPDATE marketplace.catalog_product SET title='P05 literal %_!' WHERE id=:id",Map.of("id",product.id()));
        assertEquals(1,authMapper.readTree(accountRequest(client,"GET","/api/browse/products?q=%25_%21",null,null).body()).get("items").size());
    }
    @Test
    void browseVisibilityAndDetailAvailability() throws Exception {
        var f=browseFixture("visibility",1700,10);var p=(com.example.marketplace.catalog.api.Products.Product)f.get("product");UUID owner=(UUID)f.get("owner"),sku=(UUID)f.get("sku");
        jdbc.update("UPDATE marketplace.inventory_balance SET reserved=4 WHERE sku_id=:id",Map.of("id",sku));
        var client=authBrowser();String path="/api/browse/products/"+p.slug();var response=accountRequest(client,"GET",path,null,null);assertEquals(200,response.statusCode(),response.body());
        var detail=authMapper.readTree(response.body());assertEquals(1700,detail.get("product").get("minPrice").asLong());assertFalse(detail.get("product").get("soldOut").asBoolean());assertEquals(6,detail.get("offers").get(0).get("available").asLong());
        String[] hide={"UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id","UPDATE marketplace.catalog_product SET state='STOPPED' WHERE id=:id","UPDATE marketplace.catalog_category SET active=false WHERE id=:category","UPDATE marketplace.seller_store SET visible=false WHERE owner_id=:owner","UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE owner_id=:owner","UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:owner","DELETE FROM marketplace.role_assignment WHERE user_id=:owner AND role='SELLER'"};
        for(String sql:hide) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.update(sql,Map.of("id",p.id(),"category",p.categoryId(),"owner",owner));
                var api=context.getBean(com.example.marketplace.catalog.api.Browse.class);
                assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->api.detail(p.slug())).status());
                assertTrue(api.search(new com.example.marketplace.catalog.api.Browse.Query("P05 visibility",null,null,null,null,false,"newest",0)).items().isEmpty());status.setRollbackOnly();
            });
        }
        jdbc.update("UPDATE marketplace.inventory_balance SET reserved=on_hand WHERE sku_id=:id",Map.of("id",sku));
        assertTrue(authMapper.readTree(accountRequest(client,"GET",path,null,null).body()).get("product").get("soldOut").asBoolean());
        jdbc.update("UPDATE marketplace.catalog_sku SET active=false WHERE id=:id",Map.of("id",sku));
        detail=authMapper.readTree(accountRequest(client,"GET",path,null,null).body());assertTrue(detail.get("product").get("minPrice").isNull());assertEquals(0,detail.get("offers").size());
    }
    @Test
    void browseRejectsInvalidQueriesAndIsReadOnly() throws Exception {
        var f=browseFixture("invalid",100,2);var client=authBrowser();
        for(String query:List.of("page=-1","page=10001","page=abc","minPrice=-1","maxPrice=1000000000","minPrice=20&maxPrice=10","sort=random","category=no","store=BAD","q=%00","q="+"a".repeat(81),"inStock=maybe")) {
            assertEquals(400,accountRequest(client,"GET","/api/browse/products?"+query,null,null).statusCode(),query);
        }
        assertEquals(404,accountRequest(client,"GET","/api/browse/products/does-not-exist",null,null).statusCode());
        assertEquals(403,accountRequest(client,"POST","/api/browse/products",Map.of(),null).statusCode());
        stockEquals((UUID)f.get("sku"),2,0,0);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE sku_id=:id",Map.of("id",f.get("sku")),Integer.class));
    }
    @Test
    void browseDistinctFiltersSortAndOptionPrices() {
        var a=browseFixture("contract-a",1,0);var b=browseFixture("contract-z",300,0);var c=browseFixture("contract-m",1,0);
        var pa=(com.example.marketplace.catalog.api.Products.Product)a.get("product");var pb=(com.example.marketplace.catalog.api.Products.Product)b.get("product");var pc=(com.example.marketplace.catalog.api.Products.Product)c.get("product");
        jdbc.update("UPDATE marketplace.catalog_sku SET active=false WHERE id IN (:a,:c)",Map.of("a",a.get("sku"),"c",c.get("sku")));
        UUID cheap=UUID.randomUUID(),stocked=UUID.randomUUID();
        for(UUID id:List.of(cheap,stocked)) {
            jdbc.update("INSERT INTO marketplace.catalog_sku(id,product_id,code,options,price,active) VALUES(:id,:product,:code,CAST(:options AS jsonb),:price,true)",Map.of("id",id,"product",pa.id(),"code","sku-"+id,"options","[{\"name\":\"색\",\"value\":\""+(id.equals(cheap)?"파랑":"빨강")+"\"}]","price",id.equals(cheap)?100:500));
            jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand,reserved) VALUES(:id,:stock,:reserved)",Map.of("id",id,"stock",id.equals(cheap)?0:5,"reserved",id.equals(cheap)?0:2));
        }
        jdbc.update("UPDATE marketplace.catalog_product SET created_at=CAST(:time AS timestamptz) WHERE id=:id",Map.of("id",pa.id(),"time","2020-01-01T00:00:00Z"));
        jdbc.update("UPDATE marketplace.catalog_product SET created_at=CAST(:time AS timestamptz) WHERE id=:id",Map.of("id",pb.id(),"time","2022-01-01T00:00:00Z"));
        jdbc.update("UPDATE marketplace.catalog_product SET created_at=CAST(:time AS timestamptz) WHERE id=:id",Map.of("id",pc.id(),"time","2021-01-01T00:00:00Z"));
        var api=context.getBean(com.example.marketplace.catalog.api.Browse.class);
        var orders=Map.of("price_asc",List.of(pa.slug(),pb.slug(),pc.slug()),"price_desc",List.of(pb.slug(),pa.slug(),pc.slug()),"name",List.of(pa.slug(),pc.slug(),pb.slug()),"newest",List.of(pb.slug(),pc.slug(),pa.slug()));
        orders.forEach((sort,expected)->assertEquals(expected,api.search(new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,null,null,null,false,sort,0)).items().stream().map(com.example.marketplace.catalog.api.Browse.Item::slug).toList(),sort));
        String store=context.getBean(com.example.marketplace.seller.api.Stores.class).own((UUID)b.get("owner")).slug();
        var filters=List.of(new com.example.marketplace.catalog.api.Browse.Query("P05 contract",pa.categoryId(),null,null,null,false,"newest",0),new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,store,null,null,false,"newest",0),new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,null,150L,null,false,"newest",0),new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,null,null,150L,false,"newest",0),new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,null,null,null,true,"newest",0));
        var expected=List.of(pa.slug(),pb.slug(),pb.slug(),pa.slug(),pa.slug());
        for(int i=0;i<filters.size();i++) { assertEquals(List.of(expected.get(i)),api.search(filters.get(i)).items().stream().map(com.example.marketplace.catalog.api.Browse.Item::slug).toList(),"filter "+i); }
        var detail=api.detail(pa.slug());assertEquals(100L,detail.product().minPrice());assertFalse(detail.product().soldOut());assertEquals(2,detail.offers().size());
        assertEquals(0,detail.offers().stream().filter(o->o.id().equals(cheap)).findFirst().orElseThrow().available());
        assertEquals(3,detail.offers().stream().filter(o->o.id().equals(stocked)).findFirst().orElseThrow().available());
        assertTrue(detail.offers().stream().noneMatch(o->o.id().equals(a.get("sku"))));
    }

    private com.example.marketplace.inventory.api.Reservations reservations() {
        return context.getBean(com.example.marketplace.inventory.api.Reservations.class);
    }
    private UUID stockedSku(String suffix,long quantity) {
        var f=skuFixture(suffix); UUID owner=(UUID)f.get("owner");
        var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst().id();
        context.getBean(com.example.marketplace.inventory.api.Inventory.class).adjust(owner,product.id(),sku,false,
            new com.example.marketplace.inventory.api.Inventory.Change(quantity,0L,"예약 검사 입고",UUID.randomUUID()));
        return sku;
    }
    private com.example.marketplace.inventory.api.Reservations.Request reservationRequest(UUID sku,long quantity) {
        return new com.example.marketplace.inventory.api.Reservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),sku,quantity,
            Instant.now().plusSeconds(900).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    private void stockEquals(UUID sku,long onHand,long reserved,long version) {
        var row=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",sku));
        assertEquals(onHand,((Number)row.get("on_hand")).longValue());
        assertEquals(reserved,((Number)row.get("reserved")).longValue());
        assertEquals(version,((Number)row.get("version")).longValue());
    }
    private String reservationAttempt(Runnable action) {
        try { action.run();return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException rejected) { return ""+rejected.status(); }
    }
    @Test
    void reservationCrossSkuReverseReservationKeysAreSerialized() throws Exception { reservationCrossKeys(false); }
    @Test
    void reservationCrossSkuReverseOrderItemKeysAreSerialized() throws Exception { reservationCrossKeys(true); }
    private void reservationCrossKeys(boolean orderItems) throws Exception {
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
    @Test
    void reservationTerminalAndExpiryRaces() throws Exception {
        UUID sku=stockedSku("reserve-terminal",10);var api=reservations();var request=reservationRequest(sku,2);api.reserve(List.of(request));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(race(()->reservationAttempt(()-> { if(calls.getAndIncrement()==0) { api.commit(request.checkoutId(),List.of(request.id())); }
            else { api.release(request.checkoutId(),List.of(request.id())); } }))).containsExactlyInAnyOrder("ok","409");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE reservation_id=:id AND cause<>'RESERVE'",Map.of("id",request.id()),Integer.class));
        var due=reservationRequest(sku,1);api.reserve(List.of(due));
        assertEquals("RESERVED",api.expire(due.checkoutId(),List.of(due.id())).getFirst().status());
        jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id",Map.of("id",due.id()));
        assertEquals("409",reservationAttempt(()->api.commit(due.checkoutId(),List.of(due.id()))));
        assertThat(race(()->reservationAttempt(()->api.expire(due.checkoutId(),List.of(due.id()))))).containsExactly("ok","ok");
        assertEquals("RELEASED",api.expire(due.checkoutId(),List.of(due.id())).getFirst().status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE reservation_id=:id AND cause='EXPIRE'",Map.of("id",due.id()),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",sku),Long.class));
        var expired=reservationRequest(sku,1);api.reserve(List.of(expired));
        jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id",Map.of("id",expired.id()));
        calls.set(0);
        assertThat(race(()->reservationAttempt(()-> { if(calls.getAndIncrement()==0) { api.commit(expired.checkoutId(),List.of(expired.id())); }
            else { api.expire(expired.checkoutId(),List.of(expired.id())); } }))).containsExactlyInAnyOrder("409","ok");
    }
    @Test
    void reservationExpiryIsCheckedAfterBalanceLock() throws Exception {
        UUID sku=stockedSku("reserve-lock-time",3);var api=reservations();var request=reservationRequest(sku,1);api.reserve(List.of(request));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:sku FOR UPDATE",Map.of("sku",sku),UUID.class);
                pending.set(pool.submit(()->reservationAttempt(()->api.commit(request.checkoutId(),List.of(request.id())))));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%inventory_balance%')",Map.of(),Boolean.class));
                    if(waiting) { break; } try { Thread.sleep(20); } catch(InterruptedException ex) { throw new IllegalStateException(ex); }
                }
                assertTrue(waiting,"commit must actually wait on the balance lock");
                // Advance the fixture to the DB boundary only after the worker is observed waiting.
                jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp() WHERE id=:id",Map.of("id",request.id()));
                var boundary=jdbc.queryForMap("SELECT expires_at,clock_timestamp() AS db_now,expires_at<=clock_timestamp() AS due FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",request.id()));
                assertEquals(Boolean.TRUE,boundary.get("due"),boundary.toString());
            });
            assertEquals("409",pending.get().get(5,TimeUnit.SECONDS));stockEquals(sku,3,1,2);
        }
        api.expire(request.checkoutId(),List.of(request.id()));stockEquals(sku,3,0,3);
    }
    @Test
    void reservationBatchesAndOuterTransactionRollBack() {
        UUID a=stockedSku("reserve-rollback-a",5),b=stockedSku("reserve-rollback-b",1);var api=reservations();
        var first=reservationRequest(a,2);var insufficient=reservationRequest(b,2);
        assertEquals("409",reservationAttempt(()->api.reserve(List.of(first,insufficient))));stockEquals(a,5,0,1);stockEquals(b,1,0,1);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE id IN (:a,:b)",Map.of("a",first.id(),"b",insufficient.id()),Integer.class));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            api.reserve(List.of(first));throw new IllegalStateException("outer operation failed");
        }));stockEquals(a,5,0,1);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE sku_id=:sku",Map.of("sku",a),Integer.class));
        api.reserve(List.of(first));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.inventory_reservation_event ADD CONSTRAINT p4e_audit_failure CHECK (cause<>'COMMIT') NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->api.commit(first.checkoutId(),List.of(first.id())));stockEquals(a,5,2,2);
            assertEquals("RESERVED",jdbc.queryForObject("SELECT status FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",first.id()),String.class));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.inventory_reservation_event DROP CONSTRAINT p4e_audit_failure"); }
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            api.release(first.checkoutId(),List.of(first.id()));throw new IllegalStateException("outer release rollback");
        }));stockEquals(a,5,2,2);
        api.commit(first.checkoutId(),List.of(first.id()));stockEquals(a,3,0,3);
        var x=reservationRequest(a,1);var original=reservationRequest(b,1);
        var y=new com.example.marketplace.inventory.api.Reservations.Request(original.id(),x.checkoutId(),original.orderItemId(),b,1,original.expiresAt());
        api.reserve(List.of(x,y));api.release(y.checkoutId(),List.of(y.id()));
        assertEquals("409",reservationAttempt(()->api.commit(x.checkoutId(),List.of(x.id(),y.id()))));stockEquals(a,3,1,4);
        assertEquals("RESERVED",jdbc.queryForObject("SELECT status FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",x.id()),String.class));
    }
    @Test
    void reservationReverseBatchesAndManualAdjustmentSerialize() throws Exception {
        UUID a=stockedSku("reserve-batch-a",6),b=stockedSku("reserve-batch-b",6);var api=reservations();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var outcomes=race(()-> {var x=reservationRequest(a,2);var y=reservationRequest(b,2);
            return reservationAttempt(()->api.reserve(calls.getAndIncrement()==0?List.of(x,y):List.of(y,x)));});
        assertThat(outcomes).containsExactly("ok","ok");stockEquals(a,6,4,3);stockEquals(b,6,4,3);
        var x=reservationRequest(a,1);var original=reservationRequest(b,1);
        var y=new com.example.marketplace.inventory.api.Reservations.Request(original.id(),x.checkoutId(),original.orderItemId(),b,1,original.expiresAt());
        api.reserve(List.of(x,y));calls.set(0);
        assertThat(race(()->reservationAttempt(()->{if(calls.getAndIncrement()==0) { api.commit(x.checkoutId(),List.of(x.id(),y.id())); }
            else { api.release(x.checkoutId(),List.of(y.id(),x.id())); } }))).containsExactlyInAnyOrder("ok","409");
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE reservation_id IN (:x,:y) AND cause<>'RESERVE'",Map.of("x",x.id(),"y",y.id()),Integer.class));
        var fixture=skuFixture("reserve-manual");UUID owner=(UUID)fixture.get("owner");var product=(com.example.marketplace.catalog.api.Products.Product)fixture.get("product");
        UUID sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst().id();var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        inventory.adjust(owner,product.id(),sku,false,new com.example.marketplace.inventory.api.Inventory.Change(5L,0L,"입고",UUID.randomUUID()));
        calls.set(0);assertThat(race(()->reservationAttempt(()-> { if(calls.getAndIncrement()==0) { api.reserve(List.of(reservationRequest(sku,4))); }
            else { inventory.adjust(owner,product.id(),sku,false,new com.example.marketplace.inventory.api.Inventory.Change(2L,1L,"실사",UUID.randomUUID())); } }))).containsExactlyInAnyOrder("ok","409");
        assertTrue(jdbc.queryForObject("SELECT reserved<=on_hand AND reserved>=0 FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",sku),Boolean.class));
    }
    @Test
    void reservationValidationAndDatabaseConstraints() {
        UUID sku=stockedSku("reserve-boundary",999999999);var api=reservations();var valid=reservationRequest(sku,999999999);
        assertEquals("400",reservationAttempt(()->api.reserve(null)));assertEquals("400",reservationAttempt(()->api.reserve(List.of())));
        assertEquals("400",reservationAttempt(()->api.reserve(List.of(valid,valid))));
        for(long quantity:new long[]{0,-1,1000000000L}) {
            var bad=new com.example.marketplace.inventory.api.Reservations.Request(valid.id(),valid.checkoutId(),valid.orderItemId(),sku,quantity,valid.expiresAt());
            assertEquals("400",reservationAttempt(()->api.reserve(List.of(bad))));
        }
        var past=new com.example.marketplace.inventory.api.Reservations.Request(valid.id(),valid.checkoutId(),valid.orderItemId(),sku,1,Instant.EPOCH);
        assertEquals("409",reservationAttempt(()->api.reserve(List.of(past))));
        assertEquals("404",reservationAttempt(()->api.reserve(List.of(reservationRequest(UUID.randomUUID(),1)))));
        assertEquals("400",reservationAttempt(()->api.commit(null,List.of(valid.id()))));assertEquals("400",reservationAttempt(()->api.release(valid.checkoutId(),List.of())));
        assertEquals("400",reservationAttempt(()->api.expire(valid.checkoutId(),java.util.Arrays.asList((UUID)null))));
        api.reserve(List.of(valid));stockEquals(sku,999999999,999999999,2);
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.inventory_reservation SET quantity=0 WHERE id=:id",Map.of("id",valid.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.inventory_reservation SET status='COMMITTED' WHERE id=:id",Map.of("id",valid.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=0 WHERE sku_id=:id",Map.of("id",sku)));
        api.release(valid.checkoutId(),List.of(valid.id()));stockEquals(sku,999999999,0,3);
        jdbc.update("UPDATE marketplace.inventory_balance SET version=9007199254740991 WHERE sku_id=:id",Map.of("id",sku));
        var overflow=reservationRequest(sku,1);assertEquals("409",reservationAttempt(()->api.reserve(List.of(overflow))));stockEquals(sku,999999999,0,9007199254740991L);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",overflow.id()),Integer.class));
        assertEquals("400",reservationAttempt(()->api.reserve(java.util.Collections.nCopies(101,valid))));
        assertEquals("400",reservationAttempt(()->api.commit(valid.checkoutId(),List.of(valid.id(),valid.id()))));
        var precise=new com.example.marketplace.inventory.api.Reservations.Request(UUID.randomUUID(),valid.checkoutId(),UUID.randomUUID(),sku,1,valid.expiresAt().plusNanos(123));
        jdbc.update("UPDATE marketplace.inventory_balance SET version=3 WHERE sku_id=:id",Map.of("id",sku));
        assertEquals(valid.expiresAt(),api.reserve(List.of(precise)).getFirst().expiresAt());api.reserve(List.of(precise));stockEquals(sku,999999999,1,4);
        var row=jdbc.queryForMap("SELECT * FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku));
        var ds=context.getBean(DataSource.class);String schema="p4e_upgrade";
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("17").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO p4e_upgrade.identity_user SELECT u.* FROM marketplace.identity_user u JOIN marketplace.seller_store s ON s.owner_id=u.id JOIN marketplace.catalog_product p ON p.store_id=s.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.seller_store SELECT s.* FROM marketplace.seller_store s JOIN marketplace.catalog_product p ON p.store_id=s.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.catalog_category SELECT c.* FROM marketplace.catalog_category c JOIN marketplace.catalog_product p ON p.category_id=c.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.catalog_product SELECT p.id,p.store_id,p.category_id,p.slug,p.title,p.description,p.state,p.is_hidden_by_admin,p.version FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.catalog_sku SELECT * FROM marketplace.catalog_sku WHERE id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.inventory_balance SELECT * FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.stock_movement SELECT * FROM marketplace.stock_movement WHERE sku_id=:id",Map.of("id",sku));
        var movement=jdbc.queryForList("SELECT * FROM p4e_upgrade.stock_movement",Map.of());
        var migration=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();
        assertEquals(19,migration.migrate().migrationsExecuted);assertEquals(0,migration.migrate().migrationsExecuted);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM p4e_upgrade.catalog_product WHERE created_at IS NOT NULL",Map.of(),Integer.class));
        assertEquals(row,jdbc.queryForMap("SELECT * FROM p4e_upgrade.inventory_balance WHERE sku_id=:id",Map.of("id",sku)));
        assertEquals(movement,jdbc.queryForList("SELECT * FROM p4e_upgrade.stock_movement",Map.of()));
    }
    @Test
    void reservationLifecycleHasOneEffectAndCompleteAudit() {
        UUID sku=stockedSku("reserve-basic",10); var api=reservations(); var request=reservationRequest(sku,3);
        assertEquals("RESERVED",api.reserve(List.of(request)).getFirst().status()); stockEquals(sku,10,3,2);
        api.reserve(List.of(request)); stockEquals(sku,10,3,2);
        assertEquals("COMMITTED",api.commit(request.checkoutId(),List.of(request.id())).getFirst().status()); stockEquals(sku,7,0,3);
        api.commit(request.checkoutId(),List.of(request.id())); api.reserve(List.of(request)); stockEquals(sku,7,0,3);
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.release(request.checkoutId(),List.of(request.id()))).status());
        var released=reservationRequest(sku,2); api.reserve(List.of(released));
        api.release(released.checkoutId(),List.of(released.id())); api.release(released.checkoutId(),List.of(released.id())); stockEquals(sku,7,0,5);
        var events=jdbc.queryForList("SELECT cause,before_on_hand,after_on_hand,before_reserved,after_reserved,before_version,version FROM marketplace.inventory_reservation_event WHERE sku_id=:sku ORDER BY version",Map.of("sku",sku));
        assertEquals(List.of("RESERVE","COMMIT","RESERVE","RELEASE"),events.stream().map(e->e.get("cause")).toList());
        long[][] expected={{10,10,0,3,1,2},{10,7,3,0,2,3},{7,7,0,2,3,4},{7,7,2,0,4,5}};
        String[] keys={"before_on_hand","after_on_hand","before_reserved","after_reserved","before_version","version"};
        for(int i=0;i<4;i++) { for(int j=0;j<6;j++) { assertEquals(expected[i][j],((Number)events.get(i).get(keys[j])).longValue()); } }
    }
    @Test
    void reservationLastUnitRaceAndKeyCollisions() throws Exception {
        UUID sku=stockedSku("reserve-last",1); var api=reservations();
        var outcomes=race(()-> { try { api.reserve(List.of(reservationRequest(sku,1)));return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException ex) { return ""+ex.status(); } });
        assertThat(outcomes).containsExactlyInAnyOrder("ok","409"); stockEquals(sku,1,1,2);
        UUID another=stockedSku("reserve-key",5); var request=reservationRequest(another,2);
        assertThat(race(()->{api.reserve(List.of(request));return "ok";})).containsExactly("ok","ok"); stockEquals(another,5,2,2);
        assertEquals(404,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.commit(UUID.randomUUID(),List.of(request.id()))).status());
        var changed=new com.example.marketplace.inventory.api.Reservations.Request(request.id(),request.checkoutId(),request.orderItemId(),another,3,request.expiresAt());
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.reserve(List.of(changed))).status());
        var collision=new com.example.marketplace.inventory.api.Reservations.Request(UUID.randomUUID(),request.checkoutId(),request.orderItemId(),sku,1,request.expiresAt());
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.reserve(List.of(collision))).status());
        stockEquals(another,5,2,2); stockEquals(sku,1,1,2);
        UUID other=stockedSku("reserve-cross-key",5);var cross=new com.example.marketplace.inventory.api.Reservations.Request(request.id(),request.checkoutId(),UUID.randomUUID(),other,1,request.expiresAt());
        assertEquals("409",reservationAttempt(()->api.reserve(List.of(cross))));stockEquals(other,5,0,1);
        var key=UUID.randomUUID();var checkout=UUID.randomUUID();var index=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(race(()-> {UUID target=index.getAndIncrement()==0?another:other;
            return reservationAttempt(()->api.reserve(List.of(new com.example.marketplace.inventory.api.Reservations.Request(key,checkout,UUID.randomUUID(),target,1,request.expiresAt()))));
        })).containsExactlyInAnyOrder("ok","409");
        assertEquals(3,jdbc.queryForObject("SELECT sum(reserved) FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b)",Map.of("a",another,"b",other),Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE reservation_id=:id",Map.of("id",key),Integer.class));
    }
    @Test
    void inventoryAdjustmentIsVersionedAuditedAndIdempotent() throws Exception {
        var f=skuFixture("stock-basic"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-stock-basic@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String path="/api/seller/products/"+product.id()+"/inventory";
        var list=accountRequest(client,"GET",path,null,null); assertEquals(200,list.statusCode(),list.body());
        assertEquals(0,authMapper.readTree(list.body()).get("items").get(0).get("onHand").asLong());
        var input=Map.of("onHand",10,"version",0,"reason","초기 입고","requestId",UUID.randomUUID().toString());
        var saved=accountRequest(client,"PUT",path+"/"+sku.id(),input,csrfToken(client)); assertEquals(200,saved.statusCode(),saved.body());
        assertEquals(1,authMapper.readTree(saved.body()).get("version").asLong());
        assertEquals(saved.body(),accountRequest(client,"PUT",path+"/"+sku.id(),input,csrfToken(client)).body());
        assertEquals(409,accountRequest(client,"PUT",path+"/"+sku.id(),Map.of("onHand",20,"version",0,"reason","낡은 조정","requestId",UUID.randomUUID().toString()),csrfToken(client)).statusCode());
        var history=accountRequest(client,"GET",path+"/"+sku.id()+"/history",null,null); assertEquals(200,history.statusCode());
        var events=authMapper.readTree(history.body()).get("items"); assertEquals(1,events.size()); assertEquals(0,events.get(0).get("beforeOnHand").asLong()); assertEquals(10,events.get(0).get("afterOnHand").asLong()); assertEquals("초기 입고",events.get(0).get("reason").asText());
    }
    @Test
    void inventoryBoundariesOwnershipAndReservedFloorAreEnforced() throws Exception {
        var f=skuFixture("stock-limits"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        var client=authBrowser(); authRequest(client,"/api/auth/login",loginForm("p4b-stock-limits@example.com",SIGNUP_PASSWORD),csrfToken(client));
        String path="/api/seller/products/"+product.id()+"/inventory/"+sku.id();
        for(Object value:List.of(-1,1000000000L,"1",true,1.5)) {
            assertEquals(400,accountRequest(client,"PUT",path,Map.of("onHand",value,"version",0,"reason","검사","requestId",UUID.randomUUID().toString()),csrfToken(client)).statusCode());
        }
        for(Object value:List.of(-1,"0",true,0.5,9007199254740991L)) {
            assertEquals(400,accountRequest(client,"PUT",path,Map.of("onHand",1,"version",value,"reason","검사","requestId",UUID.randomUUID().toString()),csrfToken(client)).statusCode());
        }
        assertEquals(403,accountRequest(client,"PUT",path,Map.of("onHand",1,"version",0,"reason","검사","requestId",UUID.randomUUID().toString()),null).statusCode());
        UUID stranger=(UUID)skuFixture("stock-stranger").get("owner");
        assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->inventory.list(stranger,product.id(),false,0)).status());
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->inventory.list(owner,product.id(),true,0));
        inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(10L,0L,"입고",UUID.randomUUID()));
        jdbc.update("UPDATE marketplace.inventory_balance SET reserved=4 WHERE sku_id=:id",Map.of("id",sku.id()));
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(3L,1L,"예약 하한",UUID.randomUUID()))).status());
        var result=inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(4L,1L,"실사",UUID.randomUUID()));
        assertEquals(0,result.available()); assertEquals(4,result.reserved());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=3 WHERE sku_id=:id",Map.of("id",sku.id())));
        assertEquals(400,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->inventory.history(owner,product.id(),sku.id(),false,-1)).status());
    }
    @Test
    void inventoryConcurrentKeysHaveOneEffectAndAuditFailureRollsBack() throws Exception {
        var f=skuFixture("stock-race"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        var input=new com.example.marketplace.inventory.api.Inventory.Change(10L,0L,"동시 재전송",UUID.randomUUID());
        assertEquals(List.of("ok","ok"),race(()->{ inventory.adjust(owner,product.id(),sku.id(),false,input);return "ok"; }));
        assertEquals(1,inventory.history(owner,product.id(),sku.id(),false,0).items().size());
        var attempts=race(()->{ try { inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(20L,1L,"동시 조정",UUID.randomUUID()));return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException e) { assertEquals(409,e.status());return "conflict"; } });
        assertEquals(1,attempts.stream().filter("ok"::equals).count());
        var failure=new com.example.marketplace.inventory.api.Inventory.Change(30L,2L,"실패",UUID.randomUUID());
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.stock_movement ADD CONSTRAINT p4d_fail CHECK(reason<>'실패') NOT VALID");
        try { assertThrows(DataIntegrityViolationException.class,()->inventory.adjust(owner,product.id(),sku.id(),false,failure)); }
        finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.stock_movement DROP CONSTRAINT p4d_fail"); }
        assertEquals(20,inventory.list(owner,product.id(),false,0).items().getFirst().onHand()); assertEquals(2,inventory.history(owner,product.id(),sku.id(),false,0).items().size());
        assertEquals(3,inventory.adjust(owner,product.id(),sku.id(),false,failure).version());
        assertEquals(1,inventory.adjust(owner,product.id(),sku.id(),false,input).version());
        assertEquals(30,inventory.list(owner,product.id(),false,0).items().getFirst().onHand());
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(11L,0L,input.reason(),input.requestId()))).status());
        var other=context.getBean(com.example.marketplace.catalog.api.Skus.class).save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("색","청")),0L,false,0L));
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->inventory.adjust(owner,product.id(),other.id(),false,input)).status());
    }
    @Test
    void inventoryHistoryPagesAndUpgradePreserveSkuData() {
        var f=skuFixture("stock-upgrade"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        for(int i=0;i<25;i++) { inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change((long)i+1,(long)i,"실사 "+i,UUID.randomUUID())); }
        jdbc.update("UPDATE marketplace.stock_movement SET occurred_at=clock_timestamp()+interval '1 day' WHERE sku_id=:id AND version=1",Map.of("id",sku.id()));
        var first=inventory.history(owner,product.id(),sku.id(),false,0);var next=inventory.history(owner,product.id(),sku.id(),false,1);
        assertEquals(20,first.items().size());assertTrue(first.hasMore());assertEquals(25,first.items().getFirst().version());assertEquals(6,first.items().getLast().version());assertEquals(5,next.items().size());assertFalse(next.hasMore());assertEquals(1,next.items().getLast().version());
        var ds=context.getBean(DataSource.class); String schema="p4d_upgrade";
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("16").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO p4d_upgrade.identity_user SELECT * FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner));
        jdbc.update("INSERT INTO p4d_upgrade.seller_store SELECT * FROM marketplace.seller_store WHERE owner_id=:id",Map.of("id",owner));
        jdbc.update("INSERT INTO p4d_upgrade.catalog_category SELECT * FROM marketplace.catalog_category WHERE id=:id",Map.of("id",product.categoryId()));
        jdbc.update("INSERT INTO p4d_upgrade.catalog_product SELECT id,store_id,category_id,slug,title,description,state,is_hidden_by_admin,version FROM marketplace.catalog_product WHERE id=:id",Map.of("id",product.id()));
        jdbc.update("INSERT INTO p4d_upgrade.catalog_sku SELECT * FROM marketplace.catalog_sku WHERE id=:id",Map.of("id",sku.id()));
        var upgrade=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();upgrade.migrate();assertEquals(0,upgrade.migrate().migrationsExecuted);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM p4d_upgrade.inventory_balance WHERE sku_id=:id AND on_hand=0 AND reserved=0 AND version=0",Map.of("id",sku.id()),Integer.class));
        assertEquals(sku.code(),jdbc.queryForObject("SELECT code FROM p4d_upgrade.catalog_sku WHERE id=:id",Map.of("id",sku.id()),String.class));
    }
    @Test
    void inventoryAdministratorPermissionIsRecheckedAfterProductLockWait() throws Exception {
        var f=skuFixture("stock-admin"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        UUID admin=activeMember("stock-admin@example.com"); jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        assertEquals(0,inventory.list(admin,product.id(),true,0).items().getFirst().onHand());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.catalog_product WHERE id=:id FOR UPDATE",Map.of("id",product.id()),UUID.class);
                pending.set(executor.submit(()->{ try { inventory.adjust(admin,product.id(),sku.id(),true,new com.example.marketplace.inventory.api.Inventory.Change(5L,0L,"운영",UUID.randomUUID()));return "ok"; } catch(com.example.marketplace.identity.api.AccessRejected rejected) { return "denied"; } }));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%catalog_product%FOR UPDATE%')",Map.of(),Boolean.class));
                    if(waiting) { break; } try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertTrue(waiting);jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            });
            assertEquals("denied",pending.get().get(5,TimeUnit.SECONDS));assertEquals(0,inventory.list(owner,product.id(),false,0).items().getFirst().onHand());
        }
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var saved=inventory.adjust(admin,product.id(),sku.id(),true,new com.example.marketplace.inventory.api.Inventory.Change(5L,0L,"운영 실사",UUID.randomUUID()));assertEquals(5,saved.onHand());assertEquals(admin,inventory.history(admin,product.id(),sku.id(),true,0).items().getFirst().actorId());
    }
    @Test
    void inventoryGlobalRequestKeyCollisionRollsBackOtherOwnersBalance() throws Exception {
        var a=skuFixture("stock-global-a");var b=skuFixture("stock-global-b");
        UUID ownerA=(UUID)a.get("owner"),ownerB=(UUID)b.get("owner");
        var productA=(com.example.marketplace.catalog.api.Products.Product)a.get("product");var productB=(com.example.marketplace.catalog.api.Products.Product)b.get("product");
        var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);var skuA=skus.list(ownerA,productA.id(),0).items().getFirst();var skuB=skus.list(ownerB,productB.id(),0).items().getFirst();
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);var input=new com.example.marketplace.inventory.api.Inventory.Change(10L,0L,"global race",UUID.randomUUID());
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.p4d_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.reason='global race' THEN PERFORM pg_advisory_xact_lock(20260404); END IF; RETURN NEW; END $$");
        jdbc.getJdbcTemplate().execute("CREATE TRIGGER p4d_gate BEFORE INSERT ON marketplace.stock_movement FOR EACH ROW EXECUTE FUNCTION marketplace.p4d_gate()");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(20260404)");
                for(int n=0;n<2;n++) { final int index=n; futures.add(executor.submit(()->{ try { inventory.adjust(index==0?ownerA:ownerB,index==0?productA.id():productB.id(),index==0?skuA.id():skuB.id(),false,input);return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException e) { assertEquals(409,e.status()); return "conflict"; } })); }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waiting=0;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'INSERT INTO marketplace.stock_movement%'",Map.of(),Integer.class);
                    if(waiting==2) { break; } try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertEquals(2,waiting,"Both independent transactions must reach insert before release");
            });
            var results=List.of(futures.get(0).get(5,TimeUnit.SECONDS),futures.get(1).get(5,TimeUnit.SECONDS));assertEquals(1,results.stream().filter("ok"::equals).count());
            assertEquals(10,inventory.list(ownerA,productA.id(),false,0).items().getFirst().onHand()+inventory.list(ownerB,productB.id(),false,0).items().getFirst().onHand());
            assertEquals(1,inventory.history(ownerA,productA.id(),skuA.id(),false,0).items().size()+inventory.history(ownerB,productB.id(),skuB.id(),false,0).items().size());
        } finally { jdbc.getJdbcTemplate().execute("DROP TRIGGER p4d_gate ON marketplace.stock_movement");jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.p4d_gate()"); }
    }
    private byte[] mediaPng() throws Exception {
        var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out); return out.toByteArray();
    }
    @Test
    void mediaStoreOwnershipCsrfAndPublicProjectionAreEnforced() throws Exception {
        var f=skuFixture("media-access"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var other=skuFixture("media-other"); UUID outsider=(UUID)other.get("owner"); var media=context.getBean(com.example.marketplace.media.api.Media.class);
        byte[] png=mediaPng(); var asset=media.upload(owner,"products",product.id(),0,0,"상품","x.png",png);
        assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->media.list(outsider,"products",product.id())).status());
        assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->media.upload(outsider,"products",product.id(),1,0,"타인","x.png",png)).status());
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-media-access@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String path="/api/media/products/"+product.id();
        assertEquals(403,mediaUpload(client,path+"/1",png,"x.png",0,"CSRF",false).statusCode());
        assertEquals(400,mediaUpload(client,path+"/5",png,"x.png",0,"범위",true).statusCode());
        assertEquals(400,mediaUpload(client,path+"/1",png,"x.html",0,"형식",true).statusCode());
        assertEquals(400,mediaUpload(client,path+"/1",png,"x.png",0," ",true).statusCode());
        var oversize=mediaUpload(client,path+"/1",new byte[5*1024*1024+1],"x.png",0,"크기",true); assertTrue(oversize.statusCode()==400||oversize.statusCode()==413,oversize.body());
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        products.save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),"","ACTIVE",0L));
        assertTrue(media.content(null,asset.assetId()).length>0);
        jdbc.update("UPDATE marketplace.catalog_category SET active=false WHERE id=:id",Map.of("id",product.categoryId())); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(null,asset.assetId()));
        jdbc.update("UPDATE marketplace.catalog_category SET active=true WHERE id=:id",Map.of("id",product.categoryId()));
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",product.id())); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(null,asset.assetId())); assertTrue(media.content(owner,asset.assetId()).length>0);
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=false WHERE id=:id",Map.of("id",product.id()));
        var store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(owner);
        var storeAsset=media.upload(owner,"stores",store.id(),0,0,"매대","x.png",png); assertEquals(1,media.published("stores",store.slug()).size());
        assertEquals(404,assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.upload(outsider,"stores",store.id(),0,1,"타인","x.png",png)).status());
        jdbc.update("UPDATE marketplace.seller_store SET visible=false,state='SUSPENDED' WHERE id=:id",Map.of("id",store.id()));
        assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(null,storeAsset.assetId())); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(null,asset.assetId()));
        assertTrue(media.content(owner,storeAsset.assetId()).length>0);
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->media.delete(owner,"stores",store.id(),0,1));
        jdbc.update("UPDATE marketplace.seller_store SET visible=true,state='OPEN' WHERE id=:id",Map.of("id",store.id()));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",owner)); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(owner,asset.assetId()));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner)); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(owner,storeAsset.assetId()));
    }
    @Test
    void mediaConcurrentCreateReplaceDeleteHasOneWinner() throws Exception {
        var f=skuFixture("media-race"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var media=context.getBean(com.example.marketplace.media.api.Media.class); byte[] png=mediaPng();
        var created=race(()->{ try { media.upload(owner,"products",product.id(),0,0,"동시","x.png",png); return "ok"; } catch(com.example.marketplace.media.api.MediaException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,created.stream().filter("ok"::equals).count()); var first=media.list(owner,"products",product.id()).getFirst();
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        var changed=race(()->{ try { if(sequence.incrementAndGet()==1) { media.upload(owner,"products",product.id(),0,1,"교체","x.png",png); } else { media.delete(owner,"products",product.id(),0,1); } return "ok"; } catch(com.example.marketplace.media.api.MediaException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,changed.stream().filter("ok"::equals).count()); assertEquals(2,media.list(owner,"products",product.id()).getFirst().version());
        assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(owner,first.assetId()));
    }
    @Test
    void mediaRollbackAndCleanupKeepCurrentReferencesAndRetryPartialFailure() throws Exception {
        var f=skuFixture("media-cleanup"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var media=context.getBean(com.example.marketplace.media.api.Media.class); var cleanup=context.getBean(com.example.marketplace.media.infrastructure.JdbcMedia.class);
        var root=java.nio.file.Path.of(context.getEnvironment().getRequiredProperty("marketplace.media.root")); byte[] png=mediaPng();
        var old=media.upload(owner,"products",product.id(),0,0,"첫사진","x.png",png);
        var rolled=new java.util.concurrent.atomic.AtomicReference<UUID>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{ rolled.set(media.upload(owner,"products",product.id(),0,1,"롤백","x.png",png).assetId()); status.setRollbackOnly(); });
        assertFalse(java.nio.file.Files.exists(root.resolve(rolled.get()+".png"))); assertEquals(old.assetId(),media.list(owner,"products",product.id()).getFirst().assetId());
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.media_slot ADD CONSTRAINT p4c_reject_slot CHECK(alt<>'실패') NOT VALID");
        long before; try(var files=java.nio.file.Files.list(root)) { before=files.count(); }
        try { assertThrows(DataIntegrityViolationException.class,()->media.upload(owner,"products",product.id(),0,1,"실패","x.png",png)); }
        finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.media_slot DROP CONSTRAINT p4c_reject_slot"); }
        try(var files=java.nio.file.Files.list(root)) { assertEquals(before,files.count()); }
        var current=media.upload(owner,"products",product.id(),0,1,"현재","x.png",png);
        var aged=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2)));
        java.nio.file.Files.setLastModifiedTime(root.resolve(current.assetId()+".png"),aged);
        jdbc.update("UPDATE marketplace.media_asset SET retired_at=clock_timestamp()-interval '2 days' WHERE id=:id",Map.of("id",old.assetId()));
        UUID orphan=UUID.randomUUID(),recent=UUID.randomUUID(); java.nio.file.Files.write(root.resolve(orphan+".png"),png); java.nio.file.Files.setLastModifiedTime(root.resolve(orphan+".png"),aged); java.nio.file.Files.write(root.resolve(recent+".png"),png);
        java.nio.file.Files.writeString(root.resolve("untouched.txt"),"keep"); java.nio.file.Files.setLastModifiedTime(root.resolve("untouched.txt"),aged);
        jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.media_cleanup_blocker(id uuid REFERENCES marketplace.media_asset(id))"); jdbc.update("INSERT INTO marketplace.media_cleanup_blocker VALUES(:id)",Map.of("id",old.assetId()));
        assertThrows(DataIntegrityViolationException.class,cleanup::cleanup); assertFalse(java.nio.file.Files.exists(root.resolve(old.assetId()+".png")));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_asset WHERE id=:id",Map.of("id",old.assetId()),Integer.class));
        jdbc.update("DELETE FROM marketplace.media_cleanup_blocker WHERE id=:id",Map.of("id",old.assetId())); cleanup.cleanup(); cleanup.cleanup();
        assertFalse(java.nio.file.Files.exists(root.resolve(orphan+".png"))); assertTrue(java.nio.file.Files.exists(root.resolve(recent+".png"))); assertTrue(java.nio.file.Files.exists(root.resolve("untouched.txt"))); assertTrue(java.nio.file.Files.exists(root.resolve(current.assetId()+".png")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_asset WHERE id=:id",Map.of("id",old.assetId()),Integer.class));
    }
    @Test
    void mediaOuterTransactionAndAgedInFlightFileDoNotDeadlockOrGetCollected() throws Exception {
        var f=skuFixture("media-outer"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var media=context.getBean(com.example.marketplace.media.api.Media.class); byte[] png=mediaPng();
        var root=java.nio.file.Path.of(context.getEnvironment().getRequiredProperty("marketplace.media.root"));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                context.getBean(com.example.marketplace.catalog.api.Products.class).lockForMedia(owner,product.id());
                pending.set(executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(other->{
                    jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout='3s'");
                    media.upload(owner,"products",product.id(),1,0,"후속","x.png",png);
                })));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%'))",Map.of(),Boolean.class));
                    if(waiting) { break; }
                    try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertTrue(waiting);
                var current=media.upload(owner,"products",product.id(),0,0,"진행중","x.png",png);
                try { java.nio.file.Files.setLastModifiedTime(root.resolve(current.assetId()+".png"),java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2)))); }
                catch(java.io.IOException e) { throw new IllegalStateException(e); }
                // Same REQUIRED transaction still protects the aged, not-yet-committed file.
                context.getBean(com.example.marketplace.media.infrastructure.JdbcMedia.class).cleanup();
                assertTrue(java.nio.file.Files.exists(root.resolve(current.assetId()+".png")));
            });
            pending.get().get(5,TimeUnit.SECONDS);
            assertEquals(2,media.list(owner,"products",product.id()).stream().filter(row->row.assetId()!=null).count());
        }
    }
    @Test
    void mediaReaderWaitingForSingleConnectionDoesNotHoldFileMonitor() throws Exception {
        var f=skuFixture("media-pool"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var asset=context.getBean(com.example.marketplace.media.api.Media.class).upload(owner,"products",product.id(),0,0,"연결풀","x.png",mediaPng());
        var files=context.getBean(com.example.marketplace.media.infrastructure.LocalImages.class);
        try(var pool=new com.zaxxer.hikari.HikariDataSource(); var executor=Executors.newFixedThreadPool(2)) {
            pool.setJdbcUrl(System.getenv("D2_DB_URL")); pool.setUsername(System.getenv("D2_DB_USERNAME")); pool.setPassword(System.getenv("D2_DB_PASSWORD"));
            pool.setMaximumPoolSize(1); pool.setMinimumIdle(1); pool.setConnectionTimeout(2000);
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool);
            var media=new com.example.marketplace.media.infrastructure.JdbcMedia(new NamedParameterJdbcTemplate(pool),context.getBean(com.example.marketplace.catalog.api.Products.class),context.getBean(com.example.marketplace.seller.api.Stores.class),files,new com.example.marketplace.media.infrastructure.ImageNormalizer(),manager);
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<byte[]>>();
            new TransactionTemplate(manager).executeWithoutResult(status->{
                pending.set(executor.submit(()->media.content(owner,asset.assetId())));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0&&System.nanoTime()<deadline) {
                    try { Thread.sleep(10); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertEquals(1,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
                try { assertTrue(executor.submit(()->{ synchronized(files) { return true; } }).get(500,TimeUnit.MILLISECONDS)); }
                catch(Exception e) { throw new IllegalStateException("Connection waiter must not hold file monitor",e); }
            });
            assertTrue(pending.get().get(3,TimeUnit.SECONDS).length>0);
        }
    }
    @Test
    void mediaOrphanScanWaitingForSingleConnectionDoesNotHoldFileMonitor() throws Exception {
        var files=context.getBean(com.example.marketplace.media.infrastructure.LocalImages.class);
        var root=java.nio.file.Path.of(context.getEnvironment().getRequiredProperty("marketplace.media.root"));
        var orphan=root.resolve(UUID.randomUUID()+".png"); java.nio.file.Files.write(orphan,mediaPng());
        java.nio.file.Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        try(var pool=new com.zaxxer.hikari.HikariDataSource(); var executor=Executors.newFixedThreadPool(2)) {
            pool.setJdbcUrl(System.getenv("D2_DB_URL")); pool.setUsername(System.getenv("D2_DB_USERNAME")); pool.setPassword(System.getenv("D2_DB_PASSWORD"));
            pool.setMaximumPoolSize(1); pool.setMinimumIdle(1); pool.setConnectionTimeout(2000);
            var second=new CountDownLatch(1); var proceed=new CountDownLatch(1); var calls=new java.util.concurrent.atomic.AtomicInteger();
            var gated=new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    if(calls.incrementAndGet()==2) {
                        second.countDown();
                        try { if(!proceed.await(5,TimeUnit.SECONDS)) { throw new java.sql.SQLException("Test gate timed out"); } }
                        catch(InterruptedException e) { throw new java.sql.SQLException(e); }
                    }
                    return pool.getConnection();
                }
                @Override public java.sql.Connection getConnection(String user,String password) throws java.sql.SQLException { return getConnection(); }
            };
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(gated);
            var media=new com.example.marketplace.media.infrastructure.JdbcMedia(new NamedParameterJdbcTemplate(gated),context.getBean(com.example.marketplace.catalog.api.Products.class),context.getBean(com.example.marketplace.seller.api.Stores.class),files,new com.example.marketplace.media.infrastructure.ImageNormalizer(),manager);
            var pending=executor.submit(media::cleanup);
            assertTrue(second.await(5,TimeUnit.SECONDS));
            try {
                new TransactionTemplate(manager).executeWithoutResult(status->{
                    proceed.countDown(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                    while(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0&&System.nanoTime()<deadline) {
                        try { Thread.sleep(10); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    assertEquals(1,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
                    try { assertTrue(executor.submit(()->{ synchronized(files) { return true; } }).get(500,TimeUnit.MILLISECONDS)); }
                    catch(Exception e) { throw new IllegalStateException("Orphan connection waiter must not hold file monitor",e); }
                });
            } finally { proceed.countDown(); }
            pending.get(3,TimeUnit.SECONDS); assertFalse(java.nio.file.Files.exists(orphan));
        }
    }
    @Test
    void mediaUploadReplaceDeleteAndDirectUrlRespectVisibility() throws Exception {
        var f=skuFixture("media-basic"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-media-basic@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String path="/api/media/products/"+product.id();
        var listing=accountRequest(client,"GET",path,null,null); assertEquals(200,listing.statusCode(),listing.body()); assertEquals(5,authMapper.readTree(listing.body()).size());
        var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        var uploaded=mediaUpload(client,path+"/0",out.toByteArray(),"picture.png",0,"상품 사진",true); assertEquals(200,uploaded.statusCode(),uploaded.body());
        var asset=authMapper.readTree(uploaded.body()).get("assetId").asText(); String binary="/api/media/"+asset+"/content";
        assertEquals(200,accountRequest(client,"GET",binary,null,null).statusCode()); assertEquals(404,accountRequest(authBrowser(),"GET",binary,null,null).statusCode());
        context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),"","ACTIVE",0L));
        var publicRead=accountRequest(authBrowser(),"GET",binary,null,null); assertEquals(200,publicRead.statusCode()); assertEquals("no-store",publicRead.headers().firstValue("Cache-Control").orElseThrow()); assertEquals("image/png",publicRead.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(409,mediaUpload(client,path+"/0",out.toByteArray(),"picture.png",0,"낡은 수정",true).statusCode());
        assertEquals(200,mediaUpload(client,path+"/0",out.toByteArray(),"picture.png",1,"교체 사진",true).statusCode()); assertEquals(404,accountRequest(client,"GET",binary,null,null).statusCode());
        assertEquals(200,accountRequest(client,"DELETE",path+"/0?version=2",null,csrfToken(client)).statusCode());
        var deleted=authMapper.readTree(accountRequest(client,"GET",path,null,null).body()).get(0); assertTrue(deleted.get("assetId").isNull()); assertEquals(3,deleted.get("version").asLong());
        assertEquals(409,mediaUpload(client,path+"/0",out.toByteArray(),"picture.png",0,"재생성 충돌",true).statusCode());
    }
    private HttpResponse<String> mediaUpload(HttpClient client,String path,byte[] bytes,String filename,long version,String alt,boolean csrf) throws Exception {
        String boundary="p04c-boundary"; var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n"+version+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"alt\"\r\n\r\n"+alt+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","multipart/form-data; boundary="+boundary).PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if(csrf) { request.header("X-CSRF-TOKEN",csrfToken(client)); }
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test
    void skuDefaultTransitionIsAtomicAndSerializedWithActivation() throws Exception {
        var f=skuFixture("mode"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
        var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class); var basic=skus.list(owner,product.id(),0).items().getFirst();
        skus.save(owner,product.id(),basic.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),0L,true,0L));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.catalog_sku ADD CONSTRAINT p4b_fail_variant CHECK(options='[]') NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->skus.save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("종류","실패")),1L,true,0L)));
            var retained=skus.list(owner,product.id(),0).items(); assertEquals(1,retained.size()); assertTrue(retained.getFirst().active()); assertEquals(1,retained.getFirst().version());
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.catalog_sku DROP CONSTRAINT p4b_fail_variant"); }
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        race(()->{ try { if(sequence.incrementAndGet()==1) { skus.save(owner,product.id(),basic.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),0L,true,1L)); }
                else { skus.save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("종류","성공")),1L,true,0L)); } return "ok";
            } catch(com.example.marketplace.catalog.api.CatalogException e) { assertEquals(409,e.status()); return "conflict"; } });
        var rows=skus.list(owner,product.id(),0).items(); assertEquals(2,rows.size()); assertFalse(rows.stream().filter(row->row.options().isEmpty()).findFirst().orElseThrow().active()); assertEquals(1,rows.stream().filter(row->row.active()).count());
    }
    @Test
    void inventoryWritesRecheckSuspensionAndArchiveAfterActualLockWait() throws Exception {
        for(boolean member:List.of(true,false)) {
            String suffix=member?"stock-wait-member":"stock-wait-product";
            var f=skuFixture(suffix); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
            var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
            var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-"+suffix+"@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
            String token=csrfToken(client); String table=member?"identity_user":"catalog_product"; UUID lock=member?owner:product.id();
            try(var executor=Executors.newSingleThreadExecutor()) {
                var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",lock),UUID.class);
                    future.set(executor.submit(()->accountRequest(client,"PUT","/api/seller/products/"+product.id()+"/inventory/"+sku.id(),Map.of("onHand",5,"version",0,"reason","잠금 검사","requestId",UUID.randomUUID().toString()),token)));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                    while(System.nanoTime()<deadline) {
                        jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+(member?"%FOR NO KEY UPDATE%":"%FOR UPDATE%")),Boolean.class));
                        if(waiting) { break; }
                        try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    assertTrue(waiting,"Inventory request must wait on "+table);
                    jdbc.update("UPDATE marketplace."+table+" SET state=:state WHERE id=:id",Map.of("id",lock,"state",member?"SUSPENDED":"ARCHIVED"));
                });
                var response=future.get().get(5,TimeUnit.SECONDS); assertEquals(409,response.statusCode(),response.body());
                assertEquals("CONFLICT",authMapper.readTree(response.body()).get("code").asText());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.stock_movement WHERE sku_id=:id",Map.of("id",sku.id()),Integer.class));
            }
        }
    }
    @Test
    void mediaWritesRecheckSuspensionAndArchiveAfterActualLockWait() throws Exception {
        for(boolean member:List.of(true,false)) {
            String suffix=member?"media-wait-member":"media-wait-product";
            var f=skuFixture(suffix); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
            byte[] png=mediaPng();
            var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-"+suffix+"@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
            String table=member?"identity_user":"catalog_product"; UUID lock=member?owner:product.id();
            try(var executor=Executors.newSingleThreadExecutor()) {
                var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",lock),UUID.class);
                    future.set(executor.submit(()->mediaUpload(client,"/api/media/products/"+product.id()+"/0",png,"x.png",0,"잠금 검사",true)));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                    while(System.nanoTime()<deadline) {
                        jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+(member?"%FOR NO KEY UPDATE%":"%FOR UPDATE%")),Boolean.class));
                        if(waiting) { break; }
                        try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    assertTrue(waiting,"Media request must wait on "+table);
                    jdbc.update("UPDATE marketplace."+table+" SET state=:state WHERE id=:id",Map.of("id",lock,"state",member?"SUSPENDED":"ARCHIVED"));
                });
                var response=future.get().get(5,TimeUnit.SECONDS); assertEquals(409,response.statusCode(),response.body());
                assertEquals("CONFLICT",authMapper.readTree(response.body()).get("code").asText());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_slot WHERE product_id=:id",Map.of("id",product.id()),Integer.class));
            }
        }
    }
    @Test
    void skuWritesRecheckSuspensionAndArchiveAfterActualLockWait() throws Exception {
        for(boolean member:List.of(true,false)) {
            String suffix=member?"wait-member":"wait-product";
            var f=skuFixture(suffix); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
            var basic=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
            var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-"+suffix+"@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
            String csrf=csrfToken(client); String table=member?"identity_user":"catalog_product"; UUID lock=member?owner:product.id();
            try(var executor=Executors.newSingleThreadExecutor()) {
                var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",lock),UUID.class);
                    future.set(executor.submit(()->accountRequest(client,"PUT","/api/seller/products/"+product.id()+"/skus/"+basic.id(),Map.of("options",List.of(),"price",100,"active",true,"version",0),csrf)));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                    while(System.nanoTime()<deadline) {
                        jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+(member?"%FOR NO KEY UPDATE%":"%FOR UPDATE%")),Boolean.class));
                        if(waiting) { break; }
                        try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    assertTrue(waiting,"SKU request must wait on "+table);
                    jdbc.update("UPDATE marketplace."+table+" SET state=:state WHERE id=:id",Map.of("id",lock,"state",member?"SUSPENDED":"ARCHIVED"));
                });
                var response=future.get().get(5,TimeUnit.SECONDS); assertEquals(409,response.statusCode(),response.body());
                assertEquals("CONFLICT",authMapper.readTree(response.body()).get("code").asText());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_sku WHERE id=:id AND price=0 AND NOT active AND version=0",Map.of("id",basic.id()),Integer.class));
            }
        }
    }
    @Test
    void skuMigrationBackfillsExistingProductsAndCreationRollsBackAtomically() {
        var f=skuFixture("upgrade"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
        var ds=context.getBean(DataSource.class); String schema="p4b_upgrade";
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("14").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO p4b_upgrade.identity_user SELECT * FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner));
        jdbc.update("INSERT INTO p4b_upgrade.seller_store SELECT * FROM marketplace.seller_store WHERE owner_id=:id",Map.of("id",owner));
        jdbc.update("INSERT INTO p4b_upgrade.catalog_category SELECT * FROM marketplace.catalog_category WHERE id=:id",Map.of("id",product.categoryId()));
        jdbc.update("INSERT INTO p4b_upgrade.catalog_product SELECT id,store_id,category_id,slug,title,description,state,is_hidden_by_admin,version FROM marketplace.catalog_product WHERE id=:id",Map.of("id",product.id()));
        var upgrade=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();
        upgrade.migrate(); upgrade.migrate();
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM p4b_upgrade.catalog_sku WHERE product_id=:id AND options='[]' AND price=0 AND NOT active AND version=0 AND code='sku-'||id::text",Map.of("id",product.id()),Integer.class));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.catalog_sku ADD CONSTRAINT p4b_fail_creation CHECK(false) NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,null,new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),"p4b-rollback","rollback","","DRAFT",0L)));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_product WHERE slug='p4b-rollback'",Map.of(),Integer.class));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.catalog_sku DROP CONSTRAINT p4b_fail_creation"); }
    }
    @Test
    void skuConcurrentChangesDuplicatesAndCapacityHaveOneWinner() throws Exception {
        var f=skuFixture("race"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
        var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class); var basic=skus.list(owner,product.id(),0).items().getFirst();
        var updated=race(()->{ try { skus.save(owner,product.id(),basic.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),1L,true,0L)); return "ok"; } catch(com.example.marketplace.catalog.api.CatalogException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,updated.stream().filter("ok"::equals).count());
        var options=List.of(new com.example.marketplace.catalog.api.Skus.Option("색상","빨강"));
        var created=race(()->{ try { skus.save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(options,0L,true,0L)); return "ok"; } catch(com.example.marketplace.catalog.api.CatalogException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,created.stream().filter("ok"::equals).count());
        assertEquals(2,jdbc.queryForObject("SELECT version FROM marketplace.catalog_sku WHERE id=:id",Map.of("id",basic.id()),Integer.class));
        for(int i=0;i<97;i++) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO marketplace.catalog_sku(id,product_id,code,options) VALUES(:id,:product,:code,CAST(:options AS jsonb))",Map.of("id",id,"product",product.id(),"code","sku-"+id,"options","[{\"name\":\"n\",\"value\":\""+i+"\"}]")); }
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        var capacity=race(()->{ try { skus.save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("limit",String.valueOf(sequence.incrementAndGet()))),1L,false,0L)); return "ok"; } catch(com.example.marketplace.catalog.api.CatalogException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,capacity.stream().filter("ok"::equals).count());
        assertEquals(100,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_sku WHERE product_id=:id",Map.of("id",product.id()),Integer.class));
        var all=new java.util.HashSet<UUID>(); for(int page=0;page<5;page++) { var rows=skus.list(owner,product.id(),page); assertEquals(page<4,rows.hasMore()); rows.items().forEach(row->assertTrue(all.add(row.id()))); } assertEquals(100,all.size());
    }
    @Test
    void skuHttpTypesAndPublicFiltersRemainStrict() throws Exception {
        var f=skuFixture("public"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
        var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class); var basic=skus.list(owner,product.id(),0).items().getFirst();
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-public@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String csrf=csrfToken(client), path="/api/seller/products/"+product.id()+"/skus/"+basic.id();
        for(Object price:List.of("10",true,new BigDecimal("1.0"))) { assertEquals(400,accountRequest(client,"PUT",path,Map.of("options",List.of(),"price",price,"active",true,"version",0),csrf).statusCode()); }
        var invalid=new java.util.HashMap<String,Object>(Map.of("options",List.of(),"active",true,"version",0)); invalid.put("price",null);
        assertEquals(400,accountRequest(client,"PUT",path,invalid,csrf).statusCode());
        assertEquals(400,accountRequest(client,"PUT",path,Map.of("options",List.of(),"price",10,"active","true","version",0),csrf).statusCode());
        assertEquals(400,accountRequest(client,"PUT",path,Map.of("options",List.of(),"price",10,"active",true,"version",new BigDecimal("0.1")),csrf).statusCode());
        skus.save(owner,product.id(),basic.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),0L,true,0L));
        context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),"","ACTIVE",0L));
        var guest=authBrowser(); String url="/api/products/"+product.slug()+"/skus";
        assertEquals(1,authMapper.readTree(accountRequest(guest,"GET",url,null,null).body()).get("items").size());
        jdbc.update("UPDATE marketplace.catalog_category SET active=false WHERE id=:id",Map.of("id",product.categoryId())); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        jdbc.update("UPDATE marketplace.catalog_category SET active=true WHERE id=:id",Map.of("id",product.categoryId()));
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",product.id())); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=false WHERE id=:id",Map.of("id",product.id()));
        jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE owner_id=:id",Map.of("id",owner)); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        jdbc.update("UPDATE marketplace.seller_store SET visible=true WHERE owner_id=:id",Map.of("id",owner));
        jdbc.update("UPDATE marketplace.seller_store SET visible=false,state='SUSPENDED' WHERE owner_id=:id",Map.of("id",owner));
        assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        assertEquals(409,accountRequest(client,"PUT",path,Map.of("options",List.of(),"price",10,"active",true,"version",1),csrf).statusCode());
        jdbc.update("UPDATE marketplace.seller_store SET visible=true,state='OPEN' WHERE owner_id=:id",Map.of("id",owner));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",owner)); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner)); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
    }
    @Test
    void skuOptionsPricesOwnershipAndVersionsAreEnforcedOverHttp() throws Exception {
        var f=skuFixture("rules"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-rules@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String csrf=csrfToken(client), path="/api/seller/products/"+product.id()+"/skus";
        var basic=authMapper.readTree(accountRequest(client,"GET",path,null,null).body()).get("items").get(0);
        var enabled=accountRequest(client,"PUT",path+"/"+basic.get("id").asText(),Map.of("options",List.of(),"price",100,"active",true,"version",0),csrf);
        assertEquals(200,enabled.statusCode(),enabled.body());
        var opts=List.of(Map.of("name"," 크기 ","value"," M "),Map.of("name","색상","value","빨강"));
        var input=Map.of("options",opts,"price",2500,"active",true,"version",0);
        assertEquals(403,accountRequest(client,"POST",path,input,null).statusCode());
        var created=accountRequest(client,"POST",path,input,csrf); assertEquals(201,created.statusCode(),created.body());
        var sku=authMapper.readTree(created.body()); String id=sku.get("id").asText();
        var rows=authMapper.readTree(accountRequest(client,"GET",path,null,null).body()).get("items");
        assertEquals(2,rows.size()); assertEquals(1,java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(row->row.get("active").asBoolean()).count());
        var normalized=List.of(Map.of("name","색상","value","빨강"),Map.of("name","크기","value","M"));
        assertEquals(409,accountRequest(client,"POST",path,Map.of("options",normalized,"price",1,"active",false,"version",0),csrf).statusCode());
        assertEquals(409,accountRequest(client,"POST",path,Map.of("options",List.of(),"price",0,"active",false,"version",0),csrf).statusCode());
        assertEquals(201,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","café","value","é")),"price",0,"active",false,"version",0),csrf).statusCode());
        assertEquals(409,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","cafe\u0301","value","e\u0301")),"price",0,"active",false,"version",0),csrf).statusCode());
        assertEquals(400,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","x".repeat(41),"value","v")),"price",0,"active",false,"version",0),csrf).statusCode());
        assertEquals(201,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","문자경계","value","😀".repeat(40))),"price",0,"active",false,"version",0),csrf).statusCode());
        for(String invalid:List.of("😀".repeat(41),"a\nb")) {
            assertEquals(400,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","문자경계","value",invalid)),"price",0,"active",false,"version",0),csrf).statusCode());
        }
        assertEquals(400,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","a","value","1"),Map.of("name","b","value","2"),Map.of("name","c","value","3"),Map.of("name","d","value","4")),"price",0,"active",false,"version",0),csrf).statusCode());
        for(Object price:List.of(-1,new BigDecimal("1.5"),1000000000L)) {
            assertEquals(400,accountRequest(client,"PUT",path+"/"+id,Map.of("options",normalized,"price",price,"active",true,"version",0),csrf).statusCode());
        }
        assertEquals(400,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","색상","value","a"),Map.of("name"," 색상 ","value","b")),"price",1,"active",true,"version",0),csrf).statusCode());
        assertEquals(409,accountRequest(client,"PUT",path+"/"+basic.get("id").asText(),Map.of("options",List.of(),"price",0,"active",true,"version",2),csrf).statusCode());
        assertEquals(409,accountRequest(client,"PUT",path+"/"+id,Map.of("options",List.of(Map.of("name","색상","value","변경")),"price",0,"active",true,"version",0),csrf).statusCode());
        assertEquals(200,accountRequest(client,"PUT",path+"/"+id,Map.of("options",normalized,"price",999999999,"active",false,"version",0),csrf).statusCode());
        assertEquals(409,accountRequest(client,"PUT",path+"/"+id,Map.of("options",normalized,"price",0,"active",false,"version",0),csrf).statusCode());
        var other=skuFixture("foreign"); var outsider=authBrowser(); assertEquals(200,authRequest(outsider,"/api/auth/login",loginForm("p4b-foreign@example.com",SIGNUP_PASSWORD),csrfToken(outsider)).statusCode());
        assertEquals(404,accountRequest(outsider,"GET",path,null,null).statusCode());
        assertEquals(404,accountRequest(outsider,"POST",path,input,csrfToken(outsider)).statusCode());
        String otherPath="/api/seller/products/"+((com.example.marketplace.catalog.api.Products.Product)other.get("product")).id()+"/skus/"+id;
        assertEquals(404,accountRequest(outsider,"PUT",otherPath,Map.of("options",normalized,"price",1,"active",true,"version",1),csrfToken(outsider)).statusCode());
        jdbc.update("UPDATE marketplace.catalog_product SET state='ARCHIVED' WHERE id=:id",Map.of("id",product.id()));
        assertEquals(409,accountRequest(client,"PUT",path+"/"+id,Map.of("options",normalized,"price",0,"active",false,"version",1),csrf).statusCode());
    }
    @Test
    void productCreatesOneInactiveDefaultSkuWithPublicEligibility() throws Exception {
        var fixture=skuFixture("default"); var owner=(UUID)fixture.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)fixture.get("product");
        var client=authBrowser();
        assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-default@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        var response=accountRequest(client,"GET","/api/seller/products/"+product.id()+"/skus",null,null);
        assertEquals(200,response.statusCode(),response.body());
        var items=authMapper.readTree(response.body()).get("items");
        assertEquals(1,items.size()); assertEquals(0,items.get(0).get("price").asLong());
        assertFalse(items.get(0).get("active").asBoolean()); assertEquals(0,items.get(0).get("options").size());
        var code=items.get(0).get("code").asText(); assertTrue(code.startsWith("sku-"));
        assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/"+product.slug()+"/skus",null,null).statusCode());
        context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),"","ACTIVE",0L));
        var visible=accountRequest(authBrowser(),"GET","/api/products/"+product.slug()+"/skus",null,null);
        assertEquals(200,visible.statusCode()); assertEquals(0,authMapper.readTree(visible.body()).get("items").size());
    }
    private Map<String,Object> skuFixture(String suffix) {
        UUID owner=activeMember("p4b-"+suffix+"@example.com"), category=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p4b-"+suffix,"P04B","intro","address","","","",true,0L));
        jdbc.update("INSERT INTO marketplace.catalog_category(id,name) VALUES(:id,:name)",Map.of("id",category,"name","P04B "+suffix));
        var product=context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,null,new com.example.marketplace.catalog.api.Products.Input(category,"p4b-"+suffix,"SKU 상품","","DRAFT",0L));
        return Map.of("owner",owner,"product",product);
    }
    @Test
    void storeHistoryKeepsCausalOrderWhenRecordedClockMovesBackwardOrTies() {
        UUID owner=activeMember("p4-history-owner@example.com"), admin=activeMember("p4-history-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",admin));
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);
        try {
            var store=stores.save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-history","P04","intro","address","","","",true,0L));
            stores.transition(admin,store.id(),"suspend",new com.example.marketplace.seller.api.Stores.Change(0L,"suspend"));
            stores.transition(admin,store.id(),"restore",new com.example.marketplace.seller.api.Stores.Change(1L,"restore"));
            stores.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(2L,"close"));
            jdbc.update("UPDATE marketplace.store_operation_event SET occurred_at='2000-01-01T00:00:00Z' WHERE store_id=:id AND action='close'",Map.of("id",store.id()));
            assertEquals(List.of("close","restore","suspend"),stores.history(owner,store.id(),false).stream().map(com.example.marketplace.seller.api.Stores.Event::action).toList());
            jdbc.update("UPDATE marketplace.store_operation_event SET occurred_at='2000-01-01T00:00:00Z' WHERE store_id=:id",Map.of("id",store.id()));
            assertEquals(List.of("close","restore","suspend"),stores.history(admin,store.id(),true).stream().map(com.example.marketplace.seller.api.Stores.Event::action).toList());
            jdbc.update("INSERT INTO marketplace.store_operation_event(id,store_id,actor_id,action,before_state,after_state,reason,occurred_at) VALUES(:event,:store,:actor,'suspend','OPEN','SUSPENDED','legacy','2100-01-01T00:00:00Z')",Map.of("event",UUID.randomUUID(),"store",store.id(),"actor",admin));
            assertEquals(List.of("close","restore","suspend","legacy"),stores.history(owner,store.id(),false).stream().map(com.example.marketplace.seller.api.Stores.Event::reason).toList());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.store_operation_event WHERE store_id=:id AND store_version IS NULL",Map.of("id",store.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }
    @Test
    void productSaveWaitingForSuspendedMemberReturnsConflictWithoutWrite() throws Exception {
        UUID owner=activeMember("p4-suspend-race@example.com"), category=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-suspend-race","P04","intro","address","","","",true,0L));
        jdbc.update("INSERT INTO marketplace.catalog_category(id,name) VALUES(:id,'P04 suspend race')",Map.of("id",category));
        var client=authBrowser();
        assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4-suspend-race@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String csrf=csrfToken(client);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class);
                future.set(executor.submit(()->accountRequest(client,"POST","/api/seller/products",Map.of("categoryId",category,"slug","p04-suspend-product","title","상품","description","","state","DRAFT","version",0),csrf)));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%'))",Map.of(),Boolean.class));
                    if(waiting) { break; }
                    try { Thread.sleep(20); } catch(InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                }
                assertTrue(waiting,"product write must wait on member lock");
                jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
            });
            var response=future.get().get(5,TimeUnit.SECONDS);
            assertEquals(409,response.statusCode(),response.body());
            assertEquals("CONFLICT",authMapper.readTree(response.body()).get("code").asText());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_product WHERE slug='p04-suspend-product'",Map.of(),Integer.class));
        }
    }
    @Test
    void memberReadsNeedOnlyOneDatabaseConnection() {
        UUID member=activeMember("p4-single-connection@example.com");
        var original=(com.zaxxer.hikari.HikariDataSource)context.getBean(DataSource.class);
        try(var single=new com.zaxxer.hikari.HikariDataSource()) {
            single.setJdbcUrl(original.getJdbcUrl()); single.setUsername(original.getUsername()); single.setPassword(original.getPassword());
            single.setMaximumPoolSize(1); single.setMinimumIdle(0); single.setConnectionTimeout(300);
            var members=new com.example.marketplace.identity.infrastructure.LoginMembers(new NamedParameterJdbcTemplate(single));
            assertEquals(member.toString(),members.current(member.toString()).getUsername());
            assertEquals(member.toString(),members.loadUserByUsername("p4-single-connection@example.com").getUsername());
        }
    }

    @Test
    void publicStoreReadNeedsOnlyOneDatabaseConnection() {
        UUID owner=activeMember("p4-single-store@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-single-store","P04","intro","address","","","",true,0L));
        var original=(com.zaxxer.hikari.HikariDataSource)context.getBean(DataSource.class);
        try(var single=new com.zaxxer.hikari.HikariDataSource()) {
            single.setJdbcUrl(original.getJdbcUrl()); single.setUsername(original.getUsername()); single.setPassword(original.getPassword());
            single.setMaximumPoolSize(1); single.setMinimumIdle(0); single.setConnectionTimeout(300);
            var template=new NamedParameterJdbcTemplate(single);
            var service=new com.example.marketplace.seller.infrastructure.JdbcStores(template,context.getBean(com.example.marketplace.identity.api.SellerMembership.class),new com.example.marketplace.identity.infrastructure.JdbcAccessPolicy(template),transactionManager);
            assertEquals("p04-single-store",service.published("p04-single-store").slug());
        }
    }
    @Test
    void categoryWaiterRechecksRevokedPermissionAndWritesNoAudit() throws Exception {
        UUID admin=activeMember("p4-revoked-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var categories=context.getBean(com.example.marketplace.catalog.api.Categories.class);
        var category=categories.save(admin,null,new com.example.marketplace.catalog.api.Categories.Input("P04 revoked",true,0L));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.catalog_category WHERE id=:id FOR UPDATE",Map.of("id",category.id()),UUID.class);
                future.set(executor.submit(()->{
                    try { categories.save(admin,category.id(),new com.example.marketplace.catalog.api.Categories.Input("unauthorized",false,0L)); return "changed"; }
                    catch(com.example.marketplace.identity.api.AccessRejected rejected) { return "denied"; }
                }));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%catalog_category%FOR UPDATE%')",Map.of(),Boolean.class));
                    if(waiting) { break; }
                    try { Thread.sleep(20); } catch(InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                }
                assertTrue(waiting,"must observe category lock wait before revoking permission");
                jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            });
            assertEquals("denied",future.get().get(5,TimeUnit.SECONDS));
            assertEquals("P04 revoked",jdbc.queryForObject("SELECT name FROM marketplace.catalog_category WHERE id=:id",Map.of("id",category.id()),String.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.category_event WHERE category_id=:id",Map.of("id",category.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }

    @Test
    void productDescriptionAcceptsMultilineButTitleRemainsSingleLine() {
        UUID owner=activeMember("p4-multiline@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-multiline","P04","intro","address","","","",true,0L));
        UUID category=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.catalog_category(id,name) VALUES(:id,'P04 multiline')",Map.of("id",category));
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        var input=new com.example.marketplace.catalog.api.Products.Input(category,"p04-multiline-product","title","첫 줄\r\n둘째 줄\t설명","DRAFT",0L);
        var saved=products.save(owner,null,input);
        assertEquals("첫 줄\n둘째 줄\t설명",products.own(owner,saved.id()).description());
        assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(category,"p04-invalid","첫 줄\n둘째 줄","","DRAFT",0L))).status());
        assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(category,"p04-control","title","control\u0000","DRAFT",0L))).status());
    }
    @Test
    void catalogValidationPublicEligibilityAndConcurrentUpdates() throws Exception {
        UUID admin=activeMember("p4-bounds-admin@example.com"), owner=activeMember("p4-bounds-owner@example.com"), buyer=activeMember("p4-bounds-buyer@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        var categories=context.getBean(com.example.marketplace.catalog.api.Categories.class);
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);
        var catInput=new com.example.marketplace.catalog.api.Categories.Input("P04 boundaries",true,0L);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->categories.save(admin,null,catInput));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        try {
            var cat=categories.save(admin,null,catInput);
            var store=stores.save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-boundaries","P04","intro","address","","","",true,0L));
            var draft=new com.example.marketplace.catalog.api.Products.Input(cat.id(),"p04-bounds","😀".repeat(120),"","DRAFT",0L);
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->products.save(buyer,null,draft));
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(cat.id(),"p04-long","a".repeat(121),"","DRAFT",0L))).status());
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(cat.id(),"p04-long","ok","x".repeat(4001),"DRAFT",0L))).status());
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(cat.id(),"p04-long","ok","","ACTIVE",0L))).status());
            var product=products.save(owner,null,draft);
            assertEquals(409,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,draft)).status());
            var active=new com.example.marketplace.catalog.api.Products.Input(cat.id(),draft.slug(),draft.title(),"","ACTIVE",0L);
            var results=race(()->{ try { products.save(owner,product.id(),active); return "ok"; } catch(com.example.marketplace.catalog.api.CatalogException failure) { assertEquals(409,failure.status()); return "conflict"; } });
            assertEquals(1,results.stream().filter("ok"::equals).count());
            assertEquals(1,products.own(owner,product.id()).version());
            assertEquals(draft.slug(),products.published(draft.slug()).slug());
            assertEquals(409,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(cat.id(),draft.slug(),"ok","","DRAFT",1L))).status());
            for(String restriction:List.of("store-private","store-suspended","seller-suspended","seller-role","category")) {
                switch(restriction) {
                    case "store-private" -> jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE id=:id",Map.of("id",store.id()));
                    case "store-suspended" -> jdbc.update("UPDATE marketplace.seller_store SET visible=false,state='SUSPENDED' WHERE id=:id",Map.of("id",store.id()));
                    case "seller-suspended" -> jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
                    case "seller-role" -> jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",owner));
                    default -> jdbc.update("UPDATE marketplace.catalog_category SET active=false WHERE id=:id",Map.of("id",cat.id()));
                }
                assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.published(draft.slug())).status());
                assertFalse(products.published(0).items().stream().anyMatch(published->published.slug().equals(draft.slug())));
                jdbc.update("UPDATE marketplace.seller_store SET state='OPEN',visible=true WHERE id=:id",Map.of("id",store.id()));
                jdbc.update("UPDATE marketplace.identity_user SET state='ACTIVE' WHERE id=:id",Map.of("id",owner));
                jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER') ON CONFLICT DO NOTHING",Map.of("id",owner));
                jdbc.update("UPDATE marketplace.catalog_category SET active=true WHERE id=:id",Map.of("id",cat.id()));
                assertEquals(draft.slug(),products.published(draft.slug()).slug());
            }
            var sequence=new java.util.concurrent.atomic.AtomicInteger();
            race(()->{
                if(sequence.getAndIncrement()==0) { categories.save(admin,cat.id(),new com.example.marketplace.catalog.api.Categories.Input(cat.name(),false,0L)); return "disabled"; }
                try { products.save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(cat.id(),draft.slug(),"edited","","ACTIVE",1L)); return "edited"; }
                catch(com.example.marketplace.catalog.api.CatalogException failure) { assertEquals(409,failure.status()); return "conflict"; }
            });
            assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.published(draft.slug())).status());
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.published(-1)).status());
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->categories.list(null,10001)).status());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }
    @Test
    void categoryAuditIsAtomicAndOwnedByOperator() {
        UUID admin=activeMember("p4-audit-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var categories=context.getBean(com.example.marketplace.catalog.api.Categories.class);
        try {
            var input=new com.example.marketplace.catalog.api.Categories.Input("P04 audit",true,0L);
            var created=categories.save(admin,null,input);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.category_event WHERE category_id=:id AND actor_id=:actor",Map.of("id",created.id(),"actor",admin),Integer.class));
            jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.category_event ADD CONSTRAINT p4_fail_audit CHECK(category_id <> '"+created.id()+"'::uuid) NOT VALID");
            try { assertThrows(DataIntegrityViolationException.class,()->categories.save(admin,created.id(),new com.example.marketplace.catalog.api.Categories.Input("P04 lost",false,0L))); }
            finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.category_event DROP CONSTRAINT p4_fail_audit"); }
            assertEquals("P04 audit",jdbc.queryForObject("SELECT name FROM marketplace.catalog_category WHERE id=:id",Map.of("id",created.id()),String.class));
            assertEquals(0,jdbc.queryForObject("SELECT version FROM marketplace.catalog_category WHERE id=:id",Map.of("id",created.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }
    @Test
    void catalogProductHttpOwnershipLifecycleAndPublicFilters() throws Exception {
        UUID admin=activeMember("p4-product-admin@example.com"), owner=activeMember("p4-product-owner@example.com"), other=activeMember("p4-product-other@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        for(UUID id:List.of(owner,other)) { jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",id)); }
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);
        var store=stores.save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-store","P04","intro","address","","","",true,0L));
        stores.save(other,null,new com.example.marketplace.seller.api.Stores.Input("p04-other","P04 other","intro","address","","","",true,0L));
        var adminClient=authBrowser(); var seller=authBrowser(); var stranger=authBrowser();
        assertEquals(200,authRequest(adminClient,"/api/auth/login",loginForm("p4-product-admin@example.com",SIGNUP_PASSWORD),csrfToken(adminClient)).statusCode());
        assertEquals(200,authRequest(seller,"/api/auth/login",loginForm("p4-product-owner@example.com",SIGNUP_PASSWORD),csrfToken(seller)).statusCode());
        assertEquals(200,authRequest(stranger,"/api/auth/login",loginForm("p4-product-other@example.com",SIGNUP_PASSWORD),csrfToken(stranger)).statusCode());
        try {
            var category=accountRequest(adminClient,"POST","/api/admin/content/categories",Map.of("name","P04 product category","active",true,"version",0),csrfToken(adminClient));
            assertEquals(201,category.statusCode());
            String categoryId=authMapper.readTree(category.body()).get("id").asText();
            var body=new java.util.HashMap<String,Object>(Map.of("slug","p04-product","title","상품","description","소개","categoryId",categoryId,"state","DRAFT","version",0));
            assertEquals(403,accountRequest(seller,"POST","/api/seller/products",body,null).statusCode());
            var created=accountRequest(seller,"POST","/api/seller/products",body,csrfToken(seller));
            assertEquals(201,created.statusCode(),created.body());
            String id=authMapper.readTree(created.body()).get("id").asText();
            String path="/api/seller/products/"+id;
            assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null).statusCode());
            assertEquals(404,accountRequest(stranger,"PUT",path,body,csrfToken(stranger)).statusCode());
            assertEquals(404,accountRequest(stranger,"GET",path,null,null).statusCode());
            body.put("state","ACTIVE");
            assertEquals(200,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            assertEquals(409,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            var published=accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null);
            assertEquals(200,published.statusCode()); assertFalse(published.body().contains("ownerId")); assertFalse(published.body().contains("hiddenByAdmin"));
            jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",UUID.fromString(id)));
            body.put("version",1); body.put("isHiddenByAdmin",false);
            assertEquals(200,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null).statusCode());
            assertFalse(accountRequest(authBrowser(),"GET","/api/products",null,null).body().contains("p04-product"));
            jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=false WHERE id=:id",Map.of("id",UUID.fromString(id)));
            assertEquals(200,accountRequest(adminClient,"PUT","/api/admin/content/categories/"+categoryId,Map.of("name","P04 product category","active",false,"version",0),csrfToken(adminClient)).statusCode());
            body.put("version",2);
            assertEquals(409,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null).statusCode());
            body.put("state","STOPPED");
            assertEquals(200,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            body.put("version",3); body.put("state","ARCHIVED");
            assertEquals(200,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            body.put("version",4); body.put("state","ACTIVE");
            assertEquals(409,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null).statusCode());
            assertFalse(accountRequest(authBrowser(),"GET","/api/products",null,null).body().contains("p04-product"));
            stores.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(0L,"closed"));
            body.put("slug","p04-new"); body.put("state","DRAFT"); body.put("version",0);
            assertEquals(409,accountRequest(seller,"POST","/api/seller/products",body,csrfToken(seller)).statusCode());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }
    @Test
    void catalogCategoryHttpContract() throws Exception {
        UUID admin=activeMember("p4-category-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var browser=authBrowser();
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("p4-category-admin@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        try {
            var body=Map.of("name","P04 category","active",true,"version",0);
            assertEquals(403,accountRequest(browser,"POST","/api/admin/content/categories",body,null).statusCode());
            var created=accountRequest(browser,"POST","/api/admin/content/categories",body,csrfToken(browser));
            assertEquals(201,created.statusCode(),created.body());
            String id=authMapper.readTree(created.body()).get("id").asText();
            assertEquals(409,accountRequest(browser,"POST","/api/admin/content/categories",body,csrfToken(browser)).statusCode());
            assertEquals(200,accountRequest(browser,"PUT","/api/admin/content/categories/"+id,Map.of("name","P04 edited","active",false,"version",0),csrfToken(browser)).statusCode());
            assertEquals(409,accountRequest(browser,"PUT","/api/admin/content/categories/"+id,body,csrfToken(browser)).statusCode());
            assertEquals(400,accountRequest(browser,"POST","/api/admin/content/categories",Map.of("name"," ","active",true,"version",0),csrfToken(browser)).statusCode());
            assertEquals(200,accountRequest(authBrowser(),"GET","/api/categories",null,null).statusCode());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }
    @Test
    void storeOperatorPermissionsAndConcurrentEditSuspendKeepRestriction() throws Exception {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3c-permissions-owner@example.com"), admin=activeMember("p3c-permissions-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        var input=new com.example.marketplace.seller.api.Stores.Input("permission-store","모의","소개","가상 주소","","","",true,0L);
        var store=service.save(owner,null,input);
        var change=new com.example.marketplace.seller.api.Stores.Change(0L,"모의 정지");
        try {
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.transition(admin,store.id(),"suspend",change));
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.queue(admin,0));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",owner));
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.transition(owner,store.id(),"suspend",change));
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",owner));
            assertEquals(403,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.transition(owner,store.id(),"suspend",change)).status());
            assertEquals(store,service.own(owner));
            assertTrue(service.history(owner,store.id(),false).isEmpty());
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",admin));
            var sequence=new java.util.concurrent.atomic.AtomicInteger();
            var results=race(()->{
                try {
                    if(sequence.getAndIncrement()==0) { service.save(owner,store.id(),input); return "edit"; }
                    service.transition(admin,store.id(),"suspend",change); return "suspend";
                } catch(com.example.marketplace.seller.api.StoreException failure) { assertEquals(409,failure.status()); return "conflict"; }
            });
            assertEquals(1,results.stream().filter("conflict"::equals).count());
            assertEquals(1,service.own(owner).version());
            if(results.contains("edit")) {
                service.transition(admin,store.id(),"suspend",new com.example.marketplace.seller.api.Stores.Change(1L,"최신 버전 정지"));
            }
            var suspended=service.own(owner);
            assertEquals("SUSPENDED",suspended.state());
            assertFalse(suspended.visible());
            var updated=new com.example.marketplace.seller.api.Stores.Input(input.slug(),"덮어쓰기",input.introduction(),input.address(),"","","",true,suspended.version());
            assertEquals(409,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,store.id(),updated)).status());
            assertEquals(suspended,service.own(owner));
            assertFalse(service.capabilities(owner).manageCatalog());
            assertFalse(service.capabilities(owner).newSales());
            assertTrue(service.capabilities(owner).existingTrades());
            service.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(suspended.version(),"정지 중 폐점"));
            assertEquals("CLOSED",service.own(owner).state());
            assertEquals(2,service.history(owner,store.id(),false).size());
        } finally {
            for(UUID id:List.of(owner,admin)) {
                jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",id));
                jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",id));
            }
        }
    }
    @Test
    void storeCloseIsAtomicConcurrentAndKeepsCapabilityBoundary() throws Exception {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3c-atomic@example.com"), other=activeMember("p3c-other@example.com");
        for(UUID id:List.of(owner,other)) { jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",id)); }
        var input=new com.example.marketplace.seller.api.Stores.Input("atomic-store","모의","소개","가상 주소","","","",true,0L);
        var store=service.save(owner,null,input);
        var change=new com.example.marketplace.seller.api.Stores.Change(0L,"😀".repeat(300));
        assertEquals(404,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.transition(other,store.id(),"close",change)).status());
        assertEquals(404,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.history(other,store.id(),false)).status());
        for(String reason:List.of(" ","가".repeat(301))) {
            assertEquals(400,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(0L,reason))).status());
        }
        assertThrows(IllegalStateException.class,()->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES));
        new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.store_operation_event ADD CONSTRAINT p3c_reject_event CHECK (store_id <> '"+store.id()+"'::uuid) NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->service.transition(owner,store.id(),"close",change));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.store_operation_event DROP CONSTRAINT p3c_reject_event"); }
        assertEquals(store,service.own(owner));
        assertTrue(service.history(owner,store.id(),false).isEmpty());
        var results=race(()->{ try { service.transition(owner,store.id(),"close",change); return "ok"; }
            catch(com.example.marketplace.seller.api.StoreException failure) { assertEquals(409,failure.status()); return "conflict"; } });
        assertEquals(1,results.stream().filter("ok"::equals).count());
        assertEquals("CLOSED",service.own(owner).state());
        assertFalse(service.own(owner).visible());
        assertEquals(1,service.history(owner,store.id(),false).size());
        assertEquals(change.reason(),service.history(owner,store.id(),false).getFirst().reason());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,store.id(),input));
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,null,input));
        assertFalse(service.capabilities(owner).manageCatalog());
        assertFalse(service.capabilities(owner).newSales());
        new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.EXISTING_TRADES));
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES)));
    }
    @Test
    void storeLifecycleHttpKeepsBuyingAndRestrictsNewSales() throws Exception {
        UUID owner=activeMember("p3c-owner@example.com"), admin=activeMember("p3c-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",admin));
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        var input=new com.example.marketplace.seller.api.Stores.Input("lifecycle-store","모의 매대","소개","가상 주소","","","",true,0L);
        var store=service.save(owner,null,input);
        var buyer=authBrowser(); var operator=authBrowser();
        assertEquals(200,authRequest(buyer,"/api/auth/login",loginForm("p3c-owner@example.com",SIGNUP_PASSWORD),csrfToken(buyer)).statusCode());
        assertEquals(200,authRequest(operator,"/api/auth/login",loginForm("p3c-admin@example.com",SIGNUP_PASSWORD),csrfToken(operator)).statusCode());
        String path="/api/admin/accounts/stores/"+store.id();
        try {
            assertEquals(403,accountRequest(buyer,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),csrfToken(buyer)).statusCode());
            assertEquals(403,accountRequest(operator,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),null).statusCode());
            assertEquals(200,accountRequest(operator,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),csrfToken(operator)).statusCode());
            assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/lifecycle-store",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/account/profile",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/seller/stores",null,null).statusCode());
            var capabilities=authMapper.readTree(accountRequest(buyer,"GET","/api/seller/capabilities",null,null).body());
            assertFalse(capabilities.get("manageCatalog").asBoolean());
            assertFalse(capabilities.get("newSales").asBoolean());
            assertTrue(capabilities.get("existingTrades").asBoolean());
            assertEquals(200,accountRequest(operator,"POST",path+"/restore",Map.of("version",1,"reason","모의 복구"),csrfToken(operator)).statusCode());
            assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/lifecycle-store",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"POST","/api/seller/stores/"+store.id()+"/close",Map.of("version",2,"reason","모의 폐점"),csrfToken(buyer)).statusCode());
            assertEquals(409,accountRequest(operator,"POST",path+"/restore",Map.of("version",3,"reason","재개 불가"),csrfToken(operator)).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/account/profile",null,null).statusCode());
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM marketplace.store_operation_event WHERE store_id=:id",Map.of("id",store.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }
    @Test
    void storeInputLimitsAndInvalidUpdatesLeaveSavedValuesUnchanged() {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3b-limits@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        var max=new com.example.marketplace.seller.api.Stores.Input("s".repeat(63),"😀".repeat(80),"소".repeat(1000),"주".repeat(200),"공".repeat(1000),"i".repeat(100),"그".repeat(120),true,0L);
        var saved=service.save(owner,null,max);
        assertEquals(max.name(),saved.name());
        assertEquals(max.imageAlt(),service.published(max.slug()).imageAlt());
        var invalid=List.of(
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name()+"x",max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction()+"x",max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address()+"x",max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice()+"x",max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice(),max.imageKey()+"x",max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt()+"x",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug()+"x",max.name(),max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug()," ",max.introduction(),max.address(),"","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름"," ",max.address(),"","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개"," ","","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개","주소","","key","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개","주소","","","alt",true,0L));
        for(var input:invalid) {
            assertEquals(400,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,saved.id(),input)).status());
            assertEquals(saved,service.own(owner));
        }
    }
    @Test
    void storeConcurrentCreateAndUpdateAndValidation() throws Exception {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3b-race@example.com");
        var input=new com.example.marketplace.seller.api.Stores.Input("race-store","모의","소개","가상 주소","","asset-1","가상 그림",false,0L);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.save(owner,null,input));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        var created=race(()->{ try { service.save(owner,null,input); return "ok"; } catch(com.example.marketplace.seller.api.StoreException ex) { assertEquals(409,ex.status()); return "conflict"; } });
        assertEquals(1,created.stream().filter("ok"::equals).count());
        var current=service.own(owner);
        var updated=race(()->{ try { service.save(owner,current.id(),input); return "ok"; } catch(com.example.marketplace.seller.api.StoreException ex) { assertEquals(409,ex.status()); return "conflict"; } });
        assertEquals(1,updated.stream().filter("ok"::equals).count());
        assertEquals(1,service.own(owner).version());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,current.id(),new com.example.marketplace.seller.api.Stores.Input("changed-slug","모의","소개","주소","","","",false,1L)));
        for(String slug:List.of("aa","UPPER","../bad","-bad","bad-")) {
            assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,null,new com.example.marketplace.seller.api.Stores.Input(slug,"모의","소개","주소","","","",false,0L)));
        }
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,current.id(),new com.example.marketplace.seller.api.Stores.Input("race-store","모의","소개","주소","","https://external","그림",false,1L)));
        assertEquals(1,service.own(owner).version());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.published("race-store"));
        jdbc.update("UPDATE marketplace.identity_user SET state='WITHDRAWN' WHERE id=:id",Map.of("id",owner));
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.save(owner,current.id(),input));
    }
    @Test
    void storeHttpOwnershipVisibilityAndVersion() throws Exception {
        UUID owner = activeMember("p3b-owner@example.com");
        UUID other = activeMember("p3b-other@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", other));
        var browser = authBrowser();
        var stranger = authBrowser();
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("p3b-owner@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        assertEquals(200,authRequest(stranger,"/api/auth/login",loginForm("p3b-other@example.com",SIGNUP_PASSWORD),csrfToken(stranger)).statusCode());
        var fields = new java.util.HashMap<String,Object>(Map.of("slug","sample-store","name","모의 매대","introduction","가상 소개","address","모의 주소","notice","","imageKey","","imageAlt","","visible",false,"version",0));
        assertEquals(403, accountRequest(browser,"POST","/api/seller/stores", fields,null).statusCode());
        var created = accountRequest(browser,"POST","/api/seller/stores",fields,csrfToken(browser));
        assertEquals(201,created.statusCode());
        var store = new tools.jackson.databind.ObjectMapper().readTree(created.body());
        String path = "/api/seller/stores/" + store.get("id").asText();
        assertEquals(409,accountRequest(browser,"POST","/api/seller/stores",fields,csrfToken(browser)).statusCode());
        assertEquals(409,accountRequest(stranger,"POST","/api/seller/stores",fields,csrfToken(stranger)).statusCode());
        assertEquals(404,accountRequest(stranger,"PUT",path,fields,csrfToken(stranger)).statusCode());
        assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null).statusCode());
        fields.put("visible",true);
        assertEquals(200,accountRequest(browser,"PUT",path,fields,csrfToken(browser)).statusCode());
        assertEquals(409,accountRequest(browser,"PUT",path,fields,csrfToken(browser)).statusCode());
        var published = accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null);
        assertEquals(200,published.statusCode());
        assertFalse(published.body().contains(owner.toString()));
        assertFalse(published.body().contains("version"));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
        assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null).statusCode());
    }

    @Test
    void sellerDecisionsRejectInvalidTransitionsAndRollbackRoleAndHistory() {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID operator = activeMember("p3-reviewer@example.com");
        UUID applicant = activeMember("p3-rejected@example.com");
        var input = new com.example.marketplace.seller.api.SellerApplications.Input("가상 매대", "가상 계획");
        var first = service.submit(applicant, input);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> service.decide(operator, first.id(), "APPROVED", "사유"));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", operator));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", operator));
        try {
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "PENDING", "사유"));
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "APPROVED", " "));
            var rejected = service.decide(operator, first.id(), "REJECTED", "계획 보완 필요");
            assertEquals("REJECTED", rejected.status());
            assertEquals("계획 보완 필요", rejected.events().getLast().reason());
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "APPROVED", "사유"));
            var second = service.submit(applicant, input);
            assertEquals(2, service.own(applicant, 0).items().size());
            var self = service.submit(operator, input);
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, self.id(), "APPROVED", "자기 심사"));
            jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.seller_application_event ADD CONSTRAINT p3_reject_event CHECK (state <> 'APPROVED') NOT VALID");
            try {
                assertThrows(DataIntegrityViolationException.class, () -> service.decide(operator, second.id(), "APPROVED", "승인"));
            } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.seller_application_event DROP CONSTRAINT p3_reject_event"); }
            assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM marketplace.seller_application WHERE id=:id", Map.of("id", second.id()), String.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.seller_application_event WHERE application_id=:id", Map.of("id", second.id()), Integer.class));
            assertEquals(List.of("BUYER"), jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id=:id", Map.of("id", applicant), String.class));
            jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", applicant));
            assertThrows(AccountException.class, () -> service.decide(operator, second.id(), "APPROVED", "승인"));
            jdbc.update("UPDATE marketplace.identity_user SET state='WITHDRAWN' WHERE id=:id", Map.of("id", applicant));
            assertThrows(AccountException.class, () -> service.decide(operator, second.id(), "REJECTED", "반려"));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", operator));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", operator));
        }
    }

    @Test
    void sellerConcurrentApplicationsAndDecisionsHaveOneWinner() throws Exception {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID applicant = activeMember("p3-race-buyer@example.com");
        UUID operator = activeMember("p3-race-admin@example.com");
        var input = new com.example.marketplace.seller.api.SellerApplications.Input("경쟁 매대", "모의 판매");
        var submitted = race(() -> {
            try { service.submit(applicant, input); return "ok"; }
            catch (com.example.marketplace.seller.api.SellerApplicationException conflict) { return "blocked"; }
        });
        assertEquals(1, submitted.stream().filter("ok"::equals).count());
        assertEquals(1, submitted.stream().filter("blocked"::equals).count());
        UUID id = service.own(applicant, 0).items().getFirst().id();
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", operator));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", operator));
        try {
            var turn = new java.util.concurrent.atomic.AtomicInteger();
            var decisions = race(() -> {
                try { service.decide(operator, id, turn.getAndIncrement() == 0 ? "APPROVED" : "REJECTED", "경쟁 심사"); return "ok"; }
                catch (com.example.marketplace.seller.api.SellerApplicationException conflict) { return "blocked"; }
            });
            assertEquals(1, decisions.stream().filter("ok"::equals).count());
            assertEquals(1, decisions.stream().filter("blocked"::equals).count());
            var result = service.own(applicant, 0).items().getFirst();
            assertEquals(2, result.events().size());
            assertEquals(result.status(), result.events().getLast().status());
            assertEquals(result.status().equals("APPROVED") ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", applicant), Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", operator));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", operator));
        }
    }

    @Test
    void sellerApplicationOwnershipPaginationAndNarrowSecurityException() throws Exception {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID buyer = activeMember("p3-boundary@example.com");
        UUID other = activeMember("p3-other@example.com");
        var foreign = service.submit(other, new com.example.marketplace.seller.api.SellerApplications.Input("다른 매대", "가상 계획"));
        var browser = authBrowser();
        assertEquals(401, accountRequest(browser, "GET", "/api/seller/applications", null, null).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("p3-boundary@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertTrue(service.own(buyer, 0).items().isEmpty());
        for (String path : List.of("/api/seller/applications/" + foreign.id(), "/api/seller/products", "/api/admin/accounts/seller-applications")) {
            assertEquals(403, accountRequest(browser, "GET", path, null, null).statusCode());
        }
        assertEquals(403, accountRequest(browser, "PUT", "/api/seller/applications", Map.of(), csrfToken(browser)).statusCode());
        assertEquals(400, accountRequest(browser, "POST", "/api/seller/applications", Map.of("displayName", " ", "description", "bad"), csrfToken(browser)).statusCode());
        assertEquals(400, accountRequest(browser, "GET", "/api/seller/applications?page=-1", null, null).statusCode());
        for (int i = 0; i < 21; i++) {
            UUID applicationId = UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace.seller_application(id,applicant_id,display_name,description,state) VALUES(:id,:member,'목록 매대','모의 자료','REJECTED')",
                    Map.of("id", applicationId, "member", buyer));
            for (String state : List.of("PENDING", "REJECTED")) {
                jdbc.update("INSERT INTO marketplace.seller_application_event(id,application_id,actor_id,state,reason) VALUES(:id,:application,:member,:state,'모의 목록 이력')",
                        Map.of("id", UUID.randomUUID(), "application", applicationId, "member", buyer, "state", state));
            }
        }
        assertEquals(20, service.own(buyer, 0).items().size());
        assertTrue(service.own(buyer, 0).hasMore());
        assertEquals(1, service.own(buyer, 1).items().size());
        assertFalse(service.own(buyer, 1).hasMore());
        assertEquals(1, service.own(other, 0).items().size());
        assertThrows(IllegalStateException.class, () -> context.getBean(com.example.marketplace.identity.api.SellerMembership.class).lockActiveBuyer(buyer));
    }

    @Test
    void sellerApplicationHttpSubmissionReviewAndBuyerRolePreserved() throws Exception {
        UUID buyerId = activeMember("p3-buyer@example.com");
        UUID adminId = activeMember("p3-admin@example.com");
        var buyer = authBrowser();
        var admin = authBrowser();
        assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("p3-buyer@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
        assertEquals(200, authRequest(admin, "/api/auth/login", loginForm("p3-admin@example.com", SIGNUP_PASSWORD), csrfToken(admin)).statusCode());
        String own = "/api/seller/applications";
        var input = Map.of("displayName", "모의 매대", "description", "가상 상품 판매 계획", "applicantId", adminId.toString(), "status", "APPROVED");
        assertEquals(403, accountRequest(buyer, "POST", own, input, null).statusCode());
        var submitted = accountRequest(buyer, "POST", own, input, csrfToken(buyer));
        assertEquals(201, submitted.statusCode());
        var body = authMapper.readTree(submitted.body());
        String id = body.get("id").asText();
        assertEquals(buyerId.toString(), body.get("applicantId").asText());
        assertEquals("PENDING", body.get("status").asText());
        assertEquals(409, accountRequest(buyer, "POST", own, input, csrfToken(buyer)).statusCode());
        String review = "/api/admin/accounts/seller-applications/" + id + "/decision";
        var approved = Map.of("decision", "APPROVED", "reason", "모의 심사 완료");
        assertEquals(403, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", adminId));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", adminId));
        try {
            assertEquals(403, accountRequest(admin, "POST", review, approved, null).statusCode());
            assertEquals(200, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
            assertEquals(409, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
            assertEquals(List.of("BUYER", "SELLER"), authMapper.readTree(authRequest(buyer, "/api/auth/me", null, null).body()).get("roles")
                    .valueStream().map(node -> node.asText()).sorted().toList());
            assertEquals(200, authRequest(buyer, "/api/seller/access", null, null).statusCode());
            var listing = authMapper.readTree(accountRequest(buyer, "GET", own, null, null).body());
            assertEquals(1, listing.get("items").size());
            assertEquals(2, listing.get("items").get(0).get("events").size());
            assertEquals(409, accountRequest(buyer, "POST", own, input, csrfToken(buyer)).statusCode());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", adminId));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", adminId));
        }
    }

    @Test
    void accountStateLifecycleRevokesSessionsAndPreservesIdentityReference() throws Exception {
        UUID admin = activeMember("d-admin@example.com");
        UUID target = activeMember("d-target@example.com");
        var operator = authBrowser();
        var buyer = authBrowser();
        var second = authBrowser();
        assertEquals(200, authRequest(operator, "/api/auth/login", loginForm("d-admin@example.com", SIGNUP_PASSWORD), csrfToken(operator)).statusCode());
        assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
        assertEquals(200, authRequest(second, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(second)).statusCode());
        String path = "/api/admin/accounts/" + target;
        var reason = Map.of("reason", "테스트 운영 조치");
        assertEquals(403, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", admin));
        try {
            assertEquals(403, accountRequest(operator, "POST", path + "/suspend", reason, null).statusCode());
            assertEquals(200, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(401, authRequest(buyer, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(second, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(200, accountRequest(operator, "POST", path + "/restore", reason, csrfToken(operator)).statusCode());
            assertEquals(401, authRequest(second, "/api/auth/me", null, null).statusCode());
            assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
            accountOperations.saveAddress(target, null, addressInput(true));
            accountOperations.updateProfile(target, "탈퇴자", "01012345678");
            recovery.request("d-target@example.com");
            String resetToken = resetMail("d-target@example.com");
            var withdrawal = Map.of("password", SIGNUP_PASSWORD, "confirmation", "WITHDRAW");
            jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.d_trade_reference (member_id uuid REFERENCES marketplace.identity_user(id), amount integer NOT NULL)");
            jdbc.update("INSERT INTO marketplace.d_trade_reference VALUES(:id,12300)", Map.of("id", target));
            assertEquals(403, accountRequest(buyer, "POST", "/api/account/withdraw", withdrawal, null).statusCode());
            assertEquals(400, accountRequest(buyer, "POST", "/api/account/withdraw", Map.of("password", "wrong", "confirmation", "WITHDRAW"), csrfToken(buyer)).statusCode());
            assertEquals(200, accountRequest(buyer, "POST", "/api/account/withdraw", withdrawal, csrfToken(buyer)).statusCode());
            assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(3L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals("", jdbc.queryForObject("SELECT display_name FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            for (String table : List.of("user_credential", "member_address", "email_verification_token", "password_reset_token", "role_assignment")) {
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace." + table + " WHERE user_id=:id", Map.of("id", target), Integer.class));
            }
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
            assertEquals(12300, jdbc.queryForObject("SELECT amount FROM marketplace.d_trade_reference WHERE member_id=:id", Map.of("id", target), Integer.class));
            assertNull(jdbc.queryForObject("SELECT phone FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertFalse("d-target@example.com".equals(jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class)));
            assertEquals(401, authRequest(buyer, "/api/auth/me", null, null).statusCode());
            assertThrows(SignupException.class, () -> recovery.reset(resetToken, SIGNUP_PASSWORD));
            assertEquals(409, accountRequest(operator, "POST", path + "/restore", reason, csrfToken(operator)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", "/api/admin/accounts/" + admin + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", "/api/account/withdraw", withdrawal, csrfToken(operator)).statusCode());
            UUID replacement = activeMember("d-target@example.com");
            assertFalse(target.equals(replacement));
            assertEquals(target, jdbc.queryForObject("SELECT member_id FROM marketplace.d_trade_reference", Map.of(), UUID.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", admin));
        }
    }

    @Test
    void stateChangesRollbackAndSerializeAndCannotBypassServicePermission() throws Exception {
        var service = context.getBean(com.example.marketplace.identity.api.AccountStateOperations.class);
        UUID actor = activeMember("d-service-actor@example.com");
        UUID target = activeMember("d-service-target@example.com");
        UUID pending = signup.register("d-pending@example.com", SIGNUP_PASSWORD);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> service.suspend(actor, target, "reason"));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", actor));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", actor));
        try {
            assertThrows(AccountException.class, () -> service.suspend(actor, target, " "));
            assertThrows(AccountException.class, () -> service.suspend(actor, pending, "reason"));
            assertThrows(AccountException.class, () -> service.restore(actor, target, "reason"));
            assertThrows(AccountException.class, () -> service.suspend(actor, UUID.randomUUID(), "reason"));
            assertThrows(AccountException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "wrong"));
            var browser = authBrowser();
            assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("d-service-target@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
            recovery.request("d-service-target@example.com");
            accountOperations.saveAddress(target, null, addressInput(true));
            jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.account_state_audit ADD CONSTRAINT d_reject_audit CHECK (reason='impossible') NOT VALID");
            try {
                assertThrows(DataIntegrityViolationException.class, () -> service.suspend(actor, target, "reason"));
                assertThrows(DataIntegrityViolationException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "WITHDRAW"));
            } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.account_state_audit DROP CONSTRAINT d_reject_audit"); }
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals(1, accountOperations.addresses(target).size());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.password_reset_token WHERE user_id=:id", Map.of("id", target), Integer.class));
            assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
            accountOperations.updateProfile(target, "보존 이름", "01012345678");
            // Fail after state/session/token/audit/anonymization/address writes, during credential deletion.
            jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.d_credential_reference (user_id uuid REFERENCES marketplace.user_credential(user_id))");
            jdbc.update("INSERT INTO marketplace.d_credential_reference VALUES(:id)", Map.of("id", target));
            assertThrows(DataIntegrityViolationException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "WITHDRAW"));
            assertEquals("d-service-target@example.com", accountOperations.profile(target).email());
            assertEquals("보존 이름", accountOperations.profile(target).displayName());
            assertEquals("01012345678", accountOperations.profile(target).phone());
            assertEquals(1, accountOperations.addresses(target).size());
            for (String table : List.of("user_credential", "role_assignment", "password_reset_token", "email_verification_token")) {
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace." + table + " WHERE user_id=:id", Map.of("id", target), Integer.class));
            }
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
            assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
            jdbc.update("DELETE FROM marketplace.d_credential_reference WHERE user_id=:id", Map.of("id", target));
            var results = race(() -> {
                try { service.suspend(actor, target, "race"); return "ok"; }
                catch (AccountException exception) { return "blocked"; }
            });
            assertEquals(1, results.stream().filter("ok"::equals).count());
            assertEquals(1, results.stream().filter("blocked"::equals).count());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", actor));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", actor));
        }
    }

    @Test
    void initialAdminIsExplicitAtomicAndOnlyOneConcurrentWinner() throws Exception {
        var bootstrap = new com.example.marketplace.identity.infrastructure.InitialAdmin(jdbc, transactionManager);
        String database = jdbc.queryForObject("SELECT current_database()", Map.of(), String.class);
        UUID first = activeMember("bootstrap-first-c@example.com");
        UUID second = activeMember("bootstrap-second-c@example.com");
        UUID pending = signup.register("bootstrap-pending-c@example.com", SIGNUP_PASSWORD);
        assertThrows(IllegalArgumentException.class, () -> bootstrap.initialize(first, database, "wrong"));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(first, "wrong_database", "INITIAL_ADMIN"));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(pending, database, "INITIAL_ADMIN"));
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("bootstrap-first-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", second));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(first, database, "INITIAL_ADMIN"));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", second));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.authorization_audit ADD CONSTRAINT c_reject_audit CHECK (action <> 'INITIAL_ADMIN_GRANTED') NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> bootstrap.initialize(first, database, "INITIAL_ADMIN"));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.authorization_audit DROP CONSTRAINT c_reject_audit"); }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission", Map.of(), Integer.class));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            bootstrap.initialize(first, database, "INITIAL_ADMIN");
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission", Map.of(), Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit", Map.of(), Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", first), Long.class));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        var next = new java.util.concurrent.atomic.AtomicInteger();
        var results = race(() -> {
            UUID candidate = next.getAndIncrement() == 0 ? first : second;
            return runBootstrapCommand(candidate, database, "INITIAL_ADMIN", false) == 0 ? "ok" : "blocked";
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("blocked"::equals).count());
        UUID winner = jdbc.queryForObject("SELECT user_id FROM marketplace.admin_bootstrap WHERE id=1", Map.of(), UUID.class);
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", winner), Integer.class));
        assertEquals(List.of("ADMIN", "BUYER"), jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id=:id ORDER BY role", Map.of("id", winner), String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit WHERE source='LOCAL_BOOTSTRAP' AND target_id=:id", Map.of("id", winner), Integer.class));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(winner, database, "INITIAL_ADMIN"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id", Map.of("id", winner.toString()), Integer.class));
        // Leave completed marker intact: removing ADMIN is not an authorization to bootstrap again.
        jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", winner));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", winner));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(second, database, "INITIAL_ADMIN"));
        assertEquals(1, runBootstrapCommand(second, database, "INITIAL_ADMIN", false));
        assertEquals(1, runBootstrapCommand(second, "wrong_database", "INITIAL_ADMIN", false));
        assertEquals(1, runBootstrapCommand(second, database, "wrong", false));
        assertEquals(1, runBootstrapCommand(second, database, "INITIAL_ADMIN", true));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit", Map.of(), Integer.class));
    }

    private int runBootstrapCommand(UUID user, String database, String confirmation, boolean forbiddenArgument) throws Exception {
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
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Bootstrap command timed out"); }
        String log = java.nio.file.Files.readString(output);
        assertFalse(log.contains("Tomcat started"));
        assertFalse(log.contains(System.getenv("D2_DB_PASSWORD")));
        if (process.exitValue() == 0) { assertTrue(log.contains("INITIAL_ADMIN_COMPLETED")); }
        return process.exitValue();
    }

    @Test
    void adminPermissionsAreSeparateAndRevocationAppliesToExistingSession() throws Exception {
        UUID id = activeMember("permission-c@example.com");
        var servicePolicy = new com.example.marketplace.identity.infrastructure.JdbcAccessPolicy(jdbc);
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", id));
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("permission-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertEquals(200, authRequest(browser, "/api/admin/access", null, null).statusCode());
        for (String area : List.of("accounts", "content", "support", "settlements", "system")) {
            assertEquals(403, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> servicePolicy.require(id,
                    com.example.marketplace.identity.api.AccessArea.valueOf(area.toUpperCase(java.util.Locale.ROOT))));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,:permission)", Map.of("id", id, "permission", area.toUpperCase(java.util.Locale.ROOT)));
            assertEquals(200, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
            servicePolicy.require(id, com.example.marketplace.identity.api.AccessArea.valueOf(area.toUpperCase(java.util.Locale.ROOT)));
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", id));
            assertEquals(403, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
        }
        assertEquals(403, authRequest(browser, "/api/admin/unknown", null, null).statusCode());
        assertEquals(403, authRequest(browser, "/api/seller/access", null, null).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", id));
        assertEquals(200, authRequest(browser, "/api/seller/access", null, null).statusCode());
        assertEquals(200, authRequest(browser, "/api/account/profile", null, null).statusCode());
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", id));
        assertEquals(403, authRequest(browser, "/api/seller/access", null, null).statusCode());
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", id));
    }

    @Test
    void roleSpacesDenyAnonymousAndBuyerEvenWithForgedSignupFields() throws Exception {
        var browser = authBrowser();
        assertEquals(401, authRequest(browser, "/api/seller/access", null, null).statusCode());
        assertEquals(401, authRequest(browser, "/api/admin/access", null, null).statusCode());
        var signupResponse = authJsonPost(browser, "/api/auth/signup", Map.of("email", "forged-c@example.com",
                "password", SIGNUP_PASSWORD, "role", "ADMIN", "permissions", "SYSTEM", "isAdmin", "true"), csrfToken(browser));
        assertEquals(201, signupResponse.statusCode());
        signup.verifyEmail(mail("forged-c@example.com").token());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("forged-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        for (String path : List.of("/api/seller/access", "/api/admin/access", "/api/admin/accounts/access",
                "/api/admin/content/access", "/api/admin/support/access", "/api/admin/settlements/access", "/api/admin/system/access")) {
            var response = authRequest(browser, path, null, null);
            assertEquals(403, response.statusCode(), path);
            assertEquals("FORBIDDEN", authMapper.readTree(response.body()).get("code").asText());
        }
        assertEquals(List.of("BUYER"), authMapper.readTree(authRequest(browser, "/api/auth/me", null, null).body())
                .get("roles").valueStream().map(node -> node.asText()).toList());
    }

    private String resetMail(String email) {
        return resetMailbox.messages().stream().filter(item -> item.recipient().equals(email))
                .reduce((first, second) -> second).orElseThrow().token();
    }

    private HttpResponse<String> accountRequest(HttpClient browser, String method, String path, Object body, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(body)));
        if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void accountHttpOwnerCsrfValidationAndPrivacyBoundaries() throws Exception {
        String email = "account-http-b@example.com";
        UUID member = activeMember(email);
        UUID other = activeMember("account-http-other-b@example.com");
        var foreign = accountOperations.saveAddress(other, null, addressInput(false));
        var browser = authBrowser();
        for (String path : List.of("/api/account/profile", "/api/account/addresses")) {
            assertEquals(401, accountRequest(browser, "GET", path, null, null).statusCode());
        }
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        String csrf = csrfToken(browser);
        var profileBody = Map.of("displayName", "사용자", "phone", "010-9876-5432", "email", "forged@example.com");
        assertEquals(403, accountRequest(browser, "PUT", "/api/account/profile", profileBody, null).statusCode());
        assertEquals("", accountOperations.profile(member).displayName());
        var profile = accountRequest(browser, "PUT", "/api/account/profile", profileBody, csrf);
        assertEquals(200, profile.statusCode());
        assertEquals(email, authMapper.readTree(profile.body()).get("email").asText());
        assertTrue(profile.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(400, accountRequest(browser, "PUT", "/api/account/profile", Map.of("displayName", " "), csrf).statusCode());
        assertEquals(403, accountRequest(browser, "POST", "/api/account/addresses", addressInput(false), null).statusCode());
        assertTrue(accountOperations.addresses(member).isEmpty());
        var created = accountRequest(browser, "POST", "/api/account/addresses", addressInput(false), csrf);
        assertEquals(201, created.statusCode());
        String id = authMapper.readTree(created.body()).get("id").asText();
        assertTrue(authMapper.readTree(created.body()).get("isDefault").asBoolean());
        assertEquals(403, accountRequest(browser, "PUT", "/api/account/addresses/" + id, addressInput(true), null).statusCode());
        assertEquals(403, accountRequest(browser, "DELETE", "/api/account/addresses/" + id, null, null).statusCode());
        assertEquals(1, accountOperations.addresses(member).size());
        assertEquals(404, accountRequest(browser, "PUT", "/api/account/addresses/" + foreign.id(), addressInput(true), csrf).statusCode());
        assertEquals(404, accountRequest(browser, "DELETE", "/api/account/addresses/" + foreign.id(), null, csrf).statusCode());
        assertEquals(400, accountRequest(browser, "POST", "/api/account/addresses", Map.of("label", "bad"), csrf).statusCode());
        assertEquals(200, accountRequest(browser, "PUT", "/api/account/addresses/" + id, addressInput(false), csrf).statusCode());
        var listing = accountRequest(browser, "GET", "/api/account/addresses", null, null);
        assertEquals(1, authMapper.readTree(listing.body()).get("addresses").size());
        assertTrue(listing.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(200, accountRequest(browser, "DELETE", "/api/account/addresses/" + id, null, csrf).statusCode());
        assertTrue(accountOperations.addresses(member).isEmpty());
        assertEquals(1, accountOperations.addresses(other).size());
    }

    private AddressInput addressInput(boolean isDefault) {
        return new AddressInput("집", "구매자", "010-1234-5678", "01234", "서울 주소", "101호", isDefault);
    }

    @Test
    void recoveryFailureAfterSessionDeleteRollsBackAllState() throws Exception {
        String email = "recover-atomic-b@example.com";
        UUID member = activeMember(email);
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        recovery.request(email);
        String token = resetMail(email);
        var delegate = new JdbcPasswordRecoveryStore(jdbc);
        var failingStore = new com.example.marketplace.identity.domain.PasswordRecoveryStore() {
            @Override public UUID lockEligibleMember(String value) { return delegate.lockEligibleMember(value); }
            @Override public void replaceToken(UUID id, String hash, Instant created, Instant expires) {
                delegate.replaceToken(id, hash, created, expires);
            }
            @Override public boolean consume(String hash, String passwordHash, java.util.function.Supplier<Instant> currentTime) {
                assertTrue(delegate.consume(hash, passwordHash, currentTime));
                throw new IllegalStateException("injected-after-session-delete");
            }
        };
        var failing = new PasswordRecoveryService(failingStore, passwordHasher, resetMailbox, Clock.systemUTC(), transactionManager);
        assertThrows(IllegalStateException.class, () -> failing.reset(token, "atomic-new-password"));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", member), Long.class));
        String hash = jdbc.queryForObject("SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id", Map.of("id", member), String.class);
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, hash));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        recovery.reset(token, "atomic-new-password");
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
    }

    @Test
    void recoveryConcurrentIssueAndConsumeLeavesOnlyReplacementValid() throws Exception {
        String email = "recover-reissue-b@example.com";
        activeMember(email);
        recovery.request(email);
        String old = resetMail(email);
        var issuer = new AtomicBoolean();
        race(() -> {
            if (issuer.compareAndSet(false, true)) { recovery.request(email); return "issued"; }
            try { recovery.reset(old, "concurrent-new-password"); return "consumed"; }
            catch (SignupException expected) { return expected.getMessage(); }
        });
        String replacement = resetMail(email);
        assertThrows(SignupException.class, () -> recovery.reset(old, SIGNUP_PASSWORD));
        recovery.reset(replacement, SIGNUP_PASSWORD);
    }

    @Test
    void recoveryRechecksExpiryAfterWaitingForMemberLock() throws Exception {
        String email = "recover-lock-expiry-b@example.com";
        UUID member = activeMember(email);
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        recoveryAt(issuedAt, resetMailbox).request(email);
        String token = resetMail(email);
        var current = new java.util.concurrent.atomic.AtomicReference<>(issuedAt.plusSeconds(1799));
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var service = new PasswordRecoveryService(new JdbcPasswordRecoveryStore(jdbc), passwordHasher,
                resetMailbox, moving, transactionManager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<String> reset;
            try (var blocker = source.getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, member);
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                }
                reset = pool.submit(() -> {
                    try { service.reset(token, "expired-while-waiting"); return "ok"; }
                    catch (SignupException expected) { return expected.getMessage(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT count(*) > 0 FROM pg_stat_activity WHERE datname=current_database()
                            AND pid<>pg_backend_pid() AND wait_event_type='Lock'
                            AND query LIKE '%marketplace.identity_user%' AND query LIKE '%FOR UPDATE%'
                            """, Map.of(), Boolean.class);
                    if (waiting) { break; }
                    Thread.sleep(10);
                }
                current.set(issuedAt.plusSeconds(1800));
                blocker.commit();
                assertTrue(waiting, "Reset must demonstrably wait for the member lock before clock advances");
            }
            assertEquals("INVALID_RESET_TOKEN", reset.get(10, TimeUnit.SECONDS));
        }
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", member), Long.class));
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, jdbc.queryForObject(
                "SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id", Map.of("id", member), String.class)));
    }

    @Test
    void recoveryLatestOnlyOneUseRevokesSessionsAndChangesPassword(CapturedOutput output) throws Exception {
        String email = "recover-b@example.com";
        UUID member = activeMember(email);
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        recovery.request(email);
        String old = resetMail(email);
        recovery.request(email);
        String current = resetMail(email);
        assertThrows(SignupException.class, () -> recovery.reset(old, "replacement-password"));
        assertThrows(SignupException.class, () -> recovery.reset(mail(email).token(), "replacement-password"));
        assertThrows(SignupException.class, () -> signup.verifyEmail(current));
        assertThrows(SignupException.class, () -> recovery.reset(current, "short"));
        assertEquals(TokenSecrets.hash(current), jdbc.queryForObject(
                "SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id", Map.of("id", member), String.class));
        recovery.reset(current, "replacement-password");
        assertThrows(SignupException.class, () -> recovery.reset(current, "another-password"));
        assertEquals(1L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", member), Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", member.toString()), Integer.class));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, "replacement-password"), csrfToken(browser)).statusCode());
        assertFalse(output.getAll().contains(current));
        assertFalse(output.getAll().contains("replacement-password"));
    }

    @Test
    void recoveryConcurrentConsumptionAndLatestRequestSerialize() throws Exception {
        UUID member = activeMember("recover-race-b@example.com");
        race(() -> { recovery.request("recover-race-b@example.com"); return "ok"; });
        String hash = jdbc.queryForObject("SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), String.class);
        String token = resetMailbox.messages().stream().filter(item -> TokenSecrets.hash(item.token()).equals(hash))
                .findFirst().orElseThrow().token();
        var results = race(() -> {
            try { recovery.reset(token, "new-racing-password"); return "ok"; }
            catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("INVALID_RESET_TOKEN"::equals).count());
    }

    private PasswordRecoveryService recoveryAt(Instant time, VerificationMailbox targetMailbox) {
        return new PasswordRecoveryService(new JdbcPasswordRecoveryStore(jdbc), passwordHasher, targetMailbox,
                Clock.fixed(time, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void recoveryExpiryRollbackAndIneligibleMembersLeaveStateUntouched() {
        UUID member = activeMember("recover-expire-b@example.com");
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        recoveryAt(now, resetMailbox).request("recover-expire-b@example.com");
        String token = resetMail("recover-expire-b@example.com");
        assertThrows(SignupException.class, () -> recoveryAt(now.plusSeconds(1800), resetMailbox).reset(token, SIGNUP_PASSWORD));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        VerificationMailbox failing = (recipient, secret, expiry) -> { throw new SignupException("MAIL_FAILURE"); };
        assertThrows(SignupException.class, () -> recoveryAt(now, failing).request("recover-expire-b@example.com"));
        assertEquals(TokenSecrets.hash(token), jdbc.queryForObject("SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), String.class));
        int before = resetMailbox.messages().size();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recoveryAt(now, resetMailbox).request("recover-expire-b@example.com");
            status.setRollbackOnly();
        });
        assertEquals(before, resetMailbox.messages().size());
        recoveryAt(now.plusSeconds(1799), resetMailbox).reset(token, "valid-before-expiry");
        signup.register("recover-pending-b@example.com", SIGNUP_PASSWORD);
        UUID suspended = activeMember("recover-suspended-b@example.com");
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", suspended));
        before = resetMailbox.messages().size();
        for (String email : List.of("recover-pending-b@example.com", "recover-suspended-b@example.com", "absent-b@example.com")) {
            recovery.request(email);
        }
        assertEquals(before, resetMailbox.messages().size());
    }

    @Test
    void recoveryHttpRequiresCsrfAndDoesNotRevealEligibility() throws Exception {
        var browser = authBrowser();
        assertEquals(403, authJsonPost(browser, "/api/auth/password/request", Map.of("email", "unknown-b@example.com"), null).statusCode());
        String csrf = csrfToken(browser);
        activeMember("recover-http-b@example.com");
        signup.register("recover-http-pending-b@example.com", SIGNUP_PASSWORD);
        for (String email : List.of("unknown-b@example.com", "recover-http-b@example.com", "recover-http-pending-b@example.com")) {
            var response = authJsonPost(browser, "/api/auth/password/request", Map.of("email", email), csrf);
            assertEquals(202, response.statusCode());
            assertEquals("{\"status\":\"PASSWORD_RESET_REQUESTED\"}", response.body());
        }
        String token = resetMail("recover-http-b@example.com");
        assertEquals(403, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", SIGNUP_PASSWORD), null).statusCode());
        assertEquals(400, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", "short"), csrf).statusCode());
        assertEquals(200, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", SIGNUP_PASSWORD), csrf).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
    }

    @Test
    void profileAndAddressOwnershipDefaultAndRollback() {
        UUID member = activeMember("address-owner-b@example.com");
        UUID other = activeMember("address-other-b@example.com");
        var profile = accountOperations.updateProfile(member, " 구매자😀 ", "010-1234-5678");
        assertEquals("구매자😀", profile.displayName());
        assertEquals("address-owner-b@example.com", profile.email());
        assertThrows(AccountException.class, () -> accountOperations.updateProfile(member, " ", null));
        assertEquals(profile, accountOperations.profile(member));
        var first = accountOperations.saveAddress(member, null, addressInput(false));
        assertTrue(first.isDefault());
        var second = accountOperations.saveAddress(member, null, addressInput(true));
        assertEquals(second.id(), accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).findFirst().orElseThrow().id());
        assertTrue(accountOperations.saveAddress(member, second.id(), addressInput(false)).isDefault());
        assertEquals("ADDRESS_NOT_FOUND", assertThrows(AccountException.class,
                () -> accountOperations.saveAddress(other, first.id(), addressInput(true))).getMessage());
        assertThrows(AccountException.class, () -> accountOperations.deleteAddress(other, first.id()));
        assertTrue(accountOperations.addresses(other).isEmpty());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountOperations.saveAddress(member, first.id(), addressInput(true));
            accountOperations.deleteAddress(member, second.id());
            status.setRollbackOnly();
        });
        assertEquals(2, accountOperations.addresses(member).size());
        assertEquals(second.id(), accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).findFirst().orElseThrow().id());
        accountOperations.deleteAddress(member, second.id());
        assertEquals(first.id(), accountOperations.addresses(member).getFirst().id());
        assertTrue(accountOperations.addresses(member).getFirst().isDefault());
        accountOperations.deleteAddress(member, first.id());
        assertTrue(accountOperations.addresses(member).isEmpty());
    }

    @Test
    void addressConcurrentFirstDefaultAndCapacityPreserveInvariants() throws Exception {
        UUID member = activeMember("address-race-b@example.com");
        race(() -> { accountOperations.saveAddress(member, null, addressInput(true)); return "ok"; });
        assertEquals(2, accountOperations.addresses(member).size());
        assertEquals(1, accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).count());
        for (int i = 2; i < 19; i++) { accountOperations.saveAddress(member, null, addressInput(false)); }
        var result = race(() -> {
            try { accountOperations.saveAddress(member, null, addressInput(true)); return "ok"; }
            catch (AccountException expected) { return expected.getMessage(); }
        });
        assertEquals(1, result.stream().filter("ok"::equals).count());
        assertEquals(1, result.stream().filter("ADDRESS_LIMIT"::equals).count());
        assertEquals(20, accountOperations.addresses(member).size());
        assertEquals(1, accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).count());
    }

    private HttpResponse<String> authJsonPost(HttpClient browser, String path, Map<String, String> body, String csrf)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(body)));
        if (csrf != null) { builder.header("X-CSRF-TOKEN", csrf); }
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void signupAndVerificationHttpRejectCsrfAndBadInputWithoutChanges() throws Exception {
        var browser = authBrowser();
        var body = Map.of("email", "guard-a3@example.com", "password", "guard-a3-password");
        assertEquals(403, authJsonPost(browser, "/api/auth/signup", body, null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), Integer.class));
        String csrf = csrfToken(browser);
        assertEquals(400, authJsonPost(browser, "/api/auth/signup",
                Map.of("email", "invalid", "password", "short"), csrf).statusCode());
        assertEquals(201, authJsonPost(browser, "/api/auth/signup", body, csrf).statusCode());
        assertEquals(409, authJsonPost(browser, "/api/auth/signup", body, csrf).statusCode());
        String token = mail("guard-a3@example.com").token();
        var verification = Map.of("token", token);
        assertEquals(403, authJsonPost(browser, "/api/auth/verify-email", verification, null).statusCode());
        assertEquals("PENDING_EMAIL", jdbc.queryForObject(
                "SELECT state FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.email_verification_token WHERE token_hash=:hash AND consumed_at IS NULL",
                Map.of("hash", TokenSecrets.hash(token)), Integer.class));
        assertEquals(200, authJsonPost(browser, "/api/auth/verify-email", verification, csrf).statusCode());
        assertEquals(400, authJsonPost(browser, "/api/auth/verify-email", verification, csrf).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), Integer.class));
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

    private void runAuthBrowsersIfRequested() throws Exception {
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
            for(String scenario:List.of("create","guest","conflict","historyguest","shipmentmember","shipmentguest","shipmentkeyboard","gmember","gguest","ghistory")) {
                UUID buyer=(scenario.equals("guest")||scenario.equals("historyguest")||scenario.equals("shipmentguest")||scenario.equals("gguest"))?null:activeMember("order-"+scenario+"-"+project+"@example.com");
                for(String side:List.of("a","b","c")) {
                    var fixture=browseFixture("order-"+scenario+"-"+project+"-"+side,side.equals("a")?10000:20000,10);
                    if((scenario.equals("gguest")||scenario.equals("ghistory"))&&!side.equals("c")) { var now=Instant.now();var pi=new com.example.marketplace.promotion.api.Promotions.Input("G 공개 행사 "+side,"PRODUCT",List.of(((com.example.marketplace.catalog.api.Products.Product)fixture.get("product")).id()),now.minusSeconds(10),now.plusSeconds(3600),"FIXED",1000L,null,"DRAFT",0L);var promo=promotions().save((UUID)fixture.get("owner"),null,pi);promotions().save((UUID)fixture.get("owner"),promo.id(),new com.example.marketplace.promotion.api.Promotions.Input(pi.title(),pi.scope(),pi.productIds(),pi.startsAt(),pi.endsAt(),pi.kind(),pi.value(),pi.cap(),"ACTIVE",0L)); }
                    if((scenario.equals("create")||scenario.equals("gmember")||scenario.equals("ghistory"))&&!side.equals("c")) {var template=couponTemplate((UUID)fixture.get("owner"),"주문 할인 "+side,5);coupons().claim(buyer,template.id());}
                }
            }
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
        builder.environment().put("A3_API_PORT", Integer.toString(port));
        builder.environment().put("PATH", node.getParent() + java.io.File.pathSeparator + System.getenv("PATH"));
        builder.redirectErrorStream(true).redirectOutput(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"), "browser.txt").toFile());
        var process = builder.start();
        if (!process.waitFor(240, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Auth browser verification timed out");
        }
        assertEquals(0, process.exitValue(), "See isolated evidence browser.txt");
        } finally {
            for (UUID id : cMembers) {
                jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", id));
                jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role IN ('ADMIN','SELLER')", Map.of("id", id));
            }
        }
    }

    @Test
    void authMutationLimitReturns429WithoutChangingAccount() throws Exception {
        var browser = authBrowser();
        String token = csrfToken(browser);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, authRequest(browser, "/api/auth/login",
                    loginForm("limited-a3@example.com", "wrong-password"), token).statusCode());
        }
        var blocked = authRequest(browser, "/api/auth/login", loginForm(" LIMITED-A3@example.com ", "wrong"), token);
        assertEquals(429, blocked.statusCode());
        assertEquals("60", blocked.headers().firstValue("Retry-After").orElseThrow());
        assertEquals("RATE_LIMITED", authMapper.readTree(blocked.body()).get("code").asText());
    }
    @Test
    void signupHttpRequiresCsrfAndReturnsNoSecrets() throws Exception {
        var browser = authBrowser();
        String token = csrfToken(browser);
        var response = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/signup"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"http-a3@example.com\",\"password\":\"a3-test-password\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        assertFalse(response.body().contains("password"));
        assertFalse(response.body().contains("token"));
        var verify = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/verify-email"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(Map.of("token", mail("http-a3@example.com").token()))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, verify.statusCode());
        runAuthBrowsersIfRequested();
    }

    private final tools.jackson.databind.json.JsonMapper authMapper = tools.jackson.databind.json.JsonMapper.builder().build();

    private HttpClient authBrowser() {
        // Isolate Java contract traffic from Playwright's 127.0.0.1 rate window without relaxing production limits.
        return HttpClient.newBuilder().localAddress(new java.net.InetSocketAddress("127.0.0.2", 0).getAddress()).cookieHandler(new java.net.CookieManager(
                null, java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpResponse<String> authRequest(HttpClient browser, String path, String form, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (form == null) { request.GET(); } else {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
        }
        if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrfToken(HttpClient browser) throws Exception {
        var response = authRequest(browser, "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode());
        return authMapper.readTree(response.body()).get("token").asText();
    }

    private String loginForm(String email, String password) {
        return "email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                + "&password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
    }

    private UUID activeMember(String email) {
        UUID id = signup.register(email, SIGNUP_PASSWORD);
        signup.verifyEmail(mail(email).token());
        return id;
    }

    private String cookieValue(HttpClient browser) {
        return ((java.net.CookieManager) browser.cookieHandler().orElseThrow()).getCookieStore().getCookies()
                .stream().filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
    }

    @Test
    void loginRotatesSessionPersistsInJdbcAndLogoutRevokesCookie(CapturedOutput output) throws Exception {
        UUID id = activeMember("login-a2@example.com");
        var browser = authBrowser();
        String beforeToken = csrfToken(browser);
        String beforeCookie = cookieValue(browser);
        var login = authRequest(browser, "/api/auth/login", loginForm(" LOGIN-A2@example.com ", SIGNUP_PASSWORD), beforeToken);
        assertEquals(200, login.statusCode());
        assertFalse(login.headers().firstValue("Location").isPresent());
        String afterCookie = cookieValue(browser);
        assertFalse(beforeCookie.equals(afterCookie));
        String cookieHeader = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookieHeader.contains("HttpOnly"));
        assertTrue(cookieHeader.contains("SameSite=Lax"));
        assertFalse(cookieHeader.contains("Secure"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));

        // Another HTTP client restores authentication from the same JDBC-backed cookie.
        var restored = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .header("Cookie", "SESSION=" + afterCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, restored.statusCode());
        assertEquals(id.toString(), authMapper.readTree(restored.body()).get("id").asText());
        assertTrue(restored.body().contains("BUYER"));
        assertFalse(restored.body().contains("email"));
        assertFalse(restored.body().contains("password"));
        assertTrue(restored.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var attributes = jdbc.queryForList("""
                SELECT a.attribute_bytes FROM marketplace.spring_session_attributes a
                JOIN marketplace.spring_session s ON s.primary_id=a.session_primary_id WHERE s.principal_name=:id
                """, Map.of("id", id.toString()), byte[].class);
        assertFalse(attributes.isEmpty());
        for (byte[] attribute : attributes) {
            String serialized = new String(attribute, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(serialized.contains("{argon2id}"));
            assertFalse(serialized.contains("A1-private-passphrase"));
        }

        assertEquals(403, authRequest(browser, "/api/auth/logout", "", beforeToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        String afterToken = csrfToken(browser);
        assertFalse(beforeToken.equals(afterToken));
        assertEquals(200, authRequest(browser, "/api/auth/logout", "", afterToken).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        var replay = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .header("Cookie", "SESSION=" + afterCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, replay.statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));
        assertEquals(403, authRequest(browser, "/api/auth/login", loginForm("login-a2@example.com", SIGNUP_PASSWORD),
                afterToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("login-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        assertFalse(output.getAll().contains(SIGNUP_PASSWORD));
        assertFalse(output.getAll().contains(beforeToken));
    }

    @Test
    void invalidCredentialsAndUnverifiedAccountsHaveSameJsonFailure() throws Exception {
        activeMember("wrong-a2@example.com");
        signup.register("pending-a2@example.com", SIGNUP_PASSWORD);
        var browser = authBrowser();
        String token = csrfToken(browser);
        for (String email : List.of("wrong-a2@example.com", "missing-a2@example.com", "pending-a2@example.com", "invalid-email-input")) {
            String password = email.startsWith("wrong") ? "incorrect-password" : SIGNUP_PASSWORD;
            var response = authRequest(browser, "/api/auth/login", loginForm(email, password), token);
            assertEquals(401, response.statusCode());
            assertEquals("AUTHENTICATION_REQUIRED", authMapper.readTree(response.body()).get("code").asText());
            assertEquals(response.headers().firstValue("X-Request-ID").orElseThrow(),
                    authMapper.readTree(response.body()).get("requestId").asText());
            assertFalse(response.headers().firstValue("Location").isPresent());
            assertFalse(response.body().contains(email));
        }
    }

    @Test
    void csrfCannotBeOmittedOrCopiedBetweenSessions() throws Exception {
        var first = authBrowser();
        var second = authBrowser();
        String token = csrfToken(first);
        csrfToken(second);
        for (String path : List.of("/api/auth/login", "/api/auth/logout", "/api/unknown-mutation")) {
            assertEquals(403, authRequest(first, path, "", null).statusCode());
            assertEquals(403, authRequest(second, path, "", token).statusCode());
        }
        assertFalse(authRequest(first, "/api/auth/logout", null, null).statusCode() == 200);
    }

    @Test
    void currentRolesAndAccountVersionAreCheckedEveryRequest() throws Exception {
        UUID id = activeMember("version-a2@example.com");
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("version-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES (:id, 'SELLER')", Map.of("id", id));
        assertTrue(authRequest(browser, "/api/auth/me", null, null).body().contains("SELLER"));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", id));
        assertFalse(authRequest(browser, "/api/auth/me", null, null).body().contains("SELLER"));
        jdbc.update("UPDATE marketplace.identity_user SET auth_version=auth_version+1 WHERE id=:id", Map.of("id", id));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));
    }

    @Test
    void suspendedAndWithdrawnAccountsLoseExistingSessions() throws Exception {
        for (String state : List.of("SUSPENDED", "WITHDRAWN")) {
            String email = state.toLowerCase(java.util.Locale.ROOT) + "-a2@example.com";
            UUID id = activeMember(email);
            var browser = authBrowser();
            assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD),
                    csrfToken(browser)).statusCode());
            jdbc.update("UPDATE marketplace.identity_user SET state=:state WHERE id=:id", Map.of("id", id, "state", state));
            assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD),
                    csrfToken(browser)).statusCode());
        }
    }

    @Test
    void expiredJdbcSessionCannotAuthenticateOrReuseCsrf() throws Exception {
        UUID id = activeMember("expiry-a2@example.com");
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        String oldToken = csrfToken(browser);
        jdbc.update("""
                UPDATE marketplace.spring_session SET last_access_time=0, expiry_time=0 WHERE principal_name=:id
                """, Map.of("id", id.toString()));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(403, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                oldToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
    }

    @Test
    void anonymousAuthenticationContract() throws Exception {
        HttpClient browser = HttpClient.newHttpClient();
        var me = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, me.statusCode());
        assertTrue(me.body().contains("AUTHENTICATION_REQUIRED"));
        var csrf = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrf.statusCode());
        assertTrue(csrf.body().contains("X-CSRF-TOKEN"));
        assertTrue(csrf.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }
    private static final String SIGNUP_PASSWORD = " A1-private-passphrase-가나다 ";
    @Autowired private PasswordRecoveryOperations recovery;
    @Autowired private LocalPasswordResetMailbox resetMailbox;
    @Autowired private AccountOperations accountOperations;
    @Autowired
    private SignupOperations signup;
    @Autowired
    private IdentityStore identityStore;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private LocalTestMailbox mailbox;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @LocalServerPort
    private int port;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private MBeanServer mBeanServer;
    @Autowired
    private DataSource source;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private TransferService transfers;
    @Autowired
    private BatchService batches;
    @Autowired
    private Flyway flyway;

    private LocalTestMailbox.Message mail(String email) {
        return mailbox.messages().stream().filter(item -> item.recipient().equals(email)).reduce((first, last) -> last).orElseThrow();
    }

    private SignupService serviceAt(Instant now, VerificationMailbox targetMailbox) {
        return new SignupService(identityStore, passwordHasher, targetMailbox,
                Clock.fixed(now, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void signupPersistsOnlyHashesAndBuyerThenVerificationActivates(CapturedOutput output) {
        UUID id = signup.register("  New.Buyer@EXAMPLE.COM ", SIGNUP_PASSWORD);
        String hash = jdbc.queryForObject("SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id",
                Map.of("id", id), String.class);
        assertTrue(hash.startsWith("{argon2id}$argon2id$"));
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, hash));
        assertFalse(passwordHasher.matches(SIGNUP_PASSWORD.strip(), hash));
        assertEquals("BUYER", jdbc.queryForObject("SELECT role FROM marketplace.role_assignment WHERE user_id=:id",
                Map.of("id", id), String.class));
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), String.class));
        var message = mail("new.buyer@example.com");
        assertEquals(TokenSecrets.hash(message.token()), jdbc.queryForObject(
                "SELECT token_hash FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", id), String.class));
        signup.verifyEmail(message.token());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), Long.class));
        assertEquals("INVALID_EMAIL_TOKEN", assertThrows(SignupException.class,
                () -> signup.verifyEmail(message.token())).getMessage());
        assertFalse(output.getAll().contains(SIGNUP_PASSWORD));
        assertFalse(output.getAll().contains(message.token()));
        assertFalse(output.getAll().contains(hash));
        assertFalse(message.toString().contains(message.token()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.flyway_schema_history WHERE version='3' AND success",
                Map.of(), Integer.class));
    }

    @Test
    void normalizedDuplicateIsRejectedWithoutExtraCredentialOrMail() {
        signup.register("duplicate@example.com", SIGNUP_PASSWORD);
        int mailCount = mailbox.messages().size();
        assertEquals("EMAIL_ALREADY_REGISTERED", assertThrows(SignupException.class,
                () -> signup.register(" DUPLICATE@example.COM ", SIGNUP_PASSWORD)).getMessage());
        assertEquals(mailCount, mailbox.messages().size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "duplicate@example.com"), Integer.class));
    }

    private List<String> race(Callable<String> action) throws Exception {
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

    @Test
    void concurrentDuplicateHasExactlyOneCommitAndOneMail() throws Exception {
        var results = race(() -> {
            try {
                signup.register("race@example.com", SIGNUP_PASSWORD);
                return "ok";
            } catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("EMAIL_ALREADY_REGISTERED"::equals).count());
        assertEquals(1, mailbox.messages().stream().filter(item -> item.recipient().equals("race@example.com")).count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email='race@example.com'",
                Map.of(), Integer.class));
    }

    @Test
    void concurrentTokenConsumptionHasExactlyOneSuccess() throws Exception {
        signup.register("token-race@example.com", SIGNUP_PASSWORD);
        String token = mail("token-race@example.com").token();
        var results = race(() -> {
            try {
                signup.verifyEmail(token);
                return "ok";
            } catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("INVALID_EMAIL_TOKEN"::equals).count());
    }

    @Test
    void expiryBoundaryIsExclusiveAndExpiredTokenRemainsUnconsumed() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var service = serviceAt(now, mailbox);
        UUID expired = service.register("expiry@example.com", SIGNUP_PASSWORD);
        service.register("before-expiry@example.com", SIGNUP_PASSWORD);
        String token = mail("expiry@example.com").token();
        assertThrows(SignupException.class,
                () -> serviceAt(now.plusSeconds(1800), mailbox).verifyEmail(token));
        serviceAt(now.plusSeconds(1799), mailbox).verifyEmail(mail("before-expiry@example.com").token());
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", expired), String.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id",
                Map.of("id", expired), java.sql.Timestamp.class));
        assertThrows(SignupException.class, () -> signup.verifyEmail("unknown"));
        assertThrows(SignupException.class, () -> signup.verifyEmail(TokenSecrets.generate()));
    }

    @Test
    void verificationRechecksExpiryAfterTokenLockWait() throws Exception {
        verifyExpiryAfterLockWait("email_verification_token");
    }

    @Test
    void verificationRechecksExpiryAfterMemberLockWait() throws Exception {
        verifyExpiryAfterLockWait("identity_user");
    }

    private void verifyExpiryAfterLockWait(String table) throws Exception {
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

    @Test
    void mailboxPreparationFailureRollsBackAllSignupRows() {
        VerificationMailbox unavailable = (recipient, token, expires) -> { throw new SignupException("MAILBOX_UNAVAILABLE"); };
        var service = serviceAt(Instant.now(), unavailable);
        int before = jdbc.queryForObject("SELECT count(*) FROM marketplace.email_verification_token", Map.of(), Integer.class);
        assertThrows(SignupException.class, () -> service.register("failed-mail@example.com", SIGNUP_PASSWORD));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email='failed-mail@example.com'",
                Map.of(), Integer.class));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM marketplace.email_verification_token", Map.of(), Integer.class));
        assertTrue(mailbox.messages().stream().noneMatch(item -> item.recipient().equals("failed-mail@example.com")));
    }

    @Test
    void callerRollbackAlsoRollsBackSignupAndDiscardsUnpublishedMail() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            signup.register("outer-rollback@example.com", SIGNUP_PASSWORD);
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.identity_user WHERE email='outer-rollback@example.com'",
                Map.of(), Integer.class));
        assertTrue(mailbox.messages().stream().noneMatch(item -> item.recipient().equals("outer-rollback@example.com")));
    }

    @Test
    void invalidInputDoesNotWriteOrDeliver() {
        int before = jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user", Map.of(), Integer.class);
        assertThrows(SignupException.class, () -> signup.register("bad address", SIGNUP_PASSWORD));
        assertThrows(SignupException.class, () -> signup.register("short@example.com", "short"));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user", Map.of(), Integer.class));
    }

    @Test
    void activationFailureRollsBackTokenConsumption() {
        UUID id = signup.register("suspended@example.com", SIGNUP_PASSWORD);
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", id));
        assertThrows(SignupException.class, () -> signup.verifyEmail(mail("suspended@example.com").token()));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id",
                Map.of("id", id), java.sql.Timestamp.class));
        UUID failing = signup.register("activation-write-failure@example.com", SIGNUP_PASSWORD);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.identity_user ADD CONSTRAINT r1_reject_activation CHECK (id <> '"
                + failing + "'::uuid OR state <> 'ACTIVE') NOT VALID");
        try {
            var failure = assertThrows(SignupException.class,
                    () -> signup.verifyEmail(mail("activation-write-failure@example.com").token()));
            assertEquals("VERIFICATION_UNAVAILABLE", failure.getMessage());
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.identity_user DROP CONSTRAINT r1_reject_activation"); }
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), String.class));
        assertNull(jdbc.queryForObject("SELECT email_verified_at FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", failing), java.sql.Timestamp.class));
        // A retry with the same token succeeds after the transient write constraint is removed.
        signup.verifyEmail(mail("activation-write-failure@example.com").token());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), String.class));
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void databaseSettingsValidatorAndActualDataSourceCoexist() {
        assertTrue(context.containsBean("requiredDatabaseSettings"));
        assertEquals(System.getenv("D2_DB_URL"), context.getEnvironment().getProperty("spring.datasource.url"));
        assertEquals(System.getenv("D2_DB_USERNAME"), context.getEnvironment().getProperty("spring.datasource.username"));
        assertEquals("none", context.getEnvironment().getProperty("management.endpoints.access.default"));
        assertEquals("read-only", context.getEnvironment().getProperty("management.endpoint.health.access"));
        assertTrue(context.containsBean("dbHealthContributor"));
    }

    @Test
    void invalidSettingsFailBeforeSingletonsWithRealDatabaseAutoConfigurationEnabled() {
        for (String key : new String[] {"url", "username", "password"}) {
            var singletonCreated = new AtomicBoolean();
            var databaseCreationAttempted = new AtomicBoolean();
            new ApplicationContextRunner().withInitializer(candidate -> {
                candidate.getBeanFactory().addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
                    @Override
                    public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
                        if (beanName.equals("dataSource") || beanName.equals("flyway")) {
                            databaseCreationAttempted.set(true);
                        }
                        return null;
                    }
                });
                candidate.getEnvironment().setConversionService(new ApplicationConversionService());
                candidate.getEnvironment().getPropertySources().remove("systemEnvironment");
                candidate.getEnvironment().getPropertySources().remove("systemProperties");
                candidate.getEnvironment().setActiveProfiles("db");
            }).withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(MarketplaceApplication.class)
                    .withPropertyValues("spring.config.location=classpath:/",
                            "DB_URL=" + System.getenv("D2_DB_URL"), "DB_USERNAME=marketplace_test",
                            "DB_PASSWORD=unused-synthetic-password", "spring.datasource." + key + "= ")
                    .withBean("singletonProbe", Object.class, () -> {
                        singletonCreated.set(true);
                        return new Object();
                    }).run(candidate -> {
                        assertThat(candidate).hasFailed();
                        assertThat(candidate.getStartupFailure())
                                .hasStackTraceContaining("Required database settings are missing, blank or unresolved:")
                                .hasStackTraceContaining("spring.datasource." + key);
                        assertFalse(singletonCreated.get());
                        assertFalse(databaseCreationAttempted.get());
                    });
        }
    }

    @Test
    void realDatabaseHealthPreservesStatusOnlyAndRequestId() throws Exception {
        var response = request("GET", "/actuator/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        assertTrue(response.headers().firstValue("X-Request-ID").orElse("")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        var head = request("HEAD", "/actuator/health");
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(405, request("POST", "/actuator/health").statusCode());
    }

    @Test
    void databaseProfileDoesNotExposeManagementDetailsOrJmx() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/configprops",
                "/actuator/metrics", "/actuator/beans", "/actuator/health/db",
                "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertEquals(404, request("GET", path).statusCode(), path);
        }
        assertTrue(mBeanServer.queryNames(new ObjectName("com.example.marketplace.dbfollowup:*"), null).isEmpty());
        assertFalse(context.containsBean("heapDumpWebEndpoint"));
    }

    private void seed(long id, String label, String note) {
        jdbc.update("INSERT INTO c3_fixture.account VALUES (:id, :label, :balance, :note)",
                new MapSqlParameterSource().addValue("id", id).addValue("label", label)
                        .addValue("balance", new BigDecimal("100.50")).addValue("note", note));
    }

    /** Bypasses the Spring connection pool and transaction context to observe committed state. */
    private String observe(String sql, Object parameter) throws Exception {
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

    private void balance(long id, String expected) throws Exception {
        assertEquals(expected, observe("SELECT balance FROM c3_fixture.account WHERE id = ?", id));
    }

    private void entryCount(String reference, String expected) throws Exception {
        assertEquals(expected, observe("SELECT count(*) FROM c3_fixture.transfer_entry WHERE reference = ?", reference));
    }

    @Test
    void bootUsesVerifiedPostgresAndUnprivilegedRole() throws Exception {
        try (var connection = source.getConnection()) {
            DedicatedDatabaseTarget.verifyConnection(connection, System.getenv("D2_DB_NAME"));
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(18, connection.getMetaData().getDatabaseMajorVersion());
        }
    }

    @Test
    void sameProductionV1AndFixtureV2MigrateOnce() {
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.flyway_schema_history WHERE version IN ('1','2') AND success",
                Map.of(), Integer.class));
        assertEquals("initialize schema", jdbc.queryForObject(
                "SELECT description FROM marketplace.flyway_schema_history WHERE version = '1'", Map.of(), String.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void parametersAndMappingHandleQuotesUnicodeDecimalAndNull() {
        seed(101, "구매자 O'Reilly", null);
        seed(102, "판매자", "설명");
        var account = accounts.findByLabel("구매자 O'Reilly").orElseThrow();
        assertEquals(101, account.id());
        assertEquals("구매자 O'Reilly", account.label());
        assertEquals(new BigDecimal("100.50"), account.balance());
        assertNull(account.note());
        assertEquals("설명", accounts.findByLabel("판매자").orElseThrow().note());
        assertTrue(accounts.findByLabel("missing").isEmpty());
        assertTrue(accounts.findByLabel("' OR 1=1 --").isEmpty());
    }

    @Test
    void successCommitsBothBalancesAndEntry() throws Exception {
        seed(201, "commit-from", null);
        seed(202, "commit-to", null);
        transfers.transfer(201, 202, new BigDecimal("25.25"), "commit");
        balance(201, "75.25");
        balance(202, "125.75");
        entryCount("commit", "1");
    }

    @Test
    void duplicateReferenceRollsBackUpdatesAlreadyMade() throws Exception {
        seed(301, "duplicate-from", null);
        seed(302, "duplicate-to", null);
        transfers.transfer(301, 302, new BigDecimal("10.00"), "duplicate");
        assertThrows(DataIntegrityViolationException.class,
                () -> transfers.transfer(301, 302, new BigDecimal("20.00"), "duplicate"));
        balance(301, "90.50");
        balance(302, "110.50");
        entryCount("duplicate", "1");
    }

    @Test
    void checkedFailureAfterDebitRollsBack() throws Exception {
        seed(401, "checked-from", null);
        assertThrows(TransferRejectedException.class,
                () -> transfers.transfer(401, 9999, new BigDecimal("30.00"), "checked"));
        balance(401, "100.50");
        entryCount("checked", "0");
    }

    @Test
    void databaseConstraintsPreventNegativeBalanceAndOrphanEntry() throws Exception {
        seed(501, "constraint-from", null);
        seed(502, "constraint-to", null);
        assertThrows(DataIntegrityViolationException.class,
                () -> transfers.transfer(501, 502, new BigDecimal("200.00"), "insufficient"));
        balance(501, "100.50");
        balance(502, "100.50");
        entryCount("insufficient", "0");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO c3_fixture.transfer_entry VALUES ('orphan', 9999, 501, 1)", Map.of()));
        entryCount("orphan", "0");
    }

    @Test
    void caughtRequiredFailureRollsBackEarlierSuccessfulTransfer() throws Exception {
        seed(61, "batch-from", null);
        seed(62, "batch-to", null);
        assertThrows(UnexpectedRollbackException.class, () -> batches.transferThenCatchFailure());
        balance(61, "100.50");
        balance(62, "100.50");
        entryCount("batch-first", "0");
    }
}
