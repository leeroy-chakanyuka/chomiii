package com.chomiii.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chomiii.health.HealthController;
import com.chomiii.health.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specifies that Spring MVC built-in HTTP errors keep their native status in
 * the real application (where the production advice is wired and the catch-all
 * exception handler sits in front of the default resolvers): POST /api/health
 * must stay 405 and missing paths must stay 404, never repainted to 500.
 */
@WebMvcTest(HealthController.class)
class BuiltInHttpErrorMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HealthService healthService;

    @Test
    void postToHealthStaysMethodNotAllowed405() throws Exception {
        mockMvc.perform(post("/api/health"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownPathStaysNotFound404() throws Exception {
        mockMvc.perform(get("/api/definitely-missing"))
                .andExpect(status().isNotFound());
    }
}
