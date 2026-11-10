package io.assay.server.run;

import io.assay.execution.JobRunQueue;
import io.assay.json.Json;
import io.assay.execution.RunIds;
import io.assay.execution.RunJobs;
import io.assay.execution.RunOptions;
import io.assay.execution.RunSpec;
import io.assay.execution.SuiteRunner;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
import io.assay.server.config.AssayServerProperties;
import io.assay.store.Results;
import io.assay.store.file.FileJobStore;
import io.assay.store.file.ResultStore;
import io.assay.store.jdbc.JdbcJobStore;
import io.assay.store.jdbc.JdbcResultStore;
import io.assay.support.Names;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Submits evaluation runs to the durable queue and serves their status.
 *
 * <p>A submitted run returns its id immediately; the job carries the spec and every server instance
 * that shares the same job store may execute it. Run state is persisted by the configured results
 * store, so a queued or finished run stays observable across restarts and instances.
 */
@Service
public class RunService {

    private static final int LIST_LIMIT = 200;
    private static final List<String> SUMMARY_KEYS = List.of(
            "status",
            "case_count",
            "requested_case_count",
            "skipped_case_count",
            "hard_failures",
            "soft_warnings",
            "budget",
            "retry_policy",
            "circuit_breaker");

    private final String storePath;
    private final String outputDir;
    private final JobStore jobs;
    private final JobRunQueue queue;

    public RunService(AssayServerProperties properties) {
        this.storePath = text(properties.getStore(), ".assay/store");
        this.outputDir = text(properties.getOutput(), ".assay/runs");
        this.jobs = openJobs(storePath);
        RunOptions options = new RunOptions(
                properties.getWorkers(),
                properties.getRetries(),
                properties.getTimeoutSeconds(),
                properties.isCollectFewShot());
        this.queue = new JobRunQueue(
                jobs,
                () -> openStore(storePath),
                new SuiteRunner(),
                options,
                Path.of(outputDir),
                properties.getWorkerThreads(),
                Duration.ofSeconds(properties.getJobLeaseSeconds()),
                properties.getPollMillis(),
                properties.getJobMaxAttempts());
    }

    /** Starts this instance's share of the durable queue workers. */
    @PostConstruct
    void startWorkers() {
        queue.start();
    }

    /** Validates the request and queues the durable job; returns its id plus the initial status. */
    public Map<String, Object> start(RunRequest request) {
        return start(request, null);
    }

    /**
     * Validates the request and queues the durable job.
     *
     * @param idempotencyKey optional client retry key; the same key and spec return the same job
     */
    public Map<String, Object> start(RunRequest request, String idempotencyKey) {
        return queue.submit(spec(request), idempotencyKey);
    }

    private RunSpec spec(RunRequest request) {
        String adapterRef = requireText(request.adapter(), "adapter");
        String casesRef = requireText(request.cases(), "cases");
        String suite = Names.safeName(
                request.suite() == null || request.suite().isBlank() ? "smoke" : request.suite(), "suite");
        String source = Names.safeName(
                request.source() == null || request.source().isBlank() ? "online" : request.source(), "source");
        String requestedRunId = request.runId() == null || request.runId().isBlank()
                ? null
                : Names.safeName(request.runId(), "run_id");
        boolean resume = Boolean.TRUE.equals(request.resume());
        if (resume && requestedRunId == null) {
            throw new IllegalArgumentException("resume requires a run_id");
        }
        String runId = requestedRunId != null ? requestedRunId : RunIds.newRunId(suite);
        return new RunSpec(adapterRef, request.adapterClass(), casesRef, suite, source, runId, requestedRunId, resume);
    }

    /** Persisted runs plus still-queued or running jobs, newest first. */
    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        try (Results store = openStore(storePath)) {
            for (Map<String, Object> run : store.listRuns()) {
                String runId = String.valueOf(run.get("run_id"));
                merged.put(runId, decorate(run));
            }
        } catch (UnsupportedOperationException ignored) {
            // Backends without run listing still expose live queue jobs below.
        } catch (RuntimeException ignored) {
            // Listing must stay available even when the store is temporarily unavailable.
        }
        try {
            for (Job job : queue.jobs(LIST_LIMIT)) {
                String runId = RunJobs.runId(job.payload());
                if (runId == null) {
                    continue;
                }
                Map<String, Object> existing = merged.get(runId);
                if (existing == null || finalizing(existing, job)) {
                    merged.put(runId, jobRow(job, runId));
                }
            }
        } catch (RuntimeException ignored) {
            // The queue may be unavailable while the store is reachable; persisted runs still list.
        }
        List<Map<String, Object>> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing((Map<String, Object> run) -> String.valueOf(
                        run.getOrDefault("started_at", run.getOrDefault("submitted_at", ""))))
                .reversed());
        return result;
    }

    /** A persisted run, or the queue record of a run that has not started yet. */
    public Optional<Map<String, Object>> get(String runId) {
        String safe = Names.safeName(runId, "run_id");
        Map<String, Object> persisted = null;
        try (Results store = openStore(storePath)) {
            persisted = store.getRun(safe);
        } catch (UnsupportedOperationException ignored) {
            // Fall through to the queue record.
        } catch (RuntimeException ignored) {
            // Fall through to the queue record.
        }
        Job job = queue.findJobByRunId(safe).orElse(null);
        if (persisted != null) {
            if (job != null && finalizing(persisted, job)) {
                // The engine finished but the worker has not written run-summary.json yet.
                return Optional.of(jobRow(job, safe));
            }
            return Optional.of(decorate(persisted));
        }
        return job == null ? Optional.empty() : Optional.of(jobRow(job, safe));
    }

    /** True while a run record is terminal but its queue worker is still finalizing the summary. */
    private static boolean finalizing(Map<String, Object> run, Job job) {
        if (job.status() != JobStatus.RUNNING) {
            return false;
        }
        Object status = run.get("status");
        return status != null && (status.equals("passed") || status.equals("failed")
                || status.equals("budget_exhausted") || status.equals("cancelled"));
    }

    /** The persisted per-case facts of a run; empty while the run has not started writing cases. */
    public List<Map<String, Object>> cases(String runId) {
        String safe = Names.safeName(runId, "run_id");
        try (Results store = openStore(storePath)) {
            return store.listResults(safe);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Map<String, Object> decorate(Map<String, Object> run) {
        Map<String, Object> row = new LinkedHashMap<>(run);
        Object rawRunId = run.get("run_id");
        if (rawRunId != null) {
            Path reports = Path.of(outputDir).resolve(String.valueOf(rawRunId));
            Path report = reports.resolve("report.md");
            if (Files.isRegularFile(report)) {
                row.put("report_dir", reports.toString());
                row.put("report_path", report.toString());
            }
            mergeSummary(row, reports.resolve("run-summary.json"));
        }
        return row;
    }

    private static void mergeSummary(Map<String, Object> row, Path summaryFile) {
        if (!Files.isRegularFile(summaryFile)) {
            return;
        }
        try {
            Map<String, Object> summary = Json.parseObject(Files.readString(summaryFile));
            for (String key : SUMMARY_KEYS) {
                if (summary.containsKey(key)) {
                    row.put(key, summary.get(key));
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // The summary file is optional; the persisted run record remains authoritative.
        }
    }

    private static Map<String, Object> jobRow(Job job, String runId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("job_id", job.id());
        row.put("status", RunJobs.status(job.status()));
        if (job.error() != null) {
            row.put("error", job.error());
        }
        row.put("attempts", job.attempts());
        row.put("max_attempts", job.maxAttempts());
        if (job.createdAt() != null) {
            row.put("submitted_at", job.createdAt().toString());
        }
        if (job.startedAt() != null) {
            row.put("started_at", job.startedAt().toString());
        }
        if (job.finishedAt() != null) {
            row.put("finished_at", job.finishedAt().toString());
        }
        return Collections.unmodifiableMap(row);
    }

    private static JobStore openJobs(String reference) {
        if (reference != null && reference.startsWith("jdbc:")) {
            return JdbcJobStore.fromUrl(reference, RetryPolicy.defaults());
        }
        return new FileJobStore(Path.of(reference == null || reference.isBlank() ? ".assay/store" : reference));
    }

    private static Results openStore(String reference) {
        if (reference != null && reference.startsWith("jdbc:")) {
            return JdbcResultStore.fromUrl(reference);
        }
        return new ResultStore(Path.of(reference == null || reference.isBlank() ? ".assay/store" : reference));
    }

    /** Stops this instance's workers and releases the job store on application shutdown. */
    @PreDestroy
    void shutdown() {
        queue.close();
        jobs.close();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
