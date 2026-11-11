package io.assay.cli;

import io.assay.adapters.AdapterLoader;
import io.assay.audit.ScopeAudit;
import io.assay.audit.ScopeAuditReport;
import io.assay.diff.DiffAnalysis;
import io.assay.diff.DiffSnapshot;
import io.assay.engine.CaseLoader;
import io.assay.engine.EvaluationEngine;
import io.assay.execution.Stores;
import io.assay.json.Json;
import io.assay.llm.OpenAiCompatibleReviewer;
import io.assay.model.TestSelection;
import io.assay.model.Values;
import io.assay.report.Reporting;
import io.assay.selection.TestSelectionService;
import io.assay.store.Results;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implements the {@code audit-scope} command; kept out of {@link AssayCli} to bound its size. */
final class AuditScopeCommand {

    private AuditScopeCommand() {
    }

    static int run(CommandLine.Namespace args) {
        Map<String, Object> specification = Json.parseObject(AtomicFiles.readText(args.path("spec")));
        DiffSnapshot snapshot =
                DiffAnalysis.readGitDiff(args.path("repository"), args.string("base"), args.string("target"));
        boolean useAi = "local".equals(args.string("ai-provider"));
        OpenAiCompatibleReviewer jsonReviewer = useAi ? OpenAiCompatibleReviewer.fromEnvironment() : null;
        if (useAi && jsonReviewer == null) {
            throw new CommandLine.UsageException("--use-llm requires ASSAY_MODEL", 1, false);
        }
        TestSelectionService.JsonReviewer selectionReviewer = jsonReviewer;
        TestSelection selection =
                TestSelectionService.selectTests(
                        Path.of("."), "HEAD", args.string("ai-provider"), "auto", selectionReviewer, 0.7,
                        snapshot);
        Map<String, Object> result = ScopeAudit.auditScope(snapshot, specification, selectionReviewer);
        result.put("test_selection", selection.toJson());

        Path output = args.path("output") != null
                ? args.path("output")
                : Path.of(".assay", "audits").resolve(CliIds.shortId());
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> runs = executeSelectedTests(args, snapshot, selection, output, missing);

        String testStatus = testStatus(runs, selection);
        Map<String, Object> tests = new LinkedHashMap<>();
        tests.put("status", testStatus);
        tests.put("runs", runs);
        tests.put("selected_suites", selection.suites());
        result.put("tests", tests);

        for (Object warning : Values.asList(result.get("warnings"))) {
            missing.add(String.valueOf(warning));
        }
        if (selection.humanReviewRequired()) {
            missing.addAll(selection.reviewReasons());
        }
        if (String.valueOf(result.get("requirement")).isEmpty()
                || Values.asList(result.get("acceptance_criteria")).isEmpty()) {
            missing.add("Requirement and acceptance criteria must be specific enough for scope judgment.");
        }
        for (Object rawFinding : Values.asList(result.get("findings"))) {
            if (Values.asMap(rawFinding).get("line") == null) {
                missing.add("Some changed files have no source line evidence (binary or metadata-only change).");
                break;
            }
        }
        List<String> uncovered = new ArrayList<>();
        for (String item : missing) {
            if (!uncovered.contains(item)) {
                uncovered.add(item);
            }
        }
        result.put("uncovered_risks", uncovered);

        String conclusion = conclusion(Values.asList(result.get("findings")), testStatus, uncovered);
        result.put("conclusion", conclusion);
        ScopeAuditReport.writeArtifacts(result, output, snapshot.rawDiff());
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("conclusion", conclusion);
        printed.put("tests", testStatus);
        System.out.println(Json.write(printed));
        System.out.println("report: " + output.resolve("report.md"));
        if ("no_obvious_issue".equals(conclusion)) {
            return 0;
        }
        return "clear_out_of_scope".equals(conclusion) ? 1 : 2;
    }

    private static List<Map<String, Object>> executeSelectedTests(
            CommandLine.Namespace args,
            DiffSnapshot snapshot,
            TestSelection selection,
            Path output,
            List<String> missing) {
        List<Map<String, Object>> runs = new ArrayList<>();
        if (snapshot.files().isEmpty()) {
            missing.add("The commit comparison contains no changed files.");
            return runs;
        }
        if (args.string("adapter") == null) {
            missing.add("No ProjectAdapter was supplied; selected regression suites were not executed.");
            return runs;
        }
        Path repository = args.path("repository").toAbsolutePath().normalize();
        if (!DiffAnalysis.git(repository, "rev-parse", "HEAD").trim().equals(snapshot.targetCommit())) {
            missing.add("Tests were not run because the repository HEAD is not the target commit.");
            return runs;
        }
        if (!DiffAnalysis.git(repository, "status", "--porcelain").trim().isEmpty()) {
            missing.add("Tests were not run because the target checkout has uncommitted files.");
            return runs;
        }
        Map<String, Path> suitePaths = new LinkedHashMap<>();
        for (String suite : selection.suites()) {
            suitePaths.put(suite, args.path(suite));
        }
        List<String> available = new ArrayList<>();
        for (Map.Entry<String, Path> entry : suitePaths.entrySet()) {
            if (entry.getValue() == null) {
                missing.add("Selected " + entry.getKey() + " suite has no case file.");
            } else {
                available.add(entry.getKey());
            }
        }
        if (available.isEmpty()) {
            return runs;
        }
        try (Results store = Stores.open(
                args.provided("store") ? args.string("store") : output.resolve("store").toString())) {
            EvaluationEngine engine = new EvaluationEngine(
                    AdapterLoader.loadProjectAdapter(
                            args.string("adapter"), args.string("adapter-class")),
                    store,
                    null,
                    args.integer("workers"),
                    args.integer("retries"),
                    args.decimal("timeout"),
                    false,
                    snapshot.targetCommit());
            for (String suite : available) {
                runSuite(engine, suitePaths.get(suite), suite, output, missing, runs);
            }
        } catch (RuntimeException error) {
            missing.add(
                    "Regression execution unavailable: " + error.getClass().getSimpleName() + ": "
                            + error.getMessage());
        }
        return runs;
    }

    private static void runSuite(
            EvaluationEngine engine,
            Path caseFile,
            String suite,
            Path output,
            List<String> missing,
            List<Map<String, Object>> runs) {
        try {
            Map<String, Object> summary = engine.runSuite(
                    CaseLoader.loadCases(caseFile, suite),
                    suite,
                    "online",
                    "scope-" + CliIds.shortId() + "-" + suite,
                    false);
            Reporting.writeRunArtifacts(summary, output.resolve("tests").resolve(suite));
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("suite", suite);
            run.put("status", summary.get("status"));
            run.put("case_count", summary.get("case_count"));
            run.put("hard_failures", summary.get("hard_failures"));
            run.put("report_path", "tests/" + suite + "/report.md");
            runs.add(run);
        } catch (RuntimeException error) {
            missing.add(
                    "Selected " + suite + " suite could not run: "
                            + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static String testStatus(List<Map<String, Object>> runs, TestSelection selection) {
        if (runs.isEmpty()) {
            return "not_run";
        }
        if (runs.stream().anyMatch(run -> "failed".equals(run.get("status")))) {
            return "failed";
        }
        return runs.size() < selection.suites().size() ? "partial" : "passed";
    }

    private static String conclusion(List<Object> findings, String testStatus, List<String> uncovered) {
        if (findings.stream()
                .anyMatch(finding -> "clear_out_of_scope".equals(Values.asMap(finding).get("status"))
                        && "rules".equals(Values.asMap(finding).get("source")))) {
            return "clear_out_of_scope";
        }
        if (findings.stream()
                        .anyMatch(finding -> List.of("clear_out_of_scope", "suspected_out_of_scope")
                                .contains(Values.asMap(finding).get("status")))
                || "failed".equals(testStatus)) {
            return "review_required";
        }
        if (findings.stream()
                        .anyMatch(finding -> "insufficient_evidence".equals(
                                Values.asMap(finding).get("status")))
                || !uncovered.isEmpty()
                || !"passed".equals(testStatus)) {
            return "insufficient_evidence";
        }
        return "no_obvious_issue";
    }
}
