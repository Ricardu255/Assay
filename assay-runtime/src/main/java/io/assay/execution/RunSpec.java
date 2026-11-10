package io.assay.execution;

import io.assay.support.Names;

/**
 * One evaluation run request, independent of the protocol that submitted it.
 *
 * <p>The adapter and case references are resolved by {@link SuiteRunner}; {@code runId} is the
 * effective store key and {@code runIdentity} the stable adapter/target identity the engine uses to
 * validate a resumed run. A generated run id has no identity, so it cannot be resumed.
 */
public record RunSpec(
        String adapterRef,
        String adapterClass,
        String casesRef,
        String suite,
        String source,
        String runId,
        String runIdentity,
        boolean resume) {

    public RunSpec {
        adapterRef = requireText(adapterRef, "adapter");
        casesRef = requireText(casesRef, "cases");
        suite = Names.safeName(suite == null || suite.isBlank() ? "smoke" : suite, "suite");
        source = Names.safeName(source == null || source.isBlank() ? "online" : source, "source");
        runId = runId == null || runId.isBlank() ? null : Names.safeName(runId, "run_id");
        if (resume && (runId == null || runIdentity == null || runIdentity.isBlank())) {
            throw new IllegalArgumentException("resume requires an explicit run_id and a stable run identity");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
