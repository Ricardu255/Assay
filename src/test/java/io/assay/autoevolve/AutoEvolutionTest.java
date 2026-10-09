package io.assay.autoevolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.AutoEvolutionAdapter;
import io.assay.model.EvalCase;
import io.assay.model.EvolutionBudget;
import io.assay.model.EvolutionDiagnosis;
import io.assay.model.EvolutionPolicy;
import io.assay.model.MetricObjective;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.RetryableEvolverException;
import io.assay.model.Rule;
import io.assay.model.TextCandidate;
import io.assay.model.TraceEvent;
import io.assay.report.Reporting;
import io.assay.store.ResultStore;
import io.assay.support.AtomicFiles;
import io.assay.testing.Fixtures;
import io.assay.workspace.TextArtifactWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The automatic loop: budgets, retries, accept/rollback, and resume from a checkpoint. */
class AutoEvolutionTest {

    /** Builds the keyword-driven router that the loop evolves. */
    private static ProjectAdapter buildAdapter(Path artifact, String version) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String keyword : AtomicFiles.readText(artifact).trim().split(",", -1)) {
            if (!keyword.isBlank()) {
                keywords.add(keyword.trim());
            }
        }
        return new ProjectAdapter(
                "router-" + version,
                (evalCase, context) -> {
                    String message = Fixtures.str(Fixtures.asMap(evalCase.payload()).get("message"));
                    String route =
                            keywords.stream().anyMatch(message::contains) ? "SUPPORT" : "GENERAL";
                    double quality = route.equals(Fixtures.str(evalCase.expected().get("route"))) ? 0.9 : 0.2;
                    Map<String, Object> handle = new LinkedHashMap<>();
                    handle.put("trace_id", context.runId() + "-" + evalCase.caseId());
                    handle.put("route", route);
                    handle.put("quality", quality);
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

    private static Map<String, List<EvalCase>> cases() {
        Map<String, List<EvalCase>> datasets = new LinkedHashMap<>();
        datasets.put(
                "improvement",
                List.of(Fixtures.routingCase("IMPROVE", "password reset", "SUPPORT", Map.of(), "improvement")));
        datasets.put(
                "regression",
                List.of(Fixtures.routingCase("REGRESSION", "hello", "GENERAL", Map.of(), "regression")));
        datasets.put(
                "holdout",
                List.of(Fixtures.routingCase("HOLDOUT", "billing issue", "SUPPORT", Map.of(), "holdout")));
        return datasets;
    }

    private static Path baseline(Path root) {
        Path artifact = root.resolve("router.skill");
        AtomicFiles.writeText(artifact, "billing");
        return artifact;
    }

    private static EvolutionPolicy qualityPolicy() {
        return new EvolutionPolicy(
                List.of(new MetricObjective("quality", "trace.fields.quality", 0.1)), List.of(), 0);
    }

    @Test
    void resumeRejectsChangedFrozenDatasets(@TempDir Path root) {
        Path artifact = baseline(root);
        AutoEvolutionAdapter adapter =
                new AutoEvolutionAdapter(
                        "router-auto",
                        artifact,
                        "1",
                        "skill",
                        "router",
                        summary -> new EvolutionDiagnosis("missing route", "skill", "router"),
                        (diagnosis, current, round) ->
                                List.of(new TextCandidate("good", "2", current + ",password", "add password")),
                        AutoEvolutionTest::buildAdapter);
        TextArtifactWorkspace workspace = new TextArtifactWorkspace(root.resolve("workspaces"));
        Map<String, List<EvalCase>> datasets = cases();

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            AutoEvolutionLoop loop = new AutoEvolutionLoop(store, workspace);
            loop.run(adapter, datasets, new EvolutionBudget(), "frozen", "online", false);

            Map<String, List<EvalCase>> changed = new LinkedHashMap<>(datasets);
            List<EvalCase> holdout = new ArrayList<>(changed.get("holdout"));
            holdout.add(Fixtures.evalCase("NEW", Map.of("message", "billing"), Map.of("route", "SUPPORT"), "holdout"));
            changed.put("holdout", holdout);

            IllegalArgumentException datasetsChanged =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> loop.run(adapter, changed, new EvolutionBudget(), "frozen", "online", true));
            assertTrue(datasetsChanged.getMessage().contains("frozen datasets"), datasetsChanged.getMessage());

            IllegalArgumentException policyChanged =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new AutoEvolutionLoop(store, workspace, new EvolutionPolicy(true, List.of()), 1, 0, 30)
                                            .run(adapter, datasets, new EvolutionBudget(), "frozen", "online", true));
            assertTrue(policyChanged.getMessage().contains("frozen policy"), policyChanged.getMessage());
        }
    }

    /** One accepted candidate is not enough while the improvement set still fails. */
    @Test
    void continuesAfterPartialAcceptanceUntilImprovementIsComplete(@TempDir Path root) {
        Path artifact = baseline(root);
        Map<String, List<EvalCase>> datasets = cases();
        datasets.put(
                "improvement",
                List.of(
                        Fixtures.routingCase("PASSWORD", "password reset", "SUPPORT", Map.of(), "improvement"),
                        Fixtures.routingCase("REFUND", "refund request", "SUPPORT", Map.of(), "improvement")));

        AutoEvolutionAdapter adapter =
                new AutoEvolutionAdapter(
                        "router-auto",
                        artifact,
                        "1",
                        "skill",
                        "router",
                        summary -> new EvolutionDiagnosis("missing routes", "skill", "router"),
                        (diagnosis, current, roundNumber) -> {
                            String keyword = roundNumber == 1 ? "password" : "refund";
                            return List.of(new TextCandidate(
                                    "round-" + roundNumber,
                                    String.valueOf(roundNumber + 1),
                                    current + "," + keyword,
                                    "add " + keyword));
                        },
                        AutoEvolutionTest::buildAdapter);

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new AutoEvolutionLoop(store, new TextArtifactWorkspace(root.resolve("workspaces")))
                            .run(
                                    adapter,
                                    datasets,
                                    new EvolutionBudget(2, 1),
                                    "two-rounds",
                                    "online",
                                    false);
        }

        assertEquals("completed", result.get("status"));
        assertEquals("3", result.get("current_version"));
        assertEquals(2, Fixtures.asList(result.get("rounds")).size());
        List<String> decisions = new ArrayList<>();
        for (Map<String, Object> round : Fixtures.mapsOf(result.get("rounds"))) {
            Map<String, Object> candidate = Fixtures.mapsOf(round.get("candidates")).get(0);
            decisions.add(Fixtures.str(Fixtures.asMap(candidate.get("evaluation")).get("decision")));
        }
        assertEquals(List.of("accept", "accept"), decisions);
    }

    @Test
    void retriesOneRetryableEvolverResponseWithinBudget(@TempDir Path root) {
        Path artifact = baseline(root);
        AtomicInteger diagnosisAttempts = new AtomicInteger();
        AutoEvolutionAdapter adapter =
                new AutoEvolutionAdapter(
                        "router-auto",
                        artifact,
                        "1",
                        "skill",
                        "router",
                        summary -> {
                            if (diagnosisAttempts.incrementAndGet() == 1) {
                                throw new RetryableEvolverException("invalid model JSON");
                            }
                            return new EvolutionDiagnosis("missing password routing", "skill", "router");
                        },
                        (diagnosis, current, round) ->
                                List.of(new TextCandidate("good", "2", "billing,password", "extend")),
                        AutoEvolutionTest::buildAdapter);

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new AutoEvolutionLoop(store, new TextArtifactWorkspace(root.resolve("workspaces")))
                            .run(adapter, cases(), new EvolutionBudget(1, 1, 3), "retry-test", "online", false);
        }

        assertEquals("completed", result.get("status"));
        Map<String, Object> usage = Fixtures.asMap(result.get("usage"));
        assertEquals(3, Fixtures.intOf(usage.get("evolver_calls")));
        assertEquals(1, Fixtures.intOf(usage.get("evolver_retries")));
    }

    @Test
    void rejectsRegressionThenAcceptsSafeTextCandidate(@TempDir Path root) {
        Path artifact = baseline(root);
        AutoEvolutionAdapter adapter =
                new AutoEvolutionAdapter(
                        "router-auto",
                        artifact,
                        "1",
                        "skill",
                        "router",
                        summary -> {
                            List<String> failed = new ArrayList<>();
                            for (Map<String, Object> item : Fixtures.mapsOf(summary.get("results"))) {
                                if (!Boolean.TRUE.equals(item.get("hard_pass"))) {
                                    failed.add(Fixtures.str(Fixtures.asMap(item.get("case")).get("case_id")));
                                }
                            }
                            return new EvolutionDiagnosis("missing password routing", "skill", "router", failed);
                        },
                        (diagnosis, current, round) ->
                                List.of(
                                        new TextCandidate("bad", "2-bad", "password", "replace baseline behavior"),
                                        new TextCandidate("good", "2", current + ",password", "extend baseline behavior")),
                        AutoEvolutionTest::buildAdapter);

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new AutoEvolutionLoop(
                                    store, new TextArtifactWorkspace(root.resolve("workspaces")), qualityPolicy(), 1, 0, 30)
                            .run(adapter, cases(), new EvolutionBudget(1, 2), "loop-test", "online", false);
        }
        Reporting.writeAutoEvolutionArtifacts(result, root.resolve("out"));

        List<String> decisions = new ArrayList<>();
        Map<String, Object> firstRound = Fixtures.mapsOf(result.get("rounds")).get(0);
        for (Map<String, Object> candidate : Fixtures.mapsOf(firstRound.get("candidates"))) {
            decisions.add(Fixtures.str(Fixtures.asMap(candidate.get("evaluation")).get("decision")));
        }
        assertEquals(List.of("rollback", "accept"), decisions);
        assertEquals("completed", result.get("status"));
        assertEquals("2", result.get("current_version"));
        assertEquals(1, Fixtures.intOf(Fixtures.asMap(Fixtures.asMap(result.get("datasets")).get("improvement")).get("case_count")));
        assertEquals(64, Fixtures.str(Fixtures.asMap(Fixtures.asMap(result.get("datasets")).get("improvement")).get("sha256")).length());
        assertEquals("billing", AtomicFiles.readText(artifact));
        assertEquals("billing,password", AtomicFiles.readText(Path.of(Fixtures.str(result.get("current_artifact")))));
        assertTrue(Files.isRegularFile(root.resolve("out/auto_evolution_report.md")));
    }

    @Test
    void stopsBeforeCandidateGenerationAtEvolverCallBudget(@TempDir Path root) {
        Path artifact = baseline(root);
        AutoEvolutionAdapter adapter =
                new AutoEvolutionAdapter(
                        "router-auto",
                        artifact,
                        "1",
                        "skill",
                        "router",
                        summary -> new EvolutionDiagnosis("missing route", "skill", "router"),
                        (diagnosis, current, round) -> {
                            throw new AssertionError("candidate generation must not exceed the budget");
                        },
                        AutoEvolutionTest::buildAdapter);

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new AutoEvolutionLoop(store, new TextArtifactWorkspace(root.resolve("workspaces")))
                            .run(adapter, cases(), new EvolutionBudget(1, 1, 1), "budget-test", "online", false);
        }

        assertEquals("evolver_call_budget_exhausted", result.get("status"));
        assertEquals(1, Fixtures.intOf(Fixtures.asMap(result.get("usage")).get("evolver_calls")));
        assertTrue(Files.isRegularFile(root.resolve("workspaces/budget-test/checkpoint.json")));
    }

    @Test
    void timeBudgetStopsBeforeTheFirstRound(@TempDir Path root) {
        Path artifact = baseline(root);
        AutoEvolutionAdapter adapter =
                new AutoEvolutionAdapter(
                        "router-auto",
                        artifact,
                        "1",
                        "skill",
                        "router",
                        summary -> {
                            throw new AssertionError("diagnosis must not run after time is exhausted");
                        },
                        (diagnosis, current, round) -> List.of(),
                        AutoEvolutionTest::buildAdapter);

        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            result =
                    new AutoEvolutionLoop(store, new TextArtifactWorkspace(root.resolve("workspaces")))
                            .run(adapter, cases(), new EvolutionBudget(1, 1, 0.000001, null), "time-test", "online", false);
        }

        assertEquals("time_budget_exhausted", result.get("status"));
    }

    /** A candidate that was staged before the failure must be reused, not regenerated. */
    @Test
    void resumeReusesTheStagedCandidateAfterAdapterFailure(@TempDir Path root) {
        Path artifact = baseline(root);
        AtomicBoolean failCandidateOnce = new AtomicBoolean(true);
        AtomicInteger generateCalls = new AtomicInteger();

        AutoEvolutionAdapter adapter =
                new AutoEvolutionAdapter(
                        "router-auto",
                        artifact,
                        "1",
                        "skill",
                        "router",
                        summary -> new EvolutionDiagnosis("missing password routing", "skill", "router"),
                        (diagnosis, current, round) -> {
                            generateCalls.incrementAndGet();
                            return List.of(new TextCandidate(
                                    "good-" + generateCalls.get(), "2", current + ",password", "extend"));
                        },
                        (candidateArtifact, version) -> {
                            if ("2".equals(version) && failCandidateOnce.compareAndSet(true, false)) {
                                throw new IllegalStateException("temporary candidate startup failure");
                            }
                            return buildAdapter(candidateArtifact, version);
                        });

        Map<String, Object> failed;
        Map<String, Object> resumed;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            AutoEvolutionLoop loop = new AutoEvolutionLoop(store, new TextArtifactWorkspace(root.resolve("workspaces")));
            failed = loop.run(adapter, cases(), new EvolutionBudget(1, 1, 2), "resume-test", "online", false);
            resumed = loop.run(adapter, cases(), new EvolutionBudget(1, 1, 4), "resume-test", "online", true);
        }

        assertEquals("failed", failed.get("status"));
        assertEquals("candidate_evaluation", failed.get("failed_phase"));
        assertEquals("completed", resumed.get("status"));
        assertEquals("2", resumed.get("current_version"));
        assertEquals(2, Fixtures.intOf(Fixtures.asMap(resumed.get("usage")).get("evolver_calls")));
        assertEquals(1, generateCalls.get());
        Map<String, Object> firstRound = Fixtures.mapsOf(resumed.get("rounds")).get(0);
        Map<String, Object> firstCandidate = Fixtures.mapsOf(firstRound.get("candidates")).get(0);
        assertEquals("good-1", Fixtures.str(Fixtures.asMap(firstCandidate.get("candidate")).get("candidate_id")));
    }

    @Test
    void requiresNonEmptyDatasetsAndAnExistingBaseline(@TempDir Path root) {
        AutoEvolutionAdapter missingBaseline =
                new AutoEvolutionAdapter(
                        "router-auto",
                        root.resolve("absent.skill"),
                        "1",
                        "skill",
                        "router",
                        summary -> new EvolutionDiagnosis("x", "skill", "router"),
                        (diagnosis, current, round) -> List.of(),
                        AutoEvolutionTest::buildAdapter);

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            AutoEvolutionLoop loop = new AutoEvolutionLoop(store, new TextArtifactWorkspace(root.resolve("workspaces")));
            IllegalArgumentException emptyDatasets =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> loop.run(missingBaseline, Map.of(), new EvolutionBudget(), "empty", "online", false));
            assertTrue(emptyDatasets.getMessage().contains("non-empty"), emptyDatasets.getMessage());

            IllegalArgumentException absent =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> loop.run(missingBaseline, cases(), new EvolutionBudget(), "absent", "online", false));
            assertTrue(absent.getMessage().contains("baseline artifact does not exist"), absent.getMessage());
        }
    }
}
