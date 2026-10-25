package com.example.marketplace.guest.web;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice(basePackages = "com.example.marketplace.guest.web")
public class GuestErrors {
    @ExceptionHandler({GuestRejected.class, IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> rejected(RuntimeException failure, HttpServletRequest request) {
        int status = failure instanceof GuestRejected r ? r.status() : 400;
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(ApiErrorResponse.forStatus(status, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
