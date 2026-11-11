package io.assay.engine;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A small, process-local circuit breaker for calls to an external Agent or model.
 *
 * <p>It intentionally stays in the evaluation core and has no dependency on a resilience library.
 * The breaker is shared by concurrent cases, so all transitions are synchronized.
 */
public final class CircuitBreaker {

    /** Breaker state exposed for tests and listeners. */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final boolean enabled;
    private final int failureThreshold;
    private final long openDurationNanos;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openUntilNanos;
    private boolean probeInFlight;

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this(failureThreshold, openDuration, System::nanoTime);
    }

    CircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier clock) {
        if (failureThreshold < 0) {
            throw new IllegalArgumentException("failure threshold must be non-negative");
        }
        if (openDuration == null || openDuration.isNegative()) {
            throw new IllegalArgumentException("open duration must be non-negative");
        }
        this.enabled = failureThreshold > 0;
        this.failureThreshold = failureThreshold;
        this.openDurationNanos = openDuration.toNanos();
        this.clock = clock == null ? System::nanoTime : clock;
    }

    public static CircuitBreaker disabled() {
        return new CircuitBreaker(0, Duration.ZERO);
    }

    public boolean enabled() {
        return enabled;
    }

    /** Throws when the circuit is open, or when a half-open probe is already in flight. */
    public synchronized void beforeCall() {
        if (!enabled) {
            return;
        }
        long now = clock.getAsLong();
        if (state == State.OPEN) {
            if (now < openUntilNanos) {
                throw new CircuitOpenException("circuit is open; retry after the cooldown");
            }
            state = State.HALF_OPEN;
            probeInFlight = false;
        }
        if (state == State.HALF_OPEN) {
            if (probeInFlight) {
                throw new CircuitOpenException("circuit is half-open; a probe is already in flight");
            }
            probeInFlight = true;
        }
    }

    public synchronized void recordSuccess() {
        if (!enabled) {
            return;
        }
        // A late success from a call that started before the circuit opened must not close the new
        // generation; only a HALF_OPEN probe may move OPEN back to CLOSED.
        if (state == State.OPEN) {
            return;
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    public synchronized void recordFailure() {
        if (!enabled) {
            return;
        }
        probeInFlight = false;
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    public synchronized State state() {
        return enabled ? state : State.CLOSED;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("state", state().name());
        result.put("failure_threshold", failureThreshold);
        result.put("consecutive_failures", consecutiveFailures);
        return result;
    }

    private void open() {
        state = State.OPEN;
        openUntilNanos = clock.getAsLong() + openDurationNanos;
    }
}
