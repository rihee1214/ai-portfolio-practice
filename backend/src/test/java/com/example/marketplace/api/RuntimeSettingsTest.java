package com.example.marketplace.api;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.platform.config.RuntimeSettingsConfiguration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(OutputCaptureExtension.class)
class RuntimeSettingsTest {
    private org.springframework.mock.env.MockEnvironment publicEnvironment(boolean worker) {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("marketplace.runtime.mode", "public-demo")
                .withProperty("marketplace.runtime.access", "invited-only")
                .withProperty("marketplace.runtime.public-origin", "https://demo.example.test")
                .withProperty("marketplace.runtime.internal-api", "true")
                .withProperty("marketplace.media.enabled", Boolean.toString(!worker))
                .withProperty("marketplace.worker.enabled", Boolean.toString(worker));
        env.setActiveProfiles(worker ? new String[] {"db", "worker"} : new String[] {"db"});
        if (worker) { env.setProperty("spring.main.web-application-type", "none"); }
        return env;
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            context.getEnvironment().setConversionService(new org.springframework.boot.convert.ApplicationConversionService());
            context.getEnvironment().getPropertySources().remove("systemEnvironment");
            context.getEnvironment().getPropertySources().remove("systemProperties");
        }).withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/")
                .withUserConfiguration(MarketplaceApplication.class);
    }

    @Test
    void productionAlwaysFailsBeforeAnyApplicationSingleton() {
        var created = new AtomicBoolean();
        runner().withPropertyValues("marketplace.runtime.mode=production")
                .withBean("creationProbe", Object.class, () -> { created.set(true); return new Object(); })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("RUNTIME_CONFIGURATION_REJECTED");
                    assertThat(created).isFalse();
                });
    }

    @Test void acceptedModeRoleMatrixKeepsLocalSandboxAndPublicDemoDistinct() {
        for (boolean worker : List.of(false, true)) {
            var local = publicEnvironment(worker).withProperty("marketplace.runtime.mode", "local-full")
                    .withProperty("marketplace.runtime.access", "")
                    .withProperty("marketplace.runtime.public-origin", "")
                    .withProperty("marketplace.runtime.internal-api", "false")
                    .withProperty("marketplace.toss.client-key", "test_ck_synthetic")
                    .withProperty("marketplace.toss.secret-key", "test_sk_synthetic")
                    .withProperty("GCLOUD_PROJECT", "demo-synthetic")
                    .withProperty("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9999")
                    .withProperty("marketplace.phone.mode", "local-test");
            local.setActiveProfiles(worker ? new String[] {"db", "worker", "local-mailbox", "local-http"}
                    : new String[] {"db", "local-mailbox", "local-http"});
            assertDoesNotThrow(() -> RuntimeSettingsConfiguration.validate(local));
            assertDoesNotThrow(() -> RuntimeSettingsConfiguration.validate(publicEnvironment(worker)));
        }
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(com.example.marketplace.platform.api.RuntimePolicy.class).mode()).isEqualTo("local-full");
        });
    }

    @Test void publicForbiddenSettingsAndMissingBoundariesFailInBothRoles() {
        var rejected = Map.ofEntries(Map.entry("marketplace.runtime.mode", "production"),
                Map.entry("marketplace.runtime.mode ", "unknown"),
                Map.entry("marketplace.runtime.access", ""), Map.entry("marketplace.runtime.internal-api", "false"),
                Map.entry("marketplace.runtime.public-origin", "http://demo.example.test"),
                Map.entry("marketplace.session.secure", "false"), Map.entry("marketplace.phone.mode", "local-test"),
                Map.entry("marketplace.payment.mode", "SANDBOX"), Map.entry("marketplace.toss.client-key", "test_ck_synthetic"),
                Map.entry("marketplace.toss.secret-key", "live_sk_synthetic"), Map.entry("TOSS_SECRET_KEY", "test_sk_synthetic"),
                Map.entry("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9999"), Map.entry("GCLOUD_PROJECT", "synthetic"),
                Map.entry("marketplace.toss.endpoint", "http://127.0.0.1:9999"));
        for (boolean worker : List.of(false, true)) {
            rejected.forEach((key, value) -> {
                var env = publicEnvironment(worker).withProperty(key.strip(), value);
                var error = assertThrows(IllegalStateException.class, () -> RuntimeSettingsConfiguration.validate(env), key);
                assertThat(error.getMessage()).isEqualTo("RUNTIME_CONFIGURATION_REJECTED");
                assertThat(error.getCause()).isNull();
            });
            for (String forbidden : List.of("local-http", "local-mailbox", "local-test", "emulator", "prod", "production")) {
                var env = publicEnvironment(worker);
                env.addActiveProfile(forbidden);
                assertThrows(IllegalStateException.class, () -> RuntimeSettingsConfiguration.validate(env), forbidden);
            }
        }
    }

    @Test void invalidPublicConfigurationRejectsBeforeDatasourceAndFlywayAndDoesNotLeakSecrets(CapturedOutput output) {
        var created = new AtomicBoolean();
        new ApplicationContextRunner().withUserConfiguration(RuntimeSettingsConfiguration.class)
                .withPropertyValues("marketplace.runtime.mode=public-demo", "marketplace.runtime.access=invited-only",
                        "marketplace.runtime.public-origin=https://demo.example.test", "marketplace.runtime.internal-api=true",
                        "marketplace.toss.secret-key=synthetic-private-${MISSING_RUNTIME}")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("db"))
                .withBean("dataSource", Object.class, () -> { created.set(true); return new Object(); })
                .withBean("flyway", Object.class, () -> { created.set(true); return new Object(); })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(created).isFalse();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("RUNTIME_CONFIGURATION_REJECTED")
                            .hasStackTraceContaining("IllegalStateException");
                    assertThat(context.getStartupFailure().getMessage()).doesNotContain("synthetic-private", "MISSING_RUNTIME");
                    assertThat(output.getAll()).doesNotContain("synthetic-private", "MISSING_RUNTIME");
                });
    }

    @Test void localLiveAndNonLoopbackAndWebPollingAndWorkerHttpAreRejected() {
        for (String[] pair : List.of(new String[]{"marketplace.toss.secret-key", "live_sk_synthetic"},
                new String[]{"server.address", "0.0.0.0"}, new String[]{"marketplace.session.secure", "false"},
                new String[]{"marketplace.phone.mode", "unavailable"}, new String[]{"marketplace.payment.mode", "LIVE"},
                new String[]{"marketplace.toss.endpoint", "http://127.0.0.1:9999"},
                new String[]{"marketplace.runtime.access", "invited-only"},
                new String[]{"marketplace.runtime.public-origin", "https://demo.example.test"},
                new String[]{"marketplace.runtime.internal-api", "true"},
                new String[]{"marketplace.worker.enabled", "true"})) {
            var env = new org.springframework.mock.env.MockEnvironment().withProperty(pair[0], pair[1]);
            assertThrows(IllegalStateException.class, () -> RuntimeSettingsConfiguration.validate(env));
        }
        var env = publicEnvironment(true).withProperty("spring.main.web-application-type", "servlet");
        assertThrows(IllegalStateException.class, () -> RuntimeSettingsConfiguration.validate(env));
        assertThrows(IllegalStateException.class, () -> RuntimeSettingsConfiguration.validate(
                publicEnvironment(true).withProperty("marketplace.media.enabled", "true")));
        assertThrows(IllegalStateException.class, () -> RuntimeSettingsConfiguration.validate(
                publicEnvironment(false).withProperty("marketplace.media.enabled", "false")));
    }
}
