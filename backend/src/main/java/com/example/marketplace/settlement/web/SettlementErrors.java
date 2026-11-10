package com.example.marketplace.settlement.web;

import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.settlement.api.Settlements;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages="com.example.marketplace.settlement.web")
public class SettlementErrors {
    public record UnpaidConflict(String code,String message,String requestId,java.util.List<java.time.LocalDate> conflictingWeeks) { }
    @ExceptionHandler(Settlements.AssignmentConflict.class)
    public ResponseEntity<UnpaidConflict> unpaid(Settlements.AssignmentConflict failure,HttpServletRequest request) {
        return ResponseEntity.status(409).header("Cache-Control","no-store").body(new UnpaidConflict("SETTLEMENT_UNPAID_BATCH_CONFLICT",
                "먼저 충돌 주간의 미지급 정산 초안을 다시 작성해 주세요.",(String)request.getAttribute(RequestIdFilter.ATTRIBUTE),failure.conflictingWeeks()));
    }
    @ExceptionHandler(OrderRejected.class)
    public ResponseEntity<?> rejected(OrderRejected failure,HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).header("Cache-Control","no-store")
                .body(ApiErrorResponse.forStatus(failure.status(),(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
