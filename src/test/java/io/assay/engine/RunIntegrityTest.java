package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.store.ResultStore;
import io.assay.store.Results;
import io.assay.testing.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards on what a run is allowed to reuse.
 *
 * <p>Resume is only safe when the dataset, the target identity, and the execution configuration all
 * still match. These tests are the reason the framework refuses a resume instead of silently mixing two
 * different experiments into one report.
 */
class RunIntegrityTest {

    private static ProjectAdapter adapter(String name) {
        return new ProjectAdapter(
                name,
                (evalCase, context) -> {
                    Map<String, Object> handle = new java.util.LinkedHashMap<>();
                    handle.put("trace_id", evalCase.caseId());
                    handle.put("route", Fixtures.asMap(evalCase.payload()).get("route"));
                    return handle;
                },
                (handle, evalCase) ->
                        new NormalizedTrace(
                                Fixtures.str(Fixtures.asMap(handle).get("trace_id")),
                                handle,
                                List.of(),
                                Map.of("route", Fixtures.asMap(handle).get("route")),
                                Map.of(),
                                "agent",
                                "default",
                                "unknown",
                                Map.of()),
                List.of(Rule.of("route", "fields.route", "route")),
                List.of());
    }

    private static EvalCase caseOf(String caseId, String route) {
        return new EvalCase(caseId, Map.of("route", route), Map.of("route", route), "integrity", Map.of(), "integrity");
    }

    private static boolean runExists(ResultStore store, String runId) {
        return Files.exists(store.root().resolve("runs").resolve(runId));
    }

    @Test
    void rejectsEmptySuiteBeforeCreatingRun(@TempDir Path root) {
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new EvaluationEngine(adapter("integrity"), store).runSuite(List.of(), "integrity", "online", "empty", false));
            assertTrue(error.getMessage().contains("must not be empty"), error.getMessage());
            assertFalse(runExists(store, "empty"));
        }
    }

    @Test
    void rejectsDuplicateCaseIdsBeforeCreatingRun(@TempDir Path root) {
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new EvaluationEngine(adapter("integrity"), store)
                                            .runSuite(
                                                    List.of(caseOf("DUP", "A"), caseOf("DUP", "B")),
                                                    "integrity",
                                                    "online",
                                                    "duplicate",
                                                    false));
            assertTrue(error.getMessage().contains("duplicate case_id"), error.getMessage());
            assertFalse(runExists(store, "duplicate"));
        }
    }

    @Test
    void resumeRejectsChangedOrMissingCases(@TempDir Path root) {
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationEngine engine = new EvaluationEngine(adapter("integrity"), store, null, 1, 0, 30, false, "integrity-v1");
            engine.runSuite(List.of(caseOf("A", "A"), caseOf("B", "B")), "integrity", "online", "resume", false);

            IllegalArgumentException changed =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> engine.runSuite(List.of(caseOf("A", "CHANGED"), caseOf("B", "B")), "integrity", "online", "resume", true));
            assertTrue(changed.getMessage().contains("changed case_id: A"), changed.getMessage());

            IllegalArgumentException missing =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> engine.runSuite(List.of(caseOf("A", "A")), "integrity", "online", "resume", true));
            assertTrue(missing.getMessage().contains("missing case_id: B"), missing.getMessage());
        }
    }

    @Test
    void resumeAllowsAppendOnlyCases(@TempDir Path root) {
        Map<String, Object> result;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationEngine engine = new EvaluationEngine(adapter("integrity"), store, null, 1, 0, 30, false, "integrity-v1");
            engine.runSuite(List.of(caseOf("A", "A")), "integrity", "online", "append", false);
            result = engine.runSuite(List.of(caseOf("A", "A"), caseOf("B", "B")), "integrity", "online", "append", true);
        }

        assertEquals(2, Fixtures.intOf(result.get("case_count")));
        List<String> caseIds = new ArrayList<>();
        for (Map<String, Object> item : Fixtures.mapsOf(result.get("results"))) {
            caseIds.add(Fixtures.str(Fixtures.asMap(item.get("case")).get("case_id")));
        }
        assertEquals(List.of("A", "B"), caseIds);
    }

    /** A crash can only lose the batch in flight; resume has to replay exactly that batch. */
    @Test
    void batchedResultsSurviveInterruptedRunAndResume(@TempDir Path root) {
        List<EvalCase> cases = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            cases.add(caseOf(String.valueOf(index), "A"));
        }
        List<Integer> batches = new ArrayList<>();
        try (ResultStore inner = new ResultStore(root.resolve("store"))) {
            ResultStore store = inner;
            Results interrupting = new InterruptingStore(inner, batches);
            EvaluationEngine engine = new EvaluationEngine(adapter("integrity"), interrupting, null, 2, 0, 30, false, "batch-v1");

            RuntimeException error =
                    assertThrows(
                            RuntimeException.class,
                            () -> engine.runSuite(cases, "integrity", "online", "batch", false));
            assertTrue(error.getMessage().contains("interrupted before commit"), error.getMessage());
            assertEquals(List.of(4, 4), batches);
            assertEquals(4, store.listResults("batch").size());

            Map<String, Object> resumed = engine.runSuite(cases, "integrity", "online", "batch", true);
            assertEquals("passed", resumed.get("status"));
            assertEquals(8, Fixtures.intOf(resumed.get("case_count")));
        }
    }

    @Test
    void resumeRejectsChangedRunIdentity(@TempDir Path root) {
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            new EvaluationEngine(adapter("integrity"), store, null, 1, 0, 30, false, "integrity-v1")
                    .runSuite(List.of(caseOf("A", "A")), "integrity", "online", "identity", false);

            IllegalArgumentException other =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new EvaluationEngine(adapter("other"), store, null, 1, 0, 30, false, "integrity-v1")
                                            .runSuite(List.of(caseOf("A", "A")), "integrity", "online", "identity", true));
            assertTrue(other.getMessage().contains("run identity"), other.getMessage());

            IllegalArgumentException version =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new EvaluationEngine(adapter("integrity"), store, null, 1, 0, 30, false, "integrity-v2")
                                            .runSuite(List.of(caseOf("A", "A")), "integrity", "online", "identity", true));
            assertTrue(version.getMessage().contains("run identity"), version.getMessage());

            IllegalArgumentException unstable =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new EvaluationEngine(adapter("integrity"), store)
                                            .runSuite(List.of(caseOf("A", "A")), "integrity", "online", "identity", true));
            assertTrue(unstable.getMessage().contains("requires a stable run_identity"), unstable.getMessage());
        }
    }

    /** Two cases sharing one trace id must fail the run, and must not double-report on resume. */
    @Test
    void duplicateTraceIdIsSavedAsHardFailure(@TempDir Path root) {
        ProjectAdapter shared =
                new ProjectAdapter(
                        "shared-trace",
                        (evalCase, context) -> Map.of("route", Fixtures.asMap(evalCase.payload()).get("route")),
                        (handle, evalCase) -> new NormalizedTrace("shared", handle, List.of(), Fixtures.asMap(handle), Map.of(), "agent", "default", "unknown", Map.of()),
                        List.of(Rule.of("route", "fields.route", "route")),
                        List.of());
        Map<String, Object> result;
        Map<String, Object> resumed;
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationEngine engine = new EvaluationEngine(shared, store, null, 1, 0, 30, false, "shared-v1");
            result = engine.runSuite(List.of(caseOf("A", "A"), caseOf("B", "B")), "integrity", "online", "traces", false);
            resumed = engine.runSuite(List.of(caseOf("A", "A"), caseOf("B", "B")), "integrity", "online", "traces", true);
        }

        assertEquals(2, Fixtures.intOf(result.get("hard_failures")));
        assertEquals(2, Fixtures.intOf(resumed.get("hard_failures")));
        for (Map<String, Object> item : Fixtures.mapsOf(resumed.get("results"))) {
            int occurrences = 0;
            for (Map<String, Object> check : Fixtures.mapsOf(item.get("checks"))) {
                if ("unique_trace_id".equals(check.get("name"))) {
                    occurrences++;
                }
            }
            assertEquals(1, occurrences, "unique_trace_id must be recorded once per case");
        }
    }

    /** Fails the second save so the run looks like it died mid-flight. */
    private static final class InterruptingStore implements Results {

        private final ResultStore delegate;
        private final List<Integer> batches;

        private InterruptingStore(ResultStore delegate, List<Integer> batches) {
            this.delegate = delegate;
            this.batches = batches;
        }

        @Override
        public ResultStore.Handle lockRun(String runId) {
            return delegate.lockRun(runId);
        }

        @Override
        public void startRun(String runId, String adapter, String suite, String source, Map<String, Object> metadata) {
            delegate.startRun(runId, adapter, suite, source, metadata);
        }

        @Override
        public void startRun(
                String runId,
                String adapter,
                String suite,
                String source,
                Map<String, Object> metadata,
                boolean resume,
                Map<String, String> caseManifest) {
            delegate.startRun(runId, adapter, suite, source, metadata, resume, caseManifest);
        }

        @Override
        public void finishRun(String runId, String status) {
            delegate.finishRun(runId, status);
        }

        @Override
        public boolean hasCase(String runId, String caseId) {
            return delegate.hasCase(runId, caseId);
        }

        @Override
        public void saveCase(CaseResult result) {
            delegate.saveCase(result);
        }

        @Override
        public void saveCases(List<CaseResult> results) {
            batches.add(results.size());
            if (batches.size() == 2) {
                throw new RuntimeException("interrupted before commit");
            }
            delegate.saveCases(results);
        }

        @Override
        public List<Map<String, Object>> listResults(String runId) {
            return delegate.listResults(runId);
        }

        @Override
        public void addCaseCheck(String runId, String caseId, CheckResult check) {
            delegate.addCaseCheck(runId, caseId, check);
        }

        @Override
        public void saveReview(String runId, String caseId, String decision, String finalConclusion) {
            delegate.saveReview(runId, caseId, decision, finalConclusion);
        }

        @Override
        public List<Map<String, Object>> listReviewedResults(String decision) {
            return delegate.listReviewedResults(decision);
        }

        @Override
        public void saveEvolution(String experimentId, String candidateId, String decision, Map<String, Object> result) {
            delegate.saveEvolution(experimentId, candidateId, decision, result);
        }

        @Override
        public Map<String, Object> getEvolution(String experimentId) {
            return delegate.getEvolution(experimentId);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
