package io.assay.execution;

import java.util.UUID;

/** Generates run ids that are safe to use as a store directory name. */
public final class RunIds {

    private RunIds() {
    }

    /** Returns a fresh {@code <suite>-<12 hex chars>} run id. */
    public static String newRunId(String suite) {
        return suite + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
