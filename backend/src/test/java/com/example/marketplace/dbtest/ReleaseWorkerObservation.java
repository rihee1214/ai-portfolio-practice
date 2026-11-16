package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Selected safe fields only; no embedded double quotes cross the Windows native argv boundary. */
final class ReleaseWorkerObservation {
    static final String FORMAT="{{.Id}}|{{.Image}}|{{.State.StartedAt}}|{{.State.Running}}|{{.State.Pid}}|{{index .Config.Labels `com.docker.compose.service`}}|{{index .Config.Labels `com.docker.compose.project`}}|{{index .Config.Labels `marketplace.p12d.run`}}|{{index .Config.Labels `marketplace.p12d.release`}}";
    static JsonNode observe(String container,Path evidence,Path workspace,ReleaseOperationLifecycle lifecycle) throws Exception {
        assertTrue(container.matches("[0-9a-f]{64}"),"Only an exact full container identity may be observed");
        Path fields=evidence.resolve("verified-worker-incarnation.fields.log"),errors=evidence.resolve("verified-worker-incarnation.stderr.log");
        var builder=new ProcessBuilder("docker","inspect","--format",FORMAT,container).directory(workspace.toFile()).redirectOutput(fields.toFile()).redirectError(errors.toFile());
        try(var owned=lifecycle.start(builder)) {
            var child=owned.process();assertTrue(child.waitFor(10,TimeUnit.SECONDS),"Worker observation must complete within preparation");
            assertEquals(0,child.exitValue(),"Inspect safe worker observation "+errors);
        }
        var observed=parse(Files.readString(fields));
        Files.writeString(evidence.resolve("verified-worker-incarnation.json"),ReleaseSnapshotContract.JSON.writeValueAsString(observed));
        return observed;
    }
    static JsonNode parse(String raw) {
        String line=raw.strip();assertTrue(!line.contains("\n")&&!line.contains("\r"),"Worker metadata must be one exact record");
        String[] fields=line.split("\\|",-1);assertEquals(9,fields.length,"Unexpected worker metadata field count");
        assertTrue(fields[0].matches("[0-9a-f]{64}")&&fields[1].matches("sha256:[0-9a-f]{64}"),"Full container/image identity required");
        java.time.Instant.parse(fields[2]);assertTrue(fields[3].equals("true")||fields[3].equals("false"),"Exact running state required");
        assertTrue(fields[4].matches("[0-9]+"),"Exact numeric process liveness field required");
        return ReleaseSnapshotContract.JSON.readTree(ReleaseSnapshotContract.JSON.writeValueAsString(Map.of("id",fields[0],"image",fields[1],"started",fields[2],"running",Boolean.parseBoolean(fields[3]),"pid",Long.parseLong(fields[4]),"service",fields[5],"project",fields[6],"runId",fields[7],"releaseId",fields[8])));
    }
    /** Explicit read-only diagnostic of the same Java producer against a preserved stopped owned worker. */
    public static void main(String[] args) throws Exception {
        assertEquals(2,args.length);Path result=Path.of(args[0]),evidence=Files.createDirectory(Path.of(args[1]));
        var saved=ReleaseSnapshotContract.JSON.readTree(Files.readString(result));assertEquals("passed",saved.path("status").asText());
        var workers=new java.util.ArrayList<JsonNode>();for(var row:saved.path("result").path("artifactIdentities")) {if(row.path("service").asText().equals("worker")) {workers.add(row);}}
        assertEquals(1,workers.size());var worker=workers.getFirst();var lifecycle=new ReleaseOperationLifecycle(evidence);
        try {
            var actual=observe(worker.path("id").asText(),evidence,Path.of("").toAbsolutePath(),lifecycle);
            assertEquals(worker.path("id").asText(),actual.path("id").asText());assertEquals(worker.path("image").asText(),actual.path("image").asText());
            assertEquals(saved.path("target").path("project").asText(),actual.path("project").asText());assertEquals(saved.path("runId").asText(),actual.path("runId").asText());
            assertEquals(saved.path("releaseId").asText(),actual.path("releaseId").asText());assertEquals("worker",actual.path("service").asText());
            assertTrue(!actual.path("running").asBoolean()&&actual.path("pid").asLong()==0,"Read-only diagnostic must preserve the stopped container");
            Files.writeString(evidence.resolve("result.json"),"{\"status\":\"passed\",\"actualJavaProducer\":true,\"selectedFields\":9,\"preservedStopped\":true,\"resourceWrites\":0}");
        } finally {lifecycle.cancelAndJoin();}
    }
}
