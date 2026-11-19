package io.assay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class MetricSummaryTest {

    @Test
    void aggregatesOnlyMeasuredResultsIntoTheScore() {
        List<Object> results = List.of(
                Map.of(
                        "checks", List.of(Map.of("layer", "metric", "name", "tool_permission", "level", "hard")),
                        "metrics", List.of(
                                Map.of("name", "tool_permission", "status", "pass", "score", 0.9,
                                        "cached", false),
                                Map.of("name", "task_completion", "status", "skip", "reason", "no trace"))),
                Map.of(
                        "metrics", List.of(
                                Map.of("name", "tool_permission", "status", "fail", "score", 0.3),
                                Map.of("name", "task_completion", "status", "error", "reason", "judge down"))));

        Map<String, Object> summary = MetricSummary.fromResults(results);
        Map<String, Object> permission = map(summary.get("tool_permission"));

        assertEquals("hard", permission.get("level"));
        assertEquals(2, intOf(permission.get("evaluated")));
        assertEquals(1, intOf(permission.get("passed")));
        assertEquals(0.5, (double) permission.get("pass_rate"));
        assertEquals(0.6, (double) permission.get("mean_score"), 1e-9);

        Map<String, Object> completion = map(summary.get("task_completion"));
        assertEquals(0, intOf(completion.get("evaluated")));
        assertNull(completion.get("mean_score"));
        assertEquals(1, intOf(completion.get("skipped")));
        assertEquals(1, intOf(completion.get("errors")));
    }

    @Test
    void sumsTokensCostAndCacheHits() {
        List<Object> results = List.of(Map.of("metrics", List.of(
                Map.of("name", "faithfulness", "status", "pass", "score", 1.0,
                        "input_tokens", 100, "output_tokens", 20, "cost_usd", 0.002, "cached", true),
                Map.of("name", "faithfulness", "status", "fail", "score", 0.5,
                        "input_tokens", 50, "output_tokens", 10, "cost_usd", 0.001))));

        Map<String, Object> row = map(MetricSummary.fromResults(results).get("faithfulness"));

        assertEquals(150, intOf(row.get("input_tokens")));
        assertEquals(30, intOf(row.get("output_tokens")));
        assertEquals(0.003, (double) row.get("cost_usd"), 1e-9);
        assertEquals(1, intOf(row.get("cached")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
