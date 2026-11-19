package io.assay.engine;

import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.MetricResult;
import io.assay.model.ProjectAdapter;
import io.assay.model.Values;
import io.assay.runner.RetryPolicy;
import io.assay.store.Results;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Orchestrates a run: bounded workers, batching, resume, run metadata, and release stages. */
final class RunOrchestrator {

    private static final int BATCH_SIZE_CAP = 32;

    private final ProjectAdapter adapter;
    private final Results store;
    private final RunNotifier notifier;
    private final int requestedWorkers;
    private final int workers;
    private final int maxInFlight;
    private final int retries;
    private final double timeoutSeconds;
    private final double timeoutBudgetSeconds;
    private final boolean collectFewShot;
    private final String runIdentity;
    private final EvaluationBudget budget;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker adapterBreaker;
    private final CircuitBreaker reviewerBreaker;
    private final CaseEvaluator caseEvaluator;

    RunOrchestrator(
            ProjectAdapter adapter,
            Results store,
            RunNotifier notifier,
            int requestedWorkers,
            int workers,
            int maxInFlight,
            int retries,
            double timeoutSeconds,
            double timeoutBudgetSeconds,
            boolean collectFewShot,
            String runIdentity,
            EvaluationBudget budget,
            RetryPolicy retryPolicy,
            CircuitBreaker adapterBreaker,
            CircuitBreaker reviewerBreaker,
            CaseEvaluator caseEvaluator) {
        this.adapter = adapter;
        this.store = store;
        this.notifier = notifier;
        this.requestedWorkers = requestedWorkers;
        this.workers = workers;
        this.maxInFlight = maxInFlight;
        this.retries = retries;
        this.timeoutSeconds = timeoutSeconds;
        this.timeoutBudgetSeconds = timeoutBudgetSeconds;
        this.collectFewShot = collectFewShot;
        this.runIdentity = runIdentity;
        this.budget = budget;
        this.retryPolicy = retryPolicy;
        this.adapterBreaker = adapterBreaker;
        this.reviewerBreaker = reviewerBreaker;
        this.caseEvaluator = caseEvaluator;
    }

    Map<String, Object> runSuite(List<EvalCase> cases, String suite) {
        return runSuite(cases, suite, "online", null, false);
    }

    Map<String, Object> runSuite(
            List<EvalCase> cases, String suite, String source, String runId, boolean resume) {
        Map<String, String> caseManifest = RunIdentity.caseManifest(cases);
        if (resume && runIdentity == null) {
            throw new IllegalArgumentException("resume requires a stable run_identity for the adapter and target");
        }
        String effectiveRunId = runId != null ? runId : suite + "-" + RunIdentity.shortId();
        RunState state = new RunState();
        try (Results.RunLock ignored = store.lockRun(effectiveRunId)) {
            return runSuiteLocked(cases, suite, source, effectiveRunId, resume, caseManifest, state);
        } catch (RuntimeException error) {
            if (state.started) {
                notifier.runFailed(effectiveRunId, error, System.nanoTime() - state.startedAtNanos);
            }
            throw error;
        }
    }

    private Map<String, Object> runSuiteLocked(
            List<EvalCase> cases,
            String suite,
            String source,
            String runId,
            boolean resume,
            Map<String, String> caseManifest,
            RunState state) {
        Map<String, Object> metadata = RunSummary.metadata(
                cases.size(), workers, requestedWorkers, maxInFlight, retries, timeoutSeconds,
                timeoutBudgetSeconds, collectFewShot, runIdentity);
        metadata.put("dataset_hash", RunIdentity.datasetHash(caseManifest));
        store.startRun(runId, adapter.name(), suite, source, metadata, resume, caseManifest);
        long runStarted = System.nanoTime();
        state.started = true;
        state.startedAtNanos = runStarted;
        notifier.runStarted(runId, suite, source, cases.size());

        BudgetLedger ledger = new BudgetLedger(budget);
        seedLedger(ledger, runId, resume);
        List<EvalCase> pending = pendingCases(cases, runId, resume);

        List<CaseResult> batch = new ArrayList<>();
        int batchSize = Math.min(BATCH_SIZE_CAP, maxInFlight);
        WorkState work = new WorkState();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            ExecutorCompletionService<CaseResult> completion = new ExecutorCompletionService<>(pool);
            Iterator<EvalCase> remaining = pending.iterator();
            work.inFlight = submitInitial(completion, remaining, maxInFlight, runId, source, ledger);
            drain(completion, remaining, batch, batchSize, runId, source, ledger, work);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("evaluation interrupted", error);
        } finally {
            pool.shutdownNow();
        }
        if (!batch.isEmpty()) {
            saveBatch(runId, batch);
        }

        List<Map<String, Object>> results = applyTraceIdChecks(runId);
        int hardFailures = 0;
        int softWarnings = 0;
        for (Map<String, Object> result : results) {
            if (!Boolean.TRUE.equals(result.get("hard_pass"))) {
                hardFailures++;
            }
            softWarnings += Values.toInt(result.get("soft_warning_count"), 0);
        }
        String status = hardFailures > 0
                ? "failed"
                : work.budgetExhausted ? "budget_exhausted" : "passed";
        store.finishRun(runId, status);

        Map<String, Object> summary = RunSummary.summary(
                runId,
                suite,
                source,
                status,
                new RunSummary.Counts(
                        results.size(), cases.size(), work.skippedCases, hardFailures, softWarnings),
                budget,
                ledger,
                retryPolicy,
                adapterBreaker,
                reviewerBreaker,
                results);
        notifier.runFinished(summary, System.nanoTime() - runStarted);
        return summary;
    }

    private void seedLedger(BudgetLedger ledger, String runId, boolean resume) {
        if (!(resume && ledger.enabled())) {
            return;
        }
        for (Map<String, Object> result : store.listResults(runId)) {
            Object trace = result.get("trace");
            if (trace instanceof Map<?, ?> traceMap && !traceMap.isEmpty()) {
                ledger.seed(BudgetUsage.fromTraceMap(Values.asMap(trace)));
            }
            for (Object rawMetric : Values.asList(result.get("metrics"))) {
                try {
                    chargeMetricUsage(ledger, List.of(MetricResult.fromJson(Values.asMap(rawMetric))));
                } catch (RuntimeException malformed) {
                    // An older result without a readable metric row must not block a resume.
                }
            }
        }
    }

    private void saveBatch(String runId, List<CaseResult> batch) {
        notifier.stageStarted(runId, EvaluationListener.Stage.STORE, null);
        long started = System.nanoTime();
        try {
            store.saveCases(batch);
            long duration = System.nanoTime() - started;
            notifier.stageFinished(
                    runId,
                    EvaluationListener.Stage.STORE,
                    null,
                    duration,
                    null,
                    Map.of("count", batch.size()));
            notifier.batchSaved(runId, batch.size(), duration);
        } catch (RuntimeException error) {
            notifier.stageFinished(
                    runId,
                    EvaluationListener.Stage.STORE,
                    null,
                    System.nanoTime() - started,
                    error,
                    Map.of("count", batch.size()));
            throw error;
        }
    }

    private static void chargeMetricUsage(BudgetLedger ledger, List<MetricResult> results) {
        if (!ledger.enabled()) {
            return;
        }
        for (MetricResult result : results) {
            if (result.cached() || (result.inputTokens() == 0 && result.outputTokens() == 0)) {
                continue;
            }
            ledger.chargeMetrics(
                    result.inputTokens(),
                    result.outputTokens(),
                    result.costUsd() == null ? null : result.costUsd().doubleValue());
        }
    }

    private List<EvalCase> pendingCases(List<EvalCase> cases, String runId, boolean resume) {
        List<EvalCase> pending = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            if (!(resume && store.hasCase(runId, evalCase.caseId()))) {
                pending.add(evalCase);
            }
        }
        return pending;
    }

    private int submitInitial(
            ExecutorCompletionService<CaseResult> completion,
            Iterator<EvalCase> remaining,
            int limit,
            String runId,
            String source,
            BudgetLedger ledger) {
        int inFlight = 0;
        for (int index = 0; index < limit && remaining.hasNext(); index++) {
            submitNext(completion, remaining, runId, source, ledger);
            inFlight++;
        }
        return inFlight;
    }

    private void submitNext(
            ExecutorCompletionService<CaseResult> completion,
            Iterator<EvalCase> remaining,
            String runId,
            String source,
            BudgetLedger ledger) {
        EvalCase next = remaining.next();
        completion.submit(() -> caseEvaluator.evaluateCase(next, runId, source, ledger));
    }

    private void drain(
            ExecutorCompletionService<CaseResult> completion,
            Iterator<EvalCase> remaining,
            List<CaseResult> batch,
            int batchSize,
            String runId,
            String source,
            BudgetLedger ledger,
            WorkState work) throws InterruptedException {
        while (work.inFlight > 0) {
            Future<CaseResult> finished = completion.take();
            work.inFlight--;
            collect(finished, batch, work);
            flushBatch(runId, batch, batchSize);
            if (!work.budgetExhausted && remaining.hasNext()) {
                submitNext(completion, remaining, runId, source, ledger);
                work.inFlight++;
            }
        }
        if (work.budgetExhausted) {
            while (remaining.hasNext()) {
                remaining.next();
                work.skippedCases++;
            }
        }
    }

    private static void collect(
            Future<CaseResult> finished, List<CaseResult> batch, WorkState work)
            throws InterruptedException {
        try {
            CaseResult result = finished.get();
            if (result == null) {
                work.skippedCases++;
                work.budgetExhausted = true;
            } else {
                batch.add(result);
            }
        } catch (ExecutionException error) {
            if (error.getCause() instanceof BudgetExhaustedException) {
                work.skippedCases++;
                work.budgetExhausted = true;
            } else {
                throw new IllegalStateException("evaluation worker failed", error.getCause());
            }
        }
    }

    private void flushBatch(String runId, List<CaseResult> batch, int batchSize) {
        if (batch.size() >= batchSize) {
            saveBatch(runId, batch);
            batch.clear();
        }
    }

    private List<Map<String, Object>> applyTraceIdChecks(String runId) {
        List<Map<String, Object>> results = store.listResults(runId);
        Map<String, List<String>> traceCases = new LinkedHashMap<>();
        for (Map<String, Object> result : results) {
            String traceId = traceIdOf(result);
            if (traceId != null) {
                traceCases.computeIfAbsent(traceId, key -> new ArrayList<>())
                        .add(String.valueOf(Values.asMap(result.get("case")).get("case_id")));
            }
        }
        boolean duplicates = false;
        for (Map.Entry<String, List<String>> entry : traceCases.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates = true;
                for (String caseId : entry.getValue()) {
                    store.addCaseCheck(
                            runId,
                            caseId,
                            new CheckResult(
                                    "structure",
                                    "hard",
                                    "unique_trace_id",
                                    false,
                                    entry.getKey(),
                                    "unique within the run"));
                }
            }
        }
        return duplicates ? store.listResults(runId) : results;
    }

    private static String traceIdOf(Map<String, Object> result) {
        Object trace = result.get("trace");
        if (!(trace instanceof Map)) {
            return null;
        }
        Object traceId = Values.asMap(trace).get("trace_id");
        return traceId == null || String.valueOf(traceId).isEmpty() ? null : String.valueOf(traceId);
    }

    private static final class WorkState {
        private int inFlight;
        private int skippedCases;
        private boolean budgetExhausted;
    }

    private static final class RunState {
        private boolean started;
        private long startedAtNanos;
    }

    /** A failing stage stops the release; later stages would only add noise. */
    Map<String, Object> runRelease(
            List<EvaluationEngine.SuiteCases> suites, String source, String releaseId) {
        String effectiveReleaseId = releaseId != null ? releaseId : "release-" + RunIdentity.shortId();
        List<Map<String, Object>> stages = new ArrayList<>();
        boolean failed = false;
        boolean budgetExhausted = false;
        for (EvaluationEngine.SuiteCases suite : suites) {
            Map<String, Object> summary =
                    runSuite(suite.cases(), suite.name(), source, effectiveReleaseId + "-" + suite.name(), false);
            stages.add(summary);
            if (Values.toInt(summary.get("hard_failures"), 0) > 0) {
                failed = true;
                break;
            }
            if ("budget_exhausted".equals(summary.get("status"))) {
                budgetExhausted = true;
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("release_id", effectiveReleaseId);
        result.put("status", failed ? "failed" : budgetExhausted ? "budget_exhausted" : "passed");
        result.put("stages", stages);
        return result;
    }
}
