package io.assay.model;

import io.assay.json.Jsonable;
import io.assay.support.Immutable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A role-scoped absolute release gate.
 *
 * <p>Metric objectives compare the candidate with the baseline. A quality gate asks a different
 * question: is this candidate acceptable on its own? It can require a minimum hard pass rate, cap the
 * number of regressed cases, and cap critical regressions. {@code minimumSamples} keeps a two-case
 * dataset from producing a confident verdict.
 */
public record QualityGate(
        List<String> roles,
        Double minimumHardPassRate,
        Integer maximumTaskRegressions,
        Integer maximumCriticalTaskRegressions,
        int minimumSamples)
        implements Jsonable {

    private static final List<String> ALLOWED_ROLES = List.of("improvement", "regression", "holdout");

    public QualityGate {
        roles = roles == null ? List.of("regression", "holdout") : Immutable.list(roles);
        if (roles.isEmpty() || roles.stream().anyMatch(role -> !ALLOWED_ROLES.contains(role))) {
            throw new IllegalArgumentException("quality gate has unsupported roles: " + roles);
        }
        if (minimumHardPassRate != null) {
            Values.rate("minimum_hard_pass_rate", minimumHardPassRate);
        }
        if (maximumTaskRegressions != null && maximumTaskRegressions < 0) {
            throw new IllegalArgumentException("quality gate maximum_task_regressions must be non-negative");
        }
        if (maximumCriticalTaskRegressions != null && maximumCriticalTaskRegressions < 0) {
            throw new IllegalArgumentException(
                    "quality gate maximum_critical_task_regressions must be non-negative");
        }
        if (minimumSamples < 1) {
            throw new IllegalArgumentException("quality gate minimum_samples must be at least 1");
        }
        if (minimumHardPassRate == null
                && maximumTaskRegressions == null
                && maximumCriticalTaskRegressions == null) {
            throw new IllegalArgumentException("quality gate requires at least one threshold");
        }
    }

    public static QualityGate fromDict(Map<String, Object> value) {
        List<String> roles = new ArrayList<>();
        Object rawRoles = value.getOrDefault("roles", List.of("regression", "holdout"));
        if (rawRoles instanceof String text) {
            roles.add(text);
        } else if (rawRoles instanceof List<?> items) {
            for (Object item : items) {
                roles.add(String.valueOf(item));
            }
        } else {
            throw new IllegalArgumentException("quality gate roles must be a string or an array");
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("quality gate has unsupported roles: " + roles);
        }
        Double minimumHardPassRate = value.get("minimum_hard_pass_rate") == null
                ? null
                : Values.doubleField(value, "minimum_hard_pass_rate", 0);
        Integer maximumTaskRegressions = value.containsKey("maximum_task_regressions")
                ? Values.intField(value, "maximum_task_regressions", 0)
                : null;
        Integer maximumCriticalTaskRegressions = value.containsKey("maximum_critical_task_regressions")
                ? Values.intField(value, "maximum_critical_task_regressions", 0)
                : null;
        return new QualityGate(
                roles,
                minimumHardPassRate,
                maximumTaskRegressions,
                maximumCriticalTaskRegressions,
                Values.intField(value, "minimum_samples", 1));
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roles", roles);
        result.put("minimum_hard_pass_rate", minimumHardPassRate);
        result.put("maximum_task_regressions", maximumTaskRegressions);
        result.put("maximum_critical_task_regressions", maximumCriticalTaskRegressions);
        result.put("minimum_samples", minimumSamples);
        return result;
    }
}
