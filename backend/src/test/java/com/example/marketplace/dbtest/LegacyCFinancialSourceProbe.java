package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import java.nio.file.Path;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Isolated current C-port fixture; avoids copying unrelated tests into the prior-schema database. */
public final class LegacyCFinancialSourceProbe {
 private LegacyCFinancialSourceProbe() { }
 public static void main(String[] args) throws Exception {
  String database=args[0],url=com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.databaseUrl(database);Path folder=Path.of(args[2]);com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.validate(url,System.getenv("D2_DB_USERNAME"),database);
  com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyBeforeMigration(url,System.getenv("D2_DB_USERNAME"),System.getenv("D2_DB_PASSWORD"),database);
        try(var context=new SpringApplicationBuilder(MarketplaceApplication.class).profiles("db","c3-fixture","local-mailbox","local-http").run("--spring.config.location=classpath:/","--spring.datasource.url="+url,"--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),"--spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture","--marketplace.media.root="+folder.resolve("source-media"),"--marketplace.worker.enabled=false","--marketplace.phone.mode=local-test","--server.port=0","--server.address=127.0.0.1","--spring.jmx.enabled=false")) {
   PostgresContractFixture.prepareIsolatedCFinancialGraph(context,folder,args[1]);
  }
 }
}
