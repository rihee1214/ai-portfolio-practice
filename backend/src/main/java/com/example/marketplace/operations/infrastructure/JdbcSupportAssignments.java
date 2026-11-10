package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.operations.api.SupportAssignments;
import com.example.marketplace.order.api.OrderRejected;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class JdbcSupportAssignments implements SupportAssignments {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final HistorySessions sessions;
    public JdbcSupportAssignments(NamedParameterJdbcTemplate jdbc, AccessPolicy access, HistorySessions sessions) {
        this.jdbc = jdbc;
        this.access = access;
        this.sessions = sessions;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void register(UUID target,String kind) {
        jdbc.update("INSERT INTO marketplace.operations_support_assignment(target_id,kind) VALUES(:id,:kind)", Map.of("id",target,"kind",kind));
    }
    private boolean current(Actor actor) {
        return actor.member()!=null && sessions.lockReadable(actor.session(),actor.member(),actor.version())
                && access.allowed(actor.member(),AccessArea.SUPPORT);
    }
    @Override @Transactional
    public boolean assigned(UUID target,Actor actor) {
        if (!current(actor)) { return false; }
        var row=jdbc.queryForList("SELECT target_id FROM marketplace.operations_support_assignment WHERE target_id=:id AND operator_id=:member FOR SHARE",
                Map.of("id",target,"member",actor.member()),UUID.class);
        return !row.isEmpty() && current(actor);
    }
    @Override @Transactional
    public List<Entry> queue(Actor actor) {
        if (!current(actor)) { throw new OrderRejected(403); }
        var rows=jdbc.queryForList("SELECT target_id,kind,operator_id,version FROM marketplace.operations_support_assignment ORDER BY target_id",Map.of());
        var queue=new java.util.ArrayList<Entry>();
        var eligible=new java.util.HashMap<UUID,Boolean>();
        for(var row:rows) {
            var previous=(UUID)row.get("operator_id");
            boolean mine=actor.member().equals(previous);
            if(!mine && previous!=null && eligible.computeIfAbsent(previous,id->access.allowed(id,AccessArea.SUPPORT))) { continue; }
            queue.add(new Entry((UUID)row.get("target_id"),(String)row.get("kind"),mine,(Long)row.get("version")));
        }
        return List.copyOf(queue);
    }
    @Override @Transactional
    public Entry assign(UUID target,Actor actor,Change cmd) {
        if (!current(actor)) { throw new OrderRejected(403); }
        if (cmd.key()==null || cmd.operator()==null || cmd.expectedVersion()<0) { throw new OrderRejected(400); }
        var p=new MapSqlParameterSource("id",target).addValue("actor",actor.member()).addValue("next",cmd.operator()).addValue("key",cmd.key());
        var rows=jdbc.queryForList("SELECT kind,operator_id,version FROM marketplace.operations_support_assignment WHERE target_id=:id FOR UPDATE",p);
        if (rows.isEmpty()) { throw new OrderRejected(404); }
        if (!current(actor) || !access.allowed(cmd.operator(),AccessArea.SUPPORT)) { throw new OrderRejected(403); }
        var row=rows.getFirst();
        String kind=(String)row.get("kind");
        long version=(Long)row.get("version");
        var prior=jdbc.queryForList("SELECT next_operator,version FROM marketplace.operations_assignment_event WHERE target_id=:id AND actor_id=:actor AND request_key=:key",p);
        if (!prior.isEmpty()) {
            long saved=(Long)prior.getFirst().get("version");
            if (saved-1!=cmd.expectedVersion() || !cmd.operator().equals(prior.getFirst().get("next_operator"))) { throw new OrderRejected(409); }
            return new Entry(target,kind,true,saved);
        }
        UUID previous=(UUID)row.get("operator_id");
        if (!actor.member().equals(previous)) {
            if(!actor.member().equals(cmd.operator()) || previous!=null && access.allowed(previous,AccessArea.SUPPORT)) {
                throw new OrderRejected(403);
            }
        }
        if (version!=cmd.expectedVersion() || cmd.operator().equals(previous)) { throw new OrderRejected(409); }
        p.addValue("version",version+1).addValue("previous",previous);
        jdbc.update("UPDATE marketplace.operations_support_assignment SET operator_id=:next,version=:version WHERE target_id=:id",p);
        jdbc.update("""
                INSERT INTO marketplace.operations_assignment_event(target_id,version,actor_id,previous_operator,next_operator,request_key)
                VALUES(:id,:version,:actor,:previous,:next,:key)
                """,p);
        return new Entry(target,kind,true,version+1);
    }
}
