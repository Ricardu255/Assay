package io.assay.report;

import static io.assay.report.ReportSupport.DASH;
import static io.assay.report.ReportSupport.asList;
import static io.assay.report.ReportSupport.asMap;
import static io.assay.report.ReportSupport.intValue;
import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Writes per-run artifacts: results, report, scenario stats, review queue, and few-shot candidates. */
final class RunReportWriter {

    private RunReportWriter() {
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
    static void writeRunArtifacts(Map<String, Object> summary, Path outputDir) {
        AtomicFiles.createDirectories(outputDir);
        Map<String, Object> ruleStats = RuleStats.fromResults(
                asList(summary.get("results")), intValue(summary.get("case_count")));
        summary.put("rule_stats", ruleStats);
        Map<String, Object> multiAgent = multiAgentMetrics(asList(summary.get("results")));
        if (!multiAgent.isEmpty()) {
            summary.put("multi_agent", multiAgent);
        }
        Map<String, Object> metricStats = MetricSummary.fromResults(asList(summary.get("results")));
        if (!metricStats.isEmpty()) {
            summary.put("metric_stats", metricStats);
        }
        AtomicFiles.writeText(outputDir.resolve("results.json"), Json.write(summary, 2) + "\n");

        RunDetails details = collectRunDetails(summary);
        List<String> report = renderRunReport(summary, ruleStats, multiAgent, metricStats, details);
        AtomicFiles.writeText(outputDir.resolve("report.md"), Fmt.join(report));
        AtomicFiles.writeText(
                outputDir.resolve("scenario_stats.json"),
                Json.write(details.scenarioOutput, 2) + "\n");
        writeJsonl(outputDir.resolve("review_queue.jsonl"), details.reviewRows);
        writeJsonl(outputDir.resolve("few_shot_candidates.jsonl"), details.fewShotRows);
    }

    private static RunDetails collectRunDetails(Map<String, Object> summary) {
        RunDetails details = new RunDetails();
        details.runId = summary.get("run_id");
        for (Object rawResult : asList(summary.get("results"))) {
            collectResult(asMap(rawResult), details);
        }
        return details;
    }

    private static void collectResult(Map<String, Object> result, RunDetails details) {
        Map<String, Object> evalCase = asMap(result.get("case"));
        String scenario = String.valueOf(evalCase.get("scenario"));
        Map<String, Object> trace =
                result.get("trace") instanceof Map ? asMap(result.get("trace")) : Map.of();
        String target = trace.getOrDefault("target_type", "unknown") + ":"
                + trace.getOrDefault("target_id", "unknown");
        for (Map<String, Integer> stats : List.of(
                details.scenarioStats.computeIfAbsent(scenario, key -> newStats()),
                details.targetScenarioStats.computeIfAbsent(
                        target + "|" + scenario, key -> newStats()))) {
            stats.merge("cases", 1, Integer::sum);
            stats.merge(
                    "hard_passes", Boolean.TRUE.equals(result.get("hard_pass")) ? 1 : 0, Integer::sum);
            stats.merge("soft_warnings", intValue(result.get("soft_warning_count")), Integer::sum);
        }

        List<Map<String, Object>> failed = failedChecks(result);
        if (!failed.isEmpty()) {
            collectFailedCase(result, evalCase, failed, details);
        }
        Map<String, Object> candidate = asMap(result.get("few_shot_candidate"));
        if (!candidate.isEmpty()) {
            details.fewShotRows.add(candidate);
        }
    }

    private static void collectFailedCase(
            Map<String, Object> result,
            Map<String, Object> evalCase,
            List<Map<String, Object>> failed,
            RunDetails details) {
        for (Map<String, Object> check : failed) {
            Object modules = check.get("suspected_modules");
            if (modules instanceof List<?> items) {
                for (Object module : items) {
                    details.moduleCounts.merge(String.valueOf(module), 1, Integer::sum);
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> check : failed) {
            names.add(check.get("layer") + "/" + check.get("name"));
        }
        details.failures.add(
                "- `" + evalCase.get("case_id") + "` (" + evalCase.get("scenario") + "): "
                        + String.join("; ", names));
        Map<String, Object> row = new LinkedHashMap<>();
        Object resultRunId = result.get("run_id");
        row.put("run_id", resultRunId != null ? resultRunId : details.runId);
        row.put("case_id", evalCase.get("case_id"));
        row.put(
                "auto_result",
                Boolean.TRUE.equals(result.get("hard_pass")) ? "review_candidate" : "hard_failure");
        row.put("failed_checks", failed);
        row.put("llm_review", result.getOrDefault("llm_review", Map.of()));
        row.put("human_decision", "");
        row.put("human_final_conclusion", "");
        details.reviewRows.add(row);
    }

    private static List<String> renderRunReport(
            Map<String, Object> summary,
            Map<String, Object> ruleStats,
            Map<String, Object> multiAgent,
            Map<String, Object> metricStats,
            RunDetails details) {
        List<String> report = new ArrayList<>();
        report.add("# Agent Evaluation Report " + DASH + " " + summary.get("run_id"));
        report.add("");
        report.add("- Suite: `" + summary.get("suite") + "`");
        report.add("- Source: `" + summary.get("source") + "`");
        report.add("- Status: **" + summary.get("status") + "**");
        report.add("- Cases: " + summary.get("case_count"));
        report.add("- Hard failures: " + summary.get("hard_failures"));
        report.add("- Soft/review warnings: " + summary.get("soft_warnings"));
        appendBudget(report, summary);

        report.add("");
        report.add("## Suspected modules");
        report.add("");
        report.addAll(mostCommon(details.moduleCounts));

        appendScenarioStats(report, details);
        appendMultiAgent(report, details, multiAgent);
        appendRuleStats(report, ruleStats);
        appendMetricStats(report, metricStats);

        report.add("");
        report.add("## Failed or review-candidate cases");
        report.add("");
        report.addAll(details.failures.isEmpty() ? List.of("- None") : details.failures);
        report.add("");
        report.add("> LLM analysis and module suggestions are non-authoritative candidates. "
                + "Human final conclusions must be stored separately.");
        report.add("");
        return report;
    }

    private static void appendBudget(List<String> report, Map<String, Object> summary) {
        Map<String, Object> budget = asMap(summary.get("budget"));
        if (!Boolean.TRUE.equals(budget.get("enabled"))) {
            return;
        }
        if (budget.get("max_total_tokens") != null) {
            report.add("- Budget tokens: " + budget.get("spent_tokens") + " / "
                    + budget.get("max_total_tokens"));
        }
        if (budget.get("max_cost_usd") != null) {
            report.add("- Budget cost: " + budget.get("spent_cost_usd") + " / "
                    + budget.get("max_cost_usd") + " USD");
        }
        if (intValue(summary.get("skipped_case_count")) > 0) {
            report.add("- Cases skipped by budget: " + summary.get("skipped_case_count"));
        }
    }

    private static void appendScenarioStats(List<String> report, RunDetails details) {
        report.add("");
        report.add("## Scenario statistics");
        report.add("");
        Map<String, Object> scenarios = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : sorted(details.scenarioStats).entrySet()) {
            Map<String, Integer> stats = entry.getValue();
            double[] interval = wilson(stats.get("hard_passes"), stats.get("cases"));
            double rate = (double) stats.get("hard_passes") / stats.get("cases");
            Map<String, Object> row = new LinkedHashMap<>(stats);
            row.put("hard_pass_rate", rate);
            row.put("confidence_95", List.of(interval[0], interval[1]));
            scenarios.put(entry.getKey(), row);
            report.add(
                    "- `" + entry.getKey() + "`: " + stats.get("hard_passes") + "/" + stats.get("cases")
                            + " (" + Fmt.percent1(rate) + ", 95% CI " + Fmt.percent1(interval[0])
                            + "–" + Fmt.percent1(interval[1]) + "), soft warnings "
                            + stats.get("soft_warnings"));
        }
        details.scenarioOutput.put("scenarios", scenarios);

        Map<String, Object> targetScenarios = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry
                : sorted(details.targetScenarioStats).entrySet()) {
            Map<String, Integer> stats = entry.getValue();
            double[] interval = wilson(stats.get("hard_passes"), stats.get("cases"));
            Map<String, Object> row = new LinkedHashMap<>(stats);
            row.put("hard_pass_rate", (double) stats.get("hard_passes") / stats.get("cases"));
            row.put("confidence_95", List.of(interval[0], interval[1]));
            targetScenarios.put(entry.getKey(), row);
        }
        details.scenarioOutput.put("target_scenarios", targetScenarios);
    }

    private static void appendMultiAgent(
            List<String> report, RunDetails details, Map<String, Object> multiAgent) {
        if (multiAgent.isEmpty()) {
            return;
        }
        details.scenarioOutput.put("multi_agent", multiAgent);
        report.add("");
        report.add("## Multi-agent metrics");
        report.add("");
        report.add("- Cases with trace metrics: " + multiAgent.get("cases"));
        report.add("- Steps: " + multiAgent.get("steps") + ", model calls: "
                + multiAgent.get("model_calls") + ", tool calls: " + multiAgent.get("tool_calls")
                + ", handoffs: " + multiAgent.get("handoffs") + ", denied tools: "
                + multiAgent.get("denied_tools"));
        report.add("- Longest repeated step: " + multiAgent.get("max_repeated_step"));
        report.add("- Agents: " + multiAgent.get("agents"));
    }

    private static void appendMetricStats(List<String> report, Map<String, Object> metricStats) {
        if (metricStats.isEmpty()) {
            return;
        }
        report.add("");
        report.add("## Metric scores");
        report.add("");
        report.add("| Metric | Level | Evaluated | Passed | Rate | Mean score | Skipped | Errors | Cached |");
        report.add("|---|---|---:|---:|---:|---:|---:|---:|---:|");
        for (Map.Entry<String, Object> entry : metricStats.entrySet()) {
            Map<String, Object> row = asMap(entry.getValue());
            Object rate = row.get("pass_rate");
            Object meanScore = row.get("mean_score");
            report.add("| `" + entry.getKey() + "` | " + row.get("level") + " | "
                    + intValue(row.get("evaluated")) + " | " + intValue(row.get("passed")) + " | "
                    + (rate instanceof Number number ? Fmt.percent1(number.doubleValue()) : DASH)
                    + " | "
                    + (meanScore instanceof Number number
                            ? String.format(Locale.ROOT, "%.3f", number.doubleValue()) : DASH)
                    + " | " + intValue(row.get("skipped")) + " | " + intValue(row.get("errors")) + " | "
                    + intValue(row.get("cached")) + " |");
        }
    }

    private static void appendRuleStats(List<String> report, Map<String, Object> ruleStats) {
        if (ruleStats.isEmpty()) {
            return;
        }
        report.add("");
        report.add("## Rule pass rates");
        report.add("");
        report.add("| Rule | Level | Evaluated | Passed | Rate | Missing |");
        report.add("|---|---|---:|---:|---:|---:|");
        for (Map.Entry<String, Object> entry : ruleStats.entrySet()) {
            Map<String, Object> row = asMap(entry.getValue());
            Object rate = row.get("pass_rate");
            report.add("| `" + entry.getKey() + "` | " + row.get("level") + " | "
                    + intValue(row.get("evaluated")) + " | " + intValue(row.get("passed")) + " | "
                    + (rate instanceof Number number ? Fmt.percent1(number.doubleValue()) : DASH)
                    + " | " + intValue(row.get("missing")) + " |");
        }
    }

    private static final class RunDetails {
        private final Map<String, Integer> moduleCounts = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> scenarioStats = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> targetScenarioStats = new LinkedHashMap<>();
        private final List<Map<String, Object>> reviewRows = new ArrayList<>();
        private final List<Map<String, Object>> fewShotRows = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final Map<String, Object> scenarioOutput = new LinkedHashMap<>();
        private Object runId;
    }

    /** Aggregates the multi-agent counters that adapters attach to trace fields, when present. */
    private static Map<String, Object> multiAgentMetrics(List<Object> results) {
        long steps = 0;
        long modelCalls = 0;
        long toolCalls = 0;
        long handoffs = 0;
        long deniedTools = 0;
        int cases = 0;
        int maxRepeatedStep = 0;
        Set<String> agents = new LinkedHashSet<>();
        for (Object rawResult : results) {
            Map<String, Object> result = asMap(rawResult);
            Map<String, Object> trace =
                    result.get("trace") instanceof Map ? asMap(result.get("trace")) : Map.of();
            Map<String, Object> metrics = asMap(trace.get("metrics"));
            if (metrics.isEmpty()) {
                continue;
            }
            cases++;
            steps += intValue(metrics.get("steps"));
            modelCalls += intValue(metrics.get("model_calls"));
            toolCalls += intValue(metrics.get("tool_calls"));
            handoffs += intValue(metrics.get("handoffs"));
            deniedTools += intValue(metrics.get("denied_tools"));
            maxRepeatedStep = Math.max(maxRepeatedStep, intValue(metrics.get("max_repeated_step")));
            for (Object agent : asList(metrics.get("agents"))) {
                if (agent != null) {
                    agents.add(String.valueOf(agent));
                }
            }
        }
        Map<String, Object> aggregated = new LinkedHashMap<>();
        if (cases == 0) {
            return aggregated;
        }
        aggregated.put("cases", cases);
        aggregated.put("steps", steps);
        aggregated.put("model_calls", modelCalls);
        aggregated.put("tool_calls", toolCalls);
        aggregated.put("handoffs", handoffs);
        aggregated.put("denied_tools", deniedTools);
        aggregated.put("max_repeated_step", maxRepeatedStep);
        aggregated.put("agents", new ArrayList<>(agents));
        return aggregated;
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
        entries.sort(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue())
                .reversed());
        List<String> lines = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> entry : entries) {
            lines.add("- `" + entry.getKey() + "`: " + entry.getValue());
        }
        return lines;
    }

    private static Map<String, Map<String, Integer>> sorted(
            Map<String, Map<String, Integer>> source) {
        return new TreeMap<>(source);
    }

    private static void writeJsonl(Path path, List<Map<String, Object>> rows) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> row : rows) {
            builder.append(Json.write(row)).append('\n');
        }
        AtomicFiles.writeText(path, builder.toString());
    }
}
