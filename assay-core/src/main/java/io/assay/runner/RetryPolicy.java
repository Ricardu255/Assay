package io.assay.runner;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backoff applied when a job is requeued after a retryable failure.
 *
 * <p>The delay doubles per attempt and is capped. A symmetric jitter factor keeps several workers
 * from retrying the same provider at the same instant. A zero base delay means "retry immediately"
 * and is useful in tests.
 */
public record RetryPolicy(Duration baseDelay, Duration maxDelay, double jitter) {

    public RetryPolicy {
        if (baseDelay == null || maxDelay == null) {
            throw new IllegalArgumentException("retry delays must not be null");
        }
        if (baseDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("retry delays must be non-negative");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("max retry delay must be at least the base delay");
        }
        if (!Double.isFinite(jitter) || jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("retry jitter must be between 0 and 1");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(16), 0.2);
    }

    public static RetryPolicy none() {
        return new RetryPolicy(Duration.ZERO, Duration.ZERO, 0);
    }

    public boolean enabled() {
        return !baseDelay.isZero();
    }

    /**
     * Deterministic backoff total for {@code retries} retries, without jitter.
     *
     * <p>Used to derive the outer timeout budget for one case: every attempt can consume the full
     * per-attempt timeout, and the waits between attempts are bounded by this sum.
     */
    public Duration expectedDelay(int retries) {
        if (retries <= 0 || !enabled()) {
            return Duration.ZERO;
        }
        long totalMillis = 0;
        long delay = baseDelay.toMillis();
        long maxMillis = maxDelay.toMillis();
        int remaining = retries;
        while (remaining > 0 && delay < maxMillis) {
            totalMillis = saturatingAdd(totalMillis, delay);
            delay = delay > maxMillis / 2 ? maxMillis : delay * 2;
            remaining--;
        }
        if (remaining > 0) {
            totalMillis = saturatingAdd(totalMillis, saturatingMultiply(maxMillis, remaining));
        }
        return Duration.ofMillis(totalMillis);
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long value, int count) {
        return value != 0 && count > Long.MAX_VALUE / value ? Long.MAX_VALUE : value * count;
    }

    /**
     * Delay after the given attempt count.
     *
     * <p>{@code attempt} is the number of attempts already consumed, so the first failure waits one
     * base delay, the second two, and so on until the cap.
     */
    public Duration delayFor(int attempt) {
        if (!enabled()) {
            return Duration.ZERO;
        }
        double exponential = baseDelay.toMillis() * Math.pow(2, Math.max(0, attempt - 1));
        double capped = Math.min(maxDelay.toMillis(), exponential);
        double factor = jitter == 0 ? 1.0 : 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        long millis = Math.max(0, Math.round(capped * factor));
        return Duration.ofMillis(Math.min(maxDelay.toMillis(), millis));
    }
}
