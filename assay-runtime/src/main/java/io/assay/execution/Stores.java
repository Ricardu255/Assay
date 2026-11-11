package io.assay.execution;

import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
import io.assay.store.Results;
import io.assay.store.file.FileJobStore;
import io.assay.store.file.ResultStore;
import io.assay.store.jdbc.JdbcJobStore;
import io.assay.store.jdbc.JdbcResultStore;
import java.nio.file.Path;

/**
 * Opens the configured stores from a path or a {@code jdbc:} URL.
 *
 * <p>A value starting with {@code jdbc:} selects the JDBC implementation; every other value is a
 * local directory for the zero-dependency file store. The JDBC driver must be on the runtime
 * classpath; the framework itself does not bundle one.
 */
public final class Stores {

    private static final String DEFAULT_STORE = ".assay/store";

    private Stores() {
    }

    /** Opens the result store; a null or blank reference uses {@value #DEFAULT_STORE}. */
    public static Results open(String reference) {
        if (reference != null && reference.startsWith("jdbc:")) {
            return JdbcResultStore.fromUrl(reference);
        }
        return new ResultStore(Path.of(reference == null || reference.isBlank() ? DEFAULT_STORE : reference));
    }

    /** Opens the job store; a null or blank reference uses {@value #DEFAULT_STORE}. */
    public static JobStore openJobs(String reference, RetryPolicy retryPolicy) {
        if (reference != null && reference.startsWith("jdbc:")) {
            return JdbcJobStore.fromUrl(reference, retryPolicy);
        }
        return new FileJobStore(
                Path.of(reference == null || reference.isBlank() ? DEFAULT_STORE : reference), retryPolicy);
    }
}
