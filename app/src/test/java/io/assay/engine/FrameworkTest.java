package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import io.assay.report.Reporting;
import io.assay.store.ResultStore;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end coverage of one run: gates, human review, few-shot collection, and release staging. */
class FrameworkTest {

    private static ProjectAdapter adapter() {
        return new ProjectAdapter(
                "test",
                (evalCase, context) -> {
                    Map<String, Object> handle = new LinkedHashMap<>();
                    handle.put("trace_id", context.runId() + "-" + evalCase.caseId());
                    handle.put("output", readMessage(evalCase));
                    handle.put("route", evalCase.metadata().getOrDefault("actual_route", "ECHO"));
                    return handle;
                },
                (handle, evalCase) -> {
                    Map<String, Object> values = asMap(handle);
                    return new NormalizedTrace(
                            String.valueOf(values.get("trace_id")),
                            values.get("output"),
                            List.of(new TraceEvent("EchoAgent", "reply")),
                            Map.of("route", values.get("route")),
                            Map.of(),
                            "skill",
                            "echo",
                            "1",
                            Map.of());
                },
                List.of(Rule.of("route", "fields.route", "route").suspectedModules("Router")),
                List.of());
    }

    private static Object readMessage(EvalCase evalCase) {
        return asMap(evalCase.payload()).get("message");
    }

    @Test
    void hardGateReviewAndFewShot(@TempDir Path root) {
        List<EvalCase> cases =
                List.of(
                        new EvalCase("PASS", Map.of("message", "ok"), Map.of("route", "ECHO"), "direct", Map.of(), "smoke"),
                        new EvalCase(
                                "FAIL",
                                Map.of("message", "bad"),
                                Map.of("route", "ECHO"),
                                "routing",
                                Map.of("actual_route", "OTHER"),
                                "smoke"));

        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("evaluation.db"))) {
            EvaluationEngine engine = engine(store, true);
            summary = engine.runSuite(cases, "smoke", "online", "test-run", false);
            store.saveReview("test-run", "FAIL", "confirmed_badcase", "route mismatch");
            assertEquals(1, store.listReviewedResults("confirmed_badcase").size());
        }
        Reporting.writeRunArtifacts(summary, root.resolve("out"));

        assertEquals(1, summary.get("hard_failures"));
        assertEquals("failed", summary.get("status"));
        assertTrue(AtomicFiles.readText(root.resolve("out/report.md")).contains("Agent Evaluation Report"));
        assertTrue(AtomicFiles.readText(root.resolve("out/scenario_stats.json")).contains("direct"));
        String candidates = AtomicFiles.readText(root.resolve("out/few_shot_candidates.jsonl"));
        assertTrue(candidates.contains("\"source_case_id\": \"PASS\""), candidates);

        EvalCase regressionCase =
                new EvalCase(
                        "REGRESSION-FAIL",
                        Map.of("message", "bad"),
                        Map.of("route", "ECHO"),
                        "routing",
                        Map.of("actual_route", "OTHER"),
                        "regression");
        Map<String, Object> release;
        try (ResultStore store = new ResultStore(root.resolve("release.db"))) {
            release =
                    engine(store, false)
                            .runRelease(
                                    List.of(
                                            new EvaluationEngine.SuiteCases("regression", List.of(regressionCase)),
                                            new EvaluationEngine.SuiteCases("smoke", List.of(cases.get(0)))),
                                    "online",
                                    "release-test");
        }
        assertEquals("failed", release.get("status"));
        assertEquals(1, ((List<?>) release.get("stages")).size());
    }

    /** A case the adapter cannot serve is recorded as a hard failure, not a crash. */
    @Test
    void adapterFailureBecomesAnExecutionErrorCase(@TempDir Path root) {
        ProjectAdapter failing =
                new ProjectAdapter(
                        "failing",
                        (evalCase, context) -> {
                            throw new IllegalStateException("adapter exploded");
                        },
                        (handle, evalCase) -> null,
                        List.of(),
                        List.of());
        Map<String, Object> summary;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            summary =
                    new EvaluationEngine(failing, store)
                            .runSuite(
                                    List.of(new EvalCase("A", Map.of(), Map.of(), "direct")),
                                    "smoke",
                                    "online",
                                    "adapter-error",
                                    false);
        }

        assertEquals(1, summary.get("hard_failures"));
        Map<String, Object> result = asMap(((List<?>) summary.get("results")).get(0));
        assertEquals("adapter exploded", result.get("error"));
        Map<String, Object> check = asMap(asMap(((List<?>) result.get("checks")).get(0)));
        assertEquals("execution_error", check.get("name"));
    }

    private static EvaluationEngine engine(ResultStore store, boolean collectFewShot) {
        return new EvaluationEngine(adapter(), store, null, 1, 0, 30, collectFewShot, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
