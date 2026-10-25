package com.example.marketplace.media.web;

import com.example.marketplace.media.api.MediaException;
import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.seller.api.StoreException;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages="com.example.marketplace.media.web")
public class MediaErrors {
    @ExceptionHandler({MediaException.class,CatalogException.class,AccessRejected.class,StoreException.class,AccountException.class,org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiErrorResponse> rejected(RuntimeException ex,HttpServletRequest request) {
        int status=ex instanceof MediaException m?m.status():ex instanceof CatalogException c?c.status():ex instanceof StoreException s?s.status():ex instanceof AccountException?409:ex instanceof AccessRejected?403:400;
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(ApiErrorResponse.forStatus(status,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
