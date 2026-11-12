package com.chomiii.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable snapshots of JSON-compatible values. */
final class ToolValues {
    private ToolValues() {}

    static Map<String, Object> copy(Map<?, ?> input) {
        return copyMap(input, 0);
    }

    private static Map<String, Object> copyMap(Map<?, ?> input, int depth) {
        if (depth > 8 || input.size() > 100) throw new IllegalArgumentException("Invalid tool value");
        Map<String, Object> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("Invalid tool key");
            copy.put(name, copyValue(value, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value, int depth) {
        if (value == null) throw new IllegalArgumentException("Invalid tool value");
        if (depth > 8) throw new IllegalArgumentException("Invalid tool value");
        if (value instanceof Map<?, ?> map) return copyMap(map, depth);
        if (value instanceof List<?> list) {
            if (list.size() > 100) throw new IllegalArgumentException("Invalid tool value");
            return list.stream().map(item -> copyValue(item, depth + 1)).toList();
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) return value;
        throw new IllegalArgumentException("Invalid tool value");
    }
}
