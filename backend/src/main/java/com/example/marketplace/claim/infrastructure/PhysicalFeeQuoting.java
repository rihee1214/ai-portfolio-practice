package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.claim.domain.ClaimRules;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.api.PhysicalOrders;
import com.example.marketplace.order.api.ReturnSources;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Financial origins remain original units; only the actual delivered grouping changes. */
final class PhysicalFeeQuoting {
 private PhysicalFeeQuoting() { }
 static boolean typed(ReturnFees.Quote quote) { return quote.units().stream().anyMatch(u->u.physicalId()!=null); }
 static UUID group(ReturnFees.UnitQuote unit) { return unit.deliveryBucketId()==null?unit.originalShipmentId():unit.deliveryBucketId(); }
 static UUID group(ReturnFees.BucketQuote bucket) { return bucket.deliveryBucketId()==null?bucket.originalShipmentId():bucket.deliveryBucketId(); }
 static String ledger(Map<String,Object> bucket) { return "EXCHANGE".equals(bucket.get("source_kind"))?"claim_exchange_fee_bucket":"claim_fee_bucket"; }
 static List<Map<String,Object>> lock(NamedParameterJdbcTemplate jdbc,ReturnSources.Selection selection) {
  if(selection.units().stream().anyMatch(u->u.physical()==null||u.physical().delivery()==null)) { throw new OrderRejected(409); }
  var sources=selection.units().stream().map(ReturnSources.Unit::physical).collect(java.util.stream.Collectors.toMap(s->s.delivery().bucketId(),s->s,(a,b)->a));
  var ids=sources.values().stream().map(s->Map.entry(named(selection.orderId()+"|"+selection.sellerOrderId()+"|"+s.delivery().bucketId()+"|"+ReturnFeeQuoting.FAMILY),s)).sorted(java.util.Comparator.comparing(e->e.getKey().toString())).toList();
  var result=new java.util.ArrayList<Map<String,Object>>();
  for(var pair:ids) {
   var source=pair.getValue();var d=source.delivery();boolean basic=d.kind()==PhysicalOrders.BucketKind.BASIC;
   var p=new MapSqlParameterSource("id",pair.getKey()).addValue("order",selection.orderId()).addValue("seller",selection.sellerOrderId()).addValue("delivery",d.bucketId()).addValue("kind",d.kind().name()).addValue("family",ReturnFeeQuoting.FAMILY);
   if(basic) { jdbc.update("INSERT INTO marketplace.claim_fee_bucket(id,order_id,seller_order_id,original_shipment_id,family) VALUES(:id,:order,:seller,:delivery,:family) ON CONFLICT DO NOTHING",p); }
   jdbc.update("INSERT INTO marketplace.claim_delivery_fee_bucket(id,order_id,seller_order_id,delivery_bucket_id,source_kind,family) VALUES(:id,:order,:seller,:delivery,:kind,:family) ON CONFLICT DO NOTHING",p);
   if(basic) { jdbc.update("INSERT INTO marketplace.claim_basic_fee_bucket(id,delivery_bucket_id,source_kind) VALUES(:id,:delivery,'BASIC') ON CONFLICT DO NOTHING",p); }
   else { jdbc.update("INSERT INTO marketplace.claim_exchange_fee_bucket(id,delivery_bucket_id,source_kind) VALUES(:id,:delivery,'EXCHANGE') ON CONFLICT DO NOTHING",p); }
   var root=jdbc.queryForMap("SELECT * FROM marketplace.claim_delivery_fee_bucket WHERE id=:id",p);
   if(!root.get("order_id").equals(selection.orderId())||!root.get("seller_order_id").equals(selection.sellerOrderId())||!root.get("delivery_bucket_id").equals(d.bucketId())||!root.get("source_kind").equals(d.kind().name())) { throw new OrderRejected(409); }
   var row=new java.util.HashMap<>(jdbc.queryForMap("SELECT * FROM marketplace."+(basic?"claim_fee_bucket":"claim_exchange_fee_bucket")+" WHERE id=:id FOR UPDATE",p));row.putAll(root);result.add(row);
  }
  return List.copyOf(result);
 }
 static ReturnFees.Quote write(NamedParameterJdbcTemplate jdbc,Orders orders,String number,ReturnSources.Selection selection,List<ReturnFees.Target> targets,ReturnFeeQuoting.InspectionEvidence inspection) {
  var buckets=lock(jdbc,selection);var baseline=new java.util.HashMap<UUID,Long>();
  for(var b:buckets) { if(b.get("active_assessment_id")!=null||((Number)b.get("reserved_increment")).longValue()!=0) { throw new OrderRejected(409); }baseline.put((UUID)b.get("delivery_bucket_id"),((Number)b.get("assessed_total")).longValue()); }
  var rules=ClaimRules.quote(selection.units().stream().map(u->{var target=targets.stream().filter(t->t.itemId().equals(u.itemId())&&t.ordinal()==u.ordinal()).findFirst().orElseThrow();return new ClaimRules.Target(u.physical().delivery().bucketId(),u.itemId(),u.ordinal(),u.net(),u.returnPolicy()==null?3000:u.returnPolicy().checkedAmount(),target.responsibility()==ReturnFees.Responsibility.BUYER);}).toList(),baseline);
  var units=rules.allocations().stream().map(a->{var u=selection.units().stream().filter(s->s.itemId().equals(a.itemId())&&s.ordinal()==a.ordinal()).findFirst().orElseThrow();var policy=u.returnPolicy();var s=u.physical();return new ReturnFees.UnitQuote(u.itemId(),u.ordinal(),u.originalShipmentId(),Long.toString(u.net()),Long.toString(a.policyFee()),policy==null?"LEGACY_UNSPECIFIED":policy.source(),ReturnFeeQuoting.FAMILY,policy==null?0:policy.revision(),a.buyerResponsible()?ReturnFees.Responsibility.BUYER:ReturnFees.Responsibility.SELLER,Long.toString(a.deducted()),s.id(),s.stock().skuId(),s.delivery().bucketId(),s.delivery().kind());}).toList();
  var quotedBuckets=rules.buckets().stream().map(b->{var row=buckets.stream().filter(r->r.get("delivery_bucket_id").equals(b.shipmentId())).findFirst().orElseThrow();var kind=PhysicalOrders.BucketKind.valueOf(row.get("source_kind").toString());return new ReturnFees.BucketQuote(kind==PhysicalOrders.BucketKind.BASIC?b.shipmentId():null,((Number)row.get("revision")).longValue(),Long.toString(b.baseline()),Long.toString(b.selectedMaximum()),Long.toString(b.target()),Long.toString(b.increment()),Long.toString(b.deducted()),Long.toString(b.additionalDue()),b.shipmentId(),kind);}).toList();
  boolean legacy=selection.units().stream().anyMatch(u->u.policyStatus().equals("UNRESOLVED"));String hash=ReturnFeeQuoting.hash(selection+"|"+ReturnFeeQuoting.FAMILY+"|"+units+"|"+quotedBuckets+"|"+legacy+"|"+inspection);UUID id=UUID.randomUUID();var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();
  var quote=new ReturnFees.Quote(id,hash,number,selection.sellerOrderId(),selection.revision(),at,units,quotedBuckets,Long.toString(rules.net()),Long.toString(rules.increment()),Long.toString(rules.refund()),Long.toString(rules.additionalDue()),legacy);var own=orders.find(number);var mapper=new tools.jackson.databind.ObjectMapper();
  jdbc.update("INSERT INTO marketplace.claim_fee_quote(id,order_id,seller_order_id,fingerprint,order_revision,member_id,guest_buyer_id,quoted_at,legacy_consent_required,body,purpose,source_schema_version) VALUES(:id,:order,:seller,:hash,:revision,:member,:guest,:at,:legacy,CAST(:body AS jsonb),:purpose,2)",new MapSqlParameterSource("id",id).addValue("order",selection.orderId()).addValue("seller",selection.sellerOrderId()).addValue("hash",hash).addValue("revision",selection.revision()).addValue("member",own.memberId()).addValue("guest",own.guestBuyerId()).addValue("at",Timestamp.from(at)).addValue("legacy",legacy).addValue("body",mapper.writeValueAsString(quote)).addValue("purpose",inspection==null?"OPEN":inspection.purpose()));
  for(var u:selection.units()) { var s=u.physical();jdbc.update("INSERT INTO marketplace.claim_fee_quote_physical_unit(quote_id,item_id,ordinal,physical_id,delivery_bucket_id,source_kind,source_snapshot) VALUES(:quote,:item,:ordinal,:physical,:bucket,:kind,CAST(:source AS jsonb))",Map.of("quote",id,"item",u.itemId(),"ordinal",u.ordinal(),"physical",s.id(),"bucket",s.delivery().bucketId(),"kind",s.delivery().kind().name(),"source",mapper.writeValueAsString(s))); }
  ReturnFeeQuoting.writeInspectionContext(jdbc,id,inspection);
  return quote;
 }
 static void lockAssessment(NamedParameterJdbcTemplate jdbc,UUID assessment) {
  for(var row:jdbc.queryForList("SELECT bucket_id,source_kind FROM marketplace.claim_all_fee_assessment_bucket WHERE assessment_id=:id ORDER BY bucket_id::text",Map.of("id",assessment))) { jdbc.queryForList("SELECT id FROM marketplace."+ledger(row)+" WHERE id=:id FOR UPDATE",Map.of("id",row.get("bucket_id"))); }
 }
 private static UUID named(String text) { return UUID.nameUUIDFromBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
