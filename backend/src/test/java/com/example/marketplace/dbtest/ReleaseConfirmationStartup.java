package com.example.marketplace.dbtest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.by;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.require;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.text;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.time;

/** Exact fixed ApplicationRunner scan for the no-new-confirmation-effect rehearsal fixture. */
final class ReleaseConfirmationStartup {
    static final String RUN="P09-B-H03-20261030-OPTION2";
    static final Set<String> TABLES=Set.of("order_confirmation_scan");
    static Map<String,Object> validate(ReleaseSnapshotContract.Snapshot before,ReleaseSnapshotContract.Snapshot after,
                         Map<String,List<JsonNode>> additions,Instant start,Instant end,Set<String> jobs,Set<String> scans) {
        var added=additions.get("order_confirmation_scan");
        if(added.isEmpty()) {return Map.of("newFixedRuns",0);}
        require(added.size()==1&&before.tables().get("order_confirmation_scan").stream().noneMatch(row->text(row,"run_id").equals(RUN)),"Fixed confirmation scan must be created once");
        var scan=added.getFirst();require(text(scan,"run_id").equals(RUN),"Wrong fixed confirmation run");
        var orders=before.tables().get("purchase_order").stream().sorted(java.util.Comparator.comparing(row->text(row,"id"))).toList();
        require(!orders.isEmpty()&&orders.size()<50,"Confirmation startup requires explicit single bounded page");
        require(text(scan,"upper_id").equals(text(orders.getLast(),"id")),"Confirmation scan upper must equal actual original UUID maximum");
        Instant at=time(scan,"started_at");require(!at.isBefore(start.minusSeconds(1))&&!at.isAfter(end.plusSeconds(1)),"Confirmation scan not caused by this actual startup");
        var candidates=additions.get("job").stream().filter(row->text(row,"type").equals("ORDER_CONFIRM_SCAN")).toList();
        require(candidates.size()==1,"Single bounded confirmation page must have exactly one job");var job=candidates.getFirst();String id=text(job,"id"),key="confirmation-scan:"+RUN+":START";
        require(text(job,"dedupe_key").equals(key)&&text(job,"cause_key").equals(RUN)&&text(job,"payload").equals(RUN+"|START")&&time(job,"scheduled_at").equals(at),"Wrong confirmation page route/cursor/cause");
        var receipts=additions.get("outbox_event").stream().filter(row->text(row,"job_id").equals(id)).toList();
        require(receipts.size()==1&&text(receipts.getFirst(),"id").equals(java.util.UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()),"Wrong deterministic confirmation outbox");
        var claims=additions.get("job_claim_fact").stream().filter(row->text(row,"job_id").equals(id)).toList();
        require(claims.size()==1&&claims.getFirst().path("attempt").asInt()==1&&job.path("attempts").asInt()==1&&!text(claims.getFirst(),"owner_token").isBlank(),"Confirmation page lacks one exact actual owner claim");
        var eligibility=new ArrayList<Map<String,Object>>();
        for(var order:orders) {
            String orderId=text(order,"id");
            for(var unit:before.tables().get("order_unit_lifecycle")) {
                if(!text(unit,"order_id").equals(orderId)) {continue;}
                String reason=ineligible(before,order,unit);require(!reason.equals("ELIGIBLE"),"Eligible confirmation unit requires a complete separate financial producer proof");
                eligibility.add(Map.of("order",orderId,"item",text(unit,"item_id"),"ordinal",unit.path("ordinal").asInt(),"reason",reason));
            }
        }
        require(!eligibility.isEmpty(),"Confirmation page must bind actual original lifecycle units");
        // All unit/schedule/history/money tables remain under the outer full-row exact validator.
        jobs.add(id);scans.add(id);
        return Map.of("newFixedRuns",1,"runId",RUN,"upperId",text(scan,"upper_id"),"pageOrderIds",orders.stream().map(row->text(row,"id")).toList(),"unitEligibility",eligibility,"actualJob",id,"newFinancialEffects",0);
    }
    private static String ineligible(ReleaseSnapshotContract.Snapshot source,JsonNode order,JsonNode unit) {
        if(!text(unit,"state").equals("OPEN")) {return "UNIT_NOT_OPEN";}
        var policy=by(source,"order_aftercare_policy","order_id",text(order,"id"));
        if(!text(order,"state").equals("PAID")||!text(policy,"status").equals("ACTIVE")) {return "ORDER_OR_POLICY_INACTIVE";}
        String item=text(unit,"item_id");int ordinal=unit.path("ordinal").asInt();
        for(var current:source.tables().get("order_physical_current")) {
            if(!text(current,"item_id").equals(item)||current.path("ordinal").asInt()!=ordinal) {continue;}
            var physical=by(source,"order_physical_source","id",text(current,"physical_id"));
            if(physical.path("round").asInt()>0) {return source.tables().get("order_physical_delivery").stream().anyMatch(row->text(row,"physical_id").equals(text(physical,"id")))?"ELIGIBLE":"NO_CURRENT_PHYSICAL_DELIVERY";}
        }
        return source.tables().get("order_initial_delivery_unit").stream().anyMatch(row->text(row,"item_id").equals(item)&&row.path("ordinal").asInt()==ordinal)?"ELIGIBLE":"NO_INITIAL_DELIVERY";
    }
}
