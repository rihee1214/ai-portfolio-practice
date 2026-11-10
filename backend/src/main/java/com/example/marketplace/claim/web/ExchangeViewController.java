package com.example.marketplace.claim.web;

import com.example.marketplace.claim.api.ExchangeViews;
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
public final class ExchangeViewController {
 private final ExchangeViews views;public ExchangeViewController(ExchangeViews views){this.views=views;}
 @GetMapping("/api/orders/{number}/exchanges/{id}/view") ResponseEntity<?> owner(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request,@CookieValue(name="GUEST_ACCESS",required=false) String token){return ok(views.owner(number,id,authority(principal,session,request,token)));}
 @GetMapping("/api/seller/exchanges/{id}/view") ResponseEntity<?> seller(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request){return ok(views.seller(id,authority(principal,session,request,null)));}
 @GetMapping("/api/admin/support/orders/{number}/exchanges/{id}/view") ResponseEntity<?> support(@PathVariable String number,@PathVariable UUID id,Principal principal,HttpSession session,HttpServletRequest request){return ok(views.support(number,id,authority(principal,session,request,null)));}
 @GetMapping("/api/admin/support/orders/{number}/exchanges") ResponseEntity<?> supportList(@PathVariable String number,Principal principal,HttpSession session,HttpServletRequest request,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor){return ok(views.support(number,authority(principal,session,request,null),size,cursor));}
 private static ResponseEntity<?> ok(Object body){return ResponseEntity.ok().header("Cache-Control","no-store").body(body);}
 private static OrderQueries.Authority authority(Principal principal,HttpSession session,HttpServletRequest request,String token){return new OrderQueries.Authority(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);}
}
