package io.assay.execution;

import io.assay.json.Json;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import io.assay.store.Results;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Durable, multi-instance evaluation queue.
 *
 * <p>Every server instance owns one queue object but they all share the same {@link JobStore}. A run
 * is submitted as a durable job, and any instance may claim it; the lease in the store guarantees a
 * job is executed by at most one worker at a time. Because the run itself takes the per-run store
 * lock in {@link io.assay.engine.EvaluationEngine}, a reclaimed job after a crashed instance resumes
 * the same run instead of overwriting it.
 */
public final class JobRunQueue implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(JobRunQueue.class.getName());

    private final JobStore jobs;
    private final Supplier<Results> stores;
    private final SuiteRunner runner;
    private final RunOptions options;
    private final Path outputDir;
    private final int workerThreads;
    private final int maxAttempts;
    private final Duration lease;
    private final long pollMillis;
    private final String workerId;
    private final ExecutorService workers;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JobRunQueue(
            JobStore jobs,
            Supplier<Results> stores,
            SuiteRunner runner,
            RunOptions options,
            Path outputDir,
            int workerThreads,
            Duration lease,
            long pollMillis,
            int maxAttempts) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.stores = Objects.requireNonNull(stores, "stores");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.options = Objects.requireNonNull(options, "options");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
        if (workerThreads < 0) {
            throw new IllegalArgumentException("worker_threads must be non-negative");
        }
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("job_lease_seconds must be positive");
        }
        if (pollMillis < 1) {
            throw new IllegalArgumentException("poll_millis must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("job_max_attempts must be positive");
        }
        this.workerThreads = workerThreads;
        this.maxAttempts = maxAttempts;
        this.lease = lease;
        this.pollMillis = pollMillis;
        this.workerId = "assay-server-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.workers = Executors.newFixedThreadPool(Math.max(1, workerThreads), threadFactory("assay-job-worker"));
    }

    /** Starts this instance's share of the background workers. */
    public void start() {
        for (int i = 0; i < workerThreads; i++) {
            workers.execute(this::pollLoop);
        }
    }

    /** Submits a run as a durable job; returns the API representation of the queued run. */
    public Map<String, Object> submit(RunSpec spec, String idempotencyKey) {
        Objects.requireNonNull(spec, "spec");
        if (spec.runId() == null) {
            throw new IllegalArgumentException("run_id is required before a run can be queued");
        }
        Job job = jobs.submit(idempotencyKey, RunJobs.payload(spec), maxAttempts);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", spec.runId());
        body.put("job_id", job.id());
        body.put("status", RunJobs.status(job.status()));
        body.put("status_url", "/api/runs/" + spec.runId());
        return body;
    }

    /** Finds the job that owns a run id, so a queued run can be reported before it starts. */
    public Optional<Job> findJobByRunId(String runId) {
        return jobs.findByRunId(runId);
    }

    /** Lists durable jobs (newest first when the store orders them). */
    public List<Job> jobs(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jobs.list(limit);
    }

    /** Looks up one job by id. */
    public Optional<Job> job(String jobId) {
        return jobs.find(jobId);
    }

    /** The stable identity of this instance in the shared queue. */
    public String workerId() {
        return workerId;
    }

    /** Claims and executes at most one job; used by tests and by the polling workers. */
    public boolean runOnce() {
        Optional<Job> claimed = jobs.claim(workerId, lease);
        if (claimed.isEmpty()) {
            return false;
        }
        execute(claimed.get());
        return true;
    }

    private void pollLoop() {
        int consecutiveFailures = 0;
        while (!closed.get()) {
            try {
                if (!runOnce()) {
                    Thread.sleep(pollMillis);
                }
                consecutiveFailures = 0;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                if (consecutiveFailures++ == 0) {
                    LOGGER.log(System.Logger.Level.WARNING, "job worker loop failed; retrying", failure);
                } else {
                    LOGGER.log(System.Logger.Level.DEBUG, "job worker loop still failing; retrying", failure);
                }
                try {
                    Thread.sleep(pollMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void execute(Job job) {
        String jobId = job.id();
        Thread worker = Thread.currentThread();
        AtomicBoolean cancelled = new AtomicBoolean();
        ScheduledExecutorService heartbeat =
                Executors.newSingleThreadScheduledExecutor(threadFactory("assay-job-heartbeat"));
        long beatMillis = Math.max(1, lease.toMillis() / 3);
        ScheduledFuture<?> beat = heartbeat.scheduleAtFixedRate(() -> {
            try {
                if (!jobs.heartbeat(jobId, workerId, lease)) {
                    LOGGER.log(System.Logger.Level.WARNING, "job {0} lost its lease; stopping worker", jobId);
                    stopWorker(worker, cancelled);
                    return;
                }
                if (jobs.isCancelRequested(jobId)) {
                    LOGGER.log(System.Logger.Level.INFO, "job {0} cancellation requested", jobId);
                    stopWorker(worker, cancelled);
                }
            } catch (RuntimeException failure) {
                // A transient store failure must not turn a healthy run into a terminal cancellation.
                // The next tick retries; if the lease really expires, another worker reclaims the job.
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "job {0} heartbeat failed; will retry",
                        jobId,
                        failure);
            }
        }, beatMillis, beatMillis, TimeUnit.MILLISECONDS);
        try {
            RunSpec spec = RunJobs.spec(job.payload());
            try (Results store = stores.get()) {
                RunExecution execution = runner.run(spec, options, store, outputDir);
                writeSummary(execution);
            }
            jobs.succeed(jobId, workerId);
        } catch (RuntimeException failure) {
            if (cancelled.get() || jobs.isCancelRequested(jobId)) {
                jobs.finish(jobId, workerId, JobStatus.CANCELLED, "cancelled");
            } else {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "job {0} failed: {1}",
                        jobId,
                        describe(failure));
                jobs.fail(jobId, workerId, describe(failure));
            }
        } finally {
            beat.cancel(false);
            heartbeat.shutdownNow();
            Thread.interrupted();
        }
    }

    private void writeSummary(RunExecution execution) {
        try {
            Path dir = execution.reportDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("run-summary.json"), Json.write(execution.summary()));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void stopWorker(Thread worker, AtomicBoolean cancelled) {
        cancelled.set(true);
        worker.interrupt();
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static ThreadFactory threadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        closed.set(true);
        workers.shutdownNow();
    }
}
