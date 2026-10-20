package io.assay.evolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Step alignment, field evidence, and the cases where no visible difference exists. */
class TraceDiffTest {

    @Test
    void anInsertedStepDoesNotCascadeIntoLaterSteps() {
        Map<String, Object> baseline = trace("done", List.of(
                event("Planner", "plan", fields()),
                event("Tool", "query_order", fields("order_id", "A1001")),
                event("Tool", "final", fields())));
        Map<String, Object> candidate = trace("done", List.of(
                event("Planner", "plan", fields()),
                event("Planner", "replan", fields()),
                event("Tool", "query_order", fields("order_id", "A1001")),
                event("Tool", "final", fields())));

        Map<String, Object> diff = TraceDiff.compare(baseline, candidate, Map.of());
        Map<String, Object> first = asMap(diff.get("first_divergence"));

        assertEquals("lcs", diff.get("alignment"));
        assertEquals("step_added", first.get("category"));
        assertEquals(2, intOf(first.get("step")));
        assertEquals("replan", asMap(first.get("candidate")).get("action"));
        assertEquals(1, intOf(diff.get("divergence_count")));
    }

    @Test
    void aDifferentToolAtTheSameStepIsAChangedStep() {
        Map<String, Object> baseline = trace("done", List.of(
                event("Planner", "plan", fields()),
                event("Tool", "query_order", fields("order_id", "A1001")),
                event("Tool", "final", fields())));
        Map<String, Object> candidate = trace("done", List.of(
                event("Planner", "plan", fields()),
                event("Tool", "estimate_delivery", fields("order_id", "A1001")),
                event("Tool", "final", fields())));

        Map<String, Object> first = asMap(TraceDiff.compare(baseline, candidate, Map.of()).get("first_divergence"));

        assertEquals("step_changed", first.get("category"));
        assertEquals(2, intOf(first.get("step")));
        assertTrue(str(first.get("reason")).contains("query_order"));
        assertTrue(str(first.get("reason")).contains("estimate_delivery"));
    }

    @Test
    void aChangedArgumentIsReportedAsAFieldPath() {
        Map<String, Object> baseline = trace("done", List.of(
                event("Tool", "query_order", fields("order_id", "A1001", "verbose", true)),
                event("Tool", "final", fields())));
        Map<String, Object> candidate = trace("done", List.of(
                event("Tool", "query_order", fields("order_id", "A1002", "verbose", true)),
                event("Tool", "final", fields())));

        Map<String, Object> diff = TraceDiff.compare(baseline, candidate, Map.of());
        Map<String, Object> first = asMap(diff.get("first_divergence"));

        assertEquals("step_fields", first.get("category"));
        assertEquals(List.of("order_id"), strings(first.get("changed_paths")));
        assertEquals(List.of("A1001"), values(first.get("baseline_values")));
        assertEquals(List.of("A1002"), values(first.get("candidate_values")));
        assertTrue(str(first.get("reason")).contains("fields.order_id"));
    }

    @Test
    void volatileAndConfiguredFieldsDoNotCountAsDifferences() {
        Map<String, Object> baseline = trace("3-5 days", List.of(
                event("Tool", "query_order", fields("span_id", "span-a", "response_id", "r-1", "order_id", "A1001"))),
                fields());
        Map<String, Object> candidate = trace("3-5 days", List.of(
                event("Tool", "query_order", fields("span_id", "span-b", "response_id", "r-2", "order_id", "A1001"))),
                fields());

        Map<String, Object> withSpanOnly = asMap(TraceDiff.compare(baseline, candidate, Map.of()).get("first_divergence"));
        assertEquals("step_fields", withSpanOnly.get("category"));
        assertEquals(List.of("response_id"), strings(withSpanOnly.get("changed_paths")));

        Map<String, Object> ignored = asMap(TraceDiff.compare(
                        baseline, candidate, Map.of("trace_diff_ignore", List.of("response_id")))
                .get("first_divergence"));
        assertEquals("no_visible_divergence", ignored.get("category"));
        assertNull(ignored.get("step"));
    }

    @Test
    void matchingStepsWithADifferentAnswerAreAGenerationDifference() {
        Map<String, Object> baseline = trace("3-5 days", List.of(event("Tool", "query_order", fields())));
        Map<String, Object> candidate = trace("5-7 days", List.of(event("Tool", "query_order", fields())));

        Map<String, Object> diff = TraceDiff.compare(baseline, candidate, Map.of());
        Map<String, Object> first = asMap(diff.get("first_divergence"));

        assertEquals("final_output", first.get("category"));
        assertEquals(List.of("final_output"), strings(first.get("changed_paths")));
        assertEquals(List.of("3-5 days"), values(first.get("baseline_values")));
        assertEquals(List.of("5-7 days"), values(first.get("candidate_values")));
    }

    @Test
    void matchingStepsWithADifferentTraceFieldKeepTheFieldPath() {
        Map<String, Object> baseline = trace("done", List.of(event("Tool", "route", fields())), fields("route", "SUPPORT"));
        Map<String, Object> candidate = trace("done", List.of(event("Tool", "route", fields())), fields("route", "GENERAL"));

        Map<String, Object> first = asMap(TraceDiff.compare(baseline, candidate, Map.of()).get("first_divergence"));

        assertEquals("trace_fields", first.get("category"));
        assertEquals(List.of("route"), strings(first.get("changed_paths")));
        assertEquals(List.of("SUPPORT"), values(first.get("baseline_values")));
        assertEquals(List.of("GENERAL"), values(first.get("candidate_values")));
    }

    @Test
    void aRemovedStepPointsAtTheCandidatePositionWhereItIsMissing() {
        Map<String, Object> baseline = trace("done", List.of(
                event("Planner", "plan", fields()),
                event("Tool", "query_order", fields()),
                event("Tool", "final", fields())));
        Map<String, Object> candidate = trace("done", List.of(
                event("Planner", "plan", fields()),
                event("Tool", "final", fields())));

        Map<String, Object> first = asMap(TraceDiff.compare(baseline, candidate, Map.of()).get("first_divergence"));

        assertEquals("step_removed", first.get("category"));
        assertEquals(2, intOf(first.get("baseline_step")));
        assertEquals(2, intOf(first.get("candidate_step")));
    }

    @Test
    void aMissingTraceIsReportedRatherThanComparedAsEmpty() {
        Map<String, Object> baseline = trace("done", List.of(event("Planner", "plan", fields())));

        Map<String, Object> diff = TraceDiff.compare(baseline, Map.of(), Map.of());
        Map<String, Object> first = asMap(diff.get("first_divergence"));

        assertEquals("missing_trace", first.get("category"));
        assertEquals(0, intOf(diff.get("divergence_count")));
        assertTrue(str(first.get("reason")).contains("candidate"));
    }

    @Test
    void anErrorAtTheSameStepIsReportedBeforeFields() {
        Map<String, Object> baseline = trace("done", List.of(event("Tool", "query_order", fields("order_id", "A1001"))));
        Map<String, Object> failed = event("Tool", "query_order", fields("order_id", "A1002"));
        failed.put("status", "error");
        failed.put("error", "upstream timeout");
        Map<String, Object> candidate = trace("done", List.of(failed));

        Map<String, Object> first = asMap(TraceDiff.compare(baseline, candidate, Map.of()).get("first_divergence"));

        assertEquals("step_error", first.get("category"));
        assertTrue(str(first.get("reason")).contains("upstream timeout"));
    }

    @Test
    void identicalTracesProduceNoVisibleDivergence() {
        Map<String, Object> baseline = trace("done", List.of(event("Planner", "plan", fields())));

        Map<String, Object> diff = TraceDiff.compare(baseline, baseline, Map.of());
        Map<String, Object> first = asMap(diff.get("first_divergence"));

        assertEquals("no_visible_divergence", first.get("category"));
        assertEquals(0, intOf(diff.get("divergence_count")));
        assertTrue(str(first.get("reason")).contains("run-level integrity"));
    }

    private static Map<String, Object> trace(String output, List<Map<String, Object>> events) {
        return trace(output, events, fields());
    }

    private static Map<String, Object> trace(
            String output, List<Map<String, Object>> events, Map<String, Object> fields) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("trace_id", "trace-1");
        trace.put("final_output", output);
        trace.put("events", events);
        trace.put("fields", fields);
        return trace;
    }

    private static Map<String, Object> event(String module, String action, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("module", module);
        event.put("action", action);
        event.put("status", "ok");
        event.put("duration_ms", 1);
        event.put("error", null);
        event.put("fields", fields);
        return event;
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            fields.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return fields;
    }

    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? castMap(value) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                result.add(str(item));
            }
        }
        return result;
    }

    private static List<Object> values(Object value) {
        return value instanceof List<?> items ? new ArrayList<>(items) : List.of();
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
