package io.assay.evolve;

import io.assay.model.Values;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Small shared helpers for the evolution split; keeps the helper casts in one place. */
final class EvolutionSupport {

    /** The three datasets every evolution experiment compares. */
    static final List<String> ROLES = List.of("improvement", "regression", "holdout");

    private EvolutionSupport() {
    }

    static double doubleValue(Object value) {
        return Values.numberAsDouble(value);
    }

    static int intValue(Object value) {
        return Values.numberAsInt(value);
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
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

    static List<Object> asList(Object value) {
        return Values.asList(value);
    }

    static Map<String, Object> asMap(Object value) {
        return Values.asMap(value);
    }
}
