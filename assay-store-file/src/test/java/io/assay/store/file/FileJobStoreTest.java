package io.assay.store.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileJobStoreTest {

    @Test
    void claimAndHeartbeatAreScopedToTheLeaseOwner(@TempDir Path root) {
        try (FileJobStore store = new FileJobStore(root)) {
            Job submitted = store.submit(Map.of("suite", "smoke"), 2);
            Optional<Job> claimed = store.claim("worker-a", Duration.ofSeconds(30));

            assertTrue(claimed.isPresent());
            assertEquals(submitted.id(), claimed.get().id());
            assertEquals(JobStatus.RUNNING, claimed.get().status());
            assertTrue(store.heartbeat(submitted.id(), "worker-a", Duration.ofSeconds(30)));
            assertFalse(store.heartbeat(submitted.id(), "worker-b", Duration.ofSeconds(30)));

            store.succeed(submitted.id(), "worker-a");
            assertEquals(JobStatus.SUCCEEDED, store.find(submitted.id()).orElseThrow().status());
        }
    }

    @Test
    void expiredLeaseCanBeClaimedAgain(@TempDir Path root) throws Exception {
        try (FileJobStore store = new FileJobStore(root)) {
            Job job = store.submit(Map.of("suite", "smoke"), 2);
            assertTrue(store.claim("worker-a", Duration.ofMillis(30)).isPresent());
            Thread.sleep(80);

            Optional<Job> reclaimed = store.claim("worker-b", Duration.ofSeconds(30));
            assertTrue(reclaimed.isPresent());
            assertEquals(2, reclaimed.get().attempts());
            assertEquals("worker-b", reclaimed.get().leaseOwner());
            assertEquals(JobStatus.RUNNING, store.find(job.id()).orElseThrow().status());
        }
    }

    @Test
    void expiredLeaseAtMaxAttemptsFails(@TempDir Path root) throws Exception {
        try (FileJobStore store = new FileJobStore(root)) {
            Job job = store.submit(Map.of("suite", "smoke"), 1);
            assertTrue(store.claim("worker-a", Duration.ofMillis(30)).isPresent());
            Thread.sleep(80);

            assertFalse(store.claim("worker-b", Duration.ofSeconds(30)).isPresent());
            Job failed = store.find(job.id()).orElseThrow();
            assertEquals(JobStatus.FAILED, failed.status());
            assertTrue(failed.error().contains("lease expired"));
        }
    }

    @Test
    void cancelQueuedIsTerminalAndRunningIsCooperative(@TempDir Path root) {
        try (FileJobStore store = new FileJobStore(root)) {
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
    void retryableFailureRequeuesUntilMaxAttempts(@TempDir Path root) {
        try (FileJobStore store = new FileJobStore(root)) {
            Job job = store.submit(Map.of("suite", "smoke"), 2);
            assertTrue(store.claim("worker-a", Duration.ofSeconds(30)).isPresent());
            store.fail(job.id(), "worker-a", "adapter timeout");
            assertEquals(JobStatus.QUEUED, store.find(job.id()).orElseThrow().status());

            assertTrue(store.claim("worker-b", Duration.ofSeconds(30)).isPresent());
            store.fail(job.id(), "worker-b", "adapter timeout");
            assertEquals(JobStatus.FAILED, store.find(job.id()).orElseThrow().status());
        }
    }
}
