package io.assay.engine;

import io.assay.model.NormalizedTrace;
import java.util.Map;

/**
 * The usage a trace reports for one adapter call.
 *
 * <p>A missing value stays {@code null} instead of becoming zero. Charging zero for an unreported
 * token count would turn "we do not know" into "this call was free", which is exactly the mistake a
 * budget is supposed to prevent.
 */
public record BudgetUsage(Long totalTokens, Double costUsd) {

    public static BudgetUsage unknown() {
        return new BudgetUsage(null, null);
    }

    public boolean tokensReported() {
        return totalTokens != null;
    }

    public boolean costReported() {
        return costUsd != null;
    }

    /** Reads {@code total_tokens}, {@code input_tokens}/{@code output_tokens}, and {@code cost_usd}. */
    public static BudgetUsage fromTrace(NormalizedTrace trace) {
        return trace == null ? unknown() : fromFields(trace.fields());
    }

    /** Reads a trace projection as stored in {@code results.json}. */
    @SuppressWarnings("unchecked")
    public static BudgetUsage fromTraceMap(Map<String, Object> trace) {
        if (trace == null) {
            return unknown();
        }
        Object fields = trace.get("fields");
        return fromFields(fields instanceof Map ? (Map<String, Object>) fields : Map.of());
    }

    static BudgetUsage fromFields(Map<String, Object> fields) {
        Long total = nonNegativeLong(fields.get("total_tokens"));
        if (total == null) {
            Long input = nonNegativeLong(fields.get("input_tokens"));
            Long output = nonNegativeLong(fields.get("output_tokens"));
            if (input != null && output != null) {
                total = input > Long.MAX_VALUE - output ? Long.MAX_VALUE : input + output;
            }
        }
        Double cost = nonNegativeDouble(fields.get("cost_usd"));
        return new BudgetUsage(total, cost);
    }

    private static Long nonNegativeLong(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            return Double.isFinite(decimal) && decimal >= 0 ? Math.round(decimal) : null;
        }
        if (value instanceof String text) {
            try {
                double decimal = Double.parseDouble(text.trim());
                return Double.isFinite(decimal) && decimal >= 0 ? Math.round(decimal) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double nonNegativeDouble(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            return Double.isFinite(decimal) && decimal >= 0 ? decimal : null;
        }
        if (value instanceof String text) {
            try {
                double decimal = Double.parseDouble(text.trim());
                return Double.isFinite(decimal) && decimal >= 0 ? decimal : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
