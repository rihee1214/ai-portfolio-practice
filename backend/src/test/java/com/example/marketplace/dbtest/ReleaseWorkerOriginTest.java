package com.example.marketplace.dbtest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseWorkerOriginTest {
    private static final String SHA="a".repeat(64),ID="b".repeat(64),RUN="run-owned",PROJECT="project-owned";
    @Test void checkpointCannotManufactureAnIdentityWithoutVerifiedActivation() {
        var cache=new ReleaseWorkerOrigin();assertThrows(AssertionError.class,cache::snapshot);
        assertThrows(AssertionError.class,()->host(Map.of()));
    }
    @Test void missingEitherHostIncarnationFieldIsRejected() {
        var missingPid=hostWorker();missingPid.remove("pid");assertThrows(AssertionError.class,()->host(missingPid));
        var missingCreation=hostWorker();missingCreation.remove("created");assertThrows(AssertionError.class,()->host(missingCreation));
    }
    @Test void checkpointRetainsTheVerifiedActiveBindingInsteadOfAColonPlaceholder() {
        var cache=new ReleaseWorkerOrigin();var actual=host(hostWorker());cache.activated(actual);
        assertEquals(actual,cache.snapshot());assertEquals(actual,cache.snapshot());assertTrue(!cache.snapshot().identity().equals(":"));
    }
    @Test void beginningAnotherActivationInvalidatesOldCacheEvenWhenItFails() {
        var cache=new ReleaseWorkerOrigin();cache.activated(host(hostWorker()));cache.beginActivation();
        assertThrows(AssertionError.class,cache::snapshot);
    }
    @Test void maintenanceDataObservationCannotCreateALiveWorkerOrigin() {
        var cache=new ReleaseWorkerOrigin();var prior=host(hostWorker());cache.activated(prior);cache.beginActivation();
        var data=cache.observeData();assertEquals("data-only-no-active-origin",data.kind());assertNull(data.worker());
        assertThrows(AssertionError.class,cache::snapshot);assertThrows(AssertionError.class,()->ReleaseWorkerOrigin.distinct(data.worker(),prior,RUN,PROJECT));
    }
    @Test void sameHostPidWithNewCreationIsADifferentActualIncarnation() {
        var old=host(hostWorker());var next=hostWorker();next.put("created","2026-11-16T00:00:02Z");
        ReleaseWorkerOrigin.distinct(old,host(next),RUN,PROJECT);
    }
    @Test void sameContainerIdWithNewStartedAtIsADifferentActualIncarnation() {
        var old=compose(observation("2026-11-16T00:00:00Z"));var next=compose(observation("2026-11-16T00:00:02Z"));
        ReleaseWorkerOrigin.distinct(old,next,RUN,PROJECT);
    }
    @Test void identicalIncarnationCannotPassBecauseEvidenceOrLabelsChanged() {
        var actual=compose(observation("2026-11-16T00:00:00Z"));
        var relabeled=new ReleaseWorkerOrigin.Incarnation(actual.identity(),"P","unrelated-run","unrelated-project","R1","another-command",SHA,SHA);
        assertThrows(AssertionError.class,()->ReleaseWorkerOrigin.distinct(actual,relabeled,RUN,PROJECT));
    }
    @Test void originalWorkerFromAnotherSourceScopeIsRejected() {
        var original=compose(observation("2026-11-16T00:00:00Z"));var current=compose(observation("2026-11-16T00:00:02Z"));
        assertThrows(AssertionError.class,()->ReleaseWorkerOrigin.distinct(original,current,"other-run",PROJECT));
        assertThrows(AssertionError.class,()->ReleaseWorkerOrigin.distinct(original,current,RUN,"other-project"));
    }
    @Test void actualComposeObservationMustMatchRunProjectRoleReleaseAndContainer() {
        for(String key:List.of("runId","project","service","releaseId","id")) {
            var wrong=observation("2026-11-16T00:00:00Z");wrong.put(key,"unrelated");assertThrows(AssertionError.class,()->compose(wrong));
        }
    }
    private static ReleaseWorkerOrigin.Incarnation host(Map<String,Object> worker) {
        return ReleaseWorkerOrigin.verify(node(Map.of("activeReleaseId","R0","hostRoles",Map.of("worker",worker))),node(Map.of()),"L",RUN,PROJECT,"R0","activation",SHA,SHA);
    }
    private static Map<String,Object> hostWorker() {return new HashMap<>(Map.of("pid",123,"created","2026-11-16T00:00:00Z","running",true,"role","worker","runId",RUN,"releaseId","R0","jarSha256",SHA));}
    private static ReleaseWorkerOrigin.Incarnation compose(Map<String,Object> observation) {
        return ReleaseWorkerOrigin.verify(node(Map.of("activeReleaseId","R1","artifactIdentities",List.of(Map.of("id",ID,"image","sha256:"+SHA,"service","worker")))),node(observation),"P",RUN,PROJECT,"R1","activation",SHA,SHA);
    }
    private static Map<String,Object> observation(String started) {
        return new HashMap<>(Map.of("id",ID,"image","sha256:"+SHA,"started",started,"running",true,"pid",1,"service","worker","project",PROJECT,"runId",RUN,"releaseId","R1"));
    }
    private static JsonNode node(Object value) {return ReleaseSnapshotContract.JSON.readTree(ReleaseSnapshotContract.JSON.writeValueAsString(value));}
}
