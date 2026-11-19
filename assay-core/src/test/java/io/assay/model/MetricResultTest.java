package io.assay.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricResultTest {

    @Test
    void rejectsScoresAndThresholdsOutsideTheUnitInterval() {
        IllegalArgumentException score = assertThrows(IllegalArgumentException.class,
                () -> MetricResult.pass("m", 1.2, 0.5, ""));
        assertEquals("score must be finite and between 0 and 1", score.getMessage());

        IllegalArgumentException threshold = assertThrows(IllegalArgumentException.class,
                () -> MetricResult.pass("m", 0.5, -0.1, ""));
        assertEquals("threshold must be finite and between 0 and 1", threshold.getMessage());
    }

    @Test
    void requiresAReasonWhenThereIsNoScore() {
        assertEquals("SKIP metric result requires a reason",
                assertThrows(IllegalArgumentException.class, () -> MetricResult.skip("m", 0.5, " ")).getMessage());
        assertEquals("ERROR metric result requires a reason",
                assertThrows(IllegalArgumentException.class, () -> MetricResult.error("m", "")).getMessage());
    }

    @Test
    void passAndFailRequireFiniteScores() {
        assertEquals("PASS metric result requires a finite score",
                assertThrows(IllegalArgumentException.class,
                        () -> new MetricResult("m", MetricStatus.PASS, Double.NaN, 0.5, "", Map.of(), List.of(),
                                0, 0, null, null, false)).getMessage());
    }

    @Test
    void rendersStatusInLowercaseAndOmitsTheScoreWhenSkipped() {
        Map<String, Object> json = Json.parseObject(Json.write(MetricResult.skip("m", 0.5, "no data")));

        assertEquals("skip", json.get("status"));
        assertEquals(0.5, json.get("threshold"));
        assertFalse(json.containsKey("score"));
        assertEquals("no data", json.get("reason"));
    }

    @Test
    void roundTripsThroughJsonIncludingBreakdownAndUsage() {
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("claims", 3);
        breakdown.put("supported", 2);
        MetricResult original = MetricResult.fail("faithfulness", 0.66, 0.8, "one claim unsupported")
                .withDetails(breakdown, List.of(Map.of("claim", "c1", "verdict", "no")))
                .withUsage(120, 30, new BigDecimal("0.0004"), "judge-v1")
                .cached(true);

        MetricResult restored = MetricResult.fromJson(Json.parseObject(Json.write(original)));

        assertEquals(original.name(), restored.name());
        assertEquals(original.status(), restored.status());
        assertEquals(original.score(), restored.score());
        assertEquals(original.threshold(), restored.threshold());
        assertEquals(original.reason(), restored.reason());
        assertEquals(3, ((Number) restored.breakdown().get("claims")).intValue());
        assertEquals("c1", restored.evidence().get(0).get("claim"));
        assertEquals(120, restored.inputTokens());
        assertEquals(30, restored.outputTokens());
        assertEquals(0, restored.costUsd().compareTo(new BigDecimal("0.0004")));
        assertEquals("judge-v1", restored.model());
        assertTrue(restored.cached());
    }

    @Test
    void copiesEvidenceDefensively() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tool", "shell");
        MetricResult result = MetricResult.fail("m", 0, 1, "denied")
                .withDetails(Map.of(), List.of(row));

        row.put("tool", "mutated");

        assertEquals("shell", result.evidence().get(0).get("tool"));
        assertNull(result.costUsd());
    }
}
