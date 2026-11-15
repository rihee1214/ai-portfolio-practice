package com.example.marketplace.platform.config;

import com.example.marketplace.platform.api.RuntimePolicy;
import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Reject unsafe combinations before any DataSource, migration or provider is instantiated. */
@Configuration(proxyBeanMethods = false)
public class RuntimeSettingsConfiguration {
    @Bean
    static BeanFactoryPostProcessor requiredRuntimeSettings(Environment environment) {
        return beans -> validate(environment);
    }

    @Bean
    RuntimePolicy runtimePolicy(Environment environment) { return new RuntimePolicy(value(environment, "marketplace.runtime.mode", "local-full")); }

    public static void validate(Environment env) {
        try {
            String mode = value(env, "marketplace.runtime.mode", "local-full");
            var profiles = Arrays.asList(env.getActiveProfiles());
            if (profiles.contains("prod") || profiles.contains("production")) { reject(); }
            var policy = new RuntimePolicy(mode);
            boolean worker = "true".equals(value(env, "marketplace.worker.enabled", "false"));
            if (!java.util.List.of("true", "false").contains(value(env, "marketplace.worker.enabled", "false"))) { reject(); }
            boolean workerProfile = profiles.contains("worker");
            if ((worker && !workerProfile) || (workerProfile && !"none".equals(value(env, "spring.main.web-application-type", "servlet")))) { reject(); }
            String media = value(env, "marketplace.media.enabled", "true");
            if (workerProfile && !"false".equals(media)) { reject(); }
            String client = value(env, "marketplace.toss.client-key", value(env, "TOSS_CLIENT_KEY", ""));
            String secret = value(env, "marketplace.toss.secret-key", value(env, "TOSS_SECRET_KEY", ""));
            if (client.startsWith("live_") || secret.startsWith("live_")) { reject(); }
            if (!java.util.List.of("DEMO", "SANDBOX").contains(value(env, "marketplace.payment.mode", "DEMO"))) { reject(); }
            if (!value(env, "marketplace.toss.endpoint", "https://api.tosspayments.com").equals("https://api.tosspayments.com")) { reject(); }
            if (!java.util.List.of("disabled", "local-test").contains(value(env, "marketplace.phone.mode", "disabled"))) { reject(); }
            if (!policy.restricted()) {
                // Public boundary declarations without a public mode must never silently fall back to local policy.
                if (!value(env, "marketplace.runtime.access", "").isBlank()
                        || !value(env, "marketplace.runtime.public-origin", "").isBlank()
                        || !"false".equals(value(env, "marketplace.runtime.internal-api", "false"))) { reject(); }
                String address = value(env, "server.address", "127.0.0.1");
                if (!address.equals("127.0.0.1") && !address.equals("::1")) { reject(); }
                if (!"true".equals(value(env, "marketplace.session.secure", "true")) && !profiles.contains("local-http")) { reject(); }
                if ("local-test".equals(value(env, "marketplace.phone.mode", "disabled"))
                        && (!address.equals("127.0.0.1") || !value(env, "GCLOUD_PROJECT", "").matches("demo-[a-z0-9-]+")
                            || !validEmulator(value(env, "FIREBASE_AUTH_EMULATOR_HOST", "")))) { reject(); }
                return;
            }
            if (!profiles.contains("db") || profiles.contains("local-http") || profiles.contains("local-mailbox")
                    || profiles.contains("local-test") || profiles.contains("emulator")
                    || !"true".equals(value(env, "marketplace.session.secure", "true"))
                    || (!workerProfile && !"true".equals(media))
                    || !"disabled".equals(value(env, "marketplace.phone.mode", "disabled"))
                    || !"DEMO".equals(value(env, "marketplace.payment.mode", "DEMO"))
                    || !"invited-only".equals(value(env, "marketplace.runtime.access", ""))
                    || !"true".equals(value(env, "marketplace.runtime.internal-api", "false"))) { reject(); }
            URI origin = URI.create(value(env, "marketplace.runtime.public-origin", ""));
            if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getRawQuery() != null || origin.getFragment() != null
                    || (origin.getPath() != null && !origin.getPath().isEmpty()) || origin.getPort() != -1) { reject(); }
            for (String key : new String[] {"marketplace.toss.client-key", "marketplace.toss.secret-key", "marketplace.toss.mid",
                    "marketplace.toss.api-version", "marketplace.toss.webhook-security-key", "TOSS_CLIENT_KEY", "TOSS_SECRET_KEY",
                    "TOSS_MID", "TOSS_API_VERSION", "TOSS_WEBHOOK_SECURITY_KEY", "FIREBASE_AUTH_EMULATOR_HOST",
                    "FIRESTORE_EMULATOR_HOST", "FIREBASE_DATABASE_EMULATOR_HOST", "GCLOUD_PROJECT"}) {
                if (!value(env, key, "").isBlank()) { reject(); }
            }
        } catch (RuntimeException invalid) {
            // Neither values nor placeholder/URI parser causes may enter startup logs.
            throw new IllegalStateException("RUNTIME_CONFIGURATION_REJECTED");
        }
    }

    private static String value(Environment env, String key, String fallback) {
        String value = env.getProperty(key, fallback);
        if (value.contains("${")) { reject(); }
        return value;
    }
    private static void reject() { throw new IllegalArgumentException("RUNTIME_CONFIGURATION_REJECTED"); }
    private static boolean validEmulator(String host) {
        if (!host.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) { return false; }
        int port = Integer.parseInt(host.substring(host.indexOf(':') + 1));
        return port > 0 && port <= 65535;
    }
}
