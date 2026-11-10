package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.catalog.api.ProductVisibility;
import com.example.marketplace.operations.api.ModerationTargets;
import com.example.marketplace.operations.api.ReportTargets;
import com.example.marketplace.order.api.OrderRejected;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db") @Transactional
public class ModerationActions {
    public record Command(UUID key,Boolean hide,Long caseVersion,Long closeVersion,Long edition,Long targetVersion,UUID sourceHide,String reason) {}
    public record Event(UUID id,UUID actor,String reason,boolean hidden,long edition,long targetVersion,java.time.Instant occurredAt) {}
    public record View(String kind,UUID targetId,long edition,Long targetVersion,Boolean hidden,UUID activeHide,Long closeVersion,Long caseVersion,
            boolean canHide,boolean canRestore,java.util.List<Event> history) {}
    public record Receipt(UUID id,boolean hidden,long targetVersion) {}
    private record Target(String kind,UUID id,UUID parent,long edition) {
        ReportTargets.Ref ref(){return new ReportTargets.Ref(ReportTargets.Kind.valueOf(kind),id,parent,edition);}
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final ReportInvestigations reports;
    private final ProductVisibility products;
    private final ModerationTargets reviews;
    public ModerationActions(NamedParameterJdbcTemplate jdbc,ReportInvestigations reports,ProductVisibility products,ModerationTargets reviews){
        this.jdbc=jdbc;this.reports=reports;this.products=products;this.reviews=reviews;
    }
    private Target target(ReportInvestigations.ModerationCase c,UUID legacyProduct){
        return c==null?new Target("PRODUCT",legacyProduct,null,0):new Target(c.kind(),c.target(),c.parent(),c.edition());
    }
    private ModerationTargets.State lock(Target target){
        if(!target.kind().equals("PRODUCT")){return reviews.lock(target.ref());}
        var product=products.lock(target.id());
        return new ModerationTargets.State(0,product.version(),product.hidden());
    }
    private record Hide(UUID id,UUID report,Long close) {}
    private Hide active(Target t){
        var rows=jdbc.query("""
                SELECT h.id,h.report_id,h.close_version FROM marketplace.operations_moderation_action h
                WHERE h.kind=:kind AND h.target_id=:target AND h.after_hidden AND NOT EXISTS
                (SELECT 1 FROM marketplace.operations_moderation_action r WHERE r.source_hide=h.id)
                """,Map.of("kind",t.kind(),"target",t.id()),(r,n)->new Hide(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,Long.class)));
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void legacy(UUID product){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace.operations_legacy_product_hide WHERE product_id=:id)",
                Map.of("id",product),Boolean.class))){throw new OrderRejected(404);}
    }
    public View view(ProductReports.Actor actor,UUID report,UUID legacyProduct){
        reports.current(actor);
        var c=report==null?null:reports.moderationCase(actor,report);
        if(c==null){legacy(legacyProduct);}
        var t=target(c,legacyProduct);
        var state=lock(t);
        reports.current(actor);
        var events=jdbc.query("""
                SELECT id,actor,reason,after_hidden,edition,target_version,occurred_at FROM marketplace.operations_moderation_action
                WHERE report_id IS NOT DISTINCT FROM CAST(:report AS uuid) AND target_id=:target AND kind=:kind ORDER BY target_version
                """,new MapSqlParameterSource("report",report).addValue("target",t.id()).addValue("kind",t.kind()),
                (r,n)->new Event(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getBoolean(4),r.getLong(5),r.getLong(6)+1,r.getTimestamp(7).toInstant()));
        if(state==null){
            return new View(t.kind(),t.id(),t.edition(),null,null,null,c.lastClose(),c.version(),false,false,events);
        }
        var h=active(t);
        boolean own=h!=null && Objects.equals(h.report(),report);
        boolean eligible=c!=null && c.state().equals("CLOSED") && "VIOLATION".equals(c.decision()) && c.edition()==state.edition();
        return new View(t.kind(),t.id(),state.edition(),state.version(),state.hidden(),own?h.id():null,own?h.close():c==null?null:c.lastClose(),
                c==null?null:c.version(),eligible&&!state.hidden(),state.hidden()&&(c==null?h==null:own),events);
    }
    public Receipt apply(ProductReports.Actor actor,UUID report,UUID legacyProduct,Command cmd){
        reports.current(actor);
        if(cmd==null || cmd.key()==null || cmd.hide()==null || cmd.targetVersion()==null || cmd.edition()==null ||
                cmd.reason()==null || cmd.reason().isBlank() || (cmd.caseVersion()==null)!=(report==null) ||
                (cmd.closeVersion()==null)!=(report==null)){throw new OrderRejected(400);}
        var c=report==null?null:reports.moderationCase(actor,report);
        var t=target(c,legacyProduct);
        if(c==null){legacy(legacyProduct);}
        var p=new MapSqlParameterSource("actor",actor.member()).addValue("key",cmd.key()).addValue("report",report)
                .addValue("target",t.id()).addValue("kind",t.kind()).addValue("parent",t.parent()).addValue("case",cmd.caseVersion())
                .addValue("close",cmd.closeVersion()).addValue("edition",cmd.edition()).addValue("version",cmd.targetVersion())
                .addValue("hide",cmd.hide()).addValue("source",cmd.sourceHide()).addValue("reason",cmd.reason().strip());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:receipt,0))",Map.of("receipt",actor.member()+":"+cmd.key()),(r,n)->r.getObject(1));
        reports.current(actor);
        var prior=jdbc.queryForList("SELECT * FROM marketplace.operations_moderation_action WHERE actor=:actor AND request_key=:key",p);
        if(!prior.isEmpty()){
            var saved=prior.getFirst();
            var previous=new Command(cmd.key(),(Boolean)saved.get("after_hidden"),(Long)saved.get("case_version"),(Long)saved.get("close_version"),
                    (Long)saved.get("edition"),(Long)saved.get("target_version"),(UUID)saved.get("source_hide"),(String)saved.get("reason"));
            var requested=new Command(cmd.key(),cmd.hide(),cmd.caseVersion(),cmd.closeVersion(),cmd.edition(),cmd.targetVersion(),cmd.sourceHide(),cmd.reason().strip());
            if(!Objects.equals(saved.get("report_id"),report) || !t.id().equals(saved.get("target_id")) || !previous.equals(requested)){
                throw new OrderRejected(409);
            }
            return new Receipt((UUID)saved.get("id"),cmd.hide(),cmd.targetVersion()+1);
        }
        var state=lock(t);
        reports.current(actor);
        if(state==null || state.hidden()==cmd.hide() || state.version()!=cmd.targetVersion() || state.edition()!=cmd.edition() ||
                c!=null && c.version()!=cmd.caseVersion()){throw new OrderRejected(409);}
        var h=active(t);
        if(cmd.hide()){
            if(c==null || !c.state().equals("CLOSED") || !"VIOLATION".equals(c.decision()) || !Objects.equals(c.lastClose(),cmd.closeVersion())
                    || t.edition()!=state.edition() || h!=null || cmd.sourceHide()!=null){throw new OrderRejected(409);}
        }else if(c==null){
            if(h!=null || cmd.sourceHide()!=null){throw new OrderRejected(409);}
        }else if(h==null || !h.id().equals(cmd.sourceHide()) || !report.equals(h.report()) || !Objects.equals(h.close(),cmd.closeVersion())){
            throw new OrderRejected(409);
        }
        if(t.kind().equals("PRODUCT")){products.change(t.id(),cmd.hide(),state.version());}
        else {reviews.change(t.ref(),state.version(),cmd.hide());}
        UUID action=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO marketplace.operations_moderation_action(id,actor,request_key,report_id,case_version,close_version,kind,target_id,
                    parent_id,edition,target_version,before_hidden,after_hidden,source_hide,reason)
                VALUES(:action,:actor,:key,:report,:case,:close,:kind,:target,:parent,:edition,:version,:before,:hide,:source,:reason)
                """,p.addValue("action",action).addValue("before",state.hidden()));
        return new Receipt(action,cmd.hide(),state.version()+1);
    }
}
