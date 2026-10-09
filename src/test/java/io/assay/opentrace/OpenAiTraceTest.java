package io.assay.opentrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.support.AtomicFiles;
import io.assay.testing.Fixtures;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exported span shapes drift between SDK versions, so the normalizer folds every alternative away. These
 * tests pin the alternatives it has to accept, and the counts it must derive from them.
 */
class OpenAiTraceTest {

    private static Map<String, Object> span(String type, String name, Object output) {
        Map<String, Object> spanData = new LinkedHashMap<>();
        spanData.put("type", type);
        spanData.put("name", name);
        spanData.put("output", output);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("trace_id", "trace-1");
        value.put("id", "span-" + name);
        value.put("span_data", spanData);
        return value;
    }

    @Test
    void foldsAMixedSpanSetIntoOneTrace() {
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("agent", "workflow", Map.of("step", "done")));
        spans.add(span("generation", "llm", Map.of("text", "hello")));
        spans.add(span("function", "lookup", Map.of("rows", 1)));
        spans.add(span("mcp_tools", "remote", null));

        NormalizedTrace trace = OpenAiTrace.normalizeOpenAiTrace(spans, "my-agent", "2");

        assertEquals("trace-1", trace.traceId());
        assertEquals("my-agent", trace.targetId());
        assertEquals("2", trace.targetVersion());
        assertEquals("agent", trace.targetType());
        assertEquals(List.of("OpenAIAgent", "OpenAILLM", "OpenAITool", "OpenAITool"), modules(trace));

        Map<String, Object> fields = trace.fields();
        assertEquals(4, Fixtures.intOf(fields.get("span_count")));
        assertEquals(1, Fixtures.intOf(fields.get("agent_span_count")));
        assertEquals(1, Fixtures.intOf(fields.get("llm_call_count")));
        assertEquals(2, Fixtures.intOf(fields.get("tool_call_count")));
        assertEquals(0, Fixtures.intOf(fields.get("handoff_count")));
        assertEquals(0, Fixtures.intOf(fields.get("error_count")));
    }

    /** The agent-level output wins, because it is the one a case actually asserts on. */
    @Test
    void prefersTheHighestPrioritySpanOutput() {
        List<Map<String, Object>> spans = List.of(
                span("function", "lookup", "tool result"),
                span("generation", "llm", "model text"),
                span("agent", "workflow", Map.of("answer", 42)));

        assertEquals(Map.of("answer", 42), OpenAiTrace.normalizeOpenAiTrace(spans).finalOutput());
    }

    @Test
    void fallsBackToTheLatestSpanWhenPrioritiesTie() {
        List<Map<String, Object>> spans = List.of(
                span("function", "first", "earlier"), span("function", "second", "later"));

        assertEquals("later", OpenAiTrace.normalizeOpenAiTrace(spans).finalOutput());
    }

    @Test
    void sumsTokensFromGenerationSpans() {
        Map<String, Object> first = span("generation", "one", null);
        Fixtures.asMap(first.get("span_data")).put("usage", Map.of("prompt_tokens", 3, "completion_tokens", 4));
        Map<String, Object> second = span("generation", "two", null);
        Fixtures.asMap(second.get("span_data")).put("usage", Map.of("input_tokens", 10, "output_tokens", 20));

        Map<String, Object> fields = OpenAiTrace.normalizeOpenAiTrace(List.of(first, second)).fields();

        assertEquals(13, Fixtures.intOf(fields.get("input_tokens")));
        assertEquals(24, Fixtures.intOf(fields.get("output_tokens")));
        assertEquals(37, Fixtures.intOf(fields.get("total_tokens")));
    }

    @Test
    void recordsErrorsAndLeavesTheirTokensUncounted() {
        Map<String, Object> failed = span("generation", "boom", null);
        failed.put("error", Map.of("message", "provider refused"));
        Fixtures.asMap(failed.get("span_data")).put("usage", Map.of("input_tokens", 100, "output_tokens", 100));
        List<Map<String, Object>> spans = List.of(span("agent", "workflow", "ok"), failed);

        NormalizedTrace trace = OpenAiTrace.normalizeOpenAiTrace(spans);

        assertEquals("error", trace.events().get(1).status());
        assertEquals("provider refused", trace.events().get(1).error());
        assertEquals(1, Fixtures.intOf(trace.fields().get("error_count")));
        assertEquals(0, Fixtures.intOf(trace.fields().get("input_tokens")));
    }

    @Test
    void derivesDurationAndWorkflowIdentityFromTheFirstSpan() {
        Map<String, Object> first = span("agent", "workflow", "ok");
        first.put("started_at", "2026-01-01T00:00:00Z");
        first.put("ended_at", "2026-01-01T00:00:02Z");
        Fixtures.asMap(first.get("span_data")).remove("type");
        Fixtures.asMap(first).put(
                "attributes",
                Map.of(
                        "inference.observation_kind", "AGENT",
                        "agent.workflow.name", "support",
                        "agent.workflow.group_id", "group-9"));
        Map<String, Object> second = span("generation", "llm", null);
        second.put("duration_ms", 500);

        NormalizedTrace trace = OpenAiTrace.normalizeOpenAiTrace(List.of(first, second));

        assertEquals("OpenAIAgent", trace.events().get(0).module());
        assertEquals(500.0, trace.events().get(1).durationMs(), 1e-9);
        assertEquals(2000.0, Fixtures.doubleOf(trace.fields().get("duration_ms")), 1e-9);
        assertEquals("support", trace.fields().get("workflow_name"));
        assertEquals("group-9", trace.fields().get("group_id"));
    }

    @Test
    void collectsImplicitFeedbackFromBothShapes() {
        Map<String, Object> span = span("agent", "workflow", "ok");
        span.put("feedback", Map.of("explicit_negative", true));
        Fixtures.asMap(span).put("attributes", Map.of("feedback.rephrased", true, "other", 1));

        Map<String, Object> feedback = OpenAiTrace.normalizeOpenAiTrace(List.of(span)).feedback();

        assertEquals(Boolean.TRUE, feedback.get("explicit_negative"));
        assertEquals(Boolean.TRUE, feedback.get("rephrased"));
        assertNull(feedback.get("other"));
    }

    @Test
    void rejectsTracesThatCannotBeIdentified() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiTrace.normalizeOpenAiTrace(List.of()));

        Map<String, Object> first = span("agent", "workflow", "ok");
        Map<String, Object> second = span("agent", "workflow", "ok");
        second.put("trace_id", "trace-2");
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class, () -> OpenAiTrace.normalizeOpenAiTrace(List.of(first, second)));
        assertTrue(error.getMessage().contains("exactly one trace_id"), error.getMessage());

        Map<String, Object> anonymous = span("agent", "workflow", "ok");
        anonymous.remove("trace_id");
        assertThrows(IllegalArgumentException.class, () -> OpenAiTrace.normalizeOpenAiTrace(List.of(anonymous)));
    }

    @Test
    void loadsTracesGroupedByTraceId(@TempDir Path root) {
        Map<String, Object> first = span("agent", "workflow", "a");
        Map<String, Object> second = span("generation", "llm", "b");
        second.put("trace_id", "trace-2");
        Path path = root.resolve("traces.jsonl");
        AtomicFiles.writeText(
                path,
                io.assay.json.Json.write(first) + "\n\n" + io.assay.json.Json.write(second) + "\n");

        Map<String, NormalizedTrace> traces = OpenAiTrace.loadOpenAiTraces(path, "agent-x", "7");

        assertEquals(List.of("trace-1", "trace-2"), List.copyOf(traces.keySet()));
        assertEquals("agent-x", traces.get("trace-1").targetId());
        assertEquals("7", traces.get("trace-2").targetVersion());
        assertEquals(1, traces.get("trace-2").events().size());
    }

    @Test
    void rejectsUnreadableTraceFiles(@TempDir Path root) {
        Path broken = root.resolve("broken.jsonl");
        AtomicFiles.writeText(broken, "{not json}\n");
        IllegalArgumentException syntax =
                assertThrows(IllegalArgumentException.class, () -> OpenAiTrace.loadOpenAiTraces(broken));
        assertTrue(syntax.getMessage().contains("invalid OpenAI trace"), syntax.getMessage());

        Map<String, Object> anonymous = span("agent", "workflow", "ok");
        anonymous.remove("trace_id");
        Path missing = root.resolve("missing.jsonl");
        AtomicFiles.writeText(missing, io.assay.json.Json.write(anonymous) + "\n");
        IllegalArgumentException noId =
                assertThrows(IllegalArgumentException.class, () -> OpenAiTrace.loadOpenAiTraces(missing));
        assertTrue(noId.getMessage().contains("requires trace_id"), noId.getMessage());

        Path notAnObject = root.resolve("array.jsonl");
        AtomicFiles.writeText(notAnObject, "[1, 2]\n");
        assertThrows(IllegalArgumentException.class, () -> OpenAiTrace.loadOpenAiTraces(notAnObject));
    }

    private static List<String> modules(NormalizedTrace trace) {
        List<String> modules = new ArrayList<>();
        for (TraceEvent event : trace.events()) {
            modules.add(event.module());
        }
        return modules;
    }
}
