package com.example.marketplace.dbtest;

import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retains only a verified successful ACTIVE worker, never a checkpoint manifest placeholder. */
final class ReleaseWorkerOrigin {
    record Incarnation(String identity,String mode,String runId,String project,String releaseId,String evidence,String resultSha256,String observationSha256) { }
    record DataObservation(String kind,Incarnation worker) { }
    private Incarnation active;
    void beginActivation() {active=null;}
    void activated(Incarnation verified) {assertNotNull(verified);active=verified;}
    Incarnation snapshot() {assertNotNull(active,"Snapshot requires a verified successful ACTIVE worker incarnation");return active;}
    DataObservation observeData() {return active==null?new DataObservation("data-only-no-active-origin",null):new DataObservation("verified-successful-activation",active);}

    static Incarnation verify(JsonNode state,JsonNode observed,String mode,String runId,String project,String release,String evidence,String resultSha,String observationSha) {
        assertEquals(release,state.path("activeReleaseId").asText());
        assertTrue(resultSha.matches("[0-9a-f]{64}")&&observationSha.matches("[0-9a-f]{64}"),"Actual worker observation must have immutable evidence hashes");
        String identity;
        if(mode.equals("L")) {
            var worker=state.path("hostRoles").path("worker");
            assertTrue(worker.path("pid").asLong()>0&&!worker.path("created").asText().isBlank(),"Host worker requires actual PID and creation identity");
            assertTrue(worker.path("running").asBoolean(),"Host worker must be running");
            assertEquals("worker",worker.path("role").asText());assertEquals(runId,worker.path("runId").asText());assertEquals(release,worker.path("releaseId").asText());
            assertTrue(worker.path("jarSha256").asText().matches("[0-9a-f]{64}"),"Host worker must bind actual JAR content");
            identity="host:"+worker.path("pid").asText()+"@"+worker.path("created").asText();
        } else {
            assertEquals("P",mode);var workers=new java.util.ArrayList<JsonNode>();
            for(var artifact:state.path("artifactIdentities")) {if(artifact.path("service").asText().equals("worker")) {workers.add(artifact);}}
            assertEquals(1,workers.size(),"Exactly one actual worker container is required");var worker=workers.getFirst();
            String id=worker.path("id").asText(),started=observed.path("started").asText();
            assertTrue(id.matches("[0-9a-f]{64}")&&!started.isBlank(),"Full container ID and actual StartedAt are required");
            java.time.Instant.parse(started);
            assertEquals(id,observed.path("id").asText());assertEquals(worker.path("image").asText(),observed.path("image").asText());
            assertTrue(observed.path("running").asBoolean()&&observed.path("pid").asLong()>0,"Actual owned worker must be running");
            assertEquals("worker",observed.path("service").asText());assertEquals(project,observed.path("project").asText());
            assertEquals(runId,observed.path("runId").asText());assertEquals(release,observed.path("releaseId").asText());
            identity="container:"+id+"@"+started;
        }
        return new Incarnation(identity,mode,runId,project,release,evidence,resultSha,observationSha);
    }

    static void distinct(Incarnation previous,Incarnation current,String previousRun,String previousProject) {
        assertNotNull(previous,"Original snapshot must retain its actual worker incarnation");assertNotNull(current);
        assertEquals(previousRun,previous.runId());assertEquals(previousProject,previous.project());assertEquals(previous.mode(),current.mode());
        assertTrue(!previous.identity().equals(current.identity()),"A distinct owned worker incarnation must cause the new startup runs");
    }
}
