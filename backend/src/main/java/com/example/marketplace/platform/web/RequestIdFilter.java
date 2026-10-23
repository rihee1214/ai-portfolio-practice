package com.example.marketplace.platform.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;

/** Server-owned identity for this servlet request, including redispatches. */
public final class RequestIdFilter implements Filter {
    public static final String HEADER = "X-Request-ID";
    public static final String ATTRIBUTE = "com.example.marketplace.requestId";
    public static final String MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        // Do not read client headers: the public ingress has no trusted upstream yet.
        ((HttpServletResponse) response).setHeader(HEADER, id);
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
