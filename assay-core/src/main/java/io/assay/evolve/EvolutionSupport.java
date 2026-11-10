package io.assay.evolve;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small shared helpers for the evolution split; keeps the helper casts in one place. */
final class EvolutionSupport {

    private EvolutionSupport() {
    }

    static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100);
    }

    static Map<String, Integer> coverage(int observed, int total) {
        return Map.of("sample_count", observed, "missing_count", total - observed, "case_count", total);
    }

    static Double difference(Object candidate, Object baseline) {
        if (!(candidate instanceof Number right) || !(baseline instanceof Number left)) {
            return null;
        }
        double delta = right.doubleValue() - left.doubleValue();
        return Double.isFinite(delta) ? delta : null;
    }

    static boolean incomplete(Map<String, Object> summary) {
        return "budget_exhausted".equals(summary.get("status"))
                || intValue(summary.get("skipped_case_count")) > 0;
    }

    static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
