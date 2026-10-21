package io.assay.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregates named checks across a run so rule pass rates are comparable across versions. */
public final class RuleStats {

    private RuleStats() {
    }

    public static Map<String, Object> fromResults(List<Object> results, int caseCount) {
        Map<String, Stat> stats = new TreeMap<>();
        for (Object rawResult : results == null ? List.of() : results) {
            Map<String, Object> result = asMap(rawResult);
            Object rawChecks = result.get("checks");
            if (!(rawChecks instanceof List<?> checks)) {
                continue;
            }
            for (Object rawCheck : checks) {
                Map<String, Object> check = asMap(rawCheck);
                String name = String.valueOf(check.getOrDefault("name", "")).trim();
                if (name.isEmpty() || "null".equals(name)) {
                    continue;
                }
                Stat stat = stats.computeIfAbsent(name, key -> new Stat());
                stat.evaluated++;
                if (Boolean.TRUE.equals(check.get("passed"))) {
                    stat.passed++;
                }
                String level = String.valueOf(check.getOrDefault("level", "")).trim();
                if (!level.isEmpty() && (stat.level == null || "hard".equals(level))) {
                    stat.level = level;
                }
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
            row.put("missing", Math.max(0, caseCount - stat.evaluated));
            rows.put(entry.getKey(), row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static final class Stat {
        private String level;
        private int evaluated;
        private int passed;
    }
}
