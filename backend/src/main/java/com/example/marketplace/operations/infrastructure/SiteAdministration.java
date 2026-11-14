package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.operations.infrastructure.SitePublications.Actor;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.JobInspection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class SiteAdministration {
    public record Info(long version,String siteName,String supportGuide) { }
    public record Change(UUID key,long expectedVersion,String siteName,String supportGuide,String reason) { }
    public record Revision(Info info,String reason,UUID actor,Instant at) { }
    private final JdbcTemplate db;
    private final SitePublications posts;
    private final JobInspection jobs;
    public SiteAdministration(NamedParameterJdbcTemplate jdbc,SitePublications posts,JobInspection jobs) {
        db=jdbc.getJdbcTemplate();this.posts=posts;this.jobs=jobs;
    }
    public Info current() {
        return db.queryForObject("SELECT version,site_name,support_guide FROM marketplace.site_info_revision ORDER BY version DESC LIMIT 1",
                (r,n)->new Info(r.getLong(1),r.getString(2),r.getString(3)));
    }
    @Transactional public List<Revision> history(Actor a,long before) {
        posts.authorize(a,AccessArea.SYSTEM);
        return db.query("SELECT * FROM marketplace.site_info_revision WHERE version<? ORDER BY version DESC LIMIT 50",
                (r,n)->new Revision(new Info(r.getLong("version"),r.getString("site_name"),r.getString("support_guide")),r.getString("reason"),
                        r.getObject("actor_id",UUID.class),r.getTimestamp("changed_at").toInstant()),before);
    }
    @Transactional public Info change(Actor a,Change c) {
        posts.authorize(a,AccessArea.SYSTEM);
        if(c==null||c.key()==null||c.expectedVersion()<1||!SitePublications.text(c.siteName(),80)||!SitePublications.text(c.supportGuide(),1000)
                ||!SitePublications.text(c.reason(),500)) { throw new OrderRejected(400); }
        db.query("SELECT pg_advisory_xact_lock(hashtextextended('site-info',0))",(r,n)->r.getObject(1));
        posts.authorize(a,AccessArea.SYSTEM);
        var prior=db.query("SELECT version,site_name,support_guide,reason FROM marketplace.site_info_revision WHERE actor_id=? AND request_key=?",
                (r,n)->{if(r.getLong(1)-1!=c.expectedVersion()||!r.getString(2).equals(c.siteName().strip())
                        ||!r.getString(3).equals(c.supportGuide().strip())||!r.getString(4).equals(c.reason().strip())) { throw new OrderRejected(409); }
                    return new Info(r.getLong(1),r.getString(2),r.getString(3));},a.member(),c.key());
        if(!prior.isEmpty()) { return prior.getFirst(); }
        var current=current();
        if(current.version()!=c.expectedVersion()) { throw new OrderRejected(409); }
        var next=new Info(current.version()+1,c.siteName().strip(),c.supportGuide().strip());
        db.update("INSERT INTO marketplace.site_info_revision(version,site_name,support_guide,reason,actor_id,request_key) VALUES(?,?,?,?,?,?)",
                next.version(),next.siteName(),next.supportGuide(),c.reason().strip(),a.member(),c.key());
        return next;
    }
    @Transactional public List<JobInspection.Summary> jobs(Actor a,String status,UUID after) {
        posts.authorize(a,AccessArea.SYSTEM);
        if(!Set.of("READY","RUNNING","FAILED","SUCCEEDED").contains(status)) { throw new OrderRejected(400); }
        return jobs.list(status,after);
    }
    @Transactional public JobInspection.Summary job(Actor a,UUID id) {
        posts.authorize(a,AccessArea.SYSTEM);var job=jobs.find(id);
        if(job==null) { throw new OrderRejected(404); }
        return job;
    }
}
