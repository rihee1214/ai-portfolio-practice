package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import com.example.marketplace.platform.infrastructure.WorkerEngine;
import com.example.marketplace.reporting.api.Dashboards;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Child JVM: every application class and production SQL resource must be fixed H129 bytes. */
public final class HistoricalReleaseProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JsonNode request,manifest;
    private final Path folder,progress,applicationRoot,libraryRoot;
    private final Map<String,JsonNode> entries=new LinkedHashMap<>();
    private final List<Map<String,Object>> origins=new ArrayList<>();
    private Map<String,Object> startup;
    private HistoricalReleaseProbe(Path input) throws Exception {
        request=JSON.readTree(Files.readString(input));folder=Path.of(request.path("folder").asText());
        progress=folder.resolve("historical-"+request.path("label").asText()+"-progress.ndjson");
        manifest=JSON.readTree(Files.readString(Path.of(request.path("runtimeManifest").asText())));
        var actualClasspath=java.util.Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(value -> Path.of(value).toAbsolutePath().normalize()).toList();
        var expectedClasspath=manifest.path("classpath").valueStream().map(value -> Path.of(value.asText()).toAbsolutePath().normalize()).toList();
        assertEquals(expectedClasspath,actualClasspath,"Historical classpath must exactly match its exclusive manifest");
        applicationRoot=Path.of(manifest.path("applicationRoot").asText()).toAbsolutePath().normalize();
        libraryRoot=Path.of(manifest.path("libraryRoot").asText()).toAbsolutePath().normalize();
        for(var entry:manifest.path("historicalEntries")) {
            Path file=Path.of(entry.path("file").asText());assertEquals(entry.path("sha256").asText(),sha(Files.readAllBytes(file)));
            entries.put(entry.path("entry").asText(),entry);
        }
        for(String key:List.of("testHelpers","testDependencies")) {for(var entry:manifest.path(key)) {assertEquals(entry.path("sha256").asText(),sha(Files.readAllBytes(Path.of(entry.path("file").asText()))));}}
        assertEquals(manifest.path("historicalJarSha256").asText(),sha(Files.readAllBytes(Path.of(manifest.path("historicalJar").asText()))));
        origin(MarketplaceApplication.class);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) {throw new IllegalArgumentException("Private historical probe request required");}
        var probe=new HistoricalReleaseProbe(Path.of(args[0]));
        try {probe.execute();}finally {
            PostgresContractFixture.TOSS_STUB.stop(0);
            Files.writeString(probe.folder.resolve("historical-"+probe.request.path("label").asText()+"-origins.json"),JSON.writeValueAsString(Map.of("applicationOrigins",probe.origins,"currentApplicationFallback",false)));
        }
    }
    private void execute() throws Exception {
        String url=request.path("url").asText(),username=request.path("username").asText(),password=request.path("password").asText();
        Path media=Path.of(request.path("media").asText()),fixtureFile=Path.of(request.path("fixtureFile").asText());
        try(var support=new ReleaseContractSupport()) {
            if(request.path("action").asText().equals("prepare")) {
                phase("prepare verified historical accounts",60000,() -> {support.accounts(url,username,password,media,"129");return true;});
                startup=start("historical producer context startup",30000,"lifecycle");
                var fixture=ReleaseReconciliationFixture.prepare(url,username,password,media,support.passwords,folder.resolve("historical-reconciliation-producers.json"),
                        configured -> {endStartup();audit(configured);},this::emitUnchecked);
                Files.writeString(fixtureFile,JSON.writeValueAsString(Map.of("passwords",support.passwords,"accounts",support.accounts,"reconciliation",fixture)),StandardOpenOption.CREATE_NEW);
                try(var configured=open(url,username,password,media)) {
                    var dashboard=business("H129 real dashboard refresh and handler",() -> dashboard(configured,support,null));
                    Files.writeString(folder.resolve("historical-dashboard-before.json"),JSON.writeValueAsString(dashboard));
                    commandIdentities(configured);
                }
            } else if(request.path("action").asText().equals("resume")) {
                var fixture=JSON.readTree(Files.readString(fixtureFile));fixture.path("passwords").properties().forEach(entry -> support.passwords.put(entry.getKey(),entry.getValue().asText()));
                try(var configured=open(url,username,password,media)) {
                    UUID refund=UUID.fromString(request.path("refundAttempt").asText());
                    business("H129 refund submit and result after V132 migration",() -> {runCause(configured,"REFUND_SUBMIT.v1",refund);runCause(configured,"REFUND_RESULT_RECORDED.v1",refund);return refund;});
                    long run=request.path("dashboardRun").asLong();
                    var dashboard=business("H129 queued HTTP dashboard refresh after V132 migration",() -> dashboard(configured,support,run));
                    Files.writeString(folder.resolve("historical-dashboard-after.json"),JSON.writeValueAsString(dashboard));
                }
            } else {throw new IllegalArgumentException("Unknown historical probe action");}
        }
    }
    private ConfigurableApplicationContext open(String url,String username,String password,Path media) throws Exception {
        return phase("historical HTTP and application context startup",30000,() -> {
            var app=new SpringApplication(MarketplaceApplication.class);
            app.setDefaultProperties(Map.of("spring.datasource.url",url,"spring.datasource.username",username,"spring.datasource.password",password,
                    "DB_URL",url,"DB_USERNAME",username,"DB_PASSWORD",password,"MEDIA_ROOT",media.toString(),"marketplace.media.root",media.toString()));
            var configured=app.run("--spring.profiles.active=db,local-http,local-mailbox","--server.address=127.0.0.1","--server.port=0",
                    "--marketplace.phone.mode=disabled","--marketplace.runtime.mode=local-full","--marketplace.worker.enabled=false","--spring.flyway.locations=classpath:db/migration");
            audit(configured);return configured;
        });
    }
    private Object dashboard(ConfigurableApplicationContext configured,ReleaseContractSupport support,Long existingRun) throws Exception {
        int port=configured.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
        var client=support.new Client("http://127.0.0.1:"+port);String email="p12d-desktop-admin@example.com";client.login(email);
        var jdbc=configured.getBean(NamedParameterJdbcTemplate.class);
        UUID member=UUID.fromString(client.request("GET","/api/auth/me",null,200).path("id").asText());
        var session=jdbc.queryForMap("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id ORDER BY last_access_time DESC LIMIT 1",Map.of("id",member.toString()));
        Long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",member),Long.class);
        var actor=new Dashboards.Actor(member,session.get("session_id").toString(),version);
        long run=existingRun==null?configured.getBean(Dashboards.class).refresh(actor,UUID.randomUUID(),"고정 H129 실제 원 거래 집계"):existingRun;
        UUID job=jdbc.queryForObject("SELECT job_id FROM marketplace.dashboard_run WHERE id=:id",Map.of("id",run),UUID.class);runJob(configured,"DASHBOARD_REFRESH.v1",job);
        LocalDate from=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(14),to=LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        var view=configured.getBean(Dashboards.class).read(actor,"settlements",from,to);
        assertEquals("READY",view.status());assertTrue(view.generation()!=null);assertFalse(view.metrics().isEmpty());
        long expectedPaid=existingRun==null?112040:158116,expectedItemRefund=existingRun==null?37039:47039;
        assertEquals(Long.toString(expectedPaid),view.metrics().stream().filter(metric -> metric.id().equals("PAID_AMOUNT")).findFirst().orElseThrow().value());
        assertEquals(Long.toString(expectedItemRefund),view.metrics().stream().filter(metric -> metric.id().equals("REFUNDED_ITEM")).findFirst().orElseThrow().value());
        assertEquals("3000",view.metrics().stream().filter(metric -> metric.id().equals("REFUNDED_SHIPPING")).findFirst().orElseThrow().value());
        return Map.of("run",run,"job",job,"actorId",member,"port","Dashboards.refresh/read","generation",view.generation(),"from",from.toString(),"to",to.toString(),"metrics",view.metrics());
    }
    private void runCause(ConfigurableApplicationContext configured,String type,UUID cause) throws Exception {
        UUID job=configured.getBean(NamedParameterJdbcTemplate.class).queryForObject("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status IN ('READY','RUNNING') ORDER BY scheduled_at DESC,id LIMIT 1",Map.of("type",type,"cause",cause.toString()),UUID.class);
        runJob(configured,type,job);
    }
    private void runJob(ConfigurableApplicationContext configured,String type,UUID id) throws Exception {
        var saved=configured.getBean(Jobs.class).find(id).orElseThrow();
        var jobs=new JdbcJobs(new OneJobJdbc(configured.getBean(DataSource.class),id),configured.getBean(PlatformTransactionManager.class),Clock.fixed(saved.nextRunAt().plusSeconds(1),ZoneOffset.UTC));
        var handlers=configured.getBeansOfType(JobHandler.class).values().stream().filter(handler -> handler.type().equals(type)).toList();assertEquals(1,handlers.size());
        for(var handler:handlers) {origin(org.springframework.aop.support.AopUtils.getTargetClass(handler));}
        try(var worker=new WorkerEngine(jobs,handlers)) {assertTrue(worker.runOnce());}
        assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status(),"Historical actual handler must complete "+type);
    }
    private void audit(ConfigurableApplicationContext configured) {
        try {
            for(String name:List.of("identity.api.SignupOperations","catalog.api.Categories","catalog.api.Products","catalog.api.Skus","inventory.api.Inventory",
                    "order.infrastructure.OrderCreationService","order.api.OrderQueries","order.api.OrderConfirmations","payment.api.Payments","payment.api.Refunds",
                    "claim.api.ReturnFinances","claim.infrastructure.SupportRefunds","settlement.api.Settlements","reporting.api.Dashboards","platform.api.Jobs")) {
                Class<?> type=Class.forName("com.example.marketplace."+name);origin(type);origin(org.springframework.aop.support.AopUtils.getTargetClass(configured.getBean(type)));
            }
            origin(org.springframework.transaction.support.TransactionTemplate.class);origin(org.springframework.aop.support.AopUtils.getTargetClass(configured.getBean(PlatformTransactionManager.class)));
            for(var handler:configured.getBeansOfType(JobHandler.class).values()) {origin(org.springframework.aop.support.AopUtils.getTargetClass(handler));}
            for(var entry:entries.entrySet()) {
                String name=entry.getKey();if(!name.startsWith("BOOT-INF/classes/db/")||!name.endsWith(".sql")) {continue;}
                String resource=name.substring("BOOT-INF/classes/".length());var urls=java.util.Collections.list(ClassLoader.getSystemResources(resource));assertEquals(1,urls.size(),"Exclusive historical SQL resource "+resource);
                assertEquals(Path.of(entry.getValue().path("file").asText()).toUri().toURL(),urls.getFirst());
                assertEquals(entry.getValue().path("sha256").asText(),sha(urls.getFirst().openStream().readAllBytes()));
            }
            assertEquals(null,ClassLoader.getSystemResource("db/migration/V130__site_shipping_policy.sql"));
        } catch(Exception failure) {throw new IllegalStateException("H129_RUNTIME_ORIGIN_AUDIT_FAILED",failure);}
    }
    private void commandIdentities(ConfigurableApplicationContext configured) throws Exception {
        var jdbc=configured.getBean(NamedParameterJdbcTemplate.class);var transcript=new LinkedHashMap<String,Object>();
        transcript.put("OrderCreationService.create",jdbc.queryForList("SELECT o.id AS order_id,o.order_number,o.member_id AS actor_id,c.request_key,o.amount,o.state FROM marketplace.purchase_order o JOIN marketplace.order_checkout_attempt c ON c.id=o.checkout_id ORDER BY o.id",Map.of()));
        transcript.put("Refunds.reserve/actual-handler",jdbc.queryForList("SELECT r.id AS attempt_id,r.order_id,r.seller_order_id,r.claim_id,r.submit_key AS request_key,o.member_id AS buyer_actor_id,r.amount,r.state FROM marketplace.payment_refund_attempt r JOIN marketplace.purchase_order o ON o.id=r.order_id ORDER BY r.id",Map.of()));
        transcript.put("Settlements.open/review/simulatePay",jdbc.queryForList("SELECT request_key,batch_id,action,actor_id,expected_version FROM marketplace.settlement_command ORDER BY request_key",Map.of()));
        transcript.put("SupportRefunds.confirmed-execute",jdbc.queryForList("SELECT id AS request_key,approval_id,attempt_id,actor_id FROM marketplace.claim_support_refund_execution ORDER BY id",Map.of()));
        transcript.put("actualHistoricalClassOrigins","historical-"+request.path("label").asText()+"-origins.json");
        transcript.put("portsAreUiEvidence",false);transcript.put("syntheticClockAndFaultInputs","Explicit approved P09/P11 test preparation; not actual elapsed weeks or external failures");
        Files.writeString(folder.resolve("historical-public-command-ids.json"),JSON.writeValueAsString(transcript));
    }
    private void origin(Class<?> type) throws Exception {
        Path source=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();String resource=type.getName().replace('.','/')+".class";
        var urls=java.util.Collections.list(ClassLoader.getSystemResources(resource));assertEquals(1,urls.size(),"Exclusive runtime class "+type.getName());
        String hash=sha(urls.getFirst().openStream().readAllBytes());
        if(type.getName().startsWith("com.example.marketplace.")) {
            assertEquals(applicationRoot,source,"H129 application class source: "+type.getName());
            assertEquals(entries.get("BOOT-INF/classes/"+resource).path("sha256").asText(),hash);
        } else {
            assertTrue(source.startsWith(libraryRoot),"Transaction/runtime class must come from H129 libraries: "+type.getName());
            assertEquals(entries.get("BOOT-INF/lib/"+source.getFileName()).path("sha256").asText(),sha(Files.readAllBytes(source)));
        }
        origins.add(Map.of("class",type.getName(),"codeSource",source.toString(),"resource",urls.getFirst().toString(),"classSha256",hash));
    }
    private <T> T business(String name,Callable<T> work) throws Exception {return measured(name,10000,"operation",work);}
    private <T> T phase(String name,int limit,Callable<T> work) throws Exception {return measured(name,limit,"lifecycle",work);}
    private <T> T measured(String name,int limit,String kind,Callable<T> work) throws Exception {
        var start=start(name,limit,kind);String status="failed";var executor=Executors.newSingleThreadExecutor();var future=executor.submit(work);
        try {T value=future.get(limit,TimeUnit.MILLISECONDS);status="passed";return value;}
        finally {if(!future.isDone()) {future.cancel(true);}executor.shutdownNow();end(start,status);}
    }
    private Map<String,Object> start(String name,int limit,String kind) throws Exception {
        long at=System.currentTimeMillis();var row=Map.<String,Object>of("id",UUID.randomUUID().toString(),"kind",kind,"name",name,"startedAt",at,"deadlineAt",at+limit,"pid",ProcessHandle.current().pid());
        var start=new LinkedHashMap<>(row);start.put("event","start");emitUnchecked(start);return row;
    }
    private void end(Map<String,Object> start,String status) {var row=new LinkedHashMap<>(start);row.put("event","end");row.put("status",status);row.put("endedAt",System.currentTimeMillis());emitUnchecked(row);}
    private void endStartup() {if(startup!=null) {end(startup,"passed");startup=null;}}
    private synchronized void emitUnchecked(Map<String,Object> event) {try {Files.writeString(progress,JSON.writeValueAsString(event)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception failure) {throw new IllegalStateException(failure);}}
    private static String sha(byte[] bytes) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
