package io.assay.compare;

import io.assay.evolve.EvolutionEngine;
import io.assay.model.EvalCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares two stored runs of the same suite and produces one release-shaped report.
 *
 * <p>This is the light path between {@code run} and {@code evolve}: it does not re-run an agent and
 * does not require improvement, regression, and holdout datasets. It reads the case results already
 * persisted by {@link io.assay.engine.EvaluationEngine}, reuses the same transition and first-divergence
 * logic as evolution, and adds scenario slices plus a flat gate.
 */
public final class CompareEngine {

    private CompareEngine() {
    }

    /**
     * Builds the comparison report from two run summaries.
     *
     * @param baselineName fallback label when the summary has no run id
     * @param candidateName fallback label when the summary has no run id
     * @param baselineSummary a summary shaped like the object {@code run} writes to {@code results.json}
     * @param candidateSummary the candidate summary with the same suite
     * @param gate the flat release gate to evaluate, or {@code null} for a report-only comparison
     */
    public static Map<String, Object> compare(
            String baselineName,
            String candidateName,
            Map<String, Object> baselineSummary,
            Map<String, Object> candidateSummary,
            CompareGate gate) {
        CompareGate effectiveGate = gate == null ? CompareGate.defaults() : gate;
        baselineSummary = normalizeSummary(baselineSummary);
        candidateSummary = normalizeSummary(candidateSummary);
        List<Map<String, Object>> baselineResults = resultsOf(baselineSummary);
        List<Map<String, Object>> candidateResults = resultsOf(candidateSummary);
        List<EvalCase> cases = unionCases(baselineResults, candidateResults);

        Map<String, Object> baselineMetrics = EvolutionEngine.summarizeMetrics(baselineSummary, List.of());
        Map<String, Object> candidateMetrics = EvolutionEngine.summarizeMetrics(candidateSummary, List.of());
        Map<String, Object> transitions = EvolutionEngine.compareCases(cases, baselineSummary, candidateSummary);
        Map<String, Object> scenarios = scenarioSlices(cases, baselineResults, candidateResults);
        Map<String, Object> rules = compareRules(
                asMap(baselineMetrics.get("rule_stats")), asMap(candidateMetrics.get("rule_stats")));
        Map<String, Object> estimated = CostEstimator.estimate(candidateResults);
        Map<String, Object> baselineEstimated = CostEstimator.estimate(baselineResults);

        Map<String, Object> deltas = deltas(baselineMetrics, candidateMetrics);
        CompareGate.GateOutcome outcome =
                effectiveGate.evaluate(baselineMetrics, candidateMetrics, transitions, estimated);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", dataset(baselineSummary, candidateSummary));
        report.put("baseline", runView(baselineName, baselineSummary, baselineMetrics));
        report.put("candidate", runView(candidateName, candidateSummary, candidateMetrics));
        report.put("deltas", deltas);
        report.put("transitions", transitions);
        report.put("regressions", transitions.get("regressed"));
        report.put("scenarios", scenarios);
        report.put("rules", rules);
        report.put("estimated_efficiency", estimated);
        report.put("baseline_estimated_efficiency", baselineEstimated);
        report.put("gate", outcome.toJson());
        report.put("gate_config", effectiveGate.toJson());
        return report;
    }

    private static Map<String, Object> runView(
            String fallbackName, Map<String, Object> summary, Map<String, Object> metrics) {
        Map<String, Object> view = new LinkedHashMap<>();
        Object runId = summary.get("run_id");
        view.put("run_id", runId == null ? fallbackName : runId);
        view.put("suite", summary.get("suite"));
        view.put("source", summary.get("source"));
        view.put("status", summary.get("status"));
        view.putAll(metrics);
        return view;
    }

    private static String dataset(Map<String, Object> baseline, Map<String, Object> candidate) {
        Object left = baseline.get("suite");
        Object right = candidate.get("suite");
        if (Objects.equals(left, right)) {
            return left == null ? "custom" : String.valueOf(left);
        }
        return String.valueOf(left) + " -> " + String.valueOf(right);
    }

    private static Map<String, Object> deltas(
            Map<String, Object> baselineMetrics, Map<String, Object> candidateMetrics) {
        Map<String, Object> deltas = new LinkedHashMap<>();
        putDelta(deltas, "hard_pass_rate",
                baselineMetrics.get("hard_pass_rate"), candidateMetrics.get("hard_pass_rate"));
        putDelta(deltas, "soft_warning_count",
                baselineMetrics.get("soft_warning_count"), candidateMetrics.get("soft_warning_count"));
        putDelta(deltas, "mean_latency_ms",
                baselineMetrics.get("mean_latency_ms"), candidateMetrics.get("mean_latency_ms"));
        putDelta(deltas, "p95_latency_ms",
                baselineMetrics.get("p95_latency_ms"), candidateMetrics.get("p95_latency_ms"));
        putDelta(deltas, "mean_steps",
                baselineMetrics.get("mean_steps"), candidateMetrics.get("mean_steps"));
        Map<String, Object> baselineEfficiency = asMap(baselineMetrics.get("efficiency"));
        Map<String, Object> candidateEfficiency = asMap(candidateMetrics.get("efficiency"));
        putDelta(
                deltas,
                "mean_total_tokens",
                baselineEfficiency.get("mean_total_tokens"),
                candidateEfficiency.get("mean_total_tokens"));
        putDelta(
                deltas,
                "mean_cost_usd",
                baselineEfficiency.get("mean_cost_usd"),
                candidateEfficiency.get("mean_cost_usd"));
        return deltas;
    }

    private static void putDelta(Map<String, Object> deltas, String key, Object baseline, Object candidate) {
        Double left = number(baseline);
        Double right = number(candidate);
        deltas.put(key, left == null || right == null ? null : right - left);
    }

    /**
     * Per-scenario pass rates and regression counts, including critical regressions.
     *
     * <p>The scenario key is the case's {@code scenario}, which is also what a scenario gate uses, so
     * the table is directly actionable without a second mapping.
     */
    private static Map<String, Object> scenarioSlices(
            List<EvalCase> cases,
            List<Map<String, Object>> baselineResults,
            List<Map<String, Object>> candidateResults) {
        Map<String, Map<String, Object>> baselineByCase = resultsByCase(baselineResults);
        Map<String, Map<String, Object>> candidateByCase = resultsByCase(candidateResults);
        Map<String, Slice> slices = new TreeMap<>();
        for (EvalCase evalCase : cases) {
            Map<String, Object> baseline = baselineByCase.get(evalCase.caseId());
            Map<String, Object> candidate = candidateByCase.get(evalCase.caseId());
            boolean baselinePass = baseline != null && Boolean.TRUE.equals(baseline.get("hard_pass"));
            boolean candidatePass = candidate != null && Boolean.TRUE.equals(candidate.get("hard_pass"));
            Slice slice = slices.computeIfAbsent(evalCase.scenario(), ignored -> new Slice());
            slice.cases++;
            slice.baselinePasses += baselinePass ? 1 : 0;
            slice.candidatePasses += candidatePass ? 1 : 0;
            if (baselinePass && !candidatePass) {
                slice.regressions++;
                if (isCritical(evalCase)) {
                    slice.criticalRegressions++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Slice> entry : slices.entrySet()) {
            Slice slice = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("case_count", slice.cases);
            row.put("baseline_hard_passes", slice.baselinePasses);
            row.put("candidate_hard_passes", slice.candidatePasses);
            row.put("baseline_hard_pass_rate", rate(slice.baselinePasses, slice.cases));
            row.put("candidate_hard_pass_rate", rate(slice.candidatePasses, slice.cases));
            row.put(
                    "hard_pass_rate_delta",
                    rate(slice.candidatePasses, slice.cases) - rate(slice.baselinePasses, slice.cases));
            row.put("regressions", slice.regressions);
            row.put("critical_regressions", slice.criticalRegressions);
            result.put(entry.getKey(), row);
        }
        return result;
    }

    private static Map<String, Object> compareRules(
            Map<String, Object> baselineRules, Map<String, Object> candidateRules) {
        TreeSet<String> names = new TreeSet<>(baselineRules.keySet());
        names.addAll(candidateRules.keySet());
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) {
            Map<String, Object> baseline = asMap(baselineRules.get(name));
            Map<String, Object> candidate = asMap(candidateRules.get(name));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("baseline", baseline.isEmpty() ? null : baseline);
            row.put("candidate", candidate.isEmpty() ? null : candidate);
            Double left = number(baseline.get("pass_rate"));
            Double right = number(candidate.get("pass_rate"));
            row.put("pass_rate_delta", left == null || right == null ? null : right - left);
            result.put(name, row);
        }
        return result;
    }

    /**
     * The union of both runs' cases, preferring the candidate's copy of the case definition.
     *
     * <p>A case that appears in only one run must still be visible to the transition logic; using the
     * baseline list alone would silently drop a case the candidate added or the baseline removed.
     */
    private static List<EvalCase> unionCases(
            List<Map<String, Object>> baselineResults, List<Map<String, Object>> candidateResults) {
        Map<String, Map<String, Object>> rows = new TreeMap<>();
        for (Map<String, Object> result : baselineResults) {
            Map<String, Object> evalCase = asMap(result.get("case"));
            String caseId = String.valueOf(evalCase.getOrDefault("case_id", ""));
            if (!caseId.isEmpty()) {
                rows.put(caseId, evalCase);
            }
        }
        for (Map<String, Object> result : candidateResults) {
            Map<String, Object> evalCase = asMap(result.get("case"));
            String caseId = String.valueOf(evalCase.getOrDefault("case_id", ""));
            if (!caseId.isEmpty()) {
                rows.put(caseId, evalCase);
            }
        }
        List<EvalCase> cases = new ArrayList<>(rows.size());
        for (Map.Entry<String, Map<String, Object>> entry : rows.entrySet()) {
            Map<String, Object> row = entry.getValue();
            String suite = row.get("suite") == null ? "custom" : String.valueOf(row.get("suite"));
            cases.add(EvalCase.fromDict(row, suite));
        }
        return cases;
    }

    private static Map<String, Map<String, Object>> resultsByCase(List<Map<String, Object>> results) {
        Map<String, Map<String, Object>> byCase = new LinkedHashMap<>();
        for (Map<String, Object> result : results) {
            String caseId = String.valueOf(asMap(result.get("case")).getOrDefault("case_id", ""));
            if (!caseId.isEmpty()) {
                byCase.put(caseId, result);
            }
        }
        return byCase;
    }

    private static boolean isCritical(EvalCase evalCase) {
        Object value = evalCase.metadata().get("critical");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static double rate(int passes, int total) {
        return total == 0 ? 0.0 : (double) passes / total;
    }

    private static List<Map<String, Object>> resultsOf(Map<String, Object> summary) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object raw : asList(summary.get("results"))) {
            results.add(asMap(raw));
        }
        return results;
    }

    /**
     * Recomputes the counters from the case rows rather than trusting a hand-edited summary.
     *
     * <p>{@code results.json} always carries both; a caller who assembled a summary or edited one
     * should still get the same verdict as the rows imply.
     */
    private static Map<String, Object> normalizeSummary(Map<String, Object> summary) {
        List<Map<String, Object>> results = resultsOf(summary);
        if (results.isEmpty()) {
            return summary;
        }
        int hardFailures = 0;
        int softWarnings = 0;
        for (Map<String, Object> result : results) {
            if (!Boolean.TRUE.equals(result.get("hard_pass"))) {
                hardFailures++;
            }
            Object softWarningsValue = result.get("soft_warning_count");
            if (softWarningsValue instanceof Number number) {
                softWarnings += number.intValue();
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(summary);
        normalized.put("case_count", results.size());
        normalized.put("hard_failures", hardFailures);
        normalized.put("soft_warnings", softWarnings);
        normalized.put("status", hardFailures == 0 ? "passed" : "failed");
        return normalized;
    }

    private static Double number(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                ? number.doubleValue()
                : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static final class Slice {
        private int cases;
        private int baselinePasses;
        private int candidatePasses;
        private int regressions;
        private int criticalRegressions;
    }
}
