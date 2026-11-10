package com.example.marketplace.dbtest;


import com.example.marketplace.identity.api.AccountException;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellerStoreIT extends PostgresContractFixture {
    @Test
    void storeCloseIsAtomicConcurrentAndKeepsCapabilityBoundary() throws Exception {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3c-atomic@example.com"), other=activeMember("p3c-other@example.com");
        for(UUID id:List.of(owner,other)) { jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",id)); }
        var input=new com.example.marketplace.seller.api.Stores.Input("atomic-store","모의","소개","가상 주소","","","",true,0L);
        var store=service.save(owner,null,input);
        var change=new com.example.marketplace.seller.api.Stores.Change(0L,"😀".repeat(300));
        assertEquals(404,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.transition(other,store.id(),"close",change)).status());
        assertEquals(404,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.history(other,store.id(),false)).status());
        for(String reason:List.of(" ","가".repeat(301))) {
            assertEquals(400,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(0L,reason))).status());
        }
        assertThrows(IllegalStateException.class,()->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES));
        new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.store_operation_event ADD CONSTRAINT p3c_reject_event CHECK (store_id <> '"+store.id()+"'::uuid) NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->service.transition(owner,store.id(),"close",change));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.store_operation_event DROP CONSTRAINT p3c_reject_event"); }
        assertEquals(store,service.own(owner));
        assertTrue(service.history(owner,store.id(),false).isEmpty());
        var results=race(()->{ try { service.transition(owner,store.id(),"close",change); return "ok"; }
            catch(com.example.marketplace.seller.api.StoreException failure) { assertEquals(409,failure.status()); return "conflict"; } });
        assertEquals(1,results.stream().filter("ok"::equals).count());
        assertEquals("CLOSED",service.own(owner).state());
        assertFalse(service.own(owner).visible());
        assertEquals(1,service.history(owner,store.id(),false).size());
        assertEquals(change.reason(),service.history(owner,store.id(),false).getFirst().reason());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,store.id(),input));
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,null,input));
        assertFalse(service.capabilities(owner).manageCatalog());
        assertFalse(service.capabilities(owner).newSales());
        new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.EXISTING_TRADES));
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES)));
    }

    @Test
    void storeLifecycleHttpKeepsBuyingAndRestrictsNewSales() throws Exception {
        UUID owner=activeMember("p3c-owner@example.com"), admin=activeMember("p3c-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",admin));
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        var input=new com.example.marketplace.seller.api.Stores.Input("lifecycle-store","모의 매대","소개","가상 주소","","","",true,0L);
        var store=service.save(owner,null,input);
        var buyer=authBrowser(); var operator=authBrowser();
        assertEquals(200,authRequest(buyer,"/api/auth/login",loginForm("p3c-owner@example.com",SIGNUP_PASSWORD),csrfToken(buyer)).statusCode());
        assertEquals(200,authRequest(operator,"/api/auth/login",loginForm("p3c-admin@example.com",SIGNUP_PASSWORD),csrfToken(operator)).statusCode());
        String path="/api/admin/accounts/stores/"+store.id();
        try {
            assertEquals(403,accountRequest(buyer,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),csrfToken(buyer)).statusCode());
            assertEquals(403,accountRequest(operator,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),null).statusCode());
            assertEquals(200,accountRequest(operator,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),csrfToken(operator)).statusCode());
            assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/lifecycle-store",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/account/profile",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/seller/stores",null,null).statusCode());
            var capabilities=authMapper.readTree(accountRequest(buyer,"GET","/api/seller/capabilities",null,null).body());
            assertFalse(capabilities.get("manageCatalog").asBoolean());
            assertFalse(capabilities.get("newSales").asBoolean());
            assertTrue(capabilities.get("existingTrades").asBoolean());
            assertEquals(200,accountRequest(operator,"POST",path+"/restore",Map.of("version",1,"reason","모의 복구"),csrfToken(operator)).statusCode());
            assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/lifecycle-store",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"POST","/api/seller/stores/"+store.id()+"/close",Map.of("version",2,"reason","모의 폐점"),csrfToken(buyer)).statusCode());
            assertEquals(409,accountRequest(operator,"POST",path+"/restore",Map.of("version",3,"reason","재개 불가"),csrfToken(operator)).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/account/profile",null,null).statusCode());
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM marketplace.store_operation_event WHERE store_id=:id",Map.of("id",store.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }

    @Test
    void storeInputLimitsAndInvalidUpdatesLeaveSavedValuesUnchanged() {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3b-limits@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        var max=new com.example.marketplace.seller.api.Stores.Input("s".repeat(63),"😀".repeat(80),"소".repeat(1000),"주".repeat(200),"공".repeat(1000),"i".repeat(100),"그".repeat(120),true,0L);
        var saved=service.save(owner,null,max);
        assertEquals(max.name(),saved.name());
        assertEquals(max.imageAlt(),service.published(max.slug()).imageAlt());
        var invalid=List.of(
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name()+"x",max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction()+"x",max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address()+"x",max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice()+"x",max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice(),max.imageKey()+"x",max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt()+"x",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug()+"x",max.name(),max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug()," ",max.introduction(),max.address(),"","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름"," ",max.address(),"","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개"," ","","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개","주소","","key","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개","주소","","","alt",true,0L));
        for(var input:invalid) {
            assertEquals(400,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,saved.id(),input)).status());
            assertEquals(saved,service.own(owner));
        }
    }

    @Test
    void storeConcurrentCreateAndUpdateAndValidation() throws Exception {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3b-race@example.com");
        var input=new com.example.marketplace.seller.api.Stores.Input("race-store","모의","소개","가상 주소","","asset-1","가상 그림",false,0L);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.save(owner,null,input));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        var created=race(()->{ try { service.save(owner,null,input); return "ok"; } catch(com.example.marketplace.seller.api.StoreException ex) { assertEquals(409,ex.status()); return "conflict"; } });
        assertEquals(1,created.stream().filter("ok"::equals).count());
        var current=service.own(owner);
        var updated=race(()->{ try { service.save(owner,current.id(),input); return "ok"; } catch(com.example.marketplace.seller.api.StoreException ex) { assertEquals(409,ex.status()); return "conflict"; } });
        assertEquals(1,updated.stream().filter("ok"::equals).count());
        assertEquals(1,service.own(owner).version());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,current.id(),new com.example.marketplace.seller.api.Stores.Input("changed-slug","모의","소개","주소","","","",false,1L)));
        for(String slug:List.of("aa","UPPER","../bad","-bad","bad-")) {
            assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,null,new com.example.marketplace.seller.api.Stores.Input(slug,"모의","소개","주소","","","",false,0L)));
        }
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,current.id(),new com.example.marketplace.seller.api.Stores.Input("race-store","모의","소개","주소","","https://external","그림",false,1L)));
        assertEquals(1,service.own(owner).version());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.published("race-store"));
        jdbc.update("UPDATE marketplace.identity_user SET state='WITHDRAWN' WHERE id=:id",Map.of("id",owner));
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.save(owner,current.id(),input));
    }

    @Test
    void storeHttpOwnershipVisibilityAndVersion() throws Exception {
        UUID owner = activeMember("p3b-owner@example.com");
        UUID other = activeMember("p3b-other@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", other));
        var browser = authBrowser();
        var stranger = authBrowser();
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("p3b-owner@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        assertEquals(200,authRequest(stranger,"/api/auth/login",loginForm("p3b-other@example.com",SIGNUP_PASSWORD),csrfToken(stranger)).statusCode());
        var fields = new java.util.HashMap<String,Object>(Map.of("slug","sample-store","name","모의 매대","introduction","가상 소개","address","모의 주소","notice","","imageKey","","imageAlt","","visible",false,"version",0));
        assertEquals(403, accountRequest(browser,"POST","/api/seller/stores", fields,null).statusCode());
        var created = accountRequest(browser,"POST","/api/seller/stores",fields,csrfToken(browser));
        assertEquals(201,created.statusCode());
        var store = new tools.jackson.databind.ObjectMapper().readTree(created.body());
        String path = "/api/seller/stores/" + store.get("id").asText();
        assertEquals(409,accountRequest(browser,"POST","/api/seller/stores",fields,csrfToken(browser)).statusCode());
        assertEquals(409,accountRequest(stranger,"POST","/api/seller/stores",fields,csrfToken(stranger)).statusCode());
        assertEquals(404,accountRequest(stranger,"PUT",path,fields,csrfToken(stranger)).statusCode());
        assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null).statusCode());
        fields.put("visible",true);
        assertEquals(200,accountRequest(browser,"PUT",path,fields,csrfToken(browser)).statusCode());
        assertEquals(409,accountRequest(browser,"PUT",path,fields,csrfToken(browser)).statusCode());
        var published = accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null);
        assertEquals(200,published.statusCode());
        assertFalse(published.body().contains(owner.toString()));
        assertFalse(published.body().contains("version"));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
        assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null).statusCode());
    }

    @Test
    void sellerDecisionsRejectInvalidTransitionsAndRollbackRoleAndHistory() {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID operator = activeMember("p3-reviewer@example.com");
        UUID applicant = activeMember("p3-rejected@example.com");
        var input = new com.example.marketplace.seller.api.SellerApplications.Input("가상 매대", "가상 계획");
        var first = service.submit(applicant, input);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> service.decide(operator, first.id(), "APPROVED", "사유"));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", operator));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", operator));
        try {
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "PENDING", "사유"));
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "APPROVED", " "));
            var rejected = service.decide(operator, first.id(), "REJECTED", "계획 보완 필요");
            assertEquals("REJECTED", rejected.status());
            assertEquals("계획 보완 필요", rejected.events().getLast().reason());
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "APPROVED", "사유"));
            var second = service.submit(applicant, input);
            assertEquals(2, service.own(applicant, 0).items().size());
            var self = service.submit(operator, input);
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, self.id(), "APPROVED", "자기 심사"));
            jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.seller_application_event ADD CONSTRAINT p3_reject_event CHECK (state <> 'APPROVED') NOT VALID");
            try {
                assertThrows(DataIntegrityViolationException.class, () -> service.decide(operator, second.id(), "APPROVED", "승인"));
            } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.seller_application_event DROP CONSTRAINT p3_reject_event"); }
            assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM marketplace.seller_application WHERE id=:id", Map.of("id", second.id()), String.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.seller_application_event WHERE application_id=:id", Map.of("id", second.id()), Integer.class));
            assertEquals(List.of("BUYER"), jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id=:id", Map.of("id", applicant), String.class));
            jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", applicant));
            assertThrows(AccountException.class, () -> service.decide(operator, second.id(), "APPROVED", "승인"));
            jdbc.update("UPDATE marketplace.identity_user SET state='WITHDRAWN' WHERE id=:id", Map.of("id", applicant));
            assertThrows(AccountException.class, () -> service.decide(operator, second.id(), "REJECTED", "반려"));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", operator));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", operator));
        }
    }

    @Test
    void sellerConcurrentApplicationsAndDecisionsHaveOneWinner() throws Exception {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID applicant = activeMember("p3-race-buyer@example.com");
        UUID operator = activeMember("p3-race-admin@example.com");
        var input = new com.example.marketplace.seller.api.SellerApplications.Input("경쟁 매대", "모의 판매");
        var submitted = race(() -> {
            try { service.submit(applicant, input); return "ok"; }
            catch (com.example.marketplace.seller.api.SellerApplicationException conflict) { return "blocked"; }
        });
        assertEquals(1, submitted.stream().filter("ok"::equals).count());
        assertEquals(1, submitted.stream().filter("blocked"::equals).count());
        UUID id = service.own(applicant, 0).items().getFirst().id();
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", operator));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", operator));
        try {
            var turn = new java.util.concurrent.atomic.AtomicInteger();
            var decisions = race(() -> {
                try { service.decide(operator, id, turn.getAndIncrement() == 0 ? "APPROVED" : "REJECTED", "경쟁 심사"); return "ok"; }
                catch (com.example.marketplace.seller.api.SellerApplicationException conflict) { return "blocked"; }
            });
            assertEquals(1, decisions.stream().filter("ok"::equals).count());
            assertEquals(1, decisions.stream().filter("blocked"::equals).count());
            var result = service.own(applicant, 0).items().getFirst();
            assertEquals(2, result.events().size());
            assertEquals(result.status(), result.events().getLast().status());
            assertEquals(result.status().equals("APPROVED") ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", applicant), Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", operator));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", operator));
        }
    }

    @Test
    void sellerApplicationOwnershipPaginationAndNarrowSecurityException() throws Exception {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID buyer = activeMember("p3-boundary@example.com");
        UUID other = activeMember("p3-other@example.com");
        var foreign = service.submit(other, new com.example.marketplace.seller.api.SellerApplications.Input("다른 매대", "가상 계획"));
        var browser = authBrowser();
        assertEquals(401, accountRequest(browser, "GET", "/api/seller/applications", null, null).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("p3-boundary@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertTrue(service.own(buyer, 0).items().isEmpty());
        for (String path : List.of("/api/seller/applications/" + foreign.id(), "/api/seller/products", "/api/admin/accounts/seller-applications")) {
            assertEquals(403, accountRequest(browser, "GET", path, null, null).statusCode());
        }
        assertEquals(403, accountRequest(browser, "PUT", "/api/seller/applications", Map.of(), csrfToken(browser)).statusCode());
        assertEquals(400, accountRequest(browser, "POST", "/api/seller/applications", Map.of("displayName", " ", "description", "bad"), csrfToken(browser)).statusCode());
        assertEquals(400, accountRequest(browser, "GET", "/api/seller/applications?page=-1", null, null).statusCode());
        for (int i = 0; i < 21; i++) {
            UUID applicationId = UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace.seller_application(id,applicant_id,display_name,description,state) VALUES(:id,:member,'목록 매대','모의 자료','REJECTED')",
                    Map.of("id", applicationId, "member", buyer));
            for (String state : List.of("PENDING", "REJECTED")) {
                jdbc.update("INSERT INTO marketplace.seller_application_event(id,application_id,actor_id,state,reason) VALUES(:id,:application,:member,:state,'모의 목록 이력')",
                        Map.of("id", UUID.randomUUID(), "application", applicationId, "member", buyer, "state", state));
            }
        }
        assertEquals(20, service.own(buyer, 0).items().size());
        assertTrue(service.own(buyer, 0).hasMore());
        assertEquals(1, service.own(buyer, 1).items().size());
        assertFalse(service.own(buyer, 1).hasMore());
        assertEquals(1, service.own(other, 0).items().size());
        assertThrows(IllegalStateException.class, () -> context.getBean(com.example.marketplace.identity.api.SellerMembership.class).lockActiveBuyer(buyer));
    }

    @Test
    void sellerApplicationHttpSubmissionReviewAndBuyerRolePreserved() throws Exception {
        UUID buyerId = activeMember("p3-buyer@example.com");
        UUID adminId = activeMember("p3-admin@example.com");
        var buyer = authBrowser();
        var admin = authBrowser();
        assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("p3-buyer@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
        assertEquals(200, authRequest(admin, "/api/auth/login", loginForm("p3-admin@example.com", SIGNUP_PASSWORD), csrfToken(admin)).statusCode());
        String own = "/api/seller/applications";
        var input = Map.of("displayName", "모의 매대", "description", "가상 상품 판매 계획", "applicantId", adminId.toString(), "status", "APPROVED");
        assertEquals(403, accountRequest(buyer, "POST", own, input, null).statusCode());
        var submitted = accountRequest(buyer, "POST", own, input, csrfToken(buyer));
        assertEquals(201, submitted.statusCode());
        var body = authMapper.readTree(submitted.body());
        String id = body.get("id").asText();
        assertEquals(buyerId.toString(), body.get("applicantId").asText());
        assertEquals("PENDING", body.get("status").asText());
        assertEquals(409, accountRequest(buyer, "POST", own, input, csrfToken(buyer)).statusCode());
        String review = "/api/admin/accounts/seller-applications/" + id + "/decision";
        var approved = Map.of("decision", "APPROVED", "reason", "모의 심사 완료");
        assertEquals(403, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", adminId));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", adminId));
        try {
            assertEquals(403, accountRequest(admin, "POST", review, approved, null).statusCode());
            assertEquals(200, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
            assertEquals(409, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
            assertEquals(List.of("BUYER", "SELLER"), authMapper.readTree(authRequest(buyer, "/api/auth/me", null, null).body()).get("roles")
                    .valueStream().map(node -> node.asText()).sorted().toList());
            assertEquals(200, authRequest(buyer, "/api/seller/access", null, null).statusCode());
            var listing = authMapper.readTree(accountRequest(buyer, "GET", own, null, null).body());
            assertEquals(1, listing.get("items").size());
            assertEquals(2, listing.get("items").get(0).get("events").size());
            assertEquals(409, accountRequest(buyer, "POST", own, input, csrfToken(buyer)).statusCode());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", adminId));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", adminId));
        }
    }
}
