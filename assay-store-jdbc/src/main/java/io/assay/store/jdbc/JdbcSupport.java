package io.assay.store.jdbc;

import io.assay.model.Values;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/** Small JDBC helpers shared by the repository classes behind {@link JdbcResultStore}. */
final class JdbcSupport {

    private JdbcSupport() {
    }

    static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is more useful than a rollback failure.
        }
    }

    static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful to do while closing.
        }
    }

    static int intValue(Object value) {
        return Values.numberAsInt(value);
    }

    static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    static Map<String, Object> asMap(Object value) {
        return Values.asMap(value);
    }
}
