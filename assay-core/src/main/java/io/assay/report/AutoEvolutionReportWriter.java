package io.assay.report;

import static io.assay.report.ReportSupport.DASH;
import static io.assay.report.ReportSupport.asList;
import static io.assay.report.ReportSupport.asMap;
import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes {@code auto_evolution.json} and {@code auto_evolution_report.md}. */
final class AutoEvolutionReportWriter {

    private AutoEvolutionReportWriter() {
    }

    static void writeAutoEvolutionArtifacts(Map<String, Object> result, Path outputDir) {
        AtomicFiles.createDirectories(outputDir);
        AtomicFiles.writeText(outputDir.resolve("auto_evolution.json"), Json.write(result, 2) + "\n");
        Map<String, Object> usage = asMap(result.get("usage"));
        List<String> report = new ArrayList<>();
        report.add("# Automatic Evolution Report " + DASH + " " + result.get("loop_id"));
        report.add("");
        report.add("- Status: **" + result.get("status") + "**");
        report.add("- Target: `" + result.get("target_type") + ":" + result.get("target_id") + "`");
        report.add("- Version: `" + result.get("initial_version") + "` → `"
                + result.get("current_version") + "`");
        report.add("- Current sandbox artifact: `" + result.get("current_artifact") + "`");
        report.add("- Elapsed: " + usage.getOrDefault("elapsed_seconds", 0) + " seconds");
        report.add("- Evolver calls: " + usage.getOrDefault("evolver_calls", 0));
        report.add("- Retryable evolver responses: " + usage.getOrDefault("evolver_retries", 0));
        report.add("");
        report.add("## Rounds");
        report.add("");
        if (result.get("error") != null) {
            report.add(7, "- Failed phase: `" + result.getOrDefault("failed_phase", "unknown") + "`");
            report.add(8, "- Error: `" + result.get("error") + "`");
        }
        for (Object rawRound : asList(result.get("rounds"))) {
            Map<String, Object> round = asMap(rawRound);
            report.add("### Round " + round.get("round") + " " + DASH + " "
                    + asMap(round.get("diagnosis")).get("summary"));
            report.add("");
            List<Object> candidates = asList(round.get("candidates"));
            if (candidates.isEmpty()) {
                report.add("- No candidates generated");
            }
            for (Object rawCandidate : candidates) {
                Map<String, Object> candidate = asMap(rawCandidate);
                Map<String, Object> evaluation = asMap(candidate.get("evaluation"));
                List<String> reasons = new ArrayList<>();
                for (Object reason : asList(evaluation.get("reasons"))) {
                    reasons.add(String.valueOf(reason));
                }
                report.add(
                        "- `" + asMap(candidate.get("candidate")).get("candidate_id") + "` → **"
                                + evaluation.get("decision") + "** " + DASH + " "
                                + String.join("; ", reasons));
            }
            report.add("");
        }
        report.add("> Accepted artifacts remain inside the sandbox. Production promotion is outside this loop.");
        report.add("");
        AtomicFiles.writeText(outputDir.resolve("auto_evolution_report.md"), Fmt.join(report));
    }
}
