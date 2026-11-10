package io.assay.evolve;

import io.assay.engine.EvaluationEngine;
import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.model.EvolutionCandidate;
import io.assay.model.EvolutionPolicy;
import io.assay.model.MetricObjective;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a baseline and a candidate against the same three datasets and decides the outcome.
 *
 * <p>The decision is deterministic and fully explained by {@code reasons}: {@code rollback} when the
 * candidate breaks something that was working, {@code reject} when it merely fails to improve on the
 * stated objective, and {@code accept} only when both hold. The LLM never participates here.
 *
 * <p>Metric aggregation lives in {@link MetricStats}, case transitions in {@link EvolutionComparison},
 * and the policy rules in {@link EvolutionPolicyEvaluator}; this class orchestrates the three runs,
 * assembles the result document and persists it.
 */
public final class EvolutionEngine {

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
        for (String role : EvolutionSupport.ROLES) {
            if (!datasets.containsKey(role)) {
                missing.add(role);
            }
        }
        if (!missing.isEmpty()) {
            missing.sort(java.util.Comparator.naturalOrder());
            throw new IllegalArgumentException(
                    "evolution datasets require: " + String.join(", ", missing));
        }
        for (String role : EvolutionSupport.ROLES) {
            if (datasets.get(role).isEmpty()) {
                throw new IllegalArgumentException("evolution datasets must not be empty");
            }
        }

        String effectiveId = experimentId != null ? experimentId : "evolution-" + EvolutionSupport.shortId();
        Map<String, Object> baselineRuns = new LinkedHashMap<>();
        Map<String, Object> candidateRuns = new LinkedHashMap<>();
        Map<String, Object> comparisons = new LinkedHashMap<>();
        List<String> incompleteRuns = new ArrayList<>();

        for (String role : EvolutionSupport.ROLES) {
            List<EvalCase> cases = datasets.get(role);
            Map<String, Object> baselineSummary =
                    baseline.runSuite(cases, role, source, effectiveId + "-" + role + "-baseline", resume);
            Map<String, Object> candidateSummary =
                    candidate.runSuite(cases, role, source, effectiveId + "-" + role + "-candidate", resume);
            baselineRuns.put(role, baselineSummary);
            candidateRuns.put(role, candidateSummary);
            if (EvolutionSupport.incomplete(baselineSummary)) {
                incompleteRuns.add(role + ": baseline run is incomplete (budget exhausted)");
            }
            if (EvolutionSupport.incomplete(candidateSummary)) {
                incompleteRuns.add(role + ": candidate run is incomplete (budget exhausted)");
            }
            if (EvolutionSupport.incomplete(baselineSummary) || EvolutionSupport.incomplete(candidateSummary)) {
                // A partial run has no comparable case set; comparing it would turn missing cases into
                // regressions or improvements. Keep the run artifacts and stop at a rollback.
                continue;
            }

            Map<String, Object> baselineMetrics = MetricStats.summarizeMetrics(baselineSummary, policy.objectives());
            Map<String, Object> candidateMetrics = MetricStats.summarizeMetrics(candidateSummary, policy.objectives());
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("baseline", baselineMetrics);
            comparison.put("candidate", candidateMetrics);
            comparison.put(
                    "hard_pass_rate_delta",
                    EvolutionSupport.doubleValue(candidateMetrics.get("hard_pass_rate"))
                            - EvolutionSupport.doubleValue(baselineMetrics.get("hard_pass_rate")));
            comparison.put(
                    "soft_warning_delta",
                    EvolutionSupport.intValue(candidateMetrics.get("soft_warning_count"))
                            - EvolutionSupport.intValue(baselineMetrics.get("soft_warning_count")));
            comparison.put(
                    "mean_latency_ms_delta",
                    EvolutionSupport.difference(
                            candidateMetrics.get("mean_latency_ms"), baselineMetrics.get("mean_latency_ms")));
            comparison.put(
                    "p95_latency_ms_delta",
                    EvolutionSupport.difference(
                            candidateMetrics.get("p95_latency_ms"), baselineMetrics.get("p95_latency_ms")));
            comparison.put(
                    "mean_steps_delta",
                    EvolutionSupport.difference(candidateMetrics.get("mean_steps"), baselineMetrics.get("mean_steps")));
            comparison.put("scenarios", MetricStats.compareScenarios(baselineSummary, candidateSummary));
            comparison.put(
                    "case_transitions", EvolutionComparison.compareCases(cases, baselineSummary, candidateSummary));
            comparison.put(
                    "rule_deltas",
                    MetricStats.compareRuleStats(
                            EvolutionSupport.asMap(baselineMetrics.get("rule_stats")),
                            EvolutionSupport.asMap(candidateMetrics.get("rule_stats"))));
            comparisons.put(role, comparison);
        }

        EvolutionPolicyEvaluator.Decision decision = incompleteRuns.isEmpty()
                ? new EvolutionPolicyEvaluator(policy).decide(change, baselineRuns, candidateRuns, comparisons)
                : new EvolutionPolicyEvaluator.Decision("rollback", incompleteRuns);
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

    /** Per-metric summary of one run; kept as a stable entry point for release comparison. */
    public static Map<String, Object> summarizeMetrics(
            Map<String, Object> summary, List<MetricObjective> objectives) {
        return MetricStats.summarizeMetrics(summary, objectives);
    }

    /** Per-scenario counts, so a gate can look at one slice instead of the average. */
    public static Map<String, Map<String, Object>> summarizeScenarios(Map<String, Object> summary) {
        return MetricStats.summarizeScenarios(summary);
    }

    public static Map<String, Object> compareScenarios(
            Map<String, Object> baseline, Map<String, Object> candidate) {
        return MetricStats.compareScenarios(baseline, candidate);
    }

    /** Per-case pass/fail transitions and, for every regression, the first divergent step. */
    public static Map<String, Object> compareCases(
            List<EvalCase> cases, Map<String, Object> baseline, Map<String, Object> candidate) {
        return EvolutionComparison.compareCases(cases, baseline, candidate);
    }
}
