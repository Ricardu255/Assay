package io.assay.agentscope;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.assay.model.NormalizedTrace;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects AgentScope events for one agent call.
 *
 * <p>Register it as the emitter passed to an AgentScope agent, then pass {@link #trace(String)} to
 * {@code SuiteRunner} / {@code EvaluationEngine} as the adapter's trace.
 */
public final class AgentScopeEventRecorder implements AgentEventEmitter {

    private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void emit(AgentEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    /** A stable copy of every event received so far. */
    public List<AgentEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** Maps the recorded events into a normalized trace. */
    public NormalizedTrace trace(String traceId) {
        return AgentScopeTraceAdapter.toTrace(traceId, events());
    }

    /** Clears the recorder so the next agent call starts from an empty event stream. */
    public void clear() {
        events.clear();
    }
}
