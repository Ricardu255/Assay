package io.assay.report;

import java.nio.file.Path;
import java.util.Map;

/**
 * Turns a machine-readable run result into the human-readable artifacts.
 *
 * <p>The JSON is the source of truth; the Markdown report is derived from it, never the other way
 * round. Every report keeps the LLM's contribution labelled as a non-authoritative candidate.
 *
 * <p>This class is the stable public entry point. Per-artifact rendering lives in focused writers.
 */
public final class Reporting {

    private Reporting() {
    }

    /** Writes {@code results.json}, {@code report.md}, {@code scenario_stats.json}, and the queues. */
    public static void writeRunArtifacts(Map<String, Object> summary, Path outputDir) {
        RunReportWriter.writeRunArtifacts(summary, outputDir);
    }

    /** Writes {@code evolution.json} and {@code evolution_report.md}. */
    public static void writeEvolutionArtifacts(Map<String, Object> result, Path outputDir) {
        EvolutionReportWriter.writeEvolutionArtifacts(result, outputDir);
    }

    /** Writes {@code auto_evolution.json} and {@code auto_evolution_report.md}. */
    public static void writeAutoEvolutionArtifacts(Map<String, Object> result, Path outputDir) {
        AutoEvolutionReportWriter.writeAutoEvolutionArtifacts(result, outputDir);
    }
}
