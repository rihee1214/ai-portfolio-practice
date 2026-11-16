package com.example.marketplace.dbtest;

import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseMixedCheckpointTest {
    @Test void pairsOriginalDatabaseDumpWithTheEntireForeignMediaInventory() {
        var local=checkpoint("local");var other=checkpoint("other");var mixed=ReleaseMixedCheckpoint.create(local,other);
        assertEquals(local.get("database"),mixed.get("database"));assertEquals(local.get("dump"),mixed.get("dump"));assertEquals(other.get("media"),mixed.get("media"));
        assertEquals(ReleaseMixedCheckpoint.digest(mixed),mixed.get("snapshotSha256"));assertNotEquals(local.get("snapshotSha256"),mixed.get("snapshotSha256"));
    }
    @Test void constructionDoesNotModifyEitherRetainedCheckpoint() {
        var local=checkpoint("local");var other=checkpoint("other");String a=ReleaseSnapshotContract.hash(local),b=ReleaseSnapshotContract.hash(other);
        ReleaseMixedCheckpoint.create(local,other);assertEquals(a,ReleaseSnapshotContract.hash(local));assertEquals(b,ReleaseSnapshotContract.hash(other));
    }
    @Test void integrityRebindingChangesOnlyMediaAndItsCompositeDigest() {
        var local=checkpoint("local");var mixed=ReleaseMixedCheckpoint.create(local,checkpoint("other"));
        for(var entry:local.entrySet()) {if(!List.of("media","snapshotSha256").contains(entry.getKey())) {assertEquals(entry.getValue(),mixed.get(entry.getKey()));}}
    }
    @Test void identicalNamespacesCannotPretendToExerciseTheMismatchGuard() {
        assertThrows(AssertionError.class,()->ReleaseMixedCheckpoint.create(checkpoint("same"),checkpoint("same")));
    }
    private static Map<String,Object> checkpoint(String identity) {
        var result=new java.util.LinkedHashMap<String,Object>(Map.of("database",Map.of("table",Map.of("count",1,"rowHashes",List.of(identity))),"dump",Map.of("path",identity+".dump","sha256",identity),"media",Map.of("namespace",identity,"path",identity+"/media","files",List.of(Map.of("path",".database","size",36,"sha256",identity)),"referenced",List.of(),"unreferenced",List.of()),"complete",true,"version",1,"quiescenceSha256","original"));
        result.put("snapshotSha256",ReleaseMixedCheckpoint.digest(result));return result;
    }
}
