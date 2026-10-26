package io.assay.store.jdbc;

import io.assay.json.Json;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;

/** JDBC-backed durable job queue with leases, cancellation, and bounded retries. */
public final class JdbcJobStore implements JobStore {

    @FunctionalInterface
    private interface Connections {
        Connection open() throws SQLException;
    }

    private final Connections connections;

    public JdbcJobStore(DataSource dataSource) {
        this(dataSource::getConnection);
    }

    private JdbcJobStore(Connections connections) {
        this.connections = connections;
        ensureSchema();
    }

    public static JdbcJobStore fromUrl(String url) {
        if (url == null || !url.startsWith("jdbc:")) {
            throw new IllegalArgumentException("job store requires a jdbc: URL");
        }
        Properties properties = new Properties();
        String user = System.getenv("ASSAY_DB_USER");
        String password = System.getenv("ASSAY_DB_PASSWORD");
        if (user != null && !user.isBlank()) {
            properties.setProperty("user", user);
        }
        if (password != null && !password.isBlank()) {
            properties.setProperty("password", password);
        }
        String normalized = normalizeUrl(url);
        return new JdbcJobStore(() -> DriverManager.getConnection(normalized, properties));
    }

    private static String normalizeUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:h2:file:") || lower.contains("db_close_delay")) {
            return url;
        }
        return url + (url.endsWith(";") ? "" : ";") + "DB_CLOSE_DELAY=-1";
    }

    private void ensureSchema() {
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists assay_job (
                      job_id varchar(64) primary key,
                      status varchar(32) not null,
                      payload_json text not null,
                      attempts int not null,
                      max_attempts int not null,
                      lease_owner varchar(128),
                      lease_expires_at timestamp,
                      cancel_requested boolean not null,
                      created_at timestamp not null,
                      started_at timestamp,
                      finished_at timestamp,
                      error text
                    )
                    """);
        } catch (SQLException error) {
            throw new IllegalStateException("cannot initialize job store", error);
        }
    }

    @Override
    public Job submit(Map<String, Object> payload, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        String id = "job-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();
        try (Connection connection = connections.open();
                PreparedStatement insert = connection.prepareStatement("""
                        insert into assay_job(job_id, status, payload_json, attempts, max_attempts,
                          lease_owner, lease_expires_at, cancel_requested, created_at)
                        values (?, 'QUEUED', ?, 0, ?, null, null, false, ?)
                        """)) {
            insert.setString(1, id);
            insert.setString(2, Json.write(payload));
            insert.setInt(3, maxAttempts);
            insert.setTimestamp(4, Timestamp.from(now));
            insert.executeUpdate();
            return new Job(id, JobStatus.QUEUED, payload, 0, maxAttempts, null, null, false, now, null, null, null);
        } catch (SQLException error) {
            throw new IllegalStateException("cannot submit job", error);
        }
    }

    @Override
    public Optional<Job> claim(String workerId, Duration lease) {
        String worker = text(workerId, "workerId");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        Instant now = Instant.now();
        Instant expires = now.plus(lease);
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                reapExpired(connection, now);
                String jobId;
                try (PreparedStatement select = connection.prepareStatement("""
                        select job_id, payload_json, attempts, max_attempts, created_at, started_at
                        from assay_job
                        where status = 'QUEUED' and cancel_requested = false
                        order by created_at
                        limit 1 for update
                        """); ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    jobId = rows.getString("job_id");
                    int attempts = rows.getInt("attempts") + 1;
                    int max = rows.getInt("max_attempts");
                    Map<String, Object> payload = Json.parseObject(rows.getString("payload_json"));
                    try (PreparedStatement update = connection.prepareStatement("""
                            update assay_job
                            set status = 'RUNNING', attempts = ?, lease_owner = ?, lease_expires_at = ?,
                                started_at = coalesce(started_at, ?), error = null
                            where job_id = ?
                            """)) {
                        update.setInt(1, attempts);
                        update.setString(2, worker);
                        update.setTimestamp(3, Timestamp.from(expires));
                        update.setTimestamp(4, Timestamp.from(now));
                        update.setString(5, jobId);
                        update.executeUpdate();
                    }
                    connection.commit();
                    return Optional.of(new Job(
                            jobId, JobStatus.RUNNING, payload, attempts, max, worker, expires, false, now, now, null, null));
                }
            } catch (SQLException | RuntimeException error) {
                rollback(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot claim job", error);
        }
    }

    private static void reapExpired(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement failed = connection.prepareStatement("""
                update assay_job
                set status = 'FAILED', error = 'lease expired after max attempts',
                    finished_at = ?, lease_owner = null, lease_expires_at = null
                where status = 'RUNNING' and lease_expires_at < ? and attempts >= max_attempts
                """)) {
            failed.setTimestamp(1, Timestamp.from(now));
            failed.setTimestamp(2, Timestamp.from(now));
            failed.executeUpdate();
        }
        try (PreparedStatement requeue = connection.prepareStatement("""
                update assay_job
                set status = 'QUEUED', lease_owner = null, lease_expires_at = null
                where status = 'RUNNING' and lease_expires_at < ? and attempts < max_attempts
                """)) {
            requeue.setTimestamp(1, Timestamp.from(now));
            requeue.executeUpdate();
        }
        try (PreparedStatement cancelled = connection.prepareStatement("""
                update assay_job
                set status = 'CANCELLED', finished_at = ?
                where status = 'QUEUED' and cancel_requested = true
                """)) {
            cancelled.setTimestamp(1, Timestamp.from(now));
            cancelled.executeUpdate();
        }
    }

    @Override
    public boolean heartbeat(String jobId, String workerId, Duration lease) {
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        try (Connection connection = connections.open();
                PreparedStatement update = connection.prepareStatement("""
                        update assay_job set lease_expires_at = ?
                        where job_id = ? and status = 'RUNNING' and lease_owner = ?
                        """)) {
            update.setTimestamp(1, Timestamp.from(Instant.now().plus(lease)));
            update.setString(2, jobId);
            update.setString(3, workerId);
            return update.executeUpdate() > 0;
        } catch (SQLException error) {
            throw new IllegalStateException("cannot renew job lease", error);
        }
    }

    @Override
    public boolean cancel(String jobId) {
        Instant now = Instant.now();
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement request = connection.prepareStatement("""
                        update assay_job set cancel_requested = true
                        where job_id = ? and status in ('QUEUED', 'RUNNING')
                        """)) {
                    request.setString(1, jobId);
                    updated = request.executeUpdate();
                }
                if (updated > 0) {
                    try (PreparedStatement queued = connection.prepareStatement("""
                            update assay_job set status = 'CANCELLED', finished_at = ?
                            where job_id = ? and status = 'QUEUED'
                            """)) {
                        queued.setTimestamp(1, Timestamp.from(now));
                        queued.setString(2, jobId);
                        queued.executeUpdate();
                    }
                }
                connection.commit();
                return updated > 0;
            } catch (SQLException error) {
                rollback(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot cancel job", error);
        }
    }

    @Override
    public boolean isCancelRequested(String jobId) {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select cancel_requested from assay_job where job_id = ?")) {
            select.setString(1, jobId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot read cancellation state", error);
        }
    }

    @Override
    public Optional<Job> find(String jobId) {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement("select * from assay_job where job_id = ?")) {
            select.setString(1, jobId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(job(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot read job", error);
        }
    }

    @Override
    public List<Job> list(int limit) {
        int max = Math.max(1, Math.min(500, limit));
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select * from assay_job order by created_at desc limit " + max);
                ResultSet rows = select.executeQuery()) {
            List<Job> jobs = new ArrayList<>();
            while (rows.next()) {
                jobs.add(job(rows));
            }
            return jobs;
        } catch (SQLException error) {
            throw new IllegalStateException("cannot list jobs", error);
        }
    }

    @Override
    public void succeed(String jobId, String workerId) {
        terminal(jobId, workerId, JobStatus.SUCCEEDED, null);
    }

    @Override
    public void fail(String jobId, String workerId, String error) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                int attempts;
                int max;
                try (PreparedStatement select = connection.prepareStatement("""
                        select attempts, max_attempts from assay_job
                        where job_id = ? and status = 'RUNNING' and lease_owner = ? for update
                        """)) {
                    select.setString(1, jobId);
                    select.setString(2, workerId);
                    try (ResultSet rows = select.executeQuery()) {
                        if (!rows.next()) {
                            connection.commit();
                            return;
                        }
                        attempts = rows.getInt(1);
                        max = rows.getInt(2);
                    }
                }
                Instant now = Instant.now();
                try (PreparedStatement update = connection.prepareStatement(attempts < max
                        ? "update assay_job set status = 'QUEUED', lease_owner = null, lease_expires_at = null, error = ? where job_id = ?"
                        : "update assay_job set status = 'FAILED', finished_at = ?, lease_owner = null, lease_expires_at = null, error = ? where job_id = ?")) {
                    if (attempts < max) {
                        update.setString(1, error);
                        update.setString(2, jobId);
                    } else {
                        update.setTimestamp(1, Timestamp.from(now));
                        update.setString(2, error);
                        update.setString(3, jobId);
                    }
                    update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                rollback(connection);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot fail job", failure);
        }
    }

    @Override
    public void finish(String jobId, String workerId, JobStatus status, String error) {
        if (status == JobStatus.QUEUED || status == JobStatus.RUNNING) {
            throw new IllegalArgumentException("finish requires a terminal status");
        }
        terminal(jobId, workerId, status, error);
    }

    private void terminal(String jobId, String workerId, JobStatus status, String error) {
        try (Connection connection = connections.open();
                PreparedStatement update = connection.prepareStatement("""
                        update assay_job
                        set status = ?, finished_at = ?, lease_owner = null, lease_expires_at = null, error = ?
                        where job_id = ? and status = 'RUNNING' and lease_owner = ?
                        """)) {
            update.setString(1, status.name());
            update.setTimestamp(2, Timestamp.from(Instant.now()));
            update.setString(3, error);
            update.setString(4, jobId);
            update.setString(5, workerId);
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot finish job", failure);
        }
    }

    private static Job job(ResultSet rows) throws SQLException {
        return new Job(
                rows.getString("job_id"),
                JobStatus.valueOf(rows.getString("status")),
                Json.parseObject(rows.getString("payload_json")),
                rows.getInt("attempts"),
                rows.getInt("max_attempts"),
                rows.getString("lease_owner"),
                instant(rows.getTimestamp("lease_expires_at")),
                rows.getBoolean("cancel_requested"),
                instant(rows.getTimestamp("created_at")),
                instant(rows.getTimestamp("started_at")),
                instant(rows.getTimestamp("finished_at")),
                rows.getString("error"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value.trim();
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is more useful.
        }
    }

    @Override
    public void close() {
        // Connections are opened per operation.
    }
}
