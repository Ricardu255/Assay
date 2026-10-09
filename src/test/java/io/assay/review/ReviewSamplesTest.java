package io.assay.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import io.assay.testing.Fixtures;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The promotion gate: a sample only becomes a scored case once a person has adjudicated it, and an
 * unresolved one never reaches the datasets the gates read.
 */
class ReviewSamplesTest {

    private static Map<String, Object> record(String id, Map<String, Object> metadata) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("input", Map.of());
        value.put("metadata", metadata);
        return value;
    }

    @Test
    void unresolvedReviewStaysOutOfGateDatasets() {
        Map<String, Object> promoted =
                ReviewSamples.promoteReviewRecord(
                        record("ambiguous", Map.of()),
                        "UNRESOLVED",
                        "business owner cannot decide yet",
                        "pending",
                        "owner-a",
                        "2026-08-31T00:00:00+00:00");

        assertEquals(Map.of(), Fixtures.asMap(promoted.get("expected")));
        Map<String, Object> metadata = Fixtures.asMap(promoted.get("metadata"));
        assertEquals("unresolved", metadata.get("review_status"));
        assertEquals("pending", metadata.get("dataset_role"));
        assertNull(metadata.get("gate_verdict"));
        assertEquals(Boolean.TRUE, metadata.get("human_reviewed"));
    }

    @Test
    void adjudicationPreservesThePreviousGold() {
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("human_review_outcome", "CORRECT");
        previous.put("gold_correction", null);
        previous.put("review_source", "owner-a");
        previous.put("reviewed_at", "first");

        Map<String, Object> promoted =
                ReviewSamples.promoteReviewRecord(
                        record("review-7", previous),
                        "PARTIALLY_CORRECT",
                        "unsupported capability claim",
                        "holdout",
                        "owner-a",
                        "second");

        assertEquals(Map.of("verdict", "INCORRECT"), Fixtures.asMap(promoted.get("expected")));
        Map<String, Object> metadata = Fixtures.asMap(promoted.get("metadata"));
        List<String> outcomes = new ArrayList<>();
        for (Object entry : Fixtures.asList(metadata.get("review_history"))) {
            outcomes.add(Fixtures.str(Fixtures.asMap(entry).get("outcome")));
        }
        assertEquals(List.of("CORRECT", "PARTIALLY_CORRECT"), outcomes);
        assertEquals("unsupported capability claim", metadata.get("gold_correction"));
    }

    /** A CORRECT outcome is a clean regression case, so it gets no gold correction. */
    @Test
    void correctAdjudicationCarriesNoCorrection() {
        Map<String, Object> promoted =
                ReviewSamples.promoteReviewRecord(
                        record("review-9", Map.of()), "CORRECT", "looks right", "regression", "owner-b", "third");

        assertEquals(Map.of("verdict", "CORRECT"), Fixtures.asMap(promoted.get("expected")));
        assertNull(Fixtures.asMap(promoted.get("metadata")).get("gold_correction"));
    }

    /** The caller's record is left alone; promotion returns a new one. */
    @Test
    void promotionDoesNotMutateTheInput() {
        Map<String, Object> original = record("review-8", new LinkedHashMap<>(Map.of("kept", "yes")));
        Map<String, Object> originalMetadata = Fixtures.asMap(original.get("metadata"));

        ReviewSamples.promoteReviewRecord(original, "CORRECT", "looks right", "regression", "owner-b", "third");

        assertEquals(List.of("id", "input", "metadata"), List.copyOf(original.keySet()));
        assertEquals(Map.of("kept", "yes"), originalMetadata);
    }

    @Test
    void rejectsCombinationsThatWouldCorruptTheGate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewSamples.promoteReviewRecord(record("correct", Map.of()), "CORRECT", "correct", "improvement", "owner-a", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewSamples.promoteReviewRecord(record("x", Map.of()), "INCORRECT", "wrong", "regression", "owner-a", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewSamples.promoteReviewRecord(record("x", Map.of()), "UNRESOLVED", "unknown", "holdout", "owner-a", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewSamples.promoteReviewRecord(record("x", Map.of()), "CORRECT", "  ", "regression", "owner-a", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewSamples.promoteReviewRecord(
                        record("x", Map.of()), "CORRECT", "ok", "regression", "  ", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewSamples.promoteReviewRecord(
                        Map.of("input", Map.of()), "CORRECT", "ok", "regression", "owner-a", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewSamples.promoteReviewRecord(record("x", Map.of()), "PROBABLY_FINE", "ok", "regression", "owner-a", null));
    }

    @Test
    void writeReplacesTheSameRecordInsteadOfAppending(@TempDir Path root) {
        Path ledger = root.resolve("reviews.jsonl");
        ReviewSamples.writeReviewRecord(ledger, promoted("review-1", "first conclusion"));
        ReviewSamples.writeReviewRecord(ledger, promoted("review-2", "second sample"));
        ReviewSamples.writeReviewRecord(ledger, promoted("review-1", "revised conclusion"));

        List<String> ids = new ArrayList<>();
        for (String line : AtomicFiles.readText(ledger).split("\n", -1)) {
            if (!line.isBlank()) {
                ids.add(Json.parseObject(line).get("id").toString());
            }
        }
        assertEquals(List.of("review-1", "review-2"), ids);

        String content = AtomicFiles.readText(ledger);
        assertTrue(content.contains("revised conclusion"), content);
        assertFalse(content.contains("first conclusion"), content);
    }

    private static Map<String, Object> promoted(String id, String conclusion) {
        return ReviewSamples.promoteReviewRecord(
                record(id, Map.of("review_history", List.of())),
                "INCORRECT",
                conclusion,
                "holdout",
                "owner-a",
                "stamp-" + id + "-" + conclusion);
    }
}
