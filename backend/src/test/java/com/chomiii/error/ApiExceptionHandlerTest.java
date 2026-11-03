package com.chomiii.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Unit-level assertions for the catch-all handler itself; the built-in HTTP
 * statuses (405/400/415/404) are covered behaviorally in
 * BuiltInHttpErrorMvcTest and ErrorConventionMvcTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void unexpectedExceptionYieldsHttp500ProblemDetail() {
        ProblemDetail problem = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal server error");
        assertThat(problem.getProperties()).isNull();
    }

    @Test
    void unexpectedExceptionDoesNotLogOrExposeSensitiveMessages(CapturedOutput output) {
        String sensitiveMessage = "fake-sensitive-value";

        ProblemDetail problem = handler.handleUnexpected(new RuntimeException(sensitiveMessage));

        assertThat(problem.toString()).doesNotContain(sensitiveMessage);
        assertThat(output.getAll()).doesNotContain(sensitiveMessage);
    }
}
