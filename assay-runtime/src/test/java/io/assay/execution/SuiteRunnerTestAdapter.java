package io.assay.execution;

import io.assay.engine.ProjectAdapterFactory;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.TraceEvent;
import java.util.List;
import java.util.Map;

/** A minimal adapter factory used to exercise adapter loading by class name. */
public final class SuiteRunnerTestAdapter implements ProjectAdapterFactory {

    @Override
    public ProjectAdapter create() {
        return new ProjectAdapter(
                "suite-runner-test",
                (EvalCase evalCase, io.assay.model.RunContext context) -> Map.of("message", "ok"),
                (Object handle, EvalCase evalCase) -> new NormalizedTrace(
                        "trace-" + evalCase.caseId(),
                        "ok",
                        List.of(new TraceEvent("agent", "reply")),
                        Map.of()),
                List.of(),
                List.of());
    }
}
