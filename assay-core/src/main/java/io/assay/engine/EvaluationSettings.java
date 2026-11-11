package io.assay.engine;

import io.assay.model.EvaluationBudget;
import io.assay.runner.RetryPolicy;

/**
 * Immutable execution settings for one {@link EvaluationEngine} instance.
 *
 * <p>The record owns validation and null-coercion of the optional collaborators so the engine's
 * constructors only have to supply defaults; behaviour is otherwise unchanged.
 */
public record EvaluationSettings(
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

    public EvaluationSettings {
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

    /** Starts a fluent builder; omitted collaborators fall back to the disabled defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for callers that need more than the convenience constructors expose. */
    public static final class Builder {

        private int workers = 1;
        private int retries;
        private double timeoutSeconds = 30;
        private boolean collectFewShot;
        private String runIdentity;
        private EvaluationListener listener;
        private EvaluationBudget budget;
        private RetryPolicy retryPolicy;
        private CircuitBreaker adapterBreaker;
        private CircuitBreaker reviewerBreaker;

        private Builder() {
        }

        public Builder workers(int workers) {
            this.workers = workers;
            return this;
        }

        public Builder retries(int retries) {
            this.retries = retries;
            return this;
        }

        public Builder timeoutSeconds(double timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder collectFewShot(boolean collectFewShot) {
            this.collectFewShot = collectFewShot;
            return this;
        }

        public Builder runIdentity(String runIdentity) {
            this.runIdentity = runIdentity;
            return this;
        }

        public Builder listener(EvaluationListener listener) {
            this.listener = listener;
            return this;
        }

        public Builder budget(EvaluationBudget budget) {
            this.budget = budget;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder adapterBreaker(CircuitBreaker adapterBreaker) {
            this.adapterBreaker = adapterBreaker;
            return this;
        }

        public Builder reviewerBreaker(CircuitBreaker reviewerBreaker) {
            this.reviewerBreaker = reviewerBreaker;
            return this;
        }

        public EvaluationSettings build() {
            return new EvaluationSettings(
                    workers,
                    retries,
                    timeoutSeconds,
                    collectFewShot,
                    runIdentity,
                    listener,
                    budget,
                    retryPolicy,
                    adapterBreaker,
                    reviewerBreaker);
        }
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
