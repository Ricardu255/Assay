package io.assay.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.store.Results;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The JDBC store must preserve the file store's resume, lock, review, and evolution semantics. */
class JdbcResultStoreTest {

    private JdbcResultStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        store = new JdbcResultStore(dataSource);
    }

    @Test
    void savesCasesAndReadsThemBack() {
        store.startRun("run-1", "adapter", "suite", "online", Map.of("workers", 1), false, Map.of("C1", "hash-1"));
        store.saveCases(List.of(caseResult("run-1", "C1", true), caseResult("run-1", "C2", false)));

        List<Map<String, Object>> results = store.listResults("run-1");
        assertEquals(2, results.size());
        assertEquals("C1", caseId(results.get(0)));
        assertEquals("C2", caseId(results.get(1)));
        assertTrue(store.hasCase("run-1", "C1"));
        assertFalse(store.hasCase("run-1", "missing"));

        store.finishRun("run-1", "passed");
        assertThrows(IllegalArgumentException.class, () -> store.startRun(
                "run-1", "adapter", "suite", "online", Map.of("workers", 1), false, Map.of()));
    }

    @Test
    void listsAndReadsRunRecords() {
        store.startRun("run-record", "adapter", "smoke", "online", Map.of("workers", 1));
        store.finishRun("run-record", "passed");

        Map<String, Object> run = store.getRun("run-record");
        assertEquals("passed", run.get("status"));
        assertEquals("adapter", run.get("adapter"));
        assertEquals("smoke", run.get("suite"));
        assertEquals("online", run.get("source"));
        assertNull(store.getRun("missing"));

        List<Map<String, Object>> runs = store.listRuns();
        assertEquals(1, runs.size());
        assertEquals("run-record", runs.get(0).get("run_id"));
    }

    @Test
    void prunesOldFinishedRunsAndTheirCases() throws InterruptedException {
        for (int index = 1; index <= 3; index++) {
            String runId = "prune-" + index;
            store.startRun(runId, "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash"));
            store.saveCases(List.of(caseResult(runId, "C1", true)));
            store.finishRun(runId, "passed");
            Thread.sleep(5);
        }
        Instant cutoff = Instant.now().plusSeconds(60);

        assertEquals(2, store.pruneRuns(cutoff, 1, true).size());
        assertEquals(3, store.listRuns().size(), "a dry run must not delete anything");
        assertEquals(List.of("prune-2", "prune-1"), store.pruneRuns(cutoff, 1, false));
        int remaining = store.listResults("prune-1").size()
                + store.listResults("prune-2").size()
                + store.listResults("prune-3").size();
        assertEquals(1, remaining);
        assertEquals(1, store.listRuns().size());
        assertEquals("prune-3", store.listRuns().get(0).get("run_id"));
    }

    @Test
    void pruneSkipsRunningRunsAndKeepsTheNewestFinishedRuns() throws InterruptedException {
        store.startRun("running-1", "adapter", "suite", "online", Map.of());

        store.startRun("finished-old", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash"));
        store.saveCases(List.of(caseResult("finished-old", "C1", true)));
        store.finishRun("finished-old", "passed");
        Thread.sleep(5);

        store.startRun("finished-new", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash"));
        store.saveCases(List.of(caseResult("finished-new", "C1", true)));
        store.finishRun("finished-new", "passed");

        Instant cutoff = Instant.now().plusSeconds(60);
        assertEquals(List.of("finished-old"), store.pruneRuns(cutoff, 1, false));
        assertEquals(2, store.listRuns().size());
        assertTrue(runIds().contains("finished-new"));
        assertTrue(runIds().contains("running-1"), "running runs are never pruned");
    }

    @Test
    void resumeValidatesTheManifestAndAllowsAppendOnlyCases() {
        store.startRun("run-2", "adapter", "suite", "online", Map.of("workers", 1), false, Map.of("C1", "hash-1"));
        store.saveCases(List.of(caseResult("run-2", "C1", true)));

        store.startRun(
                "run-2", "adapter", "suite", "online", Map.of("workers", 1), true,
                Map.of("C1", "hash-1", "C2", "hash-2"));
        store.saveCases(List.of(caseResult("run-2", "C2", true)));
        assertEquals(2, store.listResults("run-2").size());

        assertThrows(IllegalArgumentException.class, () -> store.startRun(
                "run-2", "adapter", "suite", "online", Map.of("workers", 1), true, Map.of("C1", "changed")));
        assertThrows(IllegalArgumentException.class, () -> store.startRun(
                "run-2", "adapter", "suite", "online", Map.of("workers", 1), true, Map.of("C2", "hash-2")));
    }

    @Test
    void lockRejectsASecondHolderAndReleasesOnClose() {
        try (Results.RunLock ignored = store.lockRun("run-3")) {
            IllegalStateException busy = assertThrows(IllegalStateException.class, () -> store.lockRun("run-3"));
            assertEquals("already running: evaluation run run-3", busy.getMessage());
        }
        try (Results.RunLock ignored = store.lockRun("run-3")) {
            assertTrue(true);
        }
    }

    @Test
    void reviewsAndEvolutionsRoundTrip() {
        store.startRun("run-4", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash-1"));
        store.saveCases(List.of(caseResult("run-4", "C1", false)));
        store.saveReview("run-4", "C1", "accepted", "looks good");

        List<Map<String, Object>> reviewed = store.listReviewedResults("accepted");
        assertEquals(1, reviewed.size());
        assertEquals("looks good", reviewed.get(0).get("human_final_conclusion"));

        store.saveEvolution("experiment-1", "candidate-1", "accept", Map.of("decision", "accept", "reason", "ok"));
        assertEquals("accept", store.getEvolution("experiment-1").get("decision"));
    }

    @Test
    void onlyTheLatestReviewPerCaseIsListed() throws InterruptedException {
        store.startRun("run-7", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash-1"));
        store.saveCases(List.of(caseResult("run-7", "C1", true)));
        store.saveReview("run-7", "C1", "accepted", "first");
        Thread.sleep(5);
        store.saveReview("run-7", "C1", "rejected", "second");

        assertTrue(store.listReviewedResults("accepted").isEmpty());
        List<Map<String, Object>> rejected = store.listReviewedResults("rejected");
        assertEquals(1, rejected.size());
        assertEquals("second", rejected.get(0).get("human_final_conclusion"));
    }

    @Test
    void addCaseCheckIsIdempotentAndUpdatesTheStoredVerdict() {
        store.startRun("run-5", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash-1"));
        store.saveCases(List.of(caseResult("run-5", "C1", true)));

        store.addCaseCheck(
                "run-5",
                "C1",
                new CheckResult("structure", "hard", "route", false, "GENERAL", "SUPPORT", "route mismatch"));
        store.addCaseCheck(
                "run-5",
                "C1",
                new CheckResult("structure", "hard", "route", false, "GENERAL", "SUPPORT", "route mismatch"));

        Map<String, Object> result = store.listResults("run-5").get(0);
        assertEquals(false, result.get("hard_pass"));
        assertEquals(1, ((List<?>) result.get("checks")).size(), "duplicate checks must not be appended");
    }

    @Test
    void addCaseCheckCountsDistinctSoftWarnings() {
        store.startRun("run-8", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash-1"));
        store.saveCases(List.of(caseResult("run-8", "C1", true)));

        store.addCaseCheck("run-8", "C1", new CheckResult("behavior", "soft", "latency", false));
        store.addCaseCheck("run-8", "C1", new CheckResult("behavior", "soft", "latency", false));
        assertEquals(1, ((Number) store.listResults("run-8").get(0).get("soft_warning_count")).intValue());

        store.addCaseCheck("run-8", "C1", new CheckResult("behavior", "soft", "steps", false));
        assertEquals(2, ((Number) store.listResults("run-8").get(0).get("soft_warning_count")).intValue());
    }

    @Test
    void fileBackedH2SurvivesStoreReconnect(@TempDir Path root) {
        String url = "jdbc:h2:file:"
                + root.resolve("assay-db").toAbsolutePath().toString().replace('\\', '/');
        JdbcResultStore first = JdbcResultStore.fromUrl(url);
        try {
            first.startRun("run-file-1", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash-1"));
            first.saveCases(List.of(caseResult("run-file-1", "C1", true)));
        } finally {
            first.close();
        }

        JdbcResultStore second = JdbcResultStore.fromUrl(url);
        try {
            second.startRun("run-file-2", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash-1"));
            second.saveCases(List.of(caseResult("run-file-2", "C1", true)));
            assertEquals(1, second.listResults("run-file-2").size());
        } finally {
            second.close();
        }
    }

    private List<String> runIds() {
        return store.listRuns().stream().map(run -> String.valueOf(run.get("run_id"))).toList();
    }

    private static CaseResult caseResult(String runId, String caseId, boolean pass) {
        EvalCase evalCase = new EvalCase(
                caseId, Map.of("message", "hello"), Map.of("keyword", "hello"),
                "default", Map.of(), "suite");
        NormalizedTrace trace = new NormalizedTrace(
                "trace-" + caseId,
                "hello",
                List.of(new TraceEvent("Agent", "answer")),
                Map.of("route", pass ? "SUPPORT" : "GENERAL"));
        List<CheckResult> checks = pass
                ? List.of()
                : List.of(new CheckResult("structure", "hard", "route", false, "GENERAL", "SUPPORT"));
        return new CaseResult(runId, "suite", "online", evalCase, trace, checks);
    }

    private static String caseId(Map<String, Object> result) {
        return String.valueOf(((Map<?, ?>) result.get("case")).get("case_id"));
    }
}
