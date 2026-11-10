package com.example.marketplace.payment.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Payments;
import com.example.marketplace.payment.infrastructure.RefundReconciliation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController @Profile("db")
public final class RefundOperationsController {
 private final RefundReconciliation refunds;public RefundOperationsController(RefundReconciliation refunds){this.refunds=refunds;}
 private Payments.Authority authority(Principal p,HttpSession s,HttpServletRequest r){return new Payments.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),(Long)r.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE),null);}
 private ResponseEntity<?> response(Object value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
 @GetMapping("/api/admin/system/refunds") public ResponseEntity<?> queue(Principal p,HttpSession s,HttpServletRequest r){return response(refunds.queue(authority(p,s,r)));}
 @GetMapping("/api/admin/system/refunds/{id}") public ResponseEntity<?> get(@PathVariable UUID id,Principal p,HttpSession s,HttpServletRequest r){return response(refunds.get(id,authority(p,s,r)));}
 @GetMapping("/api/admin/system/refunds/{id}/evidence") public ResponseEntity<?> evidence(@PathVariable UUID id,Principal p,HttpSession s,HttpServletRequest r){return response(refunds.evidence(id,authority(p,s,r)));}
 @PostMapping("/api/admin/system/refunds/{id}/{action}") public ResponseEntity<?> command(@PathVariable UUID id,@PathVariable String action,Principal p,HttpSession s,HttpServletRequest r,@RequestBody JsonNode body){
  if(!Set.of("query","reconcile").contains(action)){throw new OrderRejected(404);}boolean reconcile=action.equals("reconcile");var names=reconcile?Set.of("key","expectedVersion","reason","evidenceReference","transactionKey"):Set.of("key","expectedVersion","reason");
  if(!body.isObject()||body.size()!=names.size()||names.stream().anyMatch(k->!body.has(k))||!body.path("expectedVersion").isIntegralNumber()||!body.path("expectedVersion").canConvertToLong()||names.stream().filter(k->!k.equals("expectedVersion")).anyMatch(k->!body.path(k).isTextual())){throw new OrderRejected(400);}
  return ResponseEntity.accepted().header("Cache-Control","no-store").body(refunds.command(id,authority(p,s,r),new RefundReconciliation.Command(body.path("key").asText(),body.path("expectedVersion").asLong(),body.path("reason").asText(),reconcile?body.path("evidenceReference").asText():null,reconcile?body.path("transactionKey").asText():null),reconcile));
 }
 @GetMapping("/api/orders/{number}/refunds") public ResponseEntity<?> owned(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest r,@CookieValue(name="GUEST_ACCESS",required=false) String token){return response(refunds.owned(number,new OrderQueries.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),(Long)r.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token)));}
}
