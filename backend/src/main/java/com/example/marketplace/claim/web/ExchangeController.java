package com.example.marketplace.claim.web;
import com.example.marketplace.claim.api.Exchanges;
import com.example.marketplace.claim.api.ExchangeDispatches;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import java.util.ArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public final class ExchangeController {
 private final Exchanges exchanges;private final ExchangeDispatches dispatches;private final com.example.marketplace.claim.api.ExchangeReservationCommands reservations;public ExchangeController(Exchanges exchanges,ExchangeDispatches dispatches,com.example.marketplace.claim.api.ExchangeReservationCommands reservations) { this.exchanges=exchanges;this.dispatches=dispatches;this.reservations=reservations; }
 @PostMapping("/api/seller/exchanges/{id}/extend")
 public ResponseEntity<?> extend(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) { if(!body.isObject()||body.size()!=3||!text(body,"key")||!text(body,"reason")||!revision(body)) { throw new OrderRejected(400); }return response(reservations.extend(id,authority(principal,session,request,null),new Exchanges.Command(body.get("key").asText(),body.get("expectedRevision").asLong(),body.get("reason").asText()))); }
 @PostMapping("/api/seller/exchanges/{id}/reship")
 public ResponseEntity<?> reship(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) { if(!body.isObject()||body.size()!=3||!text(body,"key")||!text(body,"reason")||!revision(body)) { throw new OrderRejected(400); }return ResponseEntity.accepted().header("Cache-Control","no-store").body(dispatches.request(id,authority(principal,session,request,null),new Exchanges.Command(body.get("key").asText(),body.get("expectedRevision").asLong(),body.get("reason").asText()))); }
 @PostMapping("/api/orders/{number}/exchanges")
 public ResponseEntity<?> request(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=4||!text(body,"key")||!text(body,"reason")||!revision(body)||!body.has("units")||!body.get("units").isArray()) { throw new OrderRejected(400); }
  var units=new ArrayList<Exchanges.Unit>();try { for(var u:body.get("units")) { if(!u.isObject()||u.size()!=4||!text(u,"itemId")||!text(u,"targetSkuId")||!text(u,"responsibility")||!u.has("ordinal")||!u.get("ordinal").isIntegralNumber()||!u.get("ordinal").canConvertToInt()) { throw new OrderRejected(400); }units.add(new Exchanges.Unit(UUID.fromString(u.get("itemId").asText()),u.get("ordinal").asInt(),UUID.fromString(u.get("targetSkuId").asText()),Exchanges.Responsibility.valueOf(u.get("responsibility").asText()))); } }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  return ResponseEntity.accepted().header("Cache-Control","no-store").body(exchanges.request(number,authority(principal,session,request,token),new Exchanges.Request(body.get("key").asText(),body.get("expectedRevision").asLong(),units,body.get("reason").asText())));
 }
 @PostMapping("/api/seller/exchanges/{id}/approve")
 public ResponseEntity<?> approve(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) { if(!body.isObject()||body.size()!=3||!text(body,"key")||!text(body,"reason")||!revision(body)) { throw new OrderRejected(400); }return response(exchanges.approve(id,authority(principal,session,request,null),new Exchanges.Command(body.get("key").asText(),body.get("expectedRevision").asLong(),body.get("reason").asText()))); }
 @GetMapping("/api/orders/{number}/exchanges/{id}")
 public ResponseEntity<?> get(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) { return response(exchanges.get(number,id,authority(principal,session,request,token))); }
 @GetMapping("/api/orders/{number}/exchanges")
 public ResponseEntity<?> owner(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) { return response(exchanges.owner(number,authority(principal,session,request,token))); }
 @GetMapping("/api/seller/orders/{sellerOrder}/exchanges")
 public ResponseEntity<?> seller(@PathVariable UUID sellerOrder,Principal principal,HttpSession session,HttpServletRequest request) { return response(exchanges.seller(sellerOrder,authority(principal,session,request,null))); }
 private static boolean text(tools.jackson.databind.JsonNode b,String key) { return b.has(key)&&b.get(key).isTextual(); }
 private static boolean revision(tools.jackson.databind.JsonNode b) { return b.has("expectedRevision")&&b.get("expectedRevision").isIntegralNumber()&&b.get("expectedRevision").canConvertToLong(); }
 private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request,String token) { return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token); }
 private static ResponseEntity<?> response(Object body) { return ResponseEntity.ok().header("Cache-Control","no-store").body(body); }
}
