package com.chomiii.health;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Specifies the observable HTTP contract of the health endpoint: when the
 * service reports itself healthy, GET /api/health must serve 200 with the
 * UP status and the service name as JSON.
 */
@WebMvcTest(HealthController.class)
class HealthMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HealthService healthService;

    @Test
    void healthReturnsUpAndServiceNameAsJsonWithHttp200() throws Exception {
        when(healthService.current()).thenReturn(new HealthStatus("UP", "chomiii"));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("chomiii"));
    }
}
