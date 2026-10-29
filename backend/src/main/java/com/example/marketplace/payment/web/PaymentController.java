package com.example.marketplace.payment.web;
import com.example.marketplace.payment.api.Payments;
import com.example.marketplace.payment.infrastructure.PaymentCoordinator;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.identity.api.PurchaseSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public class PaymentController {
 private final PaymentCoordinator payments;
 public PaymentController(PaymentCoordinator payments) { this.payments=payments; }
 private Payments.Authority authority(Principal p,HttpSession s,HttpServletRequest r,String token) { return new Payments.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),(Long)r.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token); }
 private ResponseEntity<?> response(Object body) { return ResponseEntity.ok().header("Cache-Control","no-store").body(body); }
 @GetMapping("/api/payments/{number}") public ResponseEntity<?> get(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) { return response(payments.get(number,authority(principal,session,request,token))); }
 @PostMapping("/api/payments/{number}/approve") public ResponseEntity<?> approve(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody Map<String,Object> body) { if(!body.isEmpty()) { throw new OrderRejected(400); }return response(payments.approve(number,authority(principal,session,request,token))); }
 @PostMapping("/api/payments/{number}/sandbox") public ResponseEntity<?> sandbox(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody Map<String,Object> body) { if(!body.isEmpty()) { throw new OrderRejected(400); }return response(payments.sandbox(number,authority(principal,session,request,token))); }
 @PostMapping("/api/payments/{number}/confirm") public ResponseEntity<?> confirm(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody Map<String,Object> body) { if(!body.keySet().equals(java.util.Set.of("paymentKey","orderId","amount"))||body.values().stream().anyMatch(v->!(v instanceof String))) { throw new OrderRejected(400); }return response(payments.confirm(number,authority(principal,session,request,token),new PaymentCoordinator.Confirmation((String)body.get("paymentKey"),(String)body.get("orderId"),(String)body.get("amount")))); }
 @GetMapping("/api/admin/system/payments") public ResponseEntity<?> review(Principal principal,HttpSession session,HttpServletRequest request) { return response(payments.reviewQueue(authority(principal,session,request,null))); }
 @PostMapping("/api/admin/system/payments/{number}/reconcile") public ResponseEntity<?> reconcile(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody Map<String,String> body) { if(!body.keySet().equals(java.util.Set.of("reason"))) { throw new OrderRejected(400); }return response(payments.reconcile(number,authority(principal,session,request,null),body.get("reason"))); }
}
