package io.assay.engine;

import io.assay.json.Json;
import io.assay.llm.LlmReviewer;
import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import io.assay.rules.Rules;
import io.assay.store.ResultStore;
import io.assay.store.Results;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

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
    private final EvaluationListener listener;

    public EvaluationEngine(ProjectAdapter adapter, Results store) {
        this(adapter, store, null, 1, 0, 30, false, null);
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
        this(adapter, store, reviewer, workers, retries, timeoutSeconds, collectFewShot, runIdentity,
                EvaluationListener.NOOP);
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
        if (workers < 1 || workers > Integer.MAX_VALUE / 2 || retries < 0) {
            throw new IllegalArgumentException("workers must be positive and bounded; retries must be non-negative");
        }
        if (!Double.isFinite(timeoutSeconds) || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeout_seconds must be finite and positive");
        }
        this.adapter = adapter;
        this.store = store;
        this.reviewer = reviewer;
        this.requestedWorkers = workers;
        this.workers =
                adapter.maxConcurrency() != null
                        ? Math.min(this.requestedWorkers, adapter.maxConcurrency())
                        : this.requestedWorkers;
        this.maxInFlight = this.workers * 2;
        this.retries = retries;
        this.timeoutSeconds = timeoutSeconds;
        this.collectFewShot = collectFewShot;
        this.runIdentity = runIdentity;
        this.listener = listener == null ? EvaluationListener.NOOP : listener;
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

    private NormalizedTrace readOneTrace(EvalCase evalCase, String runId, String source, int attempt) {
        notifyStageStarted(runId, EvaluationListener.Stage.ADAPTER, evalCase);
        long started = System.nanoTime();
        try {
            NormalizedTrace trace = readOneTraceUnchecked(evalCase, runId, source, attempt);
            notifyStageFinished(
                    runId,
                    EvaluationListener.Stage.ADAPTER,
                    evalCase,
                    System.nanoTime() - started,
                    null,
                    Map.of("attempt", attempt));
            return trace;
        } catch (RuntimeException error) {
            notifyStageFinished(
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

    private List<NormalizedTrace> collectTraces(EvalCase evalCase, String runId, String source) {
        int consistencyRuns = intValue(evalCase.metadata().get("consistency_runs"), 1);
        if (Boolean.TRUE.equals(evalCase.metadata().get("consistency_check"))) {
            consistencyRuns = Math.max(2, consistencyRuns);
        }
        List<NormalizedTrace> traces = new ArrayList<>();
        for (int runIndex = 0; runIndex < consistencyRuns; runIndex++) {
            RuntimeException lastError = null;
            for (int attempt = 0; attempt <= retries; attempt++) {
                try {
                    traces.add(readOneTrace(evalCase, runId, source, attempt));
                    lastError = null;
                    break;
                } catch (RuntimeException error) {
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

    private List<CheckResult> evaluateRules(
            String runId, EvalCase evalCase, List<NormalizedTrace> traces, NormalizedTrace trace) {
        notifyStageStarted(runId, EvaluationListener.Stage.RULES, evalCase);
        long started = System.nanoTime();
        try {
            List<CheckResult> checks =
                    Rules.structureChecks(evalCase, trace, adapter.hardGates(), adapter.softQuality());
            checks.addAll(Rules.behaviorChecks(evalCase, trace));
            List<io.assay.model.Rule> allRules = new ArrayList<>(adapter.hardGates());
            allRules.addAll(adapter.softQuality());
            checks.addAll(Rules.consistencyChecks(traces, allRules));
            checks.addAll(Rules.feedbackChecks(trace));
            notifyStageFinished(
                    runId,
                    EvaluationListener.Stage.RULES,
                    evalCase,
                    System.nanoTime() - started,
                    null,
                    Map.of("checks", checks.size(), "trace_count", traces.size()));
            return checks;
        } catch (RuntimeException error) {
            notifyStageFinished(
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
        notifyCaseStarted(runId, evalCase);
        try {
            List<NormalizedTrace> traces = collectTraces(evalCase, runId, source);
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
                notifyStageStarted(runId, EvaluationListener.Stage.LLM, evalCase);
                long llmStarted = System.nanoTime();
                try {
                    result.setLlmReview(
                            reviewer.analyze(
                                    evalCase,
                                    traces,
                                    checks,
                                    collectFewShot && result.hardPass() && result.softWarningCount() == 0));
                    notifyStageFinished(
                            runId,
                            EvaluationListener.Stage.LLM,
                            evalCase,
                            System.nanoTime() - llmStarted,
                            null,
                            Map.of("collect_few_shot", collectFewShot));
                } catch (RuntimeException error) {
                    notifyStageFinished(
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
                    result.setLlmReview(failure);
                }
            }

            if (collectFewShot && result.hardPass() && result.softWarningCount() == 0) {
                result.setFewShotCandidate(fewShotCandidate(evalCase, trace, result.llmReview()));
            }
            notifyCaseFinished(result);
            return result;
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
            notifyCaseFinished(failed);
            return failed;
        }
    }

    private void notifyRunStarted(String runId, String suite, String source, int caseCount) {
        try {
            listener.onRunStarted(runId, suite, source, caseCount);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    private void notifyCaseStarted(String runId, EvalCase evalCase) {
        try {
            listener.onCaseStarted(runId, evalCase);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    private void notifyCaseFinished(CaseResult result) {
        try {
            listener.onCaseFinished(result);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    private void notifyStageStarted(String runId, EvaluationListener.Stage stage, EvalCase evalCase) {
        try {
            listener.onStageStarted(runId, stage, evalCase);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    private void notifyStageFinished(
            String runId,
            EvaluationListener.Stage stage,
            EvalCase evalCase,
            long durationNanos,
            RuntimeException error,
            Map<String, Object> attributes) {
        try {
            listener.onStageFinished(runId, stage, evalCase, durationNanos, error, attributes);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    private void saveBatch(String runId, List<CaseResult> batch) {
        notifyStageStarted(runId, EvaluationListener.Stage.STORE, null);
        long started = System.nanoTime();
        try {
            store.saveCases(batch);
            long duration = System.nanoTime() - started;
            notifyStageFinished(
                    runId,
                    EvaluationListener.Stage.STORE,
                    null,
                    duration,
                    null,
                    Map.of("count", batch.size()));
            notifyBatchSaved(runId, batch.size(), duration);
        } catch (RuntimeException error) {
            notifyStageFinished(
                    runId,
                    EvaluationListener.Stage.STORE,
                    null,
                    System.nanoTime() - started,
                    error,
                    Map.of("count", batch.size()));
            throw error;
        }
    }

    private void notifyBatchSaved(String runId, int count, long durationNanos) {
        try {
            listener.onBatchSaved(runId, count, durationNanos);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    private void notifyRunFinished(Map<String, Object> summary, long durationNanos) {
        try {
            listener.onRunFinished(summary, durationNanos);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    private void notifyRunFailed(String runId, RuntimeException error, long durationNanos) {
        try {
            listener.onRunFailed(runId, error, durationNanos);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
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
                notifyRunFailed(effectiveRunId, error, System.nanoTime() - state.startedAtNanos);
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
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("case_count", cases.size());
        metadata.put("workers", workers);
        metadata.put("requested_workers", requestedWorkers);
        metadata.put("max_in_flight", maxInFlight);
        metadata.put("retries", retries);
        metadata.put("timeout_seconds", timeoutSeconds);
        metadata.put("collect_few_shot", collectFewShot);
        metadata.put("run_identity", runIdentity);
        store.startRun(runId, adapter.name(), suite, source, metadata, resume, caseManifest);
        long runStarted = System.nanoTime();
        state.started = true;
        state.startedAtNanos = runStarted;
        notifyRunStarted(runId, suite, source, cases.size());

        List<EvalCase> pending = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            if (!(resume && store.hasCase(runId, evalCase.caseId()))) {
                pending.add(evalCase);
            }
        }

        List<CaseResult> batch = new ArrayList<>();
        int batchSize = Math.min(BATCH_SIZE_CAP, maxInFlight);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            ExecutorCompletionService<CaseResult> completion = new ExecutorCompletionService<>(pool);
            Iterator<EvalCase> remaining = pending.iterator();
            int inFlight = 0;
            for (int index = 0; index < maxInFlight && remaining.hasNext(); index++) {
                EvalCase next = remaining.next();
                completion.submit(() -> evaluateCase(next, runId, source));
                inFlight++;
            }
            while (inFlight > 0) {
                Future<CaseResult> finished = completion.take();
                batch.add(finished.get());
                inFlight--;
                if (batch.size() >= batchSize) {
                    saveBatch(runId, batch);
                    batch.clear();
                }
                if (remaining.hasNext()) {
                    EvalCase next = remaining.next();
                    completion.submit(() -> evaluateCase(next, runId, source));
                    inFlight++;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("evaluation interrupted", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IllegalStateException("evaluation worker failed", error.getCause());
        } finally {
            pool.shutdownNow();
        }
        if (!batch.isEmpty()) {
            saveBatch(runId, batch);
        }

        List<Map<String, Object>> results = store.listResults(runId);
        Map<String, List<String>> traceCases = new LinkedHashMap<>();
        for (Map<String, Object> result : results) {
            String traceId = traceIdOf(result);
            if (traceId != null) {
                traceCases.computeIfAbsent(traceId, key -> new ArrayList<>())
                        .add(String.valueOf(asMap(result.get("case")).get("case_id")));
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
        if (duplicates) {
            results = store.listResults(runId);
        }

        int hardFailures = 0;
        int softWarnings = 0;
        for (Map<String, Object> result : results) {
            if (!Boolean.TRUE.equals(result.get("hard_pass"))) {
                hardFailures++;
            }
            softWarnings += intValue(result.get("soft_warning_count"), 0);
        }
        String status = hardFailures > 0 ? "failed" : "passed";
        store.finishRun(runId, status);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("suite", suite);
        summary.put("source", source);
        summary.put("status", status);
        summary.put("case_count", results.size());
        summary.put("hard_failures", hardFailures);
        summary.put("soft_warnings", softWarnings);
        summary.put("results", results);
        notifyRunFinished(summary, System.nanoTime() - runStarted);
        return summary;
    }

    /** A failing stage stops the release; later stages would only add noise. */
    public Map<String, Object> runRelease(List<SuiteCases> suites, String source, String releaseId) {
        String effectiveReleaseId = releaseId != null ? releaseId : "release-" + shortId();
        List<Map<String, Object>> stages = new ArrayList<>();
        boolean failed = false;
        for (SuiteCases suite : suites) {
            Map<String, Object> summary =
                    runSuite(suite.cases(), suite.name(), source, effectiveReleaseId + "-" + suite.name(), false);
            stages.add(summary);
            if (intValue(summary.get("hard_failures"), 0) > 0) {
                failed = true;
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("release_id", effectiveReleaseId);
        result.put("status", failed ? "failed" : "passed");
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
            return builder.toString().toUpperCase(java.util.Locale.ROOT);
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
        Object traceId = asMap(trace).get("trace_id");
        return traceId == null || String.valueOf(traceId).isEmpty() ? null : String.valueOf(traceId);
    }

    private static int intValue(Object value) {
        return intValue(value, 1);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static final class RunState {
        private boolean started;
        private long startedAtNanos;
    }
}
