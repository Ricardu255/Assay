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
    private final Map<String, RunStart> runStarts = new ConcurrentHashMap<>();
    private final ThreadLocal<Long> caseStarts = new ThreadLocal<>();

    public MicrometerEvaluationListener(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void onRunStarted(String runId, String suite, String source, int caseCount) {
        runStarts.put(runId, new RunStart(tag(suite), tag(source)));
        registry.counter("assay.runs.started", "suite", tag(suite), "source", tag(source)).increment();
    }

    @Override
    public void onCaseStarted(EvalCase evalCase) {
        caseStarts.set(System.nanoTime());
    }

    @Override
    public void onCaseFinished(CaseResult result) {
        recordCase(result.evalCase(), result.hardPass() ? "passed" : "failed");
    }

    @Override
    public void onCaseSkipped(String runId, EvalCase evalCase, String reason) {
        recordCase(evalCase, "budget_exhausted");
    }

    @Override
    public void onRetry(String runId, EvalCase evalCase, int attempt, long delayMillis) {
        String suite = tag(evalCase.suite());
        registry.counter("assay.retries", "suite", suite, "attempt", String.valueOf(attempt)).increment();
        registry.timer("assay.retry.delay", "suite", suite).record(Duration.ofMillis(Math.max(0, delayMillis)));
    }

    private void recordCase(EvalCase evalCase, String status) {
        Long started = caseStarts.get();
        caseStarts.remove();
        String suite = tag(evalCase.suite());
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
        RunStart started = runStarts.remove(runId);
        String status = String.valueOf(summary.getOrDefault("status", "unknown"));
        String suite = started != null
                ? started.suite()
                : tag(String.valueOf(summary.getOrDefault("suite", "unknown")));
        String source = started != null
                ? started.source()
                : tag(String.valueOf(summary.getOrDefault("source", "unknown")));
        recordRun(suite, source, status, durationNanos);
        recordBudget(suite, summary);
    }

    @SuppressWarnings("unchecked")
    private void recordBudget(String suite, Map<String, Object> summary) {
        Object raw = summary.get("budget");
        if (!(raw instanceof Map<?, ?> budget) || !Boolean.TRUE.equals(budget.get("enabled"))) {
            return;
        }
        Object tokens = budget.get("spent_tokens");
        if (tokens instanceof Number number) {
            registry.summary("assay.budget.tokens", "suite", suite).record(number.doubleValue());
        }
        Object cost = budget.get("spent_cost_usd");
        if (cost instanceof Number number) {
            registry.summary("assay.budget.cost_usd", "suite", suite).record(number.doubleValue());
        }
        Object skipped = summary.get("skipped_case_count");
        if (skipped instanceof Number number && number.doubleValue() > 0) {
            registry.counter("assay.budget.exhausted", "suite", suite).increment();
            registry.summary("assay.budget.skipped_cases", "suite", suite).record(number.doubleValue());
        }
    }

    @Override
    public void onRunFailed(String runId, RuntimeException error, long durationNanos) {
        RunStart started = runStarts.remove(runId);
        String suite = started == null ? "unknown" : started.suite();
        String source = started == null ? "unknown" : started.source();
        recordRun(suite, source, "failed", durationNanos);
    }

    private void recordRun(String suite, String source, String status, long durationNanos) {
        registry.counter("assay.runs", "suite", suite, "source", source, "status", status).increment();
        registry.timer("assay.run.duration", "suite", suite, "source", source, "status", status)
                .record(Duration.ofNanos(Math.max(0, durationNanos)));
    }

    private record RunStart(String suite, String source) {
    }

    private static String tag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }
}
