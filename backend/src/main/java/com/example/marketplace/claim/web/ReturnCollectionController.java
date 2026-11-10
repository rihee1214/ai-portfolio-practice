package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.ReturnCollections;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public final class ReturnCollectionController {
 private final ReturnCollections collections;public ReturnCollectionController(ReturnCollections collections) { this.collections=collections; }
 @PostMapping("/api/seller/returns/{id}/receive")
 public ResponseEntity<?> receive(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@RequestBody tools.jackson.databind.JsonNode body) {
  if(!body.isObject()||body.size()!=4||!body.has("key")||!body.get("key").isTextual()||!body.has("reason")||!body.get("reason").isTextual()||!body.has("expectedRevision")||!body.get("expectedRevision").isIntegralNumber()||!body.get("expectedRevision").canConvertToLong()||!body.has("units")||!body.get("units").isArray()) { throw new OrderRejected(400); }
  var units=new ArrayList<ReturnCollections.Unit>();try {
   for(var u:body.get("units")) { if(!u.isObject()||u.size()!=3||!u.has("itemId")||!u.get("itemId").isTextual()||!u.has("ordinal")||!u.get("ordinal").isIntegralNumber()||!u.get("ordinal").canConvertToInt()||!u.has("status")||!u.get("status").isTextual()) { throw new OrderRejected(400); }units.add(new ReturnCollections.Unit(UUID.fromString(u.get("itemId").asText()),u.get("ordinal").asInt(),ReturnCollections.Status.valueOf(u.get("status").asText()))); }
  }catch(IllegalArgumentException bad) { throw new OrderRejected(400); }
  var authority=new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),null);
  return ResponseEntity.ok().header("Cache-Control","no-store").body(collections.receive(id,authority,new ReturnCollections.Command(body.get("key").asText(),body.get("expectedRevision").asLong(),units,body.get("reason").asText())));
 }
}
