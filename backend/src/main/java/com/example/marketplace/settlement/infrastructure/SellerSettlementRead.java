package com.example.marketplace.settlement.infrastructure;

import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.SellerSettlementOrders;
import com.example.marketplace.seller.api.Stores;
import com.example.marketplace.settlement.api.Settlements;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class SellerSettlementRead {
    public record Line(UUID sellerOrderId,UUID causeId,UUID itemId,Integer ordinal,String kind,String amount,String fee) { }
    public record Week(LocalDate weekStart,String state,List<Line> lines,List<Settlements.Held> held,
            String net,String opening,String payout,String closing) { }
    private final HistorySessions sessions;
    private final SellerMembership membership;
    private final Stores stores;
    private final SellerSettlementOrders orders;
    private final NamedParameterJdbcTemplate jdbc;
    public SellerSettlementRead(HistorySessions sessions,SellerMembership membership,Stores stores,SellerSettlementOrders orders,NamedParameterJdbcTemplate jdbc) {
        this.sessions=sessions;this.membership=membership;this.stores=stores;this.orders=orders;this.jdbc=jdbc;
    }
    private UUID own(Settlements.Actor actor) {
        if(actor.member()==null) { throw new OrderRejected(403); }
        // Both readers need the stronger member lock before touching the session or store.
        try { membership.lockSeller(actor.member()); }catch(AccountException unavailable) { throw new OrderRejected(403); }
        if(!sessions.lockReadable(actor.session(),actor.member(),actor.version())) { throw new OrderRejected(403); }
        var own=stores.own(actor.member());
        if(own==null) { throw new OrderRejected(404); }
        stores.requireCapability(actor.member(),Stores.Capability.EXISTING_TRADES);
        return own.id();
    }
    @Transactional public List<Week> read(Settlements.Actor actor) {
        UUID store=own(actor);
        var owned=orders.sellerOrdersForStore(store);
        if(owned.isEmpty()) {
            if(!own(actor).equals(store)) { throw new OrderRejected(403); }
            return List.of();
        }
        var weeks=jdbc.query("""
                SELECT b.id,b.week_start,b.state,coalesce(balance->>'net',original.net::text,'0') AS net,
                coalesce(balance->>'opening','0') AS opening,coalesce(balance->>'payout',original.net::text,'0') AS payout,
                coalesce(balance->>'closing','0') AS closing
                FROM marketplace.settlement_batch b
                LEFT JOIN LATERAL (SELECT x AS balance FROM jsonb_array_elements(b.store_balances) x WHERE x->>'storeId'=:store) s ON true
                LEFT JOIN LATERAL (SELECT sum(l.amount::numeric-l.fee) AS net FROM marketplace.settlement_batch_item i
                JOIN marketplace.settlement_ledger l ON l.id=i.ledger_id WHERE i.batch_id=b.id AND l.store_id=CAST(:store AS uuid)) original ON true
                WHERE balance IS NOT NULL OR original.net IS NOT NULL OR EXISTS(SELECT 1 FROM jsonb_array_elements(b.held) h
                WHERE CAST(h->>'sellerOrderId' AS uuid) IN (:owned)) ORDER BY b.week_start DESC
                """,Map.of("store",store.toString(),"owned",owned),(r,n)->{
            var p=Map.of("batch",r.getObject(1,UUID.class),"store",store);
            var lines=jdbc.query("""
                    SELECT seller_order_id,cause_id,item_id,ordinal,kind,amount,fee FROM marketplace.settlement_batch_item i
                    JOIN marketplace.settlement_ledger l ON l.id=i.ledger_id WHERE i.batch_id=:batch AND l.store_id=:store
                    ORDER BY l.occurred_at,l.id
                    """,p,(l,i)->new Line(l.getObject(1,UUID.class),l.getObject(2,UUID.class),l.getObject(3,UUID.class),
                            (Integer)l.getObject(4),l.getString(5),l.getString(6),l.getString(7)));
            var held=jdbc.query("""
                    SELECT CAST(h->>'sellerOrderId' AS uuid),h->>'reason' FROM marketplace.settlement_batch b
                    CROSS JOIN LATERAL jsonb_array_elements(b.held) h WHERE b.id=:batch AND CAST(h->>'sellerOrderId' AS uuid) IN (:owned)
                    """,Map.of("batch",p.get("batch"),"owned",owned),(h,i)->new Settlements.Held(h.getObject(1,UUID.class),h.getString(2)));
            return new Week(r.getObject(2,LocalDate.class),r.getString(3),lines,held,r.getString(4),r.getString(5),r.getString(6),r.getString(7));
        });
        if(!own(actor).equals(store)) { throw new OrderRejected(403); }
        return weeks;
    }
}
