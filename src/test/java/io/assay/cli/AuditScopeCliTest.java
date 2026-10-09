package io.assay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.diff.DiffAnalysis;
import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import io.assay.testing.FileValueAdapter;
import io.assay.testing.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scope-audit slice end to end: read two commits, decide which suites to run, run them against the
 * target revision, and report with an exit code a pipeline can act on.
 */
class AuditScopeCliTest {

    private static final String SOURCE = "src/main/java/example/Value.java";

    private static Path commit(Path repository, String... arguments) {
        DiffAnalysis.git(repository, arguments);
        return repository;
    }

    private static String head(Path repository) {
        return DiffAnalysis.git(repository, "rev-parse", "HEAD").trim();
    }

    private static Path initialise(Path root) {
        Path repository = root.resolve("project");
        AtomicFiles.createDirectories(repository.resolve("src/main/java/example"));
        write(repository, "public final class Value {\n    public static final int VALUE = 1;\n}\n");
        commit(repository, "init", "-q");
        commit(repository, "config", "user.email", "test@example.com");
        commit(repository, "config", "user.name", "Test");
        commit(repository, "add", "--", ".");
        commit(repository, "commit", "-qm", "baseline");
        return repository;
    }

    private static void write(Path repository, String content) {
        AtomicFiles.writeText(repository.resolve(SOURCE), content);
    }

    private static void writeExtraFile(Path repository) {
        AtomicFiles.writeText(
                repository.resolve("src/main/java/example/Extra.java"),
                "package example;\n\npublic final class Extra {\n    public static final boolean DEBUG = true;\n}\n");
    }

    private static Map<String, Object> specification(List<String> forbidden) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requirement", "Change VALUE to 2.");
        value.put("acceptance_criteria", List.of("VALUE is 2"));
        value.put("allowed_paths", List.of(SOURCE));
        value.put("forbidden_paths", forbidden);
        return value;
    }

    private static Path writeCases(Path root, Path repository, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "VALUE");
        row.put("scenario", "direct");
        row.put("input", Map.of());
        row.put("expected", Map.of("value", 2));
        row.put("metadata", Map.of("repository", repository.toAbsolutePath().toString()));
        Path path = root.resolve(name);
        AtomicFiles.writeText(path, Json.write(row) + "\n");
        return path;
    }

    private static int audit(
            Path root, Path repository, String base, String target, Map<String, Object> specification, Path output) {
        Path specPath = root.resolve("spec.json");
        AtomicFiles.writeText(specPath, Json.write(specification));
        return AssayCli.run(
                new String[] {
                    "audit-scope",
                    "--repository", repository.toString(),
                    "--base", base,
                    "--target", target,
                    "--spec", specPath.toString(),
                    "--adapter", FileValueAdapter.class.getName(),
                    "--regression", writeCases(root, repository, "regression.jsonl").toString(),
                    "--smoke", writeCases(root, repository, "smoke.jsonl").toString(),
                    "--store", root.resolve("store").toString(),
                    "--output", output.toString()
                });
    }

    @Test
    void auditsAnInScopeChangeAndRunsTheSelectedSuites(@TempDir Path root) {
        Path repository = initialise(root);
        String base = head(repository);
        write(repository, "public final class Value {\n    public static final int VALUE = 2;\n}\n");
        commit(repository, "add", "--", ".");
        commit(repository, "commit", "-qm", "change");
        String target = head(repository);

        Path output = root.resolve("audit");
        int code = audit(root, repository, base, target, specification(List.of()), output);

        assertEquals(0, code);
        Map<String, Object> report = Json.parseObject(AtomicFiles.readText(output.resolve("scope_audit.json")));
        assertEquals("no_obvious_issue", report.get("conclusion"));
        assertEquals("related", Fixtures.asMap(Fixtures.asList(report.get("findings")).get(0)).get("status"));

        Map<String, Object> selection = Fixtures.asMap(report.get("test_selection"));
        assertEquals("regression", selection.get("mode"));
        assertEquals(List.of("regression", "smoke"), selection.get("suites"));

        Map<String, Object> tests = Fixtures.asMap(report.get("tests"));
        assertEquals("passed", tests.get("status"));
        assertEquals(2, Fixtures.asList(tests.get("runs")).size());
        for (Object rawRun : Fixtures.asList(tests.get("runs"))) {
            assertEquals(1, Fixtures.intOf(Fixtures.asMap(rawRun).get("case_count")));
            assertEquals(0, Fixtures.intOf(Fixtures.asMap(rawRun).get("hard_failures")));
        }
        assertTrue(Files.isRegularFile(output.resolve("tests/regression/report.md")));
        assertTrue(AtomicFiles.readText(output.resolve("diff.patch")).contains("+    public static final int VALUE = 2;"));
        assertTrue(AtomicFiles.readText(output.resolve("report.md")).contains("no_obvious_issue"));
        assertEquals(List.of(), report.get("uncovered_risks"));
    }

    /** Passing tests do not make a forbidden edit acceptable. */
    @Test
    void reportsAForbiddenPathEvenWhenEveryTestPasses(@TempDir Path root) {
        Path repository = initialise(root);
        String base = head(repository);
        write(repository, "public final class Value {\n    public static final int VALUE = 2;\n}\n");
        writeExtraFile(repository);
        commit(repository, "add", "--", ".");
        commit(repository, "commit", "-qm", "change");
        String target = head(repository);

        Path output = root.resolve("forbidden-audit");
        int code = audit(root, repository, base, target, specification(List.of("src/main/java/example/Extra.java")), output);

        assertEquals(1, code);
        Map<String, Object> report = Json.parseObject(AtomicFiles.readText(output.resolve("scope_audit.json")));
        assertEquals("clear_out_of_scope", report.get("conclusion"));
        assertEquals("passed", Fixtures.asMap(report.get("tests")).get("status"));

        Map<String, Object> extra = null;
        for (Object rawFinding : Fixtures.asList(report.get("findings"))) {
            if ("src/main/java/example/Extra.java".equals(Fixtures.asMap(rawFinding).get("path"))) {
                extra = Fixtures.asMap(rawFinding);
            }
        }
        assertEquals("clear_out_of_scope", extra.get("status"));
        assertEquals("high", extra.get("risk"));
        assertEquals(1, Fixtures.intOf(extra.get("line")));
    }

    /** A dirty checkout means the tests would not prove anything about the audited revision. */
    @Test
    void refusesToRunTestsWhenTheTargetCheckoutIsDirty(@TempDir Path root) {
        Path repository = initialise(root);
        String base = head(repository);
        write(repository, "public final class Value {\n    public static final int VALUE = 2;\n}\n");
        commit(repository, "add", "--", ".");
        commit(repository, "commit", "-qm", "change");
        String target = head(repository);
        write(repository, "public final class Value {\n    public static final int VALUE = 3;\n}\n");

        Path output = root.resolve("dirty-audit");
        int code = audit(root, repository, base, target, specification(List.of()), output);

        assertEquals(2, code);
        Map<String, Object> report = Json.parseObject(AtomicFiles.readText(output.resolve("scope_audit.json")));
        assertEquals("not_run", Fixtures.asMap(report.get("tests")).get("status"));
        assertTrue(
                resultContains(report, "uncommitted files"), report.get("uncovered_risks").toString());
    }

    /** A failing regression suite is evidence the change is not ready, not a pass with a caveat. */
    @Test
    void reportsFailedTestsAsReviewRequired(@TempDir Path root) {
        Path repository = initialise(root);
        String base = head(repository);
        // The change lands on 3, while the cases (and the requirement) say 2.
        write(repository, "public final class Value {\n    public static final int VALUE = 3;\n}\n");
        commit(repository, "add", "--", ".");
        commit(repository, "commit", "-qm", "change");
        String target = head(repository);

        Path output = root.resolve("failed-tests");
        int code = audit(root, repository, base, target, specification(List.of()), output);

        assertEquals(2, code);
        Map<String, Object> report = Json.parseObject(AtomicFiles.readText(output.resolve("scope_audit.json")));
        assertEquals("review_required", report.get("conclusion"));
        Map<String, Object> tests = Fixtures.asMap(report.get("tests"));
        assertEquals("failed", tests.get("status"));
        assertEquals(1, Fixtures.intOf(Fixtures.asMap(Fixtures.asList(tests.get("runs")).get(0)).get("hard_failures")));
    }

    private static boolean resultContains(Map<String, Object> report, String needle) {
        for (Object risk : Fixtures.asList(report.get("uncovered_risks"))) {
            if (Fixtures.str(risk).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
