package io.assay.evolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.engine.EvaluationEngine;
import io.assay.model.EvalCase;
import io.assay.model.EvolutionCandidate;
import io.assay.model.EvolutionPolicy;
import io.assay.model.MetricObjective;
import io.assay.model.NormalizedTrace;
import io.assay.model.QualityGate;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.ScenarioGate;
import io.assay.model.TraceEvent;
import io.assay.report.Reporting;
import io.assay.store.ResultStore;
import io.assay.support.AtomicFiles;
import io.assay.testing.Fixtures;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The accept / reject / rollback decision, and the evidence each one records. */
class EvolutionTest {

    private static ProjectAdapter adapter(String version, boolean candidate, boolean breakHoldout) {
        return new ProjectAdapter(
                "router-" + version,
                (evalCase, context) -> {
                    String routeKey = candidate ? "candidate_route" : "baseline_route";
                    Object route = evalCase.metadata().get(routeKey);
                    if (candidate
                            && breakHoldout
                            && "holdout".equals(evalCase.suite())
                            && !Boolean.FALSE.equals(evalCase.metadata().getOrDefault("break_candidate", true))) {
                        route = "BROKEN";
                    }
                    String qualityKey = candidate ? "candidate_quality" : "baseline_quality";
                    Map<String, Object> handle = new LinkedHashMap<>();
                    handle.put("trace_id", context.runId() + "-" + evalCase.caseId());
                    handle.put("route", route);
                    handle.put("quality", evalCase.metadata().get(qualityKey));
                    return handle;
                },
                (handle, evalCase) -> {
                    Map<String, Object> values = Fixtures.asMap(handle);
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("route", values.get("route"));
                    fields.put("quality", values.get("quality"));
                    return new NormalizedTrace(
                            Fixtures.str(values.get("trace_id")),
                            Map.of("route", values.get("route")),
                            List.of(
                                    new TraceEvent("Router", "plan", 1),
                                    new TraceEvent(
                                            "Router", "route", "ok", 2, null,
                                            Map.of("route", values.get("route")))),
                            fields,
                            Map.of(),
                            "skill",
                            "router",
                            version,
                            Map.of());
                },
                List.of(Rule.of("route", "fields.route", "route")),
                List.of());
    }

    private static Map<String, List<EvalCase>> evolutionCases() {
        Map<String, List<EvalCase>> datasets = new LinkedHashMap<>();
        datasets.put(
                "improvement",
                List.of(
                        new EvalCase(
                                "IMPROVE",
                                Map.of("message", "support request"),
                                Map.of("route", "SUPPORT"),
                                "routing",
                                Map.of(
                                        "baseline_route", "OTHER",
                                        "candidate_route", "SUPPORT",
                                        "baseline_quality", 0.2,
                                        "candidate_quality", 0.9),
                                "improvement")));
        datasets.put(
                "regression",
                List.of(
                        new EvalCase(
                                "REGRESSION",
                                Map.of("message", "hello"),
                                Map.of("route", "GENERAL"),
                                "routing",
                                Map.of(
                                        "baseline_route", "GENERAL",
                                        "candidate_route", "GENERAL",
                                        "baseline_quality", 0.8,
                                        "candidate_quality", 0.8),
                                "regression")));
        datasets.put(
                "holdout",
                List.of(
                        new EvalCase(
                                "HOLDOUT",
                                Map.of("message", "unseen support request"),
                                Map.of("route", "SUPPORT"),
                                "routing",
                                Map.of(
                                        "baseline_route", "SUPPORT",
                                        "candidate_route", "SUPPORT",
                                        "baseline_quality", 0.8,
                                        "candidate_quality", 0.8,
                                        "critical", true),
                                "holdout")));
        return datasets;
    }

    private static EvolutionCandidate change() {
        return new EvolutionCandidate("router-v2", "skill", "router", "1", "2", "skill");
    }

    private static EvolutionPolicy qualityPolicy() {
        return new EvolutionPolicy(
                List.of(new MetricObjective("quality", "trace.fields.quality", 0.1)), List.of(), 0);
    }

    /** A required objective with a missing value must fail the whole objective, not average over gaps. */
    @Test
    void requiredMetricNeedsEveryCase() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put(
                "results",
                List.of(
                        Map.of("hard_pass", true, "soft_warning_count", 0, "trace", Map.of("fields", Map.of("cost_usd", 1), "events", List.of())),
                        Map.of("hard_pass", true, "soft_warning_count", 0, "trace", Map.of("fields", Map.of(), "events", List.of()))));
        summary.put("hard_failures", 0);
        summary.put("soft_warnings", 0);

        MetricObjective objective = new MetricObjective("cost", "cost_usd", "minimize", "mean", 0, 0, true);
        Map<String, Object> metrics = EvolutionEngine.summarizeMetrics(summary, List.of(objective));

        assertNull(Fixtures.asMap(metrics.get("objectives")).get("cost"));
    }

    @Test
    void absentTraceDoesNotBecomeZeroLatencyOrZeroModelCalls() {
        Map<String, Object> summary = Map.of("results", List.of(Map.of("hard_pass", false)),
                "hard_failures", 1, "soft_warnings", 0);
        Map<String, Object> metrics = EvolutionEngine.summarizeMetrics(summary,
                List.of(new MetricObjective("latency", "latency_ms"), new MetricObjective("calls", "llm_calls")));

        assertNull(metrics.get("mean_latency_ms"));
        assertNull(metrics.get("mean_steps"));
        assertNull(Fixtures.asMap(metrics.get("objectives")).get("latency"));
        assertNull(Fixtures.asMap(metrics.get("objectives")).get("calls"));
        assertEquals(Map.of("sample_count", 0, "missing_count", 1, "case_count", 1),
                Fixtures.asMap(metrics.get("objective_coverage")).get("latency"));
    }

    @Test
    void measuredZeroRemainsDifferentFromMissingData() {
        Map<String, Object> summary = Map.of("results", List.of(Map.of("hard_pass", true,
                "trace", Map.of("fields", Map.of("cost_usd", 0), "events", List.of()))),
                "hard_failures", 0, "soft_warnings", 0);
        Map<String, Object> metrics = EvolutionEngine.summarizeMetrics(summary,
                List.of(new MetricObjective("latency", "latency_ms"), new MetricObjective("cost", "cost_usd")));

        assertEquals(0.0, metrics.get("mean_latency_ms"));
        assertEquals(0.0, Fixtures.asMap(metrics.get("objectives")).get("cost"));
        assertEquals(0.0, Fixtures.asMap(metrics.get("objectives")).get("latency"));
        assertEquals(Map.of("sample_count", 1, "missing_count", 0, "case_count", 1),
                Fixtures.asMap(metrics.get("metric_coverage")).get("latency_ms"));
    }

    @Test
    void latencyMeanUsesObservedSamplesAndRequiredObjectiveStillRejectsGaps() {
        Map<String, Object> summary = Map.of("results", List.of(
                Map.of("hard_pass", true, "trace", Map.of("events", List.of(Map.of("duration_ms", 100)))),
                Map.of("hard_pass", false)), "hard_failures", 1, "soft_warnings", 0);
        Map<String, Object> metrics = EvolutionEngine.summarizeMetrics(summary,
                List.of(new MetricObjective("latency", "latency_ms")));

        assertEquals(100.0, metrics.get("mean_latency_ms"));
        assertNull(Fixtures.asMap(metrics.get("objectives")).get("latency"));
        assertEquals(Map.of("sample_count", 1, "missing_count", 1, "case_count", 2),
                Fixtures.asMap(metrics.get("metric_coverage")).get("latency_ms"));
    }

    @Test
    void overflowingAggregatesAreMissingRatherThanInfinite() {
        Map<String, Object> result = Map.of("hard_pass", true, "trace", Map.of("fields", Map.of("cost_usd", 1e308)));
        Map<String, Object> summary = Map.of("results", List.of(result, result), "hard_failures", 0, "soft_warnings", 0);
        Map<String, Object> metrics = EvolutionEngine.summarizeMetrics(summary,
                List.of(new MetricObjective("cost", "cost_usd", "minimize", "sum", 0)));
        assertNull(Fixtures.asMap(metrics.get("objectives")).get("cost"));
    }

    @Test
    void missingRequiredLatencyBlocksEvolutionAndAppearsInReport(@TempDir Path root) {
        ProjectAdapter normalCandidate = adapter("2", true, false);
        ProjectAdapter failingCandidate = new ProjectAdapter("failing-candidate", (evalCase, context) -> {
            if ("HOLDOUT".equals(evalCase.caseId())) {
                throw new IllegalStateException("no trace available");
            }
            return normalCandidate.callAgent().call(evalCase, context);
        }, normalCandidate.readTrace(), normalCandidate.hardGates(), normalCandidate.softQuality());
        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result = new EvolutionEngine(new EvaluationEngine(adapter("1", false, false), store),
                    new EvaluationEngine(failingCandidate, store),
                    new EvolutionPolicy(false, List.of(new MetricObjective("latency", "latency_ms", "minimize", "mean", 0))))
                    .run(change(), evolutionCases(), "online", "missing-latency", false);
        }
        assertEquals("rollback", result.get("decision"));
        assertTrue(Fixtures.stringsOf(result.get("reasons")).contains("holdout/latency: required metric is missing"));
        assertNull(Fixtures.asMap(Fixtures.asMap(result.get("comparisons")).get("holdout")).get("mean_latency_ms_delta"));
        Reporting.writeEvolutionArtifacts(result, root.resolve("out"));
        String report = AtomicFiles.readText(root.resolve("out/evolution_report.md"));
        assertTrue(report.contains("missing"));
        assertTrue(report.contains("0/1"));
    }

    @Test
    void acceptsImprovementWithoutRegression(@TempDir Path root) {
        Map<String, Object> result;
        Map<String, Object> stored;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(adapter("1", false, false), store),
                                    new EvaluationEngine(adapter("2", true, false), store),
                                    qualityPolicy())
                            .run(change(), evolutionCases(), "online", "accept-test", false);
            stored = store.getEvolution("accept-test");
        }
        Reporting.writeEvolutionArtifacts(result, root.resolve("out"));

        assertEquals("accept", result.get("decision"));
        assertEquals("accept", stored.get("decision"));
        assertEquals(
                1.0, Fixtures.doubleOf(Fixtures.asMap(Fixtures.asMap(result.get("comparisons")).get("improvement")).get("hard_pass_rate_delta")), 1e-9);
        assertTrue(AtomicFiles.readText(root.resolve("out/evolution_report.md")).contains("Evolution Report"));
    }

    @Test
    void rollsBackHoldoutRegression(@TempDir Path root) {
        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(adapter("1", false, false), store),
                                    new EvaluationEngine(adapter("2", true, true), store),
                                    qualityPolicy())
                            .run(change(), evolutionCases(), "online", "rollback-test", false);
        }

        assertEquals("rollback", result.get("decision"));
        assertTrue(String.join(" ", Fixtures.stringsOf(result.get("reasons")))
                .contains("holdout: hard pass rate exceeded"));
    }

    @Test
    void strictPolicyRejectsAnImprovedButStillFailingHoldout(@TempDir Path root) {
        Map<String, List<EvalCase>> datasets = evolutionCases();
        EvalCase holdout = datasets.get("holdout").get(0);
        Map<String, Object> metadata = new LinkedHashMap<>(holdout.metadata());
        metadata.put("baseline_route", "OTHER");
        metadata.put("candidate_route", "OTHER");
        datasets.put(
                "holdout",
                List.of(new EvalCase(
                        holdout.caseId(), holdout.payload(), holdout.expected(), holdout.scenario(), metadata,
                        holdout.suite())));
        EvolutionPolicy policy = new EvolutionPolicy(true, List.of(new MetricObjective("quality", "trace.fields.quality", 0.1)));

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(adapter("1", false, false), store),
                                    new EvaluationEngine(adapter("2", true, false), store),
                                    policy)
                            .run(change(), datasets, "online", "strict-holdout", false);
        }

        assertEquals("rollback", result.get("decision"));
        assertTrue(Fixtures.stringsOf(result.get("reasons")).contains("holdout: candidate has hard failures"));
    }

    /** The overall holdout rate looks fine; only the gate on the small high-risk slice catches it. */
    @Test
    void scenarioGateBlocksASmallRegressionHiddenByTheTotalRate(@TempDir Path root) {
        Map<String, List<EvalCase>> datasets = evolutionCases();
        EvalCase base = datasets.get("holdout").get(0);
        List<EvalCase> holdout = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
            metadata.put("break_candidate", false);
            holdout.add(new EvalCase(
                    "GENERAL-" + index, base.payload(), base.expected(), "general", metadata, "holdout"));
        }
        holdout.add(new EvalCase(
                "HIGH-RISK", base.payload(), base.expected(), "high-risk", base.metadata(), "holdout"));
        datasets.put("holdout", holdout);

        EvolutionPolicy policy =
                new EvolutionPolicy(
                        true,
                        false,
                        0,
                        0.1,
                        0,
                        true,
                        List.of(new MetricObjective("quality", "trace.fields.quality", 0.1)),
                        List.of(new ScenarioGate("high-risk", List.of("holdout"), 1, 1.0, 0, 0)));

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(adapter("1", false, false), store),
                                    new EvaluationEngine(adapter("2", true, true), store),
                                    policy)
                            .run(change(), datasets, "online", "scenario-gate", false);
        }
        Reporting.writeEvolutionArtifacts(result, root.resolve("out"));
        String report = AtomicFiles.readText(root.resolve("out/evolution_report.md"));

        assertTrue(Fixtures.doubleOf(Fixtures.asMap(Fixtures.asMap(result.get("comparisons")).get("holdout")).get("hard_pass_rate_delta")) > -0.1);
        assertEquals("rollback", result.get("decision"));
        assertTrue(
                Fixtures.stringsOf(result.get("reasons"))
                        .contains("holdout/high-risk: hard pass rate is below the configured minimum"));
        assertTrue(report.contains("| holdout | high-risk | 1 |"), report);
    }

    /** With no objective configured, the only acceptable outcome is a measurable pass-rate win. */
    @Test
    void rejectsCandidatesThatDoNotImprove(@TempDir Path root) {
        Map<String, List<EvalCase>> datasets = evolutionCases();
        EvalCase improvement = datasets.get("improvement").get(0);
        Map<String, Object> metadata = new LinkedHashMap<>(improvement.metadata());
        metadata.put("baseline_route", "SUPPORT");
        datasets.put(
                "improvement",
                List.of(new EvalCase(
                        improvement.caseId(), improvement.payload(), improvement.expected(),
                        improvement.scenario(), metadata, improvement.suite())));

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(adapter("1", false, false), store),
                                    new EvaluationEngine(adapter("2", true, false), store),
                                    new EvolutionPolicy())
                            .run(change(), datasets, "online", "no-improvement", false);
        }

        assertEquals("reject", result.get("decision"));
        assertEquals(
                List.of("candidate produced no measurable improvement"), Fixtures.stringsOf(result.get("reasons")));
    }

    /** A rollback should name the case and the first step that diverged, not only the aggregate drop. */
    @Test
    void recordsARegressedCaseWithItsFirstDivergence(@TempDir Path root) {
        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(adapter("1", false, false), store),
                                    new EvaluationEngine(adapter("2", true, true), store),
                                    qualityPolicy())
                            .run(change(), evolutionCases(), "online", "diagnosis", false);
        }
        Reporting.writeEvolutionArtifacts(result, root.resolve("out"));

        Map<String, Object> holdoutComparison =
                Fixtures.asMap(Fixtures.asMap(result.get("comparisons")).get("holdout"));
        Map<String, Object> transitions = Fixtures.asMap(holdoutComparison.get("case_transitions"));
        assertEquals(1, Fixtures.intOf(transitions.get("regressed_count")));
        assertEquals(0, Fixtures.intOf(transitions.get("recovered_count")));

        Map<String, Object> regression = Fixtures.mapsOf(transitions.get("regressed")).get(0);
        assertEquals("HOLDOUT", regression.get("case_id"));
        assertEquals(true, regression.get("critical"));
        Map<String, Object> first = Fixtures.asMap(regression.get("first_divergence"));
        assertEquals("step_fields", first.get("category"));
        assertEquals(2, Fixtures.intOf(first.get("step")));
        assertTrue(Fixtures.stringsOf(first.get("changed_paths")).contains("route"));
        assertEquals("SUPPORT", Fixtures.str(Fixtures.asList(first.get("baseline_values")).get(0)));
        assertEquals("BROKEN", Fixtures.str(Fixtures.asList(first.get("candidate_values")).get(0)));

        Map<String, Object> routeDelta =
                Fixtures.asMap(Fixtures.asMap(holdoutComparison.get("rule_deltas")).get("route"));
        assertEquals(1.0, Fixtures.doubleOf(Fixtures.asMap(routeDelta.get("baseline")).get("pass_rate")), 1e-9);
        assertEquals(0.0, Fixtures.doubleOf(Fixtures.asMap(routeDelta.get("candidate")).get("pass_rate")), 1e-9);
        assertEquals(-1.0, Fixtures.doubleOf(routeDelta.get("pass_rate_delta")), 1e-9);

        String report = AtomicFiles.readText(root.resolve("out/evolution_report.md"));
        assertTrue(report.contains("Regressions and first divergence"), report);
        assertTrue(report.contains("HOLDOUT"), report);
        assertTrue(report.contains("step_fields"), report);
        assertTrue(report.contains("Rule pass rates"), report);
        assertTrue(report.contains("| holdout | `route` | hard | 100.0% | 0.0% | -100.0% |"), report);
        assertTrue(report.contains("P95 latency samples"), report);
    }

    @Test
    void ruleStatsAggregatePassRatesAcrossChecks() {
        Map<String, Object> summary = Map.of(
                "results", List.of(
                        Map.of("hard_pass", true, "checks", List.of(
                                Map.of("name", "route", "level", "hard", "passed", true)),
                                "trace", Map.of("events", List.of())),
                        Map.of("hard_pass", false, "checks", List.of(
                                Map.of("name", "route", "level", "hard", "passed", false)),
                                "trace", Map.of("events", List.of()))),
                "hard_failures", 1,
                "soft_warnings", 0);

        Map<String, Object> metrics = EvolutionEngine.summarizeMetrics(summary, List.of());
        Map<String, Object> route = Fixtures.asMap(Fixtures.asMap(metrics.get("rule_stats")).get("route"));

        assertEquals(2, Fixtures.intOf(route.get("evaluated")));
        assertEquals(1, Fixtures.intOf(route.get("passed")));
        assertEquals(0.5, Fixtures.doubleOf(route.get("pass_rate")), 1e-9);
        assertEquals(0, Fixtures.intOf(route.get("missing")));
    }

    @Test
    void p95UsesLinearInterpolationAndStaysMissingWithoutSamples() {
        assertEquals(9.55, EvolutionEngine.aggregate(List.of(1.0, 10.0), "p95"), 1e-9);
        assertEquals(5.0, EvolutionEngine.aggregate(List.of(5.0), "p95"), 1e-9);
        assertNull(EvolutionEngine.aggregate(List.of(), "p95"));
        assertEquals(9.55, EvolutionEngine.percentile(List.of(10.0, 1.0), 0.95), 1e-9);
    }

    /** A candidate can match the baseline and still be unacceptable on its own. */
    @Test
    void qualityGateAppliesAnAbsolutePassRateFloor(@TempDir Path root) {
        Map<String, List<EvalCase>> datasets = evolutionCases();
        EvalCase regression = datasets.get("regression").get(0);
        Map<String, Object> metadata = new LinkedHashMap<>(regression.metadata());
        metadata.put("baseline_route", "OTHER");
        metadata.put("candidate_route", "OTHER");
        datasets.put(
                "regression",
                List.of(new EvalCase(
                        regression.caseId(), regression.payload(), regression.expected(),
                        regression.scenario(), metadata, regression.suite())));
        EvolutionPolicy policy = new EvolutionPolicy(
                false,
                false,
                0,
                0,
                0,
                false,
                List.of(),
                List.of(),
                List.of(new QualityGate(List.of("regression"), 0.9, null, null, 1)));

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(adapter("1", false, false), store),
                                    new EvaluationEngine(adapter("2", true, false), store),
                                    policy)
                            .run(change(), datasets, "online", "absolute-floor", false);
        }

        assertEquals("rollback", result.get("decision"));
        assertTrue(Fixtures.stringsOf(result.get("reasons"))
                .contains("regression: hard pass rate 0.0% is below the minimum 90.0%"));
        Reporting.writeEvolutionArtifacts(result, root.resolve("out"));
        String report = AtomicFiles.readText(root.resolve("out/evolution_report.md"));
        assertTrue(report.contains("Configured quality gates"), report);
        assertTrue(report.contains("90.0%"), report);
    }

    /** Equal pass rates can hide a swap; the regression count catches it. */
    @Test
    void qualityGateCountsRegressedCasesWhenTheRateDoesNotMove(@TempDir Path root) {
        Map<String, List<EvalCase>> datasets = evolutionCases();
        EvalCase base = datasets.get("holdout").get(0);
        List<EvalCase> holdout = new ArrayList<>();
        Map<String, Object> regressedMetadata = new LinkedHashMap<>(base.metadata());
        regressedMetadata.put("baseline_route", "GENERAL");
        regressedMetadata.put("candidate_route", "OTHER");
        holdout.add(new EvalCase(
                "SWAP-REGRESSED", base.payload(), Map.of("route", "GENERAL"),
                base.scenario(), regressedMetadata, "holdout"));
        Map<String, Object> recoveredMetadata = new LinkedHashMap<>(base.metadata());
        recoveredMetadata.put("baseline_route", "OTHER");
        recoveredMetadata.put("candidate_route", "SUPPORT");
        holdout.add(new EvalCase(
                "SWAP-RECOVERED", base.payload(), Map.of("route", "SUPPORT"),
                base.scenario(), recoveredMetadata, "holdout"));
        datasets.put("holdout", holdout);
        EvolutionPolicy policy = new EvolutionPolicy(
                false,
                false,
                0,
                0,
                0,
                false,
                List.of(),
                List.of(),
                List.of(new QualityGate(List.of("holdout"), null, 0, null, 2)));

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(adapter("1", false, false), store),
                                    new EvaluationEngine(adapter("2", true, false), store),
                                    policy)
                            .run(change(), datasets, "online", "swap-gate", false);
        }
        Map<String, Object> transitions = Fixtures.asMap(Fixtures.asMap(
                Fixtures.asMap(result.get("comparisons")).get("holdout")).get("case_transitions"));

        assertEquals(0.5, Fixtures.doubleOf(Fixtures.asMap(Fixtures.asMap(
                Fixtures.asMap(result.get("comparisons")).get("holdout")).get("candidate")).get("hard_pass_rate")), 1e-9);
        assertEquals(1, Fixtures.intOf(transitions.get("regressed_count")));
        assertEquals(1, Fixtures.intOf(transitions.get("recovered_count")));
        assertEquals("rollback", result.get("decision"));
        assertTrue(Fixtures.stringsOf(result.get("reasons"))
                .contains("holdout: 1 regressed cases exceed the maximum 0"));
    }

    @Test
    void objectiveMaximumIsAnAbsoluteCeiling(@TempDir Path root) {
        EvolutionPolicy policy = new EvolutionPolicy(
                false,
                false,
                0,
                0,
                0,
                false,
                List.of(new MetricObjective("latency_p95", "latency_ms", "minimize", "p95", 0, 0, false, 1200.0)),
                List.of(),
                List.of());
        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new EvolutionEngine(
                                    new EvaluationEngine(latencyAdapter("1", 2000), store),
                                    new EvaluationEngine(latencyAdapter("2", 2000), store),
                                    policy)
                            .run(change(), evolutionCases(), "online", "absolute-max", false);
        }

        assertEquals("rollback", result.get("decision"));
        assertTrue(Fixtures.stringsOf(result.get("reasons"))
                .contains("improvement/latency_p95: 2000.0 exceeds the absolute maximum 1200.0"));
    }

    private static ProjectAdapter latencyAdapter(String version, double latency) {
        return new ProjectAdapter(
                "latency-" + version,
                (evalCase, context) -> Map.of("trace_id", context.runId() + "-" + evalCase.caseId()),
                (handle, evalCase) -> new NormalizedTrace(
                        Fixtures.str(Fixtures.asMap(handle).get("trace_id")),
                        "ok",
                        List.of(new TraceEvent("LLM", "call", latency)),
                        Map.of("latency_ms", latency),
                        Map.of(),
                        "agent",
                        "latency",
                        version,
                        Map.of()),
                List.of(),
                List.of());
    }

    @Test
    void datasetsMustCoverAllThreeRoles() {
        Map<String, List<EvalCase>> datasets = evolutionCases();
        datasets.remove("holdout");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new EvolutionEngine(null, null, qualityPolicy()).run(change(), datasets, "online", "x", false));
        assertTrue(error.getMessage().contains("holdout"), error.getMessage());
    }
}
