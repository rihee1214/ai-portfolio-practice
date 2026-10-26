package com.example.marketplace.guest.web;
import com.example.marketplace.guest.api.GuestPassword;
import com.example.marketplace.guest.infrastructure.JdbcGuestRecovery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public class GuestRecoveryController {
    public record Manage(String currentPassword,String password,String confirmation) { @Override public String toString(){return "Manage[REDACTED]";} }
    public record Start(String orderNumber,String phone,String recoveryCode) { @Override public String toString(){return "Start[REDACTED]";} }
    public record Proof(String code,String proof) { @Override public String toString(){return "Proof[REDACTED]";} }
    public record Reset(String grant,String password,String confirmation) { @Override public String toString(){return "Reset[REDACTED]";} }
    private final JdbcGuestRecovery recovery;
    private final Environment env;
    public GuestRecoveryController(JdbcGuestRecovery recovery,Environment env){this.recovery=recovery;this.env=env;}
    @PostMapping("/api/guest/access/password") public ResponseEntity<?> change(@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody Manage input,HttpServletRequest request){
        return response(Map.of("status","PASSWORD_CHANGED","recoveryCode",recovery.manage(token,input.currentPassword(),new GuestPassword(input.password(),input.confirmation()),request.getRemoteAddr())));
    }
    @PostMapping("/api/guest/access/recovery-code") public ResponseEntity<?> rotate(@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody Manage input,HttpServletRequest request){
        return response(Map.of("status","CODE_REPLACED","recoveryCode",recovery.manage(token,input.currentPassword(),null,request.getRemoteAddr())));
    }
    @PostMapping("/api/guest/recovery/start") public ResponseEntity<?> start(HttpSession session,@RequestBody Start input,HttpServletRequest request){
        return response(recovery.start(session.getId(),input.orderNumber(),input.phone(),input.recoveryCode(),request.getRemoteAddr()));
    }
    @PostMapping("/api/guest/recovery/{id}/exchange") public ResponseEntity<?> exchange(HttpSession session,@PathVariable UUID id,@RequestBody Proof input){
        return response(Map.of("customToken",recovery.exchange(session.getId(),id,input.code()),"host",env.getRequiredProperty("FIREBASE_AUTH_EMULATOR_HOST"),"project",env.getRequiredProperty("GCLOUD_PROJECT")));
    }
    @PostMapping("/api/guest/recovery/{id}/verify") public ResponseEntity<?> verify(HttpSession session,@PathVariable UUID id,@RequestBody Proof input){
        return response(Map.of("grant",recovery.verify(session.getId(),id,input.proof())));
    }
    @PostMapping("/api/guest/recovery/reset") public ResponseEntity<?> reset(HttpSession session,@RequestBody Reset input){
        return response(Map.of("status","PASSWORD_RESET","recoveryCode",recovery.reset(session.getId(),input.grant(),new GuestPassword(input.password(),input.confirmation()))));
    }
    private ResponseEntity<?> response(Object body){return ResponseEntity.ok().header("Cache-Control","no-store").body(body);}
}
