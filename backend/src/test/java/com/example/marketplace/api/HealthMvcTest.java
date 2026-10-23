package com.example.marketplace.api;

import com.example.marketplace.testing.DbFreeBootTest;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DbFreeBootTest
@AutoConfigureMockMvc
class HealthMvcTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ApplicationContext context;

    @Test
    void d1ContextHasNoDatabaseOrMigrationBeans() {
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "*/*"})
    void healthExposesOnlyUp(String accept) throws Exception {
        mvc.perform(get("/actuator/health").accept(accept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void frontendJsonContentNegotiationIsPreserved() throws Exception {
        mvc.perform(get("/actuator/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/status", "/api/not-found", "/actuator", "/actuator/info", "/actuator/env", "/actuator/metrics"})
    void removedUnknownAndUnexposedPathsReturn404(String path) throws Exception {
        mvc.perform(get(path).accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotFound());
    }

    @Test
    void healthRejectsPostWithAllowHeader() throws Exception {
        mvc.perform(post("/actuator/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"));
    }
}
