package io.assay.execution;

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
import io.assay.store.Results;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs one evaluation suite from a protocol-neutral {@link RunSpec}.
 *
 * <p>This is the shared execution path for the CLI and the HTTP server: the caller supplies a
 * resolved {@link Results} store and output directory, while the runner loads the adapter and cases,
 * executes the engine, and writes the standard artifacts.
 */
public final class SuiteRunner {

    /** Loads the adapter and cases named by the spec, then runs the suite. */
    public RunExecution run(RunSpec spec, RunOptions options, Results store, Path outputDir) {
        Objects.requireNonNull(spec, "spec");
        List<EvalCase> cases = CaseLoader.loadCases(Path.of(spec.casesRef()), spec.suite());
        ProjectAdapter adapter = AdapterLoader.loadProjectAdapter(spec.adapterRef(), spec.adapterClass());
        return run(adapter, cases, spec, options, store, outputDir);
    }

    /** Runs an already resolved adapter and case list. */
    public RunExecution run(
            ProjectAdapter adapter,
            List<EvalCase> cases,
            RunSpec spec,
            RunOptions options,
            Results store,
            Path outputDir) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(cases, "cases");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(outputDir, "outputDir");
        String runId = spec.runId() != null ? spec.runId() : RunIds.newRunId(spec.suite());
        EvaluationEngine engine = new EvaluationEngine(
                adapter,
                store,
                null,
                options.workers(),
                options.retries(),
                options.timeoutSeconds(),
                options.collectFewShot(),
                spec.runIdentity(),
                EvaluationListener.NOOP,
                EvaluationBudget.disabled(),
                RetryPolicy.defaults(),
                CircuitBreaker.disabled(),
                CircuitBreaker.disabled());
        Map<String, Object> summary = engine.runSuite(cases, spec.suite(), spec.source(), runId, spec.resume());
        Path reportDir = outputDir.resolve(runId);
        Reporting.writeRunArtifacts(summary, reportDir);
        return new RunExecution(runId, Collections.unmodifiableMap(new LinkedHashMap<>(summary)), reportDir);
    }
}
