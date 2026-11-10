package com.example.marketplace.claim.web;
import com.example.marketplace.claim.api.ExchangePhysical;
import com.example.marketplace.claim.api.Exchanges;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import java.util.ArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
@RestController @Profile("db")
public final class ExchangePhysicalController {
 private final ExchangePhysical physical;public ExchangePhysicalController(ExchangePhysical physical) { this.physical=physical; }
 @PostMapping("/api/seller/exchanges/{id}/receive")
 public ResponseEntity<?> receive(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) {
  command(body);var units=new ArrayList<ExchangePhysical.Received>();try { for(var u:body.get("units")) { if(!unit(u,3)||!text(u,"status")) { throw new OrderRejected(400); }units.add(new ExchangePhysical.Received(UUID.fromString(u.get("itemId").asText()),u.get("ordinal").asInt(),ExchangePhysical.Status.valueOf(u.get("status").asText()))); } }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  return response(physical.receive(id,authority(principal,session,request),new ExchangePhysical.CollectionCommand(body.get("key").asText(),body.get("expectedRevision").asLong(),units,body.get("reason").asText())));
 }
 @PostMapping("/api/seller/exchanges/{id}/inspect")
 public ResponseEntity<?> inspect(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) {
  command(body);var units=new ArrayList<ExchangePhysical.Inspected>();try { for(var u:body.get("units")) { if(!unit(u,4)||!text(u,"condition")||!text(u,"responsibility")) { throw new OrderRejected(400); }units.add(new ExchangePhysical.Inspected(UUID.fromString(u.get("itemId").asText()),u.get("ordinal").asInt(),ExchangePhysical.Condition.valueOf(u.get("condition").asText()),Exchanges.Responsibility.valueOf(u.get("responsibility").asText()))); } }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  return response(physical.inspect(id,authority(principal,session,request),new ExchangePhysical.InspectionCommand(body.get("key").asText(),body.get("expectedRevision").asLong(),units,body.get("reason").asText())));
 }
 private static boolean text(tools.jackson.databind.JsonNode body,String key) { return body.has(key)&&body.get(key).isTextual(); }
 private static boolean unit(tools.jackson.databind.JsonNode u,int size) { return u.isObject()&&u.size()==size&&text(u,"itemId")&&u.has("ordinal")&&u.get("ordinal").isIntegralNumber()&&u.get("ordinal").canConvertToInt(); }
 private static void command(tools.jackson.databind.JsonNode b) { if(!b.isObject()||b.size()!=4||!text(b,"key")||!text(b,"reason")||!b.has("expectedRevision")||!b.get("expectedRevision").isIntegralNumber()||!b.get("expectedRevision").canConvertToLong()||!b.has("units")||!b.get("units").isArray()) { throw new OrderRejected(400); } }
 private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request) { return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),null); }
 private static ResponseEntity<?> response(Exchanges.Progress body) { return ResponseEntity.ok().header("Cache-Control","no-store").body(body); }
}
