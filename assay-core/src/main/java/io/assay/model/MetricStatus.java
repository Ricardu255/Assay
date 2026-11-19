package io.assay.model;

/**
 * Outcome of one metric measurement.
 *
 * <p>The five states keep "did not run" and "failed to run" distinct from "ran and failed".
 * Only {@link #PASS} and {@link #FAIL} carry a meaningful score; the other states are reported but
 * never enter a pass-rate denominator.
 */
public enum MetricStatus {
    PASS,
    FAIL,
    SKIP,
    ERROR,
    NOT_EVALUATED
}
