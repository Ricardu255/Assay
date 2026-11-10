package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiAgentMetricsTest {

    @Test
    void countsStructuralSignalsAndRepeats() {
        NormalizedTrace trace = new NormalizedTrace(
                "trace-1",
                "done",
                List.of(
                        new TraceEvent("agent", "start", "ok", 0, null, Map.of("agent_name", "planner")),
                        new TraceEvent("model", "call_start"),
                        new TraceEvent("tool", "call_start", "ok", 0, null, Map.of("tool_call_name", "lookup")),
                        new TraceEvent("tool", "result_end"),
                        new TraceEvent("agent", "subagent_exposed", "ok", 0, null, Map.of("subagent_id", "helper")),
                        new TraceEvent("permission", "all_tools_denied", "error", 0, "denied", Map.of())),
                Map.of());
        MultiAgentMetrics metrics = MultiAgentMetrics.analyze(trace);
        assertEquals(6, metrics.steps());
        assertEquals(1, metrics.modelCalls());
        assertEquals(1, metrics.toolCalls());
        assertEquals(1, metrics.handoffs());
        assertEquals(1, metrics.deniedTools());
        assertEquals(1, metrics.maxRepeatedStep());
        assertEquals(List.of("planner", "helper"), metrics.agents());
        assertEquals(1, metrics.toMap().get("handoffs"));
    }

    @Test
    void handlesAnEmptyTrace() {
        MultiAgentMetrics metrics = MultiAgentMetrics.analyze(new NormalizedTrace("trace-2", "ok", List.of(), Map.of()));
        assertEquals(0, metrics.steps());
        assertEquals(List.of(), metrics.agents());
    }
}
