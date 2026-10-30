package io.assay.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The flat comparison gate: defaults, severities, rule thresholds, and estimated metrics. */
class CompareGateTest {

    private static Map<String, Object> metrics(double passRate, int softWarnings, Double tokens, Double cost) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("case_count", 4);
        metrics.put("hard_pass_rate", passRate);
        metrics.put("soft_warning_count", softWarnings);
        metrics.put("mean_latency_ms", 20.0);
        metrics.put("p95_latency_ms", 30.0);
        metrics.put("mean_steps", 2.0);
        Map<String, Object> efficiency = new LinkedHashMap<>();
        efficiency.put("mean_total_tokens", tokens);
        efficiency.put("mean_cost_usd", cost);
        metrics.put("efficiency", efficiency);
        metrics.put("rule_stats", Map.of("route", Map.of("pass_rate", 1.0)));
        return metrics;
    }

    private static Map<String, Object> transitions(int regressions, int critical) {
        Map<String, Object> transitions = new LinkedHashMap<>();
        transitions.put("regressed_count", regressions);
        List<Object> rows = new java.util.ArrayList<>();
        for (int index = 0; index < critical; index++) {
            rows.add(Map.of("case_id", "C" + index, "critical", true));
        }
        transitions.put("regressed", rows);
        return transitions;
    }

    @Test
    void defaultsPassEverything() {
        CompareGate.GateOutcome outcome = CompareGate.defaults()
                .evaluate(metrics(1.0, 0, 100.0, 0.001), metrics(1.0, 0, 100.0, 0.001),
                        transitions(0, 0), Map.of());
        assertEquals("PASS", outcome.status());
        assertTrue(outcome.violations().isEmpty());
    }

    @Test
    void blocksOnPassRateDropAndCriticalRegression() {
        CompareGate gate = CompareGate.profile("strict");
        CompareGate.GateOutcome outcome = gate.evaluate(
                metrics(1.0, 0, 100.0, 0.001),
                metrics(0.75, 0, 100.0, 0.001),
                transitions(1, 1),
                Map.of());
        assertEquals("BLOCK", outcome.status());
        assertTrue(outcome.violations().stream().anyMatch(item -> "maximum_hard_pass_rate_drop".equals(item.rule())));
        assertTrue(outcome.violations().stream()
                .anyMatch(item -> "maximum_critical_task_regressions".equals(item.rule())));
    }

    @Test
    void ordinaryTaskRegressionsWarnByDefaultButCanBlock() {
        CompareGate warning = CompareGate.fromDict(Map.of("maximum_task_regressions", 0));
        CompareGate.GateOutcome warned =
                warning.evaluate(metrics(1.0, 0, null, null), metrics(1.0, 0, null, null), transitions(1, 0), Map.of());
        assertEquals("WARNING", warned.status());
        assertEquals("warning", warned.violations().get(0).severity());

        CompareGate blocking = CompareGate.fromDict(Map.of(
                "maximum_task_regressions", 0,
                "severity", Map.of("maximum_task_regressions", "block")));
        CompareGate.GateOutcome blocked =
                blocking.evaluate(metrics(1.0, 0, null, null), metrics(1.0, 0, null, null), transitions(1, 0), Map.of());
        assertEquals("BLOCK", blocked.status());
    }

    @Test
    void configuredRuleMustBeEvaluatedAndMeetItsRate() {
        CompareGate missing = CompareGate.fromDict(Map.of("minimum_rule_pass_rates", Map.of("missing_rule", 0.9)));
        CompareGate.GateOutcome missingOutcome = missing.evaluate(
                metrics(1.0, 0, null, null), metrics(1.0, 0, null, null), transitions(0, 0), Map.of());
        assertEquals("BLOCK", missingOutcome.status());

        Map<String, Object> candidate = metrics(1.0, 0, null, null);
        candidate.put("rule_stats", Map.of("route", Map.of("pass_rate", 0.5)));
        CompareGate low = CompareGate.fromDict(Map.of("minimum_rule_pass_rates", Map.of("route", 0.9)));
        assertEquals("BLOCK", low.evaluate(metrics(1.0, 0, null, null), candidate, transitions(0, 0), Map.of())
                .status());
    }

    @Test
    void estimatedMetricsAreUsedOnlyWhenEnabled() {
        CompareGate gate = CompareGate.fromDict(Map.of(
                "maximum_average_total_tokens", 200,
                "use_estimated_metrics", true));
        Map<String, Object> estimated = Map.of("mean_total_tokens", 300.0, "mean_cost_usd", 0.002);
        CompareGate.GateOutcome outcome = gate.evaluate(
                metrics(1.0, 0, null, null), metrics(1.0, 0, null, null), transitions(0, 0), estimated);
        assertEquals("WARNING", outcome.status());
        assertTrue(outcome.violations().get(0).estimated());
        assertEquals("maximum_average_total_tokens", outcome.violations().get(0).rule());
    }

    @Test
    void missingTelemetryIsInfoRatherThanAWarning() {
        CompareGate gate = CompareGate.fromDict(Map.of("maximum_average_total_tokens", 200));
        CompareGate.GateOutcome outcome = gate.evaluate(
                metrics(1.0, 0, null, null), metrics(1.0, 0, null, null), transitions(0, 0), Map.of());
        assertEquals("PASS", outcome.status());
        assertEquals("info", outcome.violations().get(0).severity());
    }

    @Test
    void unknownOptionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CompareGate.fromDict(Map.of("min_pass_rate", 0.9)));
        assertThrows(IllegalArgumentException.class, () -> CompareGate.fromDict(Map.of("minimum_hard_pass_rate", 1.5)));
    }
}
