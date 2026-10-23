package com.example.marketplace.api;

import com.example.marketplace.testing.DbFreeBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DbFreeBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "management.health.diskspace.threshold=9223372036854775807B")
class UnhealthyMvcTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void unavailableHealthReturns503WithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health").accept("application/json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
