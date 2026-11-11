package io.assay.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.assay.model.NormalizedTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.List;
import java.util.Map;

class AgentScopeTraceAdapterTest {

    @Test
    @SuppressWarnings("unchecked")
    void mapsTypedEventsIntoANormalizedTrace() {
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build();
        List<AgentEvent> events = List.of(
                new AgentStartEvent("session-1", "reply-1", "planner"),
                new ModelCallStartEvent("reply-1"),
                new ToolCallStartEvent("reply-1", "tool-1", "lookup"),
                new ToolCallEndEvent("reply-1", "tool-1", "lookup"),
                new ToolResultEndEvent("reply-1", "tool-1", "lookup", ToolResultState.SUCCESS),
                new SubagentExposedEvent("helper", "agent-1", "session-1", "helper"),
                new AgentResultEvent(result),
                new AgentEndEvent("reply-1"));
        NormalizedTrace trace = AgentScopeTraceAdapter.toTrace("trace-1", events);
        assertEquals("trace-1", trace.traceId());
        assertEquals("done", trace.finalOutput());
        assertEquals(8, trace.events().size());
        assertEquals("session-1", trace.fields().get("session_id"));
        Map<String, Object> metrics = (Map<String, Object>) trace.fields().get("metrics");
        assertNotNull(metrics);
        assertEquals(1, metrics.get("tool_calls"));
        assertEquals(1, metrics.get("handoffs"));
    }

    @Test
    void recordsEventsForOneAgentCall() {
        AgentScopeEventRecorder recorder = new AgentScopeEventRecorder();
        recorder.emit(new AgentStartEvent("session-2", "reply-2", "worker"));
        recorder.emit(new AgentEndEvent("reply-2"));
        assertEquals(2, recorder.events().size());
        NormalizedTrace trace = recorder.trace("trace-2");
        assertEquals("trace-2", trace.traceId());
        assertEquals("session-2", trace.fields().get("session_id"));
        recorder.clear();
        assertEquals(0, recorder.events().size());
    }
}
