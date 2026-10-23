package com.example.marketplace.api;

import com.example.marketplace.MarketplaceApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("d1")
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.location=classpath:/", "server.address=127.0.0.1",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.flyway.enabled=false", "spring.sql.init.mode=never"
        })
class ErrorHttpTest {
    @LocalServerPort
    private int port;

    private HttpResponse<String> request(String method, String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String error, String path) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        var json = JsonMapper.builder().build().readTree(response.body());
        if (path.startsWith("/api/")) {
            assertEquals("NOT_FOUND", json.get("code").asText());
            assertEquals("요청한 대상을 찾을 수 없습니다.", json.get("message").asText());
            assertEquals(response.headers().firstValue("X-Request-ID").orElseThrow(), json.get("requestId").asText());
            assertEquals(3, json.size());
            return;
        }
        assertEquals(status, json.get("status").asInt());
        assertEquals(error, json.get("error").asText());
        assertEquals(path, json.get("path").asText());
        assertFalse(json.get("timestamp").asText().isBlank());
        assertEquals(4, json.size());
        assertFalse(json.has("message"));
        assertFalse(json.has("trace"));
        assertFalse(json.has("exception"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/status", "/api/not-found", "/actuator", "/actuator/env", "/actuator/metrics"})
    void servlet404UsesJsonWithoutInternalDetails(String path) throws Exception {
        assertError(request("GET", path), 404, "Not Found", path);
    }

    @Test
    void servlet405UsesJsonWithoutInternalDetails() throws Exception {
        var response = request("POST", "/actuator/health");
        assertError(response, 405, "Method Not Allowed", "/actuator/health");
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    @Test
    void normalHealthOnRealServerUsesSameStatusOnlyContract() throws Exception {
        var response = request("GET", "/actuator/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
    }
}
