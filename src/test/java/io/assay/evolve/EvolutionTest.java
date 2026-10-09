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
                            List.of(new TraceEvent("Router", "route", 2)),
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
                                        "candidate_quality", 0.8),
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
