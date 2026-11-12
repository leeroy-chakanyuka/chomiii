package com.chomiii.tools;

import java.util.Map;

/** Provider-neutral function response payload. */
public final class ToolResponse {
    public enum ErrorCode { CALL_LIMIT, NOT_AVAILABLE, INVALID_ARGUMENTS, DUPLICATE_CALL, TIMEOUT, CANCELLED, FAILURE, RESULT_TOO_LARGE }

    private ToolResponse() {}

    public static Map<String, Object> success(Map<String, Object> output) {
        return Map.of("output", output);
    }

    public static Map<String, Object> error(ErrorCode code, String message) {
        return Map.of("error", message, "errorCode", code.name());
    }
}
