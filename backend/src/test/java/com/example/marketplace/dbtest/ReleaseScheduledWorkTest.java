package com.example.marketplace.dbtest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static com.example.marketplace.dbtest.ReleaseSnapshotContractTest.fixture;
import static com.example.marketplace.dbtest.ReleaseSnapshotContractTest.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Development counterexamples use input copies, never databases or returned metrics as an oracle. */
class ReleaseScheduledWorkTest {
    private static final Instant START=Instant.parse("2026-11-16T00:00:00Z");
    private static final String DAY="2026-11-16";
    private static final String STORE="22222222-2222-4222-8222-222222222222";
    private static final String SESSION="33333333-3333-4333-8333-333333333333";

    @Test void acceptsZeroScheduledDeltaAndUnchangedSessionCredentials() {
        var pair=base();sessions(pair);assertEquals(true,check(pair).get("passed"));
    }
    @Test void acceptsCausalCronGenerationWithLiteralMetricsAndExactNullToOneAllocation() {
        var pair=cron();assertEquals(true,check(pair).get("passed"));
        assertEquals(4,pair.after().tables().get("dashboard_metric").size());
    }
    @Test void rejectsOneWonMutationOfIndependentlyExpectedPaidAmount() {
        var pair=moneyCron();assertEquals(true,check(pair).get("passed"));
        var metrics=pair.after().tables().get("dashboard_metric");
        int index=java.util.stream.IntStream.range(0,metrics.size()).filter(i->metrics.get(i).path("scope").asText().equals("GLOBAL")&&metrics.get(i).path("metric").asText().equals("PAID_AMOUNT")).findFirst().orElseThrow();
        replace(pair,"dashboard_metric",index,"value",13001);reject(pair,"Independent dashboard metric key/value reconciliation failed");
    }
    @Test void rejectsRewriteOfAnExistingImmutableMetric() {
        var pair=priorGeneration();assertEquals(true,check(pair).get("passed"));
        replace(pair,"dashboard_metric",0,"value",2);reject(pair,"Original row deleted: dashboard_metric");
    }
    @Test void rejectsDeletionOfAnExistingImmutableMetric() {
        var pair=priorGeneration();assertEquals(true,check(pair).get("passed"));
        pair.after().tables().get("dashboard_metric").removeFirst();reject(pair,"Original row deleted: dashboard_metric");
    }
    @Test void rejectsMixedDashboardHeadGeneration() {
        var pair=priorGeneration();assertEquals(true,check(pair).get("passed"));
        replace(pair,"dashboard_head",0,"snapshot_id",pair.before().tables().get("dashboard_head").getFirst().path("snapshot_id").asText());
        reject(pair,"Dashboard head snapshot/run mismatch");
    }
    @Test void rejectsDashboardSnapshotWithoutItsExactClaimOwner() {
        var pair=cron();replace(pair,"dashboard_snapshot",0,"token",UUID.randomUUID());
        reject(pair,"Dashboard generation has no exact owner/attempt claim");
    }
    @Test void rejectsUnexplainedDashboardSequenceAllocationGap() {
        var pair=cron();replace(pair,"dashboard_run",0,"id",2);replace(pair,"dashboard_snapshot",0,"run_id",2);replace(pair,"dashboard_head",0,"run_id",2);
        pair=sequence(pair,false,1,true,2);reject(pair,"Unexplained dashboard sequence gap/allocation");
    }
    @Test void rejectsTwoCausalRunsForTheSameCronSlot() {
        var pair=cron();generation(pair.after(),2,START.plusMillis(200));pair=sequence(pair,false,1,true,2);
        reject(pair,"Unregistered or duplicate scheduled dashboard slot");
    }
    @Test void acceptsOneExistingNotificationJobCompletionAndOneInboxEffect() {
        var pair=notification();assertEquals(true,check(pair).get("passed"));
    }
    @Test void rejectsInboxForAnExternalUnregisteredEvent() {
        var pair=base();pair.after().keys().put("notification_inbox",List.of("event_id"));
        pair.after().tables().get("notification_inbox").add(node("event_id",UUID.randomUUID(),"read_at",null));
        reject(pair,"Missing graph row: notification_event");
    }
    @Test void rejectsDuplicateNotificationBusinessEffect() {
        var pair=notification();assertEquals(true,check(pair).get("passed"));
        var inbox=pair.after().tables().get("notification_inbox");inbox.add(inbox.getFirst());reject(pair,"Duplicate primary key: notification_inbox");
    }
    @Test void rejectsSessionPrincipalChangeWithoutAnAuthenticationCommand() {
        var pair=base();sessions(pair);replace(pair,"spring_session",0,"principal_name","different-principal");
        reject(pair,"Original immutable row changed: spring_session");
    }
    @Test void rejectsSessionCredentialAttributeRewrite() {
        var pair=base();sessions(pair);replace(pair,"spring_session_attributes",0,"attribute_bytes","different-credential-bytes");
        reject(pair,"Original immutable row changed: spring_session_attributes");
    }
    @Test void rejectsUnexplainedSessionTtlChange() {
        var pair=base();sessions(pair);replace(pair,"spring_session",0,"expiry_time",1803000);
        reject(pair,"Original immutable row changed: spring_session");
    }

    private static ReleaseSnapshotContractTest.Pair base() {
        var pair=fixture();
        for(var snapshot:List.of(pair.before(),pair.after())) {
            var row=map(snapshot.tables().get("purchase_order").getFirst());row.put("created_at",START.minusSeconds(600).toString());snapshot.tables().get("purchase_order").set(0,node(row));
        }
        return pair;
    }
    private static ReleaseSnapshotContractTest.Pair cron() {
        var pair=base();generation(pair.after(),1,START.plusMillis(100));return sequence(pair,false,1,true,1);
    }
    private static ReleaseSnapshotContractTest.Pair priorGeneration() {
        var pair=base();String job=UUID.randomUUID().toString(),event=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString(),snapshot=UUID.randomUUID().toString();
        generation(pair.before(),1,START.minusSeconds(300).plusMillis(100),job,event,owner,snapshot);
        generation(pair.after(),1,START.minusSeconds(300).plusMillis(100),job,event,owner,snapshot);
        generation(pair.after(),2,START.plusMillis(100));return sequence(pair,true,1,true,2);
    }
    private static ReleaseSnapshotContractTest.Pair moneyCron() {
        var pair=cron();String seller=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
        for(var snapshot:List.of(pair.before(),pair.after())) {
            var order=map(snapshot.tables().get("purchase_order").getFirst());order.put("amount",13000);snapshot.tables().get("purchase_order").set(0,node(order));String orderId=order.get("id").toString();
            snapshot.tables().get("seller_order").add(node("id",seller,"order_id",orderId,"store_id",STORE,"total",13000,"subtotal",10000,"product_discount",0,"coupon_discount",0,"shipping_base",3000,"shipping_discount",0));
            snapshot.tables().get("payment_attempt").add(node("id",attempt,"order_id",orderId,"amount",13000,"state","SUCCEEDED"));
            snapshot.tables().get("payment_event").add(node("id",event,"attempt_id",attempt,"event_key",attempt+":succeeded","action","SUCCEEDED","occurred_at",START.minusSeconds(550)));
        }
        var metrics=pair.after().tables().get("dashboard_metric");metrics.clear();String snapshot=pair.after().tables().get("dashboard_snapshot").getFirst().path("id").asText();
        for(String scope:List.of("GLOBAL","STORE")) {
            String owner=scope.equals("GLOBAL")?null:STORE;
            metric(pair.after(),snapshot,scope,owner,DAY,"ORDERS_CREATED",1);
            for(var amount:Map.of("PAID_ORDERS",1,"PAID_AMOUNT",13000,"NET_TRADED",13000,"PRODUCT_GROSS",10000,"PRODUCT_DISCOUNT",0,"COUPON_DISCOUNT",0,"SHIPPING_CHARGED",3000).entrySet()) {
                metric(pair.after(),snapshot,scope,owner,DAY,amount.getKey(),amount.getValue());
            }
        }
        for(String metric:List.of("CASES_WAITING","BATCH_MISMATCH","REFRESH_FAILURES")) {metric(pair.after(),snapshot,"GLOBAL",null,null,metric,0);}
        return pair;
    }
    private static ReleaseSnapshotContractTest.Pair notification() {
        var pair=base();String event=UUID.randomUUID().toString(),job=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();
        for(var snapshot:List.of(pair.before(),pair.after())) {
            snapshot.keys().put("notification_inbox",List.of("event_id"));
            snapshot.tables().get("notification_event").add(node("id",event,"kind","PAYMENT_SELLER","cause_id",UUID.nameUUIDFromBytes(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"recipient_id",STORE,"target_id",STORE,"created_at",START.minusSeconds(1)));
            snapshot.tables().get("job").add(node("id",job,"type","NOTIFICATION_DELIVER.v1","dedupe_key","notify:"+event,"cause_key",event,"payload",event,"scheduled_at",START,"next_run_at",START,"owner_token",null,"lease_until",null,"status",snapshot==pair.before()?"READY":"SUCCEEDED","attempts",snapshot==pair.before()?0:1));
            snapshot.tables().get("outbox_event").add(node("id",event,"job_id",job));
        }
        claim(pair.after(),job,owner,START,START.plusMillis(100));pair.after().tables().get("notification_inbox").add(node("event_id",event,"read_at",null));return pair;
    }
    private static void sessions(ReleaseSnapshotContractTest.Pair pair) {
        for(var snapshot:List.of(pair.before(),pair.after())) {
            snapshot.keys().put("spring_session",List.of("primary_id"));snapshot.keys().put("spring_session_attributes",List.of("session_primary_id","attribute_name"));
            snapshot.tables().get("spring_session").add(node("primary_id",SESSION,"session_id",SESSION,"creation_time",1000,"last_access_time",2000,"max_inactive_interval",1800,"expiry_time",1802000,"principal_name","original-principal"));
            snapshot.tables().get("spring_session_attributes").add(node("session_primary_id",SESSION,"attribute_name","SPRING_SECURITY_CONTEXT","attribute_bytes","synthetic-original-credential"));
        }
    }
    private static void generation(ReleaseSnapshotContract.Snapshot target,long id,Instant scheduled) {
        generation(target,id,scheduled,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
    }
    private static void generation(ReleaseSnapshotContract.Snapshot target,long id,Instant scheduled,String job,String event,String owner,String snapshot) {
        target.tables().get("dashboard_run").add(node("id",id,"job_id",job,"scheduled_at",scheduled,"state","PUBLISHED","completed_at",scheduled.plusMillis(300)));
        target.tables().get("job").add(node("id",job,"type","DASHBOARD_REFRESH.v1","dedupe_key","dashboard:"+event,"cause_key","dashboard:"+event,"payload",event,"scheduled_at",scheduled,"status","SUCCEEDED","attempts",1));
        target.tables().get("outbox_event").add(node("id",event,"job_id",job));claim(target,job,owner,scheduled,scheduled.plusMillis(100));
        target.tables().get("dashboard_snapshot").add(node("id",snapshot,"run_id",id,"job_id",job,"attempt",1,"token",owner,"definition_version",1,"source_as_of",scheduled.plusMillis(200),"source_snapshot","10:20:","created_xid","21"));
        target.tables().get("dashboard_head").set(0,node("singleton",true,"run_id",id,"snapshot_id",snapshot,"last_scheduled",Instant.ofEpochSecond(Math.floorDiv(scheduled.getEpochSecond(),300)*300)));
        metric(target,snapshot,"GLOBAL",null,DAY,"ORDERS_CREATED",1);
        for(String metric:List.of("CASES_WAITING","BATCH_MISMATCH","REFRESH_FAILURES")) {metric(target,snapshot,"GLOBAL",null,null,metric,0);}
    }
    private static void metric(ReleaseSnapshotContract.Snapshot target,String snapshot,String scope,String owner,String day,String name,int value) {
        target.tables().get("dashboard_metric").add(node("snapshot_id",snapshot,"scope",scope,"scope_id",owner,"day",day,"metric",name,"value",value));
    }
    private static void claim(ReleaseSnapshotContract.Snapshot target,String job,String owner,Instant scheduled,Instant claimed) {
        target.tables().get("job_claim_fact").add(node("job_id",job,"attempt",1,"owner_token",owner,"claimed_at",claimed,"required_at",scheduled,"initial_lease_until",claimed.plusSeconds(30),"scheduled_at_snapshot",scheduled,"previous_attempt",0,"previous_owner",null,"eligibility_kind","READY"));
    }
    private static ReleaseSnapshotContractTest.Pair sequence(ReleaseSnapshotContractTest.Pair pair,boolean beforeCalled,long beforeValue,boolean afterCalled,long afterValue) {
        return new ReleaseSnapshotContractTest.Pair(sequence(pair.before(),beforeCalled,beforeValue),sequence(pair.after(),afterCalled,afterValue));
    }
    private static ReleaseSnapshotContract.Snapshot sequence(ReleaseSnapshotContract.Snapshot source,boolean called,long value) {
        var sequences=List.of(fields("name","dashboard_run_id_seq","value",called?Long.toString(value):null),Map.<String,Object>of("name","seq","value","1"));
        var diagnostic=fields("name","dashboard_run_id_seq","start","1","increment","1","cache","1","cycle",false,"lastValue",Long.toString(value),"isCalled",called,"canonicalValue",called?Long.toString(value):null,"canSelect",true,"canUsage",true,"observationRole","same-unit-observer");
        return new ReleaseSnapshotContract.Snapshot(source.id(),source.tables(),source.keys(),sequences,source.mediaDigest(),source.locators(),source.operatorDatabase(),source.operatorMedia(),Map.of("sequenceDiagnostics",List.of(diagnostic)));
    }
    private static Map<String,Object> check(ReleaseSnapshotContractTest.Pair pair) {return ReleaseSnapshotContract.validate(pair.before(),pair.after(),START,START.plusSeconds(5),false);}
    private static void reject(ReleaseSnapshotContractTest.Pair pair,String message) {
        var failure=assertThrows(AssertionError.class,()->check(pair));assertTrue(failure.getMessage().contains(message),()->"Expected targeted rejection: "+message+"; actual: "+failure.getMessage());
    }
    private static void replace(ReleaseSnapshotContractTest.Pair pair,String table,int index,String field,Object value) {
        var data=map(pair.after().tables().get(table).get(index));data.put(field,value);pair.after().tables().get(table).set(index,node(data));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(JsonNode row) {return new LinkedHashMap<>(ReleaseSnapshotContract.JSON.readValue(row.toString(),Map.class));}
    private static Map<String,Object> fields(Object... values) {
        var result=new LinkedHashMap<String,Object>();for(int index=0;index<values.length;index+=2) {result.put(values[index].toString(),values[index+1]);}return result;
    }
}
