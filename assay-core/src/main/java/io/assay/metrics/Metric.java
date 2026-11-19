package io.assay.metrics;

import io.assay.model.MetricResult;

/**
 * A scoring check over one normalized trace.
 *
 * <p>Metrics are deliberately separate from rules: a rule answers "did this hold?", a metric answers
 * "how well, and why?". The result is mapped onto a check only after measurement, so existing gate
 * semantics keep working unchanged.
 */
public interface Metric {

    /** Stable name used in results, reports, and cache keys. */
    String name();

    /** Gate level used when the result is mapped to a check: {@code hard} or {@code soft}. */
    default String defaultLevel() {
        return "soft";
    }

    /**
     * Cache fingerprint for this metric's configuration.
     *
     * <p>Return {@code null} when results must not be reused (for example when the metric depends on
     * an external service or on wall-clock state). Implementations should include every constructor
     * argument that changes the score.
     */
    default String fingerprint() {
        return null;
    }

    /** Measures one case; implementations must not mutate the trace or the case. */
    MetricResult measure(MetricContext context);
}
