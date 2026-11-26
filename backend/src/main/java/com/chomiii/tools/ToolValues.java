package com.chomiii.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable snapshots of JSON-compatible values. */
final class ToolValues {
    private ToolValues() {}

    static Map<String, Object> copy(Map<?, ?> input) {
        return copyMap(input, 8);
    }

    // A schema nests both a property map and a child schema for each object level.
    static Map<String, Object> copySchema(Map<?, ?> schema) { return copyMap(schema, 18); }

    private static Map<String, Object> copyMap(Map<?, ?> input, int remaining) {
        if (remaining < 0 || input.size() > 100) throw new IllegalArgumentException("Invalid tool value");
        Map<String, Object> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("Invalid tool key");
            copy.put(name, copyValue(value, remaining - 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value, int remaining) {
        if (value == null) throw new IllegalArgumentException("Invalid tool value");
        if (remaining < 0) throw new IllegalArgumentException("Invalid tool value");
        if (value instanceof Map<?, ?> map) return copyMap(map, remaining);
        if (value instanceof List<?> list) {
            if (list.size() > 100) throw new IllegalArgumentException("Invalid tool value");
            return list.stream().map(item -> copyValue(item, remaining - 1)).toList();
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) return value;
        throw new IllegalArgumentException("Invalid tool value");
    }
}
