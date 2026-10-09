package io.assay.model;

/** Identity of one adapter call, passed into {@link ProjectAdapter#callAgent}. */
public record RunContext(
        String runId,
        String suite,
        String source,
        int attempt,
        double timeoutSeconds) {
}
