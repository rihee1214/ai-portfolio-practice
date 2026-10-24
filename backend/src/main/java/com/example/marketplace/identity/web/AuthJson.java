package com.example.marketplace.identity.web;

import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import tools.jackson.databind.json.JsonMapper;

final class AuthJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private AuthJson() { }

    static void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }

    static void error(HttpServletRequest request, HttpServletResponse response, int status) throws IOException {
        write(response, status, ApiErrorResponse.forStatus(status,
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
