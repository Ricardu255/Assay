package io.assay.llm;

import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import java.util.List;
import java.util.Map;

/**
 * Semantic analysis hook used by the evaluation engine.
 *
 * <p>Naming the behaviour rather than a concrete client keeps the deterministic layers free of any HTTP
 * dependency, and lets the same entry point back both {@code --use-llm} and a test double.
 */
@FunctionalInterface
public interface LlmReviewer {

    /** Returns a non-authoritative analysis record for cases that need review. */
    Map<String, Object> analyze(
            EvalCase evalCase, List<NormalizedTrace> traces, List<CheckResult> checks, boolean collectFewShot);
}
