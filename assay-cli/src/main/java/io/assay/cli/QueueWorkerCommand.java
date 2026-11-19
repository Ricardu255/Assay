package io.assay.cli;

import io.assay.json.Json;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Claims and executes durable evaluation jobs, including heartbeat and cancel handling. */
final class QueueWorkerCommand {

    private QueueWorkerCommand() {
    }

    static int worker(CommandLine.Namespace args) {
        int leaseSeconds = Math.max(1, args.integer("lease-seconds"));
        long pollMillis = Math.max(250, Math.round(Math.max(0.25, args.decimal("poll")) * 1000));
        String workerId = args.string("worker-id");
        if (workerId == null || workerId.isBlank()) {
            workerId = "worker-" + CliIds.shortId();
        }
        try (JobStore store = QueueSupport.jobStore(args)) {
            return runWorker(store, workerId, leaseSeconds, pollMillis, args.flag("once"));
        }
    }

    static int runWorker(
            JobStore store, String workerId, int leaseSeconds, long pollMillis, boolean once) {
        return runWorker(store, workerId, leaseSeconds, pollMillis, once, () -> true);
    }

    static int runWorker(
            JobStore store,
            String workerId,
            int leaseSeconds,
            long pollMillis,
            boolean once,
            BooleanSupplier running) {
        while (running.getAsBoolean()) {
            Optional<Job> claimed;
            try {
                claimed = store.claim(workerId, Duration.ofSeconds(leaseSeconds));
            } catch (RuntimeException error) {
                System.err.println("worker: cannot claim job: " + QueueSupport.message(error));
                if (once) {
                    return 1;
                }
                if (!QueueSupport.pause(pollMillis)) {
                    return running.getAsBoolean() ? 1 : 0;
                }
                continue;
            }
            if (claimed.isEmpty()) {
                if (once) {
                    return 0;
                }
                if (!QueueSupport.pause(pollMillis)) {
                    return running.getAsBoolean() ? 1 : 0;
                }
                continue;
            }
            try {
                executeJob(store, claimed.get(), workerId, leaseSeconds);
            } catch (RuntimeException error) {
                System.err.println(
                        "worker: job " + claimed.get().id() + " failed: "
                                + QueueSupport.message(error));
                if (once) {
                    return 1;
                }
            }
            if (once) {
                return 0;
            }
        }
        return 0;
    }

    private static void executeJob(JobStore store, Job job, String workerId, int leaseSeconds) {
        Thread execution = Thread.currentThread();
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "assay-worker-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(1, leaseSeconds / 3);
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!store.heartbeat(job.id(), workerId, Duration.ofSeconds(leaseSeconds))) {
                    leaseLost.set(true);
                    execution.interrupt();
                    return;
                }
                if (store.isCancelRequested(job.id())) {
                    cancelRequested.set(true);
                    execution.interrupt();
                }
            } catch (RuntimeException error) {
                // A transient store failure must not cancel the job; the next tick retries.
            }
        }, interval, interval, TimeUnit.SECONDS);
        int code = 1;
        RuntimeException executionFailure = null;
        try {
            code = AssayCli.run(runArgs(job));
        } catch (RuntimeException error) {
            executionFailure = error;
        } finally {
            heartbeat.cancel(false);
            scheduler.shutdownNow();
            Thread.interrupted();
        }
        if (leaseLost.get()) {
            System.err.println(
                    "worker: job " + job.id() + " lease was lost; the new owner writes the terminal state");
            return;
        }
        try {
            if (cancelRequested.get() || store.isCancelRequested(job.id())) {
                store.finish(job.id(), workerId, JobStatus.CANCELLED, "cancelled by user");
            } else if (executionFailure != null) {
                store.fail(job.id(), workerId, QueueSupport.message(executionFailure));
            } else if (code == 0) {
                store.succeed(job.id(), workerId);
            } else {
                store.finish(job.id(), workerId, JobStatus.FAILED, failureReason(job, code));
            }
        } catch (RuntimeException terminalFailure) {
            System.err.println(
                    "worker: job " + job.id() + " terminal state write failed: "
                            + QueueSupport.message(terminalFailure));
            return;
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
                "--adapter", QueueSupport.text(payload, "adapter"),
                "--suite", QueueSupport.text(payload, "suite"),
                "--cases", QueueSupport.text(payload, "cases"),
                "--store", QueueSupport.text(payload, "store"),
                "--output", QueueSupport.text(payload, "output"),
                "--source", QueueSupport.text(payload, "source"),
                "--workers", String.valueOf(QueueSupport.integer(payload, "workers")),
                "--retries", String.valueOf(QueueSupport.integer(payload, "retries")),
                "--timeout", String.valueOf(QueueSupport.decimal(payload, "timeout")),
                "--run-id", QueueSupport.text(payload, "run_id")));
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
        QueueSupport.addOption(args, payload, "max_total_tokens", "--max-total-tokens");
        QueueSupport.addOption(args, payload, "max_cost_usd", "--max-cost-usd");
        QueueSupport.addOption(args, payload, "reserve_output_tokens", "--reserve-output-tokens");
        QueueSupport.addOption(args, payload, "reserve_cost_usd", "--reserve-cost-usd");
        QueueSupport.addOption(args, payload, "retry_base_seconds", "--retry-base-seconds");
        QueueSupport.addOption(args, payload, "retry_max_seconds", "--retry-max-seconds");
        QueueSupport.addOption(args, payload, "retry_jitter", "--retry-jitter");
        QueueSupport.addOption(args, payload, "breaker_failures", "--breaker-failures");
        QueueSupport.addOption(args, payload, "breaker_reset_seconds", "--breaker-reset-seconds");
        QueueSupport.addOption(args, payload, "metric_preset", "--preset");
        QueueSupport.addOption(args, payload, "metrics_file", "--metrics");
        QueueSupport.addOption(args, payload, "metric_cache", "--metric-cache");
        QueueSupport.addOption(args, payload, "llm_base_url", "--base-url");
        QueueSupport.addOption(args, payload, "llm_model", "--model");
        if (job.attempts() > 1) {
            args.add("--resume");
        }
        return args.toArray(String[]::new);
    }
}
