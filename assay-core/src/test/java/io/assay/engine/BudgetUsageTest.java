package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;

/** Usage extraction keeps absent telemetry distinct from a measured zero. */
class BudgetUsageTest {

    @Test
    void prefersTotalTokensAndFallsBackToInputPlusOutput() {
        assertEquals(12L, BudgetUsage.fromFields(Map.of("total_tokens", 12, "input_tokens", 4,
                "output_tokens", 8)).totalTokens());
        assertEquals(12L, BudgetUsage.fromFields(Map.of("input_tokens", 4, "output_tokens", 8))
                .totalTokens());
        assertEquals(0L, BudgetUsage.fromFields(Map.of("total_tokens", 0)).totalTokens());
    }

    @Test
    void aMissingSideIsUnknownRatherThanZero() {
        BudgetUsage usage = BudgetUsage.fromFields(Map.of("input_tokens", 4));

        assertNull(usage.totalTokens());
        assertFalse(usage.tokensReported());
    }

    @Test
    void readsNumericStringsAndRejectsNegativeValues() {
        BudgetUsage usage = BudgetUsage.fromFields(Map.of(
                "total_tokens", "24", "cost_usd", "0.005"));

        assertEquals(24L, usage.totalTokens());
        assertEquals(0.005, usage.costUsd(), 1e-9);
        assertTrue(usage.costReported());
        assertNull(BudgetUsage.fromFields(Map.of("cost_usd", -1)).costUsd());
    }

    @Test
    void readsTheStoredTraceProjection() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("input_tokens", 3);
        fields.put("output_tokens", 5);
        fields.put("cost_usd", 0.02);

        BudgetUsage usage = BudgetUsage.fromTraceMap(Map.of("fields", fields));

        assertEquals(8L, usage.totalTokens());
        assertEquals(0.02, usage.costUsd(), 1e-9);
    }

    @Test
    void aMissingTraceIsUnknown() {
        BudgetUsage usage = BudgetUsage.fromTraceMap(Map.of());

        assertNull(usage.totalTokens());
        assertNull(usage.costUsd());
    }
}
