package com.example.marketplace.api;

import com.example.marketplace.MarketplaceApplication;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseSettingsTest {
    private static final String[] KEYS = {"url", "username", "password"};

    private ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    // Match SpringApplication's relaxed enum/property conversion.
                    context.getEnvironment().setConversionService(new ApplicationConversionService());
                    context.getEnvironment().getPropertySources().remove("systemEnvironment");
                    context.getEnvironment().getPropertySources().remove("systemProperties");
                    context.getEnvironment().setActiveProfiles(profile);
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/",
                        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                        "spring.flyway.enabled=false", "spring.sql.init.mode=never")
                .withUserConfiguration(MarketplaceApplication.class);
    }

    private ApplicationContextRunner valid(String profile) {
        return runner(profile).withPropertyValues("DB_URL=jdbc:postgresql://invalid.test/example",
                "DB_USERNAME=synthetic-user", "DB_PASSWORD=synthetic-secret");
    }

    static Stream<Arguments> invalidSettings() {
        return Stream.of(KEYS).flatMap(key -> Stream.of("", "   ", "${MISSING_E4}")
                .map(value -> Arguments.of(key, value)));
    }

    @ParameterizedTest
    @MethodSource("invalidSettings")
    void invalidEffectiveSettingFailsBeforeAnyApplicationSingleton(String key, String value) {
        var created = new AtomicBoolean();
        valid("db").withPropertyValues("spring.datasource." + key + "=" + value)
                .withBean("creationProbe", Object.class, () -> {
                    created.set(true);
                    return new Object();
                }).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("spring.datasource." + key);
                    assertThat(created).isFalse();
                });
    }

    @Test
    void missingEnvironmentSettingsReportNamesWithoutConnectionAttempt() {
        runner("db").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("DB_URL")
                    .hasStackTraceContaining("DB_USERNAME").hasStackTraceContaining("DB_PASSWORD");
        });
    }

    @Test
    void validEnvironmentSettingsPassWithoutConnectingToDatabase() {
        valid("db").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void explicitDatasourcePropertiesOverrideMissingEnvironmentVariables() {
        runner("db").withPropertyValues("spring.datasource.url=jdbc:postgresql://invalid.test/direct",
                "spring.datasource.username=direct-user", "spring.datasource.password=direct-secret")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void profileWithoutDbDoesNotRequireDatabaseSettings() {
        runner("local").run(context -> assertThat(context).hasNotFailed());
        runner("default").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void unresolvedPlaceholderDoesNotExposeRawValueOrCause(CapturedOutput output) {
        valid("db").withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("synthetic-sensitive", Map.of("spring.datasource.password",
                        "synthetic-private-${MISSING_E4}"))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("DB_PASSWORD")
                            .hasStackTraceContaining("spring.datasource.password");
                    Throwable failure = context.getStartupFailure();
                    while (failure.getCause() != null) {
                        failure = failure.getCause();
                    }
                    assertThat(failure.getMessage()).doesNotContain("synthetic-private", "MISSING_E4",
                            "synthetic-secret", "synthetic-user", "invalid.test");
                    assertThat(output.getAll()).doesNotContain("synthetic-private", "MISSING_E4",
                            "synthetic-secret", "synthetic-user", "invalid.test");
                });
    }
}
