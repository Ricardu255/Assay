package io.assay.diff;

import io.assay.json.Json;
import io.assay.support.Immutable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A privacy-safe fingerprint of one Git change.
 *
 * <p>The raw diff is held for deterministic analysis only; {@link #sanitizedSummary()} is the projection
 * that may leave the process.
 */
public record DiffSnapshot(
        String rawDiff,
        List<String> files,
        int additions,
        int deletions,
        Map<String, Integer> categories,
        Map<String, Integer> extensions,
        List<String> signals,
        String baseCommit,
        String targetCommit) {

    public DiffSnapshot {
        files = files == null ? List.of() : Immutable.list(files);
        categories = categories == null ? Map.of() : Immutable.map(categories);
        extensions = extensions == null ? Map.of() : Immutable.map(extensions);
        signals = signals == null ? List.of() : Immutable.list(signals);
    }

    /**
     * The model-facing summary: counts and aggregate labels only, never a path or a source line.
     *
     * <p>Matches the object {@code json.dumps(..., ensure_ascii=False, sort_keys=True)} renders; the
     * privacy sentence is part of the payload so a reader can see why it is safe to share.
     */
    public String sanitizedSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("changed_file_count", files.size());
        summary.put("additions", additions);
        summary.put("deletions", deletions);
        summary.put("file_categories", categories);
        summary.put("file_extensions", extensions);
        summary.put("impact_signals", signals);
        summary.put("privacy", "No source text, values, URLs, or file paths are included.");
        return Json.canonical(summary);
    }
}
