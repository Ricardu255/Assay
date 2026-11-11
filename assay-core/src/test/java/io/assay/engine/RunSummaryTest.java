package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvaluationBudget;
import io.assay.runner.RetryPolicy;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The summary builder is the contract of the report pipeline, so its key order and sections are fixed. */
class RunSummaryTest {

    @Test
    void metadataKeepsAStableKeyOrder() {
        Map<String, Object> metadata = RunSummary.metadata(3, 2, 4, 4, 1, 30.5, true, "runner-a");

        assertEquals(
                List.of("case_count", "workers", "requested_workers", "max_in_flight", "retries",
                        "timeout_seconds", "collect_few_shot", "run_identity"),
                new ArrayList<>(metadata.keySet()));
        assertEquals(3, metadata.get("case_count"));
        assertEquals(2, metadata.get("workers"));
        assertEquals(4, metadata.get("requested_workers"));
        assertEquals(4, metadata.get("max_in_flight"));
        assertEquals(1, metadata.get("retries"));
        assertEquals(30.5, metadata.get("timeout_seconds"));
        assertEquals(true, metadata.get("collect_few_shot"));
        assertEquals("runner-a", metadata.get("run_identity"));
    }

    @Test
    void summaryOmitsDisabledOptionalSections() {
        EvaluationBudget budget = EvaluationBudget.disabled();
        Map<String, Object> summary = RunSummary.summary(
                "run-1",
                "smoke",
                "online",
                "passed",
                new RunSummary.Counts(2, 3, 0, 0, 1),
                budget,
                new BudgetLedger(budget),
                RetryPolicy.none(),
                CircuitBreaker.disabled(),
                CircuitBreaker.disabled(),
                List.of());

        assertEquals(
                List.of("run_id", "suite", "source", "status", "case_count", "requested_case_count",
                        "skipped_case_count", "hard_failures", "soft_warnings", "results"),
                new ArrayList<>(summary.keySet()));
        assertEquals(2, summary.get("case_count"));
        assertEquals(3, summary.get("requested_case_count"));
        assertEquals(0, summary.get("skipped_case_count"));
        assertEquals(0, summary.get("hard_failures"));
        assertEquals(1, summary.get("soft_warnings"));
        assertFalse(summary.containsKey("budget"));
        assertFalse(summary.containsKey("retry_policy"));
        assertFalse(summary.containsKey("circuit_breaker"));
    }

    @Test
    void summaryIncludesBudgetRetryAndBreakerSectionsWhenEnabled() {
        EvaluationBudget budget = new EvaluationBudget(1000, null, 10, 0.0);
        Map<String, Object> summary = RunSummary.summary(
                "run-2",
                "smoke",
                "online",
                "failed",
                new RunSummary.Counts(1, 1, 0, 1, 0),
                budget,
                new BudgetLedger(budget),
                new RetryPolicy(Duration.ofMillis(100), Duration.ofSeconds(1), 0.1),
                new CircuitBreaker(3, Duration.ofSeconds(5)),
                new CircuitBreaker(3, Duration.ofSeconds(5)),
                List.of(Map.of("case_id", "C1")));

        assertTrue(summary.containsKey("budget"));
        assertEquals(1000, ((Map<?, ?>) summary.get("budget")).get("max_total_tokens"));
        assertEquals(100L, ((Map<?, ?>) summary.get("retry_policy")).get("base_delay_ms"));
        assertEquals(0.1, ((Map<?, ?>) summary.get("retry_policy")).get("jitter"));
        assertTrue(summary.containsKey("circuit_breaker"));
        assertEquals(1, summary.get("hard_failures"));
    }
}
