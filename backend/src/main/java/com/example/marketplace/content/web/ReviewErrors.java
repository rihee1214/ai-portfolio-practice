package com.example.marketplace.content.web;

import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.media.api.MediaException;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import com.example.marketplace.seller.api.StoreException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = ReviewEligibilityController.class)
public class ReviewErrors {
    @ExceptionHandler({OrderRejected.class, CatalogException.class, MediaException.class, StoreException.class, AccessRejected.class})
    public ResponseEntity<ApiErrorResponse> reject(RuntimeException failure, HttpServletRequest request) {
        int status = switch (failure) {
            case OrderRejected order -> order.status();
            case CatalogException product -> product.status();
            case MediaException image -> image.status();
            case StoreException store -> store.status();
            default -> 403;
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(ApiErrorResponse.forStatus(status, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
