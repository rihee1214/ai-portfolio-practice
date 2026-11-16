package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Guard and actual Spring/JDBC policy tests; no PostgreSQL process or business producer is simulated as runtime proof. */
class PublicationBrowserObservationTest {
    private static final String DATABASE="d2_it_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final UUID POST=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID KEY=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ACTOR=UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test void observerRequiresBothExplicitTestProfiles() {
        for(var profiles:List.of(new String[]{},new String[]{"db","c3-fixture"},new String[]{"publication-observation-fixture"})) {
            try(var context=new AnnotationConfigApplicationContext()) {context.getEnvironment().setActiveProfiles(profiles);context.register(PublicationBrowserObservation.class);context.refresh();assertTrue(context.getBeansOfType(PublicationBrowserObservation.class).isEmpty());}
        }
    }
    @Test void scopedReadUsesActualReadonlyRepeatableReadAndBoundedJdbcStatement() throws Exception {
        var h=new Harness(true);h.replies.add(observed(DATABASE,"on","repeatable read"));var result=h.read(POST,KEY,"pre-public-get",h.request());
        assertEquals(PublicationBrowserObservation.CLOCK_MEANING,result.get("clockMeaning"));assertEquals(3,result.get("queryLimitSeconds"));
        verify(h.connection,org.mockito.Mockito.atLeastOnce()).setReadOnly(true);verify(h.connection,org.mockito.Mockito.atLeastOnce()).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        for(var statement:h.statements) {verify(statement).setQueryTimeout(intThat(value->value>=1&&value<=3));verify(statement).setMaxRows(2);verify(statement,never()).executeUpdate();}
        var query=h.queries.getLast();assertTrue(query.contains("post_id=? AND request_key=?")&&query.contains("actor_id=? AND changed_at>=?"));
        assertTrue(bound(h.statements.getLast(),POST)&&bound(h.statements.getLast(),KEY)&&bound(h.statements.getLast(),ACTOR));
        assertFalse(ReleaseSnapshotContract.JSON.writeValueAsString(result).contains(h.key()));
    }
    @Test void uninitializedObserverRefusesBeforeAnyJdbcAccess() throws Exception {
        var h=new Harness(false);var failure=assertThrows(ResponseStatusException.class,()->h.observer.observe("missing","missing",POST,KEY,"after-post",h.request(),new MockHttpServletResponse()));
        assertEquals(503,failure.getStatusCode().value());verifyNoInteractions(h.source,h.connection);
    }
    @Test void nonLoopbackObserverRequestsAreRejectedBeforeJdbc() throws Exception {
        var h=new Harness(true);clearInvocations(h.source,h.connection);var request=h.request();request.setRemoteAddr("192.0.2.1");h.reject(403,h.run(),h.key(),POST,KEY,"after-post",request);verifyNoInteractions(h.source,h.connection);
    }
    @Test void missingOrWrongRunCredentialsAreRejectedBeforeJdbc() throws Exception {
        var h=new Harness(true);clearInvocations(h.source,h.connection);
        h.reject(403,UUID.randomUUID().toString(),h.key(),POST,KEY,"after-post",h.request());
        h.reject(403,h.run(),null,POST,KEY,"after-post",h.request());h.reject(403,h.run(),"0".repeat(64),POST,KEY,"after-post",h.request());verifyNoInteractions(h.source,h.connection);
    }
    @Test void mutationMethodAndUnregisteredPhaseAreRejectedBeforeJdbc() throws Exception {
        var h=new Harness(true);clearInvocations(h.source,h.connection);var request=h.request();request.setMethod("POST");
        h.reject(405,h.run(),h.key(),POST,KEY,"after-post",request);h.reject(400,h.run(),h.key(),POST,KEY,"unregistered",h.request());verifyNoInteractions(h.source,h.connection);
    }
    @Test void missingPrincipalCannotReadAnotherActorsPublication() throws Exception {
        var h=new Harness(true);clearInvocations(h.source,h.connection);var request=h.request();request.setUserPrincipal(null);h.reject(403,h.run(),h.key(),POST,KEY,"after-post",request);verifyNoInteractions(h.source,h.connection);
    }
    @Test void unmatchedPostAndRequestKeyHaveNoFallbackToBroaderRows() throws Exception {
        var h=new Harness(true);h.replies.add(null);h.reject(404,h.run(),h.key(),UUID.randomUUID(),KEY,"after-post",h.request());
        h.replies.add(null);h.reject(404,h.run(),h.key(),POST,UUID.randomUUID(),"after-post",h.request());assertEquals(3,h.queries.size());
        assertTrue(h.queries.stream().skip(1).allMatch(query->query.contains("post_id=? AND request_key=?")&&query.contains("actor_id=? AND changed_at>=?")));
    }
    @Test void wrongDatabaseReadonlyOrIsolationObservationFailsClosed() throws Exception {
        for(var row:List.of(observed("d2_it_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","on","repeatable read"),observed(DATABASE,"off","repeatable read"),observed(DATABASE,"on","read committed"))) {
            var h=new Harness(true);h.replies.add(row);assertThrows(IllegalStateException.class,()->h.read(POST,KEY,"post-public-get",h.request()));
        }
    }
    @Test void bootstrapContractIsPrivateAndCannotBeReinitialized() throws Exception {
        var h=new Harness(true);var file=h.folder.resolve(PublicationBrowserObservation.CONTRACT_FILE);String before=ReleaseSnapshotContract.hash(Files.readString(file));int queries=h.queries.size();
        assertThrows(IllegalArgumentException.class,()->h.observer.initialize(DATABASE,25001,h.folder));assertEquals(queries,h.queries.size());assertEquals(before,ReleaseSnapshotContract.hash(Files.readString(file)));
        var acl=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);if(acl!=null) {var owner=acl.getOwner();assertTrue(acl.getAcl().stream().allMatch(entry->entry.principal().equals(owner)));}
    }

    private static boolean bound(PreparedStatement statement,Object value) {return mockingDetails(statement).getInvocations().stream().anyMatch(invocation->{var args=invocation.getArguments();return invocation.getMethod().getName().startsWith("set")&&args.length>1&&Objects.equals(args[1],value);});}
    private static String observed(String database,String readonly,String isolation) {
        return ReleaseSnapshotContract.JSON.writeValueAsString(Map.of("database",database,"transactionReadOnly",readonly,"transactionIsolation",isolation,
                "observerStatementClock","2026-11-16T00:00:01Z","observerDatabaseClock","2026-11-16T00:00:01.001Z",
                "saved",Map.of("id",POST.toString(),"requestKey",KEY.toString()),"head",Map.of("id",POST.toString())));
    }
    private static final class Harness {
        final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class);
        final LinkedList<String> replies=new LinkedList<>();final List<String> queries=new ArrayList<>();final List<PreparedStatement> statements=new ArrayList<>();
        final PublicationBrowserObservation observer;final Path folder;final tools.jackson.databind.JsonNode contract;
        Harness(boolean initialize) throws Exception {
            when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(connection.prepareStatement(anyString())).thenAnswer(call->{
                queries.add(call.getArgument(0));var statement=mock(PreparedStatement.class);statements.add(statement);
                when(statement.executeQuery()).thenAnswer(ignored->{
                    if(replies.isEmpty()) {throw new AssertionError("Unexpected JDBC query");}String reply=replies.removeFirst();var rows=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);
                    when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);when(rows.getMetaData()).thenReturn(metadata);when(rows.next()).thenReturn(reply!=null,false);when(rows.getString(1)).thenReturn(reply);return rows;
                });return statement;
            });
            observer=new PublicationBrowserObservation(source,new DataSourceTransactionManager(source));
            var build=Path.of(System.getProperty("marketplace.build.directory","target")).toAbsolutePath();Files.createDirectories(build);folder=Files.createDirectory(build.resolve("publication-observer-unit-"+UUID.randomUUID()));
            if(initialize) {
                replies.add(ReleaseSnapshotContract.JSON.writeValueAsString(Map.of("database",DATABASE,"transactionReadOnly","on","transactionIsolation","repeatable read","databaseClock","2026-11-16T00:00:00Z")));
                observer.initialize(DATABASE,25001,folder);contract=ReleaseSnapshotContract.JSON.readTree(Files.readString(folder.resolve(PublicationBrowserObservation.CONTRACT_FILE)));
            } else {contract=ReleaseSnapshotContract.JSON.readTree("{}");}
        }
        String run() {return contract.path("runId").asText();}String key() {return contract.path("runKey").asText();}
        MockHttpServletRequest request() {var request=new MockHttpServletRequest("GET",PublicationBrowserObservation.ENDPOINT);request.setRemoteAddr("127.0.0.1");request.setUserPrincipal(()->ACTOR.toString());return request;}
        Map<String,Object> read(UUID post,UUID key,String phase,MockHttpServletRequest request) {return observer.observe(run(),key(),post,key,phase,request,new MockHttpServletResponse());}
        void reject(int status,String run,String key,UUID post,UUID requestKey,String phase,MockHttpServletRequest request) {var failure=assertThrows(ResponseStatusException.class,()->observer.observe(run,key,post,requestKey,phase,request,new MockHttpServletResponse()));assertEquals(status,failure.getStatusCode().value());}
    }
}
