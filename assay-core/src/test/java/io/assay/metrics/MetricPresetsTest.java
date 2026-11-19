package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.List;

class MetricPresetsTest {

    @Test
    void noneResolvesToNoMetrics() {
        assertEquals(List.of(), MetricPresets.resolve(null));
        assertEquals(List.of(), MetricPresets.resolve("none"));
    }

    @Test
    void agentSafetyResolvesToTheDeterministicPermissionMetric() {
        List<Metric> metrics = MetricPresets.resolve("agent-safety");

        assertEquals(2, metrics.size());
        assertEquals("tool_permission", metrics.get(0).name());
        assertEquals("hard", metrics.get(0).defaultLevel());
        assertEquals("loop_detection", metrics.get(1).name());
        assertEquals("soft", metrics.get(1).defaultLevel());
    }

    @Test
    void unknownPresetListsTheValidNames() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> MetricPresets.resolve("agent-magic"));
        assertTrue(error.getMessage().contains("agent-safety"), error.getMessage());
    }
}
