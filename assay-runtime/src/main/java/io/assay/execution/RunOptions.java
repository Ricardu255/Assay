package io.assay.execution;

/** Execution knobs shared by every caller of {@link SuiteRunner}. */
public record RunOptions(int workers, int retries, double timeoutSeconds, boolean collectFewShot) {

    public RunOptions {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be at least 1");
        }
        if (retries < 0) {
            throw new IllegalArgumentException("retries must not be negative");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeout_seconds must be positive");
        }
    }

    /** The single-worker defaults used by the CLI and the server. */
    public static RunOptions defaults() {
        return new RunOptions(1, 0, 30.0, false);
    }
}
