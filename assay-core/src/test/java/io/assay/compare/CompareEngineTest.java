package io.assay.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.report.CompareReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The comparison report: transitions, scenario slices, estimates, and the gate verdict. */
class CompareEngineTest {

    private static Map<String, Object> result(
            String runId, String caseId, String scenario, boolean critical, boolean pass, String route) {
        Map<String, Object> metadata = critical ? Map.of("critical", true) : Map.of();
        EvalCase evalCase = new EvalCase(
                caseId,
                Map.of("message", "request " + caseId),
                Map.of("route", "SUPPORT"),
                scenario,
                metadata,
                "suite");
        NormalizedTrace trace = new NormalizedTrace(
                "trace-" + caseId,
                Map.of("route", route),
                List.of(
                        new TraceEvent("Router", "plan", 1),
                        new TraceEvent("Router", "route", "ok", 2, null, Map.of("route", route))),
                Map.of("route", route));
        CheckResult check = new CheckResult("structure", "hard", "route", pass, route, "SUPPORT");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("suite", "suite");
        row.put("source", "online");
        row.put("case", evalCase.toJson());
        row.put("trace", trace.toJson());
        row.put("checks", List.of(check.toJson()));
        row.put("hard_pass", pass);
        row.put("soft_warning_count", 0);
        return row;
    }

    private static Map<String, Object> summary(String runId, List<Map<String, Object>> results) {
        int failures = 0;
        for (Map<String, Object> result : results) {
            if (!Boolean.TRUE.equals(result.get("hard_pass"))) {
                failures++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("suite", "suite");
        summary.put("source", "online");
        summary.put("status", failures == 0 ? "passed" : "failed");
        summary.put("case_count", results.size());
        summary.put("hard_failures", failures);
        summary.put("soft_warnings", 0);
        summary.put("results", new ArrayList<>(results));
        return summary;
    }

    @Test
    void reportsRegressionsSlicesAndFirstDivergence() {
        Map<String, Object> baseline = summary(
                "baseline",
                List.of(
                        result("baseline", "A", "routing", false, true, "SUPPORT"),
                        result("baseline", "B", "refund", true, true, "SUPPORT")));
        Map<String, Object> candidate = summary(
                "candidate",
                List.of(
                        result("candidate", "A", "routing", false, true, "SUPPORT"),
                        result("candidate", "B", "refund", true, false, "GENERAL")));

        CompareGate gate = CompareGate.fromDict(Map.of(
                "maximum_hard_pass_rate_drop", 0.0,
                "maximum_critical_task_regressions", 0));
        Map<String, Object> report = CompareEngine.compare("baseline", "candidate", baseline, candidate, gate);

        Map<String, Object> transitions = asMap(report.get("transitions"));
        assertEquals(1, transitions.get("regressed_count"));
        assertEquals(0, transitions.get("recovered_count"));

        Map<String, Object> regression = asMap(asList(report.get("regressions")).get(0));
        assertEquals("B", regression.get("case_id"));
        assertEquals(true, regression.get("critical"));
        assertTrue(asMap(regression.get("first_divergence")).containsKey("category"));

        Map<String, Object> refund = asMap(asMap(report.get("scenarios")).get("refund"));
        assertEquals(1, refund.get("regressions"));
        assertEquals(1, refund.get("critical_regressions"));
        assertEquals(0.0, ((Number) refund.get("candidate_hard_pass_rate")).doubleValue(), 0.0001);

        assertEquals("BLOCK", asMap(report.get("gate")).get("status"));
        assertTrue(((Number) asMap(report.get("estimated_efficiency")).get("mean_total_tokens")).doubleValue() > 0);
    }

    @Test
    void markdownIsAOneScreenReleaseReport() {
        Map<String, Object> baseline = summary(
                "baseline", List.of(result("baseline", "A", "routing", false, true, "SUPPORT")));
        Map<String, Object> candidate = summary(
                "candidate", List.of(result("candidate", "A", "routing", false, true, "SUPPORT")));
        Map<String, Object> report = CompareEngine.compare(
                "baseline", "candidate", baseline, candidate, CompareGate.defaults());
        String markdown = CompareReport.markdown(report);

        assertTrue(markdown.contains("# Assay Version Comparison"));
        assertTrue(markdown.contains("## Metrics"));
        assertTrue(markdown.contains("## Scenario slices"));
        assertTrue(markdown.contains("## Regressions"));
        assertTrue(markdown.contains("No case passed"));
        assertTrue(markdown.contains("Gate: **PASS**"));
    }

    @Test
    void recomputesCountersFromCaseRows() {
        Map<String, Object> baseline = summary(
                "baseline",
                List.of(result("baseline", "A", "routing", false, true, "SUPPORT")));
        Map<String, Object> candidate = summary(
                "candidate",
                List.of(result("candidate", "A", "routing", false, false, "SUPPORT")));
        candidate.put("hard_failures", 0);
        candidate.put("status", "passed");

        CompareGate gate = CompareGate.fromDict(Map.of("minimum_hard_pass_rate", 1.0));
        Map<String, Object> report = CompareEngine.compare("baseline", "candidate", baseline, candidate, gate);

        assertEquals("BLOCK", asMap(report.get("gate")).get("status"));
        assertEquals(1, asMap(report.get("candidate")).get("hard_failures"));
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
