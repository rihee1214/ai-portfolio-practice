package com.example.marketplace.settlement.infrastructure;

import com.example.marketplace.claim.api.SettlementClaims;
import com.example.marketplace.order.api.SettlementOrders;
import com.example.marketplace.payment.api.SettlementPayments;
import com.example.marketplace.settlement.api.Settlements;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementProjectionTest {
    private static final Instant START=Instant.parse("2026-11-08T15:00:00Z");
    private static final Instant SUNDAY=Instant.parse("2026-11-15T14:59:00Z");
    private static final Instant END=Instant.parse("2026-11-15T15:00:00Z");
    private static final Instant MONDAY=Instant.parse("2026-11-15T15:01:00Z");
    private static final UUID ORDER=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PAYMENT=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Settlements.Policy POLICY=new Settlements.Policy("SETTLEMENT_V1",500,START,"Asia/Seoul",java.time.LocalDate.of(2026,11,16));
    private SettlementOrders.Seller seller(int ordinal,Instant confirmedAt) {
        UUID seller=new UUID(0,100+ordinal),item=new UUID(0,200+ordinal),confirmation=new UUID(0,300+ordinal);
        var unit=new SettlementOrders.Unit(item,0,10019,0,0,10019,"CONFIRMED",confirmation,confirmedAt,null,null,confirmedAt);
        return new SettlementOrders.Seller(ORDER,seller,new UUID(0,400+ordinal),"SETTLEMENT_V1",3000,List.of(unit),List.of(),"RETAINED");
    }
    private SettlementService service(NamedParameterJdbcTemplate jdbc,List<SettlementOrders.Seller> sellers,
            List<SettlementPayments.Refund> refunds,Instant shippingDecisionAt) {
        return serviceWithClaims(jdbc,sellers,refunds,new SettlementClaims.View(false,List.of(),shippingDecisionAt));
    }
    private SettlementService serviceWithClaims(NamedParameterJdbcTemplate jdbc,List<SettlementOrders.Seller> sellers,
            List<SettlementPayments.Refund> refunds,SettlementClaims.View view) {
        SettlementOrders orders=new SettlementOrders() {
            @Override public List<UUID> candidates(String version) { return List.of(ORDER); }
            @Override public List<Seller> lock(UUID order,String version) { return sellers; }
        };
        long paid=sellers.stream().mapToLong(s->s.shipping()+s.units().stream().mapToLong(SettlementOrders.Unit::net).sum()).sum();
        SettlementPayments payments=order->List.of(new SettlementPayments.Payment(PAYMENT,paid,"DEMO",refunds));
        SettlementClaims claims=seller->view;
        return new SettlementService(jdbc,orders,payments,claims,null,null,Clock.systemUTC(),stage->{ });
    }
    private List<SettlementService.Line> lines(SettlementService service,Instant start,Instant end) {
        return service.sources(POLICY,start,end).lines();
    }
    @Test void lateShippingDecisionPreservesSundayProductAndCommissionIdentity() {
        var original=lines(service(null,List.of(seller(1,SUNDAY)),List.of(),null),START,END);
        var late=lines(service(null,List.of(seller(1,SUNDAY)),List.of(),MONDAY),START,END);
        assertEquals(2,late.stream().filter(l->List.of("PRODUCT","COMMISSION").contains(l.kind())).count());
        for(String kind:List.of("PRODUCT","COMMISSION")) {
            var before=original.stream().filter(l->l.kind().equals(kind)).findFirst().orElseThrow();
            var after=late.stream().filter(l->l.kind().equals(kind)).findFirst().orElseThrow();
            assertEquals(SUNDAY,after.eligibleAt());
            assertEquals(before.fingerprint(),after.fingerprint());
        }
        assertEquals(0,lines(service(null,List.of(seller(1,SUNDAY)),List.of(),MONDAY),END,END.plusSeconds(604800)).stream()
                .filter(l->List.of("PRODUCT","COMMISSION").contains(l.kind())).count());
    }
    @Test void futureWeekUnresolvedRefundDoesNotChangePriorWeekHeldSnapshot() {
        var original=seller(2,MONDAY);
        var future=new SettlementOrders.Seller(original.orderId(),original.sellerOrderId(),original.storeId(),original.policyVersion(),original.shipping(),original.units(),original.shippingRefunds(),original.shippingState(),SUNDAY,List.of());
        var unknown=new SettlementPayments.Refund(UUID.randomUUID(),UUID.randomUUID(),future.sellerOrderId(),UUID.randomUUID(),1,"ITEM",10019,"UNKNOWN",null);
        var source=service(null,List.of(future),List.of(unknown),null).sources(POLICY,START,END);
        assertTrue(source.lines().isEmpty());
        assertTrue(source.held().isEmpty());
    }
    @Test void unresolvedRefundAloneBlocksOtherwiseIdenticalConfirmedSource() {
        var seller=seller(3,SUNDAY);
        assertFalse(lines(service(null,List.of(seller),List.of(),null),START,END).isEmpty());
        for(String state:List.of("RESERVED","UNKNOWN")) {
            var unresolved=new SettlementPayments.Refund(UUID.randomUUID(),UUID.randomUUID(),seller.sellerOrderId(),UUID.randomUUID(),1,"ITEM",10019,state,null);
            var source=service(null,List.of(seller),List.of(unresolved),null).sources(POLICY,START,END);
            assertTrue(source.lines().isEmpty());
            var held=source.held();assertEquals(1,held.size());
            assertEquals("UNRESOLVED_REFUND",held.getFirst().reason());
        }
    }
    @Test void reviewedSnapshotRejectsNewSameWeekCauseBeforePayment() {
        var first=seller(4,SUNDAY);var second=seller(5,SUNDAY);
        var original=lines(service(null,List.of(first),List.of(),null),START,END);
        var assigned=new java.util.HashMap<String,String>();original.forEach(l->assigned.put(l.key(),l.fingerprint()));
        var expected=new java.util.HashMap<String,String>();
        lines(service(null,List.of(first,second),List.of(),null),START,END).forEach(l->expected.put(l.key(),l.fingerprint()));
        assertFalse(SettlementService.sameSnapshot(expected,assigned));
        assertTrue(SettlementService.sameSnapshot(assigned,Map.copyOf(assigned)));
    }
    @Test void actualClaimReleaseDefersOnlyCausesBlockedByThatHold() {
        var first=seller(6,SUNDAY);var second=seller(7,MONDAY.plusSeconds(60));
        var hold=new SettlementOrders.Hold(UUID.randomUUID(),SUNDAY.minusSeconds(86400),MONDAY);
        var seller=new SettlementOrders.Seller(first.orderId(),first.sellerOrderId(),first.storeId(),first.policyVersion(),first.shipping(),
                List.of(first.units().getFirst(),second.units().getFirst()),List.of(),"RETAINED",SUNDAY.minusSeconds(172800),List.of(hold));
        assertTrue(lines(service(null,List.of(seller),List.of(),null),START,END).isEmpty());
        var source=service(null,List.of(seller),List.of(),MONDAY.plusSeconds(86400)).sources(POLICY,END,END.plusSeconds(604800));
        var next=source.lines();
        var firstProduct=next.stream().filter(l->l.kind().equals("PRODUCT")&&l.item().equals(first.units().getFirst().itemId())).findFirst().orElseThrow();
        var secondProduct=next.stream().filter(l->l.kind().equals("PRODUCT")&&l.item().equals(second.units().getFirst().itemId())).findFirst().orElseThrow();
        assertEquals(SUNDAY,firstProduct.at());assertEquals(SUNDAY,firstProduct.eligibleAt());
        assertEquals(MONDAY,source.readiness().get(firstProduct.key()).eligibleAt());
        assertEquals(MONDAY.plusSeconds(60),source.readiness().get(secondProduct.key()).eligibleAt());
    }
    @Test void ordinaryOpenQuantityDoesNotDelayAnAlreadyConfirmedQuantity() {
        var first=seller(8,SUNDAY);var later=seller(9,MONDAY);
        var open=later.units().getFirst();
        var pending=new SettlementOrders.Unit(open.itemId(),open.ordinal(),open.gross(),0,0,open.net(),"OPEN",null,null,null,null,null);
        var partial=new SettlementOrders.Seller(first.orderId(),first.sellerOrderId(),first.storeId(),first.policyVersion(),first.shipping(),
                List.of(first.units().getFirst(),pending),List.of(),"PENDING");
        var sunday=lines(service(null,List.of(partial),List.of(),null),START,END);
        assertEquals(2,sunday.size());assertTrue(sunday.stream().noneMatch(l->l.kind().equals("SHIPPING")));
        assertEquals(10019,sunday.stream().mapToLong(SettlementService.Line::amount).sum());
        assertEquals(501,sunday.stream().mapToLong(SettlementService.Line::fee).sum());
        var complete=new SettlementOrders.Seller(first.orderId(),first.sellerOrderId(),first.storeId(),first.policyVersion(),first.shipping(),
                List.of(first.units().getFirst(),later.units().getFirst()),List.of(),"RETAINED");
        var oldWeek=lines(service(null,List.of(complete),List.of(),MONDAY.plusSeconds(60)),START,END);
        assertEquals(sunday,oldWeek);
        var next=lines(service(null,List.of(complete),List.of(),MONDAY.plusSeconds(60)),END,END.plusSeconds(604800));
        assertEquals(10019,next.stream().filter(l->l.kind().equals("PRODUCT")).mapToLong(SettlementService.Line::amount).sum());
        assertEquals(500,next.stream().mapToLong(SettlementService.Line::fee).sum());
        assertEquals(3000,next.stream().filter(l->l.kind().equals("SHIPPING")).mapToLong(SettlementService.Line::amount).sum());
    }
    private SettlementOrders.Seller withOpenPeerAndHolds(int ordinal,List<SettlementOrders.Hold> holds) {
        var original=seller(ordinal,SUNDAY);UUID other=new UUID(0,900+ordinal);
        var open=new SettlementOrders.Unit(other,0,10019,0,0,10019,"OPEN",null,null,null,null,null);
        return new SettlementOrders.Seller(original.orderId(),original.sellerOrderId(),original.storeId(),original.policyVersion(),original.shipping(),
                List.of(original.units().getFirst(),open),List.of(),"PENDING",START,holds);
    }
    @Test void holdStartedAfterConfirmationCarriesUnpaidCauseWithoutChangingEconomicFingerprint() {
        var original=lines(service(null,List.of(withOpenPeerAndHolds(10,List.of())),List.of(),null),START,END);
        var hold=new SettlementOrders.Hold(new UUID(0,1010),SUNDAY.plusSeconds(30),MONDAY);
        var released=withOpenPeerAndHolds(10,List.of(hold));
        assertTrue(lines(service(null,List.of(released),List.of(),null),START,END).isEmpty());
        var next=lines(service(null,List.of(released),List.of(),null),END,END.plusSeconds(604800));
        assertEquals(2,next.size());
        for(var old:original) {
            var carried=next.stream().filter(l->l.key().equals(old.key())).findFirst().orElseThrow();
            assertEquals(old.fingerprint(),carried.fingerprint());
            assertEquals(SUNDAY,carried.at());
        }
        var source=service(null,List.of(released),List.of(),null).sources(POLICY,END,END.plusSeconds(604800));
        for(var line:source.lines()) {
            assertEquals(MONDAY,source.readiness().get(line.key()).eligibleAt());
            assertEquals(List.of(new Settlements.EligibilityHold(hold.id(),hold.acquiredAt(),hold.releasedAt())),source.readiness().get(line.key()).holds());
        }
    }
    @Test void continuousHoldsCarryToFinalReleaseButUnrelatedNextWeekHoldDoesNotAbsorbPriorTrade() {
        var first=new SettlementOrders.Hold(new UUID(0,1110),SUNDAY.plusSeconds(30),MONDAY);
        var continuation=new SettlementOrders.Hold(new UUID(0,1111),MONDAY.minusSeconds(30),MONDAY.plusSeconds(604800));
        var seller=withOpenPeerAndHolds(11,List.of(first,continuation));
        assertTrue(lines(service(null,List.of(seller),List.of(),null),END,END.plusSeconds(604800)).isEmpty());
        assertEquals(2,lines(service(null,List.of(seller),List.of(),null),END.plusSeconds(604800),END.plusSeconds(1209600)).size());
        var unrelated=withOpenPeerAndHolds(11,List.of(new SettlementOrders.Hold(new UUID(0,1112),MONDAY,MONDAY.plusSeconds(60))));
        assertEquals(2,lines(service(null,List.of(unrelated),List.of(),null),START,END).size());
        assertTrue(lines(service(null,List.of(unrelated),List.of(),null),END,END.plusSeconds(604800)).isEmpty());
    }
    @Test void partialReturnShippingUsesFirstTerminalShippingDecisionWithoutMovingProductWeek() {
        var original=seller(12,SUNDAY);UUID attempt=new UUID(0,1210),cause=new UUID(0,1211),item=new UUID(0,1212);
        var returned=new SettlementOrders.Unit(item,0,10019,0,0,10019,"REFUNDED",null,null,cause,attempt,SUNDAY.minusSeconds(30),"RETURN");
        var seller=new SettlementOrders.Seller(original.orderId(),original.sellerOrderId(),original.storeId(),original.policyVersion(),3000,
                List.of(returned,original.units().getFirst()),List.of(),"RETAINED");
        var actual=new SettlementPayments.Refund(attempt,cause,seller.sellerOrderId(),new UUID(0,1213),1,"ITEM",10019,"SUCCEEDED",SUNDAY.minusSeconds(60));
        var prior=lines(service(null,List.of(seller),List.of(actual),MONDAY),START,END);
        assertEquals(0,prior.stream().filter(l->l.kind().equals("SHIPPING")).count());
        assertEquals(10019,prior.stream().filter(l->l.kind().equals("PRODUCT")&&l.item().equals(original.units().getFirst().itemId())).mapToLong(SettlementService.Line::amount).sum());
        var next=lines(service(null,List.of(seller),List.of(actual),MONDAY),END,END.plusSeconds(604800));
        assertEquals(3000,next.stream().filter(l->l.kind().equals("SHIPPING")).mapToLong(SettlementService.Line::amount).sum());
        assertEquals(MONDAY,next.stream().filter(l->l.kind().equals("SHIPPING")).findFirst().orElseThrow().at());
    }
    private SettlementOrders.Seller withHeldPeer(int ordinal,Instant acquiredAt) {
        var original=withOpenPeerAndHolds(ordinal,List.of(new SettlementOrders.Hold(new UUID(0,1500+ordinal),acquiredAt,null)));
        var peer=original.units().get(1);
        var held=new SettlementOrders.Unit(peer.itemId(),peer.ordinal(),peer.gross(),0,0,peer.net(),"HELD",null,null,null,null,null);
        return new SettlementOrders.Seller(original.orderId(),original.sellerOrderId(),original.storeId(),original.policyVersion(),original.shipping(),
                List.of(original.units().getFirst(),held),List.of(),"PENDING",START,original.holds());
    }
    @Test void cutoffAndLaterHoldRefundAndDueDoNotRetroactivelyChangePriorWeekSnapshot() {
        var baseline=service(null,List.of(withOpenPeerAndHolds(13,List.of())),List.of(),null).sources(POLICY,START,END);
        assertEquals(9518,baseline.lines().stream().mapToLong(l->l.amount()-l.fee()).sum());
        for(Instant began:List.of(END,MONDAY,MONDAY.plusSeconds(86400))) {
            var seller=withHeldPeer(13,began);
            var unknown=new SettlementPayments.Refund(new UUID(0,1613),new UUID(0,1713),seller.sellerOrderId(),new UUID(0,1813),1,"ITEM",10019,"UNKNOWN",null,began,null);
            var due=new SettlementClaims.View(true,List.of(),null,List.of(new SettlementClaims.Reservation(new UUID(0,1913),began,null)));
            var actual=serviceWithClaims(null,List.of(seller),List.of(unknown),due).sources(POLICY,START,END);
            assertEquals(baseline.lines(),actual.lines());assertEquals(baseline.readiness(),actual.readiness());assertEquals(baseline.held(),actual.held());
            assertTrue(actual.lines().stream().noneMatch(l->l.kind().equals("REFUND")));
        }
    }
    @Test void oneMicrosecondBeforeCutoffBlocksWhileExactCutoffHoldDoesNot() {
        var before=service(null,List.of(withHeldPeer(14,END.minusNanos(1000))),List.of(),null).sources(POLICY,START,END);
        assertTrue(before.lines().isEmpty());assertEquals("HELD_UNIT",before.held().getFirst().reason());
        var exact=service(null,List.of(withHeldPeer(14,END)),List.of(),null).sources(POLICY,START,END);
        assertEquals(9518,exact.lines().stream().mapToLong(l->l.amount()-l.fee()).sum());assertTrue(exact.held().isEmpty());
    }
    @Test void endedAndRetriedReservationsRetainTheirActualHistoricalReleaseIntervals() {
        var future=withOpenPeerAndHolds(15,List.of(new SettlementOrders.Hold(new UUID(0,2015),END,MONDAY.plusSeconds(86400))));
        var failed=new SettlementPayments.Refund(new UUID(0,2115),new UUID(0,2215),future.sellerOrderId(),new UUID(0,2315),1,"ITEM",10019,"FAILED",null,END,MONDAY);
        var old=service(null,List.of(future),List.of(failed),null).sources(POLICY,START,END);
        assertEquals(9518,old.lines().stream().mapToLong(l->l.amount()-l.fee()).sum());assertTrue(old.held().isEmpty());
        var historical=withOpenPeerAndHolds(16,List.of(new SettlementOrders.Hold(new UUID(0,2016),END.minusNanos(1000),MONDAY.plusSeconds(86400))));
        var first=new SettlementPayments.Refund(new UUID(0,2116),new UUID(0,2216),historical.sellerOrderId(),new UUID(0,2316),1,"ITEM",10019,"FAILED",null,END.minusNanos(1000),MONDAY);
        var retry=new SettlementPayments.Refund(new UUID(0,2416),new UUID(0,2516),historical.sellerOrderId(),first.claimId(),2,"ITEM",10019,"WITHDRAWN",null,MONDAY,MONDAY.plusSeconds(86400));
        var prior=service(null,List.of(historical),List.of(first,retry),null).sources(POLICY,START,END);
        assertTrue(prior.lines().isEmpty());assertFalse(prior.held().isEmpty());
        var next=service(null,List.of(historical),List.of(first,retry),null).sources(POLICY,END,END.plusSeconds(604800));
        assertEquals(9518,next.lines().stream().mapToLong(l->l.amount()-l.fee()).sum());
        assertTrue(next.lines().stream().noneMatch(l->l.kind().equals("REFUND")));
        assertTrue(next.readiness().values().stream().allMatch(r->r.eligibleAt().equals(MONDAY.plusSeconds(86400))));
    }
    @Test void reservationReleasedAfterQuantityHoldUsesItsActualReleaseInsteadOfPermanentExclusion() {
        var physical=new SettlementOrders.Hold(new UUID(0,3017),SUNDAY.plusSeconds(5),END.minusNanos(1000));
        var seller=withOpenPeerAndHolds(17,List.of(physical));
        var refund=new SettlementPayments.Refund(new UUID(0,3117),new UUID(0,3217),seller.sellerOrderId(),new UUID(0,3317),1,"ITEM",10019,"WITHDRAWN",null,SUNDAY.plusSeconds(11),END.minusNanos(2000));
        var assessment=new SettlementClaims.Reservation(new UUID(0,3417),SUNDAY.plusSeconds(10),END.plusNanos(1000));
        var view=new SettlementClaims.View(false,List.of(),null,List.of(assessment));
        var old=serviceWithClaims(null,List.of(seller),List.of(refund),view).sources(POLICY,START,END);
        assertTrue(old.lines().isEmpty());
        var next=serviceWithClaims(null,List.of(seller),List.of(refund),view).sources(POLICY,END,END.plusSeconds(604800));
        assertEquals(9518,next.lines().stream().mapToLong(l->l.amount()-l.fee()).sum());
        assertTrue(next.lines().stream().noneMatch(l->l.kind().equals("REFUND")));
        assertTrue(next.readiness().values().stream().allMatch(r->r.eligibleAt().equals(END.plusNanos(1000))));
        for(var ready:next.readiness().values()) {
            assertEquals(List.of(new Settlements.EligibilityHold(physical.id(),physical.acquiredAt(),physical.releasedAt())),ready.holds());
            assertTrue(ready.reservations().contains(new Settlements.EligibilityReservation(assessment.id(),"CLAIM_FEE",assessment.acquiredAt(),assessment.releasedAt())));
            assertTrue(ready.reservations().contains(new Settlements.EligibilityReservation(refund.id(),"REFUND",refund.reservedAt(),refund.releasedAt())));
        }
    }
    @Test void unpaidPriorWeekOwnershipBlocksTheWholeTargetUntilOriginalDraftIsRebuilt() {
        UUID target=new UUID(0,4018),prior=new UUID(0,4118);var week=java.time.LocalDate.of(2026,11,9);
        var hold=new SettlementOrders.Hold(new UUID(0,4218),END.minusNanos(1000),MONDAY);
        var carried=withOpenPeerAndHolds(18,List.of(hold));var unrelated=seller(19,MONDAY.plusSeconds(60));
        var source=service(null,List.of(carried,unrelated),List.of(),null).sources(POLICY,END,END.plusSeconds(604800));
        var keys=source.lines().stream().map(SettlementService.Line::key).collect(java.util.stream.Collectors.toSet());
        var blocked=source.lines().stream().filter(l->l.seller().equals(carried.sellerOrderId()))
                .map(l->new SettlementService.Assignment(l.key(),prior,week,"DRAFT")).toList();
        for(String state:List.of("DRAFT","REVIEWED")) {
            var owners=blocked.stream().map(a->new SettlementService.Assignment(a.sourceKey(),a.batchId(),a.weekStart(),state)).toList();
            var failure=assertThrows(Settlements.AssignmentConflict.class,()->SettlementService.paidElsewhereOrConflict(target,keys,owners));
            assertEquals(List.of(week),failure.conflictingWeeks());
        }
        assertTrue(SettlementService.paidElsewhereOrConflict(target,keys,List.of()).isEmpty());
        assertEquals(9518,source.lines().stream().filter(l->l.seller().equals(carried.sellerOrderId())).mapToLong(l->l.amount()-l.fee()).sum());
        assertTrue(source.lines().stream().anyMatch(l->l.seller().equals(unrelated.sellerOrderId())));
    }
    @Test void paidPriorWeekOwnershipOnlyDeduplicatesTheAlreadyPaidCause() {
        UUID target=new UUID(0,4019),paid=new UUID(0,4119);var week=java.time.LocalDate.of(2026,11,9);
        var keys=java.util.Set.of("paid-product","current-product");
        var owners=List.of(new SettlementService.Assignment("paid-product",paid,week,"PAID_MOCK"),
                new SettlementService.Assignment("current-product",target,week.plusWeeks(1),"DRAFT"));
        assertEquals(java.util.Set.of("paid-product"),SettlementService.paidElsewhereOrConflict(target,keys,owners));
        assertEquals(java.util.Set.of("paid-product","current-product"),keys);
    }
}
