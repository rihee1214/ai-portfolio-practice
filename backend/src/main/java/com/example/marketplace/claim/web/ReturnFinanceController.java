package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.ReturnFinances;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public final class ReturnFinanceController {
 private final ReturnFinances finances;public ReturnFinanceController(ReturnFinances finances) { this.finances=finances; }
 @PostMapping("/api/orders/{number}/returns/{id}/quote")
 public ResponseEntity<?> quote(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=1||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()) { throw new OrderRejected(400); }
  var authority=new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);
  return ResponseEntity.ok().header("Cache-Control","no-store").body(finances.quote(number,id,authority,body.get("expectedRevision").asLong()));
 }
 @PostMapping("/api/orders/{number}/returns/{id}/consent")
 public ResponseEntity<?> consent(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=6||!body.has("key")||!body.get("key").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()||!body.has("quoteId")||!body.get("quoteId").isTextual()||!body.has("quoteFingerprint")||!body.get("quoteFingerprint").isTextual()||!body.has("consent")||!body.get("consent").isBoolean()||!body.has("legacyConsent")||!body.get("legacyConsent").isBoolean()) { throw new OrderRejected(400); }
  UUID quote;try { quote=UUID.fromString(body.get("quoteId").asText()); }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  var authority=new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);
  return ResponseEntity.accepted().header("Cache-Control","no-store").body(finances.consent(number,id,authority,new ReturnFinances.Consent(body.get("key").asText(),body.get("expectedRevision").asLong(),quote,body.get("quoteFingerprint").asText(),body.get("consent").asBoolean(),body.get("legacyConsent").asBoolean())));
 }
}
