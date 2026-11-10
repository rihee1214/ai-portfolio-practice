package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.Returns;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public final class ReturnController {
 private final Returns returns;public ReturnController(Returns returns) { this.returns=returns; }
 @PostMapping("/api/orders/{number}/returns")
 public ResponseEntity<?> request(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,
                                 @CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=4||!body.has("key")||!body.get("key").isTextual()||!body.has("reason")||!body.get("reason").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()||!body.has("units")||!body.get("units").isArray()) { throw new OrderRejected(400); }
  var units=new ArrayList<Returns.Unit>();try {
   for(var u:body.get("units")) { if(!u.isObject()||u.size()!=3||!u.has("itemId")||!u.get("itemId").isTextual()||!u.has("ordinal")||!u.get("ordinal").isIntegralNumber()||!u.get("ordinal").canConvertToInt()||!u.has("responsibility")||!u.get("responsibility").isTextual()) { throw new OrderRejected(400); }units.add(new Returns.Unit(UUID.fromString(u.get("itemId").asText()),u.get("ordinal").asInt(),Returns.Responsibility.valueOf(u.get("responsibility").asText()))); }
  }catch(IllegalArgumentException bad) { throw new OrderRejected(400); }
  var authority=new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);
  return ResponseEntity.accepted().header("Cache-Control","no-store").body(returns.request(number,authority,new Returns.Request(body.get("key").asText(),body.get("expectedRevision").asLong(),units,body.get("reason").asText())));
 }
}
