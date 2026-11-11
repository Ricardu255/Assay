package io.assay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportingReviewQueueTest {

    @Test
    void fallsBackToTheSummaryRunIdWhenAResultOmitsIt(@TempDir Path outputDir) throws Exception {
        Map<String, Object> evalCase = new LinkedHashMap<>();
        evalCase.put("case_id", "CASE-1");
        evalCase.put("scenario", "direct");

        Map<String, Object> check = new LinkedHashMap<>();
        check.put("layer", "structure");
        check.put("name", "route");
        check.put("level", "hard");
        check.put("passed", false);
        check.put("message", "route mismatch");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", evalCase);
        result.put("hard_pass", false);
        result.put("soft_warning_count", 0);
        result.put("checks", List.of(check));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", "run-1");
        summary.put("suite", "smoke");
        summary.put("source", "test");
        summary.put("status", "failed");
        summary.put("case_count", 1);
        summary.put("hard_failures", 1);
        summary.put("soft_warnings", 0);
        summary.put("results", List.of(result));

        Reporting.writeRunArtifacts(summary, outputDir);

        List<String> lines = Files.readAllLines(outputDir.resolve("review_queue.jsonl"));
        assertEquals(1, lines.size(), lines.toString());
        Map<String, Object> row = io.assay.json.Json.parseObject(lines.get(0));
        assertEquals("run-1", row.get("run_id"));
        assertEquals("CASE-1", row.get("case_id"));
    }
}
