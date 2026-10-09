package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.assay.testing.Fixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Agreement metrics over one run, and stability across repeated runs. */
class EvaluatorMetricsTest {

    private static Map<String, Object> result(String caseId, String expected, String actual) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case", Map.of("case_id", caseId, "expected", Map.of("verdict", expected)));
        row.put("trace", Map.of("fields", Map.of("verdict", actual)));
        return row;
    }

    @Test
    void reportsAgreementFalsePositivesAndFalseNegatives() {
        Map<String, Object> metrics =
                EvaluatorMetrics.classificationMetrics(
                        List.of(
                                result("a", "CORRECT", "CORRECT"),
                                result("b", "CORRECT", "INCORRECT"),
                                result("c", "INCORRECT", "CORRECT")));

        assertEquals(1.0 / 3, Fixtures.doubleOf(metrics.get("agreement_rate")), 1e-12);
        assertEquals(1, Fixtures.intOf(metrics.get("false_positive_count")));
        assertEquals(1, Fixtures.intOf(metrics.get("false_negative_count")));
        assertEquals(1.0 / 3, Fixtures.doubleOf(Fixtures.asMap(metrics.get("field_accuracy")).get("verdict")), 1e-12);
    }

    @Test
    void repeatStabilityComparesTheSameCases() {
        Map<String, Object> stability =
                EvaluatorMetrics.repeatStability(
                        List.of(
                                List.of(result("a", "CORRECT", "CORRECT"), result("b", "CORRECT", "CORRECT")),
                                List.of(result("a", "CORRECT", "CORRECT"), result("b", "CORRECT", "INCORRECT"))));

        assertEquals(2, Fixtures.intOf(stability.get("comparable_case_count")));
        assertEquals(0.5, Fixtures.doubleOf(stability.get("stability_rate")), 1e-12);
    }

    /** A rate with no denominator is reported as absent, not as zero. */
    @Test
    void skipsCasesWithoutAUsableVerdict() {
        Map<String, Object> noTrace = new LinkedHashMap<>();
        noTrace.put("case", Map.of("case_id", "nottraced", "expected", Map.of("verdict", "CORRECT")));

        Map<String, Object> metrics =
                EvaluatorMetrics.classificationMetrics(
                        List.of(result("unresolved", "UNRESOLVED", "CORRECT"), noTrace));

        assertEquals(2, Fixtures.intOf(metrics.get("case_count")));
        assertEquals(0, Fixtures.intOf(metrics.get("evaluated_count")));
        assertNull(metrics.get("agreement_rate"));
        assertEquals(Map.of(), Fixtures.asMap(metrics.get("predictions")));
    }

    @Test
    void aSingleRunHasNoStabilityToReport() {
        Map<String, Object> stability =
                EvaluatorMetrics.repeatStability(List.of(List.of(result("a", "CORRECT", "CORRECT"))));

        assertEquals(1, Fixtures.intOf(stability.get("run_count")));
        assertEquals(0, Fixtures.intOf(stability.get("comparable_case_count")));
        assertNull(stability.get("stability_rate"));
    }

    /**
     * Field accuracy only covers cases that were scored at all, only counts fields the trace actually
     * carries, and reports them in name order.
     */
    @Test
    void scoresPresentFieldsInNameOrder() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(
                "case",
                Map.of(
                        "case_id", "a",
                        "expected", Map.of("verdict", "CORRECT", "zebra", 1, "alpha", "x", "missing", true)));
        row.put("trace", Map.of("fields", Map.of("verdict", "CORRECT", "alpha", "x", "zebra", 2)));

        Map<String, Object> accuracy =
                Fixtures.asMap(EvaluatorMetrics.classificationMetrics(List.of(row)).get("field_accuracy"));

        assertEquals(List.of("alpha", "verdict", "zebra"), List.copyOf(accuracy.keySet()));
        assertEquals(1.0, Fixtures.doubleOf(accuracy.get("alpha")), 1e-12);
        assertEquals(1.0, Fixtures.doubleOf(accuracy.get("verdict")), 1e-12);
        assertEquals(0.0, Fixtures.doubleOf(accuracy.get("zebra")), 1e-12);
    }
}
