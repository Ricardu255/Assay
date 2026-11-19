package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.metrics.ToolPermissionMetric;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.TraceEvent;
import io.assay.store.file.FileMetricCache;
import io.assay.store.file.ResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** End-to-end coverage for metric execution, persistence, and gate mapping. */
class MetricEvaluationTest {

    @Test
    void aHardMetricFailureIsPersistedAndBlocksTheCase(@TempDir Path root) {
        ProjectAdapter adapter = adapter(List.of(tool("shell")));
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            Map<String, Object> summary = run(adapter, store, "metric-fail");

            assertEquals(1, integer(summary.get("hard_failures")));
            Map<String, Object> result = firstResult(summary);
            Map<String, Object> metric = firstMetric(result);
            assertEquals("tool_permission", metric.get("name"));
            assertEquals("fail", metric.get("status"));
            assertEquals(0.0, metric.get("score"));

            Map<String, Object> check = firstCheck(result, "tool_permission");
            assertEquals("metric", check.get("layer"));
            assertEquals("hard", check.get("level"));
            assertFalse(Boolean.TRUE.equals(check.get("passed")));
        }
    }

    @Test
    void aSkippedMetricIsRecordedButDoesNotProduceACheck(@TempDir Path root) {
        ProjectAdapter adapter = adapter(List.of(new TraceEvent("agent", "reply")));
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            Map<String, Object> summary = run(adapter, store, "metric-skip");

            assertEquals(0, integer(summary.get("hard_failures")));
            Map<String, Object> result = firstResult(summary);
            Map<String, Object> metric = firstMetric(result);
            assertEquals("skip", metric.get("status"));
            assertTrue(result.get("checks").toString().contains("structure"));
            assertFalse(result.get("checks").toString().contains("tool_permission"));
        }
    }

    @Test
    void reusesCachedMetricResultsAcrossRuns(@TempDir Path root) throws Exception {
        ProjectAdapter adapter = adapter(List.of(tool("shell")));
        FileMetricCache cache = new FileMetricCache(root.resolve("metric-cache"));
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            Map<String, Object> first = run(adapter, store, "metric-cached-first", null);
            Map<String, Object> second = run(adapter, store, "metric-cached-second", cache);
            Map<String, Object> third = run(adapter, store, "metric-cached-third", cache);

            assertEquals(false, firstMetric(firstResult(first)).get("cached"));
            assertEquals(false, firstMetric(firstResult(second)).get("cached"));
            assertEquals(true, firstMetric(firstResult(third)).get("cached"));
        }
        try (Stream<Path> files = Files.list(root.resolve("metric-cache"))) {
            assertEquals(1, files.count());
        }
    }

    private static Map<String, Object> run(ProjectAdapter adapter, ResultStore store, String runId) {
        return run(adapter, store, runId, null);
    }

    private static Map<String, Object> run(
            ProjectAdapter adapter, ResultStore store, String runId, FileMetricCache cache) {
        EvaluationSettings settings = EvaluationSettings.builder()
                .metrics(List.of(new ToolPermissionMetric(Set.of("lookup"), Set.of("shell"))))
                .metricCache(cache)
                .build();
        return new EvaluationEngine(adapter, store, null, settings)
                .runSuite(
                        List.of(new EvalCase("CASE", Map.of(), Map.of(), "default")),
                        "smoke",
                        "online",
                        runId,
                        false);
    }

    private static ProjectAdapter adapter(List<TraceEvent> events) {
        return new ProjectAdapter(
                "test",
                (evalCase, context) -> "handle",
                (handle, evalCase) -> new NormalizedTrace("trace", "done", events, Map.of()),
                List.of(),
                List.of());
    }

    private static TraceEvent tool(String name) {
        return new TraceEvent("tool", "call_start", "ok", 0, null, Map.of("tool_call_name", name));
    }

    private static Map<String, Object> firstResult(Map<String, Object> summary) {
        return asMap(asList(summary.get("results")).get(0));
    }

    private static Map<String, Object> firstMetric(Map<String, Object> result) {
        return asMap(asList(result.get("metrics")).get(0));
    }

    private static Map<String, Object> firstCheck(Map<String, Object> result, String name) {
        for (Object row : asList(result.get("checks"))) {
            Map<String, Object> check = asMap(row);
            if (name.equals(check.get("name"))) {
                return check;
            }
        }
        throw new AssertionError("missing check: " + name);
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }
}
