package io.assay.store.jdbc;

import io.assay.json.Json;
import io.assay.runner.IdempotencyConflictException;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
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
    private final RetryPolicy retryPolicy;

    public JdbcJobStore(DataSource dataSource) {
        this(dataSource::getConnection, RetryPolicy.defaults());
    }

    public JdbcJobStore(DataSource dataSource, RetryPolicy retryPolicy) {
        this(dataSource::getConnection, retryPolicy);
    }

    private JdbcJobStore(Connections connections, RetryPolicy retryPolicy) {
        this.connections = connections;
        this.retryPolicy = retryPolicy == null ? RetryPolicy.defaults() : retryPolicy;
        ensureSchema();
    }

    public static JdbcJobStore fromUrl(String url) {
        return fromUrl(url, RetryPolicy.defaults());
    }

    public static JdbcJobStore fromUrl(String url, RetryPolicy retryPolicy) {
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
        return new JdbcJobStore(() -> DriverManager.getConnection(normalized, properties), retryPolicy);
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
                      error text,
                      idempotency_key varchar(128),
                      replay_count int not null default 0,
                      next_attempt_at timestamp
                    )
                    """);
            // Existing local stores predate idempotency/replay/backoff; add columns without a reset.
            statement.execute("alter table assay_job add column if not exists idempotency_key varchar(128)");
            statement.execute("alter table assay_job add column if not exists replay_count int not null default 0");
            statement.execute("alter table assay_job add column if not exists next_attempt_at timestamp");
            statement.execute("create unique index if not exists assay_job_idempotency on assay_job(idempotency_key)");
        } catch (SQLException error) {
            throw new IllegalStateException("cannot initialize job store", error);
        }
    }

    @Override
    public Job submit(String idempotencyKey, Map<String, Object> payload, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null) {
            Optional<Job> existing = findByIdempotencyKey(key);
            if (existing.isPresent()) {
                requireSamePayload(key, existing.get(), payload);
                return existing.get();
            }
        }
        String id = "job-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();
        try (Connection connection = connections.open();
                PreparedStatement insert = connection.prepareStatement("""
                        insert into assay_job(job_id, status, payload_json, attempts, max_attempts,
                          lease_owner, lease_expires_at, cancel_requested, created_at, idempotency_key, replay_count)
                        values (?, 'QUEUED', ?, 0, ?, null, null, false, ?, ?, 0)
                        """)) {
            insert.setString(1, id);
            insert.setString(2, Json.write(payload));
            insert.setInt(3, maxAttempts);
            insert.setTimestamp(4, Timestamp.from(now));
            insert.setString(5, key);
            insert.executeUpdate();
            return new Job(id, JobStatus.QUEUED, payload, 0, maxAttempts, null, null, false, now, null, null, null,
                    key, 0, null);
        } catch (SQLException error) {
            if (key != null) {
                Optional<Job> existing = findByIdempotencyKey(key);
                if (existing.isPresent()) {
                    requireSamePayload(key, existing.get(), payload);
                    return existing.get();
                }
            }
            throw new IllegalStateException("cannot submit job", error);
        }
    }

    private Optional<Job> findByIdempotencyKey(String key) {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select * from assay_job where idempotency_key = ?")) {
            select.setString(1, key);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(job(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot read idempotency key", error);
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
                        select job_id, payload_json, attempts, max_attempts, created_at, started_at,
                          idempotency_key, replay_count
                        from assay_job
                        where status = 'QUEUED' and cancel_requested = false
                          and (next_attempt_at is null or next_attempt_at <= ?)
                        order by created_at
                        limit 1 for update
                        """)) {
                    select.setTimestamp(1, Timestamp.from(now));
                    try (ResultSet rows = select.executeQuery()) {
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
                                    started_at = coalesce(started_at, ?), next_attempt_at = null, error = null
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
                                jobId, JobStatus.RUNNING, payload, attempts, max, worker, expires, false, now, now,
                                null, null, rows.getString("idempotency_key"), rows.getInt("replay_count"), null));
                    }
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
                    finished_at = ?, lease_owner = null, lease_expires_at = null, next_attempt_at = null
                where status = 'RUNNING' and lease_expires_at < ? and attempts >= max_attempts
                """)) {
            failed.setTimestamp(1, Timestamp.from(now));
            failed.setTimestamp(2, Timestamp.from(now));
            failed.executeUpdate();
        }
        try (PreparedStatement requeue = connection.prepareStatement("""
                update assay_job
                set status = 'QUEUED', lease_owner = null, lease_expires_at = null, next_attempt_at = null
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
                        ? "update assay_job set status = 'QUEUED', lease_owner = null, lease_expires_at = null, next_attempt_at = ?, error = ? where job_id = ?"
                        : "update assay_job set status = 'FAILED', finished_at = ?, lease_owner = null, lease_expires_at = null, next_attempt_at = null, error = ? where job_id = ?")) {
                    if (attempts < max) {
                        Duration delay = retryPolicy.delayFor(attempts);
                        update.setTimestamp(
                                1, delay.isZero() ? null : Timestamp.from(now.plus(delay)));
                        update.setString(2, error);
                        update.setString(3, jobId);
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
                        set status = ?, finished_at = ?, lease_owner = null, lease_expires_at = null,
                            next_attempt_at = null, error = ?
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
                rows.getString("error"),
                rows.getString("idempotency_key"),
                rows.getInt("replay_count"),
                instant(rows.getTimestamp("next_attempt_at")));
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

    private static void requireSamePayload(String key, Job existing, Map<String, Object> payload) {
        if (!Json.canonical(existing.payload()).equals(Json.canonical(payload))) {
            throw new IdempotencyConflictException(key, existing.id());
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is more useful.
        }
    }

    @Override
    public Optional<Job> replay(String jobId, int maxAttempts, int maxReplays) {
        if (maxAttempts < 1 || maxReplays < 1) {
            throw new IllegalArgumentException("maxAttempts and maxReplays must be positive");
        }
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                int currentReplay;
                try (PreparedStatement select = connection.prepareStatement("""
                        select replay_count from assay_job
                        where job_id = ? and status = 'FAILED' for update
                        """)) {
                    select.setString(1, jobId);
                    try (ResultSet rows = select.executeQuery()) {
                        if (!rows.next()) {
                            connection.commit();
                            return Optional.empty();
                        }
                        currentReplay = rows.getInt(1);
                    }
                }
                if (currentReplay >= maxReplays) {
                    connection.commit();
                    return Optional.empty();
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        update assay_job
                        set status = 'QUEUED', attempts = 0, max_attempts = ?,
                            replay_count = replay_count + 1, lease_owner = null, lease_expires_at = null,
                            started_at = null, finished_at = null, next_attempt_at = null, error = null
                        where job_id = ? and status = 'FAILED'
                        """)) {
                    update.setInt(1, maxAttempts);
                    update.setString(2, jobId);
                    update.executeUpdate();
                }
                connection.commit();
                return find(jobId);
            } catch (SQLException | RuntimeException error) {
                rollback(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot replay job", error);
        }
    }

    @Override
    public List<String> pruneJobs(Instant cutoff, int keepLatest, boolean dryRun) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }
        if (keepLatest < 0) {
            throw new IllegalArgumentException("keepLatest must be non-negative");
        }
        List<JobRow> rows = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement("""
                        select job_id, coalesce(finished_at, created_at) as ended_at
                        from assay_job
                        where status in ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        order by ended_at desc
                        """)) {
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    rows.add(new JobRow(result.getString("job_id"), instant(result.getTimestamp("ended_at"))));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot list jobs for pruning", error);
        }
        List<String> deleted = new ArrayList<>();
        for (int index = keepLatest; index < rows.size(); index++) {
            JobRow row = rows.get(index);
            if (row.endedAt() == null || !row.endedAt().isBefore(cutoff)) {
                continue;
            }
            deleted.add(row.jobId());
        }
        if (dryRun || deleted.isEmpty()) {
            return deleted;
        }
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("delete from assay_job where job_id = ?")) {
                for (String jobId : deleted) {
                    delete.setString(1, jobId);
                    delete.addBatch();
                }
                delete.executeBatch();
                connection.commit();
                return deleted;
            } catch (SQLException error) {
                rollback(connection);
                throw new IllegalStateException("cannot prune jobs", error);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot prune jobs", error);
        }
    }

    private record JobRow(String jobId, Instant endedAt) {
    }

    @Override
    public void close() {
        // Connections are opened per operation.
    }
}
