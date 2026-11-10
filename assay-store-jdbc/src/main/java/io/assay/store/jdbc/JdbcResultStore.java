package io.assay.store.jdbc;

import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.store.Results;
import io.assay.support.Names;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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
 *
 * <p>Run, case, review, evolution and retention responsibilities are implemented by package-private
 * repository classes; this class owns the connection factory, schema bootstrap and delegation.
 */
public final class JdbcResultStore implements Results {

    /** Opens a connection; kept as a functional interface so callers can use a DataSource or URL. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    private final ConnectionFactory connections;
    private final JdbcRunRepository runs;
    private final JdbcCaseRepository cases;
    private final JdbcReviewRepository reviews;
    private final JdbcEvolutionRepository evolutions;
    private final JdbcRetention retention;

    public JdbcResultStore(DataSource dataSource) {
        this(dataSource::getConnection);
    }

    public JdbcResultStore(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
        JdbcConnectionFactory factory = this.connections::open;
        JdbcSchema.create(factory);
        this.runs = new JdbcRunRepository(factory);
        this.cases = new JdbcCaseRepository(factory);
        this.reviews = new JdbcReviewRepository(factory, this.cases);
        this.evolutions = new JdbcEvolutionRepository(factory);
        this.retention = new JdbcRetention(factory);
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
            JdbcSupport.closeQuietly(connection);
            throw new IllegalStateException("already running: evaluation run " + runId);
        } catch (SQLException error) {
            JdbcSupport.closeQuietly(connection);
            if ("23505".equals(error.getSQLState()) || "23000".equals(error.getSQLState())) {
                throw new IllegalStateException("already running: evaluation run " + runId);
            }
            throw new IllegalStateException("cannot acquire run lock: " + runId, error);
        }
    }

    @Override
    public void startRun(String runId, String adapter, String suite, String source, Map<String, Object> metadata) {
        runs.startRun(runId, adapter, suite, source, metadata);
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
        runs.startRun(runId, adapter, suite, source, metadata, resume, caseManifest);
    }

    @Override
    public void finishRun(String runId, String status) {
        runs.finishRun(runId, status);
    }

    @Override
    public boolean hasCase(String runId, String caseId) {
        return cases.hasCase(runId, caseId);
    }

    @Override
    public void saveCase(CaseResult result) {
        cases.saveCase(result);
    }

    @Override
    public void saveCases(List<CaseResult> results) {
        cases.saveCases(results);
    }

    @Override
    public List<Map<String, Object>> listResults(String runId) {
        return cases.listResults(runId);
    }

    @Override
    public List<Map<String, Object>> listRuns() {
        return runs.listRuns();
    }

    @Override
    public Map<String, Object> getRun(String runId) {
        return runs.getRun(runId);
    }

    @Override
    public void addCaseCheck(String runId, String caseId, CheckResult check) {
        cases.addCaseCheck(runId, caseId, check);
    }

    @Override
    public void saveReview(String runId, String caseId, String decision, String finalConclusion) {
        reviews.saveReview(runId, caseId, decision, finalConclusion);
    }

    @Override
    public List<Map<String, Object>> listReviewedResults(String decision) {
        return reviews.listReviewedResults(decision);
    }

    @Override
    public void saveEvolution(String experimentId, String candidateId, String decision, Map<String, Object> result) {
        evolutions.saveEvolution(experimentId, candidateId, decision, result);
    }

    @Override
    public Map<String, Object> getEvolution(String experimentId) {
        return evolutions.getEvolution(experimentId);
    }

    @Override
    public List<String> pruneRuns(Instant cutoff, int keepLatest, boolean dryRun) {
        return retention.pruneRuns(cutoff, keepLatest, dryRun);
    }

    @Override
    public void close() {
        // Connections are opened per operation; an injected DataSource owns its own lifecycle.
    }
}
