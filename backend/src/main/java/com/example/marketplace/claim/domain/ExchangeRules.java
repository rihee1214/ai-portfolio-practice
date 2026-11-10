package com.example.marketplace.claim.domain;
import com.example.marketplace.claim.api.Exchanges;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
public final class ExchangeRules {
 public static final String POLICY="P09-E-EXCHANGE-1";
 public static final long RESERVATION_SECONDS=604800;
 private ExchangeRules() { }
 public static List<Exchanges.Unit> canonical(List<Exchanges.Unit> input) {
  if(input==null||input.isEmpty()||input.size()>1980) { throw new IllegalArgumentException("INVALID_EXCHANGE_UNITS"); }var seen=new HashSet<String>();for(var u:input) { if(u==null||u.itemId()==null||u.ordinal()<0||u.ordinal()>98||u.targetSkuId()==null||u.responsibility()==null||!seen.add(u.itemId()+":"+u.ordinal())) { throw new IllegalArgumentException("INVALID_EXCHANGE_UNITS"); } }return input.stream().sorted(Comparator.comparing((Exchanges.Unit u)->u.itemId().toString()).thenComparingInt(Exchanges.Unit::ordinal)).toList();
 }
 public static boolean samePrice(long storedGrossUnitPrice,long targetPrice) { return storedGrossUnitPrice>=0&&storedGrossUnitPrice==targetPrice; }
 public static Instant expires(Instant approvedAt) { return approvedAt.truncatedTo(ChronoUnit.MICROS).plusSeconds(RESERVATION_SECONDS); }
}
