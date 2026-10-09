package io.assay.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.diff.DiffAnalysis;
import io.assay.diff.DiffSnapshot;
import io.assay.selection.TestSelectionService;
import io.assay.support.AtomicFiles;
import io.assay.testing.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scope auditing: what the rules decide on their own, and what a model is allowed to add.
 *
 * <p>The load-bearing property is that a model finding only survives when it points at a line this change
 * actually touched. A confident model must not be able to move a finding onto unrelated context, and it
 * must not be able to quietly soften a rule's own verdict.
 */
class ScopeAuditTest {

    private static final String CHANGED_FILE = "src/main/java/example/Value.java";

    /** One target line replaced by two, which is the shape most of these tests need. */
    private static final String RAW_DIFF =
            "diff --git a/src/main/java/example/Value.java b/src/main/java/example/Value.java\n"
                    + "--- a/src/main/java/example/Value.java\n"
                    + "+++ b/src/main/java/example/Value.java\n"
                    + "@@ -1,2 +1,3 @@\n"
                    + " public final class Value {\n"
                    + "-    private static final int VALUE = 1;\n"
                    + "+    private static final int VALUE = 2;\n"
                    + "+    private static final int LIMIT = 2;\n";

    private static DiffSnapshot snapshot() {
        return new DiffSnapshot(
                RAW_DIFF,
                List.of(CHANGED_FILE),
                2,
                1,
                new LinkedHashMap<>(Map.of("source", 1)),
                new LinkedHashMap<>(Map.of(".java", 1)),
                List.of("source"),
                null,
                null);
    }

    private static Map<String, Object> spec(
            String requirement, List<String> criteria, List<String> allowed, List<String> forbidden) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requirement", requirement);
        value.put("acceptance_criteria", criteria);
        value.put("allowed_paths", allowed);
        value.put("forbidden_paths", forbidden);
        return value;
    }

    private static Map<String, Object> spec() {
        return spec(
                "Change VALUE to 2.", List.of("VALUE is 2"), List.of(CHANGED_FILE), List.of());
    }

    private static class FixedReviewer implements TestSelectionService.JsonReviewer {

        private final int line;
        private final String side;
        private final String status;

        private FixedReviewer(int line, String side, String status) {
            this.line = line;
            this.side = side;
            this.status = status;
        }

        @Override
        public String baseUrl() {
            return "http://127.0.0.1:11434/v1";
        }

        @Override
        public Map<String, Object> requestJson(String prompt) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", CHANGED_FILE);
            row.put("line", line);
            row.put("line_side", side);
            row.put("status", status);
            row.put("risk", "low");
            row.put("reason", "The change supports the requested behavior.");
            return Map.of("findings", List.of(row));
        }
    }

    private static final class RowsReviewer implements TestSelectionService.JsonReviewer {

        private final List<Map<String, Object>> rows;

        private RowsReviewer(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        @Override
        public String baseUrl() {
            return "http://127.0.0.1:11434/v1";
        }

        @Override
        public Map<String, Object> requestJson(String prompt) {
            return Map.of("findings", rows);
        }
    }

    private static Map<String, Object> row(int line, Object side, String status, String reason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("path", CHANGED_FILE);
        value.put("line", line);
        value.put("line_side", side);
        value.put("status", status);
        value.put("risk", "medium");
        value.put("reason", reason);
        return value;
    }

    @Test
    void anAllowedPathWithNoSurprisingDeclarationIsRelated() {
        Map<String, Object> result = ScopeAudit.auditScope(snapshot(), spec(), null);

        assertEquals(List.of(CHANGED_FILE), result.get("changed_files"));
        assertEquals("related", finding(result, 0).get("status"));
        assertEquals("rules", finding(result, 0).get("source"));
        assertEquals("not_requested", result.get("semantic_review"));
        assertEquals(List.of(), result.get("warnings"));
    }

    /** The shape of the audit record is a contract: consumers read these keys by position and name. */
    @Test
    void reportsTheAuditRecordInAFixedShape() {
        Map<String, Object> result = ScopeAudit.auditScope(snapshot(), spec(), null);

        assertEquals(
                List.of(
                        "requirement",
                        "acceptance_criteria",
                        "allowed_paths",
                        "forbidden_paths",
                        "diff_sha256",
                        "base_commit",
                        "target_commit",
                        "changed_files",
                        "additions",
                        "deletions",
                        "findings",
                        "model_finding_trace",
                        "semantic_review",
                        "warnings"),
                List.copyOf(result.keySet()));
        assertEquals(64, Fixtures.str(result.get("diff_sha256")).length());
        assertEquals(
                Fixtures.str(result.get("diff_sha256")).toLowerCase(java.util.Locale.ROOT),
                Fixtures.str(result.get("diff_sha256")));
    }

    @Test
    void aForbiddenPathIsClearOutOfScope() {
        Map<String, Object> result =
                ScopeAudit.auditScope(snapshot(), spec("x", List.of("y"), List.of(), List.of(CHANGED_FILE)), null);

        assertEquals("clear_out_of_scope", finding(result, 0).get("status"));
        assertEquals("high", finding(result, 0).get("risk"));
    }

    /** A path with no declared scope cannot be judged, and says so rather than guessing. */
    @Test
    void anUndeclaredPathIsInsufficientEvidence() {
        Map<String, Object> result = ScopeAudit.auditScope(snapshot(), spec("x", List.of("y"), List.of(), List.of()), null);

        assertEquals("insufficient_evidence", finding(result, 0).get("status"));
        assertEquals("medium", finding(result, 0).get("risk"));
    }

    @Test
    void aPathOutsideTheAllowedSetIsSuspected() {
        Map<String, Object> result =
                ScopeAudit.auditScope(snapshot(), spec("x", List.of("y"), List.of("src/other/"), List.of()), null);

        assertEquals("suspected_out_of_scope", finding(result, 0).get("status"));
    }

    @Test
    void aChangedLineIsLocatedOnTheCorrectDiffSide() {
        Map<String, Object> result = ScopeAudit.auditScope(snapshot(), spec("x", List.of("y"), List.of(), List.of()), null);
        Map<String, Object> finding = finding(result, 0);

        // The first changed line of the diff is the deletion, so it is reported against the base.
        assertEquals(2, Fixtures.intOf(finding.get("line")));
        assertEquals("base", finding.get("line_side"));
        assertEquals("-    private static final int VALUE = 1;", finding.get("evidence"));
    }

    @Test
    void rejectsASpecificationItCannotInterpret() {
        Map<String, Object> nullRequirement = spec();
        nullRequirement.put("requirement", null);
        assertThrows(
                IllegalArgumentException.class, () -> ScopeAudit.auditScope(snapshot(), nullRequirement, null));

        Map<String, Object> badCriteria = spec();
        badCriteria.put("acceptance_criteria", "one criterion");
        assertThrows(IllegalArgumentException.class, () -> ScopeAudit.auditScope(snapshot(), badCriteria, null));

        Map<String, Object> badPaths = spec();
        badPaths.put("allowed_paths", List.of(1, 2));
        assertThrows(IllegalArgumentException.class, () -> ScopeAudit.auditScope(snapshot(), badPaths, null));
    }

    /** An empty requirement forces the evidence verdict even when a model offers an opinion. */
    @Test
    void withoutARequirementTheVerdictStaysUncertain() {
        Map<String, Object> result =
                ScopeAudit.auditScope(
                        snapshot(), spec("", List.of(), List.of(CHANGED_FILE), List.of()), new FixedReviewer(2, "target", "related"));

        assertEquals("insufficient_evidence", finding(result, 0).get("status"));
        assertEquals("rules", finding(result, 0).get("source"));
        assertEquals("skipped", result.get("semantic_review"));
        assertTrue(result.get("warnings").toString().contains("skipped"), result.get("warnings").toString());
    }

    /** With no declared paths the model may supply the semantics the rules are missing. */
    @Test
    void aModelCanSupplyTheSemanticsTheRulesLack() {
        Map<String, Object> result =
                ScopeAudit.auditScope(
                        snapshot(), spec("x", List.of("y"), List.of(), List.of()), new FixedReviewer(2, "target", "related"));

        assertEquals("attempted", result.get("semantic_review"));
        assertEquals("ai", finding(result, 0).get("source"));
        assertEquals("related", finding(result, 0).get("status"));
    }

    /** But it cannot soften a rule that already suspects the change. */
    @Test
    void aModelCannotSoftenARuleThatAlreadySuspectsTheChange() {
        String diff =
                "diff --git a/src/main/java/example/Value.java b/src/main/java/example/Value.java\n"
                        + "--- a/src/main/java/example/Value.java\n"
                        + "+++ b/src/main/java/example/Value.java\n"
                        + "@@ -1,1 +1,2 @@\n"
                        + " public final class Value {\n"
                        + "+    public static boolean exportAll() {\n";
        DiffSnapshot extended =
                new DiffSnapshot(
                        diff,
                        List.of(CHANGED_FILE),
                        1,
                        0,
                        new LinkedHashMap<>(Map.of("source", 1)),
                        new LinkedHashMap<>(Map.of(".java", 1)),
                        List.of("source"),
                        null,
                        null);

        Map<String, Object> result =
                ScopeAudit.auditScope(extended, spec(), new FixedReviewer(2, "target", "related"));

        assertEquals("suspected_out_of_scope", finding(result, 0).get("status"));
        assertEquals("rules", finding(result, 0).get("source"));
        assertTrue(
                result.get("warnings").toString().contains("disagree"),
                result.get("warnings").toString());
    }

    @Test
    void anUnrequestedPublicDeclarationIsSuspected() {
        String diff =
                "diff --git a/src/main/java/example/Value.java b/src/main/java/example/Value.java\n"
                        + "--- a/src/main/java/example/Value.java\n"
                        + "+++ b/src/main/java/example/Value.java\n"
                        + "@@ -1,1 +1,3 @@\n"
                        + " public final class Value {\n"
                        + "+    public static boolean exportAll() {\n"
                        + "+        return true;\n";
        DiffSnapshot extended =
                new DiffSnapshot(
                        diff,
                        List.of(CHANGED_FILE),
                        2,
                        0,
                        new LinkedHashMap<>(Map.of("source", 1)),
                        new LinkedHashMap<>(Map.of(".java", 1)),
                        List.of("source"),
                        null,
                        null);

        Map<String, Object> result = ScopeAudit.auditScope(extended, spec(), null);
        Map<String, Object> finding = finding(result, 0);

        assertEquals("suspected_out_of_scope", finding.get("status"));
        assertEquals(2, Fixtures.intOf(finding.get("line")));
        assertTrue(Fixtures.str(finding.get("evidence")).contains("exportAll"), finding.toString());
    }

    /** A declaration the requirement does name is the point of the change, not a surprise. */
    @Test
    void aDeclarationTheRequirementNamesIsNotFlagged() {
        String diff =
                "diff --git a/src/main/java/example/Value.java b/src/main/java/example/Value.java\n"
                        + "--- a/src/main/java/example/Value.java\n"
                        + "+++ b/src/main/java/example/Value.java\n"
                        + "@@ -1,1 +1,2 @@\n"
                        + " public final class Value {\n"
                        + "+    public static int value() {\n";
        DiffSnapshot extended =
                new DiffSnapshot(
                        diff,
                        List.of(CHANGED_FILE),
                        1,
                        0,
                        new LinkedHashMap<>(Map.of("source", 1)),
                        new LinkedHashMap<>(Map.of(".java", 1)),
                        List.of("source"),
                        null,
                        null);

        Map<String, Object> result =
                ScopeAudit.auditScope(
                        extended,
                        spec("Add value() returning 2.", List.of("value() returns 2"), List.of(CHANGED_FILE), List.of()),
                        null);

        assertEquals("related", finding(result, 0).get("status"));
    }

    @Test
    void aDependencyFileChangeIsFlagged() {
        String diff =
                "diff --git a/pom.xml b/pom.xml\n"
                        + "--- a/pom.xml\n"
                        + "+++ b/pom.xml\n"
                        + "@@ -1,1 +1,2 @@\n"
                        + " <project>\n"
                        + "+  <dependencies/>\n";
        DiffSnapshot extended =
                new DiffSnapshot(
                        diff,
                        List.of("pom.xml"),
                        1,
                        0,
                        new LinkedHashMap<>(Map.of("configuration", 1)),
                        new LinkedHashMap<>(Map.of(".xml", 1)),
                        List.of("configuration"),
                        null,
                        null);

        Map<String, Object> result =
                ScopeAudit.auditScope(extended, spec("tidy up", List.of("nothing changes"), List.of("pom.xml"), List.of()), null);

        assertEquals("suspected_out_of_scope", finding(result, 0).get("status"));
        assertTrue(
                finding(result, 0).get("reason").toString().contains("Dependency"),
                finding(result, 0).toString());
    }

    /**
     * The whole point of the model-finding trace: a finding only survives when it lands on a changed line
     * on the stated side, and the report can show exactly what happened to each one.
     */
    @Test
    void modelFindingsKeepDistinctChangedLinesAndRejectContext() {
        Map<String, Object> first = row(2, "target", "clear_out_of_scope", "Independent behavior change.");
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(first);
        rows.add(row(3, "target", "related", "Requested update."));
        rows.add(new LinkedHashMap<>(first));
        rows.add(row(1, "target", "clear_out_of_scope", "Context is not a change."));
        rows.add(row(2, "base", "clear_out_of_scope", "Deleted old behavior."));
        rows.add(row(2, "target", "suspected_out_of_scope", "Different concern on the same line."));
        rows.add(row(3, "base", "clear_out_of_scope", "Wrong side is not a change."));
        rows.add(row(2, List.of(), "clear_out_of_scope", "Malformed side must be rejected."));

        Map<String, Object> result = ScopeAudit.auditScope(snapshot(), spec(), new RowsReviewer(rows));

        List<String> shape = new ArrayList<>();
        for (Object rawFinding : Fixtures.asList(result.get("findings"))) {
            Map<String, Object> finding = Fixtures.asMap(rawFinding);
            shape.add(finding.get("line_side") + ":" + finding.get("line") + ":" + finding.get("status"));
        }
        assertEquals(
                List.of(
                        "target:2:clear_out_of_scope",
                        "target:3:related",
                        "base:2:clear_out_of_scope",
                        "target:2:suspected_out_of_scope"),
                shape);

        List<Object> trace = Fixtures.asList(result.get("model_finding_trace"));
        List<String> dispositions = new ArrayList<>();
        for (Object rawTrace : trace) {
            dispositions.add(Fixtures.str(Fixtures.asMap(rawTrace).get("disposition")));
        }
        assertEquals(
                List.of("retained", "retained", "merged_duplicate", "rejected", "retained", "retained", "rejected", "rejected"),
                dispositions);
        assertEquals(1, Fixtures.intOf(Fixtures.asMap(trace.get(0)).get("final_finding_index")));
        assertEquals(1, Fixtures.intOf(Fixtures.asMap(trace.get(2)).get("final_finding_index")));
        assertNull(Fixtures.asMap(trace.get(3)).get("final_finding_index"));
        assertTrue(Fixtures.str(Fixtures.asMap(trace.get(3)).get("reason")).contains("No changed target line 1"),
                Fixtures.str(Fixtures.asMap(trace.get(3)).get("reason")));
        assertTrue(Fixtures.str(Fixtures.asMap(trace.get(6)).get("reason")).contains("No changed base line 3"),
                Fixtures.str(Fixtures.asMap(trace.get(6)).get("reason")));
        assertTrue(Fixtures.str(Fixtures.asMap(trace.get(7)).get("reason")).contains("Line side must be base or target"),
                Fixtures.str(Fixtures.asMap(trace.get(7)).get("reason")));
    }

    @Test
    void aModelResponseThatIsNotAFindingsArrayIsReportedNotTrusted() {
        TestSelectionService.JsonReviewer broken =
                new FixedReviewer(2, "target", "related") {
                    @Override
                    public Map<String, Object> requestJson(String prompt) {
                        return Map.of("conclusion", "looks fine");
                    }
                };

        Map<String, Object> result = ScopeAudit.auditScope(snapshot(), spec("x", List.of("y"), List.of(), List.of()), broken);

        assertEquals("rules", finding(result, 0).get("source"));
        assertTrue(
                result.get("warnings").toString().contains("Semantic review unavailable"),
                result.get("warnings").toString());
    }

    @Test
    void writesThePatchTheReportAndTheModelTrace(@TempDir Path root) {
        Map<String, Object> result =
                ScopeAudit.auditScope(
                        snapshot(), spec("x", List.of("y"), List.of(), List.of()), new FixedRowReviewer());
        result.put("conclusion", "review_required");
        result.put("test_selection", Map.of("mode", "smoke", "suites", List.of("smoke")));
        Map<String, Object> tests = new LinkedHashMap<>();
        tests.put("status", "not_run");
        tests.put("runs", List.of());
        result.put("tests", tests);
        result.put("uncovered_risks", List.of());

        ScopeAuditReport.writeArtifacts(result, root, snapshot().rawDiff());

        assertEquals(RAW_DIFF, AtomicFiles.readText(root.resolve("diff.patch")));
        assertTrue(Files.exists(root.resolve("scope_audit.json")));
        String report = AtomicFiles.readText(root.resolve("report.md"));
        assertTrue(report.contains("# AI Code Change Scope Audit"), report);
        assertTrue(report.contains("Diff SHA-256:"), report);
        assertTrue(report.contains("Model finding decisions"), report);
        assertTrue(report.contains("scope finding #1"), report);
        assertTrue(report.contains("No additional gap identified"), report);
        assertTrue(report.contains("Passing tests do not prove"), report);
    }

    private static final class FixedRowReviewer implements TestSelectionService.JsonReviewer {

        @Override
        public String baseUrl() {
            return "http://127.0.0.1:11434/v1";
        }

        @Override
        public Map<String, Object> requestJson(String prompt) {
            return Map.of("findings", List.of(row(3, "target", "related", "Requested update.")));
        }
    }

    @Test
    void readsARealRepositoryAndFlagsAnUnrequestedDeclaration(@TempDir Path root) {
        Path repository = root.resolve("project");
        AtomicFiles.createDirectories(repository.resolve("src/main/java/example"));
        AtomicFiles.writeText(
                repository.resolve("src/main/java/example/Value.java"),
                "package example;\n\npublic final class Value {\n    public static int value() {\n        return 1;\n    }\n}\n");
        DiffAnalysis.git(repository, "init", "-q");
        DiffAnalysis.git(repository, "config", "user.email", "test@example.com");
        DiffAnalysis.git(repository, "config", "user.name", "Test");
        DiffAnalysis.git(repository, "add", "--", ".");
        DiffAnalysis.git(repository, "commit", "-qm", "baseline");
        String base = DiffAnalysis.git(repository, "rev-parse", "HEAD").trim();

        AtomicFiles.writeText(
                repository.resolve("src/main/java/example/Value.java"),
                "package example;\n\npublic final class Value {\n    public static int value() {\n        return 2;\n    }\n\n    public static boolean exportAll() {\n        return true;\n    }\n}\n");
        DiffAnalysis.git(repository, "add", "--", ".");
        DiffAnalysis.git(repository, "commit", "-qm", "change");
        String target = DiffAnalysis.git(repository, "rev-parse", "HEAD").trim();

        DiffSnapshot snapshot = DiffAnalysis.readGitDiff(repository, base, target);
        Map<String, Object> result =
                ScopeAudit.auditScope(
                        snapshot,
                        spec(
                                "Change value() to return 2.",
                                List.of("value() returns 2"),
                                List.of("src/main/java/example/Value.java"),
                                List.of()),
                        null);

        assertEquals(List.of("src/main/java/example/Value.java"), result.get("changed_files"));
        Map<String, Object> finding = finding(result, 0);
        assertEquals("suspected_out_of_scope", finding.get("status"));
        assertTrue(Fixtures.str(finding.get("evidence")).contains("exportAll"), finding.toString());
    }

    private static Map<String, Object> finding(Map<String, Object> result, int index) {
        return Fixtures.asMap(Fixtures.asList(result.get("findings")).get(index));
    }
}
