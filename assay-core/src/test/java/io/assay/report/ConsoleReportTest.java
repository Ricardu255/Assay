package io.assay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class ConsoleReportTest {

    private static final Map<String, Object> FAILING = Map.of(
            "hard_pass", false,
            "case", Map.of("case_id", "C1", "scenario", "refund"),
            "metrics", List.of(Map.of(
                    "name", "quality", "status", "fail", "score", 0.7, "threshold", 0.8,
                    "reason", "missing evidence")),
            "checks", List.of(
                    Map.of("name", "route", "level", "hard", "passed", false, "message", "route mismatch"),
                    Map.of("name", "trace_id", "level", "hard", "passed", true, "message", "")),
            "trace", Map.of("final_output", "x".repeat(300)));

    private static final Map<String, Object> PASSING = Map.of(
            "hard_pass", true,
            "case", Map.of("case_id", "C2", "scenario", "greeting"),
            "metrics", List.of(Map.of(
                    "name", "quality", "status", "pass", "score", 0.9, "threshold", 0.8, "reason", "fine")),
            "checks", List.of(),
            "trace", Map.of("final_output", "hello"));

    private static final Map<String, Object> SUMMARY = Map.of(
            "run_id", "run-1",
            "status", "failed",
            "suite", "smoke",
            "case_count", 2,
            "hard_failures", 1,
            "soft_warnings", 0,
            "metric_stats", Map.of("quality", Map.of(
                    "level", "soft", "evaluated", 2, "passed", 1, "pass_rate", 0.5,
                    "mean_score", 0.8, "skipped", 0, "errors", 0)),
            "results", List.of(FAILING, PASSING));

    @Test
    void failingShowsOnlyFailingCasesWithMetricAndCheckReasons() {
        String report = ConsoleReport.render(SUMMARY, "failing", 10);

        assertTrue(report.contains("C1"));
        assertFalse(report.contains("C2"));
        assertTrue(report.contains("quality"));
        assertTrue(report.contains("0.700"));
        assertTrue(report.contains("0.800"));
        assertTrue(report.contains("missing evidence"));
        assertTrue(report.contains("route mismatch"));
        assertTrue(report.contains("metrics:"));
        assertTrue(report.contains("50.0%"));
        assertTrue(report.contains("output: " + "x".repeat(160) + "…"));
    }

    @Test
    void passingAndAllFiltersSelectTheRightCases() {
        String passing = ConsoleReport.render(SUMMARY, "passing", 10);
        assertTrue(passing.contains("C2"));
        assertFalse(passing.contains("C1"));

        String all = ConsoleReport.render(SUMMARY, "all", 10);
        assertTrue(all.contains("C1"));
        assertTrue(all.contains("C2"));
    }

    @Test
    void noneIsSilentAndInvalidModesAreRejected() {
        assertEquals("", ConsoleReport.render(SUMMARY, "none", 10));
        assertEquals("", ConsoleReport.render(SUMMARY, null, 10));
        assertThrows(IllegalArgumentException.class, () -> ConsoleReport.render(SUMMARY, "everything", 10));
    }

    @Test
    void caseLimitStopsAfterTheConfiguredCount() {
        String report = ConsoleReport.render(SUMMARY, "all", 1);

        assertTrue(report.contains("C1"));
        assertFalse(report.contains("C2"));
        assertTrue(report.contains("more cases omitted"));
    }
}
