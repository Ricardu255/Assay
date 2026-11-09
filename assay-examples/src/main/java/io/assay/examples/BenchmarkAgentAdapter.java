package io.assay.examples;

import io.assay.engine.ProjectAdapterFactory;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic, network-free multi-step agent used to benchmark trace size, rules, and storage.
 *
 * <p>This is deliberately not a model benchmark: it removes LLM and network variance so the
 * framework's own scheduling, rule evaluation, normalization, and persistence costs stay visible.
 */
public final class BenchmarkAgentAdapter implements ProjectAdapterFactory {

    @Override
    public ProjectAdapter create() {
        return new ProjectAdapter(
                "benchmark-multi-step",
                BenchmarkAgentAdapter::call,
                BenchmarkAgentAdapter::trace,
                List.of(
                        Rule.of("route", "fields.route", "route").suspectedModules("Router"),
                        Rule.of("tool_chain", "events", "sequence_contains", "tool_chain").suspectedModules("Tool"),
                        Rule.of("keyword", "final_output.answer", "contains", "keyword"),
                        Rule.ofValue(
                                "search_args",
                                "events",
                                "sequence_contains",
                                List.of(Map.of("step", "Tool/search", "args", Map.of("query", "refund"))))
                                .suspectedModules("Tool")),
                List.of(Rule.ofValue("quality", "fields.quality", "min", 0.5)));
    }

    private static Object call(EvalCase evalCase, RunContext context) {
        String message = Shared.text(evalCase.payload(), "message");
        Map<String, Object> handle = new LinkedHashMap<>();
        handle.put("trace_id", context.runId() + "-" + evalCase.caseId());
        handle.put("route", "SUPPORT");
        handle.put("query", "refund");
        handle.put("answer", "refund policy for: " + message);
        handle.put("quality", 1.0);
        return handle;
    }

    private static NormalizedTrace trace(Object raw, EvalCase evalCase) {
        Map<String, Object> values = Shared.asMap(raw);
        String answer = String.valueOf(values.get("answer"));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("route", values.get("route"));
        fields.put("query", values.get("query"));
        fields.put("quality", values.get("quality"));
        return new NormalizedTrace(
                String.valueOf(values.get("trace_id")),
                Map.of("answer", answer, "route", values.get("route")),
                List.of(
                        new TraceEvent("Router", "route", "ok", 1, null, Map.of("route", values.get("route"))),
                        new TraceEvent("Tool", "search", "ok", 2, null,
                                Map.of("query", values.get("query"), "result_count", 3)),
                        new TraceEvent("Tool", "answer", "ok", 2, null, Map.of("answer", answer)),
                        new TraceEvent("Response", "finalize", "ok", 1, null, Map.of())),
                fields,
                Map.of(),
                "agent",
                "benchmark-agent",
                "v1",
                Map.of());
    }
}
