package io.assay.store.jdbc;

import io.assay.json.Json;
import io.assay.runner.IdempotencyConflictException;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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

    private final JdbcConnectionFactory connections;
    private final RetryPolicy retryPolicy;

    public JdbcJobStore(DataSource dataSource) {
        this(dataSource::getConnection, RetryPolicy.defaults());
    }

    public JdbcJobStore(DataSource dataSource, RetryPolicy retryPolicy) {
        this(dataSource::getConnection, retryPolicy);
    }

    private JdbcJobStore(JdbcConnectionFactory connections, RetryPolicy retryPolicy) {
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
                      run_id varchar(128),
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
            // Metadata checks keep the DDL valid on H2, PostgreSQL and MySQL (which rejects
            // ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS).
            ensureColumn(connection, statement, "idempotency_key", "varchar(128)");
            ensureColumn(connection, statement, "replay_count", "int not null default 0");
            ensureColumn(connection, statement, "next_attempt_at", "timestamp");
            ensureColumn(connection, statement, "run_id", "varchar(128)");
            ensureIndex(connection, statement, "assay_job_idempotency", true, "idempotency_key");
            ensureIndex(connection, statement, "assay_job_run_id", false, "run_id");
        } catch (SQLException error) {
            throw new IllegalStateException("cannot initialize job store", error);
        }
    }

    private static void ensureColumn(
            Connection connection, Statement statement, String column, String definition)
            throws SQLException {
        if (hasColumn(connection, column)) {
            return;
        }
        statement.execute("alter table assay_job add column " + column + " " + definition);
    }

    private static boolean hasColumn(Connection connection, String column) throws SQLException {
        try (Statement probe = connection.createStatement();
                ResultSet rows = probe.executeQuery("select * from assay_job where 1 = 0")) {
            ResultSetMetaData meta = rows.getMetaData();
            for (int index = 1; index <= meta.getColumnCount(); index++) {
                if (column.equalsIgnoreCase(meta.getColumnName(index))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void ensureIndex(
            Connection connection, Statement statement, String index, boolean unique, String column)
            throws SQLException {
        if (hasIndex(connection, index)) {
            return;
        }
        statement.execute("create " + (unique ? "unique " : "") + "index " + index
                + " on assay_job(" + column + ")");
    }

    private static boolean hasIndex(Connection connection, String index) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String table : List.of("assay_job", "ASSAY_JOB")) {
            try (ResultSet rows = meta.getIndexInfo(
                    connection.getCatalog(), connection.getSchema(), table, false, false)) {
                while (rows.next()) {
                    if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        Object runId = payload.get("run_id");
        try (Connection connection = connections.open();
                PreparedStatement insert = connection.prepareStatement("""
                        insert into assay_job(job_id, status, payload_json, run_id, attempts, max_attempts,
                          lease_owner, lease_expires_at, cancel_requested, created_at, idempotency_key, replay_count)
                        values (?, 'QUEUED', ?, ?, 0, ?, null, null, false, ?, ?, 0)
                        """)) {
            insert.setString(1, id);
            insert.setString(2, Json.write(payload));
            insert.setString(3, runId == null ? null : String.valueOf(runId));
            insert.setInt(4, maxAttempts);
            insert.setTimestamp(5, Timestamp.from(now));
            insert.setString(6, key);
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

    @Override
    public Optional<Job> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select * from assay_job where run_id = ? order by created_at desc limit 1")) {
            select.setString(1, runId.trim());
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(job(rows));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot find job by run id", error);
        }
        // Rows created before the run_id column existed have no indexed value.
        return JobStore.super.findByRunId(runId);
    }

    @Override
    public int rebuildRunIdIndex() {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select job_id, payload_json from assay_job where run_id is null")) {
            List<String[]> pending = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> payload = Json.parseObject(rows.getString(2));
                    Object runId = payload.get("run_id");
                    if (runId != null) {
                        pending.add(new String[] {rows.getString(1), String.valueOf(runId)});
                    }
                }
            }
            if (pending.isEmpty()) {
                return 0;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "update assay_job set run_id = ? where job_id = ?")) {
                for (String[] pair : pending) {
                    update.setString(1, pair[1]);
                    update.setString(2, pair[0]);
                    update.addBatch();
                }
                int indexed = 0;
                for (int result : update.executeBatch()) {
                    if (result > 0 || result == Statement.SUCCESS_NO_INFO) {
                        indexed++;
                    }
                }
                return indexed;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot rebuild job run id index", error);
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
                JdbcSupport.rollbackQuietly(connection);
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
                JdbcSupport.rollbackQuietly(connection);
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
        int max = Math.max(1, Math.min(MAX_LIST_LIMIT, limit));
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select * from assay_job order by created_at desc limit ?")) {
            select.setInt(1, max);
            try (ResultSet rows = select.executeQuery()) {
                List<Job> jobs = new ArrayList<>();
                while (rows.next()) {
                    jobs.add(job(rows));
                }
                return jobs;
            }
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
                        ? """
                        update assay_job set status = 'QUEUED', lease_owner = null, lease_expires_at = null,
                          next_attempt_at = ?, error = ? where job_id = ?
                        """
                        : """
                        update assay_job set status = 'FAILED', finished_at = ?, lease_owner = null,
                          lease_expires_at = null, next_attempt_at = null, error = ?
                        where job_id = ?
                        """)) {
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
                JdbcSupport.rollbackQuietly(connection);
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
                JdbcSupport.instant(rows.getTimestamp("lease_expires_at")),
                rows.getBoolean("cancel_requested"),
                JdbcSupport.instant(rows.getTimestamp("created_at")),
                JdbcSupport.instant(rows.getTimestamp("started_at")),
                JdbcSupport.instant(rows.getTimestamp("finished_at")),
                rows.getString("error"),
                rows.getString("idempotency_key"),
                rows.getInt("replay_count"),
                JdbcSupport.instant(rows.getTimestamp("next_attempt_at")));
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
                JdbcSupport.rollbackQuietly(connection);
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
                    rows.add(new JobRow(
                            result.getString("job_id"),
                            JdbcSupport.instant(result.getTimestamp("ended_at"))));
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
                JdbcSupport.rollbackQuietly(connection);
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
