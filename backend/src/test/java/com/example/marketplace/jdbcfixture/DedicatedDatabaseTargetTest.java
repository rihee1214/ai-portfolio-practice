package com.example.marketplace.jdbcfixture;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DedicatedDatabaseTargetTest {
    private static final String DATABASE = "c3_verify_0123456789abcdef0123456789abcdef";
    private static final String URL = "jdbc:postgresql://127.0.0.1:15433/" + DATABASE;

    static Stream<Arguments> unsafeTargets() {
        return Stream.of(
                Arguments.of("jdbc:postgresql://127.0.0.1:15432/" + DATABASE, "marketplace_test", DATABASE),
                Arguments.of("jdbc:postgresql://127.0.0.1:15433/marketplace_dev", "marketplace_test", "marketplace_dev"),
                Arguments.of("jdbc:postgresql://127.0.0.1:15433/marketplace_test", "marketplace_test", "marketplace_test"),
                Arguments.of("jdbc:postgresql://production:5432/" + DATABASE, "marketplace_test", DATABASE),
                Arguments.of(URL + "?currentSchema=public", "marketplace_test", DATABASE),
                Arguments.of(URL, "postgres", DATABASE),
                Arguments.of(URL, "marketplace_dev", DATABASE),
                Arguments.of(URL, "marketplace_test", ""),
                Arguments.of(URL, "marketplace_test", "c3_verify_ffffffffffffffffffffffffffffffff"),
                Arguments.of("jdbc:postgresql://localhost:15433/" + DATABASE, "marketplace_test", DATABASE),
                Arguments.of(null, "marketplace_test", DATABASE),
                Arguments.of(URL, null, DATABASE));
    }

    @ParameterizedTest
    @MethodSource("unsafeTargets")
    void rejectsUnsafeTargetBeforeConnection(String url, String user, String confirmation) {
        assertThrows(IllegalArgumentException.class,
                () -> DedicatedDatabaseTarget.validate(url, user, confirmation, new DedicatedDatabaseTarget.Endpoint(15433,"default")));
    }

    @Test
    void acceptsExplicitDedicatedTargetWithoutConnecting() {
        assertDoesNotThrow(() -> DedicatedDatabaseTarget.validate(URL, "marketplace_test", DATABASE, new DedicatedDatabaseTarget.Endpoint(15433,"default")));
    }

    @Test
    void acceptsDedicatedD2TargetWithoutConnecting() {
        String database = "d2_it_0123456789abcdef0123456789abcdef";
        assertDoesNotThrow(() -> DedicatedDatabaseTarget.validate(
                "jdbc:postgresql://127.0.0.1:15433/" + database, "marketplace_test", database, new DedicatedDatabaseTarget.Endpoint(15433,"default")));
    }
    @Test
    void explicitlyConfirmedDiagnosticEndpointPropagatesToChildWithoutConnecting() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(0,endpointChild(true,true,false),"explicit diagnostic loopback endpoint must be recognized by the actual child guard");
    }

    @Test
    void childMissingEndpointConfirmationCannotFallBackToSharedDatabase() throws Exception {
        org.junit.jupiter.api.Assertions.assertNotEquals(0,endpointChild(false,false,true),"lost diagnostic settings must not silently authorize the shared endpoint");
    }

    @Test
    void explicitDefaultEndpointStillPropagatesToActualChild() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(0, endpointChild(false,true,true));
    }

    @Test
    void diagnosticChildWithoutConfirmationFailsBeforeConnection() throws Exception {
        org.junit.jupiter.api.Assertions.assertNotEquals(0, endpointChild(true,false,false));
    }

    @Test
    void endpointRequiresCanonicalUuidAndExactConfirmationAndRetainsDefault() {
        String instance="4abe1092-7cdf-414b-8a2d-e9130af4012e";
        var endpoint=DedicatedDatabaseTarget.endpoint("26543",instance,"127.0.0.1:26543|"+instance,true);
        org.junit.jupiter.api.Assertions.assertEquals("jdbc:postgresql://127.0.0.1:26543/"+DATABASE,endpoint.url(DATABASE));
        assertDoesNotThrow(()->DedicatedDatabaseTarget.endpoint(null,null,null,false));
        assertDoesNotThrow(()->DedicatedDatabaseTarget.endpoint("15433","default","127.0.0.1:15433|default",true));
        for(String[] row:java.util.List.of(new String[]{"15432",instance,"127.0.0.1:15432|"+instance},new String[]{"15433",instance,"127.0.0.1:15433|"+instance},new String[]{"26543",null,null},new String[]{"26543",instance,"127.0.0.1:15433|default"},new String[]{"26543",instance.toUpperCase(java.util.Locale.ROOT),"127.0.0.1:26543|"+instance},new String[]{"26543?x",instance,null},new String[]{"0",instance,null},new String[]{"65536",instance,null})) {
            assertThrows(IllegalArgumentException.class,()->DedicatedDatabaseTarget.endpoint(row[0],row[1],row[2],true));
        }
        for(var values:unsafeTargets().toList()) {var row=values.get();assertThrows(IllegalArgumentException.class,()->DedicatedDatabaseTarget.validate((String)row[0],(String)row[1],(String)row[2],endpoint));}
        assertDoesNotThrow(()->DedicatedDatabaseTarget.validate(endpoint.url(DATABASE),"marketplace_test",DATABASE,endpoint));
    }

    @Test
    void actualConnectionGateChecksInstanceBeforeRoleAndNeverWritesOnMismatch() throws Exception {
        var endpoint=new DedicatedDatabaseTarget.Endpoint(26543,"4abe1092-7cdf-414b-8a2d-e9130af4012e");
        for(boolean admin:java.util.List.of(false,true)) {
            var stub=new JdbcIdentity(endpoint,admin?"postgres":DATABASE,admin?"postgres":"marketplace_test");stub.marker="wrong-instance";
            assertThrows(IllegalArgumentException.class,()->{if(admin){DedicatedDatabaseTarget.verifyAdminConnection(stub.connection(),endpoint);}else{DedicatedDatabaseTarget.verifyConnection(stub.connection(),DATABASE,endpoint);}});
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("SELECT current_setting('marketplace.verification_instance', true)"),stub.queries,"wrong instance must not reach role checks, CREATE DATABASE or other writes");
        }
    }

    @Test
    void actualConnectionGateAllowsExactDefaultAndIsolatedLowRoleButRejectsPrivilegeOrUrlMismatch() throws Exception {
        for(var endpoint:java.util.List.of(new DedicatedDatabaseTarget.Endpoint(15433,"default"),new DedicatedDatabaseTarget.Endpoint(26543,"4abe1092-7cdf-414b-8a2d-e9130af4012e"))) {
            var stub=new JdbcIdentity(endpoint,DATABASE,"marketplace_test");
            assertDoesNotThrow(()->DedicatedDatabaseTarget.verifyConnection(stub.connection(),DATABASE,endpoint));
            for(int column:java.util.List.of(0,1,2)){stub.privileges[column]=true;assertThrows(IllegalArgumentException.class,()->DedicatedDatabaseTarget.verifyConnection(stub.connection(),DATABASE,endpoint));stub.privileges[column]=false;}
            stub.user="postgres";assertThrows(IllegalArgumentException.class,()->DedicatedDatabaseTarget.verifyConnection(stub.connection(),DATABASE,endpoint));stub.user="marketplace_test";
            stub.database="marketplace_dev";assertThrows(IllegalArgumentException.class,()->DedicatedDatabaseTarget.verifyConnection(stub.connection(),DATABASE,endpoint));stub.database=DATABASE;
            stub.url="jdbc:postgresql://127.0.0.1:15432/"+DATABASE;assertThrows(IllegalArgumentException.class,()->DedicatedDatabaseTarget.verifyConnection(stub.connection(),DATABASE,endpoint));
            var admin=new JdbcIdentity(endpoint,"postgres","postgres");assertDoesNotThrow(()->DedicatedDatabaseTarget.verifyAdminConnection(admin.connection(),endpoint));admin.user="marketplace_test";assertThrows(IllegalArgumentException.class,()->DedicatedDatabaseTarget.verifyAdminConnection(admin.connection(),endpoint));
        }
    }

    /** JDBC protocol stub only: no connection, bytecode agent, process attach or external server. */
    private static final class JdbcIdentity {
        String url,marker,database,user;final boolean[] privileges=new boolean[3];final java.util.List<String> queries=new java.util.ArrayList<>();
        JdbcIdentity(DedicatedDatabaseTarget.Endpoint endpoint,String database,String user){this.url=endpoint.url(database);this.marker=endpoint.instance();this.database=database;this.user=user;}
        java.sql.Connection connection(){return proxy(java.sql.Connection.class,(p,m,a)->switch(m.getName()){
            case "getMetaData"->proxy(java.sql.DatabaseMetaData.class,(p2,m2,a2)->{if(m2.getName().equals("getURL")){return url;}throw new UnsupportedOperationException(m2.getName());});
            case "createStatement"->proxy(java.sql.Statement.class,(p2,m2,a2)->{if(m2.getName().equals("close")){return null;}if(!m2.getName().equals("executeQuery")){throw new AssertionError("Writable/unexpected JDBC call: "+m2.getName());}String sql=(String)a2[0];queries.add(sql);Object[] values=sql.contains("current_setting")?new Object[]{marker}:sql.contains("FROM pg_roles")?new Object[]{database,user,privileges[0],privileges[1],privileges[2]}:new Object[]{database,user};var first=new java.util.concurrent.atomic.AtomicBoolean(true);return proxy(java.sql.ResultSet.class,(p3,m3,a3)->switch(m3.getName()){case "next"->first.getAndSet(false);case "getString","getBoolean"->values[(Integer)a3[0]-1];case "close"->null;default->throw new UnsupportedOperationException(m3.getName());});});
            case "close"->null;default->throw new UnsupportedOperationException(m.getName());});}
        private static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
    }

    private int endpointChild(boolean diagnostic,boolean confirmed,boolean sharedUrl) throws Exception {
        String database="d2_it_0123456789abcdef0123456789abcdef",instance="4abe1092-7cdf-414b-8a2d-e9130af4012e";String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        // Exercise the Windows command-line boundary even from a short checkout.
        while(cp.length()<=32767){cp=cp+java.io.File.pathSeparator+cp;}
        var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getProperty("user.dir"),"../.local-tools/endpoint-guard-"+java.util.UUID.randomUUID()));var output=folder.resolve("child.txt");
        var arguments=folder.resolve("probe.args");
        java.nio.file.Files.writeString(arguments,"-cp\n\""+cp.replace((char)92,'/')+"\"\ncom.example.marketplace.jdbcfixture.DedicatedEndpointPropagationProbe\n");
        var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+arguments).redirectErrorStream(true).redirectOutput(output.toFile());
        for(String key:java.util.List.of("D2_TEST_POSTGRES_PORT","D2_TEST_POSTGRES_INSTANCE","D2_DB_ENDPOINT_CONFIRMATION","D2_ADMIN_PASSWORD","D2_DB_PASSWORD","DB_PASSWORD","C3_TEST_ADMIN_PASSWORD","C3_TEST_DB_PASSWORD")){builder.environment().remove(key);}
        builder.environment().put("D2_DB_NAME",database);builder.environment().put("D2_DB_USERNAME","marketplace_test");builder.environment().put("D2_DB_URL","jdbc:postgresql://127.0.0.1:"+(sharedUrl?15433:26543)+"/"+database);
        if(diagnostic){builder.environment().put("D2_TEST_POSTGRES_PORT","26543");builder.environment().put("D2_TEST_POSTGRES_INSTANCE",instance);}
        if(confirmed){builder.environment().put("D2_DB_ENDPOINT_CONFIRMATION",diagnostic?"127.0.0.1:26543|"+instance:"127.0.0.1:15433|default");}
        var process=builder.start();try{org.junit.jupiter.api.Assertions.assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),"endpoint guard child must finish without any DB connection");
            String actual=java.nio.file.Files.readString(output);
            org.junit.jupiter.api.Assertions.assertTrue(confirmed?actual.contains("TARGET_ALLOWED"):actual.contains("IllegalArgumentException: Missing or mismatched dedicated endpoint confirmation"),"child must execute the endpoint guard, not fail to launch: "+actual);
            return process.exitValue();}finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}}
    }
}
