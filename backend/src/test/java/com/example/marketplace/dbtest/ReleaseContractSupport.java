package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.infrastructure.InitialAdmin;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-side observation and public command clients; no production test endpoints. */
final class ReleaseContractSupport implements AutoCloseable {
    final Path root = Path.of("..").toAbsolutePath().normalize();
    final Path folder = Path.of(System.getenv("A3_EVIDENCE"));
    final JsonMapper json = JsonMapper.builder().build();
    final List<Process> children = new ArrayList<>();
    final Map<String,String> passwords = new LinkedHashMap<>();
    final Map<String,Object> accounts = new LinkedHashMap<>();
    final java.util.Set<String> handlerTypes = new java.util.TreeSet<>();

    record Purchase(String number, String id, String sku, Client buyer, Client seller, long amount) { }

    Purchase purchase(String base, String project, String label) throws Exception {
        var admin = new Client(base); admin.login("p12d-"+project+"-admin@example.com");
        var seller = new Client(base); seller.login("p12d-"+project+"-seller@example.com");
        var buyer = new Client(base); buyer.login("p12d-"+project+"-buyer@example.com");
        String slug="p12d-"+label+"-"+UUID.randomUUID().toString().substring(0,8);
        String category=admin.request("POST","/api/admin/content/categories",Map.of("name",slug,"active",true,"version",0),201).path("id").asText();
        var stores=seller.request("GET","/api/seller/stores",null,200);
        if(stores.path("store").isNull()) { seller.request("POST","/api/seller/stores",Map.of("slug",slug,"name",slug,"introduction","합성 릴리스 매대","address","합성 주소","notice","","imageKey","","imageAlt","","visible",true,"version",0),201); }
        var input=new LinkedHashMap<String,Object>();
        input.put("categoryId",category);input.put("slug",slug);input.put("title",slug);input.put("description","실제 릴리스 API 생산");input.put("state","DRAFT");input.put("version",0);input.put("returnFee","3000");
        var product=seller.request("POST","/api/seller/products",input,201);String productId=product.path("id").asText();
        var initial=seller.request("GET","/api/seller/products/"+productId+"/skus",null,200).path("items").get(0);
        String sku=initial.path("id").asText();
        seller.request("PUT","/api/seller/products/"+productId+"/skus/"+sku,Map.of("options",List.of(),"price",10019,"active",true,"version",initial.path("version").asLong()),200);
        var stock=seller.request("GET","/api/seller/products/"+productId+"/inventory",null,200).path("items").get(0);
        seller.request("PUT","/api/seller/products/"+productId+"/inventory/"+sku,Map.of("onHand",10,"version",stock.path("version").asLong(),"requestId",UUID.randomUUID(),"reason","실제 릴리스 명령 입고"),200);
        input.put("version",product.path("version").asLong());input.put("state","ACTIVE");seller.request("PUT","/api/seller/products/"+productId,input,200);
        var lines=List.of(Map.of("skuId",sku,"quantity",2));
        var quote=buyer.request("POST","/api/quotes",Map.of("lines",lines,"coupons",List.of()),200);
        long expected=10019L*2+3000L;assertEquals(Long.toString(expected),quote.path("total").asText());
        var contact=Map.of("buyerName","복구 구매자","buyerPhone","01012345678","recipientName","복구 수령인","recipientPhone","01087654321","postalCode","01234","addressLine1","합성 복구 주소","addressLine2","101호");
        var command=Map.of("requestKey",UUID.randomUUID().toString(),"lines",lines,"coupons",List.of(),"fingerprint",quote.path("fingerprint").asText(),"contact",contact,"linkRevision",0);
        var order=buyer.request("POST","/api/orders",command,200);String number=order.path("orderNumber").asText();
        assertEquals(order,buyer.request("POST","/api/orders",command,200));
        buyer.request("POST","/api/payments/"+number+"/approve",Map.of(),200);
        assertEquals("PAID",buyer.request("GET","/api/orders/"+number,null,200).path("state").asText());
        return new Purchase(number,order.path("id").asText(),sku,buyer,seller,expected);
    }

    String createDatabase(String name) throws Exception {
        String url = com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.databaseUrl(name);
        com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.validate(url, "marketplace_test", name);
        var properties=new java.util.Properties();properties.setProperty("user","postgres");properties.setProperty("password",System.getenv("D2_ADMIN_PASSWORD"));
        properties.setProperty("connectTimeout","3");properties.setProperty("socketTimeout","3");
        try (var connection = DriverManager.getConnection(com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.maintenanceUrl(),properties); var statement = connection.createStatement()) {
            com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyAdminConnection(connection);
            statement.executeUpdate("CREATE DATABASE \"" + name + "\" OWNER marketplace_test");
        }
        System.out.println("D2_CREATED_DATABASE=" + name);
        return url;
    }

    NamedParameterJdbcTemplate jdbc(String url, String user, String password) {
        String bounded=url+(url.contains("?")?"&":"?")+"connectTimeout=3&socketTimeout=3";
        return new NamedParameterJdbcTemplate(new DriverManagerDataSource(bounded, user, password));
    }

    Process jar(Path jar, String name, String url, String user, String password, Path media, int port, String role) throws Exception {
        Path log = folder.resolve(name + ".log");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-jar", jar.toString(),
                "--spring.profiles.active=db,local-http" + (role.equals("worker") ? ",worker" : ""),
                "--server.address=127.0.0.1", "--server.port=" + port, "--marketplace.phone.mode=disabled",
                "--spring.datasource.hikari.data-source-properties.connectTimeout=3","--spring.datasource.hikari.data-source-properties.socketTimeout=3"));
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        var env = builder.environment();
        env.keySet().removeIf(key -> key.startsWith("TOSS_") || key.startsWith("MARKETPLACE_RUNTIME_") || key.startsWith("FIREBASE_")
                || key.startsWith("FIRESTORE_") || key.equals("D2_ADMIN_PASSWORD"));
        env.put("DB_URL", url); env.put("DB_USERNAME", user); env.put("DB_PASSWORD", password); env.put("MEDIA_ROOT", media.toString());
        var process = builder.start(); children.add(process);
        awaitLog(process, log, role.equals("worker") ? "Started MarketplaceApplication" : "Tomcat started on port", 30);
        return process;
    }

    void awaitLog(Process child, Path file, String marker, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end && child.isAlive()) {
            if (Files.exists(file) && Files.readString(file).contains(marker)) { return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Startup failed; preserved log " + file);
    }

    void stop(Process child) throws Exception {
        child.destroy();
        if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
    }

    void command(Path log, List<String> command, Map<String,String> environment, int seconds) throws Exception {
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        var child = builder.start(); children.add(child);
        if (!child.waitFor(seconds, TimeUnit.SECONDS)) {
            com.example.marketplace.jdbcfixture.BrowserChildWatchdog.terminate(child);
            throw new AssertionError("Finite infrastructure phase exceeded " + seconds + " seconds: " + log);
        }
        assertEquals(0, child.exitValue(), "Inspect preserved log " + log);
    }

    void accounts(String url, String user, String password, Path media, String flywayTarget) throws Exception {
        var app = new SpringApplication(MarketplaceApplication.class);
        app.setDefaultProperties(Map.of("spring.datasource.url",url,"spring.datasource.username",user,"spring.datasource.password",password,
                "DB_URL",url,"DB_USERNAME",user,"DB_PASSWORD",password,"MEDIA_ROOT",media.toString(),
                "marketplace.media.root",media.toString(),"spring.datasource.hikari.data-source-properties.connectTimeout","3",
                "spring.datasource.hikari.data-source-properties.socketTimeout","3"));
        try (var context = app.run("--spring.profiles.active=db,local-http,local-mailbox", "--server.port=0", "--server.address=127.0.0.1",
                "--marketplace.phone.mode=disabled", "--marketplace.runtime.mode=local-full", "--spring.flyway.target=" + flywayTarget)) {
            var signup = context.getBean(SignupOperations.class);
            var mailbox = context.getBean(LocalTestMailbox.class);
            var jdbc = context.getBean(NamedParameterJdbcTemplate.class);
            var manager = context.getBean(PlatformTransactionManager.class);
            context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values().forEach(handler -> handlerTypes.add(handler.type()));
            UUID operator = null;
            var sellers = new ArrayList<UUID>();
            for (String project : List.of("desktop","mobile")) {
                var actors = new LinkedHashMap<String,String>();
                for (String role : List.of("admin","buyer","seller","other")) {
                    String email = "p12d-" + project + "-" + role + "@example.com";
                    String individual = " P12D-" + UUID.randomUUID() + "-individual-가나다! ";
                    UUID id = signup.register(email, individual); passwords.put(email, individual);
                    signup.verifyEmail(mailbox.messages().stream().filter(message -> message.recipient().equals(email)).findFirst().orElseThrow().token());
                    if (role.equals("admin")) {
                        if (operator == null) {
                            operator = id;
                            String database = jdbc.queryForObject("SELECT current_database()",Map.of(),String.class);
                            new InitialAdmin(jdbc,manager).initialize(id,database,"INITIAL_ADMIN");
                        } else {
                            jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'ADMIN')",Map.of("id",id));
                            jdbc.update("INSERT INTO marketplace.admin_permission(user_id,permission) SELECT :id,permission FROM marketplace.admin_permission WHERE user_id=:operator",Map.of("id",id,"operator",operator));
                        }
                    }
                    if (role.equals("seller")) { sellers.add(id); }
                    actors.put(role,email);
                }
                accounts.put(project,actors);
            }
            UUID admin = operator;
            for (UUID seller : sellers) {
                new TransactionTemplate(manager).executeWithoutResult(status -> context.getBean(com.example.marketplace.identity.api.SellerMembership.class).grant(admin,seller));
            }
            jdbc.update("DELETE FROM marketplace.spring_session",Map.of());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order",Map.of(),Integer.class));
            assertEquals(8,new java.util.HashSet<>(passwords.values()).size());
            Files.writeString(folder.resolve("account-preparation-" + UUID.randomUUID() + ".json"),json.writeValueAsString(Map.of("accounts",accounts,
                    "database",jdbc.queryForObject("SELECT current_database()",Map.of(),String.class),"ordersCreated",0,"distinctPasswords",8,
                    "role","account-and-authority-preparation-only")));
        }
    }

    Map<String,String> snapshot(NamedParameterJdbcTemplate jdbc, boolean businessOnly) {
        var result = new java.util.TreeMap<String,String>();
        jdbc.getJdbcTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            boolean auto=connection.getAutoCommit(),readOnly=connection.isReadOnly();int isolation=connection.getTransactionIsolation();
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);connection.setReadOnly(true);connection.setAutoCommit(false);
            try(var command=connection.createStatement()) {
                command.setQueryTimeout(3);command.execute("SET LOCAL statement_timeout='3s'");
                var tables=new ArrayList<String>();try(var rows=command.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='marketplace' ORDER BY tablename")) {while(rows.next()) {tables.add(rows.getString(1));}}
                for(String table:tables) {
                    if(businessOnly&&(table.startsWith("spring_session")||table.startsWith("guest_")&&!List.of("guest_buyer","guest_order_reference").contains(table)
                            ||List.of("email_verification_token","password_reset_token","job","outbox_event","flyway_schema_history").contains(table)||table.startsWith("dashboard_")||table.endsWith("_limit"))) {continue;}
                    assertTrue(table.matches("[a-z_][a-z_0-9]*"));
                    try(var row=command.executeQuery("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace.\""+table+"\" t")) {assertTrue(row.next());result.put(table,row.getString(1));}
                }
                connection.commit();
            } finally {connection.rollback();connection.setAutoCommit(auto);connection.setReadOnly(readOnly);connection.setTransactionIsolation(isolation);}
            return null;
        });
        return result;
    }

    final class Client {
        final String base;
        final HttpClient http;
        Client(String base) { this.base=base; this.http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build(); }
        JsonNode request(String method,String path,Object body,int status) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            var builder=HttpRequest.newBuilder(URI.create(base+path));
            if (!method.equals("GET")) {
                var csrf=http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,csrf.statusCode()); builder.header("X-CSRF-TOKEN",json.readTree(csrf.body()).path("token").asText());
            }
            builder.timeout(remaining(deadline));
            builder.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(status,response.statusCode(),method+" "+path+": "+response.body());
            return json.readTree(response.body());
        }
        void login(String email) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            var csrf=request("GET","/api/auth/csrf",null,200);
            String form="email="+java.net.URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8)+"&password="+java.net.URLEncoder.encode(passwords.get(email),java.nio.charset.StandardCharsets.UTF_8);
            var request=HttpRequest.newBuilder(URI.create(base+"/api/auth/login")).timeout(remaining(deadline)).header("Content-Type","application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN",csrf.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString(form)).build();
            assertEquals(200,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        private Duration remaining(long deadline) {long nanos=deadline-System.nanoTime();assertTrue(nanos>0,"Public command shared ten-second deadline expired");return Duration.ofNanos(nanos);}
    }

    static int port() throws Exception { try (var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    static String sha(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    static Path requiredPath(String name) { String value=System.getenv(name); if(value==null||!Files.exists(Path.of(value))) { throw new IllegalStateException("Required retained release artifact missing: "+name); } return Path.of(value); }
    @Override public void close() throws Exception { for(var child:children) { if(child.isAlive()) { stop(child); } } }
}
