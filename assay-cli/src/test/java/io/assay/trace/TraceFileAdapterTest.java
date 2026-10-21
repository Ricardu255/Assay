package io.assay.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.cli.AdapterLoader;
import io.assay.engine.EvaluationEngine;
import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.RunContext;
import io.assay.store.file.ResultStore;
import io.assay.support.AtomicFiles;
import io.assay.testing.Fixtures;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Zero-code trace ingestion: normalized exports, OpenInference, OTLP, and case coverage. */
class TraceFileAdapterTest {

    @Test
    void loadsNormalizedTracesAndFailsACaseMissingFromTheExport(@TempDir Path root) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("trace_id", "trace-1");
        trace.put("final_output", "ok");
        trace.put("events", List.of(Map.of("module", "Agent", "action", "answer", "status", "ok")));
        Path traces = write(root, "traces.json", Map.of(
                "version", "v2",
                "runs", List.of(Map.of("case_id", "C1", "trace", trace))));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("runs", traces.toString());
        options.put("target_type", "agent");
        options.put("target_id", "test-agent");
        options.put("hard_gates", List.of(Map.of(
                "name", "answer", "actual", "final_output", "operator", "contains", "value", "ok")));
        ProjectAdapter adapter = AdapterLoader.loadProjectAdapter(config(root, options).toString(), null);

        EvalCase present = new EvalCase("C1", Map.of(), Map.of(), "default", Map.of(), "suite");
        NormalizedTrace loaded = adapter.readTrace().read(null, present);
        assertEquals("ok", loaded.finalOutput());
        assertEquals("v2", loaded.targetVersion());
        assertEquals("test-agent", loaded.targetId());

        EvalCase missing = new EvalCase("C2", Map.of(), Map.of(), "default", Map.of(), "suite");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> adapter.readTrace().read(null, missing));
        assertTrue(error.getMessage().contains("no run for case C2"), error.getMessage());

        assertThrows(IllegalStateException.class, () -> adapter.callAgent().call(
                present, new RunContext("run", "suite", "offline", 0, 30)));
    }

    @Test
    void rejectsInvalidConfigurationAndDuplicateCases(@TempDir Path root) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("trace_id", "trace-1");
        trace.put("final_output", "ok");
        Path traces = write(root, "traces.json", Map.of(
                "runs", List.of(
                        Map.of("case_id", "C1", "trace", trace),
                        Map.of("case_id", "C1", "trace", trace))));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("runs", traces.toString());
        Path duplicate = config(root, options);
        IllegalArgumentException duplicateError =
                assertThrows(IllegalArgumentException.class, () -> TraceFileAdapter.load(duplicate));
        assertTrue(duplicateError.getMessage().contains("duplicate case_id"), duplicateError.getMessage());

        Map<String, Object> unknown = new LinkedHashMap<>(options);
        unknown.put("typo", true);
        assertThrows(IllegalArgumentException.class, () -> TraceFileAdapter.load(config(root, unknown)));

        Path unknownType = write(root, "unknown.json", Map.of("type", "nope"));
        assertThrows(IllegalArgumentException.class,
                () -> AdapterLoader.loadProjectAdapter(unknownType.toString(), null));
    }

    @Test
    void loadsOpenInferenceAttributesAndRunsThroughTheOfflineEngine(@TempDir Path root) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("trace_id", "trace-1");
        agent.put("name", "support-agent");
        agent.put("attributes", Map.of(
                "openinference.span.kind", "AGENT",
                "assay.case_id", "C1",
                "output.value", "{\"answer\":\"ok\"}"));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("trace_id", "trace-1");
        tool.put("name", "query_order");
        tool.put("attributes", Map.of(
                "openinference.span.kind", "TOOL",
                "tool.name", "query_order",
                "input.value", "{\"order_id\":\"A1001\"}",
                "output.value", "{\"status\":\"delivered\"}"));
        Path traces = write(root, "openinference.json", Map.of(
                "version", "v1", "spans", List.of(agent, tool)));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("format", "openinference");
        options.put("runs", traces.toString());
        options.put("target_version", "v1");
        options.put("hard_gates", List.of(Map.of(
                "name", "tool_calls", "actual", "fields.tool_call_count",
                "operator", "min", "value", 1)));
        ProjectAdapter adapter = TraceFileAdapter.load(config(root, options));
        EvalCase evalCase = new EvalCase("C1", Map.of(), Map.of(), "default", Map.of(), "suite");

        NormalizedTrace trace = adapter.readTrace().read(null, evalCase);
        assertEquals(2, trace.events().size());
        assertEquals("Agent", trace.events().get(0).module());
        assertEquals("Tool", trace.events().get(1).module());
        assertEquals("query_order", trace.events().get(1).action());
        assertEquals("A1001", Fixtures.asMap(trace.events().get(1).fields().get("input")).get("order_id"));
        assertEquals("ok", Fixtures.asMap(trace.finalOutput()).get("answer"));
        assertEquals(1, Fixtures.intOf(trace.fields().get("tool_call_count")));

        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            summary = new EvaluationEngine(adapter, store)
                    .runSuite(List.of(evalCase), "suite", "offline", "run-openinference", false);
        }
        assertEquals(0, Fixtures.intOf(summary.get("hard_failures")));
    }

    @Test
    void loadsOtlpAttributeArraysAndPreservesSpanErrors(@TempDir Path root) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("traceId", "trace-1");
        agent.put("spanId", "span-1");
        agent.put("name", "agent");
        agent.put("startTimeUnixNano", "1700000000000000000");
        agent.put("endTimeUnixNano", "1700000001000000000");
        agent.put("attributes", List.of(
                attribute("openinference.span.kind", "AGENT"),
                attribute("assay.case_id", "C1"),
                attribute("output.value", "done")));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("traceId", "trace-1");
        tool.put("spanId", "span-2");
        tool.put("name", "search");
        tool.put("startTimeUnixNano", "1700000000100000000");
        tool.put("endTimeUnixNano", "1700000000200000000");
        tool.put("attributes", List.of(
                attribute("openinference.span.kind", "TOOL"),
                attribute("tool.name", "search"),
                attribute("input.value", "{\"q\":\"refund\"}")));
        tool.put("status", Map.of("code", 2, "message", "upstream timeout"));

        Map<String, Object> payload = Map.of(
                "version", "v3",
                "resourceSpans", List.of(Map.of(
                        "scopeSpans", List.of(Map.of("spans", List.of(agent, tool))))));
        Path traces = write(root, "otlp.json", payload);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("format", "openinference");
        options.put("runs", traces.toString());
        options.put("target_version", "v3");
        ProjectAdapter adapter = TraceFileAdapter.load(config(root, options));
        NormalizedTrace trace = adapter.readTrace().read(
                null, new EvalCase("C1", Map.of(), Map.of(), "default", Map.of(), "suite"));

        assertEquals(2, trace.events().size());
        assertEquals("Tool", trace.events().get(1).module());
        assertEquals("search", trace.events().get(1).action());
        assertEquals("error", trace.events().get(1).status());
        assertEquals("upstream timeout", trace.events().get(1).error());
        assertEquals("done", trace.finalOutput());
        assertEquals(1, Fixtures.intOf(trace.fields().get("error_count")));
        assertEquals(1000.0, Fixtures.doubleOf(trace.fields().get("duration_ms")), 1.0);
    }

    @Test
    void loadsAPhoenixTracesWrapper(@TempDir Path root) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("name", "route");
        span.put("attributes", Map.of(
                "openinference.span.kind", "CHAIN",
                "assay.case_id", "C1",
                "output.value", "done"));
        Path traces = write(root, "phoenix.json", Map.of(
                "version", "v4",
                "traces", List.of(Map.of("trace_id", "trace-1", "spans", List.of(span)))));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("format", "openinference");
        options.put("runs", traces.toString());
        options.put("target_version", "v4");
        ProjectAdapter adapter = TraceFileAdapter.load(config(root, options));
        NormalizedTrace trace = adapter.readTrace().read(
                null, new EvalCase("C1", Map.of(), Map.of(), "default", Map.of(), "suite"));

        assertEquals("trace-1", trace.traceId());
        assertEquals("done", trace.finalOutput());
        assertEquals(1, trace.events().size());
    }

    @Test
    void aMissingCaseIsAHardExecutionFailure(@TempDir Path root) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("trace_id", "trace-1");
        trace.put("final_output", "ok");
        Path traces = write(root, "traces.json", Map.of(
                "runs", List.of(Map.of("case_id", "C1", "trace", trace))));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("runs", traces.toString());
        ProjectAdapter adapter = TraceFileAdapter.load(config(root, options));
        EvalCase missing = new EvalCase("C2", Map.of(), Map.of(), "default", Map.of(), "suite");

        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            summary = new EvaluationEngine(adapter, store)
                    .runSuite(List.of(missing), "suite", "offline", "run-missing", false);
        }

        assertEquals(1, Fixtures.intOf(summary.get("hard_failures")));
        Map<String, Object> result = Fixtures.mapsOf(summary.get("results")).get(0);
        assertTrue(Fixtures.str(result.get("error")).contains("no run for case C2"), result.toString());
    }

    @Test
    void normalizedTraceRoundTripsThroughItsJsonProjection() {
        NormalizedTrace trace = new NormalizedTrace(
                "trace-1",
                Map.of("answer", "ok"),
                List.of(new io.assay.model.TraceEvent("Agent", "answer", "ok", 12, null, Map.of("route", "SUPPORT"))),
                Map.of("route", "SUPPORT"),
                Map.of("explicit_negative", false),
                "skill",
                "router",
                "v2",
                Map.of("source", "test"));

        NormalizedTrace restored = NormalizedTrace.fromDict(Fixtures.asMap(trace.toJson()));

        assertEquals(trace, restored);
    }

    private static Map<String, Object> attribute(String key, String value) {
        return Map.of("key", key, "value", Map.of("stringValue", value));
    }

    private static Path config(Path root, Map<String, Object> options) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "trace_file");
        config.putAll(options);
        return write(root, "adapter.json", config);
    }

    private static Path write(Path root, String name, Object value) {
        Path path = root.resolve(name);
        AtomicFiles.writeText(path, Json.write(value) + "\n");
        return path;
    }
}
