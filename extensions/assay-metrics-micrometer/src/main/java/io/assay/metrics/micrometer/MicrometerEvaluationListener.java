package io.assay.metrics.micrometer;

import io.assay.engine.EvaluationListener;
import io.assay.model.CaseResult;
import io.assay.model.EvalCase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emits run, case, and batch metrics to a Micrometer registry.
 *
 * <p>Tags intentionally exclude run/case ids: those are unbounded and would create a cardinality
 * explosion. Run ids are used only as local map keys for duration bookkeeping.
 */
public final class MicrometerEvaluationListener implements EvaluationListener {

    private final MeterRegistry registry;
    private final Map<String, Long> runStarts = new ConcurrentHashMap<>();
    private final ThreadLocal<Long> caseStarts = new ThreadLocal<>();

    public MicrometerEvaluationListener(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void onRunStarted(String runId, String suite, String source, int caseCount) {
        runStarts.put(runId, System.nanoTime());
        registry.counter("assay.runs.started", "suite", tag(suite), "source", tag(source)).increment();
    }

    @Override
    public void onCaseStarted(EvalCase evalCase) {
        caseStarts.set(System.nanoTime());
    }

    @Override
    public void onCaseFinished(CaseResult result) {
        Long started = caseStarts.get();
        caseStarts.remove();
        String status = result.hardPass() ? "passed" : "failed";
        String suite = tag(result.evalCase().suite());
        registry.counter("assay.cases", "suite", suite, "status", status).increment();
        if (started != null) {
            registry.timer("assay.case.duration", "suite", suite, "status", status)
                    .record(Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
        }
    }

    @Override
    public void onBatchSaved(int count, long durationNanos) {
        registry.counter("assay.batches").increment();
        registry.summary("assay.batch.size").record(count);
        registry.timer("assay.store.append").record(Duration.ofNanos(Math.max(0, durationNanos)));
    }

    @Override
    public void onRunFinished(Map<String, Object> summary, long durationNanos) {
        String runId = String.valueOf(summary.getOrDefault("run_id", ""));
        Long started = runStarts.remove(runId);
        String status = String.valueOf(summary.getOrDefault("status", "unknown"));
        String suite = tag(String.valueOf(summary.getOrDefault("suite", "unknown")));
        String source = tag(String.valueOf(summary.getOrDefault("source", "unknown")));
        registry.counter("assay.runs", "suite", suite, "source", source, "status", status).increment();
        if (started != null) {
            registry.timer("assay.run.duration", "suite", suite, "source", source, "status", status)
                    .record(Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
        }
    }

    private static String tag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }
}
