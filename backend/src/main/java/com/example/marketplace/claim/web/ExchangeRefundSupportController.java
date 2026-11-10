package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.ClaimSupport;
import com.example.marketplace.claim.api.ExchangeRefundSupport;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public final class ExchangeRefundSupportController {
 private final ExchangeRefundSupport support;public ExchangeRefundSupportController(ExchangeRefundSupport support) { this.support=support; }
 @org.springframework.web.bind.annotation.GetMapping("/api/admin/support/orders/{number}/exchanges/{claim}/refunds/SHIPPING")
 public ResponseEntity<?> shipping(@PathVariable String number,@PathVariable UUID claim,Principal principal,HttpSession session,HttpServletRequest request) { return ResponseEntity.ok().header("Cache-Control","no-store").body(support.shipping(number,authority(principal,session,request),claim)); }
 @PostMapping("/api/admin/support/orders/{number}/exchanges/{claim}/refunds/{purpose}/query")
 public ResponseEntity<?> query(@PathVariable String number,@PathVariable UUID claim,@PathVariable String purpose,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) {
  return ResponseEntity.accepted().header("Cache-Control","no-store").body(support.queryRefund(number,authority(principal,session,request),claim,purpose,command(body)));
 }
 @PostMapping("/api/admin/support/orders/{number}/exchanges/{claim}/refunds/{purpose}/retry")
 public ResponseEntity<?> retry(@PathVariable String number,@PathVariable UUID claim,@PathVariable String purpose,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) {
  return ResponseEntity.accepted().header("Cache-Control","no-store").body(support.retryFailed(number,authority(principal,session,request),claim,purpose,command(body)));
 }
 private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request) { return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),null); }
 private static ClaimSupport.Command command(tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=3||!body.has("key")||!body.get("key").isTextual()||!body.has("reason")||!body.get("reason").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()) { throw new OrderRejected(400); }return new ClaimSupport.Command(body.get("key").asText(),body.get("expectedRevision").asLong(),body.get("reason").asText());
 }
}
