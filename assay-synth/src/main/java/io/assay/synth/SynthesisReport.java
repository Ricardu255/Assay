package io.assay.synth;

import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome of one synthesis run, including what was discarded and what it cost. */
public record SynthesisReport(
        List<SyntheticCase> cases,
        int duplicates,
        int discarded,
        int llmCalls,
        long inputTokens,
        long outputTokens,
        boolean budgetExhausted) {

    public SynthesisReport {
        cases = List.copyOf(cases);
    }

    /** Machine-readable summary printed by the CLI. */
    public Map<String, Object> toJson() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cases", cases.size());
        summary.put("duplicates", duplicates);
        summary.put("discarded", discarded);
        summary.put("llm_calls", llmCalls);
        summary.put("input_tokens", inputTokens);
        summary.put("output_tokens", outputTokens);
        summary.put("budget_exhausted", budgetExhausted);
        return summary;
    }

    /** Writes the accepted cases as JSONL; returns the number of lines written. */
    public int write(Path output) {
        List<String> lines = new ArrayList<>(cases.size());
        for (SyntheticCase item : cases) {
            lines.add(item.toJsonLine());
        }
        AtomicFiles.writeText(output, lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
        return lines.size();
    }
}
