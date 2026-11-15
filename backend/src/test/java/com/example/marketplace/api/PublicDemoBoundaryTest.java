package com.example.marketplace.api;

import com.example.marketplace.guest.infrastructure.JdbcGuestAccess;
import com.example.marketplace.guest.infrastructure.JdbcGuestPurchases;
import com.example.marketplace.guest.infrastructure.JdbcGuestRecovery;
import com.example.marketplace.guest.web.GuestAccessController;
import com.example.marketplace.guest.web.GuestController;
import com.example.marketplace.guest.web.GuestRecoveryController;
import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.web.PasswordRecoveryController;
import com.example.marketplace.identity.web.SignupController;
import com.example.marketplace.payment.infrastructure.PaymentCoordinator;
import com.example.marketplace.payment.web.PaymentController;
import com.example.marketplace.payment.web.PaymentWebhookController;
import com.example.marketplace.platform.api.RuntimePolicy;
import com.example.marketplace.platform.web.PublicDemoBodyAdvice;
import com.example.marketplace.platform.web.PublicDemoBoundary;
import com.example.marketplace.platform.web.PublicDemoErrors;
import com.example.marketplace.platform.web.RuntimePolicyController;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicDemoBoundaryTest {
    private static final Set<String> RESTRICTED = Set.of("/api/auth/signup", "/api/auth/verify-email",
            "/api/auth/password/request", "/api/auth/password/reset", "/api/guest/checkout", "/api/guest/verifications",
            "/api/guest/verifications/{id}/exchange", "/api/guest/verifications/{id}/verify",
            "/api/guest/checkout/password", "/api/guest/checkout/link", "/api/guest/checkout/link/{attempt}",
            "/api/guest/access/login", "/api/guest/access/logout", "/api/guest/access/references",
            "/api/guest/access/references/{number}", "/api/guest/access/password", "/api/guest/access/recovery-code",
            "/api/guest/recovery/start", "/api/guest/recovery/{id}/exchange", "/api/guest/recovery/{id}/verify",
            "/api/guest/recovery/reset", "/api/guest/orders", "/api/payments/{number}/sandbox",
            "/api/payments/{number}/confirm", "/api/payments/webhooks/toss");

    private MockMvc mvc(RuntimePolicy policy, Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .addInterceptors(new PublicDemoBoundary(policy))
                .setControllerAdvice(new PublicDemoErrors(), new PublicDemoBodyAdvice(policy, JsonMapper.builder().build()))
                .build();
    }

    @Test void actualIssuanceAndSandboxMappingsReturnStable503BeforeAnyDelegate() throws Exception {
        var signup = mock(SignupOperations.class); var recovery = mock(PasswordRecoveryOperations.class);
        var purchases = mock(JdbcGuestPurchases.class); var access = mock(JdbcGuestAccess.class);
        var guestRecovery = mock(JdbcGuestRecovery.class); var payments = mock(PaymentCoordinator.class);
        var orderQueries = mock(com.example.marketplace.order.api.OrderQueries.class);
        Object[] controllers = {new SignupController(signup), new PasswordRecoveryController(recovery),
                new GuestController(purchases, new MockEnvironment()), new GuestAccessController(access, true),
                new GuestRecoveryController(guestRecovery, new MockEnvironment()), new PaymentController(payments),
                new PaymentWebhookController(payments), new com.example.marketplace.order.web.OrderQueryController(orderQueries)};
        var mvc = mvc(new RuntimePolicy("public-demo"), controllers);
        var actual = new java.util.HashSet<String>();
        for (Object controller : controllers) {
            for (var method : controller.getClass().getDeclaredMethods()) {
                var mapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) { continue; }
                for (String path : mapping.path()) {
                    if (!PublicDemoBoundary.ISSUANCE_CONTROLLERS.contains(controller.getClass().getSimpleName())
                            && !path.startsWith("/api/guest/") && !PublicDemoBoundary.PAYMENT_PATHS.contains(path)) { continue; }
                    actual.add(path);
                    String uri = path.replaceAll("\\{[^}]+\\}", "11111111-1111-4111-8111-111111111111");
                    for (var verb : mapping.method()) {
                        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(verb.name()), uri)
                                .contentType("application/json").content("{}");
                        mvc.perform(request).andExpect(status().isServiceUnavailable())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andExpect(jsonPath("$.code").value("DEMO_FEATURE_UNAVAILABLE"));
                    }
                }
            }
        }
        assertEquals(RESTRICTED, actual, "Register every real controller mapping, including new guest issuance paths");
        verifyNoInteractions(signup, recovery, purchases, access, guestRecovery, payments, orderQueries);
    }

    @RestController static class ProbeController {
        record Input(String value) { }
        @PostMapping("/api/mode-probe") Object post(@RequestBody Input input) { return input; }
    }

    @Test void rawBodyAndQueryCannotSelectSandboxEvenThroughUnknownOrNestedProperties() throws Exception {
        var probe = spy(new ProbeController()); var mvc = mvc(new RuntimePolicy("public-demo"), probe);
        for (String body : Set.of("{\"value\":\"ok\",\"mode\":\"SANDBOX\"}",
                "{\"value\":\"ok\",\"extra\":[{\"paymentMode\":\"SANDBOX\"}]}",
                "{\"value\":\"ok\",\"payment_mode\":\"sandbox\"}",
                "{\"value\":\"ok\",\"guestAttemptId\":\"11111111-1111-4111-8111-111111111111\"}")) {
            mvc.perform(post("/api/mode-probe").contentType("application/json").content(body))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEMO_FEATURE_UNAVAILABLE"));
        }
        mvc.perform(post("/api/mode-probe?mode=DEMO&mode=SANDBOX").contentType("application/json").content("{\"value\":\"ok\"}"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(probe);
    }

    @Test void capabilityIsReadOnlyAndLocalRequestKeepsExistingBehavior() throws Exception {
        for (String mode : Set.of("local-full", "public-demo")) {
            var policy = new RuntimePolicy(mode); var mvc = mvc(policy, new RuntimePolicyController(policy), new ProbeController());
            mvc.perform(get("/api/runtime-policy")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.mode").value(mode)).andExpect(jsonPath("$.restricted").value(policy.restricted()));
            mvc.perform(post("/api/runtime-policy")).andExpect(status().isMethodNotAllowed());
            mvc.perform(post("/api/mode-probe").contentType("application/json").content("{\"value\":\"DEMO\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("DEMO"));
        }
    }
}
