package com.example.marketplace.cart.web;
import com.example.marketplace.cart.api.CartException;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice(basePackages="com.example.marketplace.cart.web")
public class CartErrors {
    @ExceptionHandler({CartException.class,AccessRejected.class,IllegalArgumentException.class,ArithmeticException.class})
    public ResponseEntity<ApiErrorResponse> rejected(RuntimeException e,HttpServletRequest request) {
        int status=e instanceof CartException c?c.status():e instanceof AccessRejected?403:400;
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(ApiErrorResponse.forStatus(status,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
