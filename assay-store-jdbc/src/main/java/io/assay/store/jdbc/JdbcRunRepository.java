package io.assay.store.jdbc;

import io.assay.json.Json;
import io.assay.support.Names;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Run lifecycle and run-record queries for {@link JdbcResultStore}. */
final class JdbcRunRepository {

    private final JdbcConnectionFactory connections;

    JdbcRunRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
    }

    void startRun(String runId, String adapter, String suite, String source, Map<String, Object> metadata) {
        startRun(runId, adapter, suite, source, metadata, false, null);
    }

    void startRun(
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
                            || !Json.normalize(withoutCaseCount(JdbcSupport.asMap(existing.get("metadata_json"))))
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
                JdbcSupport.rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot start run: " + runId, error);
        }
    }

    private void verifyResumeManifest(Connection connection, String runId, Map<String, String> caseManifest)
            throws SQLException {
        Map<String, String> stored = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "select case_id, content_sha256 from assay_case_manifest where run_id = ?")) {
            select.setString(1, runId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    stored.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        int savedCount;
        try (PreparedStatement count = connection.prepareStatement(
                "select count(*) from assay_case where run_id = ?")) {
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

    void finishRun(String runId, String status) {
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

    List<Map<String, Object>> listRuns() {
        List<Map<String, Object>> runs = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement("""
                        select run_id, adapter, suite, source, status, started_at, finished_at, metadata_json
                        from assay_run order by started_at desc
                        """);
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                runs.add(runRecord(rows));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot list runs", error);
        }
        return runs;
    }

    Map<String, Object> getRun(String runId) {
        String safe = Names.safeName(runId, "run_id");
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement("""
                        select run_id, adapter, suite, source, status, started_at, finished_at, metadata_json
                        from assay_run where run_id = ?
                        """)) {
            select.setString(1, safe);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? runRecord(rows) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot read run: " + runId, error);
        }
    }

    private static Map<String, Object> runRecord(ResultSet rows) throws SQLException {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", rows.getString(1));
        run.put("adapter", rows.getString(2));
        run.put("suite", rows.getString(3));
        run.put("source", rows.getString(4));
        run.put("status", rows.getString(5));
        Timestamp startedAt = rows.getTimestamp(6);
        run.put("started_at", startedAt == null ? null : startedAt.toInstant().toString());
        Timestamp finishedAt = rows.getTimestamp(7);
        run.put("finished_at", finishedAt == null ? null : finishedAt.toInstant().toString());
        run.put("metadata_json", Json.parseObject(rows.getString(8)));
        return run;
    }

    private static Map<String, Object> withoutCaseCount(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.remove("case_count");
        return result;
    }
}
