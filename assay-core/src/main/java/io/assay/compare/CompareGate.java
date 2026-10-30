package io.assay.compare;

import io.assay.json.Json;
import io.assay.json.Jsonable;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A flat, single-comparison release gate.
 *
 * <p>The evolution policy exists to judge a candidate across improvement, regression, and holdout
 * datasets. This gate answers the narrower question a release owner usually asks: given two runs of
 * the same suite, is the candidate acceptable? It therefore uses the same thresholds in a flat JSON
 * shape, with no roles, objectives, or candidate manifest.
 *
 * <p>Severities mirror the release decision rather than the metric: a quality drop or a critical
 * regression blocks, while an increase in ordinary task regressions is a warning by default. Every
 * default can be overridden through {@code severity}.
 */
public record CompareGate(
        int minimumSamples,
        Double minimumHardPassRate,
        Double maximumHardPassRateDrop,
        Integer maximumTaskRegressions,
        Integer maximumCriticalTaskRegressions,
        Double maximumAverageLatencyMs,
        Double maximumP95LatencyMs,
        Double maximumAverageTotalTokens,
        Double maximumAverageCostUsd,
        Map<String, Double> minimumRulePassRates,
        Map<String, String> severities,
        boolean useEstimatedMetrics)
        implements Jsonable {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "minimum_samples",
            "minimum_hard_pass_rate",
            "maximum_hard_pass_rate_drop",
            "maximum_task_regressions",
            "maximum_critical_task_regressions",
            "maximum_average_latency_ms",
            "maximum_p95_latency_ms",
            "maximum_average_total_tokens",
            "maximum_average_cost_usd",
            "minimum_rule_pass_rates",
            "severity",
            "use_estimated_metrics");

    private static final Set<String> SEVERITY_KEYS = Set.of(
            "minimum_samples",
            "minimum_hard_pass_rate",
            "maximum_hard_pass_rate_drop",
            "maximum_task_regressions",
            "maximum_critical_task_regressions",
            "maximum_average_latency_ms",
            "maximum_p95_latency_ms",
            "maximum_average_total_tokens",
            "maximum_average_cost_usd",
            "minimum_rule_pass_rates",
            "missing_metric");

    private static final Map<String, String> DEFAULT_SEVERITIES = Map.ofEntries(
            Map.entry("minimum_samples", "block"),
            Map.entry("minimum_hard_pass_rate", "block"),
            Map.entry("maximum_hard_pass_rate_drop", "block"),
            Map.entry("maximum_task_regressions", "warning"),
            Map.entry("maximum_critical_task_regressions", "block"),
            Map.entry("maximum_average_latency_ms", "block"),
            Map.entry("maximum_p95_latency_ms", "block"),
            Map.entry("maximum_average_total_tokens", "warning"),
            Map.entry("maximum_average_cost_usd", "warning"),
            Map.entry("minimum_rule_pass_rates", "block"),
            Map.entry("missing_metric", "info"));

    public CompareGate {
        if (minimumSamples < 1) {
            throw new IllegalArgumentException("minimum_samples must be at least 1");
        }
        rate("minimum_hard_pass_rate", minimumHardPassRate);
        rate("maximum_hard_pass_rate_drop", maximumHardPassRateDrop);
        nonNegative("maximum_task_regressions", maximumTaskRegressions);
        nonNegative("maximum_critical_task_regressions", maximumCriticalTaskRegressions);
        nonNegative("maximum_average_latency_ms", maximumAverageLatencyMs);
        nonNegative("maximum_p95_latency_ms", maximumP95LatencyMs);
        nonNegative("maximum_average_total_tokens", maximumAverageTotalTokens);
        nonNegative("maximum_average_cost_usd", maximumAverageCostUsd);
        minimumRulePassRates = minimumRulePassRates == null ? Map.of() : Map.copyOf(minimumRulePassRates);
        for (Map.Entry<String, Double> entry : minimumRulePassRates.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("minimum_rule_pass_rates requires non-empty rule names");
            }
            rate("minimum_rule_pass_rates." + entry.getKey(), entry.getValue());
        }
        severities = severities == null ? Map.of() : Map.copyOf(severities);
        for (Map.Entry<String, String> entry : severities.entrySet()) {
            if (!SEVERITY_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("unknown severity rule: " + entry.getKey());
            }
            if (!List.of("block", "warning", "info").contains(entry.getValue())) {
                throw new IllegalArgumentException(
                        "severity for " + entry.getKey() + " must be block, warning, or info");
            }
        }
    }

    /** A gate with no thresholds; useful when a caller only wants the comparison report. */
    public static CompareGate defaults() {
        return new CompareGate(1, null, null, null, null, null, null, null, null, Map.of(), Map.of(), false);
    }

    /** A named preset so the first comparison needs no configuration file. */
    public static CompareGate profile(String name) {
        return switch (name) {
            case "none" -> defaults();
            case "balanced" -> new CompareGate(
                    1, 0.90, 0.03, 0, 0, null, null, null, null, Map.of(), Map.of(), false);
            case "strict" -> new CompareGate(
                    1, 0.95, 0.0, 0, 0, 1000.0, 1500.0, null, null, Map.of(), Map.of(), false);
            default -> throw new IllegalArgumentException("unknown compare profile: " + name);
        };
    }

    public static CompareGate fromFile(Path path) {
        return fromDict(Json.parseObject(AtomicFiles.readText(path)));
    }

    public static CompareGate fromDict(Map<String, Object> value) {
        for (String key : value.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown compare gate option: " + key);
            }
        }
        Map<String, Double> ruleRates = new TreeMap<>();
        Object rawRules = value.get("minimum_rule_pass_rates");
        if (rawRules != null) {
            if (!(rawRules instanceof Map<?, ?> rules)) {
                throw new IllegalArgumentException("minimum_rule_pass_rates must be an object");
            }
            for (Map.Entry<?, ?> entry : rules.entrySet()) {
                ruleRates.put(
                        String.valueOf(entry.getKey()),
                        decimal("minimum_rule_pass_rates." + entry.getKey(), entry.getValue()));
            }
        }
        Map<String, String> severities = new LinkedHashMap<>();
        Object rawSeverities = value.get("severity");
        if (rawSeverities != null) {
            if (!(rawSeverities instanceof Map<?, ?> rules)) {
                throw new IllegalArgumentException("severity must be an object");
            }
            for (Map.Entry<?, ?> entry : rules.entrySet()) {
                severities.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return new CompareGate(
                integer("minimum_samples", value.get("minimum_samples"), 1),
                optionalDecimal("minimum_hard_pass_rate", value.get("minimum_hard_pass_rate")),
                optionalDecimal("maximum_hard_pass_rate_drop", value.get("maximum_hard_pass_rate_drop")),
                optionalInteger("maximum_task_regressions", value.get("maximum_task_regressions")),
                optionalInteger("maximum_critical_task_regressions", value.get("maximum_critical_task_regressions")),
                optionalDecimal("maximum_average_latency_ms", value.get("maximum_average_latency_ms")),
                optionalDecimal("maximum_p95_latency_ms", value.get("maximum_p95_latency_ms")),
                optionalDecimal("maximum_average_total_tokens", value.get("maximum_average_total_tokens")),
                optionalDecimal("maximum_average_cost_usd", value.get("maximum_average_cost_usd")),
                ruleRates,
                severities,
                Boolean.TRUE.equals(value.get("use_estimated_metrics")));
    }

    public String severity(String rule) {
        return severities.getOrDefault(rule, DEFAULT_SEVERITIES.getOrDefault(rule, "block"));
    }

    /**
     * Evaluates the candidate against the configured thresholds.
     *
     * @param baselineMetrics metrics from {@code EvolutionEngine.summarizeMetrics}
     * @param candidateMetrics metrics from {@code EvolutionEngine.summarizeMetrics}
     * @param transitions the output of {@code EvolutionEngine.compareCases}
     * @param estimatedEfficiency estimated token and cost values for traces that carry no telemetry
     */
    public GateOutcome evaluate(
            Map<String, Object> baselineMetrics,
            Map<String, Object> candidateMetrics,
            Map<String, Object> transitions,
            Map<String, Object> estimatedEfficiency) {
        List<GateViolation> violations = new ArrayList<>();
        Map<String, Object> estimated = estimatedEfficiency == null ? Map.of() : estimatedEfficiency;

        int caseCount = intValue(candidateMetrics.get("case_count"));
        if (caseCount < minimumSamples) {
            violations.add(block(
                    "minimum_samples",
                    caseCount,
                    minimumSamples,
                    "Candidate has " + caseCount + " cases; at least " + minimumSamples + " are required."));
        }

        Double passRate = number(candidateMetrics.get("hard_pass_rate"));
        if (minimumHardPassRate != null) {
            if (passRate == null) {
                violations.add(block(
                        "minimum_hard_pass_rate",
                        null,
                        minimumHardPassRate,
                        "Candidate hard pass rate is unavailable."));
            } else if (passRate < minimumHardPassRate) {
                violations.add(block(
                        "minimum_hard_pass_rate",
                        passRate,
                        minimumHardPassRate,
                        "Candidate hard pass rate is below the required minimum."));
            }
        }

        Double baselinePassRate = number(baselineMetrics.get("hard_pass_rate"));
        if (maximumHardPassRateDrop != null && baselinePassRate != null && passRate != null) {
            double drop = baselinePassRate - passRate;
            if (drop > maximumHardPassRateDrop) {
                violations.add(block(
                        "maximum_hard_pass_rate_drop",
                        drop,
                        maximumHardPassRateDrop,
                        "Candidate hard pass rate dropped further than allowed."));
            }
        }

        int regressions = intValue(transitions.get("regressed_count"));
        if (maximumTaskRegressions != null && regressions > maximumTaskRegressions) {
            violations.add(violation(
                    "maximum_task_regressions",
                    regressions,
                    maximumTaskRegressions,
                    "Candidate regressed more cases than the configured maximum."));
        }

        int criticalRegressions = countCriticalRegressions(transitions);
        if (maximumCriticalTaskRegressions != null && criticalRegressions > maximumCriticalTaskRegressions) {
            violations.add(block(
                    "maximum_critical_task_regressions",
                    criticalRegressions,
                    maximumCriticalTaskRegressions,
                    "Candidate regressed more critical cases than allowed."));
        }

        checkUpperBound(
                violations,
                "maximum_average_latency_ms",
                maximumAverageLatencyMs,
                number(candidateMetrics.get("mean_latency_ms")),
                null,
                "Candidate mean latency");
        checkUpperBound(
                violations,
                "maximum_p95_latency_ms",
                maximumP95LatencyMs,
                number(candidateMetrics.get("p95_latency_ms")),
                null,
                "Candidate P95 latency");

        Map<String, Object> efficiency = asMap(candidateMetrics.get("efficiency"));
        Double totalTokens = number(efficiency.get("mean_total_tokens"));
        Double cost = number(efficiency.get("mean_cost_usd"));
        Double estimatedTokens = useEstimatedMetrics ? number(estimated.get("mean_total_tokens")) : null;
        Double estimatedCost = useEstimatedMetrics ? number(estimated.get("mean_cost_usd")) : null;
        checkUpperBound(
                violations,
                "maximum_average_total_tokens",
                maximumAverageTotalTokens,
                totalTokens,
                estimatedTokens,
                "Candidate mean total tokens");
        checkUpperBound(
                violations,
                "maximum_average_cost_usd",
                maximumAverageCostUsd,
                cost,
                estimatedCost,
                "Candidate mean cost");

        Map<String, Object> ruleStats = asMap(candidateMetrics.get("rule_stats"));
        for (Map.Entry<String, Double> entry : minimumRulePassRates.entrySet()) {
            Map<String, Object> rule = asMap(ruleStats.get(entry.getKey()));
            if (rule.isEmpty()) {
                violations.add(block(
                        "minimum_rule_pass_rates",
                        0,
                        entry.getValue(),
                        "Configured rule `" + entry.getKey() + "` was not evaluated."));
                continue;
            }
            Double ruleRate = number(rule.get("pass_rate"));
            if (ruleRate == null) {
                violations.add(block(
                        "minimum_rule_pass_rates",
                        null,
                        entry.getValue(),
                        "Rule `" + entry.getKey() + "` has no evaluated samples."));
            } else if (ruleRate < entry.getValue()) {
                violations.add(block(
                        "minimum_rule_pass_rates",
                        ruleRate,
                        entry.getValue(),
                        "Rule `" + entry.getKey() + "` is below the required pass rate."));
            }
        }

        boolean blocked = violations.stream().anyMatch(item -> "block".equals(item.severity()));
        boolean warned = violations.stream().anyMatch(item -> "warning".equals(item.severity()));
        String status = blocked ? "BLOCK" : warned ? "WARNING" : "PASS";
        return new GateOutcome(status, violations);
    }

    private void checkUpperBound(
            List<GateViolation> violations,
            String rule,
            Double threshold,
            Double actual,
            Double estimated,
            String label) {
        if (threshold == null) {
            return;
        }
        if (actual != null) {
            if (actual > threshold) {
                violations.add(violation(
                        rule, actual, threshold, label + " is above the configured threshold."));
            }
            return;
        }
        if (estimated != null) {
            if (estimated > threshold) {
                violations.add(new GateViolation(
                        rule,
                        severity(rule),
                        estimated,
                        threshold,
                        label + " is estimated at " + estimate(estimated) + ", above the configured threshold.",
                        true));
            }
            return;
        }
        violations.add(missing(rule, threshold, label.toLowerCase(java.util.Locale.ROOT)));
    }

    private GateViolation block(String rule, Object actual, Object threshold, String message) {
        return new GateViolation(rule, "block", actual, threshold, message, false);
    }

    private GateViolation violation(String rule, Object actual, Object threshold, String message) {
        return new GateViolation(rule, severity(rule), actual, threshold, message, false);
    }

    private GateViolation missing(String rule, Object threshold, String metric) {
        String label = metric.isEmpty()
                ? metric
                : Character.toUpperCase(metric.charAt(0)) + metric.substring(1);
        return new GateViolation(
                rule,
                severity("missing_metric"),
                null,
                threshold,
                label + " is unavailable; the threshold could not be checked.",
                false);
    }

    private static int countCriticalRegressions(Map<String, Object> transitions) {
        int count = 0;
        for (Object raw : asList(transitions.get("regressed"))) {
            if (Boolean.TRUE.equals(asMap(raw).get("critical"))) {
                count++;
            }
        }
        return count;
    }

    private static String estimate(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimum_samples", minimumSamples);
        result.put("minimum_hard_pass_rate", minimumHardPassRate);
        result.put("maximum_hard_pass_rate_drop", maximumHardPassRateDrop);
        result.put("maximum_task_regressions", maximumTaskRegressions);
        result.put("maximum_critical_task_regressions", maximumCriticalTaskRegressions);
        result.put("maximum_average_latency_ms", maximumAverageLatencyMs);
        result.put("maximum_p95_latency_ms", maximumP95LatencyMs);
        result.put("maximum_average_total_tokens", maximumAverageTotalTokens);
        result.put("maximum_average_cost_usd", maximumAverageCostUsd);
        result.put("minimum_rule_pass_rates", minimumRulePassRates);
        result.put("severity", severities);
        result.put("use_estimated_metrics", useEstimatedMetrics);
        return result;
    }

    /** The verdict plus the evidence that produced it. */
    public record GateOutcome(String status, List<GateViolation> violations) implements Jsonable {

        public GateOutcome {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        @Override
        public Object toJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            List<Object> rows = new ArrayList<>(violations.size());
            for (GateViolation violation : violations) {
                rows.add(violation.toJson());
            }
            result.put("violations", rows);
            return result;
        }
    }

    /** One threshold that did not hold, or one metric that could not be checked. */
    public record GateViolation(
            String rule, String severity, Object actual, Object threshold, String message, boolean estimated)
            implements Jsonable {

        @Override
        public Object toJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rule", rule);
            result.put("severity", severity);
            result.put("actual", actual);
            result.put("threshold", threshold);
            result.put("message", message);
            result.put("estimated", estimated);
            return result;
        }
    }

    private static void rate(String name, Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 1)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    private static void nonNegative(String name, Number value) {
        if (value != null && (!Double.isFinite(value.doubleValue()) || value.doubleValue() < 0)) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static int integer(String name, Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new IllegalArgumentException(name + " must be an integer");
    }

    private static Integer optionalInteger(String name, Object value) {
        return value == null ? null : integer(name, value, 0);
    }

    private static Double optionalDecimal(String name, Object value) {
        return value == null ? null : decimal(name, value);
    }

    private static Double decimal(String name, Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException(name + " must be a finite number");
    }

    private static Double number(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                ? number.doubleValue()
                : null;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
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
