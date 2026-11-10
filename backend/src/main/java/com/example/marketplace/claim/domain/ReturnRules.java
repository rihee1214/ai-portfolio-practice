package com.example.marketplace.claim.domain;

import com.example.marketplace.claim.api.Returns;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class ReturnRules {
 private ReturnRules() { }
 public static List<Returns.Unit> canonical(List<Returns.Unit> units) {
  if(units==null||units.isEmpty()||units.size()>2000||units.stream().anyMatch(u->u==null||u.itemId()==null||u.ordinal()<0||u.responsibility()==null)) {
   throw new IllegalArgumentException("RETURN_UNITS");
  }
  var result=units.stream().sorted(Comparator.comparing((Returns.Unit u)->u.itemId().toString()).thenComparingInt(Returns.Unit::ordinal)).toList();
  if(result.stream().map(u->u.itemId()+":"+u.ordinal()).distinct().count()!=result.size()) { throw new IllegalArgumentException("RETURN_DUPLICATE_UNIT"); }
  return result;
 }
 /** Deadline arithmetic only; callers first prove actual delivery and the accepted source graph. */
 public static boolean beforeVerifiedDeliveryDeadline(Instant delivered,Instant now,long seconds) {
  if(delivered==null||now==null||seconds<=0) { return false; }
  try { return now.isBefore(delivered.plusSeconds(seconds)); }catch(java.time.DateTimeException|ArithmeticException overflow) { return false; }
 }
}
