package io.assay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.store.file.ResultStore;
import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The compare command must work from exported files and from stored run ids. */
class CompareCliTest {

    private static Map<String, Object> result(
            String runId, String caseId, boolean critical, boolean pass, String route) {
        EvalCase evalCase = new EvalCase(
                caseId,
                Map.of("message", "request " + caseId),
                Map.of("route", "SUPPORT"),
                "routing",
                critical ? Map.of("critical", true) : Map.of(),
                "suite");
        NormalizedTrace trace = new NormalizedTrace(
                "trace-" + caseId,
                Map.of("route", route),
                List.of(
                        new TraceEvent("Router", "plan", 1),
                        new TraceEvent("Router", "route", "ok", 2, null, Map.of("route", route))),
                Map.of("route", route));
        CheckResult check = new CheckResult("structure", "hard", "route", pass, route, "SUPPORT");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("suite", "suite");
        row.put("source", "online");
        row.put("case", evalCase.toJson());
        row.put("trace", trace.toJson());
        row.put("checks", List.of(check.toJson()));
        row.put("hard_pass", pass);
        row.put("soft_warning_count", 0);
        return row;
    }

    private static Map<String, Object> summary(String runId, List<Map<String, Object>> results) {
        int failures = 0;
        for (Map<String, Object> result : results) {
            if (!Boolean.TRUE.equals(result.get("hard_pass"))) {
                failures++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("suite", "suite");
        summary.put("source", "online");
        summary.put("status", failures == 0 ? "passed" : "failed");
        summary.put("case_count", results.size());
        summary.put("hard_failures", failures);
        summary.put("soft_warnings", 0);
        summary.put("results", new ArrayList<>(results));
        return summary;
    }

    private static Path writeSummary(Path directory, String name, Map<String, Object> summary) {
        Path path = directory.resolve(name);
        AtomicFiles.writeText(path, Json.write(summary, 2));
        return path;
    }

    @Test
    void comparesTwoExportedResultsFiles(@TempDir Path root) {
        Path baseline = writeSummary(root, "baseline.json",
                summary("baseline", List.of(result("baseline", "A", true, true, "SUPPORT"))));
        Path candidate = writeSummary(root, "candidate.json",
                summary("candidate", List.of(result("candidate", "A", true, false, "GENERAL"))));
        Path report = root.resolve("compare.json");

        int code = AssayCli.run(new String[] {
            "compare",
            "--baseline", baseline.toString(),
            "--candidate", candidate.toString(),
            "--profile", "strict",
            "--format", "json",
            "--output", report.toString()
        });

        assertEquals(1, code);
        Map<String, Object> payload = Json.parseObject(AtomicFiles.readText(report));
        assertEquals("BLOCK", asMap(payload.get("gate")).get("status"));
        assertEquals(1, ((Number) asMap(payload.get("transitions")).get("regressed_count")).intValue());
        assertTrue(asList(payload.get("regressions")).size() == 1);
    }

    @Test
    void directoryOutputWritesMarkdownAndDashboardArtifact(@TempDir Path root) {
        Path baseline = writeSummary(root, "baseline.json",
                summary("baseline", List.of(result("baseline", "A", true, true, "SUPPORT"))));
        Path candidate = writeSummary(root, "candidate.json",
                summary("candidate", List.of(result("candidate", "A", true, false, "GENERAL"))));
        Path directory = root.resolve("compare");

        int code = AssayCli.run(new String[] {
            "compare",
            "--baseline", baseline.toString(),
            "--candidate", candidate.toString(),
            "--profile", "strict",
            "--output", directory.toString()
        });

        assertEquals(1, code);
        assertTrue(Files.isRegularFile(directory.resolve("report.md")));
        assertTrue(Files.isRegularFile(directory.resolve("compare.json")));
        Map<String, Object> artifact = Json.parseObject(AtomicFiles.readText(directory.resolve("compare.json")));
        assertEquals("BLOCK", asMap(artifact.get("gate")).get("status"));
    }

    @Test
    void comparesStoredRunIds(@TempDir Path root) {
        Path storePath = root.resolve("store");
        try (ResultStore store = new ResultStore(storePath)) {
            store.startRun("base", "adapter", "suite", "online", Map.of("case_count", 1));
            store.saveCases(List.of(new CaseResult(
                    "base",
                    "suite",
                    "online",
                    evalCase("A"),
                    trace("A", "SUPPORT"),
                    List.of(new CheckResult("structure", "hard", "route", true, "SUPPORT", "SUPPORT")))));
            store.finishRun("base", "passed");

            store.startRun("cand", "adapter", "suite", "online", Map.of("case_count", 1));
            store.saveCases(List.of(new CaseResult(
                    "cand",
                    "suite",
                    "online",
                    evalCase("A"),
                    trace("A", "GENERAL"),
                    List.of(new CheckResult("structure", "hard", "route", false, "GENERAL", "SUPPORT")))));
            store.finishRun("cand", "failed");
        }

        Path report = root.resolve("compare.md");
        int code = AssayCli.run(new String[] {
            "compare",
            "--baseline", "base",
            "--candidate", "cand",
            "--store", storePath.toString(),
            "--profile", "strict",
            "--output", report.toString()
        });

        assertEquals(1, code);
        String markdown = AtomicFiles.readText(report);
        assertTrue(markdown.contains("Assay Version Comparison"), markdown);
        assertTrue(markdown.contains("BLOCK"), markdown);
        assertTrue(markdown.contains("`A`"), markdown);
    }

    private static EvalCase evalCase(String caseId) {
        return new EvalCase(
                caseId,
                Map.of("message", "request " + caseId),
                Map.of("route", "SUPPORT"),
                "routing",
                Map.of(),
                "suite");
    }

    private static NormalizedTrace trace(String caseId, String route) {
        return new NormalizedTrace(
                "trace-" + caseId,
                Map.of("route", route),
                List.of(
                        new TraceEvent("Router", "plan", 1),
                        new TraceEvent("Router", "route", "ok", 2, null, Map.of("route", route))),
                Map.of("route", route));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }
}
