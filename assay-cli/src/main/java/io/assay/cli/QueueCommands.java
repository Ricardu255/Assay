package io.assay.cli;

import io.assay.execution.Stores;
import io.assay.json.Json;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
import io.assay.store.Results;
import io.assay.support.AtomicFiles;
import io.assay.web.DashboardServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Implements the dashboard and durable-queue commands. */
final class QueueCommands {

    private QueueCommands() {
    }

    static int serve(CommandLine.Namespace args) {
        JobStore jobs = args.flag("worker") ? jobStore(args) : null;
        DashboardServer server = null;
        try {
            server = startDashboard(args, jobs);
            Thread shutdown = new Thread(server::close, "assay-dashboard-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                System.out.println("dashboard: " + server.uri());
                System.out.println("reports: " + server.output());
                if (jobs != null) {
                    int leaseSeconds = Math.max(1, args.integer("lease-seconds"));
                    long pollMillis =
                            Math.max(250, Math.round(Math.max(0.25, args.decimal("poll")) * 1000));
                    String workerId = args.string("worker-id");
                    if (workerId == null || workerId.isBlank()) {
                        workerId = "dashboard-worker-" + CliIds.shortId();
                    }
                    String finalWorkerId = workerId;
                    Thread workerThread = new Thread(
                            () -> runWorker(jobs, finalWorkerId, leaseSeconds, pollMillis, false),
                            "assay-dashboard-worker");
                    workerThread.setDaemon(true);
                    workerThread.start();
                    System.out.println(
                            "queue worker: " + workerId + " (store: " + args.string("store") + ")");
                } else {
                    System.out.println("Read-only local viewer. Use --worker to enable the job queue.");
                }
                System.out.println("Press Ctrl+C to stop.");
                if (args.flag("open-browser")) {
                    try {
                        if (!Desktop.isDesktopSupported()) throw new UnsupportedOperationException();
                        Desktop.getDesktop().browse(server.uri());
                    } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                        System.out.println("Open the dashboard URL above in your browser.");
                    }
                }
                server.await();
                return 0;
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException shuttingDown) {
                    // The hook owns shutdown when the JVM is already stopping.
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IOException error) {
            throw new IllegalStateException("cannot start dashboard: " + error.getMessage(), error);
        } finally {
            if (server != null) {
                server.close();
            }
            if (jobs != null) {
                jobs.close();
            }
        }
    }

    private static DashboardServer startDashboard(CommandLine.Namespace args, JobStore jobs)
            throws IOException {
        try {
            return new DashboardServer(
                    args.path("output"), args.integer("port"), jobs, args.string("store"));
        } catch (BindException busy) {
            if (!args.flag("open-browser") || args.integer("port") == 0) throw busy;
            System.out.println("Requested port is busy; selecting an available local port.");
            return new DashboardServer(args.path("output"), 0, jobs, args.string("store"));
        }
    }

    static int submit(CommandLine.Namespace args) {
        try (JobStore store = jobStore(args)) {
            String idempotencyKey = args.string("idempotency-key");
            if (idempotencyKey != null) {
                idempotencyKey = idempotencyKey.trim();
                if (idempotencyKey.isEmpty()) {
                    idempotencyKey = null;
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("adapter", args.string("adapter"));
            if (args.string("adapter-class") != null) {
                payload.put("adapter_class", args.string("adapter-class"));
            }
            payload.put("suite", args.string("suite"));
            payload.put("cases", args.string("cases"));
            payload.put("store", args.string("store"));
            payload.put("output", args.string("output"));
            payload.put("source", args.string("source"));
            payload.put("workers", args.integer("workers"));
            payload.put("retries", args.integer("retries"));
            payload.put("timeout", args.decimal("timeout"));
            if (args.integerOrNull("max-total-tokens") != null) {
                payload.put("max_total_tokens", args.integerOrNull("max-total-tokens"));
            }
            if (args.decimalOrNull("max-cost-usd") != null) {
                payload.put("max_cost_usd", args.decimalOrNull("max-cost-usd"));
            }
            payload.put("reserve_output_tokens", args.integer("reserve-output-tokens"));
            payload.put("reserve_cost_usd", args.decimal("reserve-cost-usd"));
            payload.put("retry_base_seconds", args.decimal("retry-base-seconds"));
            payload.put("retry_max_seconds", args.decimal("retry-max-seconds"));
            payload.put("retry_jitter", args.decimal("retry-jitter"));
            payload.put("breaker_failures", args.integer("breaker-failures"));
            payload.put("breaker_reset_seconds", args.decimal("breaker-reset-seconds"));
            payload.put("use_llm", args.flag("use-llm"));
            payload.put("collect_few_shot", args.flag("collect-few-shot"));
            if (args.string("run-identity") != null) {
                payload.put("run_identity", args.string("run-identity"));
            }
            String runId = args.string("run-id");
            if (runId == null || runId.isBlank()) {
                // A retried idempotent submit must produce the same payload; a random run id would
                // make the same request look like a conflict.
                runId = idempotencyKey == null
                        ? "queued-" + CliIds.shortId()
                        : "queued-" + CliIds.stableId(idempotencyKey);
            }
            payload.put("run_id", runId);
            Job job = store.submit(idempotencyKey, payload, args.integer("max-attempts"));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("job_id", job.id());
            output.put("run_id", job.payload().getOrDefault("run_id", runId));
            output.put("status", job.status().name());
            if (job.idempotencyKey() != null) {
                output.put("idempotency_key", job.idempotencyKey());
            }
            System.out.println(Json.write(output));
            return 0;
        }
    }

    static int jobs(CommandLine.Namespace args) {
        try (JobStore store = jobStore(args)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Job job : store.list(args.integer("limit"))) {
                rows.add(jobMap(job));
            }
            System.out.println(Json.write(rows));
            return 0;
        }
    }

    static int cancelJob(CommandLine.Namespace args) {
        try (JobStore store = jobStore(args)) {
            String jobId = args.string("job-id");
            boolean cancelled = store.cancel(jobId);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("job_id", jobId);
            output.put("cancelled", cancelled);
            store.find(jobId).ifPresent(job -> output.put("status", job.status().name()));
            System.out.println(Json.write(output));
            return cancelled ? 0 : 1;
        }
    }

    static int replayJob(CommandLine.Namespace args) {
        try (JobStore store = jobStore(args)) {
            String jobId = args.string("job-id");
            Optional<Job> replayed =
                    store.replay(jobId, args.integer("max-attempts"), args.integer("max-replays"));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("job_id", jobId);
            output.put("replayed", replayed.isPresent());
            replayed.ifPresent(job -> output.put("status", job.status().name()));
            replayed.ifPresent(job -> output.put("replay_count", job.replayCount()));
            System.out.println(Json.write(output));
            return replayed.isPresent() ? 0 : 1;
        }
    }

    static int reindexJobs(CommandLine.Namespace args) {
        try (JobStore store = jobStore(args)) {
            int indexed;
            try {
                indexed = store.rebuildRunIdIndex();
            } catch (UnsupportedOperationException unsupported) {
                throw new CommandLine.UsageException(
                        "this job store does not support run id index rebuilds", 2, false);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("store", args.string("store"));
            output.put("indexed", indexed);
            System.out.println(Json.write(output));
            return 0;
        }
    }

    static int prune(CommandLine.Namespace args) {
        int keepDays = args.integer("keep-days");
        int keepRuns = args.integer("keep-runs");
        int keepJobs = args.integer("keep-jobs");
        if (keepDays < 0 || keepRuns < 0 || keepJobs < 0) {
            throw new CommandLine.UsageException("prune limits must be non-negative", 2, false);
        }
        boolean dryRun = args.flag("dry-run");
        Instant cutoff = Instant.now().minus(Duration.ofDays(keepDays));
        List<String> runs;
        List<String> jobs;
        try (Results results = Stores.open(args.string("store"));
                JobStore jobsStore = jobStore(args)) {
            runs = results.pruneRuns(cutoff, keepRuns, dryRun);
            jobs = jobsStore.pruneJobs(cutoff, keepJobs, dryRun);
        }
        int artifacts = pruneOutputArtifacts(args.path("output"), cutoff, keepRuns, dryRun);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("cutoff", cutoff.toString());
        output.put("dry_run", dryRun);
        output.put("runs", runs);
        output.put("jobs", jobs);
        output.put("artifacts", artifacts);
        System.out.println(Json.write(output));
        return 0;
    }

    private static int pruneOutputArtifacts(
            Path output, Instant cutoff, int keepLatest, boolean dryRun) {
        if (output == null || !Files.isDirectory(output)) {
            return 0;
        }
        List<Path> directories;
        try (Stream<Path> stream = Files.list(output)) {
            directories = stream.filter(Files::isDirectory)
                    .filter(QueueCommands::isRunArtifactDirectory)
                    .sorted(Comparator.comparing(QueueCommands::lastModified).reversed())
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        int deleted = 0;
        for (int index = keepLatest; index < directories.size(); index++) {
            Path directory = directories.get(index);
            if (!lastModified(directory).isBefore(cutoff)) {
                continue;
            }
            deleted++;
            if (!dryRun) {
                AtomicFiles.deleteRecursively(directory);
            }
        }
        return deleted;
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** A report directory, not an arbitrary directory that happens to live under --output. */
    private static boolean isRunArtifactDirectory(Path directory) {
        return Files.isRegularFile(directory.resolve("results.json"))
                || Files.isRegularFile(directory.resolve("report.md"));
    }

    static int worker(CommandLine.Namespace args) {
        int leaseSeconds = Math.max(1, args.integer("lease-seconds"));
        long pollMillis = Math.max(250, Math.round(Math.max(0.25, args.decimal("poll")) * 1000));
        String workerId = args.string("worker-id");
        if (workerId == null || workerId.isBlank()) {
            workerId = "worker-" + CliIds.shortId();
        }
        try (JobStore store = jobStore(args)) {
            return runWorker(store, workerId, leaseSeconds, pollMillis, args.flag("once"));
        }
    }

    static int runWorker(
            JobStore store, String workerId, int leaseSeconds, long pollMillis, boolean once) {
        while (true) {
            Optional<Job> claimed;
            try {
                claimed = store.claim(workerId, Duration.ofSeconds(leaseSeconds));
            } catch (RuntimeException error) {
                System.err.println("worker: cannot claim job: " + message(error));
                if (once) {
                    return 1;
                }
                if (!pause(pollMillis)) {
                    return 1;
                }
                continue;
            }
            if (claimed.isEmpty()) {
                if (once) {
                    return 0;
                }
                if (!pause(pollMillis)) {
                    return 1;
                }
                continue;
            }
            try {
                executeJob(store, claimed.get(), workerId, leaseSeconds);
            } catch (RuntimeException error) {
                System.err.println(
                        "worker: job " + claimed.get().id() + " failed: " + message(error));
                if (once) {
                    return 1;
                }
            }
            if (once) {
                return 0;
            }
        }
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static void executeJob(JobStore store, Job job, String workerId, int leaseSeconds) {
        Thread execution = Thread.currentThread();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "assay-worker-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(1, leaseSeconds / 3);
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!store.heartbeat(job.id(), workerId, Duration.ofSeconds(leaseSeconds))
                        || store.isCancelRequested(job.id())) {
                    execution.interrupt();
                }
            } catch (RuntimeException error) {
                // A transient store failure must not cancel the job; the next tick retries.
            }
        }, interval, interval, TimeUnit.SECONDS);
        int code = 1;
        try {
            code = AssayCli.run(runArgs(job));
            if (store.isCancelRequested(job.id())) {
                store.finish(job.id(), workerId, JobStatus.CANCELLED, "cancelled by user");
            } else if (code == 0) {
                store.succeed(job.id(), workerId);
            } else {
                store.finish(job.id(), workerId, JobStatus.FAILED, failureReason(job, code));
            }
        } catch (RuntimeException error) {
            if (store.isCancelRequested(job.id())) {
                store.finish(job.id(), workerId, JobStatus.CANCELLED, "cancelled by user");
            } else {
                store.fail(
                        job.id(),
                        workerId,
                        error.getMessage() == null ? error.toString() : error.getMessage());
            }
        } finally {
            heartbeat.cancel(false);
            scheduler.shutdownNow();
            Thread.interrupted();
        }
        System.out.println("job " + job.id() + " finished with exit code " + code);
    }

    /** Reads the produced summary so a failed job says why, not only which exit code it returned. */
    private static String failureReason(Job job, int code) {
        Object output = job.payload().get("output");
        Object runId = job.payload().get("run_id");
        if (output == null || runId == null) {
            return "exit code " + code;
        }
        Path results = Path.of(String.valueOf(output))
                .resolve(String.valueOf(runId))
                .resolve("results.json");
        if (!Files.isRegularFile(results)) {
            return "exit code " + code;
        }
        try {
            Map<String, Object> summary = Json.parseObject(AtomicFiles.readText(results));
            String status = String.valueOf(summary.getOrDefault("status", "unknown"));
            if ("budget_exhausted".equals(status)) {
                return "run budget_exhausted: skipped "
                        + summary.getOrDefault("skipped_case_count", 0) + " cases (exit code " + code + ")";
            }
            if ("failed".equals(status)) {
                return "run failed: " + summary.getOrDefault("hard_failures", 0)
                        + " hard failures (exit code " + code + ")";
            }
            return "run status " + status + " (exit code " + code + ")";
        } catch (RuntimeException malformed) {
            return "exit code " + code;
        }
    }

    private static String[] runArgs(Job job) {
        Map<String, Object> payload = job.payload();
        List<String> args = new ArrayList<>(List.of(
                "run",
                "--adapter", text(payload, "adapter"),
                "--suite", text(payload, "suite"),
                "--cases", text(payload, "cases"),
                "--store", text(payload, "store"),
                "--output", text(payload, "output"),
                "--source", text(payload, "source"),
                "--workers", String.valueOf(integer(payload, "workers")),
                "--retries", String.valueOf(integer(payload, "retries")),
                "--timeout", String.valueOf(decimal(payload, "timeout")),
                "--run-id", text(payload, "run_id")));
        if (payload.get("adapter_class") != null) {
            args.add("--adapter-class");
            args.add(String.valueOf(payload.get("adapter_class")));
        }
        if (Boolean.TRUE.equals(payload.get("use_llm"))) {
            args.add("--use-llm");
        }
        if (Boolean.TRUE.equals(payload.get("collect_few_shot"))) {
            args.add("--collect-few-shot");
        }
        if (payload.get("run_identity") != null) {
            args.add("--run-identity");
            args.add(String.valueOf(payload.get("run_identity")));
        }
        addOption(args, payload, "max_total_tokens", "--max-total-tokens");
        addOption(args, payload, "max_cost_usd", "--max-cost-usd");
        addOption(args, payload, "reserve_output_tokens", "--reserve-output-tokens");
        addOption(args, payload, "reserve_cost_usd", "--reserve-cost-usd");
        addOption(args, payload, "retry_base_seconds", "--retry-base-seconds");
        addOption(args, payload, "retry_max_seconds", "--retry-max-seconds");
        addOption(args, payload, "retry_jitter", "--retry-jitter");
        addOption(args, payload, "breaker_failures", "--breaker-failures");
        addOption(args, payload, "breaker_reset_seconds", "--breaker-reset-seconds");
        if (job.attempts() > 1) {
            args.add("--resume");
        }
        return args.toArray(String[]::new);
    }

    private static void addOption(
            List<String> args, Map<String, Object> payload, String key, String option) {
        Object value = payload.get(key);
        if (value != null) {
            args.add(option);
            args.add(String.valueOf(value));
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("job payload is missing " + key);
        }
        return String.valueOf(value);
    }

    private static int integer(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number
                ? number.intValue()
                : Integer.parseInt(String.valueOf(value));
    }

    private static double decimal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number
                ? number.doubleValue()
                : Double.parseDouble(String.valueOf(value));
    }

    private static JobStore jobStore(CommandLine.Namespace args) {
        String reference = args.string("store");
        if (reference == null || reference.isBlank()) {
            throw new CommandLine.UsageException("queue commands require --store", 2, false);
        }
        return Stores.openJobs(reference, retryPolicy(args));
    }

    static RetryPolicy retryPolicy(CommandLine.Namespace args) {
        Double base = args.decimalOrNull("retry-base-seconds");
        Double max = args.decimalOrNull("retry-max-seconds");
        Double jitter = args.decimalOrNull("retry-jitter");
        if (base == null && max == null && jitter == null) {
            return RetryPolicy.defaults();
        }
        return new RetryPolicy(
                Duration.ofMillis(Math.round((base == null ? 1 : base) * 1000)),
                Duration.ofMillis(Math.round((max == null ? 16 : max) * 1000)),
                jitter == null ? 0.2 : jitter);
    }

    private static Map<String, Object> jobMap(Job job) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("job_id", job.id());
        row.put("status", job.status().name());
        row.put("attempts", job.attempts());
        row.put("max_attempts", job.maxAttempts());
        row.put("lease_owner", job.leaseOwner());
        row.put("lease_expires_at", job.leaseExpiresAt() == null ? null : job.leaseExpiresAt().toString());
        row.put("cancel_requested", job.cancelRequested());
        row.put("created_at", job.createdAt().toString());
        row.put("started_at", job.startedAt() == null ? null : job.startedAt().toString());
        row.put("finished_at", job.finishedAt() == null ? null : job.finishedAt().toString());
        row.put("error", job.error());
        row.put("idempotency_key", job.idempotencyKey());
        row.put("replay_count", job.replayCount());
        row.put("next_attempt_at", job.nextAttemptAt() == null ? null : job.nextAttemptAt().toString());
        return row;
    }
}
