package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import java.nio.file.Path;
import java.util.ArrayList;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Actual V102 constraints validate a test-only pre-policy protocol; this is not an old-binary run. */
public final class LegacyRefundQueryProbe {
 private LegacyRefundQueryProbe() { }
 public static void main(String[] args) throws Exception {
  String database=args[0],url=com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.databaseUrl(database);var folder=Path.of(args[1]);boolean prepare=args[2].equals("prepare");
  com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyBeforeMigration(url,System.getenv("D2_DB_USERNAME"),System.getenv("D2_DB_PASSWORD"),database);
  var options=new ArrayList<String>(java.util.List.of("--spring.config.location=classpath:/","--spring.datasource.url="+url,"--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),"--spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture","--marketplace.media.root="+folder.resolve(prepare?"legacy-media":"resumed-media"),"--marketplace.worker.enabled=false","--marketplace.phone.mode=local-test","--server.port=0","--server.address=127.0.0.1","--spring.jmx.enabled=false"));
  if(prepare){options.add("--spring.flyway.target=102");}
  try(var context=new SpringApplicationBuilder(MarketplaceApplication.class).profiles("db","c3-fixture","local-mailbox","local-http").run(options.toArray(String[]::new))){PostgresContractFixture.prepareLegacyRefundQueryFixture(context,folder,prepare);}
 }
}
