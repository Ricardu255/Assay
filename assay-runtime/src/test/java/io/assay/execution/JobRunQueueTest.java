package io.assay.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.runner.JobStatus;
import io.assay.store.Results;
import io.assay.store.file.FileJobStore;
import io.assay.store.file.ResultStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobRunQueueTest {

    @Test
    void twoWorkerInstancesClaimAndExecuteEachJobOnce(@TempDir Path root) throws Exception {
        Path cases = root.resolve("cases.jsonl");
        Files.writeString(cases, "{\"id\":\"QUEUE-1\",\"scenario\":\"direct\",\"input\":{\"message\":\"hi\"},"
                + "\"expected\":{\"ok\":true}}\n");
        FileJobStore jobs = new FileJobStore(root);
        Supplier<Results> stores = () -> new ResultStore(root);
        Path reports = root.resolve("reports");
        JobRunQueue first = new JobRunQueue(
                jobs, stores, new SuiteRunner(), RunOptions.defaults(), reports, 1, Duration.ofSeconds(10), 25, 1);
        JobRunQueue second = new JobRunQueue(
                jobs, stores, new SuiteRunner(), RunOptions.defaults(), reports, 1, Duration.ofSeconds(10), 25, 1);
        first.start();
        second.start();
        try {
            RunSpec spec = new RunSpec(
                    "io.assay.execution.SuiteRunnerTestAdapter",
                    null,
                    cases.toString(),
                    "smoke",
                    "online",
                    "queue-run",
                    "queue-run",
                    false);
            Map<String, Object> submitted = first.submit(spec, "queue-key-1");
            String jobId = (String) submitted.get("job_id");
            waitFor(() -> jobs.find(jobId).map(job -> job.status() == JobStatus.SUCCEEDED).orElse(false));
            assertFalse(second.submit(spec, "queue-key-1").isEmpty());
            assertEquals(jobId, second.submit(spec, "queue-key-1").get("job_id"), "idempotent resubmission");

            try (Results store = stores.get()) {
                assertEquals(1, store.listRuns().size(), "one run record despite two workers");
                Map<String, Object> run = store.getRun("queue-run");
                assertEquals("passed", run.get("status"));
                assertEquals(1, store.listResults("queue-run").size());
            }
        } finally {
            first.close();
            second.close();
            jobs.close();
        }
    }

    @Test
    void refusesToQueueARunWithoutAnExplicitId(@TempDir Path root) {
        FileJobStore jobs = new FileJobStore(root);
        try (JobRunQueue queue = new JobRunQueue(
                jobs, () -> new ResultStore(root), new SuiteRunner(), RunOptions.defaults(),
                root.resolve("reports"), 0, Duration.ofSeconds(10), 25, 1)) {
            RunSpec spec = new RunSpec(
                    "io.assay.execution.SuiteRunnerTestAdapter", null, "cases.jsonl", "smoke", "online", null, null, false);
            IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> queue.submit(spec, null));
            assertEquals("run_id is required before a run can be queued", failure.getMessage());
            assertTrue(jobs.list(10).isEmpty());
        } finally {
            jobs.close();
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for the queued run to finish");
            }
            Thread.sleep(25);
        }
    }
}
