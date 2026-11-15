package com.example.marketplace.platform.web;

import com.example.marketplace.platform.api.DemoFeatureUnavailable;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicDemoErrors {
    @ExceptionHandler(DemoFeatureUnavailable.class)
    public ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(503).header("Cache-Control", "no-store").body(new ApiErrorResponse(
                "DEMO_FEATURE_UNAVAILABLE", "공개 데모에서 지원하지 않는 기능입니다.", (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
