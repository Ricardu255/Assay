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
import io.assay.store.file.ResultStore;
import io.assay.support.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void reviewerAndFewShotSeeRedactedCasePayload(@TempDir Path root) {
        java.util.concurrent.atomic.AtomicReference<EvalCase> seen = new java.util.concurrent.atomic.AtomicReference<>();
        LlmReviewer reviewer = (evalCase, traces, checks, collectFewShot) -> {
            seen.set(evalCase);
            return Map.of("summary", "ok");
        };
        ProjectAdapter adapter = new ProjectAdapter(
                "redact",
                (evalCase, context) -> "handle",
                (handle, evalCase) -> new NormalizedTrace(
                        "redact-trace", "done", List.of(new TraceEvent("agent", "reply")), Map.of()),
                List.of(),
                List.of());
        EvalCase evalCase = new EvalCase(
                "R1",
                Map.of("message", "hi", "api_key", "k-1"),
                Map.of("api_key", "e-1"),
                "default",
                Map.of("password", "p-1"),
                "smoke");
        EvaluationSettings settings = EvaluationSettings.builder().collectFewShot(true).build();

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            Map<String, Object> summary = new EvaluationEngine(adapter, store, reviewer, settings)
                    .runSuite(List.of(evalCase), "smoke", "online", "redact-run", false);

            assertEquals("***", asMap(seen.get().payload()).get("api_key"));
            assertEquals("***", seen.get().expected().get("api_key"));
            assertEquals("***", seen.get().metadata().get("password"));
            Map<String, Object> result = firstResult(summary);
            Map<String, Object> storedCase = asMap(result.get("case"));
            assertEquals("***", asMap(storedCase.get("payload")).get("api_key"));
            Map<String, Object> candidate = asMap(result.get("few_shot_candidate"));
            assertEquals("***", asMap(candidate.get("input")).get("api_key"));
        }
    }

    @Test
    void persistsRedactedTracesAndADatasetHash(@TempDir Path root) {
        ProjectAdapter secret = new ProjectAdapter(
                "secret",
                (evalCase, context) -> "handle",
                (handle, evalCase) -> new NormalizedTrace(
                        "secret-trace",
                        "Bearer abc123",
                        List.of(new TraceEvent(
                                "tool", "call_start", "ok", 0, null, Map.of("password", "p-1"))),
                        Map.of("api_key", "k-1"),
                        Map.of(),
                        "agent",
                        "secret",
                        "1",
                        Map.of()),
                List.of(),
                List.of());

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            Map<String, Object> summary = new EvaluationEngine(secret, store).runSuite(
                    List.of(new EvalCase("S1", Map.of(), Map.of(), "default")),
                    "smoke",
                    "online",
                    "secret-run",
                    false);

            Map<String, Object> trace = asMap(firstResult(summary).get("trace"));
            assertEquals("Bearer ***", trace.get("final_output"));
            assertEquals("***", asMap(trace.get("fields")).get("api_key"));
            Map<String, Object> event = asMap(asList(trace.get("events")).get(0));
            assertEquals("***", asMap(event.get("fields")).get("password"));
            Map<String, Object> run = store.getRun("secret-run");
            String datasetHash = String.valueOf(asMap(run.get("metadata_json")).get("dataset_hash"));
            assertEquals(64, datasetHash.length());
        }
    }

    private static Map<String, Object> firstResult(Map<String, Object> summary) {
        return asMap(asList(summary.get("results")).get(0));
    }

    private static EvaluationEngine engine(ResultStore store, boolean collectFewShot) {
        return new EvaluationEngine(adapter(), store, null, 1, 0, 30, collectFewShot, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
