package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvalCase;
import io.assay.model.MetricResult;
import io.assay.model.MetricStatus;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class LoopDetectionMetricTest {

    private final LoopDetectionMetric metric = new LoopDetectionMetric();

    @Test
    void skipsWhenThereIsNoUsableEvidence() {
        MetricResult result = metric.measure(context(List.of(new TraceEvent("agent", "reply"))));

        assertEquals(MetricStatus.SKIP, result.status());
        assertEquals("no loop evidence", result.reason());
    }

    @Test
    void passesForDistinctToolCalls() {
        MetricResult result = metric.measure(context(List.of(tool("lookup"), tool("fetch"), tool("summarize"))));

        assertEquals(MetricStatus.PASS, result.status());
        assertEquals(1.0, result.score());
        assertEquals(3, result.breakdown().get("tool_calls"));
        assertEquals(1, result.breakdown().get("max_tool_repetition"));
    }

    @Test
    void failsOnThreeIdenticalToolCalls() {
        MetricResult result = metric.measure(context(List.of(tool("lookup"), tool("lookup"), tool("lookup"))));

        assertEquals(MetricStatus.FAIL, result.status());
        assertEquals(0.5, result.score());
        assertEquals(3, result.breakdown().get("max_tool_repetition"));
        assertEquals("tool_repetition", result.evidence().get(0).get("signal"));
    }

    @Test
    void sixIdenticalCallsReachZero() {
        MetricResult result = metric.measure(context(List.of(
                tool("lookup"), tool("lookup"), tool("lookup"),
                tool("lookup"), tool("lookup"), tool("lookup"))));

        assertEquals(0.0, result.score());
    }

    @Test
    void argumentOrderDoesNotCreateADifferentSignature() {
        TraceEvent first = toolWithArguments("lookup", Map.of("a", 1, "b", 2));
        TraceEvent second = toolWithArguments("lookup", Map.of("b", 2, "a", 1));

        MetricResult result = metric.measure(context(List.of(first, second)));

        assertEquals(2, result.breakdown().get("max_tool_repetition"));
    }

    @Test
    void flagsARepeatedSignatureCycle() {
        List<TraceEvent> events = List.of(
                modelStart("r1"),
                tool("lookup"),
                modelStart("r1"),
                tool("lookup"));

        MetricResult result = metric.measure(context(events));

        assertFalse(((String) result.breakdown().get("cycle")).isEmpty());
        assertEquals(MetricStatus.FAIL, result.status());
        assertTrue(result.evidence().stream()
                .anyMatch(row -> "repeated_cycle".equals(row.get("signal"))));
    }

    @Test
    void flagsAdjacentModelOutputsThatRepeat() {
        String answer = "the agent keeps repeating the same reasoning about the refund policy again and again";
        List<TraceEvent> events = List.of(
                modelOutput(answer),
                modelOutput(answer));

        MetricResult result = metric.measure(context(events));

        assertEquals(MetricStatus.FAIL, result.status());
        assertEquals(1.0, result.breakdown().get("max_similarity"));
        assertEquals(0.0, scoreOf(result, "reasoning_stall"));
        assertTrue(result.evidence().stream()
                .anyMatch(row -> "reasoning_stall".equals(row.get("signal"))));
    }

    @Test
    void shortOutputsAreNotCompared() {
        List<TraceEvent> events = List.of(
                modelOutput("ok done"),
                modelOutput("ok done"));

        assertEquals(MetricStatus.SKIP, metric.measure(context(events)).status());
    }

    private static double scoreOf(MetricResult result, String signal) {
        Object signals = result.breakdown().get("signals");
        if (signals instanceof Map<?, ?> map && map.get(signal) instanceof Number number) {
            return number.doubleValue();
        }
        return Double.NaN;
    }

    private static MetricContext context(List<TraceEvent> events) {
        NormalizedTrace trace = new NormalizedTrace("trace", "done", events, Map.of());
        return new MetricContext(new EvalCase("case", Map.of(), Map.of(), "default"), trace);
    }

    private static TraceEvent tool(String name) {
        return new TraceEvent("tool", "call_start", "ok", 0, null, Map.of("tool_call_name", name));
    }

    private static TraceEvent toolWithArguments(String name, Map<String, Object> arguments) {
        return new TraceEvent("tool", "call_start", "ok", 0, null,
                Map.of("tool_call_name", name, "arguments", arguments));
    }

    private static TraceEvent modelStart(String replyId) {
        return new TraceEvent("model", "call_start", "ok", 0, null, Map.of("reply_id", replyId));
    }

    private static TraceEvent modelOutput(String text) {
        return new TraceEvent("model", "call_end", "ok", 0, null, Map.of("output", text));
    }
}
