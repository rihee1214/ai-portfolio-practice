package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.fulfillment.api.Shipments;
import com.example.marketplace.fulfillment.infrastructure.JdbcShipments;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes current product ports only against the separately confirmed V36-upgrade test database. */
public final class LegacyShipmentMigrationProbe {
 private LegacyShipmentMigrationProbe() { }
 private static Map<String,List<Map<String,Object>>> state(NamedParameterJdbcTemplate jdbc) {
  var rows=new java.util.LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("shipment","shipment_item","shipment_unit","shipment_preparation","shipment_command","shipment_tracking_history","shipment_event","shipment_audit","order_initial_delivery","order_initial_delivery_unit","job","outbox_event")) { rows.put(table,jdbc.queryForList("SELECT * FROM marketplace."+table+" ORDER BY 1,2",Map.of())); }return rows;
 }
 private static void run(UUID id,Jobs jobs,DataSource source,PlatformTransactionManager manager,List<JobHandler> handlers) {
  var at=jobs.find(id).orElseThrow().command().nextRunAt();var one=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(at,ZoneOffset.UTC));try(var engine=new com.example.marketplace.platform.infrastructure.WorkerEngine(one,handlers)) { assertTrue(engine.runOnce()); }assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());
 }
 public static void main(String[] args) throws Exception {
  String database=args[0],url="jdbc:postgresql://127.0.0.1:15433/"+database;Path folder=Path.of(args[1]);DedicatedDatabaseTarget.validate(url,System.getenv("D2_DB_USERNAME"),database);
  try(var context=new SpringApplicationBuilder(MarketplaceApplication.class).profiles("db","worker","local-mailbox","local-http").run("--spring.config.location=classpath:/","--spring.datasource.url="+url,"--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),"--marketplace.media.root="+folder.resolve("media"),"--marketplace.worker.enabled=false","--spring.jmx.enabled=false")) {
   var jdbc=context.getBean(NamedParameterJdbcTemplate.class);var source=context.getBean(DataSource.class);try(var connection=source.getConnection()) { DedicatedDatabaseTarget.verifyConnection(connection,database); }
   var shipments=context.getBean(JdbcShipments.class);var jobs=context.getBean(Jobs.class);var manager=context.getBean(PlatformTransactionManager.class);var handlers=List.copyOf(context.getBeansOfType(JobHandler.class).values());var markers=jdbc.queryForList("SELECT * FROM marketplace.shipment_preparation ORDER BY seller_order_id",Map.of());var money=jdbc.queryForList("SELECT * FROM marketplace.order_unit ORDER BY item_id,ordinal",Map.of());var orders=jdbc.queryForList("SELECT * FROM marketplace.purchase_order ORDER BY id",Map.of());var oldEvents=jdbc.queryForList("SELECT * FROM marketplace.shipment_event ORDER BY id",Map.of());var oldJobs=jdbc.queryForList("SELECT * FROM marketplace.job ORDER BY id",Map.of());
   var own=jdbc.queryForList("SELECT s.id,s.order_number,s.state,s.seller_order_id,o.member_id FROM marketplace.shipment s JOIN marketplace.purchase_order o ON o.id=s.order_id JOIN marketplace.seller_order so ON so.id=s.seller_order_id JOIN marketplace.seller_store ss ON ss.id=so.store_id WHERE ss.owner_id=o.member_id ORDER BY s.id",Map.of());assertEquals(4,own.size());
   var initial=state(jdbc);for(var row:own) { for(int i=0;i<3;i++) { shipments.prepare((String)row.get("order_number")); } }assertEquals(initial,state(jdbc));
   for(var row:own) {
    UUID id=(UUID)row.get("id"),seller=(UUID)row.get("seller_order_id"),member=(UUID)row.get("member_id");String number=(String)row.get("order_number"),phase=(String)row.get("state");String session=jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=:id ORDER BY expiry_time DESC LIMIT 1",Map.of("id",member.toString()),String.class);long version=jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",Map.of("id",member),Long.class);var authority=new OrderQueries.Authority(session,member,version,null);var view=shipments.seller(seller,authority).getFirst();
    if(phase.equals("PREPARING")) {
     var command=new Shipments.Command("legacy-split",view.version(),List.of(new Shipments.Item(view.items().getFirst().orderItemId(),2)));var result=shipments.split(id,authority,command);var rows=shipments.seller(seller,authority);assertEquals(2,rows.size());assertEquals(List.of(2,3),rows.stream().map(s->s.items().getFirst().quantity()).sorted().toList());assertEquals(List.of(0,1),result.units().stream().map(Shipments.Unit::ordinal).toList());assertEquals(List.of(2,3,4),rows.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow().units().stream().map(Shipments.Unit::ordinal).toList());var stable=state(jdbc);for(int i=0;i<3;i++) { shipments.prepare(number);assertEquals(result,shipments.split(id,authority,command)); }var settlement=jobs.find(jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='PAYMENT_SETTLED' AND payload LIKE :p",Map.of("p",number+":%"),UUID.class)).orElseThrow();handlers.stream().filter(h->h.type().equals("PAYMENT_SETTLED")).findFirst().orElseThrow().handle(settlement);assertEquals(stable,state(jdbc));
    }else if(!phase.equals("DELIVERED")) { shipments.tracking(id,authority,new Shipments.TrackingCommand("legacy-tracking",view.version(),"LEGACY-"+id.toString().replace("-",""),"V36 이행 후 등록")); }
   }
   for(UUID id:jdbc.queryForList("SELECT id FROM marketplace.job WHERE status='READY' AND dedupe_key LIKE 'v36-runtime:%' ORDER BY id",Map.of(),UUID.class)) { run(id,jobs,source,manager,handlers); }
   for(var row:own) { if(row.get("state").equals("SHIPPED")) { UUID id=(UUID)row.get("id");run(jdbc.queryForObject("SELECT id FROM marketplace.job WHERE dedupe_key=:key",Map.of("key","progress:"+id+":DELIVERED"),UUID.class),jobs,source,manager,handlers); } }
   for(var old:oldJobs) { UUID id=(UUID)old.get("id");var current=jdbc.queryForMap("SELECT * FROM marketplace.job WHERE id=:id",Map.of("id",id));if(old.get("status").equals("SUCCEEDED")) { assertEquals(old,current); }else { for(String field:List.of("id","type","dedupe_key","logical_key","payload","scheduled_at","next_run_at")) { assertEquals(old.get(field),current.get(field),field); }assertEquals("SUCCEEDED",current.get("status"));assertEquals(1,((Number)current.get("attempts")).intValue()); }
    if(old.get("dedupe_key").toString().startsWith("v36-runtime:")) { var payload=old.get("payload").toString().split("\\|");var at=((java.sql.Timestamp)old.get("scheduled_at")).toInstant();var first=jdbc.queryForMap("SELECT * FROM marketplace.shipment_event WHERE shipment_id=:id AND event_key=:key",Map.of("id",UUID.fromString(payload[0]),"key",old.get("dedupe_key")));assertEquals(payload[1]+"|"+payload[2]+"|"+at,first.get("content"));assertEquals(at,((java.sql.Timestamp)first.get("occurred_at")).toInstant());var expected=new Shipments.Result(UUID.fromString(payload[0]),first.get("next_state").toString(),((Number)first.get("version")).longValue(),(Boolean)first.get("applied"),(UUID)first.get("id"));var before=state(jdbc);var replay=shipments.progress(UUID.fromString(payload[0]),old.get("dedupe_key").toString(),payload[1],Long.parseLong(payload[2]),at);assertEquals(expected,replay);assertEquals(before,state(jdbc)); }
   }
   UUID scan=jobs.enqueue(new Jobs.Command("INITIAL_DELIVERY_SCAN","v36-runtime:scan-v2","v36-runtime","START",Instant.EPOCH));run(scan,jobs,source,manager,handlers);for(UUID id:jdbc.queryForList("SELECT id FROM marketplace.job WHERE type='INITIAL_DELIVERY_MIGRATE' AND status='READY' ORDER BY id",Map.of(),UUID.class)) { run(id,jobs,source,manager,handlers); }
   assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_initial_delivery",Map.of(),Integer.class));assertEquals(15,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_initial_delivery_unit",Map.of(),Integer.class));var stable=state(jdbc);for(var job:jdbc.queryForList("SELECT id FROM marketplace.job WHERE type='INITIAL_DELIVERY_MIGRATE' ORDER BY id",Map.of(),UUID.class)) { handlers.stream().filter(h->h.type().equals("INITIAL_DELIVERY_MIGRATE")).findFirst().orElseThrow().handle(jobs.find(job).orElseThrow()); }assertEquals(stable,state(jdbc));
   for(var row:own) { if(row.get("state").equals("DELIVERED")) { UUID shipment=(UUID)row.get("id");var original=oldEvents.stream().filter(e->e.get("shipment_id").equals(shipment)&&Boolean.TRUE.equals(e.get("applied"))&&e.get("next_state").equals("DELIVERED")).findFirst().orElseThrow();var fact=jdbc.queryForMap("SELECT * FROM marketplace.order_initial_delivery WHERE shipment_id=:id",Map.of("id",shipment));assertEquals(original.get("id"),fact.get("event_id"));assertEquals(original.get("occurred_at"),fact.get("occurred_at"));assertEquals(jdbc.queryForList("SELECT order_item_id AS item_id,ordinal FROM marketplace.shipment_unit WHERE shipment_id=:id ORDER BY order_item_id,ordinal",Map.of("id",shipment)),jdbc.queryForList("SELECT item_id,ordinal FROM marketplace.order_initial_delivery_unit WHERE event_id=:id ORDER BY item_id,ordinal",Map.of("id",fact.get("event_id")))); } }
   for(var event:oldEvents) { assertEquals(event,jdbc.queryForMap("SELECT * FROM marketplace.shipment_event WHERE id=:id",Map.of("id",event.get("id")))); }assertEquals(markers,jdbc.queryForList("SELECT * FROM marketplace.shipment_preparation ORDER BY seller_order_id",Map.of()));assertEquals(money,jdbc.queryForList("SELECT * FROM marketplace.order_unit ORDER BY item_id,ordinal",Map.of()));assertEquals(orders,jdbc.queryForList("SELECT * FROM marketplace.purchase_order ORDER BY id",Map.of()));assertEquals(9,jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment",Map.of(),Integer.class));
   var result=new java.util.LinkedHashMap<String,Object>();result.putAll(Map.of("database",database,"nonweb",true,"states",4,"shipments",9,"markers",8,"originalUnits",24,"receipts",3,"deliveredUnits",15,"originalEventsUnchanged",true,"originalJobsPreserved",true));result.put("sqlMarkersConsumedWithoutRewrite",true);Files.writeString(folder.resolve("runtime-result.json"),new tools.jackson.databind.ObjectMapper().writeValueAsString(result));
  }
 }
}
