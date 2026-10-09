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
