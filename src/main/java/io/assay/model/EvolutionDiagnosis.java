package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.assay.support.Immutable;

/** What the diagnoser concluded about a failing run. */
public record EvolutionDiagnosis(
        String summary,
        String targetType,
        String targetId,
        List<String> evidenceCaseIds,
        List<String> suspectedModules,
        List<String> constraints,
        Map<String, Object> metadata)
        implements Jsonable {

    public EvolutionDiagnosis {
        evidenceCaseIds = evidenceCaseIds == null ? List.of() : Immutable.list(evidenceCaseIds);
        suspectedModules = suspectedModules == null ? List.of() : Immutable.list(suspectedModules);
        constraints = constraints == null ? List.of() : Immutable.list(constraints);
        metadata = metadata == null ? Map.of() : Immutable.map(metadata);
    }

    public EvolutionDiagnosis(String summary, String targetType, String targetId) {
        this(summary, targetType, targetId, List.of(), List.of(), List.of(), Map.of());
    }

    public EvolutionDiagnosis(String summary, String targetType, String targetId, List<String> evidenceCaseIds) {
        this(summary, targetType, targetId, evidenceCaseIds, List.of(), List.of(), Map.of());
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("target_type", targetType);
        result.put("target_id", targetId);
        result.put("evidence_case_ids", evidenceCaseIds);
        result.put("suspected_modules", suspectedModules);
        result.put("constraints", constraints);
        result.put("metadata", metadata);
        return result;
    }

    /** Reads a diagnosis back from a checkpoint. */
    public static EvolutionDiagnosis fromJson(Map<String, Object> value) {
        return new EvolutionDiagnosis(
                String.valueOf(value.getOrDefault("summary", "")),
                String.valueOf(value.getOrDefault("target_type", "")),
                String.valueOf(value.getOrDefault("target_id", "")),
                EvalStrings.list(value.get("evidence_case_ids")),
                EvalStrings.list(value.get("suspected_modules")),
                EvalStrings.list(value.get("constraints")),
                EvalCase.asMap(value.get("metadata")));
    }
}
