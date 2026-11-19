package io.assay.engine;

import io.assay.metrics.MetricEvaluator;
import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.MetricResult;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import io.assay.model.TraceRedactor;
import io.assay.model.Values;
import io.assay.rules.Rules;
import io.assay.runner.RetryPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates one case: adapter call, retries, rules, metrics, redaction, and optional review. */
final class CaseEvaluator {

    private final ProjectAdapter adapter;
    private final LlmReviewer reviewer;
    private final int retries;
    private final double timeoutSeconds;
    private final boolean collectFewShot;
    private final RunNotifier notifier;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker adapterBreaker;
    private final CircuitBreaker reviewerBreaker;
    private final MetricEvaluator metricEvaluator;

    CaseEvaluator(
            ProjectAdapter adapter,
            LlmReviewer reviewer,
            int retries,
            double timeoutSeconds,
            boolean collectFewShot,
            RunNotifier notifier,
            RetryPolicy retryPolicy,
            CircuitBreaker adapterBreaker,
            CircuitBreaker reviewerBreaker,
            MetricEvaluator metricEvaluator) {
        this.adapter = adapter;
        this.reviewer = reviewer;
        this.retries = retries;
        this.timeoutSeconds = timeoutSeconds;
        this.collectFewShot = collectFewShot;
        this.notifier = notifier;
        this.retryPolicy = retryPolicy;
        this.adapterBreaker = adapterBreaker;
        this.reviewerBreaker = reviewerBreaker;
        this.metricEvaluator = metricEvaluator;
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

    private NormalizedTrace readOneTraceUnchecked(
            EvalCase evalCase, String runId, String source, int attempt) {
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

    private MetricEvaluator.Outcome evaluateMetrics(
            String runId, EvalCase evalCase, List<NormalizedTrace> traces, NormalizedTrace trace) {
        if (metricEvaluator.isEmpty()) {
            return MetricEvaluator.Outcome.EMPTY;
        }
        notifier.stageStarted(runId, EvaluationListener.Stage.METRICS, evalCase);
        long started = System.nanoTime();
        try {
            MetricEvaluator.Outcome outcome = metricEvaluator.evaluate(evalCase, trace, traces);
            notifier.stageFinished(
                    runId,
                    EvaluationListener.Stage.METRICS,
                    evalCase,
                    System.nanoTime() - started,
                    null,
                    Map.of("metrics", outcome.results().size(), "checks", outcome.checks().size()));
            return outcome;
        } catch (RuntimeException error) {
            notifier.stageFinished(
                    runId,
                    EvaluationListener.Stage.METRICS,
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
            EvalCase storedCase = redactedCase(evalCase);
            List<CheckResult> checks = evaluateRules(runId, evalCase, traces, trace);
            MetricEvaluator.Outcome metricOutcome = evaluateMetrics(runId, evalCase, traces, trace);
            chargeMetricUsage(ledger, metricOutcome.results());
            if (!metricOutcome.isEmpty()) {
                checks.addAll(metricOutcome.checks());
            }
            NormalizedTrace storedTrace = TraceRedactor.redact(trace);
            List<CheckResult> storedChecks = redactedChecks(checks);
            CaseResult result =
                    new CaseResult(runId, storedCase.suite(), source, storedCase, storedTrace, storedChecks);
            result.setMetrics(redactedMetrics(metricOutcome.results()));

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
                                    storedCase,
                                    redactedTraces(traces),
                                    storedChecks,
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
                result.setFewShotCandidate(fewShotCandidate(storedCase, storedTrace, result.llmReview()));
            }
            notifier.caseFinished(result);
            return result;
        } catch (BudgetExhaustedException exhausted) {
            notifier.caseSkipped(runId, evalCase, exhausted.getMessage());
            return null;
        } catch (RuntimeException error) {
            String message = TraceRedactor.maskText(messageOf(error));
            CaseResult failed = new CaseResult(
                    runId,
                    evalCase.suite(),
                    source,
                    redactedCase(evalCase),
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

    private static EvalCase redactedCase(EvalCase evalCase) {
        return new EvalCase(
                evalCase.caseId(),
                TraceRedactor.redactValue(evalCase.payload()),
                Values.asMap(TraceRedactor.redactValue(evalCase.expected())),
                evalCase.scenario(),
                Values.asMap(TraceRedactor.redactValue(evalCase.metadata())),
                evalCase.suite());
    }

    private static List<NormalizedTrace> redactedTraces(List<NormalizedTrace> traces) {
        List<NormalizedTrace> redacted = new ArrayList<>(traces.size());
        for (NormalizedTrace trace : traces) {
            redacted.add(TraceRedactor.redact(trace));
        }
        return redacted;
    }

    @SuppressWarnings("unchecked")
    private static List<MetricResult> redactedMetrics(List<MetricResult> metrics) {
        List<MetricResult> redacted = new ArrayList<>(metrics.size());
        for (MetricResult metric : metrics) {
            redacted.add(new MetricResult(
                    metric.name(),
                    metric.status(),
                    metric.score(),
                    metric.threshold(),
                    TraceRedactor.maskText(metric.reason()),
                    (Map<String, Object>) TraceRedactor.redactValue(metric.breakdown()),
                    (List<Map<String, Object>>) TraceRedactor.redactValue(metric.evidence()),
                    metric.inputTokens(),
                    metric.outputTokens(),
                    metric.costUsd(),
                    metric.model(),
                    metric.cached()));
        }
        return redacted;
    }

    private static List<CheckResult> redactedChecks(List<CheckResult> checks) {
        List<CheckResult> redacted = new ArrayList<>(checks.size());
        for (CheckResult check : checks) {
            redacted.add(new CheckResult(
                    check.layer(),
                    check.level(),
                    check.name(),
                    check.passed(),
                    TraceRedactor.redactValue(check.actual()),
                    TraceRedactor.redactValue(check.expected()),
                    TraceRedactor.maskText(check.message()),
                    check.suspectedModules()));
        }
        return redacted;
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
}
