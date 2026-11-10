package io.assay.agentscope;

import io.assay.metrics.MultiAgentMetrics;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps AgentScope's typed event stream onto Assay's normalized trace contract.
 *
 * <p>This is the only place that knows AgentScope types: the evaluation engine, rules, and reports
 * keep working on {@link NormalizedTrace}. Tool calls, handoffs, model calls, and permission denials
 * become ordinary trace events, and the multi-agent counters are attached under {@code metrics}.
 */
public final class AgentScopeTraceAdapter {

    private AgentScopeTraceAdapter() {
    }

    /** Maps a stream of events, using the first event id as the trace id. */
    public static NormalizedTrace toTrace(List<AgentEvent> events) {
        return toTrace(null, events);
    }

    /** Maps a stream of events into a normalized trace. */
    public static NormalizedTrace toTrace(String traceId, List<AgentEvent> events) {
        Objects.requireNonNull(events, "events");
        List<TraceEvent> mapped = new ArrayList<>();
        Object finalOutput = null;
        String resolvedTraceId = traceId;
        String sessionId = null;
        String replyId = null;
        String agentName = null;
        for (AgentEvent event : events) {
            if (event == null) {
                continue;
            }
            if (resolvedTraceId == null && event.getId() != null) {
                resolvedTraceId = event.getId();
            }
            switch (event.getType()) {
                case AGENT_START -> {
                    AgentStartEvent start = (AgentStartEvent) event;
                    sessionId = start.getSessionId();
                    replyId = start.getReplyId();
                    agentName = start.getName();
                    mapped.add(new TraceEvent(
                            "agent", "start", "ok", 0, null,
                            fields("session_id", start.getSessionId(), "reply_id", start.getReplyId(),
                                    "agent_name", start.getName(), "role", start.getRole())));
                }
                case AGENT_END -> {
                    AgentEndEvent end = (AgentEndEvent) event;
                    replyId = end.getReplyId();
                    mapped.add(new TraceEvent("agent", "end", "ok", 0, null, fields("reply_id", end.getReplyId())));
                }
                case AGENT_RESULT -> {
                    AgentResultEvent result = (AgentResultEvent) event;
                    Msg message = result.getResult();
                    if (message != null) {
                        finalOutput = message.getTextContent();
                    }
                    mapped.add(new TraceEvent(
                            "agent", "result", "ok", 0, null,
                            fields("message_id", message == null ? null : message.getId())));
                }
                case MODEL_CALL_START -> {
                    ModelCallStartEvent call = (ModelCallStartEvent) event;
                    mapped.add(new TraceEvent(
                            "model", "call_start", "ok", 0, null, fields("reply_id", call.getReplyId())));
                }
                case MODEL_CALL_END -> {
                    ModelCallEndEvent call = (ModelCallEndEvent) event;
                    mapped.add(new TraceEvent(
                            "model", "call_end", "ok", 0, null,
                            fields("reply_id", call.getReplyId(), "usage", call.getUsage())));
                }
                case TOOL_CALL_START -> {
                    ToolCallStartEvent call = (ToolCallStartEvent) event;
                    mapped.add(new TraceEvent(
                            "tool", "call_start", "ok", 0, null,
                            fields("reply_id", call.getReplyId(), "tool_call_id", call.getToolCallId(),
                                    "tool_call_name", call.getToolCallName())));
                }
                case TOOL_CALL_END -> {
                    ToolCallEndEvent call = (ToolCallEndEvent) event;
                    mapped.add(new TraceEvent(
                            "tool", "call_end", "ok", 0, null,
                            fields("reply_id", call.getReplyId(), "tool_call_id", call.getToolCallId(),
                                    "tool_call_name", call.getToolCallName())));
                }
                case TOOL_RESULT_END -> {
                    ToolResultEndEvent result = (ToolResultEndEvent) event;
                    boolean ok = result.getState() == ToolResultState.SUCCESS;
                    mapped.add(new TraceEvent(
                            "tool", "result_end", ok ? "ok" : "error", 0, ok ? null : String.valueOf(result.getState()),
                            fields("reply_id", result.getReplyId(), "tool_call_id", result.getToolCallId(),
                                    "tool_call_name", result.getToolCallName(), "state", result.getState())));
                }
                case SUBAGENT_EXPOSED -> {
                    SubagentExposedEvent exposed = (SubagentExposedEvent) event;
                    mapped.add(new TraceEvent(
                            "agent", "subagent_exposed", "ok", 0, null,
                            fields("subagent_id", exposed.getSubagentId(), "agent_id", exposed.getAgentId(),
                                    "session_id", exposed.getSessionId(), "label", exposed.getLabel())));
                }
                case EXCEED_MAX_ITERS -> {
                    ExceedMaxItersEvent exceeded = (ExceedMaxItersEvent) event;
                    mapped.add(new TraceEvent(
                            "agent", "exceed_max_iters", "error", 0, "max iterations exceeded",
                            fields("reply_id", exceeded.getReplyId(), "max_iters", exceeded.getMaxIters(),
                                    "current_iter", exceeded.getCurrentIter())));
                }
                case ALL_TOOLS_DENIED -> {
                    AllToolsDeniedEvent denied = (AllToolsDeniedEvent) event;
                    mapped.add(new TraceEvent(
                            "permission", "all_tools_denied", "error", 0, "all tool calls were denied",
                            fields("denied_tools", denied.getDeniedToolCalls() == null
                                    ? 0
                                    : denied.getDeniedToolCalls().size())));
                }
                default -> mapped.add(new TraceEvent(
                        defaultModule(event), event.getType().getValue().toLowerCase(Locale.ROOT), "ok", 0, null,
                        fields("event_id", event.getId())));
            }
        }
        NormalizedTrace withoutFields = new NormalizedTrace(
                resolvedTraceId == null ? "agentscope-trace" : resolvedTraceId, finalOutput, mapped, Map.of());
        Map<String, Object> traceFields = new LinkedHashMap<>();
        if (sessionId != null) {
            traceFields.put("session_id", sessionId);
        }
        if (replyId != null) {
            traceFields.put("reply_id", replyId);
        }
        if (agentName != null) {
            traceFields.put("agent_name", agentName);
        }
        traceFields.put("event_count", mapped.size());
        traceFields.put("metrics", MultiAgentMetrics.analyze(withoutFields).toMap());
        return new NormalizedTrace(
                withoutFields.traceId(), finalOutput, mapped, traceFields);
    }

    private static String defaultModule(AgentEvent event) {
        String type = event.getType().getValue();
        return type.startsWith("REQUIRE_") || type.startsWith("USER_") || type.startsWith("EXTERNAL_")
                ? "permission"
                : "agent";
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] != null) {
                result.put(String.valueOf(pairs[index]), pairs[index + 1]);
            }
        }
        return result;
    }
}
