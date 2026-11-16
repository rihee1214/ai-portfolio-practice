package com.example.marketplace.dbtest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseStartupPreparationTest {
    @Test void emptyQueueBeforeApplicationReadyCannotPassPreparation() {
        assertFalse(ReleaseStartupPreparation.observe(prior(),Map.of("return",List.of(),"exchange_refund",List.of())).complete());
    }
    @Test void priorCompletedRunsCannotStandInForTheNewWorker() {
        assertFalse(ReleaseStartupPreparation.observe(Map.of("return",Set.of("new-return"),"exchange_refund",Set.of("new-exchange_refund")),ready()).complete());
    }
    @Test void bothNewRunsWithEveryStageAndJobCompleteAreReady() {
        assertTrue(ReleaseStartupPreparation.observe(prior(),ready()).complete());
    }
    @Test void newRunWithAnUnfinishedStageOrClaimedJobCannotPass() {
        var stages=ready();stages.get("exchange_refund").getLast().put("completed",false);
        assertFalse(ReleaseStartupPreparation.observe(prior(),stages).complete());
        var jobs=ready();jobs.get("return").getFirst().put("status","RUNNING");
        assertFalse(ReleaseStartupPreparation.observe(prior(),jobs).complete());
    }
    @Test void duplicateNewStartupRunsAreRejected() {
        var rows=ready();var duplicate=new HashMap<>(rows.get("return").getFirst());duplicate.put("run_id","unrelated-run");rows.get("return").add(duplicate);
        assertThrows(AssertionError.class,()->ReleaseStartupPreparation.observe(prior(),rows));
    }
    @Test void anUnrelatedJobCannotProveStartupCompletion() {
        var rows=ready();rows.get("exchange_refund").getFirst().put("cause_key","other-run");
        assertThrows(AssertionError.class,()->ReleaseStartupPreparation.observe(prior(),rows));
    }
    private static Map<String,Set<String>> prior() {return Map.of("return",Set.of("old-return"),"exchange_refund",Set.of("old-exchange_refund"));}
    private static Map<String,List<Map<String,Object>>> ready() {
        var result=new HashMap<String,List<Map<String,Object>>>();
        for(var family:ReleaseStartupPreparation.TYPES.entrySet()) {
            var rows=new ArrayList<Map<String,Object>>();String id="new-"+family.getKey();
            for(String stage:List.of("ITEM_EFFECT","SHIPPING_RESULT","SELLER_REEVALUATE")) {
                rows.add(new HashMap<>(Map.of("run_id",id,"stage",stage,"completed",true,"status","SUCCEEDED","type",family.getValue(),"cause_key",id)));
            }
            result.put(family.getKey(),rows);
        }
        return result;
    }
}
