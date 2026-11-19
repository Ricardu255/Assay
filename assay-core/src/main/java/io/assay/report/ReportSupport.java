package io.assay.report;

import io.assay.model.Values;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared formatting and map-access helpers for the Markdown report writers. */
final class ReportSupport {

    static final String DASH = "—";

    private ReportSupport() {
    }

    static String cell(Object value) {
        return text(value).replace("|", "\\|").replace("\n", " ");
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String render(Object value) {
        return value == null ? "None" : String.valueOf(value);
    }

    static String renderPercent(Object value) {
        return value instanceof Number number ? Fmt.percent1(number.doubleValue()) : DASH;
    }

    static String renderOptional(Object value) {
        return value == null ? DASH : String.valueOf(value);
    }

    static double rate(Map<String, Object> metrics) {
        Object value = metrics.get("hard_pass_rate");
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    static String rateText(Object value) {
        return value instanceof Number number ? Fmt.percent1(number.doubleValue()) : DASH;
    }

    static String scoreText(Object value) {
        return value instanceof Number number
                ? String.format(Locale.ROOT, "%.3f", number.doubleValue())
                : DASH;
    }

    static String deltaText(Object value) {
        return value instanceof Number number
                ? String.format(Locale.ROOT, "%+.3f", number.doubleValue())
                : DASH;
    }

    static String latencySamples(Map<String, Object> metrics) {
        Map<String, Object> coverage = asMap(asMap(metrics.get("metric_coverage")).get("latency_ms"));
        return coverage.isEmpty()
                ? "unknown"
                : intValue(coverage.get("sample_count")) + "/" + intValue(coverage.get("case_count"));
    }

    static String passRate(Map<String, Object> stats) {
        Object value = stats.get("pass_rate");
        return value instanceof Number number ? Fmt.percent1(number.doubleValue()) : DASH;
    }

    static String samples(Map<String, Object> stats) {
        if (stats.isEmpty()) {
            return DASH;
        }
        int evaluated = intValue(stats.get("evaluated"));
        int missing = intValue(stats.get("missing"));
        return evaluated + "/" + (evaluated + missing);
    }

    static String sampleCoverage(Map<String, Object> metrics, String objective) {
        Map<String, Object> coverage = asMap(asMap(metrics.get("objective_coverage")).get(objective));
        return coverage.isEmpty()
                ? "unknown"
                : coverage.get("sample_count") + "/" + coverage.get("case_count");
    }

    static double doubleValue(Object value) {
        return Values.numberAsDouble(value);
    }

    static int intValue(Object value) {
        return Values.numberAsInt(value);
    }

    static List<Object> asList(Object value) {
        return Values.asList(value);
    }

    static Map<String, Object> asMap(Object value) {
        return Values.asMap(value);
    }
}
