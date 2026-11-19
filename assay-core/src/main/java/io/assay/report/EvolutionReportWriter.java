package io.assay.report;

import static io.assay.report.ReportSupport.DASH;
import static io.assay.report.ReportSupport.asList;
import static io.assay.report.ReportSupport.asMap;
import static io.assay.report.ReportSupport.cell;
import static io.assay.report.ReportSupport.deltaText;
import static io.assay.report.ReportSupport.doubleValue;
import static io.assay.report.ReportSupport.intValue;
import static io.assay.report.ReportSupport.latencySamples;
import static io.assay.report.ReportSupport.passRate;
import static io.assay.report.ReportSupport.rate;
import static io.assay.report.ReportSupport.rateText;
import static io.assay.report.ReportSupport.render;
import static io.assay.report.ReportSupport.renderOptional;
import static io.assay.report.ReportSupport.renderPercent;
import static io.assay.report.ReportSupport.sampleCoverage;
import static io.assay.report.ReportSupport.samples;
import static io.assay.report.ReportSupport.scoreText;
import static io.assay.report.ReportSupport.text;
import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes {@code evolution.json} and {@code evolution_report.md}. */
final class EvolutionReportWriter {

    private EvolutionReportWriter() {
    }

    static void writeEvolutionArtifacts(Map<String, Object> result, Path outputDir) {
        AtomicFiles.createDirectories(outputDir);
        AtomicFiles.writeText(outputDir.resolve("evolution.json"), Json.write(result, 2) + "\n");

        Map<String, Object> candidate = asMap(result.get("candidate"));
        List<String> report = new ArrayList<>();
        appendEvolutionHeader(report, result, candidate);
        Map<String, Object> comparisons = asMap(result.get("comparisons"));
        if (comparisons.isEmpty()) {
            report.add("");
            report.add("## Version comparison");
            report.add("");
            report.add("- No runs were executed because the policy rejected the datasets.");
        } else {
            appendComparisonTable(report, comparisons);
            appendCaseTransitions(report, result);

            Map<String, Object> policy = asMap(result.get("policy"));
            appendObjectives(report, comparisons, asList(policy.get("objectives")));
            appendScenarioGates(report, comparisons, asList(policy.get("scenario_gates")));
            appendQualityGates(report, asList(policy.get("quality_gates")));
            appendRuleDeltas(report, comparisons);
            appendMetricDeltas(report, comparisons);
        }
        appendDecisionAndLineage(report, result);
        AtomicFiles.writeText(outputDir.resolve("evolution_report.md"), Fmt.join(report));
    }

    private static void appendEvolutionHeader(
            List<String> report, Map<String, Object> result, Map<String, Object> candidate) {
        report.add("# Evolution Report " + DASH + " " + result.get("experiment_id"));
        report.add("");
        report.add("- Decision: **" + result.get("decision") + "**");
        report.add("- Candidate: `" + candidate.get("candidate_id") + "`");
        report.add("- Target: `" + candidate.get("target_type") + ":" + candidate.get("target_id") + "`");
        report.add("- Version: `" + candidate.get("baseline_version") + "` → `"
                + candidate.get("candidate_version") + "`");
        report.add("- Change type: `" + candidate.get("change_type") + "`");
    }

    private static void appendComparisonTable(
            List<String> report, Map<String, Object> comparisons) {
        report.add("");
        report.add("## Version comparison");
        report.add("");
        report.add("| Dataset | Baseline hard pass | Candidate hard pass | Delta | Soft warning delta "
                + "| Mean latency delta | P95 latency delta |");
        report.add("|---|---:|---:|---:|---:|---:|---:|");
        for (String role : List.of("improvement", "regression", "holdout")) {
            Map<String, Object> comparison = asMap(comparisons.get(role));
            report.add(
                    "| " + role + " | " + Fmt.percent1(rate(asMap(comparison.get("baseline")))) + " | "
                            + Fmt.percent1(rate(asMap(comparison.get("candidate")))) + " | "
                            + Fmt.signedPercent1(doubleValue(comparison.get("hard_pass_rate_delta"))) + " | "
                            + Fmt.signedInt(intValue(comparison.get("soft_warning_delta"))) + " | "
                            + (comparison.get("mean_latency_ms_delta") instanceof Number latency
                                    ? Fmt.signedFixed1(latency.doubleValue()) + " ms" : "missing") + " | "
                            + (comparison.get("p95_latency_ms_delta") instanceof Number p95
                                    ? Fmt.signedFixed1(p95.doubleValue()) + " ms" : "missing") + " |");
        }
        report.add("");
        report.add("P95 latency samples:");
        for (String role : List.of("improvement", "regression", "holdout")) {
            Map<String, Object> comparison = asMap(comparisons.get(role));
            report.add("- `" + role + "`: " + latencySamples(asMap(comparison.get("baseline")))
                    + " → " + latencySamples(asMap(comparison.get("candidate"))));
        }
    }

    private static void appendObjectives(
            List<String> report, Map<String, Object> comparisons, List<Object> objectives) {
        if (objectives.isEmpty()) {
            return;
        }
        report.add("");
        report.add("## Configured objectives");
        report.add("");
        report.add("| Dataset | Objective | Baseline | Candidate | Ceiling "
                + "| Baseline samples | Candidate samples |");
        report.add("|---|---|---:|---:|---:|---:|---:|");
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
                                + render(candidateObjectives.get(name)) + " | "
                                + renderOptional(objective.get("maximum")) + " | "
                                + sampleCoverage(asMap(comparison.get("baseline")), name) + " | "
                                + sampleCoverage(asMap(comparison.get("candidate")), name) + " |");
            }
        }
    }

    private static void appendScenarioGates(
            List<String> report, Map<String, Object> comparisons, List<Object> scenarioGates) {
        if (scenarioGates.isEmpty()) {
            return;
        }
        report.add("");
        report.add("## Configured scenario gates");
        report.add("");
        report.add("| Dataset | Scenario | Cases | Baseline hard pass | Candidate hard pass "
                + "| Delta | Soft warning delta |");
        report.add("|---|---|---:|---:|---:|---:|---:|");
        for (Object rawGate : scenarioGates) {
            Map<String, Object> gate = asMap(rawGate);
            String scenarioName = String.valueOf(gate.get("scenario"));
            for (Object rawRole : asList(gate.get("roles"))) {
                String role = String.valueOf(rawRole);
                Map<String, Object> scenarios =
                        asMap(asMap(comparisons.get(role)).get("scenarios"));
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
                        "| " + role + " | " + scenarioName + " | " + candidateValue.get("case_count")
                                + " | " + Fmt.percent1(rate(baseline)) + " | "
                                + Fmt.percent1(rate(candidateValue)) + " | "
                                + Fmt.signedPercent1(
                                        doubleValue(scenario.get("hard_pass_rate_delta"))) + " | "
                                + Fmt.signedInt(intValue(scenario.get("soft_warning_delta"))) + " |");
            }
        }
    }

    private static void appendQualityGates(
            List<String> report, List<Object> qualityGates) {
        if (qualityGates.isEmpty()) {
            return;
        }
        report.add("");
        report.add("## Configured quality gates");
        report.add("");
        report.add("| Dataset | Min hard pass | Max regressions | Max critical regressions | Min samples |");
        report.add("|---|---:|---:|---:|---:|");
        for (Object rawGate : qualityGates) {
            Map<String, Object> gate = asMap(rawGate);
            for (Object rawRole : asList(gate.get("roles"))) {
                report.add("| " + cell(rawRole) + " | "
                        + renderPercent(gate.get("minimum_hard_pass_rate")) + " | "
                        + renderOptional(gate.get("maximum_task_regressions")) + " | "
                        + renderOptional(gate.get("maximum_critical_task_regressions")) + " | "
                        + renderOptional(gate.get("minimum_samples")) + " |");
            }
        }
    }

    private static void appendMetricDeltas(
            List<String> report, Map<String, Object> comparisons) {
        boolean anyMetric = false;
        for (String role : List.of("improvement", "regression", "holdout")) {
            if (!asMap(asMap(comparisons.get(role)).get("metric_deltas")).isEmpty()) {
                anyMetric = true;
                break;
            }
        }
        if (!anyMetric) {
            return;
        }
        report.add("");
        report.add("## Metric scores");
        report.add("");
        report.add("| Dataset | Metric | Baseline pass | Candidate pass | Pass delta "
                + "| Baseline mean | Candidate mean | Mean delta |");
        report.add("|---|---|---:|---:|---:|---:|---:|---:|");
        for (String role : List.of("improvement", "regression", "holdout")) {
            Map<String, Object> deltas = asMap(asMap(comparisons.get(role)).get("metric_deltas"));
            for (Map.Entry<String, Object> entry : deltas.entrySet()) {
                Map<String, Object> row = asMap(entry.getValue());
                Map<String, Object> baseline = asMap(row.get("baseline"));
                Map<String, Object> candidate = asMap(row.get("candidate"));
                report.add("| " + role + " | `" + entry.getKey() + "` | "
                        + rateText(baseline.get("pass_rate")) + " | " + rateText(candidate.get("pass_rate")) + " | "
                        + deltaText(row.get("pass_rate_delta")) + " | " + scoreText(baseline.get("mean_score"))
                        + " | " + scoreText(candidate.get("mean_score")) + " | "
                        + deltaText(row.get("mean_score_delta")) + " |");
            }
        }
    }

    private static void appendRuleDeltas(
            List<String> report, Map<String, Object> comparisons) {
        report.add("");
        report.add("## Rule pass rates");
        report.add("");
        boolean anyRule = false;
        for (String role : List.of("improvement", "regression", "holdout")) {
            if (!asMap(asMap(comparisons.get(role)).get("rule_deltas")).isEmpty()) {
                anyRule = true;
                break;
            }
        }
        if (!anyRule) {
            report.add("- None");
            return;
        }
        report.add("| Dataset | Rule | Level | Baseline | Candidate | Delta "
                + "| Baseline samples | Candidate samples |");
        report.add("|---|---|---|---:|---:|---:|---:|---:|");
        for (String role : List.of("improvement", "regression", "holdout")) {
            Map<String, Object> deltas = asMap(asMap(comparisons.get(role)).get("rule_deltas"));
            for (Map.Entry<String, Object> entry : deltas.entrySet()) {
                appendRuleDelta(report, role, entry);
            }
        }
    }

    private static void appendRuleDelta(
            List<String> report, String role, Map.Entry<String, Object> entry) {
        Map<String, Object> row = asMap(entry.getValue());
        Map<String, Object> baselineStats = asMap(row.get("baseline"));
        Map<String, Object> candidateStats = asMap(row.get("candidate"));
        Map<String, Object> stats = candidateStats.isEmpty() ? baselineStats : candidateStats;
        report.add("| " + role + " | `" + cell(entry.getKey()) + "` | "
                + cell(stats.getOrDefault("level", "unknown")) + " | "
                + passRate(baselineStats) + " | " + passRate(candidateStats) + " | "
                + (row.get("pass_rate_delta") instanceof Number delta
                        ? Fmt.signedPercent1(delta.doubleValue()) : "missing") + " | "
                + samples(baselineStats) + " | " + samples(candidateStats) + " |");
    }

    private static void appendDecisionAndLineage(
            List<String> report, Map<String, Object> result) {
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
    }

    /** Adds the per-case transition counts, every regression's first divergence, and failure clusters. */
    private static void appendCaseTransitions(List<String> report, Map<String, Object> result) {
        Map<String, Object> comparisons = asMap(result.get("comparisons"));
        List<String> roles = List.of("improvement", "regression", "holdout");
        List<Map<String, Object>> regressions = new ArrayList<>();

        report.add("");
        report.add("## Case transitions");
        report.add("");
        report.add("| Dataset | Cases | Regressed | Recovered | Persistent failures | Stable passes |");
        report.add("|---|---:|---:|---:|---:|---:|");
        for (String role : roles) {
            Map<String, Object> transitions = asMap(asMap(comparisons.get(role)).get("case_transitions"));
            report.add("| " + role + " | " + intValue(transitions.get("case_count")) + " | "
                    + intValue(transitions.get("regressed_count")) + " | "
                    + intValue(transitions.get("recovered_count")) + " | "
                    + intValue(transitions.get("persistent_failure_count")) + " | "
                    + intValue(transitions.get("stable_pass_count")) + " |");
            for (Object rawRegression : asList(transitions.get("regressed"))) {
                Map<String, Object> row = new LinkedHashMap<>(asMap(rawRegression));
                row.put("_role", role);
                regressions.add(row);
            }
        }

        report.add("");
        report.add("## Regressions and first divergence");
        report.add("");
        if (regressions.isEmpty()) {
            report.add("- None");
            return;
        }
        report.add("| Dataset | Case | Scenario | Critical | Step | Category | Failed checks | First divergence |");
        report.add("|---|---|---|---:|---:|---|---|---|");

        Map<String, Cluster> clusters = new LinkedHashMap<>();
        for (Map<String, Object> row : regressions) {
            Map<String, Object> first = asMap(row.get("first_divergence"));
            String role = text(row.get("_role"));
            String category = text(first.get("category"));
            Map<String, Object> event = asMap(first.get("candidate"));
            if (event.isEmpty()) {
                event = asMap(first.get("baseline"));
            }
            String module = text(event.get("module"));
            String action = text(event.get("action"));
            String key = role + '\u0000' + category + '\u0000' + module + '\u0000' + action;
            Cluster cluster = clusters.computeIfAbsent(
                    key, ignored -> new Cluster(role, category, module, action));
            cluster.count++;
            if (cluster.cases.size() < 5) {
                cluster.cases.add(text(row.get("case_id")));
            }

            report.add("| " + role + " | `" + cell(row.get("case_id")) + "` | " + cell(row.get("scenario"))
                    + " | " + (Boolean.TRUE.equals(row.get("critical")) ? "yes" : "-")
                    + " | " + (first.get("step") == null ? DASH : cell(first.get("step")))
                    + " | " + cell(category)
                    + " | " + cell(checkNames(row.get("failed_checks")))
                    + " | " + cell(first.get("reason")) + " |");
        }

        report.add("");
        report.add("## Failure clusters");
        report.add("");
        report.add("| Dataset | Category | Module / Action | Regressions | Cases |");
        report.add("|---|---|---|---:|---|");
        List<Cluster> sorted = new ArrayList<>(clusters.values());
        sorted.sort((left, right) -> {
            int byCount = Integer.compare(right.count, left.count);
            if (byCount != 0) {
                return byCount;
            }
            int byRole = left.role.compareTo(right.role);
            if (byRole != 0) {
                return byRole;
            }
            int byCategory = left.category.compareTo(right.category);
            if (byCategory != 0) {
                return byCategory;
            }
            int byModule = left.module.compareTo(right.module);
            return byModule != 0 ? byModule : left.action.compareTo(right.action);
        });
        for (Cluster cluster : sorted) {
            String step = cluster.module.isEmpty() && cluster.action.isEmpty()
                    ? DASH
                    : (cluster.module + "/" + cluster.action).replaceAll("^/|/$", "");
            report.add("| " + cell(cluster.role) + " | " + cell(cluster.category) + " | " + cell(step)
                    + " | " + cluster.count + " | " + cell(String.join(", ", cluster.cases))
                    + (cluster.count > cluster.cases.size() ? ", …" : "") + " |");
        }
    }

    /** The failed check names, with the level only when it is not the default hard gate. */
    private static String checkNames(Object value) {
        List<String> names = new ArrayList<>();
        for (Object rawCheck : asList(value)) {
            Map<String, Object> check = asMap(rawCheck);
            String level = text(check.get("level"));
            names.add(("hard".equals(level) || level.isEmpty() ? "" : level + ":")
                    + text(check.get("name")));
        }
        return names.isEmpty() ? DASH : String.join(", ", names);
    }

    /** One group of regressions that diverged in the same category at the same kind of step. */
    private static final class Cluster {
        private final String role;
        private final String category;
        private final String module;
        private final String action;
        private final List<String> cases = new ArrayList<>();
        private int count;

        private Cluster(String role, String category, String module, String action) {
            this.role = role;
            this.category = category;
            this.module = module;
            this.action = action;
        }
    }
}
