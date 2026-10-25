package com.example.marketplace.media.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

@Configuration @Profile("db") @EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="marketplace.media.enabled",matchIfMissing=true)
public class MediaConfiguration {
    @Bean(destroyMethod="close") @DependsOnDatabaseInitialization
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(LocalImages.class)
    LocalImages localImages(@Value("${marketplace.media.root}") String root,NamedParameterJdbcTemplate jdbc) throws java.io.IOException {
        if(root.isBlank()) { throw new IllegalArgumentException("Media root required"); }
        return new LocalImages(java.nio.file.Path.of(root),jdbc.queryForObject("SELECT namespace FROM marketplace.media_namespace",java.util.Map.of(),java.util.UUID.class));
    }
    @Bean ImageNormalizer imageNormalizer() { return new ImageNormalizer(); }
}
