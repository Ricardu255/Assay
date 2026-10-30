package io.assay.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the comparison report produced by {@link io.assay.compare.CompareEngine}.
 *
 * <p>The JSON report is the source of truth; this Markdown view is the one-screen artifact a release
 * owner reads first. Every section answers one question: can this candidate ship, which cases moved,
 * where did the first step diverge, and which scenario carries the damage.
 */
public final class CompareReport {

    private static final String DASH = "-";

    private CompareReport() {
    }

    public static String markdown(Map<String, Object> report) {
        Map<String, Object> baseline = asMap(report.get("baseline"));
        Map<String, Object> candidate = asMap(report.get("candidate"));
        Map<String, Object> deltas = asMap(report.get("deltas"));
        Map<String, Object> gate = asMap(report.get("gate"));
        Map<String, Object> transitions = asMap(report.get("transitions"));
        Map<String, Object> estimated = asMap(report.get("estimated_efficiency"));
        Map<String, Object> baselineEstimated = asMap(report.get("baseline_estimated_efficiency"));

        List<String> lines = new ArrayList<>();
        lines.add("# Assay Version Comparison - " + label(baseline) + " -> " + label(candidate));
        lines.add("");
        lines.add("- Dataset: `" + report.get("dataset") + "`");
        lines.add("- Cases: " + integer(candidate.get("case_count")));
        lines.add("- Gate: **" + gate.get("status") + "**");
        lines.add("- Regressions: " + integer(transitions.get("regressed_count"))
                + " (critical: " + criticalRegressions(transitions) + ")");
        lines.add("- Recovered: " + integer(transitions.get("recovered_count"))
                + ", persistent failures: " + integer(transitions.get("persistent_failure_count")));
        lines.add("");

        lines.add("## Metrics");
        lines.add("");
        lines.add("| Metric | Baseline | Candidate | Delta |");
        lines.add("|---|---:|---:|---:|");
        lines.add(metricRow("Hard pass rate", baseline, candidate, deltas, "hard_pass_rate", Kind.RATE));
        lines.add(metricRow("Soft warnings", baseline, candidate, deltas, "soft_warning_count", Kind.INTEGER));
        lines.add(metricRow("Mean latency", baseline, candidate, deltas, "mean_latency_ms", Kind.MS));
        lines.add(metricRow("P95 latency", baseline, candidate, deltas, "p95_latency_ms", Kind.MS));
        lines.add(metricRow("Mean steps", baseline, candidate, deltas, "mean_steps", Kind.NUMBER));
        lines.add(efficiencyRow("Mean total tokens", baseline, candidate, deltas, "mean_total_tokens",
                baselineEstimated, estimated, Kind.NUMBER));
        lines.add(efficiencyRow("Mean cost", baseline, candidate, deltas, "mean_cost_usd",
                baselineEstimated, estimated, Kind.USD));
        lines.add("");

        lines.add("## Gate findings");
        lines.add("");
        List<Object> violations = asList(gate.get("violations"));
        if (violations.isEmpty()) {
            lines.add("All configured thresholds passed.");
        } else {
            for (Object raw : violations) {
                Map<String, Object> violation = asMap(raw);
                lines.add("- [" + String.valueOf(violation.get("severity"))
                        .toUpperCase(java.util.Locale.ROOT) + "] `"
                        + violation.get("rule") + "`: actual "
                        + renderActual(violation.get("rule"), violation.get("actual"))
                        + ", threshold "
                        + renderActual(violation.get("rule"), violation.get("threshold"))
                        + ". " + violation.get("message")
                        + (Boolean.TRUE.equals(violation.get("estimated")) ? " (estimated)" : ""));
            }
        }
        lines.add("");

        Map<String, Object> scenarios = asMap(report.get("scenarios"));
        if (!scenarios.isEmpty()) {
            lines.add("## Scenario slices");
            lines.add("");
            lines.add("| Scenario | Cases | Baseline pass | Candidate pass | Delta | Regressions | Critical |");
            lines.add("|---|---:|---:|---:|---:|---:|---:|");
            for (Map.Entry<String, Object> entry : scenarios.entrySet()) {
                Map<String, Object> row = asMap(entry.getValue());
                lines.add("| `" + entry.getKey() + "` | " + integer(row.get("case_count"))
                        + " | " + percent(row.get("baseline_hard_pass_rate"))
                        + " | " + percent(row.get("candidate_hard_pass_rate"))
                        + " | " + signedPercent(row.get("hard_pass_rate_delta"))
                        + " | " + integer(row.get("regressions"))
                        + " | " + integer(row.get("critical_regressions")) + " |");
            }
            lines.add("");
        }

        lines.add("## Regressions");
        lines.add("");
        List<Object> regressions = asList(report.get("regressions"));
        if (regressions.isEmpty()) {
            lines.add("No case passed for the baseline and failed for the candidate.");
        } else {
            lines.add("| Case | Scenario | Critical | First divergence | Failed checks |");
            lines.add("|---|---|---:|---|---|");
            for (Object raw : regressions) {
                Map<String, Object> regression = asMap(raw);
                Map<String, Object> divergence = asMap(regression.get("first_divergence"));
                lines.add("| `" + regression.get("case_id") + "` | " + regression.get("scenario")
                        + " | " + (Boolean.TRUE.equals(regression.get("critical")) ? "yes" : "no")
                        + " | " + divergence(divergence)
                        + " | " + failedChecks(regression.get("failed_checks")) + " |");
            }
        }
        lines.add("");

        Map<String, Object> rules = asMap(report.get("rules"));
        if (!rules.isEmpty()) {
            lines.add("## Rule pass rates");
            lines.add("");
            lines.add("| Rule | Baseline | Candidate | Delta |");
            lines.add("|---|---:|---:|---:|");
            for (Map.Entry<String, Object> entry : rules.entrySet()) {
                Map<String, Object> row = asMap(entry.getValue());
                Map<String, Object> left = asMap(row.get("baseline"));
                Map<String, Object> right = asMap(row.get("candidate"));
                lines.add("| `" + entry.getKey() + "` | " + percent(left.get("pass_rate"))
                        + " | " + percent(right.get("pass_rate"))
                        + " | " + signedPercent(row.get("pass_rate_delta")) + " |");
            }
            lines.add("");
        }

        if (Boolean.TRUE.equals(estimated.get("estimated"))
                && (efficiencyValue(candidate, "mean_total_tokens") == null
                        || efficiencyValue(candidate, "mean_cost_usd") == null)) {
            lines.add("## Estimated efficiency");
            lines.add("");
            lines.add("The candidate trace did not report provider telemetry. The values marked with `~` "
                    + "are estimates from text length and a small price table; they are not billing data.");
            lines.add("");
            lines.add("- Estimated mean total tokens: " + number(estimated.get("mean_total_tokens")));
            lines.add("- Estimated mean cost: " + usd(estimated.get("mean_cost_usd")));
            lines.add("- Price model: `" + estimated.get("price_model") + "`");
            lines.add("");
        }

        lines.add("> This comparison read stored traces. No agent was re-run.");
        return Fmt.join(lines) + "\n";
    }

    private enum Kind {
        RATE,
        INTEGER,
        MS,
        NUMBER,
        USD
    }

    private static String metricRow(
            String name,
            Map<String, Object> baseline,
            Map<String, Object> candidate,
            Map<String, Object> deltas,
            String key,
            Kind kind) {
        return "| " + name + " | " + format(baseline.get(key), kind) + " | "
                + format(candidate.get(key), kind) + " | " + delta(deltas.get(key), kind) + " |";
    }

    private static String efficiencyRow(
            String name,
            Map<String, Object> baseline,
            Map<String, Object> candidate,
            Map<String, Object> deltas,
            String key,
            Map<String, Object> baselineEstimated,
            Map<String, Object> candidateEstimated,
            Kind kind) {
        Object baselineValue = efficiencyValue(baseline, key);
        Object candidateValue = efficiencyValue(candidate, key);
        String baselineText = format(baselineValue, kind);
        String candidateText = format(candidateValue, kind);
        String deltaText = delta(deltas.get(key), kind);
        if (baselineValue == null && baselineEstimated.get(key) instanceof Number number) {
            baselineText = "~" + format(number, kind);
        }
        if (candidateValue == null && candidateEstimated.get(key) instanceof Number number) {
            candidateText = "~" + format(number, kind);
            deltaText = DASH;
        }
        return "| " + name + " | " + baselineText + " | " + candidateText + " | " + deltaText + " |";
    }

    private static Object efficiencyValue(Map<String, Object> view, String key) {
        return asMap(view.get("efficiency")).get(key);
    }

    private static String format(Object value, Kind kind) {
        if (value == null) {
            return DASH;
        }
        return switch (kind) {
            case RATE -> percent(value);
            case INTEGER -> String.valueOf(value);
            case MS -> value instanceof Number number
                    ? String.format(java.util.Locale.ROOT, "%.1f ms", number.doubleValue())
                    : DASH;
            case USD -> usd(value);
            case NUMBER -> value instanceof Number number
                    ? String.format(java.util.Locale.ROOT, "%.1f", number.doubleValue())
                    : DASH;
        };
    }

    private static String delta(Object value, Kind kind) {
        if (!(value instanceof Number number)) {
            return DASH;
        }
        double numberValue = number.doubleValue();
        return switch (kind) {
            case RATE -> Fmt.signedPercent1(numberValue);
            case INTEGER -> Fmt.signedInt((int) numberValue);
            case MS -> Fmt.signedFixed1(numberValue) + " ms";
            case USD -> (numberValue >= 0 ? "+$" : "-$")
                    + String.format(java.util.Locale.ROOT, "%.6f", Math.abs(numberValue));
            case NUMBER -> Fmt.signedFixed1(numberValue);
        };
    }

    private static String divergence(Map<String, Object> divergence) {
        if (divergence.isEmpty()) {
            return DASH;
        }
        Object category = divergence.get("category");
        Object step = divergence.get("candidate_step");
        Object reason = divergence.get("reason");
        return (category == null ? "divergence" : String.valueOf(category))
                + (step == null ? "" : " at step " + step)
                + (reason == null ? "" : ": " + reason);
    }

    private static String failedChecks(Object value) {
        List<Object> checks = asList(value);
        if (checks.isEmpty()) {
            return DASH;
        }
        List<String> names = new ArrayList<>(checks.size());
        for (Object raw : checks) {
            Map<String, Object> check = asMap(raw);
            names.add(check.get("layer") + "/" + check.get("name"));
        }
        return String.join("; ", names);
    }

    private static String renderActual(Object rule, Object value) {
        if (value == null) {
            return DASH;
        }
        String name = String.valueOf(rule);
        if (name.contains("rate") || name.contains("drop")) {
            return percent(value);
        }
        if (!(value instanceof Number number)) {
            return String.valueOf(value);
        }
        if (name.contains("cost")) {
            return usd(number);
        }
        if (name.contains("regression") || name.contains("sample")) {
            return String.valueOf(number.intValue());
        }
        return number.doubleValue() == number.intValue()
                ? String.valueOf(number.intValue())
                : String.format(java.util.Locale.ROOT, "%.2f", number.doubleValue());
    }

    private static String percent(Object value) {
        return value instanceof Number number ? Fmt.percent1(number.doubleValue()) : DASH;
    }

    private static String signedPercent(Object value) {
        return value instanceof Number number ? Fmt.signedPercent1(number.doubleValue()) : DASH;
    }

    private static String usd(Object value) {
        return value instanceof Number number
                ? String.format(java.util.Locale.ROOT, "$%.6f", number.doubleValue())
                : DASH;
    }

    private static String number(Object value) {
        return value instanceof Number number
                ? String.format(java.util.Locale.ROOT, "%.1f", number.doubleValue())
                : DASH;
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static int criticalRegressions(Map<String, Object> transitions) {
        int count = 0;
        for (Object raw : asList(transitions.get("regressed"))) {
            if (Boolean.TRUE.equals(asMap(raw).get("critical"))) {
                count++;
            }
        }
        return count;
    }

    private static String label(Map<String, Object> view) {
        Object value = view.get("run_id");
        return value == null ? "unknown" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }
}
