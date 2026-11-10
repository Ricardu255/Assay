package io.assay.server.run;

import io.assay.adapters.AdapterLoader;
import io.assay.engine.CaseLoader;
import io.assay.engine.CircuitBreaker;
import io.assay.engine.EvaluationEngine;
import io.assay.engine.EvaluationListener;
import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.ProjectAdapter;
import io.assay.report.Reporting;
import io.assay.runner.RetryPolicy;
import io.assay.server.config.AssayServerProperties;
import io.assay.store.Results;
import io.assay.store.file.ResultStore;
import io.assay.store.jdbc.JdbcResultStore;
import io.assay.support.Names;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * Executes one evaluation run per request and keeps the latest results in memory.
 *
 * <p>Runs are synchronous: the caller waits for the decision. Persisted artifacts stay in the
 * configured store and report directories; the in-memory registry only serves this process.
 */
@Service
public class RunService {

    private final String storePath;
    private final String outputDir;
    private final int workers;
    private final int retries;
    private final double timeoutSeconds;
    private final boolean collectFewShot;
    private final ConcurrentMap<String, Map<String, Object>> runs = new ConcurrentHashMap<>();

    public RunService(AssayServerProperties properties) {
        this.storePath = properties.getStore();
        this.outputDir = properties.getOutput();
        this.workers = properties.getWorkers();
        this.retries = properties.getRetries();
        this.timeoutSeconds = properties.getTimeoutSeconds();
        this.collectFewShot = properties.isCollectFewShot();
    }

    /** Loads the adapter and cases, runs the suite, writes report artifacts, and returns the summary. */
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
        if (Boolean.TRUE.equals(request.resume())) {
            throw new IllegalArgumentException("resume is not supported by the server yet");
        }

        List<EvalCase> cases = CaseLoader.loadCases(Path.of(casesRef), suite);
        ProjectAdapter adapter = AdapterLoader.loadProjectAdapter(adapterRef, request.adapterClass());
        try (Results store = openStore()) {
            EvaluationEngine engine = new EvaluationEngine(
                    adapter,
                    store,
                    null,
                    workers,
                    retries,
                    timeoutSeconds,
                    collectFewShot,
                    requestedRunId,
                    EvaluationListener.NOOP,
                    EvaluationBudget.disabled(),
                    RetryPolicy.defaults(),
                    CircuitBreaker.disabled(),
                    CircuitBreaker.disabled());
            Map<String, Object> summary = engine.runSuite(cases, suite, source, requestedRunId, false);
            String runId = String.valueOf(summary.get("run_id"));
            Path reports = Path.of(outputDir).resolve(runId);
            Reporting.writeRunArtifacts(summary, reports);
            Map<String, Object> response = new LinkedHashMap<>(summary);
            response.put("report_dir", reports.toString());
            response.put("report_path", reports.resolve("report.md").toString());
            runs.put(runId, Collections.unmodifiableMap(new LinkedHashMap<>(response)));
            return response;
        }
    }

    /** Every run started by this process, newest writes included. */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : runs.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run_id", entry.getKey());
            row.put("status", entry.getValue().get("status"));
            row.put("hard_failures", entry.getValue().get("hard_failures"));
            row.put("report_path", entry.getValue().get("report_path"));
            result.add(row);
        }
        return result;
    }

    /** The in-memory summary of one run, if this process started it. */
    public Optional<Map<String, Object>> get(String runId) {
        return Optional.ofNullable(runs.get(Names.safeName(runId, "run_id")));
    }

    /** The persisted per-case facts of a run, read back from the configured store. */
    public List<Map<String, Object>> cases(String runId) {
        String safeRunId = Names.safeName(runId, "run_id");
        try (Results store = openStore()) {
            return store.listResults(safeRunId);
        }
    }

    private Results openStore() {
        if (storePath != null && storePath.startsWith("jdbc:")) {
            return JdbcResultStore.fromUrl(storePath);
        }
        return new ResultStore(Path.of(storePath == null || storePath.isBlank() ? ".assay/store" : storePath));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
