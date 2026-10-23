package com.example.marketplace.api;

import com.example.marketplace.MarketplaceApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("e5-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.location=classpath:/", "server.address=127.0.0.1",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.flyway.enabled=false", "spring.sql.init.mode=never",
            "spring.jmx.enabled=true", "spring.jmx.unique-names=true",
            "management.endpoints.jmx.domain=com.example.marketplace.e5",
            "management.endpoints.web.exposure.include=*"
        })
class ActuatorPolicyHttpTest {
    @LocalServerPort
    private int port;
    @Autowired
    private MBeanServer mBeanServer;
    @Autowired
    private ApplicationContext context;

    private HttpResponse<String> request(String method, String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/info", "/actuator/env", "/actuator/configprops",
            "/actuator/beans", "/actuator/metrics", "/actuator/loggers", "/actuator/mappings",
            "/actuator/threaddump", "/actuator/health/diskSpace",
            "/actuator/health/liveness", "/actuator/health/readiness"})
    void managementDetailsStayUnavailableEvenWhenWebExposureIsWidened(String path) throws Exception {
        assertEquals(404, request("GET", path).statusCode());
    }

    @Test
    void healthRemainsStatusOnlyWithRequestId() throws Exception {
        var response = request("GET", "/actuator/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        assertTrue(response.headers().firstValue("X-Request-ID").isPresent());
        var head = request("HEAD", "/actuator/health");
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
    }

    @Test
    void healthRejectsWrites() throws Exception {
        var response = request("POST", "/actuator/health");
        assertEquals(405, response.statusCode());
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    @Test
    void noActuatorMBeansAreRegisteredEvenWhenSpringJmxIsEnabled() throws Exception {
        assertTrue(mBeanServer.queryNames(new ObjectName("com.example.marketplace.e5:*"), null).isEmpty());
    }

    @Test
    void heapDumpOperationIsNotCreated() {
        // Do not invoke a heap dump even if a future regression exposes it.
        assertFalse(context.containsBean("heapDumpWebEndpoint"));
    }
}
