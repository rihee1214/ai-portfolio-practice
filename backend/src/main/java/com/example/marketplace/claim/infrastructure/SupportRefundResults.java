package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.domain.AftercareShippingRules;
import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class SupportRefundResults {
    private final NamedParameterJdbcTemplate jdbc;
    private final FinancialIntents intents;
    private final AftercareSettlements orders;
    private final Refunds refunds;
    private final Jobs jobs;
    private final JobLeases leases;
    private final Outbox outbox;
    public SupportRefundResults(NamedParameterJdbcTemplate jdbc,FinancialIntents intents,AftercareSettlements orders,
            Refunds refunds,Jobs jobs,JobLeases leases,Outbox outbox) {
        this.jdbc=jdbc;this.intents=intents;this.orders=orders;this.refunds=refunds;this.jobs=jobs;this.leases=leases;this.outbox=outbox;
    }
    @Transactional
    public JobHandler.Result record(Jobs.Job job,UUID intentId,UUID attempt,long deliveredVersion) {
        var intent=intents.lock(intentId);
        var view=orders.lockAftercare(intent.sellerOrderId());
        var actual=refunds.get(attempt);
        var stored=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));
        if(!stored.command().equals(job.command())||stored.attempts()!=job.attempts()||!intent.kind().equals("SUPPORT")
                ||!actual.claimId().equals(intentId)||!actual.orderId().equals(intent.orderId())||!actual.sellerOrderId().equals(intent.sellerOrderId())
                ||actual.version()<deliveredVersion) { throw new OrderRejected(409); }
        leases.requireOwned(job.id(),job.ownerToken());
        if(!List.of("FAILED","UNKNOWN","SUCCEEDED").contains(actual.state())) { return JobHandler.Result.COMPLETE; }
        var p=new MapSqlParameterSource("attempt",attempt).addValue("version",actual.version()).addValue("approval",intentId)
                .addValue("purpose",actual.purpose()).addValue("state",actual.state()).addValue("amount",actual.amount())
                .addValue("reference",actual.resultReference()).addValue("at",actual.resultAt()==null?null:java.sql.Timestamp.from(actual.resultAt()));
        jdbc.update("""
                INSERT INTO marketplace.claim_support_refund_result(attempt_id,result_version,approval_id,purpose,amount,state,reference,result_at)
                VALUES(:attempt,:version,:approval,:purpose,:amount,:state,:reference,:at) ON CONFLICT(attempt_id,result_version) DO NOTHING
                """,p);
        if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_support_refund_retry WHERE prior_attempt=:attempt",p,Integer.class)>0) {
            leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;
        }
        if(actual.purpose().equals("SHIPPING")) {
            p.addValue("seller",intent.sellerOrderId());
            int count=jdbc.update("""
                    UPDATE marketplace.claim_shipping_obligation SET state=:state,version=version+1
                    WHERE seller_order_id=:seller AND claim_id=:approval AND refund_attempt_id=:attempt AND amount=:amount AND state<>:state
                    """,p);
            if(count==0&&jdbc.queryForObject("""
                    SELECT count(*) FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:seller AND claim_id=:approval
                    AND refund_attempt_id=:attempt AND amount=:amount AND state=:state
                    """,p,Integer.class)!=1) { throw new OrderRejected(409); }
        }else if(actual.state().equals("SUCCEEDED")&&view.originalShipping()>0) {
            var evidence=ReturnAftercareEvidence.units(jdbc,view);
            if(AftercareShippingRules.evaluate(view.originalShipping(),evidence).state()==AftercareShippingRules.State.REFUND_REQUIRED) {
                SellerShippingReservations.reserve(jdbc,intents,refunds,view,evidence,null);
            }
        }
        if(actual.state().equals("SUCCEEDED")) {
            String key="seller-aftercare:"+intent.sellerOrderId()+":support-result:"+attempt+":"+actual.version();
            outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new Jobs.Command(SellerAftercareWorker.TYPE,key,intent.sellerOrderId().toString(),"SUPPORT_RESULT|"+attempt+"|"+actual.version(),actual.resultAt())));
        }
        leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;
    }
}
