package io.assay.runner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable job queue used by external workers.
 *
 * <p>Leases are deliberately separate from {@link io.assay.store.Results.RunLock}: a lease prevents
 * two workers from claiming one job, while the run lock prevents two processes from writing the same
 * evaluation run. Delivery is at-least-once; a completed run must be safe to resume.
 */
public interface JobStore extends AutoCloseable {

    Job submit(Map<String, Object> payload, int maxAttempts);

    Optional<Job> claim(String workerId, Duration lease);

    boolean heartbeat(String jobId, String workerId, Duration lease);

    boolean cancel(String jobId);

    boolean isCancelRequested(String jobId);

    Optional<Job> find(String jobId);

    List<Job> list(int limit);

    void succeed(String jobId, String workerId);

    /** Retryable failure: requeue until max attempts is reached. */
    void fail(String jobId, String workerId, String error);

    /** Terminal failure; no retry. */
    void finish(String jobId, String workerId, JobStatus status, String error);

    @Override
    void close();
}
