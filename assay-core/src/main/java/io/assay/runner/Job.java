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
        String error) {

    public Job {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
