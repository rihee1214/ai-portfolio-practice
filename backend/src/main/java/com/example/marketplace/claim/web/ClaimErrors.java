package com.example.marketplace.claim.web;

import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses=ClaimController.class)
public final class ClaimErrors {
 @ExceptionHandler({OrderRejected.class,GuestRejected.class})
 public ResponseEntity<?> rejected(RuntimeException failure,HttpServletRequest request) {
  int status=failure instanceof OrderRejected rejected?rejected.status():((GuestRejected)failure).status();
  return ResponseEntity.status(status).header("Cache-Control","no-store").body(ApiErrorResponse.forStatus(status,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
 }
}
