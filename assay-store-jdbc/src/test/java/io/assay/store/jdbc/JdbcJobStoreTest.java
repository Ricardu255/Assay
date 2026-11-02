package io.assay.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.runner.IdempotencyConflictException;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcJobStoreTest {

    @Test
    void claimIsAtomicAndHeartbeatExtendsTheLease() {
        try (JdbcJobStore store = store()) {
            Job submitted = store.submit(Map.of("suite", "smoke"), 2);
            Optional<Job> claimed = store.claim("worker-a", Duration.ofSeconds(30));

            assertTrue(claimed.isPresent());
            assertEquals(submitted.id(), claimed.get().id());
            assertEquals(JobStatus.RUNNING, claimed.get().status());
            assertEquals(1, claimed.get().attempts());
            assertTrue(store.heartbeat(submitted.id(), "worker-a", Duration.ofSeconds(30)));
            assertFalse(store.heartbeat(submitted.id(), "worker-b", Duration.ofSeconds(30)));

            store.succeed(submitted.id(), "worker-a");
            assertEquals(JobStatus.SUCCEEDED, store.find(submitted.id()).orElseThrow().status());
        }
    }

    @Test
    void expiredLeaseIsRequeuedAndCanBeClaimedAgain() throws Exception {
        try (JdbcJobStore store = store()) {
            Job job = store.submit(Map.of("suite", "smoke"), 2);
            assertTrue(store.claim("worker-a", Duration.ofMillis(50)).isPresent());
            Thread.sleep(120);

            Optional<Job> reclaimed = store.claim("worker-b", Duration.ofSeconds(30));
            assertTrue(reclaimed.isPresent());
            assertEquals(2, reclaimed.get().attempts());
            assertEquals("worker-b", reclaimed.get().leaseOwner());
            assertEquals(JobStatus.RUNNING, store.find(job.id()).orElseThrow().status());
        }
    }

    @Test
    void expiredLeaseAtMaxAttemptsBecomesFailed() throws Exception {
        try (JdbcJobStore store = store()) {
            Job job = store.submit(Map.of("suite", "smoke"), 1);
            assertTrue(store.claim("worker-a", Duration.ofMillis(50)).isPresent());
            Thread.sleep(120);

            assertFalse(store.claim("worker-b", Duration.ofSeconds(30)).isPresent());
            assertEquals(JobStatus.FAILED, store.find(job.id()).orElseThrow().status());
        }
    }

    @Test
    void queuedCancelIsTerminalAndRunningCancelIsCooperative() {
        try (JdbcJobStore store = store()) {
            Job queued = store.submit(Map.of("suite", "smoke"), 1);
            assertTrue(store.cancel(queued.id()));
            assertEquals(JobStatus.CANCELLED, store.find(queued.id()).orElseThrow().status());
            assertFalse(store.claim("worker-a", Duration.ofSeconds(30)).isPresent());

            Job running = store.submit(Map.of("suite", "smoke"), 2);
            assertTrue(store.claim("worker-a", Duration.ofSeconds(30)).isPresent());
            assertTrue(store.cancel(running.id()));
            assertTrue(store.isCancelRequested(running.id()));
            store.finish(running.id(), "worker-a", JobStatus.CANCELLED, "cancelled by user");
            assertEquals(JobStatus.CANCELLED, store.find(running.id()).orElseThrow().status());
        }
    }

    @Test
    void retryableFailureRequeuesUntilMaxAttempts() {
        try (JdbcJobStore store = store()) {
            Job job = store.submit(Map.of("suite", "smoke"), 2);
            assertTrue(store.claim("worker-a", Duration.ofSeconds(30)).isPresent());
            store.fail(job.id(), "worker-a", "adapter timeout");
            assertEquals(JobStatus.QUEUED, store.find(job.id()).orElseThrow().status());

            assertTrue(store.claim("worker-b", Duration.ofSeconds(30)).isPresent());
            store.fail(job.id(), "worker-b", "adapter timeout");
            assertEquals(JobStatus.FAILED, store.find(job.id()).orElseThrow().status());
        }
    }

    @Test
    void retryableFailureSchedulesABackoffBeforeTheNextClaim() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
        try (JdbcJobStore store = JdbcJobStore.fromUrl(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", policy)) {
            Job job = store.submit(Map.of("suite", "smoke"), 2);
            assertTrue(store.claim("worker-a", Duration.ofSeconds(30)).isPresent());
            store.fail(job.id(), "worker-a", "adapter timeout");

            Job queued = store.find(job.id()).orElseThrow();
            assertEquals(JobStatus.QUEUED, queued.status());
            assertNotNull(queued.nextAttemptAt());
            assertTrue(queued.nextAttemptAt().isAfter(Instant.now()));
            assertFalse(store.claim("worker-b", Duration.ofSeconds(30)).isPresent());
        }
    }

    @Test
    void prunesOldTerminalJobsButKeepsQueuedWork() {
        try (JdbcJobStore store = store()) {
            for (int index = 1; index <= 3; index++) {
                Job job = store.submit(Map.of("suite", "suite-" + index), 1);
                assertTrue(store.claim("worker", Duration.ofSeconds(30)).isPresent());
                store.succeed(job.id(), "worker");
            }
            Job queued = store.submit(Map.of("suite", "queued"), 1);

            assertEquals(2, store.pruneJobs(Instant.now().plusSeconds(60), 1, false).size());
            assertEquals(2, store.list(10).size());
            assertTrue(store.find(queued.id()).isPresent());
        }
    }

    @Test
    void idempotentSubmitRejectsADifferentPayload() {
        try (JobStore store = store()) {
            Job first = store.submit("checkout-42", Map.of("suite", "smoke", "cases", "a"), 2);

            IdempotencyConflictException error = assertThrows(
                    IdempotencyConflictException.class,
                    () -> store.submit("checkout-42", Map.of("suite", "smoke", "cases", "b"), 2));

            assertEquals("checkout-42", error.idempotencyKey());
            assertEquals(first.id(), error.existingJobId());
            assertEquals(1, store.list(10).size());
        }
    }

    private static JdbcJobStore store() {
        return JdbcJobStore.fromUrl(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", RetryPolicy.none());
    }

    @Test
    void idempotentSubmitReturnsTheExistingJob() {
        try (JobStore store = store()) {
            Job first = store.submit("checkout-42", Map.of("suite", "smoke"), 2);
            Job second = store.submit("checkout-42", Map.of("suite", "smoke"), 2);

            assertEquals(first.id(), second.id());
            assertEquals(1, store.list(10).size());
            assertEquals("checkout-42", second.idempotencyKey());
        }
    }

    @Test
    void failedJobCanBeReplayedUpToTheLimit() {
        try (JobStore store = store()) {
            Job job = store.submit("replay-key", Map.of("suite", "smoke"), 1);
            assertTrue(store.claim("worker-a", Duration.ofSeconds(30)).isPresent());
            store.fail(job.id(), "worker-a", "adapter timeout");
            assertEquals(JobStatus.FAILED, store.find(job.id()).orElseThrow().status());

            Job firstReplay = store.replay(job.id(), 1, 2).orElseThrow();
            assertEquals(JobStatus.QUEUED, firstReplay.status());
            assertEquals(0, firstReplay.attempts());
            assertEquals(1, firstReplay.replayCount());
            assertEquals("replay-key", firstReplay.idempotencyKey());

            assertTrue(store.claim("worker-b", Duration.ofSeconds(30)).isPresent());
            store.fail(job.id(), "worker-b", "adapter timeout again");
            Job secondReplay = store.replay(job.id(), 1, 2).orElseThrow();
            assertEquals(2, secondReplay.replayCount());

            assertTrue(store.claim("worker-c", Duration.ofSeconds(30)).isPresent());
            store.fail(job.id(), "worker-c", "still failing");
            assertTrue(store.replay(job.id(), 1, 2).isEmpty());
        }
    }
}
