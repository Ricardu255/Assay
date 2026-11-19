package io.assay.metrics;

import io.assay.json.Json;
import io.assay.model.MetricResult;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the declarative metric configuration used by the CLI.
 *
 * <p>The file is either {@code {"metrics": [...]}} or a bare array. Each entry names a metric type
 * and its options; unknown types, options, and malformed values fail at startup instead of being
 * silently ignored.
 */
public final class MetricConfig {

    private static final Set<String> ROOT_KEYS = Set.of("metrics");
    private static final Set<String> TOOL_PERMISSION_KEYS =
            Set.of("type", "level", "threshold", "allowed_tools", "denied_tools");
    private static final Set<String> GEVAL_KEYS =
            Set.of("type", "name", "level", "threshold", "criteria", "steps", "strict");
    private static final Set<String> LOOP_DETECTION_KEYS = Set.of("type", "level", "threshold");

    private MetricConfig() {
    }

    /** Reads and parses a metrics file that contains only deterministic metrics. */
    public static List<Metric> load(Path path) {
        return load(path, null);
    }

    /** Reads and parses a metrics file; {@code judge} is required for G-Eval entries. */
    public static List<Metric> load(Path path, LlmJudge judge) {
        if (path == null) {
            return List.of();
        }
        try {
            return parse(Json.parse(AtomicFiles.readText(path)), judge);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("cannot read metrics file " + path + ": " + error.getMessage(), error);
        }
    }

    /** Parses an already decoded JSON value that contains only deterministic metrics. */
    public static List<Metric> parse(Object raw) {
        return parse(raw, null);
    }

    /** Parses an already decoded JSON value; {@code judge} is required for G-Eval entries. */
    public static List<Metric> parse(Object raw, LlmJudge judge) {
        Object entries = raw;
        if (raw instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (!ROOT_KEYS.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException("unknown metric config option: " + key);
                }
            }
            entries = map.get("metrics");
        }
        if (entries == null) {
            return List.of();
        }
        if (!(entries instanceof List<?> rows)) {
            throw new IllegalArgumentException("metrics must be an array");
        }
        List<Metric> metrics = new ArrayList<>(rows.size());
        for (Object row : rows) {
            metrics.add(parseMetric(row, judge));
        }
        return List.copyOf(metrics);
    }

    private static Metric parseMetric(Object raw, LlmJudge judge) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("each metric must be an object");
        }
        String type = text(map.get("type"));
        if (type.isEmpty()) {
            throw new IllegalArgumentException("metric requires a type");
        }
        Set<String> allowed = switch (type) {
            case ToolPermissionMetric.NAME -> TOOL_PERMISSION_KEYS;
            case LoopDetectionMetric.NAME -> LOOP_DETECTION_KEYS;
            case GevalMetric.NAME -> GEVAL_KEYS;
            default -> throw new IllegalArgumentException("unsupported metric type: " + type);
        };
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("unknown metric option: " + key);
            }
        }
        String name = text(map.get("name"));
        Metric metric = switch (type) {
            case ToolPermissionMetric.NAME -> new ToolPermissionMetric(
                    strings(map.get("allowed_tools"), "allowed_tools"),
                    strings(map.get("denied_tools"), "denied_tools"),
                    threshold(map.get("threshold"), 1.0));
            case LoopDetectionMetric.NAME -> new LoopDetectionMetric(threshold(map.get("threshold"), 0.9));
            case GevalMetric.NAME -> {
                if (judge == null) {
                    throw new IllegalArgumentException("geval requires ASSAY_MODEL to be configured");
                }
                boolean strict = strict(map.get("strict"));
                yield new GevalMetric(
                        name.isEmpty() ? GevalMetric.NAME : name,
                        criteria(map.get("criteria")),
                        strings(map.get("steps"), "steps"),
                        threshold(map.get("threshold"), strict ? 1.0 : 0.8),
                        strict,
                        judge);
            }
            default -> throw new IllegalArgumentException("unsupported metric type: " + type);
        };
        String level = text(map.get("level"));
        if (level.isEmpty()) {
            return metric;
        }
        if (!"hard".equals(level) && !"soft".equals(level)) {
            throw new IllegalArgumentException("metric level must be hard or soft: " + level);
        }
        return new ConfiguredMetric(metric, level);
    }

    private static String criteria(Object raw) {
        String criteria = text(raw);
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException("geval requires a non-empty criteria");
        }
        return criteria;
    }

    private static boolean strict(Object raw) {
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException("strict must be a boolean");
        }
        return value;
    }

    private static double threshold(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else {
            try {
                value = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("threshold must be a number", error);
            }
        }
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("threshold must be finite and between 0 and 1");
        }
        return value;
    }

    private static List<String> strings(Object raw, String field) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> rows)) {
            throw new IllegalArgumentException(field + " must be an array of strings");
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object row : rows) {
            String value = text(row);
            if (value.isEmpty()) {
                throw new IllegalArgumentException(field + " must not contain blank values");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** Applies a configured gate level without changing the delegate's measurement. */
    private record ConfiguredMetric(Metric delegate, String level) implements Metric {

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String defaultLevel() {
            return level;
        }

        @Override
        public String fingerprint() {
            String nested = delegate.fingerprint();
            return nested == null ? null : nested + ";level=" + level;
        }

        @Override
        public MetricResult measure(MetricContext context) {
            return delegate.measure(context);
        }
    }
}
