package io.assay.model;

import io.assay.json.Jsonable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.assay.support.Immutable;

/** Gate configuration shared by {@code evolve} and {@code evolve-auto}. */
public record EvolutionPolicy(
        boolean requireRegressionPass,
        boolean requireHoldoutPass,
        int maxRegressionSoftWarningIncrease,
        double maxHoldoutHardPassDrop,
        int maxHoldoutSoftWarningIncrease,
        boolean requireImprovement,
        List<MetricObjective> objectives,
        List<ScenarioGate> scenarioGates)
        implements Jsonable {

    public EvolutionPolicy {
        Values.nonNegative("max_regression_soft_warning_increase", maxRegressionSoftWarningIncrease);
        Values.rate("max_holdout_hard_pass_drop", maxHoldoutHardPassDrop);
        Values.nonNegative("max_holdout_soft_warning_increase", maxHoldoutSoftWarningIncrease);
        objectives = objectives == null ? List.of() : Immutable.list(objectives);
        scenarioGates = scenarioGates == null ? List.of() : Immutable.list(scenarioGates);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (MetricObjective objective : objectives) {
            if (!names.add(objective.name())) {
                throw new IllegalArgumentException("duplicate objective name: " + objective.name());
            }
        }
    }

    public EvolutionPolicy() {
        this(true, false, 0, 0, 0, true, List.of(), List.of());
    }

    public EvolutionPolicy(boolean requireHoldoutPass, List<MetricObjective> objectives) {
        this(true, requireHoldoutPass, 0, 0, 0, true, objectives, List.of());
    }

    public EvolutionPolicy(
            List<MetricObjective> objectives, List<ScenarioGate> scenarioGates, double maxHoldoutHardPassDrop) {
        this(true, false, 0, maxHoldoutHardPassDrop, 0, true, objectives, scenarioGates);
    }

    public static EvolutionPolicy fromDict(Map<String, Object> value) {
        List<MetricObjective> objectives = new ArrayList<>();
        if (value.containsKey("objectives") && !(value.get("objectives") instanceof List<?>)) {
            throw new IllegalArgumentException("objectives must be an array");
        }
        if (value.get("objectives") instanceof List<?> items) {
            for (Object item : items) {
                objectives.add(MetricObjective.fromDict(EvalCase.asMap(item)));
            }
        }
        List<ScenarioGate> gates = new ArrayList<>();
        if (value.containsKey("scenario_gates") && !(value.get("scenario_gates") instanceof List<?>)) {
            throw new IllegalArgumentException("scenario_gates must be an array");
        }
        if (value.get("scenario_gates") instanceof List<?> items) {
            for (Object item : items) {
                gates.add(ScenarioGate.fromDict(EvalCase.asMap(item)));
            }
        }
        return new EvolutionPolicy(
                Values.booleanField(value, "require_regression_pass", true),
                Values.booleanField(value, "require_holdout_pass", false),
                Values.intField(value, "max_regression_soft_warning_increase", 0),
                Values.doubleField(value, "max_holdout_hard_pass_drop", 0),
                Values.intField(value, "max_holdout_soft_warning_increase", 0),
                Values.booleanField(value, "require_improvement", true),
                objectives,
                gates);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("require_regression_pass", requireRegressionPass);
        result.put("require_holdout_pass", requireHoldoutPass);
        result.put("max_regression_soft_warning_increase", maxRegressionSoftWarningIncrease);
        result.put("max_holdout_hard_pass_drop", maxHoldoutHardPassDrop);
        result.put("max_holdout_soft_warning_increase", maxHoldoutSoftWarningIncrease);
        result.put("require_improvement", requireImprovement);
        List<Object> objectiveRows = new ArrayList<>(objectives.size());
        for (MetricObjective objective : objectives) {
            objectiveRows.add(objective.toJson());
        }
        result.put("objectives", objectiveRows);
        List<Object> gateRows = new ArrayList<>(scenarioGates.size());
        for (ScenarioGate gate : scenarioGates) {
            gateRows.add(gate.toJson());
        }
        result.put("scenario_gates", gateRows);
        return result;
    }
}
