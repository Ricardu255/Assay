package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.assay.metrics.Metric;
import io.assay.metrics.ToolPermissionMetric;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;

/** Settings own the constructor contract that used to live in the engine's 13-argument constructor. */
class EvaluationSettingsTest {

    @Test
    void rejectsInvalidWorkerRetryAndTimeoutValues() {
        IllegalArgumentException workers = assertThrows(IllegalArgumentException.class,
                () -> new EvaluationSettings(0, 0, 30, false, null, null, null, null, null, null));
        assertEquals(
                "workers must be positive and bounded; retries must be non-negative", workers.getMessage());

        IllegalArgumentException retries = assertThrows(IllegalArgumentException.class,
                () -> new EvaluationSettings(1, -1, 30, false, null, null, null, null, null, null));
        assertEquals(
                "workers must be positive and bounded; retries must be non-negative", retries.getMessage());

        IllegalArgumentException zeroTimeout = assertThrows(IllegalArgumentException.class,
                () -> new EvaluationSettings(1, 0, 0, false, null, null, null, null, null, null));
        assertEquals("timeout_seconds must be finite and positive", zeroTimeout.getMessage());

        IllegalArgumentException nanTimeout = assertThrows(IllegalArgumentException.class,
                () -> new EvaluationSettings(1, 0, Double.NaN, false, null, null, null, null, null, null));
        assertEquals("timeout_seconds must be finite and positive", nanTimeout.getMessage());
    }

    @Test
    void coercesOptionalCollaboratorsToDisabledDefaults() {
        EvaluationSettings settings = new EvaluationSettings(2, 1, 10, true, "run-identity", null, null, null, null, null);

        assertSame(EvaluationListener.NOOP, settings.listener());
        assertFalse(settings.budget().enabled());
        assertFalse(settings.retryPolicy().enabled());
        assertFalse(settings.adapterBreaker().enabled());
        assertFalse(settings.reviewerBreaker().enabled());
    }

    @Test
    void builderAppliesDefaultsAndOverrides() {
        EvaluationSettings settings = EvaluationSettings.builder()
                .workers(4)
                .retries(2)
                .timeoutSeconds(5.5)
                .collectFewShot(true)
                .runIdentity("adapter-v2")
                .build();

        assertEquals(4, settings.workers());
        assertEquals(2, settings.retries());
        assertEquals(5.5, settings.timeoutSeconds());
        assertEquals(true, settings.collectFewShot());
        assertEquals("adapter-v2", settings.runIdentity());
        assertSame(EvaluationListener.NOOP, settings.listener());
        assertFalse(settings.budget().enabled());
    }

    @Test
    void derivesTheOuterTimeoutFromAttemptsAndBackoff() {
        EvaluationSettings settings = EvaluationSettings.builder()
                .retries(2)
                .timeoutSeconds(10)
                .retryPolicy(io.assay.runner.RetryPolicy.defaults())
                .build();

        assertEquals(33.0, settings.timeoutBudgetSeconds(), 1e-9);
        assertEquals(10.0, EvaluationSettings.defaults(1, 0, 10, false, null).timeoutBudgetSeconds(), 1e-9);
    }

    @Test
    void carriesConfiguredMetricsAndDefaultsToNone() {
        Metric metric = new ToolPermissionMetric(Set.of("lookup"), Set.of("shell"));
        EvaluationSettings settings = EvaluationSettings.builder().metrics(List.of(metric)).build();

        assertEquals(List.of(metric), settings.metrics());
        assertEquals(List.of(), EvaluationSettings.defaults(1, 0, 30, false, null).metrics());
    }

    @Test
    void defaultsCarriesTheGivenValues() {
        EvaluationSettings settings = EvaluationSettings.defaults(4, 2, 5.5, true, "adapter-v2");

        assertEquals(4, settings.workers());
        assertEquals(2, settings.retries());
        assertEquals(5.5, settings.timeoutSeconds());
        assertEquals(true, settings.collectFewShot());
        assertEquals("adapter-v2", settings.runIdentity());
        assertSame(EvaluationListener.NOOP, settings.listener());
    }
}
