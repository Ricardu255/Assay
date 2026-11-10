package io.assay.store.jdbc;

import io.assay.store.Results;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Exclusive run lock held on a dedicated JDBC connection. */
final class JdbcRunLock implements Results.RunLock {

    private final Connection connection;
    private final String runId;
    private boolean closed;

    JdbcRunLock(Connection connection, String runId) {
        this.connection = connection;
        this.runId = runId;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try (PreparedStatement delete =
                connection.prepareStatement("delete from assay_lock where run_id = ?")) {
            delete.setString(1, runId);
            delete.executeUpdate();
            connection.commit();
        } catch (SQLException ignored) {
            // A stale lock row is preferable to masking the completion of the run.
        } finally {
            JdbcSupport.closeQuietly(connection);
        }
    }
}
