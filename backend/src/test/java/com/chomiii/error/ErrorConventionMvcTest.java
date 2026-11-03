package com.chomiii.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Specifies the error-handling convention through MockMvc against a
 * throwaway controller: it demonstrates how any future endpoint inherits
 * the convention, without inventing production surface in M0.
 *
 * <p>Parameter constraints are validated explicitly in the setup so the
 * same runtime behavior as the real DispatcherServlet applies.
 */
class ErrorConventionMvcTest {

    MockMvc mockMvc;

    /** Test-only endpoints used solely to provoke the exception handlers. */
    @RestController
    static class FixtureController {

        record GreetingRequest(@NotBlank String recipient) {
        }

        @PostMapping("/fixtures/validation")
        void accepts(@Valid @RequestBody GreetingRequest request) {
        }

        @GetMapping("/fixtures/validation")
        void validatesParameter(@RequestParam @Min(10) int limit) {
        }

        @GetMapping("/fixtures/unexpected")
        void unexpected() {
            throw new IllegalStateException("boom");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    @Test
    void invalidRequestBodyYieldsHttp400ProblemDetailsWithFieldErrors() throws Exception {
        mockMvc.perform(post("/fixtures/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("recipient"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void invalidQueryParameterYieldsHttp400ProblemDetailsWithMessage() throws Exception {
        mockMvc.perform(get("/fixtures/validation").param("limit", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void malformedJsonBodyStaysBadRequest400() throws Exception {
        mockMvc.perform(post("/fixtures/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unsupportedMediaTypeStays415() throws Exception {
        mockMvc.perform(post("/fixtures/validation")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void unexpectedExceptionYieldsHttp500ProblemDetailsWithoutLeakingInternals() throws Exception {
        mockMvc.perform(get("/fixtures/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(content().string(not(containsString("boom"))));
    }
}
