package com.example.marketplace.api;

import com.example.marketplace.MarketplaceApplication;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("e2-probe")
@Import(ApiErrorHttpTest.ProbeConfiguration.class)
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.location=classpath:/", "server.address=127.0.0.1",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.flyway.enabled=false", "spring.sql.init.mode=never"
        })
class ApiErrorHttpTest {
    @LocalServerPort
    private int port;

    private HttpResponse<String> request(String method, String path, String accept, String body, String type)
            throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", accept)
                    .header("X-Request-ID", "untrusted-e2").header("Content-Type", type)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        var json = JsonMapper.builder().build().readTree(response.body());
        assertEquals(3, json.size());
        assertEquals(code, json.get("code").asText());
        assertFalse(json.get("message").asText().isBlank());
        String id = json.get("requestId").asText();
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, response.headers().firstValue("X-Request-ID").orElseThrow());
        assertNotEquals("untrusted-e2", id);
        assertFalse(response.body().contains("private-e2"));
        assertFalse(response.body().contains("Exception"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "*/*", "application/xml"})
    void missingApiAlwaysUsesJson(String accept) throws Exception {
        assertError(request("GET", "/api/missing?secret=private-e2", accept, "", "application/json"), 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/"})
    void apiRootIsIncluded(String path) throws Exception {
        assertError(request("GET", path, "application/json", "", "application/json"), 404, "NOT_FOUND");
    }

    @Test
    void methodNotAllowedPreservesAllow() throws Exception {
        var response = request("POST", "/api/__e2/number", "application/json", "", "application/json");
        assertError(response, 405, "METHOD_NOT_ALLOWED");
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    @Test
    void invalidInputAndMalformedJsonAreSafe400() throws Exception {
        assertError(request("GET", "/api/__e2/number?value=private-e2", "application/json", "", "application/json"),
                400, "INVALID_REQUEST");
        assertError(request("POST", "/api/__e2/body", "application/json", "{private-e2", "application/json"),
                400, "INVALID_REQUEST");
    }

    @Test
    void unsupportedMediaTypeIs415() throws Exception {
        assertError(request("POST", "/api/__e2/body", "application/json", "private-e2", "text/plain"),
                415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/__e2/fail", "/api/__e2/async"})
    void unexpectedSyncAndAsyncFailuresAreSafe500(String path) throws Exception {
        assertError(request("GET", path, "application/json", "", "application/json"), 500, "INTERNAL_ERROR");
    }

    @ParameterizedTest
    @CsvSource({"401,AUTHENTICATION_REQUIRED", "403,FORBIDDEN", "409,CONFLICT", "429,RATE_LIMITED",
            "406,NOT_ACCEPTABLE", "418,REQUEST_FAILED", "503,INTERNAL_ERROR"})
    void sentStatusHasFixedPublicCode(int status, String code) throws Exception {
        var response = request("GET", "/api/__e2/status/" + status, "application/json", "", "application/json");
        assertError(response, status, code);
        if (status == 429) {
            assertEquals("30", response.headers().firstValue("Retry-After").orElseThrow());
        }
    }

    @Test
    void successfulApiIsNotWrapped() throws Exception {
        var response = request("GET", "/api/__e2/number?value=42", "application/json", "", "application/json");
        assertEquals(200, response.statusCode());
        assertEquals("42", response.body());
    }

    @Test
    void unacceptableSuccessRepresentationStillHasJsonError() throws Exception {
        assertError(request("GET", "/api/__e2/number?value=42", "application/xml", "", "application/json"),
                406, "NOT_ACCEPTABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/apiary", "/API/missing", "/actuator/env", "/error"})
    void nonApiRetainsDefaultErrorShape(String path) throws Exception {
        var response = request("GET", path, "application/json", "", "application/json");
        var json = JsonMapper.builder().build().readTree(response.body());
        assertTrue(json.has("status"));
        assertFalse(json.has("code"));
    }

    @Test
    void headHasNoResponseBody() throws Exception {
        var response = request("HEAD", "/api/missing", "application/json", "", "application/json");
        assertEquals(404, response.statusCode());
        assertEquals("", response.body());
        assertTrue(response.headers().firstValue("X-Request-ID").isPresent());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ProbeController apiErrorProbeController() {
            return new ProbeController();
        }
    }

    @RestController
    @Profile("e2-probe")
    static class ProbeController {
        @GetMapping("/api/__e2/number")
        int number(@RequestParam int value) {
            return value;
        }

        @PostMapping(value = "/api/__e2/body", consumes = "application/json")
        Map<String, Object> body(@RequestBody Map<String, Object> value) {
            return value;
        }

        @GetMapping("/api/__e2/fail")
        String fail() {
            throw new IllegalStateException("private-e2 SQL secret");
        }

        @GetMapping("/api/__e2/async")
        Callable<String> async() {
            return () -> { throw new IllegalStateException("private-e2 async secret"); };
        }

        @GetMapping("/api/__e2/status/{status}")
        void status(@PathVariable int status, HttpServletResponse response) throws IOException {
            if (status == 429) {
                response.setHeader("Retry-After", "30");
            }
            response.sendError(status, "private-e2 reason");
        }
    }
}
