package com.example.marketplace.order.web;

import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.promotion.api.PromotionRejected;
import com.example.marketplace.inventory.api.InventoryException;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.seller.api.StoreException;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages={"com.example.marketplace.order.web","com.example.marketplace.payment.web","com.example.marketplace.fulfillment.web"})
public class OrderErrors {
    @ExceptionHandler({OrderRejected.class,GuestRejected.class,CatalogException.class,PromotionRejected.class,InventoryException.class,AccessRejected.class,AccountException.class,StoreException.class})
    public ResponseEntity<?> rejected(RuntimeException failure,HttpServletRequest request) {
        int status=failure instanceof OrderRejected o?o.status():failure instanceof GuestRejected g?g.status():failure instanceof CatalogException c?c.status():failure instanceof PromotionRejected p?p.status():failure instanceof InventoryException i?i.status():failure instanceof StoreException s?s.status():failure instanceof AccessRejected?403:409;
        var error=ApiErrorResponse.forStatus(status,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE));
        if(failure instanceof OrderRejected order&&order.quote()!=null) {
            return ResponseEntity.status(status).header("Cache-Control","no-store").body(java.util.Map.of("code","QUOTE_CHANGED","message",error.message(),"requestId",error.requestId(),"quote",java.util.Map.of("fingerprint",order.quote().fingerprint(),"total",Long.toString(order.quote().total()),"evaluatedAt",order.quote().evaluatedAt())));
        }
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(error);
    }
}

