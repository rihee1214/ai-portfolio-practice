package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.guest.api.GuestAccessSessions;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.notification.api.NotificationEvents;
import com.example.marketplace.operations.api.SupportAssignments;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class SupportCases implements com.example.marketplace.operations.api.SupportOrderCases {
    public enum Kind { SUPPORT, ORDER, LOST_ACCESS }
    public enum Reader { CUSTOMER, SELLER, OPERATOR }
    public record Open(UUID key,String body,String orderNumber,UUID sellerOrderId) { }
    public record Reply(UUID key,long expectedVersion,String body) { }
    public record Receipt(UUID id,long version) { }
    public record Message(UUID id,long version,String sender,String body,boolean mine) { }
    public record Detail(UUID id,Kind kind,String orderNumber,UUID sellerOrderId,long version,List<Message> messages) { }
    public record Summary(UUID id,Kind kind,long version) { }
    public record Owner(UUID member,UUID guest) { }
    private record Ticket(UUID id,Kind kind,UUID member,UUID guest,String number,UUID seller,long version) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final HistorySessions sessions;
    private final GuestAccessSessions guests;
    private final OrderQueries orders;
    private final SupportAssignments assignments;
    private final NotificationEvents notifications;
    private final com.example.marketplace.identity.api.AccessPolicy access;
    public SupportCases(NamedParameterJdbcTemplate jdbc,HistorySessions sessions,GuestAccessSessions guests,
            OrderQueries orders,SupportAssignments assignments,NotificationEvents notifications,
            com.example.marketplace.identity.api.AccessPolicy access) {
        this.notifications=notifications;this.access=access;
        this.jdbc=jdbc;this.sessions=sessions;this.guests=guests;this.orders=orders;this.assignments=assignments;
    }
    private Owner owner(OrderQueries.Authority a) {
        if (a.member()!=null) {
            if (!sessions.lockReadable(a.browser(),a.member(),a.version())) { throw new OrderRejected(404); }
            return new Owner(a.member(),null);
        }
        if (a.guestToken()==null) { throw new OrderRejected(404); }
        return new Owner(null,guests.requireCurrent(a.guestToken()));
    }
    private void requireOrder(String number,UUID seller,OrderQueries.Authority actor) {
        orders.requireOwned(number,actor);
        if (orders.detail(number,actor).stores().stream().noneMatch(store->store.sellerOrderId().equals(seller))) {
            throw new OrderRejected(404);
        }
    }
    private String body(String raw) {
        if(raw==null || raw.isBlank()) { throw new OrderRejected(400); }
        String text=raw.strip();
        if(text.codePointCount(0,text.length())>2000) { throw new OrderRejected(400); }
        return text;
    }
    @Transactional
    public Receipt open(Kind kind,Open cmd,OrderQueries.Authority actor) {
        if(cmd.key()==null || (kind==Kind.ORDER ? cmd.orderNumber()==null || cmd.sellerOrderId()==null :
                cmd.orderNumber()!=null || cmd.sellerOrderId()!=null)) { throw new OrderRejected(400); }
        Owner user=kind==Kind.LOST_ACCESS ? new Owner(null,null) : owner(actor);
        if(kind==Kind.ORDER) { requireOrder(cmd.orderNumber(),cmd.sellerOrderId(),actor); }
        var p=new MapSqlParameterSource("id",UUID.randomUUID()).addValue("kind",kind.name()).addValue("member",user.member())
                .addValue("guest",user.guest()).addValue("number",cmd.orderNumber()).addValue("seller",cmd.sellerOrderId())
                .addValue("key",cmd.key()).addValue("body",body(cmd.body()));
        var added=jdbc.queryForList("""
                INSERT INTO marketplace.operations_support_case(id,kind,member_id,guest_id,order_number,seller_order_id,request_key)
                VALUES(:id,:kind,:member,:guest,:number,:seller,:key) ON CONFLICT DO NOTHING RETURNING id
                """,p,UUID.class);
        if (kind!=Kind.LOST_ACCESS) { owner(actor); }
        if (!added.isEmpty()) {
            UUID id=added.getFirst();
            jdbc.update("""
                    INSERT INTO marketplace.operations_case_message(id,case_id,version,sender,member_id,guest_id,request_key,body)
                    VALUES(:id,:id,1,'CUSTOMER',:member,:guest,:key,:body)
                    """,p);
            assignments.register(id,kind.name());
            return new Receipt(id,1);
        }
        if(kind==Kind.LOST_ACCESS) { return new Receipt(null,1); }
        var same=jdbc.queryForList("""
                SELECT c.id FROM marketplace.operations_support_case c JOIN marketplace.operations_case_message m
                  ON m.case_id=c.id AND m.version=1
                WHERE c.request_key=:key AND c.kind=:kind AND c.member_id IS NOT DISTINCT FROM CAST(:member AS uuid)
                AND c.guest_id IS NOT DISTINCT FROM CAST(:guest AS uuid) AND m.body=:body
                AND c.order_number IS NOT DISTINCT FROM CAST(:number AS text)
                AND c.seller_order_id IS NOT DISTINCT FROM CAST(:seller AS uuid)
                """,p,UUID.class);
        if(same.isEmpty()) { throw new OrderRejected(409); }
        return new Receipt(same.getFirst(),1);
    }
    private Ticket ticket(UUID id,boolean write) {
        var rows=jdbc.query("""
                SELECT id,kind,member_id,guest_id,order_number,seller_order_id,version FROM marketplace.operations_support_case
                WHERE id=:id
                """+(write?" FOR UPDATE":" FOR SHARE"),Map.of("id",id),(r,n)->new Ticket(r.getObject(1,UUID.class),
                Kind.valueOf(r.getString(2)),r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getString(5),r.getObject(6,UUID.class),r.getLong(7)));
        if(rows.isEmpty()) { throw new OrderRejected(404); }
        return rows.getFirst();
    }
    @Override @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public OrderCase locateAssigned(UUID caseId,SupportAssignments.Actor actor) {
        if(actor.member()==null||!sessions.lockReadable(actor.session(),actor.member(),actor.version())) { throw new OrderRejected(404); }
        var rows=jdbc.query("""
                SELECT c.order_number,c.seller_order_id,c.version FROM marketplace.operations_support_case c
                JOIN marketplace.operations_support_assignment a ON a.target_id=c.id
                WHERE c.id=:id AND c.kind='ORDER' AND a.operator_id=:member
                """,Map.of("id",caseId,"member",actor.member()),(r,n)->new OrderCase(r.getString(1),r.getObject(2,UUID.class),r.getLong(3)));
        if(rows.size()!=1) { throw new OrderRejected(404); }return rows.getFirst();
    }
    @Override @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public OrderCase lockAssigned(UUID caseId,SupportAssignments.Actor actor) {
        var ticket=ticket(caseId,false);
        if(ticket.kind()!=Kind.ORDER||!assignments.assigned(caseId,actor)) { throw new OrderRejected(404); }
        return new OrderCase(ticket.number(),ticket.seller(),ticket.version());
    }
    private Owner authorize(Reader reader,Ticket t,OrderQueries.Authority a) {
        if(reader==Reader.OPERATOR) {
            if(!assignments.assigned(t.id(),new SupportAssignments.Actor(a.member(),a.browser(),a.version()))) { throw new OrderRejected(404); }
            return new Owner(a.member(),null);
        }
        if(reader==Reader.SELLER) {
            if(a.member()==null || t.kind()!=Kind.ORDER || !orders.lockSellerOrder(t.seller(),a).orderNumber().equals(t.number())) { throw new OrderRejected(404); }
            return new Owner(a.member(),null);
        }
        var user=owner(a);
        if(t.kind()==Kind.LOST_ACCESS || !java.util.Objects.equals(t.member(),user.member()) || !java.util.Objects.equals(t.guest(),user.guest())) {
            throw new OrderRejected(404);
        }
        if(t.kind()==Kind.ORDER) { requireOrder(t.number(),t.seller(),a); }
        return user;
    }
    @Transactional
    public List<Summary> list(Reader reader,OrderQueries.Authority a,String number,UUID sellerOrder) {
        if(reader==Reader.OPERATOR) { throw new OrderRejected(400); }
        var p=new MapSqlParameterSource();
        String filter;
        if(reader==Reader.SELLER){
            if(a.member()==null || sellerOrder==null) { throw new OrderRejected(404); }
            orders.lockSellerOrder(sellerOrder,a);
            filter="seller_order_id=:seller"; p.addValue("seller",sellerOrder);
        }else{
            var customer=owner(a);
            filter=customer.member()!=null?"member_id=:owner":"guest_id=:owner";
            p.addValue("owner",customer.member()!=null?customer.member():customer.guest());
            if(number!=null) {
                requireOrder(number,sellerOrder,a);filter+=" AND order_number=:number AND seller_order_id=:seller";
                p.addValue("number",number).addValue("seller",sellerOrder);
            }else{filter+=" AND kind='SUPPORT'";}
        }
        return jdbc.query("SELECT id,kind,version FROM marketplace.operations_support_case WHERE "+filter+" ORDER BY id",p,
                (r,n)->new Summary(r.getObject(1,UUID.class),Kind.valueOf(r.getString(2)),r.getLong(3)));
    }
    @Transactional
    public Detail detail(UUID id,Reader reader,OrderQueries.Authority actor) {
        var t=ticket(id,false);
        var owner=authorize(reader,t,actor);
        var messages=jdbc.query("SELECT id,version,sender,body,member_id,guest_id FROM marketplace.operations_case_message WHERE case_id=:id ORDER BY version",
                Map.of("id",id),(r,n)->new Message(r.getObject(1,UUID.class),r.getLong(2),r.getString(3),r.getString(4),
                        owner.member()!=null ? owner.member().equals(r.getObject(5,UUID.class)) :
                                owner.guest()!=null && owner.guest().equals(r.getObject(6,UUID.class))));
        return new Detail(id,t.kind(),t.number(),t.seller(),t.version(),messages);
    }
    @Transactional
    public Receipt reply(UUID id,Reader reader,OrderQueries.Authority actor,Reply cmd) {
        var t=ticket(id,true);var user=authorize(reader,t,actor);
        if(cmd.key()==null || cmd.expectedVersion()<1) { throw new OrderRejected(400); }
        String value=body(cmd.body());
        var p=new MapSqlParameterSource("id",id).addValue("member",user.member()).addValue("guest",user.guest())
                .addValue("key",cmd.key()).addValue("sender",reader.name()).addValue("body",value).addValue("version",cmd.expectedVersion()+1);
        var prior=jdbc.queryForList("""
                SELECT id,version,sender,body FROM marketplace.operations_case_message WHERE case_id=:id AND request_key=:key
                AND member_id IS NOT DISTINCT FROM CAST(:member AS uuid) AND guest_id IS NOT DISTINCT FROM CAST(:guest AS uuid)
                """,p);
        if(!prior.isEmpty()){
            var row=prior.getFirst();
            if(!row.get("body").equals(value) || !reader.name().equals(row.get("sender")) || (Long)row.get("version")!=cmd.expectedVersion()+1) { throw new OrderRejected(409); }
            return new Receipt((UUID)row.get("id"),(Long)row.get("version"));
        }
        if(t.version()!=cmd.expectedVersion()) { throw new OrderRejected(409); }
        UUID message=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO marketplace.operations_case_message(id,case_id,version,sender,member_id,guest_id,request_key,body)
                VALUES(:message,:id,:version,:sender,:member,:guest,:key,:body)
                """,p.addValue("message",message));
        jdbc.update("UPDATE marketplace.operations_support_case SET version=:version WHERE id=:id",p);
        if(t.kind()!=Kind.LOST_ACCESS) {
            var recipients=new java.util.LinkedHashSet<UUID>();
            if(reader!=Reader.CUSTOMER && access.allowed(t.member(),com.example.marketplace.identity.api.AccessArea.BUYER)) {
                recipients.add(t.member());
            }
            if(reader!=Reader.SELLER && t.kind()==Kind.ORDER) {
                UUID seller=orders.sellerMember(t.seller());
                if(access.allowed(seller,com.example.marketplace.identity.api.AccessArea.SELLER)) { recipients.add(seller); }
            }
            if(reader!=Reader.OPERATOR) {
                jdbc.queryForList("SELECT operator_id FROM marketplace.operations_support_assignment WHERE target_id=:id AND operator_id IS NOT NULL",p,UUID.class)
                        .stream().filter(operator->access.allowed(operator,com.example.marketplace.identity.api.AccessArea.SUPPORT))
                        .forEach(recipients::add);
            }
            recipients.remove(user.member());
            for(UUID recipient:recipients) { notifications.record(NotificationEvents.Kind.CASE_REPLY,message,recipient,id); }
        }
        return new Receipt(message,t.version()+1);
    }
    String noticeDestination(UUID id,com.example.marketplace.notification.api.NotificationAccess.Reader reader) {
        var t=ticket(id,false);
        var a=new OrderQueries.Authority(reader.session(),reader.member(),reader.version(),null);
        if(reader.member().equals(t.member())) { authorize(Reader.CUSTOMER,t,a);return "/support?case="+id; }
        if(t.kind()==Kind.ORDER && reader.member().equals(orders.sellerMember(t.seller()))) {
            authorize(Reader.SELLER,t,a);return "/seller/orders/"+t.seller()+"?case="+id;
        }
        authorize(Reader.OPERATOR,t,a);return "/admin/support?case="+id;
    }
    @Transactional
    public Owner messageAccess(UUID id,UUID message,Reader reader,OrderQueries.Authority actor,boolean write) {
        var t=ticket(id,write);var user=authorize(reader,t,actor);
        var rows=jdbc.queryForList("SELECT member_id,guest_id FROM marketplace.operations_case_message WHERE case_id=:id AND id=:msg",
                Map.of("id",id,"msg",message));
        if(rows.isEmpty() || write && (!java.util.Objects.equals(rows.getFirst().get("member_id"),user.member())
                || !java.util.Objects.equals(rows.getFirst().get("guest_id"),user.guest()))) { throw new OrderRejected(404); }
        return user;
    }
}
