package com.chomiii.tools;

import java.util.Map;

public interface InternalTool {
    ToolDefinition definition();
    Result execute(Map<String, Object> arguments);

    default String fingerprint(Map<String, Object> arguments) {
        return new java.util.TreeMap<>(arguments).toString();
    }

    record Result(Map<String, Object> output, Runnable onDelivered) {
        public Result {
            output = Map.copyOf(output);
        }
    }
}
