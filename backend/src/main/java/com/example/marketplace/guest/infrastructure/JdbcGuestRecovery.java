package com.example.marketplace.guest.infrastructure;
import com.example.marketplace.guest.api.GuestPassword;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.guest.api.RecoverySecrets;
import com.example.marketplace.identity.api.PasswordHashes;
import com.example.marketplace.phone.api.PhoneVerifications;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
public final class JdbcGuestRecovery {
    public record Started(UUID attemptId,Instant expiresAt) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordHashes hashes;
    private final PhoneVerifications provider;
    private final Clock clock;
    public JdbcGuestRecovery(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,PasswordHashes hashes,PhoneVerifications provider,Clock clock) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.hashes=hashes;this.provider=provider;this.clock=clock;
    }
    /** Internal first-order contract, called in the order transaction, never a product HTTP endpoint. */
    public String initialCode(UUID id) {
        available();return tx.execute(status->{var buyer=buyer(id);if(buyer.get("recovery_code_hash")!=null){throw new GuestRejected(409);}return rotate(id);});
    }
    public String manage(String token,String current,GuestPassword replacement,String source) {
        available();outside();charge("manage:"+source,20,false);
        var candidate=tx.execute(status->session(token));
        charge("buyer:"+candidate.get("id"),5,false);
        if(current==null || current.length()>256 || !hashes.matches(current,(String)candidate.get("password_hash"))){throw new GuestRejected(401);}
        String next=replacement==null?null:hashes.encode(replacement.value());
        return tx.execute(status->{var locked=session(token);same(candidate,locked);
            if(next!=null){change((UUID)locked.get("id"),next);}return rotate((UUID)locked.get("id"));});
    }
    public Started start(String browser,String order,String phone,String code,String source) {
        available();outside();charge("source:"+source,20,false);
        if(browser==null || order==null || order.length()>64){throw new GuestRejected(401);}charge("order:"+order,5,false);
        String normalized;try{normalized=PhoneVerifications.normalize(phone);}catch(IllegalArgumentException e){throw new GuestRejected(401);}
        var rows=jdbc.queryForList("SELECT b.* FROM marketplace.guest_buyer b JOIN marketplace.guest_order_reference o ON o.guest_buyer_id=b.id WHERE o.order_number=:o",Map.of("o",order));
        if(rows.isEmpty() || !normalized.equals(rows.getFirst().get("verified_phone")) || !RecoverySecrets.matches(code,(String)rows.getFirst().get("recovery_code_hash"))){throw new GuestRejected(401);}
        var candidate=rows.getFirst();charge("phone:"+normalized,3,true);UUID id=UUID.randomUUID();
        tx.executeWithoutResult(status->{var locked=buyer((UUID)candidate.get("id"));same(candidate,locked);
            jdbc.update("INSERT INTO marketplace.guest_recovery_attempt(id,buyer_id,browser_hash,phone,password_version,recovery_hash,issued_at,expires_at,state) VALUES(:id,:b,:s,:p,:v,:h,:now,:exp,'ISSUING')",Map.of("id",id,"b",locked.get("id"),"s",RecoverySecrets.hash(browser),"p",normalized,"v",locked.get("password_version"),"h",locked.get("recovery_code_hash"),"now",stamp(clock.instant()),"exp",stamp(clock.instant().plusSeconds(180))));});
        PhoneVerifications.Request request;
        try{request=provider.issue(normalized);}catch(RuntimeException e){reject(id);throw new GuestRejected(409);}
        return tx.execute(status->{var row=attempt(browser,id,"ISSUING");jdbc.update("UPDATE marketplace.guest_recovery_attempt SET provider_id=:p,state='SENT' WHERE id=:id",Map.of("p",request.id(),"id",id));return new Started(id,time(row,"expires_at"));});
    }
    public String exchange(String browser,UUID id,String code) {
        available();outside();if(code==null || !code.matches("[0-9]{6}")){throw new GuestRejected(400);}
        var row=tx.execute(status->{var current=attempt(browser,id,"SENT");if(((Number)current.get("failures")).intValue()>=5){throw new GuestRejected(429);}state(id,"EXCHANGING");return current;});
        String custom;
        try{custom=provider.exchange((String)row.get("provider_id"),code);}catch(RuntimeException e){
            tx.executeWithoutResult(status->jdbc.update("UPDATE marketplace.guest_recovery_attempt SET failures=LEAST(failures+1,5),state=CASE WHEN failures>=4 THEN 'REJECTED' ELSE 'SENT' END WHERE id=:id AND state='EXCHANGING'",Map.of("id",id)));throw new GuestRejected(409);
        }
        tx.executeWithoutResult(status->{attempt(browser,id,"EXCHANGING");state(id,"EXCHANGED");});return custom;
    }
    public String verify(String browser,UUID id,String token) {
        available();outside();if(token==null || token.length()>16384){throw new GuestRejected(400);}
        var row=tx.execute(status->{var current=attempt(browser,id,"EXCHANGED");state(id,"CONFIRMING");return current;});
        PhoneVerifications.Proof proof;
        try{proof=provider.confirm((String)row.get("provider_id"),token);}catch(RuntimeException e){reject(id);throw new GuestRejected(409);}
        if(proof==null || !row.get("phone").equals(proof.phone()) || proof.authenticatedAt()==null || proof.authenticatedAt().isBefore(time(row,"issued_at").truncatedTo(java.time.temporal.ChronoUnit.SECONDS)) || proof.authenticatedAt().isAfter(clock.instant())){reject(id);throw new GuestRejected(409);}
        return tx.execute(status->{attempt(browser,id,"CONFIRMING");String grant=RecoverySecrets.generate();
            jdbc.update("UPDATE marketplace.guest_recovery_attempt SET state='READY',grant_hash=:g,grant_expires_at=:exp WHERE id=:id",Map.of("id",id,"g",RecoverySecrets.hash(grant),"exp",stamp(clock.instant().plusSeconds(300))));return grant;});
    }
    public String reset(String browser,String grant,GuestPassword password) {
        available();outside();var candidate=tx.execute(status->grant(browser,grant));String hash=hashes.encode(password.value());
        return tx.execute(status->{var current=grant(browser,grant);if(!candidate.get("id").equals(current.get("id"))){throw new GuestRejected(401);}
            UUID buyer=(UUID)current.get("buyer_id");change(buyer,hash);String code=rotate(buyer);state((UUID)current.get("id"),"CONSUMED");return code;});
    }
    private Map<String,Object> grant(String browser,String value) {
        if(value==null || !value.matches("[A-Za-z0-9_-]{43}")){throw new GuestRejected(401);}
        var rows=jdbc.queryForList("SELECT id FROM marketplace.guest_recovery_attempt WHERE grant_hash=:g",Map.of("g",RecoverySecrets.hash(value)));
        if(rows.isEmpty()){throw new GuestRejected(401);}return attempt(browser,(UUID)rows.getFirst().get("id"),"READY");
    }
    private Map<String,Object> attempt(String browser,UUID id,String expected) {
        if(browser==null || id==null){throw new GuestRejected(401);}
        var rows=jdbc.queryForList("SELECT buyer_id FROM marketplace.guest_recovery_attempt WHERE id=:id",Map.of("id",id));if(rows.isEmpty()){throw new GuestRejected(401);}
        var b=buyer((UUID)rows.getFirst().get("buyer_id"));
        var row=jdbc.queryForMap("SELECT * FROM marketplace.guest_recovery_attempt WHERE id=:id FOR UPDATE",Map.of("id",id));
        String expiry="READY".equals(expected)?"grant_expires_at":"expires_at";
        if(!RecoverySecrets.hash(browser).equals(row.get("browser_hash")) || !"RECOVERY".equals(row.get("purpose")) || !expected.equals(row.get("state"))
                || !b.get("password_version").equals(row.get("password_version")) || !row.get("recovery_hash").equals(b.get("recovery_code_hash")) || !row.get("phone").equals(b.get("verified_phone"))
                || !clock.instant().isBefore(time(row,expiry))){throw new GuestRejected(401);}return row;
    }
    private Map<String,Object> session(String token) {
        if(token==null || token.length()!=43){throw new GuestRejected(401);}
        var rows=jdbc.queryForList("SELECT guest_buyer_id FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",RecoverySecrets.hash(token)));if(rows.isEmpty()){throw new GuestRejected(401);}
        var buyer=buyer((UUID)rows.getFirst().get("guest_buyer_id"));var s=jdbc.queryForMap("SELECT * FROM marketplace.guest_access_session WHERE token_hash=:t FOR UPDATE",Map.of("t",RecoverySecrets.hash(token)));
        if(s.get("revoked_at")!=null || !buyer.get("password_version").equals(s.get("password_version")) || !clock.instant().isBefore(time(s,"expires_at")) || !clock.instant().isBefore(time(s,"idle_expires_at"))){throw new GuestRejected(401);}return buyer;
    }
    private Map<String,Object> buyer(UUID id){return jdbc.queryForMap("SELECT * FROM marketplace.guest_buyer WHERE id=:b FOR UPDATE",Map.of("b",id));}
    private void same(Map<String,Object> first,Map<String,Object> second){
        if(!first.get("password_hash").equals(second.get("password_hash")) || !first.get("password_version").equals(second.get("password_version")) || !java.util.Objects.equals(first.get("recovery_code_hash"),second.get("recovery_code_hash")) || !first.get("verified_phone").equals(second.get("verified_phone"))){throw new GuestRejected(401);}
    }
    private String rotate(UUID id){String code=RecoverySecrets.generate();jdbc.update("UPDATE marketplace.guest_buyer SET recovery_code_hash=:h WHERE id=:b",Map.of("h",RecoverySecrets.hash(code),"b",id));return code;}
    private void change(UUID id,String hash){
        jdbc.update("UPDATE marketplace.guest_buyer SET password_hash=:h,password_version=password_version+1 WHERE id=:b",Map.of("h",hash,"b",id));
        jdbc.update("UPDATE marketplace.guest_access_session SET revoked_at=:now WHERE guest_buyer_id=:b AND revoked_at IS NULL",Map.of("now",stamp(clock.instant()),"b",id));
    }
    private void state(UUID id,String state){jdbc.update("UPDATE marketplace.guest_recovery_attempt SET state=:s WHERE id=:id",Map.of("s",state,"id",id));}
    private void reject(UUID id){tx.executeWithoutResult(status->state(id,"REJECTED"));}
    private void charge(String key,int max,boolean cooldown){tx.executeWithoutResult(status->{String hash=RecoverySecrets.hash(key);
        jdbc.update("INSERT INTO marketplace.guest_recovery_limit(key_hash,window_start,requests) VALUES(:k,:now,0) ON CONFLICT DO NOTHING",Map.of("k",hash,"now",stamp(clock.instant())));
        var row=jdbc.queryForMap("SELECT * FROM marketplace.guest_recovery_limit WHERE key_hash=:k FOR UPDATE",Map.of("k",hash));Instant now=clock.instant();boolean reset=!now.isBefore(time(row,"window_start").plusSeconds(900));
        if(!reset && (((Number)row.get("requests")).intValue()>=max || cooldown && row.get("last_sent")!=null && now.isBefore(time(row,"last_sent").plusSeconds(60)))){throw new GuestRejected(429);}
        jdbc.update("UPDATE marketplace.guest_recovery_limit SET requests=:n,window_start=:w,last_sent=:now WHERE key_hash=:k",Map.of("k",hash,"n",reset?1:((Number)row.get("requests")).intValue()+1,"w",reset?stamp(now):row.get("window_start"),"now",stamp(now)));});}
    private static Timestamp stamp(Instant value){return Timestamp.from(value);}
    private static Instant time(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toInstant();}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive()){throw new IllegalStateException("External work must be outside transaction");}}
    private void available(){if(provider==null){throw new GuestRejected(503);}}
}
