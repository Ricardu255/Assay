package io.assay.audit;

import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the scope-audit artifacts.
 *
 * <p>The patch itself is always written next to the report so a reviewer can check any finding against
 * the exact code that produced it — the report's line numbers are meaningless without it.
 */
public final class ScopeAuditReport {

    private static final String DASH = "—";

    private ScopeAuditReport() {
    }

    public static void writeArtifacts(Map<String, Object> result, Path outputDir, String rawDiff) {
        AtomicFiles.createDirectories(outputDir);
        AtomicFiles.writeText(outputDir.resolve("diff.patch"), rawDiff == null ? "" : rawDiff);
        AtomicFiles.writeText(outputDir.resolve("scope_audit.json"), Json.write(result, 2) + "\n");

        List<String> report = new ArrayList<>();
        report.add("# AI Code Change Scope Audit");
        report.add("");
        report.add("- Base: `" + result.get("base_commit") + "`");
        report.add("- Target: `" + result.get("target_commit") + "`");
        report.add("- Conclusion: **" + result.get("conclusion") + "**");
        report.add("");
        report.add("- Diff: [complete patch](diff.patch), +" + result.get("additions") + " / -"
                + result.get("deletions"));
        report.add("- Diff SHA-256: `" + result.get("diff_sha256") + "`");
        report.add("- Allowed paths: " + joinOr(asList(result.get("allowed_paths")), "Not declared"));
        report.add("- Forbidden paths: " + joinOr(asList(result.get("forbidden_paths")), "Not declared"));
        report.add("");
        report.add("## Requirement");
        report.add("");
        String requirement = String.valueOf(result.getOrDefault("requirement", ""));
        report.add(requirement.isEmpty() ? "Not supplied." : requirement);
        report.add("");
        report.add("## Acceptance criteria");
        report.add("");
        for (Object criterion : asList(result.get("acceptance_criteria"))) {
            report.add("- " + criterion);
        }
        report.add("");
        report.add("## Changed files");
        report.add("");
        for (Object path : asList(result.get("changed_files"))) {
            report.add("- `" + path + "`");
        }
        report.add("");
        report.add("## Scope findings");
        report.add("");

        List<Object> findings = asList(result.get("findings"));
        for (Object rawFinding : findings) {
            Map<String, Object> finding = asMap(rawFinding);
            Object line = finding.get("line");
            String location = finding.get("path") + ":" + (line == null ? "?" : line)
                    + " (" + finding.get("line_side") + ")";
            String evidence = String.valueOf(finding.getOrDefault("evidence", "")).replace("`", "\\`");
            report.add("- `" + location + "` " + DASH + " **" + finding.get("status") + "** / "
                    + finding.get("risk") + " / " + finding.get("source") + ": " + finding.get("reason"));
            report.add("  - Evidence: `" + evidence + "`");
        }
        if (findings.isEmpty()) {
            report.add("- No changed code was available to assess.");
        }

        List<Object> traces = asList(result.get("model_finding_trace"));
        if (!traces.isEmpty()) {
            report.add("");
            report.add("## Model finding decisions");
            report.add("");
            for (Object rawTrace : traces) {
                Map<String, Object> trace = asMap(rawTrace);
                Map<String, Object> raw = asMap(trace.get("raw"));
                String location = raw.getOrDefault("path", "?") + ":" + raw.getOrDefault("line", "?")
                        + " (" + raw.getOrDefault("line_side", "target") + ")";
                String retained = trace.get("final_finding_index") != null
                        ? "scope finding #" + trace.get("final_finding_index")
                        : "no scope finding";
                report.add("- Model #" + trace.get("index") + " `" + location + "` / `"
                        + raw.getOrDefault("status", "?") + "`: " + trace.get("validation") + ", "
                        + trace.get("disposition") + " → " + retained + ". " + trace.get("reason"));
            }
        }

        Map<String, Object> testSelection = asMap(result.get("test_selection"));
        Map<String, Object> tests = asMap(result.get("tests"));
        report.add("");
        report.add("## Selected regression tests");
        report.add("");
        report.add("- Selection: `" + testSelection.get("mode") + "` → "
                + joinOr(asList(testSelection.get("suites")), "none"));
        report.add("- Execution: **" + tests.get("status") + "**");
        report.add("");
        for (Object rawRun : asList(tests.get("runs"))) {
            Map<String, Object> run = asMap(rawRun);
            report.add("- `" + run.get("suite") + "`: **" + run.get("status") + "**, " + run.get("case_count")
                    + " cases, " + run.get("hard_failures") + " hard failures ([details]("
                    + run.get("report_path") + ")).");
        }
        report.add("");
        report.add("## Uncovered risks");
        report.add("");
        List<Object> risks = asList(result.get("uncovered_risks"));
        for (Object risk : risks) {
            report.add("- " + risk);
        }
        if (risks.isEmpty()) {
            report.add("- No additional gap identified in this run; tests do not prove absence of scope violations.");
        }
        report.add("");
        report.add("Passing tests do not prove that the change stayed within the requested scope.");
        report.add("");
        AtomicFiles.writeText(outputDir.resolve("report.md"), String.join("\n", report));
    }

    private static String joinOr(List<Object> values, String fallback) {
        if (values.isEmpty()) {
            return fallback;
        }
        List<String> items = new ArrayList<>(values.size());
        for (Object value : values) {
            items.add(String.valueOf(value));
        }
        return String.join(", ", items);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
