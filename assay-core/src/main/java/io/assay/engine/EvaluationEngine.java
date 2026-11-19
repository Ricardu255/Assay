package io.assay.engine;

import io.assay.metrics.MetricEvaluator;
import io.assay.model.CaseResult;
import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.ProjectAdapter;
import io.assay.runner.RetryPolicy;
import io.assay.store.Results;
import java.util.List;
import java.util.Map;

/**
 * Runs one suite of cases and records the outcome.
 *
 * <p>Cases execute on a bounded worker pool; the in-flight window is twice the worker count so a slow
 * case never starves the queue. Results are written in bounded batches, which means an abrupt exit can
 * only ever lose the current batch — resume replays it from the case-identity manifest.
 *
 * <p>This class is the public facade. Case execution lives in {@link CaseEvaluator}; run
 * orchestration, batching, and release stages live in {@link RunOrchestrator}.
 */
public final class EvaluationEngine {

    private final int requestedWorkers;
    private final int workers;
    private final int maxInFlight;
    private final Results store;
    private final EvaluationBudget budget;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker adapterBreaker;
    private final CircuitBreaker reviewerBreaker;
    private final CaseEvaluator caseEvaluator;
    private final RunOrchestrator orchestrator;

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
    public EvaluationEngine(
            ProjectAdapter adapter, Results store, LlmReviewer reviewer, EvaluationSettings settings) {
        this.requestedWorkers = settings.workers();
        this.workers =
                adapter.maxConcurrency() != null
                        ? Math.min(this.requestedWorkers, adapter.maxConcurrency())
                        : this.requestedWorkers;
        this.maxInFlight = this.workers * 2;
        this.store = store;
        this.budget = settings.budget();
        this.retryPolicy = settings.retryPolicy();
        this.adapterBreaker = settings.adapterBreaker();
        this.reviewerBreaker = settings.reviewerBreaker();
        RunNotifier notifier = new RunNotifier(settings.listener());
        MetricEvaluator metricEvaluator = new MetricEvaluator(settings.metrics(), settings.metricCache());
        this.caseEvaluator = new CaseEvaluator(
                adapter,
                reviewer,
                settings.retries(),
                settings.timeoutSeconds(),
                settings.collectFewShot(),
                notifier,
                settings.retryPolicy(),
                settings.adapterBreaker(),
                settings.reviewerBreaker(),
                metricEvaluator);
        this.orchestrator = new RunOrchestrator(
                adapter,
                store,
                notifier,
                requestedWorkers,
                workers,
                maxInFlight,
                settings.retries(),
                settings.timeoutSeconds(),
                settings.timeoutBudgetSeconds(),
                settings.collectFewShot(),
                settings.runIdentity(),
                settings.budget(),
                settings.retryPolicy(),
                settings.adapterBreaker(),
                settings.reviewerBreaker(),
                caseEvaluator);
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

    CaseResult evaluateCase(EvalCase evalCase, String runId, String source) {
        return caseEvaluator.evaluateCase(evalCase, runId, source);
    }

    CaseResult evaluateCase(EvalCase evalCase, String runId, String source, BudgetLedger ledger) {
        return caseEvaluator.evaluateCase(evalCase, runId, source, ledger);
    }

    public Map<String, Object> runSuite(List<EvalCase> cases, String suite) {
        return orchestrator.runSuite(cases, suite);
    }

    public Map<String, Object> runSuite(
            List<EvalCase> cases, String suite, String source, String runId, boolean resume) {
        return orchestrator.runSuite(cases, suite, source, runId, resume);
    }

    /** A failing stage stops the release; later stages would only add noise. */
    public Map<String, Object> runRelease(List<SuiteCases> suites, String source, String releaseId) {
        return orchestrator.runRelease(suites, source, releaseId);
    }

    /** One named stage of a release run. */
    public record SuiteCases(String name, List<EvalCase> cases) {
    }

    /** Hashes each case so resume can prove the dataset did not change between the two runs. */
    static Map<String, String> caseManifest(List<EvalCase> cases) {
        return RunIdentity.caseManifest(cases);
    }

    /** Order-independent hash of the case set, so two runs can prove they saw the same dataset. */
    static String datasetHash(Map<String, String> caseManifest) {
        return RunIdentity.datasetHash(caseManifest);
    }

    static String sha256Hex(byte[] value) {
        return RunIdentity.sha256Hex(value);
    }
}
