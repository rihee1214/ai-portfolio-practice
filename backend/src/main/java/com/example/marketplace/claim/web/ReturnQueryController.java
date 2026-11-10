package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.ReturnQueries;
import com.example.marketplace.order.api.OrderQueries;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public final class ReturnQueryController {
 private final ReturnQueries reads;public ReturnQueryController(ReturnQueries reads) { this.reads=reads; }
 @GetMapping("/api/orders/{number}/returns")
 public ResponseEntity<?> owner(@PathVariable String number,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) { return response(reads.owner(number,authority(principal,session,request,token),size,cursor)); }
 @GetMapping("/api/orders/{number}/returns/{id}/view")
 public ResponseEntity<?> owner(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token) { return response(reads.owner(number,id,authority(principal,session,request,token))); }
 @GetMapping("/api/seller/orders/{seller}/returns")
 public ResponseEntity<?> seller(@PathVariable UUID seller,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor,Principal principal,HttpSession session,HttpServletRequest request) { return response(reads.seller(seller,authority(principal,session,request,null),size,cursor)); }
 @GetMapping("/api/seller/orders/{seller}/returns/{id}")
 public ResponseEntity<?> seller(@PathVariable UUID seller,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request) { return response(reads.seller(seller,id,authority(principal,session,request,null))); }
 @GetMapping("/api/admin/support/orders/{number}/returns")
 public ResponseEntity<?> support(@PathVariable String number,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor,Principal principal,HttpSession session,HttpServletRequest request) { return response(reads.support(number,authority(principal,session,request,null),size,cursor)); }
 @GetMapping("/api/admin/support/orders/{number}/returns/{id}")
 public ResponseEntity<?> support(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request) { return response(reads.support(number,id,authority(principal,session,request,null))); }
 private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request,String token) { return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token); }
 private static ResponseEntity<?> response(Object body) { return ResponseEntity.ok().header("Cache-Control","no-store").body(body); }
}
