package io.assay.evolve;

import io.assay.engine.EvaluationEngine;
import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.model.EvolutionCandidate;
import io.assay.model.EvolutionPolicy;
import io.assay.model.MetricObjective;
import io.assay.model.QualityGate;
import io.assay.model.ScenarioGate;
import io.assay.report.RuleStats;
import io.assay.rules.ValueOps;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Compares a baseline and a candidate against the same three datasets and decides the outcome.
 *
 * <p>The decision is deterministic and fully explained by {@code reasons}: {@code rollback} when the
 * candidate breaks something that was working, {@code reject} when it merely fails to improve on the
 * stated objective, and {@code accept} only when both hold. The LLM never participates here.
 */
public final class EvolutionEngine {

    private static final List<String> ROLES = List.of("improvement", "regression", "holdout");

    private final EvaluationEngine baseline;
    private final EvaluationEngine candidate;
    private final EvolutionPolicy policy;

    public EvolutionEngine(EvaluationEngine baseline, EvaluationEngine candidate, EvolutionPolicy policy) {
        this.baseline = baseline;
        this.candidate = candidate;
        this.policy = policy == null ? new EvolutionPolicy() : policy;
    }

    public static EvolutionCandidate loadCandidate(Path path) {
        return EvolutionCandidate.fromDict(Json.parseObject(AtomicFiles.readText(path)));
    }

    public static EvolutionPolicy loadPolicy(Path path) {
        if (path == null) {
            return new EvolutionPolicy();
        }
        return EvolutionPolicy.fromDict(Json.parseObject(AtomicFiles.readText(path)));
    }

    public Map<String, Object> run(
            EvolutionCandidate change, Map<String, List<EvalCase>> datasets, String source, String experimentId) {
        return run(change, datasets, source, experimentId, false);
    }

    public Map<String, Object> run(
            EvolutionCandidate change,
            Map<String, List<EvalCase>> datasets,
            String source,
            String experimentId,
            boolean resume) {
        List<String> missing = new ArrayList<>();
        for (String role : ROLES) {
            if (!datasets.containsKey(role)) {
                missing.add(role);
            }
        }
        if (!missing.isEmpty()) {
            missing.sort(java.util.Comparator.naturalOrder());
            throw new IllegalArgumentException(
                    "evolution datasets require: " + String.join(", ", missing));
        }
        for (String role : ROLES) {
            if (datasets.get(role).isEmpty()) {
                throw new IllegalArgumentException("evolution datasets must not be empty");
            }
        }

        String effectiveId = experimentId != null ? experimentId : "evolution-" + shortId();
        Map<String, Object> baselineRuns = new LinkedHashMap<>();
        Map<String, Object> candidateRuns = new LinkedHashMap<>();
        Map<String, Object> comparisons = new LinkedHashMap<>();

        for (String role : ROLES) {
            List<EvalCase> cases = datasets.get(role);
            Map<String, Object> baselineSummary =
                    baseline.runSuite(cases, role, source, effectiveId + "-" + role + "-baseline", resume);
            Map<String, Object> candidateSummary =
                    candidate.runSuite(cases, role, source, effectiveId + "-" + role + "-candidate", resume);
            Map<String, Object> baselineMetrics = summarizeMetrics(baselineSummary, policy.objectives());
            Map<String, Object> candidateMetrics = summarizeMetrics(candidateSummary, policy.objectives());
            baselineRuns.put(role, baselineSummary);
            candidateRuns.put(role, candidateSummary);

            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("baseline", baselineMetrics);
            comparison.put("candidate", candidateMetrics);
            comparison.put(
                    "hard_pass_rate_delta",
                    doubleValue(candidateMetrics.get("hard_pass_rate"))
                            - doubleValue(baselineMetrics.get("hard_pass_rate")));
            comparison.put(
                    "soft_warning_delta",
                    intValue(candidateMetrics.get("soft_warning_count"))
                            - intValue(baselineMetrics.get("soft_warning_count")));
            comparison.put(
                    "mean_latency_ms_delta",
                    difference(candidateMetrics.get("mean_latency_ms"), baselineMetrics.get("mean_latency_ms")));
            comparison.put(
                    "p95_latency_ms_delta",
                    difference(candidateMetrics.get("p95_latency_ms"), baselineMetrics.get("p95_latency_ms")));
            comparison.put(
                    "mean_steps_delta",
                    difference(candidateMetrics.get("mean_steps"), baselineMetrics.get("mean_steps")));
            comparison.put("scenarios", compareScenarios(baselineSummary, candidateSummary));
            comparison.put("case_transitions", compareCases(cases, baselineSummary, candidateSummary));
            comparison.put(
                    "rule_deltas",
                    compareRuleStats(
                            asMap(baselineMetrics.get("rule_stats")),
                            asMap(candidateMetrics.get("rule_stats"))));
            comparisons.put(role, comparison);
        }

        Decision decision = decide(change, baselineRuns, candidateRuns, comparisons);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("experiment_id", effectiveId);
        result.put("candidate", change.toJson());
        result.put("policy", policy.toJson());
        result.put("decision", decision.decision());
        result.put("reasons", decision.reasons());
        result.put("comparisons", comparisons);
        result.put("baseline_runs", baselineRuns);
        result.put("candidate_runs", candidateRuns);
        baseline.store().saveEvolution(effectiveId, change.candidateId(), decision.decision(), result);
        return result;
    }

    /** A verdict plus the evidence that produced it. */
    public record Decision(String decision, List<String> reasons) {
    }

    private Decision decide(
            EvolutionCandidate change,
            Map<String, Object> baselineRuns,
            Map<String, Object> candidateRuns,
            Map<String, Object> comparisons) {
        List<String> rollback = new ArrayList<>();
        List<String> reject = new ArrayList<>();
        List<String> improvementEvidence = new ArrayList<>();

        for (String role : ROLES) {
            if (!matchesTarget(asMap(baselineRuns.get(role)), change, change.baselineVersion())
                    || !matchesTarget(asMap(candidateRuns.get(role)), change, change.candidateVersion())) {
                rollback.add(role + ": target identity or version does not match candidate manifest");
            }
        }

        Map<String, Object> regression = asMap(comparisons.get("regression"));
        if (policy.requireRegressionPass() && intValue(asMap(regression.get("candidate")).get("hard_failures")) > 0) {
            rollback.add("regression: candidate has hard failures");
        }
        if (doubleValue(regression.get("hard_pass_rate_delta")) < 0) {
            rollback.add("regression: hard pass rate decreased");
        }
        if (intValue(regression.get("soft_warning_delta")) > policy.maxRegressionSoftWarningIncrease()) {
            rollback.add("regression: soft warnings exceeded the allowed increase");
        }

        Map<String, Object> holdout = asMap(comparisons.get("holdout"));
        if (policy.requireHoldoutPass() && intValue(asMap(holdout.get("candidate")).get("hard_failures")) > 0) {
            rollback.add("holdout: candidate has hard failures");
        }
        if (doubleValue(holdout.get("hard_pass_rate_delta")) < -policy.maxHoldoutHardPassDrop()) {
            rollback.add("holdout: hard pass rate exceeded the allowed drop");
        }
        if (intValue(holdout.get("soft_warning_delta")) > policy.maxHoldoutSoftWarningIncrease()) {
            rollback.add("holdout: soft warnings exceeded the allowed increase");
        }

        for (ScenarioGate gate : policy.scenarioGates()) {
            for (String role : gate.roles()) {
                Map<String, Object> scenarios = asMap(asMap(comparisons.get(role)).get("scenarios"));
                Object rawScenario = scenarios.get(gate.scenario());
                Map<String, Object> scenario = rawScenario instanceof Map ? asMap(rawScenario) : null;
                if (scenario == null || scenario.get("candidate") == null) {
                    rollback.add(role + "/" + gate.scenario() + ": configured scenario is missing");
                    continue;
                }
                Map<String, Object> candidateMetrics = asMap(scenario.get("candidate"));
                if (intValue(candidateMetrics.get("case_count")) < gate.minimumCaseCount()) {
                    rollback.add(
                            role + "/" + gate.scenario() + ": scenario has fewer than "
                                    + gate.minimumCaseCount() + " cases");
                }
                if (gate.minimumHardPassRate() != null
                        && doubleValue(candidateMetrics.get("hard_pass_rate")) < gate.minimumHardPassRate()) {
                    rollback.add(role + "/" + gate.scenario() + ": hard pass rate is below the configured minimum");
                }
                if (doubleValue(scenario.get("hard_pass_rate_delta")) < -gate.maximumHardPassRateDrop()) {
                    rollback.add(role + "/" + gate.scenario() + ": hard pass rate exceeded the allowed drop");
                }
                if (intValue(scenario.get("soft_warning_delta")) > gate.maximumSoftWarningIncrease()) {
                    rollback.add(role + "/" + gate.scenario() + ": soft warnings exceeded the allowed increase");
                }
            }
        }

        for (QualityGate gate : policy.qualityGates()) {
            for (String role : gate.roles()) {
                Map<String, Object> comparison = asMap(comparisons.get(role));
                Map<String, Object> transitions = asMap(comparison.get("case_transitions"));
                int caseCount = intValue(transitions.get("case_count"));
                if (caseCount == 0) {
                    rollback.add(role + ": quality gate has no evaluated cases");
                    continue;
                }
                if (caseCount < gate.minimumSamples()) {
                    rollback.add(role + ": quality gate needs at least " + gate.minimumSamples()
                            + " cases, found " + caseCount);
                    continue;
                }
                double passRate = doubleValue(asMap(comparison.get("candidate")).get("hard_pass_rate"));
                if (gate.minimumHardPassRate() != null && passRate < gate.minimumHardPassRate()) {
                    rollback.add(role + ": hard pass rate " + percent(passRate)
                            + " is below the minimum " + percent(gate.minimumHardPassRate()));
                }
                int regressions = intValue(transitions.get("regressed_count"));
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
        }

        for (MetricObjective objective : policy.objectives()) {
            for (String role : ROLES) {
                Map<String, Object> comparison = asMap(comparisons.get(role));
                Object baselineValue = asMap(asMap(comparison.get("baseline")).get("objectives")).get(objective.name());
                Object candidateValue = asMap(asMap(comparison.get("candidate")).get("objectives")).get(objective.name());
                if (baselineValue == null || candidateValue == null) {
                    if (objective.required()) {
                        rollback.add(role + "/" + objective.name() + ": required metric is missing");
                    }
                    continue;
                }
                double candidateNumeric = doubleValue(candidateValue);
                if (objective.maximum() != null && candidateNumeric > objective.maximum()) {
                    rollback.add(role + "/" + objective.name() + ": " + candidateNumeric
                            + " exceeds the absolute maximum " + objective.maximum());
                }
                double delta = improvement(doubleValue(baselineValue), candidateNumeric, objective.direction());
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
        }

        Map<String, Object> improvement = asMap(comparisons.get("improvement"));
        if (doubleValue(improvement.get("hard_pass_rate_delta")) > 0) {
            improvementEvidence.add("improvement: hard pass rate increased");
        }
        if (intValue(improvement.get("soft_warning_delta")) < 0) {
            improvementEvidence.add("improvement: soft warnings decreased");
        }

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

    static double improvement(double baseline, double candidate, String direction) {
        return "maximize".equals(direction) ? candidate - baseline : baseline - candidate;
    }

    private static boolean matchesTarget(Map<String, Object> summary, EvolutionCandidate candidate, String version) {
        List<Map<String, Object>> traces = new ArrayList<>();
        for (Object rawResult : asList(summary.get("results"))) {
            Object trace = asMap(rawResult).get("trace");
            if (trace instanceof Map) {
                traces.add(asMap(trace));
            }
        }
        if (traces.isEmpty()) {
            return false;
        }
        for (Map<String, Object> trace : traces) {
            if (!java.util.Objects.equals(trace.get("target_type"), candidate.targetType())
                    || !java.util.Objects.equals(trace.get("target_id"), candidate.targetId())
                    || !java.util.Objects.equals(trace.get("target_version"), version)) {
                return false;
            }
        }
        return true;
    }

    /** Per-metric summary of one run, including every configured objective. */
    public static Map<String, Object> summarizeMetrics(Map<String, Object> summary, List<MetricObjective> objectives) {
        List<Object> results = asList(summary.get("results"));
        int caseCount = results.size();
        List<Double> latencies = new ArrayList<>();
        List<Double> steps = new ArrayList<>();
        for (Object rawResult : results) {
            Map<String, Object> trace = asMap(asMap(rawResult).get("trace"));
            Double latency = traceMetric(trace, "latency_ms");
            Double stepCount = traceMetric(trace, "steps");
            if (latency != null && Double.isFinite(latency)) {
                latencies.add(latency);
            }
            if (stepCount != null && Double.isFinite(stepCount)) {
                steps.add(stepCount);
            }
        }

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("latency_ms", coverage(latencies.size(), caseCount));
        coverage.put("steps", coverage(steps.size(), caseCount));
        Map<String, Object> efficiency = new LinkedHashMap<>();
        for (String metric : List.of("llm_calls", "input_tokens", "output_tokens", "total_tokens", "cost_usd")) {
            List<Double> values = new ArrayList<>();
            for (Object rawResult : results) {
                Object trace = asMap(rawResult).get("trace");
                Double value = traceMetric(trace instanceof Map ? asMap(trace) : Map.of(), metric);
                if (value != null && Double.isFinite(value)) {
                    values.add(value);
                }
            }
            efficiency.put("mean_" + metric, aggregate(values, "mean"));
            coverage.put(metric, coverage(values.size(), caseCount));
        }

        Map<String, Object> objectiveValues = new LinkedHashMap<>();
        Map<String, Object> objectiveCoverage = new LinkedHashMap<>();
        for (MetricObjective objective : objectives) {
            List<Double> samples = objectiveSamples(summary, objective);
            objectiveValues.put(objective.name(), samples.size() == caseCount ? aggregate(samples, objective.aggregation()) : null);
            objectiveCoverage.put(objective.name(), coverage(samples.size(), caseCount));
        }

        Map<String, Object> ruleStats = RuleStats.fromResults(results, caseCount);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("case_count", caseCount);
        metrics.put("hard_failures", summary.get("hard_failures"));
        metrics.put(
                "hard_pass_rate",
                caseCount == 0 ? 0.0 : (double) (caseCount - intValue(summary.get("hard_failures"))) / caseCount);
        metrics.put("soft_warning_count", summary.get("soft_warnings"));
        metrics.put("mean_latency_ms", aggregate(latencies, "mean"));
        metrics.put("p95_latency_ms", aggregate(latencies, "p95"));
        metrics.put("mean_steps", aggregate(steps, "mean"));
        metrics.put("efficiency", efficiency);
        metrics.put("objectives", objectiveValues);
        metrics.put("rule_stats", ruleStats);
        metrics.put("metric_coverage", coverage);
        metrics.put("objective_coverage", objectiveCoverage);
        return metrics;
    }

    /** Per-scenario counts, so a gate can look at one slice instead of the average. */
    public static Map<String, Map<String, Object>> summarizeScenarios(Map<String, Object> summary) {
        Map<String, Map<String, Object>> scenarios = new LinkedHashMap<>();
        for (Object rawResult : asList(summary.get("results"))) {
            Map<String, Object> result = asMap(rawResult);
            String scenario = String.valueOf(asMap(result.get("case")).getOrDefault("scenario", "default"));
            Map<String, Object> metrics =
                    scenarios.computeIfAbsent(
                            scenario,
                            key -> {
                                Map<String, Object> fresh = new LinkedHashMap<>();
                                fresh.put("case_count", 0);
                                fresh.put("hard_failures", 0);
                                fresh.put("soft_warning_count", 0);
                                return fresh;
                            });
            metrics.put("case_count", intValue(metrics.get("case_count")) + 1);
            metrics.put(
                    "hard_failures",
                    intValue(metrics.get("hard_failures")) + (Boolean.TRUE.equals(result.get("hard_pass")) ? 0 : 1));
            metrics.put(
                    "soft_warning_count",
                    intValue(metrics.get("soft_warning_count")) + intValue(result.get("soft_warning_count")));
        }
        for (Map<String, Object> metrics : scenarios.values()) {
            int count = intValue(metrics.get("case_count"));
            metrics.put(
                    "hard_pass_rate",
                    count == 0 ? 0.0 : (double) (count - intValue(metrics.get("hard_failures"))) / count);
        }
        return scenarios;
    }

    public static Map<String, Object> compareScenarios(Map<String, Object> baseline, Map<String, Object> candidate) {
        Map<String, Map<String, Object>> baselineScenarios = summarizeScenarios(baseline);
        Map<String, Map<String, Object>> candidateScenarios = summarizeScenarios(candidate);
        Map<String, Object> comparisons = new LinkedHashMap<>();
        java.util.Set<String> names = new java.util.TreeSet<>(baselineScenarios.keySet());
        names.addAll(candidateScenarios.keySet());
        for (String scenario : names) {
            Map<String, Object> baselineMetrics = baselineScenarios.get(scenario);
            Map<String, Object> candidateMetrics = candidateScenarios.get(scenario);
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("baseline", baselineMetrics);
            comparison.put("candidate", candidateMetrics);
            if (baselineMetrics != null && candidateMetrics != null) {
                comparison.put(
                        "hard_pass_rate_delta",
                        doubleValue(candidateMetrics.get("hard_pass_rate"))
                                - doubleValue(baselineMetrics.get("hard_pass_rate")));
                comparison.put(
                        "soft_warning_delta",
                        intValue(candidateMetrics.get("soft_warning_count"))
                                - intValue(baselineMetrics.get("soft_warning_count")));
            }
            comparisons.put(scenario, comparison);
        }
        return comparisons;
    }

    /**
     * Per-case pass/fail transitions and, for every regression, the first divergent step.
     *
     * <p>A regression is exactly a case that passed for the baseline and fails for the candidate. The
     * traces used here are the ones that were already evaluated and stored, so a diagnosis never
     * re-runs an agent and never disagrees with the report it explains.
     */
    public static Map<String, Object> compareCases(
            List<EvalCase> cases, Map<String, Object> baseline, Map<String, Object> candidate) {
        Map<String, Map<String, Object>> baselineByCase = resultsByCase(baseline);
        Map<String, Map<String, Object>> candidateByCase = resultsByCase(candidate);

        List<Map<String, Object>> regressed = new ArrayList<>();
        List<Map<String, Object>> recovered = new ArrayList<>();
        List<Map<String, Object>> persistent = new ArrayList<>();
        int stablePasses = 0;

        for (EvalCase evalCase : cases) {
            Map<String, Object> baselineResult = baselineByCase.get(evalCase.caseId());
            Map<String, Object> candidateResult = candidateByCase.get(evalCase.caseId());
            boolean baselinePass = Boolean.TRUE.equals(
                    baselineResult == null ? null : baselineResult.get("hard_pass"));
            boolean candidatePass = Boolean.TRUE.equals(
                    candidateResult == null ? null : candidateResult.get("hard_pass"));
            if (baselinePass && candidatePass) {
                stablePasses++;
            } else if (!baselinePass && !candidatePass) {
                persistent.add(caseIdentity(evalCase));
            } else if (baselinePass) {
                regressed.add(regression(evalCase, baselineResult, candidateResult));
            } else {
                recovered.add(caseIdentity(evalCase));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_count", cases.size());
        result.put("stable_pass_count", stablePasses);
        result.put("regressed_count", regressed.size());
        result.put("recovered_count", recovered.size());
        result.put("persistent_failure_count", persistent.size());
        result.put("regressed", regressed);
        result.put("recovered", recovered);
        result.put("persistent_failures", persistent);
        return result;
    }

    private static Map<String, Map<String, Object>> resultsByCase(Map<String, Object> summary) {
        Map<String, Map<String, Object>> byCase = new LinkedHashMap<>();
        for (Object rawResult : asList(summary.get("results"))) {
            Map<String, Object> result = asMap(rawResult);
            String caseId = String.valueOf(asMap(result.get("case")).getOrDefault("case_id", ""));
            if (!caseId.isEmpty()) {
                byCase.put(caseId, result);
            }
        }
        return byCase;
    }

    private static Map<String, Object> regression(
            EvalCase evalCase, Map<String, Object> baseline, Map<String, Object> candidate) {
        Map<String, Object> row = caseIdentity(evalCase);
        row.put("failed_checks", failedChecks(candidate));
        row.put("baseline_trace_id", traceId(baseline));
        row.put("candidate_trace_id", traceId(candidate));
        Map<String, Object> diff =
                TraceDiff.compare(traceOf(baseline), traceOf(candidate), evalCase.metadata());
        row.put("first_divergence", asMap(diff.get("first_divergence")));
        row.put("divergence_count", diff.get("divergence_count"));
        row.put("alignment", diff.get("alignment"));
        row.put("divergences", diff.get("divergences"));
        return row;
    }

    private static Map<String, Object> caseIdentity(EvalCase evalCase) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case_id", evalCase.caseId());
        row.put("scenario", evalCase.scenario());
        row.put("critical", isCritical(evalCase));
        return row;
    }

    private static boolean isCritical(EvalCase evalCase) {
        Object value = evalCase.metadata().get("critical");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static List<Map<String, Object>> failedChecks(Map<String, Object> result) {
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Object rawCheck : asList(result.get("checks"))) {
            Map<String, Object> check = asMap(rawCheck);
            if (!Boolean.TRUE.equals(check.get("passed"))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("layer", check.get("layer"));
                row.put("level", check.get("level"));
                row.put("name", check.get("name"));
                row.put("message", check.get("message"));
                failed.add(row);
            }
        }
        return failed;
    }

    private static Map<String, Object> traceOf(Map<String, Object> result) {
        return result == null ? Map.of() : asMap(result.get("trace"));
    }

    private static String traceId(Map<String, Object> result) {
        Object value = traceOf(result).get("trace_id");
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> compareRuleStats(
            Map<String, Object> baseline, Map<String, Object> candidate) {
        Set<String> names = new TreeSet<>(baseline.keySet());
        names.addAll(candidate.keySet());
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) {
            Map<String, Object> left = asMap(baseline.get(name));
            Map<String, Object> right = asMap(candidate.get(name));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("baseline", left.isEmpty() ? null : left);
            row.put("candidate", right.isEmpty() ? null : right);
            Object leftRate = left.get("pass_rate");
            Object rightRate = right.get("pass_rate");
            row.put(
                    "pass_rate_delta",
                    leftRate instanceof Number leftValue && rightRate instanceof Number rightValue
                            ? rightValue.doubleValue() - leftValue.doubleValue()
                            : null);
            result.put(name, row);
        }
        return result;
    }

    /** Reads one numeric metric out of a normalized trace, or null when the trace does not carry it. */
    static Double traceMetric(Map<String, Object> trace, String metric) {
        Map<String, Object> fields = asMap(trace.get("fields"));
        Map<String, String> aliases = Map.of("model_calls", "llm_call_count", "llm_calls", "llm_call_count",
                "tokens", "total_tokens");
        String fieldName = aliases.getOrDefault(metric, metric);
        Object value = fields.get(fieldName);
        if (fields.containsKey(fieldName)) {
            return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : null;
        }
        if (!(trace.get("events") instanceof List<?>)) {
            return null;
        }
        List<Object> events = asList(trace.get("events"));
        if (List.of("latency_ms", "duration_ms").contains(metric)) {
            double total = 0;
            for (Object rawEvent : events) {
                Object duration = asMap(rawEvent).get("duration_ms");
                if (!(duration instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    return null;
                }
                total += number.doubleValue();
            }
            return Double.isFinite(total) ? total : null;
        }
        if ("steps".equals(metric)) {
            return (double) events.size();
        }
        if (List.of("model_calls", "llm_calls").contains(metric)) {
            int total = 0;
            for (Object rawEvent : events) {
                String spanType = String.valueOf(asMap(asMap(rawEvent).get("fields")).getOrDefault("span_type", ""));
                if (List.of("generation", "response").contains(spanType)) {
                    total++;
                }
            }
            return (double) total;
        }
        return null;
    }

    static Double objectiveValue(Map<String, Object> summary, MetricObjective objective) {
        List<Double> values = objectiveSamples(summary, objective);
        return values.size() == asList(summary.get("results")).size() ? aggregate(values, objective.aggregation()) : null;
    }

    private static List<Double> objectiveSamples(Map<String, Object> summary, MetricObjective objective) {
        List<Object> results = asList(summary.get("results"));
        List<Double> values = new ArrayList<>();
        for (Object rawResult : results) {
            Map<String, Object> result = asMap(rawResult);
            Object traceValue = result.get("trace");
            Map<String, Object> trace = traceValue instanceof Map ? asMap(traceValue) : Map.of();
            Map<String, Object> builtins = new LinkedHashMap<>();
            builtins.put("hard_pass", Boolean.TRUE.equals(result.get("hard_pass")) ? 1.0 : 0.0);
            builtins.put("soft_warning_count", (double) intValue(result.get("soft_warning_count")));
            for (String metric :
                    List.of(
                            "latency_ms",
                            "duration_ms",
                            "steps",
                            "model_calls",
                            "llm_calls",
                            "input_tokens",
                            "output_tokens",
                            "total_tokens",
                            "tokens",
                            "cost_usd")) {
                Double value = traceMetric(trace, metric);
                if (value != null) {
                    builtins.put(metric, value);
                }
            }
            Object value = builtins.get(objective.metric());
            if (value == null) {
                value = ValueOps.readPath(result, objective.metric());
                if (value == ValueOps.MISSING) {
                    value = null;
                }
            }
            if (value instanceof Number number && !(value instanceof Boolean) && Double.isFinite(number.doubleValue())) {
                values.add(number.doubleValue());
            }
        }
        return values;
    }

    static Double aggregate(List<Double> values, String aggregation) {
        if (values.isEmpty()) {
            return null;
        }
        double result = switch (aggregation) {
            case "sum" -> values.stream().mapToDouble(Double::doubleValue).sum();
            case "min" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case "max" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "p50" -> percentile(values, 0.50);
            case "p95" -> percentile(values, 0.95);
            case "p99" -> percentile(values, 0.99);
            default -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        };
        return Double.isFinite(result) ? result : null;
    }

    /**
     * Linear-interpolated percentile, so a small sample still produces a defined value instead of an
     * index error. The sample count is reported separately through metric coverage.
     */
    static Double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = fraction * (sorted.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double value = sorted.get(lower) * (1 - (rank - lower)) + sorted.get(upper) * (rank - lower);
        return Double.isFinite(value) ? value : null;
    }

    private static Map<String, Integer> coverage(int observed, int total) {
        return Map.of("sample_count", observed, "missing_count", total - observed, "case_count", total);
    }

    private static Double difference(Object candidate, Object baseline) {
        if (!(candidate instanceof Number right) || !(baseline instanceof Number left)) {
            return null;
        }
        double delta = right.doubleValue() - left.doubleValue();
        return Double.isFinite(delta) ? delta : null;
    }

    private static int criticalRegressions(Map<String, Object> transitions) {
        int count = 0;
        for (Object rawRegression : asList(transitions.get("regressed"))) {
            if (Boolean.TRUE.equals(asMap(rawRegression).get("critical"))) {
                count++;
            }
        }
        return count;
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
