package io.assay.model;

import io.assay.json.Jsonable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.assay.support.Immutable;

/** A proposed replacement for a text artifact, either whole-file or a set of operations. */
public record TextCandidate(
        String candidateId,
        String candidateVersion,
        String content,
        String summary,
        String changeType,
        Map<String, Object> metadata,
        Map<String, String> files,
        List<TextFileOperation> operations)
        implements Jsonable {

    public TextCandidate {
        content = content == null ? "" : content;
        changeType = changeType == null ? "skill" : changeType;
        metadata = metadata == null ? Map.of() : Immutable.map(metadata);
        files = files == null ? Map.of() : Immutable.map(files);
        operations = operations == null ? List.of() : Immutable.list(operations);
    }

    public TextCandidate(String candidateId, String candidateVersion, String content, String summary) {
        this(candidateId, candidateVersion, content, summary, "skill", Map.of(), Map.of(), List.of());
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidate_id", candidateId);
        result.put("candidate_version", candidateVersion);
        result.put("content", content);
        result.put("summary", summary);
        result.put("change_type", changeType);
        result.put("metadata", metadata);
        result.put("files", files);
        List<Object> rows = new ArrayList<>(operations.size());
        for (TextFileOperation operation : operations) {
            rows.add(operation.toJson());
        }
        result.put("operations", rows);
        return result;
    }

    public static TextCandidate fromJson(Map<String, Object> value) {
        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : EvalCase.asMap(value.get("files")).entrySet()) {
            files.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return new TextCandidate(
                String.valueOf(value.getOrDefault("candidate_id", "")),
                String.valueOf(value.getOrDefault("candidate_version", "")),
                String.valueOf(value.getOrDefault("content", "")),
                String.valueOf(value.getOrDefault("summary", "")),
                String.valueOf(value.getOrDefault("change_type", "skill")),
                EvalCase.asMap(value.get("metadata")),
                files,
                TextFileOperation.listFromJson(value.get("operations")));
    }
}
