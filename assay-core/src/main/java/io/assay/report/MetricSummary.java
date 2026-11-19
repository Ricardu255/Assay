package io.assay.report;

import io.assay.model.Values;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregates metric results across a run.
 *
 * <p>Scores are averaged only over metrics that actually ran ({@code PASS}/{@code FAIL});
 * {@code SKIP}, {@code ERROR}, and {@code NOT_EVALUATED} are counted separately so a missing
 * measurement never looks like a good or a bad score.
 */
public final class MetricSummary {

    private MetricSummary() {
    }

    /** Builds name → statistics from the metrics embedded in each case result. */
    public static Map<String, Object> fromResults(List<Object> results) {
        Map<String, Stat> stats = new TreeMap<>();
        for (Object rawResult : results == null ? List.of() : results) {
            Map<String, Object> result = Values.asMap(rawResult);
            Map<String, String> levels = metricLevels(result);
            for (Object rawMetric : Values.asList(result.get("metrics"))) {
                collect(Values.asMap(rawMetric), levels, stats);
            }
        }
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Stat> entry : stats.entrySet()) {
            Stat stat = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", stat.level == null ? "unknown" : stat.level);
            row.put("evaluated", stat.evaluated);
            row.put("passed", stat.passed);
            row.put("pass_rate", stat.evaluated == 0 ? null : (double) stat.passed / stat.evaluated);
            row.put("mean_score", stat.scored == 0 ? null : stat.scoreSum / stat.scored);
            row.put("skipped", stat.skipped);
            row.put("errors", stat.errors);
            row.put("not_evaluated", stat.notEvaluated);
            row.put("input_tokens", stat.inputTokens);
            row.put("output_tokens", stat.outputTokens);
            row.put("cost_usd", stat.costUsd);
            row.put("cached", stat.cached);
            rows.put(entry.getKey(), row);
        }
        return rows;
    }

    private static void collect(Map<String, Object> metric, Map<String, String> levels, Map<String, Stat> stats) {
        String name = String.valueOf(metric.getOrDefault("name", "")).trim();
        if (name.isEmpty() || "null".equals(name)) {
            return;
        }
        Stat stat = stats.computeIfAbsent(name, key -> new Stat());
        String level = levels.get(name);
        if (level != null) {
            stat.level = level;
        }
        String status = String.valueOf(metric.getOrDefault("status", "")).trim().toLowerCase(Locale.ROOT);
        switch (status) {
            case "pass", "fail" -> {
                stat.evaluated++;
                if ("pass".equals(status)) {
                    stat.passed++;
                }
                Object score = metric.get("score");
                if (score instanceof Number number) {
                    stat.scoreSum += number.doubleValue();
                    stat.scored++;
                }
            }
            case "skip" -> stat.skipped++;
            case "error" -> stat.errors++;
            case "not_evaluated" -> stat.notEvaluated++;
            default -> stat.errors++;
        }
        stat.inputTokens += intValue(metric.get("input_tokens"));
        stat.outputTokens += intValue(metric.get("output_tokens"));
        Object cost = metric.get("cost_usd");
        if (cost instanceof Number number) {
            stat.costUsd += number.doubleValue();
        }
        if (Boolean.TRUE.equals(metric.get("cached"))) {
            stat.cached++;
        }
    }

    private static Map<String, String> metricLevels(Map<String, Object> result) {
        Map<String, String> levels = new LinkedHashMap<>();
        for (Object rawCheck : Values.asList(result.get("checks"))) {
            Map<String, Object> check = Values.asMap(rawCheck);
            if (!"metric".equals(check.get("layer"))) {
                continue;
            }
            String name = String.valueOf(check.getOrDefault("name", "")).trim();
            if (!name.isEmpty()) {
                levels.putIfAbsent(name, String.valueOf(check.getOrDefault("level", "unknown")));
            }
        }
        return levels;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static final class Stat {
        private String level;
        private int evaluated;
        private int passed;
        private int skipped;
        private int errors;
        private int notEvaluated;
        private int scored;
        private double scoreSum;
        private long inputTokens;
        private long outputTokens;
        private double costUsd;
        private int cached;
    }
}
