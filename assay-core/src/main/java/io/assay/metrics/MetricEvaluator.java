package io.assay.metrics;

import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.MetricResult;
import io.assay.model.MetricStatus;
import io.assay.model.NormalizedTrace;
import io.assay.support.Immutable;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the configured metrics and projects their outcomes onto checks.
 *
 * <p>Metric failures are contained: a metric that throws becomes an {@code ERROR} result instead of
 * aborting the case. Only {@code PASS}/{@code FAIL} become checks; {@code SKIP} and
 * {@code NOT_EVALUATED} stay visible in the result but never enter a pass-rate denominator.
 */
public final class MetricEvaluator {

    private final List<Metric> metrics;
    private final MetricCache cache;

    public MetricEvaluator(List<Metric> metrics) {
        this(metrics, null);
    }

    public MetricEvaluator(List<Metric> metrics, MetricCache cache) {
        this.metrics = Immutable.list(metrics);
        this.cache = cache;
    }

    public boolean isEmpty() {
        return metrics.isEmpty();
    }

    public List<Metric> metrics() {
        return metrics;
    }

    /** Measures every metric for one case. */
    public Outcome evaluate(EvalCase evalCase, NormalizedTrace trace, List<NormalizedTrace> traces) {
        if (metrics.isEmpty()) {
            return Outcome.EMPTY;
        }
        MetricContext context = new MetricContext(evalCase, trace, traces);
        List<MetricResult> results = new ArrayList<>(metrics.size());
        List<CheckResult> checks = new ArrayList<>(metrics.size());
        for (Metric metric : metrics) {
            String key = keyOf(context, metric);
            MetricResult result = fromCache(key);
            if (result == null) {
                result = measureOne(metric, context);
                remember(key, result);
            }
            results.add(result);
            CheckResult check = checkOf(result, metric);
            if (check != null) {
                checks.add(check);
            }
        }
        return new Outcome(results, checks);
    }

    private String keyOf(MetricContext context, Metric metric) {
        if (cache == null) {
            return null;
        }
        try {
            return MetricCacheKey.of(context, metric);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private MetricResult fromCache(String key) {
        if (key == null) {
            return null;
        }
        try {
            return cache.lookup(key).map(MetricResult::asCached).orElse(null);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void remember(String key, MetricResult result) {
        if (key == null || (result.status() != MetricStatus.PASS && result.status() != MetricStatus.FAIL)) {
            return;
        }
        try {
            cache.store(key, result);
        } catch (RuntimeException ignored) {
            // A cache write failure must never turn a successful measurement into a failed run.
        }
    }

    private static MetricResult measureOne(Metric metric, MetricContext context) {
        try {
            MetricResult result = metric.measure(context);
            return result == null
                    ? MetricResult.error(safeName(metric), "metric returned no result")
                    : result;
        } catch (RuntimeException error) {
            return MetricResult.error(safeName(metric), messageOf(error));
        }
    }

    private static CheckResult checkOf(MetricResult result, Metric metric) {
        return switch (result.status()) {
            case PASS -> new CheckResult(
                    "metric",
                    level(metric),
                    result.name(),
                    true,
                    result.score(),
                    result.threshold(),
                    result.reason());
            case FAIL -> new CheckResult(
                    "metric",
                    level(metric),
                    result.name(),
                    false,
                    result.score(),
                    result.threshold(),
                    result.reason());
            case ERROR -> new CheckResult(
                    "metric", "hard", result.name(), false, null, null, result.reason());
            case SKIP, NOT_EVALUATED -> null;
        };
    }

    private static String level(Metric metric) {
        String declared = metric.defaultLevel();
        return declared != null && "hard".equalsIgnoreCase(declared.trim()) ? "hard" : "soft";
    }

    private static String safeName(Metric metric) {
        try {
            String name = metric.name();
            return name == null || name.isBlank() ? metric.getClass().getSimpleName() : name;
        } catch (RuntimeException error) {
            return metric.getClass().getSimpleName();
        }
    }

    private static String messageOf(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** The results of one evaluation pass plus the checks they produced. */
    public record Outcome(List<MetricResult> results, List<CheckResult> checks) {

        /** A shared empty outcome for engines that have no metrics configured. */
        public static final Outcome EMPTY = new Outcome(List.of(), List.of());

        public Outcome {
            results = Immutable.list(results);
            checks = Immutable.list(checks);
        }

        public boolean isEmpty() {
            return results.isEmpty();
        }
    }
}
