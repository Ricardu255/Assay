package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A run-level cap on tokens and provider cost.
 *
 * <p>The framework cannot know a case's real consumption before the adapter returns, so every call
 * reserves an estimate first and settles against the trace afterwards. The limits are deliberately
 * optional and independent: a run may cap only tokens, only cost, or both. {@code reserveOutputTokens}
 * is the output allowance added to the case's estimated input, and {@code reserveCostUsd} is the
 * per-call cash reservation used when a cost cap is configured.
 */
public record EvaluationBudget(
        Integer maxTotalTokens, Double maxCostUsd, int reserveOutputTokens, double reserveCostUsd)
        implements Jsonable {

    public EvaluationBudget {
        if (maxTotalTokens != null && maxTotalTokens < 0) {
            throw new IllegalArgumentException("max_total_tokens must be non-negative");
        }
        if (maxCostUsd != null && (!Double.isFinite(maxCostUsd) || maxCostUsd < 0)) {
            throw new IllegalArgumentException("max_cost_usd must be finite and non-negative");
        }
        if (reserveOutputTokens < 0) {
            throw new IllegalArgumentException("reserve_output_tokens must be non-negative");
        }
        if (!Double.isFinite(reserveCostUsd) || reserveCostUsd < 0) {
            throw new IllegalArgumentException("reserve_cost_usd must be finite and non-negative");
        }
    }

    /** The default when neither budget dimension is requested. */
    public static EvaluationBudget disabled() {
        return new EvaluationBudget(null, null, 0, 0);
    }

    public boolean enabled() {
        return maxTotalTokens != null || maxCostUsd != null;
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("max_total_tokens", maxTotalTokens);
        result.put("max_cost_usd", maxCostUsd);
        result.put("reserve_output_tokens", reserveOutputTokens);
        result.put("reserve_cost_usd", reserveCostUsd);
        return result;
    }
}
