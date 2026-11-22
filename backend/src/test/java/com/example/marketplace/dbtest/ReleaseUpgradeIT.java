package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixed H129 application ports plus historical HTTP; no current application fallback. */
@ExtendWith(ContractTiming.class)
class ReleaseUpgradeIT {
    // Keep the registered historical upgrade case identity as the current end version advances.
    @Test void retainedH129ApiWritesBeforeAndAfterRealV132MigrationWithoutRewritingHistory() throws Exception {
        try(var support=new ReleaseContractSupport()) {
            Path old=ReleaseContractSupport.requiredPath("P12_D_H129_JAR"),proof=ReleaseContractSupport.requiredPath("P12_D_HISTORY_PROOF");
            var provenance=support.json.readTree(Files.readString(proof));
            var records=provenance.path("runs").valueStream().filter(record -> record.path("label").asText().equals("H129")).toList();
            assertEquals(1,records.size());var retained=records.getFirst();
            assertEquals("aee626bb8d69ba1b4191d5a4688cb6b39538839c",retained.path("commit").asText());
            assertEquals(old.toAbsolutePath().normalize(),Path.of(retained.path("jar").asText()).toAbsolutePath().normalize());
            assertEquals(ReleaseContractSupport.sha(old),retained.path("jarSha256").asText());
            try(var jar=new JarFile(old.toFile())) {
                assertTrue(jar.stream().anyMatch(entry -> entry.getName().matches(".*db/migration/V129__.*")));
                assertFalse(jar.stream().anyMatch(entry -> entry.getName().matches(".*db/migration/V13[0-9]__.*")));
            }
            var runtime=HistoricalReleaseArtifacts.prepare(old,support.folder.resolve("exclusive-H129-runtime"));
            String database=System.getenv("D2_DB_NAME"),url=support.createDatabase(database),password=System.getenv("D2_DB_PASSWORD");
            var jdbc=support.jdbc(url,"marketplace_test",password);int port=ReleaseContractSupport.port();Path media=support.folder.resolve("media");
            var first=support.jar(old,"H129-initial",url,"marketplace_test",password,media,port,"api");ContractTiming.identity(port,url);
            assertEquals("129",jdbc.queryForObject("SELECT version FROM marketplace.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",Map.of(),String.class));
            support.stop(first);
            Path fixtureFile=support.folder.resolve("historical-fixture.private.json");
            HistoricalReleaseArtifacts.run(support,runtime,probeRequest(support,"prepare",url,password,media,fixtureFile),"prepare");
            var fixture=support.json.readTree(Files.readString(fixtureFile));
            fixture.path("passwords").properties().forEach(entry -> support.passwords.put(entry.getKey(),entry.getValue().asText()));
            var financial=fixture.path("reconciliation");assertEquals(4,financial.path("orders").size());
            assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order",Map.of(),Integer.class),"Four advanced paid orders and one actual return-seller setup order");
            first=support.jar(old,"H129-producer",url,"marketplace_test",password,media,port,"api");
            var initialDashboard=support.json.readTree(Files.readString(support.folder.resolve("historical-dashboard-before.json")));
            var beforeConsumers=consumeHistoricalHttp(support,port,financial,initialDashboard);
            var purchase=support.purchase("http://127.0.0.1:"+port,"desktop","historical-before");
            assertEquals(purchase.amount(),jdbc.queryForObject("SELECT amount FROM marketplace.purchase_order WHERE id=CAST(:id AS uuid)",Map.of("id",purchase.id()),Long.class));
            var beforeSettlementWrite=settlementHttpWrite(support,port,jdbc,financial.path("settlements").get(0).path("batchId").asText());
            support.stop(first);assertFalse(first.isAlive());
            var original=support.snapshot(jdbc,true);
            var history=jdbc.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM marketplace.flyway_schema_history ORDER BY installed_rank",Map.of());
            var sessions=jdbc.queryForList("SELECT primary_id,session_id,principal_name FROM marketplace.spring_session ORDER BY primary_id",Map.of());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'",Map.of(),Integer.class));
            assertFalse(support.children.stream().anyMatch(Process::isAlive),"No API, worker or historical producer remains active before exclusive migration");
            Path migrations=support.root.resolve("backend/src/main/resources/db/migration");
            var failures=new ArrayList<Map<String,Object>>();
            for(String fault:List.of("transaction-failure","statement-timeout")) {
                Path copied=copyMigrations(migrations,support.folder.resolve("migration-"+fault)),v130=version130(copied);
                Files.writeString(v130,Files.readString(v130)+(fault.equals("statement-timeout")?"\nSET LOCAL statement_timeout='1000ms';\nSELECT pg_sleep(5);\n":"\nSELECT 1/0;\n"));
                var failed=flyway(url,password,"filesystem:"+copied.toAbsolutePath());
                var failure=assertThrows(org.flywaydb.core.api.FlywayException.class,failed::migrate);
                unchanged(original,support.snapshot(jdbc,true),"Failed copied V130 changed original business rows");
                assertTrue(history.equals(jdbc.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM marketplace.flyway_schema_history ORDER BY installed_rank",Map.of())),"Transactional failure must preserve SCHEMA NULL history");
                assertEquals(null,jdbc.queryForObject("SELECT to_regclass('marketplace.site_shipping_policy')",Map.of(),String.class));
                assertFalse(support.children.stream().anyMatch(Process::isAlive));
                String sqlState=sqlState(failure);assertEquals(fault.equals("statement-timeout")?"57014":"22012",sqlState);
                failures.add(Map.of("fault",fault,"sqlState",sqlState,"copiedV130Sha256",ReleaseContractSupport.sha(v130),"businessAndHistoryUnchanged",true,"realProcessCrash",false));
            }
            var migration=flyway(url,password,"classpath:db/migration");
            var expectedVersions=List.of("130","131","132","133");
            assertEquals(expectedVersions,java.util.Arrays.stream(migration.info().pending()).map(info -> info.getVersion().getVersion()).toList());
            assertEquals(expectedVersions.size(),migration.migrate().migrationsExecuted);migration.validate();assertEquals(0,migration.migrate().migrationsExecuted);
            var migratedBusiness=support.snapshot(jdbc,true);
            var historicalTables=new LinkedHashMap<>(migratedBusiness);
            historicalTables.put("catalog_product",jdbc.queryForObject("""
                    SELECT coalesce(jsonb_agg(legacy ORDER BY legacy::text),'[]'::jsonb)::text
                    FROM (SELECT to_jsonb(p)-'description_blocks' AS legacy FROM marketplace.catalog_product p) products
                    """,Map.of(),String.class));
            for(var entry:original.entrySet()) {assertTrue(entry.getValue().equals(historicalTables.get(entry.getKey())),"Historical business canonical hash mismatch: "+entry.getKey());}
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_product WHERE description_blocks IS NOT NULL",Map.of(),Integer.class));
            assertEquals("[]",migratedBusiness.get("media_product_description"));
            assertTrue(history.equals(jdbc.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM marketplace.flyway_schema_history WHERE version IS NULL OR version::integer<=129 ORDER BY installed_rank",Map.of())),"Historical history must preserve NULL SCHEMA version");
            assertTrue(sessions.equals(jdbc.queryForList("SELECT primary_id,session_id,principal_name FROM marketplace.spring_session ORDER BY primary_id",Map.of())),"Historical JDBC session identifiers changed during migration");
            assertEquals(Map.of("base_fee",3000L,"free_threshold",50000L),jdbc.queryForMap("SELECT base_fee,free_threshold FROM marketplace.site_shipping_policy WHERE version=1",Map.of()));
            assertEquals(false,jdbc.queryForObject("SELECT marketplace.site_post_visible('PUBLISHED',now(),now(),now())",Map.of(),Boolean.class));
            Path tampered=copyMigrations(migrations,support.folder.resolve("migration-checksum-mismatch")),changed=version130(tampered);
            Files.writeString(changed,Files.readString(changed)+"\n-- deliberate copied applied-checksum fault\n");
            var currentHistory=jdbc.queryForList("SELECT installed_rank,version,checksum,success FROM marketplace.flyway_schema_history ORDER BY installed_rank",Map.of());
            assertThrows(org.flywaydb.core.api.exception.FlywayValidateException.class,() -> flyway(url,password,"filesystem:"+tampered.toAbsolutePath()).validate());
            unchanged(migratedBusiness,support.snapshot(jdbc,true),"Checksum validation must be nonmutating");
            assertTrue(currentHistory.equals(jdbc.queryForList("SELECT installed_rank,version,checksum,success FROM marketplace.flyway_schema_history ORDER BY installed_rank",Map.of())),"Checksum rejection changed history");
            Path candidateClasses=Path.of(MarketplaceApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidateJar=candidateClasses.getParent().resolve("marketplace-api-0.0.1-SNAPSHOT.jar");
            assertTrue(Files.isRegularFile(candidateJar),"Actual current candidate R1 JAR required for the historical DB migration sequence");
            var candidate=support.jar(candidateJar,"R1-on-migrated-H129",url,"marketplace_test",password,media,port,"api");
            var candidateCookieRead=purchase.buyer().request("GET","/api/orders/"+purchase.number(),null,200);
            assertEquals(purchase.number(),candidateCookieRead.path("orderNumber").asText());
            assertEquals(Long.toString(purchase.amount()),candidateCookieRead.path("total").asText());
            assertEquals("PAID",candidateCookieRead.path("state").asText());
            var candidateConsumers=consumeHistoricalHttp(support,port,financial,initialDashboard);
            support.stop(candidate);assertFalse(candidate.isAlive());
            unchanged(migratedBusiness,support.snapshot(jdbc,true),"R1 historical HTTP consumers must preserve historical business rows");
            assertFalse(support.children.stream().anyMatch(Process::isAlive),"Current R1 writer must stop before historical H129 resumes");
            var restarted=support.jar(old,"H129-on-V133",url,"marketplace_test",password,media,port,"api");
            var read=purchase.buyer().request("GET","/api/orders/"+purchase.number(),null,200);
            assertEquals(Long.toString(purchase.amount()),read.path("total").asText());assertEquals("PAID",read.path("state").asText());
            var afterConsumers=consumeHistoricalHttp(support,port,financial,initialDashboard);
            var after=support.purchase("http://127.0.0.1:"+port,"mobile","historical-after");
            assertEquals(7,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order",Map.of(),Integer.class),"Five actual advanced/setup orders plus both historical HTTP orders");
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE order_number IN (:before,:after)",Map.of("before",purchase.number(),"after",after.number()),Integer.class),"Original narrow HTTP cohort remains exactly two orders");
            assertEquals(after.amount(),jdbc.queryForObject("SELECT amount FROM marketplace.purchase_order WHERE id=CAST(:id AS uuid)",Map.of("id",after.id()),Long.class));
            var operator=support.new Client("http://127.0.0.1:"+port);operator.login("p12d-desktop-admin@example.com");
            var afterSettlementWrite=settlementHttpWrite(support,port,jdbc,financial.path("settlements").get(0).path("batchId").asText());
            var partial=financial.path("orders").get(0);var buyer=support.new Client("http://127.0.0.1:"+port);buyer.login(partial.path("buyerEmail").asText());
            String number=partial.path("orderNumber").asText(),item=partial.path("units").get(0).path("itemId").asText();
            long revision=buyer.request("GET","/api/orders/"+number+"/confirmations",null,200).path("revision").asLong();
            var units=List.of(Map.of("itemId",item,"ordinal",1));String base="/api/orders/"+number+"/cancellations";
            var quote=buyer.request("POST",base+"/quote",Map.of("units",units,"reason","기존 H129의 이행 후 원단위 환불","expectedRevision",revision),200);
            var cancellation=buyer.request("POST",base,Map.of("key","h129-after-"+UUID.randomUUID(),"units",units,"reason","기존 H129의 이행 후 원단위 환불","expectedRevision",revision,"quoteFingerprint",quote.path("fingerprint").asText()),202);
            assertEquals("10000",cancellation.path("itemAmount").asText());String refund=cancellation.path("refundAttemptId").asText();
            long refresh=operator.request("POST","/api/admin/system/dashboard/refresh",Map.of("key",UUID.randomUUID(),"reason","이행 뒤 H129 HTTP 환불과 원 지급 집계"),200).asLong();
            support.stop(restarted);
            var resume=probeRequest(support,"resume",url,password,media,fixtureFile);resume.put("refundAttempt",refund);resume.put("dashboardRun",refresh);
            HistoricalReleaseArtifacts.run(support,runtime,resume,"resume");
            restarted=support.jar(old,"H129-final-consumer",url,"marketplace_test",password,media,port,"api");
            var finalBuyer=support.new Client("http://127.0.0.1:"+port);finalBuyer.login(partial.path("buyerEmail").asText());
            var refunds=finalBuyer.request("GET","/api/orders/"+number+"/refunds",null,200);
            var newRefund=refunds.valueStream().filter(row -> row.path("attemptId").asText().equals(refund)).findFirst().orElseThrow();
            assertEquals("SUCCEEDED",newRefund.path("state").asText());assertEquals("10000",newRefund.path("amount").asText());
            var dashboard=support.json.readTree(Files.readString(support.folder.resolve("historical-dashboard-after.json")));
            var finalOperator=support.new Client("http://127.0.0.1:"+port);finalOperator.login("p12d-desktop-admin@example.com");assertDashboard(finalOperator,dashboard);
            assertEquals("23038",purchase.buyer().request("GET","/api/orders/"+purchase.number(),null,200).path("total").asText());support.stop(restarted);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("historicalCommit","aee626bb8d69ba1b4191d5a4688cb6b39538839c");
            evidence.put("historicalJarSha256",ReleaseContractSupport.sha(old));evidence.put("provenanceSha256",ReleaseContractSupport.sha(proof));
            evidence.put("exclusiveRuntimeManifest",runtime.manifest().toString());evidence.put("database",database);evidence.put("migrationVersions",expectedVersions);
            evidence.put("businessBeforeHashes",hashes(original));evidence.put("historyRowsPreserved",history.size());evidence.put("sessionRowsPreserved",sessions.size());
            evidence.put("beforeOrder",purchase.number());evidence.put("afterOrder",after.number());evidence.put("sameOldCookieRead",true);evidence.put("actualOldApiWritesAfterUpgrade",true);
            evidence.put("advancedBeforeHttp",beforeConsumers);evidence.put("advancedAfterHttp",afterConsumers);evidence.put("newHistoricalHttpRefund",Map.of("attemptId",refund,"amount","10000","state","SUCCEEDED"));
            evidence.put("candidateJarPath",candidateJar.toAbsolutePath().toString());evidence.put("candidateJarSha256",ReleaseContractSupport.sha(candidateJar));evidence.put("candidatePid",candidate.pid());evidence.put("candidateHistoricalHttp",candidateConsumers);
            evidence.put("candidateReadRetainedOldCookie",true);evidence.put("candidateWriterStoppedBeforeHistoricalResume",true);
            evidence.put("beforeSettlementHttpWrite",beforeSettlementWrite);evidence.put("afterSettlementHttpWrite",afterSettlementWrite);evidence.put("migrationFaults",failures);
            evidence.put("appliedChecksumMismatchRejected",true);evidence.put("dashboardAfter",dashboard);evidence.put("currentApplicationFallback",false);
            Files.writeString(support.folder.resolve("historical-upgrade.json"),support.json.writeValueAsString(evidence));
        }
    }
    private Map<String,Object> probeRequest(ReleaseContractSupport support,String action,String url,String password,Path media,Path fixture) {
        var input=new LinkedHashMap<String,Object>();input.put("action",action);input.put("label",action);input.put("url",url);input.put("username","marketplace_test");
        input.put("password",password);input.put("media",media.toString());input.put("folder",support.folder.toString());input.put("fixtureFile",fixture.toString());return input;
    }
    private Map<String,Object> consumeHistoricalHttp(ReleaseContractSupport support,int port,JsonNode fixture,JsonNode dashboard) throws Exception {
        String base="http://127.0.0.1:"+port;var observed=new ArrayList<Map<String,Object>>();
        for(var expected:fixture.path("orders")) {
            var buyer=support.new Client(base);buyer.login(expected.path("buyerEmail").asText());String number=expected.path("orderNumber").asText();
            var detail=buyer.request("GET","/api/orders/"+number,null,200);assertEquals(expected.path("state").asText(),detail.path("state").asText());assertEquals(expected.path("total").asText(),detail.path("total").asText());
            var units=new ArrayList<JsonNode>();for(var store:detail.path("stores")) {for(var item:store.path("items")) {for(var unit:item.path("units")) {var copy=support.json.createObjectNode();copy.put("itemId",item.path("id").asText());for(String field:List.of("ordinal","gross","promotionDiscount","couponDiscount","net")) {copy.set(field,unit.path(field));}units.add(copy);}}}
            assertTrue(units.equals(expected.path("units").valueStream().toList()),"Historical HTTP original-unit IDs/money mismatch: "+number);
            var refunds=buyer.request("GET","/api/orders/"+number+"/refunds",null,200);assertEquals(expected.path("refunds").size(),refunds.size());
            for(var refund:expected.path("refunds")) {var actual=refunds.valueStream().filter(row -> row.path("attemptId").asText().equals(refund.path("attemptId").asText())).findFirst().orElseThrow();assertEquals(refund.path("state").asText(),actual.path("state").asText());assertEquals(refund.path("amount").asText(),actual.path("amount").asText());}
            observed.add(Map.of("orderNumber",number,"state",detail.path("state").asText(),"total",detail.path("total").asText(),"originalUnits",units,"refundAttempts",expected.path("refunds")));
        }
        var operator=support.new Client(base);operator.login("p12d-desktop-admin@example.com");
        for(var expected:fixture.path("settlements")) {
            var seller=support.new Client(base);seller.login(expected.path("sellerEmail").asText());var rows=seller.request("GET","/api/seller/settlements",null,200);
            var week=rows.valueStream().filter(row -> row.path("weekStart").asText().equals(expected.path("weekStart").asText())).findFirst().orElseThrow();
            for(String field:List.of("net","payout","opening","closing")) {assertEquals(expected.path(field).asText(),week.path(field).asText());}
            assertTrue(week.path("lines").valueStream().anyMatch(line -> line.path("sellerOrderId").asText().equals(expected.path("sellerOrderId").asText())));
            var batch=operator.request("GET","/api/admin/settlements/batches/"+expected.path("batchId").asText(),null,200);assertEquals(expected.path("batchId").asText(),batch.path("id").asText());
        }
        assertDashboard(operator,dashboard);return Map.of("orders",observed,"settlements",fixture.path("settlements"),"dashboardGeneration",dashboard.path("generation").asText());
    }
    private void assertDashboard(ReleaseContractSupport.Client operator,JsonNode expected) throws Exception {
        var view=operator.request("GET","/api/admin/settlements/dashboard?from="+expected.path("from").asText()+"&to="+expected.path("to").asText(),null,200);
        assertEquals(expected.path("generation").asText(),view.path("generation").asText());assertEquals("READY",view.path("status").asText());
        assertFalse(view.path("metrics").isEmpty());assertTrue(expected.path("metrics").equals(view.path("metrics")),"Historical HTTP dashboard financial metrics mismatch");
    }
    private Map<String,Object> settlementHttpWrite(ReleaseContractSupport support,int port,org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,String id) throws Exception {
        var parameters=Map.of("id",UUID.fromString(id));
        var command=jdbc.queryForMap("SELECT c.request_key,c.expected_version,c.actor_id,u.email FROM marketplace.settlement_command c JOIN marketplace.identity_user u ON u.id=c.actor_id WHERE c.batch_id=:id AND c.action='PAY_MOCK'",parameters);
        var original=jdbc.queryForList("SELECT id,batch_id,amount,actor_id,occurred_at FROM marketplace.settlement_mock_payment WHERE batch_id=:id",parameters);
        assertEquals(1,original.size());assertEquals(22037L,((Number)original.getFirst().get("amount")).longValue());
        var operator=support.new Client("http://127.0.0.1:"+port);operator.login(command.get("email").toString());
        var paid=operator.request("POST","/api/admin/settlements/batches/"+id+"/simulate-pay",Map.of("key",command.get("request_key"),"expectedVersion",((Number)command.get("expected_version")).longValue()),200);
        assertEquals(id,paid.path("id").asText());assertEquals("PAID_MOCK",paid.path("state").asText());assertEquals("22037",paid.path("payout").asText());
        assertTrue(original.equals(jdbc.queryForList("SELECT id,batch_id,amount,actor_id,occurred_at FROM marketplace.settlement_mock_payment WHERE batch_id=:id",parameters)),"Historical HTTP same-command replay must preserve the exact original positive payout row");
        return Map.of("id",id,"actorId",command.get("actor_id"),"requestKey",command.get("request_key"),"expectedVersion",command.get("expected_version"),"state","PAID_MOCK","payout","22037","paymentEffects",1,"sameCommandReplay",true);
    }
    private Flyway flyway(String url,String password,String location) {return Flyway.configure().dataSource(new DriverManagerDataSource(url,"marketplace_test",password)).schemas("marketplace").defaultSchema("marketplace").locations(location).cleanDisabled(true).load();}
    private Path copyMigrations(Path source,Path target) throws Exception {Files.createDirectory(target);try(var paths=Files.list(source)) {for(var path:paths.filter(Files::isRegularFile).toList()) {Files.copy(path,target.resolve(path.getFileName()));}}return target;}
    private Path version130(Path directory) throws Exception {try(var paths=Files.list(directory)) {return paths.filter(path -> path.getFileName().toString().startsWith("V130__")).findFirst().orElseThrow();}}
    private static void unchanged(Map<String,String> before,Map<String,String> after,String message) {assertTrue(before.equals(after),message+" (canonical hashes only; raw rows withheld)");}
    private Map<String,String> hashes(Map<String,String> rows) throws Exception {var result=new java.util.TreeMap<String,String>();for(var row:rows.entrySet()) {result.put(row.getKey(),HistoricalReleaseArtifacts.sha(row.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}return result;}
    private String sqlState(Throwable failure) {for(Throwable current=failure;current!=null;current=current.getCause()) {if(current instanceof java.sql.SQLException sql) {return sql.getSQLState();}}throw new AssertionError("Actual PostgreSQL failure SQLSTATE required");}
}
