package com.example.marketplace.dbtest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Separate SELECT clock brackets only. Never claims to observe the public query's clock or snapshot. */
@RestController
@Profile("c3-fixture & publication-observation-fixture")
final class PublicationBrowserObservation {
    static final String CONTRACT_FILE="publication-observer-contract.private.json";
    static final String ENDPOINT="/api/__test/publication-observation";
    static final String CLOCK_MEANING="separate-readonly-select-not-public-query-clock-or-snapshot";
    private static final String BOOTSTRAP="SELECT json_build_object('database',current_database(),'transactionReadOnly',current_setting('transaction_read_only'),'transactionIsolation',current_setting('transaction_isolation'),'databaseClock',clock_timestamp())::text";
    private static final String OBSERVE="""
            WITH sample AS MATERIALIZED (
              SELECT current_database() AS database_name,current_setting('transaction_read_only') AS read_only,
                current_setting('transaction_isolation') AS isolation,statement_timestamp() AS statement_clock,
                clock_timestamp() AS database_clock,pg_backend_pid() AS backend_pid,pg_current_snapshot()::text AS snapshot
            ), saved AS (
              SELECT * FROM marketplace.site_post_revision WHERE post_id=:post AND request_key=:key
                AND actor_id=:actor AND changed_at>=:started
            )
            SELECT json_build_object('database',s.database_name,'transactionReadOnly',s.read_only,'transactionIsolation',s.isolation,
              'observerStatementClock',s.statement_clock,'observerDatabaseClock',s.database_clock,
              'observerBackendPid',s.backend_pid,'observerSnapshot',s.snapshot,
              'saved',json_build_object('id',saved.post_id,'version',saved.version,'requestKey',saved.request_key,
                'kind',saved.kind,'state',saved.state,'startsAt',saved.starts_at,'endsAt',saved.ends_at,'changedAt',saved.changed_at,
                'bodySha256',encode(sha256(convert_to(saved.body,'UTF8')),'hex')),
              'head',json_build_object('id',h.id,'version',h.version,'kind',r.kind,'state',r.state,
                'startsAt',r.starts_at,'endsAt',r.ends_at,'changedAt',r.changed_at,
                'bodySha256',encode(sha256(convert_to(r.body,'UTF8')),'hex'),
                'visibleAtObserverStatement',marketplace.site_post_visible(r.state,r.starts_at,r.ends_at,s.statement_clock)))::text
            FROM sample s CROSS JOIN saved JOIN marketplace.site_post h ON h.id=saved.post_id
            JOIN marketplace.site_post_revision r ON (r.post_id,r.version)=(h.id,h.version)
            """;
    private final NamedParameterJdbcTemplate sql;
    private final TransactionTemplate snapshot;
    private volatile Scope scope;
    private record Scope(UUID run,String key,String database,Instant started,int apiPort) { }

    PublicationBrowserObservation(DataSource source,PlatformTransactionManager manager) {
        // This template is private to this observer; shared application JDBC settings are untouched.
        var bounded=new JdbcTemplate(source);bounded.setQueryTimeout(3);bounded.setMaxRows(2);sql=new NamedParameterJdbcTemplate(bounded);
        snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setTimeout(3);
    }
    synchronized void initialize(String expectedDatabase,int apiPort,Path evidence) throws Exception {
        if(scope!=null||expectedDatabase==null||!expectedDatabase.matches("d2_it_[0-9a-f]{32}")||apiPort<1||apiPort>65535) {throw new IllegalArgumentException("Explicit new publication observation owner required");}
        var initial=read(BOOTSTRAP,Map.of());checkDatabase(initial,expectedDatabase);
        var bytes=new byte[32];new SecureRandom().nextBytes(bytes);var selected=new Scope(UUID.randomUUID(),HexFormat.of().formatHex(bytes),expectedDatabase,Instant.parse(initial.path("databaseClock").asText()),apiPort);
        var contract=Map.of("version",1,"runId",selected.run().toString(),"runKey",selected.key(),"database",expectedDatabase,"apiPort",apiPort,"endpoint",ENDPOINT,"clockMeaning",CLOCK_MEANING);
        ReleaseSnapshotContract.privateFile(evidence.resolve(CONTRACT_FILE),ReleaseSnapshotContract.JSON.writeValueAsString(contract));scope=selected;
    }
    @GetMapping(ENDPOINT)
    Map<String,Object> observe(@RequestHeader(value="X-Publication-Run",required=false) String run,
                               @RequestHeader(value="X-Publication-Key",required=false) String key,
                               @RequestParam UUID postId,@RequestParam UUID requestKey,@RequestParam String phase,
                               HttpServletRequest request,HttpServletResponse response) {
        var selected=scope;if(selected==null) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
        if(!request.getMethod().equals("GET")) {throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED);}
        if(!loopback(request.getRemoteAddr())||!selected.run().toString().equals(run)||key==null||!key.matches("[0-9a-f]{64}")
                ||!MessageDigest.isEqual(selected.key().getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8))) {throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
        if(postId==null||requestKey==null||!Set.of("after-post","pre-public-get","post-public-get").contains(phase)) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        UUID actor;
        try {if(request.getUserPrincipal()==null) {throw new IllegalArgumentException();}actor=UUID.fromString(request.getUserPrincipal().getName());}
        catch(IllegalArgumentException failure) {throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
        var parameters=Map.<String,Object>of("post",postId,"key",requestKey,"actor",actor,"started",Timestamp.from(selected.started()));
        tools.jackson.databind.JsonNode observed;
        try {observed=read(OBSERVE,parameters);}catch(EmptyResultDataAccessException missing) {throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        checkDatabase(observed,selected.database());
        if(!observed.path("saved").path("id").asText().equals(postId.toString())||!observed.path("saved").path("requestKey").asText().equals(requestKey.toString())
                ||!observed.path("head").path("id").asText().equals(postId.toString())) {throw new IllegalStateException("Publication observation identity mismatch");}
        var result=new LinkedHashMap<String,Object>();result.put("runId",selected.run().toString());result.put("database",selected.database());result.put("apiPort",selected.apiPort());result.put("phase",phase);
        result.put("clockMeaning",CLOCK_MEANING);result.put("queryLimitSeconds",3);result.put("observation",observed);response.setHeader("Cache-Control","no-store");return result;
    }
    private tools.jackson.databind.JsonNode read(String query,Map<String,?> parameters) {
        return snapshot.execute(tx->ReleaseSnapshotContract.JSON.readTree(sql.queryForObject(query,parameters,String.class)));
    }
    private static void checkDatabase(tools.jackson.databind.JsonNode result,String expected) {
        if(!result.path("database").asText().equals(expected)||!result.path("transactionReadOnly").asText().equals("on")
                ||!result.path("transactionIsolation").asText().equals("repeatable read")) {throw new IllegalStateException("Readonly publication observer database/isolation mismatch");}
    }
    private static boolean loopback(String remote) {
        if(remote==null||remote.length()>45||!(remote.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")||remote.contains(":")&&remote.matches("[0-9a-fA-F:]+"))) {return false;}
        try {return InetAddress.getByName(remote).isLoopbackAddress();}catch(java.net.UnknownHostException malformed) {return false;}
    }
}
