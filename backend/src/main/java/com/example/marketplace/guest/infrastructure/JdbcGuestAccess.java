package com.example.marketplace.guest.infrastructure;

import com.example.marketplace.guest.api.GuestPassword;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.identity.api.PasswordHashes;
import com.example.marketplace.phone.api.PhoneVerifications;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcGuestAccess {
    public record Order(String orderNumber) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcGuestPurchases purchases;
    private final PasswordHashes hashes;
    private final Clock clock;
    private final String dummyHash;
    private final boolean enabled;
    public JdbcGuestAccess(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, JdbcGuestPurchases purchases, PasswordHashes hashes, Clock clock, boolean enabled) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.purchases=purchases; this.hashes=hashes; this.clock=clock;
        this.dummyHash=hashes.encode("non-account-dummy-password");
        this.enabled=enabled;
    }
    public void prepare(String browser, UUID attempt, GuestPassword password) {
        available();
        outsideTransaction();
        purchases.verified(browser,purchases.credential(browser),attempt);
        String hash=hashes.encode(password.value());
        tx.executeWithoutResult(status->{
            purchases.verified(browser,purchases.credential(browser),attempt);
            jdbc.update("UPDATE marketplace.guest_attempt SET prepared_password_hash=:h WHERE id=:id",Map.of("h",hash,"id",attempt));
        });
    }
    /** Internal creation contract. The order caller must join this transaction; no public creation endpoint. */
    public UUID createBuyer(String browser, UUID attempt) {
        available();
        return tx.execute(status->{
            var verified=purchases.verified(browser,purchases.credential(browser),attempt);
            String hash=jdbc.queryForObject("SELECT prepared_password_hash FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",attempt),String.class);
            if(hash==null) { throw new GuestRejected(409); }
            purchases.consume(browser,purchases.credential(browser),attempt,verified.phone(),"PURCHASE");
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace.guest_buyer(id,buyer_name,verified_phone,password_hash,created_at) VALUES(:id,:n,:p,:h,:now)",
                    Map.of("id",id,"n",verified.name(),"p",verified.phone(),"h",hash,"now",Timestamp.from(clock.instant())));
            return id;
        });
    }
    public void registerOrder(UUID buyer, String order) {
        available();
        if(order==null || !order.matches("[A-Za-z0-9-]{1,64}")) { throw new IllegalArgumentException("Invalid order reference"); }
        jdbc.update("INSERT INTO marketplace.guest_order_reference(order_number,guest_buyer_id) VALUES(:o,:b)",Map.of("o",order,"b",buyer));
    }
    public String login(String order, String phone, String password, String source, String previous) {
        available();
        outsideTransaction();
        String reference=order==null?"":order;
        if(reference.length()>64 || password==null || password.length()>256 || phone==null || phone.length()>32) { throw new GuestRejected(401); }
        tx.executeWithoutResult(status->{ quota("order:"+reference,5); quota("source:"+source,20); });
        String normalized;
        try { normalized=PhoneVerifications.normalize(phone); } catch(IllegalArgumentException rejected) { normalized="invalid"; }
        var rows=jdbc.queryForList("SELECT b.* FROM marketplace.guest_buyer b JOIN marketplace.guest_order_reference o ON o.guest_buyer_id=b.id WHERE o.order_number=:o",Map.of("o",reference));
        var candidate=rows.isEmpty()?null:rows.getFirst();
        String hash=candidate==null?dummyHash:(String)candidate.get("password_hash");
        boolean match=hashes.matches(password,hash);
        if(candidate==null || !match || !normalized.equals(candidate.get("verified_phone"))) { throw new GuestRejected(401); }
        return tx.execute(status->{
            jdbc.queryForList("SELECT id FROM marketplace.guest_buyer WHERE id=:b OR id IN (SELECT guest_buyer_id FROM marketplace.guest_access_session WHERE token_hash=:t) ORDER BY id FOR UPDATE",Map.of("b",candidate.get("id"),"t",previous==null?"":JdbcGuestPurchases.hash(previous)));
            var buyer=jdbc.queryForMap("SELECT * FROM marketplace.guest_buyer WHERE id=:id FOR UPDATE",Map.of("id",candidate.get("id")));
            if(!buyer.get("password_hash").equals(hash) || !buyer.get("password_version").equals(candidate.get("password_version"))
                    || !buyer.get("verified_phone").equals(candidate.get("verified_phone"))) { throw new GuestRejected(401); }
            Instant now=clock.instant(); byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
            String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if(previous!=null) { revoke(previous,now); }
            jdbc.update("INSERT INTO marketplace.guest_access_session(token_hash,guest_buyer_id,password_version,idle_expires_at,expires_at) VALUES(:t,:b,:v,:idle,:max)",
                    Map.of("t",JdbcGuestPurchases.hash(token),"b",buyer.get("id"),"v",buyer.get("password_version"),"idle",Timestamp.from(now.plusSeconds(1800)),"max",Timestamp.from(now.plusSeconds(43200))));
            return token;
        });
    }
    public List<Order> orders(String token) {
        available();
        return tx.execute(status->{ UUID buyer=authorize(token); return jdbc.query("SELECT order_number FROM marketplace.guest_order_reference WHERE guest_buyer_id=:b ORDER BY order_number",Map.of("b",buyer),(r,n)->new Order(r.getString(1))); });
    }
    public Order order(String token,String number) {
        available();
        return tx.execute(status->{
            UUID buyer=authorize(token);
            var rows=jdbc.query("SELECT order_number FROM marketplace.guest_order_reference WHERE guest_buyer_id=:b AND order_number=:o",Map.of("b",buyer,"o",number),(r,n)->new Order(r.getString(1)));
            if(rows.isEmpty()) { throw new GuestRejected(404); } return rows.getFirst();
        });
    }
    public void logout(String token) { if(token!=null) { tx.executeWithoutResult(status->revoke(token,clock.instant())); } }
    private void revoke(String token,Instant now) {
        jdbc.update("UPDATE marketplace.guest_access_session SET revoked_at=:now WHERE token_hash=:t AND revoked_at IS NULL",Map.of("now",Timestamp.from(now),"t",JdbcGuestPurchases.hash(token)));
    }
    private UUID authorize(String token) {
        if(token==null || token.length()!=43) { throw new GuestRejected(401); }
        var sessions=jdbc.queryForList("SELECT * FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",JdbcGuestPurchases.hash(token)));
        if(sessions.isEmpty()) { throw new GuestRejected(401); }
        var session=sessions.getFirst();
        var buyer=jdbc.queryForMap("SELECT password_version FROM marketplace.guest_buyer WHERE id=:id FOR UPDATE",Map.of("id",session.get("guest_buyer_id")));
        session=jdbc.queryForMap("SELECT * FROM marketplace.guest_access_session WHERE token_hash=:t FOR UPDATE",Map.of("t",JdbcGuestPurchases.hash(token)));
        Instant now=clock.instant(), maximum=((Timestamp)session.get("expires_at")).toInstant();
        if(session.get("revoked_at")!=null || !buyer.get("password_version").equals(session.get("password_version"))
                || !now.isBefore(maximum) || !now.isBefore(((Timestamp)session.get("idle_expires_at")).toInstant())) { throw new GuestRejected(401); }
        Instant idle=now.plusSeconds(1800); if(idle.isAfter(maximum)) { idle=maximum; }
        jdbc.update("UPDATE marketplace.guest_access_session SET idle_expires_at=:idle WHERE token_hash=:t",Map.of("idle",Timestamp.from(idle),"t",JdbcGuestPurchases.hash(token)));
        return (UUID)session.get("guest_buyer_id");
    }
    private void quota(String key,int max) {
        String hash=JdbcGuestPurchases.hash(key);
        jdbc.update("INSERT INTO marketplace.guest_login_limit(key_hash,window_start,requests) VALUES(:k,:now,0) ON CONFLICT DO NOTHING",Map.of("k",hash,"now",Timestamp.from(clock.instant())));
        var row=jdbc.queryForMap("SELECT * FROM marketplace.guest_login_limit WHERE key_hash=:k FOR UPDATE",Map.of("k",hash));
        Instant now=clock.instant(); boolean reset=!now.isBefore(((Timestamp)row.get("window_start")).toInstant().plusSeconds(900));
        if(!reset && ((Number)row.get("requests")).intValue()>=max) { throw new GuestRejected(429); }
        jdbc.update("UPDATE marketplace.guest_login_limit SET requests=:n,window_start=:w WHERE key_hash=:k",Map.of("n",reset?1:((Number)row.get("requests")).intValue()+1,"w",reset?Timestamp.from(now):row.get("window_start"),"k",hash));
    }
    private static void outsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Password hashing must be outside a transaction"); }
    }
    private void available() { if(!enabled) { throw new GuestRejected(503); } }
}
