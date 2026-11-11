package io.assay.engine;

import io.assay.model.CaseResult;
import io.assay.model.EvalCase;
import java.util.Map;

/**
 * Reports evaluation progress to an {@link EvaluationListener} without letting an observer change
 * evaluation truth: every callback is isolated and failures are ignored on purpose.
 */
final class RunNotifier {

    private static final System.Logger LOGGER = System.getLogger(RunNotifier.class.getName());

    private final EvaluationListener listener;

    RunNotifier(EvaluationListener listener) {
        this.listener = listener == null ? EvaluationListener.NOOP : listener;
    }

    void runStarted(String runId, String suite, String source, int caseCount) {
        try {
            listener.onRunStarted(runId, suite, source, caseCount);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }

    void caseStarted(String runId, EvalCase evalCase) {
        try {
            listener.onCaseStarted(runId, evalCase);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }

    void caseFinished(CaseResult result) {
        try {
            listener.onCaseFinished(result);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }

    void caseSkipped(String runId, EvalCase evalCase, String reason) {
        try {
            listener.onCaseSkipped(runId, evalCase, reason == null ? "run budget exhausted" : reason);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }

    void retry(String runId, EvalCase evalCase, int attempt, long delayMillis) {
        try {
            listener.onRetry(runId, evalCase, attempt, delayMillis);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }

    void stageStarted(String runId, EvaluationListener.Stage stage, EvalCase evalCase) {
        try {
            listener.onStageStarted(runId, stage, evalCase);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
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
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }

    void batchSaved(String runId, int count, long durationNanos) {
        try {
            listener.onBatchSaved(runId, count, durationNanos);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }

    void runFinished(Map<String, Object> summary, long durationNanos) {
        try {
            listener.onRunFinished(summary, durationNanos);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }

    void runFailed(String runId, RuntimeException error, long durationNanos) {
        try {
            listener.onRunFailed(runId, error, durationNanos);
        } catch (RuntimeException failure) {
            // Observers never change evaluation truth, but a broken observer should be diagnosable.
            LOGGER.log(System.Logger.Level.DEBUG, "evaluation listener callback failed", failure);
        }
    }
}
