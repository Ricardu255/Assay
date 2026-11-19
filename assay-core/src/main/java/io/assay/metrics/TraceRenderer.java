package io.assay.metrics;

import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.model.TraceRedactor;
import java.util.List;

/**
 * Renders a case and its trace as the evidence block a judge reads.
 *
 * <p>The rendering is stable and bounded: events are numbered, values are JSON, secrets are masked
 * by default, and long evidence is truncated so one runaway output cannot blow up the prompt.
 */
public final class TraceRenderer {

    private static final int MAX_CHARS = 12_000;

    private TraceRenderer() {
    }

    /** Renders the case input, expectations, trace events, and final output. */
    public static String render(EvalCase evalCase, NormalizedTrace trace) {
        StringBuilder text = new StringBuilder();
        if (evalCase != null) {
            text.append("INPUT:\n").append(json(evalCase.payload())).append('\n');
            if (evalCase.expected() != null && !evalCase.expected().isEmpty()) {
                text.append("EXPECTED:\n").append(json(evalCase.expected())).append('\n');
            }
        }
        if (trace != null) {
            text.append("EVENTS:\n");
            List<TraceEvent> events = trace.events();
            for (int index = 0; index < events.size(); index++) {
                TraceEvent event = events.get(index);
                text.append('#').append(index + 1).append(' ')
                        .append(event.module()).append('/').append(event.action())
                        .append(" status=").append(event.status());
                if (event.durationMs() > 0) {
                    text.append(" duration_ms=").append(event.durationMs());
                }
                if (event.error() != null && !event.error().isBlank()) {
                    text.append(" error=").append(event.error().trim());
                }
                if (!event.fields().isEmpty()) {
                    text.append(" fields=").append(json(event.fields()));
                }
                text.append('\n');
            }
            if (trace.fields() != null && !trace.fields().isEmpty()) {
                text.append("TRACE FIELDS:\n").append(json(trace.fields())).append('\n');
            }
            text.append("FINAL OUTPUT:\n").append(value(trace.finalOutput())).append('\n');
        }
        if (text.length() > MAX_CHARS) {
            return text.substring(0, MAX_CHARS) + "\n[evidence truncated]\n";
        }
        return text.toString();
    }

    private static String json(Object value) {
        return Json.write(TraceRedactor.redactValue(value));
    }

    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return TraceRedactor.maskText(text);
        }
        return json(value);
    }
}
