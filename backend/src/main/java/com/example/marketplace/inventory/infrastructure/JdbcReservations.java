package com.example.marketplace.inventory.infrastructure;

import com.example.marketplace.inventory.api.Reservations;
import com.example.marketplace.inventory.api.InventoryException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class JdbcReservations implements Reservations {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JdbcReservations(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc;tx=new TransactionTemplate(manager); }
    private <T> T transaction(Supplier<T> work) {
        try { return tx.execute(status->work.get()); } catch(DuplicateKeyException conflict) { throw new InventoryException(409); }
    }
    @Override public List<Reservation> reserve(List<Request> requests) {
        if(requests==null||requests.isEmpty()||requests.size()>100) { throw new InventoryException(400); }
        var ids=new HashSet<UUID>();var items=new HashSet<UUID>();var inputs=new ArrayList<Request>();
        for(var r:requests) {
            if(r==null||r.id()==null||r.checkoutId()==null||r.orderItemId()==null||r.skuId()==null||r.expiresAt()==null
                ||r.quantity()<1||r.quantity()>999999999||!ids.add(r.id())||!items.add(r.orderItemId())) { throw new InventoryException(400); }
            if(r.expiresAt().isBefore(Instant.EPOCH)||r.expiresAt().isAfter(Instant.parse("9999-12-31T23:59:59Z"))) { throw new InventoryException(400); }
            inputs.add(new Request(r.id(),r.checkoutId(),r.orderItemId(),r.skuId(),r.quantity(),r.expiresAt().truncatedTo(ChronoUnit.MICROS)));
        }
        return transaction(()-> {
            lockRequestKeys(inputs);
            lockBalances(inputs.stream().map(Request::skuId).toList());
            var result=new ArrayList<Reservation>();
            for(var r:inputs) {
                var existing=find(r.id(),false);
                if(existing!=null) {
                    if(!same(existing,r)) { throw new InventoryException(409); }
                    result.add(existing);continue;
                }
                if(!r.expiresAt().isAfter(now())) { throw new InventoryException(409); }
                var p=new MapSqlParameterSource("id",r.id()).addValue("checkout",r.checkoutId()).addValue("item",r.orderItemId())
                    .addValue("sku",r.skuId()).addValue("quantity",r.quantity()).addValue("expires",java.sql.Timestamp.from(r.expiresAt()));
                jdbc.update("INSERT INTO marketplace.inventory_reservation(id,checkout_id,order_item_id,sku_id,quantity,expires_at,status) VALUES(:id,:checkout,:item,:sku,:quantity,:expires,'RESERVED')",p);
                change(new Reservation(r.id(),r.checkoutId(),r.orderItemId(),r.skuId(),r.quantity(),r.expiresAt(),"RESERVED"),"RESERVE");
                result.add(find(r.id(),false));
            }
            return List.copyOf(result);
        });
    }
    @Override public List<Reservation> commit(UUID checkoutId,List<UUID> ids) { return finish(checkoutId,ids,"COMMIT"); }
    @Override public List<Reservation> inspect(UUID checkout,List<UUID> ids) {
        return transaction(()-> { var rows=ids.stream().map(id->owned(checkout,id,false)).toList();lockBalances(rows.stream().map(Reservation::skuId).toList());return ids.stream().sorted(Comparator.comparing(UUID::toString)).map(id->owned(checkout,id,true)).toList(); });
    }
    @Override public List<Reservation> release(UUID checkoutId,List<UUID> ids) { return finish(checkoutId,ids,"RELEASE"); }
    @Override public List<Reservation> expire(UUID checkoutId,List<UUID> ids) { return finish(checkoutId,ids,"EXPIRE"); }
    private List<Reservation> finish(UUID checkout,List<UUID> ids,String cause) {
        if(checkout==null||ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(java.util.Objects::isNull)||new HashSet<>(ids).size()!=ids.size()) { throw new InventoryException(400); }
        var input=List.copyOf(ids);
        return transaction(()-> {
            var initial=input.stream().map(id->owned(checkout,id,false)).toList();
            lockBalances(initial.stream().map(Reservation::skuId).toList());
            // All balances precede reservation locks, including in caller-supplied reverse batches.
            var locked=new java.util.HashMap<UUID,Reservation>();
            input.stream().sorted(Comparator.comparing(UUID::toString)).forEach(id->locked.put(id,owned(checkout,id,true)));
            var results=new ArrayList<Reservation>();
            for(UUID id:input) {
                var r=locked.get(id); String target=cause.equals("COMMIT")?"COMMITTED":"RELEASED";
                if(cause.equals("EXPIRE")&&(!r.status().equals("RESERVED")||r.expiresAt().isAfter(now()))) { results.add(r);continue; }
                if(r.status().equals(target)) { results.add(r);continue; }
                if(!r.status().equals("RESERVED")||(cause.equals("COMMIT")&&!r.expiresAt().isAfter(now()))) { throw new InventoryException(409); }
                var p=new MapSqlParameterSource("id",id).addValue("target",target);
                int updated=jdbc.update("UPDATE marketplace.inventory_reservation SET status=:target,finished_at=clock_timestamp() WHERE id=:id AND status='RESERVED'",p);
                if(updated!=1) { throw new InventoryException(409); }
                change(r,cause);results.add(find(id,false));
            }
            return List.copyOf(results);
        });
    }
    private void lockRequestKeys(List<Request> inputs) {
        // Both unique key families precede balances. Sorting only reservation IDs leaves order-item cycles possible.
        var keys=new ArrayList<String>();
        for(var r:inputs) { keys.add("inventory/reservation/"+r.id());keys.add("inventory/order-item/"+r.orderItemId()); }
        var locks=jdbc.queryForList("SELECT DISTINCT hashtextextended(value,0) AS lock_id FROM unnest(CAST(ARRAY[:keys] AS text[])) AS keys(value) ORDER BY lock_id",Map.of("keys",keys),Long.class);
        // Sort the actual lock numbers, so a hash collision only adds serialization and cannot invert lock order.
        for(long key:locks) { jdbc.query("SELECT pg_advisory_xact_lock(:key)",Map.of("key",key),(r,i)->r.getObject(1)); }
    }
    private void lockBalances(List<UUID> skus) {
        for(UUID sku:skus.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var p=Map.of("sku",sku);
            jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id) SELECT id FROM marketplace.catalog_sku WHERE id=:sku ON CONFLICT DO NOTHING",p);
            var rows=jdbc.queryForList("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:sku FOR UPDATE",p,UUID.class);
            if(rows.isEmpty()) { throw new InventoryException(404); }
        }
    }
    private Reservation owned(UUID checkout,UUID id,boolean lock) {
        var r=find(id,lock); if(r==null||!r.checkoutId().equals(checkout)) { throw new InventoryException(404); } return r;
    }
    private Reservation find(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM marketplace.inventory_reservation WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id),
            (r,i)->new Reservation(r.getObject("id",UUID.class),r.getObject("checkout_id",UUID.class),r.getObject("order_item_id",UUID.class),r.getObject("sku_id",UUID.class),r.getLong("quantity"),r.getTimestamp("expires_at").toInstant(),r.getString("status")));
        return rows.isEmpty()?null:rows.getFirst();
    }
    private boolean same(Reservation saved,Request input) {
        return saved.checkoutId().equals(input.checkoutId())&&saved.orderItemId().equals(input.orderItemId())&&saved.skuId().equals(input.skuId())
            &&saved.quantity()==input.quantity()&&saved.expiresAt().equals(input.expiresAt());
    }
    private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant(); }
    private void change(Reservation r,String cause) {
        var before=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",r.skuId()));
        long onHand=((Number)before.get("on_hand")).longValue(), reserved=((Number)before.get("reserved")).longValue(),version=((Number)before.get("version")).longValue();
        long afterOnHand=onHand-(cause.equals("COMMIT")?r.quantity():0);
        long afterReserved=reserved+(cause.equals("RESERVE")?r.quantity():-r.quantity());
        var p=new MapSqlParameterSource("sku",r.skuId()).addValue("id",r.id()).addValue("cause",cause).addValue("quantity",r.quantity())
            .addValue("beforeOnHand",onHand).addValue("afterOnHand",afterOnHand).addValue("beforeReserved",reserved).addValue("afterReserved",afterReserved).addValue("version",version);
        int updated=jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=:afterOnHand,reserved=:afterReserved,version=version+1 WHERE sku_id=:sku AND version=:version AND version<9007199254740991 AND :afterReserved>=0 AND :afterOnHand>=:afterReserved AND :afterOnHand>=0",p);
        if(updated!=1) { throw new InventoryException(409); }
        jdbc.update("INSERT INTO marketplace.inventory_reservation_event(reservation_id,sku_id,cause,quantity,before_on_hand,after_on_hand,before_reserved,after_reserved,before_version,version) VALUES(:id,:sku,:cause,:quantity,:beforeOnHand,:afterOnHand,:beforeReserved,:afterReserved,:version,:version+1)",p);
    }
}
