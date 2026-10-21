package io.assay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleStatsTest {

    @Test
    void aggregatesChecksAndTracksMissingSamples() {
        Map<String, Object> results = RuleStats.fromResults(List.of(
                Map.of("checks", List.of(
                        Map.of("name", "route", "level", "hard", "passed", true),
                        Map.of("name", "tone", "level", "soft", "passed", false))),
                Map.of("checks", List.of(
                        Map.of("name", "route", "level", "hard", "passed", false),
                        Map.of("name", "tone", "level", "soft", "passed", true)))), 3);
        Map<String, Object> route = row(results, "route");
        assertEquals("hard", route.get("level"));
        assertEquals(2, route.get("evaluated"));
        assertEquals(1, route.get("passed"));
        assertEquals(0.5, route.get("pass_rate"));
        assertEquals(1, route.get("missing"));

        Map<String, Object> tone = row(results, "tone");
        assertEquals("soft", tone.get("level"));
        assertEquals(2, tone.get("evaluated"));
        assertEquals(0.5, tone.get("pass_rate"));
        assertEquals(1, tone.get("missing"));
    }

    @Test
    void ignoresBlankNamesAndHandlesNoChecks() {
        Map<String, Object> results = RuleStats.fromResults(List.of(
                Map.of("checks", List.of(Map.of("name", " ", "passed", true))),
                Map.of()), 2);
        assertEquals(0, results.size());
        assertNull(RuleStats.fromResults(List.of(), 1).get("missing"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> stats, String name) {
        return (Map<String, Object>) stats.get(name);
    }
}
