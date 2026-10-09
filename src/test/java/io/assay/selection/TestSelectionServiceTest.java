package io.assay.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.diff.DiffAnalysis;
import io.assay.diff.DiffSnapshot;
import io.assay.model.TestImpactAssessment;
import io.assay.model.TestSelection;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How much testing a change needs.
 *
 * <p>The deterministic rules are the floor: they run with no model at all, and the AI layer can only
 * raise the recommendation. These tests pin both halves, plus the data boundary that stops a raw diff
 * from leaving the machine.
 */
class TestSelectionServiceTest {

    /** Records the prompt so a test can assert exactly what would have left the process. */
    private static class FakeReviewer implements TestSelectionService.JsonReviewer {

        private final Map<String, Object> result;
        private final String baseUrl;
        private String prompt = "";

        private FakeReviewer(Map<String, Object> result, String baseUrl) {
            this.result = result;
            this.baseUrl = baseUrl;
        }

        @Override
        public String baseUrl() {
            return baseUrl;
        }

        @Override
        public Map<String, Object> requestJson(String prompt) {
            this.prompt = prompt;
            return result;
        }
    }

    private static FakeReviewer localReviewer(Map<String, Object> result) {
        return new FakeReviewer(result, "http://127.0.0.1:11434/v1");
    }

    private static FakeReviewer remoteReviewer(Map<String, Object> result) {
        return new FakeReviewer(result, "https://example.com/v1");
    }

    private static Map<String, Object> assessment(String mode, double confidence, String risk) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", mode);
        value.put("confidence", confidence);
        value.put("risk", risk);
        value.put("reasons", List.of("because"));
        return value;
    }

    private static DiffSnapshot snapshot(String... files) {
        Map<String, Integer> categories = new LinkedHashMap<>();
        for (String path : files) {
            categories.merge(DiffAnalysis.category(path), 1, Integer::sum);
        }
        return new DiffSnapshot(
                "secret-value",
                List.of(files),
                1,
                0,
                categories,
                Map.of(".java", files.length),
                new ArrayList<>(categories.keySet()),
                null,
                null);
    }

    private static Path initRepository(Path root) {
        DiffAnalysis.git(root, "init", "-q");
        DiffAnalysis.git(root, "config", "user.email", "test@example.com");
        DiffAnalysis.git(root, "config", "user.name", "Test");
        AtomicFiles.writeText(root.resolve("README.md"), "baseline\n");
        DiffAnalysis.git(root, "add", "--", "README.md");
        DiffAnalysis.git(root, "commit", "-qm", "baseline");
        return root;
    }

    private static void write(Path root, String relative, String content) {
        AtomicFiles.writeText(root.resolve(relative), content);
    }

    @Test
    void documentationAndUiChangesOnlyNeedSmoke() {
        assertEquals("smoke", DiffAnalysis.assessRules(snapshot("README.md", "ui/page.css")).mode());
        assertEquals("low", DiffAnalysis.assessRules(snapshot("README.md")).risk());
    }

    @Test
    void promptChangesAreHighRiskRegression() {
        TestImpactAssessment assessment = DiffAnalysis.assessRules(snapshot("prompts/planner.txt"));

        assertEquals("regression", assessment.mode());
        assertEquals("high", assessment.risk());
        assertEquals("rules", assessment.source());
    }

    @Test
    void retrievalChangesNeedTheFullSuite() {
        assertEquals("full", DiffAnalysis.assessRules(snapshot("src/rag/retriever.py")).mode());
    }

    /** A path is classified by what it is, not by a word appearing anywhere in it. */
    @Test
    void fileNamesContainingTestAreNotMistakenForTestAssets() {
        assertEquals("full", DiffAnalysis.assessRules(snapshot("src/agent_eval/test_selection.py")).mode());
        assertEquals("regression", DiffAnalysis.assessRules(snapshot("src/contest/payments.py")).mode());
        assertEquals("tests", DiffAnalysis.category("tests/orders/fixtures.json"));
        assertEquals("source", DiffAnalysis.category("src/contest/payments.py"));
    }

    @Test
    void localAiReceivesTheRawDiff(@TempDir Path root) {
        initRepository(root);
        write(root, "README.md", "secret-value\n");
        FakeReviewer reviewer = localReviewer(assessment("smoke", 0.9, "low"));

        TestSelection selection =
                TestSelectionService.selectTests(
                        root, "HEAD", "local", "auto", reviewer, 0.7, null);

        assertTrue(reviewer.prompt.contains("secret-value"), "a local endpoint gets the complete diff");
        assertEquals("smoke", selection.mode());
        assertFalse(selection.humanReviewRequired());
        assertEquals(List.of("smoke"), selection.suites());
        assertEquals(1, selection.changedFiles());
    }

    @Test
    void remoteAiReceivesOnlyASanitizedSummary(@TempDir Path root) {
        initRepository(root);
        write(root, "private-secret.py", "API_KEY = 'secret-value'\n");
        FakeReviewer reviewer = remoteReviewer(assessment("regression", 0.9, "medium"));

        TestSelectionService.selectTests(root, "HEAD", "remote", "auto", reviewer, 0.7, null);

        assertFalse(reviewer.prompt.contains("secret-value"), reviewer.prompt);
        assertFalse(reviewer.prompt.contains("private-secret.py"), reviewer.prompt);
        assertTrue(reviewer.prompt.contains("changed_file_count"), reviewer.prompt);
    }

    /** The AI may raise the bar; lowering it is not its call. */
    @Test
    void aiCannotDowngradeTheRuleFloor(@TempDir Path root) {
        initRepository(root);
        write(root, "rag/retriever.py", "changed = true\n");
        FakeReviewer reviewer = localReviewer(assessment("smoke", 0.95, "low"));

        TestSelection selection =
                TestSelectionService.selectTests(root, "HEAD", "local", "auto", reviewer, 0.7, null);

        assertEquals("full", selection.mode());
        assertEquals(List.of("regression", "smoke", "full"), selection.suites());
        assertTrue(selection.humanReviewRequired());
        assertTrue(
                selection.reviewReasons().contains("AI and deterministic rules recommend different modes."),
                selection.reviewReasons().toString());
    }

    @Test
    void lowConfidenceOrHighRiskAlwaysNeedsAPerson() {
        TestSelection lowConfidence =
                TestSelectionService.selectTests(
                        Path.of("."), "HEAD", "local", "auto", localReviewer(assessment("smoke", 0.4, "low")), 0.7, snapshot("README.md"));
        assertTrue(lowConfidence.reviewReasons().contains("AI confidence is below the configured threshold."));

        TestSelection highRisk =
                TestSelectionService.selectTests(
                        Path.of("."), "HEAD", "local", "auto", localReviewer(assessment("regression", 0.9, "high")), 0.7, snapshot("prompts/planner.txt"));
        assertTrue(highRisk.reviewReasons().contains("The change is high risk."));
    }

    @Test
    void aFailedAiAssessmentFallsBackToTheRules() {
        TestSelectionService.JsonReviewer broken =
                new FakeReviewer(null, "http://127.0.0.1:11434/v1") {
                    @Override
                    public Map<String, Object> requestJson(String prompt) {
                        throw new IllegalStateException("provider down");
                    }
                };

        TestSelection selection =
                TestSelectionService.selectTests(
                        Path.of("."), "HEAD", "local", "auto", broken, 0.7, snapshot("prompts/planner.txt"));

        assertEquals("regression", selection.mode());
        assertNull(selection.aiAssessment());
        assertTrue(selection.reviewReasons().stream().anyMatch(reason -> reason.startsWith("AI assessment failed:")),
                selection.reviewReasons().toString());
    }

    @Test
    void rejectsProviderCombinationsThatWouldLeakTheDiff() {
        IllegalArgumentException raw =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                TestSelectionService.selectTests(
                                        Path.of("."), "HEAD", "remote", "raw", remoteReviewer(assessment("smoke", 0.9, "low")), 0.7, snapshot("README.md")));
        assertTrue(raw.getMessage().contains("sanitized summary"), raw.getMessage());

        IllegalArgumentException publicLocal =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                TestSelectionService.selectTests(
                                        Path.of("."), "HEAD", "local", "auto", remoteReviewer(assessment("smoke", 0.9, "low")), 0.7, snapshot("README.md")));
        assertTrue(publicLocal.getMessage().contains("loopback"), publicLocal.getMessage());

        assertThrows(
                IllegalArgumentException.class,
                () -> TestSelectionService.selectTests(Path.of("."), "HEAD", "local", "auto", null, 0.7, snapshot("README.md")));
        assertThrows(
                IllegalArgumentException.class,
                () -> TestSelectionService.selectTests(Path.of("."), "HEAD", "sometimes", "auto", null, 0.7, snapshot("README.md")));
        assertThrows(
                IllegalArgumentException.class,
                () -> TestSelectionService.selectTests(Path.of("."), "HEAD", "none", "everything", null, 0.7, snapshot("README.md")));
        assertThrows(
                IllegalArgumentException.class,
                () -> TestSelectionService.selectTests(Path.of("."), "HEAD", "none", "auto", null, 2.0, snapshot("README.md")));
    }

    @Test
    void readsTheChangeFromTheRepositoryWhenNoSnapshotIsGiven(@TempDir Path root) {
        initRepository(root);
        DiffSnapshot snapshot = DiffAnalysis.readGitDiff(root, "HEAD");
        assertEquals(List.of(), snapshot.files());
        assertEquals(0, snapshot.additions());

        write(root, "src/orders/Price.java", "class Price {}\n");
        DiffSnapshot withUntracked = DiffAnalysis.readGitDiff(root, "HEAD");
        assertEquals(List.of("src/orders/Price.java"), withUntracked.files());
        assertEquals("source", DiffAnalysis.category("src/orders/Price.java"));
    }
}
