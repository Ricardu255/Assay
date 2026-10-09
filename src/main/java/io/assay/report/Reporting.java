package io.assay.report;

import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a machine-readable run result into the human-readable artifacts.
 *
 * <p>The JSON is the source of truth; the Markdown report is derived from it, never the other way
 * round. Every report keeps the LLM's contribution labelled as a non-authoritative candidate.
 */
public final class Reporting {

    private static final String BOX_DRAWING_DASH = "—";

    private Reporting() {
    }

    static List<Map<String, Object>> failedChecks(Map<String, Object> result) {
        List<Map<String, Object>> failed = new ArrayList<>();
        Object checks = result.get("checks");
        if (checks instanceof List<?> items) {
            for (Object item : items) {
                Map<String, Object> check = asMap(item);
                if (!Boolean.TRUE.equals(check.get("passed"))) {
                    failed.add(check);
                }
            }
        }
        return failed;
    }

    /** Wilson score interval, used so a small scenario sample is not read as a precise rate. */
    static double[] wilson(int successes, int total) {
        if (total == 0) {
            return new double[] {0.0, 0.0};
        }
        double z = 1.959963984540054;
        double rate = (double) successes / total;
        double denominator = 1 + z * z / total;
        double centre = rate + z * z / (2.0 * total);
        double margin = z * Math.sqrt((rate * (1 - rate) + z * z / (4.0 * total)) / total);
        return new double[] {(centre - margin) / denominator, (centre + margin) / denominator};
    }

    /** Writes {@code results.json}, {@code report.md}, {@code scenario_stats.json}, and the queues. */
    public static void writeRunArtifacts(Map<String, Object> summary, Path outputDir) {
        AtomicFiles.createDirectories(outputDir);
        AtomicFiles.writeText(outputDir.resolve("results.json"), Json.write(summary, 2) + "\n");

        Map<String, Integer> moduleCounts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> scenarioStats = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> targetScenarioStats = new LinkedHashMap<>();
        List<Map<String, Object>> reviewRows = new ArrayList<>();
        List<Map<String, Object>> fewShotRows = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (Object rawResult : asList(summary.get("results"))) {
            Map<String, Object> result = asMap(rawResult);
            Map<String, Object> evalCase = asMap(result.get("case"));
            String scenario = String.valueOf(evalCase.get("scenario"));
            Map<String, Object> trace = result.get("trace") instanceof Map ? asMap(result.get("trace")) : Map.of();
            String target = trace.getOrDefault("target_type", "unknown") + ":" + trace.getOrDefault("target_id", "unknown");
            for (Map<String, Integer> stats :
                    List.of(
                            scenarioStats.computeIfAbsent(scenario, key -> newStats()),
                            targetScenarioStats.computeIfAbsent(target + "|" + scenario, key -> newStats()))) {
                stats.merge("cases", 1, Integer::sum);
                stats.merge("hard_passes", Boolean.TRUE.equals(result.get("hard_pass")) ? 1 : 0, Integer::sum);
                stats.merge("soft_warnings", intValue(result.get("soft_warning_count")), Integer::sum);
            }
            List<Map<String, Object>> failed = failedChecks(result);
            if (!failed.isEmpty()) {
                for (Map<String, Object> check : failed) {
                    Object modules = check.get("suspected_modules");
                    if (modules instanceof List<?> items) {
                        for (Object module : items) {
                            moduleCounts.merge(String.valueOf(module), 1, Integer::sum);
                        }
                    }
                }
                List<String> names = new ArrayList<>();
                for (Map<String, Object> check : failed) {
                    names.add(check.get("layer") + "/" + check.get("name"));
                }
                failures.add(
                        "- `" + evalCase.get("case_id") + "` (" + evalCase.get("scenario") + "): "
                                + String.join("; ", names));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("run_id", summary.get("run_id"));
                row.put("case_id", evalCase.get("case_id"));
                row.put("auto_result", Boolean.TRUE.equals(result.get("hard_pass")) ? "review_candidate" : "hard_failure");
                row.put("failed_checks", failed);
                row.put("llm_review", result.getOrDefault("llm_review", Map.of()));
                row.put("human_decision", "");
                row.put("human_final_conclusion", "");
                reviewRows.add(row);
            }
            Map<String, Object> candidate = asMap(result.get("few_shot_candidate"));
            if (!candidate.isEmpty()) {
                fewShotRows.add(candidate);
            }
        }

        List<String> report = new ArrayList<>();
        report.add("# Agent Evaluation Report " + BOX_DRAWING_DASH + " " + summary.get("run_id"));
        report.add("");
        report.add("- Suite: `" + summary.get("suite") + "`");
        report.add("- Source: `" + summary.get("source") + "`");
        report.add("- Status: **" + summary.get("status") + "**");
        report.add("- Cases: " + summary.get("case_count"));
        report.add("- Hard failures: " + summary.get("hard_failures"));
        report.add("- Soft/review warnings: " + summary.get("soft_warnings"));
        report.add("");
        report.add("## Suspected modules");
        report.add("");
        report.addAll(mostCommon(moduleCounts));

        Map<String, Object> scenarioOutput = new LinkedHashMap<>();
        scenarioOutput.put("scenarios", new LinkedHashMap<String, Object>());
        scenarioOutput.put("target_scenarios", new LinkedHashMap<String, Object>());
        report.add("");
        report.add("## Scenario statistics");
        report.add("");
        Map<String, Object> scenarios = asMap(scenarioOutput.get("scenarios"));
        for (Map.Entry<String, Map<String, Integer>> entry : sorted(scenarioStats).entrySet()) {
            Map<String, Integer> stats = entry.getValue();
            double[] interval = wilson(stats.get("hard_passes"), stats.get("cases"));
            double rate = (double) stats.get("hard_passes") / stats.get("cases");
            Map<String, Object> row = new LinkedHashMap<>(stats);
            row.put("hard_pass_rate", rate);
            row.put("confidence_95", List.of(interval[0], interval[1]));
            scenarios.put(entry.getKey(), row);
            report.add(
                    "- `" + entry.getKey() + "`: " + stats.get("hard_passes") + "/" + stats.get("cases")
                            + " (" + Fmt.percent1(rate) + ", 95% CI " + Fmt.percent1(interval[0]) + "–"
                            + Fmt.percent1(interval[1]) + "), soft warnings " + stats.get("soft_warnings"));
        }
        Map<String, Object> targetScenarios = asMap(scenarioOutput.get("target_scenarios"));
        for (Map.Entry<String, Map<String, Integer>> entry : sorted(targetScenarioStats).entrySet()) {
            Map<String, Integer> stats = entry.getValue();
            double[] interval = wilson(stats.get("hard_passes"), stats.get("cases"));
            Map<String, Object> row = new LinkedHashMap<>(stats);
            row.put("hard_pass_rate", (double) stats.get("hard_passes") / stats.get("cases"));
            row.put("confidence_95", List.of(interval[0], interval[1]));
            targetScenarios.put(entry.getKey(), row);
        }

        report.add("");
        report.add("## Failed or review-candidate cases");
        report.add("");
        report.addAll(failures.isEmpty() ? List.of("- None") : failures);
        report.add("");
        report.add("> LLM analysis and module suggestions are non-authoritative candidates. "
                + "Human final conclusions must be stored separately.");
        report.add("");
        AtomicFiles.writeText(outputDir.resolve("report.md"), Fmt.join(report));
        AtomicFiles.writeText(outputDir.resolve("scenario_stats.json"), Json.write(scenarioOutput, 2) + "\n");
        writeJsonl(outputDir.resolve("review_queue.jsonl"), reviewRows);
        writeJsonl(outputDir.resolve("few_shot_candidates.jsonl"), fewShotRows);
    }

    /** Writes {@code evolution.json} and {@code evolution_report.md}. */
    public static void writeEvolutionArtifacts(Map<String, Object> result, Path outputDir) {
        AtomicFiles.createDirectories(outputDir);
        AtomicFiles.writeText(outputDir.resolve("evolution.json"), Json.write(result, 2) + "\n");

        Map<String, Object> candidate = asMap(result.get("candidate"));
        List<String> report = new ArrayList<>();
        report.add("# Evolution Report " + BOX_DRAWING_DASH + " " + result.get("experiment_id"));
        report.add("");
        report.add("- Decision: **" + result.get("decision") + "**");
        report.add("- Candidate: `" + candidate.get("candidate_id") + "`");
        report.add("- Target: `" + candidate.get("target_type") + ":" + candidate.get("target_id") + "`");
        report.add("- Version: `" + candidate.get("baseline_version") + "` → `" + candidate.get("candidate_version") + "`");
        report.add("- Change type: `" + candidate.get("change_type") + "`");
        report.add("");
        report.add("## Version comparison");
        report.add("");
        report.add("| Dataset | Baseline hard pass | Candidate hard pass | Delta | Soft warning delta | Latency delta |");
        report.add("|---|---:|---:|---:|---:|---:|");
        Map<String, Object> comparisons = asMap(result.get("comparisons"));
        for (String role : List.of("improvement", "regression", "holdout")) {
            Map<String, Object> comparison = asMap(comparisons.get(role));
            report.add(
                    "| " + role + " | " + Fmt.percent1(rate(asMap(comparison.get("baseline")))) + " | "
                            + Fmt.percent1(rate(asMap(comparison.get("candidate")))) + " | "
                            + Fmt.signedPercent1(doubleValue(comparison.get("hard_pass_rate_delta"))) + " | "
                            + Fmt.signedInt(intValue(comparison.get("soft_warning_delta"))) + " | "
                            + Fmt.signedFixed1(doubleValue(comparison.get("mean_latency_ms_delta"))) + " ms |");
        }

        Map<String, Object> policy = asMap(result.get("policy"));
        List<Object> objectives = asList(policy.get("objectives"));
        if (!objectives.isEmpty()) {
            report.add("");
            report.add("## Configured objectives");
            report.add("");
            report.add("| Dataset | Objective | Baseline | Candidate |");
            report.add("|---|---|---:|---:|");
            for (String role : List.of("improvement", "regression", "holdout")) {
                Map<String, Object> comparison = asMap(comparisons.get(role));
                Map<String, Object> baselineObjectives =
                        asMap(asMap(comparison.get("baseline")).get("objectives"));
                Map<String, Object> candidateObjectives =
                        asMap(asMap(comparison.get("candidate")).get("objectives"));
                for (Object rawObjective : objectives) {
                    Map<String, Object> objective = asMap(rawObjective);
                    String name = String.valueOf(objective.get("name"));
                    report.add(
                            "| " + role + " | " + name + " | " + render(baselineObjectives.get(name)) + " | "
                                    + render(candidateObjectives.get(name)) + " |");
                }
            }
        }

        List<Object> scenarioGates = asList(policy.get("scenario_gates"));
        if (!scenarioGates.isEmpty()) {
            report.add("");
            report.add("## Configured scenario gates");
            report.add("");
            report.add("| Dataset | Scenario | Cases | Baseline hard pass | Candidate hard pass | Delta | Soft warning delta |");
            report.add("|---|---|---:|---:|---:|---:|---:|");
            for (Object rawGate : scenarioGates) {
                Map<String, Object> gate = asMap(rawGate);
                String scenarioName = String.valueOf(gate.get("scenario"));
                for (Object rawRole : asList(gate.get("roles"))) {
                    String role = String.valueOf(rawRole);
                    Map<String, Object> scenarios = asMap(asMap(comparisons.get(role)).get("scenarios"));
                    Map<String, Object> scenario = scenarios.get(scenarioName) instanceof Map
                            ? asMap(scenarios.get(scenarioName))
                            : null;
                    if (scenario == null || scenario.get("candidate") == null) {
                        report.add("| " + role + " | " + scenarioName + " | missing | - | - | - | - |");
                        continue;
                    }
                    Map<String, Object> baseline = asMap(scenario.get("baseline"));
                    Map<String, Object> candidateValue = asMap(scenario.get("candidate"));
                    report.add(
                            "| " + role + " | " + scenarioName + " | " + candidateValue.get("case_count") + " | "
                                    + Fmt.percent1(rate(baseline)) + " | "
                                    + Fmt.percent1(rate(candidateValue)) + " | "
                                    + Fmt.signedPercent1(doubleValue(scenario.get("hard_pass_rate_delta"))) + " | "
                                    + Fmt.signedInt(intValue(scenario.get("soft_warning_delta"))) + " |");
                }
            }
        }

        report.add("");
        report.add("## Decision evidence");
        report.add("");
        for (Object reason : asList(result.get("reasons"))) {
            report.add("- " + reason);
        }
        report.add("");
        report.add("## Run lineage");
        report.add("");
        Map<String, Object> baselineRuns = asMap(result.get("baseline_runs"));
        Map<String, Object> candidateRuns = asMap(result.get("candidate_runs"));
        for (String role : List.of("improvement", "regression", "holdout")) {
            report.add(
                    "- `" + role + "`: `" + asMap(baselineRuns.get(role)).get("run_id") + "` → `"
                            + asMap(candidateRuns.get(role)).get("run_id") + "`");
        }
        AtomicFiles.writeText(outputDir.resolve("evolution_report.md"), Fmt.join(report));
    }

    /** Writes {@code auto_evolution.json} and {@code auto_evolution_report.md}. */
    public static void writeAutoEvolutionArtifacts(Map<String, Object> result, Path outputDir) {
        AtomicFiles.createDirectories(outputDir);
        AtomicFiles.writeText(outputDir.resolve("auto_evolution.json"), Json.write(result, 2) + "\n");
        Map<String, Object> usage = asMap(result.get("usage"));
        List<String> report = new ArrayList<>();
        report.add("# Automatic Evolution Report " + BOX_DRAWING_DASH + " " + result.get("loop_id"));
        report.add("");
        report.add("- Status: **" + result.get("status") + "**");
        report.add("- Target: `" + result.get("target_type") + ":" + result.get("target_id") + "`");
        report.add("- Version: `" + result.get("initial_version") + "` → `" + result.get("current_version") + "`");
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
            report.add("### Round " + round.get("round") + " " + BOX_DRAWING_DASH + " "
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
                                + evaluation.get("decision") + "** " + BOX_DRAWING_DASH + " "
                                + String.join("; ", reasons));
            }
            report.add("");
        }
        report.add("> Accepted artifacts remain inside the sandbox. Production promotion is outside this loop.");
        report.add("");
        AtomicFiles.writeText(outputDir.resolve("auto_evolution_report.md"), Fmt.join(report));
    }

    private static Map<String, Integer> newStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("cases", 0);
        stats.put("hard_passes", 0);
        stats.put("soft_warnings", 0);
        return stats;
    }

    private static List<String> mostCommon(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return List.of("- None");
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed());
        List<String> lines = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> entry : entries) {
            lines.add("- `" + entry.getKey() + "`: " + entry.getValue());
        }
        return lines;
    }

    private static Map<String, Map<String, Integer>> sorted(Map<String, Map<String, Integer>> source) {
        return new TreeMap<>(source);
    }

    private static void writeJsonl(Path path, List<Map<String, Object>> rows) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> row : rows) {
            builder.append(Json.write(row)).append('\n');
        }
        AtomicFiles.writeText(path, builder.toString());
    }

    private static double rate(Map<String, Object> metrics) {
        Object value = metrics.get("hard_pass_rate");
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static String render(Object value) {
        return value == null ? "None" : String.valueOf(value);
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
