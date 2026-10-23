package com.example.marketplace.testing;

import com.example.marketplace.MarketplaceApplication;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Test-only context: DB integration belongs to D2, not this suite. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ActiveProfiles("d1")
@SpringBootTest(classes = MarketplaceApplication.class, properties = {
    "spring.config.location=classpath:/",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=never"
})
public @interface DbFreeBootTest {
}
