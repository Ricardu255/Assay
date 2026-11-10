package io.assay.store.jdbc;

import io.assay.json.Json;
import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.support.Names;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Case result persistence for {@link JdbcResultStore}. */
final class JdbcCaseRepository {

    private final JdbcResultStore.ConnectionFactory connections;

    JdbcCaseRepository(JdbcResultStore.ConnectionFactory connections) {
        this.connections = connections;
    }

    boolean hasCase(String runId, String caseId) {
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

    void saveCase(CaseResult result) {
        saveCases(List.of(result));
    }

    void saveCases(List<CaseResult> results) {
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
                    Map<String, Object> payload = JdbcSupport.asMap(Json.normalize(result.toJson()));
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
                JdbcSupport.rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot save cases for run: " + runId, error);
        }
    }

    List<Map<String, Object>> listResults(String runId) {
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

    void addCaseCheck(String runId, String caseId, CheckResult check) {
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
                Map<String, Object> payload = JdbcSupport.asMap(Json.normalize(check.toJson()));
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
                    record.put("soft_warning_count", JdbcSupport.intValue(record.get("soft_warning_count")) + 1);
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "update assay_case set payload_json = ?, hard_pass = ?, soft_warning_count = ?, updated_at = ? "
                                + "where run_id = ? and case_id = ?")) {
                    update.setString(1, Json.write(record));
                    update.setBoolean(2, Boolean.TRUE.equals(record.get("hard_pass")));
                    update.setInt(3, JdbcSupport.intValue(record.get("soft_warning_count")));
                    update.setTimestamp(4, Timestamp.from(Instant.now()));
                    update.setString(5, safe);
                    update.setString(6, caseId);
                    update.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException error) {
                JdbcSupport.rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot add check to " + runId + "/" + caseId, error);
        }
    }

    Map<String, Object> findCase(String runId, String caseId) {
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
}
