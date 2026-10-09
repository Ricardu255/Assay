package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One numeric objective an evolution policy optimises or protects. */
public record MetricObjective(
        String name,
        String metric,
        String direction,
        String aggregation,
        double minimumImprovement,
        double maximumRegression,
        boolean required)
        implements Jsonable {

    public MetricObjective {
        direction = direction == null ? "maximize" : direction;
        aggregation = aggregation == null ? "mean" : aggregation;
    }

    public MetricObjective(
            String name, String metric, String direction, String aggregation, double minimumImprovement) {
        this(name, metric, direction, aggregation, minimumImprovement, 0, true);
    }

    public MetricObjective(String name, String metric, double minimumImprovement) {
        this(name, metric, "maximize", "mean", minimumImprovement, 0, true);
    }

    public MetricObjective(String name, String metric) {
        this(name, metric, "maximize", "mean", 0, 0, true);
    }

    public static MetricObjective fromDict(Map<String, Object> value) {
        String name = EvalCase.str(value.get("name"));
        String metric = EvalCase.str(value.get("metric"));
        if (name.isEmpty() || metric.isEmpty()) {
            throw new IllegalArgumentException("objective requires name and metric");
        }
        String direction = String.valueOf(value.getOrDefault("direction", "maximize"));
        String aggregation = String.valueOf(value.getOrDefault("aggregation", "mean"));
        if (!List.of("maximize", "minimize").contains(direction)) {
            throw new IllegalArgumentException("unsupported objective direction: " + direction);
        }
        if (!List.of("mean", "sum", "min", "max").contains(aggregation)) {
            throw new IllegalArgumentException("unsupported objective aggregation: " + aggregation);
        }
        return new MetricObjective(
                name,
                metric,
                direction,
                aggregation,
                Values.toDouble(value.get("minimum_improvement"), 0),
                Values.toDouble(value.get("maximum_regression"), 0),
                !Boolean.FALSE.equals(value.get("required")));
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
        return result;
    }
}
