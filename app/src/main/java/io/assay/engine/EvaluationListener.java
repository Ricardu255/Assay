package io.assay.engine;

import io.assay.model.CaseResult;
import io.assay.model.EvalCase;
import java.util.Map;

/**
 * Optional observation hooks for one evaluation run.
 *
 * <p>The core emits these events and otherwise stays dependency-free. Implementations must be
 * thread-safe: case callbacks arrive from the worker pool. Callbacks must not throw; a listener is
 * an observer, never part of the evaluation decision.
 */
public interface EvaluationListener {

    /** A listener that ignores every event. */
    EvaluationListener NOOP = new EvaluationListener() {
    };

    default void onRunStarted(String runId, String suite, String source, int caseCount) {
    }

    /**
     * Called on a worker thread with the owning run id, so tracing implementations can parent a
     * case span to its run. The default keeps older listeners working through {@link
     * #onCaseStarted(EvalCase)}.
     */
    default void onCaseStarted(String runId, EvalCase evalCase) {
        onCaseStarted(evalCase);
    }

    default void onCaseStarted(EvalCase evalCase) {
    }

    default void onCaseFinished(CaseResult result) {
    }

    default void onBatchSaved(int count, long durationNanos) {
    }

    default void onRunFinished(Map<String, Object> summary, long durationNanos) {
    }

    /** Called only after {@link #onRunStarted} succeeded and the run failed before finishing. */
    default void onRunFailed(String runId, RuntimeException error, long durationNanos) {
    }
}
