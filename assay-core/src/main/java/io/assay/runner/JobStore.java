package io.assay.runner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable job queue used by external workers.
 *
 * <p>Leases are deliberately separate from {@link io.assay.store.Results.RunLock}: a lease prevents
 * two workers from claiming one job, while the run lock prevents two processes from writing the same
 * evaluation run. Delivery is at-least-once; a completed run must be safe to resume.
 *
 * <p>Submissions may carry an idempotency key. Re-submitting the same key with the same payload
 * returns the existing job instead of enqueueing a second evaluation; reusing the key with a
 * different payload raises {@link IdempotencyConflictException}. A retryable failure is requeued
 * after {@link RetryPolicy} backoff, recorded in {@link Job#nextAttemptAt()}. A job that exhausts its
 * attempts remains terminal ({@link JobStatus#FAILED}); {@link #replay(String, int, int)} moves it
 * back to the queue while preserving the original failure and counting the replay.
 */
public interface JobStore extends AutoCloseable {

    /** Default page size for queue listings when the caller has no stronger requirement. */
    int DEFAULT_LIST_LIMIT = 200;

    /** Upper bound enforced by stores so a listing cannot scan an unbounded queue. */
    int MAX_LIST_LIMIT = 500;

    /** Submit without an idempotency key. */
    default Job submit(Map<String, Object> payload, int maxAttempts) {
        return submit(null, payload, maxAttempts);
    }

    /**
     * Submit a job, or return the existing job for the same non-blank idempotency key.
     * A null or blank key keeps the original non-idempotent behavior.
     *
     * @throws IdempotencyConflictException when the key already belongs to a different payload
     */
    Job submit(String idempotencyKey, Map<String, Object> payload, int maxAttempts);

    Optional<Job> claim(String workerId, Duration lease);

    /**
     * Extends the lease for a job owned by {@code workerId}.
     *
     * @return {@code true} when the lease was renewed; {@code false} when this worker no longer owns
     *     the job (it finished, was cancelled, or was reclaimed after the lease expired)
     * @throws RuntimeException when the store cannot be reached; callers should treat this as a
     *     transient failure and retry the heartbeat rather than assuming the lease was lost
     */
    boolean heartbeat(String jobId, String workerId, Duration lease);

    boolean cancel(String jobId);

    boolean isCancelRequested(String jobId);

    Optional<Job> find(String jobId);

    /**
     * Finds the job that owns a run id.
     *
     * <p>The default implementation scans the newest {@link #MAX_LIST_LIMIT} jobs and compares the
     * {@code run_id} payload field. Stores with an index should override this.
     */
    default Optional<Job> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        String wanted = runId.trim();
        for (Job job : list(MAX_LIST_LIMIT)) {
            Object value = job.payload() == null ? null : job.payload().get("run_id");
            if (value != null && wanted.equals(String.valueOf(value))) {
                return Optional.of(job);
            }
        }
        return Optional.empty();
    }

    /**
     * Rebuilds any derived run-id index and backfills legacy rows.
     *
     * @return the number of jobs indexed
     * @throws UnsupportedOperationException when the store has no derived index
     */
    default int rebuildRunIdIndex() {
        throw new UnsupportedOperationException("this job store does not support run id index rebuilds");
    }

    /** Lists jobs newest first, capped by the store at {@link #MAX_LIST_LIMIT}. */
    List<Job> list(int limit);

    void succeed(String jobId, String workerId);

    /** Retryable failure: requeue until max attempts is reached; then leave a terminal failure. */
    void fail(String jobId, String workerId, String error);

    /** Terminal failure; no retry. */
    void finish(String jobId, String workerId, JobStatus status, String error);

    /**
     * Move one terminal failed job back to the queue.
     *
     * @param maxAttempts attempts allowed for the replay run
     * @param maxReplays maximum number of replays for this job
     * @return the requeued job, or empty if it is not failed or the replay limit is reached
     */
    Optional<Job> replay(String jobId, int maxAttempts, int maxReplays);

    /**
     * Deletes terminal jobs older than the cutoff while always keeping the newest {@code keepLatest}.
     *
     * <p>Queued and running jobs are never deleted. A dry run returns the would-be-deleted job ids.
     */
    default List<String> pruneJobs(Instant cutoff, int keepLatest, boolean dryRun) {
        throw new UnsupportedOperationException("this job store does not support pruning");
    }

    @Override
    void close();
}
