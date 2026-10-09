package io.assay.examples;

import io.assay.engine.ProjectAdapterFactory;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import io.assay.rules.ValueOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The smallest possible adapter: it echoes the message back and reports a fixed route.
 *
 * <p>A reference adapter for the {@code run} walkthrough. Replace {@link #callAgent} with the real HTTP,
 * SDK, subprocess, or in-process call.
 */
public final class EchoAdapter implements ProjectAdapterFactory {

    @Override
    public ProjectAdapter create() {
        return new ProjectAdapter(
                "echo",
                EchoAdapter::callAgent,
                EchoAdapter::readTrace,
                List.of(Rule.of("route", "fields.route", "route").suspectedModules("Router")),
                List.of(
                        Rule.of("expected_keyword", "final_output", "contains", "keyword")
                                .suspectedModules("ResponseGenerator")));
    }

    private static Object callAgent(EvalCase evalCase, RunContext context) {
        Object message = ValueOps.readPath(evalCase.payload(), "message");
        Map<String, Object> handle = new LinkedHashMap<>();
        handle.put("trace_id", context.runId() + "-" + evalCase.caseId());
        handle.put("output", message == ValueOps.MISSING ? "" : message);
        handle.put("route", "ECHO");
        handle.put("events", List.of(event("EchoAgent", "reply", 1)));
        return handle;
    }

    private static NormalizedTrace readTrace(Object handle, EvalCase evalCase) {
        Map<String, Object> values = Shared.asMap(handle);
        return new NormalizedTrace(
                String.valueOf(values.get("trace_id")),
                values.get("output"),
                List.of(new TraceEvent("EchoAgent", "reply", 1)),
                Map.of("route", values.get("route")),
                Map.of(),
                "skill",
                "echo",
                "1",
                Map.of());
    }

    static Map<String, Object> event(String module, String action, double durationMs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("module", module);
        event.put("action", action);
        event.put("duration_ms", durationMs);
        return event;
    }
}
