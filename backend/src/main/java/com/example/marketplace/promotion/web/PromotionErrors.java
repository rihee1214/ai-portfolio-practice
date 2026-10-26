package com.example.marketplace.promotion.web;

import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.promotion.api.PromotionRejected;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.seller.api.StoreException;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages="com.example.marketplace.promotion.web")
public class PromotionErrors {
    @ExceptionHandler({PromotionRejected.class,CatalogException.class,AccessRejected.class,StoreException.class,AccountException.class})
    public ResponseEntity<ApiErrorResponse> rejected(RuntimeException ex,HttpServletRequest request) {
        int status=ex instanceof PromotionRejected p?p.status():ex instanceof CatalogException c?c.status():ex instanceof StoreException s?s.status():ex instanceof AccountException?409:403;
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(ApiErrorResponse.forStatus(status,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
