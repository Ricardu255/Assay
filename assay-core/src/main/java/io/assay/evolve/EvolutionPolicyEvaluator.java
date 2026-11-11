package io.assay.evolve;

import io.assay.model.EvolutionCandidate;
import io.assay.model.EvolutionPolicy;
import io.assay.model.MetricObjective;
import io.assay.model.QualityGate;
import io.assay.model.ScenarioGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies the configured policy to three dataset comparisons and returns a verdict plus evidence. */
final class EvolutionPolicyEvaluator {

    private final EvolutionPolicy policy;

    EvolutionPolicyEvaluator(EvolutionPolicy policy) {
        this.policy = policy == null ? new EvolutionPolicy() : policy;
    }

    /** A verdict plus the evidence that produced it. */
    record Decision(String decision, List<String> reasons) {
    }

    Decision decide(
            EvolutionCandidate change,
            Map<String, Object> baselineRuns,
            Map<String, Object> candidateRuns,
            Map<String, Object> comparisons) {
        List<String> rollback = new ArrayList<>();
        List<String> reject = new ArrayList<>();
        List<String> improvementEvidence = new ArrayList<>();

        checkTargets(change, baselineRuns, candidateRuns, rollback);
        checkRegression(comparisons, rollback);
        checkHoldout(comparisons, rollback);
        checkScenarioGates(comparisons, rollback);
        checkQualityGates(comparisons, rollback);
        checkObjectives(comparisons, rollback, reject, improvementEvidence);
        checkImprovement(comparisons, improvementEvidence);
        return verdict(rollback, reject, improvementEvidence);
    }

    private static void checkTargets(
            EvolutionCandidate change,
            Map<String, Object> baselineRuns,
            Map<String, Object> candidateRuns,
            List<String> rollback) {
        for (String role : EvolutionSupport.ROLES) {
            if (!matchesTarget(EvolutionSupport.asMap(baselineRuns.get(role)), change, change.baselineVersion())
                    || !matchesTarget(
                            EvolutionSupport.asMap(candidateRuns.get(role)), change, change.candidateVersion())) {
                rollback.add(role + ": target identity or version does not match candidate manifest");
            }
            if (EvolutionSupport.incomplete(EvolutionSupport.asMap(baselineRuns.get(role)))) {
                rollback.add(role + ": baseline run is incomplete (budget exhausted)");
            }
            if (EvolutionSupport.incomplete(EvolutionSupport.asMap(candidateRuns.get(role)))) {
                rollback.add(role + ": candidate run is incomplete (budget exhausted)");
            }
        }
    }

    private void checkRegression(Map<String, Object> comparisons, List<String> rollback) {
        Map<String, Object> regression = EvolutionSupport.asMap(comparisons.get("regression"));
        if (policy.requireRegressionPass()
                && EvolutionSupport.intValue(EvolutionSupport.asMap(regression.get("candidate")).get("hard_failures"))
                        > 0) {
            rollback.add("regression: candidate has hard failures");
        }
        if (EvolutionSupport.doubleValue(regression.get("hard_pass_rate_delta")) < 0) {
            rollback.add("regression: hard pass rate decreased");
        }
        if (EvolutionSupport.intValue(regression.get("soft_warning_delta"))
                > policy.maxRegressionSoftWarningIncrease()) {
            rollback.add("regression: soft warnings exceeded the allowed increase");
        }
    }

    private void checkHoldout(Map<String, Object> comparisons, List<String> rollback) {
        Map<String, Object> holdout = EvolutionSupport.asMap(comparisons.get("holdout"));
        if (policy.requireHoldoutPass()
                && EvolutionSupport.intValue(
                                EvolutionSupport.asMap(holdout.get("candidate")).get("hard_failures"))
                        > 0) {
            rollback.add("holdout: candidate has hard failures");
        }
        if (EvolutionSupport.doubleValue(holdout.get("hard_pass_rate_delta"))
                < -policy.maxHoldoutHardPassDrop()) {
            rollback.add("holdout: hard pass rate exceeded the allowed drop");
        }
        if (EvolutionSupport.intValue(holdout.get("soft_warning_delta"))
                > policy.maxHoldoutSoftWarningIncrease()) {
            rollback.add("holdout: soft warnings exceeded the allowed increase");
        }
    }

    private void checkScenarioGates(Map<String, Object> comparisons, List<String> rollback) {
        for (ScenarioGate gate : policy.scenarioGates()) {
            for (String role : gate.roles()) {
                Map<String, Object> scenarios = EvolutionSupport.asMap(
                        EvolutionSupport.asMap(comparisons.get(role)).get("scenarios"));
                Object rawScenario = scenarios.get(gate.scenario());
                Map<String, Object> scenario = rawScenario instanceof Map
                        ? EvolutionSupport.asMap(rawScenario)
                        : null;
                if (scenario == null || scenario.get("candidate") == null) {
                    rollback.add(role + "/" + gate.scenario() + ": configured scenario is missing");
                    continue;
                }
                checkScenarioGate(role, gate, scenario, rollback);
            }
        }
    }

    private static void checkScenarioGate(
            String role, ScenarioGate gate, Map<String, Object> scenario, List<String> rollback) {
        Map<String, Object> candidateMetrics = EvolutionSupport.asMap(scenario.get("candidate"));
        if (EvolutionSupport.intValue(candidateMetrics.get("case_count")) < gate.minimumCaseCount()) {
            rollback.add(role + "/" + gate.scenario() + ": scenario has fewer than "
                    + gate.minimumCaseCount() + " cases");
        }
        if (gate.minimumHardPassRate() != null
                && EvolutionSupport.doubleValue(candidateMetrics.get("hard_pass_rate"))
                        < gate.minimumHardPassRate()) {
            rollback.add(role + "/" + gate.scenario()
                    + ": hard pass rate is below the configured minimum");
        }
        if (EvolutionSupport.doubleValue(scenario.get("hard_pass_rate_delta"))
                < -gate.maximumHardPassRateDrop()) {
            rollback.add(role + "/" + gate.scenario()
                    + ": hard pass rate exceeded the allowed drop");
        }
        if (EvolutionSupport.intValue(scenario.get("soft_warning_delta"))
                > gate.maximumSoftWarningIncrease()) {
            rollback.add(role + "/" + gate.scenario()
                    + ": soft warnings exceeded the allowed increase");
        }
    }

    private void checkQualityGates(Map<String, Object> comparisons, List<String> rollback) {
        for (QualityGate gate : policy.qualityGates()) {
            for (String role : gate.roles()) {
                Map<String, Object> comparison = EvolutionSupport.asMap(comparisons.get(role));
                Map<String, Object> transitions =
                        EvolutionSupport.asMap(comparison.get("case_transitions"));
                int caseCount = EvolutionSupport.intValue(transitions.get("case_count"));
                if (caseCount == 0) {
                    rollback.add(role + ": quality gate has no evaluated cases");
                    continue;
                }
                if (caseCount < gate.minimumSamples()) {
                    rollback.add(role + ": quality gate needs at least " + gate.minimumSamples()
                            + " cases, found " + caseCount);
                    continue;
                }
                checkQualityGate(role, gate, comparison, transitions, rollback);
            }
        }
    }

    private static void checkQualityGate(
            String role,
            QualityGate gate,
            Map<String, Object> comparison,
            Map<String, Object> transitions,
            List<String> rollback) {
        double passRate = EvolutionSupport.doubleValue(
                EvolutionSupport.asMap(comparison.get("candidate")).get("hard_pass_rate"));
        if (gate.minimumHardPassRate() != null && passRate < gate.minimumHardPassRate()) {
            rollback.add(role + ": hard pass rate " + EvolutionSupport.percent(passRate)
                    + " is below the minimum "
                    + EvolutionSupport.percent(gate.minimumHardPassRate()));
        }
        int regressions = EvolutionSupport.intValue(transitions.get("regressed_count"));
        if (gate.maximumTaskRegressions() != null
                && regressions > gate.maximumTaskRegressions()) {
            rollback.add(role + ": " + regressions + " regressed cases exceed the maximum "
                    + gate.maximumTaskRegressions());
        }
        int criticalRegressions = criticalRegressions(transitions);
        if (gate.maximumCriticalTaskRegressions() != null
                && criticalRegressions > gate.maximumCriticalTaskRegressions()) {
            rollback.add(role + ": " + criticalRegressions
                    + " critical regressions exceed the maximum "
                    + gate.maximumCriticalTaskRegressions());
        }
    }

    private void checkObjectives(
            Map<String, Object> comparisons,
            List<String> rollback,
            List<String> reject,
            List<String> improvementEvidence) {
        for (MetricObjective objective : policy.objectives()) {
            for (String role : EvolutionSupport.ROLES) {
                Map<String, Object> comparison = EvolutionSupport.asMap(comparisons.get(role));
                Object baselineValue = EvolutionSupport.asMap(
                                EvolutionSupport.asMap(comparison.get("baseline")).get("objectives"))
                        .get(objective.name());
                Object candidateValue = EvolutionSupport.asMap(
                                EvolutionSupport.asMap(comparison.get("candidate")).get("objectives"))
                        .get(objective.name());
                if (baselineValue == null || candidateValue == null) {
                    if (objective.required()) {
                        rollback.add(role + "/" + objective.name() + ": required metric is missing");
                    }
                    continue;
                }
                checkObjective(
                        role, objective, baselineValue, candidateValue,
                        rollback, reject, improvementEvidence);
            }
        }
    }

    private static void checkObjective(
            String role,
            MetricObjective objective,
            Object baselineValue,
            Object candidateValue,
            List<String> rollback,
            List<String> reject,
            List<String> improvementEvidence) {
        double candidateNumeric = EvolutionSupport.doubleValue(candidateValue);
        if (objective.maximum() != null && candidateNumeric > objective.maximum()) {
            rollback.add(role + "/" + objective.name() + ": " + candidateNumeric
                    + " exceeds the absolute maximum " + objective.maximum());
        }
        double delta = improvement(
                EvolutionSupport.doubleValue(baselineValue), candidateNumeric, objective.direction());
        if (delta < -objective.maximumRegression()) {
            rollback.add(role + "/" + objective.name() + ": exceeded maximum regression");
        }
        if ("improvement".equals(role)) {
            if (delta < objective.minimumImprovement()) {
                reject.add("improvement/" + objective.name() + ": minimum improvement not met");
            } else if (delta > 0) {
                improvementEvidence.add("objective improved: " + objective.name());
            }
        }
    }

    private static void checkImprovement(
            Map<String, Object> comparisons, List<String> improvementEvidence) {
        Map<String, Object> improvement = EvolutionSupport.asMap(comparisons.get("improvement"));
        if (EvolutionSupport.doubleValue(improvement.get("hard_pass_rate_delta")) > 0) {
            improvementEvidence.add("improvement: hard pass rate increased");
        }
        if (EvolutionSupport.intValue(improvement.get("soft_warning_delta")) < 0) {
            improvementEvidence.add("improvement: soft warnings decreased");
        }
    }

    private Decision verdict(
            List<String> rollback, List<String> reject, List<String> improvementEvidence) {
        if (!rollback.isEmpty()) {
            return new Decision("rollback", rollback);
        }
        if (!reject.isEmpty()) {
            return new Decision("reject", reject);
        }
        if (policy.requireImprovement() && improvementEvidence.isEmpty()) {
            return new Decision("reject", List.of("candidate produced no measurable improvement"));
        }
        return new Decision(
                "accept",
                improvementEvidence.isEmpty()
                        ? List.of("candidate satisfied all configured gates")
                        : improvementEvidence);
    }

    private static double improvement(double baseline, double candidate, String direction) {
        return "maximize".equals(direction) ? candidate - baseline : baseline - candidate;
    }

    private static boolean matchesTarget(
            Map<String, Object> summary, EvolutionCandidate candidate, String version) {
        List<Map<String, Object>> traces = new ArrayList<>();
        for (Object rawResult : EvolutionSupport.asList(summary.get("results"))) {
            Object trace = EvolutionSupport.asMap(rawResult).get("trace");
            if (trace instanceof Map) {
                traces.add(EvolutionSupport.asMap(trace));
            }
        }
        if (traces.isEmpty()) {
            return false;
        }
        for (Map<String, Object> trace : traces) {
            if (!Objects.equals(trace.get("target_type"), candidate.targetType())
                    || !Objects.equals(trace.get("target_id"), candidate.targetId())
                    || !Objects.equals(trace.get("target_version"), version)) {
                return false;
            }
        }
        return true;
    }

    private static int criticalRegressions(Map<String, Object> transitions) {
        int count = 0;
        for (Object rawRegression : EvolutionSupport.asList(transitions.get("regressed"))) {
            if (Boolean.TRUE.equals(EvolutionSupport.asMap(rawRegression).get("critical"))) {
                count++;
            }
        }
        return count;
    }
}
