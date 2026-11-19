package io.assay.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class TraceRedactorTest {

    @Test
    void masksCredentialKeysAndTokenShapesWithoutTouchingCounters() {
        NormalizedTrace trace = new NormalizedTrace(
                "trace-1",
                "Bearer abc123",
                List.of(new TraceEvent(
                        "tool",
                        "call_start",
                        "ok",
                        1,
                        "sk-abcdefghijklmnop",
                        Map.of(
                                "arguments", Map.of("password", "p-1"),
                                "total_tokens", 42))),
                Map.of("api_key", "k-1"),
                Map.of("access_token", "a-1"),
                "agent",
                "router",
                "1",
                Map.of("private_key", "pk-1"));

        NormalizedTrace redacted = TraceRedactor.redact(trace);

        assertEquals("Bearer ***", redacted.finalOutput());
        assertEquals("***", redacted.fields().get("api_key"));
        assertEquals("***", redacted.feedback().get("access_token"));
        assertEquals("***", redacted.raw().get("private_key"));
        TraceEvent event = redacted.events().get(0);
        assertEquals("sk-***", event.error());
        assertEquals("***", asMap(event.fields().get("arguments")).get("password"));
        assertEquals(42, event.fields().get("total_tokens"));
        assertEquals("trace-1", redacted.traceId());
        assertEquals("router", redacted.targetId());
    }

    @Test
    void masksCommonPersonalIdentifiersWithoutOvermatchingNumbers() {
        String text = "mail user@example.com, call 13812345678, id 110105199003071234, "
                + "order 123456789012345678, reference 12345678901";

        String masked = TraceRedactor.maskText(text);

        assertEquals("mail ***, call ***, id ***, order 123456789012345678, reference 12345678901", masked);
    }

    @Test
    void nullTraceAndPlainValuesAreHandled() {
        assertNull(TraceRedactor.redact(null));
        assertEquals(7, TraceRedactor.redactValue(7));
        assertEquals("plain text", TraceRedactor.maskText("plain text"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
