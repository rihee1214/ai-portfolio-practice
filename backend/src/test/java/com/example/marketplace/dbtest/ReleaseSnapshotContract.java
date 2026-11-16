package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Full-table observation and narrow, causal restart validation. Never changes a database. */
final class ReleaseSnapshotContract {
    static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    static final Set<String> AUDIT=Set.of("claim_exchange_refund_recovery_run","claim_exchange_refund_recovery_stage",
            "claim_exchange_refund_recovery_page","claim_exchange_refund_recovery_page_complete",
            "claim_return_recovery_run","claim_return_recovery_stage","claim_return_recovery_page",
            "claim_return_recovery_page_complete","claim_return_recovery_candidate","job_claim_fact");
    static final Set<String> AUTH=Set.of("spring_session","spring_session_attributes","guest_access_session",
            "email_verification_token","password_reset_token","guest_checkout","guest_recovery_attempt");
    static final Set<String> STAGES=Set.of("ITEM_EFFECT","SHIPPING_RESULT","SELLER_REEVALUATE");
    static final Set<String> NON_PRIMARY=Set.of("dashboard_metric","media_slot");
    static final List<String> MULTISET=List.of("$canonical-row-multiset");
    record Snapshot(String id,Map<String,List<JsonNode>> tables,Map<String,List<String>> keys,
                    List<Map<String,Object>> sequences,String mediaDigest,Map<String,List<String>> locators,
                    Map<String,Object> operatorDatabase,Map<String,Object> operatorMedia,Map<String,Object> observation) {
        Map<String,Object> full() {
            return Map.of("sha256",hash(Map.of("database",operatorDatabase,"media",operatorMedia)),
                    "tableInventory",new TreeSet<>(tables.keySet()),"sequences",sequences,"mediaDigest",mediaDigest);
        }
    }

    static Set<String> coverage() {
        try(var input=ReleaseSnapshotContract.class.getResourceAsStream("/release-snapshot-tables.txt")) {
            require(input!=null,"Explicit snapshot coverage resource missing");
            return new TreeSet<>(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF","").lines().filter(s->!s.isBlank()).toList());
        } catch(java.io.IOException failure) {throw new IllegalStateException(failure);}
    }

    static Snapshot capture(ReleaseContractSupport support,org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,Path media) throws Exception {
        var tables=new TreeMap<String,List<JsonNode>>();var keys=new TreeMap<String,List<String>>();
        var sequences=new ArrayList<Map<String,Object>>();var locators=new TreeMap<String,List<String>>();
        var operatorDatabase=new TreeMap<String,Object>();var mediaReferences=new ArrayList<String>();var diagnostics=new ArrayList<Map<String,Object>>();
        jdbc.getJdbcTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
            boolean auto=connection.getAutoCommit(),readOnly=connection.isReadOnly();int isolation=connection.getTransactionIsolation();
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);connection.setReadOnly(true);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(3);statement.execute("SET LOCAL statement_timeout='3s'");statement.execute("SET LOCAL TIME ZONE 'UTC'");statement.execute("SET LOCAL DateStyle='ISO, YMD'");
                var names=new TreeSet<String>();try(var rows=statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='marketplace' ORDER BY tablename")) {while(rows.next()) {names.add(rows.getString(1));}}
                require(names.equals(coverage()),"Unregistered full snapshot table inventory");
                for(String table:names) {
                    var values=new ArrayList<JsonNode>();
                    try(var rows=statement.executeQuery("SELECT to_jsonb(t)::text FROM marketplace.\""+table+"\" t ORDER BY to_jsonb(t)::text")) {while(rows.next()) {values.add(JSON.readTree(rows.getString(1)));}}
                    tables.put(table,values);
                    try(var rows=statement.executeQuery("SELECT json_build_object('count',count(*),'md5',md5(coalesce(string_agg(rowtext,E'\\n' ORDER BY rowtext),'')),'rowHashes',coalesce(json_agg(encode(sha256(convert_to(rowtext,'UTF8')),'hex') ORDER BY rowtext),'[]'::json))::text FROM (SELECT row_to_json(t)::text rowtext FROM marketplace.\""+table+"\" t) rows")) {require(rows.next(),"Missing canonical table inventory");operatorDatabase.put(table,JSON.readValue(rows.getString(1),Map.class));}
                }
                try(var rows=statement.executeQuery("SELECT c.relname,string_agg(a.attname,',' ORDER BY k.ordinality) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum,ordinality) JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=k.attnum WHERE i.indisprimary AND n.nspname='marketplace' GROUP BY c.relname")) {while(rows.next()) {keys.put(rows.getString(1),List.of(rows.getString(2).split(",")));}}
                var missing=new TreeSet<>(names);missing.removeAll(keys.keySet());
                require(missing.equals(new TreeSet<>(NON_PRIMARY)),"Unregistered non-primary snapshot tables: "+missing);
                NON_PRIMARY.forEach(table->keys.put(table,MULTISET));
                try(var rows=statement.executeQuery("SELECT sequencename,last_value::text FROM pg_sequences WHERE schemaname='marketplace' ORDER BY sequencename")) {while(rows.next()) {var row=new LinkedHashMap<String,Object>();row.put("name",rows.getString(1));row.put("value",rows.getString(2));sequences.add(row);}}
                operatorDatabase.put("__sequences",sequences);
                var sequenceSettings=new ArrayList<Map<String,Object>>();
                try(var rows=statement.executeQuery("SELECT p.sequencename,p.start_value::text,p.increment_by::text,p.cache_size::text,p.cycle,current_user,has_sequence_privilege(current_user,c.oid,'SELECT'),has_sequence_privilege(current_user,c.oid,'USAGE') FROM pg_sequences p JOIN pg_namespace n ON n.nspname=p.schemaname JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=p.sequencename WHERE p.schemaname='marketplace' ORDER BY p.sequencename")) {while(rows.next()) {var diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("name",rows.getString(1));diagnostic.put("start",rows.getString(2));diagnostic.put("increment",rows.getString(3));diagnostic.put("cache",rows.getString(4));diagnostic.put("cycle",rows.getBoolean(5));diagnostic.put("observationRole",rows.getString(6));diagnostic.put("canSelect",rows.getBoolean(7));diagnostic.put("canUsage",rows.getBoolean(8));sequenceSettings.add(diagnostic);}}
                for(var diagnostic:sequenceSettings) {String name=diagnostic.get("name").toString();require(name.matches("[a-z_][a-z0-9_]*"),"Invalid sequence metadata name");try(var rows=statement.executeQuery("SELECT last_value::text,is_called FROM marketplace.\""+name+"\"")) {require(rows.next(),"Missing sequence allocation state");diagnostic.put("lastValue",rows.getString(1));diagnostic.put("isCalled",rows.getBoolean(2));diagnostic.put("canonicalValue",sequences.stream().filter(row->name.equals(row.get("name"))).findFirst().orElseThrow().get("value"));diagnostics.add(diagnostic);}}
                var conditions=new ArrayList<String>();
                try(var rows=statement.executeQuery("SELECT c.relname,a.attname FROM pg_constraint f JOIN pg_class c ON c.oid=f.conrelid JOIN pg_attribute a ON a.attrelid=f.conrelid AND a.attnum=f.conkey[1] WHERE f.contype='f' AND f.confrelid='marketplace.media_asset'::regclass")) {while(rows.next()) {String table=rows.getString(1),column=rows.getString(2);require(table.matches("[a-z_][a-z0-9_]*")&&column.matches("[a-z_][a-z0-9_]*"),"Invalid media reference inventory");conditions.add("EXISTS(SELECT 1 FROM marketplace.\""+table+"\" r WHERE r.\""+column+"\"=m.id)");}}
                try(var rows=statement.executeQuery("SELECT id::text FROM marketplace.media_asset m WHERE retired_at IS NULL"+(conditions.isEmpty()?"":" OR "+String.join(" OR ",conditions))+" ORDER BY id")) {while(rows.next()) {mediaReferences.add(rows.getString(1));}}
                for(String family:List.of("return","exchange_refund")) {
                    for(String stage:STAGES) {
                        var ids=new ArrayList<String>();
                        try(var rows=statement.executeQuery("SELECT id::text FROM marketplace.claim_"+family+"_recovery_ids('"+stage+"') ORDER BY id")) {while(rows.next()) {ids.add(rows.getString(1));}}
                        locators.put(family+":"+stage,ids);
                    }
                }
                connection.commit();
            } finally {connection.rollback();connection.setAutoCommit(auto);connection.setReadOnly(readOnly);connection.setTransactionIsolation(isolation);}
            return null;
        });
        var files=new TreeMap<String,String>();try(var paths=Files.walk(media)) {for(Path path:paths.filter(Files::isRegularFile).filter(path->!path.equals(media.resolve(".lock"))).toList()) {files.put(media.relativize(path).toString().replace('\\','/'),ReleaseContractSupport.sha(path));}}
        var inventory=new ArrayList<Map<String,Object>>();for(var entry:files.entrySet()) {inventory.add(Map.of("path",entry.getKey(),"size",Files.size(media.resolve(entry.getKey())),"sha256",entry.getValue()));}
        var operatorMedia=Map.<String,Object>of("namespace",Files.readString(media.resolve(".database")),"files",inventory,"referenced",mediaReferences,"unreferenced",files.keySet().stream().filter(name->!name.equals(".database")&&mediaReferences.stream().noneMatch(id->name.equals(id+".png"))).toList());
        var result=new Snapshot(java.util.UUID.randomUUID().toString(),tables,keys,sequences,hash(files),locators,operatorDatabase,operatorMedia,Map.of("sequenceDiagnostics",diagnostics,"observedAt",Instant.now().toString()));
        privateFile(support.folder.resolve("snapshot-"+result.id()+".private.json"),JSON.writeValueAsString(Map.of("tables",tables,"keys",keys,"sequences",sequences,"media",files,"locators",locators,"observation",result.observation())));
        Files.writeString(support.folder.resolve("snapshot-"+result.id()+".json"),JSON.writeValueAsString(safe(result)));
        return result;
    }

    static Map<String,Object> safe(Snapshot snapshot) {
        var rows=new TreeMap<String,Object>();
        for(String table:snapshot.tables().keySet()) {var hashes=new TreeMap<String,String>();index(snapshot,table).forEach((key,row)->hashes.put(key,hash(row)));rows.put(table,Map.of("identityMode",snapshot.keys().get(table).equals(MULTISET)?"full-canonical-row-multiset":"primary-key","validator",AUDIT.contains(table)?"typed-scanner-graph":table.equals("job")||table.equals("outbox_event")?"typed-job-graph":"exact","primaryKeyRowHashes",hashes));}
        return Map.of("snapshotId",snapshot.id(),"full",snapshot.full(),"coverage",rows);
    }

    static void exact(Snapshot before,Snapshot after) {
        require(before.tables().equals(after.tables())&&before.keys().equals(after.keys()),"Stopped source table/row mutation during restore");
        require(before.sequences().equals(after.sequences()),"Stopped source sequence mutation during restore");
        require(before.mediaDigest().equals(after.mediaDigest()),"Stopped source media mutation during restore");
        require(before.full().equals(after.full()),"Stopped source canonical inventory mutation during restore");
    }

    static Map<String,Object> validate(Snapshot before,Snapshot after,Instant started,Instant ended,boolean restore) {
        require(before.tables().keySet().equals(coverage())&&after.tables().keySet().equals(coverage()),"Unregistered changed table");
        require(before.keys().equals(after.keys()),"Primary key inventory changed");
        require(before.mediaDigest().equals(after.mediaDigest()),"Media mutation during release");
        var additions=new TreeMap<String,List<JsonNode>>();var typed=new TreeMap<String,Map<String,Object>>();
        for(String table:coverage()) {
            var old=index(before,table);var current=index(after,table);var added=new ArrayList<JsonNode>();var changed=new ArrayList<String>();
            for(var entry:old.entrySet()) {
                var row=current.get(entry.getKey());
                if(restore&&AUTH.contains(table)) {auth(table,entry.getValue(),row);continue;}
                require(row!=null,"Original row deleted: "+table+"/"+entry.getKey());
                if(!row.equals(entry.getValue())) {
                    require(table.equals("job")||table.equals("dashboard_head")||table.equals("dashboard_run"),"Original immutable row changed: "+table+"/"+entry.getKey());
                    changed.add(entry.getKey());
                }
            }
            current.forEach((key,row)->{if(!old.containsKey(key)) {added.add(row);}});
            boolean registered=AUDIT.contains(table)||table.equals("job")||table.equals("outbox_event")||ReleaseScheduledWork.TABLES.contains(table)||ReleaseConfirmationStartup.TABLES.contains(table);
            require(added.isEmpty()||registered,"Unregistered append: "+table);
            additions.put(table,added);
            if(registered) {typed.put(table,Map.of("passed",true,"validator",table.equals("job")||table.equals("outbox_event")?"actual-job-route-and-claim":"exact-scanner-origin-graph","addedIds",added.stream().map(row->key(after,table,row)).toList(),"changedIds",changed));}
        }
        var newJobs=new TreeSet<String>();var scanJobs=new TreeSet<String>();
        for(String family:List.of("return","exchange_refund")) {scanner(before,after,additions,family,started,ended,newJobs,scanJobs);}
        ReleaseScheduledWork.validate(before,after,additions,started,ended,newJobs);
        var confirmation=ReleaseConfirmationStartup.validate(before,after,additions,started,ended,newJobs,scanJobs);
        var confirmationTyped=new java.util.LinkedHashMap<String,Object>(typed.get("order_confirmation_scan"));confirmationTyped.put("validator","fixed-confirmation-startup-graph");confirmationTyped.put("producerProof",confirmation);typed.put("order_confirmation_scan",confirmationTyped);
        jobs(before,after,additions,newJobs,scanJobs);
        if(restore) {for(String table:AUTH) {typed.put(table,Map.of("passed",true,"validator","restore-only-exact-credential-fields","addedIds",List.of(),"changedIds",index(before,table).entrySet().stream().filter(entry->!entry.getValue().equals(index(after,table).get(entry.getKey()))).map(Map.Entry::getKey).toList()));}}
        return Map.of("passed",true,"allOldRowsPreserved",true,"coverageComplete",true,"unregisteredChanges",List.of(),"typeRegistry",new ArrayList<>(typed.keySet()),"typedResults",typed,"beforeSnapshotId",before.id(),"afterSnapshotId",after.id());
    }

    private static void scanner(Snapshot before,Snapshot after,Map<String,List<JsonNode>> added,String family,Instant start,Instant end,Set<String> expectedJobs,Set<String> scanJobs) {
        String prefix="claim_"+family+"_recovery_",kind=family.equals("return")?"RETURN_RECOVERY_SCAN.v1":"EXCHANGE_REFUND_RECOVERY_SCAN.v1",keyPrefix=family.equals("return")?"return-recovery-scan:":"exchange-refund-recovery-scan:";
        var runs=added.get(prefix+"run");require(runs.size()==1,"Restart must produce exactly one "+family+" scan run");var run=runs.getFirst();String runId=text(run,"id");
        require(text(run,"scan_version").equals(family.equals("return")?"P09-D-RECOVERY-1":"P09-E-REFUND-RECOVERY-1"),"Wrong scan version");
        require(strings(run.path("stages")).equals(STAGES),"Wrong supported stage set");Instant at=time(run,"started_at");require(!at.isBefore(start.minusSeconds(1))&&!at.isAfter(end.plusSeconds(1)),"Unregistered scanner startup event");
        var stages=added.get(prefix+"stage");require(stages.size()==3,"Incorrect stage count");var seen=new TreeSet<String>();
        for(var stage:stages) {
            String name=text(stage,"stage"),jobId=text(stage,"first_job_id");require(STAGES.contains(name)&&seen.add(name)&&text(stage,"run_id").equals(runId),"Unrelated or duplicate stage");
            var source=after.locators().get(family+":"+name);require(source!=null&&source.size()<50,"This rehearsal requires an explicit single bounded page");
            require(nullable(stage,"upper_id").equals(source.isEmpty()?"":source.getLast()),"Scanner upper bound changed");
            var job=by(after,"job","id",jobId);require(text(job,"type").equals(kind)&&text(job,"cause_key").equals(runId)&&text(job,"payload").equals(runId+"|"+name+"|START")&&text(job,"dedupe_key").equals(keyPrefix+runId+":"+name+":START")&&time(job,"scheduled_at").equals(at),"Wrong scan job route/cause/dedupe");
            expectedJobs.add(jobId);scanJobs.add(jobId);
            var pages=added.get(prefix+"page").stream().filter(row->text(row,"run_id").equals(runId)&&text(row,"stage").equals(name)).toList();require(pages.size()==1,"Missing or unrelated scanner page");var page=pages.getFirst();String pageId=text(page,"id");
            require(text(page,"cursor_key").equals("START")&&text(page,"scan_job_id").equals(jobId)&&new ArrayList<>(strings(page.path("locator_ids"))).equals(source),"Wrong page cursor/locators/job");
            claim(after,jobId,text(page,"plan_owner_token"));
            var completions=added.get(prefix+"page_complete").stream().filter(row->text(row,"page_id").equals(pageId)).toList();require(completions.size()==1,"Missing page completion");var completion=completions.getFirst();
            require(completion.path("finished").asBoolean()&&nullable(completion,"next_job_id").isEmpty()&&nullable(completion,"next_cursor").equals(source.isEmpty()?"":source.getLast())&&text(completion,"scan_job_id").equals(jobId)&&text(completion,"owner_token").equals(text(page,"plan_owner_token")),"Wrong completion route/owner/cursor");
            require(stage.path("completed").asBoolean()&&text(stage,"cursor_page_id").equals(pageId)&&nullable(stage,"cursor_id").equals(nullable(completion,"next_cursor")),"Cursor regression or completion graph mismatch");
            if(family.equals("return")) {
                var candidates=added.get(prefix+"candidate").stream().filter(row->text(row,"page_id").equals(pageId)).toList();require(candidates.size()==source.size(),"Candidate coverage mismatch");
                for(var candidate:candidates) {
                    String target=text(candidate,"return_id");require(source.contains(target)&&text(candidate,"run_id").equals(runId)&&text(candidate,"stage").equals(name)&&text(candidate,"scan_job_id").equals(jobId)&&text(candidate,"owner_token").equals(text(page,"plan_owner_token")),"Unrelated candidate or owner");
                    require(text(candidate,"outcome").equals("SKIP")&&nullable(candidate,"delivery_id").isEmpty(),"Unexpected recovery delivery/effect");
                    require(skipReason(after,name,target).equals(text(candidate,"reason")),"Candidate reason does not match original source fact");
                }
            } else {require(source.isEmpty(),"Unregistered exchange recovery candidates");}
        }
        require(added.get(prefix+"page").size()==3&&added.get(prefix+"page_complete").size()==3,"Unrelated recovery append");
    }

    private static String skipReason(Snapshot snapshot,String stage,String target) {
        if(stage.equals("ITEM_EFFECT")) {
            require(snapshot.tables().get("claim_return_financial_result").stream().anyMatch(row->text(row,"return_id").equals(target)),"Missing original item effect source");
            return "ITEM_OPERATION_ALREADY_RECORDED";
        }
        if(stage.equals("SELLER_REEVALUATE")) {
            var request=by(snapshot,"claim_return_request","id",target);var ready=by(snapshot,"claim_return_item_ready","return_id",target);var completion=by(snapshot,"claim_return_completion","return_id",target);
            String seller=text(request,"seller_order_id"),order=text(request,"order_id");var current=by(snapshot,"claim_seller_shipping_current","seller_order_id",seller);var decision=by(snapshot,"claim_seller_shipping_decision","id",text(current,"decision_id"));
            require(text(request,"state").equals("COMPLETED")&&text(completion,"decision_id").equals(text(decision,"id"))&&text(decision,"seller_order_id").equals(seller)&&text(decision,"order_id").equals(order),"Seller recovery skip lacks the original completed/current decision chain");
            var refund=by(snapshot,"payment_refund_attempt","id",text(ready,"attempt_id"));
            require(text(ready,"order_id").equals(order)&&text(ready,"seller_order_id").equals(seller)&&text(refund,"claim_id").equals(target)&&text(refund,"order_id").equals(order)&&text(refund,"seller_order_id").equals(seller)&&text(refund,"purpose").equals("ITEM")&&text(refund,"state").equals("SUCCEEDED")&&refund.path("version").equals(ready.path("result_version")),"Seller recovery skip lacks the original item refund result");
            require(java.util.stream.StreamSupport.stream(decision.path("units").spliterator(),false).anyMatch(unit->text(unit,"factId").equals(target)&&Set.of("BUYER_RETURN_READY","SELLER_RETURN_READY").contains(text(unit,"kind"))),"Seller recovery decision does not contain the retained return source");
            return "CURRENT_SELLER_FACTS_ALREADY_EVALUATED";
        }
        throw new AssertionError("Unregistered candidate stage: "+stage);
    }

    private static void jobs(Snapshot before,Snapshot after,Map<String,List<JsonNode>> added,Set<String> expectedJobs,Set<String> scanJobs) {
        require(added.get("job").stream().map(row->text(row,"id")).collect(java.util.stream.Collectors.toSet()).equals(expectedJobs),"Unrelated new runtime job");
        for(var receipt:added.get("outbox_event")) {
            String jobId=text(receipt,"job_id");require(expectedJobs.contains(jobId),"Unrelated outbox append");var job=by(after,"job","id",jobId);
            String event=text(job,"type").equals("DASHBOARD_REFRESH.v1")?text(job,"payload"):java.util.UUID.nameUUIDFromBytes(text(job,"dedupe_key").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            require(text(receipt,"id").equals(event),"Outbox cause identity mismatch");
        }
        require(added.get("outbox_event").size()==expectedJobs.size(),"Missing outbox receipt");
        for(var fact:added.get("job_claim_fact")) {
            String jobId=text(fact,"job_id");var job=by(after,"job","id",jobId);int attempt=fact.path("attempt").asInt();
            require(attempt>=1&&attempt==fact.path("previous_attempt").asInt()+1&&attempt<=job.path("attempts").asInt()&&time(fact,"initial_lease_until").equals(time(fact,"claimed_at").plusSeconds(30))&&!time(fact,"claimed_at").isBefore(time(fact,"required_at"))&&time(fact,"scheduled_at_snapshot").equals(time(job,"scheduled_at")),"Invalid actual due claim fact");
            String kind=text(fact,"eligibility_kind");require(Set.of("READY","TAKEOVER").contains(kind),"Unknown actual claim eligibility");
            if(kind.equals("TAKEOVER")) {
                require(attempt>1,"Takeover requires an earlier owner");var prior=after.tables().get("job_claim_fact").stream().filter(row->text(row,"job_id").equals(jobId)&&row.path("attempt").asInt()==attempt-1).findFirst().orElseThrow();
                require(text(fact,"previous_owner").equals(text(prior,"owner_token"))&&!time(fact,"required_at").isBefore(time(prior,"initial_lease_until")),"Wrong previous takeover claim owner/lease");
            } else {require(nullable(fact,"previous_owner").isEmpty()&&!time(fact,"claimed_at").isBefore(time(job,"scheduled_at")),"READY retry must have no previous live owner");}
            var original=before.tables().get("job").stream().filter(row->text(row,"id").equals(jobId)).findFirst();
            if(original.isPresent()&&attempt==original.get().path("attempts").asInt()+1) {
                var source=original.get();String expectedKind=text(source,"status").equals("RUNNING")?"TAKEOVER":"READY";
                require(kind.equals(expectedKind)&&nullable(fact,"previous_owner").equals(nullable(source,"owner_token"))&&time(fact,"required_at").equals(time(source,expectedKind.equals("TAKEOVER")?"lease_until":"next_run_at")),"New claim not bound to original observed status/owner/due");
            }
            require(!text(fact,"owner_token").isBlank(),"Missing claim owner");
        }
        for(var old:before.tables().get("job")) {
            var current=by(after,"job","id",text(old,"id"));
            for(String field:List.of("id","type","dedupe_key","cause_key","payload","scheduled_at")) {require(old.path(field).equals(current.path(field)),"Original job command rewritten");}
            if(old.equals(current)) {continue;}
            int from=old.path("attempts").asInt(),to=current.path("attempts").asInt();require(to>=from&&to<=5&&!text(old,"status").equals("SUCCEEDED")&&!text(old,"status").equals("FAILED"),"Invalid job state progression");
            for(int attempt=from+1;attempt<=to;attempt++) {final int number=attempt;require(added.get("job_claim_fact").stream().filter(row->text(row,"job_id").equals(text(old,"id"))&&row.path("attempt").asInt()==number).count()==1,"Job transition has no exact actual claim");}
            require(to>from||text(old,"status").equals("RUNNING"),"Uncaused runtime job mutation");
        }
        for(String id:scanJobs) {require(text(by(after,"job","id",id),"status").equals("SUCCEEDED"),"Scan job not completed");}
    }

    private static void claim(Snapshot snapshot,String job,String owner) {
        require(snapshot.tables().get("job_claim_fact").stream().filter(row->text(row,"job_id").equals(job)&&text(row,"owner_token").equals(owner)).count()==1,"Wrong job claim owner");
    }

    private static void auth(String table,JsonNode old,JsonNode current) {
        if(table.equals("spring_session")||table.equals("spring_session_attributes")) {require(current==null,"Restored session survived");return;}
        require(current!=null,"Restore deleted authentication history");
        var allowed=switch(table) {
            case "guest_access_session" -> Set.of("revoked_at");
            case "email_verification_token","password_reset_token" -> Set.of("consumed_at");
            case "guest_checkout" -> Set.of("state");
            case "guest_recovery_attempt" -> Set.of("state","grant_hash","grant_expires_at");
            default -> throw new AssertionError("Unknown credential table");
        };
        for(var field:old.properties()) {if(!allowed.contains(field.getKey())) {require(field.getValue().equals(current.path(field.getKey())),"Credential invalidation rewrote protected field");}}
        switch(table) {
            case "guest_access_session" -> {if(old.path("revoked_at").isNull()) {require(!current.path("revoked_at").isNull(),"Old guest session not revoked");}else {require(old.equals(current),"Revoked history rewritten");}}
            case "email_verification_token","password_reset_token" -> {if(old.path("consumed_at").isNull()) {require(!current.path("consumed_at").isNull(),"Old token not consumed");}else {require(old.equals(current),"Consumed token rewritten");}}
            case "guest_checkout" -> {if(Set.of("OPEN","VERIFIED").contains(text(old,"state"))) {require(text(current,"state").equals("REVOKED"),"Open guest checkout survived");}else {require(old.equals(current),"Consumed checkout rewritten");}}
            case "guest_recovery_attempt" -> {if(!Set.of("CONSUMED","REJECTED").contains(text(old,"state"))||text(old,"state").equals("REJECTED")&&!old.path("grant_hash").isNull()) {require(text(current,"state").equals("REJECTED")&&current.path("grant_hash").isNull()&&current.path("grant_expires_at").isNull(),"Old recovery grant survived");}else {require(old.equals(current),"Consumed recovery history rewritten");}}
            default -> throw new AssertionError("Unknown credential table");
        }
    }

    static Map<String,JsonNode> index(Snapshot snapshot,String table) {
        var result=new TreeMap<String,JsonNode>();var multiplicities=new TreeMap<String,Integer>();
        for(var row:snapshot.tables().get(table)) {
            String key=key(snapshot,table,row);
            if(snapshot.keys().get(table).equals(MULTISET)) {key=hash(row)+":"+multiplicities.merge(hash(row),1,Integer::sum);}
            require(result.put(key,row)==null,"Duplicate primary key: "+table);
        }
        return result;
    }
    static String key(Snapshot snapshot,String table,JsonNode row) {return hash(snapshot.keys().get(table).stream().map(row::path).toList());}
    static JsonNode by(Snapshot snapshot,String table,String field,String value) {return snapshot.tables().get(table).stream().filter(row->text(row,field).equals(value)).findFirst().orElseThrow(()->new AssertionError("Missing graph row: "+table));}
    static String text(JsonNode row,String field) {return row.path(field).asText();}
    static String nullable(JsonNode row,String field) {return row.path(field).isNull()||row.path(field).isMissingNode()?"":text(row,field);}
    static Instant time(JsonNode row,String field) {return java.time.OffsetDateTime.parse(text(row,field).replace(' ','T')).toInstant();}
    static Set<String> strings(JsonNode array) {var values=new TreeSet<String>();array.forEach(row->require(values.add(row.asText()),"Duplicate typed locator"));return values;}
    static String hash(Object value) {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(JSON.readValue(JSON.writeValueAsString(value),Object.class)))));}catch(Exception failure) {throw new IllegalStateException(failure);}}
    private static Object canonical(Object value) {if(value instanceof Map<?,?> map) {var sorted=new TreeMap<String,Object>();map.forEach((key,item)->sorted.put(key.toString(),canonical(item)));return sorted;}if(value instanceof List<?> list) {return list.stream().map(ReleaseSnapshotContract::canonical).toList();}return value;}
    static void require(boolean condition,String message) {if(!condition) {throw new AssertionError(message);}}
    static void privateFile(Path path,String content) throws Exception {Files.createFile(path);var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);if(acl!=null) {acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path)).setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class)).build()));}Files.writeString(path,content);}
}
