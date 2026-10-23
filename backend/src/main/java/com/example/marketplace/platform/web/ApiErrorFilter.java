package com.example.marketplace.platform.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Handles only servlet error dispatches for the original business API path. */
public final class ApiErrorFilter implements Filter {
    private final JsonMapper mapper;

    public ApiErrorFilter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var httpRequest = (HttpServletRequest) request;
        var httpResponse = (HttpServletResponse) response;
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String context = httpRequest.getContextPath();
        String path = original instanceof String uri && uri.startsWith(context) ? uri.substring(context.length()) : "";
        if ((!path.equals("/api") && !path.startsWith("/api/")) || response.isCommitted()) {
            chain.doFilter(request, response);
            return;
        }
        Object errorStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = errorStatus instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
        String id = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        httpResponse.setStatus(status);
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader("Cache-Control", "no-store");
        if (!httpRequest.getMethod().equals("HEAD")) {
            mapper.writeValue(httpResponse.getOutputStream(), ApiErrorResponse.forStatus(status, id));
        }
    }
}
