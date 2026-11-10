package com.example.marketplace.operations.web;

import com.example.marketplace.guest.api.GuestRejected;
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

@RestControllerAdvice(basePackageClasses=SupportController.class)
public class SupportErrors {
    @ExceptionHandler({OrderRejected.class,GuestRejected.class,StoreException.class,MediaException.class,AccessRejected.class})
    public ResponseEntity<ApiErrorResponse> rejected(RuntimeException error,HttpServletRequest request) {
        int status=switch(error) {
            case OrderRejected order -> order.status();
            case GuestRejected guest -> 404;
            case StoreException store -> store.status();
            case MediaException media -> media.status();
            default -> 403;
        };
        return ResponseEntity.status(status).header("Cache-Control","no-store")
                .body(ApiErrorResponse.forStatus(status,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
