package com.example.marketplace.identity.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PasswordRecoveryRateLimitTest {
    @Test void bothRecoveryMutationsUseTheBoundedAttemptWindow() throws Exception {
        for (String path : new String[] {"/api/auth/password/request", "/api/auth/password/reset"}) {
            var filter = new AttemptLimitFilter();
            for (int attempt = 0; attempt < 101; attempt++) {
                var request = new MockHttpServletRequest("POST", path);
                request.setRemoteAddr("192.0.2.1");
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(202));
                assertEquals(attempt < 100 ? 202 : 429, response.getStatus());
                if (attempt == 100) { assertEquals("60", response.getHeader("Retry-After")); }
            }
        }
    }
}
