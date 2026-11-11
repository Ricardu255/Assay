package io.assay.cli;

import io.assay.compare.CompareGate;
import io.assay.execution.Stores;
import io.assay.json.Json;
import io.assay.model.Values;
import io.assay.release.CompareEngine;
import io.assay.report.CompareReport;
import io.assay.store.Results;
import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implements the {@code compare} command and its input resolution. */
final class CompareCommand {

    private CompareCommand() {
    }

    static int compare(CommandLine.Namespace args) {
        if (args.provided("config") && args.provided("profile")) {
            throw new CommandLine.UsageException("use either --config or --profile, not both", 2, false);
        }
        CompareGate gate = args.path("config") != null
                ? CompareGate.fromFile(args.path("config"))
                : CompareGate.profile(args.string("profile"));
        String baselineRef = args.string("baseline");
        String candidateRef = args.string("candidate");
        Map<String, Object> baselineSummary;
        Map<String, Object> candidateSummary;
        Results store = null;
        try {
            if (needsStore(baselineRef) || needsStore(candidateRef)) {
                store = Stores.open(args.string("store"));
            }
            baselineSummary = loadCompareSummary(baselineRef, store);
            candidateSummary = loadCompareSummary(candidateRef, store);
        } finally {
            if (store != null) {
                store.close();
            }
        }

        Map<String, Object> report =
                CompareEngine.compare(baselineRef, candidateRef, baselineSummary, candidateSummary, gate);
        String markdown = CompareReport.markdown(report);
        String json = Json.write(report, 2) + "\n";
        Path output = args.path("output");
        if (output != null) {
            writeToPath(output, args.string("output"), args.string("format"), markdown, json, report);
        } else {
            System.out.print("json".equals(args.string("format")) ? json : markdown);
        }
        return "BLOCK".equals(Values.asMap(report.get("gate")).get("status")) ? 1 : 0;
    }

    private static void writeToPath(
            Path output,
            String reference,
            String format,
            String markdown,
            String json,
            Map<String, Object> report) {
        if (isDirectoryOutput(reference, output)) {
            AtomicFiles.createDirectories(output);
            Path markdownPath = output.resolve("report.md");
            Path jsonPath = output.resolve("compare.json");
            AtomicFiles.writeText(markdownPath, markdown);
            AtomicFiles.writeText(jsonPath, json);
            Map<String, Object> printed = new LinkedHashMap<>();
            printed.put("status", Values.asMap(report.get("gate")).get("status"));
            printed.put("report", markdownPath.toString());
            printed.put("artifact", jsonPath.toString());
            System.out.println(Json.write(printed));
            return;
        }
        AssayCli.createParent(output);
        AtomicFiles.writeText(output, "json".equals(format) ? json : markdown);
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("status", Values.asMap(report.get("gate")).get("status"));
        printed.put("report", output.toString());
        System.out.println(Json.write(printed));
    }

    /**
     * A trailing separator or an existing directory means "writer folder"; an extension means "file".
     *
     * <p>The folder form writes both artifacts: the Markdown report for a person and
     * {@code compare.json} for the dashboard catalog.
     */
    private static boolean isDirectoryOutput(String reference, Path path) {
        if (Files.isDirectory(path)) {
            return true;
        }
        if (reference != null && (reference.endsWith("/") || reference.endsWith("\\"))) {
            return true;
        }
        Path name = path.getFileName();
        return name == null || !name.toString().contains(".");
    }

    /**
     * Reads a comparison input from a {@code results.json} file, a run directory, or a stored run id.
     *
     * <p>The file path is the portable form: a report can be compared on a machine that never had the
     * original store. A run id is the convenient form when both runs live in the same local store.
     */
    private static Map<String, Object> loadCompareSummary(String reference, Results store) {
        Path path;
        try {
            path = Path.of(reference);
        } catch (RuntimeException invalidPath) {
            path = null;
        }
        if (path != null && Files.isDirectory(path)) {
            path = path.resolve("results.json");
        }
        if (path != null && Files.isRegularFile(path)) {
            Map<String, Object> summary = Json.parseObject(AtomicFiles.readText(path));
            if (!(summary.get("results") instanceof List<?>)) {
                throw new IllegalArgumentException("comparison input has no results array: " + path);
            }
            return summary;
        }
        if (store == null) {
            throw new IllegalArgumentException("cannot read comparison input: " + reference);
        }
        List<Map<String, Object>> results = store.listResults(reference);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("run has no stored cases: " + reference);
        }
        return summaryFromResults(reference, results);
    }

    private static boolean needsStore(String reference) {
        try {
            Path path = Path.of(reference);
            return !Files.isDirectory(path) && !Files.isRegularFile(path);
        } catch (RuntimeException invalidPath) {
            return true;
        }
    }

    private static Map<String, Object> summaryFromResults(
            String reference, List<Map<String, Object>> results) {
        String runId = reference;
        String suite = "custom";
        String source = "online";
        int hardFailures = 0;
        int softWarnings = 0;
        List<Object> rows = new ArrayList<>(results.size());
        for (Map<String, Object> result : results) {
            rows.add(result);
            if (result.get("run_id") != null) {
                runId = String.valueOf(result.get("run_id"));
            }
            if (result.get("suite") != null) {
                suite = String.valueOf(result.get("suite"));
            }
            if (result.get("source") != null) {
                source = String.valueOf(result.get("source"));
            }
            if (!Boolean.TRUE.equals(result.get("hard_pass"))) {
                hardFailures++;
            }
            softWarnings += Values.numberAsInt(result.get("soft_warning_count"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("suite", suite);
        summary.put("source", source);
        summary.put("status", hardFailures == 0 ? "passed" : "failed");
        summary.put("case_count", results.size());
        summary.put("hard_failures", hardFailures);
        summary.put("soft_warnings", softWarnings);
        summary.put("results", rows);
        return summary;
    }
}
