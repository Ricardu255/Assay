package io.assay.store.file;

import io.assay.json.Json;
import io.assay.metrics.MetricCache;
import io.assay.model.MetricResult;
import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One JSON file per metric result, named by its SHA-256 key.
 *
 * <p>The cache is content-addressed, so concurrent writers of the same key write identical content.
 * A corrupt or half-written entry is treated as a miss rather than failing the run.
 */
public final class FileMetricCache implements MetricCache {

    private static final String KEY_PATTERN = "[0-9a-f]{64}";

    private final Path directory;

    public FileMetricCache(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Directory that holds the cache entries. */
    public Path directory() {
        return directory;
    }

    @Override
    public Optional<MetricResult> lookup(String key) {
        Path file = file(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MetricResult.fromJson(Json.parseObject(AtomicFiles.readText(file))));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    @Override
    public void store(String key, MetricResult result) {
        Objects.requireNonNull(result, "result");
        AtomicFiles.writeText(file(key), Json.write(result.toJson()) + "\n");
    }

    private Path file(String key) {
        if (key == null || !key.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("metric cache key must be a SHA-256 hex digest");
        }
        return directory.resolve(key + ".json");
    }
}
