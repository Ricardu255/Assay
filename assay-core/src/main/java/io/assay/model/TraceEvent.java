package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.Map;
import io.assay.support.Immutable;

/** One step recorded in a normalized trace. */
public record TraceEvent(
        String module,
        String action,
        String status,
        double durationMs,
        String error,
        Map<String, Object> fields)
        implements Jsonable {

    public TraceEvent {
        status = status == null ? "ok" : status;
        fields = fields == null ? Map.of() : Immutable.map(fields);
    }

    public TraceEvent(String module, String action) {
        this(module, action, "ok", 0, null, Map.of());
    }

    public TraceEvent(String module, String action, double durationMs) {
        this(module, action, "ok", durationMs, null, Map.of());
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("module", module);
        result.put("action", action);
        result.put("status", status);
        result.put("duration_ms", durationMs);
        result.put("error", error);
        result.put("fields", fields);
        return result;
    }

    /** Reads an event from a JSON object shaped like {@link #toJson()}. */
    public static TraceEvent fromDict(Map<String, Object> value) {
        return new TraceEvent(
                String.valueOf(value.getOrDefault("module", "")),
                String.valueOf(value.getOrDefault("action", "")),
                String.valueOf(value.getOrDefault("status", "ok")),
                Values.toDouble(value.get("duration_ms"), 0),
                value.get("error") == null ? null : String.valueOf(value.get("error")),
                EvalCase.asMap(value.get("fields")));
    }
}
