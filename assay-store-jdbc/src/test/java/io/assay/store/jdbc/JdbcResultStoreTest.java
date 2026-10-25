package io.assay.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.store.Results;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            assertThrows(IllegalStateException.class, () -> store.lockRun("run-3"));
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
    void addCaseCheckUpdatesTheStoredVerdict() {
        store.startRun("run-5", "adapter", "suite", "online", Map.of(), false, Map.of("C1", "hash-1"));
        store.saveCases(List.of(caseResult("run-5", "C1", true)));

        store.addCaseCheck(
                "run-5",
                "C1",
                new CheckResult("structure", "hard", "route", false, "GENERAL", "SUPPORT", "route mismatch"));

        Map<String, Object> result = store.listResults("run-5").get(0);
        assertEquals(false, result.get("hard_pass"));
        assertEquals(1, ((List<?>) result.get("checks")).size());
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
