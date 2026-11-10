package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.AccountStateOperations;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.identity.domain.SignupRules;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class ManagedAccounts {
    public record Operator(UUID member,String session,long version) {}
    public record Search(String term,String state,UUID after) {}
    public record Member(UUID id,String state,long version,boolean administrator) {}
    public record Summary(UUID id,String state) {}
    public record Page(List<Summary> members,UUID next) {}
    public record Event(UUID id,UUID actor,String before,String after,String reason,java.time.Instant at) {}
    public record Detail(Member member,List<Event> events) {}
    private static final String MEMBERS="""
            SELECT u.id,u.state,u.auth_version,EXISTS(SELECT 1 FROM marketplace.role_assignment r WHERE r.user_id=u.id AND r.role='ADMIN')
            FROM marketplace.identity_user u
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final HistorySessions sessions;
    private final AccountStateOperations states;
    public ManagedAccounts(NamedParameterJdbcTemplate jdbc,AccessPolicy access,HistorySessions sessions,AccountStateOperations states){
        this.jdbc=jdbc;this.access=access;this.sessions=sessions;this.states=states;
    }
    private void requireCurrent(Operator actor){
        if(!sessions.lockReadable(actor.session(),actor.member(),actor.version())){throw new AccessRejected();}
        access.require(actor.member(),AccessArea.ACCOUNTS);
    }
    private Member member(java.sql.ResultSet r,int index)throws java.sql.SQLException{
        return new Member(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),r.getBoolean(4));
    }
    @Transactional
    public Page search(Operator actor,Search query){
        requireCurrent(actor);
        if(query==null){throw new AccountException("INVALID_SEARCH");}
        String term=query.term()==null?"":query.term().strip();
        var sql=new StringBuilder("SELECT u.id,u.state FROM marketplace.identity_user u WHERE true");
        var params=new MapSqlParameterSource();
        if(!term.isEmpty()){
            try {
                if(term.contains("@")){
                    sql.append(" AND u.email=:email");params.addValue("email",SignupRules.email(term));
                }else{
                    UUID id=UUID.fromString(term);
                    if(!id.toString().equalsIgnoreCase(term)){throw new IllegalArgumentException();}
                    sql.append(" AND u.id=:id");params.addValue("id",id);
                }
            }catch(IllegalArgumentException invalid){throw new AccountException("INVALID_SEARCH");}
        }
        if(query.state()!=null){
            if(!Set.of("ACTIVE","SUSPENDED","PENDING_EMAIL","WITHDRAWN").contains(query.state())){throw new AccountException("INVALID_SEARCH");}
            sql.append(" AND u.state=:state");params.addValue("state",query.state());
        }
        if(query.after()!=null){sql.append(" AND u.id>:after");params.addValue("after",query.after());}
        var rows=jdbc.query(sql.append(" ORDER BY u.id LIMIT 21").toString(),params,
                (r,n)->new Summary(r.getObject(1,UUID.class),r.getString(2)));
        requireCurrent(actor);
        return new Page(rows.stream().limit(20).toList(),rows.size()>20?rows.get(19).id():null);
    }
    @Transactional
    public Detail detail(Operator actor,UUID target){
        requireCurrent(actor);
        var params=Map.of("id",target);
        var members=jdbc.query(MEMBERS+" WHERE u.id=:id FOR SHARE OF u",params,this::member);
        requireCurrent(actor);
        if(members.isEmpty()){throw new AccountException("ACCOUNT_NOT_FOUND");}
        var events=jdbc.query("""
                SELECT id,actor_id,previous_state,next_state,reason,occurred_at FROM marketplace.account_state_audit
                WHERE target_id=:id ORDER BY occurred_at DESC,id DESC LIMIT 20
                """,params,(r,n)->new Event(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant()));
        requireCurrent(actor);
        return new Detail(members.getFirst(),events);
    }
    @Transactional
    public void change(Operator actor,UUID target,String reason,Long expectedVersion,boolean suspend){
        requireCurrent(actor);
        try{
            if(suspend){states.suspend(actor.member(),target,reason,expectedVersion);}
            else{states.restore(actor.member(),target,reason,expectedVersion);}
        }finally{
            // Keep the account transition, audit and session revocations conditional on the operator surviving any row wait.
            requireCurrent(actor);
        }
    }
}
