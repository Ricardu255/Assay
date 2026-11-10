package io.assay.evolve;

import io.assay.model.EvalCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Case-level transitions and first-divergence diagnosis for two stored run summaries. */
public final class EvolutionComparison {

    private EvolutionComparison() {
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
        for (Object rawResult : EvolutionSupport.asList(summary.get("results"))) {
            Map<String, Object> result = EvolutionSupport.asMap(rawResult);
            String caseId = String.valueOf(EvolutionSupport.asMap(result.get("case")).getOrDefault("case_id", ""));
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
        row.put("first_divergence", EvolutionSupport.asMap(diff.get("first_divergence")));
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
        for (Object rawCheck : EvolutionSupport.asList(result.get("checks"))) {
            Map<String, Object> check = EvolutionSupport.asMap(rawCheck);
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
        return result == null ? Map.of() : EvolutionSupport.asMap(result.get("trace"));
    }

    private static String traceId(Map<String, Object> result) {
        Object value = traceOf(result).get("trace_id");
        return value == null ? "" : String.valueOf(value);
    }
}
