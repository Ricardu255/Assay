package io.assay.store;

import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The persistence surface the evaluation, evolution, and CLI layers depend on.
 *
 * <p>Everything downstream only needs these methods, so a different backend — a JDBC store, an object
 * store, an in-memory double used by tests — can be dropped in without touching the engine. {@link
 * ResultStore} is the default implementation.
 */
public interface Results extends AutoCloseable {

    /** A held run lock; closing it releases the lock and its underlying resource. */
    interface RunLock extends AutoCloseable {
        @Override
        void close();
    }

    /** Holds the run's cross-process lock for the duration of a suite execution. */
    RunLock lockRun(String runId);

    void startRun(String runId, String adapter, String suite, String source, Map<String, Object> metadata);

    void startRun(
            String runId,
            String adapter,
            String suite,
            String source,
            Map<String, Object> metadata,
            boolean resume,
            Map<String, String> caseManifest);

    void finishRun(String runId, String status);

    boolean hasCase(String runId, String caseId);

    void saveCase(CaseResult result);

    void saveCases(List<CaseResult> results);

    List<Map<String, Object>> listResults(String runId);

    /**
     * Lists the persisted runs, newest first.
     *
     * <p>Backends that cannot enumerate runs keep this default; the server falls back to the runs
     * it started in this process. Implementations should return the same record shape the engine
     * persists: run_id, adapter, suite, source, status, started_at, finished_at, metadata_json.
     */
    default List<Map<String, Object>> listRuns() {
        throw new UnsupportedOperationException("this result store does not support listing runs");
    }

    /** Returns one persisted run record, or {@code null} when the run does not exist. */
    default Map<String, Object> getRun(String runId) {
        throw new UnsupportedOperationException("this result store does not support reading runs");
    }

    void addCaseCheck(String runId, String caseId, CheckResult check);

    void saveReview(String runId, String caseId, String decision, String finalConclusion);

    List<Map<String, Object>> listReviewedResults(String decision);

    void saveEvolution(String experimentId, String candidateId, String decision, Map<String, Object> result);

    Map<String, Object> getEvolution(String experimentId);

    /**
     * Deletes finished runs older than the cutoff while always keeping the newest {@code keepLatest}.
     *
     * <p>Running runs are never deleted. A dry run returns the would-be-deleted run ids without
     * touching storage, which lets an operator review the list before a destructive cleanup.
     *
     * @return run ids that were deleted, or would be deleted in dry-run mode
     */
    default List<String> pruneRuns(Instant cutoff, int keepLatest, boolean dryRun) {
        throw new UnsupportedOperationException("this result store does not support pruning");
    }

    @Override
    void close();
}
