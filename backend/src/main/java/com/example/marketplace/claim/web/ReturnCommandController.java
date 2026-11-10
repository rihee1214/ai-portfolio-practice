package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.Returns;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public final class ReturnCommandController {
 private final Returns returns;public ReturnCommandController(Returns returns) { this.returns=returns; }
 @GetMapping("/api/orders/{number}/returns/{id}")
 public ResponseEntity<?> get(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
  return response(returns.get(number,id,authority(principal,session,request,token)));
 }
 @PostMapping("/api/orders/{number}/returns/{id}/withdraw")
 public ResponseEntity<?> withdraw(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  return response(returns.withdraw(number,id,authority(principal,session,request,token),command(body)));
 }
 @PostMapping("/api/seller/returns/{id}/approve")
 public ResponseEntity<?> approve(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) {
  return response(returns.approve(id,authority(principal,session,request,null),command(body)));
 }
 @PostMapping("/api/seller/returns/{id}/reject")
 public ResponseEntity<?> reject(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) {
  return response(returns.reject(id,authority(principal,session,request,null),command(body)));
 }
 private static Returns.Command command(tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=3||!body.has("key")||!body.get("key").isTextual()||!body.has("reason")||!body.get("reason").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()) { throw new OrderRejected(400); }
  return new Returns.Command(body.get("key").asText(),body.get("expectedRevision").asLong(),body.get("reason").asText());
 }
 private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request,String token) {
  return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);
 }
 private static ResponseEntity<?> response(Returns.Progress result) { return ResponseEntity.ok().header("Cache-Control","no-store").body(result); }
}
