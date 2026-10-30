package io.assay.compare;

import io.assay.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rough token and cost estimates for traces that carry no provider telemetry.
 *
 * <p>These numbers are deliberately labelled as estimates. A real adapter should report
 * {@code input_tokens}, {@code output_tokens}, and {@code cost_usd} in the trace fields; this class
 * only keeps a demo or a deterministic fixture from showing an empty efficiency column. It never
 * changes the stored trace.
 */
public final class CostEstimator {

    private static final String DEFAULT_MODEL = "default";

    /**
     * USD per million tokens, as prompt and completion prices. The table is intentionally small: an
     * unknown model falls back to the conservative default rather than pretending to know its price.
     */
    private static final Map<String, double[]> PRICES = Map.of(
            "gpt-4o-mini", new double[] {0.15, 0.60},
            "gpt-4o", new double[] {2.50, 10.00},
            DEFAULT_MODEL, new double[] {0.15, 0.60});

    private CostEstimator() {
    }

    /** Estimates mean tokens and cost across one run's stored results. */
    public static Map<String, Object> estimate(List<Map<String, Object>> results) {
        int caseCount = results == null ? 0 : results.size();
        long inputTokens = 0;
        long outputTokens = 0;
        double cost = 0;
        String model = null;
        for (Map<String, Object> result : results == null ? List.<Map<String, Object>>of() : results) {
            Map<String, Object> evalCase = asMap(result.get("case"));
            Map<String, Object> trace = asMap(result.get("trace"));
            int input = tokens(evalCase.get("payload"));
            int output = tokens(trace.get("final_output"));
            inputTokens += input;
            outputTokens += output;
            String caseModel = modelOf(trace);
            if (caseModel != null && model == null) {
                model = caseModel;
            }
            double[] price = PRICES.getOrDefault(caseModel == null ? DEFAULT_MODEL : caseModel,
                    PRICES.get(DEFAULT_MODEL));
            cost += input * price[0] / 1_000_000 + output * price[1] / 1_000_000;
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("case_count", caseCount);
        metrics.put("model", model);
        metrics.put("price_model", model == null || !PRICES.containsKey(model) ? DEFAULT_MODEL : model);
        metrics.put("mean_input_tokens", caseCount == 0 ? null : (double) inputTokens / caseCount);
        metrics.put("mean_output_tokens", caseCount == 0 ? null : (double) outputTokens / caseCount);
        metrics.put("mean_total_tokens", caseCount == 0 ? null : (double) (inputTokens + outputTokens) / caseCount);
        metrics.put("mean_cost_usd", caseCount == 0 ? null : cost / caseCount);
        metrics.put("estimated", true);
        return metrics;
    }

    /** Estimates tokens as characters divided by four, the same coarse rule a demo needs. */
    public static int tokens(Object value) {
        if (value == null) {
            return 0;
        }
        String text = value instanceof String string ? string : Json.write(value);
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    private static String modelOf(Map<String, Object> trace) {
        Map<String, Object> fields = asMap(trace.get("fields"));
        for (String key : List.of("model", "model_name", "llm.model_name")) {
            Object value = fields.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
