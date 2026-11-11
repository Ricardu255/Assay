package io.assay.store.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.runner.Job;
import io.assay.runner.JobStore;
import io.assay.store.Results;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Retention deletes old terminal data while keeping the newest records and running work. */
class RetentionTest {

    @Test
    void fileResultStoreKeepsTheNewestRunsAndHonoursDryRun(@TempDir Path root) throws Exception {
        Path storeRoot = root.resolve("store");
        try (ResultStore store = new ResultStore(storeRoot)) {
            for (int index = 1; index <= 3; index++) {
                String runId = "run-" + index;
                store.startRun(runId, "adapter", "suite", "online", Map.of(), false, Map.of());
                store.finishRun(runId, "passed");
            }
            Instant cutoff = Instant.now().plusSeconds(60);
            List<String> preview = store.pruneRuns(cutoff, 1, true);
            assertEquals(2, preview.size());
            assertTrue(Files.isDirectory(storeRoot.resolve("runs").resolve(preview.get(0))));

            List<String> deleted = store.pruneRuns(cutoff, 1, false);
            assertEquals(2, deleted.size());
            long remaining;
            try (var stream = Files.list(storeRoot.resolve("runs"))) {
                remaining = stream.filter(Files::isDirectory).count();
            }
            assertEquals(1, remaining);
        }
    }

    @Test
    void fileJobStoreKeepsNewestTerminalJobsAndNeverDeletesQueuedOnes(@TempDir Path root) {
        try (JobStore store = new FileJobStore(root.resolve("jobs"))) {
            for (int index = 1; index <= 3; index++) {
                Job job = store.submit(Map.of("suite", "suite-" + index), 1);
                assertTrue(store.claim("worker", Duration.ofSeconds(30)).isPresent());
                store.succeed(job.id(), "worker");
            }
            Job queued = store.submit(Map.of("suite", "queued"), 1);

            List<String> deleted =
                    store.pruneJobs(Instant.now().plusSeconds(60), 1, false);
            assertEquals(2, deleted.size());
            assertEquals(2, store.list(10).size());
            assertFalse(store.find(queued.id()).isEmpty());
        }
    }
}
