package com.example.marketplace.content.infrastructure;

import com.example.marketplace.operations.api.ModerationTargets;
import com.example.marketplace.operations.api.ReportTargets;
import com.example.marketplace.order.api.OrderRejected;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public class ReviewModeration implements ModerationTargets {
    private final NamedParameterJdbcTemplate jdbc;
    public ReviewModeration(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public State lock(ReportTargets.Ref ref){
        var parents=jdbc.query("SELECT edition,version,hidden_by_admin FROM marketplace.content_review WHERE id=:id FOR UPDATE",
                Map.of("id",ref.parent()),(r,n)->new State(r.getLong(1),r.getLong(2),r.getBoolean(3)));
        if(parents.isEmpty()){throw new OrderRejected(404);}
        var parent=parents.getFirst();
        if(ref.kind()==ReportTargets.Kind.REVIEW && ref.id().equals(ref.parent())){return parent;}
        if(ref.kind()!=ReportTargets.Kind.COMMENT && ref.kind()!=ReportTargets.Kind.SELLER_REPLY){throw new OrderRejected(409);}
        if(parent.edition()!=ref.edition()){return null;}
        var rows=jdbc.query("""
                SELECT edition,version,hidden_by_admin FROM marketplace.content_review_response
                WHERE id=:id AND review_id=:parent AND kind=:kind AND edition=:edition FOR UPDATE
                """,Map.of("id",ref.id(),"parent",ref.parent(),"kind",ref.kind().name(),"edition",parent.edition()),
                (r,n)->new State(r.getLong(1),r.getLong(2),r.getBoolean(3)));
        if(rows.isEmpty()){throw new OrderRejected(409);}
        return rows.getFirst();
    }
    public void change(ReportTargets.Ref ref,long version,boolean hidden){
        String table=ref.kind()==ReportTargets.Kind.REVIEW?"content_review":"content_review_response";
        if(jdbc.update("UPDATE marketplace."+table+" SET hidden_by_admin=:hidden,version=version+1 WHERE id=:id AND version=:version",
                Map.of("id",ref.id(),"version",version,"hidden",hidden))!=1){throw new OrderRejected(409);}
    }
}
