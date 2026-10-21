package io.assay.cli;

import io.assay.store.Results;
import io.assay.store.file.ResultStore;
import io.assay.store.jdbc.JdbcResultStore;
import java.nio.file.Path;

/**
 * Opens the configured result store.
 *
 * <p>A value starting with {@code jdbc:} selects the JDBC implementation; every other value is a
 * local directory for the zero-dependency file store. The JDBC driver must be on the runtime
 * classpath; the framework itself does not bundle one.
 */
public final class Stores {

    private Stores() {
    }

    public static Results open(String reference) {
        if (reference != null && reference.startsWith("jdbc:")) {
            return JdbcResultStore.fromUrl(reference);
        }
        return new ResultStore(Path.of(reference == null || reference.isBlank() ? ".assay/store" : reference));
    }
}
