package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.Map;
import io.assay.support.Immutable;

/** Manifest of one candidate version under evaluation. */
public record EvolutionCandidate(
        String candidateId,
        String targetType,
        String targetId,
        String baselineVersion,
        String candidateVersion,
        String changeType,
        String artifactRef,
        String summary,
        Map<String, Object> metadata)
        implements Jsonable {

    public EvolutionCandidate {
        artifactRef = artifactRef == null ? "" : artifactRef;
        summary = summary == null ? "" : summary;
        metadata = metadata == null ? Map.of() : Immutable.map(metadata);
    }

    public EvolutionCandidate(
            String candidateId,
            String targetType,
            String targetId,
            String baselineVersion,
            String candidateVersion,
            String changeType) {
        this(candidateId, targetType, targetId, baselineVersion, candidateVersion, changeType, "", "", Map.of());
    }

    /** Every required field must be non-blank. */
    public static EvolutionCandidate fromDict(Map<String, Object> value) {
        String[] required = {
            "candidate_id", "target_type", "target_id", "baseline_version", "candidate_version", "change_type"
        };
        StringBuilder missing = new StringBuilder();
        for (String field : required) {
            if (EvalCase.str(value.get(field)).isEmpty()) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(field);
            }
        }
        if (missing.length() > 0) {
            throw new IllegalArgumentException("candidate requires: " + missing);
        }
        return new EvolutionCandidate(
                String.valueOf(value.get("candidate_id")),
                String.valueOf(value.get("target_type")),
                String.valueOf(value.get("target_id")),
                String.valueOf(value.get("baseline_version")),
                String.valueOf(value.get("candidate_version")),
                String.valueOf(value.get("change_type")),
                String.valueOf(value.getOrDefault("artifact_ref", "")),
                String.valueOf(value.getOrDefault("summary", "")),
                EvalCase.asMap(value.get("metadata")));
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidate_id", candidateId);
        result.put("target_type", targetType);
        result.put("target_id", targetId);
        result.put("baseline_version", baselineVersion);
        result.put("candidate_version", candidateVersion);
        result.put("change_type", changeType);
        result.put("artifact_ref", artifactRef);
        result.put("summary", summary);
        result.put("metadata", metadata);
        return result;
    }
}
