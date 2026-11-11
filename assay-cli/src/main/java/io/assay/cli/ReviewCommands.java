package io.assay.cli;

import io.assay.execution.Stores;
import io.assay.json.Json;
import io.assay.model.Values;
import io.assay.review.ReviewSamples;
import io.assay.store.Results;
import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implements the human-review commands: {@code review}, {@code export}, {@code promote-review}. */
final class ReviewCommands {

    private ReviewCommands() {
    }

    static int review(CommandLine.Namespace args) {
        try (Results store = Stores.open(args.string("store"))) {
            store.saveReview(
                    args.string("run-id"),
                    args.string("case-id"),
                    args.string("decision"),
                    args.string("conclusion"));
        }
        System.out.println("human conclusion saved");
        return 0;
    }

    static int export(CommandLine.Namespace args) {
        String kind = args.string("kind");
        String decision = "regression".equals(kind) ? "confirmed_badcase" : "accepted";
        List<Map<String, Object>> results;
        try (Results store = Stores.open(args.string("store"))) {
            results = store.listReviewedResults(decision);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> result : results) {
            Map<String, Object> row = exportRow(result, kind);
            if (row != null) {
                rows.add(row);
            }
        }
        Path output = args.path("output");
        AssayCli.createParent(output);
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> row : rows) {
            builder.append(Json.write(row)).append('\n');
        }
        AtomicFiles.writeText(output, builder.toString());
        System.out.println("exported " + rows.size() + " records to " + output);
        return 0;
    }

    private static Map<String, Object> exportRow(Map<String, Object> result, String kind) {
        if ("regression".equals(kind)) {
            Map<String, Object> evalCase = Values.asMap(result.get("case"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", evalCase.get("case_id"));
            row.put("scenario", evalCase.get("scenario"));
            row.put("input", evalCase.get("payload"));
            row.put("expected", evalCase.get("expected"));
            row.put("metadata", evalCase.get("metadata"));
            row.put("human_final_conclusion", result.get("human_final_conclusion"));
            return row;
        }
        Map<String, Object> candidate = Values.asMap(result.get("few_shot_candidate"));
        if (candidate.isEmpty()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>(candidate);
        row.put("status", "human_accepted");
        row.put("human_final_conclusion", result.get("human_final_conclusion"));
        return row;
    }

    static int promoteReview(CommandLine.Namespace args) {
        String content = AtomicFiles.readText(args.path("input")).trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("review input is empty");
        }
        Map<String, Object> record = Json.parseObject(content);
        Path output = args.path("output");
        if (Files.exists(output)) {
            record = findExistingRecord(output, record);
        }
        Map<String, Object> promoted = ReviewSamples.promoteReviewRecord(
                record,
                args.string("outcome"),
                args.string("conclusion"),
                args.string("role"),
                args.string("reviewer"),
                args.string("reviewed-at"));
        ReviewSamples.writeReviewRecord(output, promoted);
        Object id = promoted.get("id") != null ? promoted.get("id") : promoted.get("case_id");
        System.out.println("promoted " + id + " to " + output);
        return 0;
    }

    private static Map<String, Object> findExistingRecord(
            Path output, Map<String, Object> fallback) {
        String recordId = String.valueOf(
                fallback.get("id") != null ? fallback.get("id") : fallback.get("case_id"));
        for (String line : AtomicFiles.readText(output).split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> existing = Json.parseObject(line);
            String existingId = String.valueOf(
                    existing.get("id") != null ? existing.get("id") : existing.get("case_id"));
            if (existingId.equals(recordId)) {
                return existing;
            }
        }
        return fallback;
    }
}
