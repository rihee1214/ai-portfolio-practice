package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.ExchangeRefunds;
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
public final class ExchangeRefundController {
 private final ExchangeRefunds refunds;public ExchangeRefundController(ExchangeRefunds refunds) { this.refunds=refunds; }
 @PostMapping("/api/orders/{number}/exchanges/{id}/refund-quote")
 public ResponseEntity<?> quote(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=1||!revision(body)) { throw new OrderRejected(400); }
  return ResponseEntity.ok().header("Cache-Control","no-store").body(refunds.quote(number,id,authority(principal,session,request,token),body.get("expectedRevision").asLong()));
 }
 @PostMapping("/api/orders/{number}/exchanges/{id}/refund-consent")
 public ResponseEntity<?> consent(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=7||!revision(body)||!text(body,"key")||!text(body,"quoteId")||!text(body,"quoteFingerprint")||!text(body,"reason")||!body.has("consent")||!body.get("consent").isBoolean()||!body.has("legacyConsent")||!body.get("legacyConsent").isBoolean()) { throw new OrderRejected(400); }
  UUID quote;try { quote=UUID.fromString(body.get("quoteId").asText()); }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  return ResponseEntity.accepted().header("Cache-Control","no-store").body(refunds.consent(number,id,authority(principal,session,request,token),new ExchangeRefunds.Consent(body.get("key").asText(),body.get("expectedRevision").asLong(),quote,body.get("quoteFingerprint").asText(),body.get("consent").asBoolean(),body.get("legacyConsent").asBoolean(),body.get("reason").asText())));
 }
 @org.springframework.web.bind.annotation.ExceptionHandler(com.example.marketplace.inventory.api.InventoryException.class)
 ResponseEntity<?> inventoryRejected(com.example.marketplace.inventory.api.InventoryException rejected) { return ResponseEntity.status(rejected.status()).header("Cache-Control","no-store").body(java.util.Map.of("code","EXCHANGE_RESERVATION_REJECTED")); }
 private static boolean revision(tools.jackson.databind.JsonNode b) { return b.has("expectedRevision")&&b.get("expectedRevision").isIntegralNumber()&&b.get("expectedRevision").canConvertToLong(); }
 private static boolean text(tools.jackson.databind.JsonNode b,String key) { return b.has(key)&&b.get(key).isTextual(); }
 private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request,String token) { return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token); }
}
