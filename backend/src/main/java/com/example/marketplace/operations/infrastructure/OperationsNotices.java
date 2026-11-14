package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.catalog.api.ProductVisibility;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.notification.api.NotificationAccess;
import com.example.marketplace.notification.api.NotificationEvents;
import com.example.marketplace.operations.api.ModerationTargets;
import com.example.marketplace.operations.api.ReportTargets;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.seller.api.Stores;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public class OperationsNotices implements NotificationAccess {
    public record Decision(String kind,boolean hidden,String reason) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationEvents events;
    private final ProductVisibility products;
    private final ModerationTargets reviews;
    private final Stores stores;
    private final SupportCases cases;
    private final HistorySessions sessions;
    public OperationsNotices(NamedParameterJdbcTemplate jdbc,NotificationEvents events,ProductVisibility products,
            ModerationTargets reviews,Stores stores,SupportCases cases,HistorySessions sessions) {
        this.jdbc=jdbc;this.events=events;this.products=products;this.reviews=reviews;this.stores=stores;this.cases=cases;
        this.sessions=sessions;
    }
    public void closed(UUID report,long version) {
        UUID recipient=jdbc.queryForObject("SELECT reporter_id FROM marketplace.operations_report WHERE id=:id",Map.of("id",report),UUID.class);
        UUID cause=UUID.nameUUIDFromBytes(("report-closed:"+report+":"+version).getBytes(StandardCharsets.UTF_8));
        events.record(NotificationEvents.Kind.REPORT_CLOSED,cause,recipient,report);
    }
    private UUID owner(String kind,UUID target,UUID parent,long edition) {
        if(kind.equals("PRODUCT")) { return stores.tradeOwner(products.storeId(target)); }
        return reviews.owner(new ReportTargets.Ref(ReportTargets.Kind.valueOf(kind),target,parent,edition));
    }
    public void moderated(UUID action,String kind,UUID target,UUID parent,long edition,boolean hidden) {
        UUID recipient=owner(kind,target,parent,edition);
        if(recipient!=null) { events.record(hidden?NotificationEvents.Kind.MODERATION_ACTION:NotificationEvents.Kind.MODERATION_RESTORED,
                action,recipient,action); }
    }
    @Override public boolean handles(NotificationEvents.Kind kind) {
        return switch(kind) {
            case CASE_REPLY,REPORT_CLOSED,MODERATION_ACTION,MODERATION_RESTORED -> true;
            default -> false;
        };
    }
    @Override public String destination(NotificationEvents.Kind kind,UUID target,Reader reader) {
        if(kind==NotificationEvents.Kind.CASE_REPLY) {
            try { return cases.noticeDestination(target,reader); }
            catch(OrderRejected denied) {
                if(denied.status()==403 || denied.status()==404) { return null; }
                throw denied;
            }
        }
        if(kind==NotificationEvents.Kind.REPORT_CLOSED) {
            var mine=jdbc.queryForList("SELECT id FROM marketplace.operations_report WHERE id=:id AND reporter_id=:member FOR SHARE",
                    Map.of("id",target,"member",reader.member()),UUID.class);
            return mine.isEmpty()?null:"/account/reports#"+target;
        }
        return action(target,reader)==null?null:"/account/moderation/"+target;
    }
    @org.springframework.transaction.annotation.Transactional
    public Decision action(UUID id,Reader reader) {
        if(!sessions.lockMemberThenSession(reader.session(),reader.member(),reader.version())) { throw new OrderRejected(404); }
        var rows=jdbc.queryForList("""
                SELECT kind,target_id,parent_id,edition,after_hidden,public_reason FROM marketplace.operations_moderation_action
                WHERE id=:id FOR SHARE
                """,Map.of("id",id));
        if(rows.isEmpty()) { return null; }
        var row=rows.getFirst();
        String kind=(String)row.get("kind");
        UUID target=(UUID)row.get("target_id");
        if(!reader.member().equals(owner(kind,target,(UUID)row.get("parent_id"),((Number)row.get("edition")).longValue()))) { return null; }
        return new Decision(kind,(Boolean)row.get("after_hidden"),(String)row.get("public_reason"));
    }
}
