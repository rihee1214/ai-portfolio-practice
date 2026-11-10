package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.media.api.Media;
import com.example.marketplace.operations.domain.ReportTransition;
import com.example.marketplace.operations.api.ReportTargets;
import com.example.marketplace.operations.api.SupportAssignments;
import com.example.marketplace.order.api.OrderRejected;
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
@Transactional
public class ReportInvestigations {
    public enum Action { ASSIGN, START, CLOSE, REOPEN }
    public enum Decision { VIOLATION, NO_VIOLATION, INCONCLUSIVE }
    public enum ReopenBasis { NEW_EVIDENCE, JUDGMENT_ERROR }
    public record Command(UUID key,Long version,Action action,UUID operator,Decision decision,String reason,ReopenBasis reopenBasis,UUID evidenceKey) { }
    public record Saved(long version,String state) { }
    public record FileEvidence(UUID key,String reason,UUID assetId,String outcome,long version,java.time.Instant receivedAt) { }
    public record Event(long version,String command,UUID actor,String before,String after,UUID previousAssignee,
                        UUID nextAssignee,String reason,String decision,java.time.Instant occurredAt,String reopenBasis,UUID evidenceKey) { }
    public record Detail(UUID reportId,String kind,UUID targetId,UUID parentId,long edition,String state,long version,
                         String decision,List<FileEvidence> evidence,List<Event> history) { }
    private record Case(UUID id,String kind,UUID target,UUID parent,long edition,boolean secret,UUID assignee,
                        String state,long version,String decision) {
        ReportTargets.Ref ref(){return new ReportTargets.Ref(ReportTargets.Kind.valueOf(kind),target,parent,edition);}
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final HistorySessions sessions;
    private final AccessPolicy access;
    private final SupportAssignments support;
    private final ReportTargets targets;
    private final Browse browse;
    private final ObjectProvider<Media> media;

    public ReportInvestigations(NamedParameterJdbcTemplate jdbc,HistorySessions sessions,AccessPolicy access,
            SupportAssignments support,ReportTargets targets,Browse browse,ObjectProvider<Media> media){
        this.jdbc=jdbc;this.sessions=sessions;this.access=access;this.support=support;
        this.targets=targets;this.browse=browse;this.media=media;
    }
    void current(ProductReports.Actor a){
        if(!sessions.lockReadable(a.session(),a.member(),a.version()) || !access.allowed(a.member(),AccessArea.CONTENT)){
            throw new OrderRejected(403);
        }
    }
    private Case load(ProductReports.Actor actor,UUID id,boolean write){
        current(actor);
        var rows=jdbc.query("""
                SELECT id,target_kind,target_id,parent_id,edition,private_target,assignee,state,version,decision
                FROM marketplace.operations_report WHERE id=:id
                """+(write?" FOR UPDATE":" FOR SHARE"),Map.of("id",id),(r,n)->new Case(r.getObject(1,UUID.class),
                r.getString(2),r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getLong(5),r.getBoolean(6),
                r.getObject(7,UUID.class),r.getString(8),r.getLong(9),r.getString(10)));
        current(actor);
        if(rows.isEmpty()){throw new OrderRejected(404);}
        return rows.getFirst();
    }
    private SupportAssignments.Actor identity(ProductReports.Actor a){return new SupportAssignments.Actor(a.member(),a.session(),a.version());}
    record ModerationCase(UUID id,String kind,UUID target,UUID parent,long edition,String state,long version,Long lastClose,String decision) {}
    ModerationCase moderationCase(ProductReports.Actor actor,UUID report){
        var c=load(actor,report,true);
        assigned(actor,c);
        if(!List.of("PRODUCT","REVIEW","COMMENT","SELLER_REPLY").contains(c.kind())){throw new OrderRejected(409);}
        var close=jdbc.queryForObject("SELECT max(version) FROM marketplace.operations_report_event WHERE report_id=:id AND command='CLOSE'",
                Map.of("id",report),Long.class);
        return new ModerationCase(report,c.kind(),c.target(),c.parent(),c.edition(),c.state(),c.version(),close,c.decision());
    }
    private void assigned(ProductReports.Actor a,Case c){
        if(!a.member().equals(c.assignee()) || c.secret() && !support.assigned(c.parent(),identity(a))){throw new OrderRejected(404);}
        current(a);
    }
    public Detail detail(ProductReports.Actor a,UUID id){
        var c=load(a,id,false);assigned(a,c);
        var p=Map.of("id",id);
        var evidence=jdbc.query("""
                SELECT request_key,reason,asset_id,outcome,case_version,received_at FROM marketplace.operations_report_ordered_evidence
                WHERE report_id=:id ORDER BY case_version,received_at,request_key
                """,p,(r,n)->new FileEvidence(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getString(4),
                        r.getLong(5),r.getTimestamp(6).toInstant()));
        var history=jdbc.query("""
                SELECT version,command,actor,previous_state,next_state,previous_assignee,next_assignee,reason,decision,occurred_at,reopen_basis,evidence_key
                FROM marketplace.operations_report_event WHERE report_id=:id ORDER BY version
                """,p,(r,n)->new Event(r.getLong(1),r.getString(2),r.getObject(3,UUID.class),r.getString(4),r.getString(5),
                r.getObject(6,UUID.class),r.getObject(7,UUID.class),r.getString(8),r.getString(9),r.getTimestamp(10).toInstant(),
                r.getString(11),r.getObject(12,UUID.class)));
        return new Detail(id,c.kind(),c.target(),c.parent(),c.edition(),c.state(),c.version(),c.decision(),evidence,history);
    }
    public String source(ProductReports.Actor a,UUID id){
        var c=load(a,id,false);assigned(a,c);
        if(!c.kind().equals("PRODUCT")){
            String body=targets.current(c.ref(),identity(a),null).body();
            assigned(a,c);
            return body;
        }
        var product=browse.forProduct(c.target());
        if(product==null){throw new OrderRejected(404);}
        assigned(a,c);
        return product.product().title();
    }
    public byte[] image(ProductReports.Actor a,UUID id,UUID key){
        var c=load(a,id,false);assigned(a,c);
        var assets=jdbc.queryForList("SELECT asset_id FROM marketplace.operations_report_evidence WHERE report_id=:id AND request_key=:key AND asset_id IS NOT NULL",
                Map.of("id",id,"key",key),UUID.class);
        if(assets.isEmpty()){throw new OrderRejected(404);}
        UUID asset=assets.getFirst();
        if(!c.kind().equals("PRODUCT")){
            byte[] bytes=targets.image(c.ref(),identity(a),asset);
            assigned(a,c);
            return bytes;
        }
        var product=browse.forProduct(c.target());
        var storage=media.getIfAvailable();
        if(product==null || storage==null || storage.published("products",product.product().slug()).stream().noneMatch(s->asset.equals(s.assetId()))){
            throw new OrderRejected(404);
        }
        byte[] bytes=storage.content(null,asset);
        assigned(a,c);
        return bytes;
    }

    public Saved change(ProductReports.Actor a,UUID id,Command cmd){
        var c=load(a,id,true);
        if(cmd.key()==null || cmd.action()==null || cmd.version()==null || cmd.version()<0 ||
                (cmd.action()==Action.ASSIGN)!=(cmd.operator()!=null) || (cmd.action()==Action.CLOSE)!=(cmd.decision()!=null)){
            throw new OrderRejected(400);
        }
        String reason=cmd.reason()==null?"":cmd.reason().strip();
        if((cmd.action()==Action.REOPEN)!=(cmd.reopenBasis()!=null) ||
                (cmd.reopenBasis()==ReopenBasis.NEW_EVIDENCE)!=(cmd.evidenceKey()!=null)){throw new OrderRejected(400);}
        String basis=cmd.reopenBasis()==null?null:cmd.reopenBasis().name();
        if((cmd.action()==Action.CLOSE || cmd.action()==Action.REOPEN) && reason.isBlank()){throw new OrderRejected(400);}
        if(cmd.action()!=Action.ASSIGN){assigned(a,c);}
        else if(!reason.isEmpty()){throw new OrderRejected(400);}
        String decision=cmd.decision()==null?null:cmd.decision().name();
        var p=new MapSqlParameterSource("id",id).addValue("actor",a.member()).addValue("key",cmd.key());
        var receipts=jdbc.queryForList("SELECT * FROM marketplace.operations_report_event WHERE report_id=:id AND actor=:actor AND request_key=:key",p);
        if(!receipts.isEmpty()){
            var row=receipts.getFirst();long saved=(Long)row.get("version");
            if(saved-1!=cmd.version() || !cmd.action().name().equals(row.get("command")) || !reason.equals(row.get("reason"))
                    || !Objects.equals(decision,row.get("decision")) || !Objects.equals(basis,row.get("reopen_basis"))
                    || !Objects.equals(cmd.evidenceKey(),row.get("evidence_key")) || cmd.action()==Action.ASSIGN && !cmd.operator().equals(row.get("next_assignee"))){
                throw new OrderRejected(409);
            }
            return new Saved(saved,(String)row.get("next_state"));
        }
        if(c.version()!=cmd.version()){throw new OrderRejected(409);}
        UUID next=c.assignee();
        String state=c.state();
        switch(cmd.action()){
            case ASSIGN -> {
                if(!access.allowed(cmd.operator(),AccessArea.CONTENT)){throw new OrderRejected(403);}
                if(!a.member().equals(c.assignee()) && (!a.member().equals(cmd.operator()) ||
                        c.assignee()!=null && access.allowed(c.assignee(),AccessArea.CONTENT))){throw new OrderRejected(403);}
                if(cmd.operator().equals(c.assignee())){throw new OrderRejected(409);}
                next=cmd.operator();
            }
            case START -> state=transition(ReportTransition.START,state);
            case CLOSE -> state=transition(ReportTransition.CLOSE,state);
            case REOPEN -> {
                state=transition(ReportTransition.REOPEN,state);
                if(cmd.reopenBasis()==ReopenBasis.NEW_EVIDENCE && !Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM marketplace.operations_report_ordered_evidence e WHERE e.report_id=:id
                        AND e.request_key=:evidenceKey AND e.outcome='REOPEN_REQUESTED' AND e.case_version >
                        (SELECT max(version) FROM marketplace.operations_report_event WHERE report_id=:id AND command='CLOSE'))
                        """,p.addValue("evidenceKey",cmd.evidenceKey()),Boolean.class))){throw new OrderRejected(409);}

            }
        }
        p.addValue("action",cmd.action().name()).addValue("previous",c.assignee()).addValue("next",next)
                .addValue("before",c.state()).addValue("state",state).addValue("version",c.version()+1)
                .addValue("reason",reason).addValue("decision",decision).addValue("basis",basis).addValue("evidenceKey",cmd.evidenceKey())
                .addValue("currentDecision",cmd.action()==Action.ASSIGN?c.decision():decision);
        jdbc.update("UPDATE marketplace.operations_report SET state=:state,version=:version,assignee=:next,decision=:currentDecision WHERE id=:id",p);
        jdbc.update("""
                INSERT INTO marketplace.operations_report_event(report_id,version,actor,request_key,command,previous_state,next_state,
                    previous_assignee,next_assignee,decision,reason,reopen_basis,evidence_key)
                    VALUES(:id,:version,:actor,:key,:action,:before,:state,:previous,:next,:decision,:reason,:basis,:evidenceKey)
                """,p);
        return new Saved(c.version()+1,state);
    }
    private String transition(ReportTransition action,String current){
        try{return action.apply(current);}catch(IllegalStateException conflict){throw new OrderRejected(409);}
    }
}
