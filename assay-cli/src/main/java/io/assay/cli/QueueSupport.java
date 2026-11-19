package io.assay.cli;

import io.assay.bootstrap.Stores;
import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Shared helpers for the durable-queue command family. */
final class QueueSupport {

    private QueueSupport() {
    }

    static JobStore jobStore(CommandLine.Namespace args) {
        String reference = args.string("store");
        if (reference == null || reference.isBlank()) {
            throw new CommandLine.UsageException("queue commands require --store", 2, false);
        }
        return Stores.openJobs(reference, retryPolicy(args));
    }

    static RetryPolicy retryPolicy(CommandLine.Namespace args) {
        Double base = args.decimalOrNull("retry-base-seconds");
        Double max = args.decimalOrNull("retry-max-seconds");
        Double jitter = args.decimalOrNull("retry-jitter");
        if (base == null && max == null && jitter == null) {
            return RetryPolicy.defaults();
        }
        return new RetryPolicy(
                Duration.ofMillis(Math.round((base == null ? 1 : base) * 1000)),
                Duration.ofMillis(Math.round((max == null ? 16 : max) * 1000)),
                jitter == null ? 0.2 : jitter);
    }

    static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static String message(RuntimeException error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    static void addOption(
            List<String> args, Map<String, Object> payload, String key, String option) {
        Object value = payload.get(key);
        if (value != null) {
            args.add(option);
            args.add(String.valueOf(value));
        }
    }

    static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("job payload is missing " + key);
        }
        return String.valueOf(value);
    }

    static int integer(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number
                ? number.intValue()
                : Integer.parseInt(String.valueOf(value));
    }

    static double decimal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number
                ? number.doubleValue()
                : Double.parseDouble(String.valueOf(value));
    }
}
