package com.example.marketplace.platform.web;

import com.example.marketplace.platform.api.DemoFeatureUnavailable;
import com.example.marketplace.platform.api.RuntimePolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Runs after the security chain and before controller input or domain commands. */
public final class PublicDemoBoundary implements HandlerInterceptor {
    public static final Set<String> ISSUANCE_CONTROLLERS = Set.of("SignupController", "PasswordRecoveryController",
            "GuestController", "GuestAccessController", "GuestRecoveryController");
    public static final Set<String> PAYMENT_PATHS = Set.of("/api/payments/{number}/sandbox",
            "/api/payments/{number}/confirm", "/api/payments/webhooks/toss");
    private final RuntimePolicy policy;
    public PublicDemoBoundary(RuntimePolicy policy) { this.policy = policy; }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!policy.restricted() || !(handler instanceof HandlerMethod method)) { return true; }
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (ISSUANCE_CONTROLLERS.contains(method.getBeanType().getSimpleName()) || PAYMENT_PATHS.contains(pattern)
                || (pattern != null && pattern.startsWith("/api/guest/"))
                || ("/api/orders".equals(pattern) && "POST".equals(request.getMethod()) && request.getUserPrincipal() == null)) {
            throw new DemoFeatureUnavailable();
        }
        request.getParameterMap().forEach((key, values) -> {
            if (modeField(key)) {
                for (String value : values) { if ("SANDBOX".equalsIgnoreCase(value)) { throw new DemoFeatureUnavailable(); } }
            }
        });
        return true;
    }
    static boolean modeField(String key) { return key.replace("_", "").replace("-", "").equalsIgnoreCase("mode")
            || key.replace("_", "").replace("-", "").equalsIgnoreCase("paymentMode"); }
}
