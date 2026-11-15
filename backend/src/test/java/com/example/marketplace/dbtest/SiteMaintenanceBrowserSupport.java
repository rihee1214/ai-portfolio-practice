package com.example.marketplace.dbtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Separate JVM/DB per project. Only identities and rights are bootstrapped here. */
@ActiveProfiles("site-maintenance-fixture")
@Import({SiteMaintenanceBrowserSupport.Observation.class,SiteMaintenanceBrowserSupport.Security.class})
class SiteMaintenanceBrowserSupport extends PostgresContractFixture {
    protected void runMaintenanceBrowser(String project) throws Exception {
        assertEquals(project,System.getenv("P12_B_BROWSER_PROJECT"));
        assertEquals("true",System.getenv("A3_RUN_BROWSER"));
        var observer=context.getBean(Observation.class);
        var initial=observer.policy();
        assertEquals(Map.of("version",1L,"baseFee",3000L,"freeThreshold",50000L),initial);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order",Map.of(),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_product",Map.of(),Integer.class));
        var members=new LinkedHashMap<String,UUID>();
        for(String role:List.of("system","content","accounts","seller","buyer")) {
            UUID id=activeMember("p12b-"+role+"-"+project+"@example.com");members.put(role,id);
            if(List.of("system","content","accounts").contains(role)) {
                jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'ADMIN')",Map.of("id",id));
                jdbc.update("INSERT INTO marketplace.admin_permission(user_id,permission) VALUES(:id,:permission)",Map.of("id",id,"permission",role.toUpperCase(java.util.Locale.ROOT)));
            } else if(role.equals("seller")) {
                jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'SELLER')",Map.of("id",id));
            }
        }
        observer.initialize(project,port,initial,members);
        assertTrue(Observation.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("test-classes"));
        var root=Path.of("..").toAbsolutePath().normalize();
        var node=root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe");
        var builder=new ProcessBuilder(node.toString(),"node_modules/@playwright/test/cli.js","test",
                "--config=auth-e2e/site-maintenance.config.ts").directory(root.resolve("frontend").toFile());
        builder.environment().put("A3_API_PORT",Integer.toString(port));
        builder.environment().put("P12_B_BROWSER_PROJECT",project);
        builder.environment().put("PATH",node.getParent()+java.io.File.pathSeparator+System.getenv("PATH"));
        var folder=Path.of(System.getenv("A3_EVIDENCE"));
        builder.redirectErrorStream(true).redirectOutput(folder.resolve("browser.txt").toFile());
        long started=System.nanoTime();var child=builder.start();recordBrowserTiming("maintenance:started",started,child.pid());
        com.example.marketplace.jdbcfixture.BrowserChildWatchdog.await(child,folder.resolve("browser-progress.ndjson"));
        recordBrowserTiming("maintenance:exited",started,child.exitValue());
        assertEquals(0,child.exitValue(),"See isolated maintenance browser.txt");
        var current=observer.policy();
        assertEquals(Map.of("version",2L,"baseFee",4567L,"freeThreshold",51000L),current);
        int orders=jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order",Map.of(),Integer.class);
        assertTrue(orders>=2,"The original paid order and reconfirmed order must be real business outputs");
        assertEquals(orders,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",members.get("buyer")),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.site_shipping_policy WHERE version>1",Map.of(),Integer.class));
        assertFalse(observer.identity().get("databaseName").equals("marketplace"));
        var proof=new LinkedHashMap<String,Object>();proof.putAll(observer.identity());
        proof.put("database",observer.identity().get("databaseName"));proof.put("httpPort",port);
        proof.put("initialPolicy",initial);proof.put("finalPolicy",current);proof.put("orders",orders);
        proof.put("businessRowsBefore",0);proof.put("originalPolicyReset",false);
        Files.writeString(folder.resolve("site-maintenance-lifecycle.json"),authMapper.writeValueAsString(proof));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @Profile("site-maintenance-fixture")
    static class Security {
        @org.springframework.context.annotation.Bean
        @org.springframework.core.annotation.Order(-1)
        org.springframework.security.web.SecurityFilterChain maintenanceObservationChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/__test/site-maintenance/**")
                    .authorizeHttpRequests(auth->auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/**").permitAll().anyRequest().denyAll()).build();
        }
    }

    /** No producer/mutation endpoint: snapshots read actual committed application output. */
    @RestController @Profile("site-maintenance-fixture")
    static class Observation {
        private final NamedParameterJdbcTemplate sql;
        private final TransactionTemplate snapshot;
        private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
        private volatile Map<String,Object> scope;
        @Autowired Observation(NamedParameterJdbcTemplate sql,PlatformTransactionManager manager) {
            this.sql=sql;this.snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);
            snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
        void initialize(String project,int port,Map<String,Long> initial,Map<String,UUID> members) {
            scope=Map.of("project",project,"apiPort",port,"pid",ProcessHandle.current().pid(),
                    "databaseName",sql.queryForObject("SELECT current_database()",Map.of(),String.class),
                    "media",Path.of(System.getenv("A3_EVIDENCE"),"media").toString(),"initialPolicy",initial,"members",Map.copyOf(members));
        }
        Map<String,Long> policy() {
            return sql.queryForObject("SELECT p.version,p.base_fee,p.free_threshold FROM marketplace.site_shipping_head h JOIN marketplace.site_shipping_policy p ON p.version=h.version",Map.of(),
                    (row,index)->Map.of("version",row.getLong(1),"baseFee",row.getLong(2),"freeThreshold",row.getLong(3)));
        }
        Map<String,Object> identity() {
            if(scope==null){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
            return scope;
        }
        @GetMapping("/api/__test/site-maintenance/identity")
        Map<String,Object> observeIdentity(jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control","no-store");return identity();
        }
        @GetMapping("/api/__test/site-maintenance/database")
        Map<String,Object> database(@RequestParam(required=false) String orderNumber,
                @RequestParam(required=false) UUID requestKey,jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control","no-store");
            return snapshot.execute(tx->{
                var parameters=new LinkedHashMap<String,Object>();String order=null;
                if(orderNumber!=null) {
                    var found=sql.queryForList("SELECT id FROM marketplace.purchase_order WHERE order_number=:number",Map.of("number",orderNumber));
                    if(found.size()!=1){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
                    order=found.getFirst().get("id").toString();parameters.put("order",order);
                }
                var tables=new LinkedHashMap<String,Object>();
                for(String table:List.of("site_shipping_head","site_shipping_policy","site_post","site_post_revision","media_site_image","site_info_revision")) {
                    tables.put(table,rows(table,"true",Map.of()));
                }
                for(String table:List.of("purchase_order","seller_order","order_item","order_unit","order_contact","order_checkout_attempt",
                        "order_event","order_reservation","order_aftercare_policy","order_settlement_policy","payment_attempt","payment_event","payment_mock",
                        "payment_refund_budget","payment_refund_attempt","payment_refund_event","inventory_reservation","inventory_reservation_event",
                        "coupon_reservation","coupon_reservation_event","coupon_restoration","settlement_ledger")) {
                    tables.put(table,rows(table,order==null?"true":ownedRows(table),parameters));
                }
                var result=new LinkedHashMap<String,Object>(identity());result.put("tables",tables);result.put("currentPolicy",policy());
                if(requestKey!=null) {
                    int matches=0;
                    for(var entry:Map.of("matchingPolicy","site_shipping_policy","matchingPosts","site_post_revision","matchingInfo","site_info_revision").entrySet()) {
                        var matching=rows(entry.getValue(),"t.request_key=:key",Map.of("key",requestKey));
                        result.put(entry.getKey(),matching);matches+=matching.size();
                    }
                    if(matches==0){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
                }
                return result;
            });
        }
        private tools.jackson.databind.JsonNode rows(String table,String predicate,Map<String,?> values) {
            String json=sql.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t WHERE "+predicate,values,String.class);
            return mapper.readTree(json);
        }
        private String ownedRows(String table) {
            String order="CAST(:order AS uuid)";
            return switch(table) {
                case "purchase_order" -> "t.id="+order;
                case "order_checkout_attempt" -> "t.id=(SELECT checkout_id FROM marketplace.purchase_order WHERE id="+order+")";
                case "order_unit" -> "t.item_id IN(SELECT id FROM marketplace.order_item WHERE order_id="+order+")";
                case "payment_event","payment_mock" -> "t.attempt_id IN(SELECT id FROM marketplace.payment_attempt WHERE order_id="+order+")";
                case "payment_refund_event" -> "t.attempt_id IN(SELECT id FROM marketplace.payment_refund_attempt WHERE order_id="+order+")";
                case "inventory_reservation","coupon_reservation" -> "t.id IN(SELECT reservation_id FROM marketplace.order_reservation WHERE order_id="+order+")";
                case "inventory_reservation_event","coupon_reservation_event","coupon_restoration" -> "t.reservation_id IN(SELECT reservation_id FROM marketplace.order_reservation WHERE order_id="+order+")";
                default -> "t.order_id="+order;
            };
        }
    }
}
