package io.assay.model;

import io.assay.json.Jsonable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.assay.support.Immutable;

/** Per-scenario thresholds that stop a small but important regression hiding behind the overall rate. */
public record ScenarioGate(
        String scenario,
        List<String> roles,
        int minimumCaseCount,
        Double minimumHardPassRate,
        double maximumHardPassRateDrop,
        int maximumSoftWarningIncrease)
        implements Jsonable {

    public ScenarioGate {
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("scenario gate requires scenario");
        }
        roles = roles == null ? List.of("regression", "holdout") : Immutable.list(roles);
        if (roles.isEmpty() || roles.stream().anyMatch(role -> !List.of("improvement", "regression", "holdout").contains(role))) {
            throw new IllegalArgumentException("scenario gate has unsupported roles: " + roles);
        }
        if (minimumCaseCount < 1) {
            throw new IllegalArgumentException("scenario gate minimum_case_count must be at least 1");
        }
        if (minimumHardPassRate != null) {
            Values.rate("minimum_hard_pass_rate", minimumHardPassRate);
        }
        Values.rate("maximum_hard_pass_rate_drop", maximumHardPassRateDrop);
        Values.nonNegative("maximum_soft_warning_increase", maximumSoftWarningIncrease);
    }

    public ScenarioGate(String scenario) {
        this(scenario, List.of("regression", "holdout"), 1, null, 0, 0);
    }

    public ScenarioGate(String scenario, List<String> roles, Double minimumHardPassRate) {
        this(scenario, roles, 1, minimumHardPassRate, 0, 0);
    }

    public static ScenarioGate fromDict(Map<String, Object> value) {
        String scenario = EvalCase.str(value.get("scenario"));
        if (scenario.isEmpty()) {
            throw new IllegalArgumentException("scenario gate requires scenario");
        }
        List<String> roles = new ArrayList<>();
        Object rawRoles = value.getOrDefault("roles", List.of("regression", "holdout"));
        if (rawRoles instanceof String text) {
            roles.add(text);
        } else if (rawRoles instanceof List<?> items) {
            for (Object item : items) {
                roles.add(String.valueOf(item));
            }
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("scenario gate has unsupported roles: " + roles);
        }
        for (String role : roles) {
            if (!List.of("improvement", "regression", "holdout").contains(role)) {
                throw new IllegalArgumentException("scenario gate has unsupported roles: " + roles);
            }
        }
        int minimumCaseCount = Values.intField(value, "minimum_case_count", 1);
        Double minimumHardPassRate =
                value.get("minimum_hard_pass_rate") == null
                        ? null
                        : Values.doubleField(value, "minimum_hard_pass_rate", 0);
        double maximumHardPassRateDrop = Values.doubleField(value, "maximum_hard_pass_rate_drop", 0);
        int maximumSoftWarningIncrease = Values.intField(value, "maximum_soft_warning_increase", 0);
        return new ScenarioGate(
                scenario,
                roles,
                minimumCaseCount,
                minimumHardPassRate,
                maximumHardPassRateDrop,
                maximumSoftWarningIncrease);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario);
        result.put("roles", roles);
        result.put("minimum_case_count", minimumCaseCount);
        result.put("minimum_hard_pass_rate", minimumHardPassRate);
        result.put("maximum_hard_pass_rate_drop", maximumHardPassRateDrop);
        result.put("maximum_soft_warning_increase", maximumSoftWarningIncrease);
        return result;
    }
}
