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
        roles = roles == null ? List.of("regression", "holdout") : Immutable.list(roles);
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
        int minimumCaseCount = Values.toInt(value.get("minimum_case_count"), 1);
        Double minimumHardPassRate =
                value.get("minimum_hard_pass_rate") == null
                        ? null
                        : Values.toDouble(value.get("minimum_hard_pass_rate"), 0);
        double maximumHardPassRateDrop = Values.toDouble(value.get("maximum_hard_pass_rate_drop"), 0);
        int maximumSoftWarningIncrease = Values.toInt(value.get("maximum_soft_warning_increase"), 0);
        if (minimumCaseCount < 1) {
            throw new IllegalArgumentException("scenario gate minimum_case_count must be at least 1");
        }
        if (minimumHardPassRate != null && (minimumHardPassRate < 0 || minimumHardPassRate > 1)) {
            throw new IllegalArgumentException(
                    "scenario gate minimum_hard_pass_rate must be between 0 and 1");
        }
        if (maximumHardPassRateDrop < 0 || maximumHardPassRateDrop > 1) {
            throw new IllegalArgumentException(
                    "scenario gate maximum_hard_pass_rate_drop must be between 0 and 1");
        }
        if (maximumSoftWarningIncrease < 0) {
            throw new IllegalArgumentException(
                    "scenario gate maximum_soft_warning_increase must not be negative");
        }
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
