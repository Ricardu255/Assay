package io.assay.runner;

import java.time.Instant;
import java.util.Map;

/** One durable evaluation job and its current lease state. */
public record Job(
        String id,
        JobStatus status,
        Map<String, Object> payload,
        int attempts,
        int maxAttempts,
        String leaseOwner,
        Instant leaseExpiresAt,
        boolean cancelRequested,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String error,
        String idempotencyKey,
        int replayCount,
        Instant nextAttemptAt) {

    public Job {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        replayCount = Math.max(0, replayCount);
    }

    /** Compatibility constructor for callers that do not use idempotency, replay, or backoff. */
    public Job(String id, JobStatus status, Map<String, Object> payload, int attempts, int maxAttempts,
               String leaseOwner, Instant leaseExpiresAt, boolean cancelRequested, Instant createdAt,
               Instant startedAt, Instant finishedAt, String error) {
        this(id, status, payload, attempts, maxAttempts, leaseOwner, leaseExpiresAt, cancelRequested,
                createdAt, startedAt, finishedAt, error, null, 0, null);
    }

    /** Compatibility constructor for callers that predate scheduled retries. */
    public Job(String id, JobStatus status, Map<String, Object> payload, int attempts, int maxAttempts,
               String leaseOwner, Instant leaseExpiresAt, boolean cancelRequested, Instant createdAt,
               Instant startedAt, Instant finishedAt, String error, String idempotencyKey, int replayCount) {
        this(id, status, payload, attempts, maxAttempts, leaseOwner, leaseExpiresAt, cancelRequested,
                createdAt, startedAt, finishedAt, error, idempotencyKey, replayCount, null);
    }
}
