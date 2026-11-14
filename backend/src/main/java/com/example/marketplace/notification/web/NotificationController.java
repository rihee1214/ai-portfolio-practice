package com.example.marketplace.notification.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.notification.api.NotificationAccess;
import com.example.marketplace.notification.infrastructure.MemberInbox;
import com.example.marketplace.notification.infrastructure.NotificationRetries;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@Profile("db")
public class NotificationController {
    private final MemberInbox inbox;
    private final NotificationRetries retries;
    public NotificationController(MemberInbox inbox,NotificationRetries retries) { this.inbox=inbox;this.retries=retries; }
    private NotificationAccess.Reader reader(HttpServletRequest request) {
        var principal=request.getUserPrincipal();
        var session=request.getSession(false);
        return new NotificationAccess.Reader(principal==null?null:UUID.fromString(principal.getName()),
                session==null?null:session.getId(),(Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE));
    }
    @GetMapping("/api/account/notifications")
    public ResponseEntity<MemberInbox.Page> list(HttpServletRequest request,@RequestParam(required=false) UUID before) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inbox.list(reader(request),before));
    }
    @PostMapping("/api/account/notifications/{id}/read")
    public ResponseEntity<MemberInbox.Entry> read(@PathVariable UUID id,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inbox.read(id,reader(request)));
    }
    @GetMapping("/api/admin/system/notifications/failed")
    public ResponseEntity<java.util.List<NotificationRetries.Failure>> failed(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(retries.failed(reader(request)));
    }
    @PostMapping("/api/admin/system/notifications/{id}/retry")
    public ResponseEntity<java.util.Map<String,UUID>> retry(@PathVariable UUID id,@RequestBody NotificationRetries.Command cmd,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("job",retries.retry(id,cmd,reader(request))));
    }
}
