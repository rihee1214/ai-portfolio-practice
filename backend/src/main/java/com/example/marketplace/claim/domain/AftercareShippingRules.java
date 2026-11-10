package com.example.marketplace.claim.domain;

import java.util.List;
import java.util.UUID;

/** The caller supplies every original seller unit with verified immutable evidence. */
public final class AftercareShippingRules {
 public enum Kind { OPEN, UNRESOLVED, CONFIRMED, CANCELED, SELLER_RETURN_READY, BUYER_RETURN_READY, SELLER_EXCHANGE_REFUND_READY, BUYER_EXCHANGE_REFUND_READY }
 public enum State { PENDING, RETAINED, REFUND_REQUIRED }
 public enum Reason { INCOMPLETE_UNITS, ALL_UNSHIPPED_CANCEL, ALL_SELLER_RETURN_OR_CANCEL, BUYER_RETURN_PRESENT, CONFIRMED_UNITS_REMAIN, ALL_SELLER_AFTERCARE_OR_CANCEL, BUYER_EXCHANGE_REFUND_PRESENT }
 public record Unit(UUID itemId,int ordinal,Kind kind,UUID factId) { }
 public record Evaluation(State state,long amount,boolean allCanceled,Reason reason) { }
 private AftercareShippingRules() { }
 public static Evaluation evaluate(long originalShipping,List<Unit> units) {
  if(originalShipping<0||units==null||units.isEmpty()||units.stream().anyMatch(u->u==null||u.itemId()==null||u.ordinal()<0||u.ordinal()>98||u.kind()==null||(u.kind()!=Kind.OPEN&&u.kind()!=Kind.UNRESOLVED&&u.factId()==null))||units.stream().map(u->u.itemId()+":"+u.ordinal()).distinct().count()!=units.size()) { throw new IllegalArgumentException("INVALID_ORIGINAL_AFTERCARE_EVIDENCE"); }
  if(units.stream().anyMatch(u->u.kind()==Kind.OPEN||u.kind()==Kind.UNRESOLVED)) { return new Evaluation(State.PENDING,0,false,Reason.INCOMPLETE_UNITS); }
  boolean canceled=units.stream().allMatch(u->u.kind()==Kind.CANCELED);
  boolean required=units.stream().allMatch(u->u.kind()==Kind.CANCELED||u.kind()==Kind.SELLER_RETURN_READY||u.kind()==Kind.SELLER_EXCHANGE_REFUND_READY);
  Reason reason=required?(canceled?Reason.ALL_UNSHIPPED_CANCEL:units.stream().anyMatch(u->u.kind()==Kind.SELLER_EXCHANGE_REFUND_READY)?Reason.ALL_SELLER_AFTERCARE_OR_CANCEL:Reason.ALL_SELLER_RETURN_OR_CANCEL):units.stream().anyMatch(u->u.kind()==Kind.CONFIRMED)?Reason.CONFIRMED_UNITS_REMAIN:units.stream().anyMatch(u->u.kind()==Kind.BUYER_EXCHANGE_REFUND_READY)?Reason.BUYER_EXCHANGE_REFUND_PRESENT:Reason.BUYER_RETURN_PRESENT;
  return new Evaluation(required?State.REFUND_REQUIRED:State.RETAINED,required?originalShipping:0,canceled,reason);
 }
}
