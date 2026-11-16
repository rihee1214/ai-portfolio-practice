package com.example.marketplace.dbtest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Counterexamples mutate validator input copies, never database business state. */
class ReleaseSnapshotContractTest {
    private static final Instant START=Instant.parse("2026-11-16T00:00:00Z");
    private static final String TARGET="11111111-1111-4111-8111-111111111111";
    record Pair(ReleaseSnapshotContract.Snapshot before,ReleaseSnapshotContract.Snapshot after) { }

    @Test void acceptsOnlyTheExactStartupGraphWhilePreservingEveryOriginalRow() {
        var pair=fixture();assertEquals(true,check(pair).get("passed"));
    }
    @Test void rejectsDeletionOfAnOriginalRow() {
        var pair=fixture();pair.after().tables().get("purchase_order").clear();reject(pair);
    }
    @Test void rejectsOneWonMutationOfOriginalMoney() {
        var pair=fixture();replace(pair,"purchase_order",0,"amount",10001);reject(pair);
    }
    @Test void rejectsRewriteOfOriginalImmutableHistory() {
        var pair=fixture();replace(pair,"payment_event",0,"action","REWRITTEN");reject(pair);
    }
    @Test void rejectsAnUnrelatedRecoveryAppend() {
        var pair=fixture();pair.after().tables().get("claim_return_recovery_page").add(node("id",UUID.randomUUID(),"run_id",UUID.randomUUID(),"stage","ITEM_EFFECT"));reject(pair);
    }
    @Test void rejectsDuplicateRestartRuns() {
        var pair=fixture();var rows=pair.after().tables().get("claim_return_recovery_run");var copy=map(rows.getFirst());copy.put("id",UUID.randomUUID().toString());rows.add(node(copy));reject(pair);
    }
    @Test void rejectsWrongClaimOwnerAndWrongJobCause() {
        var owner=fixture();replace(owner,"claim_return_recovery_page",0,"plan_owner_token",UUID.randomUUID().toString());reject(owner);
        var cause=fixture();replace(cause,"job",0,"cause_key",UUID.randomUUID().toString());reject(cause);
    }
    @Test void rejectsCompletedStageCursorRegression() {
        var pair=fixture();replace(pair,"claim_return_recovery_stage",0,"completed",false);reject(pair);
    }
    @Test void rejectsAnUnregisteredTableChange() {
        var pair=fixture();pair.after().tables().put("unknown_release_table",new ArrayList<>());reject(pair);
    }
    @Test void rejectsSourceSequenceAndMediaMutationDuringRestore() {
        var pair=fixture();var changed=new ReleaseSnapshotContract.Snapshot(pair.after().id(),pair.after().tables(),pair.after().keys(),List.of(Map.of("name","seq","value","2")),pair.after().mediaDigest(),pair.after().locators(),pair.after().operatorDatabase(),pair.after().operatorMedia(),pair.after().observation());
        assertThrows(AssertionError.class,()->ReleaseSnapshotContract.validate(pair.before(),changed,START,START.plusSeconds(5),false));
        var media=new ReleaseSnapshotContract.Snapshot(pair.after().id(),pair.after().tables(),pair.after().keys(),pair.after().sequences(),"different-media",pair.after().locators(),pair.after().operatorDatabase(),pair.after().operatorMedia(),pair.after().observation());
        assertThrows(AssertionError.class,()->ReleaseSnapshotContract.validate(pair.before(),media,START,START.plusSeconds(5),false));
        var tables=new TreeMap<>(pair.before().tables());tables.put("purchase_order",new ArrayList<>(List.of(node("id",TARGET,"amount",10001))));
        var source=new ReleaseSnapshotContract.Snapshot(pair.before().id(),tables,pair.before().keys(),pair.before().sequences(),pair.before().mediaDigest(),pair.before().locators(),pair.before().operatorDatabase(),pair.before().operatorMedia(),pair.before().observation());
        assertThrows(AssertionError.class,()->ReleaseSnapshotContract.exact(pair.before(),source));
    }
    @Test void nonPrimaryIdentityPreservesEveryDuplicateMultiplicity() {
        var pair=fixture();pair.before().keys().put("media_slot",ReleaseSnapshotContract.MULTISET);
        var row=node("kind","products","target_id",TARGET,"slot",0,"asset_id",null);
        pair.before().tables().get("media_slot").addAll(List.of(row,row));pair.after().tables().get("media_slot").addAll(List.of(row,row));
        assertEquals(true,check(pair).get("passed"));assertEquals(2,ReleaseSnapshotContract.index(pair.after(),"media_slot").size());
        pair.after().tables().get("media_slot").removeLast();reject(pair);
    }
    @Test void sellerRecoverySkipRequiresOriginalCompletionAndCurrentDecisionChain() {
        var pair=fixture("SELLER_REEVALUATE");assertEquals(true,check(pair).get("passed"));
        var wrong=node("seller_order_id",TARGET,"decision_id",UUID.randomUUID().toString());
        pair.before().tables().get("claim_seller_shipping_current").set(0,wrong);pair.after().tables().get("claim_seller_shipping_current").set(0,wrong);reject(pair);
    }
    @Test void readyRetryUsesNoPreviousLiveOwnerAndKeepsOriginalDueBinding() {
        var pair=fixture();String id=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString(),next=UUID.randomUUID().toString();
        var old=node("id",id,"type","SHIPMENT_PROGRESS","dedupe_key","existing-retry","cause_key",TARGET,"payload",TARGET,"scheduled_at",START.minusSeconds(2),"next_run_at",START.plusSeconds(1),"status","READY","attempts",1,"owner_token",null,"lease_until",null);
        var completed=node("id",id,"type","SHIPMENT_PROGRESS","dedupe_key","existing-retry","cause_key",TARGET,"payload",TARGET,"scheduled_at",START.minusSeconds(2),"next_run_at",START.plusSeconds(1),"status","SUCCEEDED","attempts",2,"owner_token",null,"lease_until",null);
        var first=node("job_id",id,"attempt",1,"owner_token",owner,"claimed_at",START.minusSeconds(1),"required_at",START.minusSeconds(2),"scheduled_at_snapshot",START.minusSeconds(2),"initial_lease_until",START.plusSeconds(29),"previous_attempt",0,"previous_owner",null,"eligibility_kind","READY");
        var retry=node("job_id",id,"attempt",2,"owner_token",next,"claimed_at",START.plusSeconds(2),"required_at",START.plusSeconds(1),"scheduled_at_snapshot",START.minusSeconds(2),"initial_lease_until",START.plusSeconds(32),"previous_attempt",1,"previous_owner",null,"eligibility_kind","READY");
        pair.before().tables().get("job").add(old);pair.after().tables().get("job").add(completed);pair.before().tables().get("job_claim_fact").add(first);pair.after().tables().get("job_claim_fact").addAll(List.of(first,retry));assertEquals(true,check(pair).get("passed"));
        var wrong=map(retry);wrong.put("previous_owner",owner);var facts=pair.after().tables().get("job_claim_fact");facts.set(facts.size()-1,node(wrong));reject(pair);
    }
    @Test void actualObserverErrorStreamPreservesPrivateThrowableAndReportsFailedPhase() throws Exception {
        var build=java.nio.file.Path.of(System.getProperty("marketplace.build.directory","target")).toAbsolutePath().normalize();java.nio.file.Files.createDirectories(build);
        var folder=java.nio.file.Files.createDirectory(build.resolve("observer-failure-unit-"+UUID.randomUUID()));
        var failure=new AssertionError("PRIVATE-original-message");failure.initCause(new IllegalStateException("PRIVATE-cause"));failure.addSuppressed(new IllegalArgumentException("PRIVATE-suppressed"));
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/failure",exchange->{exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");exchange.sendResponseHeaders(200,0);ReleaseRecoveryIT.reportObserverFailure(folder,exchange,failure,"Checkpoint",true);});server.start();
        try {
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/failure")).timeout(java.time.Duration.ofSeconds(3)).GET().build();
            var response=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertFalse(response.body().contains("PRIVATE-"));var lines=response.body().lines().map(ReleaseSnapshotContract.JSON::readTree).toList();
            assertEquals(2,lines.size());assertEquals("start",lines.get(0).path("event").asText());assertEquals(10000,lines.get(0).path("limitMs").asInt());assertEquals(lines.get(0).path("id"),lines.get(1).path("id"));assertEquals("end",lines.get(1).path("event").asText());assertEquals("failed",lines.get(1).path("status").asText());
            String id=lines.getFirst().path("id").asText();var privatePath=folder.resolve("observer-failed-"+id+".private.txt");String privateText=java.nio.file.Files.readString(privatePath),safe=java.nio.file.Files.readString(folder.resolve("observer-failed-"+id+".json"));
            assertTrue(privateText.contains("PRIVATE-original-message")&&privateText.contains("PRIVATE-cause")&&privateText.contains("PRIVATE-suppressed"));assertFalse(safe.contains("PRIVATE-"));assertEquals(1,ReleaseSnapshotContract.JSON.readTree(safe).path("suppressedCount").asInt());
            var acl=java.nio.file.Files.getFileAttributeView(privatePath,java.nio.file.attribute.AclFileAttributeView.class);if(acl!=null) {var owner=acl.getOwner();assertTrue(acl.getAcl().stream().allMatch(entry->entry.principal().equals(owner)));}
        } finally {server.stop(0);}
    }

    private static Map<String,Object> check(Pair pair) {return ReleaseSnapshotContract.validate(pair.before(),pair.after(),START,START.plusSeconds(5),false);}
    private static void reject(Pair pair) {assertThrows(AssertionError.class,()->check(pair));}
    private static void replace(Pair pair,String table,int row,String field,Object value) {var data=map(pair.after().tables().get(table).get(row));data.put(field,value);pair.after().tables().get(table).set(row,node(data));}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(JsonNode row) {return new LinkedHashMap<>(ReleaseSnapshotContract.JSON.readValue(row.toString(),Map.class));}
    static JsonNode node(Object... fields) {var map=new LinkedHashMap<String,Object>();for(int index=0;index<fields.length;index+=2) {map.put(fields[index].toString(),fields[index+1]);}return node(map);}
    static JsonNode node(Map<String,Object> fields) {return ReleaseSnapshotContract.JSON.readTree(ReleaseSnapshotContract.JSON.writeValueAsString(fields));}
    static Pair fixture() {return fixture("ITEM_EFFECT");}
    static Pair fixture(String candidateStage) {
        var old=new TreeMap<String,List<JsonNode>>();var current=new TreeMap<String,List<JsonNode>>();var keys=new TreeMap<String,List<String>>();var locators=new TreeMap<String,List<String>>();
        for(String table:ReleaseSnapshotContract.coverage()) {old.put(table,new ArrayList<>());current.put(table,new ArrayList<>());keys.put(table,List.of("id"));}
        for(String family:List.of("return","exchange_refund")) {
            String prefix="claim_"+family+"_recovery_";keys.put(prefix+"stage",List.of("run_id","stage"));keys.put(prefix+"page_complete",List.of("page_id"));
        }
        keys.put("claim_return_recovery_candidate",List.of("page_id","return_id"));keys.put("job_claim_fact",List.of("job_id","attempt"));
        keys.put("dashboard_head",List.of("singleton"));keys.put("dashboard_metric",ReleaseSnapshotContract.MULTISET);
        old.get("dashboard_head").add(node("singleton",true,"run_id",null,"snapshot_id",null,"last_scheduled",null));
        old.get("purchase_order").add(node("id",TARGET,"amount",10000));old.get("payment_event").add(node("id",TARGET,"action","SUCCEEDED"));old.get("claim_return_financial_result").add(node("id",TARGET,"return_id",TARGET));
        if(candidateStage.equals("SELLER_REEVALUATE")) {
            String decision=UUID.randomUUID().toString(),refund=UUID.randomUUID().toString();
            old.get("claim_return_request").add(node("id",TARGET,"order_id",TARGET,"seller_order_id",TARGET,"state","COMPLETED"));
            old.get("claim_return_item_ready").add(node("return_id",TARGET,"order_id",TARGET,"seller_order_id",TARGET,"attempt_id",refund,"result_version",1));
            old.get("payment_refund_attempt").add(node("id",refund,"claim_id",TARGET,"order_id",TARGET,"seller_order_id",TARGET,"purpose","ITEM","state","SUCCEEDED","version",1));
            old.get("claim_return_completion").add(node("return_id",TARGET,"decision_id",decision));old.get("claim_seller_shipping_current").add(node("seller_order_id",TARGET,"decision_id",decision));
            old.get("claim_seller_shipping_decision").add(node("id",decision,"order_id",TARGET,"seller_order_id",TARGET,"units",List.of(Map.of("kind","BUYER_RETURN_READY","factId",TARGET))));
            keys.put("claim_return_item_ready",List.of("return_id"));keys.put("claim_return_completion",List.of("return_id"));keys.put("claim_seller_shipping_current",List.of("seller_order_id"));
        }
        old.forEach((table,rows)->current.get(table).addAll(rows));
        for(String family:List.of("return","exchange_refund")) {
            String prefix="claim_"+family+"_recovery_",run=UUID.randomUUID().toString();
            current.get(prefix+"run").add(node("id",run,"scan_version",family.equals("return")?"P09-D-RECOVERY-1":"P09-E-REFUND-RECOVERY-1","started_at",START,"stages",ReleaseSnapshotContract.STAGES));
            for(String stage:new java.util.TreeSet<>(ReleaseSnapshotContract.STAGES)) {
                var ids=family.equals("return")&&stage.equals(candidateStage)?List.of(TARGET):List.<String>of();locators.put(family+":"+stage,ids);
                String job=UUID.randomUUID().toString(),page=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();String key=(family.equals("return")?"return-recovery-scan:":"exchange-refund-recovery-scan:")+run+":"+stage+":START";
                current.get(prefix+"stage").add(node("run_id",run,"stage",stage,"upper_id",ids.isEmpty()?null:TARGET,"first_job_id",job,"cursor_id",ids.isEmpty()?null:TARGET,"completed",true,"cursor_page_id",page));
                current.get(prefix+"page").add(node("id",page,"run_id",run,"stage",stage,"cursor_key","START","locator_ids",ids,"scan_job_id",job,"plan_owner_token",owner));
                current.get(prefix+"page_complete").add(node("page_id",page,"next_cursor",ids.isEmpty()?null:TARGET,"finished",true,"next_job_id",null,"scan_job_id",job,"owner_token",owner));
                current.get("job").add(node("id",job,"type",family.equals("return")?"RETURN_RECOVERY_SCAN.v1":"EXCHANGE_REFUND_RECOVERY_SCAN.v1","dedupe_key",key,"cause_key",run,"payload",run+"|"+stage+"|START","scheduled_at",START,"status","SUCCEEDED","attempts",1));
                current.get("job_claim_fact").add(node("job_id",job,"attempt",1,"owner_token",owner,"claimed_at",START.plusMillis(1),"required_at",START,"initial_lease_until",START.plusMillis(1).plusSeconds(30),"scheduled_at_snapshot",START,"previous_attempt",0,"previous_owner",null,"eligibility_kind","READY"));
                current.get("outbox_event").add(node("id",UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"job_id",job));
                if(!ids.isEmpty()) {current.get(prefix+"candidate").add(node("page_id",page,"run_id",run,"stage",stage,"return_id",TARGET,"outcome","SKIP","reason",candidateStage.equals("ITEM_EFFECT")?"ITEM_OPERATION_ALREADY_RECORDED":"CURRENT_SELLER_FACTS_ALREADY_EVALUATED","delivery_id",null,"scan_job_id",job,"owner_token",owner));}
            }
        }
        var seq=List.<Map<String,Object>>of(Map.of("name","seq","value","1"));
        return new Pair(new ReleaseSnapshotContract.Snapshot(UUID.randomUUID().toString(),old,keys,seq,"same-media",locators,Map.of(),Map.of(),Map.of()),new ReleaseSnapshotContract.Snapshot(UUID.randomUUID().toString(),current,keys,seq,"same-media",locators,Map.of(),Map.of(),Map.of()));
    }
}
