package io.assay.model;

import io.assay.json.Jsonable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.assay.support.Immutable;

/** The framework's stable trace contract, produced by the domain adapter. */
public record NormalizedTrace(
        String traceId,
        Object finalOutput,
        List<TraceEvent> events,
        Map<String, Object> fields,
        Map<String, Object> feedback,
        String targetType,
        String targetId,
        String targetVersion,
        Map<String, Object> raw)
        implements Jsonable {

    public NormalizedTrace {
        events = events == null ? List.of() : Immutable.list(events);
        fields = fields == null ? Map.of() : Immutable.map(fields);
        feedback = feedback == null ? Map.of() : Immutable.map(feedback);
        targetType = targetType == null ? "agent" : targetType;
        targetId = targetId == null ? "default" : targetId;
        targetVersion = targetVersion == null ? "unknown" : targetVersion;
        raw = raw == null ? Map.of() : Immutable.map(raw);
    }

    public NormalizedTrace(String traceId, Object finalOutput, List<TraceEvent> events, Map<String, Object> fields) {
        this(traceId, finalOutput, events, fields, Map.of(), "agent", "default", "unknown", Map.of());
    }

    /**
     * Reads a trace back from its JSON projection.
     *
     * <p>This is the contract external runtimes write to: the same field names {@link #toJson()}
     * emits are accepted here, so an exported trace can be evaluated without a Java adapter. A blank
     * target identity falls back to the record defaults instead of remaining an empty string.
     */
    public static NormalizedTrace fromDict(Map<String, Object> value) {
        List<TraceEvent> events = new ArrayList<>();
        Object rawEvents = value.get("events");
        if (rawEvents instanceof List<?> rows) {
            for (Object row : rows) {
                events.add(TraceEvent.fromDict(EvalCase.asMap(row)));
            }
        }
        return new NormalizedTrace(
                text(value.get("trace_id")),
                value.get("final_output"),
                events,
                EvalCase.asMap(value.get("fields")),
                EvalCase.asMap(value.get("feedback")),
                textOrNull(value.get("target_type")),
                textOrNull(value.get("target_id")),
                textOrNull(value.get("target_version")),
                EvalCase.asMap(value.get("raw")));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String textOrNull(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    /** The trace as a plain JSON-shaped mapping, which is what the rule paths are read from. */
    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trace_id", traceId);
        result.put("final_output", finalOutput);
        List<Object> eventRows = new ArrayList<>(events.size());
        for (TraceEvent event : events) {
            eventRows.add(event.toJson());
        }
        result.put("events", eventRows);
        result.put("fields", fields);
        result.put("feedback", feedback);
        result.put("target_type", targetType);
        result.put("target_id", targetId);
        result.put("target_version", targetVersion);
        result.put("raw", raw);
        return result;
    }
}
