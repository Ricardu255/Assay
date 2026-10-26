package io.assay.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import java.time.Duration;
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

    private static JdbcJobStore store() {
        return JdbcJobStore.fromUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }
}
