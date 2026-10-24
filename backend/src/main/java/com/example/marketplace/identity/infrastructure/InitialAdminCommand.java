package com.example.marketplace.identity.infrastructure;

import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Local executable entry, never a web endpoint. Requires an already migrated schema. */
public final class InitialAdminCommand {
    private InitialAdminCommand() { }
    public static void main(String[] args) {
        try {
            if (args.length != 0) { throw new IllegalArgumentException("Arguments are not accepted"); }
            String id = System.getenv("BOOTSTRAP_USER_ID");
            String database = System.getenv("BOOTSTRAP_EXPECTED_DATABASE");
            String confirmation = System.getenv("BOOTSTRAP_CONFIRM");
            if (id == null || database == null || database.isBlank() || !"INITIAL_ADMIN".equals(confirmation)) {
                throw new IllegalArgumentException("Explicit environment inputs required");
            }
            UUID member = UUID.fromString(id);
            var application = new SpringApplication(com.example.marketplace.MarketplaceApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            try (var context = application.run("--spring.main.web-application-type=none", "--spring.profiles.active=db",
                    "--spring.config.location=classpath:/", "--spring.flyway.enabled=false", "--spring.sql.init.mode=never",
                    "--spring.main.banner-mode=off")) {
                new InitialAdmin(context.getBean(NamedParameterJdbcTemplate.class),
                        context.getBean(PlatformTransactionManager.class)).initialize(member, database, confirmation);
            }
            System.out.println("INITIAL_ADMIN_COMPLETED");
        } catch (Exception failure) {
            System.err.println("INITIAL_ADMIN_FAILED: check explicit inputs, target schema and one-time eligibility");
            System.exit(1);
        }
    }
}
