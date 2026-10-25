package io.assay.store.jdbc;

import io.assay.json.Json;
import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.store.Results;
import io.assay.support.Names;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * A JDBC-backed {@link Results} implementation.
 *
 * <p>The schema is created on construction. Records are stored as JSON text plus the few columns the
 * store must query (run identity, case id, pass/fail counters, review decision). The file store's
 * semantics are preserved: case results are upserts, the case manifest is hashed, resume validates
 * the manifest, and a run lock rejects a second holder.
 *
 * <p>This is deliberately a <em>portable</em> implementation, not a migration framework. It uses
 * {@code create table if not exists}, {@code text}, {@code boolean}, and {@code timestamp}; a
 * production deployment should own schema migrations and column sizing for its database.
 */
public final class JdbcResultStore implements Results {

    /** Opens a connection; kept as a functional interface so callers can use a DataSource or URL. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    private final ConnectionFactory connections;

    public JdbcResultStore(DataSource dataSource) {
        this(dataSource::getConnection);
    }

    public JdbcResultStore(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
        ensureSchema();
    }

    /** Opens a store from a JDBC URL, reading optional credentials from the environment. */
    public static JdbcResultStore fromUrl(String url) {
        if (url == null || !url.startsWith("jdbc:")) {
            throw new IllegalArgumentException("JDBC store requires a jdbc: URL");
        }
        Properties properties = new Properties();
        copyEnvironment(properties, "ASSAY_DB_USER", "user");
        copyEnvironment(properties, "ASSAY_DB_PASSWORD", "password");
        String normalized = normalizeUrl(url);
        return new JdbcResultStore(() -> DriverManager.getConnection(normalized, properties));
    }

    /**
     * H2 file databases close when the last connection is closed; reopening them in the same JVM can
     * make the database read-only unless the close delay is set. Keep the database alive for the
     * process so the per-operation connection model of this store stays usable.
     */
    private static String normalizeUrl(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("jdbc:h2:file:") || lower.contains("db_close_delay")) {
            return url;
        }
        return url + (url.endsWith(";") ? "" : ";") + "DB_CLOSE_DELAY=-1";
    }

    private static void copyEnvironment(Properties properties, String environment, String property) {
        String value = System.getenv(environment);
        if (value != null && !value.isBlank()) {
            properties.setProperty(property, value);
        }
    }

    private void ensureSchema() {
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists assay_run (
                      run_id varchar(128) primary key,
                      adapter varchar(256) not null,
                      suite varchar(128) not null,
                      source varchar(32) not null,
                      status varchar(32) not null,
                      started_at timestamp not null,
                      finished_at timestamp,
                      metadata_json text not null
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_case (
                      run_id varchar(128) not null,
                      case_id varchar(256) not null,
                      payload_json text not null,
                      hard_pass boolean not null,
                      soft_warning_count int not null,
                      updated_at timestamp not null,
                      primary key (run_id, case_id)
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_case_manifest (
                      run_id varchar(128) not null,
                      case_id varchar(256) not null,
                      content_sha256 varchar(64) not null,
                      primary key (run_id, case_id)
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_review (
                      id varchar(64) primary key,
                      run_id varchar(128) not null,
                      case_id varchar(256) not null,
                      decision varchar(64) not null,
                      final_conclusion text not null,
                      reviewed_at timestamp not null
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_evolution (
                      experiment_id varchar(128) primary key,
                      candidate_id varchar(256) not null,
                      decision varchar(32) not null,
                      result_json text not null,
                      created_at timestamp not null
                    )
                    """);
            statement.execute("""
                    create table if not exists assay_lock (
                      run_id varchar(128) primary key,
                      acquired_at timestamp not null
                    )
                    """);
        } catch (SQLException error) {
            throw new IllegalStateException("cannot initialize JDBC store schema", error);
        }
    }

    @Override
    public RunLock lockRun(String runId) {
        String safe = Names.safeName(runId, "run_id");
        Connection connection = null;
        try {
            connection = connections.open();
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into assay_lock(run_id, acquired_at) values (?, ?)")) {
                insert.setString(1, safe);
                insert.setTimestamp(2, Timestamp.from(Instant.now()));
                insert.executeUpdate();
            }
            connection.commit();
            return new JdbcRunLock(connection, safe);
        } catch (SQLIntegrityConstraintViolationException busy) {
            closeQuietly(connection);
            throw new IllegalStateException("already running: evaluation run " + runId);
        } catch (SQLException error) {
            closeQuietly(connection);
            if ("23505".equals(error.getSQLState()) || "23000".equals(error.getSQLState())) {
                throw new IllegalStateException("already running: evaluation run " + runId);
            }
            throw new IllegalStateException("cannot acquire run lock: " + runId, error);
        }
    }

    @Override
    public void startRun(String runId, String adapter, String suite, String source, Map<String, Object> metadata) {
        startRun(runId, adapter, suite, source, metadata, false, null);
    }

    @Override
    public void startRun(
            String runId,
            String adapter,
            String suite,
            String source,
            Map<String, Object> metadata,
            boolean resume,
            Map<String, String> caseManifest) {
        String safe = Names.safeName(runId, "run_id");
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> existing = loadRun(connection, safe);
                if (existing != null) {
                    if (!resume) {
                        throw new IllegalArgumentException("run already exists; use resume to continue: " + runId);
                    }
                    if (!Objects.equals(existing.get("adapter"), adapter)
                            || !Objects.equals(existing.get("suite"), suite)
                            || !Objects.equals(existing.get("source"), source)
                            || !Json.normalize(withoutCaseCount(asMap(existing.get("metadata_json"))))
                                    .equals(Json.normalize(withoutCaseCount(metadata)))) {
                        throw new IllegalArgumentException("resume run identity or execution configuration changed");
                    }
                    if (caseManifest != null) {
                        verifyResumeManifest(connection, safe, caseManifest);
                    }
                    updateRunState(connection, safe, "running", null, metadata);
                } else {
                    insertRun(connection, safe, adapter, suite, source, metadata);
                    if (caseManifest != null) {
                        writeManifest(connection, safe, caseManifest);
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot start run: " + runId, error);
        }
    }

    private void verifyResumeManifest(Connection connection, String runId, Map<String, String> caseManifest)
            throws SQLException {
        Map<String, String> stored = new LinkedHashMap<>();
        try (PreparedStatement select =
                connection.prepareStatement("select case_id, content_sha256 from assay_case_manifest where run_id = ?")) {
            select.setString(1, runId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    stored.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        int savedCount;
        try (PreparedStatement count = connection.prepareStatement("select count(*) from assay_case where run_id = ?")) {
            count.setString(1, runId);
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                savedCount = rows.getInt(1);
            }
        }
        if (stored.isEmpty() && savedCount > 0) {
            throw new IllegalArgumentException("cannot safely resume a legacy run without case identities");
        }
        List<String> missing = new ArrayList<>();
        for (String caseId : stored.keySet()) {
            if (!caseManifest.containsKey(caseId)) {
                missing.add(caseId);
            }
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            if (caseManifest.containsKey(entry.getKey())
                    && !caseManifest.get(entry.getKey()).equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        missing.sort(Comparator.naturalOrder());
        changed.sort(Comparator.naturalOrder());
        if (!missing.isEmpty() || !changed.isEmpty()) {
            List<String> details = new ArrayList<>();
            if (!missing.isEmpty()) {
                details.add("missing case_id: " + String.join(", ", missing));
            }
            if (!changed.isEmpty()) {
                details.add("changed case_id: " + String.join(", ", changed));
            }
            throw new IllegalArgumentException("resume dataset changed; " + String.join("; ", details));
        }
        Map<String, String> merged = new LinkedHashMap<>(stored);
        for (Map.Entry<String, String> entry : caseManifest.entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        writeManifest(connection, runId, merged);
    }

    private void insertRun(
            Connection connection,
            String runId,
            String adapter,
            String suite,
            String source,
            Map<String, Object> metadata)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into assay_run(run_id, adapter, suite, source, status, started_at, finished_at, metadata_json)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, runId);
            insert.setString(2, adapter);
            insert.setString(3, suite);
            insert.setString(4, source);
            insert.setString(5, "running");
            insert.setTimestamp(6, Timestamp.from(Instant.now()));
            insert.setTimestamp(7, null);
            insert.setString(8, Json.write(metadata));
            insert.executeUpdate();
        }
    }

    private void updateRunState(
            Connection connection, String runId, String status, Instant finishedAt, Map<String, Object> metadata)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "update assay_run set status = ?, finished_at = ?, metadata_json = ? where run_id = ?")) {
            update.setString(1, status);
            update.setTimestamp(2, finishedAt == null ? null : Timestamp.from(finishedAt));
            update.setString(3, Json.write(metadata));
            update.setString(4, runId);
            update.executeUpdate();
        }
    }

    private void writeManifest(Connection connection, String runId, Map<String, String> manifest)
            throws SQLException {
        try (PreparedStatement update =
                        connection.prepareStatement(
                                "update assay_case_manifest set content_sha256 = ? where run_id = ? and case_id = ?");
                PreparedStatement insert = connection.prepareStatement(
                        "insert into assay_case_manifest(run_id, case_id, content_sha256) values (?, ?, ?)")) {
            for (Map.Entry<String, String> entry : new TreeMap<>(manifest).entrySet()) {
                update.setString(1, entry.getValue());
                update.setString(2, runId);
                update.setString(3, entry.getKey());
                if (update.executeUpdate() == 0) {
                    insert.setString(1, runId);
                    insert.setString(2, entry.getKey());
                    insert.setString(3, entry.getValue());
                    insert.executeUpdate();
                }
            }
        }
    }

    private Map<String, Object> loadRun(Connection connection, String runId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "select adapter, suite, source, metadata_json from assay_run where run_id = ?")) {
            select.setString(1, runId);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("adapter", rows.getString(1));
                run.put("suite", rows.getString(2));
                run.put("source", rows.getString(3));
                run.put("metadata_json", Json.parseObject(rows.getString(4)));
                return run;
            }
        }
    }

    @Override
    public void finishRun(String runId, String status) {
        String safe = Names.safeName(runId, "run_id");
        try (Connection connection = connections.open();
                PreparedStatement update = connection.prepareStatement(
                        "update assay_run set status = ?, finished_at = ? where run_id = ?")) {
            update.setString(1, status);
            update.setTimestamp(2, Timestamp.from(Instant.now()));
            update.setString(3, safe);
            if (update.executeUpdate() == 0) {
                throw new IllegalArgumentException("run does not exist: " + runId);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot finish run: " + runId, error);
        }
    }

    @Override
    public boolean hasCase(String runId, String caseId) {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select 1 from assay_case where run_id = ? and case_id = ?")) {
            select.setString(1, Names.safeName(runId, "run_id"));
            select.setString(2, caseId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot read case: " + runId + "/" + caseId, error);
        }
    }

    @Override
    public void saveCase(CaseResult result) {
        saveCases(List.of(result));
    }

    @Override
    public void saveCases(List<CaseResult> results) {
        if (results.isEmpty()) {
            return;
        }
        String runId = Names.safeName(results.get(0).runId(), "run_id");
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                            "update assay_case set payload_json = ?, hard_pass = ?, soft_warning_count = ?, updated_at = ? "
                                    + "where run_id = ? and case_id = ?");
                    PreparedStatement insert = connection.prepareStatement(
                            "insert into assay_case(run_id, case_id, payload_json, hard_pass, soft_warning_count, updated_at) "
                                    + "values (?, ?, ?, ?, ?, ?)")) {
                for (CaseResult result : results) {
                    if (!Objects.equals(result.runId(), runId)) {
                        throw new IllegalArgumentException("a result batch must belong to one run");
                    }
                    Map<String, Object> payload = asMap(Json.normalize(result.toJson()));
                    payload.put("hard_pass", result.hardPass());
                    payload.put("soft_warning_count", result.softWarningCount());
                    String json = Json.write(payload);
                    Timestamp now = Timestamp.from(Instant.now());
                    update.setString(1, json);
                    update.setBoolean(2, result.hardPass());
                    update.setInt(3, result.softWarningCount());
                    update.setTimestamp(4, now);
                    update.setString(5, runId);
                    update.setString(6, result.evalCase().caseId());
                    if (update.executeUpdate() == 0) {
                        insert.setString(1, runId);
                        insert.setString(2, result.evalCase().caseId());
                        insert.setString(3, json);
                        insert.setBoolean(4, result.hardPass());
                        insert.setInt(5, result.softWarningCount());
                        insert.setTimestamp(6, now);
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot save cases for run: " + runId, error);
        }
    }

    @Override
    public List<Map<String, Object>> listResults(String runId) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select payload_json from assay_case where run_id = ? order by case_id")) {
            select.setString(1, Names.safeName(runId, "run_id"));
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    results.add(Json.parseObject(rows.getString(1)));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot list results for run: " + runId, error);
        }
        return results;
    }

    @Override
    public void addCaseCheck(String runId, String caseId, CheckResult check) {
        String safe = Names.safeName(runId, "run_id");
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> record;
                try (PreparedStatement select = connection.prepareStatement(
                        "select payload_json from assay_case where run_id = ? and case_id = ? for update")) {
                    select.setString(1, safe);
                    select.setString(2, caseId);
                    try (ResultSet rows = select.executeQuery()) {
                        if (!rows.next()) {
                            throw new IllegalArgumentException("cannot add a check to missing result: " + runId + "/" + caseId);
                        }
                        record = Json.parseObject(rows.getString(1));
                    }
                }
                Object rawChecks = record.get("checks");
                if (!(rawChecks instanceof List<?>)) {
                    throw new IllegalStateException("stored result has no checks array: " + runId + "/" + caseId);
                }
                List<Object> checks = new ArrayList<>((List<?>) rawChecks);
                Map<String, Object> payload = asMap(Json.normalize(check.toJson()));
                if (checks.contains(payload)) {
                    connection.commit();
                    return;
                }
                checks.add(payload);
                record = new LinkedHashMap<>(record);
                record.put("checks", checks);
                if ("hard".equals(check.level()) && !check.passed()) {
                    record.put("hard_pass", false);
                } else if (("soft".equals(check.level()) || "candidate".equals(check.level())) && !check.passed()) {
                    record.put("soft_warning_count", intValue(record.get("soft_warning_count")) + 1);
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "update assay_case set payload_json = ?, hard_pass = ?, soft_warning_count = ?, updated_at = ? "
                                + "where run_id = ? and case_id = ?")) {
                    update.setString(1, Json.write(record));
                    update.setBoolean(2, Boolean.TRUE.equals(record.get("hard_pass")));
                    update.setInt(3, intValue(record.get("soft_warning_count")));
                    update.setTimestamp(4, Timestamp.from(Instant.now()));
                    update.setString(5, safe);
                    update.setString(6, caseId);
                    update.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot add check to " + runId + "/" + caseId, error);
        }
    }

    @Override
    public void saveReview(String runId, String caseId, String decision, String finalConclusion) {
        String safe = Names.safeName(runId, "run_id");
        if (!hasCase(safe, caseId)) {
            throw new IllegalArgumentException("cannot review missing result: " + runId + "/" + caseId);
        }
        try (Connection connection = connections.open();
                PreparedStatement insert = connection.prepareStatement(
                        "insert into assay_review(id, run_id, case_id, decision, final_conclusion, reviewed_at) "
                                + "values (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, safe);
            insert.setString(3, caseId);
            insert.setString(4, decision);
            insert.setString(5, finalConclusion);
            insert.setTimestamp(6, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("cannot save review for " + runId + "/" + caseId, error);
        }
    }

    @Override
    public List<Map<String, Object>> listReviewedResults(String decision) {
        List<Map<String, Object>> reviews = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select run_id, case_id, decision, final_conclusion, reviewed_at from assay_review "
                                + "order by reviewed_at, case_id")) {
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> review = new LinkedHashMap<>();
                    review.put("run_id", rows.getString(1));
                    review.put("case_id", rows.getString(2));
                    review.put("decision", rows.getString(3));
                    review.put("final_conclusion", rows.getString(4));
                    review.put("reviewed_at", rows.getTimestamp(5).toInstant().toString());
                    reviews.add(review);
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot list reviews", error);
        }
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> review : reviews) {
            latest.put(review.get("run_id") + "\0" + review.get("case_id"), review);
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> review : latest.values()) {
            if (Objects.equals(review.get("decision"), decision)) {
                selected.add(review);
            }
        }
        selected.sort(Comparator
                .comparing((Map<String, Object> review) -> String.valueOf(review.get("reviewed_at")))
                .thenComparing(review -> String.valueOf(review.get("case_id"))));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> review : selected) {
            String runId = String.valueOf(review.get("run_id"));
            String caseId = String.valueOf(review.get("case_id"));
            Map<String, Object> record = findCase(runId, caseId);
            if (record == null) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(record);
            copy.put("human_final_conclusion", review.get("final_conclusion"));
            results.add(copy);
        }
        return results;
    }

    private Map<String, Object> findCase(String runId, String caseId) {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select payload_json from assay_case where run_id = ? and case_id = ?")) {
            select.setString(1, runId);
            select.setString(2, caseId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Json.parseObject(rows.getString(1)) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot read case: " + runId + "/" + caseId, error);
        }
    }

    @Override
    public void saveEvolution(String experimentId, String candidateId, String decision, Map<String, Object> result) {
        String safe = Names.safeName(experimentId, "experiment_id");
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement(
                        "update assay_evolution set candidate_id = ?, decision = ?, result_json = ?, created_at = ? "
                                + "where experiment_id = ?")) {
                    update.setString(1, candidateId);
                    update.setString(2, decision);
                    update.setString(3, Json.write(result));
                    update.setTimestamp(4, Timestamp.from(Instant.now()));
                    update.setString(5, safe);
                    if (update.executeUpdate() == 0) {
                        try (PreparedStatement insert = connection.prepareStatement(
                                "insert into assay_evolution(experiment_id, candidate_id, decision, result_json, created_at) "
                                        + "values (?, ?, ?, ?, ?)")) {
                            insert.setString(1, safe);
                            insert.setString(2, candidateId);
                            insert.setString(3, decision);
                            insert.setString(4, Json.write(result));
                            insert.setTimestamp(5, Timestamp.from(Instant.now()));
                            insert.executeUpdate();
                        }
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot save evolution: " + experimentId, error);
        }
    }

    @Override
    public Map<String, Object> getEvolution(String experimentId) {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "select result_json from assay_evolution where experiment_id = ?")) {
            select.setString(1, Names.safeName(experimentId, "experiment_id"));
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Json.parseObject(rows.getString(1)) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot read evolution: " + experimentId, error);
        }
    }

    @Override
    public void close() {
        // Connections are opened per operation; an injected DataSource owns its own lifecycle.
    }

    private final class JdbcRunLock implements RunLock {
        private final Connection connection;
        private final String runId;
        private boolean closed;

        private JdbcRunLock(Connection connection, String runId) {
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
                closeQuietly(connection);
            }
        }
    }

    private static Map<String, Object> withoutCaseCount(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.remove("case_count");
        return result;
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is more useful than a rollback failure.
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful to do while closing.
        }
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }
}
