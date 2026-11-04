package com.chomiii.provider;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProviderCatalogController.class)
@Import(ProviderCatalog.class)
class ProviderCatalogMvcTest {
    @Autowired
    MockMvc mvc;

    @Test
    void listsGeminiAndItsSupportedDefaultModelWithoutRequiringAKey() throws Exception {
        mvc.perform(get("/api/chat/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("gemini"))
                .andExpect(jsonPath("$[0].defaultModel").value("gemini-3.5-flash-lite"))
                .andExpect(jsonPath("$[0].models[0].id").value("gemini-3.5-flash-lite"));
    }
}
