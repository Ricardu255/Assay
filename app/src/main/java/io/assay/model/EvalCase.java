package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.Map;
import io.assay.support.Immutable;

/** One evaluation case supplied by the domain project. */
public record EvalCase(
        String caseId,
        Object payload,
        Map<String, Object> expected,
        String scenario,
        Map<String, Object> metadata,
        String suite)
        implements Jsonable {

    public EvalCase {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("case requires a non-empty id");
        }
        expected = expected == null ? Map.of() : Immutable.map(expected);
        metadata = metadata == null ? Map.of() : Immutable.map(metadata);
        scenario = scenario == null ? "default" : scenario;
        suite = suite == null ? "custom" : suite;
    }

    public EvalCase(String caseId, Object payload, Map<String, Object> expected, String scenario) {
        this(caseId, payload, expected, scenario, Map.of(), "custom");
    }

    /** {@code id} and {@code input} are accepted as aliases for {@code case_id} and {@code payload}. */
    public static EvalCase fromDict(Map<String, Object> value, String suite) {
        String caseId = str(value.get("id"));
        if (caseId.isEmpty()) {
            caseId = str(value.get("case_id"));
        }
        if (caseId.isEmpty()) {
            throw new IllegalArgumentException("case requires a non-empty id");
        }
        Object payload = value.containsKey("input") ? value.get("input") : value.get("payload");
        return new EvalCase(
                caseId,
                payload == null ? Map.of() : payload,
                asMap(value.get("expected")),
                String.valueOf(value.getOrDefault("scenario", "default")),
                asMap(value.get("metadata")),
                suite);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", caseId);
        result.put("payload", payload);
        result.put("expected", expected);
        result.put("scenario", scenario);
        result.put("metadata", metadata);
        result.put("suite", suite);
        return result;
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
