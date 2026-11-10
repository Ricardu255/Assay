package io.assay.engine;

import io.assay.model.EvaluationBudget;
import io.assay.runner.RetryPolicy;

/**
 * Immutable execution settings for one {@link EvaluationEngine} instance.
 *
 * <p>The record owns validation and null-coercion of the optional collaborators so the engine's
 * constructors only have to supply defaults; behaviour is otherwise unchanged.
 */
record EvaluationSettings(
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

    EvaluationSettings {
        if (workers < 1 || workers > Integer.MAX_VALUE / 2 || retries < 0) {
            throw new IllegalArgumentException("workers must be positive and bounded; retries must be non-negative");
        }
        if (!Double.isFinite(timeoutSeconds) || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeout_seconds must be finite and positive");
        }
        listener = listener == null ? EvaluationListener.NOOP : listener;
        budget = budget == null ? EvaluationBudget.disabled() : budget;
        retryPolicy = retryPolicy == null ? RetryPolicy.none() : retryPolicy;
        adapterBreaker = adapterBreaker == null ? CircuitBreaker.disabled() : adapterBreaker;
        reviewerBreaker = reviewerBreaker == null ? CircuitBreaker.disabled() : reviewerBreaker;
    }

    /** Settings used by the convenience constructors: one worker, no retries, no budget or breaker. */
    static EvaluationSettings defaults(
            int workers, int retries, double timeoutSeconds, boolean collectFewShot, String runIdentity) {
        return new EvaluationSettings(
                workers,
                retries,
                timeoutSeconds,
                collectFewShot,
                runIdentity,
                EvaluationListener.NOOP,
                EvaluationBudget.disabled(),
                RetryPolicy.none(),
                CircuitBreaker.disabled(),
                CircuitBreaker.disabled());
    }
}
