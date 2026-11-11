package io.assay.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ReportingMultiAgentTest {

    @Test
    void aggregatesMultiAgentMetricsWhenAdaptersAttachThem(@TempDir Path outputDir) throws Exception {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("steps", 6);
        metrics.put("model_calls", 2);
        metrics.put("tool_calls", 1);
        metrics.put("handoffs", 1);
        metrics.put("denied_tools", 0);
        metrics.put("max_repeated_step", 1);
        metrics.put("agents", List.of("planner", "worker"));

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("target_type", "agent");
        trace.put("target_id", "demo");
        trace.put("metrics", metrics);

        Map<String, Object> evalCase = new LinkedHashMap<>();
        evalCase.put("case_id", "CASE-1");
        evalCase.put("scenario", "multi-agent");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", evalCase);
        result.put("hard_pass", true);
        result.put("soft_warning_count", 0);
        result.put("checks", List.of());
        result.put("trace", trace);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", "run-1");
        summary.put("suite", "smoke");
        summary.put("source", "test");
        summary.put("status", "passed");
        summary.put("case_count", 1);
        summary.put("hard_failures", 0);
        summary.put("soft_warnings", 0);
        summary.put("results", List.of(result));

        Reporting.writeRunArtifacts(summary, outputDir);

        String resultsJson = Files.readString(outputDir.resolve("results.json"));
        assertTrue(resultsJson.contains("\"multi_agent\""), resultsJson);
        assertTrue(resultsJson.contains("\"handoffs\": 1"), resultsJson);
        assertTrue(Json.parseObject(resultsJson).containsKey("multi_agent"));
        String reportMarkdown = Files.readString(outputDir.resolve("report.md"));
        assertTrue(reportMarkdown.contains("## Multi-agent metrics"), reportMarkdown);
        assertTrue(reportMarkdown.contains("handoffs: 1"), reportMarkdown);
    }
}
