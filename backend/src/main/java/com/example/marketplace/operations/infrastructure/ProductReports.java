package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.media.api.Media;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.operations.api.ReportTargets;
import com.example.marketplace.operations.api.SupportAssignments;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ProductReports {
    public record Actor(UUID member,String session,Long version) { }
    public record Submission(UUID key,String reason,UUID assetId) { }
    public record Receipt(UUID reportId,String outcome) { }
    public record Summary(UUID reportId,String state,String decision,long version,boolean mine,boolean available) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final Browse browse;
    private final HistorySessions sessions;
    private final ObjectProvider<Media> media;
    private final AccessPolicy access;
    private final ReportTargets targets;

    public ProductReports(NamedParameterJdbcTemplate jdbc,Browse browse,HistorySessions sessions,ObjectProvider<Media> media,AccessPolicy access,ReportTargets targets) {
        this.jdbc=jdbc;
        this.browse=browse;
        this.sessions=sessions;
        this.media=media;
        this.access=access;this.targets=targets;
    }

    private void requireCurrent(Actor actor) {
        if(actor.member()==null || !sessions.lockReadable(actor.session(),actor.member(),actor.version())) {
            throw new OrderRejected(403);
        }
    }

    @Transactional
    public Receipt submit(Actor actor,String slug,Submission input) {
        return intake(actor,slug,null,input);
    }

    @Transactional
    public Receipt submitContent(Actor actor,ReportTargets.Ref ref,Submission input){
        if(ref.kind()==null || ref.id()==null || ref.parent()==null){throw new OrderRejected(404);}
        return intake(actor,ref.kind()+"/"+ref.parent()+"/"+ref.id()+"/"+ref.edition(),ref,input);
    }

    private Receipt intake(Actor actor,String slug,ReportTargets.Ref ref,Submission input){
        requireCurrent(actor);
        if(input.key()==null || input.reason()==null || input.reason().isBlank()) { throw new OrderRejected(400); }
        String reason=input.reason().strip();
        var params=new MapSqlParameterSource("member",actor.member()).addValue("key",input.key())
                .addValue("slug",slug).addValue("reason",reason).addValue("asset",input.assetId());
        // Serialize the rolling limit, duplicate detection and receipt across all targets of this reporter.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lock,0))",
                Map.of("lock","reporter:"+actor.member()),(r,n)->r.getObject(1));
        requireCurrent(actor);
        var previous=jdbc.queryForList("""
                SELECT report_id,outcome,submitted_target,reason,asset_id FROM marketplace.operations_report_evidence
                WHERE reporter_id=:member AND request_key=:key
                """,params);
        if(!previous.isEmpty()) {
            var saved=previous.getFirst();
            if(!slug.equals(saved.get("submitted_target")) || !reason.equals(saved.get("reason"))
                    || !Objects.equals(input.assetId(),saved.get("asset_id"))) { throw new OrderRejected(409); }
            return new Receipt((UUID)saved.get("report_id"),(String)saved.get("outcome"));
        }
        String kind=ref==null?"PRODUCT":ref.kind().name();
        UUID target=ref==null?browse.identify(slug):ref.id();
        if(target==null){throw new OrderRejected(404);}
        params.addValue("target",target).addValue("kind",kind).addValue("edition",ref==null?0:ref.edition())
                .addValue("parent",ref==null?null:ref.parent());
        var matching=jdbc.queryForList("""
                SELECT id,state FROM marketplace.operations_report WHERE reporter_id=:member AND target_kind=:kind
                    AND target_id=:target AND edition=:edition FOR UPDATE
                """,params);
        requireCurrent(actor);
        boolean secret=false;
        if(ref==null){
            if(!target.equals(browse.identify(slug))){throw new OrderRejected(404);}
            if(input.assetId()!=null){
                var storage=media.getIfAvailable();
                if(storage==null || storage.published("products",slug).stream().noneMatch(slot->input.assetId().equals(slot.assetId()))){
                    throw new OrderRejected(404);
                }
            }
        }else{
            secret=targets.submission(ref,new SupportAssignments.Actor(actor.member(),actor.session(),actor.version()),input.assetId());
        }
        requireCurrent(actor);
        params.addValue("secret",secret);
        UUID report;
        String outcome;
        long caseVersion=0;
        if(matching.isEmpty()){
            int created=jdbc.queryForObject("""
                    SELECT count(*) FROM marketplace.operations_report WHERE reporter_id=:member
                      AND created_at>clock_timestamp()-interval '24 hours'
                    """,params,Integer.class);
            if(created>=10){ throw new OrderRejected(429); }
            report=UUID.randomUUID();
            params.addValue("report",report);
            jdbc.update("""
                    INSERT INTO marketplace.operations_report(id,reporter_id,target_kind,target_id,edition,state,parent_id,private_target)
                    VALUES(:report,:member,:kind,:target,:edition,'RECEIVED',:parent,:secret)
                    """,params);
            outcome="RECEIVED";
        }else{
            report=(UUID)matching.getFirst().get("id");
            params.addValue("report",report);
            outcome="CLOSED".equals(matching.getFirst().get("state"))?"REOPEN_REQUESTED":"EVIDENCE_ADDED";
            if(outcome.equals("REOPEN_REQUESTED") && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM marketplace.operations_report_evidence WHERE report_id=:report
                    AND reason=:reason AND asset_id IS NOT DISTINCT FROM CAST(:asset AS uuid))
                    """,params,Boolean.class))){ throw new OrderRejected(409); }
            caseVersion=jdbc.queryForObject("UPDATE marketplace.operations_report SET version=version+1 WHERE id=:report RETURNING version",params,Long.class);
        }
        jdbc.update("""
                INSERT INTO marketplace.operations_report_evidence(reporter_id,request_key,report_id,submitted_target,asset_id,reason,outcome,case_version)
                VALUES(:member,:key,:report,:slug,:asset,:reason,:outcome,:caseVersion)
                """,params.addValue("outcome",outcome).addValue("caseVersion",caseVersion));
        return new Receipt(report,outcome);
    }

    @Transactional
    public List<Summary> mine(Actor actor){
        requireCurrent(actor);
        return jdbc.query("SELECT id,state,decision,version FROM marketplace.operations_report WHERE reporter_id=:member ORDER BY created_at DESC,id",
                Map.of("member",actor.member()),(r,n)->new Summary(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getLong(4),false,false));
    }

    @Transactional
    public List<Summary> queue(Actor actor,String state,int page){
        requireCurrent(actor);
        access.require(actor.member(),AccessArea.CONTENT);
        if(page<0 || page>100000 || state!=null && !List.of("RECEIVED","INVESTIGATING","CLOSED").contains(state)){throw new OrderRejected(400);}
        var eligible=new java.util.HashMap<UUID,Boolean>();
        return jdbc.query("""
                SELECT id,state,version,assignee FROM marketplace.operations_report
                WHERE (CAST(:state AS text) IS NULL OR state=:state) ORDER BY created_at DESC,id LIMIT 20 OFFSET :offset
                """,new MapSqlParameterSource("member",actor.member()).addValue("state",state).addValue("offset",page*20),
                (r,n)->{
                    UUID owner=r.getObject(4,UUID.class);
                    boolean mine=actor.member().equals(owner);
                    boolean available=owner==null || !mine && !eligible.computeIfAbsent(owner,id->access.allowed(id,AccessArea.CONTENT));
                    return new Summary(r.getObject(1,UUID.class),r.getString(2),null,r.getLong(3),mine,available);
                });
    }
}
