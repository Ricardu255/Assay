package io.assay.engine;

import io.assay.json.Json;
import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import io.assay.model.Values;
import io.assay.rules.Rules;
import io.assay.runner.RetryPolicy;
import io.assay.store.Results;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs one suite of cases and records the outcome.
 *
 * <p>Cases execute on a bounded worker pool; the in-flight window is twice the worker count so a slow
 * case never starves the queue. Results are written in bounded batches, which means an abrupt exit can
 * only ever lose the current batch — resume replays it from the case-identity manifest.
 */
public final class EvaluationEngine {

    private static final int BATCH_SIZE_CAP = 32;

    private final ProjectAdapter adapter;
    private final Results store;
    private final LlmReviewer reviewer;
    private final int requestedWorkers;
    private final int workers;
    private final int maxInFlight;
    private final int retries;
    private final double timeoutSeconds;
    private final boolean collectFewShot;
    private final String runIdentity;
    private final RunNotifier notifier;
    private final EvaluationBudget budget;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker adapterBreaker;
    private final CircuitBreaker reviewerBreaker;

    public EvaluationEngine(ProjectAdapter adapter, Results store) {
        this(adapter, store, null, EvaluationSettings.defaults(1, 0, 30, false, null));
    }

    public EvaluationEngine(
            ProjectAdapter adapter,
            Results store,
            LlmReviewer reviewer,
            int workers,
            int retries,
            double timeoutSeconds,
            boolean collectFewShot,
            String runIdentity) {
        this(
                adapter,
                store,
                reviewer,
                EvaluationSettings.defaults(workers, retries, timeoutSeconds, collectFewShot, runIdentity));
    }

    public EvaluationEngine(
            ProjectAdapter adapter,
            Results store,
            LlmReviewer reviewer,
            int workers,
            int retries,
            double timeoutSeconds,
            boolean collectFewShot,
            String runIdentity,
            EvaluationListener listener) {
        this(adapter, store, reviewer, new EvaluationSettings(
                workers,
                retries,
                timeoutSeconds,
                collectFewShot,
                runIdentity,
                listener,
                EvaluationBudget.disabled(),
                RetryPolicy.none(),
                CircuitBreaker.disabled(),
                CircuitBreaker.disabled()));
    }

    public EvaluationEngine(
            ProjectAdapter adapter,
            Results store,
            LlmReviewer reviewer,
            int workers,
            int retries,
            double timeoutSeconds,
            boolean collectFewShot,
            String runIdentity,
            EvaluationListener listener,
            EvaluationBudget budget) {
        this(adapter, store, reviewer, new EvaluationSettings(
                workers,
                retries,
                timeoutSeconds,
                collectFewShot,
                runIdentity,
                listener,
                budget,
                RetryPolicy.none(),
                CircuitBreaker.disabled(),
                CircuitBreaker.disabled()));
    }

    public EvaluationEngine(
            ProjectAdapter adapter,
            Results store,
            LlmReviewer reviewer,
            int workers,
            int retries,
            double timeoutSeconds,
            boolean collectFewShot,
            String runIdentity,
            EvaluationListener listener,
            EvaluationBudget budget,
            RetryPolicy retryPolicy,
            CircuitBreaker adapterBreaker,
            CircuitBreaker reviewerBreaker) {
        this(adapter, store, reviewer, new EvaluationSettings(
                workers,
                retries,
                timeoutSeconds,
                collectFewShot,
                runIdentity,
                listener,
                budget,
                retryPolicy,
                adapterBreaker,
                reviewerBreaker));
    }

    /** Creates an engine from an externally built settings object. */
    public EvaluationEngine(ProjectAdapter adapter, Results store, LlmReviewer reviewer, EvaluationSettings settings) {
        this.adapter = adapter;
        this.store = store;
        this.reviewer = reviewer;
        this.requestedWorkers = settings.workers();
        this.workers =
                adapter.maxConcurrency() != null
                        ? Math.min(this.requestedWorkers, adapter.maxConcurrency())
                        : this.requestedWorkers;
        this.maxInFlight = this.workers * 2;
        this.retries = settings.retries();
        this.timeoutSeconds = settings.timeoutSeconds();
        this.collectFewShot = settings.collectFewShot();
        this.runIdentity = settings.runIdentity();
        this.notifier = new RunNotifier(settings.listener());
        this.budget = settings.budget();
        this.retryPolicy = settings.retryPolicy();
        this.adapterBreaker = settings.adapterBreaker();
        this.reviewerBreaker = settings.reviewerBreaker();
    }

    public int requestedWorkers() {
        return requestedWorkers;
    }

    public int workers() {
        return workers;
    }

    public int maxInFlight() {
        return maxInFlight;
    }

    /** The store this engine records into; the evolution engine needs it for its audit record. */
    public Results store() {
        return store;
    }

    /** The configured run budget; a disabled budget is a no-op. */
    public EvaluationBudget budget() {
        return budget;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public CircuitBreaker adapterBreaker() {
        return adapterBreaker;
    }

    public CircuitBreaker reviewerBreaker() {
        return reviewerBreaker;
    }

    private NormalizedTrace readOneTrace(EvalCase evalCase, String runId, String source, int attempt) {
        notifier.stageStarted(runId, EvaluationListener.Stage.ADAPTER, evalCase);
        long started = System.nanoTime();
        try {
            NormalizedTrace trace = readOneTraceUnchecked(evalCase, runId, source, attempt);
            notifier.stageFinished(
                    runId,
                    EvaluationListener.Stage.ADAPTER,
                    evalCase,
                    System.nanoTime() - started,
                    null,
                    Map.of("attempt", attempt));
            return trace;
        } catch (RuntimeException error) {
            notifier.stageFinished(
                    runId,
                    EvaluationListener.Stage.ADAPTER,
                    evalCase,
                    System.nanoTime() - started,
                    error,
                    Map.of("attempt", attempt));
            throw error;
        }
    }

    private NormalizedTrace readOneTraceUnchecked(EvalCase evalCase, String runId, String source, int attempt) {
        RunContext context = new RunContext(runId, evalCase.suite(), source, attempt, timeoutSeconds);
        Object handle;
        if ("online".equals(source)) {
            handle = adapter.callAgent().call(evalCase, context);
        } else if ("offline".equals(source)) {
            handle =
                    evalCase.metadata().containsKey("trace_ref")
                            ? evalCase.metadata().get("trace_ref")
                            : evalCase.metadata().get("trace");
            // A file-backed adapter normally keys its collection by case id. Supplying the id as the
            // default handle keeps that case zero-code while still letting an adapter read trace_ref.
            if (handle == null) {
                handle = evalCase.caseId();
            }
        } else {
            throw new IllegalArgumentException("unsupported source: " + source);
        }
        NormalizedTrace trace = adapter.readTrace().read(handle, evalCase);
        if (trace == null) {
            throw new IllegalStateException("read_trace must return NormalizedTrace");
        }
        return trace;
    }

    private List<NormalizedTrace> collectTraces(
            EvalCase evalCase, String runId, String source, BudgetLedger ledger) {
        int consistencyRuns = Values.toInt(evalCase.metadata().get("consistency_runs"), 1);
        if (Boolean.TRUE.equals(evalCase.metadata().get("consistency_check"))) {
            consistencyRuns = Math.max(2, consistencyRuns);
        }
        List<NormalizedTrace> traces = new ArrayList<>();
        for (int runIndex = 0; runIndex < consistencyRuns; runIndex++) {
            RuntimeException lastError = null;
            for (int attempt = 0; attempt <= retries; attempt++) {
                if (attempt > 0) {
                    pauseForRetry(runId, evalCase, attempt);
                }
                BudgetLedger.Reservation reservation = reserveBudget(ledger, evalCase);
                try {
                    adapterBreaker.beforeCall();
                    NormalizedTrace trace = readOneTrace(evalCase, runId, source, attempt);
                    adapterBreaker.recordSuccess();
                    ledger.settle(reservation, BudgetUsage.fromTrace(trace));
                    traces.add(trace);
                    lastError = null;
                    break;
                } catch (CircuitOpenException open) {
                    ledger.release(reservation);
                    throw open;
                } catch (BudgetExhaustedException exhausted) {
                    ledger.release(reservation);
                    throw exhausted;
                } catch (RuntimeException error) {
                    adapterBreaker.recordFailure();
                    // A failed call still may have consumed provider tokens; charge the reservation.
                    ledger.settleFailure(reservation);
                    // Adapter boundary: keep the external failure and retry it while budget remains.
                    lastError = error;
                }
            }
            if (lastError != null) {
                throw lastError;
            }
        }
        return traces;
    }

    private void pauseForRetry(String runId, EvalCase evalCase, int attempt) {
        Duration delay = retryPolicy.delayFor(attempt);
        if (delay.isZero()) {
            return;
        }
        notifier.retry(runId, evalCase, attempt, delay.toMillis());
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("evaluation retry interrupted", interrupted);
        }
    }

    /** Reserves before a call so concurrent cases cannot all pass a check that is already spent. */
    private static BudgetLedger.Reservation reserveBudget(BudgetLedger ledger, EvalCase evalCase) {
        if (!ledger.enabled()) {
            return null;
        }
        BudgetLedger.Reservation reservation = ledger.reserve(CostEstimator.tokens(evalCase.payload()));
        if (reservation == null) {
            throw new BudgetExhaustedException("run budget exhausted before case " + evalCase.caseId());
        }
        return reservation;
    }

    private List<CheckResult> evaluateRules(
            String runId, EvalCase evalCase, List<NormalizedTrace> traces, NormalizedTrace trace) {
        notifier.stageStarted(runId, EvaluationListener.Stage.RULES, evalCase);
        long started = System.nanoTime();
        try {
            List<CheckResult> checks =
                    Rules.structureChecks(evalCase, trace, adapter.hardGates(), adapter.softQuality());
            checks.addAll(Rules.behaviorChecks(evalCase, trace));
            List<io.assay.model.Rule> allRules = new ArrayList<>(adapter.hardGates());
            allRules.addAll(adapter.softQuality());
            checks.addAll(Rules.consistencyChecks(traces, allRules));
            checks.addAll(Rules.feedbackChecks(trace));
            notifier.stageFinished(
                    runId,
                    EvaluationListener.Stage.RULES,
                    evalCase,
                    System.nanoTime() - started,
                    null,
                    Map.of("checks", checks.size(), "trace_count", traces.size()));
            return checks;
        } catch (RuntimeException error) {
            notifier.stageFinished(
                    runId,
                    EvaluationListener.Stage.RULES,
                    evalCase,
                    System.nanoTime() - started,
                    error,
                    Map.of("trace_count", traces.size()));
            throw error;
        }
    }

    CaseResult evaluateCase(EvalCase evalCase, String runId, String source) {
        return evaluateCase(evalCase, runId, source, new BudgetLedger(EvaluationBudget.disabled()));
    }

    CaseResult evaluateCase(EvalCase evalCase, String runId, String source, BudgetLedger ledger) {
        notifier.caseStarted(runId, evalCase);
        try {
            List<NormalizedTrace> traces = collectTraces(evalCase, runId, source, ledger);
            NormalizedTrace trace = traces.get(0);
            List<CheckResult> checks = evaluateRules(runId, evalCase, traces, trace);
            CaseResult result = new CaseResult(runId, evalCase.suite(), source, evalCase, trace, checks);

            boolean shouldReview = collectFewShot;
            if (!shouldReview) {
                for (CheckResult check : checks) {
                    if (!check.passed()) {
                        shouldReview = true;
                        break;
                    }
                }
            }
            if (reviewer != null && shouldReview) {
                notifier.stageStarted(runId, EvaluationListener.Stage.LLM, evalCase);
                long llmStarted = System.nanoTime();
                try {
                    reviewerBreaker.beforeCall();
                    result.setLlmReview(
                            reviewer.analyze(
                                    evalCase,
                                    traces,
                                    checks,
                                    collectFewShot && result.hardPass() && result.softWarningCount() == 0));
                    reviewerBreaker.recordSuccess();
                    notifier.stageFinished(
                            runId,
                            EvaluationListener.Stage.LLM,
                            evalCase,
                            System.nanoTime() - llmStarted,
                            null,
                            Map.of("collect_few_shot", collectFewShot));
                } catch (RuntimeException error) {
                    if (!(error instanceof CircuitOpenException)) {
                        reviewerBreaker.recordFailure();
                    }
                    notifier.stageFinished(
                            runId,
                            EvaluationListener.Stage.LLM,
                            evalCase,
                            System.nanoTime() - llmStarted,
                            error,
                            Map.of("collect_few_shot", collectFewShot));
                    // LLM analysis never changes evaluation truth.
                    Map<String, Object> failure = new LinkedHashMap<>();
                    failure.put("error", String.valueOf(error));
                    failure.put("authoritative", false);
                    if (error instanceof CircuitOpenException) {
                        failure.put("circuit_open", true);
                    }
                    result.setLlmReview(failure);
                }
            }

            if (collectFewShot && result.hardPass() && result.softWarningCount() == 0) {
                result.setFewShotCandidate(fewShotCandidate(evalCase, trace, result.llmReview()));
            }
            notifier.caseFinished(result);
            return result;
        } catch (BudgetExhaustedException exhausted) {
            notifier.caseSkipped(runId, evalCase, exhausted.getMessage());
            return null;
        } catch (RuntimeException error) {
            String message = messageOf(error);
            CaseResult failed = new CaseResult(
                    runId,
                    evalCase.suite(),
                    source,
                    evalCase,
                    null,
                    List.of(
                            new CheckResult(
                                    "structure",
                                    "hard",
                                    "execution_error",
                                    false,
                                    message,
                                    "successful execution and readable trace",
                                    "framework or adapter execution failed")),
                    message);
            notifier.caseFinished(failed);
            return failed;
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

    /** A failure is recorded by its message; the exception class adds nothing a case report can use. */
    private static String messageOf(RuntimeException error) {
        return error.getMessage() == null ? "" : error.getMessage();
    }

    private static Map<String, Object> fewShotCandidate(
            EvalCase evalCase, NormalizedTrace trace, Map<String, Object> llmReview) {
        List<Object> decisionPath = new ArrayList<>(trace.events().size());
        for (TraceEvent event : trace.events()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("module", event.module());
            step.put("action", event.action());
            decisionPath.add(step);
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("status", "pending_human_review");
        candidate.put("source_case_id", evalCase.caseId());
        candidate.put("target_type", trace.targetType());
        candidate.put("target_id", trace.targetId());
        candidate.put("target_version", trace.targetVersion());
        candidate.put("scenario", evalCase.scenario());
        candidate.put("input", evalCase.payload());
        candidate.put("decision_path", decisionPath);
        candidate.put("preferred_output", trace.finalOutput());
        candidate.put("llm_candidate", llmReview.get("few_shot_candidate"));
        return candidate;
    }

    public Map<String, Object> runSuite(List<EvalCase> cases, String suite) {
        return runSuite(cases, suite, "online", null, false);
    }

    public Map<String, Object> runSuite(
            List<EvalCase> cases, String suite, String source, String runId, boolean resume) {
        Map<String, String> caseManifest = caseManifest(cases);
        if (resume && runIdentity == null) {
            throw new IllegalArgumentException("resume requires a stable run_identity for the adapter and target");
        }
        String effectiveRunId = runId != null ? runId : suite + "-" + shortId();
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
                collectFewShot, runIdentity);
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
        completion.submit(() -> evaluateCase(next, runId, source, ledger));
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

    private static final class WorkState {
        private int inFlight;
        private int skippedCases;
        private boolean budgetExhausted;
    }

    /** A failing stage stops the release; later stages would only add noise. */
    public Map<String, Object> runRelease(List<SuiteCases> suites, String source, String releaseId) {
        String effectiveReleaseId = releaseId != null ? releaseId : "release-" + shortId();
        List<Map<String, Object>> stages = new ArrayList<>();
        boolean failed = false;
        boolean budgetExhausted = false;
        for (SuiteCases suite : suites) {
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

    /** One named stage of a release run. */
    public record SuiteCases(String name, List<EvalCase> cases) {
    }

    /**
     * Hashes each case so resume can prove the dataset did not change between the two runs.
     *
     * <p>Duplicate ids are rejected here rather than silently overwriting, because a duplicate would
     * make the run's case count disagree with the number of distinct cases it evaluated.
     */
    static Map<String, String> caseManifest(List<EvalCase> cases) {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("evaluation suite must not be empty");
        }
        Map<String, String> manifest = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            String caseId = evalCase.caseId().trim();
            if (caseId.isEmpty()) {
                throw new IllegalArgumentException("evaluation case requires a non-empty case_id");
            }
            if (manifest.containsKey(caseId)) {
                throw new IllegalArgumentException("evaluation suite contains duplicate case_id: " + caseId);
            }
            byte[] canonical = Json.canonical(evalCase.toJson()).getBytes(StandardCharsets.UTF_8);
            manifest.put(caseId, sha256Hex(canonical));
        }
        return manifest;
    }

    static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(Character.forDigit((item >> 4) & 0xF, 16));
                builder.append(Character.forDigit(item & 0xF, 16));
            }
            return builder.toString().toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required but unavailable", error);
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String traceIdOf(Map<String, Object> result) {
        Object trace = result.get("trace");
        if (!(trace instanceof Map)) {
            return null;
        }
        Object traceId = Values.asMap(trace).get("trace_id");
        return traceId == null || String.valueOf(traceId).isEmpty() ? null : String.valueOf(traceId);
    }

    private static final class RunState {
        private boolean started;
        private long startedAtNanos;
    }
}
