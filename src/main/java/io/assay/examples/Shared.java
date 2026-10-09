package io.assay.examples;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared coercions for the reference adapters. */
final class Shared {

    private Shared() {
    }

    /** Reads a value out of a JSON-shaped payload, tolerating anything that is not a mapping. */
    static Object field(Object source, String key) {
        return asMap(source).get(key);
    }

    static String text(Object source, String key) {
        Object value = field(source, key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }
}
