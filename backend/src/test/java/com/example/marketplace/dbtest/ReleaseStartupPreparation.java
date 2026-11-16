package com.example.marketplace.dbtest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A quiet queue is not ready until both actual worker startup listeners have committed. */
final class ReleaseStartupPreparation {
    static final Map<String,String> TYPES=Map.of("return","RETURN_RECOVERY_SCAN.v1","exchange_refund","EXCHANGE_REFUND_RECOVERY_SCAN.v1");
    private static final Set<String> STAGES=Set.of("ITEM_EFFECT","SHIPPING_RESULT","SELLER_REEVALUATE");
    record Observation(boolean complete,Map<String,Object> families) { }

    static Observation observe(Map<String,Set<String>> prior,Map<String,List<Map<String,Object>>> observations) {
        assertTrue(prior.keySet().equals(TYPES.keySet())&&observations.keySet().equals(TYPES.keySet()),"Both registered startup families must be observed");
        boolean complete=true;var evidence=new TreeMap<String,Object>();
        for(String family:TYPES.keySet()) {
            var runs=new HashMap<String,List<Map<String,Object>>>();
            for(var row:observations.get(family)) {
                String id=row.get("run_id").toString();
                if(!prior.get(family).contains(id)) {runs.computeIfAbsent(id,key->new ArrayList<>()).add(row);}
            }
            assertTrue(runs.size()<=1,"Unexpected duplicate startup runs: "+family);
            if(runs.isEmpty()) {complete=false;evidence.put(family,Map.of("newRunObserved",false));continue;}
            var entry=runs.entrySet().iterator().next();var rows=entry.getValue();var stages=new java.util.HashSet<String>();
            for(var row:rows) {
                assertTrue(STAGES.contains(row.get("stage"))&&stages.add(row.get("stage").toString()),"Unregistered or duplicate startup stage: "+family);
                assertTrue(TYPES.get(family).equals(row.get("type"))&&entry.getKey().equals(row.get("cause_key")),"Startup job must belong to the actual new run: "+family);
            }
            boolean done=stages.equals(STAGES)&&rows.stream().allMatch(row->Boolean.TRUE.equals(row.get("completed"))&&"SUCCEEDED".equals(row.get("status")));
            complete&=done;
            evidence.put(family,Map.of("newRunObserved",true,"runId",entry.getKey(),"complete",done,"stages",rows));
        }
        return new Observation(complete,evidence);
    }
}
