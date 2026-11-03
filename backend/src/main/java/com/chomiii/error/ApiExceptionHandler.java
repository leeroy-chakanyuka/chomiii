package com.chomiii.error;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions thrown by controllers onto one consistent HTTP error
 * contract (RFC 7807 problem details) so the frontend never has to guess
 * error shapes per endpoint.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC built-in
 * errors (405 wrong method, 400 malformed JSON, 415 unsupported media type,
 * 404 unknown path, ...) keep their native status with their built-in
 * problem-detail handling; the catch-all below only claims exceptions that
 * nothing else handled.
 *
 * <p>Unexpected exception messages may contain sensitive data, so neither the
 * response nor the log includes the exception message or stack trace.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String VALIDATION_FAILED = "Validation failed";
    private static final String INTERNAL_ERROR = "Internal server error";
    private static final String NOT_FOUND = "Resource not found";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(VALIDATION_FAILED);
        problem.setProperty("errors", fieldErrors(exception));
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(VALIDATION_FAILED);
        problem.setProperty("errors", parameterErrors(exception));
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /**
     * Keeps the established problem-detail title for unknown paths while the
     * built-in 404 handling does the rest.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(NOT_FOUND);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled exception while serving request (type: {})", exception.getClass().getName());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle(INTERNAL_ERROR);
        return problem;
    }

    private static List<ValidationError> fieldErrors(MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ValidationError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
    }

    private static List<ValidationError> parameterErrors(HandlerMethodValidationException exception) {
        return exception.getAllErrors().stream()
                .map(error -> new ValidationError("", error.getDefaultMessage()))
                .toList();
    }

    record ValidationError(String field, String message) {
    }
}
