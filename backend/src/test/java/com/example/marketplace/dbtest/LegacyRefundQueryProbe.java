package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.notification.api.NotificationEvents;
import com.example.marketplace.notification.infrastructure.NotificationDelivery;
import java.nio.file.Path;
import java.util.ArrayList;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Actual V102 constraints validate a test-only pre-policy protocol; this is not an old-binary run. */
public final class LegacyRefundQueryProbe {
 private LegacyRefundQueryProbe() { }
 // Explicit source for the V102 prepare child only. No component/configuration stereotype for scanning.
 static class V102Preparation {
  @org.springframework.context.annotation.Bean
  @org.springframework.context.annotation.Primary
  V102Notifications v102Notifications() { return new V102Notifications(); }
 }
 static final class V102Notifications implements NotificationEvents {
  private int paid;
  @Override public void record(Kind kind,java.util.UUID cause,java.util.UUID recipient,java.util.UUID target) {
   if(kind==Kind.PAYMENT_BUYER) { paid++; }
  }
 }
 public static void main(String[] args) throws Exception {
  String database=args[0],url=com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.databaseUrl(database);var folder=Path.of(args[1]);boolean prepare=args[2].equals("prepare");
  com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyBeforeMigration(url,System.getenv("D2_DB_USERNAME"),System.getenv("D2_DB_PASSWORD"),database);
  var options=new ArrayList<String>(java.util.List.of("--spring.config.location=classpath:/","--spring.datasource.url="+url,"--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),"--spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture","--marketplace.media.root="+folder.resolve(prepare?"legacy-media":"resumed-media"),"--marketplace.worker.enabled=false","--marketplace.phone.mode=local-test","--server.port=0","--server.address=127.0.0.1","--spring.jmx.enabled=false"));
  var builder=new SpringApplicationBuilder(MarketplaceApplication.class).profiles("db","c3-fixture","local-mailbox","local-http");
  if(prepare){options.add("--spring.flyway.target=102");builder.sources(V102Preparation.class);}
  try(var context=builder.run(options.toArray(String[]::new))){
   var sql=new org.springframework.jdbc.core.JdbcTemplate(context.getBean(javax.sql.DataSource.class));
   var events=context.getBean(NotificationEvents.class);
   org.junit.jupiter.api.Assertions.assertEquals(prepare?102:127,sql.queryForObject(
           "SELECT max(version::integer) FROM marketplace.flyway_schema_history WHERE success AND version ~ '^[0-9]+$'",Integer.class));
   org.junit.jupiter.api.Assertions.assertEquals(!prepare,sql.queryForObject(
           "SELECT to_regclass('marketplace.notification_event') IS NOT NULL",Boolean.class));
   if(prepare) { org.junit.jupiter.api.Assertions.assertInstanceOf(V102Notifications.class,events); }
   else {
    org.junit.jupiter.api.Assertions.assertSame(context.getBean(NotificationDelivery.class),events);
    org.junit.jupiter.api.Assertions.assertEquals(0,sql.queryForObject("SELECT count(*) FROM marketplace.notification_event",Integer.class));
   }
   PostgresContractFixture.prepareLegacyRefundQueryFixture(context,folder,prepare);
   if(prepare) { org.junit.jupiter.api.Assertions.assertTrue(((V102Notifications)events).paid>0); }
  }
 }
}
