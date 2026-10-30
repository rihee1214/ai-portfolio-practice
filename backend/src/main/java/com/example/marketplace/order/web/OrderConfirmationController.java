package com.example.marketplace.order.web;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestBody;
@RestController @Profile("db")
public class OrderConfirmationController {
 private final OrderConfirmations api;public OrderConfirmationController(OrderConfirmations api) { this.api=api; }
 private OrderQueries.Authority authority(Principal p,HttpSession s,HttpServletRequest r,String token) { return new OrderQueries.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),(Long)r.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token); }
 @GetMapping("/api/orders/{number}/confirmations") public ResponseEntity<?> get(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest r,@CookieValue(name="GUEST_ACCESS",required=false) String token) { return ResponseEntity.ok().header("Cache-Control","no-store").body(api.get(number,authority(p,s,r,token))); }
 @PostMapping("/api/orders/{number}/confirm") public ResponseEntity<?> confirm(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest r,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=3||!body.has("key")||!body.get("key").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()||!body.has("units")||!body.get("units").isArray()) { throw new OrderRejected(400); }
  var units=new java.util.ArrayList<OrderConfirmations.Unit>();try { for(var u:body.get("units")) { if(!u.isObject()||u.size()!=2||!u.has("itemId")||!u.get("itemId").isTextual()||!u.has("ordinal")||!u.get("ordinal").isIntegralNumber()||!u.get("ordinal").canConvertToInt()) { throw new OrderRejected(400); }units.add(new OrderConfirmations.Unit(UUID.fromString(u.get("itemId").asText()),u.get("ordinal").asInt())); } }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  return ResponseEntity.ok().header("Cache-Control","no-store").body(api.confirm(number,authority(p,s,r,token),new OrderConfirmations.Command(body.get("key").asText(),body.get("expectedRevision").asLong(),units)));
 }
}
