package io.assay.store.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The store must be able to enumerate persisted runs and read a single run record back. */
class RunListingTest {

    @Test
    void listsRunsNewestFirstAndReadsOneRun(@TempDir Path directory) throws Exception {
        ResultStore store = new ResultStore(directory);
        store.startRun("run-b", "adapter", "smoke", "online", Map.of("workers", 1));
        store.finishRun("run-b", "passed");
        Thread.sleep(5);
        store.startRun("run-a", "adapter", "smoke", "online", Map.of("workers", 1));
        store.finishRun("run-a", "budget_exhausted");

        List<Map<String, Object>> runs = store.listRuns();
        assertEquals(2, runs.size());
        assertEquals("run-a", runs.get(0).get("run_id"));
        assertEquals("budget_exhausted", runs.get(0).get("status"));
        assertEquals("run-b", runs.get(1).get("run_id"));

        Map<String, Object> run = store.getRun("run-b");
        assertEquals("passed", run.get("status"));
        assertEquals("adapter", run.get("adapter"));
        assertEquals("smoke", run.get("suite"));
        assertTrue(run.get("metadata_json") instanceof Map<?, ?>);
        assertEquals(
                1,
                ((Number) ((Map<?, ?>) run.get("metadata_json")).get("workers")).intValue());
        assertNull(store.getRun("missing"));
    }
}
