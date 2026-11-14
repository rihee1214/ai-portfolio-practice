package com.example.marketplace.notification.infrastructure;

import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.notification.api.NotificationAccess;
import com.example.marketplace.notification.api.NotificationEvents.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("db")
public class MemberInbox {
    private record Row(UUID id,Kind kind,UUID target,Instant createdAt,Instant readAt) { }
    public record Entry(UUID id,Kind kind,String destination,Instant createdAt,Instant readAt) { }
    public record Page(List<Entry> items,UUID next) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final HistorySessions sessions;
    private final List<NotificationAccess> policies;
    private static final String SELECT="""
            SELECT e.id,e.kind,e.target_id,e.created_at,i.read_at FROM marketplace.notification_event e
            JOIN marketplace.notification_inbox i ON i.event_id=e.id WHERE e.recipient_id=:member
            """;

    public MemberInbox(NamedParameterJdbcTemplate jdbc,HistorySessions sessions,List<NotificationAccess> policies) {
        this.jdbc=jdbc;
        this.sessions=sessions;
        this.policies=List.copyOf(policies);
    }
    private void requireMember(NotificationAccess.Reader reader) {
        if(reader.member()==null || !sessions.lockMemberThenSession(reader.session(),reader.member(),reader.version())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
    private Entry visible(Row row,NotificationAccess.Reader reader) {
        for(var policy:policies) {
            if(policy.handles(row.kind())) {
                String path=policy.destination(row.kind(),row.target(),reader);
                return path==null?null:new Entry(row.id(),row.kind(),path,row.createdAt(),row.readAt());
            }
        }
        return null;
    }
    private List<Row> query(String suffix,Map<String,?> parameters) {
        return jdbc.query(SELECT+suffix,parameters,(r,n)->{
            var read=r.getTimestamp("read_at");
            return new Row(r.getObject("id",UUID.class),Kind.valueOf(r.getString("kind")),r.getObject("target_id",UUID.class),
                    r.getTimestamp("created_at").toInstant(),read==null?null:read.toInstant());
        });
    }
    @Transactional
    public Page list(NotificationAccess.Reader reader,UUID before) {
        requireMember(reader);
        var items=new java.util.ArrayList<Entry>(30);
        UUID cursor=before;
        UUID next=null;
        boolean finished=false;
        while(!finished) {
            String filter=cursor==null?"":" AND (e.created_at,e.id)<(SELECT created_at,id FROM marketplace.notification_event WHERE id=:before AND recipient_id=:member)";
            var rows=query(filter+" ORDER BY e.created_at DESC,e.id DESC LIMIT 31",cursor==null?Map.of("member",reader.member()):
                    Map.of("member",reader.member(),"before",cursor));
            finished=rows.size()<31;
            for(var row:rows) {
                cursor=row.id();
                var entry=visible(row,reader);
                if(entry!=null) {
                    if(items.size()==30) {
                        next=items.getLast().id();
                        finished=true;
                        break;
                    }
                    items.add(entry);
                }
            }
        }
        requireMember(reader);
        return new Page(List.copyOf(items),next);
    }
    @Transactional
    public Entry read(UUID id,NotificationAccess.Reader reader) {
        requireMember(reader);
        var params=Map.of("id",id,"member",reader.member());
        var entry=query(" AND e.id=:id",params).stream().map(row->visible(row,reader)).filter(java.util.Objects::nonNull).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireMember(reader);
        if(entry.readAt()!=null) { return entry; }
        var readAt=jdbc.queryForObject("UPDATE marketplace.notification_inbox SET read_at=COALESCE(read_at,clock_timestamp()) WHERE event_id=:id RETURNING read_at",
                params,java.sql.Timestamp.class);
        return new Entry(entry.id(),entry.kind(),entry.destination(),entry.createdAt(),readAt.toInstant());
    }
}
