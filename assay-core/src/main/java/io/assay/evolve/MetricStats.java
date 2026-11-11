package io.assay.evolve;

import io.assay.model.MetricObjective;
import io.assay.report.RuleStats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Per-run metric aggregation and scenario/rule deltas shared by evolution and release comparison. */
public final class MetricStats {

    private MetricStats() {
    }

    /** Per-metric summary of one run, including every configured objective. */
    public static Map<String, Object> summarizeMetrics(
            Map<String, Object> summary, List<MetricObjective> objectives) {
        List<Object> results = EvolutionSupport.asList(summary.get("results"));
        int caseCount = results.size();
        List<Double> latencies = new ArrayList<>();
        List<Double> steps = new ArrayList<>();
        for (Object rawResult : results) {
            Map<String, Object> trace = EvolutionSupport.asMap(EvolutionSupport.asMap(rawResult).get("trace"));
            Double latency = traceMetric(trace, "latency_ms");
            Double stepCount = traceMetric(trace, "steps");
            if (latency != null && Double.isFinite(latency)) {
                latencies.add(latency);
            }
            if (stepCount != null && Double.isFinite(stepCount)) {
                steps.add(stepCount);
            }
        }

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("latency_ms", EvolutionSupport.coverage(latencies.size(), caseCount));
        coverage.put("steps", EvolutionSupport.coverage(steps.size(), caseCount));
        Map<String, Object> efficiency = new LinkedHashMap<>();
        for (String metric : List.of("llm_calls", "input_tokens", "output_tokens", "total_tokens", "cost_usd")) {
            List<Double> values = new ArrayList<>();
            for (Object rawResult : results) {
                Object trace = EvolutionSupport.asMap(rawResult).get("trace");
                Double value = traceMetric(
                        trace instanceof Map ? EvolutionSupport.asMap(trace) : Map.of(), metric);
                if (value != null && Double.isFinite(value)) {
                    values.add(value);
                }
            }
            efficiency.put("mean_" + metric, aggregate(values, "mean"));
            coverage.put(metric, EvolutionSupport.coverage(values.size(), caseCount));
        }

        Map<String, Object> objectiveValues = new LinkedHashMap<>();
        Map<String, Object> objectiveCoverage = new LinkedHashMap<>();
        for (MetricObjective objective : objectives) {
            List<Double> samples = objectiveSamples(summary, objective);
            objectiveValues.put(
                    objective.name(),
                    samples.size() == caseCount ? aggregate(samples, objective.aggregation()) : null);
            objectiveCoverage.put(objective.name(), EvolutionSupport.coverage(samples.size(), caseCount));
        }

        Map<String, Object> ruleStats = RuleStats.fromResults(results, caseCount);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("case_count", caseCount);
        metrics.put("hard_failures", summary.get("hard_failures"));
        metrics.put(
                "hard_pass_rate",
                caseCount == 0
                        ? 0.0
                        : (double) (caseCount - EvolutionSupport.intValue(summary.get("hard_failures"))) / caseCount);
        metrics.put("soft_warning_count", summary.get("soft_warnings"));
        metrics.put("mean_latency_ms", aggregate(latencies, "mean"));
        metrics.put("p95_latency_ms", aggregate(latencies, "p95"));
        metrics.put("mean_steps", aggregate(steps, "mean"));
        metrics.put("efficiency", efficiency);
        metrics.put("objectives", objectiveValues);
        metrics.put("rule_stats", ruleStats);
        metrics.put("metric_coverage", coverage);
        metrics.put("objective_coverage", objectiveCoverage);
        return metrics;
    }

    /** Per-scenario counts, so a gate can look at one slice instead of the average. */
    public static Map<String, Map<String, Object>> summarizeScenarios(Map<String, Object> summary) {
        Map<String, Map<String, Object>> scenarios = new LinkedHashMap<>();
        for (Object rawResult : EvolutionSupport.asList(summary.get("results"))) {
            Map<String, Object> result = EvolutionSupport.asMap(rawResult);
            String scenario = String.valueOf(
                    EvolutionSupport.asMap(result.get("case")).getOrDefault("scenario", "default"));
            Map<String, Object> metrics = scenarios.computeIfAbsent(scenario, key -> {
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("case_count", 0);
                fresh.put("hard_failures", 0);
                fresh.put("soft_warning_count", 0);
                return fresh;
            });
            metrics.put("case_count", EvolutionSupport.intValue(metrics.get("case_count")) + 1);
            metrics.put(
                    "hard_failures",
                    EvolutionSupport.intValue(metrics.get("hard_failures"))
                            + (Boolean.TRUE.equals(result.get("hard_pass")) ? 0 : 1));
            metrics.put(
                    "soft_warning_count",
                    EvolutionSupport.intValue(metrics.get("soft_warning_count"))
                            + EvolutionSupport.intValue(result.get("soft_warning_count")));
        }
        for (Map<String, Object> metrics : scenarios.values()) {
            int count = EvolutionSupport.intValue(metrics.get("case_count"));
            metrics.put(
                    "hard_pass_rate",
                    count == 0
                            ? 0.0
                            : (double) (count - EvolutionSupport.intValue(metrics.get("hard_failures"))) / count);
        }
        return scenarios;
    }

    public static Map<String, Object> compareScenarios(
            Map<String, Object> baseline, Map<String, Object> candidate) {
        Map<String, Map<String, Object>> baselineScenarios = summarizeScenarios(baseline);
        Map<String, Map<String, Object>> candidateScenarios = summarizeScenarios(candidate);
        Map<String, Object> comparisons = new LinkedHashMap<>();
        Set<String> names = new TreeSet<>(baselineScenarios.keySet());
        names.addAll(candidateScenarios.keySet());
        for (String scenario : names) {
            Map<String, Object> baselineMetrics = baselineScenarios.get(scenario);
            Map<String, Object> candidateMetrics = candidateScenarios.get(scenario);
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("baseline", baselineMetrics);
            comparison.put("candidate", candidateMetrics);
            if (baselineMetrics != null && candidateMetrics != null) {
                comparison.put(
                        "hard_pass_rate_delta",
                        EvolutionSupport.doubleValue(candidateMetrics.get("hard_pass_rate"))
                                - EvolutionSupport.doubleValue(baselineMetrics.get("hard_pass_rate")));
                comparison.put(
                        "soft_warning_delta",
                        EvolutionSupport.intValue(candidateMetrics.get("soft_warning_count"))
                                - EvolutionSupport.intValue(baselineMetrics.get("soft_warning_count")));
            }
            comparisons.put(scenario, comparison);
        }
        return comparisons;
    }

    static Map<String, Object> compareRuleStats(
            Map<String, Object> baseline, Map<String, Object> candidate) {
        Set<String> names = new TreeSet<>(baseline.keySet());
        names.addAll(candidate.keySet());
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) {
            Map<String, Object> left = EvolutionSupport.asMap(baseline.get(name));
            Map<String, Object> right = EvolutionSupport.asMap(candidate.get(name));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("baseline", left.isEmpty() ? null : left);
            row.put("candidate", right.isEmpty() ? null : right);
            Object leftRate = left.get("pass_rate");
            Object rightRate = right.get("pass_rate");
            row.put(
                    "pass_rate_delta",
                    leftRate instanceof Number leftValue && rightRate instanceof Number rightValue
                            ? rightValue.doubleValue() - leftValue.doubleValue()
                            : null);
            result.put(name, row);
        }
        return result;
    }

    /** Reads one numeric metric out of a normalized trace, or null when the trace does not carry it. */
    static Double traceMetric(Map<String, Object> trace, String metric) {
        Map<String, Object> fields = EvolutionSupport.asMap(trace.get("fields"));
        Map<String, String> aliases = Map.of(
                "model_calls", "llm_call_count", "llm_calls", "llm_call_count", "tokens", "total_tokens");
        String fieldName = aliases.getOrDefault(metric, metric);
        Object value = fields.get(fieldName);
        if (fields.containsKey(fieldName)) {
            return value instanceof Number number && Double.isFinite(number.doubleValue())
                    ? number.doubleValue()
                    : null;
        }
        if (!(trace.get("events") instanceof List<?>)) {
            return null;
        }
        List<Object> events = EvolutionSupport.asList(trace.get("events"));
        if (List.of("latency_ms", "duration_ms").contains(metric)) {
            double total = 0;
            for (Object rawEvent : events) {
                Object duration = EvolutionSupport.asMap(rawEvent).get("duration_ms");
                if (!(duration instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    return null;
                }
                total += number.doubleValue();
            }
            return Double.isFinite(total) ? total : null;
        }
        if ("steps".equals(metric)) {
            return (double) events.size();
        }
        if (List.of("model_calls", "llm_calls").contains(metric)) {
            int total = 0;
            for (Object rawEvent : events) {
                String spanType = String.valueOf(
                        EvolutionSupport.asMap(EvolutionSupport.asMap(rawEvent).get("fields"))
                                .getOrDefault("span_type", ""));
                if (List.of("generation", "response").contains(spanType)) {
                    total++;
                }
            }
            return (double) total;
        }
        return null;
    }

    private static List<Double> objectiveSamples(Map<String, Object> summary, MetricObjective objective) {
        List<Object> results = EvolutionSupport.asList(summary.get("results"));
        List<Double> values = new ArrayList<>();
        for (Object rawResult : results) {
            Map<String, Object> result = EvolutionSupport.asMap(rawResult);
            Object traceValue = result.get("trace");
            Map<String, Object> trace = traceValue instanceof Map ? EvolutionSupport.asMap(traceValue) : Map.of();
            Map<String, Object> builtins = new LinkedHashMap<>();
            builtins.put("hard_pass", Boolean.TRUE.equals(result.get("hard_pass")) ? 1.0 : 0.0);
            builtins.put("soft_warning_count", (double) EvolutionSupport.intValue(result.get("soft_warning_count")));
            for (String metric : List.of(
                    "latency_ms",
                    "duration_ms",
                    "steps",
                    "model_calls",
                    "llm_calls",
                    "input_tokens",
                    "output_tokens",
                    "total_tokens",
                    "tokens",
                    "cost_usd")) {
                Double value = traceMetric(trace, metric);
                if (value != null) {
                    builtins.put(metric, value);
                }
            }
            Object value = builtins.get(objective.metric());
            if (value == null) {
                value = io.assay.rules.ValueOps.readPath(result, objective.metric());
                if (value == io.assay.rules.ValueOps.MISSING) {
                    value = null;
                }
            }
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                values.add(number.doubleValue());
            }
        }
        return values;
    }

    static Double aggregate(List<Double> values, String aggregation) {
        if (values.isEmpty()) {
            return null;
        }
        double result = switch (aggregation) {
            case "sum" -> values.stream().mapToDouble(Double::doubleValue).sum();
            case "min" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case "max" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "p50" -> percentile(values, 0.50);
            case "p95" -> percentile(values, 0.95);
            case "p99" -> percentile(values, 0.99);
            default -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        };
        return Double.isFinite(result) ? result : null;
    }

    /**
     * Linear-interpolated percentile, so a small sample still produces a defined value instead of an
     * index error. The sample count is reported separately through metric coverage.
     */
    static Double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = fraction * (sorted.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double value = sorted.get(lower) * (1 - (rank - lower)) + sorted.get(upper) * (rank - lower);
        return Double.isFinite(value) ? value : null;
    }
}
