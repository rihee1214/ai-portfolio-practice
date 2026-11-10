package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.claim.infrastructure.ReturnRecoveryScanner;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import com.example.marketplace.platform.infrastructure.WorkerEngine;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current ports consume a genuine, constraint-checked V57 C-only graph after D migration. */
public final class LegacyFinancialReturnMigrationProbe {
 private LegacyFinancialReturnMigrationProbe() { }
 private static void run(UUID id,NamedParameterJdbcTemplate jdbc,DataSource source,PlatformTransactionManager manager,List<JobHandler> handlers,Jobs actual) {
  var before=actual.find(id).orElseThrow();Instant stored=before.nextRunAt();Instant due=java.util.stream.Stream.of(stored.plusNanos(1000),Instant.now()).max(Instant::compareTo).orElseThrow();
  var jobs=new JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(due,ZoneOffset.UTC));try(var engine=new WorkerEngine(jobs,handlers)){assertTrue(engine.runOnce());}var result=actual.find(id).orElseThrow();assertEquals("SUCCEEDED",result.status());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE id=:id AND last_error IS NULL",Map.of("id",id),Integer.class));
 }
 public static void main(String[] args) throws Exception {
  String database=args[0],url=com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.databaseUrl(database);Path folder=Path.of(args[1]);UUID intent=UUID.fromString(args[2]),assessment=UUID.fromString(args[3]),attempt=UUID.fromString(args[4]);com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.validate(url,System.getenv("D2_DB_USERNAME"),database);
  com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyBeforeMigration(url,System.getenv("D2_DB_USERNAME"),System.getenv("D2_DB_PASSWORD"),database);
        try(var context=new SpringApplicationBuilder(MarketplaceApplication.class).profiles("db","worker","local-mailbox","local-http").run("--spring.config.location=classpath:/","--spring.datasource.url="+url,"--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),"--marketplace.media.root="+folder.resolve("media"),"--marketplace.worker.enabled=false","--spring.jmx.enabled=false")) {
   var jdbc=context.getBean(NamedParameterJdbcTemplate.class);var source=context.getBean(DataSource.class);try(var connection=source.getConnection()){com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyConnection(connection,database);}var manager=context.getBean(PlatformTransactionManager.class);var tx=new TransactionTemplate(manager);var jobs=context.getBean(Jobs.class);var handlers=List.copyOf(context.getBeansOfType(JobHandler.class).values());var intents=context.getBean(FinancialIntents.class);var fees=context.getBean(ReturnFees.class);
   var original=new java.util.TreeMap<String,String>();for(String table:List.of("purchase_order","order_item","order_unit","order_aftercare_policy","order_unit_lifecycle","order_confirmation_state","order_confirmation_schedule","order_initial_delivery","order_initial_delivery_unit","order_item_return_policy","claim_financial_intent","claim_financial_intent_unit","order_unit_hold","order_hold_unit","claim_fee_consent","inventory_reservation","inventory_balance","coupon_reservation","coupon_restoration")){original.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));}
   for(String type:List.of("REFUND_SUBMIT.v1","REFUND_RESULT_RECORDED.v1")){UUID id=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type=:type AND cause_key=:cause AND status='READY'",Map.of("type",type,"cause",attempt.toString()),UUID.class);run(id,jdbc,source,manager,handlers,jobs);}
   var result=tx.execute(t->intents.results(intent)).getLast();assertEquals("SUCCEEDED",result.state());assertEquals(attempt,result.attemptId());var applied=tx.execute(t->fees.apply(new ReturnFees.Apply(assessment,attempt,result.version(),null,null)));assertEquals(applied,tx.execute(t->fees.apply(new ReturnFees.Apply(assessment,attempt,result.version(),null,null))));assertEquals("3000",applied.increment());assertEquals(1,jdbc.queryForObject("SELECT effects FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",attempt),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT submit_calls FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",attempt),Integer.class));
   UUID run=context.getBean(ReturnRecoveryScanner.class).start();int scanned=0;while(true){var pending=jdbc.queryForList("SELECT id FROM marketplace.job WHERE type='RETURN_RECOVERY_SCAN.v1' AND cause_key=:run AND status='READY' ORDER BY next_run_at,id",Map.of("run",run.toString()),UUID.class);if(pending.isEmpty()){break;}for(UUID id:pending){run(id,jdbc,source,manager,handlers,jobs);assertTrue(++scanned<=3,"empty physical source cannot grow scan pages");}}
   assertEquals(3,scanned);assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_recovery_stage WHERE run_id=:run AND completed",Map.of("run",run),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_recovery_delivery WHERE run_id=:run",Map.of("run",run),Integer.class));
   for(String table:List.of("order_return_application","claim_return_request","claim_return_collection","claim_return_inspection","inventory_return_restitution","inventory_restitution","claim_return_financial_consent","claim_return_item_ready","claim_return_completion","order_return_completion","order_aftercare_closure")){assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace."+table,Map.of(),Integer.class),table+" must not be synthesized from a C financial intent");}
   for(var entry:original.entrySet()){assertEquals(entry.getValue(),jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+entry.getKey()+" t",String.class),entry.getKey());}assertEquals("PAID",jdbc.queryForObject("SELECT state FROM marketplace.purchase_order WHERE id=(SELECT order_id FROM marketplace.claim_financial_intent WHERE id=:id)",Map.of("id",intent),String.class));
   Files.writeString(folder.resolve("runtime-result.json"),context.getBean(tools.jackson.databind.ObjectMapper.class).writeValueAsString(Map.of("database",database,"actualCurrentSubmitCollectorCommonApply",true,"providerEffects",1,"physicalUpgrade",0,"physicalRecoveryDeliveries",0,"scanStages",scanned,"originalCFinancialAndOrderEvidenceUnchanged",true,"originalEvidence",original)));
  }
 }
}
