package io.assay.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.time.Duration;

/** Backoff grows exponentially, caps at the configured maximum, and never goes negative. */
class RetryPolicyTest {

    @Test
    void doublesPerAttemptAndCaps() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(5), 0);

        assertEquals(Duration.ofSeconds(1), policy.delayFor(1));
        assertEquals(Duration.ofSeconds(2), policy.delayFor(2));
        assertEquals(Duration.ofSeconds(4), policy.delayFor(3));
        assertEquals(Duration.ofSeconds(5), policy.delayFor(4));
        assertEquals(Duration.ofSeconds(5), policy.delayFor(50));
    }

    @Test
    void noneRetriesImmediately() {
        assertEquals(Duration.ZERO, RetryPolicy.none().delayFor(1));
        assertEquals(Duration.ZERO, RetryPolicy.none().delayFor(5));
    }

    @Test
    void jitterStaysWithinTheConfiguredBand() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(10), Duration.ofSeconds(10), 0.5);
        for (int index = 0; index < 100; index++) {
            Duration delay = policy.delayFor(1);
            assertTrue(delay.compareTo(Duration.ofSeconds(5)) >= 0);
            assertTrue(delay.compareTo(Duration.ofSeconds(10)) <= 0);
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 1.5));
    }
}
