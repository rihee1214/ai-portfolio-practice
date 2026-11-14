package com.example.marketplace.operations.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.notification.api.NotificationAccess.Reader;
import com.example.marketplace.operations.infrastructure.OperationsNotices;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController @Profile("db")
public class OwnerDecisionController {
    private final OperationsNotices notices;
    public OwnerDecisionController(OperationsNotices notices) { this.notices=notices; }
    @GetMapping("/api/account/moderation/{id}")
    public ResponseEntity<OperationsNotices.Decision> detail(@PathVariable UUID id,HttpServletRequest request) {
        var principal=request.getUserPrincipal();
        var session=request.getSession(false);
        if(principal==null || session==null) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        var decision=notices.action(id,new Reader(UUID.fromString(principal.getName()),session.getId(),
                (Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE)));
        if(decision==null) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(decision);
    }
}
