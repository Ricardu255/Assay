package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.metrics.GevalMetric;
import io.assay.metrics.LlmJudge;
import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.TraceEvent;
import io.assay.store.file.ResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Judge tokens count against the same run budget as adapter calls. */
class MetricBudgetTest {

    @Test
    void judgeUsageExhaustsTheBudgetAndSkipsLaterCases(@TempDir Path root) {
        GevalMetric metric = new GevalMetric("quality", "answer is clear", 0.8, new FakeJudge());
        EvaluationSettings settings = EvaluationSettings.builder()
                .metrics(List.of(metric))
                .budget(new EvaluationBudget(350, null, 0, 0))
                .build();

        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            summary = new EvaluationEngine(adapter(), store, null, settings)
                    .runSuite(
                            List.of(
                                    new EvalCase("C1", Map.of(), Map.of(), "default"),
                                    new EvalCase("C2", Map.of(), Map.of(), "default"),
                                    new EvalCase("C3", Map.of(), Map.of(), "default")),
                            "smoke",
                            "online",
                            "metric-budget",
                            false);
        }

        assertEquals("budget_exhausted", summary.get("status"));
        assertEquals(1, integer(summary.get("skipped_case_count")));
        Map<String, Object> budget = asMap(summary.get("budget"));
        assertEquals(450L, ((Number) budget.get("spent_tokens")).longValue());
        assertEquals(2, ((Number) budget.get("metric_calls")).intValue());
        assertTrue(Boolean.TRUE.equals(budget.get("overrun")));
        assertEquals(2, ((List<?>) summary.get("results")).size());
    }

    @Test
    void resumeSeedsEarlierJudgeUsage(@TempDir Path root) {
        GevalMetric metric = new GevalMetric("quality", "answer is clear", 0.8, new FakeJudge());
        EvaluationSettings settings = EvaluationSettings.builder()
                .metrics(List.of(metric))
                .budget(new EvaluationBudget(400, null, 0, 0))
                .runIdentity("metric-budget-agent")
                .build();
        List<EvalCase> cases = List.of(
                new EvalCase("C1", Map.of(), Map.of(), "default"),
                new EvalCase("C2", Map.of(), Map.of(), "default"),
                new EvalCase("C3", Map.of(), Map.of(), "default"));

        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            new EvaluationEngine(adapter(), store, null, settings)
                    .runSuite(cases.subList(0, 1), "smoke", "online", "metric-resume", false);
            summary = new EvaluationEngine(adapter(), store, null, settings)
                    .runSuite(cases, "smoke", "online", "metric-resume", true);
        }

        assertEquals("budget_exhausted", summary.get("status"));
        assertEquals(1, integer(summary.get("skipped_case_count")));
        Map<String, Object> budget = asMap(summary.get("budget"));
        assertEquals(450L, ((Number) budget.get("spent_tokens")).longValue());
        assertEquals(2, ((Number) budget.get("metric_calls")).intValue());
    }

    private static ProjectAdapter adapter() {
        return new ProjectAdapter(
                "test",
                (evalCase, context) -> "handle",
                (handle, evalCase) -> new NormalizedTrace(
                        "trace-" + evalCase.caseId(),
                        "the answer",
                        List.of(new TraceEvent("agent", "reply")),
                        Map.of("total_tokens", 0)),
                List.of(),
                List.of());
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** Steps on the first call, then a 7/10 score; each call reports 100 input and 50 output tokens. */
    private static final class FakeJudge implements LlmJudge {

        private int calls;

        @Override
        public String model() {
            return "fake-judge";
        }

        @Override
        public Response evaluate(String prompt) {
            calls++;
            Map<String, Object> result = calls == 1
                    ? Map.of("steps", List.of("check clarity"))
                    : Map.of("score", 7, "reason", "partially clear");
            return new Response(result, 100, 50, null, "fake-judge");
        }
    }
}
