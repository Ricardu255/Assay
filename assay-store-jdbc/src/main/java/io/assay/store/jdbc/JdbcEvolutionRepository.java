package io.assay.store.jdbc;

import io.assay.json.Json;
import io.assay.support.Names;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/** Evolution result persistence for {@link JdbcResultStore}. */
final class JdbcEvolutionRepository {

    private final JdbcResultStore.ConnectionFactory connections;

    JdbcEvolutionRepository(JdbcResultStore.ConnectionFactory connections) {
        this.connections = connections;
    }

    void saveEvolution(String experimentId, String candidateId, String decision, Map<String, Object> result) {
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
                JdbcSupport.rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("cannot save evolution: " + experimentId, error);
        }
    }

    Map<String, Object> getEvolution(String experimentId) {
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
}
