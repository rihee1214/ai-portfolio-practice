package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.Claims;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestBody;

@RestController @Profile("db")
public final class ClaimController {
 private final Claims claims;public ClaimController(Claims claims) { this.claims=claims; }
 @GetMapping("/api/orders/{number}/cancellations")
 public ResponseEntity<?> get(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
  var authority=new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);return ResponseEntity.ok().header("Cache-Control","no-store").body(claims.get(number,authority));
 }
 @PostMapping("/api/orders/{number}/cancellations/{id}/withdraw")
 public ResponseEntity<?> withdraw(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=3||!body.has("key")||!body.get("key").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()||!body.has("reason")||!body.get("reason").isTextual()) { throw new OrderRejected(400); }var authority=new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);return ResponseEntity.ok().header("Cache-Control","no-store").body(claims.withdraw(number,authority,id,new Claims.WithdrawCommand(body.get("key").asText(),body.get("expectedRevision").asLong(),body.get("reason").asText())));
 }
 @PostMapping("/api/orders/{number}/cancellations/quote")
 public ResponseEntity<?> quote(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=3||!body.has("units")||!body.get("units").isArray()||!body.has("reason")||!body.get("reason").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()) { throw new OrderRejected(400); }
  var units=new ArrayList<OrderConfirmations.Unit>();try { for(var u:body.get("units")) { if(!u.isObject()||u.size()!=2||!u.has("itemId")||!u.get("itemId").isTextual()||!u.has("ordinal")||!u.get("ordinal").isIntegralNumber()||!u.get("ordinal").canConvertToInt()) { throw new OrderRejected(400); }units.add(new OrderConfirmations.Unit(UUID.fromString(u.get("itemId").asText()),u.get("ordinal").asInt())); } }catch(IllegalArgumentException bad) { throw new OrderRejected(400); }
  var authority=new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);
  return ResponseEntity.ok().header("Cache-Control","no-store").body(claims.quote(number,authority,new Claims.QuoteCommand(units,body.get("reason").asText(),body.get("expectedRevision").asLong())));
 }
 @PostMapping("/api/orders/{number}/cancellations")
 public ResponseEntity<?> cancel(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=5||!body.has("units")||!body.get("units").isArray()||!body.has("reason")||!body.get("reason").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()||!body.has("key")||!body.get("key").isTextual()||!body.has("quoteFingerprint")||!body.get("quoteFingerprint").isTextual()) { throw new OrderRejected(400); }
  var units=new ArrayList<OrderConfirmations.Unit>();try { for(var u:body.get("units")) { if(!u.isObject()||u.size()!=2||!u.has("itemId")||!u.get("itemId").isTextual()||!u.has("ordinal")||!u.get("ordinal").isIntegralNumber()||!u.get("ordinal").canConvertToInt()) { throw new OrderRejected(400); }units.add(new OrderConfirmations.Unit(UUID.fromString(u.get("itemId").asText()),u.get("ordinal").asInt())); } }catch(IllegalArgumentException bad) { throw new OrderRejected(400); }
  var authority=new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);
  return ResponseEntity.accepted().header("Cache-Control","no-store").body(claims.cancel(number,authority,new Claims.CancelCommand(body.get("key").asText(),units,body.get("reason").asText(),body.get("expectedRevision").asLong(),body.get("quoteFingerprint").asText())));
 }
}
