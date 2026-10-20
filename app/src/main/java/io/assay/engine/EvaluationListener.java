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

    default void onCaseStarted(EvalCase evalCase) {
    }

    default void onCaseFinished(CaseResult result) {
    }

    default void onBatchSaved(int count, long durationNanos) {
    }

    default void onRunFinished(Map<String, Object> summary, long durationNanos) {
    }
}
