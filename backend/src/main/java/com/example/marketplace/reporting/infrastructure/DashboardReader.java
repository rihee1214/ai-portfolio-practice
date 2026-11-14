package com.example.marketplace.reporting.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.reporting.api.Dashboards;
import com.example.marketplace.reporting.domain.DashboardPeriod;
import com.example.marketplace.seller.api.Stores;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class DashboardReader implements Dashboards {
    private final JdbcTemplate jdbc;
    private final Jobs jobs;
    private final DashboardRuns runs;
    private final SellerMembership membership;
    private final HistorySessions sessions;
    private final Stores stores;
    private final AccessPolicy access;
    private final Runnable afterHead;
    @org.springframework.beans.factory.annotation.Autowired
    public DashboardReader(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,Jobs jobs,DashboardRuns runs,SellerMembership membership,
                           HistorySessions sessions,Stores stores,AccessPolicy access) {
        this(jdbc,jobs,runs,membership,sessions,stores,access,()->{ });
    }
    public DashboardReader(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,Jobs jobs,DashboardRuns runs,SellerMembership membership,
                           HistorySessions sessions,Stores stores,AccessPolicy access,Runnable afterHead) {
        this.jdbc=jdbc.getJdbcTemplate();this.jobs=jobs;this.runs=runs;this.membership=membership;
        this.sessions=sessions;this.stores=stores;this.access=access;this.afterHead=afterHead;
    }
    private Stores.Store authorize(Actor actor,AccessArea area) {
        if(actor.member()==null) { throw new OrderRejected(401); }
        if(area==AccessArea.SELLER) {
            try { membership.lockSeller(actor.member()); }
            catch(AccountException denied) { throw new OrderRejected(403); }
        }
        if(!sessions.lockReadable(actor.session(),actor.member(),actor.version())||!access.allowed(actor.member(),area)) {
            throw new OrderRejected(403);
        }
        if(area!=AccessArea.SELLER) { return null; }
        var own=stores.own(actor.member());
        if(own==null) { throw new OrderRejected(404); }
        stores.requireCapability(actor.member(),Stores.Capability.EXISTING_TRADES);
        return own;
    }
    private static AccessArea area(String raw) {
        try {
            var result=AccessArea.valueOf(raw.toUpperCase(Locale.ROOT));
            if(result.permissionRequired()||result==AccessArea.SELLER) { return result; }
        } catch(IllegalArgumentException ignored) { }
        throw new OrderRejected(404);
    }
    @Override @Transactional public View read(Actor actor,String raw,LocalDate from,LocalDate to) {
        var area=area(raw);var store=authorize(actor,area);
        var now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        DashboardPeriod period;
        try { period=DashboardPeriod.resolve(from,to,now.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()); }
        catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
        var row=jdbc.queryForMap("""
                SELECT h.snapshot_id,s.source_as_of,r.completed_at,
                  (SELECT job_id FROM marketplace.dashboard_run ORDER BY id DESC LIMIT 1) latest_job
                FROM marketplace.dashboard_head h LEFT JOIN marketplace.dashboard_snapshot s ON s.id=h.snapshot_id
                LEFT JOIN marketplace.dashboard_run r ON r.id=h.run_id WHERE singleton
                """);
        var generation=(UUID)row.get("snapshot_id");
        afterHead.run();
        var source=(Timestamp)row.get("source_as_of");var completed=(Timestamp)row.get("completed_at");
        var latest=(UUID)row.get("latest_job");
        var latestJob=latest==null?null:jobs.find(latest).orElseThrow();
        boolean failed=latestJob!=null&&("FAILED".equals(latestJob.status())||"READY".equals(latestJob.status())&&latestJob.lastError()!=null);
        String status=failed?"FAILED":generation==null?"NOT_READY":
                Duration.between(source.toInstant(),now).compareTo(Duration.ofMinutes(10))>0?"DELAYED":"READY";
        var metrics=new ArrayList<Metric>();
        if(generation!=null) {
            var values=jdbc.queryForList("""
                    SELECT metric,sum(value)::text amount FROM marketplace.dashboard_metric
                    WHERE snapshot_id=? AND ((scope=? AND scope_id IS NOT DISTINCT FROM ?::uuid)
                      OR (scope='ASSIGNEE' AND scope_id=?::uuid AND ?='SUPPORT'))
                      AND (day IS NULL OR day BETWEEN ? AND ?) GROUP BY metric
                    """,generation,store==null?"GLOBAL":"STORE",store==null?null:store.id(),actor.member(),area.name(),period.from(),period.to());
            for(var definition:DashboardMetric.values()) {
                if(definition.visible(area)) {
                    String value=values.stream().filter(v->definition.name().equals(v.get("metric")))
                            .map(v->(String)v.get("amount")).findFirst().orElse(null);
                    if(value==null && definition==DashboardMetric.REPLACEMENT_OLDEST_SECONDS) { continue; }
                    if(value==null) { value="0"; }
                    metrics.add(new Metric(definition.name(),definition.basis,definition.unit,value));
                }
            }
        }
        var finalStore=authorize(actor,area);
        if(store!=null&&!store.id().equals(finalStore.id())) { throw new OrderRejected(403); }
        return new View(area.name(),store==null?null:store.name(),period.from(),period.to(),status,generation,1,
                source==null?null:source.toInstant(),completed==null?null:completed.toInstant(),List.copyOf(metrics));
    }
    @Override @Transactional public long refresh(Actor actor,UUID key,String reason) {
        authorize(actor,AccessArea.SYSTEM);
        if(key==null||reason==null||reason.isBlank()||reason.length()>300) { throw new OrderRejected(400); }
        long id=runs.schedule(actor.member(),key,reason.strip());
        authorize(actor,AccessArea.SYSTEM);
        return id;
    }
}
