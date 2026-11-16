package com.example.marketplace.dbtest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/** Retains the producer's absolute finite phase deadline; observations cannot complete it. */
final class ReleasePhaseForwarder {
    private String id,name;
    private long deadline;
    ReleasePhaseForwarder(long wallMillis,long nanoTime) {deadline=nanoTime+TimeUnit.SECONDS.toNanos(10);}
    List<Map<String,Object>> accept(JsonNode event,long wallMillis,long nanoTime) {
        check(nanoTime);String state=event.path("state").asText(),phase=event.path("phase").asText();
        if(state.equals("started")) {
            require(id==null,"Overlapping release infrastructure phases");long until=java.time.Instant.parse(event.path("deadline").asText()).toEpochMilli(),at=java.time.Instant.parse(event.path("at").asText()).toEpochMilli();
            long duration=until-at,remaining=until-wallMillis;require(duration>0&&duration<=60000&&remaining>0&&remaining<=60000,"Expired or unbounded original infrastructure deadline");
            require(!phase.isBlank(),"Release phase name missing");id=UUID.randomUUID().toString();name=phase;deadline=nanoTime+TimeUnit.MILLISECONDS.toNanos(remaining);
            return List.of(Map.of("event","start","id",id,"name",name,"limitMs",remaining));
        }
        if(List.of("completed","failed","timeout").contains(state)) {
            require(id!=null&&name.equals(phase),"Unmatched release phase completion");var result=Map.<String,Object>of("event","end","id",id,"name",name,"status",state.equals("completed")?"passed":"failed");id=null;name=null;deadline=nanoTime+TimeUnit.SECONDS.toNanos(10);return List.of(result);
        }
        if(state.equals("progress")) {
            boolean spawn=event.path("observation").asText().equals("owned-role-spawned")&&
                    (phase.equals("start-host-api")&&"api-readiness".equals(name)||phase.equals("start-host-worker")&&"worker-readiness".equals(name));
            require(id!=null&&(name.equals(phase)||name.equals(event.path("parentPhase").asText())||spawn),"Release observation has no matching outer phase");
            var result=new LinkedHashMap<String,Object>();result.put("event","progress");result.put("id",id);result.put("name",name);
            for(String field:List.of("attempt","attemptState","originalExitCode","exitCode","timedOut")) {if(event.has(field)) {result.put(field,event.get(field));}}
            return List.of(result);
        }
        // Spawn receipts and package-state observations are retained in the original journal.
        return List.of();
    }
    void check(long nanoTime) {require(nanoTime<=deadline,"Unobserved or expired release infrastructure phase: "+name);}
    void finish() {require(id==null,"Release process exited with an unfinished phase");}
    boolean active() {return id!=null;}
    long deadlineNanos() {return deadline;}
    private static void require(boolean value,String message) {if(!value) {throw new AssertionError(message);}}
}
