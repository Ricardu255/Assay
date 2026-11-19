package io.assay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class CalibrationReportTest {

    @Test
    void buildsAConfusionMatrixFromHumanDecisionsAndHardPass() {
        List<Map<String, Object>> results = List.of(
                Map.of("human_decision", "confirmed_badcase", "hard_pass", false),
                Map.of("human_decision", "accepted", "hard_pass", false),
                Map.of("human_decision", "confirmed_badcase", "hard_pass", true),
                Map.of("human_decision", "accepted", "hard_pass", true));

        Map<String, Object> report = CalibrationReport.compute(results, null);

        assertEquals("hard_pass", report.get("verdict"));
        assertEquals(4, report.get("recognized"));
        Map<String, Object> matrix = map(report.get("matrix"));
        assertEquals(1, matrix.get("true_positive"));
        assertEquals(1, matrix.get("false_positive"));
        assertEquals(1, matrix.get("false_negative"));
        assertEquals(1, matrix.get("true_negative"));
        assertEquals(0.5, (double) report.get("precision"), 1e-9);
        assertEquals(0.5, (double) report.get("recall"), 1e-9);
        assertEquals(0.5, (double) report.get("f1"), 1e-9);
        assertEquals(0.5, (double) report.get("agreement"), 1e-9);
        assertTrue(CalibrationReport.markdown(report).contains("| Auto: bad | 1 | 1 |"));
    }

    @Test
    void metricModeUsesTheMetricStatusAndExcludesUnmeasurableCases() {
        List<Map<String, Object>> results = List.of(
                Map.of("human_decision", "confirmed_badcase", "metrics", List.of(
                        Map.of("name", "groundedness", "status", "fail"))),
                Map.of("human_decision", "accepted", "metrics", List.of(
                        Map.of("name", "groundedness", "status", "pass"))),
                Map.of("human_decision", "accepted", "metrics", List.of(
                        Map.of("name", "groundedness", "status", "skip"))),
                Map.of("human_decision", "accepted", "metrics", List.of(
                        Map.of("name", "other", "status", "fail"))));

        Map<String, Object> report = CalibrationReport.compute(results, "groundedness");

        assertEquals("metric:groundedness", report.get("verdict"));
        assertEquals(2, report.get("recognized"));
        assertEquals(2, report.get("not_applicable"));
        Map<String, Object> matrix = map(report.get("matrix"));
        assertEquals(1, matrix.get("true_positive"));
        assertEquals(1, matrix.get("true_negative"));
    }

    @Test
    void aFalsePositiveReviewCountsAsAHumanGoodVerdict() {
        Map<String, Object> report = CalibrationReport.compute(
                List.of(Map.of("human_decision", "false_positive", "hard_pass", false)), null);

        Map<String, Object> matrix = map(report.get("matrix"));
        assertEquals(1, matrix.get("false_positive"));
        assertEquals(0, matrix.get("true_negative"));
    }

    @Test
    void unrecognizedDecisionsAndEmptyInputDoNotProduceFakeRates() {
        Map<String, Object> report = CalibrationReport.compute(
                List.of(Map.of("human_decision", "maybe", "hard_pass", false)), null);
        assertEquals(1, report.get("unrecognized_decision"));
        assertEquals(0, report.get("recognized"));
        assertNull(report.get("precision"));
        assertNull(report.get("agreement"));

        Map<String, Object> empty = CalibrationReport.compute(List.of(), null);
        assertEquals(0, empty.get("samples"));
        assertNull(empty.get("precision"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
