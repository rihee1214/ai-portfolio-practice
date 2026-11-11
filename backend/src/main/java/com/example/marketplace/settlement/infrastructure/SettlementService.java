package com.example.marketplace.settlement.infrastructure;

import com.example.marketplace.claim.api.SettlementClaims;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.SettlementOrders;
import com.example.marketplace.payment.api.SettlementPayments;
import com.example.marketplace.settlement.api.Settlements;
import com.example.marketplace.settlement.domain.SettlementRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class SettlementService implements Settlements {
    public interface Checkpoint { void after(String stage); }
    private final NamedParameterJdbcTemplate jdbc;
    private final SettlementOrders orders;private final SettlementPayments payments;private final SettlementClaims claims;
    private final HistorySessions sessions;private final AccessPolicy access;private final Clock clock;private final Checkpoint checkpoint;
    private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
    @Autowired
    public SettlementService(NamedParameterJdbcTemplate jdbc,SettlementOrders orders,SettlementPayments payments,SettlementClaims claims,
            HistorySessions sessions,AccessPolicy access,@Qualifier("settlementClock") Clock clock) {
        this(jdbc,orders,payments,claims,sessions,access,clock,stage->{ });
    }
    public SettlementService(NamedParameterJdbcTemplate jdbc,SettlementOrders orders,SettlementPayments payments,SettlementClaims claims,
            HistorySessions sessions,AccessPolicy access,Clock clock,Checkpoint checkpoint) {
        this.jdbc=jdbc;this.orders=orders;this.payments=payments;this.claims=claims;this.sessions=sessions;this.access=access;this.clock=clock;this.checkpoint=checkpoint;
    }
    private void authorize(Actor a) {
        if(a==null||a.member()==null||!sessions.lockReadable(a.session(),a.member(),a.version())||!access.allowed(a.member(),AccessArea.SETTLEMENTS)) { throw new OrderRejected(403); }
    }
    private Policy policyRow(boolean lock) {
        return jdbc.queryForObject("SELECT version,rate_basis_points,effective_at,zone FROM marketplace.settlement_policy"+(lock?" FOR UPDATE":""),Map.of(),(r,n)->new Policy(r.getString(1),r.getInt(2),r.getTimestamp(3).toInstant(),r.getString(4),SettlementRules.week(clock.instant())));
    }
    @Override @Transactional public Policy policy(Actor actor) { authorize(actor);return policyRow(false); }
    @Override @Transactional public List<Summary> list(Actor actor) {
        authorize(actor);return jdbc.query("SELECT * FROM marketplace.settlement_batch ORDER BY period_start DESC,id",Map.of(),(r,n)->new Summary(r.getObject("id",UUID.class),r.getObject("week_start",LocalDate.class),r.getTimestamp("period_start").toInstant(),r.getTimestamp("period_end").toInstant(),r.getString("state"),r.getLong("version"),!clock.instant().isBefore(r.getTimestamp("period_end").toInstant()),r.getString("gross"),r.getString("fee"),r.getString("net")));
    }
    private Map<String,Object> batch(UUID id,boolean lock) {
        if(id==null) { throw new OrderRejected(400); }
        var rows=jdbc.queryForList("SELECT * FROM marketplace.settlement_batch WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id));
        if(rows.size()!=1) { throw new OrderRejected(404); }return rows.getFirst();
    }
    private Batch view(UUID id,List<Held> held) {
        var b=batch(id,false);
        var items=jdbc.query("SELECT l.*,i.eligible_at AS payable_at,i.hold_evidence::text AS hold_evidence,i.reservation_evidence::text AS reservation_evidence FROM marketplace.settlement_batch_item i JOIN marketplace.settlement_ledger l ON l.id=i.ledger_id WHERE i.batch_id=:id ORDER BY l.occurred_at,l.seller_order_id::text,l.item_id::text NULLS LAST,l.ordinal,l.kind,l.id",Map.of("id",id),(r,n)->new Item(r.getObject("id",UUID.class),r.getObject("store_id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("seller_order_id",UUID.class),r.getObject("item_id",UUID.class),(Integer)r.getObject("ordinal"),r.getObject("cause_id",UUID.class),r.getObject("payment_id",UUID.class),r.getObject("refund_attempt_id",UUID.class),r.getString("kind"),r.getString("amount"),r.getString("fee"),Long.toString(Math.subtractExact(r.getLong("amount"),r.getLong("fee"))),r.getTimestamp("occurred_at").toInstant(),r.getTimestamp("payable_at").toInstant(),r.getString("policy_version"),List.of(mapper.readValue(r.getString("hold_evidence"),EligibilityHold[].class)),List.of(mapper.readValue(r.getString("reservation_evidence"),EligibilityReservation[].class))));
        Instant end=((Timestamp)b.get("period_end")).toInstant();
        var stored=List.of(mapper.readValue(b.get("held").toString(),Held[].class));
        var balances=List.of(mapper.readValue(b.get("store_balances").toString(),StoreBalance[].class));
        String payout=((Number)b.get("readiness_version")).intValue()==1?b.get("net").toString():
                Long.toString(balances.stream().mapToLong(v->Long.parseLong(v.payout())).reduce(0,Math::addExact));
        return new Batch(id,((java.sql.Date)b.get("week_start")).toLocalDate(),((Timestamp)b.get("period_start")).toInstant(),end,b.get("state").toString(),((Number)b.get("version")).longValue(),!clock.instant().isBefore(end),List.copyOf(items),b.get("gross").toString(),b.get("fee").toString(),b.get("net").toString(),stored,balances,payout);
    }
    @Override @Transactional public Batch get(UUID id,Actor actor) { authorize(actor);return view(id,List.of()); }
    private static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    record Line(UUID store,UUID order,UUID seller,UUID item,Integer ordinal,UUID cause,UUID payment,UUID refund,
            String kind,long amount,long fee,Instant at,Instant eligibleAt,String policy) {
        String key() { return seller+"|"+item+"|"+ordinal+"|"+kind+"|"+cause; }
        String fingerprint() { return hash(toString()); }
    }
    record Readiness(Instant eligibleAt,List<EligibilityHold> holds,List<EligibilityReservation> reservations) {
        Readiness {
            holds=holds.stream().sorted(java.util.Comparator.comparing(h->h.id().toString())).toList();
            reservations=reservations.stream().sorted(java.util.Comparator.comparing(r->r.kind()+"|"+r.id())).toList();
        }
        String fingerprint() { return hash(eligibleAt+"|"+holds+"|"+reservations); }
    }
    record Sources(List<Line> lines,List<Held> held,Map<String,Readiness> readiness) { }
    private static boolean inPeriod(Instant at,Instant start,Instant end) { return at!=null&&!at.isBefore(start)&&at.isBefore(end); }
    private static boolean relevant(SettlementOrders.Seller seller,Instant start,Instant end) {
        return seller.units().stream().anyMatch(u->inPeriod(u.confirmedAt(),start,end)||inPeriod(u.terminalAt(),start,end))
                ||seller.holds().stream().anyMatch(h->h.acquiredAt().isBefore(end)&&(h.releasedAt()==null||!h.releasedAt().isBefore(start)));
    }
    static Readiness readiness(Instant base,List<SettlementOrders.Hold> holds,List<EligibilityReservation> reservations) {
        Instant boundary=SettlementRules.start(SettlementRules.week(base).plusWeeks(1)),eligible=base;
        boolean changed;
        do {
            changed=false;
            for(var hold:holds) {
                if(hold.releasedAt()!=null&&hold.releasedAt().isAfter(base)&&
                        (hold.acquiredAt().isBefore(boundary)||!hold.acquiredAt().isAfter(eligible))&&hold.releasedAt().isAfter(eligible)) {
                    eligible=hold.releasedAt();changed=true;
                }
            }
            for(var reservation:reservations) {
                if(reservation.releasedAt()!=null&&reservation.releasedAt().isAfter(base)&&
                        (reservation.acquiredAt().isBefore(boundary)||!reservation.acquiredAt().isAfter(eligible))&&reservation.releasedAt().isAfter(eligible)) {
                    eligible=reservation.releasedAt();changed=true;
                }
            }
        }while(changed);
        Instant ready=eligible;
        var evidence=holds.stream().filter(h->h.releasedAt()!=null&&h.releasedAt().isAfter(base)&&
                (h.acquiredAt().isBefore(boundary)||!h.acquiredAt().isAfter(ready)))
                .map(h->new EligibilityHold(h.id(),h.acquiredAt(),h.releasedAt())).toList();
        var financial=reservations.stream().filter(r->r.releasedAt()!=null&&r.releasedAt().isAfter(base)&&
                (r.acquiredAt().isBefore(boundary)||!r.acquiredAt().isAfter(ready))).toList();
        return new Readiness(ready,evidence,financial);
    }
    private record Concern(String reason,Instant beganAt,Instant releasedAt,UUID reservation) {
        Concern(String reason,Instant beganAt,Instant releasedAt) { this(reason,beganAt,releasedAt,null); }
    }
    private static Instant knownAt(Instant at) { return at==null?Instant.MIN:at; }
    private static boolean spansCutoff(Instant began,Instant released,Instant cutoff) {
        return began.isBefore(cutoff)&&(released==null||!released.isBefore(cutoff));
    }
    private static String historyReason(SettlementOrders.Seller seller,List<Concern> concerns,Instant cutoff) {
        if(seller.holds().stream().anyMatch(h->spansCutoff(h.acquiredAt(),h.releasedAt(),cutoff))) { return "HELD_UNIT"; }
        return concerns.stream().filter(c->!c.reason().equals("INCOMPLETE_ORIGINAL_GRAPH")&&spansCutoff(c.beganAt(),c.releasedAt(),cutoff)).map(Concern::reason).findFirst().orElse(null);
    }
    private static String blocker(Line line,Readiness ready,List<SettlementOrders.Hold> holds,List<Concern> concerns) {
        Instant boundary=SettlementRules.start(SettlementRules.week(line.eligibleAt()).plusWeeks(1));
        if(holds.stream().anyMatch(h->h.releasedAt()==null&&(h.acquiredAt().isBefore(boundary)||!h.acquiredAt().isAfter(ready.eligibleAt())))) { return "HELD_UNIT"; }
        for(var concern:concerns) {
            if(!concern.beganAt().isBefore(boundary)&&concern.beganAt().isAfter(ready.eligibleAt())) { continue; }
            if(concern.reason().equals("INCOMPLETE_ORIGINAL_GRAPH")||concern.releasedAt()==null) { return concern.reason(); }
            if(!concern.releasedAt().isBefore(boundary)&&concern.releasedAt().isAfter(ready.eligibleAt())) { return "INCOMPLETE_ORIGINAL_GRAPH"; }
        }
        return null;
    }
    Sources sources(Policy policy,Instant start,Instant end) {
        return sources(policy,start,end,false);
    }
    private static boolean affects(Line line,List<com.example.marketplace.order.api.OrderConfirmations.Unit> units) {
        return line.item()==null||units==null||units.isEmpty()||units.contains(new com.example.marketplace.order.api.OrderConfirmations.Unit(line.item(),line.ordinal()));
    }
    private Sources sources(Policy policy,Instant start,Instant end,boolean scoped) {
        var lines=new ArrayList<Line>();var held=new ArrayList<Held>();var readiness=new HashMap<String,Readiness>();
        for(UUID order:orders.candidates(policy.version())) {
            var sellers=orders.lock(order,policy.version());var paid=payments.lock(order);
            for(var seller:sellers) {
                var claim=claims.lock(seller.sellerOrderId());
                long originalTotal=0;for(var original:sellers) { originalTotal=Math.addExact(originalTotal,original.shipping());for(var unit:original.units()) { originalTotal=Math.addExact(originalTotal,unit.net()); } }
                if(paid.size()!=1||paid.getFirst().amount()!=originalTotal||seller.units().isEmpty()) { if(relevant(seller,start,end)) { held.add(new Held(seller.sellerOrderId(),"PAYMENT_EVIDENCE")); }continue; }
                var payment=paid.getFirst();var fees=SettlementRules.commissions(seller.units().stream().map(SettlementOrders.Unit::net).toList());
                var refunds=new HashMap<UUID,SettlementPayments.Refund>();payment.refunds().forEach(r->refunds.put(r.id(),r));
                var concerns=new ArrayList<Concern>();var financial=new ArrayList<EligibilityReservation>();
                claim.reservations().forEach(r->{financial.add(new EligibilityReservation(r.id(),"CLAIM_FEE",r.acquiredAt(),r.releasedAt()));concerns.add(new Concern("CLAIM_RESERVATION",r.acquiredAt(),r.releasedAt(),r.id()));});
                if(claim.pending()&&claim.reservations().isEmpty()) { concerns.add(new Concern("CLAIM_RESERVATION",Instant.MIN,null)); }
                payment.refunds().stream().filter(r->r.sellerOrderId().equals(seller.sellerOrderId())).forEach(r->{
                    Instant released=List.of("SUCCEEDED","FAILED","WITHDRAWN").contains(r.state())?r.releasedAt():null;
                    financial.add(new EligibilityReservation(r.id(),"REFUND",r.reservedAt(),released));concerns.add(new Concern("UNRESOLVED_REFUND",r.reservedAt(),released,r.id()));
                });
                if(seller.units().stream().anyMatch(u->u.state().equals("HELD"))&&seller.holds().stream().noneMatch(h->h.releasedAt()==null)) { concerns.add(new Concern("INCOMPLETE_ORIGINAL_GRAPH",Instant.MIN,null)); }
                var local=new ArrayList<Line>();Instant terminal=Instant.MIN;
                for(int i=0;i<seller.units().size();i++) {
                    var u=seller.units().get(i);boolean refunded=u.state().equals("REFUNDED");
                    if(List.of("OPEN","HELD").contains(u.state())) { continue; }
                    var actual=refunded?refunds.get(u.refundAttemptId()):null;
                    if(refunded&&(actual==null||!actual.state().equals("SUCCEEDED")||!actual.purpose().equals("ITEM")||!actual.sellerOrderId().equals(seller.sellerOrderId()))||!refunded&&(u.confirmationId()==null||u.confirmedAt()==null)) {
                        concerns.add(new Concern("INCOMPLETE_ORIGINAL_GRAPH",knownAt(actual==null?u.terminalAt():actual.reservedAt()),null));continue;
                    }
                    UUID cause=refunded?u.refundCauseId():u.confirmationId();Instant at=refunded?actual.resultAt():u.confirmedAt();
                    if(cause==null||at==null||u.terminalAt()==null) { concerns.add(new Concern("INCOMPLETE_ORIGINAL_GRAPH",knownAt(at),null));continue; }
                    if(u.terminalAt().isAfter(terminal)) { terminal=u.terminalAt(); }if(at.isAfter(terminal)) { terminal=at; }
                    local.add(line(seller,u,cause,payment.id(),null,"PRODUCT",u.net(),0,at));
                    local.add(line(seller,u,cause,payment.id(),null,"COMMISSION",0,fees.get(i),at));
                    if(refunded) {
                        local.add(line(seller,u,cause,payment.id(),actual.id(),"REFUND",-u.net(),0,at));
                        local.add(line(seller,u,cause,payment.id(),actual.id(),"COMMISSION_REVERSAL",0,-fees.get(i),at));
                    }
                }
                var supportAttempts=new java.util.HashSet<UUID>();
                for(var result:claim.supportRefunds()) {
                    var actual=refunds.get(result.attemptId());
                    if(actual==null||!seller.sellerOrderId().equals(actual.sellerOrderId())||!result.approvalId().equals(actual.claimId())
                            ||!actual.state().equals("SUCCEEDED")||!actual.purpose().equals(result.purpose())
                            ||actual.amount()!=result.amount()||!result.resultAt().equals(actual.resultAt())) {
                        concerns.add(new Concern("INCOMPLETE_ORIGINAL_GRAPH",result.resultAt(),null));continue;
                    }
                    if(result.purpose().equals("SHIPPING")&&actual.amount()==seller.shipping()) {
                        local.add(line(seller,null,result.approvalId(),payment.id(),actual.id(),"SHIPPING_REFUND",-actual.amount(),0,actual.resultAt()));
                        supportAttempts.add(actual.id());continue;
                    }
                    for(int i=0;i<seller.units().size();i++) {
                        var unit=seller.units().get(i);
                        if(result.purpose().equals("ITEM")&&unit.itemId().equals(result.unit().itemId())&&unit.ordinal()==result.unit().ordinal()
                                &&unit.state().equals("CONFIRMED")&&result.confirmationId().equals(unit.confirmationId())&&unit.net()==actual.amount()) {
                            local.add(line(seller,unit,result.approvalId(),payment.id(),actual.id(),"REFUND",-unit.net(),0,actual.resultAt()));
                            local.add(line(seller,unit,result.approvalId(),payment.id(),actual.id(),"COMMISSION_REVERSAL",0,-fees.get(i),actual.resultAt()));
                            supportAttempts.add(actual.id());break;
                        }
                    }
                }
                for(var recovery:claim.recoveries()) {
                    var actual=refunds.get(recovery.attemptId());
                    if(actual==null||!actual.state().equals("SUCCEEDED")) { concerns.add(new Concern("INCOMPLETE_ORIGINAL_GRAPH",recovery.occurredAt(),null));continue; }
                    local.add(line(seller,null,recovery.causeId(),payment.id(),actual.id(),"RECOVERY_FEE",recovery.deducted(),0,recovery.occurredAt()));
                }
                for(var actual:payment.refunds()) {
                    if(supportAttempts.contains(actual.id())||!actual.sellerOrderId().equals(seller.sellerOrderId())||!actual.purpose().equals("ITEM")||!actual.state().equals("SUCCEEDED")) { continue; }
                    long original=seller.units().stream().filter(u->actual.id().equals(u.refundAttemptId())).mapToLong(SettlementOrders.Unit::net).reduce(0,Math::addExact);
                    long deducted=claim.recoveries().stream().filter(r->actual.id().equals(r.attemptId())).mapToLong(SettlementClaims.Recovery::deducted).reduce(0,Math::addExact);
                    if(original!=Math.addExact(actual.amount(),deducted)) { concerns.add(new Concern("INCOMPLETE_ORIGINAL_GRAPH",actual.reservedAt(),null)); }
                }
                for(var actual:payment.refunds()) {
                    if(actual.sellerOrderId().equals(seller.sellerOrderId())&&actual.purpose().equals("SHIPPING")&&actual.state().equals("SUCCEEDED")&&
                            !supportAttempts.contains(actual.id())&&seller.shippingRefunds().stream().noneMatch(r->r.attemptId().equals(actual.id()))) { concerns.add(new Concern("INCOMPLETE_ORIGINAL_GRAPH",actual.reservedAt(),null)); }
                }
                boolean allTerminal=seller.units().stream().allMatch(u->List.of("CONFIRMED","REFUNDED").contains(u.state()));
                boolean physical=seller.units().stream().anyMatch(u->u.state().equals("REFUNDED")&&!"CANCEL".equals(u.refundKind()));
                // A later adjustment cannot move the original charge beyond the first retained outcome.
                Instant retained=physical?claim.retainedForConfirmationAt():null;
                boolean shippingReady=retained!=null||(seller.units().stream().noneMatch(u->u.state().equals("REFUNDED"))||"RETAINED".equals(seller.shippingState())||!seller.shippingRefunds().isEmpty())
                        &&(!physical||claim.shippingFinalizedAt()!=null);
                Instant shippingAt=terminal;
                if(physical&&claim.shippingFinalizedAt()!=null&&claim.shippingFinalizedAt().isAfter(shippingAt)) { shippingAt=claim.shippingFinalizedAt(); }
                boolean shippingValid=allTerminal&&shippingReady;
                if(shippingValid) {
                    for(var refund:seller.shippingRefunds()) {
                        var actual=refunds.get(refund.attemptId());
                        if(actual==null||!actual.state().equals("SUCCEEDED")||!actual.purpose().equals("SHIPPING")||actual.amount()!=refund.amount()) {
                            concerns.add(new Concern("INCOMPLETE_ORIGINAL_GRAPH",knownAt(actual==null?terminal:actual.reservedAt()),null));shippingValid=false;break;
                        }
                        if(actual.resultAt().isAfter(shippingAt)) { shippingAt=actual.resultAt(); }
                    }
                    for(var refund:shippingValid?seller.shippingRefunds():List.<SettlementOrders.ShippingRefund>of()) {
                        if(supportAttempts.contains(refund.attemptId())) { continue; }
                        var actual=refunds.get(refund.attemptId());
                        if(actual!=null&&actual.state().equals("SUCCEEDED")) {
                            var economic=line(seller,null,refund.causeId(),payment.id(),actual.id(),"SHIPPING_REFUND",-actual.amount(),0,actual.resultAt());
                            local.add(new Line(economic.store(),economic.order(),economic.seller(),null,null,economic.cause(),economic.payment(),economic.refund(),economic.kind(),economic.amount(),0,economic.at(),shippingAt,economic.policy()));
                        }
                    }
                    if(shippingValid) {
                        Instant originalAt=retained==null?shippingAt:(terminal.isAfter(retained)?terminal:retained);
                        local.add(line(seller,null,seller.sellerOrderId(),payment.id(),null,"SHIPPING",seller.shipping(),0,originalAt));
                    }
                }
                String historical=historyReason(seller,concerns,end);
                if(historical!=null&&relevant(seller,start,end)) { held.add(new Held(seller.sellerOrderId(),historical)); }
                if(allTerminal&&retained==null&&inPeriod(terminal,start,end)&&(!shippingReady||!shippingAt.isBefore(end))) { held.add(new Held(seller.sellerOrderId(),"SHIPPING_OUTCOME_PENDING")); }
                boolean deferred=false;
                for(var economic:local) {
                    var holds=scoped?seller.holds().stream().filter(h->affects(economic,h.units())).toList():seller.holds();
                    java.util.function.Predicate<UUID> applies=id->{
                        if(!scoped||id==null||economic.item()==null) { return true; }
                        var refund=refunds.get(id);
                        return (refund==null||!"SHIPPING".equals(refund.purpose()))&&affects(economic,claim.attributedUnits().get(id));
                    };
                    var reservations=financial.stream().filter(r->applies.test(r.id())).toList();
                    var ready=readiness(economic.eligibleAt(),holds,reservations);
                    String issue=blocker(economic,ready,holds,concerns.stream().filter(c->applies.test(c.reservation())).toList());
                    if(issue!=null) {
                        if(inPeriod(economic.eligibleAt(),start,end)||inPeriod(ready.eligibleAt(),start,end)) { held.add(new Held(seller.sellerOrderId(),historical==null?issue:historical)); }
                        continue;
                    }
                    if(inPeriod(ready.eligibleAt(),start,end)) { lines.add(economic);readiness.put(economic.key(),ready); }
                    else if(inPeriod(economic.eligibleAt(),start,end)&&!ready.eligibleAt().isBefore(end)) { deferred=true; }
                }
                if(deferred&&historical==null) { held.add(new Held(seller.sellerOrderId(),"DEFERRED_AFTER_HOLD")); }
            }
        }
        return new Sources(List.copyOf(lines),held.stream().distinct().toList(),Map.copyOf(readiness));
    }
    private static Line line(SettlementOrders.Seller s,SettlementOrders.Unit u,UUID cause,UUID payment,UUID refund,String kind,long amount,long fee,Instant at) {
        Instant eligible=u!=null&&u.state().equals("REFUNDED")&&u.terminalAt().isAfter(at)?u.terminalAt():at;
        return new Line(s.storeId(),s.orderId(),s.sellerOrderId(),u==null?null:u.itemId(),u==null?null:u.ordinal(),cause,payment,refund,kind,amount,fee,at,eligible,s.policyVersion());
    }
    private void append(Line l) {
        var p=new MapSqlParameterSource("id",UUID.nameUUIDFromBytes(l.key().getBytes(StandardCharsets.UTF_8))).addValue("policy",l.policy()).addValue("store",l.store()).addValue("order",l.order()).addValue("seller",l.seller()).addValue("item",l.item()).addValue("ordinal",l.ordinal()).addValue("cause",l.cause()).addValue("payment",l.payment()).addValue("refund",l.refund()).addValue("kind",l.kind()).addValue("amount",l.amount()).addValue("fee",l.fee()).addValue("at",Timestamp.from(l.at())).addValue("eligible",Timestamp.from(l.eligibleAt())).addValue("key",l.key()).addValue("hash",l.fingerprint());
        jdbc.update("INSERT INTO marketplace.settlement_ledger(id,policy_version,store_id,order_id,seller_order_id,item_id,ordinal,cause_id,payment_id,refund_attempt_id,kind,amount,fee,occurred_at,eligible_at,source_key,fingerprint) VALUES(:id,:policy,:store,:order,:seller,:item,:ordinal,:cause,:payment,:refund,:kind,:amount,:fee,:at,:eligible,:key,:hash) ON CONFLICT(source_key) DO NOTHING",p);
        if(!l.fingerprint().equals(jdbc.queryForObject("SELECT fingerprint FROM marketplace.settlement_ledger WHERE source_key=:key",p,String.class))) { throw new OrderRejected(409); }
    }
    private Map<String,String> snapshot(Sources source) {
        var result=new HashMap<String,String>();
        source.lines().forEach(l->result.put(l.key(),hash(l.fingerprint()+"|"+source.readiness().get(l.key()).fingerprint())));
        return result;
    }
    private Readiness storedReadiness(Map<String,Object> row) {
        return new Readiness(((Timestamp)row.get("eligible_at")).toInstant(),List.of(mapper.readValue(row.get("hold_evidence").toString(),EligibilityHold[].class)),List.of(mapper.readValue(row.get("reservation_evidence").toString(),EligibilityReservation[].class)));
    }
    private String storedSnapshot(Map<String,Object> row) { return hash(row.get("fingerprint")+"|"+storedReadiness(row).fingerprint()); }
    private List<Map<String,Object>> assigned(UUID id) {
        return jdbc.queryForList("SELECT l.source_key,l.fingerprint,i.eligible_at,i.hold_evidence::text AS hold_evidence,i.reservation_evidence::text AS reservation_evidence FROM marketplace.settlement_batch_item i JOIN marketplace.settlement_ledger l ON l.id=i.ledger_id WHERE i.batch_id=:id",Map.of("id",id));
    }
    record Assignment(String sourceKey,UUID batchId,LocalDate weekStart,String state) { }
    static java.util.Set<String> paidElsewhereOrConflict(UUID target,java.util.Set<String> candidates,List<Assignment> owners) {
        var weeks=new java.util.TreeSet<LocalDate>();var paid=new java.util.HashSet<String>();
        for(var owner:owners) {
            if(owner.batchId().equals(target)||!candidates.contains(owner.sourceKey())) { continue; }
            if(owner.state().equals("PAID_MOCK")) { paid.add(owner.sourceKey()); }
            else { weeks.add(owner.weekStart()); }
        }
        if(!weeks.isEmpty()) { throw new AssignmentConflict(List.copyOf(weeks)); }
        return java.util.Set.copyOf(paid);
    }
    private java.util.Set<String> foreignPaid(Sources source,UUID id) {
        var keys=source.lines().stream().map(Line::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(keys.isEmpty()) { return java.util.Set.of(); }
        var owners=jdbc.query("SELECT l.source_key,i.batch_id,b.week_start,b.state FROM marketplace.settlement_batch_item i JOIN marketplace.settlement_ledger l ON l.id=i.ledger_id JOIN marketplace.settlement_batch b ON b.id=i.batch_id WHERE l.source_key IN(:keys) ORDER BY b.week_start,b.id,l.source_key",Map.of("keys",keys),(r,n)->new Assignment(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,LocalDate.class),r.getString(4)));
        return paidElsewhereOrConflict(id,keys,owners);
    }
    private Sources refresh(UUID id,Policy policy,boolean draft) {
        var b=batch(id,true);var source=batchSources(policy,b);
        var used=foreignPaid(source,id);
        var expected=snapshot(source);var financial=new HashMap<String,String>();source.lines().forEach(l->financial.put(l.key(),l.fingerprint()));
        var assigned=assigned(id);
        for(var l:assigned) {
            String key=l.get("source_key").toString(),fingerprint=financial.get(key);
            if(fingerprint!=null&&!l.get("fingerprint").equals(fingerprint)) { throw new OrderRejected(409); }
            if(draft&&(fingerprint==null||!storedSnapshot(l).equals(expected.get(key)))) {
                jdbc.update("DELETE FROM marketplace.settlement_batch_item i USING marketplace.settlement_ledger l WHERE i.ledger_id=l.id AND i.batch_id=:id AND l.source_key=:key",Map.of("id",id,"key",l.get("source_key")));
            }else if(fingerprint==null) { throw new OrderRejected(409); }
        }
        if(!draft) {
            expected.keySet().removeAll(used);
            var saved=new HashMap<String,String>();assigned.forEach(l->saved.put(l.get("source_key").toString(),storedSnapshot(l)));
            if(saved.isEmpty()&&b.get("store_balances").toString().equals("[]")||!balancesCurrent(id)
                    ||!sameSnapshot(expected,saved)||!List.of(mapper.readValue(b.get("held").toString(),Held[].class)).equals(source.held())) { throw new OrderRejected(409); }
        }
        if(draft) {
            for(var l:source.lines()) { append(l); }
            for(var l:source.lines()) {
                var ready=source.readiness().get(l.key());
                jdbc.update("INSERT INTO marketplace.settlement_batch_item(batch_id,ledger_id,eligible_at,hold_evidence,reservation_evidence) SELECT :id,l.id,:at,CAST(:holds AS jsonb),CAST(:reservations AS jsonb) FROM marketplace.settlement_ledger l WHERE l.source_key=:key AND NOT EXISTS(SELECT 1 FROM marketplace.settlement_batch_item i WHERE i.ledger_id=l.id) ON CONFLICT DO NOTHING",Map.of("id",id,"key",l.key(),"at",Timestamp.from(ready.eligibleAt()),"holds",mapper.writeValueAsString(ready.holds()),"reservations",mapper.writeValueAsString(ready.reservations())));
            }
            var totals=jdbc.queryForMap("SELECT count(*) AS count,COALESCE(sum(l.amount),0) AS gross,COALESCE(sum(l.fee),0) AS fee FROM marketplace.settlement_batch_item i JOIN marketplace.settlement_ledger l ON l.id=i.ledger_id WHERE i.batch_id=:id",Map.of("id",id));
            long gross=new java.math.BigDecimal(totals.get("gross").toString()).longValueExact(),fee=new java.math.BigDecimal(totals.get("fee").toString()).longValueExact();
            boolean empty=((Number)totals.get("count")).longValue()==0;
            boolean priorDetails=!assigned.isEmpty()||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace.settlement_batch_revision WHERE batch_id=:id AND cardinality(ledger_ids)>0)",Map.of("id",id),Boolean.class));
            String balances=jdbc.queryForObject("SELECT marketplace.settlement_store_balances(:id)::text",Map.of("id",id),String.class);
            if(empty&&balances.equals("[]")&&(!priorDetails||source.held().isEmpty())) { throw new OrderRejected(409); }
            jdbc.update("UPDATE marketplace.settlement_batch SET gross=:gross,fee=:fee,net=:net,held=CAST(:held AS jsonb),store_balances=CAST(:balances AS jsonb),version=version+1 WHERE id=:id",Map.of("gross",gross,"fee",fee,"net",Math.subtractExact(gross,fee),"held",mapper.writeValueAsString(source.held()),"balances",balances,"id",id));
        }
        checkpoint.after("LEDGER");return source;
    }
    private Sources safeRefresh(UUID id,Policy policy,boolean draft) {
        try { return refresh(id,policy,draft); }
        catch(ArithmeticException overflow) { throw new OrderRejected(409); }
    }
    static boolean sameSnapshot(Map<String,String> expected,Map<String,String> saved) { return expected.equals(saved); }
    private void reopenReviewed(UUID id,Policy policy,Map<String,Object> batch) {
        Sources source;
        try { source=sources(policy,((Timestamp)batch.get("period_start")).toInstant(),((Timestamp)batch.get("period_end")).toInstant(),true); }
        catch(ArithmeticException overflow) { throw new OrderRejected(409); }
        var expected=snapshot(source);var financial=new HashMap<String,String>();source.lines().forEach(l->financial.put(l.key(),l.fingerprint()));
        expected.keySet().removeAll(foreignPaid(source,id));
        var assigned=assigned(id);
        var saved=new HashMap<String,String>();assigned.forEach(l->saved.put(l.get("source_key").toString(),storedSnapshot(l)));
        for(var line:assigned) {
            String key=line.get("source_key").toString();
            if(financial.containsKey(key)&&!line.get("fingerprint").equals(financial.get(key))) { throw new OrderRejected(409); }
        }
        if(sameSnapshot(expected,saved)&&balancesCurrent(id)&&List.of(mapper.readValue(batch.get("held").toString(),Held[].class)).equals(source.held())) { throw new OrderRejected(409); }
        jdbc.update("UPDATE marketplace.settlement_batch SET state='DRAFT',reviewed_by=NULL,reviewed_at=NULL,version=version+1 WHERE id=:id AND state='REVIEWED' AND NOT EXISTS(SELECT 1 FROM marketplace.settlement_mock_payment WHERE batch_id=:id)",Map.of("id",id));
        if(!batch(id,true).get("state").equals("DRAFT")) { throw new OrderRejected(409); }
    }
    private boolean replay(UUID key,UUID id,String fingerprint) {
        var rows=jdbc.queryForList("SELECT batch_id,fingerprint FROM marketplace.settlement_command WHERE request_key=:key",Map.of("key",key));
        if(rows.isEmpty()) { return false; }if(!rows.getFirst().get("batch_id").equals(id)||!rows.getFirst().get("fingerprint").equals(fingerprint)) { throw new OrderRejected(409); }return true;
    }
    private boolean balancesCurrent(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT readiness_version=1 OR store_balances=marketplace.settlement_store_balances(id) FROM marketplace.settlement_batch WHERE id=:id",
                Map.of("id",id),Boolean.class));
    }
    private void receipt(UUID key,UUID id,String action,Long expected,Actor actor,String fingerprint) {
        jdbc.update("INSERT INTO marketplace.settlement_command(request_key,batch_id,action,expected_version,actor_id,fingerprint) VALUES(:key,:id,:action,:version,:actor,:hash)",new MapSqlParameterSource("key",key).addValue("id",id).addValue("action",action).addValue("version",expected).addValue("actor",actor.member()).addValue("hash",fingerprint));checkpoint.after("RECEIPT");
    }
    private Batch replayView(UUID id,Policy policy,Actor actor) {
        var existing=batch(id,true);
        if(!existing.get("state").equals("PAID_MOCK")) {
            Sources source;
            try { source=batchSources(policy,existing); }
            catch(ArithmeticException overflow) { throw new OrderRejected(409); }
            foreignPaid(source,id);
        }
        authorize(actor);return view(id,List.of());
    }
    private Sources batchSources(Policy policy,Map<String,Object> batch) {
        return sources(policy,((Timestamp)batch.get("period_start")).toInstant(),((Timestamp)batch.get("period_end")).toInstant(),
                ((Number)batch.get("readiness_version")).intValue()==2);
    }
    @Override @Transactional public Batch open(Open cmd,Actor actor) {
        authorize(actor);if(cmd==null||cmd.key()==null) { throw new OrderRejected(400); }
        Instant start;try { start=SettlementRules.start(cmd.weekStart()); }catch(IllegalArgumentException e) { throw new OrderRejected(400); }
        if(start.isAfter(clock.instant())) { throw new OrderRejected(409); }
        Policy policy=policyRow(true);UUID id=UUID.nameUUIDFromBytes(("settlement-week:"+cmd.weekStart()).getBytes(StandardCharsets.UTF_8));
        String fingerprint=hash("OPEN|"+cmd.weekStart()+"|"+actor.member());
        if(replay(cmd.key(),id,fingerprint)) { return replayView(id,policy,actor); }
        var p=Map.of("id",id,"week",java.sql.Date.valueOf(cmd.weekStart()),"start",Timestamp.from(start),"end",Timestamp.from(SettlementRules.start(cmd.weekStart().plusWeeks(1))),"actor",actor.member());
        jdbc.update("INSERT INTO marketplace.settlement_batch(id,week_start,period_start,period_end,state,created_by) VALUES(:id,:week,:start,:end,'DRAFT',:actor) ON CONFLICT(week_start) DO NOTHING",p);
        var existing=batch(id,true);String action="OPEN";
        if(existing.get("state").equals("REVIEWED")) { reopenReviewed(id,policy,existing);action="REBUILD"; }
        else if(!existing.get("state").equals("DRAFT")) { throw new OrderRejected(409); }
        if(((Number)existing.get("readiness_version")).intValue()==1) {
            jdbc.update("UPDATE marketplace.settlement_batch SET readiness_version=2,version=version+1 WHERE id=:id AND state='DRAFT'",p);
        }
        var source=safeRefresh(id,policy,true);authorize(actor);receipt(cmd.key(),id,action,null,actor,fingerprint);return view(id,source.held());
    }
    @Override @Transactional public Batch review(UUID id,Change cmd,Actor actor) { return change(id,cmd,actor,false); }
    @Override @Transactional public Batch simulatePay(UUID id,Change cmd,Actor actor) { return change(id,cmd,actor,true); }
    private Batch change(UUID id,Change cmd,Actor actor,boolean pay) {
        authorize(actor);if(cmd==null||cmd.key()==null||cmd.expectedVersion()<0) { throw new OrderRejected(400); }
        var policy=policyRow(true);String action=pay?"PAY_MOCK":"REVIEW";
        String fingerprint=hash(action+"|"+id+"|"+cmd.expectedVersion()+"|"+actor.member());
        if(replay(cmd.key(),id,fingerprint)) { return replayView(id,policy,actor); }
        var b=batch(id,true);String expectedState=pay?"REVIEWED":"DRAFT";
        if(!b.get("state").equals(expectedState)||((Number)b.get("version")).longValue()!=cmd.expectedVersion()||clock.instant().isBefore(((Timestamp)b.get("period_end")).toInstant())) { throw new OrderRejected(409); }
        var source=safeRefresh(id,policy,false);authorize(actor);Instant at=clock.instant();
        if(pay) {
            jdbc.update("INSERT INTO marketplace.settlement_mock_payment(id,batch_id,amount,actor_id,occurred_at) SELECT :key,id,CASE WHEN readiness_version=1 THEN net ELSE marketplace.settlement_payout(store_balances) END,:actor,:at FROM marketplace.settlement_batch WHERE id=:id",Map.of("key",cmd.key(),"id",id,"actor",actor.member(),"at",Timestamp.from(at)));checkpoint.after("PAYMENT");
            jdbc.update("UPDATE marketplace.settlement_batch SET state='PAID_MOCK',paid_at=:at,version=version+1 WHERE id=:id",Map.of("id",id,"at",Timestamp.from(at)));
        }else {
            jdbc.update("UPDATE marketplace.settlement_batch SET state='REVIEWED',reviewed_by=:actor,reviewed_at=:at,version=version+1 WHERE id=:id",Map.of("id",id,"actor",actor.member(),"at",Timestamp.from(at)));
        }
        checkpoint.after("STATE");receipt(cmd.key(),id,action,cmd.expectedVersion(),actor,fingerprint);return view(id,source.held());
    }
}
