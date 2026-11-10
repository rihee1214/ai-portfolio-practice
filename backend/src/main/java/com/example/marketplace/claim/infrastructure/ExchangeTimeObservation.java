package com.example.marketplace.claim.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Observe one actual database clock value without replacing or normalizing business time. */
final class ExchangeTimeObservation {
 private ExchangeTimeObservation() { }
 static Instant read(NamedParameterJdbcTemplate jdbc,String boundary,UUID exchange) {
  Instant before=Instant.now();
  return jdbc.queryForObject("WITH observed AS MATERIALIZED(SELECT pg_catalog.clock_timestamp() AS observed_at) SELECT observed_at,observed_at::text AS raw_at,extract(epoch FROM observed_at)::text AS epoch,pg_backend_pid() AS backend,current_database() AS database,current_setting('marketplace.verification_instance',true) AS instance,inet_server_addr()::text AS server_address,inet_server_port() AS server_port,pg_postmaster_start_time()::text AS server_started FROM observed",Map.of(),(r,n)->{
   Instant actual=r.getTimestamp("observed_at").toInstant();
   System.getLogger(ExchangeTimeObservation.class.getName()).log(System.Logger.Level.INFO,"EXCHANGE_ACTUAL_DATABASE_CLOCK boundary="+boundary+" exchange="+exchange+" raw="+r.getString("raw_at")+" epoch="+r.getString("epoch")+" jdbc="+actual+" backend="+r.getInt("backend")+" database="+r.getString("database")+" instance="+r.getString("instance")+" server="+r.getString("server_address")+":"+r.getInt("server_port")+" serverStarted="+r.getString("server_started")+" jvmBefore="+before+" jvmAfter="+Instant.now());
   return actual;
  });
 }
}
