package com.example.marketplace.api;

import com.example.marketplace.MarketplaceApplication;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("e1-probe")
@ExtendWith(OutputCaptureExtension.class)
@Import(RequestIdHttpTest.ProbeConfiguration.class)
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.location=classpath:/", "server.address=127.0.0.1",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.flyway.enabled=false", "spring.sql.init.mode=never"
        })
class RequestIdHttpTest {
    private static final AtomicReference<String> FAILED_ID = new AtomicReference<>();
    private static final AtomicReference<String> ERROR_CONTEXT = new AtomicReference<>();
    private static final AtomicReference<String> ASYNC_CONTEXT = new AtomicReference<>();
    @LocalServerPort
    private int port;

    private HttpResponse<String> request(String method, String path, String... ids) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody());
            for (String id : ids) {
                builder.header("X-Request-ID", id);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String id(HttpResponse<String> response) {
        var values = response.headers().allValues("X-Request-ID");
        assertEquals(1, values.size());
        String value = values.getFirst();
        assertEquals(value, UUID.fromString(value).toString());
        assertEquals(4, UUID.fromString(value).version());
        return value;
    }

    @Test
    void healthBodyIsUnchangedAndEveryRequestGetsANewServerId() throws Exception {
        var first = request("GET", "/actuator/health");
        var second = request("GET", "/actuator/health");
        assertEquals(200, first.statusCode());
        assertEquals("{\"status\":\"UP\"}", first.body());
        assertNotEquals(id(first), id(second));
    }

    @Test
    void externalValidMalformedAndDuplicateIdsAreNotTrusted() throws Exception {
        String external = UUID.randomUUID().toString();
        assertNotEquals(external, id(request("GET", "/actuator/health", external)));
        assertNotEquals("untrusted", id(request("GET", "/actuator/health", "untrusted")));
        assertNotEquals(external, id(request("GET", "/actuator/health", external, "second")));
    }

    @Test
    void servlet404And405HaveAnIdWithoutChangingErrorBodyContract() throws Exception {
        var missing = request("GET", "/api/e1-missing");
        var wrongMethod = request("POST", "/actuator/health");
        assertEquals(404, missing.statusCode());
        assertEquals(405, wrongMethod.statusCode());
        id(missing);
        id(wrongMethod);
        assertFalse(missing.body().contains("requestId"));
        assertTrue(wrongMethod.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    @Test
    void requestAttributeMdcHeaderAndLogHaveSameId(CapturedOutput output) throws Exception {
        var response = request("GET", "/__e1/probe");
        String id = id(response);
        assertEquals(200, response.statusCode());
        assertEquals(id + "|" + id, response.body());
        assertTrue(output.getOut().contains("[requestId=" + id + "]"));
    }

    @Test
    void realExceptionErrorDispatchKeepsOriginalRequestId() throws Exception {
        ERROR_CONTEXT.set(null);
        var response = request("GET", "/__e1/fail");
        assertEquals(500, response.statusCode());
        assertEquals(FAILED_ID.get(), id(response));
        assertEquals(id(response) + "|" + id(response), ERROR_CONTEXT.get());
        assertFalse(response.body().contains("intentional-e1"));
    }

    @Test
    void realAsyncRedispatchRebindsTheSameRequestId() throws Exception {
        ASYNC_CONTEXT.set(null);
        var response = request("GET", "/__e1/async");
        assertEquals(200, response.statusCode());
        assertEquals(id(response), response.body());
        assertEquals(id(response) + "|" + id(response), ASYNC_CONTEXT.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        @Bean
        FilterRegistrationBean<Filter> dispatchObserver() {
            Filter observer = (request, response, chain) -> {
                String value = request.getAttribute("com.example.marketplace.requestId") + "|" + MDC.get("requestId");
                if (request.getDispatcherType() == DispatcherType.ERROR) {
                    ERROR_CONTEXT.set(value);
                } else {
                    ASYNC_CONTEXT.set(value);
                }
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(observer);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            registration.setDispatcherTypes(DispatcherType.ERROR, DispatcherType.ASYNC);
            registration.setAsyncSupported(true);
            return registration;
        }
    }

    @RestController
    @Profile("e1-probe")
    static class ProbeController {
        @GetMapping("/__e1/probe")
        String probe(HttpServletRequest request) {
            LoggerFactory.getLogger(ProbeController.class).info("E1 test-only log probe");
            return request.getAttribute("com.example.marketplace.requestId") + "|" + MDC.get("requestId");
        }

        @GetMapping("/__e1/fail")
        String fail(HttpServletRequest request) {
            FAILED_ID.set((String) request.getAttribute("com.example.marketplace.requestId"));
            throw new IllegalStateException("intentional-e1");
        }

        @GetMapping("/__e1/async")
        Callable<String> async(HttpServletRequest request) {
            String id = (String) request.getAttribute("com.example.marketplace.requestId");
            return () -> id;
        }
    }
}
