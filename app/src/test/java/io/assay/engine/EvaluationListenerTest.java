package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.CaseResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.TraceEvent;
import io.assay.store.ResultStore;
import io.assay.store.Results;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Observers receive the run/case/batch lifecycle and cannot change the verdict. */
class EvaluationListenerTest {

    @Test
    void emitsTheRunLifecycleAndBatchEvents(@TempDir Path root) {
        List<String> events = new CopyOnWriteArrayList<>();
        EvaluationListener listener = new EvaluationListener() {
            @Override
            public void onRunStarted(String runId, String suite, String source, int caseCount) {
                events.add("run-start:" + caseCount);
            }

            @Override
            public void onCaseStarted(EvalCase evalCase) {
                events.add("case-start:" + evalCase.caseId());
            }

            @Override
            public void onCaseFinished(CaseResult result) {
                events.add("case-finish:" + result.evalCase().caseId() + ":" + result.hardPass());
            }

            @Override
            public void onBatchSaved(int count, long durationNanos) {
                events.add("batch:" + count);
            }

            @Override
            public void onRunFinished(Map<String, Object> summary, long durationNanos) {
                events.add("run-finish");
            }
        };

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            Map<String, Object> summary = new EvaluationEngine(
                            adapter(), store, null, 1, 0, 30, false, null, listener)
                    .runSuite(cases(), "suite", "online", "listener-run", false);

            assertEquals(2, summary.get("case_count"));
        }

        assertTrue(events.contains("run-start:2"), events.toString());
        assertTrue(events.contains("case-start:C1"), events.toString());
        assertTrue(events.contains("case-start:C2"), events.toString());
        assertTrue(events.contains("case-finish:C1:true"), events.toString());
        assertTrue(events.contains("case-finish:C2:true"), events.toString());
        assertTrue(events.stream().anyMatch(event -> event.startsWith("batch:")), events.toString());
        assertEquals("run-finish", events.get(events.size() - 1), events.toString());
    }

    @Test
    void aBrokenListenerCannotBreakTheEvaluation(@TempDir Path root) {
        EvaluationListener broken = new EvaluationListener() {
            @Override
            public void onRunStarted(String runId, String suite, String source, int caseCount) {
                throw new IllegalStateException("metrics backend down");
            }

            @Override
            public void onCaseStarted(EvalCase evalCase) {
                throw new IllegalStateException("metrics backend down");
            }

            @Override
            public void onCaseFinished(CaseResult result) {
                throw new IllegalStateException("metrics backend down");
            }

            @Override
            public void onBatchSaved(int count, long durationNanos) {
                throw new IllegalStateException("metrics backend down");
            }

            @Override
            public void onRunFinished(Map<String, Object> summary, long durationNanos) {
                throw new IllegalStateException("metrics backend down");
            }
        };

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            Map<String, Object> summary = new EvaluationEngine(
                            adapter(), store, null, 1, 0, 30, false, null, broken)
                    .runSuite(cases(), "suite", "online", "broken-listener", false);

            assertEquals(2, summary.get("case_count"));
            assertEquals(0, summary.get("hard_failures"));
        }
    }

    @Test
    void emitsAdapterRulesAndStoreStages(@TempDir Path root) {
        List<String> events = new CopyOnWriteArrayList<>();
        EvaluationListener listener = new EvaluationListener() {
            @Override
            public void onStageStarted(String runId, Stage stage, EvalCase evalCase) {
                events.add("start:" + stage);
            }

            @Override
            public void onStageFinished(
                    String runId,
                    Stage stage,
                    EvalCase evalCase,
                    long durationNanos,
                    RuntimeException error,
                    Map<String, Object> attributes) {
                events.add("finish:" + stage + ":" + (error == null) + ":" + attributes);
            }
        };

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            new EvaluationEngine(
                            adapter(),
                            store,
                            (evalCase, traces, checks, collectFewShot) -> Map.of("summary", "ok"),
                            1,
                            0,
                            30,
                            true,
                            null,
                            listener)
                    .runSuite(List.of(cases().get(0)), "suite", "online", "stage-run", false);
        }

        assertEquals(1, events.stream().filter((event) -> event.equals("start:ADAPTER")).count());
        assertEquals(1, events.stream().filter((event) -> event.equals("start:RULES")).count());
        assertEquals(1, events.stream().filter((event) -> event.equals("start:LLM")).count());
        assertEquals(1, events.stream().filter((event) -> event.equals("start:STORE")).count());
        assertTrue(events.stream().anyMatch((event) -> event.startsWith("finish:ADAPTER:true:")));
        assertTrue(events.stream().anyMatch((event) -> event.startsWith("finish:RULES:true:")));
        assertTrue(events.stream().anyMatch((event) -> event.startsWith("finish:LLM:true:")));
        assertTrue(events.stream().anyMatch((event) -> event.startsWith("finish:STORE:true:")));
    }

    @Test
    void reportsAFailedRunAfterItStarted(@TempDir Path root) {
        List<String> events = new CopyOnWriteArrayList<>();
        EvaluationListener listener = new EvaluationListener() {
            @Override
            public void onRunStarted(String runId, String suite, String source, int caseCount) {
                events.add("started");
            }

            @Override
            public void onRunFinished(Map<String, Object> summary, long durationNanos) {
                events.add("finished");
            }

            @Override
            public void onRunFailed(String runId, RuntimeException error, long durationNanos) {
                events.add("failed");
            }
        };

        try (ResultStore delegate = new ResultStore(root.resolve("store"))) {
            Results failing = (Results) Proxy.newProxyInstance(
                    Results.class.getClassLoader(),
                    new Class<?>[] {Results.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("saveCases")) {
                            throw new IllegalStateException("disk full");
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });

            assertThrows(
                    IllegalStateException.class,
                    () -> new EvaluationEngine(adapter(), failing, null, 1, 0, 30, false, null, listener)
                            .runSuite(cases(), "suite", "online", "failed-listener", false));
        }

        assertEquals(List.of("started", "failed"), events);
    }

    private static ProjectAdapter adapter() {
        return new ProjectAdapter(
                "listener-test",
                (evalCase, context) -> Map.of("trace_id", context.runId() + "-" + evalCase.caseId()),
                (handle, evalCase) -> new NormalizedTrace(
                        String.valueOf(((Map<?, ?>) handle).get("trace_id")),
                        "ok",
                        List.of(new TraceEvent("Agent", "answer")),
                        Map.of()),
                List.of(),
                List.of());
    }

    private static List<EvalCase> cases() {
        return List.of(
                new EvalCase("C1", Map.of("message", "one"), Map.of(), "default", Map.of(), "suite"),
                new EvalCase("C2", Map.of("message", "two"), Map.of(), "default", Map.of(), "suite"));
    }
}
