package com.example.marketplace.dbtest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.example.marketplace.dbtest.ReleaseSnapshotContractTest.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseConfirmationStartupTest {
    private static final Instant START=Instant.parse("2026-11-16T00:00:00Z");
    private static final String ORDER="11111111-1111-4111-8111-111111111111",ITEM="22222222-2222-4222-8222-222222222222",JOB="33333333-3333-4333-8333-333333333333";
    @Test void actualFixedStartupScanWithUndeliveredUnitsPreservesCommerce() {assertEquals(true,check(fixture(false)).get("passed"));}
    @Test void existingFixedRunRemainsExactAcrossAnotherWorkerStartup() {assertEquals(true,check(fixture(true)).get("passed"));}
    @Test void wrongHighWaterUuidIsRejected() {var p=fixture(false);change(p,"order_confirmation_scan",0,"upper_id",UUID.randomUUID().toString());reject(p,"upper");}
    @Test void unrelatedFixedRunIsRejected() {var p=fixture(false);change(p,"order_confirmation_scan",0,"run_id","invented-run");reject(p,"fixed confirmation run");}
    @Test void wrongCauseAndSkippedPageCursorAreRejected() {
        var cause=fixture(false);change(cause,"job",cause.after().tables().get("job").size()-1,"cause_key","other-run");reject(cause,"route/cursor/cause");
        var cursor=fixture(false);change(cursor,"job",cursor.after().tables().get("job").size()-1,"payload",ReleaseConfirmationStartup.RUN+"|"+ORDER);reject(cursor,"route/cursor/cause");
    }
    @Test void eligibleDeliveredUnitCannotUseTheNoFinancialEffectException() {
        var p=fixture(false);var delivered=node("item_id",ITEM,"ordinal",0,"event_id",UUID.randomUUID());p.before().tables().get("order_initial_delivery_unit").add(delivered);p.after().tables().get("order_initial_delivery_unit").add(delivered);
        reject(p,"Eligible confirmation unit");
    }
    @Test void wrongOutboxOrMissingActualClaimCannotProveTheScan() {
        var event=fixture(false);change(event,"outbox_event",event.after().tables().get("outbox_event").size()-1,"id",UUID.randomUUID().toString());reject(event,"confirmation outbox");
        var claim=fixture(false);claim.after().tables().get("job_claim_fact").removeIf(row->row.path("job_id").asText().equals(JOB));reject(claim,"actual owner claim");
    }
    @Test void originalFixedScanHistoryCannotBeRewritten() {var p=fixture(true);change(p,"order_confirmation_scan",0,"upper_id",UUID.randomUUID().toString());reject(p,"immutable row changed");}
    @Test void oneWonMutationStillFailsWithAValidStartupGraph() {var p=fixture(false);change(p,"purchase_order",0,"amount",10001);reject(p,"immutable row changed");}
    private static Map<String,Object> check(ReleaseSnapshotContractTest.Pair p) {return ReleaseSnapshotContract.validate(p.before(),p.after(),START,START.plusSeconds(5),false);}
    private static void reject(ReleaseSnapshotContractTest.Pair p,String reason) {var failure=assertThrows(AssertionError.class,()->check(p));assertTrue(failure.getMessage().contains(reason),failure.getMessage());}
    private static void change(ReleaseSnapshotContractTest.Pair p,String table,int ordinal,String field,Object value) {
        var row=new java.util.LinkedHashMap<String,Object>();p.after().tables().get(table).get(ordinal).properties().forEach(entry->row.put(entry.getKey(),ReleaseSnapshotContract.JSON.readValue(entry.getValue().toString(),Object.class)));row.put(field,value);p.after().tables().get(table).set(ordinal,node(row));
    }
    private static ReleaseSnapshotContractTest.Pair fixture(boolean existing) {
        var p=ReleaseSnapshotContractTest.fixture();var order=node("id",ORDER,"amount",10000,"state","PAID");p.before().tables().get("purchase_order").set(0,order);p.after().tables().get("purchase_order").set(0,order);
        p.before().keys().put("order_confirmation_scan",List.of("run_id"));p.before().keys().put("order_aftercare_policy",List.of("order_id"));p.before().keys().put("order_unit_lifecycle",List.of("item_id","ordinal"));
        var policy=node("order_id",ORDER,"status","ACTIVE");var unit=node("item_id",ITEM,"ordinal",0,"order_id",ORDER,"state","OPEN");
        for(var sample:List.of(p.before(),p.after())) {sample.tables().get("order_aftercare_policy").add(policy);sample.tables().get("order_unit_lifecycle").add(unit);}
        var scan=node("run_id",ReleaseConfirmationStartup.RUN,"upper_id",ORDER,"started_at",START);String key="confirmation-scan:"+ReleaseConfirmationStartup.RUN+":START";
        var job=node("id",JOB,"type","ORDER_CONFIRM_SCAN","dedupe_key",key,"cause_key",ReleaseConfirmationStartup.RUN,"payload",ReleaseConfirmationStartup.RUN+"|START","scheduled_at",START,"status","SUCCEEDED","attempts",1);
        var event=node("id",UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"job_id",JOB);
        var claim=node("job_id",JOB,"attempt",1,"owner_token",UUID.randomUUID(),"claimed_at",START.plusMillis(1),"required_at",START,"scheduled_at_snapshot",START,"initial_lease_until",START.plusMillis(1).plusSeconds(30),"previous_attempt",0,"previous_owner",null,"eligibility_kind","READY");
        for(var sample:existing?List.of(p.before(),p.after()):List.of(p.after())) {sample.tables().get("order_confirmation_scan").add(scan);sample.tables().get("job").add(job);sample.tables().get("outbox_event").add(event);sample.tables().get("job_claim_fact").add(claim);}
        return p;
    }
}
