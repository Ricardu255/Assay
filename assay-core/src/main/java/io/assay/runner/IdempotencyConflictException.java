package io.assay.runner;

/** Raised when an idempotency key is reused with a different job payload. */
public final class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;
    private final String existingJobId;

    public IdempotencyConflictException(String idempotencyKey, String existingJobId) {
        super("idempotency key '" + idempotencyKey + "' already belongs to job " + existingJobId
                + " with a different payload");
        this.idempotencyKey = idempotencyKey;
        this.existingJobId = existingJobId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String existingJobId() {
        return existingJobId;
    }
}
