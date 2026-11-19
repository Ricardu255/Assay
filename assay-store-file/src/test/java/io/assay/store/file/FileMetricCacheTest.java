package io.assay.store.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.metrics.MetricCacheKey;
import io.assay.model.MetricResult;
import io.assay.support.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class FileMetricCacheTest {

    private static final String KEY = MetricCacheKey.sha256("case-a");

    @Test
    void storesAndReadsBackAResult(@TempDir Path root) {
        FileMetricCache cache = new FileMetricCache(root);
        MetricResult original = MetricResult.pass("tool_permission", 1.0, 1.0, "all tool calls authorized")
                .withDetails(Map.of("tool_calls", 2), List.of(Map.of("tool", "lookup")));

        cache.store(KEY, original);
        MetricResult restored = cache.lookup(KEY).orElseThrow();

        assertEquals("tool_permission", restored.name());
        assertEquals(original.status(), restored.status());
        assertEquals(1.0, restored.score());
        assertEquals(2, ((Number) restored.breakdown().get("tool_calls")).intValue());
    }

    @Test
    void treatsACorruptEntryAsAMiss(@TempDir Path root) {
        FileMetricCache cache = new FileMetricCache(root);
        AtomicFiles.writeText(root.resolve(KEY + ".json"), "{not json");

        assertTrue(cache.lookup(KEY).isEmpty());
    }

    @Test
    void missesWhenTheDirectoryHasNoEntry(@TempDir Path root) {
        assertFalse(new FileMetricCache(root.resolve("absent")).lookup(KEY).isPresent());
    }

    @Test
    void rejectsKeysThatAreNotSha256Digests(@TempDir Path root) {
        FileMetricCache cache = new FileMetricCache(root);

        assertThrows(IllegalArgumentException.class, () -> cache.lookup("../escape"));
        assertThrows(IllegalArgumentException.class,
                () -> cache.store("short", MetricResult.pass("m", 1, 0.5, "")));
    }
}
