package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.runner.RetryPolicy;
import io.assay.store.file.ResultStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adapter retries wait for the configured backoff, and an open breaker skips further calls. */
class EvaluationRetryAndBreakerTest {

    private static ProjectAdapter flaky(AtomicInteger calls, int failBeforeSuccess) {
        return new ProjectAdapter(
                "flaky",
                (evalCase, context) -> {
                    if (calls.incrementAndGet() <= failBeforeSuccess) {
                        throw new IllegalStateException("transient adapter failure");
                    }
                    return Map.of("trace_id", evalCase.caseId());
                },
                (handle, evalCase) -> new NormalizedTrace(
                        String.valueOf(((Map<?, ?>) handle).get("trace_id")),
                        "ok",
                        List.of(),
                        Map.of("route", "ECHO")),
                List.of(Rule.of("route", "fields.route", "route")),
                List.of());
    }

    private static EvalCase evalCase(String id) {
        return new EvalCase(id, Map.of("message", "x"), Map.of("route", "ECHO"), "direct", Map.of(), "retry");
    }

    @Test
    void adapterRetryWaitsForTheConfiguredBackoff(@TempDir Path root) {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy retry = new RetryPolicy(Duration.ofMillis(60), Duration.ofMillis(60), 0);
        long started = System.nanoTime();
        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            summary = engine(flaky(calls, 1), store, 1, retry, CircuitBreaker.disabled())
                    .runSuite(List.of(evalCase("C1")), "retry", "online", "retry-run", false);
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(2, calls.get());
        assertEquals(0, summary.get("hard_failures"));
        assertTrue(elapsedMillis >= 50, "retry should wait for backoff, elapsed=" + elapsedMillis);
        Map<String, Object> policy = asMap(summary.get("retry_policy"));
        assertEquals(60L, policy.get("base_delay_ms"));
    }

    @Test
    void anOpenAdapterBreakerStopsFurtherCalls(@TempDir Path root) {
        AtomicInteger calls = new AtomicInteger();
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30));
        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            summary = engine(flaky(calls, 100), store, 0, RetryPolicy.none(), breaker)
                    .runSuite(
                            List.of(evalCase("C1"), evalCase("C2"), evalCase("C3")),
                            "breaker",
                            "online",
                            "breaker-run",
                            false);
        }

        assertEquals(3, summary.get("case_count"));
        assertEquals(3, summary.get("hard_failures"));
        assertEquals(2, calls.get(), "the third case must fail fast without calling the adapter");
        Map<String, Object> circuit = asMap(summary.get("circuit_breaker"));
        assertEquals("OPEN", asMap(circuit.get("adapter")).get("state"));
    }

    private static EvaluationEngine engine(
            ProjectAdapter adapter,
            ResultStore store,
            int retries,
            RetryPolicy retryPolicy,
            CircuitBreaker adapterBreaker) {
        return new EvaluationEngine(
                adapter,
                store,
                null,
                1,
                retries,
                30,
                false,
                null,
                EvaluationListener.NOOP,
                EvaluationBudget.disabled(),
                retryPolicy,
                adapterBreaker,
                CircuitBreaker.disabled());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
