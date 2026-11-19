package io.assay.synth;

import io.assay.json.Json;
import io.assay.support.Immutable;
import java.util.LinkedHashMap;
import java.util.Map;

/** One generated case in the framework's case JSONL shape. */
public record SyntheticCase(
        String id,
        String input,
        String expectedKey,
        String expectedOutput,
        String scenario,
        Map<String, Object> metadata) {

    public SyntheticCase {
        if (id == null || id.isBlank() || input == null || input.isBlank()) {
            throw new IllegalArgumentException("synthetic case requires an id and input");
        }
        metadata = Immutable.map(metadata);
    }

    /** The line written to the case JSONL file. */
    public Map<String, Object> toCaseJson() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("scenario", scenario);
        row.put("input", input);
        row.put("expected", Map.of(expectedKey, expectedOutput));
        row.put("metadata", metadata);
        return row;
    }

    /** The case as one JSON line. */
    public String toJsonLine() {
        return Json.write(toCaseJson());
    }
}
