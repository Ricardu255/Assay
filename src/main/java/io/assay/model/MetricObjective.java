package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One numeric objective an evolution policy optimises or protects.
 *
 * <p>{@code maximum} is an absolute ceiling on the candidate, independent of the baseline; the
 * remaining thresholds describe how much the candidate may regress relative to the baseline.
 */
public record MetricObjective(
        String name,
        String metric,
        String direction,
        String aggregation,
        double minimumImprovement,
        double maximumRegression,
        boolean required,
        Double maximum)
        implements Jsonable {

    public MetricObjective {
        if (name == null || name.isBlank() || metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("objective requires name and metric");
        }
        direction = direction == null ? "maximize" : direction;
        aggregation = aggregation == null ? "mean" : aggregation;
        if (!List.of("maximize", "minimize").contains(direction)) {
            throw new IllegalArgumentException("unsupported objective direction: " + direction);
        }
        if (!List.of("mean", "sum", "min", "max", "p50", "p95", "p99").contains(aggregation)) {
            throw new IllegalArgumentException("unsupported objective aggregation: " + aggregation);
        }
        Values.nonNegative("minimum_improvement", minimumImprovement);
        Values.nonNegative("maximum_regression", maximumRegression);
        if (maximum != null) {
            Values.nonNegative("maximum", maximum);
        }
    }

    public MetricObjective(
            String name,
            String metric,
            String direction,
            String aggregation,
            double minimumImprovement,
            double maximumRegression,
            boolean required) {
        this(name, metric, direction, aggregation, minimumImprovement, maximumRegression, required, null);
    }

    public MetricObjective(
            String name, String metric, String direction, String aggregation, double minimumImprovement) {
        this(name, metric, direction, aggregation, minimumImprovement, 0, true, null);
    }

    public MetricObjective(String name, String metric, double minimumImprovement) {
        this(name, metric, "maximize", "mean", minimumImprovement, 0, true, null);
    }

    public MetricObjective(String name, String metric) {
        this(name, metric, "maximize", "mean", 0, 0, true, null);
    }

    public static MetricObjective fromDict(Map<String, Object> value) {
        String name = EvalCase.str(value.get("name"));
        String metric = EvalCase.str(value.get("metric"));
        if (name.isEmpty() || metric.isEmpty()) {
            throw new IllegalArgumentException("objective requires name and metric");
        }
        String direction = String.valueOf(value.getOrDefault("direction", "maximize"));
        String aggregation = String.valueOf(value.getOrDefault("aggregation", "mean"));
        Double maximum = value.get("maximum") == null
                ? null
                : Values.doubleField(value, "maximum", 0);
        return new MetricObjective(
                name,
                metric,
                direction,
                aggregation,
                Values.doubleField(value, "minimum_improvement", 0),
                Values.doubleField(value, "maximum_regression", 0),
                Values.booleanField(value, "required", true),
                maximum);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("metric", metric);
        result.put("direction", direction);
        result.put("aggregation", aggregation);
        result.put("minimum_improvement", minimumImprovement);
        result.put("maximum_regression", maximumRegression);
        result.put("required", required);
        result.put("maximum", maximum);
        return result;
    }
}
