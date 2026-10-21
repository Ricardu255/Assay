package io.assay.metrics;

import io.assay.rules.ValueOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Prediction-agreement metrics over one run and across repeated runs.
 *
 * <p>A case only counts as evaluated when both the expected verdict and the trace's verdict are
 * {@code CORRECT}/{@code INCORRECT}; every other shape — a missing case, a missing trace, a human
 * verdict of {@code UNRESOLVED} — is skipped rather than scored. The rates that divide by a count
 * therefore report {@code null} when that count is zero instead of inventing a number.
 */
public final class EvaluatorMetrics {

    private EvaluatorMetrics() {
    }

    /** Prediction agreement, plus per-field accuracy, over one run. */
    public static Map<String, Object> classificationMetrics(List<Map<String, Object>> results) {
        int agreement = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        Map<String, Integer> fieldCorrect = new LinkedHashMap<>();
        Map<String, Integer> fieldTotal = new LinkedHashMap<>();
        int evaluated = 0;
        Map<String, String> predictions = new LinkedHashMap<>();

        for (Map<String, Object> result : results) {
            Map<String, Object> evalCase = asMap(result.get("case"));
            Map<String, Object> expected = asMap(evalCase.get("expected"));
            // A missing or empty trace is the same as no trace at all.
            Object traceValue = result.get("trace");
            Map<String, Object> trace =
                    traceValue instanceof Map<?, ?> map && !map.isEmpty() ? asMap(traceValue) : Map.of();
            Map<String, Object> fields = asMap(trace.get("fields"));
            Object expectedVerdict = expected.get("verdict");
            Object actualVerdict = fields.get("verdict");
            if (!isVerdict(expectedVerdict) || !isVerdict(actualVerdict)) {
                continue;
            }
            evaluated++;
            predictions.put(textOf(evalCase.getOrDefault("case_id", "")), String.valueOf(actualVerdict));
            if (ValueOps.equal(actualVerdict, expectedVerdict)) {
                agreement++;
            }
            if ("INCORRECT".equals(actualVerdict) && "CORRECT".equals(expectedVerdict)) {
                falsePositives++;
            }
            if ("CORRECT".equals(actualVerdict) && "INCORRECT".equals(expectedVerdict)) {
                falseNegatives++;
            }
            for (Map.Entry<String, Object> entry : expected.entrySet()) {
                String field = entry.getKey();
                if (!fields.containsKey(field)) {
                    continue;
                }
                fieldTotal.merge(field, 1, Integer::sum);
                boolean matches = ValueOps.equal(fields.get(field), entry.getValue());
                fieldCorrect.merge(field, matches ? 1 : 0, Integer::sum);
            }
        }

        Map<String, Object> fieldAccuracy = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(fieldTotal).entrySet()) {
            int total = entry.getValue();
            fieldAccuracy.put(
                    entry.getKey(), (double) fieldCorrect.getOrDefault(entry.getKey(), 0) / total);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("case_count", results.size());
        metrics.put("evaluated_count", evaluated);
        metrics.put("agreement_count", agreement);
        metrics.put("agreement_rate", evaluated == 0 ? null : (double) agreement / evaluated);
        metrics.put("false_positive_count", falsePositives);
        metrics.put("false_negative_count", falseNegatives);
        metrics.put("field_accuracy", fieldAccuracy);
        metrics.put("predictions", predictions);
        return metrics;
    }

    /** How often repeated runs agree on the same cases. */
    public static Map<String, Object> repeatStability(List<List<Map<String, Object>>> runs) {
        if (runs.size() < 2) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("run_count", runs.size());
            metrics.put("comparable_case_count", 0);
            metrics.put("stability_rate", null);
            return metrics;
        }

        List<Map<String, String>> byRun = new ArrayList<>(runs.size());
        for (List<Map<String, Object>> results : runs) {
            byRun.add(predictionsOf(results));
        }
        Set<String> common = new LinkedHashSet<>(byRun.get(0).keySet());
        for (int index = 1; index < byRun.size(); index++) {
            common.retainAll(byRun.get(index).keySet());
        }
        int stable = 0;
        for (String caseId : common) {
            Set<String> verdicts = new LinkedHashSet<>();
            for (Map<String, String> predictions : byRun) {
                verdicts.add(predictions.get(caseId));
            }
            if (verdicts.size() == 1) {
                stable++;
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("run_count", runs.size());
        metrics.put("comparable_case_count", common.size());
        metrics.put("stable_case_count", stable);
        metrics.put("stability_rate", common.isEmpty() ? null : (double) stable / common.size());
        return metrics;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> predictionsOf(List<Map<String, Object>> results) {
        Object predictions = classificationMetrics(results).get("predictions");
        return predictions instanceof Map ? (Map<String, String>) predictions : new LinkedHashMap<>();
    }

    private static boolean isVerdict(Object value) {
        return "CORRECT".equals(value) || "INCORRECT".equals(value);
    }

    /** Renders a scalar as text. */
    private static String textOf(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
