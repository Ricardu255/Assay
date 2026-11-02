package io.assay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.store.jdbc.JdbcJobStore;
import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    void quickstartGeneratesConfigAndCases(@TempDir Path root) throws Exception {
        Path dir = root.resolve("quickstart");
        int code = AssayCli.run(new String[] {
            "quickstart",
            "--endpoint", "http://127.0.0.1:9/agent",
            "--output-path", "data.answer",
            "--keyword", "refund",
            "--generate-only",
            "--dir", dir.toString()
        });

        assertEquals(0, code);
        Map<String, Object> config = Json.parseObject(AtomicFiles.readText(dir.resolve("my-agent.http.json")));
        assertEquals("http", config.get("type"));
        assertEquals("data.answer", config.get("output_path"));
        assertEquals(1, ((List<?>) config.get("hard_gates")).size());
        List<String> lines = Files.readAllLines(dir.resolve("cases.jsonl"));
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("refund"), lines.get(0));
    }

    @Test
    void workerClaimsAQueuedJobAndCompletesIt(@TempDir Path root) {
        Path cases = writeCases(root, "queue.jsonl", List.of(echoCase("Q1", "hello", "ECHO", "hello")));
        String storeUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (JdbcJobStore jobs = JdbcJobStore.fromUrl(storeUrl)) {
            int submitted = AssayCli.run(new String[] {
                "submit",
                "--adapter", "io.assay.examples.EchoAdapter",
                "--suite", "queue",
                "--cases", cases.toString(),
                "--store", storeUrl,
                "--output", root.resolve("runs").toString(),
                "--max-attempts", "2"
            });
            assertEquals(0, submitted);
            Job queued = jobs.list(1).get(0);
            assertEquals(JobStatus.QUEUED, queued.status());

            int worked = AssayCli.run(new String[] {
                "worker", "--store", storeUrl, "--once", "--lease-seconds", "10"
            });
            assertEquals(0, worked);

            Job finished = jobs.find(queued.id()).orElseThrow();
            assertEquals(JobStatus.SUCCEEDED, finished.status());
            assertTrue(Files.isRegularFile(root.resolve("runs").resolve(finished.payload().get("run_id").toString())
                    .resolve("results.json")));
        }
    }

    @Test
    void submitIsIdempotentWithTheSameKey(@TempDir Path root) {
        Path cases = writeCases(root, "queue.jsonl", List.of(echoCase("Q1", "hello", "ECHO", "hello")));
        String storeUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        String[] args = {
            "submit",
            "--adapter", "io.assay.examples.EchoAdapter",
            "--suite", "queue",
            "--cases", cases.toString(),
            "--store", storeUrl,
            "--output", root.resolve("runs").toString(),
            "--idempotency-key", "checkout-42"
        };
        assertEquals(0, AssayCli.run(args));
        assertEquals(0, AssayCli.run(args));

        try (JdbcJobStore jobs = JdbcJobStore.fromUrl(storeUrl)) {
            assertEquals(1, jobs.list(10).size());
            assertEquals("checkout-42", jobs.list(1).get(0).idempotencyKey());
        }
    }

    @Test
    void replayMovesAFailedJobBackToTheQueue(@TempDir Path root) {
        String storeUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        String jobId;
        try (JdbcJobStore jobs = JdbcJobStore.fromUrl(storeUrl)) {
            Job job = jobs.submit(Map.of("suite", "queue"), 1);
            jobId = job.id();
            assertTrue(jobs.claim("worker-a", java.time.Duration.ofSeconds(30)).isPresent());
            jobs.fail(jobId, "worker-a", "adapter timeout");
            assertEquals(JobStatus.FAILED, jobs.find(jobId).orElseThrow().status());
        }

        assertEquals(0, AssayCli.run(new String[] {
            "replay", "--store", storeUrl, "--job-id", jobId, "--max-attempts", "1", "--max-replays", "2"
        }));

        try (JdbcJobStore jobs = JdbcJobStore.fromUrl(storeUrl)) {
            Job replayed = jobs.find(jobId).orElseThrow();
            assertEquals(JobStatus.QUEUED, replayed.status());
            assertEquals(1, replayed.replayCount());
        }
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
        assertTrue(AtomicFiles.readText(runDir.resolve("report.md")).contains("Rule pass rates"));
        assertTrue(Json.parseObject(AtomicFiles.readText(runDir.resolve("results.json"))).containsKey("rule_stats"));
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
    void runCanUseAJDBCStore(@TempDir Path root) {
        Path cases = writeCases(root, "smoke.jsonl", List.of(echoCase("C1", "hello agent", "ECHO", "hello")));
        Path output = root.resolve("runs");
        String store = "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

        int code =
                AssayCli.run(
                        new String[] {
                            "run",
                            "--adapter", "io.assay.examples.EchoAdapter",
                            "--suite", "smoke",
                            "--cases", cases.toString(),
                            "--store", store,
                            "--output", output.toString()
                        });

        assertEquals(0, code);
        Path runDir = output.resolve(readOnlyRunId(output));
        assertTrue(Files.isRegularFile(runDir.resolve("results.json")));
        assertTrue(AtomicFiles.readText(runDir.resolve("report.md")).contains("Agent Evaluation Report"));
    }

    @Test
    void submitRejectsAnIdempotencyKeyWithADifferentPayload(@TempDir Path root) {
        Path firstCases = writeCases(root, "first.jsonl", List.of(echoCase("Q1", "hello", "ECHO", "hello")));
        Path secondCases = writeCases(root, "second.jsonl", List.of(echoCase("Q2", "bye", "ECHO", "bye")));
        String storeUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        String[] first = {
            "submit",
            "--adapter", "io.assay.examples.EchoAdapter",
            "--suite", "queue",
            "--cases", firstCases.toString(),
            "--store", storeUrl,
            "--output", root.resolve("runs").toString(),
            "--idempotency-key", "checkout-42"
        };
        String[] second = {
            "submit",
            "--adapter", "io.assay.examples.EchoAdapter",
            "--suite", "queue",
            "--cases", secondCases.toString(),
            "--store", storeUrl,
            "--output", root.resolve("runs").toString(),
            "--idempotency-key", "checkout-42"
        };

        assertEquals(0, AssayCli.run(first));
        assertEquals(1, AssayCli.run(second));

        try (JdbcJobStore jobs = JdbcJobStore.fromUrl(storeUrl)) {
            assertEquals(1, jobs.list(10).size());
        }
    }

    @Test
    void queuedJobCarriesItsBudgetAndStopsWhenExhausted(@TempDir Path root) {
        Path cases = writeCases(
                root,
                "budget-queue.jsonl",
                List.of(echoCase("C1", "x", "ECHO", "x"), echoCase("C2", "x", "ECHO", "x")));
        String storeUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (JdbcJobStore jobs = JdbcJobStore.fromUrl(storeUrl)) {
            assertEquals(0, AssayCli.run(new String[] {
                "submit",
                "--adapter", "io.assay.examples.EchoAdapter",
                "--suite", "budget-queue",
                "--cases", cases.toString(),
                "--store", storeUrl,
                "--output", root.resolve("runs").toString(),
                "--max-total-tokens", "6",
                "--reserve-output-tokens", "0"
            }));
            Job queued = jobs.list(1).get(0);
            assertEquals(6L, queued.payload().get("max_total_tokens"));
            assertEquals(0L, queued.payload().get("reserve_output_tokens"));

            assertEquals(0, AssayCli.run(new String[] {
                "worker", "--store", storeUrl, "--once", "--lease-seconds", "10"
            }));

            Job finished = jobs.find(queued.id()).orElseThrow();
            assertEquals(JobStatus.FAILED, finished.status());
            Map<String, Object> results = Json.parseObject(AtomicFiles.readText(
                    root.resolve("runs").resolve(queued.payload().get("run_id").toString()).resolve("results.json")));
            assertEquals("budget_exhausted", results.get("status"));
            assertEquals(1L, results.get("skipped_case_count"));
        }
    }

    @Test
    void runStopsAtTheTokenBudgetAndExitsOne(@TempDir Path root) {
        Path cases = writeCases(
                root,
                "budget.jsonl",
                List.of(echoCase("C1", "x", "ECHO", "x"), echoCase("C2", "x", "ECHO", "x"),
                        echoCase("C3", "x", "ECHO", "x")));
        Path output = root.resolve("runs");

        int code = AssayCli.run(new String[] {
            "run",
            "--adapter", "io.assay.examples.EchoAdapter",
            "--suite", "budget",
            "--cases", cases.toString(),
            "--store", root.resolve("store").toString(),
            "--output", output.toString(),
            "--max-total-tokens", "6",
            "--reserve-output-tokens", "0"
        });

        assertEquals(1, code);
        Path runDir = output.resolve(readOnlyRunId(output));
        Map<String, Object> results = Json.parseObject(AtomicFiles.readText(runDir.resolve("results.json")));
        assertEquals("budget_exhausted", results.get("status"));
        assertEquals(1L, results.get("case_count"));
        assertEquals(2L, results.get("skipped_case_count"));
        Map<String, Object> budget = asMap(results.get("budget"));
        assertEquals(4L, budget.get("spent_tokens"));
        assertTrue(AtomicFiles.readText(runDir.resolve("report.md")).contains("Budget tokens"));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String readOnlyRunId(Path runs) {
        try (java.util.stream.Stream<Path> entries = Files.list(runs)) {
            return entries.findFirst().orElseThrow().getFileName().toString();
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
