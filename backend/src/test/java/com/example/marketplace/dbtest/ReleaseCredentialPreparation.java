package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.infrastructure.LocalPasswordResetMailbox;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real, unused signup/reset credentials intentionally included in the private checkpoint. */
final class ReleaseCredentialPreparation {
    private record PendingToken(String table,String hash,Map<String,Object> row,Map<String,Object> account,Map<String,Object> credential) { }
    private record GuestProof(String browser,String access,String grant,UUID consumedCheckout,Map<String,Object> consumed,
            com.example.marketplace.phone.api.PhoneVerifications provider,java.time.Clock clock,java.util.List<PendingToken> pendingTokens) { }
    private static final Map<UUID,GuestProof> GUEST_PROOFS=new java.util.concurrent.ConcurrentHashMap<>();
    static Map<String,String> prepare(String url,String user,String password,Path media,ReleaseContractSupport support) throws Exception {
        var application=new SpringApplication(MarketplaceApplication.class);
        application.setDefaultProperties(Map.of("spring.datasource.url",url,"spring.datasource.username",user,"spring.datasource.password",password,
                "DB_URL",url,"DB_USERNAME",user,"DB_PASSWORD",password,"MEDIA_ROOT",media.toString(),"marketplace.media.root",media.toString()));
        try(var context=application.run("--spring.profiles.active=db,local-http,local-mailbox","--server.port=0","--server.address=127.0.0.1",
                "--marketplace.phone.mode=disabled","--marketplace.runtime.mode=local-full")) {
            String pending="p12d-unused-"+UUID.randomUUID()+"@example.com";
            context.getBean(SignupOperations.class).register(pending,"unused "+UUID.randomUUID()+"-가나다!");
            String emailToken=context.getBean(LocalTestMailbox.class).messages().stream().filter(message->message.recipient().equals(pending)).findFirst().orElseThrow().token();
            String email="p12d-desktop-buyer@example.com";
            context.getBean(PasswordRecoveryOperations.class).request(email);
            String resetToken=context.getBean(LocalPasswordResetMailbox.class).messages().stream().filter(message->message.recipient().equals(email)).findFirst().orElseThrow().token();
            var jdbc=support.jdbc(url,user,password);var before=support.snapshot(jdbc,false);
            // Existing approved authentication fixture: actual guest purchase/recovery services,
            // synthetic phone proof only, no order/payment/financial final states are seeded.
            var fixture=new PostgresContractFixture();fixture.jdbc=jdbc;fixture.transactionManager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
            var guest=fixture.new GuestFixture();var access=fixture.guestAccess(guest);String reference="AUTH-RESTORE-"+UUID.randomUUID();
            UUID buyer=fixture.guestBuyer(guest,access,reference);
            String oldAccess=access.login(reference,guest.phone,PostgresContractFixture.GUEST_PASSWORD,guest.source,null);
            assertEquals(1,access.orders(oldAccess).size());
            UUID consumed=jdbc.queryForObject("SELECT checkout_id FROM marketplace.guest_attempt WHERE consumed_at IS NOT NULL AND checkout_id IN (SELECT id FROM marketplace.guest_checkout WHERE browser_hash=:hash)",Map.of("hash",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(guest.browser)),UUID.class);
            var history=jdbc.queryForMap("SELECT * FROM marketplace.guest_checkout WHERE id=:id",Map.of("id",consumed));
            // A repeated create on the same browser reopens its deterministic checkout.
            // Keep the consumed history and the unused credential as separate real purchases.
            var unusedGuest=fixture.new GuestFixture();
            UUID unusedAttempt=fixture.guestVerified(unusedGuest);
            UUID unusedCheckout=jdbc.queryForObject("SELECT checkout_id FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",unusedAttempt),UUID.class);
            org.junit.jupiter.api.Assertions.assertNotEquals(consumed,unusedCheckout,"Unused verification must have a distinct checkout identity");
            assertEquals("VERIFIED",jdbc.queryForObject("SELECT state FROM marketplace.guest_checkout WHERE id=:id",Map.of("id",unusedCheckout),String.class));
            var recovery=fixture.recovery(guest);String code=recovery.initialCode(buyer),grant=fixture.resetGrant(guest,recovery,reference,code);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_recovery_attempt WHERE buyer_id=:id AND state='READY' AND grant_hash IS NOT NULL",Map.of("id",buyer),Integer.class));
            assertEquals("CONSUMED",history.get("state"));
            org.junit.jupiter.api.Assertions.assertTrue(history.equals(jdbc.queryForMap("SELECT * FROM marketplace.guest_checkout WHERE id=:id",Map.of("id",consumed))),
                    "Credential preparation must preserve the original consumed checkout before checkpoint");
            UUID namespace=jdbc.queryForObject("SELECT namespace FROM marketplace.media_namespace WHERE singleton",Map.of(),UUID.class);
            var pendingTokens=java.util.List.of(pendingToken(jdbc,"email_verification_token",emailToken),pendingToken(jdbc,"password_reset_token",resetToken));
            GUEST_PROOFS.put(namespace,new GuestProof(guest.browser,oldAccess,grant,consumed,history,guest.provider,guest.clock,pendingTokens));
            before=support.snapshot(jdbc,false);
            Path sql=support.root.resolve("infra/p12-d/Invalidate-RestoredCredentials.sql");
            assertThrows(java.sql.SQLException.class,()->{
                try(var connection=context.getBean(javax.sql.DataSource.class).getConnection();var statement=connection.createStatement()) {
                    statement.setQueryTimeout(3);
                    statement.execute("SELECT set_config('p12d.restore_database','different_target',false),set_config('p12d.restore_run','"+UUID.randomUUID()+"',false)");
                    statement.execute(Files.readString(sql));
                }
            });
            org.junit.jupiter.api.Assertions.assertTrue(before.equals(support.snapshot(jdbc,false)),"Rejected administrative identity must not invalidate any source credential or business row");
            Files.writeString(support.folder.resolve("restore-credential-guard-"+UUID.randomUUID()+".json"),support.json.writeValueAsString(Map.of("database",jdbc.queryForObject("SELECT current_database()",Map.of(),String.class),
                    "wrongIdentityRejected",true,"sourceAllRowsUnchanged",true,"sqlSha256",ReleaseContractSupport.sha(sql),"unconsumedEmail",1,"unconsumedPasswordReset",1)));
            return Map.of("pendingEmail",pending,"emailVerificationToken",emailToken,"passwordResetEmail",email,"passwordResetToken",resetToken);
        }
    }

    private static PendingToken pendingToken(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,String table,String token) {
        String hash=com.example.marketplace.identity.application.TokenSecrets.hash(token);
        var rows=jdbc.queryForList("SELECT * FROM marketplace."+table+" WHERE token_hash=:hash AND consumed_at IS NULL AND expires_at>clock_timestamp()",Map.of("hash",hash));
        assertEquals(1,rows.size(),"Exact source-issued token must be unused and unexpired: "+table);
        var row=rows.getFirst();var member=Map.of("id",row.get("user_id"));
        return new PendingToken(table,hash,row,jdbc.queryForMap("SELECT * FROM marketplace.identity_user WHERE id=:id",member),
                jdbc.queryForMap("SELECT * FROM marketplace.user_credential WHERE user_id=:id",member));
    }

    static void assertInvalidated(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        for(String table:java.util.List.of("spring_session","spring_session_attributes")) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace."+table,Map.of(),Integer.class),table);
        }
        var conditions=Map.of("guest_access_session","revoked_at IS NULL","email_verification_token","consumed_at IS NULL",
                "password_reset_token","consumed_at IS NULL","guest_checkout","state IN ('OPEN','VERIFIED')",
                "guest_recovery_attempt","state NOT IN ('CONSUMED','REJECTED') OR (state='REJECTED' AND grant_hash IS NOT NULL)");
        for(var entry:conditions.entrySet()) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace."+entry.getKey()+" WHERE "+entry.getValue(),Map.of(),Integer.class),entry.getKey());
        }
        UUID namespace=jdbc.queryForObject("SELECT namespace FROM marketplace.media_namespace WHERE singleton",Map.of(),UUID.class);
        var proof=GUEST_PROOFS.get(namespace);
        org.junit.jupiter.api.Assertions.assertNotNull(proof,"Actual pre-checkpoint guest credentials must exist for revocation assertions");
        assertPendingTokensPreserved(jdbc);
        var manager=new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getJdbcTemplate().getDataSource());
        var hashes=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var purchases=new com.example.marketplace.guest.infrastructure.JdbcGuestPurchases(jdbc,manager,proof.clock(),proof.provider());
        var access=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,manager,purchases,hashes,proof.clock(),true);
        var recovery=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,manager,hashes,proof.provider(),proof.clock());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(proof.access()));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.reset(proof.browser(),proof.grant(),new com.example.marketplace.guest.api.GuestPassword("rejected restore credential","rejected restore credential")));
        org.junit.jupiter.api.Assertions.assertTrue(proof.consumed().equals(jdbc.queryForMap("SELECT * FROM marketplace.guest_checkout WHERE id=:id",Map.of("id",proof.consumedCheckout()))),"Consumed checkout authentication history must be unchanged");
    }

    static void assertPendingTokensPreserved(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        UUID namespace=jdbc.queryForObject("SELECT namespace FROM marketplace.media_namespace WHERE singleton",Map.of(),UUID.class);
        var proof=GUEST_PROOFS.get(namespace);
        org.junit.jupiter.api.Assertions.assertNotNull(proof,"Original checkpoint token identities must exist");
        for(var token:proof.pendingTokens()) {
            var current=jdbc.queryForList("SELECT * FROM marketplace."+token.table()+" WHERE token_hash=:hash AND consumed_at IS NOT NULL AND expires_at>clock_timestamp()",Map.of("hash",token.hash()));
            assertEquals(1,current.size(),"Exact old token must be revoked while still unexpired: "+token.table());
            var original=new java.util.HashMap<>(token.row());var restored=new java.util.HashMap<>(current.getFirst());
            original.remove("consumed_at");restored.remove("consumed_at");
            org.junit.jupiter.api.Assertions.assertTrue(original.equals(restored),"Restore must preserve old token identity and expiry: "+token.table());
            var member=Map.of("id",token.row().get("user_id"));
            org.junit.jupiter.api.Assertions.assertTrue(token.account().equals(jdbc.queryForMap("SELECT * FROM marketplace.identity_user WHERE id=:id",member)),"Rejected old token must preserve original account: "+token.table());
            org.junit.jupiter.api.Assertions.assertTrue(token.credential().equals(jdbc.queryForMap("SELECT * FROM marketplace.user_credential WHERE user_id=:id",member)),"Rejected old token must preserve original password: "+token.table());
        }
    }
}
