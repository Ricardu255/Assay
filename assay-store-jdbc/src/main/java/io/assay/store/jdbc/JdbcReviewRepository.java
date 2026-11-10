package io.assay.store.jdbc;

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
import java.util.UUID;

/** Human review persistence for {@link JdbcResultStore}. */
final class JdbcReviewRepository {

    private final JdbcResultStore.ConnectionFactory connections;
    private final JdbcCaseRepository cases;

    JdbcReviewRepository(JdbcResultStore.ConnectionFactory connections, JdbcCaseRepository cases) {
        this.connections = connections;
        this.cases = cases;
    }

    void saveReview(String runId, String caseId, String decision, String finalConclusion) {
        String safe = Names.safeName(runId, "run_id");
        if (!cases.hasCase(safe, caseId)) {
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

    List<Map<String, Object>> listReviewedResults(String decision) {
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
            Map<String, Object> record = cases.findCase(runId, caseId);
            if (record == null) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(record);
            copy.put("human_final_conclusion", review.get("final_conclusion"));
            results.add(copy);
        }
        return results;
    }
}
