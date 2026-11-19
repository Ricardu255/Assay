package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class TraceRendererTest {

    @Test
    void rendersInputEventsAndFinalOutput() {
        EvalCase evalCase = new EvalCase(
                "case",
                Map.of("message", "refund my order"),
                Map.of("route", "REFUND"),
                "default",
                Map.of(),
                "smoke");
        NormalizedTrace trace = new NormalizedTrace(
                "trace",
                "refund issued",
                List.of(new TraceEvent("tool", "call_start", "ok", 12, null,
                        Map.of("tool_call_name", "lookup", "order_id", "A1"))),
                Map.of());

        String text = TraceRenderer.render(evalCase, trace);

        assertTrue(text.contains("INPUT:"));
        assertTrue(text.contains("refund my order"));
        assertTrue(text.contains("EXPECTED:"));
        assertTrue(text.contains("#1 tool/call_start status=ok"));
        assertTrue(text.contains("tool_call_name"));
        assertTrue(text.contains("FINAL OUTPUT:"));
        assertTrue(text.contains("refund issued"));
    }

    @Test
    void masksSecretsByDefault() {
        EvalCase evalCase = new EvalCase(
                "case",
                Map.of("message", "hi", "authorization", "Bearer abc123", "nested", Map.of("api_key", "k-1")),
                Map.of(),
                "default");
        NormalizedTrace trace = new NormalizedTrace(
                "trace",
                "done",
                List.of(new TraceEvent("tool", "call_start", "ok", 0, null,
                        Map.of("password", "p-1", "note", "Bearer xyz789"))),
                Map.of("secret", "s-1"));

        String text = TraceRenderer.render(evalCase, trace);

        assertFalse(text.contains("abc123"), text);
        assertFalse(text.contains("k-1"), text);
        assertFalse(text.contains("p-1"), text);
        assertFalse(text.contains("xyz789"), text);
        assertTrue(text.contains("Bearer ***"));
        assertTrue(text.contains("\"***\""));
    }

    @Test
    void truncatesLongEvidence() {
        NormalizedTrace trace = new NormalizedTrace("trace", "x".repeat(20_000), List.of(), Map.of());

        String text = TraceRenderer.render(null, trace);

        assertTrue(text.length() < 12_100, "length=" + text.length());
        assertTrue(text.contains("[evidence truncated]"));
    }
}
