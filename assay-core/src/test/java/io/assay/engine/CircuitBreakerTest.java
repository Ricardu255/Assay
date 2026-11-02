package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The breaker opens after consecutive failures and allows one probe after the cooldown. */
class CircuitBreakerTest {

    @Test
    void opensAfterTheFailureThresholdAndRejectsCalls() {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30));

        breaker.beforeCall();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());

        breaker.beforeCall();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertThrows(CircuitOpenException.class, breaker::beforeCall);
    }

    @Test
    void aSuccessfulProbeAfterCooldownClosesTheCircuit() {
        AtomicLong now = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(10), now::get);

        breaker.beforeCall();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        now.addAndGet(Duration.ofSeconds(11).toNanos());
        breaker.beforeCall();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertThrows(CircuitOpenException.class, breaker::beforeCall);

        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void aFailedProbeReopensTheCircuitAndSuccessResetsTheCount() {
        AtomicLong now = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(10), now::get);

        breaker.beforeCall();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        now.addAndGet(Duration.ofSeconds(11).toNanos());
        breaker.beforeCall();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        now.addAndGet(Duration.ofSeconds(11).toNanos());
        breaker.beforeCall();
        breaker.recordSuccess();
        breaker.beforeCall();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void aLateSuccessFromAnOldCallDoesNotCloseAnOpenCircuit() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30));

        breaker.beforeCall();
        breaker.beforeCall();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }

    @Test
    void disabledBreakerNeverRejects() {
        CircuitBreaker breaker = CircuitBreaker.disabled();

        for (int index = 0; index < 100; index++) {
            breaker.beforeCall();
            breaker.recordFailure();
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(!breaker.enabled());
    }
}
