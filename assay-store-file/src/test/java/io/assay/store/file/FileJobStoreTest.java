package io.assay.store.file;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

class FileJobStoreTest {

    @Test
    void findsJobByRunIdWithAnIndexedLookup(@TempDir Path root) {
        try (FileJobStore store = new FileJobStore(root)) {
            Job job = store.submit(Map.of("run_id", "run-123", "suite", "smoke"), 1);
            store.submit(Map.of("run_id", "run-999", "suite", "smoke"), 1);

            assertEquals(job.id(), store.findByRunId("run-123").orElseThrow().id());
            assertTrue(store.findByRunId("missing").isEmpty());
        }
    }

    @Test
    void rebuildsRunIndexAfterIndexFilesAreRemoved(@TempDir Path root) throws Exception {
        try (FileJobStore store = new FileJobStore(root)) {
            store.submit(Map.of("run_id", "run-123", "suite", "smoke"), 1);
            Path index = root.resolve("run-index");
            try (Stream<Path> pointers = Files.list(index)) {
                for (Path pointer : pointers.toList()) {
                    Files.deleteIfExists(pointer);
                }
            }

            assertEquals(1, store.rebuildRunIdIndex());
            assertTrue(store.findByRunId("run-123").isPresent());
        }
    }

    @Test
    void rebuildIndexPrefersTheNewestJobForDuplicateRunIds(@TempDir Path root) throws Exception {
        try (FileJobStore store = new FileJobStore(root)) {
            store.submit("key-1", Map.of("run_id", "dup-run", "suite", "smoke"), 1);
            Thread.sleep(5);
            Job second = store.submit("key-2", Map.of("run_id", "dup-run", "suite", "smoke"), 1);
            Path index = root.resolve("run-index");
            try (Stream<Path> pointers = Files.list(index)) {
                for (Path pointer : pointers.toList()) {
                    Files.deleteIfExists(pointer);
                }
            }

            store.rebuildRunIdIndex();

            assertEquals(second.id(), store.findByRunId("dup-run").orElseThrow().id());
        }
    }

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
        try (FileJobStore store = new FileJobStore(root, RetryPolicy.none())) {
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
    void retryableFailureSchedulesABackoffBeforeTheNextClaim(@TempDir Path root) {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
        try (FileJobStore store = new FileJobStore(root, policy)) {
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
    void idempotentSubmitRejectsADifferentPayload(@TempDir Path root) {
        try (JobStore store = new FileJobStore(root)) {
            Job first = store.submit("checkout-42", Map.of("suite", "smoke", "cases", "a"), 2);

            IdempotencyConflictException error = assertThrows(
                    IdempotencyConflictException.class,
                    () -> store.submit("checkout-42", Map.of("suite", "smoke", "cases", "b"), 2));

            assertEquals("checkout-42", error.idempotencyKey());
            assertEquals(first.id(), error.existingJobId());
            assertEquals(1, store.list(10).size());
        }
    }

    @Test
    void idempotentSubmitReturnsTheExistingJob(@TempDir Path root) {
        try (JobStore store = new FileJobStore(root)) {
            Job first = store.submit("checkout-42", Map.of("suite", "smoke"), 2);
            Job second = store.submit("checkout-42", Map.of("suite", "smoke"), 2);

            assertEquals(first.id(), second.id());
            assertEquals(1, store.list(10).size());
            assertEquals("checkout-42", second.idempotencyKey());
        }
    }

    @Test
    void failedJobCanBeReplayedUpToTheLimit(@TempDir Path root) {
        try (JobStore store = new FileJobStore(root)) {
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
