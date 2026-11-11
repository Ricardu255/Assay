package io.assay.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Run retention pruning for {@link JdbcResultStore}. */
final class JdbcRetention {

    private final JdbcConnectionFactory connections;

    JdbcRetention(JdbcConnectionFactory connections) {
        this.connections = connections;
    }

    List<String> pruneRuns(Instant cutoff, int keepLatest, boolean dryRun) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }
        if (keepLatest < 0) {
            throw new IllegalArgumentException("keepLatest must be non-negative");
        }
        List<RunRow> rows = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement("""
                        select run_id, coalesce(finished_at, started_at) as ended_at
                        from assay_run
                        where status <> 'running'
                        order by ended_at desc
                        """)) {
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    rows.add(new RunRow(
                            result.getString("run_id"),
                            JdbcSupport.instant(result.getTimestamp("ended_at"))));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot list runs for pruning", error);
        }
        List<String> deleted = new ArrayList<>();
        for (int index = keepLatest; index < rows.size(); index++) {
            RunRow row = rows.get(index);
            if (row.endedAt() == null || !row.endedAt().isBefore(cutoff)) {
                continue;
            }
            deleted.add(row.runId());
        }
        if (dryRun || deleted.isEmpty()) {
            return deleted;
        }
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                for (String runId : deleted) {
                    deleteByRun(connection, "delete from assay_review where run_id = ?", runId);
                    deleteByRun(connection, "delete from assay_case_manifest where run_id = ?", runId);
                    deleteByRun(connection, "delete from assay_case where run_id = ?", runId);
                    deleteByRun(connection, "delete from assay_lock where run_id = ?", runId);
                    deleteByRun(connection, "delete from assay_run where run_id = ?", runId);
                }
                connection.commit();
                return deleted;
            } catch (SQLException error) {
                JdbcSupport.rollbackQuietly(connection);
                throw new IllegalStateException("cannot prune runs", error);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot prune runs", error);
        }
    }

    private record RunRow(String runId, Instant endedAt) {
    }

    private static void deleteByRun(Connection connection, String sql, String runId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(sql)) {
            delete.setString(1, runId);
            delete.executeUpdate();
        }
    }
}
