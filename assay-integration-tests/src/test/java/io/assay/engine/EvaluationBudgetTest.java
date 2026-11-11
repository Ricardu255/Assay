package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.store.file.ResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Budget enforcement stops new cases without turning them into hard failures. */
class EvaluationBudgetTest {

    private static ProjectAdapter adapter(Map<String, Object> fields) {
        return new ProjectAdapter(
                "budget",
                (evalCase, context) -> Map.of("trace_id", evalCase.caseId()),
                (handle, evalCase) ->
                        new NormalizedTrace(
                                String.valueOf(((Map<?, ?>) handle).get("trace_id")),
                                "ok",
                                List.of(),
                                fields),
                List.of(Rule.of("route", "fields.route", "route")),
                List.of());
    }

    private static List<EvalCase> cases(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new EvalCase(
                        "C" + index,
                        Map.of("message", "x"),
                        Map.of("route", "ECHO"),
                        "direct",
                        Map.of(),
                        "budget"))
                .toList();
    }

    @Test
    void stopsNewCasesWhenTheTokenBudgetIsSpent(@TempDir Path root) {
        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationBudget budget = new EvaluationBudget(6, null, 0, 0);
            summary = new EvaluationEngine(adapter(Map.of("route", "ECHO", "total_tokens", 2)), store, null,
                            1, 0, 30, false, null, EvaluationListener.NOOP, budget)
                    .runSuite(cases(3), "budget", "online", "token-budget", false);
        }

        assertEquals("budget_exhausted", summary.get("status"));
        assertEquals(2, summary.get("case_count"));
        assertEquals(3, summary.get("requested_case_count"));
        assertEquals(1, summary.get("skipped_case_count"));
        assertEquals(0, summary.get("hard_failures"));

        Map<String, Object> ledger = asMap(summary.get("budget"));
        assertEquals(4L, ledger.get("spent_tokens"));
        assertEquals(2L, ledger.get("remaining_tokens"));
        assertEquals(2, ledger.get("calls"));
        assertTrue((Boolean) ledger.get("exhausted"));
    }

    @Test
    void missingUsageChargesTheReservationInsteadOfZero(@TempDir Path root) {
        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationBudget budget = new EvaluationBudget(10, null, 5, 0);
            summary = new EvaluationEngine(adapter(Map.of("route", "ECHO")), store, null,
                            1, 0, 30, false, null, EvaluationListener.NOOP, budget)
                    .runSuite(cases(3), "budget", "online", "missing-usage", false);
        }

        assertEquals("budget_exhausted", summary.get("status"));
        assertEquals(1, summary.get("case_count"));
        assertEquals(2, summary.get("skipped_case_count"));
        Map<String, Object> ledger = asMap(summary.get("budget"));
        assertEquals(9L, ledger.get("spent_tokens"));
        assertEquals(1, ledger.get("unreported_token_calls"));
        assertEquals(0, ledger.get("unreported_cost_calls"));
    }

    @Test
    void stopsNewCasesWhenTheCostBudgetIsSpent(@TempDir Path root) {
        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationBudget budget = new EvaluationBudget(null, 0.015, 0, 0.01);
            summary = new EvaluationEngine(adapter(Map.of("route", "ECHO", "cost_usd", 0.01)), store, null,
                            1, 0, 30, false, null, EvaluationListener.NOOP, budget)
                    .runSuite(cases(3), "budget", "online", "cost-budget", false);
        }

        assertEquals("budget_exhausted", summary.get("status"));
        assertEquals(1, summary.get("case_count"));
        assertEquals(2, summary.get("skipped_case_count"));
        Map<String, Object> ledger = asMap(summary.get("budget"));
        assertEquals(0.01, (Double) ledger.get("spent_cost_usd"), 1e-9);
        assertEquals(0.005, (Double) ledger.get("remaining_cost_usd"), 1e-9);
    }

    @Test
    void resumeSeedsPreviousSpendAndLetsAHigherBudgetFinishTheRun(@TempDir Path root) {
        Map<String, Object> first;
        Map<String, Object> resumed;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationBudget small = new EvaluationBudget(6, null, 0, 0);
            first = new EvaluationEngine(adapter(Map.of("route", "ECHO", "total_tokens", 2)), store, null,
                            1, 0, 30, false, "budget-v1", EvaluationListener.NOOP, small)
                    .runSuite(cases(3), "budget", "online", "resume-budget", false);

            EvaluationBudget larger = new EvaluationBudget(10, null, 0, 0);
            resumed = new EvaluationEngine(adapter(Map.of("route", "ECHO", "total_tokens", 2)), store, null,
                            1, 0, 30, false, "budget-v1", EvaluationListener.NOOP, larger)
                    .runSuite(cases(3), "budget", "online", "resume-budget", true);
        }

        assertEquals("budget_exhausted", first.get("status"));
        assertEquals(2, first.get("case_count"));
        assertEquals("passed", resumed.get("status"));
        assertEquals(3, resumed.get("case_count"));
        assertEquals(0, resumed.get("skipped_case_count"));
        Map<String, Object> ledger = asMap(resumed.get("budget"));
        assertEquals(6L, ledger.get("spent_tokens"));
        assertEquals(2, ledger.get("seeded_cases"));
    }

    @Test
    void aHardFailureStillWinsOverTheBudgetStatus(@TempDir Path root) {
        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationBudget budget = new EvaluationBudget(6, null, 0, 0);
            summary = new EvaluationEngine(adapter(Map.of("route", "GENERAL", "total_tokens", 2)), store, null,
                            1, 0, 30, false, null, EvaluationListener.NOOP, budget)
                    .runSuite(cases(3), "budget", "online", "mixed", false);
        }

        assertEquals("failed", summary.get("status"));
        assertTrue((Integer) summary.get("hard_failures") > 0);
        assertEquals(1, summary.get("skipped_case_count"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }
}
