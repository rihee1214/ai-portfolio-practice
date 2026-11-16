package com.example.marketplace.dbtest;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.by;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.nullable;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.require;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.text;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.time;

/** Explicit scheduled read-model and notification effects; all commerce rows stay exact. */
final class ReleaseScheduledWork {
    static final Set<String> TABLES=Set.of("dashboard_run","dashboard_snapshot","dashboard_metric","dashboard_head","notification_inbox");
    static void validate(ReleaseSnapshotContract.Snapshot before,ReleaseSnapshotContract.Snapshot after,
                         Map<String,List<JsonNode>> additions,Instant start,Instant end,Set<String> newJobs) {
        dashboard(before,after,additions,start,end,newJobs);notifications(before,after,additions);
        sequences(before,after,additions.get("dashboard_run"));
    }
    private static void dashboard(ReleaseSnapshotContract.Snapshot before,ReleaseSnapshotContract.Snapshot after,
                                  Map<String,List<JsonNode>> added,Instant start,Instant end,Set<String> expectedJobs) {
        var slots=new TreeSet<Long>();var oldHead=before.tables().get("dashboard_head");var head=after.tables().get("dashboard_head");
        require(oldHead.size()==1&&head.size()==1,"Dashboard head singleton changed");var previous=oldHead.getFirst();var current=head.getFirst();
        for(var field:previous.properties()) {if(!Set.of("run_id","snapshot_id","last_scheduled").contains(field.getKey())) {require(field.getValue().equals(current.path(field.getKey())),"Unregistered dashboard head field mutation");}}
        for(var run:added.get("dashboard_run")) {
            String jobId=text(run,"job_id");var job=by(after,"job","id",jobId);String event=text(job,"payload"),key="dashboard:"+event;
            java.util.UUID.fromString(event);
            require(text(job,"type").equals("DASHBOARD_REFRESH.v1")&&text(job,"cause_key").equals(key)&&text(job,"dedupe_key").equals(key)&&time(job,"scheduled_at").equals(time(run,"scheduled_at")),"Unrelated dashboard command route");
            require(added.get("job").stream().filter(row->text(row,"id").equals(jobId)).count()==1&&added.get("outbox_event").stream().filter(row->text(row,"id").equals(event)&&text(row,"job_id").equals(jobId)).count()==1,"Dashboard run lacks actual new job/outbox");
            Instant scheduled=time(run,"scheduled_at");long slot=Math.floorDiv(scheduled.getEpochSecond(),300)*300;
            require(!scheduled.isBefore(start.minusSeconds(1))&&!scheduled.isAfter(end.plusSeconds(1))&&slots.add(slot),"Unregistered or duplicate scheduled dashboard slot");
            require(nullable(previous,"last_scheduled").isEmpty()||slot>time(previous,"last_scheduled").getEpochSecond(),"Duplicate dashboard schedule slot");
            for(var pending:before.tables().get("dashboard_run")) {if(text(pending,"state").equals("PENDING")&&!text(by(before,"job","id",text(pending,"job_id")),"status").equals("FAILED")) {var completed=by(after,"dashboard_run","id",text(pending,"id"));require(!text(completed,"state").equals("PENDING")&&!time(completed,"completed_at").isAfter(scheduled),"Scheduled run did not reuse the active pending generation");}}
            expectedJobs.add(jobId);
        }
        for(var old:before.tables().get("dashboard_run")) {
            var run=by(after,"dashboard_run","id",text(old,"id"));if(old.equals(run)) {continue;}
            require(text(old,"state").equals("PENDING")&&Set.of("PUBLISHED","SUPERSEDED").contains(text(run,"state")),"Published dashboard run rewritten or regressed");
            for(var field:old.properties()) {if(!Set.of("state","completed_at").contains(field.getKey())) {require(field.getValue().equals(run.path(field.getKey())),"Original dashboard run identity changed");}}
        }
        var newSnapshotIds=new TreeSet<String>();
        for(var generation:added.get("dashboard_snapshot")) {
            String id=text(generation,"id"),runId=text(generation,"run_id"),jobId=text(generation,"job_id");var run=by(after,"dashboard_run","id",runId);var job=by(after,"job","id",jobId);
            require(text(run,"job_id").equals(jobId)&&text(job,"type").equals("DASHBOARD_REFRESH.v1")&&text(job,"status").equals("SUCCEEDED")&&Set.of("PUBLISHED","SUPERSEDED").contains(text(run,"state")),"Dashboard generation not committed by its actual worker");
            var claims=after.tables().get("job_claim_fact").stream().filter(row->text(row,"job_id").equals(jobId)&&row.path("attempt").asInt()==generation.path("attempt").asInt()&&text(row,"owner_token").equals(text(generation,"token"))).toList();require(claims.size()==1,"Dashboard generation has no exact owner/attempt claim");var claim=claims.getFirst();
            require(generation.path("definition_version").asInt()==1&&!time(generation,"source_as_of").isBefore(time(claim,"claimed_at").minusSeconds(1))&&!time(generation,"source_as_of").isAfter(time(run,"completed_at")),"Dashboard source snapshot time/definition mismatch");
            require(text(generation,"source_snapshot").matches("[0-9]+:[0-9]+:([0-9]+(,[0-9]+)*)?"),"Missing PostgreSQL source snapshot provenance");
            if(!generation.path("created_xid").isMissingNode()) {require(new BigInteger(text(generation,"created_xid")).signum()>0,"Missing staging transaction provenance");}
            var expected=ReleaseDashboardExpectation.calculate(before,time(generation,"source_as_of"));var actual=new TreeMap<ReleaseDashboardExpectation.Key,BigInteger>();
            for(var metric:added.get("dashboard_metric")) {if(text(metric,"snapshot_id").equals(id)) {require(actual.put(ReleaseDashboardExpectation.metricKey(metric),ReleaseDashboardExpectation.amount(metric,"value"))==null,"Duplicate dashboard metric key");}}
            require(expected.equals(actual),"Independent dashboard metric key/value reconciliation failed");newSnapshotIds.add(id);
        }
        for(var metric:added.get("dashboard_metric")) {require(newSnapshotIds.contains(text(metric,"snapshot_id")),"Metric belongs to an unrelated generation");}
        for(var run:added.get("dashboard_run")) {require(added.get("dashboard_snapshot").stream().filter(row->text(row,"run_id").equals(text(run,"id"))).count()==1,"Scheduled dashboard run lacks one complete generation");}
        long previousId=nullable(previous,"run_id").isEmpty()?0:Long.parseLong(text(previous,"run_id"));
        long published=after.tables().get("dashboard_run").stream().filter(row->text(row,"state").equals("PUBLISHED")).mapToLong(row->Long.parseLong(text(row,"id"))).max().orElse(previousId);long expectedHead=Math.max(previousId,published);
        require((nullable(current,"run_id").isEmpty()?0:Long.parseLong(text(current,"run_id")))==expectedHead,"Mixed or regressed dashboard head generation");
        if(expectedHead>0) {var generation=by(after,"dashboard_snapshot","id",text(current,"snapshot_id"));require(Long.parseLong(text(generation,"run_id"))==expectedHead,"Dashboard head snapshot/run mismatch");}else {require(nullable(current,"snapshot_id").isEmpty(),"Head points to missing dashboard generation");}
        long last=nullable(previous,"last_scheduled").isEmpty()?Long.MIN_VALUE:time(previous,"last_scheduled").getEpochSecond();if(!slots.isEmpty()) {last=Math.max(last,slots.last());}
        long currentSlot=nullable(current,"last_scheduled").isEmpty()?Long.MIN_VALUE:time(current,"last_scheduled").getEpochSecond();
        if(currentSlot!=last) {
            require(slots.isEmpty()&&currentSlot>last&&currentSlot%300==0&&currentSlot>=start.minusSeconds(1).getEpochSecond()&&currentSlot<=end.plusSeconds(1).getEpochSecond(),"Unexplained dashboard schedule watermark");
            require(before.tables().get("dashboard_run").stream().anyMatch(row->text(row,"state").equals("PENDING")&&after.tables().get("dashboard_snapshot").stream().anyMatch(g->text(g,"run_id").equals(text(row,"id")))),"Schedule watermark has no reused pending run");
        }
    }
    private static void notifications(ReleaseSnapshotContract.Snapshot before,ReleaseSnapshotContract.Snapshot after,Map<String,List<JsonNode>> added) {
        for(var inbox:added.get("notification_inbox")) {
            String eventId=text(inbox,"event_id");require(inbox.path("read_at").isNull(),"Uncommanded notification read state");var event=by(before,"notification_event","id",eventId);require(event.equals(by(after,"notification_event","id",eventId)),"Notification source event changed");
            var jobs=after.tables().get("job").stream().filter(row->text(row,"type").equals("NOTIFICATION_DELIVER.v1")&&text(row,"cause_key").equals(eventId)&&text(row,"payload").equals(eventId)&&text(row,"dedupe_key").equals("notify:"+eventId)).toList();require(jobs.size()==1,"Unrelated notification delivery");var job=jobs.getFirst();String jobId=text(job,"id");
            require(text(job,"status").equals("SUCCEEDED")&&before.tables().get("outbox_event").stream().anyMatch(row->text(row,"id").equals(eventId)&&text(row,"job_id").equals(jobId)),"Notification lacks original outbox and completed worker");
            require(added.get("job_claim_fact").stream().anyMatch(row->text(row,"job_id").equals(jobId)&&row.path("attempt").asInt()==job.path("attempts").asInt()),"Notification lacks new actual claim");
            require(after.tables().get("notification_inbox").stream().filter(row->text(row,"event_id").equals(eventId)).count()==1,"Duplicate notification business effect");
        }
    }
    private static void sequences(ReleaseSnapshotContract.Snapshot before,ReleaseSnapshotContract.Snapshot after,List<JsonNode> allocated) {
        require(before.sequences().stream().map(row->row.get("name")).toList().equals(after.sequences().stream().map(row->row.get("name")).toList()),"Sequence inventory changed");
        for(var sequence:before.sequences()) {
            String name=sequence.get("name").toString();var current=after.sequences().stream().filter(row->name.equals(row.get("name"))).findFirst().orElseThrow();
            if(!name.equals("dashboard_run_id_seq")) {require(sequence.equals(current),"Unregistered sequence mutation: "+name);continue;}
            var old=diagnostic(before,name);var next=diagnostic(after,name);
            for(String field:List.of("start","increment","cache","cycle")) {require(old.path(field).equals(next.path(field)),"Sequence allocation settings changed");}
            require(old.path("canSelect").asBoolean()&&old.path("canUsage").asBoolean()&&next.path("canSelect").asBoolean()&&next.path("canUsage").asBoolean(),"Sequence NULL may reflect missing observation privilege");
            require(text(old,"increment").equals("1")&&text(old,"cache").equals("1")&&!old.path("cycle").asBoolean(),"Unregistered sequence allocation mode");
            require(nullable(old,"canonicalValue").equals(sequence.get("value")==null?"":sequence.get("value").toString())&&nullable(next,"canonicalValue").equals(current.get("value")==null?"":current.get("value").toString()),"Sequence diagnostic/canonical mismatch");
            boolean called=old.path("isCalled").asBoolean();long first=called?Long.parseLong(text(old,"lastValue"))+1:Long.parseLong(text(old,"start"));var expected=new TreeSet<Long>();for(int index=0;index<allocated.size();index++) {expected.add(first+index);}
            var actual=new TreeSet<Long>();for(var run:allocated) {require(actual.add(Long.parseLong(text(run,"id"))),"Duplicate dashboard identity allocation");}require(expected.equals(actual),"Unexplained dashboard sequence gap/allocation");
            if(allocated.isEmpty()) {require(sequence.equals(current)&&old.path("isCalled").equals(next.path("isCalled"))&&old.path("lastValue").equals(next.path("lastValue")),"Dashboard sequence advanced without a committed allocation");}
            else {require(next.path("isCalled").asBoolean()&&Long.parseLong(text(next,"lastValue"))==actual.last()&&current.get("value").toString().equals(Long.toString(actual.last())),"Dashboard sequence does not match exact new run allocations");}
        }
    }
    private static JsonNode diagnostic(ReleaseSnapshotContract.Snapshot snapshot,String name) {
        var diagnostics=ReleaseSnapshotContract.JSON.readTree(ReleaseSnapshotContract.JSON.writeValueAsString(snapshot.observation().get("sequenceDiagnostics")));var result=new ArrayList<JsonNode>();diagnostics.forEach(row->{if(text(row,"name").equals(name)) {result.add(row);}});require(result.size()==1,"Missing exact sequence allocation diagnostic");return result.getFirst();
    }
}
