package io.assay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exit codes are the part of the CLI other tools depend on, so each one is asserted directly rather
 * than inferred from the artifacts.
 */
class AssayCliTest {

    private static Path writeCases(Path directory, String name, List<Map<String, Object>> rows) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> row : rows) {
            builder.append(Json.write(row)).append('\n');
        }
        Path path = directory.resolve(name);
        AtomicFiles.writeText(path, builder.toString());
        return path;
    }

    private static Map<String, Object> echoCase(String caseId, String message, String route, String keyword) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", caseId);
        row.put("scenario", "direct");
        row.put("input", Map.of("message", message));
        row.put("expected", Map.of("route", route, "keyword", keyword));
        return row;
    }

    @Test
    void runWritesArtifactsAndReturnsZeroWhenGatesPass(@TempDir Path root) {
        Path cases = writeCases(root, "smoke.jsonl", List.of(echoCase("C1", "hello agent", "ECHO", "hello")));
        Path store = root.resolve("store");
        Path output = root.resolve("runs");

        int code =
                AssayCli.run(
                        new String[] {
                            "run",
                            "--adapter", "io.assay.examples.EchoAdapter",
                            "--suite", "smoke",
                            "--cases", cases.toString(),
                            "--store", store.toString(),
                            "--output", output.toString(),
                            "--collect-few-shot"
                        });

        assertEquals(0, code);
        Path runDir = output.resolve(readOnlyRunId(output));
        assertTrue(Files.isRegularFile(runDir.resolve("results.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("report.md")));
        assertTrue(Files.isRegularFile(runDir.resolve("scenario_stats.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("review_queue.jsonl")));
        assertTrue(Files.isRegularFile(runDir.resolve("few_shot_candidates.jsonl")));
        assertTrue(AtomicFiles.readText(runDir.resolve("report.md")).contains("Agent Evaluation Report"));
        assertTrue(AtomicFiles.readText(runDir.resolve("few_shot_candidates.jsonl")).contains("C1"));
    }

    @Test
    void runReturnsOneWhenAHardGateFails(@TempDir Path root) {
        Path cases = writeCases(root, "smoke.jsonl", List.of(echoCase("C1", "hello", "SUPPORT", "hello")));

        int code =
                AssayCli.run(
                        new String[] {
                            "run",
                            "--adapter", "io.assay.examples.EchoAdapter",
                            "--suite", "smoke",
                            "--cases", cases.toString(),
                            "--store", root.resolve("store").toString(),
                            "--output", root.resolve("runs").toString()
                        });

        assertEquals(1, code);
    }

    /** A run that needs a person must not look like a pass. */
    @Test
    void reviewExportAndPromoteReviewCoverTheHumanLoop(@TempDir Path root) {
        Path cases = writeCases(root, "smoke.jsonl", List.of(echoCase("C1", "hello", "SUPPORT", "hello")));
        Path store = root.resolve("store");
        AssayCli.run(
                new String[] {
                    "run",
                    "--adapter", "io.assay.examples.EchoAdapter",
                    "--suite", "smoke",
                    "--cases", cases.toString(),
                    "--store", store.toString(),
                    "--output", root.resolve("runs").toString(),
                    "--collect-few-shot"
                });
        String runId = readOnlyRunId(root.resolve("runs"));

        assertEquals(
                0,
                AssayCli.run(
                        new String[] {
                            "review",
                            "--store", store.toString(),
                            "--run-id", runId,
                            "--case-id", "C1",
                            "--decision", "confirmed_badcase",
                            "--conclusion", "route mismatch"
                        }));

        Path exported = root.resolve("regression.candidates.jsonl");
        assertEquals(
                0,
                AssayCli.run(
                        new String[] {
                            "export",
                            "--store", store.toString(),
                            "--kind", "regression",
                            "--output", exported.toString()
                        }));
        String line = AtomicFiles.readText(exported);
        assertTrue(line.contains("\"id\": \"C1\""), line);
        assertTrue(line.contains("route mismatch"), line);

        // The promoted ledger is separate from the evaluation store.
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("id", "REVIEW-1");
        sample.put("input", Map.of());
        sample.put("metadata", Map.of());
        Path samplePath = root.resolve("review-sample.json");
        AtomicFiles.writeText(samplePath, Json.write(sample));
        Path promoted = root.resolve("promoted.jsonl");

        assertEquals(
                0,
                AssayCli.run(
                        new String[] {
                            "promote-review",
                            "--input", samplePath.toString(),
                            "--output", promoted.toString(),
                            "--outcome", "UNRESOLVED",
                            "--role", "pending",
                            "--conclusion", "business owner cannot decide yet",
                            "--reviewer", "owner-a"
                        }));
        assertTrue(AtomicFiles.readText(promoted).contains("\"review_status\": \"unresolved\""));
    }

    @Test
    void usageErrorsExitWithTwo(@TempDir Path root) {
        assertEquals(2, AssayCli.run(new String[] {}));
        assertEquals(2, AssayCli.run(new String[] {"no-such-command"}));
        assertEquals(2, AssayCli.run(new String[] {"run", "--suite", "smoke"}));
        assertEquals(
                2,
                AssayCli.run(
                        new String[] {
                            "run",
                            "--adapter", "io.assay.examples.EchoAdapter",
                            "--suite", "smoke",
                            "--cases", root.resolve("absent.jsonl").toString(),
                            "--source", "somewhere-else"
                        }));
        assertTrue(AssayCli.run(new String[] {"--help"}) == 0);
    }

    @Test
    void unknownAdapterClassFailsWithAMessage(@TempDir Path root) {
        Path cases = writeCases(root, "smoke.jsonl", List.of(echoCase("C1", "hello", "ECHO", "hello")));

        int code =
                AssayCli.run(
                        new String[] {
                            "run",
                            "--adapter", "com.example.NotOnTheClasspath",
                            "--suite", "smoke",
                            "--cases", cases.toString(),
                            "--store", root.resolve("store").toString(),
                            "--output", root.resolve("runs").toString()
                        });

        assertEquals(1, code);
    }

    private static String readOnlyRunId(Path runs) {
        try (java.util.stream.Stream<Path> entries = Files.list(runs)) {
            return entries.findFirst().orElseThrow().getFileName().toString();
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
