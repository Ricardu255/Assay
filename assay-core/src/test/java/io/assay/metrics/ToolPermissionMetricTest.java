package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvalCase;
import io.assay.model.MetricResult;
import io.assay.model.MetricStatus;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolPermissionMetricTest {

    private final ToolPermissionMetric metric =
            new ToolPermissionMetric(Set.of("lookup"), Set.of("shell"));

    @Test
    void passesWhenEveryCallIsAuthorized() {
        MetricResult result = metric.measure(context(tool("lookup"), tool("lookup")));

        assertEquals(MetricStatus.PASS, result.status());
        assertEquals(1.0, result.score());
        assertEquals(2, result.breakdown().get("tool_calls"));
        assertEquals(0, result.breakdown().get("violations"));
        assertEquals("all 2 tool calls authorized", result.reason());
    }

    @Test
    void failsOnAnExplicitlyDeniedTool() {
        MetricResult result = metric.measure(context(tool("shell")));

        assertEquals(MetricStatus.FAIL, result.status());
        assertEquals(0.0, result.score());
        assertEquals("shell", result.evidence().get(0).get("tool"));
        assertEquals("tool is explicitly denied", result.evidence().get(0).get("reason"));
        assertTrue(result.reason().contains("shell"), result.reason());
    }

    @Test
    void failsOnAToolOutsideTheAllowList() {
        MetricResult result = metric.measure(context(tool("fetch")));

        assertEquals(MetricStatus.FAIL, result.status());
        assertEquals("tool is not in the allow list", result.evidence().get(0).get("reason"));
    }

    @Test
    void failsOnADeniedEventEvenWithoutAToolCall() {
        MetricResult result = metric.measure(context(
                new TraceEvent("permission", "all_tools_denied", "error", 0, "denied", Map.of())));

        assertEquals(MetricStatus.FAIL, result.status());
        assertEquals(1, result.breakdown().get("denied_events"));
        assertEquals("all_tools_denied", result.evidence().get(0).get("tool"));
    }

    @Test
    void skipsWhenTheTraceHasNoToolCalls() {
        MetricResult result = metric.measure(context(new TraceEvent("agent", "reply")));

        assertEquals(MetricStatus.SKIP, result.status());
        assertEquals("no tool calls", result.reason());
        assertTrue(Double.isNaN(result.score()));
    }

    @Test
    void appliesAPartialThreshold() {
        ToolPermissionMetric lenient = new ToolPermissionMetric(Set.of("lookup"), Set.of(), 0.5);
        MetricResult result = lenient.measure(context(tool("lookup"), tool("fetch")));

        assertEquals(MetricStatus.PASS, result.status());
        assertEquals(0.5, result.score());
        assertEquals("authorized 1/2 tool calls; violating: fetch", result.reason());
    }

    @Test
    void treatsAnUnnamedCallAsAViolationWhenAnAllowListIsConfigured() {
        MetricResult result = metric.measure(context(
                new TraceEvent("tool", "call_start", "ok", 0, null, Map.of())));

        assertEquals(MetricStatus.FAIL, result.status());
        assertEquals(1, result.breakdown().get("unnamed_calls"));
        assertEquals("(unnamed)", result.evidence().get(0).get("tool"));
    }

    @Test
    void rejectsAConflictingPolicy() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ToolPermissionMetric(Set.of("lookup"), Set.of("lookup")));
        assertTrue(error.getMessage().contains("both allow and deny lists"), error.getMessage());
    }

    @Test
    void defaultsToTheHardGateLevel() {
        assertEquals("hard", metric.defaultLevel());
        assertEquals(MetricStatus.SKIP, metric.measure(context()).status());
    }

    private static MetricContext context(TraceEvent... events) {
        NormalizedTrace trace = new NormalizedTrace("trace", "output", List.of(events), Map.of());
        EvalCase evalCase = new EvalCase("case", Map.of(), Map.of(), "default");
        return new MetricContext(evalCase, trace);
    }

    private static TraceEvent tool(String name) {
        return new TraceEvent("tool", "call_start", "ok", 0, null, Map.of("tool_call_name", name));
    }
}
