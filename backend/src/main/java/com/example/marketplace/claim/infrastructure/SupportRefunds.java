package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.operations.api.SupportAssignments;
import com.example.marketplace.operations.api.SupportOrderCases;
import com.example.marketplace.order.api.ConfirmedRefundSources;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class SupportRefunds {
    public enum Action { COLLECT, INSPECT, APPROVE, EXECUTE, RETRY_FAILED }
    public record Command(UUID key,long caseVersion,UUID item,int ordinal,UUID previous,String reference,String responsibility,String reason) { }
    public record Entry(UUID collection,UUID item,int ordinal,UUID inspection,String responsibility,UUID approval,
            String amount,UUID execution,UUID attempt,String result,UUID shippingAttempt,String shippingState) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentSupport support;
    private final SupportOrderCases cases;
    private final ConfirmedRefundSources orders;
    private final FinancialIntents intents;
    private final Refunds refunds;
    public SupportRefunds(NamedParameterJdbcTemplate jdbc,CurrentSupport support,SupportOrderCases cases,
            ConfirmedRefundSources orders,FinancialIntents intents,Refunds refunds) {
        this.jdbc=jdbc;this.support=support;this.cases=cases;this.orders=orders;this.intents=intents;this.refunds=refunds;
    }
    private SupportOrderCases.OrderCase assigned(UUID id,OrderQueries.Authority actor) {
        support.require(actor);
        return cases.lockAssigned(id,new SupportAssignments.Actor(actor.member(),actor.browser(),actor.version()));
    }
    @Transactional
    public List<Entry> list(UUID caseId,OrderQueries.Authority actor) {
        var ticket=assigned(caseId,actor);
        var rows=jdbc.query("""
                SELECT c.id,c.item_id,c.ordinal,i.id,i.responsibility,a.id,a.net,x.id,coalesce(last_retry.attempt_id,x.attempt_id),r.state,
                ship.refund_attempt_id,ship.state
                FROM marketplace.claim_support_collection c
                LEFT JOIN marketplace.claim_support_inspection i ON i.collection_id=c.id
                LEFT JOIN marketplace.claim_support_refund_approval a ON a.inspection_id=i.id
                LEFT JOIN marketplace.claim_support_refund_execution x ON x.approval_id=a.id
                LEFT JOIN LATERAL (SELECT attempt_id FROM marketplace.claim_support_refund_retry retry
                 WHERE retry.approval_id=a.id AND retry.purpose='ITEM' ORDER BY sequence DESC LIMIT 1) last_retry ON true
                LEFT JOIN marketplace.claim_shipping_obligation ship ON ship.seller_order_id=c.seller_order_id AND ship.claim_id=a.id
                LEFT JOIN LATERAL (SELECT state FROM marketplace.claim_support_refund_result
                 WHERE attempt_id=coalesce(last_retry.attempt_id,x.attempt_id) ORDER BY result_version DESC LIMIT 1) r ON true
                WHERE c.case_id=:case AND c.seller_order_id=:seller ORDER BY c.item_id,c.ordinal
                """,Map.of("case",caseId,"seller",ticket.sellerOrderId()),(r,n)->new Entry(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getInt(3),r.getObject(4,UUID.class),r.getString(5),r.getObject(6,UUID.class),
                r.getString(7),r.getObject(8,UUID.class),r.getObject(9,UUID.class),r.getString(10),r.getObject(11,UUID.class),r.getString(12)));
        assigned(caseId,actor);return rows;
    }
    private static void requireText(String text,int max) {
        if(text==null||text.isBlank()||text.length()>max||text.codePoints().anyMatch(Character::isISOControl)) { throw new OrderRejected(400); }
    }
    @Transactional
    public List<Entry> command(UUID id,Action action,Command cmd,OrderQueries.Authority actor) {
        support.require(actor);
        var ticket=cases.locateAssigned(id,new SupportAssignments.Actor(actor.member(),actor.browser(),actor.version()));
        if(cmd.key()==null||cmd.item()==null||cmd.ordinal()<0||cmd.caseVersion()<1) { throw new OrderRejected(400); }
        if(ticket.version()!=cmd.caseVersion()) { throw new OrderRejected(409); }
        var unit=orders.lock(ticket.orderNumber(),ticket.sellerOrderId(),cmd.item(),cmd.ordinal());
        if(!ticket.equals(assigned(id,actor))) { throw new OrderRejected(409); }
        var p=new MapSqlParameterSource("id",cmd.key()).addValue("case",id).addValue("actor",actor.member())
                .addValue("number",ticket.orderNumber()).addValue("order",unit.order()).addValue("seller",unit.sellerOrder())
                .addValue("item",unit.item()).addValue("ordinal",unit.ordinal()).addValue("confirmation",unit.confirmation())
                .addValue("net",unit.net()).addValue("policy",unit.policy()).addValue("ref",cmd.reference())
                .addValue("reason",cmd.reason()).addValue("responsibility",cmd.responsibility()).addValue("prev",cmd.previous());
        switch(action) {
            case COLLECT -> {
                requireText(cmd.reference(),200);
                jdbc.update("""
                        INSERT INTO marketplace.claim_support_collection(id,case_id,order_number,order_id,seller_order_id,item_id,ordinal,
                        confirmation_id,collection_reference,actor_id) VALUES(:id,:case,:number,:order,:seller,:item,:ordinal,:confirmation,:ref,:actor)
                        ON CONFLICT DO NOTHING
                        """,p);
                exact("SELECT count(*) FROM marketplace.claim_support_collection WHERE id=:id AND case_id=:case AND item_id=:item AND ordinal=:ordinal AND confirmation_id=:confirmation AND actor_id=:actor AND collection_reference=:ref",p);
            }
            case INSPECT -> {
                requireText(cmd.reference(),200);requireText(cmd.reason(),1000);
                if(!"SELLER".equals(cmd.responsibility())&&!"BUYER".equals(cmd.responsibility())) { throw new OrderRejected(400); }
                jdbc.update("""
                        INSERT INTO marketplace.claim_support_inspection(id,collection_id,inspection_reference,responsibility,defect_evidence,actor_id)
                        SELECT :id,c.id,:ref,:responsibility,:reason,:actor FROM marketplace.claim_support_collection c
                        WHERE c.id=:prev AND c.case_id=:case AND c.item_id=:item AND c.ordinal=:ordinal AND c.confirmation_id=:confirmation
                        ON CONFLICT DO NOTHING
                        """,p);
                exact("""
                        SELECT count(*) FROM marketplace.claim_support_inspection i JOIN marketplace.claim_support_collection c ON c.id=i.collection_id
                        WHERE i.id=:id AND c.id=:prev AND c.case_id=:case AND c.item_id=:item AND c.ordinal=:ordinal
                        AND i.inspection_reference=:ref AND i.responsibility=:responsibility AND i.defect_evidence=:reason AND i.actor_id=:actor
                        """,p);
            }
            case APPROVE -> {
                requireText(cmd.reason(),500);
                jdbc.update("""
                        INSERT INTO marketplace.claim_support_refund_approval(id,inspection_id,collection_id,order_id,seller_order_id,item_id,ordinal,
                        confirmation_id,net,policy_version,reason,actor_id)
                        SELECT :id,i.id,c.id,c.order_id,c.seller_order_id,c.item_id,c.ordinal,c.confirmation_id,:net,:policy,:reason,:actor
                        FROM marketplace.claim_support_inspection i JOIN marketplace.claim_support_collection c ON c.id=i.collection_id
                        WHERE i.id=:prev AND i.responsibility='SELLER' AND c.case_id=:case AND c.item_id=:item AND c.ordinal=:ordinal
                        AND c.confirmation_id=:confirmation ON CONFLICT DO NOTHING
                        """,p);
                exact("""
                        SELECT count(*) FROM marketplace.claim_support_refund_approval a JOIN marketplace.claim_support_collection c ON c.id=a.collection_id
                        WHERE a.id=:id AND a.inspection_id=:prev AND c.case_id=:case AND a.item_id=:item AND a.ordinal=:ordinal
                        AND a.reason=:reason AND a.actor_id=:actor AND a.confirmation_id=:confirmation AND a.net=:net AND a.policy_version=:policy
                        """,p);
            }
            case EXECUTE -> {
                exact("""
                        SELECT count(*) FROM marketplace.claim_support_refund_approval a JOIN marketplace.claim_support_collection c ON c.id=a.collection_id
                        WHERE a.id=:prev AND c.case_id=:case AND a.item_id=:item AND a.ordinal=:ordinal
                        AND a.confirmation_id=:confirmation AND a.net=:net AND a.policy_version=:policy
                        """,p);
                var intent=intents.registerSupport(cmd.previous());
                var original=refunds.original(unit.order());
                UUID attempt=UUID.nameUUIDFromBytes(("support-item:"+intent.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                refunds.reserve(new Refunds.Reserve(attempt,intent.id(),original.paymentId(),unit.order(),unit.sellerOrder(),intent.id(),
                        "ITEM",1,unit.net(),intent.id().toString()));
                intents.bindSupportItem(intent.id(),attempt);
                p.addValue("attempt",attempt);
                jdbc.update("""
                        INSERT INTO marketplace.claim_support_refund_execution(id,approval_id,attempt_id,actor_id)
                        VALUES(:id,:prev,:attempt,:actor) ON CONFLICT DO NOTHING
                        """,p);
                exact("SELECT count(*) FROM marketplace.claim_support_refund_execution WHERE id=:id AND approval_id=:prev AND attempt_id=:attempt AND actor_id=:actor",p);
            }
            case RETRY_FAILED -> {
                requireText(cmd.reason(),500);
                var old=refunds.get(cmd.previous());UUID approval=old.claimId();
                p.addValue("approval",approval).addValue("purpose",old.purpose()).addValue("version",old.version());
                exact("""
                        SELECT count(*) FROM marketplace.claim_support_refund_approval a JOIN marketplace.claim_support_collection c ON c.id=a.collection_id
                        JOIN marketplace.claim_support_refund_result r ON r.approval_id=a.id AND r.state='FAILED'
                        WHERE a.id=:approval AND c.case_id=:case AND a.item_id=:item AND a.ordinal=:ordinal AND a.confirmation_id=:confirmation
                        AND r.attempt_id=:prev AND r.result_version=:version AND r.purpose=:purpose
                        """,p);
                var existing=jdbc.queryForList("SELECT id,actor_id,reason FROM marketplace.claim_support_refund_retry WHERE prior_attempt=:prev OR id=:id FOR UPDATE",p);
                if(!existing.isEmpty()) {
                    exact("SELECT count(*) FROM marketplace.claim_support_refund_retry WHERE prior_attempt=:prev AND id=:id AND actor_id=:actor AND reason=:reason",p);
                    break;
                }
                UUID next=UUID.randomUUID();
                var retried=refunds.retryFailed(old.id(),next,cmd.key(),cmd.reason());
                p.addValue("next",next).addValue("sequence",retried.sequence());
                if(old.purpose().equals("ITEM")) { intents.bindSupportItem(approval,next); }
                else {
                    intents.bindSupportShipping(approval,next);
                    if(jdbc.update("UPDATE marketplace.claim_shipping_obligation SET refund_attempt_id=:next,state='RESERVED',version=version+1 WHERE claim_id=:approval AND seller_order_id=:seller AND refund_attempt_id=:prev AND state='FAILED'",p)!=1) { throw new OrderRejected(409); }
                }
                jdbc.update("""
                        INSERT INTO marketplace.claim_support_refund_retry(id,actor_id,approval_id,prior_attempt,prior_version,attempt_id,purpose,sequence,reason)
                        VALUES(:id,:actor,:approval,:prev,:version,:next,:purpose,:sequence,:reason)
                        """,p);
            }
        }
        return list(id,actor);
    }
    private void exact(String sql,MapSqlParameterSource params) {
        if(jdbc.queryForObject(sql,params,Integer.class)!=1) { throw new OrderRejected(409); }
    }
}
