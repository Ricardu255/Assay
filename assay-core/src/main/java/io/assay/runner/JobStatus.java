package io.assay.runner;

/** Lifecycle of one queued evaluation job. */
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
