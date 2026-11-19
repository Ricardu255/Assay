package io.assay.report;

import io.assay.model.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Human-readable console view of one run.
 *
 * <p>The default is silent so JSON output and exit codes stay script-friendly. When enabled, it shows
 * the failing cases first-class: metric score and reason, failed checks, and a bounded final output.
 */
public final class ConsoleReport {

    /** Which cases to print. */
    public enum Display {
        NONE,
        FAILING,
        PASSING,
        ALL;

        /** Parses a CLI value; unknown values are a usage error. */
        public static Display parse(String value) {
            if (value == null || value.isBlank() || "none".equalsIgnoreCase(value)) {
                return NONE;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("display must be none, failing, passing, or all");
            }
        }
    }

    private static final int MAX_REASON_CHARS = 200;
    private static final int MAX_OUTPUT_CHARS = 160;

    private ConsoleReport() {
    }

    /** Renders the report; an empty string is returned when display is {@code none}. */
    public static String render(Map<String, Object> summary, String display, int maxCases) {
        Display mode = Display.parse(display);
        if (mode == Display.NONE || summary == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Run " + value(summary.get("run_id")) + " · " + value(summary.get("status")));
        lines.add("  suite=" + value(summary.get("suite")) + " cases=" + value(summary.get("case_count"))
                + " hard_failures=" + value(summary.get("hard_failures"))
                + " soft_warnings=" + value(summary.get("soft_warnings")));
        appendMetrics(lines, Values.asMap(summary.get("metric_stats")));

        int limit = Math.max(1, maxCases);
        int printed = 0;
        for (Object raw : Values.asList(summary.get("results"))) {
            if (printed == limit) {
                lines.add("  … more cases omitted (limit " + limit + ")");
                break;
            }
            Map<String, Object> result = Values.asMap(raw);
            boolean passed = Boolean.TRUE.equals(result.get("hard_pass"));
            if (!matches(mode, passed)) {
                continue;
            }
            appendCase(lines, result, passed);
            printed++;
        }
        lines.add("");
        return String.join("\n", lines);
    }

    /** Writes the report to stdout when a display mode is enabled. */
    public static void print(Map<String, Object> summary, String display, int maxCases) {
        String rendered = render(summary, display, maxCases);
        if (!rendered.isEmpty()) {
            System.out.print(rendered);
        }
    }

    private static boolean matches(Display mode, boolean passed) {
        return switch (mode) {
            case ALL -> true;
            case FAILING -> !passed;
            case PASSING -> passed;
            case NONE -> false;
        };
    }

    private static void appendMetrics(List<String> lines, Map<String, Object> metricStats) {
        if (metricStats.isEmpty()) {
            return;
        }
        lines.add("  metrics:");
        for (Map.Entry<String, Object> entry : metricStats.entrySet()) {
            Map<String, Object> row = Values.asMap(entry.getValue());
            Object rate = row.get("pass_rate");
            Object mean = row.get("mean_score");
            lines.add("    " + entry.getKey() + " · " + value(row.get("level"))
                    + " · pass " + value(row.get("passed")) + "/" + value(row.get("evaluated"))
                    + " (" + (rate instanceof Number number ? Fmt.percent1(number.doubleValue()) : "—") + ")"
                    + " · mean " + (mean instanceof Number number
                            ? String.format(Locale.ROOT, "%.3f", number.doubleValue()) : "—")
                    + " · skipped " + value(row.get("skipped"))
                    + " · errors " + value(row.get("errors")));
        }
    }

    private static void appendCase(List<String> lines, Map<String, Object> result, boolean passed) {
        Map<String, Object> evalCase = Values.asMap(result.get("case"));
        lines.add("");
        lines.add("[" + (passed ? "PASS" : "FAIL") + "] " + value(evalCase.get("case_id"))
                + " · " + value(evalCase.get("scenario")));
        for (Object raw : Values.asList(result.get("metrics"))) {
            Map<String, Object> metric = Values.asMap(raw);
            String status = value(metric.get("status"));
            if (passed && "pass".equalsIgnoreCase(status)) {
                continue;
            }
            lines.add("  metric " + value(metric.get("name")) + " · " + status
                    + " · score " + number(metric.get("score"))
                    + " / " + number(metric.get("threshold"))
                    + " · " + truncate(value(metric.get("reason")), MAX_REASON_CHARS));
        }
        for (Object raw : Values.asList(result.get("checks"))) {
            Map<String, Object> check = Values.asMap(raw);
            if (Boolean.TRUE.equals(check.get("passed"))) {
                continue;
            }
            lines.add("  check " + value(check.get("name")) + " · " + value(check.get("level"))
                    + " · " + truncate(value(check.get("message")), MAX_REASON_CHARS));
        }
        Map<String, Object> trace = Values.asMap(result.get("trace"));
        if (!trace.isEmpty() && trace.get("final_output") != null) {
            lines.add("  output: " + truncate(String.valueOf(trace.get("final_output")), MAX_OUTPUT_CHARS));
        }
    }

    private static String number(Object value) {
        return value instanceof Number number ? String.format(Locale.ROOT, "%.3f", number.doubleValue()) : "—";
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String value(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }
}
