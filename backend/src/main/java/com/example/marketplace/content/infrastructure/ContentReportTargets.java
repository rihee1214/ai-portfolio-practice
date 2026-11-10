package com.example.marketplace.content.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.media.api.MessageImages;
import com.example.marketplace.operations.api.ReportTargets;
import com.example.marketplace.operations.api.SupportAssignments;
import com.example.marketplace.order.api.OrderRejected;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
@Transactional(propagation=Propagation.MANDATORY)
public class ContentReportTargets implements ReportTargets {
    private final ReviewQueries reviews;
    private final ProductQuestions questions;
    private final AccessPolicy access;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<MessageImages> images;
    public ContentReportTargets(ReviewQueries reviews,ProductQuestions questions,AccessPolicy access,
            NamedParameterJdbcTemplate jdbc,ObjectProvider<MessageImages> images){
        this.reviews=reviews;this.questions=questions;this.access=access;this.jdbc=jdbc;this.images=images;
    }

    @Override public boolean submission(Ref ref,SupportAssignments.Actor actor,UUID asset){
        return resolve(ref,actor,asset,true).privateTarget();
    }
    @Override public Source current(Ref ref,SupportAssignments.Actor actor,UUID asset){
        return resolve(ref,actor,asset,false);
    }
    private Source resolve(Ref ref,SupportAssignments.Actor actor,UUID asset,boolean submitting){
        if(ref.parent()==null || ref.id()==null){throw new OrderRejected(404);}
        if(ref.kind()==Kind.QUESTION || ref.kind()==Kind.ANSWER){
            if(ref.edition()!=0 || (ref.kind()==Kind.QUESTION)!=ref.parent().equals(ref.id())){throw new OrderRejected(404);}
            boolean seller=access.allowed(actor.member(),AccessArea.SELLER);
            var target=questions.messageAccess(ref.parent(),ref.id(),actor,seller,false);
            var q=questions.get(ref.parent(),actor,seller);
            boolean secret="PRIVATE".equals(q.visibility());
            if(submitting && secret && !q.ownQuestion() && !questions.currentSeller(ref.parent(),actor,seller)){
                throw new OrderRejected(404);
            }
            if(asset!=null && images.getObject().forMessage(target).images().stream().noneMatch(i->i.asset().equals(asset))){
                throw new OrderRejected(404);
            }
            return new Source(ref.kind()==Kind.QUESTION?q.body():q.answer(),secret);
        }
        var locked=jdbc.queryForList("SELECT id FROM marketplace.content_review WHERE id=:id FOR SHARE",Map.of("id",ref.parent()),UUID.class);
        if(locked.isEmpty()){throw new OrderRejected(404);}
        var review=reviews.detail(ref.parent(),actor.member(),false);
        if(review.edition()!=ref.edition()){throw new OrderRejected(404);}
        if(ref.kind()==Kind.REVIEW){
            if(!ref.parent().equals(ref.id()) || asset!=null && review.images().stream().noneMatch(i->i.id().equals(asset))){
                throw new OrderRejected(404);
            }
            return new Source(review.body(),false);
        }
        if(asset!=null){throw new OrderRejected(404);}
        return review.responses().stream().filter(r->r.id().equals(ref.id()) && ref.kind().name().equals(r.kind()))
                .map(r->new Source(r.body(),false)).findFirst().orElseThrow(()->new OrderRejected(404));
    }
    @Override public byte[] image(Ref ref,SupportAssignments.Actor actor,UUID asset){
        current(ref,actor,asset);
        if(ref.kind()==Kind.REVIEW){return reviews.image(ref.id(),asset);}
        var target=questions.messageAccess(ref.parent(),ref.id(),actor,access.allowed(actor.member(),AccessArea.SELLER),false);
        return images.getObject().readMessageImage(target,asset);
    }
}
