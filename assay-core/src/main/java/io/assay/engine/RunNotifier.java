package io.assay.engine;

import io.assay.model.CaseResult;
import io.assay.model.EvalCase;
import java.util.Map;

/**
 * Reports evaluation progress to an {@link EvaluationListener} without letting an observer change
 * evaluation truth: every callback is isolated and failures are ignored on purpose.
 */
final class RunNotifier {

    private final EvaluationListener listener;

    RunNotifier(EvaluationListener listener) {
        this.listener = listener == null ? EvaluationListener.NOOP : listener;
    }

    void runStarted(String runId, String suite, String source, int caseCount) {
        try {
            listener.onRunStarted(runId, suite, source, caseCount);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    void caseStarted(String runId, EvalCase evalCase) {
        try {
            listener.onCaseStarted(runId, evalCase);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    void caseFinished(CaseResult result) {
        try {
            listener.onCaseFinished(result);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    void caseSkipped(String runId, EvalCase evalCase, String reason) {
        try {
            listener.onCaseSkipped(runId, evalCase, reason == null ? "run budget exhausted" : reason);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    void retry(String runId, EvalCase evalCase, int attempt, long delayMillis) {
        try {
            listener.onRetry(runId, evalCase, attempt, delayMillis);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    void stageStarted(String runId, EvaluationListener.Stage stage, EvalCase evalCase) {
        try {
            listener.onStageStarted(runId, stage, evalCase);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    void stageFinished(
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

    void batchSaved(String runId, int count, long durationNanos) {
        try {
            listener.onBatchSaved(runId, count, durationNanos);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    void runFinished(Map<String, Object> summary, long durationNanos) {
        try {
            listener.onRunFinished(summary, durationNanos);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }

    void runFailed(String runId, RuntimeException error, long durationNanos) {
        try {
            listener.onRunFailed(runId, error, durationNanos);
        } catch (RuntimeException ignored) {
            // Observers never change evaluation truth.
        }
    }
}
