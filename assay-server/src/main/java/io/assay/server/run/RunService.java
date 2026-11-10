package io.assay.server.run;

import io.assay.execution.RunExecution;
import io.assay.execution.RunIds;
import io.assay.execution.RunOptions;
import io.assay.execution.RunSpec;
import io.assay.execution.SuiteRunner;
import io.assay.server.config.AssayServerProperties;
import io.assay.store.Results;
import io.assay.store.file.ResultStore;
import io.assay.store.jdbc.JdbcResultStore;
import io.assay.support.Names;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * Submits evaluation runs asynchronously and serves their status.
 *
 * <p>A submitted run returns a run id immediately; the pool executes it in the background and the
 * latest status is kept in memory. Finished runs are also persisted by the configured store, so a
 * restarted process can still list and read them through {@link Results#listRuns()}.
 */
@Service
public class RunService {

    private static final String QUEUED = "queued";
    private static final String RUNNING = "running";
    private static final String FAILED = "failed";

    private final String storePath;
    private final String outputDir;
    private final RunOptions options;
    private final SuiteRunner runner = new SuiteRunner();
    private final ConcurrentMap<String, Map<String, Object>> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public RunService(AssayServerProperties properties) {
        this.storePath = properties.getStore();
        this.outputDir = properties.getOutput();
        this.options = new RunOptions(
                properties.getWorkers(),
                properties.getRetries(),
                properties.getTimeoutSeconds(),
                properties.isCollectFewShot());
        int threads = Math.max(1, properties.getExecutorThreads());
        int queueCapacity = Math.max(1, properties.getQueueCapacity());
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Validates the request, enqueues the run, and returns its id plus the initial status. */
    public Map<String, Object> start(RunRequest request) {
        String adapterRef = requireText(request.adapter(), "adapter");
        String casesRef = requireText(request.cases(), "cases");
        String suite = Names.safeName(
                request.suite() == null || request.suite().isBlank() ? "smoke" : request.suite(), "suite");
        String source = Names.safeName(
                request.source() == null || request.source().isBlank() ? "online" : request.source(), "source");
        String requestedRunId = request.runId() == null || request.runId().isBlank()
                ? null
                : Names.safeName(request.runId(), "run_id");
        RunSpec spec = new RunSpec(
                adapterRef,
                request.adapterClass(),
                casesRef,
                suite,
                source,
                requestedRunId != null ? requestedRunId : RunIds.newRunId(suite),
                requestedRunId,
                Boolean.TRUE.equals(request.resume()));
        String submittedAt = Instant.now().toString();
        runs.put(spec.runId(), snapshot(spec.runId(), QUEUED, adapterRef, suite, source, null, submittedAt, null));
        try {
            executor.execute(() -> execute(spec, submittedAt));
        } catch (RejectedExecutionException error) {
            runs.remove(spec.runId());
            throw error;
        }
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("run_id", spec.runId());
        submitted.put("status", QUEUED);
        submitted.put("status_url", "/api/runs/" + spec.runId());
        return submitted;
    }

    /** Runs the suite in the background and replaces the in-memory record with its final summary. */
    private void execute(RunSpec spec, String submittedAt) {
        runs.put(
                spec.runId(),
                snapshot(spec.runId(), RUNNING, spec.adapterRef(), spec.suite(), spec.source(), null, submittedAt, null));
        try (Results store = openStore()) {
            RunExecution execution = runner.run(spec, options, store, Path.of(outputDir));
            Map<String, Object> finished = new LinkedHashMap<>(execution.summary());
            finished.put("status", execution.summary().getOrDefault("status", FAILED));
            finished.put("started_at", submittedAt);
            finished.put("finished_at", Instant.now().toString());
            finished.put("report_dir", execution.reportDir().toString());
            finished.put("report_path", execution.reportDir().resolve("report.md").toString());
            runs.put(spec.runId(), Collections.unmodifiableMap(finished));
        } catch (RuntimeException error) {
            markStoreFailed(spec.runId());
            runs.put(
                    spec.runId(),
                    snapshot(
                            spec.runId(),
                            FAILED,
                            spec.adapterRef(),
                            spec.suite(),
                            spec.source(),
                            error.getMessage(),
                            submittedAt,
                            Instant.now().toString()));
        }
    }

    /** Persisted runs plus this process's live state, newest first. */
    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        try (Results store = openStore()) {
            for (Map<String, Object> run : store.listRuns()) {
                String runId = String.valueOf(run.get("run_id"));
                merged.put(runId, decorate(run));
            }
        } catch (UnsupportedOperationException ignored) {
            // A backend without run listing still has the in-memory registry below.
        } catch (RuntimeException ignored) {
            // Listing must stay available even when the store is temporarily unavailable.
        }
        for (Map.Entry<String, Map<String, Object>> entry : runs.entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        List<Map<String, Object>> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing((Map<String, Object> run) -> String.valueOf(run.get("started_at")))
                .reversed());
        return result;
    }

    /** A live run from this process, or a persisted record read back from the store. */
    public Optional<Map<String, Object>> get(String runId) {
        String safe = Names.safeName(runId, "run_id");
        Map<String, Object> live = runs.get(safe);
        if (live != null) {
            return Optional.of(live);
        }
        try (Results store = openStore()) {
            Map<String, Object> persisted = store.getRun(safe);
            return Optional.ofNullable(persisted).map(this::decorate);
        } catch (UnsupportedOperationException ignored) {
            return Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /** The persisted per-case facts of a run, read back from the configured store. */
    public List<Map<String, Object>> cases(String runId) {
        String safeRunId = Names.safeName(runId, "run_id");
        try (Results store = openStore()) {
            return store.listResults(safeRunId);
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
        }
        return row;
    }

    private void markStoreFailed(String runId) {
        try (Results store = openStore()) {
            Map<String, Object> persisted = store.getRun(runId);
            if (persisted != null && RUNNING.equals(persisted.get("status"))) {
                store.finishRun(runId, FAILED);
            }
        } catch (RuntimeException ignored) {
            // Best effort: the in-memory status is authoritative for this process.
        }
    }

    private Results openStore() {
        if (storePath != null && storePath.startsWith("jdbc:")) {
            return JdbcResultStore.fromUrl(storePath);
        }
        return new ResultStore(Path.of(storePath == null || storePath.isBlank() ? ".assay/store" : storePath));
    }

    /** Stops accepting runs and interrupts the in-flight workers on application shutdown. */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static Map<String, Object> snapshot(
            String runId,
            String status,
            String adapter,
            String suite,
            String source,
            String error,
            String startedAt,
            String finishedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("status", status);
        row.put("adapter", adapter);
        row.put("suite", suite);
        row.put("source", source);
        if (error != null) {
            row.put("error", error);
        }
        row.put("started_at", startedAt);
        row.put("finished_at", finishedAt);
        return Collections.unmodifiableMap(row);
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "assay-server-run-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
