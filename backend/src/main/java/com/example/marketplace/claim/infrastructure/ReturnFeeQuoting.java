package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.claim.domain.ClaimRules;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.ReturnSources;
import com.example.marketplace.order.api.Orders;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Shared arithmetic and original-shipment locking; callers own current authority and source selection. */
final class ReturnFeeQuoting {
 static final String FAMILY="P09-RETURN-PRODUCT-MAX-1";
 sealed interface InspectionEvidence permits Inspection,ExchangeInspection {
  UUID claimId();long revision();String fingerprint();String snapshot();String purpose();
 }
 // Keep the original record shape/toString: existing RETURN fingerprints and saved quotes are historical facts.
 record Inspection(UUID returnId,long revision,String fingerprint,String snapshot) implements InspectionEvidence {
  public UUID claimId() { return returnId; }public String purpose() { return "INSPECTED_RETURN"; }
 }
 record ExchangeInspection(UUID exchangeId,long revision,String fingerprint,String snapshot) implements InspectionEvidence {
  public UUID claimId() { return exchangeId; }public String purpose() { return "INSPECTED_EXCHANGE_REFUND"; }
 }
 private ReturnFeeQuoting() { }
 static ReturnFees.Quote write(NamedParameterJdbcTemplate jdbc,Orders orders,String number,ReturnSources.Selection selection,List<ReturnFees.Target> targets,InspectionEvidence inspection) {
  if(selection.units().stream().anyMatch(u->u.physical()!=null)) { return PhysicalFeeQuoting.write(jdbc,orders,number,selection,targets,inspection); }
  var buckets=lockBuckets(jdbc,selection);var baselines=new java.util.HashMap<UUID,Long>();for(var b:buckets) { if(b.get("active_assessment_id")!=null||((Number)b.get("reserved_increment")).longValue()!=0) { throw new OrderRejected(409); }baselines.put((UUID)b.get("original_shipment_id"),((Number)b.get("assessed_total")).longValue()); }
  var rules=ClaimRules.quote(selection.units().stream().map(u->{var target=targets.stream().filter(a->a.itemId().equals(u.itemId())&&a.ordinal()==u.ordinal()).findFirst().orElseThrow();long fee=u.returnPolicy()==null?3000:u.returnPolicy().checkedAmount();return new ClaimRules.Target(u.originalShipmentId(),u.itemId(),u.ordinal(),u.net(),fee,target.responsibility()==ReturnFees.Responsibility.BUYER);}).toList(),baselines);
  var units=rules.allocations().stream().map(a->{var source=selection.units().stream().filter(u->u.itemId().equals(a.itemId())&&u.ordinal()==a.ordinal()).findFirst().orElseThrow();var policy=source.returnPolicy();return new ReturnFees.UnitQuote(a.itemId(),a.ordinal(),a.shipmentId(),Long.toString(a.net()),Long.toString(a.policyFee()),policy==null?"LEGACY_UNSPECIFIED":policy.source(),FAMILY,policy==null?0:policy.revision(),a.buyerResponsible()?ReturnFees.Responsibility.BUYER:ReturnFees.Responsibility.SELLER,Long.toString(a.deducted()));}).toList();
  var bucketQuotes=rules.buckets().stream().map(b->{var saved=buckets.stream().filter(row->row.get("original_shipment_id").equals(b.shipmentId())).findFirst().orElseThrow();return new ReturnFees.BucketQuote(b.shipmentId(),((Number)saved.get("revision")).longValue(),Long.toString(b.baseline()),Long.toString(b.selectedMaximum()),Long.toString(b.target()),Long.toString(b.increment()),Long.toString(b.deducted()),Long.toString(b.additionalDue()));}).toList();
  boolean legacy=selection.units().stream().anyMatch(u->u.policyStatus().equals("UNRESOLVED"));String original=selection.orderId()+"|"+selection.sellerOrderId()+"|"+selection.revision()+"|"+FAMILY+"|"+units+"|"+bucketQuotes+"|"+rules.net()+"|"+rules.increment()+"|"+rules.refund()+"|"+rules.additionalDue()+"|"+legacy;
  String fingerprint=hash(inspection==null?original:original+"|"+inspection.purpose()+"|"+inspection.claimId()+"|"+inspection.revision()+"|"+inspection.fingerprint());UUID id=UUID.randomUUID();Instant at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();
  var quote=new ReturnFees.Quote(id,fingerprint,number,selection.sellerOrderId(),selection.revision(),at,units,bucketQuotes,Long.toString(rules.net()),Long.toString(rules.increment()),Long.toString(rules.refund()),Long.toString(rules.additionalDue()),legacy);var snapshot=orders.find(number);var mapper=new tools.jackson.databind.ObjectMapper();
  jdbc.update("INSERT INTO marketplace.claim_fee_quote(id,order_id,seller_order_id,fingerprint,order_revision,member_id,guest_buyer_id,quoted_at,legacy_consent_required,body,purpose) VALUES(:id,:order,:seller,:hash,:revision,:member,:guest,:at,:legacy,CAST(:body AS jsonb),:purpose)",new MapSqlParameterSource("id",id).addValue("order",selection.orderId()).addValue("seller",selection.sellerOrderId()).addValue("hash",fingerprint).addValue("revision",selection.revision()).addValue("member",snapshot.memberId()).addValue("guest",snapshot.guestBuyerId()).addValue("at",Timestamp.from(at)).addValue("legacy",legacy).addValue("body",mapper.writeValueAsString(quote)).addValue("purpose",inspection==null?"OPEN":inspection.purpose()));
  writeInspectionContext(jdbc,id,inspection);
  return quote;
 }
 static void writeInspectionContext(NamedParameterJdbcTemplate jdbc,UUID quoteId,InspectionEvidence inspection) {
  if(inspection==null) { return; }
  String table=inspection instanceof ExchangeInspection?"claim_exchange_refund_fee_quote_context":"claim_return_fee_quote_context";
  String identity=inspection instanceof ExchangeInspection?"exchange_id,exchange_revision":"return_id,return_revision";
  jdbc.update("INSERT INTO marketplace."+table+"(quote_id,"+identity+",inspection_fingerprint,inspection_snapshot) VALUES(:quote,:id,:revision,:hash,CAST(:snapshot AS jsonb))",Map.of("quote",quoteId,"id",inspection.claimId(),"revision",inspection.revision(),"hash",inspection.fingerprint(),"snapshot",inspection.snapshot()));
 }
 static List<Map<String,Object>> lockBuckets(NamedParameterJdbcTemplate jdbc,ReturnSources.Selection selection) {
  var result=new ArrayList<Map<String,Object>>();var ids=selection.units().stream().map(ReturnSources.Unit::originalShipmentId).distinct().map(shipment->Map.entry(named(selection.orderId()+"|"+selection.sellerOrderId()+"|"+shipment+"|"+FAMILY),shipment)).sorted(java.util.Comparator.comparing(e->e.getKey().toString())).toList();
  for(var pair:ids) { var p=Map.of("id",pair.getKey(),"order",selection.orderId(),"seller",selection.sellerOrderId(),"shipment",pair.getValue(),"family",FAMILY);jdbc.update("INSERT INTO marketplace.claim_fee_bucket(id,order_id,seller_order_id,original_shipment_id,family) VALUES(:id,:order,:seller,:shipment,:family) ON CONFLICT DO NOTHING",p);var bucket=jdbc.queryForMap("SELECT * FROM marketplace.claim_fee_bucket WHERE id=:id FOR UPDATE",p);if(!bucket.get("order_id").equals(selection.orderId())||!bucket.get("seller_order_id").equals(selection.sellerOrderId())||!bucket.get("original_shipment_id").equals(pair.getValue())||!bucket.get("family").equals(FAMILY)) { throw new OrderRejected(409); }result.add(bucket); }
  return List.copyOf(result);
 }
 static String hash(String text) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
 private static UUID named(String text) { return UUID.nameUUIDFromBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
