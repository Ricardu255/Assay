package io.assay.review;

import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Promotes a human review into a dataset-ready sample and rewrites the JSONL ledger.
 *
 * <p>A review carries two facts the rest of the pipeline keys on: the {@code dataset_role} that decides
 * where the sample belongs, and the {@code gate_verdict} the automatic gates score against. Both are
 * derived from the human outcome here, and every promotion is appended to {@code review_history} so the
 * path a sample took is auditable.
 */
public final class ReviewSamples {

    public static final Set<String> OUTCOMES =
            Set.of("CORRECT", "PARTIALLY_CORRECT", "INCORRECT", "UNRESOLVED");
    public static final Set<String> ROLES = Set.of("improvement", "regression", "holdout", "pending");

    private ReviewSamples() {
    }

    private static String now() {
        return Instant.now().toString();
    }

    /** Promotes a review sample; returns a shallow copy rather than mutating {@code record}. */
    public static Map<String, Object> promoteReviewRecord(
            Map<String, Object> record,
            String outcome,
            String conclusion,
            String role,
            String reviewer,
            String reviewedAt) {
        outcome = outcome == null ? "" : outcome.toUpperCase(Locale.ROOT);
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("unsupported review outcome: " + outcome);
        }
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("unsupported dataset role: " + role);
        }
        boolean unresolved = "UNRESOLVED".equals(outcome);
        boolean pending = "pending".equals(role);
        if (unresolved != pending) {
            throw new IllegalArgumentException(
                    "UNRESOLVED reviews must stay pending, and pending requires UNRESOLVED");
        }
        if ("regression".equals(role) && !"CORRECT".equals(outcome)) {
            throw new IllegalArgumentException("regression requires a CORRECT human outcome");
        }
        if ("improvement".equals(role)
                && !"PARTIALLY_CORRECT".equals(outcome)
                && !"INCORRECT".equals(outcome)) {
            throw new IllegalArgumentException(
                    "improvement requires a PARTIALLY_CORRECT or INCORRECT outcome");
        }
        if (text(conclusion).strip().isEmpty() || text(reviewer).strip().isEmpty()) {
            throw new IllegalArgumentException("review conclusion and reviewer must not be empty");
        }

        Map<String, Object> promoted = new LinkedHashMap<>(record);
        String caseId = textOf(firstTruthy(promoted.get("id"), promoted.get("case_id"), "")).strip();
        if (caseId.isEmpty()) {
            throw new IllegalArgumentException("review sample requires a non-empty id");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(asMap(promoted.get("metadata")));
        List<Object> history = new ArrayList<>(asList(metadata.get("review_history")));
        Object previous = metadata.get("human_review_outcome");
        if (truthy(previous) && history.isEmpty()) {
            Map<String, Object> prior = new LinkedHashMap<>();
            prior.put("outcome", previous);
            prior.put("conclusion", metadata.get("gold_correction"));
            prior.put("reviewer", metadata.getOrDefault("review_source", "unknown"));
            prior.put("reviewed_at", metadata.get("reviewed_at"));
            history.add(prior);
        }

        String timestamp = reviewedAt == null || reviewedAt.isEmpty() ? now() : reviewedAt;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("outcome", outcome);
        entry.put("conclusion", conclusion);
        entry.put("reviewer", reviewer);
        entry.put("reviewed_at", timestamp);
        history.add(entry);

        Object gateVerdict = unresolved ? null : ("CORRECT".equals(outcome) ? "CORRECT" : "INCORRECT");
        metadata.put("dataset_role", role);
        metadata.put("human_reviewed", true);
        metadata.put("review_status", unresolved ? "unresolved" : "confirmed");
        metadata.put("human_review_outcome", outcome);
        metadata.put("gate_verdict", gateVerdict);
        metadata.put("gold_correction", "CORRECT".equals(outcome) ? null : conclusion);
        metadata.put("reviewed_at", timestamp);
        metadata.put("review_source", reviewer);
        metadata.put("review_history", history);
        promoted.put("metadata", metadata);

        Map<String, Object> expected = new LinkedHashMap<>();
        if (gateVerdict != null) {
            expected.put("verdict", gateVerdict);
        }
        promoted.put("expected", expected);
        return promoted;
    }

    /** Upserts one record into the JSONL ledger by id. */
    public static void writeReviewRecord(Path path, Map<String, Object> record) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (Files.exists(path)) {
            for (String line : AtomicFiles.readText(path).split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                rows.add(Json.parseObject(line));
            }
        }
        String recordId = textOf(firstTruthy(record.get("id"), record.get("case_id")));
        boolean replaced = false;
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            if (textOf(firstTruthy(row.get("id"), row.get("case_id"))).equals(recordId)) {
                rows.set(index, new LinkedHashMap<>(record));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            rows.add(new LinkedHashMap<>(record));
        }
        StringBuilder content = new StringBuilder();
        for (Map<String, Object> row : rows) {
            content.append(Json.write(row)).append('\n');
        }
        AtomicFiles.writeText(path, content.toString());
    }

    /** The first truthy operand, or the last one when none is truthy. */
    private static Object firstTruthy(Object... values) {
        if (values.length == 0) {
            return null;
        }
        for (Object value : values) {
            if (truthy(value)) {
                return value;
            }
        }
        return values[values.length - 1];
    }

    /** Truthiness over the JSON-shaped value set. */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return true;
    }

    /** Renders a value as text. */
    private static String textOf(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        return String.valueOf(value);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }
}
