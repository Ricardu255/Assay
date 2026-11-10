package io.assay.execution;

import io.assay.runner.JobStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a {@link RunSpec} to and from the payload of a durable queue job.
 *
 * <p>The payload is intentionally JSON-friendly: every value is a string or a boolean, so both the
 * file-backed and the JDBC-backed job stores can persist it without custom serialization.
 */
public final class RunJobs {

    private RunJobs() {
    }

    /** Builds the durable payload for a submitted run. */
    public static Map<String, Object> payload(RunSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "adapter", spec.adapterRef());
        putIfPresent(payload, "adapter_class", spec.adapterClass());
        putIfPresent(payload, "cases", spec.casesRef());
        putIfPresent(payload, "suite", spec.suite());
        putIfPresent(payload, "source", spec.source());
        putIfPresent(payload, "run_id", spec.runId());
        putIfPresent(payload, "run_identity", spec.runIdentity());
        payload.put("resume", spec.resume());
        return payload;
    }

    /** Rebuilds the spec stored in a claimed job. */
    public static RunSpec spec(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("job payload is missing");
        }
        return new RunSpec(
                text(payload, "adapter"),
                text(payload, "adapter_class"),
                text(payload, "cases"),
                text(payload, "suite"),
                text(payload, "source"),
                text(payload, "run_id"),
                text(payload, "run_identity"),
                Boolean.TRUE.equals(payload.get("resume")));
    }

    /** Returns the run id recorded in a job payload, or null when the payload has none. */
    public static String runId(Map<String, Object> payload) {
        return payload == null ? null : text(payload, "run_id");
    }

    /** Lower-case status name used by the HTTP API. */
    public static String status(JobStatus status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status) {
            case QUEUED -> "queued";
            case RUNNING -> "running";
            case SUCCEEDED -> "passed";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
