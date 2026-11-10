package com.example.marketplace.claim.domain;
import com.example.marketplace.claim.api.Exchanges;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class ExchangeRulesTest {
 @Test void priceUsesOriginalGrossUnitPriceRatherThanDiscountedNet() { assertTrue(ExchangeRules.samePrice(33001,33001));assertFalse(ExchangeRules.samePrice(33001,26004));assertFalse(ExchangeRules.samePrice(33001,33002));assertTrue(ExchangeRules.samePrice(0,0));assertFalse(ExchangeRules.samePrice(-1,-1)); }
 @Test void canonicalUnitsPreserveTargetAndResponsibilityAndRejectDuplicateOriginalOrdinal() { UUID item=UUID.randomUUID(),target=UUID.randomUUID();var first=new Exchanges.Unit(item,0,target,Exchanges.Responsibility.SELLER);var second=new Exchanges.Unit(item,1,target,Exchanges.Responsibility.BUYER);assertEquals(List.of(first,second),ExchangeRules.canonical(List.of(second,first)));assertThrows(IllegalArgumentException.class,()->ExchangeRules.canonical(List.of(first,new Exchanges.Unit(item,0,UUID.randomUUID(),Exchanges.Responsibility.BUYER)))); }
 @Test void invalidTargetOrdinalAndResponsibilityNeverProduceCanonicalRequest() { UUID item=UUID.randomUUID(),target=UUID.randomUUID();for(var u:List.of(new Exchanges.Unit(item,-1,target,Exchanges.Responsibility.BUYER),new Exchanges.Unit(item,99,target,Exchanges.Responsibility.BUYER),new Exchanges.Unit(item,0,null,Exchanges.Responsibility.BUYER),new Exchanges.Unit(item,0,target,null))) { assertThrows(IllegalArgumentException.class,()->ExchangeRules.canonical(List.of(u))); }assertThrows(IllegalArgumentException.class,()->ExchangeRules.canonical(List.of())); }
 @Test void approvalCreatesExactlySevenDaysAtDatabaseMicrosecondPrecision() { var at=Instant.parse("2026-11-02T00:00:00.123456789Z");assertEquals(Instant.parse("2026-11-09T00:00:00.123456Z"),ExchangeRules.expires(at));assertEquals(604800,ExchangeRules.RESERVATION_SECONDS); }
}
