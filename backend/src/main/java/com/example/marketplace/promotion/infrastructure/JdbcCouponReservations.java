package com.example.marketplace.promotion.infrastructure;
import com.example.marketplace.promotion.api.CouponReservations;
import com.example.marketplace.promotion.api.Quotes;
import com.example.marketplace.promotion.api.Coupons;
import com.example.marketplace.promotion.api.PromotionRejected;
import com.example.marketplace.promotion.domain.CouponReservationRules;
import com.example.marketplace.promotion.domain.QuoteCalculator;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.seller.api.Stores;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
@Service @Profile("db")
public final class JdbcCouponReservations implements CouponReservations {
 private final NamedParameterJdbcTemplate jdbc;private final Quotes quotes;private final Coupons coupons;private final SellerMembership members;private final Stores stores;private final ObjectMapper mapper;private final Clock clock;private final TransactionTemplate tx;
 @Autowired public JdbcCouponReservations(NamedParameterJdbcTemplate jdbc,Quotes quotes,Coupons coupons,SellerMembership members,Stores stores,ObjectMapper mapper,PlatformTransactionManager manager){this(jdbc,quotes,coupons,members,stores,mapper,manager,Clock.systemUTC());}
 public JdbcCouponReservations(NamedParameterJdbcTemplate jdbc,Quotes quotes,Coupons coupons,SellerMembership members,Stores stores,ObjectMapper mapper,PlatformTransactionManager manager,Clock clock){this.jdbc=jdbc;this.quotes=quotes;this.coupons=coupons;this.members=members;this.stores=stores;this.mapper=mapper;this.clock=clock;tx=new TransactionTemplate(manager);}
 private static final java.util.Comparator<UUID> IDS=java.util.Comparator.comparing(UUID::toString);
 private String hash(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private Instant deadline(Instant time){return time.truncatedTo(ChronoUnit.MICROS);}
 @Override public List<Reservation> reserve(List<Request> input){
  if(input==null||input.isEmpty()||input.size()>20||input.stream().anyMatch(r->r==null||r.reservationId()==null||r.checkoutId()==null||r.sellerOrderRef()==null||r.memberId()==null||r.storeId()==null||r.issuedCouponId()==null||r.quoteSnapshot()==null||r.expiresAt()==null)){throw new PromotionRejected(400);}
  var requests=input.stream().sorted(java.util.Comparator.comparing(r->r.issuedCouponId().toString())).toList();var first=requests.getFirst();
  if(requests.stream().map(Request::reservationId).distinct().count()!=requests.size()||requests.stream().map(Request::issuedCouponId).distinct().count()!=requests.size()||requests.stream().map(Request::storeId).distinct().count()!=requests.size()||requests.stream().map(Request::sellerOrderRef).distinct().count()!=requests.size()||requests.stream().anyMatch(r->!r.checkoutId().equals(first.checkoutId())||!r.memberId().equals(first.memberId()))){throw new PromotionRejected(400);}
  String fingerprint;
  try{fingerprint=QuoteCalculator.fingerprint(first.quoteSnapshot());if(requests.stream().anyMatch(r->!r.memberId().equals(r.quoteSnapshot().memberId())||!fingerprint.equals(QuoteCalculator.fingerprint(r.quoteSnapshot()))||!fingerprint.equals(r.quoteSnapshot().fingerprint()))){throw new PromotionRejected(409);}}
  catch(NullPointerException|IllegalArgumentException e){throw new PromotionRejected(400);}
  String requestHash=hash(requests.stream().map(r->r.reservationId()+"|"+r.checkoutId()+"|"+r.sellerOrderRef()+"|"+r.memberId()+"|"+r.storeId()+"|"+r.issuedCouponId()+"|"+deadline(r.expiresAt())+"|"+fingerprint).collect(java.util.stream.Collectors.joining("\n")));
  try{return tx.execute(status->{
   var batchParameters=Map.of("checkout",first.checkoutId(),"member",first.memberId(),"hash",requestHash);
   jdbc.update("INSERT INTO marketplace.coupon_reservation_batch(checkout_id,member_id,request_hash) VALUES(:checkout,:member,:hash) ON CONFLICT DO NOTHING",batchParameters);
   var batch=jdbc.queryForMap("SELECT member_id,request_hash FROM marketplace.coupon_reservation_batch WHERE checkout_id=:checkout FOR UPDATE",batchParameters);
   if(!first.memberId().equals(batch.get("member_id"))||!requestHash.equals(batch.get("request_hash"))){throw new PromotionRejected(409);}
   var prior=prior(requests,requestHash);if(prior!=null){return prior;}
   var quote=first.quoteSnapshot();var owners=new java.util.TreeSet<UUID>(IDS);owners.add(first.memberId());var sellers=new java.util.TreeSet<UUID>(IDS);
   for(var store:quote.stores()){var rows=jdbc.queryForList("SELECT owner_id FROM marketplace.seller_store WHERE id=:id",Map.of("id",store.storeId()),UUID.class);if(rows.size()!=1){throw new PromotionRejected(409);}sellers.add(rows.getFirst());owners.add(rows.getFirst());}
   owners.forEach(members::lockActiveBuyer);sellers.forEach(owner->stores.requireCapability(owner,Stores.Capability.NEW_SALES));lockCoupons(requests.stream().map(Request::issuedCouponId).toList());
   prior=prior(requests,requestHash);if(prior!=null){return prior;}
   var selections=quote.stores().stream().filter(s->s.coupon()!=null).map(s->new Quotes.CouponSelection(s.storeId(),s.coupon().id())).toList();
   if(selections.size()!=requests.size()||requests.stream().anyMatch(r->selections.stream().noneMatch(s->s.storeId().equals(r.storeId())&&s.couponId().equals(r.issuedCouponId())))){throw new PromotionRejected(409);}
   var current=quotes.calculate(first.memberId(),quote.stores().stream().flatMap(s->s.lines().stream()).map(l->new Quotes.LineRequest(l.skuId(),l.quantity())).toList(),selections);
   if(!fingerprint.equals(current.fingerprint())){throw new PromotionRejected(409);}Instant now=clock.instant();
   for(var r:requests){var c=coupons.own(r.memberId(),r.issuedCouponId());Instant expires=deadline(r.expiresAt());if(!expires.isAfter(now)||expires.isAfter(now.plusSeconds(900))||expires.isAfter(c.terms().endsAt())||!c.storeId().equals(r.storeId())||!c.state().equals("AVAILABLE")){throw new PromotionRejected(409);}
    var p=new MapSqlParameterSource("id",r.reservationId()).addValue("checkout",r.checkoutId()).addValue("order",r.sellerOrderRef()).addValue("member",r.memberId()).addValue("store",r.storeId()).addValue("coupon",r.issuedCouponId()).addValue("hash",requestHash).addValue("snapshot",mapper.writeValueAsString(current)).addValue("expires",java.sql.Timestamp.from(expires));
    jdbc.update("INSERT INTO marketplace.coupon_reservation(id,checkout_id,seller_order_ref,member_id,store_id,coupon_id,request_hash,quote_snapshot,expires_at,state) VALUES(:id,:checkout,:order,:member,:store,:coupon,:hash,CAST(:snapshot AS jsonb),:expires,'RESERVED')",p);changeCoupon(r.issuedCouponId(),"AVAILABLE","RESERVED");event(r.reservationId(),"RESERVED",0);
   }
   return requests.stream().map(r->find(r.reservationId())).toList();
  });}catch(org.springframework.dao.DuplicateKeyException e){throw new PromotionRejected(409);}
 }
 private record Saved(Reservation reservation,String hash){}
 private List<Reservation> prior(List<Request> requests,String hash){
  var rows=jdbc.query("SELECT * FROM marketplace.coupon_reservation WHERE id IN (:ids) ORDER BY coupon_id",Map.of("ids",requests.stream().map(Request::reservationId).toList()),(r,n)->new Saved(map(r,n),r.getString("request_hash")));
  if(rows.isEmpty()){return null;}if(rows.size()!=requests.size()||rows.stream().anyMatch(row->!hash.equals(row.hash()))){throw new PromotionRejected(409);}return rows.stream().map(Saved::reservation).toList();
 }
 private void lockCoupons(List<UUID> ids){for(UUID id:ids.stream().distinct().sorted(IDS).toList()){var rows=jdbc.queryForList("SELECT id FROM marketplace.issued_coupon WHERE id=:id FOR UPDATE",Map.of("id",id),UUID.class);if(rows.size()!=1){throw new PromotionRejected(404);}}}
 @Override public List<Reservation> commit(UUID checkout,List<UUID> ids){return transition(checkout,ids,"COMMITTED");}
 @Override public List<Reservation> inspect(UUID checkout,List<UUID> ids){return tx.execute(status->{var rows=ids.stream().map(this::find).toList();if(rows.stream().anyMatch(r->!r.checkoutId().equals(checkout))){throw new PromotionRejected(409);}lockCoupons(rows.stream().map(Reservation::issuedCouponId).toList());return ids.stream().sorted(IDS).map(this::find).toList();});}
 @Override public List<Reservation> release(UUID checkout,List<UUID> ids){return transition(checkout,ids,"RELEASED");}
 @Override public List<Reservation> expire(UUID checkout,List<UUID> ids){return transition(checkout,ids,"EXPIRED");}
 private List<Reservation> transition(UUID checkout,List<UUID> ids,String target){
  if(checkout==null||ids==null||ids.isEmpty()||ids.size()>20||ids.stream().anyMatch(java.util.Objects::isNull)||ids.stream().distinct().count()!=ids.size()){throw new PromotionRejected(400);}
  return tx.execute(status->{var initial=ids.stream().map(this::find).toList();if(initial.stream().anyMatch(r->!r.checkoutId().equals(checkout))){throw new PromotionRejected(409);}lockCoupons(initial.stream().map(Reservation::issuedCouponId).toList());Instant now=clock.instant();var result=new java.util.ArrayList<Reservation>();
   for(UUID id:ids.stream().sorted(IDS).toList()){var row=find(id);String next=CouponReservationRules.transition(row.state(),target,now,row.expiresAt());if(!row.state().equals(next)){changeCoupon(row.issuedCouponId(),"RESERVED",next.equals("COMMITTED")?"USED":"AVAILABLE");jdbc.update("UPDATE marketplace.coupon_reservation SET state=:state,version=version+1 WHERE id=:id",Map.of("id",id,"state",next));event(id,next,row.version()+1);}result.add(find(id));}return List.copyOf(result);
  });
 }
 @Override public Restoration restore(RestoreRequest r){
  if(r==null||r.causeId()==null||r.sellerOrderRef()==null||r.reservationId()==null||r.refundCompletion()==null){throw new PromotionRejected(400);}String hash=hash(r.sellerOrderRef()+"|"+r.reservationId()+"|"+r.refundCompletion().full()+"|"+r.refundCompletion().confirmed());
  try{return tx.execute(status->{var row=find(r.reservationId());if(!row.sellerOrderRef().equals(r.sellerOrderRef())){throw new PromotionRejected(409);}lockCoupons(List.of(row.issuedCouponId()));row=find(r.reservationId());var previous=jdbc.queryForList("SELECT request_hash FROM marketplace.coupon_restoration WHERE cause_id=:id",Map.of("id",r.causeId()),String.class);if(!previous.isEmpty()){if(!previous.getFirst().equals(hash)){throw new PromotionRejected(409);}return restoration(r.causeId());}
   boolean restored=CouponReservationRules.restorable(row.state(),r.refundCompletion().full(),r.refundCompletion().confirmed());boolean reusable=false;String reason=!r.refundCompletion().full()?"PARTIAL":!r.refundCompletion().confirmed()?"UNCONFIRMED":row.state().equals("RESTORED")?"ALREADY_RESTORED":"STATE";
   if(restored){Instant ends=jdbc.queryForObject("SELECT ends_at FROM marketplace.issued_coupon WHERE id=:id",Map.of("id",row.issuedCouponId()),java.sql.Timestamp.class).toInstant();reusable=CouponReservationRules.reusable(clock.instant(),ends);reason=reusable?"RESTORED":"RESTORED_EXPIRED";changeCoupon(row.issuedCouponId(),"USED","AVAILABLE");jdbc.update("UPDATE marketplace.coupon_reservation SET state='RESTORED',version=version+1 WHERE id=:id",Map.of("id",row.id()));event(row.id(),"RESTORED",row.version()+1);}
   jdbc.update("INSERT INTO marketplace.coupon_restoration(cause_id,reservation_id,seller_order_ref,request_hash,restored,reusable,reason) VALUES(:cause,:id,:order,:hash,:restored,:reusable,:reason)",Map.of("cause",r.causeId(),"id",r.reservationId(),"order",r.sellerOrderRef(),"hash",hash,"restored",restored,"reusable",reusable,"reason",reason));return restoration(r.causeId());
  });}catch(org.springframework.dao.DuplicateKeyException e){throw new PromotionRejected(409);}
 }
 private void changeCoupon(UUID id,String from,String to){int changed=jdbc.update("UPDATE marketplace.issued_coupon SET state=:to,version=version+1 WHERE id=:id AND state=:from",Map.of("id",id,"from",from,"to",to));if(changed!=1){throw new PromotionRejected(409);}}
 private void event(UUID id,String action,long version){jdbc.update("INSERT INTO marketplace.coupon_reservation_event(id,reservation_id,action,version) VALUES(:event,:id,:action,:version)",Map.of("event",UUID.randomUUID(),"id",id,"action",action,"version",version));}
 private Reservation map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new Reservation(r.getObject("id",UUID.class),r.getObject("checkout_id",UUID.class),r.getObject("seller_order_ref",UUID.class),r.getObject("member_id",UUID.class),r.getObject("store_id",UUID.class),r.getObject("coupon_id",UUID.class),r.getString("state"),r.getTimestamp("expires_at").toInstant(),r.getLong("version"));}
 private Reservation optional(UUID id){var rows=jdbc.query("SELECT * FROM marketplace.coupon_reservation WHERE id=:id",Map.of("id",id),this::map);return rows.isEmpty()?null:rows.getFirst();}
 private Reservation find(UUID id){var row=optional(id);if(row==null){throw new PromotionRejected(404);}return row;}
 private Restoration restoration(UUID id){return jdbc.queryForObject("SELECT * FROM marketplace.coupon_restoration WHERE cause_id=:id",Map.of("id",id),(r,n)->new Restoration(r.getObject("cause_id",UUID.class),r.getObject("seller_order_ref",UUID.class),r.getObject("reservation_id",UUID.class),r.getBoolean("restored"),r.getBoolean("reusable"),r.getString("reason")));}
}
