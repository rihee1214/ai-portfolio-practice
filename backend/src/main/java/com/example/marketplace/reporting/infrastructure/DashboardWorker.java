package com.example.marketplace.reporting.infrastructure;

import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component @Profile("db")
public class DashboardWorker implements JobHandler {
    public interface Checkpoint { void reached(String stage); }
    static final String TYPE="DASHBOARD_REFRESH.v1";
    private final JdbcTemplate jdbc;
    private final DashboardTradeFacts trade;
    private final DashboardBalances balances;
    private final JobLeases leases;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate publish;
    private final Checkpoint checkpoint;
    @org.springframework.beans.factory.annotation.Autowired
    public DashboardWorker(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,DashboardTradeFacts trade,DashboardBalances balances,JobLeases leases,PlatformTransactionManager manager) {
        this(jdbc,trade,balances,leases,manager,stage->{ });
    }
    public DashboardWorker(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,DashboardTradeFacts trade,DashboardBalances balances,
                           JobLeases leases,PlatformTransactionManager manager,Checkpoint checkpoint) {
        this.jdbc=jdbc.getJdbcTemplate();this.trade=trade;this.balances=balances;this.leases=leases;
        this.checkpoint=checkpoint;
        snapshot=new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        publish=new TransactionTemplate(manager);
        publish.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        publish.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public String type() { return TYPE; }
    @Override public Result handle(Jobs.Job job) {
        try { return refresh(job); }
        catch(RuntimeException problem) {
            try {
                publish.executeWithoutResult(tx->{
                    leases.requireOwned(job.id(),job.ownerToken());
                    jdbc.update("""
                            INSERT INTO marketplace.dashboard_failed_attempt(job_id,attempt,owner_token)
                            VALUES(?,?,?) ON CONFLICT(job_id,attempt) DO NOTHING
                            """,job.id(),job.attempts(),job.ownerToken());
                });
            }catch(RuntimeException noReceipt) { problem.addSuppressed(noReceipt); }
            throw problem;
        }
    }
    private Result refresh(Jobs.Job job) {
        var generation=snapshot.execute(tx->stage(job));
        checkpoint.reached("STAGE_COMMITTED");
        publish.executeWithoutResult(tx->{
            var head=jdbc.queryForObject("SELECT coalesce(run_id,0) FROM marketplace.dashboard_head WHERE singleton FOR UPDATE",Long.class);
            var run=jdbc.queryForMap("SELECT id,state FROM marketplace.dashboard_run WHERE job_id=? FOR UPDATE",job.id());
            long sequence=((Number)run.get("id")).longValue();
            if("PENDING".equals(run.get("state"))) {
                if(generation==null) { throw new IllegalStateException("DASHBOARD_STAGE_REQUIRED"); }
                if(sequence>head) {
                    int changed=jdbc.update("""
                            UPDATE marketplace.dashboard_head SET run_id=?,snapshot_id=? WHERE singleton AND EXISTS
                              (SELECT 1 FROM marketplace.dashboard_snapshot WHERE id=? AND run_id=? AND job_id=? AND attempt=? AND token=?)
                            """,sequence,generation,generation,sequence,job.id(),job.attempts(),job.ownerToken());
                    if(changed!=1) { throw new IllegalStateException("DASHBOARD_STAGE_BINDING"); }
                }
                jdbc.update("UPDATE marketplace.dashboard_run SET state=?,completed_at=clock_timestamp() WHERE id=?",
                        sequence>head?"PUBLISHED":"SUPERSEDED",sequence);
            }
            // Fresh READ COMMITTED ownership at effect commit. Heartbeats never contend with RR computation.
            checkpoint.reached("BEFORE_FENCE");
            leases.requireOwned(job.id(),job.ownerToken());
        });
        return Result.COMPLETE;
    }
    private UUID stage(Jobs.Job job) {
        var run=jdbc.queryForMap("SELECT id,state FROM marketplace.dashboard_run WHERE job_id=?",job.id());
        if(!"PENDING".equals(run.get("state"))) { return null; }
        var existing=jdbc.queryForList("SELECT id FROM marketplace.dashboard_snapshot WHERE job_id=? AND attempt=? AND token=?",
                UUID.class,job.id(),job.attempts(),job.ownerToken());
        if(!existing.isEmpty()) { return existing.getFirst(); }
        var generation=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO marketplace.dashboard_snapshot(id,run_id,job_id,attempt,token,definition_version,source_as_of,source_snapshot)
                VALUES(?,?,?,?,?,1,transaction_timestamp(),pg_current_snapshot())
                """,generation,run.get("id"),job.id(),job.attempts(),job.ownerToken());
        var facts=trade.collect();
        checkpoint.reached("AFTER_TRADE");
        balances.collect(facts);
        facts.put(new DashboardTradeFacts.Key("GLOBAL",null,null,"REFRESH_FAILURES"),
                java.math.BigInteger.valueOf(jdbc.queryForObject("SELECT count(*) FROM marketplace.dashboard_failed_attempt",Long.class)));
        jdbc.batchUpdate("""
                INSERT INTO marketplace.dashboard_metric(snapshot_id,scope,scope_id,day,metric,value) VALUES(?,?,?,?,?,?)
                """,facts.entrySet(),512,(p,e)->{
            var k=e.getKey();p.setObject(1,generation);p.setString(2,k.scope());p.setObject(3,k.owner());
            p.setObject(4,k.day());p.setString(5,k.metric());p.setBigDecimal(6,new java.math.BigDecimal(e.getValue()));
        });
        return generation;
    }
}
