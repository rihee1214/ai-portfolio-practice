package com.example.marketplace.api;

import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearTestContext() {
        MDC.clear();
    }

    @Test
    void sequentialRequestsDoNotLeakAndOtherMdcKeysRemain() throws Exception {
        MDC.put("other", "keep");
        var first = new MockHttpServletResponse();
        var second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), first, (req, res) ->
                assertEquals(first.getHeader(RequestIdFilter.HEADER), MDC.get(RequestIdFilter.MDC_KEY)));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) ->
                assertEquals(second.getHeader(RequestIdFilter.HEADER), MDC.get(RequestIdFilter.MDC_KEY)));
        assertNotEquals(first.getHeader(RequestIdFilter.HEADER), second.getHeader(RequestIdFilter.HEADER));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        assertEquals("keep", MDC.get("other"));
    }

    @Test
    void exceptionalExitRestoresOuterContext() {
        MDC.put(RequestIdFilter.MDC_KEY, "outer");
        assertThrows(ServletException.class, () -> filter.doFilter(new MockHttpServletRequest(),
                new MockHttpServletResponse(), (req, res) -> {
                    assertNotEquals("outer", MDC.get(RequestIdFilter.MDC_KEY));
                    throw new ServletException("test failure");
                }));
        assertEquals("outer", MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void exceptionalExitWithoutOuterContextRemovesId() {
        assertThrows(ServletException.class, () -> filter.doFilter(new MockHttpServletRequest(),
                new MockHttpServletResponse(), (req, res) -> { throw new ServletException("test failure"); }));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"ERROR", "ASYNC"})
    void redispatchKeepsAttributeAndRebindsMdc(DispatcherType type) throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        String original = response.getHeader(RequestIdFilter.HEADER);
        response.reset();
        request.setDispatcherType(type);
        filter.doFilter(request, response, (req, res) -> {
            assertEquals(original, req.getAttribute(RequestIdFilter.ATTRIBUTE));
            assertEquals(original, MDC.get(RequestIdFilter.MDC_KEY));
        });
        assertEquals(original, response.getHeader(RequestIdFilter.HEADER));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
