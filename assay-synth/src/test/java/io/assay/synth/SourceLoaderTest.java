package io.assay.synth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.support.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class SourceLoaderTest {

    @Test
    void loadsMarkdownAndTextDocuments(@TempDir Path root) {
        AtomicFiles.writeText(root.resolve("policy.md"), "# Refund policy\n30 days with receipt.");
        AtomicFiles.writeText(root.resolve("notes.txt"), "Support is available 24/7.");

        List<SourceDocument> documents = SourceLoader.load("docs", root);

        assertEquals(2, documents.size());
        assertTrue(documents.stream().anyMatch(document -> document.name().equals("policy.md")));
    }

    @Test
    void loadsTracesAsRenderedEvidence(@TempDir Path root) {
        Path traces = root.resolve("traces.jsonl");
        NormalizedTrace trace = new NormalizedTrace(
                "trace-1",
                "refund issued",
                List.of(new TraceEvent("tool", "call_start", "ok", 0, null,
                        Map.of("tool_call_name", "lookup"))),
                Map.of());
        AtomicFiles.writeText(traces, Json.write(trace.toJson()) + "\n");

        List<SourceDocument> documents = SourceLoader.load("traces", traces);

        assertEquals(1, documents.size());
        assertTrue(documents.get(0).text().contains("FINAL OUTPUT:"));
        assertTrue(documents.get(0).text().contains("refund issued"));
    }

    @Test
    void rejectsMissingInputAndUnknownKinds(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class, () -> SourceLoader.load("docs", root.resolve("absent")));
        AtomicFiles.writeText(root.resolve("a.txt"), "text");
        assertThrows(IllegalArgumentException.class, () -> SourceLoader.load("videos", root));
    }
}
