package io.assay.metrics;

import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic multi-agent signals derived from a normalized trace.
 *
 * <p>The counters are intentionally structural: they do not judge quality, they only make handoffs,
 * tool/model calls, permission denials, and repeated steps visible so rules and reports can gate on
 * them without depending on an agent framework.
 */
public record MultiAgentMetrics(
        int steps,
        int modelCalls,
        int toolCalls,
        int handoffs,
        int deniedTools,
        int maxRepeatedStep,
        List<String> agents) {

    public MultiAgentMetrics {
        agents = agents == null ? List.of() : List.copyOf(agents);
    }

    public static MultiAgentMetrics analyze(NormalizedTrace trace) {
        if (trace == null) {
            return new MultiAgentMetrics(0, 0, 0, 0, 0, 0, List.of());
        }
        int modelCalls = 0;
        int toolCalls = 0;
        int handoffs = 0;
        int deniedTools = 0;
        int maxRepeated = 0;
        int repeated = 0;
        String previous = null;
        Set<String> agents = new LinkedHashSet<>();
        for (TraceEvent event : trace.events()) {
            String module = text(event.module());
            String action = text(event.action());
            if ("model".equals(module) && "call_start".equals(action)) {
                modelCalls++;
            }
            if ("tool".equals(module) && "call_start".equals(action)) {
                toolCalls++;
            }
            if ("subagent_exposed".equals(action)) {
                handoffs++;
            }
            if ("all_tools_denied".equals(action)) {
                deniedTools++;
            }
            Object agentName = event.fields().get("agent_name");
            if (agentName != null) {
                agents.add(String.valueOf(agentName));
            }
            Object subagentId = event.fields().get("subagent_id");
            if (subagentId != null) {
                agents.add(String.valueOf(subagentId));
            }
            String key = module + '/' + action;
            if (key.equals(previous)) {
                repeated++;
            } else {
                previous = key;
                repeated = 1;
            }
            maxRepeated = Math.max(maxRepeated, repeated);
        }
        return new MultiAgentMetrics(
                trace.events().size(), modelCalls, toolCalls, handoffs, deniedTools, maxRepeated,
                agents.stream().toList());
    }

    /** The metrics as a JSON-shaped map, suitable for a report or trace field. */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("steps", steps);
        value.put("model_calls", modelCalls);
        value.put("tool_calls", toolCalls);
        value.put("handoffs", handoffs);
        value.put("denied_tools", deniedTools);
        value.put("max_repeated_step", maxRepeatedStep);
        value.put("agents", new ArrayList<>(agents));
        return value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
