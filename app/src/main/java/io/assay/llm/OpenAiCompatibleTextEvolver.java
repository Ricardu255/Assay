package io.assay.llm;

import io.assay.json.Json;
import io.assay.model.EvolutionDiagnosis;
import io.assay.model.RetryableEvolverException;
import io.assay.model.TextCandidate;
import io.assay.model.TextFileOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An OpenAI-compatible diagnoser and candidate generator.
 *
 * <p>It proposes; the deterministic gates decide. A response that cannot be used raises
 * {@link RetryableEvolverException} so the loop can spend one retry instead of failing the run.
 */
public record OpenAiCompatibleTextEvolver(
        OpenAiCompatibleReviewer client,
        String targetType,
        String targetId,
        String changeType,
        int maxCandidates) {

    public static OpenAiCompatibleTextEvolver fromEnvironment(
            String targetType, String targetId, String changeType, int maxCandidates) {
        OpenAiCompatibleReviewer client = OpenAiCompatibleReviewer.fromEnvironment();
        if (client == null) {
            return null;
        }
        return new OpenAiCompatibleTextEvolver(
                client, targetType, targetId, changeType, Math.max(1, maxCandidates));
    }

    public EvolutionDiagnosis diagnose(Map<String, Object> summary) {
        List<Object> failedResults = new ArrayList<>();
        for (Object rawResult : asList(summary.get("results"))) {
            Map<String, Object> result = asMap(rawResult);
            if (!Boolean.TRUE.equals(result.get("hard_pass")) || intValue(result.get("soft_warning_count")) > 0) {
                failedResults.add(result);
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("target_type", targetType);
        evidence.put("target_id", targetId);
        evidence.put("run_id", summary.get("run_id"));
        evidence.put("failed_results", failedResults);
        String prompt =
                "Diagnose the supplied Agent or Skill evaluation failures. Use only the evidence. "
                        + "Return JSON with summary, evidence_case_ids, suspected_modules, constraints, and metadata. "
                        + "Do not propose production deployment.\n\n"
                        + Json.write(evidence);
        Map<String, Object> result = client.requestJson(prompt);
        if (result.get("parse_error") != null) {
            throw new RetryableEvolverException("diagnosis model did not return valid JSON");
        }
        return new EvolutionDiagnosis(
                String.valueOf(result.getOrDefault("summary", "")).trim(),
                targetType,
                targetId,
                strings(result.get("evidence_case_ids")),
                strings(result.get("suspected_modules")),
                strings(result.get("constraints")),
                asMap(result.get("metadata")));
    }

    public List<TextCandidate> generateCandidates(
            EvolutionDiagnosis diagnosis, Object currentContent, int roundNumber) {
        boolean isDirectory = currentContent instanceof Map;
        String protocol =
                isDirectory
                        ? "For a directory, each candidate must contain summary and an operations array. "
                                + "Each operation is one of: "
                                + "{\"operation\":\"write\",\"path\":\"relative/file\",\"content\":\"complete UTF-8 text\"}, "
                                + "{\"operation\":\"delete\",\"path\":\"relative/file\"}, or "
                                + "{\"operation\":\"move\",\"path\":\"old/relative/file\",\"destination\":\"new/relative/file\"}. "
                                + "Use forward-slash relative paths. Do not emit binary, permission, or symlink changes."
                        : "For a single file, each candidate must contain summary and complete_content.";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("target_type", targetType);
        evidence.put("target_id", targetId);
        evidence.put("change_type", changeType);
        evidence.put("diagnosis", diagnosis.toJson());
        evidence.put("current_content", currentContent);
        evidence.put("maximum_candidates", maxCandidates);
        String prompt =
                "Generate bounded text-artifact candidates from the diagnosis. Preserve existing behavior "
                        + "unless the evidence requires a change. Return JSON with a candidates array. "
                        + protocol
                        + " Do not include markdown fences.\n\n"
                        + Json.write(evidence);
        Map<String, Object> result = client.requestJson(prompt);
        if (result.get("parse_error") != null) {
            throw new RetryableEvolverException("candidate model did not return valid JSON");
        }

        List<TextCandidate> candidates = new ArrayList<>();
        List<Object> proposals = asList(result.get("candidates"));
        int limit = Math.min(proposals.size(), maxCandidates);
        for (int index = 0; index < limit; index++) {
            int position = index + 1;
            Map<String, Object> value = asMap(proposals.get(index));
            List<TextFileOperation> operations;
            try {
                operations = TextFileOperation.listFromJson(value.get("operations"));
            } catch (RuntimeException error) {
                throw new RetryableEvolverException(
                        "candidate model returned invalid file operations: " + error.getMessage(), error);
            }
            if (isDirectory && operations.isEmpty()) {
                throw new RetryableEvolverException("candidate model returned an empty file operation set");
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("generator", "openai-compatible");
            metadata.put("provider", client.provider());
            metadata.put("model", client.model());
            metadata.put("temperature", client.temperature());
            metadata.putAll(asMap(value.get("metadata")));
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            candidates.add(
                    new TextCandidate(
                            "ai-round-" + roundNumber + "-" + position + "-" + suffix,
                            "ai-r" + roundNumber + "-c" + position + "-" + suffix,
                            isDirectory ? "" : String.valueOf(value.getOrDefault("complete_content", "")),
                            String.valueOf(value.getOrDefault("summary", "AI-generated text candidate")),
                            changeType,
                            metadata,
                            Map.of(),
                            isDirectory ? operations : List.of()));
        }
        return candidates;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : asList(value)) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
