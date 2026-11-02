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

    boolean heartbeat(String jobId, String workerId, Duration lease);

    boolean cancel(String jobId);

    boolean isCancelRequested(String jobId);

    Optional<Job> find(String jobId);

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
