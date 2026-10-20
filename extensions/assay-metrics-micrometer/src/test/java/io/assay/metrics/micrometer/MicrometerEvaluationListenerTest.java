package io.assay.metrics.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.engine.EvaluationEngine;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.TraceEvent;
import io.assay.store.ResultStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Micrometer adapter observes a real engine run without changing its result. */
class MicrometerEvaluationListenerTest {

    @Test
    void recordsRunCaseAndBatchMetrics(@TempDir Path root) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerEvaluationListener listener = new MicrometerEvaluationListener(registry);

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            Map<String, Object> summary = new EvaluationEngine(
                            adapter(), store, null, 1, 0, 30, false, null, listener)
                    .runSuite(cases(), "suite", "online", "metrics-run", false);

            assertEquals(2, summary.get("case_count"));
        }

        assertEquals(1.0, registry.find("assay.runs.started").counter().count());
        assertEquals(2.0, registry.find("assay.cases").tag("status", "passed").counter().count());
        assertEquals(1.0, registry.find("assay.runs").tag("status", "passed").counter().count());
        assertTrue(registry.find("assay.batches").counter().count() >= 1);
        assertTrue(registry.find("assay.store.append").timer().count() >= 1);
        assertEquals(2, registry.find("assay.case.duration").timers().stream()
                .mapToLong(timer -> timer.count())
                .sum());
        assertNotNull(registry.find("assay.run.duration").timer());
    }

    @Test
    void recordsAFailedRun() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerEvaluationListener listener = new MicrometerEvaluationListener(registry);

        listener.onRunStarted("failed-run", "suite", "online", 2);
        listener.onRunFailed("failed-run", new IllegalStateException("disk full"), 2_000_000);

        assertEquals(1.0, registry.find("assay.runs").tag("status", "failed").counter().count());
        assertEquals(1, registry.find("assay.run.duration").tag("status", "failed").timer().count());
    }

    private static ProjectAdapter adapter() {
        return new ProjectAdapter(
                "metrics-test",
                (evalCase, context) -> Map.of("trace_id", context.runId() + "-" + evalCase.caseId()),
                (handle, evalCase) -> new NormalizedTrace(
                        String.valueOf(((Map<?, ?>) handle).get("trace_id")),
                        "ok",
                        List.of(new TraceEvent("Agent", "answer")),
                        Map.of()),
                List.of(),
                List.of());
    }

    private static List<EvalCase> cases() {
        return List.of(
                new EvalCase("C1", Map.of("message", "one"), Map.of(), "default", Map.of(), "suite"),
                new EvalCase("C2", Map.of("message", "two"), Map.of(), "default", Map.of(), "suite"));
    }
}
