package com.example.marketplace.fulfillment.web;
import com.example.marketplace.fulfillment.api.Shipments;
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
public class ShipmentController {
 private final Shipments shipments;public ShipmentController(Shipments shipments) { this.shipments=shipments; }
 private OrderQueries.Authority authority(Principal p,HttpSession s,HttpServletRequest r,String token) { return new OrderQueries.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),(Long)r.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token); }
 private ResponseEntity<?> response(Object body) { return ResponseEntity.ok().header("Cache-Control","no-store").body(body); }
 @GetMapping("/api/seller/orders/{id}/shipments") public ResponseEntity<?> seller(@PathVariable UUID id,Principal p,HttpSession s,HttpServletRequest r) { return response(shipments.seller(id,authority(p,s,r,null))); }
 @GetMapping("/api/orders/{number}/shipments") public ResponseEntity<?> buyer(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest r,@CookieValue(name="GUEST_ACCESS",required=false) String token) { return response(shipments.buyer(number,authority(p,s,r,token))); }
 @PostMapping("/api/seller/shipments/{id}/ship") public ResponseEntity<?> ship(@PathVariable UUID id,Principal p,HttpSession s,HttpServletRequest r,@RequestBody tools.jackson.databind.JsonNode body) {
  return response(shipments.ship(id,authority(p,s,r,null),command(body)));
 }
 @PostMapping("/api/seller/shipments/{id}/split") public ResponseEntity<?> split(@PathVariable UUID id,Principal p,HttpSession s,HttpServletRequest r,@RequestBody tools.jackson.databind.JsonNode body) { return response(shipments.split(id,authority(p,s,r,null),command(body))); }
 @PostMapping("/api/seller/shipments/{id}/tracking") public ResponseEntity<?> tracking(@PathVariable UUID id,Principal p,HttpSession s,HttpServletRequest r,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=4||!body.has("key")||!body.get("key").isTextual()||!body.has("version")||!body.get("version").isIntegralNumber()||!body.get("version").canConvertToLong()||!body.has("tracking")||!body.get("tracking").isTextual()||!body.has("reason")||!body.get("reason").isTextual()) { throw new OrderRejected(400); }
  return response(shipments.tracking(id,authority(p,s,r,null),new Shipments.TrackingCommand(body.get("key").asText(),body.get("version").asLong(),body.get("tracking").asText(),body.get("reason").asText())));
 }
 private Shipments.Command command(tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=3||!body.has("key")||!body.get("key").isTextual()||!body.has("version")||!body.get("version").canConvertToLong()||!body.get("version").isIntegralNumber()||!body.has("items")||!body.get("items").isArray()) { throw new OrderRejected(400); }
  var items=new java.util.ArrayList<Shipments.Item>();try { for(var item:body.get("items")) { if(!item.isObject()||item.size()!=2||!item.has("orderItemId")||!item.get("orderItemId").isTextual()||!item.has("quantity")||!item.get("quantity").isIntegralNumber()||!item.get("quantity").canConvertToInt()) { throw new OrderRejected(400); }items.add(new Shipments.Item(UUID.fromString(item.get("orderItemId").asText()),item.get("quantity").asInt())); } }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  return new Shipments.Command(body.get("key").asText(),body.get("version").asLong(),items);
 }
}
