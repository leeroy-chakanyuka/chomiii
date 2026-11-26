package com.chomiii.tools;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the supported JSON Schema subset at registration and invocation. */
public final class ToolSchemaValidator {
    private static final Set<String> KEYWORDS = Set.of("type", "description", "title", "properties", "required",
            "additionalProperties", "enum", "minimum", "maximum", "minLength", "maxLength", "pattern",
            "items", "minItems", "maxItems");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "integer", "number", "boolean");
    private static final int MAX_DEPTH = 8;

    private ToolSchemaValidator() {}

    public static void validateSchema(Map<String, Object> schema) {
        checkSchema(schema, 0);
        if (!"object".equals(schema.get("type"))) throw invalid();
    }

    public static void validate(Map<String, Object> schema, Map<String, Object> arguments) {
        if (arguments == null) throw invalid();
        checkValue(schema, arguments, 0);
    }

    private static void checkSchema(Map<?, ?> schema, int depth) {
        if (depth > MAX_DEPTH || !KEYWORDS.containsAll(schema.keySet())
                || !(schema.get("type") instanceof String type) || !TYPES.contains(type)) throw invalid();
        Set<String> applicable = switch (type) {
            case "object" -> Set.of("type", "description", "title", "enum", "properties", "required", "additionalProperties");
            case "array" -> Set.of("type", "description", "title", "enum", "items", "minItems", "maxItems");
            case "string" -> Set.of("type", "description", "title", "enum", "minLength", "maxLength", "pattern");
            case "integer", "number" -> Set.of("type", "description", "title", "enum", "minimum", "maximum");
            case "boolean" -> Set.of("type", "description", "title", "enum");
            default -> throw invalid();
        };
        if (!applicable.containsAll(schema.keySet())) throw invalid();
        if (schema.containsKey("enum") && (!(schema.get("enum") instanceof List<?> values) || values.isEmpty())) throw invalid();
        for (String bound : List.of("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) {
            if (schema.containsKey(bound) && (!(schema.get(bound) instanceof Number number)
                    || !Double.isFinite(number.doubleValue()))) throw invalid();
        }
        for (String bound : List.of("minLength", "maxLength", "minItems", "maxItems")) {
            if (schema.get(bound) instanceof Number number && (number.doubleValue() < 0
                    || number.doubleValue() != Math.rint(number.doubleValue()))) throw invalid();
        }
        for (List<String> pair : List.of(List.of("minimum", "maximum"), List.of("minLength", "maxLength"),
                List.of("minItems", "maxItems"))) {
            if (schema.get(pair.get(0)) instanceof Number lower && schema.get(pair.get(1)) instanceof Number upper
                    && lower.doubleValue() > upper.doubleValue()) throw invalid();
        }
        if (schema.containsKey("pattern")) {
            if (!(schema.get("pattern") instanceof String pattern)) throw invalid();
            Pattern.compile(pattern);
        }
        if ("object".equals(type)) {
            Object properties = schema.containsKey("properties") ? schema.get("properties") : Map.of();
            if (!(properties instanceof Map<?, ?> entries)) throw invalid();
            for (var entry : entries.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Map<?, ?> child)) throw invalid();
                checkSchema(child, depth + 1);
            }
            if (schema.containsKey("required")) {
                if (!(schema.get("required") instanceof List<?> required)
                        || required.stream().anyMatch(item -> !(item instanceof String) || !entries.containsKey(item))) throw invalid();
            }
            if (schema.containsKey("additionalProperties") && !(schema.get("additionalProperties") instanceof Boolean)) throw invalid();
        }
        if ("array".equals(type)) {
            if (!(schema.get("items") instanceof Map<?, ?> items)) throw invalid();
            checkSchema(items, depth + 1);
        }
    }

    private static void checkValue(Map<?, ?> schema, Object value, int depth) {
        if (depth > MAX_DEPTH || value == null) throw invalid();
        String type = (String) schema.get("type");
        switch (type) {
            case "object" -> {
                if (!(value instanceof Map<?, ?> object) || object.size() > 100) throw invalid();
                Map<?, ?> properties = (Map<?, ?>) (schema.containsKey("properties") ? schema.get("properties") : Map.of());
                Collection<?> required = (Collection<?>) (schema.containsKey("required") ? schema.get("required") : List.of());
                if (!object.keySet().containsAll(required)) throw invalid();
                for (var entry : object.entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw invalid();
                    Object child = properties.get(entry.getKey());
                    if (child == null) {
                        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) throw invalid();
                    } else {
                        checkValue((Map<?, ?>) child, entry.getValue(), depth + 1);
                    }
                }
            }
            case "array" -> {
                if (!(value instanceof List<?> list) || list.size() > 100) throw invalid();
                checkBounds(schema, list.size(), "minItems", "maxItems");
                for (Object item : list) checkValue((Map<?, ?>) schema.get("items"), item, depth + 1);
            }
            case "string" -> {
                if (!(value instanceof String text)) throw invalid();
                checkBounds(schema, text.codePointCount(0, text.length()), "minLength", "maxLength");
                if (schema.get("pattern") instanceof String pattern && !Pattern.compile(pattern).matcher(text).find()) throw invalid();
            }
            case "integer", "number" -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || "integer".equals(type) && number.doubleValue() != Math.rint(number.doubleValue())) throw invalid();
                checkBounds(schema, number.doubleValue(), "minimum", "maximum");
            }
            case "boolean" -> { if (!(value instanceof Boolean)) throw invalid(); }
            default -> throw invalid();
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) throw invalid();
    }

    private static void checkBounds(Map<?, ?> schema, double value, String minimum, String maximum) {
        if (schema.get(minimum) instanceof Number lower && value < lower.doubleValue()
                || schema.get(maximum) instanceof Number upper && value > upper.doubleValue()) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid tool schema or arguments");
    }
}
