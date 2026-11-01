package com.example.marketplace.claim.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure common fee calculation. No claim completion, collections or return workflow effects. */
public final class ClaimRules {
 private ClaimRules() { }
 public record Target(UUID shipmentId,UUID itemId,int ordinal,long net,long policyFee,boolean buyerResponsible) { }
 public record Allocation(UUID shipmentId,UUID itemId,int ordinal,long net,long policyFee,boolean buyerResponsible,long deducted) { }
 public record Bucket(UUID shipmentId,long baseline,long selectedMaximum,long target,long increment,long deducted,long additionalDue) { }
 public record Quote(List<Allocation> allocations,List<Bucket> buckets,long net,long increment,long refund,long additionalDue) { public Quote { allocations=List.copyOf(allocations);buckets=List.copyOf(buckets); } }
 public static Quote quote(List<Target> targets,Map<UUID,Long> priorAssessments) {
  if(targets==null||targets.isEmpty()||targets.size()>2000||priorAssessments==null||priorAssessments.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null||e.getValue()<0)) { throw new IllegalArgumentException("INVALID_FEE_INPUT"); }
  var seen=new java.util.HashSet<String>();var groups=new java.util.TreeMap<UUID,List<Target>>(java.util.Comparator.comparing(UUID::toString));
  for(var t:targets) {
   if(t==null||t.shipmentId()==null||t.itemId()==null||t.ordinal()<0||t.ordinal()>98||t.net()<0||t.policyFee()<0||!seen.add(t.itemId()+":"+t.ordinal())) { throw new IllegalArgumentException("INVALID_FEE_ORIGINAL_UNIT"); }
   groups.computeIfAbsent(t.shipmentId(),key->new java.util.ArrayList<>()).add(t);
  }
  var allocations=new java.util.ArrayList<Allocation>();var buckets=new java.util.ArrayList<Bucket>();long net=0,increment=0,deducted=0,additional=0;
  for(var entry:groups.entrySet()) {
   UUID shipment=entry.getKey();var units=entry.getValue().stream().sorted(java.util.Comparator.comparing((Target t)->t.itemId().toString()).thenComparingInt(Target::ordinal)).toList();long baseline=priorAssessments.getOrDefault(shipment,0L),maximum=units.stream().filter(Target::buyerResponsible).mapToLong(Target::policyFee).max().orElse(0),target=Math.max(baseline,maximum),delta=Math.subtractExact(target,baseline),remaining=delta;
   for(var t:units) {
    long share=t.buyerResponsible()?Math.min(remaining,t.net()):0;remaining=Math.subtractExact(remaining,share);net=Math.addExact(net,t.net());deducted=Math.addExact(deducted,share);allocations.add(new Allocation(shipment,t.itemId(),t.ordinal(),t.net(),t.policyFee(),t.buyerResponsible(),share));
   }
   increment=Math.addExact(increment,delta);additional=Math.addExact(additional,remaining);buckets.add(new Bucket(shipment,baseline,maximum,target,delta,Math.subtractExact(delta,remaining),remaining));
  }
  return new Quote(allocations,buckets,net,increment,Math.subtractExact(net,deducted),additional);
 }
}
