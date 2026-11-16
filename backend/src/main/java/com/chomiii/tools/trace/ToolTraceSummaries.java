package com.chomiii.tools.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the privacy-safe summaries stored with every tool trace: the number
 * of top-level entries. Keys and values are untrusted, so neither is stored.
 */
final class ToolTraceSummaries {
    private static final Pattern JSON_KEY = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private static final ObjectMapper json = new ObjectMapper();

    private ToolTraceSummaries() {}

    static ToolTraceDto.Summary from(Map<String, ?> values) {
        int count = values == null ? 0 : values.size();
        return new ToolTraceDto.Summary(count, List.of());
    }

    static String toJson(ToolTraceDto.Summary summary) {
        for (String label : summary.labels()) {
            if (!JSON_KEY.matcher(label).matches()) {
                throw new IllegalStateException("Summary labels must be validated keys");
            }
        }
        try {
            return json.writeValueAsString(summary);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Tool trace summary cannot be serialized", exception);
        }
    }

    static ToolTraceDto.Summary fromJson(String jsonText) {
        if (jsonText == null) {
            return new ToolTraceDto.Summary(0, List.of());
        }
        try {
            var node = json.readTree(jsonText);
            var labels = new java.util.ArrayList<String>();
            for (var label : node.path("labels")) {
                String text = label.asText();
                if (!JSON_KEY.matcher(text).matches()) {
                    throw new IllegalStateException("Stored summary label is not a validated key");
                }
                labels.add(text);
            }
            return new ToolTraceDto.Summary((int) node.path("count").asInt(0), List.copyOf(labels));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Stored tool trace summary is not valid JSON", exception);
        }
    }
}
